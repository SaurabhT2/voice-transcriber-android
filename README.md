# Meeting Workspace for Android

Native Android client for the shared Meeting Workspace.

## Product behavior

Android and web use the same Supabase project, authentication identity, FastAPI backend, private audio bucket, transcription jobs, meetings, transcripts, and meeting notes. Sign in with the same email address on either client to see the same shared meeting history.

The Android client adds two device-native capabilities:

- **Record:** capture a meeting with the phone microphone and save the original audio in app-private storage.
- **Local transcription:** run the on-device multilingual model using the native Android runtime. The audio remains on the phone; only the completed transcript is synced to the shared workspace.

For recordings that should use the workstation GPU, select **Shared GPU** explicitly. The app creates a job through the same upload API used by the web app, uploads to the same private Supabase bucket using a short-lived signed URL, and polls the same job until a transcript is ready.

## Current Android implementation

- Supabase email magic-link sign-in using the shared project, protected by PKCE (see below).
- Encrypted session storage backed by Android Keystore; tokens refresh automatically and the session survives being offline.
- Shared recording history with owner-scoped Supabase reads.
- Local audio capture with pause, resume and stop, running in a foreground service so it continues with the screen off or the app in the background, and survives rotation.
- Audio import using Android Storage Access Framework.
- On-device transcription using the existing pinned whisper.cpp Android runtime. It runs in an application-scoped job under a foreground service, so leaving the app or rotating the phone does not cancel it.
- Shared GPU upload, job tracking and transcript retrieval.
- Offline transcript persistence (atomic writes) and idempotent sync through `POST /api/v1/meetings/sync`, retried in the background by WorkManager whenever the network is available.
- Meeting notes retrieval and generation through the shared API.
- White-label UI: Meeting Workspace; no inference-engine branding is exposed to users.

## Supabase Auth one-time setup

In Supabase Dashboard → Authentication → URL Configuration → Additional Redirect URLs, add:

```text
meetingworkspace://auth/callback
```

The web client continues using `https://transcribe.saurabhtiwariai.com`. Android uses the custom URI above so a magic link opened on the phone returns to the app.

Android requests the link with **PKCE** (`code_challenge`, S256). The emailed link carries a one-time `code` that only the phone that requested it can exchange, so another app that registers the same custom scheme cannot use an intercepted link to sign anyone in. Consequences:

- The link must be opened on the same phone that requested it. Otherwise the app asks the user to request a new link.
- Requesting a second link invalidates the first one.
- The legacy implicit flow (tokens in the URL fragment) is intentionally not accepted any more. If the Supabase email template was customised to build its own link from `{{ .TokenHash }}`, switch it back to `{{ .ConfirmationURL }}`.

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
- Gradle 8.9 (use the committed wrapper: `./gradlew`)

The Android CI workflow runs unit tests and lint, assembles a debug APK and verifies that the native libraries are aligned for 16 KB memory pages.

### Build options

Only `arm64-v8a` is built by default (all current phones). For an x86_64 emulator:

```bash
./gradlew assembleDebug -PwhisperAbis=arm64-v8a,x86_64
```

Configuration can be overridden with `-P<NAME>=...` or in `~/.gradle/gradle.properties`:

| Property | Default | Purpose |
| --- | --- | --- |
| `SUPABASE_URL`, `SUPABASE_PUBLISHABLE_KEY` | shared project | Public client identifiers |
| `API_BASE_URL` | M2 preview API | Backend used by debug builds |
| `RELEASE_API_BASE_URL` | same as `API_BASE_URL` | Backend used by release builds. **Set this to the production API before publishing.** |
| `whisperAbis` | `arm64-v8a` | Native ABIs to build and package |

Release builds are minified with R8. Signing reads `ANDROID_KEYSTORE_PATH`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS` and `ANDROID_KEY_PASSWORD` from the environment; without them the release APK is unsigned.

### Tests

`./gradlew testDebugUnitTest` covers the resampler, chunker, PKCE, token refresh/retry rules, atomic storage and sync-failure classification. These are plain JVM tests; no emulator is needed.

## Local model

The compact multilingual model is downloaded only after the user requests local transcription. An interrupted download resumes where it stopped. Model files are stored in app-private storage and verified by SHA-256 before loading. Actual speed and memory use depend on the phone.

Local transcription skips silent sections (which Whisper tends to hallucinate text for) and splits long audio at the quietest moment near each 30-second boundary instead of mid-word. Note: the spoken language is always auto-detected on device; the language picker only applies to Shared GPU jobs and to the language label stored with the transcript.

## Privacy and execution choices

- Local mode does not upload audio. The app syncs transcript text and meeting metadata only.
- Shared GPU mode requires an explicit user action before audio upload.
- Audio uploads use a short-lived signed URL to the private `meeting-audio` bucket.
- Supabase publishable keys are client-safe and are paired with owner-scoped RLS and the user's access token.
- Service-role keys, worker credentials, and inference secrets never ship in the Android app.
- There is no mandatory transcription subscription or third-party cloud inference provider.

## Shared backend

The FastAPI contract and Supabase data model are maintained in [whisper-platform-api](https://github.com/SaurabhT2/whisper-platform-api). Android calls the same API version used by the web app.
