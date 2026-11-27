import { useState } from "react";
import { supabase } from "../supabase";
import * as store from "../store";
import { Art, EpisodeItem, type Nav } from "./common";

type Tab = "queue" | "new" | "latest" | "podcasts";

export function Library({ nav }: { nav: Nav }) {
  const s = store.useStore();
  const [tab, setTab] = useState<Tab>("queue");
  const [dragId, setDragId] = useState<string | null>(null);
  const queue = store.queue(s);
  const inbox = store.inbox(s);
  const lib = store.library(s);
  const titleOf = (feedUrl: string) => s.podcasts.get(feedUrl)?.title;
  const artOf = (feedUrl: string) => s.podcasts.get(feedUrl)?.image_url;

  return (
    <>
      <header className="bar">
        <h1>Earshot</h1>
        <span className="spacer" />
        {s.error && <span className="error small" title={s.error}>Sync problem</span>}
        <button className="icon" title="Sync now" onClick={() => void store.pull()}>
          {s.syncing ? "…" : "⟳"}
        </button>
        <button className="icon" title="Add podcast" onClick={() => nav.open({ name: "add" })}>
          ＋
        </button>
        <button className="text" title="Sign out" onClick={() => void store.flush().then(() => supabase.auth.signOut()).then(store.resetStore)}>
          Sign out
        </button>
      </header>
      <nav className="tabs">
        <button className={tab === "queue" ? "on" : ""} onClick={() => setTab("queue")}>
          Queue {queue.length || ""}
        </button>
        <button className={tab === "new" ? "on" : ""} onClick={() => setTab("new")}>
          New Episodes {inbox.length || ""}
        </button>
        <button className={tab === "latest" ? "on" : ""} onClick={() => setTab("latest")}>
          Latest
        </button>
        <button className={tab === "podcasts" ? "on" : ""} onClick={() => setTab("podcasts")}>
          Podcasts {lib.length || ""}
        </button>
      </nav>

      {!s.loaded && <div className="center muted">Loading your library…</div>}

      {s.loaded && tab === "queue" && (
        <section>
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
                  ✕
                </button>
              }
            />
          ))}
        </section>
      )}

      {s.loaded && tab === "new" && (
        <section>
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
                    ⤓
                  </button>
                  <button className="icon" title="Archive" onClick={() => store.archive(e)}>
                    ✕
                  </button>
                </>
              }
            />
          ))}
        </section>
      )}

      {s.loaded && tab === "latest" && (
        <section>
          {store.latest(s).map((e) => (
            <EpisodeItem key={e.id} e={e} podcastTitle={titleOf(e.feed_url)} art={artOf(e.feed_url)} />
          ))}
        </section>
      )}

      {s.loaded && tab === "podcasts" && (
        <section>
          {lib.map((p) => {
            const unplayed = [...s.episodes.values()].filter((e) => e.feed_url === p.feed_url && e.in_inbox).length;
            return (
              <div className="row" key={p.feed_url}>
                <Art url={p.image_url} size={44} />
                <button className="row-main" onClick={() => nav.open({ name: "podcast", feedUrl: p.feed_url })}>
                  <div className="title">{p.title}</div>
                  <div className="meta">{p.author}</div>
                </button>
                {unplayed > 0 && <span className="badge">{unplayed}</span>}
                <button
                  className={`icon pin ${p.pinned ? "on" : ""}`}
                  title={p.pinned ? "Unpin" : "Pin to top"}
                  onClick={() => store.setPinned(p.feed_url, !p.pinned)}
                >
                  📌
                </button>
              </div>
            );
          })}
        </section>
      )}
    </>
  );
}
