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
        // Official sherpa-onnx Android docs use JitPack. The nested coordinate
        // com.github.k2-fsa.sherpa-onnx:sherpa-onnx:v2.0.1 is unpublished (404);
        // jitpack.yml itself wget's the GitHub Releases AAR. We keep this repo
        // and resolve the AAR via GitHub (see :app fetchSherpaOnnxAar / ivy).
        maven { url = uri("https://jitpack.io") }
        exclusiveContent {
            forRepository {
                ivy {
                    name = "sherpa-onnx-github-releases"
                    url = uri("https://github.com/k2-fsa/sherpa-onnx/releases/download")
                    patternLayout {
                        artifact("v[revision]/[module]-[revision].[ext]")
                    }
                    metadataSources { artifact() }
                }
            }
            filter {
                includeGroup("com.k2fsa.sherpa.onnx.github")
            }
        }
    }
}

rootProject.name = "neurosteno"
include(":app")
