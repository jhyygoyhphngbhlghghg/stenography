import java.io.File
import java.security.MessageDigest

fun fileSha256(file: File): String = file.inputStream().use { input ->
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(1024 * 1024)
    while (true) {
        val n = input.read(buffer)
        if (n < 0) break
        digest.update(buffer, 0, n)
    }
    digest.digest().joinToString("") { "%02x".format(it) }
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val repoRoot: File = rootProject.projectDir.resolve("../..").normalize()
val modelsSrc: File = File(repoRoot, "models")
val embeddedModelsAssets = layout.buildDirectory.dir("generated/embeddedModels/assets")

// Self-contained by default. Slim remains model-free; -PembedLlm=false is
// available only for developer builds that install their own GGUF separately.
val embedLlm: Boolean = (findProperty("embedLlm")?.toString() ?: "true") == "true"
val compactModels: Boolean = (findProperty("compactModels")?.toString() ?: "false") == "true"
val targetAbis = (findProperty("targetAbis")?.toString() ?: "arm64-v8a")
    .split(',').map { it.trim() }.filter { it.isNotEmpty() }
require(targetAbis.isNotEmpty() && targetAbis.all { it in setOf("arm64-v8a", "armeabi-v7a", "x86_64") }) {
    "targetAbis must list supported Android ABIs"
}
val encoderRelPath = "asr/zipformer-ru-int8/encoder.int8.onnx"
val compactValidation = File(repoRoot, "artifacts/compact/encoder-validation.json")

val judgeModelRelPaths = mutableListOf(
    "asr/zipformer-ru-int8/encoder.int8.onnx",
    "asr/zipformer-ru-int8/decoder.onnx",
    "asr/zipformer-ru-int8/joiner.int8.onnx",
    "asr/zipformer-ru-int8/tokens.txt",
    "vad/silero_vad.onnx",
    "diar/segmentation.onnx",
    "diar/embedding.onnx",
)
if (embedLlm) judgeModelRelPaths.add("llm/qwen2-0_5b-instruct-q4_0.gguf")

fun resolveJudgeModel(rel: String): File {
    if (compactModels && rel == encoderRelPath) return File(modelsSrc, "compact/$rel")
    val direct = File(modelsSrc, rel)
    if (direct.isFile) return direct
    if (rel.endsWith("embedding.onnx")) {
        val alt = File(modelsSrc, "diar/3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx")
        if (alt.isFile) return alt
    }
    if (rel.endsWith("segmentation.onnx")) {
        val alt = File(modelsSrc, "diar/model.onnx")
        if (alt.isFile) return alt
        val nested = File(modelsSrc, "diar/sherpa-onnx-pyannote-segmentation-3-0/model.onnx")
        if (nested.isFile) return nested
    }
    return direct
}

val prepareEmbeddedModels by tasks.registering {
    group = "build"
    description = "Copy Zipformer-ru + Silero VAD + diar ONNX (and, with -PembedLlm, the GGUF) into judge APK assets/models"
    outputs.dir(embeddedModelsAssets)
    // Without declared inputs Gradle marks this up-to-date forever, and the
    // APK silently ships whatever weights the first build found. Measured the
    // hard way: a replaced pyannote segmentation stayed stale inside a
    // "successful" assemble.
    judgeModelRelPaths.forEach { rel ->
        val src = resolveJudgeModel(rel)
        if (src.isFile) {
            inputs.file(src)
        } else {
            inputs.property("absent:$rel", true)
        }
    }
    inputs.property("embedLlm", embedLlm)
    inputs.property("compactModels", compactModels)
    if (compactModels && compactValidation.isFile) inputs.file(compactValidation)
    doLast {
        if (compactModels) {
            require(compactValidation.isFile && resolveJudgeModel(encoderRelPath).isFile) {
                "Run scripts/prepare_compact_encoder.py before -PcompactModels=true"
            }
            val validation = groovy.json.JsonSlurper().parse(compactValidation) as Map<*, *>
            require(validation["all_text_tokens_timestamps_identical"] == true &&
                validation["metadata_identical"] == true &&
                validation["hardware_layout_optimization"] == false &&
                validation["prepared_sha256"] == fileSha256(resolveJudgeModel(encoderRelPath)) &&
                validation["source_sha256"] == fileSha256(File(modelsSrc, encoderRelPath))) {
                "Compact encoder does not match its passing validation report; rerun prepare_compact_encoder.py"
            }
        }
        var missing = judgeModelRelPaths.filter { !resolveJudgeModel(it).isFile }
        if (missing.isNotEmpty()) {
            val script = File(repoRoot, "scripts/download-models.sh")
            require(script.isFile) { "missing $script and models $missing" }
            exec {
                commandLine("bash", script.absolutePath)
                workingDir(repoRoot)
            }
            missing = judgeModelRelPaths.filter { !resolveJudgeModel(it).isFile }
        }
        require(missing.isEmpty()) { "judge models missing after download: $missing" }
        val destRoot = embeddedModelsAssets.get().asFile.resolve("models")
        destRoot.deleteRecursively()
        for (rel in judgeModelRelPaths) {
            val src = resolveJudgeModel(rel)
            val dest = File(destRoot, rel)
            dest.parentFile.mkdirs()
            src.copyTo(dest, overwrite = true)
        }
        File(destRoot, ".embedded").writeText(
            judgeModelRelPaths.joinToString("\n") { rel ->
                val f = File(destRoot, rel)
                "$rel ${f.length()} ${fileSha256(f)}"
            } + "\n",
        )
        println("embedded ${judgeModelRelPaths.size} model files into $destRoot (${destRoot.walkTopDown().filter { it.isFile }.sumOf { it.length() }} bytes)")
    }
}

android {
    namespace = "team.mikcoders.neurosteno"
    compileSdk = 35

    defaultConfig {
        applicationId = "team.mikcoders.neurosteno"
        // AAR minSdk 21; sherpa Java demo uses 28. Brief allows 26–28.
        minSdk = 28
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += targetAbis
        }
        // EMBEDDED_MODELS is set per build type; slim stays model-free.
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
        debug {
            buildConfigField("Boolean", "EMBEDDED_MODELS", "true")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            buildConfigField("Boolean", "EMBEDDED_MODELS", "true")
        }
        create("compact") {
            initWith(getByName("release"))
            matchingFallbacks += listOf("release")
            // Installable local candidate, not a production-signing credential.
            signingConfig = signingConfigs.getByName("debug")
            versionNameSuffix = "-compact"
        }
        create("slim") {
            initWith(getByName("debug"))
            matchingFallbacks += listOf("debug")
            versionNameSuffix = "-slim"
            buildConfigField("Boolean", "EMBEDDED_MODELS", "false")
        }
    }

    sourceSets {
        getByName("debug") {
            assets.srcDir(embeddedModelsAssets)
        }
        getByName("release") {
            assets.srcDir(embeddedModelsAssets)
        }
        getByName("compact") {
            assets.srcDir(embeddedModelsAssets)
        }
    }

    // Let AAPT losslessly compress ONNX/GGUF assets. They are streamed to
    // filesDir once, then loaded from ordinary mmap-compatible files. Inference
    // never decompresses a model, and no tensor values are changed.

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
    // JNI + Java API: ASR, Silero VAD, OfflineSpeakerDiarization. JitPack AAR
    // (packaging=aar), 49 441 341 bytes, sha256 a8d09d1d32f3eb8520482c6d3d43fae
    // 674681c5b050385967c70a3c2c9bf0ee7 — re-check with scripts/inspect-sherpa-aar.sh.
    implementation("com.github.k2-fsa.sherpa-onnx:sherpa-onnx:v2.0.1")
    // Tiny on-device GGUF (llama.cpp prebuilt JNI, MIT, arm64-v8a, API 24+).
    // Weights are NOT packed here — ASR/VAD/diar assets come from
    // prepareEmbeddedModels; the GGUF lands in filesDir/models/llm
    // (scripts/download-models.sh).
    implementation("dev.ffmpegkit-maintained:llama-android:0.1.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
}

afterEvaluate {
    tasks.matching { t ->
        val n = t.name
        !n.contains("Slim", ignoreCase = true) &&
            (n.contains("LintVital", ignoreCase = true) ||
                (n.contains("Assets") &&
                    (n.startsWith("merge") || n.startsWith("generate") || n.startsWith("package"))))
    }.configureEach {
        dependsOn(prepareEmbeddedModels)
    }
}

tasks.register<Copy>("copyApkToDrop") {
    dependsOn("assembleStenographerDebug")
    from(layout.buildDirectory.dir("outputs/apk/stenographer/debug"))
    into(rootProject.layout.projectDirectory.dir("../../apk"))
    include("*.apk")
}
