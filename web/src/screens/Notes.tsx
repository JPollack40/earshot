import { useEffect, useMemo, useRef, useState } from "react";
import * as notes from "../notes";
import type { BookmarkRow, LoadedTranscript } from "../notes";
import * as player from "../player";
import * as store from "../store";
import {
  allBookmarksMd,
  downloadMarkdown,
  episodeBookmarksMd,
  exportFileName,
  fullTranscriptMd,
  indexAt,
  normalize,
  quote,
  speakerName,
  type ExportEpisode,
} from "../transcript";
import type { EpisodeRow } from "../types";
import { formatClock, searchTerms } from "../util";
import { Art, ArtLink, PodcastLink, type Nav } from "./common";
import { Glyph } from "./Icons";

// Phase 6 pages: an episode's transcript, all bookmarks, and search across transcripts.

/** What's known about an episode: the library's copy, or the details a bookmark kept. */
export interface EpisodeInfo {
  id: string;
  episode: EpisodeRow | undefined;
  title: string;
  podcastTitle: string;
  /** The podcast's feed, for opening it (phase 7); "" when unknown. */
  feedUrl: string;
  image: string | null;
  audioUrl: string | null;
  pubDate: number;
}

export function episodeInfo(id: string, s: store.State = store.getState(), n: notes.NotesState = notes.notesState()): EpisodeInfo {
  const e = s.episodes.get(id);
  const pod = e ? s.podcasts.get(e.feed_url) : undefined;
  const b = e ? undefined : notes.bookmarks(n).find((x) => x.episode_id === id);
  return {
    id,
    episode: e,
    title: e?.title ?? b?.episode_title ?? "Episode",
    podcastTitle: pod?.title ?? b?.podcast_title ?? "",
    feedUrl: e?.feed_url ?? b?.feed_url ?? "",
    image: e?.image_url ?? pod?.image_url ?? b?.image_url ?? null,
    audioUrl: e?.audio_url ?? b?.audio_url ?? null,
    pubDate: e?.pub_date ?? 0,
  };
}

const exportEpisode = (i: EpisodeInfo): ExportEpisode => ({ title: i.title, podcastTitle: i.podcastTitle, audioUrl: i.audioUrl, pubDate: i.pubDate });

/** Downloads an episode's bookmarks as Markdown (with the transcript quoted where there is one). */
export async function exportEpisodeBookmarks(id: string) {
  const info = episodeInfo(id);
  const t = await notes.loadTranscript(id).catch(() => null);
  downloadMarkdown(exportFileName(`${info.title} bookmarks`), episodeBookmarksMd(exportEpisode(info), notes.bookmarks(notes.notesState(), id), t));
}

/** Downloads an episode's full transcript with its bookmarks marked. */
export async function exportTranscript(id: string) {
  const info = episodeInfo(id);
  const t = await notes.loadTranscript(id);
  if (!t) throw new Error("This episode has no transcript yet");
  downloadMarkdown(exportFileName(`${info.title} transcript`), fullTranscriptMd(exportEpisode(info), t, notes.bookmarks(notes.notesState(), id)));
}

/** Plays an episode from a moment, if it's in the library. */
function playFrom(id: string, ms: number): string | null {
  const e = store.getState().episodes.get(id);
  if (!e) return "That episode isn't in your library any more";
  player.playAt(e, ms);
  return null;
}

/** A small menu under a ⋯ button. */
function Menu({ items }: { items: { label: string; onClick: () => void; hidden?: boolean }[] }) {
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (!open) return;
    const close = (ev: MouseEvent | KeyboardEvent) => {
      if (ev instanceof KeyboardEvent ? ev.key === "Escape" : !ref.current?.contains(ev.target as Node)) setOpen(false);
    };
    document.addEventListener("mousedown", close);
    document.addEventListener("keydown", close);
    return () => {
      document.removeEventListener("mousedown", close);
      document.removeEventListener("keydown", close);
    };
  }, [open]);
  return (
    <div className="appearance" ref={ref}>
      <button className="icon" title="More" aria-expanded={open} onClick={() => setOpen(!open)}>
        <Glyph name="more" text="⋯" />
      </button>
      {open && (
        <div className="appearance-pop menu-pop" role="menu">
          {items
            .filter((i) => !i.hidden)
            .map((i) => (
              <button
                key={i.label}
                role="menuitem"
                onClick={() => {
                  setOpen(false);
                  i.onClick();
                }}
              >
                {i.label}
              </button>
            ))}
        </div>
      )}
    </div>
  );
}

// ------------------------------------------------------------------ bookmark button (players)

/** Bookmarks what's playing now; shows "Bookmarked 12:34" for a moment. */
export function BookmarkButton({ className, iconOnly }: { className?: string; iconOnly?: boolean }) {
  const p = player.usePlayer();
  const s = store.useStore();
  const n = notes.useNotes();
  const [flash, setFlash] = useState<string | null>(null);
  const timer = useRef<number | undefined>(undefined);
  useEffect(() => () => window.clearTimeout(timer.current), []);
  const e = p.episode;
  if (!e) return null;
  const count = notes.bookmarks(n, e.id).length;
  const pod = s.podcasts.get(e.feed_url);
  const add = () => {
    const b = notes.addBookmark(e, pod?.title ?? "", pod?.image_url ?? null, player.snapshot().positionMs);
    setFlash(`Bookmarked ${formatClock(b.position_ms)}`);
    window.clearTimeout(timer.current);
    timer.current = window.setTimeout(() => setFlash(null), 3000);
  };
  return (
    <button
      className={`${className ?? "chip"} ${flash ? "on" : ""}`}
      disabled={!n.available}
      title={n.available ? "Bookmark this moment (keeps the last minute of transcript)" : "Bookmarks aren't set up yet: run 002_transcripts_bookmarks.sql in Supabase"}
      onClick={add}
    >
      <Glyph name={flash ? "bookmarkOn" : "bookmark"} text="🔖" />
      {!iconOnly && <span>{flash ?? (count ? `Bookmark (${count})` : "Bookmark")}</span>}
    </button>
  );
}

/** Opens the transcript of what's playing. */
export function TranscriptButton({ nav, className, iconOnly }: { nav: Nav; className?: string; iconOnly?: boolean }) {
  const p = player.usePlayer();
  if (!p.episode) return null;
  const id = p.episode.id;
  return (
    <button className={className ?? "chip"} title="Transcript" onClick={() => nav.open({ name: "transcript", episodeId: id })}>
      <Glyph name="transcript" text="≡" />
      {!iconOnly && <span>Transcript</span>}
    </button>
  );
}

// ------------------------------------------------------------------ transcript page

export function TranscriptPage({ episodeId, focusMs, nav }: { episodeId: string; focusMs?: number; nav: Nav }) {
  const s = store.useStore();
  const n = notes.useNotes();
  const p = player.usePlayer();
  const info = episodeInfo(episodeId, s, n);
  const e = info.episode;
  const indexed = n.transcripts.get(episodeId);
  const [t, setT] = useState<LoadedTranscript | null | undefined>(undefined); // undefined: still looking
  const [fetching, setFetching] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [following, setFollowing] = useState(focusMs == null);
  const [editing, setEditing] = useState<BookmarkRow | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const transcribing = n.transcribing.get(episodeId);
  const bms = notes.bookmarks(n, episodeId);
  const isLoaded = p.episode?.id === episodeId;

  // Load the saved transcript; if there's none, fetch the feed's (free) when it has one.
  useEffect(() => {
    let cancelled = false;
    setError(null);
    notes
      .loadTranscript(episodeId)
      .then(async (got) => {
        if (cancelled) return;
        if (got || !e?.transcript_url) {
          setT(got);
          return;
        }
        setFetching(true);
        try {
          const feed = await notes.ensureFeedTranscript(e);
          if (!cancelled) setT(feed);
        } finally {
          if (!cancelled) setFetching(false);
        }
      })
      .catch((err) => {
        if (cancelled) return;
        setT(null);
        setError(err instanceof Error ? err.message : String(err));
      });
    return () => {
      cancelled = true;
    };
  }, [episodeId, indexed?.changed_at]);

  const segments = t?.segments ?? [];
  const timed = !!t?.timed;
  const current = isLoaded && timed ? indexAt(segments, p.positionMs) : -1;
  const focus = focusMs != null && timed ? indexAt(segments, focusMs) : -1;

  // Which segment each bookmark falls in.
  const marks = useMemo(() => {
    const m = new Map<number, BookmarkRow[]>();
    if (!timed) return m;
    for (const b of bms) {
      const i = Math.max(0, indexAt(segments, b.position_ms));
      m.set(i, [...(m.get(i) ?? []), b]);
    }
    return m;
  }, [t, n.version]);

  // Follow along while playing; scrolling by hand stops it (Follow brings it back).
  const lineRefs = useRef<(HTMLDivElement | null)[]>([]);
  useEffect(() => {
    const target = following ? current : focus;
    if (target >= 0) lineRefs.current[target]?.scrollIntoView({ block: "center", behavior: following ? "smooth" : "auto" });
  }, [following ? current : focus, following, segments.length]);
  useEffect(() => {
    const stop = () => setFollowing(false);
    window.addEventListener("wheel", stop, { passive: true });
    window.addEventListener("touchmove", stop, { passive: true });
    return () => {
      window.removeEventListener("wheel", stop);
      window.removeEventListener("touchmove", stop);
    };
  }, []);

  const run = (f: () => Promise<void>) => () => void f().catch((err) => setMessage(err instanceof Error ? err.message : String(err)));
  const canTranscribe = !!e && n.available && transcribing?.kind !== "running";

  return (
    <>
      <header className="bar">
        <button className="icon" title="Back" onClick={nav.back}>
          <Glyph name="arrowLeft" text="←" />
        </button>
        <div className="grow ellipsis-col">
          <h1 className="ellipsis">{info.title}</h1>
          <div className="meta ellipsis">
            <PodcastLink feedUrl={info.feedUrl} title={info.podcastTitle} />
            {t ? `${info.podcastTitle ? " · " : ""}${t.source === "deepgram" ? "Transcribed by Deepgram" : t.source === "gemini" ? "Transcribed by Gemini" : "Transcript from the feed"}` : ""}
          </div>
        </div>
        {isLoaded && <BookmarkButton className="icon" iconOnly />}
        <Menu
          items={[
            { label: "Export bookmarks (.md)", onClick: run(() => exportEpisodeBookmarks(episodeId)) },
            { label: "Export transcript (.md)", onClick: run(() => exportTranscript(episodeId)), hidden: !t },
            { label: t && t.source !== "feed" ? "Transcribe again with Deepgram" : "Transcribe with Deepgram", onClick: () => e && void notes.transcribeWithGemini(e), hidden: !canTranscribe },
            { label: "Play from the start", onClick: () => setMessage(playFrom(episodeId, 0)), hidden: !e },
          ]}
        />
      </header>

      {message && (
        <div className="subbar small">
          <span className="error">{message}</span>
          <span className="spacer" />
          <button className="text" onClick={() => setMessage(null)}>
            OK
          </button>
        </div>
      )}

      {transcribing?.kind === "running" && (
        <div className="notice">
          <span className="g-spin" aria-hidden="true" />
          <span className="grow">
            Transcribing with Deepgram{transcribing.parts > 1 ? `: part ${Math.min(transcribing.part + 1, transcribing.parts)} of ${transcribing.parts}` : "…"}. Keep this page open; it usually takes about a minute.
          </span>
          <button className="text" onClick={() => notes.cancelTranscription(episodeId)}>
            Cancel
          </button>
        </div>
      )}
      {transcribing?.kind === "failed" && (
        <div className="notice">
          <span className="grow error">Transcription failed: {transcribing.error}</span>
          {e && (
            <button className="text" onClick={() => void notes.transcribeWithGemini(e)}>
              Retry
            </button>
          )}
        </div>
      )}

      {t === undefined || fetching ? (
        <div className="center muted">{fetching ? "Getting the podcast's transcript…" : "Loading…"}</div>
      ) : !t ? (
        <div className="empty">
          {!n.available ? (
            <p>Transcripts aren't set up yet: run 002_transcripts_bookmarks.sql in the Supabase SQL Editor.</p>
          ) : (
            <>
              <p>{error ? `Couldn't get the podcast's transcript: ${error}` : "This episode has no transcript."}</p>
              {e && transcribing?.kind !== "running" && (
                <>
                  <button className="primary" onClick={() => void notes.transcribeWithGemini(e)}>
                    Transcribe with Deepgram
                  </button>
                  <p className="small">Uses your free Deepgram credit (about a minute per episode).</p>
                </>
              )}
              {!e && <p className="small">This episode isn't in your library any more, so it can't be transcribed.</p>}
            </>
          )}
          {bms.length > 0 && <p className="small">This episode has {bms.length} bookmark{bms.length === 1 ? "" : "s"}; they'll show their quotes once there's a transcript.</p>}
        </div>
      ) : (
        <section className="transcript">
          {!timed && <div className="muted small t-note">This transcript has no timings, so it can't follow along or jump to a moment.</div>}
          {segments.map((seg, i) => {
            const prev = segments[i - 1];
            const showSpeaker = !!seg.sp && seg.sp !== prev?.sp;
            return (
              <div key={i} ref={(el) => {
                lineRefs.current[i] = el;
              }}>
                {marks.get(i)?.map((b) => (
                  <button key={b.id} className="t-mark" onClick={() => setEditing(b)}>
                    <Glyph name="bookmarkOn" text="🔖" /> {formatClock(b.position_ms)}
                    {b.note ? ` · ${b.note}` : ""}
                  </button>
                ))}
                {showSpeaker && <div className="t-speaker">{speakerName(seg.sp!)}</div>}
                <div
                  className={`t-line ${i === current ? "now" : ""} ${i === focus && !following ? "focus" : ""} ${timed ? "timed" : ""}`}
                  onClick={timed ? () => setMessage(playFrom(episodeId, seg.s)) : undefined}
                  title={timed ? "Play from here" : undefined}
                >
                  {timed && <span className="time">{formatClock(seg.s)}</span>}
                  <span>{seg.t}</span>
                </div>
              </div>
            );
          })}
        </section>
      )}

      {isLoaded && timed && !following && (
        <button className="follow primary" onClick={() => setFollowing(true)}>
          Follow
        </button>
      )}
      {editing && <BookmarkDialog bookmark={editing} transcript={t ?? null} onClose={() => setEditing(null)} />}
    </>
  );
}

// ------------------------------------------------------------------ bookmark dialog

export function BookmarkDialog({ bookmark, transcript, onClose }: { bookmark: BookmarkRow; transcript: LoadedTranscript | null; onClose: () => void }) {
  const [start, setStart] = useState(bookmark.start_ms);
  const [end, setEnd] = useState(bookmark.end_ms);
  const [note, setNote] = useState(bookmark.note);
  const [confirmDelete, setConfirmDelete] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const q = transcript ? quote(transcript.segments, transcript.timed, start, end) : null;
  const setStartBy = (d: number) => setStart((v) => Math.max(0, Math.min(end - 1000, v + d)));
  const setEndBy = (d: number) => setEnd((v) => Math.max(start + 1000, v + d));
  const save = () => {
    if (start !== bookmark.start_ms || end !== bookmark.end_ms || note !== bookmark.note) notes.updateBookmark(bookmark, { start_ms: start, end_ms: end, note });
    onClose();
  };
  return (
    <div className="scrim" onClick={onClose}>
      <div className="sheet" role="dialog" aria-label="Bookmark" onClick={(ev) => ev.stopPropagation()}>
        <div className="sheet-title">Bookmark at {formatClock(bookmark.position_ms)}</div>
        <div className="meta">
          {bookmark.episode_title}
          {bookmark.podcast_title ? ` · ${bookmark.podcast_title}` : ""}
        </div>
        <div className="bm-window">
          <span className="muted small">
            Quote {formatClock(start)} to {formatClock(end)}
          </span>
          <div className="actions">
            <button onClick={() => setStartBy(-15_000)}>Start −15 s</button>
            <button onClick={() => setStartBy(15_000)}>Start +15 s</button>
            <button onClick={() => setEndBy(-5_000)}>End −5 s</button>
            <button onClick={() => setEndBy(15_000)}>End +15 s</button>
          </div>
          <blockquote className="bm-quote">{q ?? <span className="muted">No transcript for this moment yet.</span>}</blockquote>
        </div>
        <label className="bm-note">
          <span className="muted small">Note</span>
          <textarea rows={3} value={note} placeholder="Add a note" onChange={(ev) => setNote(ev.target.value)} />
        </label>
        {message && <div className="error small pad">{message}</div>}
        <div className="actions">
          <button className="primary" onClick={save}>
            Save
          </button>
          <button onClick={() => setMessage(playFrom(bookmark.episode_id, start))}>Play from start</button>
          <span className="spacer" />
          {confirmDelete ? (
            <>
              <span className="small">Delete this bookmark?</span>
              <button
                className="danger"
                onClick={() => {
                  notes.deleteBookmark(bookmark);
                  onClose();
                }}
              >
                Delete
              </button>
              <button onClick={() => setConfirmDelete(false)}>Keep</button>
            </>
          ) : (
            <button onClick={() => setConfirmDelete(true)}>Delete</button>
          )}
        </div>
      </div>
    </div>
  );
}

// ------------------------------------------------------------------ bookmarks page

export function BookmarksPage({ nav }: { nav: Nav }) {
  const s = store.useStore();
  const n = notes.useNotes();
  const [transcripts, setTranscripts] = useState<Map<string, LoadedTranscript | null>>(new Map());
  const [editing, setEditing] = useState<BookmarkRow | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  // Groups by episode, most recently bookmarked first.
  const groups = useMemo(() => {
    const m = new Map<string, BookmarkRow[]>();
    for (const b of notes.bookmarks(n)) m.set(b.episode_id, [...(m.get(b.episode_id) ?? []), b]);
    return [...m.entries()].map(([id, list]) => ({ id, list: list.sort((a, b) => a.position_ms - b.position_ms) }));
  }, [n.version]);

  // Load each episode's transcript for the quotes (one at a time; they're cached afterwards).
  const ids = groups.map((g) => `${g.id}:${n.transcripts.get(g.id)?.changed_at ?? 0}`).join(",");
  useEffect(() => {
    let cancelled = false;
    void (async () => {
      for (const g of groups) {
        if (cancelled) return;
        const t = n.transcripts.has(g.id) ? await notes.loadTranscript(g.id).catch(() => null) : null;
        if (!cancelled) setTranscripts((m) => new Map(m).set(g.id, t));
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [ids]);

  const exportAll = async () => {
    setBusy(true);
    try {
      const out = [];
      for (const g of groups) {
        const info = episodeInfo(g.id, s, n);
        out.push({ episode: exportEpisode(info), bookmarks: g.list, transcript: await notes.loadTranscript(g.id).catch(() => null) });
      }
      downloadMarkdown(exportFileName("Earshot bookmarks"), allBookmarksMd(out, Date.now()));
    } finally {
      setBusy(false);
    }
  };
  const run = (f: () => Promise<void>) => () => void f().catch((err) => setMessage(err instanceof Error ? err.message : String(err)));
  const total = groups.reduce((sum, g) => sum + g.list.length, 0);

  return (
    <>
      <header className="bar">
        <button className="icon" title="Back" onClick={nav.back}>
          <Glyph name="arrowLeft" text="←" />
        </button>
        <h1 className="grow ellipsis">Bookmarks</h1>
        <button className="text" disabled={!total || busy} onClick={run(exportAll)} title="Download every bookmark as one Markdown file">
          {busy ? "Exporting…" : "Export all"}
        </button>
      </header>
      {message && (
        <div className="subbar small">
          <span className="error">{message}</span>
          <span className="spacer" />
          <button className="text" onClick={() => setMessage(null)}>
            OK
          </button>
        </div>
      )}
      {!n.available && <div className="empty">Bookmarks aren't set up yet: run 002_transcripts_bookmarks.sql in the Supabase SQL Editor.</div>}
      {n.available && !total && <div className="empty">No bookmarks yet. Use Bookmark in the player (or on the phone, in the car or the notification) to keep a moment with the minute of transcript before it.</div>}
      <section className="list">
        {groups.map((g) => {
          const info = episodeInfo(g.id, s, n);
          const t = transcripts.get(g.id) ?? null;
          return (
            <div key={g.id} className="bm-group">
              <div className="row bm-head">
                {info.feedUrl ? <ArtLink url={info.image} size={40} feedUrl={info.feedUrl} /> : <Art url={info.image} size={40} />}
                <button className="row-main" onClick={() => nav.open({ name: "transcript", episodeId: g.id })}>
                  <div className="title">{info.title}</div>
                  <div className="meta">
                    <PodcastLink feedUrl={info.feedUrl} title={info.podcastTitle} /> · {g.list.length} bookmark{g.list.length === 1 ? "" : "s"}
                  </div>
                </button>
                <Menu
                  items={[
                    { label: "Open transcript", onClick: () => nav.open({ name: "transcript", episodeId: g.id }) },
                    { label: "Export bookmarks (.md)", onClick: run(() => exportEpisodeBookmarks(g.id)) },
                    { label: "Export transcript (.md)", onClick: run(() => exportTranscript(g.id)), hidden: !n.transcripts.has(g.id) },
                  ]}
                />
              </div>
              {g.list.map((b) => {
                const q = t ? quote(t.segments, t.timed, b.start_ms, b.end_ms) : null;
                return (
                  <div key={b.id} className="row bm-row">
                    <button className="icon play" title={`Play from ${formatClock(b.start_ms)}`} onClick={() => setMessage(playFrom(b.episode_id, b.start_ms))}>
                      <Glyph name="play" text="▶" />
                    </button>
                    <button className="row-main" onClick={() => setEditing(b)}>
                      <div className="time">{formatClock(b.position_ms)}</div>
                      {q ? <div className="bm-q">“{q}”</div> : <div className="meta">{n.transcripts.has(b.episode_id) ? "Loading quote…" : "No transcript for this moment yet."}</div>}
                      {b.note && <div className="bm-n">{b.note}</div>}
                    </button>
                  </div>
                );
              })}
            </div>
          );
        })}
      </section>
      {editing && <BookmarkDialog bookmark={editing} transcript={transcripts.get(editing.episode_id) ?? null} onClose={() => setEditing(null)} />}
    </>
  );
}

// ------------------------------------------------------------------ search page

export function TranscriptSearchPage({ nav }: { nav: Nav }) {
  const s = store.useStore();
  const n = notes.useNotes();
  const [query, setQuery] = useState(() => lastQuery);
  const [results, setResults] = useState<notes.SearchHit[]>(() => lastResults);
  const [searching, setSearching] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const terms = useMemo(() => searchTerms(normalize(query)), [query]);

  useEffect(() => {
    lastQuery = query;
    if (!terms.length) {
      setResults((lastResults = []));
      setError(null);
      return;
    }
    let cancelled = false;
    const timer = window.setTimeout(() => {
      setSearching(true);
      notes
        .searchTranscripts(query)
        .then((r) => {
          if (cancelled) return;
          setResults((lastResults = r));
          setError(null);
        })
        .catch((err) => !cancelled && setError(err instanceof Error ? err.message : String(err)))
        .finally(() => !cancelled && setSearching(false));
    }, 300);
    return () => {
      cancelled = true;
      window.clearTimeout(timer);
    };
  }, [terms.join("\u0000")]);

  const count = n.transcripts.size;
  return (
    <>
      <header className="bar">
        <button className="icon" title="Back" onClick={nav.back}>
          <Glyph name="arrowLeft" text="←" />
        </button>
        <input className="search grow" autoFocus placeholder="Search transcripts" value={query} onChange={(ev) => setQuery(ev.target.value)} onKeyDown={(ev) => ev.key === "Escape" && setQuery("")} />
        {query && (
          <button className="icon" title="Clear" onClick={() => setQuery("")}>
            <Glyph name="close" text="✕" />
          </button>
        )}
      </header>
      <div className="subbar small muted">
        {searching && <span className="g-spin small-spin" aria-hidden="true" />}
        {error ? (
          <span className="error">{error}</span>
        ) : !terms.length ? (
          `${count} saved transcript${count === 1 ? "" : "s"}. Words can be in any order; put "quotes" around a phrase.`
        ) : !results.length && !searching ? (
          `No matches in ${count} transcripts`
        ) : (
          `${results.length} episode${results.length === 1 ? "" : "s"}`
        )}
      </div>
      <section className="list">
        {results.map((hit) => {
          const info = episodeInfo(hit.episodeId, s, n);
          return (
            <div key={hit.episodeId} className="bm-group">
              <div className="row bm-head s-head">
                {info.feedUrl ? <ArtLink url={info.image} size={36} feedUrl={info.feedUrl} /> : <Art url={info.image} size={36} />}
                <button className="row-main" onClick={() => nav.open({ name: "transcript", episodeId: hit.episodeId })}>
                  <div className="title">{info.title}</div>
                  <div className="meta">
                    <PodcastLink feedUrl={info.feedUrl} title={info.podcastTitle} />
                  </div>
                </button>
              </div>
              {hit.matches.map((m, i) => (
                <button key={`${m.s}-${i}`} className="s-hit" onClick={() => nav.open({ name: "transcript", episodeId: hit.episodeId, focusMs: m.s })}>
                  <span className="time">{formatClock(m.s)}</span>
                  <span>{highlight(m.t, terms)}</span>
                </button>
              ))}
            </div>
          );
        })}
      </section>
    </>
  );
}

// Coming back from a result keeps the search.
let lastQuery = "";
let lastResults: notes.SearchHit[] = [];

/** The text with each search term marked. */
function highlight(text: string, terms: string[]): React.ReactNode {
  const lower = normalize(text);
  const marks = new Array<boolean>(text.length).fill(false);
  for (const t of terms) {
    for (let i = lower.indexOf(t); i >= 0; i = lower.indexOf(t, i + t.length)) for (let k = i; k < Math.min(text.length, i + t.length); k++) marks[k] = true;
  }
  const out: React.ReactNode[] = [];
  for (let i = 0; i < text.length; ) {
    let j = i;
    while (j < text.length && marks[j] === marks[i]) j++;
    out.push(marks[i] ? <mark key={i}>{text.slice(i, j)}</mark> : text.slice(i, j));
    i = j;
  }
  return out;
}

