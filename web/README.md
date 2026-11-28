# Web player

React + TypeScript (Vite), live at https://jpollack40.github.io/earshot/ (GitHub Pages, deployed by
`.github/workflows/web-pages.yml` on every push to main that touches `web/` or `shared/`).

It uses the same Supabase data as the Android app, so the library, queue, played state, positions,
playback settings, transcripts, bookmarks and playlists follow you between phone and browser.
Feeds are read through the `feed-proxy` edge function. Smart Speed and Voice Boost are Web Audio
approximations and need the audio host to allow CORS.

Local build (Node LTS):

```
npm install
npm run build
```

`npm run dev` starts a local server, and `npm test` runs the tests, including the shared test vectors
in `../shared/test-vectors/`.
