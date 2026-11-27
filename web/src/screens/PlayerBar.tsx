import { useEffect, useState } from "react";
import { fetchChapters, type Chapter } from "../feed";
import * as player from "../player";
import * as store from "../store";
import { SKIPS_MS, SPEEDS, type PlaybackDefaults, type PodcastRow } from "../types";
import { formatClock, hasOwnAudio, skipName, speedName } from "../util";
import { Art, type Nav } from "./common";

export function PlayerBar({ prefs, podcast, nav }: { prefs: PlaybackDefaults; podcast: PodcastRow | undefined; nav: Nav }) {
  const p = player.usePlayer();
  const [more, setMore] = useState(false);
  const [chapters, setChapters] = useState<Chapter[]>([]);
  const [scrub, setScrub] = useState<number | null>(null);
  const e = p.episode;

  useEffect(() => {
    setChapters([]);
    if (e?.chapters_url) fetchChapters(e.chapters_url).then(setChapters).catch(() => setChapters([]));
  }, [e?.id]);

  if (!e) return null;

  // Same rule as the phone: a change goes to the podcast if it has its own audio settings, else to the defaults.
  const changeAudio = (patch: Partial<PlaybackDefaults>) => {
    if (podcast && hasOwnAudio(podcast)) {
      store.updatePodcastPlayback(podcast.feed_url, {
        speed: patch.speed ?? prefs.speed,
        smart_speed: patch.smartSpeed ?? prefs.smartSpeed,
        voice_boost: patch.voiceBoost ?? prefs.voiceBoost,
      });
    } else store.updateDefaults(patch);
  };
  const changeSkip = (patch: Partial<PlaybackDefaults>) => {
    if (podcast && (podcast.skip_back_ms != null || podcast.skip_forward_ms != null)) {
      store.updatePodcastPlayback(podcast.feed_url, {
        skip_back_ms: patch.skipBackMs ?? prefs.skipBackMs,
        skip_forward_ms: patch.skipForwardMs ?? prefs.skipForwardMs,
      });
    } else store.updateDefaults(patch);
  };

  const duration = p.durationMs || e.duration_ms;
  const pos = scrub ?? p.positionMs;
  const chapter = [...chapters].reverse().find((c) => c.startMs <= pos);

  return (
    <footer className="player">
      <div className="player-row">
        <button className="art-button" title="Open podcast" onClick={() => nav.open({ name: "podcast", feedUrl: e.feed_url })}>
          <Art url={e.image_url ?? podcast?.image_url} size={48} />
        </button>
        <div className="grow ellipsis-col">
          <div className="title ellipsis">{e.title}</div>
          <div className="meta ellipsis">{chapter ? chapter.title : podcast?.title}</div>
        </div>
        <button className="icon skip" title={`Back ${skipName(prefs.skipBackMs)}`} onClick={() => player.skipBy(-prefs.skipBackMs)}>
          ↺<small>{prefs.skipBackMs < 60000 ? prefs.skipBackMs / 1000 : `${prefs.skipBackMs / 60000}m`}</small>
        </button>
        <button className="icon big-play" title={p.playing ? "Pause" : "Play"} onClick={player.toggle}>
          {p.buffering && !p.playing ? "…" : p.playing ? "❚❚" : "▶"}
        </button>
        <button className="icon skip" title={`Forward ${skipName(prefs.skipForwardMs)}`} onClick={() => player.skipBy(prefs.skipForwardMs)}>
          ↻<small>{prefs.skipForwardMs < 60000 ? prefs.skipForwardMs / 1000 : `${prefs.skipForwardMs / 60000}m`}</small>
        </button>
        <select className="chip" title="Speed" value={prefs.speed} onChange={(ev) => changeAudio({ speed: Number(ev.target.value) })}>
          {SPEEDS.map((v) => (
            <option key={v} value={v}>
              {speedName(v)}
            </option>
          ))}
        </select>
        <button
          className={`chip ${prefs.smartSpeed ? "on" : ""}`}
          disabled={!p.effectsAvailable}
          title={p.effectsAvailable ? "Smart Speed: shortens silences" : "This episode's host doesn't allow Smart Speed in the browser"}
          onClick={() => changeAudio({ smartSpeed: !prefs.smartSpeed })}
        >
          Smart Speed
        </button>
        <button
          className={`chip ${prefs.voiceBoost ? "on" : ""}`}
          disabled={!p.effectsAvailable}
          title={p.effectsAvailable ? "Voice Boost: evens out loudness" : "This episode's host doesn't allow Voice Boost in the browser"}
          onClick={() => changeAudio({ voiceBoost: !prefs.voiceBoost })}
        >
          Voice Boost
        </button>
        <button className="icon" title="More" onClick={() => setMore(!more)}>
          ⋯
        </button>
      </div>
      <div className="scrubber">
        <span className="time">{formatClock(pos)}</span>
        <input
          type="range"
          min={0}
          max={Math.max(1, duration)}
          step={1000}
          value={Math.min(pos, duration)}
          onChange={(ev) => setScrub(Number(ev.target.value))}
          onPointerUp={() => {
            if (scrub != null) player.seekTo(scrub);
            setScrub(null);
          }}
          onKeyUp={() => {
            if (scrub != null) player.seekTo(scrub);
            setScrub(null);
          }}
        />
        <span className="time">-{formatClock(Math.max(0, duration - pos))}</span>
      </div>
      {p.error && <div className="error small">{p.error}</div>}
      {more && (
        <div className="panel">
          <label>
            Skip back{" "}
            <select value={prefs.skipBackMs} onChange={(ev) => changeSkip({ skipBackMs: Number(ev.target.value) })}>
              {SKIPS_MS.map((v) => (
                <option key={v} value={v}>
                  {skipName(v)}
                </option>
              ))}
            </select>
          </label>
          <label>
            Skip forward{" "}
            <select value={prefs.skipForwardMs} onChange={(ev) => changeSkip({ skipForwardMs: Number(ev.target.value) })}>
              {SKIPS_MS.map((v) => (
                <option key={v} value={v}>
                  {skipName(v)}
                </option>
              ))}
            </select>
          </label>
          <label>
            Sleep timer{" "}
            <select
              value={p.sleepAtEnd ? "end" : p.sleepEndsAt ? "on" : ""}
              onChange={(ev) => {
                const v = ev.target.value;
                if (v === "end") player.setSleep(null, true);
                else if (v === "") player.setSleep(null);
                else if (v !== "on") player.setSleep(Number(v));
              }}
            >
              <option value="">Off</option>
              {p.sleepEndsAt && <option value="on">Ends {new Date(p.sleepEndsAt).toLocaleTimeString([], { hour: "numeric", minute: "2-digit" })}</option>}
              {[5, 10, 15, 30, 45, 60].map((m) => (
                <option key={m} value={m}>
                  {m} minutes
                </option>
              ))}
              <option value="end">End of episode</option>
            </select>
          </label>
          {!p.effectsAvailable && <div className="muted small">Smart Speed and Voice Boost aren't available for this episode: its host doesn't allow browsers to process the audio.</div>}
          {chapters.length > 0 && (
            <div className="chapters">
              {chapters.map((c) => (
                <button key={c.startMs} className={c === chapter ? "on" : ""} onClick={() => player.seekTo(c.startMs)}>
                  <span className="time">{formatClock(c.startMs)}</span> {c.title}
                </button>
              ))}
            </div>
          )}
        </div>
      )}
    </footer>
  );
}
