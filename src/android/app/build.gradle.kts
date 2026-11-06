plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "team.mikcoders.neurosteno"
    compileSdk = 35

    defaultConfig {
        applicationId = "team.mikcoders.neurosteno"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-overnight"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    flavorDimensions += "role"
    productFlavors {
        create("stenographer") {
            dimension = "role"
            isDefault = true
            buildConfigField("Boolean", "PRESENT_CONNECT", "false")
        }
        create("presentConnect") {
            dimension = "role"
            applicationIdSuffix = ".present"
            buildConfigField("Boolean", "PRESENT_CONNECT", "true")
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
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // JNI + Java API: ASR, Silero VAD, OfflineSpeakerDiarization
    // JitPack AAR (packaging=aar), ~49 MB, contains libsherpa-onnx-jni.so for
    // arm64-v8a / armeabi-v7a / x86 / x86_64. Measured: scripts/inspect-sherpa-aar.sh
    implementation("com.github.k2-fsa.sherpa-onnx:sherpa-onnx:v2.0.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
}

tasks.register<Copy>("copyApkToDrop") {
    dependsOn("assembleStenographerDebug")
    from(layout.buildDirectory.dir("outputs/apk/stenographer/debug"))
    into(rootProject.layout.projectDirectory.dir("../../apk"))
    include("*.apk")
}
