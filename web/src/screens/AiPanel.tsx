import { AD_SKIP_LABELS, generate, useAiState, useEpisodeAi, type AiKind } from "../ai";
import type { Chapter } from "../feed";
import * as player from "../player";
import * as store from "../store";
import type { EpisodeRow, PlaybackDefaults } from "../types";
import { formatClock } from "../util";

/**
 * Phase 11: a summary with key moments, chapters and ads, made on request from the playing
 * episode's transcript by Gemini (on the server). Shared with the phone. Times jump there.
 */
export function AiPanel({ episode, podcastTitle, prefs, chapters }: { episode: EpisodeRow; podcastTitle: string; prefs: PlaybackDefaults; chapters: Chapter[] }) {
  const ai = useEpisodeAi(episode.id);
  const s = useAiState();
  const key = (k: AiKind) => `${episode.id}|${k}`;
  const busy = (k: AiKind) => s.running.has(key(k));
  const err = (k: AiKind) => s.errors.get(key(k));
  const make = (k: AiKind) => void generate(episode, podcastTitle, k);
  const hasOwn = chapters.length > 0 && !chapters.some((c) => c.generated);

  const Button = ({ kind, label, again }: { kind: AiKind; label: string; again: boolean }) => (
    <>
      <button className="chip" disabled={busy(kind)} onClick={() => make(kind)}>
        {busy(kind) ? "Working…" : again ? `${label} again` : label}
      </button>
      {err(kind) && <div className="error small">{err(kind)}</div>}
    </>
  );

  return (
    <div className="ai-panel">
      <div className="muted small">Made by Gemini from this episode's transcript (it needs a timed transcript). It can make mistakes.</div>

      <h5>Summary</h5>
      {ai.summary && (
        <>
          {ai.summary.text
            .split("\n")
            .filter((t) => t.trim())
            .map((t, i) => (
              <p key={i}>{t.trim()}</p>
            ))}
          {ai.summary.points.map((p) => (
            <button key={p.atMs} className="ai-time" onClick={() => player.seekTo(p.atMs)}>
              <span className="time">{formatClock(p.atMs)}</span> {p.text}
            </button>
          ))}
        </>
      )}
      <Button kind="summary" label="Summarise this episode" again={!!ai.summary} />

      <h5>Chapters</h5>
      <div className="muted small">
        {hasOwn
          ? `This episode has its own chapters (${chapters.length}), so generated ones aren't used.`
          : ai.chapters
            ? `${ai.chapters.length} chapters made from the transcript (in the chapter list).`
            : "No chapters yet. Make some from the transcript to navigate it and skip parts."}
      </div>
      {!hasOwn && <Button kind="chapters" label="Make chapters" again={!!ai.chapters} />}

      <h5>Ads</h5>
      {ai.ads && ai.ads.length === 0 && <div className="muted small">No ads were found in this episode.</div>}
      {ai.ads?.map((a) => (
        <button key={a.startMs} className="ai-time" onClick={() => player.seekTo(a.startMs)}>
          <span className="time">{formatClock(a.startMs)}</span> {a.label} ({formatClock(a.endMs - a.startMs)})
        </button>
      ))}
      <Button kind="ads" label="Find ads" again={!!ai.ads} />
      <label className="small">
        Found ads (all episodes){" "}
        <select value={prefs.adSkip ?? "auto"} onChange={(ev) => store.updateDefaults({ adSkip: ev.target.value })}>
          {Object.entries(AD_SKIP_LABELS).map(([v, l]) => (
            <option key={v} value={v}>
              {l}
            </option>
          ))}
        </select>
      </label>
    </div>
  );
}
