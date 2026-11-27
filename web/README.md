# Web player (phase 5b)

React + TypeScript (Vite), live at https://jpollack40.github.io/earshot/ (GitHub Pages, deployed by
`.github/workflows/web-pages.yml` on every push to main that touches `web/`).

It uses the same Supabase data as the Android app, so subscriptions, pins, the queue, New Episodes,
played state, positions and playback settings follow you between phone and browser.

Local build on the VM: `npm install`, then `npm run build` (typecheck + build) or `npm run dev`.
