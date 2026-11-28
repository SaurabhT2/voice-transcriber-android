plugins {
    id("com.android.application") version "8.7.3" apply false
    id("com.android.library") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}

val patchWhisperAutoLanguage = tasks.register("patchWhisperAutoLanguage") {
    val nativeBridge = layout.projectDirectory.file(
        "third_party/whisper.cpp/examples/whisper.android/lib/src/main/jni/whisper/jni.c"
    )
    doLast {
        val file = nativeBridge.asFile
        check(file.isFile) { "Whisper Android JNI source is missing. Clone with --recurse-submodules." }
        val source = file.readText()
        val englishOnly = "params.language = \"en\";"
        val autoDetect = "params.language = \"auto\";"
        when {
            source.contains(autoDetect) -> logger.lifecycle("Whisper JNI already enables automatic language detection.")
            source.contains(englishOnly) -> {
                file.writeText(source.replace(englishOnly, autoDetect))
                logger.lifecycle("Patched Whisper JNI to automatically detect spoken language.")
            }
            else -> error("Whisper JNI language setting changed upstream; review the patch before building.")
        }
    }
}

// ABIs to build natively; keep in sync with app/build.gradle.kts. Override with -PwhisperAbis=...
val whisperAbis = (providers.gradleProperty("whisperAbis").orNull ?: "arm64-v8a")
    .split(",").map { it.trim() }.filter { it.isNotEmpty() }

subprojects {
    if (name == "whisperlib") {
        // Configure only after the Android library plugin has created its dependency
        // configurations and Android extension. This avoids racing project evaluation.
        plugins.withId("com.android.library") {
            dependencies.add("implementation", "org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
            extensions.findByType(com.android.build.gradle.LibraryExtension::class.java)?.apply {
                // The upstream whisper.android module pins NDK r25. Override it with r28,
                // whose packaged native runtimes (including libomp.so) support 16 KB pages.
                ndkVersion = "28.0.13004108"
                defaultConfig {
                    // Build only the ABIs we ship (the library would otherwise build all of them).
                    ndk {
                        abiFilters.clear()
                        abiFilters.addAll(whisperAbis)
                    }
                    // Android 15+ devices may use 16 KB memory pages; Google Play requires
                    // native libraries to be aligned for them.
                    externalNativeBuild {
                        cmake {
                            // Align newly linked Whisper/GGML shared libraries for 16 KB pages.
                            arguments("-DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384")
                        }
                    }
                }
            }
        }
    }
    tasks.configureEach {
        if (name.startsWith("configureCMake") || name.startsWith("buildCMake")) {
            dependsOn(rootProject.tasks.named("patchWhisperAutoLanguage"))
        }
    }
}
