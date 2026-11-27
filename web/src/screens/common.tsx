import { useState } from "react";
import type { Route } from "../App";
import type { Tab } from "./Library";
import type { ParsedFeed } from "../feed";
import * as player from "../player";
import * as store from "../store";
import type { EpisodeRow } from "../types";
import { episodeMeta, safeHtml } from "../util";
import * as notes from "../notes";
import { Glyph } from "./Icons";

export interface Nav {
  open: (r: Route) => void;
  back: () => void;
  home: () => void;
  showTab: (t: Tab) => void;
}

/** The app's navigation, for places that aren't handed it (the episode sheet). Set by App. */
export const navHolder: { current: Nav | null } = { current: null };

/** Playing an episode puts it at the top of the queue, as on the phone. */
export function playEpisode(e: EpisodeRow, feed?: ParsedFeed) {
  store.queueFirst(e, feed);
  const row = store.getState().episodes.get(e.id) ?? e;
  player.load(row, true);
}

/** Opens a podcast's page (phase 7: tapping a podcast's name or artwork anywhere). */
export function openPodcast(feedUrl: string) {
  if (feedUrl) navHolder.current?.open({ name: "podcast", feedUrl });
}

/**
 * A podcast's name that opens the podcast. Used inside rows that are buttons themselves, so it's a
 * span with the link role that stops the click from reaching the row.
 */
export function PodcastLink({ feedUrl, title, className }: { feedUrl: string; title: string | undefined; className?: string }) {
  if (!title) return null;
  if (!feedUrl) return <span className={className}>{title}</span>;
  const go = (ev: React.SyntheticEvent) => {
    ev.stopPropagation();
    ev.preventDefault();
    openPodcast(feedUrl);
  };
  return (
    <span
      role="link"
      tabIndex={0}
      className={`plink ${className ?? ""}`}
      title={`Open ${title}`}
      onClick={go}
      onKeyDown={(ev) => (ev.key === "Enter" || ev.key === " ") && go(ev)}
    >
      {title}
    </span>
  );
}

/** Artwork that opens its podcast when clicked. */
export function ArtLink({ url, size = 40, feedUrl }: { url: string | null | undefined; size?: number; feedUrl: string }) {
  return (
    <button className="art-button" title="Open podcast" onClick={() => openPodcast(feedUrl)}>
      <Art url={url} size={size} />
    </button>
  );
}

export function Art({ url, size = 40 }: { url: string | null | undefined; size?: number }) {
  return url ? (
    <img className="art" src={url} width={size} height={size} loading="lazy" alt="" />
  ) : (
    <div className="art" style={{ width: size, height: size }} />
  );
}

export function EpisodeItem({
  e,
  podcastTitle,
  art,
  snippet,
  feed,
  extra,
  draggable,
  onDragStart,
  onDropOn,
}: {
  e: EpisodeRow;
  podcastTitle?: string;
  art?: string | null;
  snippet?: string | null;
  feed?: ParsedFeed;
  extra?: React.ReactNode;
  draggable?: boolean;
  onDragStart?: () => void;
  onDropOn?: () => void;
}) {
  const p = player.usePlayer();
  const [open, setOpen] = useState(false);
  const isCurrent = p.episode?.id === e.id;
  const progress = !e.played && e.position_ms > 0 && e.duration_ms > 0 ? Math.min(1, e.position_ms / e.duration_ms) : 0;
  return (
    <>
      <div
        className={`row ${e.played ? "played" : ""}`}
        draggable={draggable}
        onDragStart={onDragStart}
        onDragOver={(ev) => onDropOn && ev.preventDefault()}
        onDrop={(ev) => {
          ev.preventDefault();
          onDropOn?.();
        }}
      >
        {draggable && <span className="handle" title="Drag to reorder">≡</span>}
        {podcastTitle ? <ArtLink url={e.image_url ?? art} feedUrl={e.feed_url} /> : <Art url={e.image_url ?? art} />}
        <button className="row-main" onClick={() => setOpen(true)}>
          <div className={`title ${isCurrent ? "current" : ""}`}>{e.title}</div>
          <div className="meta">
            {podcastTitle ? (
              <>
                <PodcastLink feedUrl={e.feed_url} title={podcastTitle} />
                {episodeMeta(e) ? ` • ${episodeMeta(e)}` : ""}
              </>
            ) : (
              episodeMeta(e)
            )}
          </div>
          {snippet && <div className="meta snippet">{snippet}</div>}
          {progress > 0 && (
            <div className="progress">
              <div style={{ width: `${progress * 100}%` }} />
            </div>
          )}
        </button>
        {extra}
        <button
          className="icon play"
          title={isCurrent && p.playing ? "Pause" : "Play"}
          onClick={() => (isCurrent ? player.toggle() : playEpisode(e, feed))}
        >
          <Glyph name={isCurrent && p.playing ? "pause" : "play"} text={isCurrent && p.playing ? "❚❚" : "▶"} />
        </button>
      </div>
      {open && <EpisodeSheet e={store.getState().episodes.get(e.id) ?? e} podcastTitle={podcastTitle} feed={feed} onClose={() => setOpen(false)} />}
    </>
  );
}

export function EpisodeSheet({ e, podcastTitle, feed, onClose }: { e: EpisodeRow; podcastTitle?: string; feed?: ParsedFeed; onClose: () => void }) {
  const act = (f: () => void) => () => {
    f();
    onClose();
  };
  const n = notes.useNotes();
  const hasTranscript = n.transcripts.has(e.id);
  const transcribing = n.transcribing.get(e.id);
  const bookmarkCount = notes.bookmarks(n, e.id).length;
  return (
    <div className="scrim" onClick={onClose}>
      <div className="sheet" onClick={(ev) => ev.stopPropagation()}>
        <div className="sheet-title">{e.title}</div>
        <div className="meta">
          {podcastTitle ? (
            <>
              <PodcastLink feedUrl={e.feed_url} title={podcastTitle} />
              {episodeMeta(e) ? ` • ${episodeMeta(e)}` : ""}
            </>
          ) : (
            episodeMeta(e)
          )}
        </div>
        <div className="actions">
          <button onClick={act(() => playEpisode(e, feed))}>Play</button>
          <button onClick={act(() => store.queueNext(e, feed))}>Play next</button>
          {e.queue_position == null ? (
            <button onClick={act(() => store.queueLast(e, feed))}>Play last</button>
          ) : (
            <button onClick={act(() => store.removeFromQueue(e))}>Remove from queue</button>
          )}
          <button onClick={act(() => store.setPlayed(e, !e.played, feed))}>{e.played ? "Mark unplayed" : "Mark played"}</button>
          {e.in_inbox && <button onClick={act(() => store.archive(e))}>Archive</button>}
        </div>
        {n.available && (
          <div className="actions">
            <button onClick={act(() => navHolder.current?.open({ name: "transcript", episodeId: e.id }))}>
              {transcribing?.kind === "running" ? "Transcript (transcribing…)" : bookmarkCount ? `Transcript · ${bookmarkCount} bookmark${bookmarkCount === 1 ? "" : "s"}` : "Transcript"}
            </button>
            {!hasTranscript && !e.transcript_url && transcribing?.kind !== "running" && (
              <button
                title="Uses your free Deepgram credit; keep the page open while it runs (about a minute)"
                onClick={act(() => {
                  void notes.transcribeWithGemini(e);
                  navHolder.current?.open({ name: "transcript", episodeId: e.id });
                })}
              >
                Transcribe
              </button>
            )}
          </div>
        )}
        <div className="notes" dangerouslySetInnerHTML={{ __html: safeHtml(e.description) }} />
      </div>
    </div>
  );
}

/** A player error, with a button that copies everything needed to look into it (plus recent errors). */
export function PlayerError({ className }: { className?: string }) {
  const p = player.usePlayer();
  const [copied, setCopied] = useState(false);
  if (!p.error) return null;
  const copy = async () => {
    const earlier = player.errorLog().slice(1, 6);
    const text = [p.errorDetails ?? p.error, ...(earlier.length ? ["", "Earlier errors in this browser:", ...earlier.map((e) => "---\n" + e)] : [])].join("\n");
    try {
      await navigator.clipboard.writeText(text);
    } catch {
      // Older browsers: copy through a hidden text box.
      const t = document.createElement("textarea");
      t.value = text;
      document.body.appendChild(t);
      t.select();
      document.execCommand("copy");
      t.remove();
    }
    setCopied(true);
    window.setTimeout(() => setCopied(false), 2000);
  };
  return (
    <div className={`player-error ${className ?? ""}`}>
      <span className="error small" title={p.error}>
        {p.error}
      </span>
      <button className="text small" title="Copy the error details to paste into a message" onClick={() => void copy()}>
        {copied ? "Copied" : "Copy details"}
      </button>
    </div>
  );
}
