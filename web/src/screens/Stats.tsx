// Stats page and week / month / year in review (same sections and rules as the phone's
// StatsScreen.kt). Data: the listening history in Supabase (`listens`, phone + car + web), with the
// maths in ../stats.ts. Charts are plain CSS bars, no chart library.

import { useEffect, useMemo, useState } from "react";
import { useAppearance, useArtColor } from "../appearance";
import * as store from "../store";
import * as S from "../stats";
import { feedKey, formatDuration } from "../util";
import { Art, openPodcast, PodcastLink, type Nav } from "./common";
import { Glyph } from "./Icons";
import { Hero } from "./Sidebar";
import "../stats.css";

// The history is loaded once per visit to the Stats pages and shared by them; Refresh reloads it.
let cache: { rows: S.ListenRow[]; at: number } | null = null;

function useListens(): { rows: S.ListenRow[] | null; error: string | null; reload: () => void } {
  const [rows, setRows] = useState<S.ListenRow[] | null>(cache && Date.now() - cache.at < 5 * 60_000 ? cache.rows : null);
  const [error, setError] = useState<string | null>(null);
  const [tick, setTick] = useState(0);
  useEffect(() => {
    if (rows && tick === 0) return;
    let cancelled = false;
    setError(null);
    S.loadListens()
      .then((r) => {
        cache = { rows: r, at: Date.now() };
        if (!cancelled) setRows(r);
      })
      .catch((e) => {
        if (cancelled) return;
        const msg = e instanceof Error ? e.message : String(e);
        setError(/does not exist|could not find the|schema cache/i.test(msg) ? "The listening history table isn't set up yet (run 003_automation_playlists.sql)." : msg);
        setRows((r) => r ?? []);
      });
    return () => {
      cancelled = true;
    };
  }, [tick]);
  return { rows, error, reload: () => setTick((t) => t + 1) };
}

const total = (ms: number) => (ms < 60_000 ? "under a minute" : formatDuration(ms));
const plural = (n: number, word: string) => `${n} ${word}${n === 1 ? "" : "s"}`;
const fmt = (ms: number, o: Intl.DateTimeFormatOptions) => new Date(ms).toLocaleDateString(undefined, o);
const dayLabel = (ms: number) => fmt(ms, { weekday: "long", month: "short", day: "numeric" });

function bucketLabel(ms: number, g: S.Granularity) {
  return g === "day" ? fmt(ms, { month: "short", day: "numeric" }) : g === "week" ? `Week of ${fmt(ms, { month: "short", day: "numeric" })}` : fmt(ms, { month: "short", year: "numeric" });
}

export function reviewTitle(kind: S.ReviewKind, from: number) {
  return kind === "week" ? `Week of ${fmt(from, { month: "short", day: "numeric", year: "numeric" })}` : kind === "month" ? fmt(from, { month: "long", year: "numeric" }) : String(new Date(from).getFullYear());
}

/** "7–10 PM", "11 PM–2 AM" for the 3 hours from [start]. */
function hourRange(start: number) {
  const h = (x: number): [number, string] => {
    const hour = ((x % 24) + 24) % 24;
    return [hour === 0 ? 12 : hour > 12 ? hour - 12 : hour, hour < 12 ? "AM" : "PM"];
  };
  const [a, am] = h(start);
  const [b, bm] = h(start + 3);
  return am === bm ? `${a}–${b} ${bm}` : `${a} ${am}–${b} ${bm}`;
}

function compare(now: number, before: number, comparedWith: string) {
  const d = now - before;
  if (before <= 0) return `Nothing ${comparedWith}`;
  if (Math.abs(d) < 60_000) return `Same as ${comparedWith}`;
  return d > 0 ? `${total(d)} more than ${comparedWith}` : `${total(-d)} less than ${comparedWith}`;
}

function durationLookup(s: store.State) {
  return (id: string) => s.episodes.get(id)?.duration_ms || undefined;
}

function artFor(s: store.State, feedUrl: string) {
  return store.findPodcast(feedUrl, s)?.image_url ?? null;
}

/** Glow: tinted hero; Classic: plain bar. */
function Header({ nav, eyebrow, title, summary, tintArt, onRefresh }: { nav: Nav; eyebrow: string; title: string; summary: string | null; tintArt: string | null; onRefresh?: () => void }) {
  const glow = useAppearance().theme === "glow";
  const tint = useArtColor(tintArt, "earshot-stats");
  const back = (
    <button className="icon" title="Back" onClick={nav.back}>
      <Glyph name="arrowLeft" text="←" />
    </button>
  );
  const refresh = onRefresh && (
    <button className="icon" title="Reload listening history" onClick={onRefresh}>
      <Glyph name="sync" text="⟳" />
    </button>
  );
  if (glow) {
    return (
      <Hero tint={tint} eyebrow={eyebrow} title={title} summary={summary ?? undefined}>
        <div className="st-hero-actions">
          {back}
          {refresh}
        </div>
      </Hero>
    );
  }
  return (
    <header className="bar">
      {back}
      <div className="grow">
        <h1 className="ellipsis">{title}</h1>
        <div className="meta ellipsis">{[eyebrow, summary].filter(Boolean).join(" · ")}</div>
      </div>
      {refresh}
    </header>
  );
}

export function StatsPage({ nav }: { nav: Nav }) {
  const s = store.useStore();
  const { rows, error, reload } = useListens();
  const [range, setRange] = useState<S.StatsRange>(() => (sessionStorage.getItem("statsRange") as S.StatsRange) || "fourWeeks");
  const choose = (r: S.StatsRange) => {
    setRange(r);
    try {
      sessionStorage.setItem("statsRange", r);
    } catch {
      /* ignore */
    }
  };
  const now = useMemo(() => Date.now(), [rows]);
  const data = useMemo(() => {
    if (!rows) return null;
    const historyStart = rows.length ? rows[0].started_at : null;
    const p = S.period(range, now, historyStart);
    const prev = range === "all" ? null : { from: p.from - (p.to - p.from), to: p.from };
    return {
      historyStart,
      result: S.compute(rows, p, { feedKey, durationOf: durationLookup(s), previous: prev }),
      streaks: S.streaks(rows, now),
      reviews: S.reviews(rows, now),
    };
  }, [rows, range, s.episodes]);
  const label = S.RANGES.find((r) => r.range === range)!.label.toLowerCase();
  const summary = !data ? null : !rows?.length ? "Nothing yet" : range === "all" ? `${total(data.result.totalMs)} in all` : `${total(data.result.totalMs)} in the last ${label}`;
  const top = data?.result.topPodcasts[0];

  return (
    <>
      <Header nav={nav} eyebrow="Your listening" title="Stats" summary={summary} tintArt={top ? artFor(s, top.feedUrl) : null} onRefresh={reload} />
      <div className="stats">
        {error && <div className="notice error">{error}</div>}
        {!data && <div className="center muted">Loading your listening history…</div>}
        {data && rows && rows.length === 0 && !error && (
          <section className="st-section">
            <h2>Your stats start here</h2>
            <p className="muted">
              Earshot has kept a listening log since the phase 7 update (Nov 27, 2026). Play something and your stats appear here: listening on the phone, in the car and in this web player all count.
            </p>
          </section>
        )}
        {data && rows && rows.length > 0 && (
          <>
            <div className="seg st-ranges" role="group" aria-label="Period">
              {S.RANGES.map((r) => (
                <button key={r.range} aria-pressed={range === r.range} onClick={() => choose(r.range)}>
                  {r.label}
                </button>
              ))}
            </div>
            <StatsBody s={s} r={data.result} comparedWith={`the ${label} before`} streaks={data.streaks} />
            <Reviews nav={nav} reviews={data.reviews} />
            {data.historyStart != null && (
              <p className="small muted st-foot">
                History kept since {fmt(data.historyStart, { month: "short", day: "numeric", year: "numeric" })}. Times are in this browser's time zone. Listening in another tab or just now may take a minute to show (Refresh).
              </p>
            )}
          </>
        )}
      </div>
    </>
  );
}

export function StatsReviewPage({ nav, kind, from }: { nav: Nav; kind: S.ReviewKind; from: number }) {
  const s = store.useStore();
  const { rows, error, reload } = useListens();
  const p = S.reviewPeriod(kind, from);
  const r = useMemo(() => (rows ? S.compute(rows, p, { feedKey, durationOf: durationLookup(s), previous: S.previousReview(kind, p) }) : null), [rows, kind, from, s.episodes]);
  const inProgress = Date.now() < p.to;
  const eyebrow = `${{ week: "Week", month: "Month", year: "Year" }[kind]} in review${inProgress ? " · so far" : ""}`;
  const top = r?.topPodcasts[0];
  return (
    <>
      <Header
        nav={nav}
        eyebrow={eyebrow}
        title={reviewTitle(kind, p.from)}
        summary={r ? (r.totalMs > 0 ? `${total(r.totalMs)} listened` : "No listening") : null}
        tintArt={top ? artFor(s, top.feedUrl) : null}
        onRefresh={reload}
      />
      <div className="stats">
        {error && <div className="notice error">{error}</div>}
        {!r && <div className="center muted">Loading…</div>}
        {r && (
          <>
            <Highlights r={r} kind={kind} />
            <StatsBody s={s} r={r} comparedWith={`the ${kind} before`} streaks={null} />
          </>
        )}
      </div>
    </>
  );
}

function StatsBody({ s, r, comparedWith, streaks }: { s: store.State; r: S.StatsResult; comparedWith: string; streaks: S.Streaks | null }) {
  const [allPods, setAllPods] = useState(false);
  const [allEps, setAllEps] = useState(false);
  if (r.totalMs <= 0) return <p className="muted st-section">No listening in this period.</p>;
  const peak = S.peakHours(r.hours);
  const dayNames = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"];
  const topMs = r.topPodcasts[0]?.ms || 1;
  const facts = [
    streaks && streaks.current > 0 ? `Streak: ${plural(streaks.current, "day")} in a row (best ${streaks.longest})` : null,
    r.savedBySpeedMs >= 60_000 ? `Saved ${total(r.savedBySpeedMs)} by playing faster (average ${(Math.round(r.averageSpeed * 20) / 20).toString()}×)` : null,
  ].filter(Boolean);

  return (
    <>
      <div className="st-tiles">
        <Tile label="Listened" value={total(r.totalMs)} note={r.previousTotalMs != null ? compare(r.totalMs, r.previousTotalMs, comparedWith) : null} />
        <Tile label="Days listened" value={`${r.activeDays} of ${r.days}`} note={r.longestStreak > 1 ? `Longest run: ${r.longestStreak} days` : null} />
        <Tile label="Episodes" value={String(r.episodes)} note={r.finished != null ? `${r.finished} finished` : null} />
        <Tile label="Podcasts" value={String(r.podcasts)} note={null} />
      </div>
      {facts.map((f) => (
        <p key={f} className="small muted st-fact">
          {f}
        </p>
      ))}

      <section className="st-section">
        <h2>Top podcasts</h2>
        <ol className="st-list">
          {(allPods ? r.topPodcasts : r.topPodcasts.slice(0, 5)).map((p, i) => (
            <li key={p.feedUrl + p.title}>
              <button className="st-row" onClick={() => openPodcast(p.feedUrl)} title={`Open ${p.title}`} disabled={!p.feedUrl}>
                <span className="st-rank">{i + 1}</span>
                <Art url={artFor(s, p.feedUrl)} size={40} />
                <span className="grow">
                  <span className="ellipsis st-name">{p.title || "Unknown podcast"}</span>
                  <span className="meta">
                    {total(p.ms)} · {plural(p.episodes, "episode")}
                  </span>
                  <span className="st-share">
                    <span style={{ width: `${Math.max(2, (p.ms / topMs) * 100)}%` }} />
                  </span>
                </span>
              </button>
            </li>
          ))}
        </ol>
        {r.topPodcasts.length > 5 && (
          <button className="text" onClick={() => setAllPods(!allPods)}>
            {allPods ? "Show top 5" : `Show all ${r.topPodcasts.length}`}
          </button>
        )}
      </section>

      <section className="st-section">
        <h2>Top episodes</h2>
        <ol className="st-list">
          {(allEps ? r.topEpisodes.slice(0, 50) : r.topEpisodes.slice(0, 5)).map((e, i) => (
            <li key={e.episodeId} className="st-row">
              <span className="st-rank">{i + 1}</span>
              <span className="grow">
                <span className="st-name st-clamp">{e.title || "Unknown episode"}</span>
                <span className="meta">
                  <PodcastLink feedUrl={e.feedUrl} title={e.podcastTitle || "Unknown podcast"} /> · {total(e.ms)}
                </span>
              </span>
            </li>
          ))}
        </ol>
        {r.topEpisodes.length > 5 && (
          <button className="text" onClick={() => setAllEps(!allEps)}>
            {allEps ? "Show top 5" : "Show more"}
          </button>
        )}
      </section>

      {r.timeline.length > 1 && (
        <section className="st-section">
          <h2>{{ day: "Each day", week: "Each week", month: "Each month" }[r.granularity]}</h2>
          <Bars values={r.timeline.map((b) => b.ms)} titles={r.timeline.map((b) => `${bucketLabel(b.start, r.granularity)}: ${b.ms ? total(b.ms) : "none"}`)} label={`Listening ${r.granularity} by ${r.granularity}`} />
          <div className="st-axis">
            <span>{bucketLabel(r.timeline[0].start, r.granularity)}</span>
            <span>{bucketLabel(r.timeline[r.timeline.length - 1].start, r.granularity)}</span>
          </div>
          {r.busiestDay && (
            <p className="small muted">
              Busiest day: {dayLabel(r.busiestDay.start)} ({total(r.busiestDay.ms)})
            </p>
          )}
        </section>
      )}

      <section className="st-section">
        <h2>Time of day</h2>
        {peak == null ? (
          <p className="small muted">Shows when you listen once there's 10 minutes of listening in this period.</p>
        ) : (
          <>
            <Bars values={r.hours} titles={r.hours.map((ms, h) => `${hourRange(h).split("–")[0]}: ${ms ? total(ms) : "none"}`)} label={`Listening by hour of the day, mostly ${hourRange(peak)}`} />
            <div className="st-axis st-axis4">
              <span>12 AM</span>
              <span>6 AM</span>
              <span>12 PM</span>
              <span>6 PM</span>
            </div>
            <p className="small muted">Mostly {hourRange(peak)}</p>
          </>
        )}
      </section>

      <section className="st-section">
        <h2>Day of the week</h2>
        {r.activeDays < 3 ? (
          <p className="small muted">Shows your pattern after 3 days of listening ({r.activeDays} so far).</p>
        ) : (
          <>
            <Bars values={r.weekdays} titles={r.weekdays.map((ms, i) => `${dayNames[i]}: ${ms ? total(ms) : "none"}`)} label="Listening by day of the week" />
            <div className="st-axis st-axis7">
              {dayNames.map((d) => (
                <span key={d}>{d}</span>
              ))}
            </div>
          </>
        )}
      </section>

      <section className="st-section">
        <h2>Where you listen</h2>
        <Devices r={r} />
      </section>

      {r.newPodcasts && r.newPodcasts.length > 0 && (
        <section className="st-section">
          <h2>New to you</h2>
          <p className="small muted">Podcasts you first played in this period.</p>
          {r.newPodcasts.slice(0, 5).map((p) => (
            <div key={p.feedUrl + p.title} className="st-new">
              <PodcastLink feedUrl={p.feedUrl} title={p.title || "Unknown podcast"} /> <span className="meta">· {total(p.ms)}</span>
            </div>
          ))}
          {r.newPodcasts.length > 5 && <p className="small muted">and {r.newPodcasts.length - 5} more</p>}
        </section>
      )}
    </>
  );
}

function Tile({ label, value, note }: { label: string; value: string; note: string | null }) {
  return (
    <div className="st-tile">
      <div className="small muted">{label}</div>
      <div className="st-value">{value}</div>
      {note && <div className="small muted">{note}</div>}
    </div>
  );
}

function Bars({ values, titles, label }: { values: number[]; titles: string[]; label: string }) {
  const max = Math.max(1, ...values);
  return (
    <div className="st-bars" role="img" aria-label={label}>
      {values.map((v, i) => (
        <span key={i} title={titles[i]} className={v > 0 ? "" : "zero"} style={v > 0 ? { height: `${Math.max(4, (v / max) * 100)}%` } : undefined} />
      ))}
    </div>
  );
}

const DEVICE_NAMES: Record<string, string> = { phone: "Phone", car: "Car", web: "Web" };

function Devices({ r }: { r: S.StatsResult }) {
  const used = r.devices.filter(([, ms]) => ms > 0);
  const pct = (ms: number) => `${Math.round((ms * 100) / r.totalMs)}%`;
  return (
    <>
      <div className="st-devices" role="img" aria-label={used.map(([d, ms]) => `${DEVICE_NAMES[d]} ${pct(ms)}`).join(", ")}>
        {used.map(([d, ms]) => (
          <span key={d} className={`st-dev-${d}`} style={{ flexGrow: ms }} />
        ))}
      </div>
      <div className="st-legend">
        {used.map(([d, ms]) => (
          <span key={d}>
            <i className={`st-dev-${d}`} /> {DEVICE_NAMES[d]} {total(ms)} ({pct(ms)})
          </span>
        ))}
      </div>
    </>
  );
}

function Highlights({ r, kind }: { r: S.StatsResult; kind: S.ReviewKind }) {
  if (r.totalMs <= 0) return null;
  const peak = S.peakHours(r.hours);
  const lines = [
    `You listened for ${total(r.totalMs)}: ${plural(r.episodes, "episode")} from ${plural(r.podcasts, "podcast")}.`,
    r.topPodcasts[0] && `Top podcast: ${r.topPodcasts[0].title} (${total(r.topPodcasts[0].ms)}).`,
    r.topEpisodes[0] && `Most played episode: ${r.topEpisodes[0].title}.`,
    r.busiestDay && (kind !== "year" || r.activeDays > 1) && `Busiest day: ${dayLabel(r.busiestDay.start)}.`,
    peak != null && `Usually ${hourRange(peak)}.`,
    r.previousTotalMs != null && `${compare(r.totalMs, r.previousTotalMs, `the ${kind} before`)}.`,
    r.longestStreak > 1 && `Longest run: ${r.longestStreak} days in a row.`,
  ].filter(Boolean) as string[];
  return (
    <section className="st-highlights">
      <h2>Highlights</h2>
      {lines.map((l) => (
        <p key={l}>{l}</p>
      ))}
    </section>
  );
}

function Reviews({ nav, reviews }: { nav: Nav; reviews: S.Review[] }) {
  if (!reviews.length) return null;
  const groups: [S.ReviewKind, string][] = [
    ["week", "Weeks"],
    ["month", "Months"],
    ["year", "Years"],
  ];
  return (
    <section className="st-section">
      <h2>Reviews</h2>
      <p className="small muted">Every past week, month and year stays here to open again.</p>
      {groups.map(([kind, label]) => {
        const list = reviews.filter((x) => x.kind === kind);
        if (!list.length) return null;
        return (
          <div key={kind}>
            <h3 className="st-sub">{label}</h3>
            {list.map((rv) => (
              <button key={rv.from} className="st-review" onClick={() => nav.open({ name: "statsReview", kind: rv.kind, from: rv.from })}>
                <span className="grow">
                  {reviewTitle(rv.kind, rv.from)}
                  {rv.inProgress ? " (so far)" : ""}
                </span>
                <span className="meta">{total(rv.totalMs)}</span>
                <span aria-hidden="true">›</span>
              </button>
            ))}
          </div>
        );
      })}
    </section>
  );
}
