import { useMemo, useState } from "react";
import * as store from "../store";
import { DAY_CHOICES, DEFAULT_FILTERS, filtersOf, LIMIT_CHOICES, select, SORT_LABELS, STATUS_LABELS, summary, type PlaylistFilters, type PlaylistSort, type PlaylistStatus } from "../playlists";
import { INBOX_DAY_CHOICES, INBOX_KEEP_CHOICES, MINUTE_CHOICES, NEW_EPISODE_ACTIONS, type NewEpisodeAction, type PodcastRow } from "../types";
import { feedKey } from "../util";
import { EpisodeItem, type Nav } from "./common";
import { Glyph, Icon } from "./Icons";

// Smart playlists (phase 7) and the automation settings: the Playlists tab, a playlist's page, the
// playlist editor, a podcast's rules panel and the app-wide tidy-up panel.

function useTitles(s: store.State) {
  return useMemo(() => {
    const byKey = new Map([...s.podcasts.values()].map((p) => [feedKey(p.feed_url), p.title]));
    return (url: string) => byKey.get(feedKey(url));
  }, [s.podcasts, s.version]);
}

const NOT_READY = "Smart playlists and rules aren't set up yet: run 003_automation_playlists.sql in the Supabase SQL Editor.";

/** The Playlists tab: each playlist with how many episodes it has now. */
export function PlaylistsTab({ nav }: { nav: Nav }) {
  const s = store.useStore();
  const [creating, setCreating] = useState(false);
  const titleOf = useTitles(s);
  const list = store.playlists(s);
  if (!s.phase7) return <div className="empty">{NOT_READY}</div>;
  return (
    <section className="list">
      <div className="subbar">
        <span className="muted small">Smart playlists keep themselves up to date. The web shows the episodes your phone has synced (the last 60 days, plus anything started or new).</span>
        <span className="spacer" />
        <button className="text" onClick={() => setCreating(true)}>
          New playlist
        </button>
      </div>
      {list.length === 0 && (
        <div className="empty">
          <p>No playlists yet. For example "Short ones": unplayed, under 30 minutes.</p>
          <button className="primary" onClick={() => setCreating(true)}>
            New playlist
          </button>
        </div>
      )}
      {list.map((p) => {
        const f = filtersOf(p);
        const n = select(f, s.episodes.values(), s.podcasts).length;
        return (
          <div className="row" key={p.id}>
            <span className="pl-icon">
              <Icon name="playlists" size={22} />
            </span>
            <button className="row-main" onClick={() => nav.open({ name: "playlist", id: p.id })}>
              <div className="title">{p.name}</div>
              <div className="meta">{summary(f, titleOf)}</div>
            </button>
            <span className="badge">{n}</span>
          </div>
        );
      })}
      {creating && (
        <PlaylistEditor
          title="New playlist"
          name=""
          filters={DEFAULT_FILTERS}
          onSave={(name, f) => {
            setCreating(false);
            const id = store.createPlaylist(name, f);
            nav.open({ name: "playlist", id });
          }}
          onClose={() => setCreating(false)}
        />
      )}
    </section>
  );
}

/** One playlist's episodes. */
export function PlaylistPage({ id, nav }: { id: string; nav: Nav }) {
  const s = store.useStore();
  const p = s.playlists.get(id);
  const [editing, setEditing] = useState(false);
  const titleOf = useTitles(s);
  if (!p || p.deleted) {
    return (
      <>
        <header className="bar">
          <button className="icon" title="Back" onClick={nav.back}>
            <Glyph name="arrowLeft" text="←" />
          </button>
          <h1>Playlist</h1>
        </header>
        <div className="empty">{s.loaded ? "This playlist was deleted." : "Loading…"}</div>
      </>
    );
  }
  const f = filtersOf(p);
  const episodes = select(f, s.episodes.values(), s.podcasts);
  const queueAll = () => {
    const queued = new Set(store.queue().map((e) => e.id));
    for (const e of episodes) if (!queued.has(e.id)) store.queueLast(e);
  };
  return (
    <>
      <header className="bar">
        <button className="icon" title="Back" onClick={nav.back}>
          <Glyph name="arrowLeft" text="←" />
        </button>
        <h1 className="ellipsis">{p.name}</h1>
        <span className="spacer" />
        <button className="text" onClick={() => setEditing(true)}>
          Edit
        </button>
        <button
          className="text"
          onClick={() => {
            if (confirm(`Delete "${p.name}"? Only the playlist goes; its episodes stay in your library and queue.`)) {
              store.deletePlaylist(p.id);
              nav.back();
            }
          }}
        >
          Delete
        </button>
      </header>
      <div className="subbar">
        <span className="muted small">
          {episodes.length} episode{episodes.length === 1 ? "" : "s"} · {summary(f, titleOf)}
        </span>
        <span className="spacer" />
        {episodes.length > 0 && (
          <button className="text" onClick={queueAll}>
            Queue all
          </button>
        )}
      </div>
      <section className="list">
        {episodes.length === 0 && <div className="empty">Nothing matches these filters right now. Use Edit to change them.</div>}
        {episodes.map((e) => {
          const pod = s.podcasts.get(e.feed_url);
          return <EpisodeItem key={e.id} e={e} podcastTitle={pod?.title} art={pod?.image_url} />;
        })}
      </section>
      {editing && (
        <PlaylistEditor
          title="Edit playlist"
          name={p.name}
          filters={f}
          onSave={(name, nf) => {
            setEditing(false);
            store.updatePlaylist(p.id, name, nf);
          }}
          onClose={() => setEditing(false)}
        />
      )}
    </>
  );
}

const num = (v: string) => (v ? Number(v) : null);

/** Making or changing a playlist. */
function PlaylistEditor({ title, name: initialName, filters, onSave, onClose }: { title: string; name: string; filters: PlaylistFilters; onSave: (name: string, f: PlaylistFilters) => void; onClose: () => void }) {
  const s = store.useStore();
  const [name, setName] = useState(initialName);
  const [f, setF] = useState<PlaylistFilters>(filters);
  const set = (patch: Partial<PlaylistFilters>) => setF((x) => ({ ...x, ...patch }));
  const lib = store.library(s);
  const chosen = new Set(f.podcasts.map(feedKey));
  const toggle = (p: PodcastRow) => {
    const k = feedKey(p.feed_url);
    set({ podcasts: chosen.has(k) ? f.podcasts.filter((u) => feedKey(u) !== k) : [...f.podcasts, p.feed_url] });
  };
  const preview = select(f, s.episodes.values(), s.podcasts).length;
  return (
    <div className="scrim" onClick={onClose}>
      <div className="sheet" onClick={(ev) => ev.stopPropagation()}>
        <div className="sheet-title">{title}</div>
        <div className="form">
          <label>
            Name
            <input value={name} placeholder="e.g. Short ones" onChange={(e) => setName(e.target.value)} autoFocus />
          </label>
          <label>
            Episodes
            <select value={f.status} onChange={(e) => set({ status: e.target.value as PlaylistStatus })}>
              {Object.entries(STATUS_LABELS).map(([k, v]) => (
                <option key={k} value={k}>
                  {v}
                </option>
              ))}
            </select>
          </label>
          <label>
            At least
            <select value={f.minMinutes ?? ""} onChange={(e) => set({ minMinutes: num(e.target.value) })}>
              <option value="">Any length</option>
              {MINUTE_CHOICES.map((m) => (
                <option key={m} value={m}>
                  {m} min
                </option>
              ))}
            </select>
          </label>
          <label>
            At most
            <select value={f.maxMinutes ?? ""} onChange={(e) => set({ maxMinutes: num(e.target.value) })}>
              <option value="">Any length</option>
              {MINUTE_CHOICES.map((m) => (
                <option key={m} value={m}>
                  {m} min
                </option>
              ))}
            </select>
          </label>
          <label>
            Released in the last
            <select value={f.withinDays ?? ""} onChange={(e) => set({ withinDays: num(e.target.value) })}>
              <option value="">Any time</option>
              {DAY_CHOICES.map((d) => (
                <option key={d} value={d}>
                  {d === 1 ? "Day" : `${d} days`}
                </option>
              ))}
            </select>
          </label>
          <label>
            Title contains
            <input value={f.keywords} placeholder="optional; comma-separated" onChange={(e) => set({ keywords: e.target.value })} />
          </label>
          <label>
            Order
            <select value={f.sort} onChange={(e) => set({ sort: e.target.value as PlaylistSort })}>
              {Object.entries(SORT_LABELS).map(([k, v]) => (
                <option key={k} value={k}>
                  {v}
                </option>
              ))}
            </select>
          </label>
          <label>
            Show at most
            <select value={f.limit} onChange={(e) => set({ limit: Number(e.target.value) })}>
              {LIMIT_CHOICES.map((n) => (
                <option key={n} value={n}>
                  {n} episodes
                </option>
              ))}
            </select>
          </label>
          <label className="tick">
            <input type="checkbox" checked={f.downloaded} onChange={(e) => set({ downloaded: e.target.checked })} />
            Downloaded only (the phone; ignored here)
          </label>
          <div>
            <div className="form-label">
              Podcasts <span className="muted small">{f.podcasts.length ? `${f.podcasts.length} chosen` : "none ticked = all"}</span>
              {f.podcasts.length > 0 && (
                <button className="text small" onClick={() => set({ podcasts: [] })}>
                  Clear
                </button>
              )}
            </div>
            <div className="pick-list">
              {lib.map((p) => (
                <label key={p.feed_url} className="tick">
                  <input type="checkbox" checked={chosen.has(feedKey(p.feed_url))} onChange={() => toggle(p)} />
                  <span className="ellipsis">{p.title}</span>
                </label>
              ))}
            </div>
          </div>
        </div>
        <div className="actions">
          <button className="primary" onClick={() => onSave(name.trim() || "Playlist", { ...f, keywords: f.keywords.trim() })}>
            Save
          </button>
          <button onClick={onClose}>Cancel</button>
          <span className="muted small">{preview} episode{preview === 1 ? "" : "s"} match here now</span>
        </div>
      </div>
    </div>
  );
}

const daysLabel = (d: number) => (d <= 0 ? "Never" : `${d} days`);
const keepLabel = (n: number) => (n <= 0 ? "No limit" : `Latest ${n}`);

/** A podcast's automation rules (podcast page). The phone applies them when it finds new episodes. */
export function RulesPanel({ podcast }: { podcast: PodcastRow }) {
  const s = store.useStore();
  const [include, setInclude] = useState(podcast.include_words ?? "");
  const [exclude, setExclude] = useState(podcast.exclude_words ?? "");
  if (!s.phase7) return <div className="panel muted small">{NOT_READY}</div>;
  const d = s.defaults;
  const update = (patch: Partial<PodcastRow>) => store.updateRules(podcast.feed_url, patch);
  const text = (v: string) => v.trim() || null;
  return (
    <div className="panel rules">
      <div className="muted small wide">New-episode rules run on the phone when it finds new episodes (every 2 hours); episodes already found aren't moved.</div>
      <label>
        New episodes go to{" "}
        <select value={podcast.new_episode_action ?? "inbox"} onChange={(e) => update({ new_episode_action: e.target.value === "inbox" ? null : (e.target.value as NewEpisodeAction) })}>
          {NEW_EPISODE_ACTIONS.map((a) => (
            <option key={a.key} value={a.key}>
              {a.label}
            </option>
          ))}
        </select>
      </label>
      <label>
        Only if the title contains{" "}
        <input value={include} placeholder="e.g. Part 1, interview" onChange={(e) => setInclude(e.target.value)} onBlur={() => update({ include_words: text(include) })} />
      </label>
      <label>
        Skip if the title contains{" "}
        <input value={exclude} placeholder="e.g. bonus, trailer" onChange={(e) => setExclude(e.target.value)} onBlur={() => update({ exclude_words: text(exclude) })} />
      </label>
      <label>
        At least{" "}
        <select value={podcast.min_minutes ?? ""} onChange={(e) => update({ min_minutes: num(e.target.value) })}>
          <option value="">Any length</option>
          {MINUTE_CHOICES.map((m) => (
            <option key={m} value={m}>
              {m} min
            </option>
          ))}
        </select>
      </label>
      <label>
        At most{" "}
        <select value={podcast.max_minutes ?? ""} onChange={(e) => update({ max_minutes: num(e.target.value) })}>
          <option value="">Any length</option>
          {MINUTE_CHOICES.map((m) => (
            <option key={m} value={m}>
              {m} min
            </option>
          ))}
        </select>
      </label>
      <label>
        Archive New Episodes after{" "}
        <select value={podcast.inbox_days ?? ""} onChange={(e) => update({ inbox_days: e.target.value === "" ? null : Number(e.target.value) })}>
          <option value="">Default ({daysLabel(d.inboxDays ?? 0).toLowerCase()})</option>
          {INBOX_DAY_CHOICES.map((v) => (
            <option key={v} value={v}>
              {daysLabel(v)}
            </option>
          ))}
        </select>
      </label>
      <label>
        Keep in New Episodes{" "}
        <select value={podcast.inbox_keep ?? ""} onChange={(e) => update({ inbox_keep: e.target.value === "" ? null : Number(e.target.value) })}>
          <option value="">Default ({keepLabel(d.inboxKeep ?? 0).toLowerCase()})</option>
          {INBOX_KEEP_CHOICES.map((v) => (
            <option key={v} value={v}>
              {keepLabel(v)}
            </option>
          ))}
        </select>
      </label>
    </div>
  );
}

/** The app-wide tidy-up defaults (New Episodes tab). Applied by the phone; off until chosen. */
export function TidyPanel() {
  const s = store.useStore();
  const d = s.defaults;
  return (
    <div className="panel">
      <div className="muted small wide">Tidy up New Episodes for every podcast (a podcast's own rules win). Your phone applies these at its next sync.</div>
      <label>
        Archive after{" "}
        <select value={d.inboxDays ?? 0} onChange={(e) => store.updateDefaults({ inboxDays: Number(e.target.value) })}>
          {INBOX_DAY_CHOICES.map((v) => (
            <option key={v} value={v}>
              {daysLabel(v)}
            </option>
          ))}
        </select>
      </label>
      <label>
        Keep per podcast{" "}
        <select value={d.inboxKeep ?? 0} onChange={(e) => store.updateDefaults({ inboxKeep: Number(e.target.value) })}>
          {INBOX_KEEP_CHOICES.map((v) => (
            <option key={v} value={v}>
              {keepLabel(v)}
            </option>
          ))}
        </select>
      </label>
    </div>
  );
}

/** "Tidy-up: off" / "Tidy-up: 14 days, latest 3", for the New Episodes bar. */
export function tidyLabel(s: store.State): string {
  const parts = [s.defaults.inboxDays ? `after ${s.defaults.inboxDays} days` : "", s.defaults.inboxKeep ? `latest ${s.defaults.inboxKeep}` : ""].filter(Boolean);
  return parts.length ? `Tidy-up: ${parts.join(", ")}` : "Tidy-up: off";
}
