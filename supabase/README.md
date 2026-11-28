# Supabase backend

Project `earshot` (ref `drdpgrdewxcquswuuzfw`). Sync, accounts and the server-side work for the Android
app and the web player.

## Migrations

`migrations/` holds the schema, in order (`001_sync.sql` to `009_sync_safety.sql`). Each was run once in
the dashboard's SQL Editor (paste the whole file, Run) and is safe to run again. `005_schedule_new_episodes.sql`
has two placeholders to fill in first (`<PROJECT_URL>` and `<CRON_SECRET>`).

Every table has row-level security (only the owner, `to authenticated`). Since 2026-10-30 Supabase no longer
exposes new tables to the Data API by itself, so **every new SQL file grants explicitly** (`grant select, insert,
… on table … to authenticated`) next to its policies, as `010_safe_updates.sql` does. Sync is "last change wins" per row:
each row carries `changed_at` and a `rev`, and a trigger rejects writes older than the stored row
(with a few exceptions in 009, such as a played episode never going back to unplayed).

## Edge functions

The files in `functions/` are the source of truth (the dashboard editor keeps no history). Deploy through
Edge Functions > the function > Code, paste the file, Deploy updates. Secrets live under Edge Functions > Secrets.

| Function | What | Secrets |
|---|---|---|
| `podcast-search` | Podcast Index search for the Add screen | `PODCASTINDEX_KEY`, `PODCASTINDEX_SECRET` |
| `feed-proxy` | Fetches feeds and chapters for the web player (browsers can't read most feeds directly) | none |
| `transcribe` | Transcribes an episode on demand with Deepgram Nova-3 | `DEEPGRAM_API_KEY` |
| `ai` | Gemini from the transcript: summary, chapters, ads, Ask about this episode, bookmark titles | `GEMINI_API_KEY`, optional `GEMINI_MODEL`, `GEMINI_FALLBACK_MODEL` |
| `browse` | The Browse page, categories and "You might like" | Podcast Index and Gemini keys |
| `new-episodes` | Runs every 15 minutes (pg_cron): new episodes for every subscribed feed, push notifications, leftover bookmark titles | Podcast Index and Gemini keys, `FCM_SERVICE_ACCOUNT`, `NEW_EPISODES_SECRET` |

### Phase 16: switches and keys

- `app_config` (010): Serena's switches, read by `ai`, `transcribe`, `browse` and `new-episodes` on every call.
  `gemini_model` / `gemini_fallback_model` there win over the secrets. With nothing set, the fallback model is
  `gemini-3.5-flash-lite` (`gemini-3.1-flash-lite` shuts down from 2027-05-07).
- **Verify JWT is off** for `ai`, `transcribe`, `browse`, `feed-proxy`, `podcast-search` and `new-episodes`.
  The built-in check only understands the legacy JWT keys; each function checks the sign-in (or its secret)
  itself.
- No function accepts the legacy `anon` / `service_role` keys from callers any more. `browse` and `new-episodes`
  use the new secret key (`SUPABASE_SECRET_KEYS`, provided by Supabase once a secret key exists under Project
  Settings > API Keys) and fall back to the legacy service_role key only when there is none, so check a secret
  key exists before deactivating the legacy keys.

## Tests

`tests/` checks the sync rules in an in-memory Postgres (PGlite), no Supabase project needed:

```
cd supabase/tests
npm install
npm test
```

## Backups

A GitHub Actions workflow dumps and encrypts the database every Monday; see "Server backup" in
[RELEASES.md](../RELEASES.md).
