import { useSyncExternalStore } from "react";
import type { EpisodeRow, PlaybackDefaults } from "./types";

// One <audio> element plus, when the audio host allows it (CORS), a Web Audio chain:
//   Voice Boost = compressor + make-up gain, Smart Speed = speed up through silences.
// Hosts that don't allow it still play, with speed only (effects shown as unavailable).

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
};
const listeners = new Set<() => void>();
function set(patch: Partial<PlayerState>) {
  state = { ...state, ...patch };
  for (const l of listeners) l();
}
export function usePlayer(): PlayerState {
  return useSyncExternalStore(
    (l) => {
      listeners.add(l);
      return () => listeners.delete(l);
    },
    () => state,
  );
}

const audio = new Audio();
audio.preload = "auto";
let prefs: PlaybackDefaults = { speed: 1, smartSpeed: false, voiceBoost: false, skipBackMs: 15_000, skipForwardMs: 30_000 };
let triedWithoutCors = false;

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
  if (ctx || audio.crossOrigin !== "anonymous") return;
  ctx = new AudioContext();
  source = ctx.createMediaElementSource(audio);
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
  if (!ctx || !prefs.smartSpeed || audio.paused) {
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

// ---- element events ----
let lastSaved = 0;
audio.addEventListener("timeupdate", () => {
  const ms = audio.currentTime * 1000;
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
audio.addEventListener("durationchange", () => {
  if (isFinite(audio.duration)) {
    set({ durationMs: audio.duration * 1000 });
    if (state.episode) hooks.onDuration(state.episode.id, audio.duration * 1000);
  }
});
audio.addEventListener("playing", () => set({ playing: true, buffering: false, error: null }));
audio.addEventListener("pause", () => {
  set({ playing: false });
  if (state.episode) hooks.onPosition(state.episode.id, audio.currentTime * 1000, true);
});
audio.addEventListener("waiting", () => set({ buffering: true }));
audio.addEventListener("ended", () => {
  const e = state.episode;
  set({ playing: false });
  if (!e) return;
  const stop = state.sleepAtEnd;
  if (stop) set({ sleepAtEnd: false });
  hooks.onEnded(e, stop);
});
audio.addEventListener("error", () => {
  // The host refused cross-origin access: play without effects instead.
  if (audio.crossOrigin === "anonymous" && !triedWithoutCors && state.episode) {
    triedWithoutCors = true;
    const at = state.positionMs;
    const wasPlaying = true;
    audio.removeAttribute("crossorigin");
    set({ effectsAvailable: false });
    audio.src = state.episode.audio_url;
    audio.currentTime = at / 1000;
    if (wasPlaying) void audio.play().catch(() => {});
    return;
  }
  set({ error: "This episode couldn't be played.", playing: false, buffering: false });
});

// ---- controls ----

/** Loads an episode (from its saved position) and, if [autoplay], starts it. */
export function load(e: EpisodeRow, autoplay: boolean) {
  if (state.episode?.id === e.id) {
    if (autoplay) play();
    return;
  }
  if (state.episode && !audio.paused) hooks.onPosition(state.episode.id, audio.currentTime * 1000, true);
  triedWithoutCors = false;
  // Once the graph exists the element must stay cross-origin; otherwise try it, falling back on error.
  audio.crossOrigin = "anonymous";
  audio.src = e.audio_url;
  const start = e.played ? 0 : e.position_ms;
  audio.currentTime = start / 1000;
  lastSaved = start;
  set({ episode: e, positionMs: start, durationMs: e.duration_ms, effectsAvailable: true, error: null, playing: false });
  audio.playbackRate = prefs.speed;
  updateMediaSession(e);
  if (autoplay) play();
}

export function play() {
  if (!state.episode) return;
  if (audio.crossOrigin === "anonymous") {
    buildGraph();
    void ctx?.resume();
  }
  set({ buffering: true });
  void audio.play().catch((err) => set({ error: String(err), buffering: false }));
}

export function pause() {
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
