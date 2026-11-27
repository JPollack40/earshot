import { setAppearance, useArtColor } from "../appearance";
import * as player from "../player";
import type { PlaybackDefaults, PodcastRow } from "../types";
import { episodeMeta, formatClock, safeHtml } from "../util";
import { Art, type Nav } from "./common";
import { Icon } from "./Icons";
import { AudioChips, audioChanger, GlowScrubber, SkipSelect, SleepSelect, useChapters } from "./PlayerBar";

/** Glow's optional right-hand panel (full screen at phone width): artwork, chapters, settings, show notes. */
export function NowPanel({ podcast, prefs, nav }: { podcast: PodcastRow | undefined; prefs: PlaybackDefaults; nav: Nav }) {
  const p = player.usePlayer();
  const e = p.episode;
  const chapters = useChapters(e);
  const tint = useArtColor(e ? (e.image_url ?? podcast?.image_url) : null, e?.feed_url ?? "earshot");
  if (!e) return null;
  const { changeSkip } = audioChanger(prefs, podcast);
  const current = [...chapters].reverse().find((c) => c.startMs <= p.positionMs);
  return (
    <aside className="now-panel" aria-label="Now playing" style={{ "--tint": tint } as React.CSSProperties}>
      <div className="np-top">
        <span className="eyebrow">Now playing</span>
        <span className="spacer" />
        <button className="icon" title="Close panel" onClick={() => setAppearance({ nowPanel: false })}>
          <Icon name="close" />
        </button>
      </div>
      <button className="np-art" title="Open podcast" onClick={() => nav.open({ name: "podcast", feedUrl: e.feed_url })}>
        <Art url={e.image_url ?? podcast?.image_url} size={480} />
      </button>
      <div>
        <div className="np-title">{e.title}</div>
        <div className="meta">{episodeMeta(e, podcast?.title)}</div>
      </div>
      {/* Phone width: the player bar is reduced to a strip, so the big controls live here. */}
      <div className="np-phone">
        <GlowScrubber chapters={chapters} />
      </div>
      <div className="np-controls">
        <button className="g-skip" title="Skip back" onClick={() => player.skipBy(-prefs.skipBackMs)}>
          <Icon name="back" size={30} />
        </button>
        <button className="g-play" title={p.playing ? "Pause" : "Play"} onClick={player.toggle}>
          <Icon name={p.playing ? "pause" : "play"} />
        </button>
        <button className="g-skip" title="Skip forward" onClick={() => player.skipBy(prefs.skipForwardMs)}>
          <Icon name="forward" size={30} />
        </button>
      </div>
      <div className="np-phone g-chips np-chips">
        <AudioChips prefs={prefs} podcast={podcast} />
      </div>
      {!p.effectsAvailable && <div className="np-phone muted small">Smart Speed and Voice Boost aren't available for this episode: its host doesn't allow browsers to process the audio.</div>}
      {chapters.length > 0 && (
        <div className="np-box">
          <h4>Chapters</h4>
          {chapters.map((c) => (
            <button key={c.startMs} className={`np-chap ${c === current ? "on" : ""}`} onClick={() => player.seekTo(c.startMs)}>
              <span className="ellipsis">{c.title}</span>
              <span className="time">{formatClock(c.startMs)}</span>
            </button>
          ))}
        </div>
      )}
      <div className="np-box np-settings">
        <h4>Playback</h4>
        <SkipSelect label="Skip back" value={prefs.skipBackMs} onChange={(v) => changeSkip({ skipBackMs: v })} />
        <SkipSelect label="Skip forward" value={prefs.skipForwardMs} onChange={(v) => changeSkip({ skipForwardMs: v })} />
        <label>
          Sleep timer <SleepSelect />
        </label>
      </div>
      {e.description && (
        <div className="np-box">
          <h4>Show notes</h4>
          <div className="np-notes" dangerouslySetInnerHTML={{ __html: safeHtml(e.description) }} />
        </div>
      )}
    </aside>
  );
}
