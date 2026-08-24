plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.koreadervoicepager"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.koreadervoicepager.cld"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
        viewBinding = true
    }

    // Vosk ships prebuilt native .so libraries per ABI inside its AAR; nothing extra needed
    // here, but we cap the ABIs we ship to keep the APK small (matches the phones people
    // actually use an e-reader companion app on).
    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-service:2.8.4")

    // HTTP client. Configured with a single pinned/kept-alive connection so the request that
    // fires on a recognized word never pays a TCP-handshake tax - see KOReaderClient.kt.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Offline speech recognition (same engine family as the Windows version's NuGet "Vosk"
    // package, just the Android build of the JNI bindings). No cloud, no network round trip.
    implementation("com.alphacephei:vosk-android:0.3.47@aar") {
        isTransitive = true
    }
    implementation("net.java.dev.jna:jna:5.13.0@aar")
}
