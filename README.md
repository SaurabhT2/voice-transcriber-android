# Voice Transcriber for Android

A privacy-first Android app for selecting audio files stored on the phone, previewing them, and transcribing them locally with Whisper.

## Current milestone

**M0.3 — Offline transcription implementation in progress**

- Select and preview audio using Android's Storage Access Framework and platform media codecs.
- Integrate the upstream `whisper.cpp` Android JNI runtime as a pinned Git submodule.
- Download the multilingual Whisper tiny Q5_1 model only after the user explicitly requests it.
- Verify the model SHA-256 before loading it from app-private storage.
- Decode supported device audio formats to mono 16 kHz float PCM.
- Transcribe in bounded 30-second chunks, with status updates and cancellation between chunks.
- Patch the pinned Android JNI bridge to request automatic language detection rather than forcing English.
- Keep the audio on the phone. Model download is the only network operation.

The first model is `ggml-tiny-q5_1.bin` from the `ggerganov/whisper.cpp` Hugging Face repository. It is a compact multilingual model intended as a practical starting point for Android devices. The model is downloaded once; after download, inference works without network access. Actual device compatibility and transcription quality still require a physical-phone test.

## Planned milestones

- M0.1: File selection and playback — CI verified.
- M0.2: Native `whisper.cpp` runtime and multilingual model loading — CI build green.
- M0.3: Decode selected audio to 16 kHz mono PCM, run offline transcription, and expose progress/cancellation — implementation underway.
- M0.4: Transcript display, copy, TXT and SRT export.
- M0.5: Long-file, memory and performance testing.
- M1.x: Optional private workstation/GPU execution path. Local and remote execution remain explicit user choices.

## Build

Clone with the native engine submodule:

```bash
git clone --recurse-submodules https://github.com/SaurabhT2/voice-transcriber-android.git
```

Open the project in Android Studio and let Gradle sync.

Requirements:
- JDK 17
- Android SDK Platform 35
- Android NDK 25.2.9519653
- CMake 3.22.1
- Gradle 8.9

The CI workflow installs the required Android SDK, NDK and CMake packages and builds the debug APK. The root Gradle build applies a small, idempotent patch to the pinned upstream Android JNI bridge so Whisper auto-detects the spoken language. The patch is limited to the JNI language setting; review `patches/whisper-android-auto-language.patch` when updating the submodule.

## Privacy

- Audio is read only from a URI explicitly selected by the user.
- Audio is not sent to a cloud transcription provider.
- The model is stored in app-private storage.
- The app does not require an account or subscription.
- The optional future workstation mode will be separately configured and explicitly selected; it will never silently upload audio.

## Upstream native engine

The native engine is pinned as a Git submodule at `third_party/whisper.cpp` from [ggml-org/whisper.cpp](https://github.com/ggml-org/whisper.cpp). See the upstream project for its source and license.
