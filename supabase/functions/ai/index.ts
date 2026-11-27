// Earshot: AI from transcripts (Supabase Edge Function "ai"), phase 11.
//
// POST { episodeId, kind, title?, podcast? } from a signed-in user (phone or web), where kind is
//   "chapters": chapters for an episode that has none (shown as generated)
//   "summary":  a short summary plus key moments with timestamps
//   "ads":      ad reads and sponsor segments, so players can skip them
// Reads the user's saved transcript (it must have timings), asks Gemini, checks the answer, saves it in
// public.episode_ai (006_ai.sql) as the user, and returns { kind, data, model }.
//
// Secrets: GEMINI_API_KEY (already set). Optional GEMINI_MODEL (default below) and
// GEMINI_FALLBACK_MODEL (used when the main model is overloaded or not offered).
// Verify JWT: on (the default). The function also checks the sign-in itself.
// Errors: 400 no usable transcript, 401 not signed in, 429 Gemini's limit reached (try later), 502 Gemini failed.

const GEMINI = "https://generativelanguage.googleapis.com/v1beta/models";
const DEFAULT_MODEL = "gemini-3.8-flash";
/** Very long transcripts are cut to keep within free-tier token limits (about 4 hours of speech). */
const MAX_CHARS = 240_000;

const CORS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "apikey, authorization, content-type, x-client-info",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { ...CORS, "Content-Type": "application/json; charset=utf-8" } });
}

type Segment = { s: number; e: number; t: string; sp?: string | null };
type Kind = "chapters" | "summary" | "ads";

function clock(ms: number): string {
  const s = Math.floor(ms / 1000);
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  const sec = s % 60;
  return h > 0 ? `${h}:${String(m).padStart(2, "0")}:${String(sec).padStart(2, "0")}` : `${m}:${String(sec).padStart(2, "0")}`;
}

/** "h:mm:ss" / "mm:ss" / seconds -> ms. */
function parseClock(v: unknown): number | null {
  if (typeof v === "number" && isFinite(v)) return Math.max(0, Math.round(v * 1000));
  if (typeof v !== "string") return null;
  const parts = v.trim().split(":").map(Number);
  if (!parts.length || parts.some((n) => !isFinite(n))) return null;
  return Math.round(parts.reduce((a, n) => a * 60 + n, 0) * 1000);
}

function transcriptText(segments: Segment[]): string {
  let out = "";
  for (const seg of segments) {
    const line = `[${clock(seg.s)}] ${seg.sp ? seg.sp + ": " : ""}${seg.t.replace(/\s+/g, " ").trim()}\n`;
    if (out.length + line.length > MAX_CHARS) break;
    out += line;
  }
  return out;
}

const PROMPTS: Record<Kind, string> = {
  chapters:
    "Split this podcast episode into chapters, as a listener would want to navigate it. Usually 5 to 15 chapters, " +
    "each at least 2 minutes long, the first starting at 0:00. Titles: short (under 60 characters), specific, no numbering, " +
    "no quotation marks. Mark ad breaks or sponsor reads as their own chapter titled \"Ad break\" (or \"Sponsor: <name>\"). " +
    "Use the timestamps from the transcript lines (the moment the topic starts).",
  summary:
    "Summarise this podcast episode for someone deciding what to listen to or remembering what they heard. " +
    "Write 2 to 4 short paragraphs in plain language (no headings, no bullet characters, no Markdown). Then list 3 to 8 " +
    "key moments, each with the transcript timestamp where it starts and one sentence describing it. Leave out ads.",
  ads:
    "Find every advertisement in this podcast episode: sponsor reads (including host-read ads), ad breaks, promos for other " +
    "podcasts or the network, and calls to support the show or buy merchandise that interrupt the content. For each give " +
    "the start timestamp (where the ad begins) and the end timestamp (where the episode's own content resumes), and a short " +
    "label such as the sponsor's name. Use the transcript timestamps. If there are none, return an empty list. " +
    "Don't include the show's normal introduction or credits.",
};

// Gemini structured output (OpenAPI-style schema).
const SCHEMAS: Record<Kind, unknown> = {
  chapters: {
    type: "OBJECT",
    properties: {
      chapters: { type: "ARRAY", items: { type: "OBJECT", properties: { start: { type: "STRING" }, title: { type: "STRING" } }, required: ["start", "title"] } },
    },
    required: ["chapters"],
  },
  summary: {
    type: "OBJECT",
    properties: {
      summary: { type: "STRING" },
      points: { type: "ARRAY", items: { type: "OBJECT", properties: { at: { type: "STRING" }, text: { type: "STRING" } }, required: ["at", "text"] } },
    },
    required: ["summary", "points"],
  },
  ads: {
    type: "OBJECT",
    properties: {
      ads: { type: "ARRAY", items: { type: "OBJECT", properties: { start: { type: "STRING" }, end: { type: "STRING" }, label: { type: "STRING" } }, required: ["start", "end", "label"] } },
    },
    required: ["ads"],
  },
};

/** Checks and normalises Gemini's answer into the stored shape (times in ms, sorted, within the episode). */
// deno-lint-ignore no-explicit-any
function normalise(kind: Kind, o: any, lastMs: number): unknown {
  const inRange = (ms: number | null): ms is number => ms != null && ms >= 0 && ms <= lastMs + 60_000;
  if (kind === "chapters") {
    // deno-lint-ignore no-explicit-any
    const list = (Array.isArray(o?.chapters) ? o.chapters : []).map((c: any) => ({ start: parseClock(c?.start), title: String(c?.title ?? "").trim().slice(0, 100) }))
      .filter((c: { start: number | null; title: string }) => inRange(c.start) && c.title)
      .sort((a: { start: number }, b: { start: number }) => a.start - b.start);
    // drop chapters starting within 20 s of the previous one
    const chapters = list.filter((c: { start: number }, i: number) => i === 0 || c.start - list[i - 1].start >= 20_000);
    if (chapters.length) chapters[0].start = 0;
    return { chapters };
  }
  if (kind === "summary") {
    // deno-lint-ignore no-explicit-any
    const points = (Array.isArray(o?.points) ? o.points : []).map((p: any) => ({ at: parseClock(p?.at), text: String(p?.text ?? "").trim() }))
      .filter((p: { at: number | null; text: string }) => inRange(p.at) && p.text)
      .sort((a: { at: number }, b: { at: number }) => a.at - b.at);
    return { summary: String(o?.summary ?? "").trim(), points };
  }
  // deno-lint-ignore no-explicit-any
  const ads = (Array.isArray(o?.ads) ? o.ads : []).map((a: any) => ({ start: parseClock(a?.start), end: parseClock(a?.end), label: String(a?.label ?? "Ad").trim().slice(0, 80) || "Ad" }))
    .filter((a: { start: number | null; end: number | null }) => inRange(a.start) && inRange(a.end) && a.end! > a.start! && a.end! - a.start! <= 10 * 60_000)
    .sort((a: { start: number }, b: { start: number }) => a.start - b.start);
  return { ads };
}

Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: CORS });
  if (req.method !== "POST") return json({ error: "Use POST" }, 405);

  const base = Deno.env.get("SUPABASE_URL");
  const auth = req.headers.get("authorization") ?? "";
  const apikey = req.headers.get("apikey") ?? "";
  if (!base || !auth.toLowerCase().startsWith("bearer ") || !apikey) return json({ error: "Sign in first" }, 401);
  const asUser = { Authorization: auth, apikey };
  const who = await fetch(`${base}/auth/v1/user`, { headers: asUser });
  if (!who.ok) return json({ error: "Sign in first" }, 401);
  await who.body?.cancel();

  let body: { episodeId?: string; kind?: string; title?: string; podcast?: string };
  try {
    body = await req.json();
  } catch (_) {
    return json({ error: "Expected JSON" }, 400);
  }
  const episodeId = String(body.episodeId ?? "");
  const kind = body.kind as Kind;
  if (!episodeId || !(kind in PROMPTS)) return json({ error: "episodeId and kind (chapters, summary or ads) are required" }, 400);

  const key = Deno.env.get("GEMINI_API_KEY");
  if (!key) return json({ error: "GEMINI_API_KEY secret is not set" }, 500);
  let model = Deno.env.get("GEMINI_MODEL") || DEFAULT_MODEL;

  // The user's own transcript (row-level security keeps it to theirs).
  const tr = await fetch(`${base}/rest/v1/transcripts?select=segments,timed&episode_id=eq.${encodeURIComponent(episodeId)}`, { headers: asUser });
  if (!tr.ok) return json({ error: `Couldn't read the transcript (HTTP ${tr.status})` }, 502);
  const rows = await tr.json() as { segments: Segment[]; timed: boolean }[];
  const t = rows[0];
  if (!t || !Array.isArray(t.segments) || t.segments.length === 0) return json({ error: "This episode has no transcript yet. Get one first (Transcript)." }, 400);
  if (!t.timed) return json({ error: "This episode's transcript has no timings, so moments can't be placed. Transcribe it instead." }, 400);
  const lastMs = Math.max(...t.segments.map((s) => s.e || s.s || 0));

  const context = [body.podcast ? `Podcast: ${body.podcast}` : "", body.title ? `Episode: ${body.title}` : ""].filter(Boolean).join("\n");
  const prompt = `${PROMPTS[kind]}\nTimestamps are h:mm:ss or m:ss, exactly as written at the start of the transcript lines.\n\n${context}\n\nTranscript:\n${transcriptText(t.segments)}`;

  const request = JSON.stringify({
    contents: [{ role: "user", parts: [{ text: prompt }] }],
    generationConfig: { responseMimeType: "application/json", responseSchema: SCHEMAS[kind], temperature: 0.2 },
  });
  const ask = (m: string) =>
    fetch(`${GEMINI}/${m}:generateContent`, {
      method: "POST",
      headers: { "Content-Type": "application/json", "x-goog-api-key": key },
      body: request,
    });
  // Gemini answers 503 ("high demand") or 500 at busy times: try again once after a pause, then the
  // fallback model (GEMINI_FALLBACK_MODEL secret) if one is set.
  // Edge functions are stopped after about 150 s, so only try again while there's time left.
  const started = Date.now();
  const timeLeft = () => Date.now() - started < 60_000;
  let res = await ask(model);
  if ((res.status === 503 || res.status === 500) && timeLeft()) {
    console.error(`Gemini ${model} HTTP ${res.status}, trying again: ${(await res.text()).slice(0, 300)}`);
    await new Promise((r) => setTimeout(r, 5_000));
    res = await ask(model);
  }
  const fallback = Deno.env.get("GEMINI_FALLBACK_MODEL");
  if ((res.status === 503 || res.status === 500 || res.status === 404) && fallback && fallback !== model && timeLeft()) {
    console.error(`Gemini ${model} HTTP ${res.status}, using ${fallback}: ${(await res.text()).slice(0, 300)}`);
    model = fallback;
    res = await ask(model);
  }
  if (!res.ok) {
    const detail = (await res.text()).slice(0, 800);
    // Shows in Supabase > Edge Functions > ai > Logs.
    console.error(`Gemini ${model} HTTP ${res.status}: ${detail}`);
    if (res.status === 429) return json({ error: "Gemini's free limit is used up for now. Try again later (limits reset over the day).", detail }, 429);
    let reason = "";
    try {
      reason = String(JSON.parse(detail)?.error?.message ?? "").slice(0, 200);
    } catch (_) {
      // not JSON
    }
    if (res.status === 503 || res.status === 500) {
      return json({ error: "Gemini is overloaded right now (Google's side). Try again in a few minutes.", detail }, 503);
    }
    if (res.status === 404) {
      return json({ error: `Gemini doesn't offer the model "${model}" to this key. Set the GEMINI_MODEL secret to one it does.`, detail }, 502);
    }
    return json({ error: `Gemini returned HTTP ${res.status}${reason ? `: ${reason}` : ""}`, detail }, 502);
  }
  // deno-lint-ignore no-explicit-any
  const out: any = await res.json();
  // deno-lint-ignore no-explicit-any
  const text = (out?.candidates?.[0]?.content?.parts ?? []).map((p: any) => p?.text ?? "").join("");
  let parsed: unknown;
  try {
    parsed = JSON.parse(text);
  } catch (_) {
    console.error(`Gemini ${model}: unreadable answer (finishReason ${out?.candidates?.[0]?.finishReason}): ${JSON.stringify(out).slice(0, 800)}`);
    return json({ error: "Gemini's answer wasn't readable. Try again.", detail: text.slice(0, 300) }, 502);
  }
  const data = normalise(kind, parsed, lastMs);
  console.log(`Gemini ${model}: ${kind} for ${episodeId} OK`);
  if (kind === "chapters" && (data as { chapters: unknown[] }).chapters.length < 2) return json({ error: "Gemini couldn't find chapters in this episode." }, 502);

  const now = Date.now();
  const save = await fetch(`${base}/rest/v1/episode_ai?on_conflict=user_id,episode_id,kind`, {
    method: "POST",
    headers: { ...asUser, "Content-Type": "application/json", Prefer: "resolution=merge-duplicates,return=minimal" },
    body: JSON.stringify({ episode_id: episodeId, kind, data, model, created_at: now, changed_at: now }),
  });
  if (!save.ok) {
    const detail = (await save.text()).slice(0, 300);
    // Still hand back the result; it just won't sync until 006_ai.sql is run.
    return json({ kind, data, model, saved: false, detail });
  }
  return json({ kind, data, model, saved: true });
});
