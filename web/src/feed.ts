import { SUPABASE_KEY, SUPABASE_URL } from "./config";
import { supabase } from "./supabase";
import type { EpisodeRow } from "./types";
import { episodeId } from "./util";

// Mirrors android FeedParser: RSS 2.0, iTunes tags and the Podcasting 2.0 namespace.

const NS_ITUNES = "http://www.itunes.com/dtds/podcast-1.0.dtd";
const NS_CONTENT = "http://purl.org/rss/1.0/modules/content/";
const PODCAST_NS = [
  "https://podcastindex.org/namespace/1.0",
  "https://github.com/Podcastindex-org/podcast-namespace/blob/main/docs/1.0.md",
];

export interface Person {
  name: string;
  role: string;
  img: string | null;
  href: string | null;
}

export interface ParsedFeed {
  title: string;
  author: string;
  description: string;
  imageUrl: string | null;
  episodes: EpisodeRow[];
  // Podcasting 2.0 extras (phase 10)
  fundingUrl?: string | null;
  fundingText?: string | null;
  persons?: Person[];
  /** podcast:updateFrequency complete="true" or itunes:complete "Yes". */
  complete?: boolean;
  /** Episode guests and hosts, by episode id. */
  episodePersons?: Record<string, Person[]>;
}

function persons(el: Element): Person[] {
  return childrenNamed(el, "person", PODCAST_NS)
    .map((p) => ({
      name: (p.textContent ?? "").trim(),
      role: (p.getAttribute("role") ?? "host").trim().toLowerCase() || "host",
      img: /^https?:\/\//.test(p.getAttribute("img") ?? "") ? p.getAttribute("img") : null,
      href: /^https?:\/\//.test(p.getAttribute("href") ?? "") ? p.getAttribute("href") : null,
    }))
    .filter((p) => p.name);
}

/** "Hosts: A, B · Guest: C", as on the phone. */
export function personsSummary(list: Person[]): string {
  const groups = new Map<string, string[]>();
  for (const p of list) groups.set(p.role, [...(groups.get(p.role) ?? []), p.name]);
  const order = (r: string) => (r === "host" ? 0 : r === "guest" ? 1 : 2);
  return [...groups.entries()]
    .sort((a, b) => order(a[0]) - order(b[0]))
    .map(([role, names]) => `${role[0].toUpperCase()}${role.slice(1)}${names.length > 1 ? "s" : ""}: ${names.join(", ")}`)
    .join(" · ");
}

/** Fetches any URL through the feed-proxy function (browsers can't read most feeds directly). */
export async function proxyFetch(url: string): Promise<string> {
  const { data } = await supabase.auth.getSession();
  const token = data.session?.access_token;
  if (!token) throw new Error("Not signed in");
  const res = await fetch(`${SUPABASE_URL}/functions/v1/feed-proxy?url=${encodeURIComponent(url)}`, {
    headers: { apikey: SUPABASE_KEY, Authorization: `Bearer ${token}` },
  });
  const text = await res.text();
  if (!res.ok) throw new Error(text || `HTTP ${res.status}`);
  return text;
}

const cache = new Map<string, { at: number; feed: ParsedFeed }>();

/** Fetches and parses a feed; episodes get ids for [feedUrl] (use the library's spelling of it). */
export async function fetchFeed(feedUrl: string, maxAgeMs = 5 * 60_000): Promise<ParsedFeed> {
  const hit = cache.get(feedUrl);
  if (hit && Date.now() - hit.at < maxAgeMs) return hit.feed;
  const feed = await parseFeed(await proxyFetch(feedUrl), feedUrl);
  cache.set(feedUrl, { at: Date.now(), feed });
  return feed;
}

function childText(el: Element, name: string, ns: string | null = null): string {
  for (const c of Array.from(el.children)) {
    if (c.localName === name && (ns === null ? !c.namespaceURI || c.namespaceURI === "" : c.namespaceURI === ns)) {
      return (c.textContent ?? "").trim();
    }
  }
  return "";
}

function childEl(el: Element, name: string, nss: (string | null)[]): Element | null {
  for (const c of Array.from(el.children)) {
    if (c.localName !== name) continue;
    const ns = c.namespaceURI ?? null;
    if (nss.some((n) => (n === null ? !ns : ns?.toLowerCase().replace(/\/+$/, "") === n.toLowerCase().replace(/\/+$/, "")))) return c;
  }
  return null;
}

function childrenNamed(el: Element, name: string, nss: string[]): Element[] {
  return Array.from(el.children).filter(
    (c) => c.localName === name && nss.some((n) => c.namespaceURI?.toLowerCase().replace(/\/+$/, "") === n.toLowerCase().replace(/\/+$/, "")),
  );
}

export function parseDuration(raw: string): number {
  const s = raw.trim();
  if (!s) return 0;
  let seconds = 0;
  for (const part of s.split(":")) {
    const n = Number(part.trim());
    if (!isFinite(n)) return 0;
    seconds = seconds * 60 + n;
  }
  return Math.round(seconds * 1000);
}

function transcriptRank(type: string | null): number {
  switch (type?.toLowerCase()) {
    case "text/vtt":
      return 4;
    case "application/x-subrip":
    case "application/srt":
      return 3;
    case "application/json":
      return 2;
    case undefined:
      return 0;
    default:
      return 1;
  }
}

export async function parseFeed(xml: string, feedUrl: string): Promise<ParsedFeed> {
  const doc = new DOMParser().parseFromString(xml, "application/xml");
  if (doc.querySelector("parsererror")) throw new Error("That doesn't look like a podcast feed");
  const channel = doc.querySelector("rss > channel") ?? doc.getElementsByTagName("channel")[0];
  if (!channel) throw new Error("That doesn't look like a podcast feed");

  const itunesImage = childEl(channel, "image", [NS_ITUNES])?.getAttribute("href") || null;
  const rssImage = childEl(channel, "image", [null]);
  const imageUrl = itunesImage || (rssImage ? childText(rssImage, "url") || null : null);
  const description = childText(channel, "description") || childText(channel, "summary", NS_ITUNES);

  const episodes: EpisodeRow[] = [];
  const episodePersons: Record<string, Person[]> = {};
  for (const item of Array.from(channel.children).filter((c) => c.localName === "item" && !c.namespaceURI)) {
    const enclosure = childEl(item, "enclosure", [null]);
    const audioUrl = enclosure?.getAttribute("url")?.trim();
    if (!audioUrl) continue;
    const guid = childText(item, "guid") || audioUrl;
    let transcriptUrl: string | null = null;
    let transcriptType: string | null = null;
    let rank = 0;
    for (const t of childrenNamed(item, "transcript", PODCAST_NS)) {
      const r = transcriptRank(t.getAttribute("type"));
      if (t.getAttribute("url") && r > rank) {
        transcriptUrl = t.getAttribute("url");
        transcriptType = t.getAttribute("type");
        rank = r;
      }
    }
    const pub = Date.parse(childText(item, "pubDate").replace(/\s+/g, " "));
    const id = await episodeId(feedUrl, guid);
    const num = (v: string) => (v.trim() && isFinite(Number(v)) ? Number(v) : null);
    const season = num(childrenNamed(item, "season", PODCAST_NS)[0]?.textContent ?? "") ?? num(childText(item, "season", NS_ITUNES));
    const episodeNo = num(childrenNamed(item, "episode", PODCAST_NS)[0]?.textContent ?? "") ?? num(childText(item, "episode", NS_ITUNES));
    const people = persons(item);
    if (people.length) episodePersons[id] = people;
    episodes.push({
      id,
      feed_url: feedUrl,
      guid,
      title: childText(item, "title") || "Untitled episode",
      description: childText(item, "encoded", NS_CONTENT) || childText(item, "description") || childText(item, "summary", NS_ITUNES),
      pub_date: isFinite(pub) ? pub : 0,
      audio_url: audioUrl,
      mime_type: enclosure?.getAttribute("type") ?? null,
      duration_ms: parseDuration(childText(item, "duration", NS_ITUNES)),
      image_url: childEl(item, "image", [NS_ITUNES])?.getAttribute("href") ?? null,
      chapters_url: childrenNamed(item, "chapters", PODCAST_NS)[0]?.getAttribute("url") ?? null,
      transcript_url: transcriptUrl,
      transcript_type: transcriptType,
      position_ms: 0,
      played: false,
      last_played_at: 0,
      in_inbox: false,
      queue_position: null,
      season: season != null ? Math.trunc(season) : null,
      episode_no: episodeNo,
      changed_at: 0,
    });
  }
  const funding = childrenNamed(channel, "funding", PODCAST_NS)[0];
  const fundingUrl = funding?.getAttribute("url")?.trim() ?? "";
  const updateFrequency = childrenNamed(channel, "updateFrequency", PODCAST_NS)[0];
  // Same feed listing a guid twice: keep the first, as the phone does.
  const seen = new Set<string>();
  return {
    title: childText(channel, "title"),
    author: childText(channel, "author", NS_ITUNES),
    description,
    imageUrl,
    episodes: episodes.filter((e) => (seen.has(e.id) ? false : (seen.add(e.id), true))).sort((a, b) => b.pub_date - a.pub_date),
    fundingUrl: /^https?:\/\//.test(fundingUrl) ? fundingUrl : null,
    fundingText: (funding?.textContent ?? "").trim() || null,
    persons: persons(channel),
    complete: updateFrequency?.getAttribute("complete")?.trim().toLowerCase() === "true" || childText(channel, "complete", NS_ITUNES).toLowerCase() === "yes",
    episodePersons,
  };
}

export interface Chapter {
  startMs: number;
  title: string;
  /** Phase 10: chapter artwork and link, and an end time when the chapters give one. */
  img?: string | null;
  url?: string | null;
  endMs?: number | null;
  /** Phase 11: made from the transcript. */
  generated?: boolean;
}

const httpUrl = (v: unknown): string | null => (typeof v === "string" && /^https?:\/\//.test(v) ? v : null);

export function parseChapters(o: { chapters?: unknown[] }): Chapter[] {
  const list: Chapter[] = ((o.chapters ?? []) as any[])
    .filter((c) => c && c.toc !== false)
    .map((c, i) => {
      const startMs = Math.round((Number(c.startTime) || 0) * 1000);
      const end = Number(c.endTime);
      return {
        startMs,
        title: c.title || `Chapter ${i + 1}`,
        img: httpUrl(c.img),
        url: httpUrl(c.url),
        endMs: isFinite(end) && end * 1000 > startMs ? Math.round(end * 1000) : null,
      };
    });
  return list.sort((a, b) => a.startMs - b.startMs);
}

export async function fetchChapters(url: string): Promise<Chapter[]> {
  return parseChapters(JSON.parse(await proxyFetch(url)));
}
