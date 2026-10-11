// app/build.gradle.kts
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.example.droidnode"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.droidnode"
        minSdk = 26 // Android 8.0 Oreo (needed for modern process & service APIs)
        targetSdk = 34 // Android 14

        versionCode = 1
        versionName = "1.0.0"

        // Ensure we only package target architectures to save APK size
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    packaging {
        jniLibs {
            // CRITICAL FOR ANDROID 10+ W^X WORKAROUND:
            // Setting useLegacyPackaging = true forces Gradle to extract .so files into
            // context.applicationInfo.nativeLibraryDir with SELinux execution permissions!
            useLegacyPackaging = true
        }
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)

    // Coroutines for non-blocking stream pumping & background process execution
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Apache Commons Compress: CRITICAL for reading tar/tar.xz archives and preserving symlinks!
    // Standard java.util.zip.ZipInputStream or TarInputStream destroys Linux symbolic links.
    implementation("org.apache.commons:commons-compress:1.26.1")
    implementation("org.tukaani:xz:1.9") // For .tar.xz decompression support

    // For in-app local health testing
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
