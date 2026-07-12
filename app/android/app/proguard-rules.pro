# DroidForge ProGuard Rules

# Keep Flutter Play Store split compat classes
-dontwarn com.google.android.play.core.splitcompat.SplitCompatApplication
-dontwarn com.google.android.play.core.splitinstall.**
-dontwarn com.google.android.play.core.tasks.**

# Keep Flutter embedding
-keep class io.flutter.app.** { *; }
-keep class io.flutter.plugin.**  { *; }
-keep class io.flutter.util.**  { *; }
-keep class io.flutter.view.**  { *; }
-keep class io.flutter.**  { *; }
-keep class io.flutter.plugins.**  { *; }

# Keep MethodChannel handlers
-keep class com.droidforge.core.** { *; }

# Keep JNI
-keepclasseswithmembernames class * {
    native <methods>;
}
