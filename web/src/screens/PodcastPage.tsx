import { useEffect, useMemo, useState } from "react";
import { fetchFeed, type ParsedFeed } from "../feed";
import * as store from "../store";
import { SKIPS_MS, SPEEDS } from "../types";
import { plainText, safeHtml, searchTerms, skipName, snippet, speedName } from "../util";
import { useArtColor } from "../appearance";
import { Art, EpisodeItem, type Nav } from "./common";
import { Glyph } from "./Icons";

export function PodcastPage({ feedUrl, nav }: { feedUrl: string; nav: Nav }) {
  const s = store.useStore();
  const podcast = store.findPodcast(feedUrl, s);
  const url = podcast?.feed_url ?? feedUrl; // the library's spelling, so episode ids match the phone's
  const [feed, setFeed] = useState<ParsedFeed | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [query, setQuery] = useState("");
  const [searchOpen, setSearchOpen] = useState(false);
  const [showSettings, setShowSettings] = useState(false);
  const [descOpen, setDescOpen] = useState(false);

  useEffect(() => {
    let cancelled = false;
    fetchFeed(url)
      .then((f) => !cancelled && setFeed(f))
      .catch((e) => !cancelled && setError(e instanceof Error ? e.message : String(e)));
    return () => {
      cancelled = true;
    };
  }, [url]);

  // Feed episodes, with this account's listening state where it has any.
  const episodes = useMemo(() => (feed?.episodes ?? []).map((e) => s.episodes.get(e.id) ?? e), [feed, s.version]);
  const index = useMemo(() => new Map(episodes.map((e) => [e.id, plainText(e.description)])), [feed]);
  const terms = searchTerms(query);
  const shown = terms.length
    ? episodes.filter((e) => {
        const text = (e.title + "\n" + (index.get(e.id) ?? "")).toLowerCase();
        return terms.every((t) => text.includes(t));
      })
    : episodes;

  const subscribed = !!podcast?.subscribed;
  const title = feed?.title || podcast?.title || url;
  const image = feed?.imageUrl ?? podcast?.image_url;
  const tint = useArtColor(image, url);

  return (
    <>
      <header className="bar">
        <button className="icon" title="Back" onClick={nav.back}>
          <Glyph name="arrowLeft" text="←" />
        </button>
        {searchOpen ? (
          <input
            className="search"
            autoFocus
            placeholder="Search episodes and show notes"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            onKeyDown={(e) => e.key === "Escape" && (setQuery(""), setSearchOpen(false))}
          />
        ) : (
          <h1 className="ellipsis">{title}</h1>
        )}
        <span className="spacer" />
        <button className="icon" title={searchOpen ? "Close search" : "Search this podcast"} onClick={() => (setSearchOpen(!searchOpen), setQuery(""))}>
          {searchOpen ? <Glyph name="close" text="✕" /> : <Glyph name="search" text="🔍" />}
        </button>
        {subscribed && (
          <button className="icon" title="Playback settings" onClick={() => setShowSettings(!showSettings)}>
            <Glyph name="sliders" text="⚙" />
          </button>
        )}
      </header>

      {terms.length > 0 ? (
        <div className="subbar muted small">
          {shown.length} of {episodes.length} episodes match
        </div>
      ) : (
        <div className="podcast-head" style={{ "--tint": tint } as React.CSSProperties}>
          <Art url={image} size={88} />
          <div className="grow">
            <div className="big">{title}</div>
            <div className="meta">{feed?.author || podcast?.author}</div>
            <div className="meta">{feed ? `${episodes.length} episodes` : error ? "" : "Loading…"}</div>
            <div className="actions">
              {subscribed ? (
                <>
                  <button aria-pressed={podcast!.pinned} onClick={() => store.setPinned(podcast!.feed_url, !podcast!.pinned)}>{podcast!.pinned ? "Unpin" : "Pin to top"}</button>
                  <button onClick={() => confirm(`Unsubscribe from ${title}? Episodes in your queue stay until you play or remove them.`) && (store.unsubscribe(podcast!.feed_url), nav.back())}>
                    Unsubscribe
                  </button>
                </>
              ) : (
                <>
                  <button className="primary" disabled={!feed} onClick={() => feed && store.subscribe(url, feed)}>
                    Subscribe
                  </button>
                  <span className="muted small">Not subscribed. Episodes you play or queue stay in your queue until played.</span>
                </>
              )}
            </div>
          </div>
        </div>
      )}

      {showSettings && podcast && !terms.length && (
        <div className="panel">
          <div className="muted small">This podcast's playback settings. "Default" follows the app-wide setting.</div>
          <label>
            Speed{" "}
            <select value={podcast.speed ?? ""} onChange={(e) => store.updatePodcastPlayback(podcast.feed_url, { speed: e.target.value ? Number(e.target.value) : null })}>
              <option value="">Default ({speedName(s.defaults.speed)})</option>
              {SPEEDS.map((v) => (
                <option key={v} value={v}>
                  {speedName(v)}
                </option>
              ))}
            </select>
          </label>
          <TriState label="Smart Speed" value={podcast.smart_speed} def={s.defaults.smartSpeed} onChange={(v) => store.updatePodcastPlayback(podcast.feed_url, { smart_speed: v })} />
          <TriState label="Voice Boost" value={podcast.voice_boost} def={s.defaults.voiceBoost} onChange={(v) => store.updatePodcastPlayback(podcast.feed_url, { voice_boost: v })} />
          <label>
            Skip back{" "}
            <select value={podcast.skip_back_ms ?? ""} onChange={(e) => store.updatePodcastPlayback(podcast.feed_url, { skip_back_ms: e.target.value ? Number(e.target.value) : null })}>
              <option value="">Default ({skipName(s.defaults.skipBackMs)})</option>
              {SKIPS_MS.map((v) => (
                <option key={v} value={v}>
                  {skipName(v)}
                </option>
              ))}
            </select>
          </label>
          <label>
            Skip forward{" "}
            <select value={podcast.skip_forward_ms ?? ""} onChange={(e) => store.updatePodcastPlayback(podcast.feed_url, { skip_forward_ms: e.target.value ? Number(e.target.value) : null })}>
              <option value="">Default ({skipName(s.defaults.skipForwardMs)})</option>
              {SKIPS_MS.map((v) => (
                <option key={v} value={v}>
                  {skipName(v)}
                </option>
              ))}
            </select>
          </label>
        </div>
      )}

      {!terms.length && feed?.description && (
        <div className={`notes podcast-desc ${descOpen ? "open" : ""}`} onClick={() => setDescOpen(!descOpen)} dangerouslySetInnerHTML={{ __html: safeHtml(feed.description) }} />
      )}
      {error && <div className="error pad">Couldn't load this feed: {error}</div>}

      <section className="list">
        {shown.map((e) => (
          <EpisodeItem key={e.id} e={e} art={image} feed={feed ?? undefined} snippet={terms.length ? snippet(index.get(e.id) ?? "", terms) : null} />
        ))}
      </section>
    </>
  );
}

function TriState({ label, value, def, onChange }: { label: string; value: boolean | null; def: boolean; onChange: (v: boolean | null) => void }) {
  return (
    <label>
      {label}{" "}
      <select value={value == null ? "" : value ? "on" : "off"} onChange={(e) => onChange(e.target.value === "" ? null : e.target.value === "on")}>
        <option value="">Default ({def ? "on" : "off"})</option>
        <option value="on">On</option>
        <option value="off">Off</option>
      </select>
    </label>
  );
}
