// Listening history (phase 7), kept for a future Stats page: same table and rules as the phone
// (android/.../playback/ListeningLog.kt). Each stretch of listening (play to pause, a change of
// episode, or 30 minutes) becomes one row in `listens` with device "web". Stretches under 5 s are
// dropped. Rows only go up; nothing here reads them back yet.

import * as player from "./player";
import * as store from "./store";
import { supabase } from "./supabase";

interface Listen {
  id: string;
  episode_id: string;
  feed_url: string;
  podcast_title: string;
  episode_title: string;
  started_at: number;
  ended_at: number;
  listened_ms: number;
  from_ms: number;
  to_ms: number;
  speed: number;
  device: "web";
  changed_at: number;
}

const MAX_STRETCH_MS = 30 * 60 * 1000;
const MIN_STRETCH_MS = 5_000;

let current: Listen | null = null;
let lastTick = 0;
const pending: Listen[] = [];
let timer: number | undefined;

function tick() {
  const now = Date.now();
  const elapsed = lastTick ? now - lastTick : 0;
  lastTick = now;
  const p = player.snapshot();
  const e = p.episode;
  // Background tabs run timers as rarely as once a minute, so gaps up to 2 minutes still count.
  if (!p.playing || !e || elapsed <= 0 || elapsed > 120_000) {
    if (!p.playing) finish();
    return;
  }
  if (current && (current.episode_id !== e.id || now - current.started_at > MAX_STRETCH_MS)) finish();
  const speed = player.currentSpeed();
  if (!current) {
    const pod = store.getState().podcasts.get(e.feed_url);
    current = {
      id: crypto.randomUUID(),
      episode_id: e.id,
      feed_url: e.feed_url,
      podcast_title: pod?.title ?? "",
      episode_title: e.title,
      started_at: now - elapsed,
      ended_at: now,
      listened_ms: 0,
      from_ms: Math.max(0, Math.round(p.positionMs - elapsed * speed)),
      to_ms: Math.round(p.positionMs),
      speed,
      device: "web",
      changed_at: 0,
    };
  }
  current.ended_at = now;
  current.listened_ms += elapsed;
  current.to_ms = Math.round(p.positionMs);
  current.speed = speed;
}

/** The current stretch is over: queue it for upload. */
function finish() {
  const c = current;
  current = null;
  if (!c || c.listened_ms < MIN_STRETCH_MS) return;
  pending.push({ ...c, changed_at: Date.now() });
  void upload();
}

async function upload() {
  if (!pending.length) return;
  const rows = pending.splice(0, pending.length);
  const { error } = await supabase.from("listens").upsert(rows, { onConflict: "user_id,id" });
  if (error && !/does not exist|could not find the|schema cache/i.test(error.message)) {
    pending.unshift(...rows); // try again with the next one
  }
}

/** Starts logging. Called once the user is signed in; returns a stop function. */
export function startListenLog(): () => void {
  window.clearInterval(timer);
  lastTick = 0;
  timer = window.setInterval(tick, 1000);
  const onHide = () => finish();
  window.addEventListener("pagehide", onHide);
  return () => {
    finish();
    window.clearInterval(timer);
    window.removeEventListener("pagehide", onHide);
  };
}
