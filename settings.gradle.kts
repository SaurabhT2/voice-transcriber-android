pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "VoiceTranscriber"
include(":app", ":whisperlib")
project(":whisperlib").projectDir = file("third_party/whisper.cpp/examples/whisper.android/lib")
