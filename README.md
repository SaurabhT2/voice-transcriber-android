# Voice Transcriber for Android

A privacy-first Android app for selecting audio files stored on the phone, playing them back, and transcribing them locally with Whisper.

## Current milestone

**M0.1 — Audio selection and playback**

- Select an audio file using Android's Storage Access Framework.
- Read supported audio formats through a content URI (no broad storage permission).
- Play and pause the selected audio.
- Prepare a clean boundary for the upcoming on-device Whisper integration.

## Planned milestones

- M0.1: File selection and playback.
- M0.2: Integrate `whisper.cpp` and load a multilingual model.
- M0.3: Offline transcription with progress and cancellation.
- M0.4: Transcript display, copy, TXT and SRT export.
- M0.5: Long-file, memory and performance testing.

## Build

Open the project in Android Studio (Hedgehog or newer) and let Gradle sync. Build the `app` configuration for an Android device or emulator.

Requirements: JDK 17, Android SDK 35, Gradle 8.9.

## Privacy

The initial app does not upload audio or require an account. Audio is accessed through the URI explicitly selected by the user. Whisper model integration will be local-first.
