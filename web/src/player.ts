import { useSyncExternalStore } from "react";
import type { EpisodeRow, PlaybackDefaults } from "./types";

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
  const start = e.played ? 0 : e.position_ms;
  audio.currentTime = start / 1000;
  lastSaved = start;
  set({ episode: e, positionMs: start, durationMs: e.duration_ms, effectsAvailable: true, error: null, errorDetails: null, playing: false });
  audio.playbackRate = prefs.speed;
  updateMediaSession(e);
  if (autoplay) play();
}

export function play() {
  if (!state.episode) return;
  wantPlaying = true;
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
}

export function pause() {
  wantPlaying = false;
  audio.pause();
}

export function toggle() {
  if (audio.paused) play();
  else pause();
}

export function seekTo(ms: number) {
  audio.currentTime = Math.max(0, ms / 1000);
  set({ positionMs: ms });
}

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
  seekTo(audio.currentTime * 1000 + ms);
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
