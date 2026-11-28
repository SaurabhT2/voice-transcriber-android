# Phase 1 Source-backed Audit — Android Live Transcription Branch

**Repository:** `SaurabhT2/voice-transcriber-android`  
**Audit base:** `feature/live-transcription-android`  
**Implementation branch:** `feature/phase1-foundation-completion`  
**Audit date:** 28 November 2026  
**Roadmap:** Meeting Intelligence Phase-wise Product Roadmap and Otter Parity, Phase 1 (P1-01–P1-30)

## Scope and limitations

This audit examines the Android repository only. It does not certify the FastAPI backend, Supabase schema/RLS policies, Web client, iOS client, production deployment, or device behaviour. A UI/API client call is not proof that the server-side operation, data policy, or integration is complete. Items marked **Needs integration verification** require backend, device, or cross-platform evidence.

## Source-backed feature matrix

| ID | Phase 1 requirement | Source evidence on live branch | Audit result |
|---|---|---|---|
| P1-01 | Native recording, pause/resume and background capture | `Recording.kt`, `LiveCaptureRecorder.kt`, `ForegroundServices.kt`, `AndroidManifest.xml`; README documents foreground-service capture | Implemented in Android; real-device lifecycle acceptance still required |
| P1-02 | Import existing audio files | `MainActivity.kt`: OpenDocument picker; `ViewModels.kt:onAudioPicked` | Implemented |
| P1-03 | Offline recording and reliable upload/sync | `LocalMeetingStore.kt`, `SyncWork.kt`, `ViewModels.kt:triggerSync`; README documents atomic local transcript persistence and WorkManager retry | Transcript sync implemented; offline audio upload/recovery is not established |
| P1-04 | Live transcription and chunked updates | `LiveCaptureRecorder.kt`, `LiveChunks.kt`, `LiveStreamer.kt`, `WorkspaceBackend.kt:uploadLiveChunk` | Implemented beta; live V1 acceptance gates still apply |
| P1-05 | Whisper and on-device transcription path | `LocalTranscriber.kt`, `WhisperModelManager.kt`; README documents on-device whisper.cpp | Implemented; device performance/quality validation required |
| P1-06 | Shared GPU transcription path | `WorkspaceBackend.kt:createCloudUpload/getCloudJob`, `ViewModels.kt:startSharedGpuTranscription` | Implemented client flow; worker availability/recovery is external |
| P1-07 | Multilingual transcription and automatic language detection | README states on-device language auto-detection; language selection passed for shared GPU jobs | Implemented by design; validate Hindi, English, Marathi and Hinglish with a golden set |
| P1-08 | Speaker diarisation and speaker-attributed transcript | `SpeakerTranscript.kt`, `MainActivity.kt:TranscriptBody` parse and render server segments/speakers | Client display implemented; diarisation generation/accuracy is server-side and unverified |
| P1-09 | Executive Summary | `MainActivity.kt:NotesContent` reads `executive_summary` | Implemented display; output quality not evaluated here |
| P1-10 | Key discussion points | `NotesContent` reads `key_points` | Implemented display |
| P1-11 | Decisions | `NotesContent` reads `decisions` | Implemented display |
| P1-12 | Action items with owner/due date when supported | `NotesContent` reads `action_items` | Basic display implemented; owner/due-date schema and extraction need validation |
| P1-13 | Open questions | Not rendered by original `NotesContent` | Implemented in this branch: render `open_questions` |
| P1-14 | Risks and blockers | Not rendered by original `NotesContent` | Implemented in this branch: render `risks_and_blockers`, with `risks` fallback; extraction still depends on notes service |
| P1-15 | Next steps | Not rendered by original `NotesContent` | Implemented in this branch: render `next_steps` |
| P1-16 | Timestamp-linked notes and transcript references | `NoteItems`, `NotesContent`, `MeetingTools.kt` | Implemented: notes with `start_seconds` show `[m:ss]` and, when the audio is on this phone, jump to that moment. Notes only carry timestamps if the notes service provides them; device validation pending |
| P1-17 | Full audio playback | `AudioPlayerController.kt` (platform `MediaPlayer`), `AudioLinks.kt`, `MeetingTools.kt:PlayerBar` | Implemented for recordings stored on this phone. Imported audio and recordings from other devices have no local file; remote playback would need signed URLs from the API. Device validation pending |
| P1-18 | Tap transcript timestamp to seek audio | `TimedTranscript`, `Playback.seekTargetMs` | Implemented: tapping a line starts playback 1.5 s before it (also works for transcripts without speaker labels, one line per segment). Device validation pending |
| P1-19 | Highlight active transcript segment during playback | `Playback.activeIndex`, `TimedTranscript` | Implemented. Device validation pending |
| P1-20 | Search within current transcript | `TranscriptFind.kt`, `FindBar`, `HistorySearch.kt` | Implemented: find in the open transcript with highlighted matches, a match count and "Show only matches"; plus library search across titles, on-device transcript text and workspace transcripts (owner-scoped `ilike` query) |
| P1-21 | Rename/delete meeting and associated data | `WorkspaceBackend.renameMeeting/deleteMeeting/deleteJob`, `LocalMeetingStore`, `MeetingViewModel` | Rename implemented for synced and on-device transcripts; a refused update is reported instead of shown as success. Delete implemented: job rows are removed first (no foreign key links `transcription_jobs.meeting_id` to `meetings`), then the meeting, which cascades to transcripts, artifacts and notes jobs. Failed or cancelled jobs can be removed on their own. Storage audio objects are **not** deleted; that needs an API endpoint |
| P1-22 | Basic sharing of meeting notes | `MeetingActionBar`, `TranscriptExport.kt` | Implemented: share as plain text with speaker names, timestamps and any loaded notes; very long transcripts are saved as files instead of risking an oversized share intent |
| P1-23 | Export transcript and notes | `MeetingActionBar` (system file picker) | Implemented: save Markdown or JSON to a user-selected file, or copy as Markdown |
| P1-24 | Markdown/PDF and structured JSON export | `TranscriptExport.markdown/json/plainText`, `TranscriptPdfExport.kt`, `MeetingTools.kt` | Markdown, structured JSON and paginated PDF export implemented. JSON keeps the earlier `meeting_id`, `title`, `transcript`, `notes`, `created_at` keys and adds `speakers` and timed `turns`; PDF uses the same canonical plain-text content. Device rendering/large-document validation pending |
| P1-25 | Local-only processing mode | `LocalTranscriber.kt`, `ViewModels.kt`, UI copy in `MainActivity.kt`; README says audio stays on device | Implemented; verify no audio network call in local mode |
| P1-26 | Optional shared-GPU processing | UI offers Local/Shared GPU mode; explicit upload button; `startSharedGpuTranscription` | Implemented explicit user-initiated path; verify consent copy and backend behaviour |
| P1-27 | Optional cloud processing with clear consent | `TranscribeContent` requires a per-upload confirmation that identifies original audio transfer, private workspace storage and configured GPU worker processing | Android consent UX implemented; backend retention/disclosure policy and end-to-end verification remain |
| P1-28 | Authentication, user isolation, encryption and RLS | `SessionManager` uses Keystore; `WorkspaceBackend.loadRecordings` filters by authenticated owner; README describes owner-scoped RLS | Android auth/session and owner filters implemented; database RLS, encryption at rest/in transit and cross-tenant tests require backend audit Row-level security was checked against the live project on 28 Nov 2026 (see "Live Supabase verification"). |
| P1-29 | Retention and temporary live-chunk cleanup | RLS inspection (below): clients may only INSERT and SELECT `live_chunks` | Pending: with no UPDATE/DELETE policy for clients, temporary chunk cleanup and retention must run server-side. Not implemented in this repository |
| P1-30 | Recording integrity checks and idempotent sync | `AtomicFiles.kt`, `LocalMeetingStore.kt`, `SyncWork.kt`; stable `client_event_id` in `WorkspaceBackend.syncOfflineTranscript` | Atomic transcript persistence and idempotent sync mechanism implemented; media integrity and duplicate/recovery tests still required |

## Changes made on this branch

Phase 1 foundation work (earlier commits):

1. Transcript search in `TranscriptBody`, expanded `NotesContent` (open questions, risks and blockers, next steps) and Share / JSON export actions.

Phase 2 library tools, stacked on top (this integration):

2. **Playback:** `AudioPlayerController` (platform `MediaPlayer`), transcript-to-audio links (`AudioLinkStore`), tap a transcript line or timestamped note to seek, active line highlighted.
3. **Find and search:** in-transcript find now highlights matches and can show only matching lines (it replaces the earlier filter-only view); library search covers titles, on-device transcript text and workspace transcripts.
4. **Export:** one menu with share as text, copy as Markdown, save Markdown file, share as JSON and save JSON file. Exports include speaker names, timestamps and every notes section. The earlier JSON keys are kept.
5. **Rename, delete, edit:** rename (synced and on-device), delete with job and local cleanup, remove failed jobs, edit unsynced on-device text, speaker display names persisted in the synced transcript's `speakers` JSON (with device-local fallback). This replaces the earlier rename, which could not detect a refused update.
6. **Cancel** for shared-GPU uploads and queued or running jobs.
7. Notes sections accept both object and plain-string entries, and the legacy `risks` key.
8. **PDF export:** added a paginated Android `PdfDocument` exporter and a Save PDF action alongside Markdown and JSON.
9. **Speaker-name persistence:** synced-meeting aliases are stored in the owner-scoped transcript `speakers` JSON as `display_name`, preserving provider labels; device-local aliases remain the offline fallback.
10. **Cloud upload consent:** Shared GPU uploads now require a per-upload confirmation explaining that original audio leaves the device, is stored in the private workspace and is processed by the configured GPU worker.

## Remaining Phase 1 work

### Android implementation still required
- Device validation of everything marked "Implemented" above; the Phase 2 work was syntax-checked and its pure logic unit-tested, but not built or run on a device.
- P1-21: delete of the audio object in Storage (needs an API endpoint, together with live-chunk cleanup).
- P1-24: standalone PDF exporter.
- Editing server-side transcript text still needs a dedicated update operation. Speaker display names now persist in the existing owner-scoped `transcripts.speakers` JSON using a separate `display_name` field; provider labels and diarisation metadata are preserved.

### Backend/security acceptance required
- P1-03: offline audio upload recovery (not just transcript sync).
- P1-06: shared GPU worker health, retries and recovery.
- P1-07/P1-08: multilingual and diarisation quality validation.
- P1-09–P1-15: validate actual notes-generation schema and extraction quality.
- P1-27: explicit cloud-processing disclosure/consent policy.
- P1-28/P1-29: RLS is verified (see "Live Supabase verification"); still validate encryption, Storage bucket policies, retention and chunk cleanup, and add cross-tenant tests.
- P1-30: end-to-end recording integrity and idempotency tests.

## Live Supabase verification

Checked read-only on 28 November 2026 against the project this app connects to ("Whisper"), by querying `pg_policies`, `pg_class` and `pg_constraint`:

- Row-level security is **enabled on all 8 application tables**.
- `meetings`, `transcripts`, `transcription_jobs`, `meeting_artifacts` and `device_sync_events` each have one `ALL` policy limited to the owner (`auth.uid() = owner_id`, for both `USING` and `WITH CHECK`).
- `live_sessions`: owners may INSERT (status `recording`), SELECT, and UPDATE only from `recording` to `closed`.
- `live_chunks`: owners may INSERT (a tightly constrained row, including the exact `live/<user>/<session>/<index>.aac` object path) and SELECT. There is **no** UPDATE or DELETE policy for clients.
- `meeting_notes_jobs`: RLS is enabled with **no policies**, so clients cannot read or write it; only the API and workers (service role) can. (An earlier version of this audit said policies existed for notes jobs; that was not accurate.)
- Foreign keys: `transcripts`, `meeting_artifacts` and `meeting_notes_jobs` reference `meetings` with `ON DELETE CASCADE`. `transcription_jobs` has **no** foreign key to `meetings`, so deleting a meeting leaves its job rows behind unless they are deleted separately.
- `meetings.title` is limited to 500 characters; `transcription_jobs.status` allows `cancelled`.

Not verified: Storage bucket policies, encryption settings, and behaviour of the API (not part of this repository).

The GitHub source was inspected and the changes were committed to the new branch. This environment does not provide the repository's Android SDK/NDK/Gradle build runtime or a connected Android device, so **no claim is made that Gradle tests, lint, APK assembly, or device acceptance have passed**. Run the repository CI workflow and real-device checks before merging.

## Acceptance commands

```bash
./gradlew testDebugUnitTest
./gradlew lintDebug
./gradlew assembleDebug
```

Also verify the new search, notes sections and share/export flow on a physical Android device, including empty notes, absent optional arrays, large transcripts and offline conditions.
