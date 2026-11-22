# Meeting Workspace for Android

Native Android client for the shared Meeting Workspace.

## Product behavior

Android and web use the same Supabase project, authentication identity, FastAPI backend, private audio bucket, transcription jobs, meetings, transcripts, and meeting notes. Sign in with the same email address on either client to see the same shared meeting history.

The Android client adds two device-native capabilities:

- **Record:** capture a meeting with the phone microphone and save the original audio in app-private storage.
- **Local transcription:** run the on-device multilingual model using the native Android runtime. The audio remains on the phone; only the completed transcript is synced to the shared workspace.

For recordings that should use the workstation GPU, select **Shared GPU** explicitly. The app creates a job through the same upload API used by the web app, uploads to the same private Supabase bucket using a short-lived signed URL, and polls the same job until a transcript is ready.

## Current Android implementation

- Supabase email magic-link sign-in using the shared project.
- Encrypted session storage backed by Android Keystore.
- Shared recording history with owner-scoped Supabase reads.
- Local audio capture with pause, resume and stop.
- Audio import using Android Storage Access Framework.
- On-device transcription using the existing pinned whisper.cpp Android runtime.
- Shared GPU upload, job tracking and transcript retrieval.
- Offline transcript persistence and idempotent sync through `POST /api/v1/meetings/sync`.
- Meeting notes retrieval and generation through the shared API.
- White-label UI: Meeting Workspace; no inference-engine branding is exposed to users.

## Supabase Auth one-time setup

In Supabase Dashboard → Authentication → URL Configuration → Additional Redirect URLs, add:

```text
meetingworkspace://auth/callback
```

The web client continues using `https://transcribe.saurabhtiwariai.com`. Android uses the custom URI above so a magic link opened on the phone returns to the app.

## Build

Clone with the native engine submodule:

```bash
git clone --recurse-submodules https://github.com/SaurabhT2/voice-transcriber-android.git
```

Open the project in Android Studio and sync Gradle.

Requirements:
- JDK 17
- Android SDK Platform 35
- Android NDK 25.2.9519653
- CMake 3.22.1
- Gradle 8.9

The Android CI workflow runs unit tests and assembles a debug APK.

## Local model

The compact multilingual model is downloaded only after the user requests local transcription. Model files are stored in app-private storage and verified by SHA-256 before loading. Actual speed and memory use depend on the phone.

## Privacy and execution choices

- Local mode does not upload audio. The app syncs transcript text and meeting metadata only.
- Shared GPU mode requires an explicit user action before audio upload.
- Audio uploads use a short-lived signed URL to the private `meeting-audio` bucket.
- Supabase publishable keys are client-safe and are paired with owner-scoped RLS and the user's access token.
- Service-role keys, worker credentials, and inference secrets never ship in the Android app.
- There is no mandatory transcription subscription or third-party cloud inference provider.

## Shared backend

The FastAPI contract and Supabase data model are maintained in [whisper-platform-api](https://github.com/SaurabhT2/whisper-platform-api). Android calls the same API version used by the web app.
