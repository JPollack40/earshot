import type { EpisodeRow } from "./types";

/**
 * "Mark older played" / "Mark newer played" (phase 7b). Same rules as the phone's MarkRange.kt.
 *
 * From one episode of a podcast, picks every episode released before it (older) or after it
 * (newer), by release date. The episode itself is never included (it has its own button), nor are
 * episodes released at exactly the same time. Already played episodes don't count. Queued episodes
 * and the one loaded in the player are left alone and counted as skipped.
 */
export type Direction = "older" | "newer";

export interface MarkPick {
  rows: EpisodeRow[];
  skipped: number;
}

export function pickRange(all: EpisodeRow[], anchor: EpisodeRow, direction: Direction, loadedId: string | null | undefined): MarkPick {
  const rows: EpisodeRow[] = [];
  let skipped = 0;
  for (const e of all) {
    if (e.id === anchor.id || e.feed_url !== anchor.feed_url || e.played) continue;
    const inRange = direction === "older" ? e.pub_date < anchor.pub_date : e.pub_date > anchor.pub_date;
    if (!inRange) continue;
    if (e.queue_position != null || e.id === loadedId) skipped++;
    else rows.push(e);
  }
  return { rows, skipped };
}
