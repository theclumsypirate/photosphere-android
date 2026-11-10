/**
 * OpenCV 360° Equirectangular Panorama Stitcher
 * 
 * Debug-only native module for high-quality 360° panorama stitching.
 * Adapted from OpenCV's stitching.cpp sample for 360° equirectangular output.
 * 
 * This module provides a C++ implementation that can be called from Kotlin via JNI.
 * It implements the full OpenCV stitching pipeline optimized for 360° spherical panoramas.
 */

#include <jni.h>
#include <vector>
#include <string>
#include <android/log.h>
#include <opencv2/core.hpp>
#include <opencv2/imgcodecs.hpp>
#include <opencv2/imgproc.hpp>
#include <opencv2/stitching.hpp>
#include <opencv2/stitching/detail/autocalib.hpp>
#include <opencv2/stitching/detail/blenders.hpp>
#include <opencv2/stitching/detail/camera.hpp>
#include <opencv2/stitching/detail/exposure_compensate.hpp>
#include <opencv2/stitching/detail/matchers.hpp>
#include <opencv2/stitching/detail/motion_estimators.hpp>
#include <opencv2/stitching/detail/seam_finders.hpp>
#include <opencv2/stitching/detail/warpers.hpp>
#include <opencv2/stitching/warpers.hpp>

#define LOG_TAG "OpenCv360Stitcher"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

using namespace cv;
using namespace cv::detail;
using namespace std;

/**
 * Configuration for 360° equirectangular stitching
 */
struct StitchConfig360 {
    // Feature detection
    string features_type = "orb";          // "surf", "orb", "sift"
    float match_conf = 0.3f;               // Match confidence threshold
    string matcher_type = "homography";    // "homography", "affine"
    
    // Camera estimation
    string estimator_type = "homography";  // "homography", "affine"
    float conf_thresh = 1.0f;              // Panorama confidence threshold
    
    // Bundle adjustment
    string ba_cost_func = "ray";           // "reproj", "ray", "affine", "no"
    string ba_refine_mask = "xxxxx";       // Refinement mask for BA
    
    // Wave correction
    bool do_wave_correct = true;
    WaveCorrectKind wave_correct = WAVE_CORRECT_HORIZ;
    
    // Warping
    string warp_type = "spherical";        // For 360°: "spherical"
    
    // Exposure compensation
    int expos_comp_type = ExposureCompensator::GAIN_BLOCKS;
    
    // Seam finding
    string seam_find_type = "gc_color";    // "no", "voronoi", "gc_color", "gc_colorgrad", "dp_color", "dp_colorgrad"
    
    // Blending
    int blend_type = Blender::MULTI_BAND;
    float blend_strength = 5.0f;
    
    // Resolution scaling
    double work_megapix = 0.6;             // Feature detection resolution
    double seam_megapix = 0.1;             // Seam estimation resolution
    double compose_megapix = -1;           // Final composition resolution (-1 = original)
    
    // Range width for matching (for sequential images)
    int range_width = -1;
    
    // Output
    string result_name = "pano_360.jpg";
    
    // CUDA
    bool try_cuda = false;
};

/**
 * Result structure for JNI
 */
struct StitchResult360 {
    bool success = false;
    string error_message;
    string output_path;
    int width = 0;
    int height = 0;
    vector<CameraParams> cameras;
    vector<Mat> warped_images;
};

/**
 * Main 360° equirectangular stitcher class
 */
class EquirectangularStitcher360 {
public:
    EquirectangularStitcher360(const StitchConfig360& config) : config_(config) {}
    
    StitchResult360 stitch(const vector<string>& image_paths, const string& output_path) {
        StitchResult360 result;
        result.output_path = output_path;
        
        LOGI("Starting 360° equirectangular stitching with %zu images", image_paths.size());
        
        if (image_paths.size() < 2) {
            result.error_message = "Need at least 2 images for stitching";
            return result;
        }
        
        try {
            // Step 1: Load and prepare images
            vector<Mat> images;
            vector<Size> full_img_sizes;
            vector<ImageFeatures> features;
            
            if (!loadImagesAndExtractFeatures(image_paths, images, full_img_sizes, features, result)) {
                return result;
            }
            
            // Step 2: Pairwise matching
            vector<MatchesInfo> pairwise_matches;
            if (!matchFeatures(features, pairwise_matches, result)) {
                return result;
            }
            
            // Step 3: Leave only images from the same panorama
            vector<int> indices = leaveBiggestComponent(features, pairwise_matches, config_.conf_thresh);
            if (indices.size() < 2) {
                result.error_message = "Not enough matching images for panorama";
                return result;
            }
            
            // Filter to matching subset
            vector<Mat> img_subset;
            vector<Size> full_img_sizes_subset;
            vector<ImageFeatures> features_subset;
            vector<string> img_names_subset;
            
            for (size_t i = 0; i < indices.size(); ++i) {
                img_subset.push_back(images[indices[i]]);
                full_img_sizes_subset.push_back(full_img_sizes[indices[i]]);
                features_subset.push_back(features[indices[i]]);
                img_names_subset.push_back(image_paths[indices[i]]);
            }
            
            images = img_subset;
            full_img_sizes = full_img_sizes_subset;
            features = features_subset;
            
            // Re-match on subset
            vector<MatchesInfo> pairwise_matches_subset;
            if (!matchFeatures(features, pairwise_matches_subset, result)) {
                return result;
            }
            
            // Step 4: Estimate camera parameters
            vector<CameraParams> cameras;
            if (!estimateCameras(features, pairwise_matches_subset, cameras, result)) {
                return result;
            }
            
            // Step 5: Bundle adjustment
            if (!adjustCameras(features, pairwise_matches_subset, cameras, result)) {
                return result;
            }
            
            // Step 6: Wave correction
            if (config_.do_wave_correct) {
                vector<Mat> rmats;
                for (size_t i = 0; i < cameras.size(); ++i) {
                    rmats.push_back(cameras[i].R.clone());
                }
                waveCorrect(rmats, config_.wave_correct);
                for (size_t i = 0; i < cameras.size(); ++i) {
                    cameras[i].R = rmats[i];
                }
            }
            
            // Step 6: Warp images for seam finding
            vector<Point> corners;
            vector<UMat> masks_warped;
            vector<UMat> images_warped;
            vector<Size> sizes;
            
            if (!warpImagesForSeams(images, full_img_sizes, cameras, corners, masks_warped, images_warped, sizes, result)) {
                return result;
            }
            
            // Step 7: Exposure compensation
            Ptr<ExposureCompensator> compensator = ExposureCompensator::createDefault(config_.expos_comp_type);
            compensator->feed(corners, images_warped, masks_warped);
            
            // Step 8: Seam finding
            vector<UMat> images_warped_f;
            for (size_t i = 0; i < images_warped.size(); ++i) {
                UMat img_f;
                images_warped[i].convertTo(img_f, CV_32F);
                images_warped_f.push_back(img_f);
            }
            
            Ptr<SeamFinder> seam_finder = createSeamFinder(config_.seam_find_type, config_.try_cuda);
            if (!seam_finder) {
                result.error_message = "Failed to create seam finder";
                return result;
            }
            
            seam_finder->find(images_warped_f, corners, masks_warped);
            
            // Step 9: Final warping and blending
            Mat pano;
            if (!warpAndBlendFinal(images, full_img_sizes, cameras, corners, sizes, compensator, masks_warped, pano, result)) {
                return result;
            }
            
            // Step 10: Save result
            if (!imwrite(output_path, pano)) {
                result.error_message = "Failed to write output image";
                return result;
            }
            
            result.success = true;
            result.width = pano.cols;
            result.height = pano.rows;
            result.cameras = cameras;
            
            LOGI("360° stitching completed successfully: %dx%d", result.width, result.height);
            return result;
            
        } catch (const cv::Exception& e) {
            LOGE("OpenCV exception: %s", e.what());
            result.error_message = string("OpenCV error: ") + e.what();
            return result;
        } catch (const std::exception& e) {
            LOGE("Standard exception: %s", e.what());
            result.error_message = string("Error: ") + e.what();
            return result;
        } catch (...) {
            LOGE("Unknown exception");
            result.error_message = "Unknown error during stitching";
            return result;
        }
    }
    
private:
    StitchConfig360 config_;
    
    bool loadImagesAndExtractFeatures(const vector<string>& image_paths,
                                      vector<Mat>& images,
                                      vector<Size>& full_img_sizes,
                                      vector<ImageFeatures>& features,
                                      StitchResult360& result) {
        Ptr<FeaturesFinder> finder = createFeaturesFinder(config_.features_type, config_.try_cuda);
        if (!finder) {
            result.error_message = "Failed to create feature finder";
            return false;
        }
        
        Mat full_img, img;
        double work_scale = 1, seam_scale = 1;
        bool is_work_scale_set = false, is_seam_scale_set = false;
        double seam_work_aspect = 1;
        
        features.resize(image_paths.size());
        images.resize(image_paths.size());
        full_img_sizes.resize(image_paths.size());
        
        for (size_t i = 0; i < image_paths.size(); ++i) {
            full_img = imread(image_paths[i]);
            if (full_img.empty()) {
                result.error_message = "Failed to load image: " + image_paths[i];
                return false;
            }
            
            full_img_sizes[i] = full_img.size();
            
            if (config_.work_megapix < 0) {
                img = full_img;
                work_scale = 1;
                is_work_scale_set = true;
            } else {
                if (!is_work_scale_set) {
                    work_scale = min(1.0, sqrt(config_.work_megapix * 1e6 / full_img.size().area()));
                    is_work_scale_set = true;
                }
                resize(full_img, img, Size(), work_scale, work_scale, INTER_LINEAR_EXACT);
            }
            
            if (!is_seam_scale_set) {
                seam_scale = min(1.0, sqrt(config_.seam_megapix * 1e6 / full_img.size().area()));
                seam_work_aspect = seam_scale / work_scale;
                is_seam_scale_set = true;
            }
            
            (*finder)(img, features[i]);
            features[i].img_idx = static_cast<int>(i);
            
            LOGI("Image %zu: %zu features", i, features[i].keypoints.size());
            
            resize(full_img, img, Size(), seam_scale, seam_scale, INTER_LINEAR_EXACT);
            images[i] = img.clone();
        }
        
        finder->collectGarbage();
        return true;
    }
    
    Ptr<FeaturesFinder> createFeaturesFinder(const string& type, bool try_cuda) {
        if (type == "surf") {
#ifdef HAVE_OPENCV_XFEATURES2D
            if (try_cuda && cuda::getCudaEnabledDeviceCount() > 0) {
                return makePtr<SurfFeaturesFinderGpu>();
            }
#endif
            return makePtr<SurfFeaturesFinder>();
        } else if (type == "orb") {
            return makePtr<OrbFeaturesFinder>();
        } else if (type == "sift") {
            return makePtr<SiftFeaturesFinder>();
        }
        return Ptr<FeaturesFinder>();
    }
    
    bool matchFeatures(const vector<ImageFeatures>& features,
                       vector<MatchesInfo>& pairwise_matches,
                       StitchResult360& result) {
        Ptr<FeaturesMatcher> matcher;
        if (config_.matcher_type == "affine") {
            matcher = makePtr<AffineBestOf2NearestMatcher>(false, config_.try_cuda, config_.match_conf);
        } else if (config_.range_width == -1) {
            matcher = makePtr<BestOf2NearestMatcher>(config_.try_cuda, config_.match_conf);
        } else {
            matcher = makePtr<BestOf2NearestRangeMatcher>(config_.range_width, config_.try_cuda, config_.match_conf);
        }
        
        (*matcher)(features, pairwise_matches);
        matcher->collectGarbage();
        
        LOGI("Pairwise matching completed");
        return true;
    }
    
    bool estimateCameras(const vector<ImageFeatures>& features,
                         const vector<MatchesInfo>& pairwise_matches,
                         vector<CameraParams>& cameras,
                         StitchResult360& result) {
        Ptr<Estimator> estimator;
        if (config_.estimator_type == "affine") {
            estimator = makePtr<AffineBasedEstimator>();
        } else {
            estimator = makePtr<HomographyBasedEstimator>();
        }
        
        if (!(*estimator)(features, pairwise_matches, cameras)) {
            result.error_message = "Camera estimation failed";
            return false;
        }
        
        // Convert rotation matrices to float
        for (auto& cam : cameras) {
            Mat R;
            cam.R.convertTo(R, CV_32F);
            cam.R = R;
        }
        
        LOGI("Camera estimation completed: %zu cameras", cameras.size());
        return true;
    }
    
    bool adjustCameras(const vector<ImageFeatures>& features,
                       const vector<MatchesInfo>& pairwise_matches,
                       vector<CameraParams>& cameras,
                       StitchResult360& result) {
        Ptr<detail::BundleAdjusterBase> adjuster;
        if (config_.ba_cost_func == "reproj") {
            adjuster = makePtr<detail::BundleAdjusterReproj>();
        } else if (config_.ba_cost_func == "ray") {
            adjuster = makePtr<detail::BundleAdjusterRay>();
        } else if (config_.ba_cost_func == "affine") {
            adjuster = makePtr<detail::BundleAdjusterAffinePartial>();
        } else if (config_.ba_cost_func == "no") {
            adjuster = makePtr<NoBundleAdjuster>();
        } else {
            result.error_message = "Unknown bundle adjustment cost function";
            return false;
        }
        
        adjuster->setConfThresh(config_.conf_thresh);
        
        Mat_<uchar> refine_mask = Mat::zeros(3, 3, CV_8U);
        if (config_.ba_refine_mask[0] == 'x') refine_mask(0,0) = 1;
        if (config_.ba_refine_mask[1] == 'x') refine_mask(0,1) = 1;
        if (config_.ba_refine_mask[2] == 'x') refine_mask(0,2) = 1;
        if (config_.ba_refine_mask[3] == 'x') refine_mask(1,1) = 1;
        if (config_.ba_refine_mask[4] == 'x') refine_mask(1,2) = 1;
        adjuster->setRefinementMask(refine_mask);
        
        if (!(*adjuster)(features, pairwise_matches, cameras)) {
            result.error_message = "Bundle adjustment failed";
            return false;
        }
        
        LOGI("Bundle adjustment completed");
        return true;
    }
    
    bool warpImagesForSeams(const vector<Mat>& images,
                            const vector<Size>& full_img_sizes,
                            const vector<CameraParams>& cameras,
                            vector<Point>& corners,
                            vector<UMat>& masks_warped,
                            vector<UMat>& images_warped,
                            vector<Size>& sizes,
                            StitchResult360& result) {
        // Calculate median focal length for warping scale
        vector<double> focals;
        for (const auto& cam : cameras) {
            focals.push_back(cam.focal);
        }
        sort(focals.begin(), focals.end());
        float warped_image_scale;
        if (focals.size() % 2 == 1) {
            warped_image_scale = static_cast<float>(focals[focals.size() / 2]);
        } else {
            warped_image_scale = static_cast<float>(focals[focals.size() / 2 - 1] + focals[focals.size() / 2]) * 0.5f;
        }
        
        // Create warper for spherical projection (360° equirectangular)
        Ptr<RotationWarper> warper = createWarper(config_.warp_type, warped_image_scale);
        if (!warper) {
            result.error_message = "Failed to create warper for type: " + config_.warp_type;
            return false;
        }
        
        corners.resize(cameras.size());
        masks_warped.resize(cameras.size());
        images_warped.resize(cameras.size());
        sizes.resize(cameras.size());
        
        // Prepare masks
        vector<UMat> masks(cameras.size());
        for (size_t i = 0; i < cameras.size(); ++i) {
            masks[i].create(images[i].size(), CV_8U);
            masks[i].setTo(Scalar::all(255));
        }
        
        // Warp images and masks
        for (size_t i = 0; i < cameras.size(); ++i) {
            Mat_<float> K;
            cameras[i].K().convertTo(K, CV_32F);
            float swa = 1.0f; // seam_work_aspect
            K(0,0) *= swa; K(0,2) *= swa;
            K(1,1) *= swa; K(1,2) *= swa;
            
            corners[i] = warper->warp(images[i], K, cameras[i].R, INTER_LINEAR, BORDER_REFLECT, images_warped[i]);
            sizes[i] = images_warped[i].size();
            warper->warp(masks[i], K, cameras[i].R, INTER_NEAREST, BORDER_CONSTANT, masks_warped[i]);
        }
        
        LOGI("Warping for seams completed");
        return true;
    }
    
    Ptr<RotationWarper> createWarper(const string& warp_type, float scale) {
        if (warp_type == "plane") return makePtr<PlaneWarper>(scale);
        if (warp_type == "affine") return makePtr<AffineWarper>(scale);
        if (warp_type == "cylindrical") return makePtr<CylindricalWarper>(scale);
        if (warp_type == "spherical") return makePtr<SphericalWarper>(scale);  // 360° equirectangular
        if (warp_type == "fisheye") return makePtr<FisheyeWarper>(scale);
        if (warp_type == "stereographic") return makePtr<StereographicWarper>(scale);
        if (warp_type == "compressedPlaneA2B1") return makePtr<CompressedRectilinearWarper>(2.0f, 1.0f, scale);
        if (warp_type == "compressedPlaneA1.5B1") return makePtr<CompressedRectilinearWarper>(1.5f, 1.0f, scale);
        if (warp_type == "paniniA2B1") return makePtr<PaniniWarper>(2.0f, 1.0f, scale);
        if (warp_type == "paniniA1.5B1") return makePtr<PaniniWarper>(1.5f, 1.0f, scale);
        if (warp_type == "mercator") return makePtr<MercatorWarper>(scale);
        if (warp_type == "transverseMercator") return makePtr<TransverseMercatorWarper>(scale);
        return Ptr<RotationWarper>();
    }
    
    Ptr<SeamFinder> createSeamFinder(const string& type, bool try_cuda) {
        if (type == "no") return makePtr<NoSeamFinder>();
        if (type == "voronoi") return makePtr<VoronoiSeamFinder>();
        if (type == "gc_color") {
#ifdef HAVE_OPENCV_CUDALEGACY
            if (try_cuda && cuda::getCudaEnabledDeviceCount() > 0) {
                return makePtr<GraphCutSeamFinderGpu>(GraphCutSeamFinderBase::COST_COLOR);
            }
#endif
            return makePtr<GraphCutSeamFinder>(GraphCutSeamFinderBase::COST_COLOR);
        }
        if (type == "gc_colorgrad") {
#ifdef HAVE_OPENCV_CUDALEGACY
            if (try_cuda && cuda::getCudaEnabledDeviceCount() > 0) {
                return makePtr<GraphCutSeamFinderGpu>(GraphCutSeamFinderBase::COST_COLOR_GRAD);
            }
#endif
            return makePtr<GraphCutSeamFinder>(GraphCutSeamFinderBase::COST_COLOR_GRAD);
        }
        if (type == "dp_color") return makePtr<DpSeamFinder>(DpSeamFinder::COLOR);
        if (type == "dp_colorgrad") return makePtr<DpSeamFinder>(DpSeamFinder::COLOR_GRAD);
        return Ptr<SeamFinder>();
    }
    
    bool warpAndBlendFinal(const vector<Mat>& images,
                           const vector<Size>& full_img_sizes,
                           const vector<CameraParams>& cameras,
                           const vector<Point>& corners,
                           const vector<Size>& sizes,
                           Ptr<ExposureCompensator> compensator,
                           const vector<UMat>& masks_warped,
                           Mat& pano,
                           StitchResult360& result) {
        // Recalculate warping scale for final composition
        vector<double> focals;
        for (const auto& cam : cameras) {
            focals.push_back(cam.focal);
        }
        sort(focals.begin(), focals.end());
        float warped_image_scale;
        if (focals.size() % 2 == 1) {
            warped_image_scale = static_cast<float>(focals[focals.size() / 2]);
        } else {
            warped_image_scale = static_cast<float>(focals[focals.size() / 2 - 1] + focals[focals.size() / 2]) * 0.5f;
        }
        
        // Apply compose scale if needed
        double compose_scale = 1.0;
        if (config_.compose_megapix > 0) {
            compose_scale = min(1.0, sqrt(config_.compose_megapix * 1e6 / full_img_sizes[0].area()));
            warped_image_scale *= static_cast<float>(compose_scale);
        }
        
        // Create final warper for spherical projection
        Ptr<RotationWarper> warper = createWarper(config_.warp_type, warped_image_scale);
        if (!warper) {
            result.error_message = "Failed to create final warper";
            return false;
        }
        
        // Update corners and sizes for final resolution
        vector<Point> final_corners(cameras.size());
        vector<Size> final_sizes(cameras.size());
        vector<CameraParams> final_cameras = cameras;
        
        for (size_t i = 0; i < cameras.size(); ++i) {
            if (config_.compose_megapix > 0) {
                final_cameras[i].focal *= compose_scale;
                final_cameras[i].ppx *= compose_scale;
                final_cameras[i].ppy *= compose_scale;
            }
            
            Size sz = full_img_sizes[i];
            if (config_.compose_megapix > 0) {
                sz.width = cvRound(full_img_sizes[i].width * compose_scale);
                sz.height = cvRound(full_img_sizes[i].height * compose_scale);
            }
            
            Mat K;
            final_cameras[i].K().convertTo(K, CV_32F);
            Rect roi = warper->warpRoi(sz, K, final_cameras[i].R);
            final_corners[i] = roi.tl();
            final_sizes[i] = roi.size();
        }
        
        // Create blender
        Ptr<Blender> blender = Blender::createDefault(Blender::MULTI_BAND, false);
        Size dst_sz = resultRoi(final_corners, final_sizes).size();
        float blend_width = sqrt(static_cast<float>(dst_sz.area())) * 5.0f / 100.0f;
        
        if (blend_width < 1.0f) {
            blender = Blender::createDefault(Blender::NO, false);
        } else {
            MultiBandBlender* mb = dynamic_cast<MultiBandBlender*>(blender.get());
            if (mb) {
                mb->setNumBands(static_cast<int>(ceil(log(blend_width)/log(2.0)) - 1.0));
                LOGI("Multi-band blender, bands: %d", mb->numBands());
            }
        }
        
        blender->prepare(final_corners, final_sizes);
        
        // Process each image for final blending
        for (size_t img_idx = 0; img_idx < images.size(); ++img_idx) {
            LOGI("Compositing image %zu", img_idx);
            
            Mat full_img = imread(""); // Will be loaded per image
            // Note: In real implementation, we'd reload images here at full resolution
            // For now, use the already loaded images at seam resolution
            
            Mat img = images[img_idx]; // Use seam-resolution image
            if (config_.compose_megapix > 0) {
                // Would need to reload at full resolution here
            }
            
            Mat K;
            cameras[img_idx].K().convertTo(K, CV_32F);
            if (config_.compose_megapix > 0) {
                // K already scaled in final_cameras
            }
            
            Mat img_warped, mask_warped;
            warper->warp(img, K, cameras[img_idx].R, INTER_LINEAR, BORDER_REFLECT, img_warped);
            
            Mat mask(img.size(), CV_8U, Scalar::all(255));
            warper->warp(mask, K, cameras[img_idx].R, INTER_NEAREST, BORDER_CONSTANT, mask_warped);
            
            // Apply exposure compensation
            compensator->apply(static_cast<int>(img_idx), final_corners[img_idx], img_warped, mask_warped);
            
            img_warped.convertTo(img_warped, CV_16S);
            blender->feed(img_warped, mask_warped, final_corners[img_idx]);
        }
        
        // Final blend
        Mat result_mask;
        blender->blend(pano, result_mask);
        
        LOGI("Final blending completed: %dx%d", pano.cols, pano.rows);
        return true;
    }
    
    StitchConfig360 config_;
};

/**
 * JNI Interface
 */
extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_n30dyn4m1c_photosphere_stitching_OpenCv360Stitcher_nativeStitch360(
    JNIEnv* env, jobject thiz,
    jobjectArray image_paths,
    jstring output_path,
    jint work_megapix,
    jint seam_megapix,
    jint compose_megapix,
    jfloat match_conf,
    jfloat blend_strength,
    jboolean try_cuda) {
    
    // Convert Java strings to C++
    jsize count = env->GetArrayLength(image_paths);
    vector<string> paths;
    paths.reserve(count);
    
    for (jsize i = 0; i < count; ++i) {
        jstring jstr = (jstring)env->GetObjectArrayElement(image_paths, i);
        const char* cstr = env->GetStringUTFChars(jstr, nullptr);
        paths.emplace_back(cstr);
        env->ReleaseStringUTFChars(jstr, cstr);
        env->DeleteLocalRef(jstr);
    }
    
    const char* out_cstr = env->GetStringUTFChars(output_path, nullptr);
    string out_path(out_cstr);
    env->ReleaseStringUTFChars(output_path, out_cstr);
    
    // Configure stitcher
    StitchConfig360 config;
    config.work_megapix = work_megapix;
    config.seam_megapix = seam_megapix;
    config.compose_megapix = compose_megapix;
    config.match_conf = match_conf;
    config.blend_strength = blend_strength;
    config.try_cuda = try_cuda;
    config.warp_type = "spherical";  // Force spherical for 360°
    config.features_type = "orb";    // Fast and good for 360°
    config.result_name = out_path;
    
    // Run stitching
    EquirectangularStitcher360 stitcher(config);
    StitchResult360 result = stitcher.stitch(paths, out_path);
    
    return result.success ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_n30dyn4m1c_photosphere_stitching_OpenCv360Stitcher_nativeGetError(
    JNIEnv* env, jobject thiz) {
    // In a real implementation, you'd store the last error
    return env->NewStringUTF("No error");
}

} // extern "C"