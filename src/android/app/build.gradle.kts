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
        // AAR minSdk 21; sherpa Java demo uses 28. Brief allows 26–28.
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-overnight"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
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
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

val sherpaOnnxVersion = "1.13.5"
val sherpaAar = layout.projectDirectory.file("libs/sherpa-onnx-$sherpaOnnxVersion.aar")

val fetchSherpaOnnxAar by tasks.registering {
    group = "build"
    description = "Download sherpa-onnx Android AAR (same file JitPack jitpack.yml wget's)."
    outputs.file(sherpaAar)
    outputs.upToDateWhen { sherpaAar.asFile.isFile && sherpaAar.asFile.length() > 1_000_000L }
    doLast {
        val dest = sherpaAar.asFile
        dest.parentFile.mkdirs()
        if (dest.isFile && dest.length() > 1_000_000L) return@doLast
        val url =
            uri("https://github.com/k2-fsa/sherpa-onnx/releases/download/v$sherpaOnnxVersion/sherpa-onnx-$sherpaOnnxVersion.aar")
        logger.lifecycle("GET $url")
        url.toURL().openStream().use { input ->
            dest.outputStream().buffered().use { output -> input.copyTo(output) }
        }
        require(dest.length() > 1_000_000L) {
            "sherpa-onnx AAR download too small (${dest.length()} bytes)"
        }
    }
}

tasks.matching { it.name.startsWith("pre") && it.name.endsWith("Build") }.configureEach {
    dependsOn(fetchSherpaOnnxAar)
}
tasks.matching { it.name.contains("compile") && it.name.contains("Kotlin") }.configureEach {
    dependsOn(fetchSherpaOnnxAar)
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
    // sherpa-onnx Android AAR (Kotlin API + JNI). JitPack coordinate
    // com.github.k2-fsa.sherpa-onnx:sherpa-onnx:v2.0.1 is 404; v1.13.5 AAR is
    // what k2-fsa jitpack.yml wget's from GitHub Releases (settings ivy + fetch).
    implementation(files(sherpaAar))
    // Tiny on-device GGUF (Qwen2-0.5B) via llama.cpp JNI. Weights are NOT packed
    // here — Platform PR #3 @ af71d91 embeds ASR/VAD/diar; GGUF lives in
    // filesDir/models/llm after that unpack (download-models.sh).
    implementation("dev.ffmpegkit-maintained:llama-android:0.1.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
}

tasks.register<Copy>("copyApkToDrop") {
    dependsOn("assembleStenographerDebug")
    from(layout.buildDirectory.dir("outputs/apk/stenographer/debug"))
    into(rootProject.layout.projectDirectory.dir("../../apk"))
    include("*.apk")
}
