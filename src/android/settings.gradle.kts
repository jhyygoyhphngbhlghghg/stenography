pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Official sherpa-onnx Android docs use JitPack. Measured 2026-11-07:
        // com.github.k2-fsa.sherpa-onnx:sherpa-onnx:v2.0.1 resolves (HTTP 200,
        // 49 441 341 bytes, sha256 a8d09d1d…bf0ee7, jni for 4 ABIs) — see
        // artifacts/test-results/sherpa-aar-resolve.json + scripts/inspect-sherpa-aar.sh.
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "neurosteno"
include(":app")
