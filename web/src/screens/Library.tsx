import { useState } from "react";
import { setAppearance, useAppearance, useArtColor } from "../appearance";
import * as player from "../player";
import * as store from "../store";
import { formatDuration } from "../util";
import { Art, EpisodeItem, type Nav } from "./common";
import { Glyph, Icon } from "./Icons";
import { AppearanceMenu, Hero, newCounts, signOut } from "./Sidebar";

export type Tab = "queue" | "new" | "latest" | "podcasts";

export function Library({ nav, tab }: { nav: Nav; tab: Tab }) {
  const s = store.useStore();
  const a = useAppearance();
  const glow = a.theme === "glow";
  const p = player.usePlayer();
  const [dragId, setDragId] = useState<string | null>(null);
  const queue = store.queue(s);
  const inbox = store.inbox(s);
  const lib = store.library(s);
  const titleOf = (feedUrl: string) => s.podcasts.get(feedUrl)?.title;
  const artOf = (feedUrl: string) => s.podcasts.get(feedUrl)?.image_url;

  // Glow tints the header with the artwork of what's playing (or the top of the queue).
  const tintEpisode = p.episode ?? queue[0];
  const tint = useArtColor(tintEpisode ? (tintEpisode.image_url ?? artOf(tintEpisode.feed_url)) : null, tintEpisode?.feed_url ?? "earshot");

  const left = queue.reduce((sum, e) => sum + Math.max(0, (e.duration_ms || 0) - (e.position_ms || 0)), 0);
  const counts = newCounts(s);

  const header = glow ? (
    <Hero
      tint={tint}
      eyebrow={{ queue: "Up next", new: "From your podcasts", latest: "Everything recent", podcasts: "Your library" }[tab]}
      title={{ queue: "Queue", new: "New Episodes", latest: "Latest", podcasts: "Podcasts" }[tab]}
      summary={
        tab === "queue"
          ? queue.length
            ? `${queue.length} episode${queue.length === 1 ? "" : "s"}${left > 0 ? ` · about ${formatDuration(left)} left` : ""}`
            : "Nothing queued"
          : tab === "new"
            ? inbox.length
              ? `${inbox.length} new`
              : "All caught up"
            : tab === "latest"
              ? "Newest episodes across your podcasts"
              : `${lib.length} podcast${lib.length === 1 ? "" : "s"}${lib.some((x) => x.pinned) ? ` · ${lib.filter((x) => x.pinned).length} pinned` : ""}`
      }
    >
      {/* At phone width there's no sidebar, so its actions sit here. */}
      <div className="hero-actions">
        {s.error && (
          <span className="error small" title={s.error}>
            Sync problem
          </span>
        )}
        <button className="icon" title="Sync now" onClick={() => void store.pull()}>
          <Icon name="sync" />
        </button>
        <button className="icon" title="Add podcast" onClick={() => nav.open({ name: "add" })}>
          <Icon name="plus" />
        </button>
        <AppearanceMenu />
        <button className="icon" title="Sign out" onClick={signOut}>
          <Icon name="signOut" />
        </button>
      </div>
    </Hero>
  ) : (
    <>
      <header className="bar">
        <h1>Earshot</h1>
        <span className="spacer" />
        {s.error && (
          <span className="error small" title={s.error}>
            Sync problem
          </span>
        )}
        <button className="icon" title="Sync now" onClick={() => void store.pull()}>
          {s.syncing ? "…" : "⟳"}
        </button>
        <button className="icon" title="Add podcast" onClick={() => nav.open({ name: "add" })}>
          ＋
        </button>
        <AppearanceMenu />
        <button className="text" title="Sign out" onClick={signOut}>
          Sign out
        </button>
      </header>
      <nav className="tabs">
        <button className={tab === "queue" ? "on" : ""} onClick={() => nav.showTab("queue")}>
          Queue {queue.length || ""}
        </button>
        <button className={tab === "new" ? "on" : ""} onClick={() => nav.showTab("new")}>
          New Episodes {inbox.length || ""}
        </button>
        <button className={tab === "latest" ? "on" : ""} onClick={() => nav.showTab("latest")}>
          Latest
        </button>
        <button className={tab === "podcasts" ? "on" : ""} onClick={() => nav.showTab("podcasts")}>
          Podcasts {lib.length || ""}
        </button>
      </nav>
    </>
  );

  const pinButton = (feedUrl: string, pinned: boolean) => (
    <button className={`icon pin ${pinned ? "on" : ""}`} title={pinned ? "Unpin" : "Pin to top"} aria-pressed={pinned} onClick={() => store.setPinned(feedUrl, !pinned)}>
      📌
    </button>
  );

  return (
    <>
      {header}

      {!s.loaded && <div className="center muted">Loading your library…</div>}

      {s.loaded && tab === "queue" && (
        <section className="list">
          {queue.length === 0 && <div className="empty">Your queue is empty. Use Play next or Play last on any episode, or queue from New Episodes.</div>}
          {queue.map((e, i) => (
            <EpisodeItem
              key={e.id}
              e={e}
              podcastTitle={titleOf(e.feed_url)}
              art={artOf(e.feed_url)}
              draggable
              onDragStart={() => setDragId(e.id)}
              onDropOn={() => {
                if (dragId && dragId !== e.id) store.moveInQueue(dragId, i);
                setDragId(null);
              }}
              extra={
                <button className="icon" title="Remove from queue" onClick={() => store.removeFromQueue(e)}>
                  <Glyph name="close" text="✕" />
                </button>
              }
            />
          ))}
        </section>
      )}

      {s.loaded && tab === "new" && (
        <section className="list">
          {inbox.length === 0 ? (
            <div className="empty">All caught up. New episodes from your podcasts show up here.</div>
          ) : (
            <div className="subbar">
              <span className="muted small">{inbox.length} new</span>
              <span className="spacer" />
              <button className="text" onClick={store.archiveAll}>
                Archive all
              </button>
            </div>
          )}
          {inbox.map((e) => (
            <EpisodeItem
              key={e.id}
              e={e}
              podcastTitle={titleOf(e.feed_url)}
              art={artOf(e.feed_url)}
              extra={
                <>
                  <button className="icon" title="Add to the end of the queue" onClick={() => store.queueLast(e)}>
                    <Glyph name="queueLast" text="⤓" />
                  </button>
                  <button className="icon" title="Archive" onClick={() => store.archive(e)}>
                    <Glyph name="close" text="✕" />
                  </button>
                </>
              }
            />
          ))}
        </section>
      )}

      {s.loaded && tab === "latest" && (
        <section className="list">
          {store.latest(s).map((e) => (
            <EpisodeItem key={e.id} e={e} podcastTitle={titleOf(e.feed_url)} art={artOf(e.feed_url)} />
          ))}
        </section>
      )}

      {s.loaded && tab === "podcasts" && glow && (
        <div className="subbar view-switch">
          <div className="seg" role="group" aria-label="Show podcasts as">
            <button aria-pressed={a.podcastView === "grid"} onClick={() => setAppearance({ podcastView: "grid" })}>
              Grid
            </button>
            <button aria-pressed={a.podcastView === "list"} onClick={() => setAppearance({ podcastView: "list" })}>
              List
            </button>
          </div>
          <span className="spacer" />
          <button className="text" onClick={() => nav.open({ name: "add" })}>
            Add podcast
          </button>
        </div>
      )}

      {s.loaded && tab === "podcasts" && glow && a.podcastView === "grid" && (
        <section className="podcast-grid">
          {lib.length === 0 && <div className="empty">No podcasts yet. Use Add podcast to search or import.</div>}
          {lib.map((pod) => {
            const n = counts.get(pod.feed_url) ?? 0;
            return (
              <div className="tile" key={pod.feed_url}>
                <button className="tile-open" onClick={() => nav.open({ name: "podcast", feedUrl: pod.feed_url })}>
                  <span className="tile-art">
                    <Art url={pod.image_url} size={160} />
                    {n > 0 && <span className="tile-count">{n} new</span>}
                  </span>
                  <span className="tile-title">{pod.title}</span>
                  <span className="tile-meta ellipsis">{pod.author}</span>
                </button>
                {pinButton(pod.feed_url, pod.pinned)}
              </div>
            );
          })}
        </section>
      )}

      {s.loaded && tab === "podcasts" && (!glow || a.podcastView === "list") && (
        <section className="list">
          {lib.map((pod) => {
            const unplayed = counts.get(pod.feed_url) ?? 0;
            return (
              <div className="row" key={pod.feed_url}>
                <Art url={pod.image_url} size={44} />
                <button className="row-main" onClick={() => nav.open({ name: "podcast", feedUrl: pod.feed_url })}>
                  <div className="title">{pod.title}</div>
                  <div className="meta">{pod.author}</div>
                </button>
                {unplayed > 0 && <span className="badge">{unplayed}</span>}
                {pinButton(pod.feed_url, pod.pinned)}
              </div>
            );
          })}
        </section>
      )}
    </>
  );
}
