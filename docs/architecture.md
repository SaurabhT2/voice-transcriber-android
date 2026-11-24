# Meeting Workspace — Shared Backend and Dual Transcription Architecture

## Product contract

The Android and web apps are two clients of one Meeting Workspace. They share:

- Supabase Auth users and sessions.
- Supabase project and private `meeting-audio` bucket.
- FastAPI API base and versioned endpoint contract.
- Transcription jobs, meetings, transcript records and meeting artifacts.
- Owner-scoped access enforced by Supabase RLS and the FastAPI bearer-token dependency.
- Visual language, navigation concepts, status labels and transcript/notes terminology.

The web client provides browser upload and recording. Android adds native recording and an explicit on-device transcription path.

## Execution modes

1. **Local on device** — use the Android `whisper.cpp` runtime and a compact multilingual model. Audio stays in app-private storage. Once a transcript is complete, Android syncs transcript text and metadata to the shared backend using `POST /api/v1/meetings/sync`. It does not upload the audio.
2. **Shared GPU** — upload audio after explicit user selection. Android creates a job through `POST /api/v1/transcriptions/upload`, uploads through the returned short-lived signed URL to the same private Supabase bucket used by the web client, calls `complete-upload`, and polls the same job endpoint. The existing workstation worker runs faster-whisper and persists the transcript and segments.

No silent fallback is allowed between Local and Shared GPU because that would change where the audio is processed.

## Shared service topology

```text
Android app                         Web app
  |                                    |
  | Supabase Auth                      | Supabase Auth
  |                                    |
  +----------------+-------------------+
                   |
             Supabase project
        Auth | Postgres | Private Storage
                   |
            Shared FastAPI API
          /                     \
   Android offline sync       Upload/job APIs
   meeting notes              meeting detail
          \                     /
               Supabase data
                    |
          Persistent GPU worker
          existing faster-whisper
                    |
            Transcript + segments
```

## Shared API contract

Base prefix: `/api/v1`. All protected endpoints require `Authorization: Bearer <Supabase access token>`.

| Method | Endpoint | Android/web use |
|---|---|---|
| GET | `/api/v1/meta` | Discover backend identity/capabilities |
| GET | `/api/v1/providers` | Discover inference providers |
| GET | `/api/v1/me` | Verify caller identity |
| POST | `/api/v1/transcriptions/upload` | Create an upload-pending job and signed URL |
| POST | `/api/v1/transcriptions/{job_id}/complete-upload` | Verify object and enqueue |
| GET | `/api/v1/transcriptions/{job_id}` | Poll job status |
| POST | `/api/v1/transcriptions/{job_id}/cancel` | Cancel eligible jobs |
| POST | `/api/v1/meetings/sync` | Idempotently sync an on-device transcript |
| GET | `/api/v1/meetings/{meeting_id}` | Read transcript, segments and speakers |
| GET/POST | `/api/v1/meetings/{meeting_id}/notes` | Read/generate meeting notes |

The current web client reads the shared job and meeting lists from Supabase Data API using the same publishable key, signed-in access token and owner-scoped RLS policies. Android follows the same read path for cross-device history. Transcript detail and notes are read through the FastAPI contract.

## Authentication

- Use the same Supabase project as the web app.
- Magic-link sign-in uses the same email identity, with PKCE so an intercepted custom-scheme link cannot be redeemed by another app.
- Android receives the verified session on `meetingworkspace://auth/callback`.
- Add this URI to Supabase Authentication → URL Configuration → Additional Redirect URLs.
- Access/refresh tokens are stored encrypted using Android Keystore. `SessionManager` refreshes tokens before expiry, retries once after a 401, shares one refresh between concurrent calls, and only signs the user out when the auth server rejects the refresh token (never because the device is offline).
- The Android client includes only the project URL and publishable key. Never include service-role keys or worker secrets.

## Native recording and local inference

- Android records through `MediaRecorder` to app-private `.m4a` files.
- Recording is owned by `RecordingController` and driven by a foreground `RecordingService` (microphone type), so it keeps running when the screen locks, the app is backgrounded or the device rotates. Audio is captured at 16 kHz mono, the rate Whisper consumes.
- Pause/resume/stop are available from the app and (stop) from the notification.
- The existing native decoder converts audio to mono 16 kHz float PCM in bounded chunks.
- The existing pinned whisper.cpp JNI runtime processes those chunks.
- Local transcript and sync metadata are written atomically to app-private storage before network sync. If the device is offline, the transcript remains available locally and `SyncWorker` (WorkManager, network-constrained, exponential backoff) retries later. `SyncCoordinator` serialises all sync callers.
- On-device transcription runs in `LocalTranscriber`, an application-scoped component under a foreground service. The UI (`MeetingViewModel`) only observes it.
- The offline sync payload includes a stable `client_event_id`, device identifier, title, timestamps, transcript, language and local model identifier.

## Shared GPU upload

- Audio is sent only after the user chooses Shared GPU and taps Upload and transcribe.
- Android creates the job through the FastAPI upload endpoint and uses the short-lived signed URL returned by that endpoint.
- The upload target is the same private `meeting-audio` bucket used by web.
- The shared API verifies the object and enqueues the job.
- Android polls the owner-scoped job endpoint and opens the shared meeting when complete.
- Original audio remains on the phone unless separately removed by the user; cloud upload is an explicit copy.

## Security and privacy

- Supabase publishable key only in mobile code; user access token travels over HTTPS.
- Database history reads use authenticated PostgREST requests protected by RLS.
- FastAPI endpoints verify Supabase bearer tokens and enforce owner-scoped access.
- Private audio upload uses a short-lived signed token; the bucket stays private.
- Android never receives service-role keys, worker credentials, model-server credentials or LLM secrets.
- Local transcription never sends raw audio to the backend.

## Build and verification

The Android repo pins the native whisper.cpp runtime as a Git submodule. CI must clone submodules, install JDK 17, Android SDK 35, NDK 25.2.9519653 and CMake 3.22.1, then run:

```bash
gradle testDebugUnitTest
gradle assembleDebug
```
