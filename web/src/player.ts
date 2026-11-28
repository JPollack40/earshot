import { useSyncExternalStore } from "react";
import type { EpisodeRow, PlaybackDefaults } from "./types";
import { adSkipTarget, currentAd, type AdSpan } from "./ai";

// Two <audio> elements. "fx" is cross-origin and feeds a Web Audio chain:
//   Voice Boost = compressor + make-up gain, Smart Speed = speed up through silences.
// Hosts that don't allow cross-origin access (CORS) fail on "fx", so the episode moves to "plain",
// which plays with speed only (effects shown as unavailable). Two elements are needed because once an
// element feeds Web Audio, audio it plays without CORS comes out silent.

export interface PlayerState {
  episode: EpisodeRow | null;
  playing: boolean;
  buffering: boolean;
  positionMs: number;
  durationMs: number;
  effectsAvailable: boolean; // false when the host doesn't allow Web Audio processing
  sleepEndsAt: number | null; // ms, or null
  sleepAtEnd: boolean;
  error: string | null;
  /** Everything known about the last error, for the Copy button (and kept in the error log). */
  errorDetails: string | null;
  /** After a big jump: where "Back to mm:ss" returns to, shown for a few seconds (phase 10). */
  jumpBack: { episodeId: string; fromMs: number } | null;
}

let state: PlayerState = {
  episode: null,
  playing: false,
  buffering: false,
  positionMs: 0,
  durationMs: 0,
  effectsAvailable: true,
  sleepEndsAt: null,
  sleepAtEnd: false,
  error: null,
  errorDetails: null,
  jumpBack: null,
};
const listeners = new Set<() => void>();
function set(patch: Partial<PlayerState>) {
  state = { ...state, ...patch };
  for (const l of listeners) l();
}
/** The current state, outside React. */
export const snapshot = (): PlayerState => state;
/** The playback speed in use (for the listening history). */
export const currentSpeed = (): number => prefs.speed;

export function usePlayer(): PlayerState {
  return useSyncExternalStore(
    (l) => {
      listeners.add(l);
      return () => listeners.delete(l);
    },
    () => state,
  );
}

const fx = new Audio();
fx.preload = "auto";
fx.crossOrigin = "anonymous";
const plain = new Audio();
plain.preload = "auto";
/** The element playing the current episode. */
let audio: HTMLAudioElement = fx;
/** Whether the user wants it playing (so a switch to the plain element keeps going). */
let wantPlaying = false;
let prefs: PlaybackDefaults = { speed: 1, smartSpeed: false, voiceBoost: false, skipBackMs: 15_000, skipForwardMs: 30_000 };

// Callbacks into the app (wired in App.tsx).
export const hooks = {
  onPosition: (_id: string, _ms: number, _soon: boolean) => {},
  onDuration: (_id: string, _ms: number) => {},
  /** [stop]: the sleep timer was set to the end of this episode, so don't start the next one. */
  onEnded: (_e: EpisodeRow, _stop: boolean) => {},
  /** Phase 20 (C5): a play or pause asked for here (handoff). */
  onPlayStart: () => {},
  onPause: () => {},
};

// ---- Web Audio chain (built on first play, once the user has interacted) ----
let ctx: AudioContext | null = null;
let source: MediaElementAudioSourceNode | null = null;
let compressor: DynamicsCompressorNode;
let makeup: GainNode;
let analyser: AnalyserNode;
let silentSince = 0;
let skipping = false;

function buildGraph() {
  if (ctx) return;
  ctx = new AudioContext();
  source = ctx.createMediaElementSource(fx);
  analyser = ctx.createAnalyser();
  analyser.fftSize = 2048;
  compressor = ctx.createDynamicsCompressor();
  makeup = ctx.createGain();
  source.connect(analyser);
  applyVoiceBoost();
}

function applyVoiceBoost() {
  if (!ctx || !source) return;
  source.disconnect();
  compressor.disconnect();
  makeup.disconnect();
  source.connect(analyser);
  if (prefs.voiceBoost) {
    // Lifts quiet speech and tames loud parts: about what the phone's Voice Boost does.
    compressor.threshold.value = -30;
    compressor.knee.value = 10;
    compressor.ratio.value = 4;
    compressor.attack.value = 0.01;
    compressor.release.value = 0.3;
    makeup.gain.value = 2.2; // about +7 dB
    source.connect(compressor).connect(makeup).connect(ctx.destination);
  } else {
    source.connect(ctx.destination);
  }
}

// Smart Speed: while the level stays below the threshold for more than 0.15 s, play much faster.
const buf = new Float32Array(2048);
window.setInterval(() => {
  if (!ctx || audio !== fx || !prefs.smartSpeed || audio.paused) {
    if (skipping) {
      skipping = false;
      audio.playbackRate = prefs.speed;
    }
    return;
  }
  analyser.getFloatTimeDomainData(buf);
  let sum = 0;
  for (let i = 0; i < buf.length; i++) sum += buf[i] * buf[i];
  const rms = Math.sqrt(sum / buf.length);
  const now = performance.now();
  if (rms < 0.01) {
    if (!silentSince) silentSince = now;
    if (!skipping && now - silentSince > 150) {
      skipping = true;
      audio.playbackRate = Math.min(4, prefs.speed * 3);
    }
  } else {
    silentSince = 0;
    if (skipping) {
      skipping = false;
      audio.playbackRate = prefs.speed;
    }
  }
}, 40);

// ---- element events (only the active element's count) ----
let lastSaved = 0;

/** Why an <audio> element failed, in words. */
function mediaErrorText(el: HTMLAudioElement): string {
  switch (el.error?.code) {
    case 2:
      return "This episode couldn't be played: the network failed while loading it.";
    case 3:
      return "This episode couldn't be played: the audio file seems to be damaged.";
    case 4:
      return "This episode couldn't be played: its server refused the request or the file is missing. Try again later, or play it on the phone.";
    default:
      return "This episode couldn't be played.";
  }
}

for (const el of [fx, plain]) {
  const active = () => el === audio;
  el.addEventListener("timeupdate", () => {
    if (!active()) return;
    const ms = el.currentTime * 1000;
    set({ positionMs: ms });
    if (state.episode && Math.abs(ms - lastSaved) > 5000) {
      lastSaved = ms;
      hooks.onPosition(state.episode.id, ms, false);
    }
    if (state.sleepEndsAt && Date.now() >= state.sleepEndsAt) {
      pause();
      set({ sleepEndsAt: null });
    }
  });
  el.addEventListener("durationchange", () => {
    if (active() && isFinite(el.duration)) {
      set({ durationMs: el.duration * 1000 });
      if (state.episode) hooks.onDuration(state.episode.id, el.duration * 1000);
    }
  });
  el.addEventListener("playing", () => active() && set({ playing: true, buffering: false, error: null, errorDetails: null }));
  el.addEventListener("pause", () => {
    if (!active()) return;
    set({ playing: false });
    if (state.episode) hooks.onPosition(state.episode.id, el.currentTime * 1000, true);
  });
  el.addEventListener("waiting", () => active() && set({ buffering: true }));
  el.addEventListener("ended", () => {
    if (!active()) return;
    const e = state.episode;
    wantPlaying = false;
    set({ playing: false });
    if (!e) return;
    const stop = state.sleepAtEnd;
    if (stop) set({ sleepAtEnd: false });
    hooks.onEnded(e, stop);
  });
  el.addEventListener("error", () => {
    if (!active() || !el.getAttribute("src")) return;
    // The host refused cross-origin access: play on the plain element, without effects.
    if (el === fx && state.episode) {
      const at = state.positionMs;
      fx.removeAttribute("src");
      fx.load();
      audio = plain;
      set({ effectsAvailable: false });
      plain.src = state.episode.audio_url;
      plain.currentTime = at / 1000;
      plain.playbackRate = prefs.speed;
      if (wantPlaying) void plain.play().catch(playFailed);
      return;
    }
    wantPlaying = false;
    fail(mediaErrorText(el), { "Media error": el.error ? `code ${el.error.code}${el.error.message ? `, ${el.error.message}` : ""}` : "none", "Tried with effects first": el === plain ? "yes, then without" : "no" });
  });
}

/** Shows an error under the player and records it, with the details needed to look into it. */
function fail(message: string, extra: Record<string, unknown> = {}) {
  if (state.error === message) return; // already showing (the error event and play() can both report it)
  const e = state.episode;
  const details = [
    `Earshot web player error, ${new Date().toISOString()}`,
    `Message: ${message}`,
    e ? `Episode: ${e.title} (id ${e.id})` : null,
    e ? `Feed: ${e.feed_url}` : null,
    e ? `Audio: ${e.audio_url}` : null,
    `Element: ${audio === fx ? "cross-origin (effects)" : "plain (no effects)"}, network state ${audio.networkState}, ready state ${audio.readyState}`,
    ...Object.entries(extra).map(([k, v]) => `${k}: ${typeof v === "string" ? v : JSON.stringify(v)}`),
    `Browser: ${navigator.userAgent}`,
  ]
    .filter(Boolean)
    .join("\n");
  console.warn(details);
  logError(details);
  set({ error: message, errorDetails: details, playing: false, buffering: false });
}

const ERROR_LOG = "earshot.errorLog";

/** Keeps the last 30 errors in this browser (localStorage), newest first. */
function logError(details: string) {
  try {
    const log = JSON.parse(localStorage.getItem(ERROR_LOG) ?? "[]") as string[];
    localStorage.setItem(ERROR_LOG, JSON.stringify([details, ...log].slice(0, 30)));
  } catch {
    // Storage blocked: the Copy button still has it.
  }
}

/** The saved error log, newest first. */
export function errorLog(): string[] {
  try {
    return JSON.parse(localStorage.getItem(ERROR_LOG) ?? "[]") as string[];
  } catch {
    return [];
  }
}

/** play() was refused. An interrupted play (a new source loading) isn't an error. */
function playFailed(err: unknown) {
  if (err instanceof DOMException && err.name === "AbortError") return;
  if (err instanceof DOMException && err.name === "NotAllowedError") {
    fail("The browser blocked playback. Click Play to start it.", { Reason: String(err) });
    return;
  }
  // NotSupportedError: the element's own "error" event explains (and falls back), unless it already fired.
  if (err instanceof DOMException && err.name === "NotSupportedError") {
    if (audio === plain && audio.error) fail(mediaErrorText(audio), { Reason: String(err) });
    return;
  }
  fail(`This episode couldn't be played: ${String(err)}`, { Reason: String(err) });
}

// ---- Rewind after a pause (phase 13; same rule as the phone's ResumeRewind) ----

let resumeRewind = true;
let pausedAt = 0;
let pausedId: string | null = null;

export function setResumeRewind(on: boolean) {
  resumeRewind = on;
}

/** How far to back up after [pausedForMs]: nothing under 1 s, 2 s under a minute, 5 s under an hour, then 10 s. */
export function rewindMs(pausedForMs: number): number {
  if (pausedForMs < 1_000) return 0;
  if (pausedForMs < 60_000) return 2_000;
  if (pausedForMs < 60 * 60_000) return 5_000;
  return 10_000;
}

// ---- controls ----

/** Loads an episode (from its saved position) and, if [autoplay], starts it. */
export function load(e: EpisodeRow, autoplay: boolean) {
  if (state.episode?.id === e.id) {
    if (autoplay) play();
    return;
  }
  if (state.episode && !audio.paused) hooks.onPosition(state.episode.id, audio.currentTime * 1000, true);
  // Always try the cross-origin element first; its error handler moves to the plain one.
  audio.pause();
  if (audio === plain) {
    plain.removeAttribute("src");
    plain.load();
  }
  audio = fx;
  wantPlaying = autoplay;
  audio.src = e.audio_url;
  pausedAt = 0;
  // Back up a little when coming back to an episode after a break (phase 13).
  const saved = e.played ? 0 : e.position_ms;
  const start = resumeRewind && saved > 0 && e.last_played_at > 0 ? Math.max(0, saved - rewindMs(Date.now() - e.last_played_at)) : saved;
  audio.currentTime = start / 1000;
  lastSaved = start;
  set({ episode: e, positionMs: start, durationMs: e.duration_ms, effectsAvailable: true, error: null, errorDetails: null, playing: false, jumpBack: null });
  audio.playbackRate = prefs.speed;
  updateMediaSession(e);
  if (autoplay) play();
}

export function play() {
  if (!state.episode) return;
  wantPlaying = true;
  if (pausedAt && pausedId === state.episode.id && resumeRewind && audio.paused) {
    const back = rewindMs(Date.now() - pausedAt);
    if (back > 0) rawSeek(Math.max(0, audio.currentTime * 1000 - back));
  }
  pausedAt = 0;
  // After a failure, try again from the start of the chain (the host may be back, or allow CORS now).
  if (audio.error) {
    const at = state.positionMs;
    if (audio === plain) {
      plain.removeAttribute("src");
      plain.load();
    }
    audio = fx;
    fx.src = state.episode.audio_url;
    fx.currentTime = at / 1000;
    fx.playbackRate = prefs.speed;
    set({ effectsAvailable: true });
  }
  if (audio === fx) {
    buildGraph();
    void ctx?.resume();
  }
  set({ buffering: true, error: null, errorDetails: null });
  void audio.play().catch(playFailed);
  hooks.onPlayStart();
}

export function pause() {
  wantPlaying = false;
  audio.pause();
  pausedAt = Date.now();
  pausedId = state.episode?.id ?? null;
  hooks.onPause();
}

export function toggle() {
  if (audio.paused) play();
  else pause();
}

function rawSeek(ms: number) {
  // A seek while paused is where the listener chose to carry on: no rewind from there.
  if (audio.paused) pausedAt = 0;
  audio.currentTime = Math.max(0, ms / 1000);
  set({ positionMs: ms });
}

const JUMP_MIN_MS = 30_000;
const JUMP_SHOW_MS = 8_000;
let jumpTimer = 0;

/**
 * Seeks within the loaded episode. A jump of 30 s or more (chapter, transcript line, bookmark,
 * scrubber) offers "Back to mm:ss" for a few seconds (phase 10). Skip buttons don't count.
 */
export function seekTo(ms: number) {
  const from = audio.currentTime * 1000;
  if (state.episode && Math.abs(ms - from) >= JUMP_MIN_MS) {
    const jump = { episodeId: state.episode.id, fromMs: from };
    set({ jumpBack: jump });
    window.clearTimeout(jumpTimer);
    jumpTimer = window.setTimeout(() => state.jumpBack === jump && set({ jumpBack: null }), JUMP_SHOW_MS);
  }
  rawSeek(ms);
}

/** "Back to mm:ss": returns to where the last big jump started. */
export function undoJump() {
  const j = state.jumpBack;
  set({ jumpBack: null });
  if (j && state.episode?.id === j.episodeId) rawSeek(j.fromMs);
}

// ---- Skipped chapters and ad trim (phase 10), set by the app for the loaded episode ----

export interface SkipRules {
  episodeId: string;
  /** Chapter starts and ends (ms), in order. */
  chapters: { startMs: number; endMs?: number | null }[];
  /** Start times of unticked chapters. */
  skipped: number[];
  /** The podcast's ad trim: "start", "end" or null. */
  adTrim: string | null;
  /** The length the feed states (ms), for the ad trim. */
  feedDurationMs: number;
  /** Phase 11: ads found in the transcript, and what to do with them ("auto", "button" or "off"). */
  ads?: AdSpan[];
  adSkip?: string;
}
let rules: SkipRules | null = null;
/** Ads (by start) already skipped automatically for the loaded episode; going back into one plays it. */
let adsSkipped = { episodeId: "", starts: new Set<number>() };
export function setSkipRules(r: SkipRules | null) {
  rules = r;
}

/** Same rule as the phone's AdTrim.extraMs: 10 s to 20 min longer than stated. */
export function adExtraMs(feedMs: number, realMs: number): number {
  if (feedMs <= 0 || realMs <= 0) return 0;
  const d = realMs - feedMs;
  return d >= 10_000 && d <= 20 * 60_000 ? d : 0;
}

/** Same rule as the phone's SkippedChapters.skipTarget. */
export function skipTarget(chapters: SkipRules["chapters"], skipped: number[], pos: number, duration: number): number | null {
  if (!skipped.length || !chapters.length) return null;
  const isSkipped = (c: { startMs: number }) => skipped.some((s) => Math.abs(s - c.startMs) <= 1000);
  const end = (i: number) => chapters[i].endMs ?? chapters[i + 1]?.startMs ?? (duration > 0 ? duration : Infinity);
  let i = -1;
  for (let k = 0; k < chapters.length; k++) if (chapters[k].startMs <= pos) i = k;
  if (i < 0 || !isSkipped(chapters[i])) return null;
  let target = end(i);
  if (pos >= target) return null;
  while (i + 1 < chapters.length && isSkipped(chapters[i + 1]) && chapters[i + 1].startMs <= target + 1000) {
    i++;
    target = end(i);
  }
  return target;
}

function checkSkips() {
  const r = rules;
  const e = state.episode;
  if (!r || !e || r.episodeId !== e.id || audio.paused) return;
  const pos = audio.currentTime * 1000;
  const duration = isFinite(audio.duration) ? audio.duration * 1000 : 0;
  if (duration <= 0) return;
  const extra = r.adTrim ? adExtraMs(r.feedDurationMs, duration) : 0;
  if (extra > 0 && r.adTrim === "start" && pos < extra - 1000) {
    rawSeek(extra);
    return;
  }
  const endAt = extra > 0 && r.adTrim === "end" ? duration - extra : duration;
  let target = endAt < duration && pos >= endAt ? endAt : null;
  if (target == null && r.ads?.length && (r.adSkip ?? "button") === "auto") {
    if (adsSkipped.episodeId !== e.id) adsSkipped = { episodeId: e.id, starts: new Set() };
    const to = adSkipTarget(r.ads, pos, adsSkipped.starts);
    if (to != null) {
      const first = currentAd(r.ads, pos);
      if (first) adsSkipped.starts.add(first.startMs);
      for (const a of r.ads) if (a.startMs >= pos && a.startMs <= to) adsSkipped.starts.add(a.startMs);
      target = to;
    }
  }
  target ??= skipTarget(r.chapters, r.skipped, pos, duration);
  if (target == null) return;
  if (target >= endAt - 500) {
    // The rest is skipped: finish the episode as if it had ended.
    audio.pause();
    wantPlaying = false;
    set({ playing: false });
    const stop = state.sleepAtEnd;
    if (stop) set({ sleepAtEnd: false });
    hooks.onEnded(e, stop);
  } else {
    rawSeek(target);
  }
}
window.setInterval(checkSkips, 500);

/** Plays an episode from [ms]: seeks if it's the one loaded, otherwise loads it there. */
export function playAt(e: EpisodeRow, ms: number) {
  if (state.episode?.id === e.id) {
    seekTo(ms);
    play();
    return;
  }
  load({ ...e, played: false, position_ms: Math.max(1, Math.round(ms)) }, true);
}

export function skipBy(ms: number) {
  rawSeek(audio.currentTime * 1000 + ms);
}

export function setPrefs(p: PlaybackDefaults) {
  const vbChanged = p.voiceBoost !== prefs.voiceBoost;
  prefs = p;
  if (!skipping) audio.playbackRate = p.speed;
  audio.preservesPitch = true;
  if (vbChanged) applyVoiceBoost();
}

export function setSleep(minutes: number | null, atEnd = false) {
  set({ sleepEndsAt: minutes ? Date.now() + minutes * 60_000 : null, sleepAtEnd: atEnd });
}

export function current(): EpisodeRow | null {
  return state.episode;
}

// ---- OS media keys and lock screen (Media Session API) ----
function updateMediaSession(e: EpisodeRow) {
  if (!("mediaSession" in navigator)) return;
  navigator.mediaSession.metadata = new MediaMetadata({
    title: e.title,
    artwork: e.image_url ? [{ src: e.image_url }] : [],
  });
  navigator.mediaSession.setActionHandler("play", play);
  navigator.mediaSession.setActionHandler("pause", pause);
  navigator.mediaSession.setActionHandler("seekbackward", () => skipBy(-prefs.skipBackMs));
  navigator.mediaSession.setActionHandler("seekforward", () => skipBy(prefs.skipForwardMs));
}
