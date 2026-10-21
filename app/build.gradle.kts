plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.n30dyn4m1c.photosphere"
    compileSdk = 37

    defaultConfig {
        applicationId = "theclumsypirate.com.photosphere.github"
        // 26 keeps adaptive launcher icons, ART, and the modern camera2 pipeline
        // available without legacy fallbacks. CameraX itself supports 21+.
        minSdk = 26
        targetSdk = 37
        versionCode = 3
        versionName = "0.0.2-beta"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Release signing comes from CI secrets, never from the repo:
    // -PkeystorePath/-PkeystorePassword/-PkeyAlias/-PkeyPassword. Without them
    // the release stays unsigned (installable only for inspection).
    val keystorePath = project.findProperty("keystorePath")?.toString().orEmpty()
    signingConfigs {
        create("release") {
            if (keystorePath.isNotEmpty()) {
                storeFile = file(keystorePath)
                storePassword = project.findProperty("keystorePassword")?.toString()
                keyAlias = project.findProperty("keyAlias")?.toString()
                keyPassword = project.findProperty("keyPassword")?.toString()
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
        }
        release {
            if (keystorePath.isNotEmpty()) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    // The OpenCV AAR ships native libraries for every ABI (~120 MB unpacked).
    // Splitting keeps each installable APK to a single ABI. Disable this block if
    // you would rather build one universal APK, and prefer an App Bundle for
    // Play distribution — Play performs the same split automatically.
    splits {
        abi {
            isEnable = true
            reset()
            // `-PonlyAbi=arm64-v8a` narrows the split (used by CI, which only
            // needs the ABI modern phones run). By default all ABIs are built.
            val onlyAbi = project.findProperty("onlyAbi")?.toString()
                ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
            include(*(onlyAbi?.toTypedArray() ?: arrayOf("arm64-v8a", "armeabi-v7a", "x86_64")))
            isUniversalApk = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        // BuildConfig.DEBUG gates the orientation debug screen, so R8 can strip
        // it from release builds.
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            // OpenCV's .so files are loaded from the APK directly on API 23+.
            useLegacyPackaging = false
        }
    }
}

/**
 * The person and object detection, kept as source but kept out of the build.
 *
 * The MediaPipe artifact is gone from the dependencies, so neither the
 * library nor its native libraries reach the APK. The code that used it
 * lives in app/orbit-disabled/, a directory no source set compiles, so the
 * Kotlin compiler never sees it.
 *
 * Do not delete this block. AGP 9 does not wire the Kotlin compile
 * classpath on its own: without this block the compile task gets an empty
 * classpath and every library in the app reads as an unresolved reference.
 * The two excludes below are not what keeps the analyzer out. AGP takes the
 * Kotlin files from its own source sets and ignores them, so the files were
 * still compiled and the build failed on the missing mediapipe imports. The
 * move to app/orbit-disabled/ is what keeps them out; the excludes stay
 * only so the two rules cannot drift apart.
 *
 * To bring it back: move app/orbit-disabled/*.kt back into
 * app/src/main/java/com/n30dyn4m1c/photosphere/orbit/, delete the two
 * excludes below, put the dependency back
 * (com.google.mediapipe:tasks-vision:0.10.14), and restore the orbit entry
 * in MainActivity.
 */
kotlin {
    sourceSets {
        getByName("main") {
            kotlin.exclude("**/orbit/OrbitMediaPipeAnalyzer.kt")
            kotlin.exclude("**/orbit/OrbitCaptureScreen.kt")
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    // Jetpack Compose + Material 3
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    // CameraX
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    // EXIF read/write for captured frames
    implementation(libs.androidx.exifinterface)

    // OpenCV (stitching / feature matching for the sphere pipeline).
    // Option A (default): the official Maven Central artifact.
    // Option B: swap this line for `implementation(project(":opencv"))` after
    // wiring the local SDK module — see README.md.
    implementation(libs.opencv)

    // MediaPipe pose for the debug orbit subject tracker. Kept out of the
    // build: the person and object detection is not shipped, and the
    // library and its native libraries are several megabytes of APK for a
    // feature that is not in it. See the kotlin source set excludes above.

    testImplementation(libs.junit)
    // runTest / test dispatchers, for the capture coordinator's suspending API.
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}
