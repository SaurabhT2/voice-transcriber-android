# Backend requirements for the Phase 2 library tools

The Android app talks to the shared Supabase project with the signed-in user's access token, so every
statement below is subject to **row-level security (RLS)**. Nothing here needs a service-role key.

> The schema and policies live in [whisper-platform-api](https://github.com/SaurabhT2/whisper-platform-api),
> which was **not reviewed** when this was written. The SQL is a starting point to compare against what
> already exists, not a verified migration.

## What the app calls

| Feature | Request | Needs |
|---|---|---|
| Rename | `PATCH /rest/v1/meetings?id=eq.{id}&owner_id=eq.{user}` body `{"title": …}` | UPDATE policy on `meetings` |
| Delete meeting | `DELETE /rest/v1/transcription_jobs?meeting_id=eq.{id}&owner_id=eq.{user}`, then `DELETE /rest/v1/meetings?id=eq.{id}&owner_id=eq.{user}` | DELETE policies on both tables; the meeting's transcripts and notes must cascade (or be deletable) |
| Remove failed job | `DELETE /rest/v1/transcription_jobs?id=eq.{id}&owner_id=eq.{user}` | DELETE policy on `transcription_jobs` |
| Transcript search | `GET /rest/v1/transcripts?select=meeting_id&owner_id=eq.{user}&content=ilike.*term*&limit=100` | SELECT policy on `transcripts` |
| Cancel | `POST /api/v1/transcriptions/{id}/cancel` | Already part of the API |

The app sends `Prefer: return=representation` and treats an **empty result as "not permitted"**, so a missing
policy surfaces as a clear message ("could not be renamed / deleted") instead of silently doing nothing.
Search is the exception: a refused request just yields no server matches, and title plus on-device matching
still works.

## Example policies (verify against the existing schema first)

```sql
create policy meetings_update_own on public.meetings
  for update using (owner_id = auth.uid()) with check (owner_id = auth.uid());

create policy meetings_delete_own on public.meetings
  for delete using (owner_id = auth.uid());

create policy transcription_jobs_delete_own on public.transcription_jobs
  for delete using (owner_id = auth.uid());

create policy transcripts_select_own on public.transcripts
  for select using (owner_id = auth.uid());
```

Foreign keys from `transcripts`, notes and notes jobs to `meetings` should be `on delete cascade`;
otherwise a meeting delete fails with a foreign-key error, which the app reports to the user.

Optional, for fast search on large histories:

```sql
create extension if not exists pg_trgm;
create index if not exists transcripts_content_trgm on public.transcripts using gin (content gin_trgm_ops);
```

## Known gaps to close in the API

- **Audio objects are not deleted.** Removing a meeting leaves its file in the private `meeting-audio`
  bucket. A server-side delete endpoint that removes the object, derived rows and any live-session chunks in
  one step should replace the client-side DELETEs.
- **Server transcripts are not editable.** Editing is limited to on-device transcripts that have not synced.
  Editing a synced transcript needs an update endpoint.
- **Speaker names are device-local.** Persisting them server-side needs an endpoint (or a column) for per-speaker labels.
