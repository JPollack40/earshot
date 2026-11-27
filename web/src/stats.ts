// Stats page maths: the same rules as the phone (android/.../data/stats/ListeningStats.kt); keep the
// two in step. Input is the listening history (`listens`, phase 7): one row per stretch of
// listening. A stretch's real listening time is spread evenly over the wall-clock span it covers and
// split at local hour boundaries, so a stretch crossing midnight or a period edge counts on both
// sides. Times are in this browser's time zone. Weeks start on Monday (decided overnight 2026-11-27).

import { supabase } from "./supabase";

export interface ListenRow {
  id: string;
  episode_id: string;
  feed_url: string;
  podcast_title: string;
  episode_title: string;
  started_at: number;
  ended_at: number;
  listened_ms: number;
  to_ms: number;
  speed: number;
  device: string;
}

export type StatsRange = "week" | "fourWeeks" | "sixMonths" | "all";
export const RANGES: { range: StatsRange; label: string }[] = [
  { range: "week", label: "7 days" },
  { range: "fourWeeks", label: "4 weeks" },
  { range: "sixMonths", label: "6 months" },
  { range: "all", label: "All time" },
];

export type ReviewKind = "week" | "month" | "year";
export type Granularity = "day" | "week" | "month";

export interface Period {
  from: number;
  to: number;
}
export interface PodcastTotal {
  feedUrl: string;
  title: string;
  ms: number;
  episodes: number;
}
export interface EpisodeTotal {
  episodeId: string;
  title: string;
  podcastTitle: string;
  feedUrl: string;
  ms: number;
}
export interface Bucket {
  start: number;
  ms: number;
}
export interface Review {
  kind: ReviewKind;
  from: number;
  to: number;
  totalMs: number;
  inProgress: boolean;
}
export interface StatsResult {
  from: number;
  to: number;
  totalMs: number;
  episodes: number;
  podcasts: number;
  activeDays: number;
  days: number;
  /** The same length of time just before; null when history doesn't reach back that far. */
  previousTotalMs: number | null;
  topPodcasts: PodcastTotal[];
  topEpisodes: EpisodeTotal[];
  /** Per local hour, 0 = midnight to 1 AM. */
  hours: number[];
  /** Per day of the week, 0 = Monday. */
  weekdays: number[];
  timeline: Bucket[];
  granularity: Granularity;
  /** phone, car, web, in that order. */
  devices: [string, number][];
  savedBySpeedMs: number;
  averageSpeed: number;
  /** Episodes heard to within a minute of the end; null when no lengths are known. */
  finished: number | null;
  /** Podcasts first heard in this period; null when history starts inside it. */
  newPodcasts: PodcastTotal[] | null;
  longestStreak: number;
  busiestDay: Bucket | null;
}
export interface Streaks {
  current: number;
  longest: number;
}

export const ACTIVE_DAY_MS = 60_000;

// ---- local calendar helpers (browser time zone) ----

/** A local date as a whole number of days, so consecutive days differ by 1 (DST-safe). */
function dayNumber(ms: number): number {
  const d = new Date(ms);
  return Math.round(Date.UTC(d.getFullYear(), d.getMonth(), d.getDate()) / 86_400_000);
}
function dayStartFromNumber(n: number): number {
  const u = new Date(n * 86_400_000);
  return new Date(u.getUTCFullYear(), u.getUTCMonth(), u.getUTCDate()).getTime();
}
export function startOfDay(ms: number): number {
  const d = new Date(ms);
  return new Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime();
}
function addDays(dayStart: number, n: number): number {
  const d = new Date(dayStart);
  return new Date(d.getFullYear(), d.getMonth(), d.getDate() + n).getTime();
}
function addMonths(dayStart: number, n: number): number {
  const d = new Date(dayStart);
  return new Date(d.getFullYear(), d.getMonth() + n, d.getDate()).getTime();
}
/** 0 = Monday. */
function weekday(ms: number): number {
  return (new Date(ms).getDay() + 6) % 7;
}
function mondayOf(ms: number): number {
  const s = startOfDay(ms);
  return addDays(s, -weekday(s));
}
function monthStart(ms: number): number {
  const d = new Date(ms);
  return new Date(d.getFullYear(), d.getMonth(), 1).getTime();
}

/** The range ending now: whole local days, today included. All time starts on the first day with history. */
export function period(range: StatsRange, now: number, historyStart: number | null): Period {
  const today = startOfDay(now);
  const to = addDays(today, 1);
  const from =
    range === "week"
      ? addDays(today, -6)
      : range === "fourWeeks"
        ? addDays(today, -27)
        : range === "sixMonths"
          ? addDays(addMonths(today, -6), 1)
          : historyStart != null
            ? startOfDay(historyStart)
            : today;
  return { from, to };
}

/** The calendar week (Monday to Sunday), month or year containing [at]. */
export function reviewPeriod(kind: ReviewKind, at: number): Period {
  const d = new Date(at);
  if (kind === "week") {
    const from = mondayOf(at);
    return { from, to: addDays(from, 7) };
  }
  if (kind === "month") return { from: new Date(d.getFullYear(), d.getMonth(), 1).getTime(), to: new Date(d.getFullYear(), d.getMonth() + 1, 1).getTime() };
  return { from: new Date(d.getFullYear(), 0, 1).getTime(), to: new Date(d.getFullYear() + 1, 0, 1).getTime() };
}

export const previousReview = (kind: ReviewKind, p: Period): Period => reviewPeriod(kind, p.from - 1);

/** Splits a stretch at local hour boundaries, clipped to [from, to), with each piece's share of the listening time. */
function slices(e: ListenRow, from: number, to: number, slice: (start: number, ms: number) => void) {
  if (e.listened_ms <= 0) return;
  const span = e.ended_at - e.started_at;
  if (span <= 0) {
    if (e.started_at >= from && e.started_at < to) slice(e.started_at, e.listened_ms);
    return;
  }
  const start = Math.max(e.started_at, from);
  const end = Math.min(e.ended_at, to);
  if (end <= start) return;
  let t = start;
  const hour = new Date(start);
  hour.setMinutes(0, 0, 0);
  while (t < end) {
    hour.setHours(hour.getHours() + 1);
    const next = Math.min(hour.getTime(), end);
    if (next > t) slice(t, (e.listened_ms * (next - t)) / span);
    t = next;
  }
}

function daily(rows: ListenRow[]): Map<number, number> {
  const m = new Map<number, number>();
  for (const e of rows) slices(e, -Infinity, Infinity, (s, ms) => m.set(dayNumber(s), (m.get(dayNumber(s)) ?? 0) + ms));
  return m;
}

function longestRun(days: number[]): number {
  if (!days.length) return 0;
  const sorted = [...days].sort((a, b) => a - b);
  let longest = 1;
  let run = 1;
  for (let i = 1; i < sorted.length; i++) {
    run = sorted[i - 1] + 1 === sorted[i] ? run + 1 : 1;
    longest = Math.max(longest, run);
  }
  return longest;
}

/** Days in a row with listening: the run ending today (or yesterday, if today has none yet) and the longest ever. */
export function streaks(rows: ListenRow[], now: number): Streaks {
  const days = new Set([...daily(rows)].filter(([, ms]) => ms >= ACTIVE_DAY_MS).map(([d]) => d));
  if (!days.size) return { current: 0, longest: 0 };
  const today = dayNumber(now);
  let d = days.has(today) ? today : today - 1;
  let current = 0;
  while (days.has(d)) {
    current++;
    d--;
  }
  return { current, longest: longestRun([...days]) };
}

function granularityFor(p: Period): Granularity {
  const days = (p.to - p.from) / 86_400_000;
  return days <= 45 ? "day" : days <= 200 ? "week" : "month";
}
function bucketStart(ms: number, g: Granularity): number {
  return g === "day" ? startOfDay(ms) : g === "week" ? mondayOf(ms) : monthStart(ms);
}
function nextBucket(ms: number, g: Granularity): number {
  return g === "day" ? addDays(ms, 1) : g === "week" ? addDays(ms, 7) : addMonths(ms, 1);
}

const keyOf = (feedKey: (u: string) => string, feedUrl: string, title: string) => feedKey(feedUrl) || `title:${title}`;

/** Everything the Stats page shows for [p]. */
export function compute(
  rows: ListenRow[],
  p: Period,
  opts: { feedKey?: (u: string) => string; durationOf?: (id: string) => number | undefined; previous?: Period | null } = {},
): StatsResult {
  const feedKey = opts.feedKey ?? ((u: string) => u);
  const durationOf = opts.durationOf ?? (() => undefined);
  const historyStart = rows.length ? rows.reduce((m, r) => Math.min(m, r.started_at), Infinity) : null;
  const hours = new Array<number>(24).fill(0);
  const weekdays = new Array<number>(7).fill(0);
  const byDay = new Map<number, number>(); // day start ms -> ms
  const pods = new Map<string, { feedUrl: string; title: string; ms: number; eps: Set<string> }>();
  const eps = new Map<string, { e: ListenRow; ms: number }>();
  const devices = new Map<string, number>([
    ["phone", 0],
    ["car", 0],
    ["web", 0],
  ]);
  let total = 0;
  let speedWeighted = 0;
  let saved = 0;
  const reachedEnd = new Set<string>();

  // Newest first, so a podcast or episode shows its most recent title.
  for (const e of [...rows].sort((a, b) => b.started_at - a.started_at)) {
    let inPeriod = 0;
    slices(e, p.from, p.to, (s, ms) => {
      const d = new Date(s);
      hours[d.getHours()] += ms;
      weekdays[weekday(s)] += ms;
      const day = startOfDay(s);
      byDay.set(day, (byDay.get(day) ?? 0) + ms);
      inPeriod += ms;
    });
    if (inPeriod <= 0) continue;
    total += inPeriod;
    const speed = e.speed > 0 ? e.speed : 1;
    speedWeighted += inPeriod * speed;
    saved += inPeriod * (speed - 1);
    const device = devices.has(e.device) ? e.device : "phone";
    devices.set(device, (devices.get(device) ?? 0) + inPeriod);
    const key = keyOf(feedKey, e.feed_url, e.podcast_title);
    const pod = pods.get(key) ?? { feedUrl: e.feed_url, title: e.podcast_title, ms: 0, eps: new Set<string>() };
    if (!pod.title) pod.title = e.podcast_title;
    pod.ms += inPeriod;
    pod.eps.add(e.episode_id);
    pods.set(key, pod);
    const ep = eps.get(e.episode_id) ?? { e, ms: 0 };
    ep.ms += inPeriod;
    eps.set(e.episode_id, ep);
    const dur = durationOf(e.episode_id);
    if (dur && dur > 0 && e.to_ms >= dur - 60_000) reachedEnd.add(e.episode_id);
  }

  const topPodcasts = [...pods.values()]
    .map((v) => ({ feedUrl: v.feedUrl, title: v.title, ms: Math.trunc(v.ms), episodes: v.eps.size }))
    .filter((x) => x.ms > 0)
    .sort((a, b) => b.ms - a.ms);
  const topEpisodes = [...eps.values()]
    .map(({ e, ms }) => ({ episodeId: e.episode_id, title: e.episode_title, podcastTitle: e.podcast_title, feedUrl: e.feed_url, ms: Math.trunc(ms) }))
    .filter((x) => x.ms > 0)
    .sort((a, b) => b.ms - a.ms);

  const g = granularityFor(p);
  const byBucket = new Map<number, number>();
  for (const [d, ms] of byDay) byBucket.set(bucketStart(d, g), (byBucket.get(bucketStart(d, g)) ?? 0) + ms);
  const timeline: Bucket[] = [];
  const lastDay = startOfDay(p.to - 1);
  for (let b = bucketStart(p.from, g); b <= lastDay; b = nextBucket(b, g)) timeline.push({ start: b, ms: Math.trunc(byBucket.get(b) ?? 0) });

  const active = [...byDay].filter(([, ms]) => ms >= ACTIVE_DAY_MS).map(([d]) => dayNumber(d));
  let busiestDay: Bucket | null = null;
  for (const [d, ms] of byDay) if (ms >= ACTIVE_DAY_MS && (!busiestDay || ms > busiestDay.ms)) busiestDay = { start: d, ms: Math.trunc(ms) };

  let previousTotalMs: number | null = null;
  const prev = opts.previous;
  if (prev && historyStart != null && startOfDay(historyStart) <= prev.from) {
    let t = 0;
    for (const e of rows) slices(e, prev.from, prev.to, (_, ms) => (t += ms));
    previousTotalMs = Math.trunc(t);
  }

  let newPodcasts: PodcastTotal[] | null = null;
  if (historyStart != null && startOfDay(historyStart) < p.from) {
    const first = new Map<string, number>();
    for (const e of rows) {
      const k = keyOf(feedKey, e.feed_url, e.podcast_title);
      first.set(k, Math.min(first.get(k) ?? Infinity, e.started_at));
    }
    newPodcasts = topPodcasts.filter((x) => (first.get(keyOf(feedKey, x.feedUrl, x.title)) ?? -Infinity) >= p.from);
  }

  const anyDuration = [...eps.keys()].some((id) => (durationOf(id) ?? 0) > 0);

  return {
    from: p.from,
    to: p.to,
    totalMs: Math.trunc(total),
    episodes: topEpisodes.length,
    podcasts: topPodcasts.length,
    activeDays: active.length,
    days: dayNumber(lastDay) - dayNumber(p.from) + 1,
    previousTotalMs,
    topPodcasts,
    topEpisodes,
    hours: hours.map(Math.trunc),
    weekdays: weekdays.map(Math.trunc),
    timeline,
    granularity: g,
    devices: [...devices].map(([d, ms]) => [d, Math.trunc(ms)] as [string, number]),
    savedBySpeedMs: Math.max(0, Math.trunc(saved)),
    averageSpeed: total > 0 ? speedWeighted / total : 1,
    finished: anyDuration ? reachedEnd.size : null,
    newPodcasts,
    longestStreak: longestRun(active),
    busiestDay,
  };
}

/** First hour of the 3-hour stretch with the most listening (wrapping past midnight); null when too little. */
export function peakHours(hours: number[]): number | null {
  if (hours.reduce((a, b) => a + b, 0) < 10 * 60_000) return null;
  let best = -1;
  let at = 0;
  for (let h = 0; h < 24; h++) {
    const sum = hours[h] + hours[(h + 1) % 24] + hours[(h + 2) % 24];
    if (sum > best) {
      best = sum;
      at = h;
    }
  }
  return at;
}

/** Weeks (last 12), months (last 12) and years with listening, newest first, worked out from the history. */
export function reviews(rows: ListenRow[], now: number): Review[] {
  const days = daily(rows);
  const out: Review[] = [];
  const kinds: [ReviewKind, number][] = [
    ["week", 12],
    ["month", 12],
    ["year", 100],
  ];
  for (const [kind, limit] of kinds) {
    const totals = new Map<number, number>();
    for (const [d, ms] of days) {
      const from = reviewPeriod(kind, dayStartFromNumber(d)).from;
      totals.set(from, (totals.get(from) ?? 0) + ms);
    }
    [...totals]
      .filter(([, ms]) => ms >= ACTIVE_DAY_MS)
      .sort((a, b) => b[0] - a[0])
      .slice(0, limit)
      .forEach(([from, ms]) => {
        const p = reviewPeriod(kind, from);
        out.push({ kind, from: p.from, to: p.to, totalMs: Math.trunc(ms), inProgress: now < p.to });
      });
  }
  return out;
}

/** All of this account's listening history, oldest first (paged; PostgREST returns 1000 rows at most per call). */
export async function loadListens(): Promise<ListenRow[]> {
  const out: ListenRow[] = [];
  const page = 1000;
  for (let i = 0; i < 200; i++) {
    const { data, error } = await supabase
      .from("listens")
      .select("id,episode_id,feed_url,podcast_title,episode_title,started_at,ended_at,listened_ms,to_ms,speed,device")
      .order("started_at", { ascending: true })
      .order("id", { ascending: true })
      .range(i * page, i * page + page - 1);
    if (error) throw new Error(error.message);
    const rows = (data ?? []) as ListenRow[];
    out.push(...rows.map((r) => ({ ...r, started_at: Number(r.started_at), ended_at: Number(r.ended_at), listened_ms: Number(r.listened_ms), to_ms: Number(r.to_ms), speed: Number(r.speed) })));
    if (rows.length < page) break;
  }
  return out;
}
