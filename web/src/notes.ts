import { useSyncExternalStore } from "react";
import { proxyFetch } from "./feed";
import { callFunction, supabase } from "./supabase";
import { normalize, parseTranscript, type Segment } from "./transcript";
import type { EpisodeRow } from "./types";
import { searchTerms } from "./util";

// Transcripts and bookmarks (phase 6), synced with the same Supabase tables as the phone
// (supabase/migrations/002_transcripts_bookmarks.sql). Bookmarks are all kept here; for transcripts
// only an index is kept (which episodes have one), and each transcript's text is fetched when opened.

export interface BookmarkRow {
  id: string;
  episode_id: string;
  feed_url: string;
  episode_title: string;
  podcast_title: string;
  image_url: string | null;
  audio_url: string | null;
  position_ms: number;
  start_ms: number;
  end_ms: number;
  note: string;
  /** Phase 13 (007_browse.sql): a short title made from the transcript passage. */
  title?: string | null;
  created_at: number;
  deleted: boolean;
  changed_at: number;
  rev?: number;
}

export interface TranscriptInfo {
  episode_id: string;
  source: string; // "feed" or "gemini"
  timed: boolean;
  changed_at: number;
}

export interface LoadedTranscript extends TranscriptInfo {
  segments: Segment[];
}

export type TranscribeState = { kind: "running"; part: number; parts: number } | { kind: "failed"; error: string };

export interface NotesState {
  bookmarks: Map<string, BookmarkRow>; // by id, tombstones included
  transcripts: Map<string, TranscriptInfo>; // by episode id
  transcribing: Map<string, TranscribeState>;
  /** False until 002_transcripts_bookmarks.sql has been run (the tables don't exist). */
  available: boolean;
  version: number;
}

/** Bookmark quote window: 60 s before the moment to 5 s after (same as the phone). */
export const QUOTE_BEFORE_MS = 60_000;
export const QUOTE_AFTER_MS = 5_000;

let state: NotesState = { bookmarks: new Map(), transcripts: new Map(), transcribing: new Map(), available: true, version: 0 };
const listeners = new Set<() => void>();
const lastRev = { bookmarks: 0, transcripts: 0 };
const loaded = new Map<string, LoadedTranscript>();

function emit() {
  state = { ...state, version: state.version + 1 };
  for (const l of listeners) l();
}

export function useNotes(): NotesState {
  return useSyncExternalStore(
    (l) => {
      listeners.add(l);
      return () => listeners.delete(l);
    },
    () => state,
  );
}

export const notesState = () => state;

/** The tables aren't there yet: sync carries on without transcripts and bookmarks. */
function missingTable(message: string) {
  return /does not exist|could not find the table|schema cache/i.test(message);
}

// ------------------------------------------------------------------ pull

/** Pulls changed bookmarks and the transcript index. Called by store.pull. */
export async function pullNotes() {
  try {
    await flushNotes();
    for (;;) {
      const { data, error } = await supabase.from("bookmarks").select("*").gt("rev", lastRev.bookmarks).order("rev").limit(1000);
      if (error) throw new Error(error.message);
      for (const r of data as BookmarkRow[]) {
        const local = state.bookmarks.get(r.id);
        if (!local || r.changed_at >= local.changed_at) state.bookmarks.set(r.id, r);
        lastRev.bookmarks = Math.max(lastRev.bookmarks, r.rev ?? 0);
      }
      if (data.length < 1000) break;
    }
    for (;;) {
      const { data, error } = await supabase
        .from("transcripts")
        .select("episode_id, source, timed, changed_at, rev")
        .gt("rev", lastRev.transcripts)
        .order("rev")
        .limit(1000);
      if (error) throw new Error(error.message);
      for (const r of data as (TranscriptInfo & { rev: number })[]) {
        const local = state.transcripts.get(r.episode_id);
        if (!local || r.changed_at >= local.changed_at) {
          state.transcripts.set(r.episode_id, { episode_id: r.episode_id, source: r.source, timed: r.timed, changed_at: r.changed_at });
          const cached = loaded.get(r.episode_id);
          if (cached && cached.changed_at !== r.changed_at) loaded.delete(r.episode_id);
        }
        lastRev.transcripts = Math.max(lastRev.transcripts, r.rev ?? 0);
      }
      if (data.length < 1000) break;
    }
    state.available = true;
    // Phase 14: bookmarks still without a title get one now that their transcript may be here.
    titleUntitledSoon();
  } catch (e) {
    const msg = e instanceof Error ? e.message : String(e);
    if (missingTable(msg)) state.available = false;
    else throw e;
  } finally {
    emit();
  }
}

export function resetNotes() {
  state = { bookmarks: new Map(), transcripts: new Map(), transcribing: new Map(), available: true, version: state.version + 1 };
  lastRev.bookmarks = lastRev.transcripts = 0;
  loaded.clear();
  titleGaveUp.clear();
  for (const l of listeners) l();
}

// ------------------------------------------------------------------ push

const pendingBookmarks = new Map<string, BookmarkRow>();
let flushTimer: number | undefined;

export async function flushNotes() {
  window.clearTimeout(flushTimer);
  const rows = [...pendingBookmarks.values()];
  pendingBookmarks.clear();
  if (!rows.length) return;
  const { error } = await supabase.from("bookmarks").upsert(
    // Phase 14: the server's own retry columns (title_tries, title_next_at) come in with select("*")
    // but are never sent back.
    rows.map(({ rev: _rev, ...r }) => {
      const { title_tries: _tries, title_next_at: _next, ...row } = r as BookmarkRow & { title_tries?: unknown; title_next_at?: unknown };
      return { ...row, title: r.title ?? null };
    }),
    { onConflict: "user_id,id" },
  );
  if (error) {
    for (const r of rows) if (!pendingBookmarks.has(r.id)) pendingBookmarks.set(r.id, r);
    flushTimer = window.setTimeout(() => void flushNotes().catch(() => {}), 15_000);
    throw new Error(`Couldn't save bookmarks: ${error.message}`);
  }
}

export const hasPendingNotes = () => pendingBookmarks.size > 0;

function putBookmark(b: BookmarkRow) {
  const row = { ...b, changed_at: Math.max(Date.now(), b.changed_at + 1) };
  state.bookmarks.set(row.id, row);
  pendingBookmarks.set(row.id, row);
  window.clearTimeout(flushTimer);
  flushTimer = window.setTimeout(() => void flushNotes().catch(() => {}), 800);
  emit();
}

// ------------------------------------------------------------------ bookmarks

/** Live bookmarks, newest first (optionally one episode's, in order through the episode). */
export function bookmarks(s: NotesState = state, episodeId?: string): BookmarkRow[] {
  const all = [...s.bookmarks.values()].filter((b) => !b.deleted && (episodeId == null || b.episode_id === episodeId));
  return episodeId == null ? all.sort((a, b) => b.created_at - a.created_at) : all.sort((a, b) => a.position_ms - b.position_ms);
}

/** Bookmarks this moment of an episode, then fetches the feed's transcript if it isn't saved yet. */
export function addBookmark(e: EpisodeRow, podcastTitle: string, podcastImage: string | null, positionMs: number): BookmarkRow {
  const pos = Math.max(0, Math.round(positionMs));
  const now = Date.now();
  const b: BookmarkRow = {
    id: crypto.randomUUID(),
    episode_id: e.id,
    feed_url: e.feed_url,
    episode_title: e.title,
    podcast_title: podcastTitle,
    image_url: e.image_url ?? podcastImage,
    audio_url: e.audio_url,
    position_ms: pos,
    start_ms: Math.max(0, pos - QUOTE_BEFORE_MS),
    end_ms: pos + QUOTE_AFTER_MS,
    note: "",
    title: null,
    created_at: now,
    deleted: false,
    changed_at: now,
  };
  putBookmark(b);
  // Phase 13: once there's a timed transcript, give the bookmark a short title from its passage.
  void ensureFeedTranscript(e)
    .catch(() => null)
    .then((t) => t ?? loadTranscript(e.id))
    .then((t) => (t?.timed ? titleBookmark(b.id, false) : undefined))
    .catch(() => titleUntitledSoon()); // Phase 14: tried again in the background
  return b;
}

/** Phase 13: asks the "ai" function for a short title for the bookmark's passage, and saves it. */
export async function titleBookmark(id: string, force: boolean): Promise<string | null> {
  const b = state.bookmarks.get(id);
  if (!b || b.deleted) return null;
  if (!force && b.title) return b.title;
  const res = await callFunction<{ data?: { title?: string } }>("ai", {
    episodeId: b.episode_id,
    kind: "title",
    startMs: b.start_ms,
    endMs: b.end_ms,
    title: b.episode_title,
    podcast: b.podcast_title,
  });
  const title = res.data?.title?.trim();
  if (!title) throw new Error("No title came back");
  const cur = state.bookmarks.get(id);
  if (!cur || cur.deleted) return null;
  putBookmark({ ...cur, title });
  return title;
}

// Phase 14: automatic titles for every untitled bookmark whose episode has a timed transcript (one
// made here, on the phone, or fetched from the feed). Runs after each pull and when a transcript is
// saved. When Gemini is busy or the network drops, it tries again after 2, 4, 8, 16, then every 30
// minutes while the page is open. A bookmark that can't get a title (no transcript text at that
// moment) isn't tried again until its transcript changes. The server also titles leftovers every
// 15 minutes, so a title can arrive with the next pull instead.
const titleGaveUp = new Map<string, number>(); // bookmark id -> transcript changed_at when it failed for good
let titleRunning = false;
let titleAgain = false;
let titleRetryAt = 0;
let titleRetryDelay = 2 * 60_000;
let titleTimer: number | undefined;

function titleFailedForGood(message: string) {
  return /no transcript text at this bookmark|has no timings|needs a timed transcript/i.test(message);
}

export function titleUntitledSoon() {
  if (titleRunning) {
    titleAgain = true;
    return;
  }
  if (Date.now() < titleRetryAt) return;
  titleRunning = true;
  void titleUntitled().finally(() => {
    titleRunning = false;
    if (titleAgain) {
      titleAgain = false;
      titleUntitledSoon();
    }
  });
}

async function titleUntitled() {
  const todo = bookmarks().filter((b) => {
    if (b.title) return false;
    const t = state.transcripts.get(b.episode_id);
    return !!t?.timed && titleGaveUp.get(b.id) !== t.changed_at;
  }).slice(0, 20);
  let retry = 0;
  for (const b of todo) {
    try {
      await titleBookmark(b.id, false);
    } catch (e) {
      const msg = e instanceof Error ? e.message : String(e);
      if (titleFailedForGood(msg)) titleGaveUp.set(b.id, state.transcripts.get(b.episode_id)?.changed_at ?? 0);
      else retry++;
      console.warn(`Bookmark title for ${b.id} failed${titleFailedForGood(msg) ? "" : " (will try again)"}: ${msg}`);
    }
  }
  window.clearTimeout(titleTimer);
  if (retry > 0) {
    titleRetryAt = Date.now() + titleRetryDelay;
    titleTimer = window.setTimeout(titleUntitledSoon, titleRetryDelay + 1_000);
    titleRetryDelay = Math.min(titleRetryDelay * 2, 30 * 60_000);
  } else {
    titleRetryAt = 0;
    titleRetryDelay = 2 * 60_000;
  }
}

export function updateBookmark(b: BookmarkRow, patch: Partial<Pick<BookmarkRow, "start_ms" | "end_ms" | "note">>) {
  putBookmark({ ...b, ...patch });
}

export function deleteBookmark(b: BookmarkRow) {
  putBookmark({ ...b, deleted: true });
}

// ------------------------------------------------------------------ transcripts

/** The episode's transcript (fetched once, then kept for this session), or null if there's none. */
export async function loadTranscript(episodeId: string): Promise<LoadedTranscript | null> {
  const cached = loaded.get(episodeId);
  if (cached) return cached;
  const { data, error } = await supabase
    .from("transcripts")
    .select("episode_id, source, timed, segments, changed_at")
    .eq("episode_id", episodeId)
    .maybeSingle();
  if (error) {
    if (missingTable(error.message)) return null;
    throw new Error(error.message);
  }
  if (!data) return null;
  const t = data as LoadedTranscript;
  // Phase 18-pre: after 012 the text lives in the shared copy; this row is only the pointer.
  if (!Array.isArray(t.segments) || t.segments.length === 0) {
    const shared = await supabase.from("episode_transcripts").select("segments, timed").eq("episode_id", episodeId).maybeSingle();
    if (!shared.error && shared.data && Array.isArray(shared.data.segments)) {
      t.segments = shared.data.segments as Segment[];
      t.timed = shared.data.timed as boolean;
    }
  }
  loaded.set(episodeId, t);
  if (!state.transcripts.has(episodeId)) {
    state.transcripts.set(episodeId, { episode_id: t.episode_id, source: t.source, timed: t.timed, changed_at: t.changed_at });
    emit();
  }
  return t;
}

async function saveTranscript(episodeId: string, source: string, segments: Segment[], timed: boolean): Promise<LoadedTranscript> {
  const t: LoadedTranscript = { episode_id: episodeId, source, timed, segments, changed_at: Date.now() };
  const { error } = await supabase.from("transcripts").upsert(t, { onConflict: "user_id,episode_id" });
  if (error) throw new Error(`Couldn't save the transcript: ${error.message}`);
  loaded.set(episodeId, t);
  state.transcripts.set(episodeId, { episode_id: episodeId, source, timed, changed_at: t.changed_at });
  emit();
  if (timed) titleUntitledSoon();
  return t;
}

const feedFetches = new Map<string, Promise<LoadedTranscript | null>>();

/**
 * Fetches and saves the podcast's own transcript (free) when the episode has one and nothing is saved
 * yet. Returns the saved transcript, or null when the episode has no feed transcript.
 */
export function ensureFeedTranscript(e: EpisodeRow): Promise<LoadedTranscript | null> {
  if (!e.transcript_url || !state.available) return Promise.resolve(null);
  const running = feedFetches.get(e.id);
  if (running) return running;
  const p = (async () => {
    const existing = await loadTranscript(e.id);
    if (existing) return existing;
    const body = await proxyFetch(e.transcript_url!);
    const parsed = parseTranscript(body, e.transcript_type);
    if (!parsed.segments.length) throw new Error("The podcast's transcript is empty");
    return saveTranscript(e.id, "feed", parsed.segments, parsed.timed);
  })().finally(() => feedFetches.delete(e.id));
  feedFetches.set(e.id, p);
  return p;
}

function setTranscribing(id: string, s: TranscribeState | null) {
  const m = new Map(state.transcribing);
  if (s) m.set(id, s);
  else m.delete(id);
  state.transcribing = m;
  emit();
}

const cancelled = new Set<string>();

/** Stops a cloud transcription (its result is ignored if it arrives later). */
export function cancelTranscription(id: string) {
  cancelled.add(id);
  setTranscribing(id, null);
}

const PARTS_KEY = (id: string) => `earshot.transcribe.${id}`;

/**
 * Transcribes an episode with Deepgram through the "transcribe" function. Keep the page open until it's
 * done (usually about a minute). The function answers in parts (one, with Deepgram); finished parts
 * are kept in this browser, so a retry carries on where it stopped.
 */
export async function transcribeWithGemini(e: EpisodeRow) {
  if (state.transcribing.get(e.id)?.kind === "running") return;
  cancelled.delete(e.id);
  let segments: Segment[] = [];
  let part = 0;
  try {
    const saved = JSON.parse(localStorage.getItem(PARTS_KEY(e.id)) ?? "null") as { audioUrl: string; engine?: string; next: number; segments: Segment[] } | null;
    if (saved && saved.audioUrl === e.audio_url && saved.engine === "deepgram") {
      segments = saved.segments;
      part = saved.next;
    }
  } catch {
    // No saved parts.
  }
  let parts = 1;
  setTranscribing(e.id, { kind: "running", part, parts });
  try {
    for (;;) {
      const { data, error } = await supabase.functions.invoke("transcribe", {
        body: { audioUrl: e.audio_url, mimeType: e.mime_type, durationMs: e.duration_ms, part },
      });
      if (error) {
        let msg = error.message;
        const ctx = (error as { context?: Response }).context;
        if (ctx && typeof ctx.json === "function") {
          try {
            msg = ((await ctx.json()) as { error?: string }).error ?? msg;
          } catch {
            // Keep the general message.
          }
        }
        throw new Error(msg);
      }
      if (cancelled.has(e.id)) return;
      const res = data as { parts?: number; done?: boolean; segments?: Segment[] };
      segments = segments.concat(res.segments ?? []);
      if (res.parts && res.parts > 0) parts = res.parts;
      part++;
      try {
        localStorage.setItem(PARTS_KEY(e.id), JSON.stringify({ audioUrl: e.audio_url, engine: "deepgram", next: part, segments }));
      } catch {
        // Storage full or blocked: a retry starts over.
      }
      if (res.done) break;
      if (part > 200) throw new Error("The episode seems endless; stopped after 200 parts");
      setTranscribing(e.id, { kind: "running", part, parts: Math.max(parts, part + 1) });
    }
    if (!segments.length) throw new Error("No speech was found in this episode");
    await saveTranscript(e.id, "deepgram", segments, true);
    try {
      localStorage.removeItem(PARTS_KEY(e.id));
    } catch {
      // Nothing to clean up.
    }
    setTranscribing(e.id, null);
  } catch (err) {
    if (!cancelled.has(e.id)) setTranscribing(e.id, { kind: "failed", error: err instanceof Error ? err.message : String(err) });
  }
}

// ------------------------------------------------------------------ search

export interface SearchHit {
  episodeId: string;
  matches: { s: number; t: string }[];
}

/** Search across every saved transcript (words in any order; "quoted phrases" as written). */
export async function searchTranscripts(query: string): Promise<SearchHit[]> {
  const terms = searchTerms(normalize(query));
  if (!terms.length) return [];
  const { data, error } = await supabase.rpc("search_transcripts", { terms, max_results: 400 });
  if (error) throw new Error(missingTable(error.message) || /function/i.test(error.message) ? "Transcript search isn't set up yet: run 002_transcripts_bookmarks.sql in Supabase" : error.message);
  const byEpisode = new Map<string, SearchHit>();
  for (const r of data as { episode_id: string; start_ms: number; text: string }[]) {
    let hit = byEpisode.get(r.episode_id);
    if (!hit) byEpisode.set(r.episode_id, (hit = { episodeId: r.episode_id, matches: [] }));
    if (hit.matches.length < 20) hit.matches.push({ s: Number(r.start_ms), t: r.text });
  }
  return [...byEpisode.values()].slice(0, 50);
}
