# OpenCV's Java classes are reached from native code via JNI, so they must keep
# their names. Without this the release build crashes on the first Mat access.
-keep class org.opencv.** { *; }
-keepclassmembers class org.opencv.** { *; }
-dontwarn org.opencv.**

# Any other JNI entry point reached by name.
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep file/line info so release crashes stay readable in logcat.
-keepattributes SourceFile,LineNumberTable

# CameraX ships its own consumer rules; nothing extra is required here.
