# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Keep ONNX Runtime classes
-keep class ai.onnxruntime.** { *; }

# Keep Wyoming protocol classes
-keep class com.wyoming.satellite.** { *; }

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile
