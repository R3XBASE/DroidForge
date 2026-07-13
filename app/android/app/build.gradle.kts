plugins {
    id("com.android.application")
    id("kotlin-android")
    // The Flutter Gradle Plugin must be applied after the Android and Kotlin Gradle plugins.
    id("dev.flutter.flutter-gradle-plugin")
}

android {
    namespace = "com.droidforge.core"
    compileSdk = flutter.compileSdkVersion
    ndkVersion = flutter.ndkVersion

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = JavaVersion.VERSION_17.toString()
    }

    defaultConfig {
        applicationId = "com.droidforge.core"
        // API 28: disables Android 10+ execve() block, bypasses W^X restrictions
        minSdk = 28
        targetSdk = 28
        versionCode = flutter.versionCode
        versionName = flutter.versionName

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        release {
            // Use debug signing for now — switch to release keystore for production
            signingConfig = signingConfigs.getByName("debug")
        }
        
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

flutter {
    source = "../.."
}

// Copy APK to Flutter's expected output path after assemble tasks.
// AGP 8.x outputs to android/app/build/outputs/flutter-apk/ but Flutter
// tool looks for it at build/app/outputs/flutter-apk/ (Flutter project root).
tasks.configureEach {
    if (name.startsWith("assemble")) {
        doLast {
            val srcDir = file("build/outputs/flutter-apk")
            val destDir = rootProject.file("../build/app/outputs/flutter-apk")
            if (srcDir.exists() && destDir.parentFile != null) {
                destDir.mkdirs()
                srcDir.listFiles()?.forEach { apk ->
                    val dest = File(destDir, apk.name)
                    apk.copyTo(dest, overwrite = true)
                    println("Copied ${apk.name} → ${dest.absolutePath}")
                }
            }
        }
    }
}
