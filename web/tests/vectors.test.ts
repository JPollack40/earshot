// Phase 15: the web player's copies of rules the phone also has, checked against the same cases as
// android/.../SharedVectorsTest.kt (shared/test-vectors/), so the two can't drift apart.
import { readFileSync } from "node:fs";
import { describe, expect, test } from "vitest";
import type { EpisodeRow, PodcastRow } from "../src/types";
import { feedKey } from "../src/util";
import { pickRange } from "../src/markRange";
import { filtersOf, normalize, select, words, type PlaylistRow } from "../src/playlists";

// eslint-disable-next-line @typescript-eslint/no-explicit-any
const vectors = (name: string): any => JSON.parse(readFileSync(new URL(`../../shared/test-vectors/${name}`, import.meta.url), "utf8"));

function episode(o: Partial<EpisodeRow> & { id: string }): EpisodeRow {
  return {
    feed_url: "", guid: o.id, title: o.id, description: "", pub_date: 0, audio_url: `https://example.com/${o.id}.mp3`, mime_type: null,
    duration_ms: 0, image_url: null, chapters_url: null, transcript_url: null, transcript_type: null, position_ms: 0, played: false,
    last_played_at: 0, in_inbox: false, queue_position: null, changed_at: 0, ...o,
  };
}

describe("shared test vectors", () => {
  test("feedKey", () => {
    for (const c of vectors("feedKey.json").cases) {
      expect(feedKey(c.a) === feedKey(c.b), `${c.a} vs ${c.b}`).toBe(c.same);
    }
  });

  test("keywords", () => {
    const v = vectors("keywords.json");
    for (const c of v.normalize) expect(normalize(c.in)).toBe(c.out);
    for (const c of v.words) expect(words(c.in)).toEqual(c.out);
  });

  test("markRange", () => {
    const v = vectors("markRange.json");
    const all: EpisodeRow[] = v.episodes.map((o: { id: string; podcast: string; pub: number; played?: boolean; queue?: number }) =>
      episode({ id: o.id, feed_url: o.podcast, pub_date: o.pub, played: !!o.played, queue_position: o.queue ?? null }));
    for (const c of v.cases) {
      const anchor = all.find((e) => e.id === c.anchor)!;
      const r = pickRange(all, anchor, c.direction, c.loaded);
      expect(r.rows.map((e) => e.id), `${c.anchor} ${c.direction}`).toEqual(c.ids);
      expect(r.skipped, `${c.anchor} ${c.direction}`).toBe(c.skipped);
    }
  });

  test("smart playlists", () => {
    const v = vectors("playlists.json");
    const podcasts = new Map<string, PodcastRow>();
    for (const url of v.subscribed as string[]) podcasts.set(url, { feed_url: url, subscribed: true } as PodcastRow);
    const all: EpisodeRow[] = v.episodes.map((o: { id: string; feed: string; title: string; pub: number; durationMs: number; positionMs: number; played?: boolean }) =>
      episode({ id: o.id, feed_url: o.feed, title: o.title, pub_date: o.pub, duration_ms: o.durationMs, position_ms: o.positionMs, played: !!o.played }));
    for (const c of v.cases) {
      const f = filtersOf({ filters: c.filters } as PlaylistRow);
      expect(select(f, all, podcasts, v.now).map((e) => e.id), c.name).toEqual(c.ids);
    }
  });
});
