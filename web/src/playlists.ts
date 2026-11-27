// Smart playlists (phase 7): saved filters over the episodes of subscribed podcasts. Same JSON and
// the same rules as the phone (android/.../data/SmartPlaylists.kt). The web player only knows the
// episodes the phone has synced (anything with listening state, New Episodes, and the last 60 days),
// so a playlist here can be shorter than on the phone; "downloaded only" is ignored here.

import type { EpisodeRow, PodcastRow } from "./types";
import { feedKey } from "./util";

export type PlaylistStatus = "unplayed" | "not_started" | "in_progress" | "played" | "any";
export type PlaylistSort = "newest" | "oldest" | "shortest" | "longest";

export interface PlaylistFilters {
  podcasts: string[]; // feed urls; empty = every subscribed podcast
  status: PlaylistStatus;
  downloaded: boolean; // phone only
  minMinutes: number | null;
  maxMinutes: number | null;
  withinDays: number | null;
  keywords: string; // comma-separated; the title must contain one
  sort: PlaylistSort;
  limit: number;
}

export interface PlaylistRow {
  id: string;
  name: string;
  position: number;
  filters: Partial<PlaylistFilters>;
  deleted: boolean;
  changed_at: number;
  rev?: number;
}

export const DEFAULT_FILTERS: PlaylistFilters = {
  podcasts: [],
  status: "unplayed",
  downloaded: false,
  minMinutes: null,
  maxMinutes: null,
  withinDays: null,
  keywords: "",
  sort: "newest",
  limit: 100,
};

export const STATUS_LABELS: Record<PlaylistStatus, string> = {
  unplayed: "Unplayed (incl. in progress)",
  not_started: "Not started",
  in_progress: "In progress",
  played: "Played",
  any: "Any",
};

export const SORT_LABELS: Record<PlaylistSort, string> = {
  newest: "Newest first",
  oldest: "Oldest first",
  shortest: "Shortest first",
  longest: "Longest first",
};

export const DAY_CHOICES = [1, 3, 7, 14, 30, 60, 90, 365];
export const LIMIT_CHOICES = [10, 25, 50, 100, 200];

/** Filters with every field filled in (rows from older devices may lack some). */
export function filtersOf(p: PlaylistRow): PlaylistFilters {
  const f = { ...DEFAULT_FILTERS, ...(p.filters ?? {}) };
  const pos = (n: unknown) => (typeof n === "number" && n > 0 ? n : null);
  return {
    ...f,
    podcasts: Array.isArray(f.podcasts) ? f.podcasts.filter(Boolean) : [],
    minMinutes: pos(f.minMinutes),
    maxMinutes: pos(f.maxMinutes),
    withinDays: pos(f.withinDays),
    keywords: f.keywords ?? "",
    limit: Math.min(500, Math.max(1, Number(f.limit) || 100)),
  };
}

/** Lower case, curly quotes made straight (same as the phone's Automation.normalize). */
export function normalize(s: string): string {
  return s.toLowerCase().replace(/[‘’]/g, "'").replace(/[“”]/g, '"');
}

/** Comma-separated words or phrases, trimmed and lower-cased. */
export function words(list: string | null | undefined): string[] {
  return (list ?? "")
    .split(",")
    .map((w) => normalize(w).trim())
    .filter(Boolean);
}

/** The playlist's episodes, in order. */
export function select(f: PlaylistFilters, episodes: Iterable<EpisodeRow>, podcasts: Map<string, PodcastRow>, now = Date.now()): EpisodeRow[] {
  const keys = new Set(f.podcasts.map(feedKey));
  const subscribed = new Set([...podcasts.values()].filter((p) => p.subscribed).map((p) => p.feed_url));
  const ws = words(f.keywords);
  const since = f.withinDays ? now - f.withinDays * 86_400_000 : null;
  const out: EpisodeRow[] = [];
  for (const e of episodes) {
    if (!subscribed.has(e.feed_url)) continue;
    if (keys.size && !keys.has(feedKey(e.feed_url))) continue;
    const ok =
      f.status === "unplayed"
        ? !e.played
        : f.status === "not_started"
          ? !e.played && e.position_ms === 0
          : f.status === "in_progress"
            ? !e.played && e.position_ms > 0
            : f.status === "played"
              ? e.played
              : true;
    if (!ok) continue;
    // Unknown length (0) only passes when there's no length filter.
    if (f.minMinutes && e.duration_ms < f.minMinutes * 60_000) continue;
    if (f.maxMinutes && !(e.duration_ms > 0 && e.duration_ms <= f.maxMinutes * 60_000)) continue;
    if (since != null && e.pub_date < since) continue;
    if (ws.length) {
      const t = normalize(e.title);
      if (!ws.some((w) => t.includes(w))) continue;
    }
    out.push(e);
  }
  const unknownLast = (a: EpisodeRow, b: EpisodeRow) => Number(a.duration_ms <= 0) - Number(b.duration_ms <= 0);
  out.sort((a, b) =>
    f.sort === "newest"
      ? b.pub_date - a.pub_date
      : f.sort === "oldest"
        ? a.pub_date - b.pub_date
        : f.sort === "shortest"
          ? unknownLast(a, b) || a.duration_ms - b.duration_ms
          : unknownLast(a, b) || b.duration_ms - a.duration_ms,
  );
  return out.slice(0, f.limit);
}

/** "3 podcasts · unplayed · under 30 min · newest first" */
export function summary(f: PlaylistFilters, titleOf: (feedUrl: string) => string | undefined): string {
  const parts: string[] = [];
  parts.push(f.podcasts.length === 0 ? "All podcasts" : f.podcasts.length === 1 ? (titleOf(f.podcasts[0]) ?? "1 podcast") : `${f.podcasts.length} podcasts`);
  if (f.status !== "any") parts.push(STATUS_LABELS[f.status].replace(/ \(.*\)$/, "").toLowerCase());
  if (f.downloaded) parts.push("downloaded (phone)");
  if (f.minMinutes && f.maxMinutes) parts.push(`${f.minMinutes}–${f.maxMinutes} min`);
  else if (f.minMinutes) parts.push(`over ${f.minMinutes} min`);
  else if (f.maxMinutes) parts.push(`under ${f.maxMinutes} min`);
  if (f.withinDays) parts.push(`last ${f.withinDays} days`);
  if (words(f.keywords).length) parts.push(`"${f.keywords.trim()}"`);
  parts.push(SORT_LABELS[f.sort].toLowerCase());
  return parts.join(" · ");
}
