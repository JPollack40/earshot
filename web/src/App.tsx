import { useEffect, useState } from "react";
import type { Session } from "@supabase/supabase-js";
import { supabase } from "./supabase";
import * as store from "./store";
import * as player from "./player";
import { Library } from "./screens/Library";
import { PodcastPage } from "./screens/PodcastPage";
import { AddPage } from "./screens/AddPage";
import { PlayerBar } from "./screens/PlayerBar";
import { SignIn } from "./screens/SignIn";
import { resolvePlayback } from "./util";

export type Route = { name: "library" } | { name: "podcast"; feedUrl: string } | { name: "add" };

export function App() {
  const [session, setSession] = useState<Session | null | undefined>(undefined);
  const [stack, setStack] = useState<Route[]>([{ name: "library" }]);
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
  };

  return (
    <div className="app">
      <main className="content">
        {route.name === "library" && <Library nav={nav} />}
        {route.name === "podcast" && <PodcastPage key={route.feedUrl} feedUrl={route.feedUrl} nav={nav} />}
        {route.name === "add" && <AddPage nav={nav} />}
      </main>
      <PlayerBar prefs={prefs} podcast={loadedPodcast} nav={nav} />
    </div>
  );
}
