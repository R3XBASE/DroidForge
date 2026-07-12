# Keep MethodChannel bridge classes
-keep class com.droidforge.core.** { *; }

# Keep Flutter embedding
-keep class io.flutter.app.** { *; }
-keep class io.flutter.plugin.** { *; }
-keep class io.flutter.util.** { *; }
-keep class io.flutter.view.** { *; }
-keep class io.flutter.** { *; }
-keep class io.flutter.plugins.** { *; }

# Keep provider
-keep class provider.** { *; }

# Keep annotations
-keepattributes *Annotation*

# Suppress warnings
-dontwarn javax.annotation.**
-dontwarn sun.misc.Unsafe
