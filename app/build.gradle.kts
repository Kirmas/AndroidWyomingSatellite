plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Ensure Kotlin uses Java 21 toolchain for compilation
kotlin {
    jvmToolchain(21)
}


android {
    namespace = "com.wyoming.satellite"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.wyoming.satellite"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    
    applicationVariants.all {
        outputs.all {
            val output = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            output.outputFileName = "wyoming-satellite-${versionName}-${buildType.name}.apk"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlinOptions {
        jvmTarget = "21"
    }
}

dependencies {
    implementation("com.github.gkonovalov.android-vad:webrtc:2.0.10")
    implementation("com.github.gkonovalov.android-vad:silero:2.0.10")
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.2.0")
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    
    // ONNX Runtime for wake word detection
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.16.3")
    
    // Coroutines for async operations
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    
    // JSON parsing
    implementation("org.json:json:20231013")
}
