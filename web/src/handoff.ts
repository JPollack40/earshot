// Phase 20 (C5): hand playback between the web player and the phone (013_now_playing.sql).
// Starting to play here takes playback; while playing, the web player reports every 10 s. When the
// phone (or another device) takes over, Realtime tells us at once and playback pauses here. When
// another device is playing (or just paused), a banner offers "Continue here".
import { useSyncExternalStore } from "react";
import { supabase } from "./supabase";
import * as player from "./player";
import * as store from "./store";
import type { EpisodeRow } from "./types";

export const DEVICE = "web";
const REPORT_MS = 10_000;
const PLAYING_FRESH_MS = 2 * 60_000;
const PAUSED_FRESH_MS = 12 * 60 * 60_000;

export interface Holder {
  device: string;
  device_name: string;
  episode_id: string;
  position_ms: number;
  playing: boolean;
  updated_at: string;
}

let holder: Holder | null = null;
let dismissed: string | null = null; // the holder (device + claim) the banner was closed for
let available = true;
let lastClaimSentAt = 0;
const listeners = new Set<() => void>();

function setHolder(h: Holder | null) {
  holder = h;
  for (const l of listeners) l();
}

/** Whether to offer "Continue here" for [h] (pure, for tests). */
export function shouldOffer(h: Holder | null, nowMs: number, playingHereId: string | null): boolean {
  if (!h || h.device === DEVICE) return false;
  if (playingHereId && playingHereId === h.episode_id) return false;
  const age = nowMs - Date.parse(h.updated_at);
  if (!Number.isFinite(age) || age < 0) return false;
  return h.playing ? age <= PLAYING_FRESH_MS : age <= PAUSED_FRESH_MS;
}

export function holderLabel(h: Holder): string {
  if (h.device === DEVICE) return "the web player";
  return h.device_name || "your phone";
}

export interface Offer {
  holder: Holder;
  episode: EpisodeRow;
}

function currentOffer(): Offer | null {
  const p = player.snapshot();
  const h = holder;
  if (!h || !shouldOffer(h, Date.now(), p.playing ? (p.episode?.id ?? null) : null)) return null;
  if (dismissed === `${h.device}|${h.episode_id}|${h.playing}`) return null;
  const episode = store.episode(h.episode_id);
  return episode ? { holder: h, episode } : null;
}

let lastOffer: Offer | null = null;
function offerSnapshot(): Offer | null {
  const o = currentOffer();
  // Same object while nothing changed, as useSyncExternalStore needs.
  if (o?.holder === lastOffer?.holder && o?.episode === lastOffer?.episode) return lastOffer;
  lastOffer = o;
  return o;
}

export function useHandoffOffer(): Offer | null {
  return useSyncExternalStore(
    (l) => {
      listeners.add(l);
      return () => listeners.delete(l);
    },
    offerSnapshot,
  );
}

export function dismissOffer() {
  if (holder) dismissed = `${holder.device}|${holder.episode_id}|${holder.playing}`;
  for (const l of listeners) l();
}

export function continueHere(o: Offer) {
  dismissed = null;
  player.playAt(o.episode, o.holder.position_ms); // play() reports the claim
}

async function report(claim: boolean) {
  if (!available) return;
  const p = player.snapshot();
  const e = p.episode;
  if (!e) return;
  const sentAt = Date.now();
  if (claim) lastClaimSentAt = sentAt;
  const { data, error } = await supabase.rpc("handoff", {
    p_device: DEVICE,
    p_name: "Web player",
    p_episode: e.id,
    p_position: Math.round(p.positionMs),
    p_playing: claim ? true : p.playing,
    p_claim: claim,
  });
  if (error) {
    // The function isn't on the server yet (013 not run): stop until the page reloads.
    if (error.code === "PGRST202" || error.code === "PGRST205" || error.code === "42883") available = false;
    return;
  }
  if (sentAt < lastClaimSentAt) return; // a later claim is on its way
  const h = data as Holder | null;
  if (!h) return;
  setHolder(h);
  if (h.device !== DEVICE && player.snapshot().playing && player.snapshot().episode?.id === e.id) player.pause();
}

/** Starts handoff for the signed-in person; returns a stop function. */
export function startHandoff(userId: string): () => void {
  player.hooks.onPlayStart = () => void report(true);
  player.hooks.onPause = () => void report(false);
  const timer = window.setInterval(() => {
    if (player.snapshot().playing) void report(false);
    else for (const l of listeners) l(); // let "fresh" age out
  }, REPORT_MS);

  void supabase
    .from("now_playing")
    .select("*")
    .maybeSingle()
    .then(({ data, error }) => {
      if (error) {
        if (error.code === "PGRST205" || error.code === "42P01") available = false;
        return;
      }
      setHolder((data as Holder | null) ?? null);
    });

  const channel = supabase
    .channel(`now_playing:${userId}`)
    .on("postgres_changes", { event: "*", schema: "public", table: "now_playing", filter: `user_id=eq.${userId}` }, (payload) => {
      const h = payload.new as Holder | undefined;
      if (!h || !h.device) return;
      setHolder(h);
      // Another device started: pause here.
      if (h.device !== DEVICE && h.playing && player.snapshot().playing) player.pause();
    })
    .subscribe();

  return () => {
    window.clearInterval(timer);
    player.hooks.onPlayStart = () => {};
    player.hooks.onPause = () => {};
    void supabase.removeChannel(channel);
  };
}
