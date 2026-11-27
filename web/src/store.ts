import { useSyncExternalStore } from "react";
import type { ParsedFeed } from "./feed";
import { supabase } from "./supabase";
import { DEFAULT_PLAYBACK, type EpisodeRow, type PlaybackDefaults, type PodcastRow } from "./types";
import { feedKey } from "./util";
import { flushNotes, pullNotes, resetNotes } from "./notes";
import { filtersOf, type PlaylistFilters, type PlaylistRow } from "./playlists";

// The web player's copy of the library, synced with the same Supabase tables as the phone.
// A change here is applied locally at once and uploaded shortly after; the server keeps the newest
// change (changed_at). Changes from the phone come in by polling rows with a higher rev.

const RECENT_MS = 60 * 24 * 60 * 60 * 1000;

export interface State {
  podcasts: Map<string, PodcastRow>; // by feed_url
  episodes: Map<string, EpisodeRow>; // by id
  defaults: PlaybackDefaults;
  defaultsChangedAt: number;
  /** Smart playlists by id, tombstones included (phase 7). */
  playlists: Map<string, PlaylistRow>;
  /** False until 003_automation_playlists.sql has been run (no playlists table, no rule columns). */
  phase7: boolean;
  loaded: boolean;
  syncing: boolean;
  error: string | null;
  version: number;
}

let state: State = {
  podcasts: new Map(),
  episodes: new Map(),
  defaults: DEFAULT_PLAYBACK,
  defaultsChangedAt: 0,
  playlists: new Map(),
  phase7: true,
  loaded: false,
  syncing: false,
  error: null,
  version: 0,
};
const listeners = new Set<() => void>();
const lastRev = { podcasts: 0, episodes: 0, settings: 0, playlists: 0 };

/** A table or column from a migration that hasn't been run yet. */
function missingSchema(message: string) {
  return /does not exist|could not find the|schema cache/i.test(message);
}

function emit() {
  state = { ...state, version: state.version + 1 };
  for (const l of listeners) l();
}

export function useStore(): State {
  return useSyncExternalStore(
    (l) => {
      listeners.add(l);
      return () => listeners.delete(l);
    },
    () => state,
  );
}

export const getState = () => state;

// ------------------------------------------------------------------ pull

async function pullTable<T extends { rev?: number }>(table: "podcasts" | "episodes" | "settings" | "playlists", apply: (row: T) => void) {
  for (;;) {
    const { data, error } = await supabase.from(table).select("*").gt("rev", lastRev[table]).order("rev").limit(1000);
    if (error) throw new Error(error.message);
    for (const row of data as T[]) {
      apply(row);
      lastRev[table] = Math.max(lastRev[table], row.rev ?? 0);
    }
    if (data.length < 1000) return;
  }
}

export async function pull() {
  if (state.syncing) return;
  state.syncing = true;
  try {
    await flush();
    await pullTable<PodcastRow>("podcasts", (r) => {
      const local = state.podcasts.get(r.feed_url);
      if (!local || r.changed_at >= local.changed_at) state.podcasts.set(r.feed_url, r);
    });
    await pullTable<EpisodeRow>("episodes", (r) => {
      const local = state.episodes.get(r.id);
      if (!local || r.changed_at >= local.changed_at) state.episodes.set(r.id, r);
    });
    await pullTable<{ playback: Partial<PlaybackDefaults>; changed_at: number; rev?: number }>("settings", (r) => {
      if (r.changed_at >= state.defaultsChangedAt) {
        state.defaults = { ...DEFAULT_PLAYBACK, ...r.playback };
        state.defaultsChangedAt = r.changed_at;
      }
    });
    // Smart playlists (skipped quietly until 003_automation_playlists.sql has been run).
    try {
      await pullTable<PlaylistRow>("playlists", (r) => {
        const local = state.playlists.get(r.id);
        if (!local || r.changed_at >= local.changed_at) state.playlists.set(r.id, r);
      });
      state.phase7 = true;
    } catch (e) {
      if (missingSchema(e instanceof Error ? e.message : String(e))) state.phase7 = false;
      else throw e;
    }
    state.loaded = true;
    state.error = null;
    // Transcripts and bookmarks (skipped quietly until 002_transcripts_bookmarks.sql has been run).
    await pullNotes();
  } catch (e) {
    state.error = e instanceof Error ? e.message : String(e);
  } finally {
    state.syncing = false;
    emit();
  }
}

export function resetStore() {
  state = { ...state, podcasts: new Map(), episodes: new Map(), playlists: new Map(), loaded: false, defaults: DEFAULT_PLAYBACK, defaultsChangedAt: 0 };
  lastRev.podcasts = lastRev.episodes = lastRev.settings = lastRev.playlists = 0;
  resetNotes();
  emit();
}

// ------------------------------------------------------------------ push

const pendingEpisodes = new Map<string, EpisodeRow>();
const pendingPodcasts = new Map<string, PodcastRow>();
const pendingPlaylists = new Map<string, PlaylistRow>();
let pendingSettings = false;

const RULE_KEYS = ["new_episode_action", "include_words", "exclude_words", "min_minutes", "max_minutes", "inbox_days", "inbox_keep"] as const;
/** Columns added by 004_listening_upgrades.sql (phase 10); left out of uploads until it has been run. */
const P10_PODCAST_KEYS = ["ad_trim"] as const;
const P10_EPISODE_KEYS = ["skipped_chapters", "season", "episode_no"] as const;
let phase10 = true;
const without = (row: object, keys: readonly string[]) => {
  const r: Record<string, unknown> = { ...row };
  for (const k of keys) delete r[k];
  return r;
};
let flushTimer: number | undefined;
let flushAt = Infinity;

function scheduleFlush(delayMs: number) {
  const at = Date.now() + delayMs;
  if (at >= flushAt) return;
  window.clearTimeout(flushTimer);
  flushAt = at;
  flushTimer = window.setTimeout(() => void flush(), delayMs);
}

export async function flush() {
  window.clearTimeout(flushTimer);
  flushAt = Infinity;
  const podcasts = [...pendingPodcasts.values()];
  const episodes = [...pendingEpisodes.values()];
  const playlists = [...pendingPlaylists.values()];
  const settings = pendingSettings;
  pendingPodcasts.clear();
  pendingEpisodes.clear();
  pendingPlaylists.clear();
  pendingSettings = false;
  const strip = <T extends { rev?: number }>(r: T) => {
    const { rev: _rev, ...rest } = r;
    return rest;
  };
  try {
    if (podcasts.length) {
      let { error } = await supabase
        .from("podcasts")
        .upsert(podcasts.map((p) => (phase10 ? strip(p) : without(strip(p), P10_PODCAST_KEYS))), { onConflict: "user_id,feed_url" });
      if (error && missingSchema(error.message) && phase10) {
        // 004 hasn't been run: save everything except the phase 10 columns.
        phase10 = false;
        ({ error } = await supabase.from("podcasts").upsert(podcasts.map((p) => without(strip(p), P10_PODCAST_KEYS)), { onConflict: "user_id,feed_url" }));
      }
      if (error && missingSchema(error.message)) {
        // 003 hasn't been run: save everything except the rules.
        state.phase7 = false;
        const plain = podcasts.map((p) => {
          return without(strip(p), [...RULE_KEYS, ...P10_PODCAST_KEYS]);
        });
        const retry = await supabase.from("podcasts").upsert(plain, { onConflict: "user_id,feed_url" });
        if (retry.error) throw new Error(retry.error.message);
      } else if (error) throw new Error(error.message);
    }
    if (playlists.length && state.phase7) {
      const { error } = await supabase.from("playlists").upsert(playlists.map(strip), { onConflict: "user_id,id" });
      if (error) throw new Error(error.message);
    }
    for (let i = 0; i < episodes.length; i += 500) {
      const rows = episodes.slice(i, i + 500).map((e) => (phase10 ? strip(e) : without(strip(e), P10_EPISODE_KEYS)));
      let { error } = await supabase.from("episodes").upsert(rows, { onConflict: "user_id,id" });
      if (error && missingSchema(error.message) && phase10) {
        phase10 = false;
        ({ error } = await supabase.from("episodes").upsert(rows.map((r) => without(r, P10_EPISODE_KEYS)), { onConflict: "user_id,id" }));
      }
      if (error) throw new Error(error.message);
    }
    if (settings) {
      const { error } = await supabase
        .from("settings")
        .upsert({ playback: state.defaults, changed_at: state.defaultsChangedAt }, { onConflict: "user_id" });
      if (error) throw new Error(error.message);
    }
  } catch (e) {
    // Put them back and try again later.
    for (const p of podcasts) if (!pendingPodcasts.has(p.feed_url)) pendingPodcasts.set(p.feed_url, p);
    for (const p of playlists) if (!pendingPlaylists.has(p.id)) pendingPlaylists.set(p.id, p);
    for (const e2 of episodes) if (!pendingEpisodes.has(e2.id)) pendingEpisodes.set(e2.id, e2);
    pendingSettings ||= settings;
    state.error = `Couldn't save changes: ${e instanceof Error ? e.message : e}`;
    emit();
    scheduleFlush(15_000);
  }
}

/** True while changes are waiting to be uploaded (shown as "Saving…"). */
export const hasPending = () => pendingEpisodes.size + pendingPodcasts.size + pendingPlaylists.size > 0 || pendingSettings;

function putEpisode(e: EpisodeRow, delayMs = 1000) {
  const row = { ...e, changed_at: Math.max(Date.now(), e.changed_at + 1) };
  state.episodes.set(row.id, row);
  pendingEpisodes.set(row.id, row);
  scheduleFlush(delayMs);
}

function putPodcast(p: PodcastRow) {
  const row = { ...p, changed_at: Math.max(Date.now(), p.changed_at + 1) };
  state.podcasts.set(row.feed_url, row);
  pendingPodcasts.set(row.feed_url, row);
  scheduleFlush(1000);
}

function putPlaylist(p: PlaylistRow) {
  const row = { ...p, changed_at: Math.max(Date.now(), p.changed_at + 1) };
  state.playlists.set(row.id, row);
  pendingPlaylists.set(row.id, row);
  scheduleFlush(800);
}

// ------------------------------------------------------------------ queries

/** Live playlists, in their order. */
export function playlists(s: State = state): PlaylistRow[] {
  return [...s.playlists.values()]
    .filter((p) => !p.deleted)
    .sort((a, b) => a.position - b.position || a.name.localeCompare(b.name, undefined, { sensitivity: "base" }));
}

export function queue(s: State = state): EpisodeRow[] {
  return [...s.episodes.values()].filter((e) => e.queue_position != null).sort((a, b) => a.queue_position! - b.queue_position!);
}

/** Started but not finished and not queued, most recently played first (same rule as the phone). */
export function continueListening(s: State = state, limit = 20): EpisodeRow[] {
  return [...s.episodes.values()]
    .filter((e) => !e.played && e.position_ms > 30_000 && e.queue_position == null)
    .sort((a, b) => (b.last_played_at ?? 0) - (a.last_played_at ?? 0))
    .slice(0, limit);
}

export function inbox(s: State = state): EpisodeRow[] {
  return [...s.episodes.values()].filter((e) => e.in_inbox).sort((a, b) => b.pub_date - a.pub_date);
}

export function latest(s: State = state, limit = 300): EpisodeRow[] {
  return [...s.episodes.values()]
    .filter((e) => s.podcasts.get(e.feed_url)?.subscribed)
    .sort((a, b) => b.pub_date - a.pub_date)
    .slice(0, limit);
}

export function library(s: State = state): PodcastRow[] {
  return [...s.podcasts.values()]
    .filter((p) => p.subscribed)
    .sort((a, b) => Number(b.pinned) - Number(a.pinned) || a.title.localeCompare(b.title, undefined, { sensitivity: "base" }));
}

/** The library's podcast for a feed address, allowing for directories spelling it differently. */
export function findPodcast(url: string, s: State = state): PodcastRow | undefined {
  const exact = s.podcasts.get(url);
  if (exact) return exact;
  const key = feedKey(url);
  for (const p of s.podcasts.values()) if (feedKey(p.feed_url) === key) return p;
  return undefined;
}

/** The stored episode, or the feed's copy merged in so it can be acted on. */
function known(e: EpisodeRow): EpisodeRow {
  return state.episodes.get(e.id) ?? e;
}

// ------------------------------------------------------------------ episode actions

function writeQueue(ids: string[]) {
  ids.forEach((id, i) => {
    const e = state.episodes.get(id);
    if (e && e.queue_position !== i) putEpisode({ ...e, queue_position: i });
  });
}

/** Make sure a previewed podcast exists on the server (hidden) before its episode is queued or played. */
function ensurePodcastFor(e: EpisodeRow, feed?: ParsedFeed) {
  if (state.podcasts.has(e.feed_url)) return;
  putPodcast({
    feed_url: e.feed_url,
    title: feed?.title || e.feed_url,
    author: feed?.author ?? "",
    description: feed?.description ?? "",
    image_url: feed?.imageUrl ?? null,
    subscribed: false,
    pinned: false,
    speed: null,
    smart_speed: null,
    voice_boost: null,
    skip_back_ms: null,
    skip_forward_ms: null,
    changed_at: 0,
  });
}

function queueAt(ep: EpisodeRow, index: (ids: string[]) => number, feed?: ParsedFeed) {
  ensurePodcastFor(ep, feed);
  const e = known(ep);
  const ids = queue().map((q) => q.id).filter((id) => id !== e.id);
  ids.splice(Math.max(0, Math.min(ids.length, index(ids))), 0, e.id);
  state.episodes.set(e.id, e);
  putEpisode({ ...e, in_inbox: false, queue_position: ids.indexOf(e.id) });
  writeQueue(ids);
  emit();
}

export const queueFirst = (e: EpisodeRow, feed?: ParsedFeed) => queueAt(e, () => 0, feed);
export const queueNext = (e: EpisodeRow, feed?: ParsedFeed) => queueAt(e, (ids) => (ids.length ? 1 : 0), feed);
export const queueLast = (e: EpisodeRow, feed?: ParsedFeed) => queueAt(e, (ids) => ids.length, feed);

export function removeFromQueue(e: EpisodeRow) {
  putEpisode({ ...known(e), queue_position: null });
  writeQueue(queue().map((q) => q.id));
  emit();
}

export function moveInQueue(id: string, toIndex: number) {
  const ids = queue().map((q) => q.id).filter((x) => x !== id);
  ids.splice(Math.max(0, Math.min(ids.length, toIndex)), 0, id);
  writeQueue(ids);
  emit();
}

export function setPlayed(e: EpisodeRow, played: boolean, feed?: ParsedFeed) {
  ensurePodcastFor(e, feed);
  const cur = known(e);
  putEpisode({
    ...cur,
    played,
    position_ms: 0,
    in_inbox: played ? false : cur.in_inbox,
    queue_position: played ? null : cur.queue_position,
  });
  writeQueue(queue().map((q) => q.id));
  emit();
}

/**
 * Marks many episodes played in one go (phase 7b, "mark older/newer played"). They're uploaded
 * together (500 a request) a second later. Callers leave queued episodes out, so the queue isn't
 * touched. Returns how many changed.
 */
export function markPlayedMany(rows: EpisodeRow[]): number {
  let n = 0;
  for (const r of rows) {
    const cur = known(r);
    if (cur.played) continue;
    putEpisode({ ...cur, played: true, position_ms: 0, in_inbox: false });
    n++;
  }
  if (n) emit();
  return n;
}

export function archive(e: EpisodeRow) {
  putEpisode({ ...known(e), in_inbox: false });
  emit();
}

export function archiveAll() {
  for (const e of inbox()) putEpisode({ ...e, in_inbox: false });
  emit();
}

/** Saves the play position; uploads within 15 s (or at once with [soon]). */
export function savePosition(id: string, positionMs: number, soon = false) {
  const e = state.episodes.get(id);
  if (!e) return;
  putEpisode({ ...e, position_ms: Math.round(positionMs), last_played_at: Date.now() }, soon ? 500 : 15_000);
  emit();
}

/** Phase 10: ticks or unticks a chapter; unticked chapters are skipped (synced with the phone). */
export function setChapterSkipped(id: string, startMs: number, skip: boolean) {
  const e = state.episodes.get(id);
  if (!e) return;
  const set = (e.skipped_chapters ?? []).filter((s) => Math.abs(s - startMs) > 1000);
  if (skip) set.push(Math.round(startMs));
  set.sort((a, b) => a - b);
  putEpisode({ ...e, skipped_chapters: set.length ? set : null });
  emit();
}

export function setAdTrim(feedUrl: string, mode: string | null) {
  const p = state.podcasts.get(feedUrl);
  if (p) {
    putPodcast({ ...p, ad_trim: mode });
    emit();
  }
}

/** The stored copy of an episode (for its synced chapter choices), if any. */
export function episode(id: string): EpisodeRow | undefined {
  return state.episodes.get(id);
}

export function setDuration(id: string, durationMs: number) {
  const e = state.episodes.get(id);
  if (e && durationMs > 0 && Math.abs(e.duration_ms - durationMs) > 2000) {
    state.episodes.set(id, { ...e, duration_ms: Math.round(durationMs) });
  }
}

// ------------------------------------------------------------------ podcasts

/** Subscribes (from a preview or a search result): newest episode to New Episodes, recent ones for Latest. */
export function subscribe(feedUrl: string, feed: ParsedFeed) {
  const existing = findPodcast(feedUrl);
  const url = existing?.feed_url ?? feedUrl;
  putPodcast({
    ...(existing ?? {
      feed_url: url,
      pinned: false,
      speed: null,
      smart_speed: null,
      voice_boost: null,
      skip_back_ms: null,
      skip_forward_ms: null,
      changed_at: 0,
    }),
    title: feed.title || existing?.title || url,
    author: feed.author,
    description: feed.description,
    image_url: feed.imageUrl,
    subscribed: true,
  });
  const now = Date.now();
  feed.episodes.forEach((fe, i) => {
    const e = state.episodes.get(fe.id);
    const newest = i === 0 && !e?.played && e?.queue_position == null;
    if (newest) putEpisode({ ...(e ?? fe), in_inbox: true });
    else if (!e && fe.pub_date > now - RECENT_MS) putEpisode(fe);
  });
  emit();
}

export function unsubscribe(feedUrl: string) {
  const p = state.podcasts.get(feedUrl);
  if (!p) return;
  putPodcast({ ...p, subscribed: false });
  for (const e of state.episodes.values()) if (e.feed_url === feedUrl && e.in_inbox) putEpisode({ ...e, in_inbox: false });
  emit();
}

export function setPinned(feedUrl: string, pinned: boolean) {
  const p = state.podcasts.get(feedUrl);
  if (p) {
    putPodcast({ ...p, pinned });
    emit();
  }
}

export function updatePodcastPlayback(feedUrl: string, patch: Partial<PodcastRow>) {
  const p = state.podcasts.get(feedUrl);
  if (p) {
    putPodcast({ ...p, ...patch });
    emit();
  }
}

/** A podcast's automation rules (phase 7). The phone applies them when it finds new episodes. */
export function updateRules(feedUrl: string, patch: Partial<PodcastRow>) {
  const p = state.podcasts.get(feedUrl);
  if (p) {
    putPodcast({ ...p, ...patch });
    emit();
  }
}

// ------------------------------------------------------------------ smart playlists (phase 7)

export function createPlaylist(name: string, filters: PlaylistFilters): string {
  const id = crypto.randomUUID();
  const position = Math.max(-1, ...playlists().map((p) => p.position)) + 1;
  putPlaylist({ id, name: name.trim() || "Playlist", position, filters, deleted: false, changed_at: 0 });
  emit();
  return id;
}

export function updatePlaylist(id: string, name: string, filters: PlaylistFilters) {
  const p = state.playlists.get(id);
  if (!p) return;
  putPlaylist({ ...p, name: name.trim() || p.name, filters });
  emit();
}

export function deletePlaylist(id: string) {
  const p = state.playlists.get(id);
  if (!p) return;
  putPlaylist({ ...p, deleted: true, filters: filtersOf(p) });
  emit();
}

export function updateDefaults(patch: Partial<PlaybackDefaults>) {
  state.defaults = { ...state.defaults, ...patch };
  state.defaultsChangedAt = Math.max(Date.now(), state.defaultsChangedAt + 1);
  pendingSettings = true;
  scheduleFlush(1000);
  emit();
}

/** OPML import: subscribes to each feed (no New Episodes, like the phone). Returns titles that failed. */
export async function importFeeds(
  feeds: { url: string; title: string }[],
  load: (url: string) => Promise<ParsedFeed>,
  onProgress: (done: number, total: number) => void,
): Promise<string[]> {
  const failed: string[] = [];
  let done = 0;
  const todo = feeds.filter((f) => !findPodcast(f.url)?.subscribed);
  const worker = async () => {
    for (;;) {
      const f = todo.shift();
      if (!f) return;
      try {
        const feed = await load(f.url);
        const now = Date.now();
        putPodcast({
          feed_url: f.url,
          title: feed.title || f.title,
          author: feed.author,
          description: feed.description,
          image_url: feed.imageUrl,
          subscribed: true,
          pinned: false,
          speed: null,
          smart_speed: null,
          voice_boost: null,
          skip_back_ms: null,
          skip_forward_ms: null,
          changed_at: 0,
        });
        for (const fe of feed.episodes) if (!state.episodes.has(fe.id) && fe.pub_date > now - RECENT_MS) putEpisode(fe);
      } catch {
        failed.push(f.title || f.url);
      }
      onProgress(++done, feeds.length);
      emit();
    }
  };
  await Promise.all([worker(), worker(), worker(), worker()]);
  return failed;
}

// Keep in sync: poll every 30 s while visible, and when the tab comes back.
let pollTimer: number | undefined;
export function startPolling() {
  stopPolling();
  void pull();
  pollTimer = window.setInterval(() => {
    if (document.visibilityState === "visible") void pull();
  }, 30_000);
  document.addEventListener("visibilitychange", onVisible);
  window.addEventListener("pagehide", onHide);
}
export function stopPolling() {
  window.clearInterval(pollTimer);
  document.removeEventListener("visibilitychange", onVisible);
  window.removeEventListener("pagehide", onHide);
}
function onVisible() {
  if (document.visibilityState === "visible") void pull();
  else {
    void flush();
    void flushNotes().catch(() => {});
  }
}
function onHide() {
  void flush();
  void flushNotes().catch(() => {});
}
