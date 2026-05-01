plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.bhuvan.callback"
    compileSdk = 35
    ndkVersion = libs.versions.ndk.get()

    defaultConfig {
        applicationId = "com.bhuvan.callback"
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // LiteRT and LiteRT-LM load models via AssetManager file descriptor.
    // Compressed assets cannot be opened as file descriptors, so these
    // extensions must be stored uncompressed in the APK.
    androidResources {
        noCompress += listOf(".tflite", ".litertlm", ".task", ".model")
    }

    // QNN native libraries must be extracted to disk (not loaded from zip).
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation(libs.arcore)
    implementation(libs.litert.core)
    implementation(libs.litertlm.android)
    implementation(libs.djl.api)
    implementation(libs.djl.sentencepiece)
    implementation(libs.djl.android.core)
    implementation(libs.djl.android.tokenizer.native)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
