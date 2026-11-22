# Voice Transcriber for Android

A privacy-first Android app for selecting audio files stored on the phone, previewing them, and transcribing them locally with Whisper.

## Current milestone

**M0.2 — Native Whisper runtime and offline model loading**

- Select an audio file using Android's Storage Access Framework.
- Preview audio with Android's media decoder.
- Integrate the upstream `whisper.cpp` Android JNI runtime as a pinned Git submodule.
- Download the multilingual Whisper tiny Q5_1 model only after the user explicitly requests it.
- Store the model in app-private storage and load it into the native runtime.
- No audio is uploaded. Model download is the only network operation in this milestone.

The first model is `ggml-tiny-q5_1.bin` from the `ggerganov/whisper.cpp` Hugging Face repository. It is a compact multilingual model intended as a practical starting point for Android devices. The model is downloaded once; after download, loading it does not require network access.

## Planned milestones

- M0.1: File selection and playback — CI verified.
- M0.2: Native `whisper.cpp` runtime and multilingual model loading.
- M0.3: Decode selected audio to 16 kHz mono PCM, run offline transcription, and expose progress/cancellation.
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

The CI workflow installs the required Android SDK, NDK and CMake packages and builds the debug APK.

## Privacy

- Audio is read only from a URI explicitly selected by the user.
- Audio is not sent to a cloud transcription provider.
- The model is stored in app-private storage.
- The app does not require an account or subscription.
- The optional future workstation mode will be separately configured and explicitly selected; it will never silently upload audio.

## Upstream native engine

The native engine is pinned as a Git submodule at `third_party/whisper.cpp` from [ggml-org/whisper.cpp](https://github.com/ggml-org/whisper.cpp). See the upstream project for its source and license.
