plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.n30dyn4m1c.photosphere"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.n30dyn4m1c.photosphere"
        // 26 keeps adaptive launcher icons, ART, and the modern camera2 pipeline
        // available without legacy fallbacks. CameraX itself supports 21+.
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
        }
        release {
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

    testImplementation(libs.junit)
    // runTest / test dispatchers, for the capture coordinator's suspending API.
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}
