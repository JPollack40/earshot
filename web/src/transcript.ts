// Transcript formats, quotes, search and Markdown export. Mirrors the phone's TranscriptFormat.kt and
// MarkdownExport.kt so a transcript or an export looks the same wherever it was made.
//
// Stored shape (Supabase "transcripts.segments", same on the phone):
//   [{ "s": startMs, "e": endMs, "t": "text", "sp": "speaker or null" }]

export interface Segment {
  s: number;
  e: number;
  t: string;
  sp: string | null;
}

export interface ParsedTranscript {
  segments: Segment[];
  timed: boolean;
}

// ------------------------------------------------------------ feed formats

/** Parses a feed transcript by its declared type, falling back to sniffing the content. */
export function parseTranscript(body: string, type: string | null | undefined): ParsedTranscript {
  const text = body.replace(/^﻿/, "");
  const trimmed = text.trimStart();
  const t = (type ?? "").toLowerCase();
  if (t === "text/vtt" || trimmed.startsWith("WEBVTT")) return { segments: tidy(parseCues(text)), timed: true };
  if (t.includes("subrip") || t.includes("srt")) return { segments: tidy(parseCues(text)), timed: true };
  if (t.includes("json") || trimmed.startsWith("{")) return { segments: tidy(parseJson(trimmed)), timed: true };
  if (t.includes("html") || /<(p|br|html|body|div)\b/i.test(text)) return { segments: parseHtml(text), timed: false };
  if (text.includes("-->")) return { segments: tidy(parseCues(text)), timed: true };
  return { segments: parsePlain(text), timed: false };
}

const CUE_TIME = /((?:\d+:)?\d{1,2}:\d{2}[.,]\d{1,3})\s*-->\s*((?:\d+:)?\d{1,2}:\d{2}[.,]\d{1,3})/;
const VOICE = /<v(?:\.[^ >]*)?\s+([^>]+)>/;

/** "01:02:03.450", "02:03,450" → ms */
function clockMs(s: string): number {
  let sec = 0;
  for (const p of s.replace(",", ".").split(":")) sec = sec * 60 + (Number(p) || 0);
  return Math.round(sec * 1000);
}

function decode(s: string): string {
  return s
    .replace(/&amp;/g, "&")
    .replace(/&lt;/g, "<")
    .replace(/&gt;/g, ">")
    .replace(/&quot;/g, '"')
    .replace(/&#39;/g, "'")
    .replace(/&apos;/g, "'")
    .replace(/&nbsp;/g, " ");
}

/** WebVTT and SRT: each cue is a time line followed by text lines, ended by a blank line. */
function parseCues(text: string): Segment[] {
  const out: Segment[] = [];
  const lines = text.replace(/\r\n/g, "\n").replace(/\r/g, "\n").split("\n");
  let i = 0;
  while (i < lines.length) {
    const m = CUE_TIME.exec(lines[i]);
    if (!m) {
      i++;
      continue;
    }
    const start = clockMs(m[1]);
    const end = clockMs(m[2]);
    i++;
    const parts: string[] = [];
    let speaker: string | null = null;
    while (i < lines.length && lines[i].trim() !== "" && !CUE_TIME.test(lines[i])) {
      const v = VOICE.exec(lines[i]);
      if (v) speaker = v[1].trim();
      parts.push(decode(lines[i].replace(/<[^>]*>/g, "")));
      i++;
    }
    const cue = parts.join(" ").replace(/\s+/g, " ").trim();
    if (cue) out.push({ s: start, e: Math.max(end, start), t: cue, sp: speaker });
  }
  return out;
}

/** Podcasting 2.0 JSON: {"segments":[{"speaker":"..","startTime":1.2,"endTime":3.4,"body":".."}]} */
function parseJson(text: string): Segment[] {
  const root = JSON.parse(text) as { segments?: { speaker?: string; startTime?: number; endTime?: number; body?: string }[] };
  const out: Segment[] = [];
  for (const o of root.segments ?? []) {
    const body = (o.body ?? "").trim();
    if (!body) continue;
    const start = Math.round((o.startTime ?? 0) * 1000);
    const end = Math.round((o.endTime ?? o.startTime ?? 0) * 1000);
    out.push({ s: start, e: Math.max(end, start), t: decode(body), sp: o.speaker?.trim() || null });
  }
  return out;
}

/** HTML transcripts have no times: one untimed segment per paragraph. */
function parseHtml(text: string): Segment[] {
  const blocks = text
    .replace(/<(script|style)[^>]*>[\s\S]*?<\/\1>/gi, " ")
    .replace(/<br\s*\/?>/gi, "\n")
    .replace(/<\/(p|div|h[1-6]|li|cite)>/gi, "\n\n");
  const doc = new DOMParser().parseFromString(blocks, "text/html");
  return parsePlain(doc.body.textContent ?? "");
}

/** Plain text: one untimed segment per paragraph. */
function parsePlain(text: string): Segment[] {
  return text
    .replace(/\r\n/g, "\n")
    .split(/\n\s*\n/)
    .map((p) => p.replace(/\s+/g, " ").trim())
    .filter(Boolean)
    .map((t) => ({ s: 0, e: 0, t, sp: null }));
}

/**
 * Joins very short cues (some feeds have one cue per word or per couple of words) into readable
 * segments: same speaker, until a segment is 6 s long and ends a sentence, or reaches 20 s.
 */
function tidy(segments: Segment[]): Segment[] {
  if (!segments.length) return segments;
  const sorted = [...segments].sort((a, b) => a.s - b.s);
  const out: Segment[] = [];
  let cur = sorted[0];
  for (const s of sorted.slice(1)) {
    const len = cur.e - cur.s;
    const sentenceEnd = /[.?!"]$/.test(cur.t.trimEnd());
    const join = s.sp === cur.sp && s.s - cur.e < 1500 && len < 20_000 && !(sentenceEnd && len >= 6_000) && (len < 3_000 || s.e - s.s < 3_000);
    if (join) cur = { s: cur.s, e: Math.max(cur.e, s.e), t: `${cur.t} ${s.t}`, sp: cur.sp };
    else {
      out.push(cur);
      cur = s;
    }
  }
  out.push(cur);
  return out;
}

/** Gemini labels speakers "spk_1", "spk_2": shown as "Speaker 1". Feed names are shown as they are. */
export function speakerName(sp: string): string {
  const m = /^spk_(\d+)$/.exec(sp);
  return m ? `Speaker ${m[1]}` : sp;
}

// ------------------------------------------------------------ quotes and search

/** Lower-cased, curly quotes made straight: what search compares. */
export function normalize(s: string): string {
  return s.toLowerCase().replace(/[‘’]/g, "'").replace(/[“”]/g, '"');
}

/** The transcript text between [startMs] and [endMs] (segments that overlap it), or null. */
export function quote(segments: Segment[], timed: boolean, startMs: number, endMs: number): string | null {
  if (!timed) return null;
  const q = segments
    .filter((s) => s.e >= startMs && s.s <= endMs)
    .map((s) => s.t)
    .join(" ")
    .trim();
  return q || null;
}

/** Index of the segment playing at [positionMs] (the last one starting at or before it), or -1. */
export function indexAt(segments: Segment[], positionMs: number): number {
  let lo = 0;
  let hi = segments.length - 1;
  let ans = -1;
  while (lo <= hi) {
    const mid = (lo + hi) >>> 1;
    if (segments[mid].s <= positionMs) {
      ans = mid;
      lo = mid + 1;
    } else hi = mid - 1;
  }
  return ans;
}

/**
 * Segments that match search terms (from util.searchTerms, after normalize): every term must appear
 * in the segment or the one after it (so a phrase split across two is still found), and at least one
 * must start in the segment itself. Same rule as the phone and the search_transcripts function.
 */
export function matchingSegments(segments: Segment[], terms: string[]): number[] {
  if (!terms.length) return [];
  const lower = segments.map((s) => normalize(s.t));
  const hits: number[] = [];
  for (let i = 0; i < lower.length; i++) {
    const window = i + 1 < lower.length ? `${lower[i]} ${lower[i + 1]}` : lower[i];
    if (terms.every((t) => window.includes(t)) && terms.some((t) => window.indexOf(t) < lower[i].length && window.indexOf(t) >= 0)) hits.push(i);
  }
  return hits;
}

// ------------------------------------------------------------ Markdown

export interface ExportEpisode {
  title: string;
  podcastTitle: string;
  audioUrl: string | null;
  pubDate: number;
}

export interface ExportTranscript {
  segments: Segment[];
  timed: boolean;
  source: string;
}

export interface ExportBookmark {
  position_ms: number;
  start_ms: number;
  end_ms: number;
  note: string;
}

/** "1:02:03" or "2:03" */
export function clock(ms: number): string {
  const t = Math.floor(Math.max(0, ms) / 1000);
  const h = Math.floor(t / 3600);
  const m = Math.floor((t % 3600) / 60);
  const s = String(t % 60).padStart(2, "0");
  return h > 0 ? `${h}:${String(m).padStart(2, "0")}:${s}` : `${m}:${s}`;
}

function isoDate(ms: number): string {
  if (ms <= 0) return "";
  const d = new Date(ms);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
}

/** A file name for an export: letters, digits, spaces and dashes only. */
export function exportFileName(base: string): string {
  const clean = base
    .replace(/[^A-Za-z0-9 -]/g, "")
    .replace(/\s+/g, " ")
    .trim()
    .slice(0, 80)
    .trim();
  return (clean || "Earshot") + ".md";
}

function header(e: ExportEpisode): string {
  let out = `# ${e.title.trim() || "Episode"}\n\n`;
  out += `**Podcast:** ${e.podcastTitle}  \n`;
  if (e.pubDate > 0) out += `**Published:** ${isoDate(e.pubDate)}  \n`;
  if (e.audioUrl) out += `**Audio:** <${e.audioUrl}>  \n`;
  return out + "\n";
}

function quoteBlock(text: string): string {
  return text
    .split("\n")
    .map((l) => `> ${l}`)
    .join("\n");
}

function bookmarkBlock(b: ExportBookmark, t: ExportTranscript | null, level = "###"): string {
  let out = `${level} ${clock(b.position_ms)}\n\n`;
  const q = t ? quote(t.segments, t.timed, b.start_ms, b.end_ms) : null;
  if (q) out += quoteBlock(q) + "\n\n";
  if (b.note.trim()) out += b.note.trim() + "\n\n";
  if (!q && !b.note.trim()) out += "_No transcript for this moment yet._\n\n";
  return out;
}

const finish = (s: string) => s.trimEnd() + "\n";

/** One episode's bookmarks, in order through the episode. */
export function episodeBookmarksMd(e: ExportEpisode, bookmarks: ExportBookmark[], t: ExportTranscript | null): string {
  let out = header(e) + "## Bookmarks\n\n";
  if (!bookmarks.length) out += "_No bookmarks._\n";
  for (const b of [...bookmarks].sort((a, b2) => a.position_ms - b2.position_ms)) out += bookmarkBlock(b, t);
  return finish(out);
}

/** Every bookmark, grouped by episode (in the order given: most recently bookmarked first). */
export function allBookmarksMd(groups: { episode: ExportEpisode; bookmarks: ExportBookmark[]; transcript: ExportTranscript | null }[], exportedAt: number): string {
  let out = `# Earshot bookmarks\n\n_Exported ${isoDate(exportedAt)}_\n\n`;
  for (const g of groups) {
    out += `## ${g.episode.title.trim() || "Episode"}\n\n*${g.episode.podcastTitle}*`;
    if (g.episode.pubDate > 0) out += ` · ${isoDate(g.episode.pubDate)}`;
    out += "\n\n";
    for (const b of [...g.bookmarks].sort((a, b2) => a.position_ms - b2.position_ms)) out += bookmarkBlock(b, g.transcript, "####");
  }
  return finish(out);
}

/**
 * The whole transcript, one paragraph per segment with its time (and speaker when it changes), with
 * each bookmark marked just before the segment it falls in.
 */
export function fullTranscriptMd(e: ExportEpisode, t: ExportTranscript, bookmarks: ExportBookmark[]): string {
  let out = header(e) + "## Transcript\n\n";
  out += t.source === "gemini" ? "_Transcribed by Gemini._\n\n" : "_Transcript from the podcast's feed._\n\n";
  const pending = [...bookmarks].sort((a, b) => a.position_ms - b.position_ms);
  let lastSpeaker: string | null = null;
  t.segments.forEach((s, i) => {
    if (t.timed) {
      const nextStart = t.segments[i + 1]?.s ?? Infinity;
      while (pending.length && pending[0].position_ms < nextStart) {
        const b = pending.shift()!;
        out += `> **🔖 Bookmark ${clock(b.position_ms)}**`;
        if (b.note.trim()) out += `: ${b.note.trim().replace(/\n/g, " ")}`;
        out += "\n\n";
      }
    }
    if (s.sp && s.sp !== lastSpeaker) {
      out += `**${speakerName(s.sp)}:** `;
      lastSpeaker = s.sp;
    }
    if (t.timed) out += `[${clock(s.s)}] `;
    out += s.t + "\n\n";
  });
  if (!t.timed && pending.length) {
    out += "## Bookmarks\n\n";
    for (const b of pending) out += bookmarkBlock(b, null);
  }
  return finish(out);
}

/** Saves Markdown as a file (the browser's download). */
export function downloadMarkdown(fileName: string, markdown: string) {
  const url = URL.createObjectURL(new Blob([markdown], { type: "text/markdown;charset=utf-8" }));
  const a = document.createElement("a");
  a.href = url;
  a.download = fileName;
  document.body.appendChild(a);
  a.click();
  a.remove();
  window.setTimeout(() => URL.revokeObjectURL(url), 10_000);
}
