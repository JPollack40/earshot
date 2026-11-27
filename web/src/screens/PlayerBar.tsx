import { useEffect, useState } from "react";
import { fetchChapters, type Chapter } from "../feed";
import { currentAd, useEpisodeAi } from "../ai";
import * as player from "../player";
import * as store from "../store";
import { SKIPS_MS, SPEEDS, type PlaybackDefaults, type PodcastRow } from "../types";
import { formatClock, hasOwnAudio, skipName, speedName } from "../util";
import { setAppearance, useAppearance, useArtColor } from "../appearance";
import { Art, PlayerError, PodcastLink, type Nav } from "./common";
import { Icon } from "./Icons";
import { BookmarkButton, TranscriptButton } from "./Notes";
import { AiPanel } from "./AiPanel";

const chapterCache = new Map<string, Chapter[]>();

/** The loaded episode's chapters (Podcasting 2.0 JSON), fetched once per episode. */
export function useChapters(e: { id: string; chapters_url: string | null } | null): Chapter[] {
  const feedChapters = useFeedChapters(e);
  // Phase 11: chapters made from the transcript when the feed has none.
  const generated = useEpisodeAi(e?.id).chapters;
  return feedChapters.length ? feedChapters : (generated ?? feedChapters);
}

function useFeedChapters(e: { id: string; chapters_url: string | null } | null): Chapter[] {
  const [chapters, setChapters] = useState<Chapter[]>(() => (e?.chapters_url ? (chapterCache.get(e.chapters_url) ?? []) : []));
  useEffect(() => {
    const url = e?.chapters_url;
    if (!url) {
      setChapters([]);
      return;
    }
    const cached = chapterCache.get(url);
    if (cached) {
      setChapters(cached);
      return;
    }
    setChapters([]);
    let cancelled = false;
    fetchChapters(url)
      .then((c) => {
        chapterCache.set(url, c);
        if (!cancelled) setChapters(c);
      })
      .catch(() => !cancelled && setChapters([]));
    return () => {
      cancelled = true;
    };
  }, [e?.id, e?.chapters_url]);
  return chapters;
}

/** Same rule as the phone: a change goes to the podcast if it has its own settings, else to the defaults. */
export function audioChanger(prefs: PlaybackDefaults, podcast: PodcastRow | undefined) {
  return {
    changeAudio: (patch: Partial<PlaybackDefaults>) => {
      if (podcast && hasOwnAudio(podcast)) {
        store.updatePodcastPlayback(podcast.feed_url, {
          speed: patch.speed ?? prefs.speed,
          smart_speed: patch.smartSpeed ?? prefs.smartSpeed,
          voice_boost: patch.voiceBoost ?? prefs.voiceBoost,
        });
      } else store.updateDefaults(patch);
    },
    changeSkip: (patch: Partial<PlaybackDefaults>) => {
      if (podcast && (podcast.skip_back_ms != null || podcast.skip_forward_ms != null)) {
        store.updatePodcastPlayback(podcast.feed_url, {
          skip_back_ms: patch.skipBackMs ?? prefs.skipBackMs,
          skip_forward_ms: patch.skipForwardMs ?? prefs.skipForwardMs,
        });
      } else store.updateDefaults(patch);
    },
  };
}

/** Hands the loaded episode's skipped chapters and its podcast's ad trim to the player (phase 10). */
function useSkipRules(e: { id: string; duration_ms: number } | null, chapters: Chapter[], podcast: PodcastRow | undefined, adSkip: string) {
  const s = store.useStore();
  const ads = useEpisodeAi(e?.id).ads;
  const skipped = e ? (store.episode(e.id)?.skipped_chapters ?? null) : null;
  const key = JSON.stringify(skipped);
  useEffect(() => {
    if (!e) {
      player.setSkipRules(null);
      return;
    }
    player.setSkipRules({
      episodeId: e.id,
      chapters,
      skipped: skipped ?? [],
      adTrim: podcast?.ad_trim ?? null,
      feedDurationMs: e.duration_ms,
      ads,
      adSkip,
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [e?.id, chapters, key, podcast?.ad_trim, s, ads, adSkip]);
}

/** "Skip ad" while a found ad plays, when ads are set to show a button (phase 11). */
export function SkipAdButton({ adSkip, className = "chip" }: { adSkip: string; className?: string }) {
  const p = player.usePlayer();
  const ads = useEpisodeAi(p.episode?.id).ads;
  if (adSkip !== "button") return null;
  const ad = currentAd(ads, p.positionMs);
  if (!ad) return null;
  return (
    <button className={`${className} on`} title={`Skip to ${formatClock(ad.endMs)}`} onClick={() => player.seekTo(ad.endMs)}>
      ⏭ Skip ad: {ad.label}
    </button>
  );
}

/** "Back to 12:34" for a few seconds after a big jump (phase 10). */
export function JumpBackButton({ className = "chip" }: { className?: string }) {
  const p = player.usePlayer();
  if (!p.jumpBack || p.jumpBack.episodeId !== p.episode?.id) return null;
  return (
    <button className={`${className} on`} title="Go back to where you jumped from" onClick={player.undoJump}>
      ↶ Back to {formatClock(p.jumpBack.fromMs)}
    </button>
  );
}

/**
 * The chapter list (phase 10): tick boxes (unticked chapters are skipped, synced with the phone),
 * chapter artwork and links.
 */
export function ChapterList({ chapters, positionMs, className, itemClass }: { chapters: Chapter[]; positionMs: number; className?: string; itemClass?: string }) {
  const p = player.usePlayer();
  store.useStore();
  const e = p.episode;
  if (!e || chapters.length === 0) return null;
  const stored = store.episode(e.id);
  const skipped = stored?.skipped_chapters ?? [];
  const current = [...chapters].reverse().find((c) => c.startMs <= positionMs);
  const isSkipped = (c: Chapter) => skipped.some((s) => Math.abs(s - c.startMs) <= 1000);
  return (
    <div className={className}>
      {chapters.some((c) => c.generated) && <div className="muted small">Generated from the transcript</div>}
      {chapters.map((c) => (
        <div key={c.startMs} className={`chap-row ${itemClass ?? ""} ${c === current ? "on" : ""} ${isSkipped(c) ? "skipped" : ""}`}>
          <input
            type="checkbox"
            checked={!isSkipped(c)}
            disabled={!stored}
            title={stored ? (isSkipped(c) ? "Skipped: tick to play this chapter" : "Untick to skip this chapter") : "Queue or play the episode from your library to skip chapters"}
            onChange={(ev) => store.setChapterSkipped(e.id, c.startMs, !ev.target.checked)}
          />
          {c.img && <img className="chap-img" src={c.img} alt="" width={28} height={28} loading="lazy" />}
          <button className="chap-go" onClick={() => player.seekTo(c.startMs)}>
            <span className="ellipsis">{c.title}</span>
            <span className="time">{formatClock(c.startMs)}</span>
          </button>
          {c.url && (
            <a className="chap-link" href={c.url} target="_blank" rel="noreferrer" title="Open chapter link">
              ↗
            </a>
          )}
        </div>
      ))}
    </div>
  );
}

export function PlayerBar({ prefs, podcast, nav }: { prefs: PlaybackDefaults; podcast: PodcastRow | undefined; nav: Nav }) {
  const p = player.usePlayer();
  const [more, setMore] = useState(false);
  const [scrub, setScrub] = useState<number | null>(null);
  const look = useAppearance();
  const e = p.episode;
  const chapters = useChapters(e);
  const tint = useArtColor(e ? (e.image_url ?? podcast?.image_url) : null, e?.feed_url ?? "earshot");
  useSkipRules(e, chapters, podcast, prefs.adSkip ?? "button");

  if (!e) return null;

  const { changeAudio, changeSkip } = audioChanger(prefs, podcast);

  const duration = p.durationMs || e.duration_ms;
  const pos = scrub ?? p.positionMs;
  const chapter = [...chapters].reverse().find((c) => c.startMs <= pos);

  if (look.theme === "glow") {
    return (
      <footer className="gplayer" style={{ "--tint": tint } as React.CSSProperties}>
        <div className="g-now">
          <button className="art-button" title="Open podcast" onClick={() => nav.open({ name: "podcast", feedUrl: e.feed_url })}>
            <Art url={e.image_url ?? podcast?.image_url} size={52} />
          </button>
          <div className="grow ellipsis-col">
            <div className="g-title ellipsis">{e.title}</div>
            {p.error ? (
              <PlayerError className="g-error" />
            ) : (
              <div className="meta ellipsis">
                {chapter?.title ? `${chapter.title} · ` : ""}
                <PodcastLink feedUrl={e.feed_url} title={podcast?.title} />
              </div>
            )}
          </div>
          <button className="icon g-mobile-panel" title="Now playing" onClick={() => setAppearance({ nowPanel: true })}>
            <Icon name="panel" />
          </button>
          <button className="g-mobile-play" title={p.playing ? "Pause" : "Play"} onClick={player.toggle}>
            <Icon name={p.playing ? "pause" : "play"} />
          </button>
        </div>
        <div className="g-ctl">
          <div className="g-btns">
            <button className="g-skip" title={`Back ${skipName(prefs.skipBackMs)}`} onClick={() => player.skipBy(-prefs.skipBackMs)}>
              <Icon name="back" size={28} />
              <small>{skipLabel(prefs.skipBackMs)}</small>
            </button>
            <button className="g-play" title={p.playing ? "Pause" : "Play"} onClick={player.toggle}>
              {p.buffering && !p.playing ? <span className="g-spin" aria-label="Loading" /> : <Icon name={p.playing ? "pause" : "play"} />}
            </button>
            <button className="g-skip" title={`Forward ${skipName(prefs.skipForwardMs)}`} onClick={() => player.skipBy(prefs.skipForwardMs)}>
              <Icon name="forward" size={28} />
              <small>{skipLabel(prefs.skipForwardMs)}</small>
            </button>
          </div>
          <GlowScrubber chapters={chapters} />
        </div>
        <div className="g-side">
          <div className="g-chips">
            <JumpBackButton />
            <SkipAdButton adSkip={prefs.adSkip ?? "button"} />
            <BookmarkButton />
            <TranscriptButton nav={nav} />
            <AudioChips prefs={prefs} podcast={podcast} />
            <SleepSelect className={`chip ${p.sleepAtEnd || p.sleepEndsAt ? "on" : ""}`} label="Sleep" />
            <button className={`chip ${look.nowPanel ? "on" : ""}`} aria-pressed={look.nowPanel} title="Show or hide the Now Playing panel" onClick={() => setAppearance({ nowPanel: !look.nowPanel })}>
              <Icon name="panel" size={13} /> Now playing
            </button>
            <button className={`chip ${more ? "on" : ""}`} aria-expanded={more} title="Skip lengths" onClick={() => setMore(!more)}>
              Skips
            </button>
          </div>
          {more && (
            <div className="g-pop">
              <SkipSelect label="Skip back" value={prefs.skipBackMs} onChange={(v) => changeSkip({ skipBackMs: v })} />
              <SkipSelect label="Skip forward" value={prefs.skipForwardMs} onChange={(v) => changeSkip({ skipForwardMs: v })} />
              <RewindToggle />
              {!p.effectsAvailable && <div className="muted small">Smart Speed and Voice Boost aren't available for this episode: its host doesn't allow browsers to process the audio.</div>}
            </div>
          )}
        </div>
      </footer>
    );
  }

  return (
    <footer className="player">
      <div className="player-row">
        <button className="art-button" title="Open podcast" onClick={() => nav.open({ name: "podcast", feedUrl: e.feed_url })}>
          <Art url={e.image_url ?? podcast?.image_url} size={48} />
        </button>
        <div className="grow ellipsis-col">
          <div className="title ellipsis">{e.title}</div>
          <div className="meta ellipsis">{chapter ? chapter.title : <PodcastLink feedUrl={e.feed_url} title={podcast?.title} />}</div>
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
        <JumpBackButton />
        <SkipAdButton adSkip={prefs.adSkip ?? "button"} />
        <BookmarkButton />
        <TranscriptButton nav={nav} />
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
      <PlayerError />
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
          <RewindToggle />
          {!p.effectsAvailable && <div className="muted small">Smart Speed and Voice Boost aren't available for this episode: its host doesn't allow browsers to process the audio.</div>}
          <ChapterList chapters={chapters} positionMs={pos} className="chapters" />
          <AiPanel episode={e} podcastTitle={podcast?.title ?? ""} prefs={prefs} chapters={chapters} />
        </div>
      )}
    </footer>
  );
}

function skipLabel(ms: number) {
  return ms < 60000 ? String(ms / 1000) : `${ms / 60000}m`;
}

export function SkipSelect({ label, value, onChange }: { label: string; value: number; onChange: (ms: number) => void }) {
  return (
    <label>
      {label}{" "}
      <select value={value} onChange={(ev) => onChange(Number(ev.target.value))}>
        {SKIPS_MS.map((v) => (
          <option key={v} value={v}>
            {skipName(v)}
          </option>
        ))}
      </select>
    </label>
  );
}

/** Phase 13: "Rewind a little after a pause" (synced with the phone). */
function RewindToggle() {
  const s = store.useStore();
  return (
    <label className="small" title="On resuming, back up 2 s (up to 10 s after a long break)">
      <input type="checkbox" checked={s.defaults.resumeRewind !== false} onChange={(ev) => store.updateDefaults({ resumeRewind: ev.target.checked })} /> Rewind a little after a pause
    </label>
  );
}

/** Sleep timer picker: minutes, end of episode, or off. */
export function SleepSelect({ className, label }: { className?: string; label?: string }) {
  const p = player.usePlayer();
  return (
    <select
      className={className}
      aria-label="Sleep timer"
      title="Sleep timer"
      value={p.sleepAtEnd ? "end" : p.sleepEndsAt ? "on" : ""}
      onChange={(ev) => {
        const v = ev.target.value;
        if (v === "end") player.setSleep(null, true);
        else if (v === "") player.setSleep(null);
        else if (v !== "on") player.setSleep(Number(v));
      }}
    >
      <option value="">{label ?? "Off"}</option>
      {p.sleepEndsAt && <option value="on">Sleep {new Date(p.sleepEndsAt).toLocaleTimeString([], { hour: "numeric", minute: "2-digit" })}</option>}
      {[5, 10, 15, 30, 45, 60].map((m) => (
        <option key={m} value={m}>
          {m} minutes
        </option>
      ))}
      <option value="end">End of episode</option>
      {label && (p.sleepEndsAt || p.sleepAtEnd) && <option value="">Turn off</option>}
    </select>
  );
}

/** Glow's scrubber, with a tick for each chapter. */
export function GlowScrubber({ chapters }: { chapters: Chapter[] }) {
  const p = player.usePlayer();
  const [scrub, setScrub] = useState<number | null>(null);
  const duration = p.durationMs || p.episode?.duration_ms || 0;
  const pos = scrub ?? p.positionMs;
  const pct = duration > 0 ? Math.min(100, (Math.min(pos, duration) / duration) * 100) : 0;
  const commit = () => {
    if (scrub != null) player.seekTo(scrub);
    setScrub(null);
  };
  return (
    <div className="g-scrub">
      <span className="time">{formatClock(pos)}</span>
      <div className="g-track">
        {duration > 0 &&
          chapters
            .filter((c) => c.startMs > 0 && c.startMs < duration)
            .map((c) => <i key={c.startMs} className="g-mark" style={{ left: `${(c.startMs / duration) * 100}%` }} title={c.title} />)}
        <input
          type="range"
          aria-label="Position"
          min={0}
          max={Math.max(1, duration)}
          step={1000}
          value={Math.min(pos, duration)}
          style={{ "--pct": `${pct}%` } as React.CSSProperties}
          onChange={(ev) => setScrub(Number(ev.target.value))}
          onPointerUp={commit}
          onKeyUp={commit}
        />
      </div>
      <span className="time">-{formatClock(Math.max(0, duration - pos))}</span>
    </div>
  );
}

/** Speed, Smart Speed and Voice Boost as chips (Glow). */
export function AudioChips({ prefs, podcast }: { prefs: PlaybackDefaults; podcast: PodcastRow | undefined }) {
  const p = player.usePlayer();
  const { changeAudio } = audioChanger(prefs, podcast);
  return (
    <>
      <select className="chip on" title="Speed" aria-label="Speed" value={prefs.speed} onChange={(ev) => changeAudio({ speed: Number(ev.target.value) })}>
        {SPEEDS.map((v) => (
          <option key={v} value={v}>
            {speedName(v)}
          </option>
        ))}
      </select>
      <button
        className={`chip ${prefs.smartSpeed ? "on" : ""}`}
        aria-pressed={prefs.smartSpeed}
        disabled={!p.effectsAvailable}
        title={p.effectsAvailable ? "Smart Speed: shortens silences" : "This episode's host doesn't allow Smart Speed in the browser"}
        onClick={() => changeAudio({ smartSpeed: !prefs.smartSpeed })}
      >
        Smart Speed
      </button>
      <button
        className={`chip ${prefs.voiceBoost ? "on" : ""}`}
        aria-pressed={prefs.voiceBoost}
        disabled={!p.effectsAvailable}
        title={p.effectsAvailable ? "Voice Boost: evens out loudness" : "This episode's host doesn't allow Voice Boost in the browser"}
        onClick={() => changeAudio({ voiceBoost: !prefs.voiceBoost })}
      >
        Voice Boost
      </button>
    </>
  );
}
