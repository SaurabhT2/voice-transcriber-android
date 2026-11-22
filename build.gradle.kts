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
    inputs.file(nativeBridge)
    outputs.file(nativeBridge)
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

subprojects {
    if (name == "whisperlib") {
        afterEvaluate {
            dependencies.add("implementation", "org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.2")
        }
    }
    tasks.configureEach {
        if (name.startsWith("configureCMake") || name.startsWith("buildCMake")) {
            dependsOn(rootProject.tasks.named("patchWhisperAutoLanguage"))
        }
    }
}
