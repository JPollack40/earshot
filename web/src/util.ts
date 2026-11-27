import type { EpisodeRow, PlaybackDefaults, PodcastRow } from "./types";

/** Same as the Android app: first 10 bytes of SHA-1(feedUrl + "\n" + guid), hex. Stable across devices. */
export async function episodeId(feedUrl: string, guid: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-1", new TextEncoder().encode(`${feedUrl}\n${guid}`));
  return Array.from(new Uint8Array(digest).slice(0, 10), (b) => b.toString(16).padStart(2, "0")).join("");
}

/** Same as Repository.normalizeUrl on the phone. */
export function normalizeUrl(raw: string): string {
  let u = raw.trim();
  if (u.startsWith("feed://")) u = "https://" + u.slice("feed://".length);
  if (!u.includes("://")) u = "https://" + u;
  return u;
}

/** Same as Repository.feedKey: ignores scheme, "www.", letter case and a trailing slash. */
export function feedKey(raw: string): string {
  let u = normalizeUrl(raw).toLowerCase();
  u = u.slice(u.indexOf("://") + 3);
  if (u.startsWith("www.")) u = u.slice(4);
  return u.replace(/\/+$/, "");
}

export function formatDuration(ms: number): string {
  if (!ms || ms <= 0) return "";
  const m = Math.round(ms / 60000);
  if (m < 60) return `${m}m`;
  return `${Math.floor(m / 60)}h ${String(m % 60).padStart(2, "0")}m`;
}

export function formatClock(ms: number): string {
  if (!isFinite(ms) || ms < 0) ms = 0;
  const s = Math.floor(ms / 1000);
  const h = Math.floor(s / 3600);
  const mm = String(Math.floor((s % 3600) / 60)).padStart(h > 0 ? 2 : 1, "0");
  const ss = String(s % 60).padStart(2, "0");
  return h > 0 ? `${h}:${mm}:${ss}` : `${mm}:${ss}`;
}

export function formatDate(ms: number): string {
  if (!ms) return "";
  const d = new Date(ms);
  const sameYear = d.getFullYear() === new Date().getFullYear();
  return d.toLocaleDateString(undefined, sameYear ? { month: "short", day: "numeric" } : { month: "short", day: "numeric", year: "numeric" });
}

export function skipName(ms: number): string {
  return ms < 60_000 ? `${ms / 1000} s` : `${ms / 60_000} min`;
}

export function speedName(s: number): string {
  return `${s.toFixed(s * 10 === Math.round(s * 10) ? 1 : 2)}×`;
}

/** Episode line: "Nov 24 • 1h 02m" or "… • 32m left". */
export function episodeMeta(e: EpisodeRow, podcastTitle?: string): string {
  const time = e.played
    ? "Played"
    : e.position_ms > 0 && e.duration_ms > 0
      ? `${formatDuration(e.duration_ms - e.position_ms)} left`
      : formatDuration(e.duration_ms);
  return [podcastTitle, formatDate(e.pub_date), time].filter(Boolean).join(" • ");
}

/** Show notes as plain text (for search and snippets). */
export function plainText(html: string): string {
  const doc = new DOMParser().parseFromString(html, "text/html");
  return (doc.body.textContent ?? "").replace(/[‘’]/g, "'").replace(/[“”]/g, '"').replace(/\s+/g, " ").trim();
}

/** Show notes made safe to display: only simple formatting and links survive. */
export function safeHtml(html: string): string {
  const doc = new DOMParser().parseFromString(html, "text/html");
  const allowed = new Set(["P", "BR", "B", "STRONG", "I", "EM", "UL", "OL", "LI", "A"]);
  const walk = (node: Element) => {
    for (const child of Array.from(node.children)) {
      walk(child);
      if (!allowed.has(child.tagName)) {
        child.replaceWith(...Array.from(child.childNodes));
        continue;
      }
      for (const attr of Array.from(child.attributes)) {
        if (!(child.tagName === "A" && attr.name === "href")) child.removeAttribute(attr.name);
      }
      if (child.tagName === "A") {
        const href = child.getAttribute("href") ?? "";
        if (!/^https?:/i.test(href)) child.removeAttribute("href");
        child.setAttribute("target", "_blank");
        child.setAttribute("rel", "noopener noreferrer");
      }
    }
  };
  walk(doc.body);
  return doc.body.innerHTML;
}

/** Search terms: words, and "quoted phrases", lower-cased (same rules as the phone). */
export function searchTerms(query: string): string[] {
  const out: string[] = [];
  for (const m of query.toLowerCase().matchAll(/"([^"]+)"|(\S+)/g)) {
    const t = (m[1] ?? m[2] ?? "").replace(/"/g, "").replace(/\s+/g, " ").trim();
    if (t) out.push(t);
  }
  return out;
}

export function snippet(text: string, terms: string[], radius = 60): string | null {
  const lower = text.toLowerCase();
  let best = -1;
  let len = 0;
  for (const t of terms) {
    const i = lower.indexOf(t);
    if (i >= 0 && (best < 0 || i < best)) {
      best = i;
      len = t.length;
    }
  }
  if (best < 0) return null;
  const start = Math.max(0, best - radius);
  const end = Math.min(text.length, best + len + radius);
  return (start > 0 ? "…" : "") + text.slice(start, end).trim() + (end < text.length ? "…" : "");
}

export function resolvePlayback(d: PlaybackDefaults, p: PodcastRow | undefined): PlaybackDefaults {
  return {
    speed: p?.speed ?? d.speed,
    smartSpeed: p?.smart_speed ?? d.smartSpeed,
    voiceBoost: p?.voice_boost ?? d.voiceBoost,
    skipBackMs: p?.skip_back_ms ?? d.skipBackMs,
    skipForwardMs: p?.skip_forward_ms ?? d.skipForwardMs,
  };
}

export function hasOwnAudio(p: PodcastRow | undefined): boolean {
  return !!p && (p.speed != null || p.smart_speed != null || p.voice_boost != null);
}
