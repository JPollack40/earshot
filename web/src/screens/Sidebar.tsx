import { useEffect, useRef, useState } from "react";
import { setAppearance, useAppearance } from "../appearance";
import { supabase } from "../supabase";
import * as store from "../store";
import * as notes from "../notes";
import { Art, type Nav } from "./common";
import { Icon } from "./Icons";
import type { Tab } from "./Library";

export const TABS: { tab: Tab; label: string; short: string; icon: string }[] = [
  { tab: "queue", label: "Queue", short: "Queue", icon: "queue" },
  { tab: "new", label: "New Episodes", short: "New", icon: "new" },
  { tab: "latest", label: "Latest", short: "Latest", icon: "latest" },
  { tab: "playlists", label: "Playlists", short: "Playlists", icon: "playlists" },
  { tab: "podcasts", label: "Podcasts", short: "Podcasts", icon: "podcasts" },
];

export function signOut() {
  void store
    .flush()
    .then(() => supabase.auth.signOut())
    .then(store.resetStore);
}

/** New-episode count per podcast (episodes still in New Episodes). */
export function newCounts(s: store.State): Map<string, number> {
  const m = new Map<string, number>();
  for (const e of s.episodes.values()) if (e.in_inbox) m.set(e.feed_url, (m.get(e.feed_url) ?? 0) + 1);
  return m;
}

export function tabCount(s: store.State, tab: Tab): number | "" {
  if (tab === "queue") return store.queue(s).length || "";
  if (tab === "new") return store.inbox(s).length || "";
  if (tab === "podcasts") return store.library(s).length || "";
  if (tab === "playlists") return store.playlists(s).length || "";
  return "";
}

/** Glow's left sidebar: the five sections, pinned podcasts, and account actions. */
export function Sidebar({ tab, nav, activeFeed, page }: { tab: Tab | null; nav: Nav; activeFeed: string | null; page: string }) {
  const s = store.useStore();
  const pinned = store.library(s).filter((p) => p.pinned);
  const counts = newCounts(s);
  const bookmarkCount = notes.bookmarks(notes.useNotes()).length;
  return (
    <aside className="sidebar">
      <div className="brand">
        <i aria-hidden="true" />
        Earshot
      </div>
      <nav className="side-nav" aria-label="Library">
        {TABS.map((t) => (
          <button key={t.tab} className={`nav-item ${tab === t.tab ? "on" : ""}`} aria-current={tab === t.tab ? "page" : undefined} onClick={() => nav.showTab(t.tab)}>
            <Icon name={t.icon} />
            <span>{t.label}</span>
            <span className="count">{tabCount(s, t.tab)}</span>
          </button>
        ))}
        <button className={`nav-item ${page === "search" ? "on" : ""}`} aria-current={page === "search" ? "page" : undefined} onClick={() => nav.open({ name: "search" })}>
          <Icon name="findText" />
          <span>Search transcripts</span>
        </button>
        <button className={`nav-item ${page === "bookmarks" ? "on" : ""}`} aria-current={page === "bookmarks" ? "page" : undefined} onClick={() => nav.open({ name: "bookmarks" })}>
          <Icon name="bookmark" />
          <span>Bookmarks</span>
          <span className="count">{bookmarkCount || ""}</span>
        </button>
      </nav>
      {pinned.length > 0 && (
        <>
          <div className="side-label">Pinned</div>
          <div className="side-pins">
            {pinned.map((p) => (
              <button
                key={p.feed_url}
                className={`pin-item ${activeFeed === p.feed_url ? "on" : ""}`}
                title={p.title}
                onClick={() => nav.open({ name: "podcast", feedUrl: p.feed_url })}
              >
                <Art url={p.image_url} size={28} />
                <span className="ellipsis">{p.title}</span>
                {counts.get(p.feed_url) ? <span className="count">{counts.get(p.feed_url)}</span> : null}
              </button>
            ))}
          </div>
        </>
      )}
      <span className="spacer" />
      <button className="side-add" onClick={() => nav.open({ name: "add" })}>
        <Icon name="plus" />
        Add podcast
      </button>
      <div className="side-foot">
        <button className="side-small" title="Sync now" onClick={() => void store.pull()}>
          <Icon name="sync" size={16} />
          {s.syncing ? "Syncing…" : s.error ? <span className="error">Sync problem</span> : "Sync"}
        </button>
        <AppearanceMenu />
        <a className="side-small" href={ANDROID_APP_URL} target="_blank" rel="noreferrer" title="Download the Android app (latest release on GitHub)">
          ⬇ Get the Android app
        </a>
        <button className="side-small" title="Sign out" onClick={signOut}>
          <Icon name="signOut" size={16} />
          Sign out
        </button>
      </div>
    </aside>
  );
}

/** The latest release-signed APK (phase 9). Opening it on an Android phone downloads the newest Earshot. */
export const ANDROID_APP_URL = "https://github.com/JPollack40/earshot/releases/latest";

/** Glow at phone width: the five sections as a bottom tab bar, like the Android app. */
export function BottomTabs({ tab, nav }: { tab: Tab | null; nav: Nav }) {
  const s = store.useStore();
  return (
    <nav className="bottom-tabs" aria-label="Library">
      {TABS.map((t) => {
        const n = tabCount(s, t.tab);
        return (
          <button key={t.tab} className={tab === t.tab ? "on" : ""} aria-current={tab === t.tab ? "page" : undefined} onClick={() => nav.showTab(t.tab)}>
            <Icon name={t.icon} />
            <span>
              {t.short}
              {n ? ` ${n}` : ""}
            </span>
          </button>
        );
      })}
    </nav>
  );
}

/** Theme and row density. Shown in Glow's sidebar and in Classic's top bar. */
export function AppearanceMenu() {
  const a = useAppearance();
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
  const glow = a.theme === "glow";
  return (
    <div className="appearance" ref={ref}>
      <button className={glow ? "side-small" : "text"} title="Theme and row size" aria-expanded={open} onClick={() => setOpen(!open)}>
        {glow && <Icon name="theme" size={16} />}
        Theme
      </button>
      {open && (
        <div className="appearance-pop" role="dialog" aria-label="Theme and row size">
          <div className="pop-label">Theme</div>
          <div className="seg" role="group" aria-label="Theme">
            <button aria-pressed={a.theme === "glow"} onClick={() => setAppearance({ theme: "glow" })}>
              Glow
            </button>
            <button aria-pressed={a.theme === "classic"} onClick={() => setAppearance({ theme: "classic" })}>
              Classic
            </button>
          </div>
          {glow && (
            <>
              <div className="pop-label">Rows</div>
              <div className="seg" role="group" aria-label="Row size">
                <button aria-pressed={a.density === "comfortable"} onClick={() => setAppearance({ density: "comfortable" })}>
                  Comfortable
                </button>
                <button aria-pressed={a.density === "compact"} onClick={() => setAppearance({ density: "compact" })}>
                  Compact
                </button>
              </div>
            </>
          )}
          <div className="pop-note">Saved in this browser.</div>
        </div>
      )}
    </div>
  );
}

/** Glow's page header: a band tinted with the artwork colour. */
export function Hero({ tint, eyebrow, title, summary, children }: { tint: string; eyebrow: string; title: string; summary?: React.ReactNode; children?: React.ReactNode }) {
  return (
    <header className="hero" style={{ "--tint": tint } as React.CSSProperties}>
      <div className="hero-top">
        <div className="grow">
          <div className="eyebrow">{eyebrow}</div>
          <h1>{title}</h1>
          {summary && <div className="hero-sum">{summary}</div>}
        </div>
        {children}
      </div>
    </header>
  );
}
