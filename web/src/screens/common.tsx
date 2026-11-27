import { useState } from "react";
import type { Route } from "../App";
import type { ParsedFeed } from "../feed";
import * as player from "../player";
import * as store from "../store";
import type { EpisodeRow } from "../types";
import { episodeMeta, safeHtml } from "../util";

export interface Nav {
  open: (r: Route) => void;
  back: () => void;
  home: () => void;
}

/** Playing an episode puts it at the top of the queue, as on the phone. */
export function playEpisode(e: EpisodeRow, feed?: ParsedFeed) {
  store.queueFirst(e, feed);
  const row = store.getState().episodes.get(e.id) ?? e;
  player.load(row, true);
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
        <Art url={e.image_url ?? art} />
        <button className="row-main" onClick={() => setOpen(true)}>
          <div className={`title ${isCurrent ? "current" : ""}`}>{e.title}</div>
          <div className="meta">{episodeMeta(e, podcastTitle)}</div>
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
          {isCurrent && p.playing ? "❚❚" : "▶"}
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
  return (
    <div className="scrim" onClick={onClose}>
      <div className="sheet" onClick={(ev) => ev.stopPropagation()}>
        <div className="sheet-title">{e.title}</div>
        <div className="meta">{episodeMeta(e, podcastTitle)}</div>
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
        <div className="notes" dangerouslySetInnerHTML={{ __html: safeHtml(e.description) }} />
      </div>
    </div>
  );
}
