# Supabase backend

Project `earshot` (ref `drdpgrdewxcquswuuzfw`). Sync, accounts and the server-side work for the Android
app and the web player.

## Migrations

`migrations/` holds the schema, in order (`001_sync.sql` to `009_sync_safety.sql`). Each was run once in
the dashboard's SQL Editor (paste the whole file, Run) and is safe to run again. `005_schedule_new_episodes.sql`
has two placeholders to fill in first (`<PROJECT_URL>` and `<CRON_SECRET>`).

Every table has row-level security (only the owner, `to authenticated`). Sync is "last change wins" per row:
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
