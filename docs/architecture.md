# Voice Transcriber — Dual-Execution Architecture

## Product decision

One Android application supports two execution modes:

1. **On-device mode** — private, offline transcription using an Android-compatible Whisper runtime.
2. **My GPU mode** — submit recordings to the user's own workstation running the existing Python/faster-whisper utility.

The Android app owns recording, file selection, playback, job status, transcript viewing, history, and export. The workstation is a private inference service, not a second user-facing application.

## High-level topology

```text
                         Android application
      Recording | Import | Playback | History | Transcript | Export
                                  |
                       Transcription coordinator
                         /                  \
                Local engine             Remote client
              whisper.cpp/NDK          HTTPS + job API
                   |                        |
             Phone CPU/GPU          Private connectivity
                                      /           \
                              LAN endpoint      Tailscale
                                      \           /
                                    GPU API
                                       |
                              FastAPI service layer
                                       |
                           Job queue + job workspace
                                       |
                         Existing Python engine adapter
                                       |
                     faster-whisper / CTranslate2 / CUDA
                                       |
                              Transcript + metadata
```

## Connectivity: local network and away from home

Use two configured routes to the same workstation service:

- **LAN route:** private workstation address/hostname on the trusted home/office network, protected by HTTPS and device authentication.
- **Remote route:** private Tailscale network using the workstation's tailnet HTTPS name. Use Tailscale Serve as the private HTTPS ingress.

The Android remote client maintains an endpoint profile for each route. It checks health, prefers the LAN route when reachable, and falls back to the Tailscale route. It must never fall back to a public, unauthenticated endpoint.

Do not configure router port forwarding or expose the GPU API directly to the public internet. Tailscale access should be restricted by tailnet ACLs to the user's phone and workstation. The API also requires its own revocable device token. Keep secrets in Android Keystore.

For LAN HTTPS, use a certificate trusted or explicitly pinned by the Android app. Do not disable TLS certificate validation or enable unrestricted cleartext traffic.

If LAN certificate setup proves too operationally expensive, the first remote milestone may use the Tailscale HTTPS endpoint for both same-network and off-network access; direct LAN routing can then be optimized without changing the API contract.

## Android application modules

Suggested package boundaries:

- `ui/`: Compose screens and view models.
- `domain/`: transcription request, mode, job, segment, and result models.
- `data/local/`: selected content URI handling, local model management, local inference adapter.
- `data/remote/`: API client, endpoint health checks, upload and job polling.
- `data/history/`: local persistence for jobs and transcript metadata.
- `orchestration/`: selects local or remote engine and normalizes results.
- `export/`: TXT, SRT, and JSON export.

Define a common engine boundary, for example:

```kotlin
interface TranscriptionEngine {
    suspend fun transcribe(
        input: AudioInput,
        options: TranscriptionOptions,
        onProgress: (TranscriptionProgress) -> Unit
    ): TranscriptionResult
}
```

The local and remote engines implement the same domain contract. The UI should not know which engine produced the transcript.

### Local inference

- Integrate whisper.cpp through Android NDK/JNI.
- Start with a quantized multilingual model sized for practical phone memory and latency; do not target large-v3 as the default phone model.
- Download model files only after explicit user choice, show storage requirements, and retain an offline-ready model.
- Keep audio on the phone in local mode.
- Support cancellation, progress, and safe handling of long recordings.

### Remote inference

- Upload the selected audio to the user's workstation.
- Run the existing faster-whisper implementation and preserve its language detection, VAD, beam size, CUDA/CPU fallback, timestamps, and metadata.
- Return normalized transcript segments and run metadata to Android.
- Keep model weights and audio processing on the workstation; no third-party cloud inference is involved.

## Workstation service

Add a separate service layer around the existing Python package. Do not rewrite the transcription engine.

Suggested layout:

```text
server/
  app/
    main.py                 # FastAPI application
    auth.py                 # device-token authentication
    schemas.py              # request/response contracts
    jobs.py                 # job lifecycle and queue
    worker.py               # calls existing engine
    storage.py              # temporary audio and result storage
  tests/
  requirements-server.txt
```

The service should call the existing modules for environment validation, device selection, transcription, and output generation. The current CLI remains usable independently.

Initially run one GPU transcription job at a time. Queue additional jobs; do not load multiple large-v3 models concurrently. Store job state and results on the workstation, with configurable retention and explicit cleanup.

## Initial API contract

All endpoints are versioned under `/v1` and require authentication except where explicitly exposed only to the private ingress.

| Method | Endpoint | Purpose |
|---|---|---|
| GET | `/v1/health` | Service readiness, GPU availability, model readiness |
| POST | `/v1/jobs` | Upload audio and create a transcription job |
| GET | `/v1/jobs/{job_id}` | Read queued/running/completed/failed/cancelled status |
| GET | `/v1/jobs/{job_id}/result` | Retrieve transcript segments and metadata |
| DELETE | `/v1/jobs/{job_id}` | Request cancellation |

Job creation accepts audio plus options such as model, language, beam size, and VAD. The response returns a job ID. Job status includes progress, stage, timestamps, and a safe error message. Result segments use start/end seconds and text, matching the current Python `Segment` model.

Add resumable/chunked uploads, server-sent progress, and background retry after the basic job API is reliable. Do not keep an HTTP request open for the duration of inference.

## Security and privacy requirements

- No public port forwarding and no anonymous API access.
- Tailscale ACLs restrict which devices can reach the workstation.
- Per-device revocable bearer tokens; store tokens in Android Keystore.
- HTTPS on both LAN and tailnet paths; certificate pinning/trust configured deliberately.
- Validate file size, extension, MIME type, and job ownership.
- Generate server-side job IDs and use non-guessable identifiers.
- Temporary audio and transcript retention is configurable; default to deletion after a short retention window once the app has retrieved the result.
- Do not log transcript text, bearer tokens, or raw audio.
- The user explicitly chooses Local or My GPU; no silent upload of audio.
- Show the destination and mode before upload.

## Failure behavior

- If the user selects Local, no network is required.
- If the user selects My GPU and neither route is reachable, keep the audio on the phone and offer retry or local transcription.
- If an upload is interrupted, preserve the source audio and allow retry.
- If the GPU worker fails, preserve a clear failed job state and diagnostic ID; do not lose the original recording.
- If the app closes, it can restore remote job status by job ID.
- Never silently switch from Local to Remote or Remote to Local when that changes where audio is processed.

## Delivery plan

| Milestone | Scope | Exit criteria |
|---|---|---|
| M0.1 | Android file selection and playback | Existing CI green; reliable URI lifecycle |
| M0.2 | Local Whisper runtime | Short multilingual test audio transcribes offline |
| M0.3 | Local transcript UX | Progress, cancellation, history, TXT/SRT export |
| M1.0 | Workstation API wrapper | Health, authenticated job creation, status, result |
| M1.1 | LAN connectivity | Phone submits and retrieves a job over trusted LAN HTTPS |
| M1.2 | Remote connectivity | Same workflow works over Tailscale away from LAN |
| M1.3 | Resilience and hardening | Retry, cancellation, retention, security and long-file tests |

## Architectural guardrails

- Preserve the Python CLI and its existing modules as the reference implementation.
- Keep the workstation service separate from Android and from the transcription engine.
- Keep the API contract transport-independent so LAN and Tailscale use the same endpoints.
- Keep local and remote execution behind one Android domain interface.
- No paid cloud transcription API, mandatory subscription, or public internet exposure.
