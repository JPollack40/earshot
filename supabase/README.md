# Supabase backend

Project `earshot` (ref `drdpgrdewxcquswuuzfw`).

## Edge functions

- `functions/podcast-search` (phase 5a, deployed): Podcast Index search. It signs requests with the API key and secret, which are stored as the function secrets `PODCASTINDEX_KEY` and `PODCASTINDEX_SECRET`, so neither is ever on a device.
  Deployed through the dashboard editor (Edge Functions > Deploy a new function > Via Editor). To update it, paste this file into the editor and choose Deploy updates. This file is the source of truth.
- Transcription proxy for the cloud transcription API (phase 6, not built yet).

## Planned (phase 5b)

- Postgres tables for subscriptions, episode state, queue, bookmarks and rules, with row-level security
- Auth and two-way sync
