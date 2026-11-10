/**
 * OpenCV 360° Equirectangular Panorama Stitcher — 100% C++ Native Pipeline
 * 
 * Debug-only native module implementing the EXACT pipeline from sphere-100-percent-cpp-kotlin.md:
 * - Uses KNOWN poses from gyroscope (sidecar poses.json) — NO feature matching for pose estimation
 * - Direct equirectangular 2:1 rendering via cv::remap with known poses
 * - Gain compensation (exposure equalization) on equirect grid
 * - Feather blending via distance transform on valid masks
 * - Optional SIFT pose refinement on overlapping edges
 * - Coverage validation per 10° latitude band
 * 
 * Coordinate convention (from sphere-100-percent-cpp-kotlin.md §1):
 *   World: X right, Y down, Z forward
 *   yaw   : rotation around Y, positive = right
 *   pitch : rotation around X, positive = camera tilted UP
 *   roll  : rotation around Z, positive = clockwise from behind
 *   R = Ry(yaw) * Rx(pitch) * Rz(roll)  — transforms camera -> world
 *   Optical axis = (0,0,1) in camera coords
 * 
 * Equirectangular projection (2:1, H = W/2):
 *   lon = atan2(x, z)          ∈ [-π, π]
 *   el  = asin(-y)             ∈ [-π/2, π/2]  (Y down, so -y = up)
 *   u   = (lon/(2π) + 0.5) * W
 *   v   = (0.5 - el/π) * H
 * 
 * Inverse (for rendering):
 *   lon = (u/W - 0.5) * 2π
 *   el  = (0.5 - v/H) * π
 *   d   = (cos(el)*sin(lon), -sin(el), cos(el)*cos(lon))
 * 
 * DEBUG ONLY — only loaded when C++ OpenCV toggle is enabled in Settings
 */

#include <jni.h>
#include <vector>
#include <string>
#include <fstream>
#include <sstream>
#include <cmath>
#include <algorithm>
#include <android/log.h>
#include <opencv2/core.hpp>
#include <opencv2/imgcodecs.hpp>
#include <opencv2/imgproc.hpp>
#include <opencv2/features2d.hpp>
#include <opencv2/calib3d.hpp>

#define LOG_TAG "OpenCv360Stitcher"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

using namespace cv;
using namespace std;

// ============================================================================
// Coordinate math — EXACT from sphere-100-percent-cpp-kotlin.md §1
// ============================================================================

// World: X right, Y down, Z forward
// yaw: around Y, positive = right
// pitch: around X, positive = UP (camera tilted up)
// roll: around Z, positive = clockwise from behind
// R = Ry(yaw) * Rx(pitch) * Rz(roll)  -- camera -> world

inline Matx33d Ry(double yawDeg) {
    double y = yawDeg * CV_PI / 180.0;
    double c = cos(y), s = sin(y);
    return Matx33d(c, 0, s,  0, 1, 0,  -s, 0, c);
}

inline Matx33d Rx(double pitchDeg) {
    double p = pitchDeg * CV_PI / 180.0;
    double c = cos(p), s = sin(p);
    return Matx33d(1, 0, 0,  0, c, -s,  0, s, c);
}

inline Matx33d Rz(double rollDeg) {
    double r = rollDeg * CV_PI / 180.0;
    double c = cos(r), s = sin(r);
    return Matx33d(c, -s, 0,  s, c, 0,  0, 0, 1);
}

inline Matx33d R_from_ypr(double yawDeg, double pitchDeg, double rollDeg) {
    // R = Ry(yaw) * Rx(pitch) * Rz(roll)  -- camera -> world
    return Ry(yawDeg) * Rx(pitchDeg) * Rz(rollDeg);
}

inline Matx33d K_from_hfov(int w, int h, double hfovDeg) {
    double f = (w / 2.0) / tan(hfovDeg * CV_PI / 360.0);
    return Matx33d(f, 0, w/2.0 - 0.5,  0, f, h/2.0 - 0.5,  0, 0, 1);
}

// ============================================================================
// Equirectangular direction grids (precomputed once per output resolution)
// ============================================================================

static void buildEquirectGrids(int W, int H, Mat& dx, Mat& dy, Mat& dz) {
    dx.create(H, W, CV_32F);
    dy.create(H, W, CV_32F);
    dz.create(H, W, CV_32F);
    
    for (int v = 0; v < H; ++v) {
        double el = (0.5 - (v + 0.5) / H) * CV_PI;  // elevation
        double ce = cos(el), se = sin(el);
        float* dxRow = dx.ptr<float>(v);
        float* dyRow = dy.ptr<float>(v);
        float* dzRow = dz.ptr<float>(v);
        for (int u = 0; u < W; ++u) {
            double lon = ((u + 0.5) / W - 0.5) * 2.0 * CV_PI;
            dxRow[u] = float(ce * sin(lon));
            dyRow[u] = float(-se);  // Y down convention
            dzRow[u] = float(ce * cos(lon));
        }
    }
}

// Project world direction to a specific camera's pixel coordinates
// Returns valid mask (1 = visible in this camera)
static void projectToCamera(const Matx33d& R_cam, const Matx33d& K,
                           const Mat& dx, const Mat& dy, const Mat& dz,
                           Mat& mapx, Mat& mapy, Mat& valid) {
    int H = dx.rows, W = dx.cols;
    mapx.create(H, W, CV_32F);
    mapy.create(H, W, CV_32F);
    valid.create(H, W, CV_8U);
    
    Matx33d Rt = R_cam.t();  // world -> camera
    
    for (int v = 0; v < H; ++v) {
        float* mx = mapx.ptr<float>(v);
        float* my = mapy.ptr<float>(v);
        uchar* vl = valid.ptr<uchar>(v);
        const float* dxRow = dx.ptr<float>(v);
        const float* dyRow = dy.ptr<float>(v);
        const float* dzRow = dz.ptr<float>(v);
        
        for (int u = 0; u < W; ++u) {
            Vec3d d(dxRow[u], dyRow[u], dzRow[u]);
            Vec3d c = Rt * d;  // direction in camera coords
            
            if (c[2] <= 1e-6) {  // behind camera
                vl[u] = 0;
                mx[u] = my[u] = 0;
            } else {
                double px = K(0,0) * c[0] / c[2] + K(0,2);
                double py = K(1,1) * c[1] / c[2] + K(1,2);
                mx[u] = float(px);
                my[u] = float(py);
                vl[u] = 1;
            }
        }
    }
}

// ============================================================================
// Shot data structure (from sidecar poses.json)
// ============================================================================

struct Shot {
    string file;              // absolute path
    double yawDeg = 0, pitchDeg = 0, rollDeg = 0;
    double gain = 1.0;        // exposure gain (set by equalizeGains)
    Matx33d R;                // camera -> world rotation
    float hfovDeg = 65.0f;    // horizontal FOV
};

// Load poses from sidecar JSON written by Kotlin
static bool loadPoses(const string& jsonPath, vector<Shot>& shots, int& outW, int& outH, double& hfovDeg) {
    ifstream f(jsonPath);
    if (!f.is_open()) return false;
    
    string json((istreambuf_iterator<char>(f)), istreambuf_iterator<char>());
    
    // Simple JSON parsing (could use nlohmann/json if available)
    // Expected format from Kotlin:
    // {"hfov_deg":65.0,"plan":"A24","shots":[{"file":"...","index":0,"yaw_deg":0,"pitch_deg":0,"roll_deg":0,"target_yaw_deg":0,"target_pitch_deg":0,"q_wxyz":[1,0,0,0]},...]}
    
    // Quick parse using OpenCV FileStorage (write as YAML first from Kotlin, or use simple parsing)
    // For robustness, we'll do simple string parsing here
    
    // Find hfov_deg
    size_t pos = json.find("\"hfov_deg\":");
    if (pos != string::npos) {
        pos += 11;
        hfovDeg = stod(json.substr(pos));
    } else {
        hfovDeg = 65.0;
    }
    
    // Parse shots array
    size_t shotsStart = json.find("\"shots\":");
    if (shotsStart == string::npos) return false;
    
    string shotsJson = json.substr(shotsStart);
    size_t bracket = shotsJson.find('[');
    if (bracket == string::npos) return false;
    shotsJson = shotsJson.substr(bracket + 1);
    
    // Simple shot parsing
    size_t objStart = 0;
    while ((objStart = shotsJson.find('{', objStart)) != string::npos) {
        size_t objEnd = shotsJson.find('}', objStart);
        if (objEnd == string::npos) break;
        string obj = shotsJson.substr(objStart, objEnd - objStart + 1);
        
        Shot s;
        
        auto extract = [&](const string& key, double& val) {
            size_t k = obj.find("\"" + key + "\":");
            if (k != string::npos) {
                k += key.length() + 3;
                val = stod(obj.substr(k));
            }
        };
        
        auto extractStr = [&](const string& key, string& val) {
            size_t k = obj.find("\"" + key + "\":");
            if (k != string::npos) {
                k += key.length() + 3;
                size_t end = obj.find('"', k);
                if (end != string::npos) {
                    val = obj.substr(k, end - k);
                }
            }
        };
        
        extractStr("file", s.file);
        extract("yaw_deg", s.yawDeg);
        extract("pitch_deg", s.pitchDeg);
        extract("roll_deg", s.rollDeg);
        extract("target_yaw_deg", s.yawDeg);  // fallback
        extract("target_pitch_deg", s.pitchDeg);
        
        s.R = R_from_ypr(s.yawDeg, s.pitchDeg, s.rollDeg);
        shots.push_back(s);
        
        objStart = objEnd + 1;
    }
    
    return !shots.empty();
}

// ============================================================================
// Gain compensation (exposure equalization) on equirect grid
// ============================================================================

static void equalizeGains(vector<Shot>& shots, const Matx33d& K, int W = 512, int H = 256, int iters = 30) {
    LOGI("Computing gain compensation on %dx%d grid", W, H);
    
    Mat dx, dy, dz;
    buildEquirectGrids(W, H, dx, dy, dz);
    
    int n = shots.size();
    vector<Mat> masks(n), means(n);
    
    // 1. Warp each frame to equirect grid at low res, compute masks and means
    for (int i = 0; i < n; ++i) {
        Mat mapx, mapy, valid;
        projectToCamera(shots[i].R, K, dx, dy, dz, mapx, mapy, valid);
        
        Mat img = imread(shots[i].file, IMREAD_GRAYSCALE);
        if (img.empty()) {
            LOGW("Failed to read %s", shots[i].file.c_str());
            masks[i] = Mat::zeros(H, W, CV_8U);
            means[i] = Mat::zeros(H, W, CV_32F);
            continue;
        }
        resize(img, img, Size(W, H));
        Mat warped;
        remap(img, warped, mapx, mapy, INTER_LINEAR, BORDER_CONSTANT, 0);
        
        masks[i] = valid.clone();
        warped.convertTo(means[i], CV_32F);
    }
    
    // 2. Solve linear system in log space: log g_i - log g_j = log mean_j - log mean_i
    Mat A = Mat::zeros(n + 1, n, CV_64F);
    Mat b = Mat::zeros(n + 1, 1, CV_64F);
    
    for (int i = 0; i < n; ++i) {
        for (int j = i + 1; j < n; ++j) {
            Mat both = (masks[i] > 0) & (masks[j] > 0);
            double area = countNonZero(both) / double(W * H);
            if (area < 0.02) continue;  // too little overlap
            
            double mi = mean(means[i], both)[0];
            double mj = mean(means[j], both)[0];
            if (mi < 1 || mj < 1) continue;
            
            A.at<double>(i, i) += 1; A.at<double>(j, j) += 1;
            A.at<double>(i, j) -= 1; A.at<double>(j, i) -= 1;
            b.at<double>(i) += log(mj) - log(mi);
            b.at<double>(j) -= log(mj) - log(mi);
        }
    }
    
    // Fix scale: sum of log-gains = 0
    for (int i = 0; i < n; ++i) A.at<double>(n, i) = 1.0;
    
    Mat x;
    solve(A, b, x, DECOMP_SVD);
    
    for (int i = 0; i < n; ++i) shots[i].gain = exp(x.at<double>(i));
    
    // Normalize gains to mean 1
    double meanLog = 0;
    for (int i = 0; i < n; ++i) meanLog += log(shots[i].gain);
    meanLog /= n;
    for (auto& s : shots) s.gain /= exp(meanLog);
    
    LOGI("Gains computed: min=%.3f max=%.3f", 
         *min_element(shots.begin(), shots.end(), [](auto& a, auto& b){ return a.gain < b.gain; }),
         *max_element(shots.begin(), shots.end(), [](auto& a, auto& b){ return a.gain < b.gain; }));
}

// ============================================================================
// Main rendering: remap each frame to equirectangular, feather blend
// ============================================================================

struct PanoResult {
    Mat img;      // CV_32FC3 equirectangular
    Mat wsum;     // CV_32F weight sum
    double coverage = 0;
    vector<double> bandCoverage;  // per 10° latitude band
};

static PanoResult renderEquirect(const vector<Shot>& shots, const Matx33d& K,
                                 int outW = 1024, int ssaa = 2) {
    int W = outW * ssaa;
    int H = outW / 2 * ssaa;
    
    LOGI("Rendering equirectangular %dx%d (SSAA %dx)", outW, outW/2, ssaa);
    
    Mat dx, dy, dz;
    buildEquirectGrids(W, H, dx, dy, dz);
    
    PanoResult out;
    out.img = Mat::zeros(H, W, CV_32FC3);
    out.wsum = Mat::zeros(H, W, CV_32F);
    
    for (size_t i = 0; i < shots.size(); ++i) {
        const Shot& s = shots[i];
        LOGI("  Warping frame %zu/%zu: %s", i+1, shots.size(), s.file.c_str());
        
        Mat img = imread(s.file, IMREAD_COLOR);
        if (img.empty()) {
            LOGW("Failed to read %s", s.file.c_str());
            continue;
        }
        img.convertTo(img, CV_32FC3, s.gain / 255.0);
        
        Mat mapx, mapy, valid;
        projectToCamera(s.R, K, dx, dy, dz, mapx, mapy, valid);
        
        Mat warped;
        remap(img, warped, mapx, mapy, INTER_LINEAR, BORDER_CONSTANT, Scalar::all(0));
        
        // Feather weight: distance to valid edge
        Mat w;
        distanceTransform(valid, w, DIST_L2, 3);
        w.convertTo(w, CV_32F);
        double maxD = 64.0 * 2.0;  // in SSAA space
        w = min(w / maxD, 1.0f) + 1e-3f;
        w.setTo(0, valid == 0);
        
        Mat w3;
        merge(vector<Mat>{w, w, w}, w3);
        
        out.img += warped.mul(w3);
        out.wsum += w;
    }
    
    // Normalize
    Mat wsum3;
    merge(vector<Mat>{out.wsum, out.wsum, out.wsum}, wsum3);
    Mat pano = out.img.mul(1.0f / (wsum3 + 1e-6f));
    pano.setTo(Scalar::all(0), out.wsum < 1e-4f);
    
    // Downsample from SSAA
    if (ssaa > 1) {
        resize(pano, pano, Size(outW, outW/2), 0, 0, INTER_AREA);
    }
    
    out.img = pano;
    out.img.convertTo(out.img, CV_8UC3, 255.0);
    
    // Coverage stats
    Mat dsWsum;
    if (ssaa > 1) {
        resize(out.wsum, dsWsum, Size(outW, outW/2), 0, 0, INTER_AREA);
    } else {
        dsWsum = out.wsum;
    }
    
    out.coverage = countNonZero(dsWsum > 1e-4f) / double(dsWsum.total());
    
    // Per 10° latitude band
    out.bandCoverage.resize(18);
    for (int b = 0; b < 18; ++b) {
        int y0 = b * outW / 36;
        int y1 = (b + 1) * outW / 36;
        Mat band = dsWsum.rowRange(y0, y1);
        out.bandCoverage[b] = 100.0 * countNonZero(band > 1e-4f) / band.total();
        LOGI("  Band %+4d..%+4d: %.2f%%", 90 - b * 10, 80 - b * 10, out.bandCoverage[b]);
    }
    
    LOGI("Total coverage: %.4f%%", out.coverage * 100);
    return out;
}

// ============================================================================
// Optional SIFT pose refinement on overlapping edges
// ============================================================================

static double costFor(int i, const Vec3d& ypr,
                      const vector<pair<int,int>>& edges,
                      const vector<Mat>& descs,
                      const vector<vector<KeyPoint>>& kps,
                      vector<Matx33d>& R, const Matx33d& K) {
    R[i] = R_from_ypr(ypr[0], ypr[1], ypr[2]);
    double c = 0; int cnt = 0;
    Ptr<SIFT> sift = SIFT::create(2000);
    BFMatcher bf(NORM_L2);
    
    for (auto& e : edges) {
        int a = e.first, b = e.second;
        if (a != i && b != i) continue;
        int other = (a == i) ? b : a;
        
        vector<DMatch> good;
        vector<vector<DMatch>> knn;
        bf.knnMatch(descs[a], descs[b], knn, 2);
        for (auto& v : knn) if (v.size() == 2 && v[0].distance < 0.8f * v[1].distance)
            good.push_back(v[0]);
        if (good.size() < 20) continue;
        
        vector<Point2f> pa, pb;
        for (auto& g : good) {
            pa.push_back(kps[a][g.queryIdx].pt);
            pb.push_back(kps[b][g.trainIdx].pt);
        }
        
        for (size_t k = 0; k < pa.size(); ++k) {
            Vec3d da = R[a] * Vec3d((pa[k].x - K(0,2))/K(0,0), (pa[k].y - K(1,2))/K(1,1), 1.0);
            Vec3d db = R[b] * Vec3d((pb[k].x - K(0,2))/K(0,0), (pb[k].y - K(1,2))/K(1,1), 1.0);
            double cang = (da.dot(db)) / (norm(da) * norm(db));
            c += acos(clamp(cang, -1.0, 1.0)) * 180.0 / CV_PI;
            ++cnt;
        }
    }
    return cnt ? c / cnt : 1e9;
}

static void refinePoses(vector<Shot>& shots, const vector<pair<int,int>>& edges,
                        const Matx33d& K, int iters = 4, double step = 0.2) {
    LOGI("Refining poses with SIFT on %zu edges...", edges.size());
    
    int n = shots.size();
    vector<Mat> descs(n);
    vector<vector<KeyPoint>> kps(n);
    Ptr<SIFT> sift = SIFT::create(2000);
    
    for (int i = 0; i < n; ++i) {
        Mat img = imread(shots[i].file, IMREAD_GRAYSCALE);
        sift->detectAndCompute(img, noArray(), kps[i], descs[i]);
    }
    
    vector<Matx33d> R(n);
    for (int i = 0; i < n; ++i) R[i] = shots[i].R;
    
    for (int it = 0; it < iters; ++it) {
        int moved = 0;
        for (int i = 0; i < n; ++i) {
            Vec3d best(shots[i].yawDeg, shots[i].pitchDeg, shots[i].rollDeg);
            double bc = costFor(i, best, edges, descs, kps, R, K);
            for (int ax = 0; ax < 3; ++ax) {
                for (double d : {+step, -step}) {
                    Vec3d cand = best; cand[ax] += d;
                    double c = costFor(i, cand, edges, descs, kps, R, K);
                    if (c < bc) { bc = c; best = cand; ++moved; }
                }
            }
            shots[i].yawDeg = best[0]; shots[i].pitchDeg = best[1]; shots[i].rollDeg = best[2];
            R[i] = R_from_ypr(best[0], best[1], best[2]);
        }
        if (!moved) break;
        LOGI("  Iteration %d: %d poses moved", it+1, moved);
    }
    
    for (int i = 0; i < n; ++i) shots[i].R = R[i];
}

// ============================================================================
// JNI Interface
// ============================================================================

extern "C" {

struct StitchConfig {
    int outWidth = 1024;
    int ssaa = 2;
    double hfovDeg = 65.0;
    bool doGain = true;
    bool doRefine = true;
    int refineIters = 4;
    string jsonPath;
    string outputPath;
};

JNIEXPORT jobject JNICALL
Java_com_n30dyn4m1c_photosphere_stitching_OpenCv360Stitcher_nativeStitch360(
    JNIEnv* env, jobject thiz,
    jstring jsonPath,
    jstring outputPath,
    jint outWidth,
    jint ssaa,
    jfloat hfovDeg,
    jboolean doGain,
    jboolean doRefine,
    jint refineIters) {
    
    const char* jsonC = env->GetStringUTFChars(jsonPath, nullptr);
    const char* outC = env->GetStringUTFChars(outputPath, nullptr);
    
    StitchConfig cfg;
    cfg.jsonPath = jsonC;
    cfg.outputPath = outC;
    cfg.outWidth = outWidth;
    cfg.ssaa = ssaa;
    cfg.hfovDeg = hfovDeg;
    cfg.doGain = doGain;
    cfg.doRefine = doRefine;
    cfg.refineIters = refineIters;
    
    env->ReleaseStringUTFChars(jsonPath, jsonC);
    env->ReleaseStringUTFChars(outputPath, outC);
    
    LOGI("Starting 360° C++ stitcher: json=%s out=%s W=%d ssaa=%d hfov=%.1f gain=%d refine=%d iters=%d",
         cfg.jsonPath.c_str(), cfg.outputPath.c_str(), cfg.outWidth, cfg.ssaa, cfg.hfovDeg,
         cfg.doGain, cfg.doRefine, cfg.refineIters);
    
    // Prepare failure result class and constructor
    jclass resultClass = env->FindClass("com/n30dyn4m1c/photosphere/stitching/NativeStitchResult");
    if (!resultClass) {
        LOGE("Cannot find NativeStitchResult class");
        return nullptr;
    }
    jmethodID resultCtor = env->GetMethodID(resultClass, "<init>", "(ZIIDLjava/util/List;)V");
    if (!resultCtor) {
        LOGE("Cannot find NativeStitchResult constructor");
        return nullptr;
    }
    
    // Empty list for failure case
    jclass listClass = env->FindClass("java/util/ArrayList");
    jmethodID listCtor = env->GetMethodID(listClass, "<init>", "()V");
    jobject emptyList = env->NewObject(listClass, listCtor);
    
    jobject failureResult = env->NewObject(resultClass, resultCtor, JNI_FALSE, 0, 0, 0.0, emptyList);
    
    try {
        // 1. Load poses from sidecar JSON
        vector<Shot> shots;
        int imgW = 0, imgH = 0;
        double hfov = cfg.hfovDeg;
        if (!loadPoses(cfg.jsonPath, shots, imgW, imgH, hfov)) {
            LOGE("Failed to load poses from %s", cfg.jsonPath.c_str());
            return failureResult;
        }
        
        if (shots.size() < 2) {
            LOGE("Need at least 2 shots, got %zu", shots.size());
            return failureResult;
        }
        
        // Read first image to get dimensions if not in JSON
        Mat first = imread(shots[0].file, IMREAD_UNCHANGED);
        if (!first.empty()) {
            imgW = first.cols;
            imgH = first.rows;
        }
        
        if (imgW == 0 || imgH == 0) {
            LOGE("Could not determine image dimensions");
            return failureResult;
        }
        
        // Intrinsics
        Matx33d K = K_from_hfov(imgW, imgH, hfov);
        LOGI("Image: %dx%d, K: f=%.2f, hfov=%.1f", imgW, imgH, K(0,0), hfov);
        
        // 2. Gain compensation
        if (cfg.doGain) {
            equalizeGains(shots, K);
        }
        
        // 3. Optional SIFT pose refinement (uses known poses as initialization)
        if (cfg.doRefine && shots.size() >= 2) {
            // Build edges between neighboring frames in capture order + spatial neighbors
            vector<pair<int,int>> edges;
            for (size_t i = 0; i < shots.size(); ++i) {
                if (i + 1 < shots.size()) edges.emplace_back(i, i+1);
            }
            // Add ring neighbors (same pitch ring)
            for (size_t i = 0; i < shots.size(); ++i) {
                for (size_t j = i+1; j < shots.size(); ++j) {
                    if (abs(shots[i].pitchDeg - shots[j].pitchDeg) < 1.0 &&
                        abs(shots[i].yawDeg - shots[j].yawDeg) > 10 &&
                        abs(shots[i].yawDeg - shots[j].yawDeg) < 80) {
                        edges.emplace_back(i, j);
                    }
                }
            }
            refinePoses(shots, edges, K, cfg.refineIters);
        }
        
        // 4. Render equirectangular
        PanoResult result = renderEquirect(shots, K, cfg.outWidth, 2);
        
        if (result.coverage < 0.95) {
            LOGW("Low coverage: %.2f%%", result.coverage * 100);
        }
        
        // 5. Save output
        if (!imwrite(cfg.outputPath, result.img)) {
            LOGE("Failed to write output to %s", cfg.outputPath.c_str());
            return failureResult;
        }
        
        LOGI("360° C++ stitching completed: %s (%dx%d) coverage=%.2f%%",
             cfg.outputPath.c_str(), result.img.cols, result.img.rows, result.coverage * 100);
        
        // Create success result with band coverage list
        jobject bandCoverageList = env->NewObject(listClass, listCtor);
        jmethodID listAdd = env->GetMethodID(listClass, "add", "(Ljava/lang/Object;)Z");
        jclass doubleClass = env->FindClass("java/lang/Double");
        jmethodID doubleCtor = env->GetMethodID(doubleClass, "<init>", "(D)V");
        for (double val : result.bandCoverage) {
            jobject doubleObj = env->NewObject(doubleClass, doubleCtor, val);
            env->CallBooleanMethod(bandCoverageList, listAdd, doubleObj);
        }
        
        jobject successResult = env->NewObject(
            resultClass, resultCtor,
            JNI_TRUE,
            result.img.cols,
            result.img.rows,
            result.coverage,
            bandCoverageList
        );
        
        return successResult;
        
    } catch (const cv::Exception& e) {
        LOGE("OpenCV error: %s", e.what());
        return failureResult;
    } catch (const exception& e) {
        LOGE("Std error: %s", e.what());
        return failureResult;
    } catch (...) {
        LOGE("Unknown error");
        return failureResult;
    }
}

JNIEXPORT jstring JNICALL
Java_com_n30dyn4m1c_photosphere_stitching_OpenCv360Stitcher_nativeGetError(
    JNIEnv* env, jobject thiz) {
    return env->NewStringUTF("See logcat for details");
}

} // extern "C"