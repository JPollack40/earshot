import { useEffect, useState } from "react";
import type { Session } from "@supabase/supabase-js";
import { supabase } from "./supabase";
import * as store from "./store";
import * as player from "./player";
import { useAppearance } from "./appearance";
import { Library, type Tab } from "./screens/Library";
import { NowPanel } from "./screens/NowPanel";
import { BottomTabs, Sidebar } from "./screens/Sidebar";
import { PodcastPage } from "./screens/PodcastPage";
import { AddPage } from "./screens/AddPage";
import { PlayerBar } from "./screens/PlayerBar";
import { SignIn } from "./screens/SignIn";
import { resolvePlayback } from "./util";
import { BookmarksPage, TranscriptPage, TranscriptSearchPage } from "./screens/Notes";
import { navHolder } from "./screens/common";

export type Route =
  | { name: "library" }
  | { name: "podcast"; feedUrl: string }
  | { name: "add" }
  | { name: "transcript"; episodeId: string; focusMs?: number }
  | { name: "bookmarks" }
  | { name: "search" };

export function App() {
  const [session, setSession] = useState<Session | null | undefined>(undefined);
  const [stack, setStack] = useState<Route[]>([{ name: "library" }]);
  const [tab, setTab] = useState<Tab>("queue");
  const look = useAppearance();
  const s = store.useStore();
  const p = player.usePlayer();

  useEffect(() => {
    void supabase.auth.getSession().then(({ data }) => setSession(data.session));
    const { data } = supabase.auth.onAuthStateChange((_event, sess) => setSession(sess));
    return () => data.subscription.unsubscribe();
  }, []);

  const userId = session?.user.id;
  useEffect(() => {
    if (!userId) return;
    store.startPolling();
    return () => store.stopPolling();
  }, [userId]);

  // Wire the player to the store: positions, durations, and what happens when an episode ends.
  useEffect(() => {
    player.hooks.onPosition = (id, ms, soon) => store.savePosition(id, ms, soon);
    player.hooks.onDuration = (id, ms) => store.setDuration(id, ms);
    player.hooks.onEnded = (e, stop) => {
      store.setPlayed(e, true); // also takes it out of the queue
      const next = store.queue()[0];
      if (next) player.load(next, !stop);
    };
  }, []);

  // Apply the playback settings for the loaded episode's podcast whenever they change.
  const loadedPodcast = p.episode ? s.podcasts.get(p.episode.feed_url) : undefined;
  const prefs = resolvePlayback(s.defaults, loadedPodcast);
  useEffect(() => {
    player.setPrefs(prefs);
  }, [prefs.speed, prefs.smartSpeed, prefs.voiceBoost, prefs.skipBackMs, prefs.skipForwardMs]);

  // When the library first loads, put the top of the queue in the player, paused (like the phone).
  useEffect(() => {
    if (s.loaded && !p.episode) {
      const head = store.queue(s)[0];
      if (head) player.load(head, false);
    }
  }, [s.loaded]);

  // Space bar plays and pauses (unless typing).
  useEffect(() => {
    const onKey = (ev: KeyboardEvent) => {
      const t = ev.target as HTMLElement;
      if (ev.code === "Space" && !["INPUT", "TEXTAREA", "SELECT", "BUTTON"].includes(t.tagName)) {
        ev.preventDefault();
        player.toggle();
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, []);

  if (session === undefined) return <div className="center muted">Loading…</div>;
  if (!session) return <SignIn />;

  const route = stack[stack.length - 1];
  const nav = {
    open: (r: Route) => setStack((st) => [...st, r]),
    back: () => setStack((st) => (st.length > 1 ? st.slice(0, -1) : st)),
    home: () => setStack([{ name: "library" }]),
    showTab: (t: Tab) => {
      setTab(t);
      setStack([{ name: "library" }]);
    },
  };
  navHolder.current = nav;

  const page = (
    <>
      {route.name === "library" && <Library nav={nav} tab={tab} />}
      {route.name === "podcast" && <PodcastPage key={route.feedUrl} feedUrl={route.feedUrl} nav={nav} />}
      {route.name === "add" && <AddPage nav={nav} />}
      {route.name === "transcript" && <TranscriptPage key={`${route.episodeId}-${route.focusMs ?? ""}`} episodeId={route.episodeId} focusMs={route.focusMs} nav={nav} />}
      {route.name === "bookmarks" && <BookmarksPage nav={nav} />}
      {route.name === "search" && <TranscriptSearchPage nav={nav} />}
    </>
  );

  if (look.theme === "glow") {
    const activeTab = route.name === "library" ? tab : null;
    return (
      <div className={`app glow-app ${look.nowPanel && p.episode ? "with-panel" : ""}`}>
        <Sidebar tab={activeTab} nav={nav} activeFeed={route.name === "podcast" ? route.feedUrl : null} page={route.name} />
        <main className="content">{page}</main>
        {look.nowPanel && p.episode && <NowPanel podcast={loadedPodcast} prefs={prefs} nav={nav} />}
        <PlayerBar prefs={prefs} podcast={loadedPodcast} nav={nav} />
        <BottomTabs tab={activeTab} nav={nav} />
      </div>
    );
  }

  return (
    <div className="app">
      <main className="content">{page}</main>
      <PlayerBar prefs={prefs} podcast={loadedPodcast} nav={nav} />
    </div>
  );
}
