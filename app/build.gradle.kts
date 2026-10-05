plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.store.inventoryscanner"
    compileSdk = 34

    defaultConfig {
    applicationId = "com.store.inventoryscanner"
    minSdk = 24
    targetSdk = 34
    versionCode = 1
    versionName = "1.0"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    // 本機 JVM Unit Test 設定
    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }
}

dependencies {

    // AndroidX
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // CameraX
    val camerax_version = "1.3.4"

    implementation("androidx.camera:camera-camera2:$camerax_version")
    implementation("androidx.camera:camera-lifecycle:$camerax_version")
    implementation("androidx.camera:camera-view:$camerax_version")

    // Google ML Kit Barcode
    implementation("com.google.mlkit:barcode-scanning:17.3.0")

    // ZXing Core
    // App 本身使用
    implementation("com.google.zxing:core:3.5.4")

    // ZXing Core Unit Test 專用
    testImplementation("com.google.zxing:core:3.5.4")

    // JUnit
    testImplementation("junit:junit:4.13.2")

    // OkHttp
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
