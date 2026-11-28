// Earshot: AI from transcripts (Supabase Edge Function "ai"), phase 11.
//
// POST { episodeId, kind, title?, podcast? } from a signed-in user (phone or web), where kind is
//   "chapters": chapters for an episode that has none (shown as generated)
//   "summary":  a short summary plus key moments with timestamps
//   "ads":      ad reads and sponsor segments, so players can skip them
//   "ask":      (phase 13) { question }: an answer from the transcript with quotes and their times; not saved
//   "title":    (phase 13) { startMs, endMs }: a short title for a bookmark's passage; not saved
// Reads the user's saved transcript (it must have timings), asks Gemini, checks the answer, saves it in
// public.episode_ai (006_ai.sql) as the user, and returns { kind, data, model }.
//
// Secrets: GEMINI_API_KEY (already set). Optional GEMINI_MODEL (default below) and
// GEMINI_FALLBACK_MODEL (used when the main model is overloaded or not offered).
// Verify JWT: on (the default). The function also checks the sign-in itself.
// Errors: 503 { paused: true } switched off in app_config (phase 16), 400 no usable transcript, 401 not signed in, 429 Gemini's limit reached (try later), 502 Gemini failed.

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

/**
 * Asks Gemini for JSON matching [schema]. Gemini answers 503 ("high demand") or 500 at busy times, or
 * hangs: go to the fallback model (GEMINI_FALLBACK_MODEL secret) if one is set. The
 * fallback is also used when the main model's per-minute free limit is reached (429), since each model
 * has its own limit. Edge functions are stopped after about 150 s, so only try again while there's time.
 * Returns the OK response and the model that gave it, or a ready error response.
 */
async function gemini(key: string, prompt: string, schema: unknown, temperature: number, light = false): Promise<{ res: Response; model: string; error?: undefined } | { error: Response; res?: undefined; model?: undefined }> {
  let model = Deno.env.get("GEMINI_MODEL") || DEFAULT_MODEL;
  // Small jobs (bookmark titles) go to the lighter fallback model first: it answers in seconds,
  // while the main model is often overloaded and its retry can take over a minute.
  const lightModel = Deno.env.get("GEMINI_FALLBACK_MODEL");
  if (light && lightModel) model = lightModel;
  const request = JSON.stringify({
    contents: [{ role: "user", parts: [{ text: prompt }] }],
    generationConfig: { responseMimeType: "application/json", responseSchema: schema, temperature },
  });
  // Phase 14: each try has its own time limit, so a main model that hangs (it once took 95 s and then
  // answered 503, leaving no time for the fallback) can't use up the function's ~150 s. A hung try
  // counts as a 503.
  const ask = async (m: string, limitMs: number): Promise<Response> => {
    const ctl = new AbortController();
    const timer = setTimeout(() => ctl.abort(), Math.max(5_000, limitMs));
    try {
      return await fetch(`${GEMINI}/${m}:generateContent`, {
        method: "POST",
        headers: { "Content-Type": "application/json", "x-goog-api-key": key },
        body: request,
        signal: ctl.signal,
      });
    } catch (e) {
      console.error(`Gemini ${m}: no answer within ${Math.round(limitMs / 1000)} s (${(e as Error).message})`);
      return new Response(JSON.stringify({ error: { code: 503, message: "No answer in time" } }), { status: 503 });
    } finally {
      clearTimeout(timer);
    }
  };
  const started = Date.now();
  const elapsed = () => Date.now() - started;
  const fallback = light && lightModel ? (Deno.env.get("GEMINI_MODEL") || DEFAULT_MODEL) : Deno.env.get("GEMINI_FALLBACK_MODEL");
  const busy = (r: Response) => r.status === 503 || r.status === 500;
  // With a fallback, the first model gets 70 s; without one, it may use most of the time.
  let res = await ask(model, fallback && fallback !== model ? 70_000 : 120_000);
  // Same model again only when it said "busy" quickly and there's no fallback to go to instead.
  if (busy(res) && (!fallback || fallback === model) && elapsed() < 60_000) {
    console.error(`Gemini ${model} HTTP ${res.status}, trying again: ${(await res.text()).slice(0, 300)}`);
    await new Promise((r) => setTimeout(r, 5_000));
    res = await ask(model, 130_000 - elapsed());
  }
  if ((busy(res) || res.status === 404 || res.status === 429) && fallback && fallback !== model && elapsed() < 125_000) {
    console.error(`Gemini ${model} HTTP ${res.status}, using ${fallback}: ${(await res.text()).slice(0, 300)}`);
    model = fallback;
    res = await ask(model, 140_000 - elapsed());
  }
  if (!res.ok) {
    const detail = (await res.text()).slice(0, 800);
    // Shows in Supabase > Edge Functions > ai > Logs.
    console.error(`Gemini ${model} HTTP ${res.status}: ${detail}`);
    if (res.status === 429) return { error: json({ error: "Gemini's free limit is used up for now. Try again later (limits reset over the day).", detail }, 429) };
    let reason = "";
    try {
      reason = String(JSON.parse(detail)?.error?.message ?? "").slice(0, 200);
    } catch (_) {
      // not JSON
    }
    if (res.status === 503 || res.status === 500) {
      return { error: json({ error: "Gemini is overloaded right now (Google's side). Try again in a few minutes.", detail }, 503) };
    }
    if (res.status === 404) {
      return { error: json({ error: `Gemini doesn't offer the model "${model}" to this key. Set the GEMINI_MODEL secret to one it does.`, detail }, 502) };
    }
    return { error: json({ error: `Gemini returned HTTP ${res.status}${reason ? `: ${reason}` : ""}`, detail }, 502) };
  }
  return { res, model };
}

/** Gemini's JSON answer, or null if it isn't readable. */
// deno-lint-ignore no-explicit-any
async function answerOf(res: Response, model: string): Promise<any | null> {
  // deno-lint-ignore no-explicit-any
  const out: any = await res.json();
  // deno-lint-ignore no-explicit-any
  const text = (out?.candidates?.[0]?.content?.parts ?? []).map((p: any) => p?.text ?? "").join("");
  try {
    return JSON.parse(text);
  } catch (_) {
    console.error(`Gemini ${model}: unreadable answer (finishReason ${out?.candidates?.[0]?.finishReason}): ${JSON.stringify(out).slice(0, 800)}`);
    return null;
  }
}

/**
 * Phase 16: the kill switch (public.app_config, 010_safe_updates.sql). Returns the "paused" message when
 * Serena has switched [flag] off, else null. A failed or missing read never pauses anything.
 */
async function pausedMessage(base: string, apikey: string, flag: string): Promise<string | null> {
  try {
    const res = await fetch(`${base}/rest/v1/app_config?id=eq.1&select=*`, { headers: { apikey }, signal: AbortSignal.timeout(5_000) });
    if (!res.ok) {
      await res.body?.cancel();
      return null;
    }
    const rows = await res.json() as Record<string, unknown>[];
    const c = rows?.[0];
    if (c && c[flag] === false) return String(c.paused_message || "Paused for now.");
  } catch (_) {
    // offline or table missing: carry on
  }
  return null;
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

  let body: { episodeId?: string; kind?: string; title?: string; podcast?: string; question?: string; startMs?: number; endMs?: number };
  try {
    body = await req.json();
  } catch (_) {
    return json({ error: "Expected JSON" }, 400);
  }
  const episodeId = String(body.episodeId ?? "");
  const kind = body.kind as Kind | "ask" | "title";
  if (!episodeId || !(kind in PROMPTS || kind === "ask" || kind === "title")) {
    return json({ error: "episodeId and kind (chapters, summary, ads, ask or title) are required" }, 400);
  }

  const paused = await pausedMessage(base, apikey, kind === "ads" ? "ad_finding_on" : "ai_on");
  if (paused) return json({ error: paused, paused: true }, 503);

  const key = Deno.env.get("GEMINI_API_KEY");
  if (!key) return json({ error: "GEMINI_API_KEY secret is not set" }, 500);

  // The user's own transcript (row-level security keeps it to theirs).
  const tr = await fetch(`${base}/rest/v1/transcripts?select=segments,timed&episode_id=eq.${encodeURIComponent(episodeId)}`, { headers: asUser });
  if (!tr.ok) return json({ error: `Couldn't read the transcript (HTTP ${tr.status})` }, 502);
  const rows = await tr.json() as { segments: Segment[]; timed: boolean }[];
  const t = rows[0];
  if (!t || !Array.isArray(t.segments) || t.segments.length === 0) return json({ error: "This episode has no transcript yet. Get one first (Transcript)." }, 400);
  if (!t.timed) return json({ error: "This episode's transcript has no timings, so moments can't be placed. Transcribe it instead." }, 400);
  const lastMs = Math.max(...t.segments.map((s) => s.e || s.s || 0));
  const context = [body.podcast ? `Podcast: ${body.podcast}` : "", body.title ? `Episode: ${body.title}` : ""].filter(Boolean).join("\n");

  // Phase 13: a typed question, answered from the transcript with quotes and their times. Not saved.
  if (kind === "ask") {
    const question = String(body.question ?? "").trim().slice(0, 500);
    if (!question) return json({ error: "Type a question first" }, 400);
    const prompt =
      "Answer the listener's question about this podcast episode using only what is said in the transcript below. " +
      "Answer in plain language in 1 to 4 short paragraphs (no headings, no bullet characters, no Markdown). If the episode " +
      "doesn't cover it, say so plainly in one sentence instead of guessing. Then give up to 4 short quotes from the " +
      "transcript that support the answer, each with the timestamp of the transcript line where it is said, copied exactly.\n" +
      `Timestamps are h:mm:ss or m:ss, exactly as written at the start of the transcript lines.\n\n${context}\n\nQuestion: ${question}\n\nTranscript:\n${transcriptText(t.segments)}`;
    const schema = {
      type: "OBJECT",
      properties: {
        answer: { type: "STRING" },
        quotes: { type: "ARRAY", items: { type: "OBJECT", properties: { at: { type: "STRING" }, text: { type: "STRING" } }, required: ["at", "text"] } },
      },
      required: ["answer", "quotes"],
    };
    const g = await gemini(key, prompt, schema, 0.2);
    if (g.error) return g.error;
    const o = await answerOf(g.res, g.model);
    if (!o) return json({ error: "Gemini's answer wasn't readable. Try again." }, 502);
    // deno-lint-ignore no-explicit-any
    const quotes = (Array.isArray(o?.quotes) ? o.quotes : []).map((q: any) => ({ at: parseClock(q?.at), text: String(q?.text ?? "").trim().slice(0, 400) }))
      .filter((q: { at: number | null; text: string }) => q.at != null && q.at <= lastMs + 60_000 && q.text);
    console.log(`Gemini ${g.model}: ask for ${episodeId} OK`);
    return json({ kind, data: { answer: String(o?.answer ?? "").trim(), quotes }, model: g.model });
  }

  // Phase 13: a short title for a bookmark, from the transcript around it. Not saved here (the bookmark is).
  if (kind === "title") {
    const from = Math.max(0, Number(body.startMs ?? 0) - 5_000);
    const to = Number(body.endMs ?? 0) + 5_000;
    const passage = t.segments.filter((s) => (s.e || s.s) >= from && s.s <= to).map((s) => s.t.replace(/\s+/g, " ").trim()).join(" ").slice(0, 4_000);
    if (!passage) return json({ error: "No transcript text at this bookmark" }, 400);
    const prompt =
      "Write a short, specific title (3 to 8 words) for this moment of a podcast, as a note to find it again later. " +
      "Name the topic or claim, not the speaker's manner. No quotation marks, no final full stop.\n\n" +
      `${context}\n\nPassage:\n${passage}`;
    const g = await gemini(key, prompt, { type: "OBJECT", properties: { title: { type: "STRING" } }, required: ["title"] }, 0.3, true);
    if (g.error) return g.error;
    const o = await answerOf(g.res, g.model);
    const title = String(o?.title ?? "").replace(/^["'“”]+|["'“”.]+$/g, "").trim().slice(0, 80);
    if (!title) return json({ error: "Gemini didn't give a title. Try again." }, 502);
    console.log(`Gemini ${g.model}: title for ${episodeId} OK`);
    return json({ kind, data: { title }, model: g.model });
  }

  const prompt = `${PROMPTS[kind]}\nTimestamps are h:mm:ss or m:ss, exactly as written at the start of the transcript lines.\n\n${context}\n\nTranscript:\n${transcriptText(t.segments)}`;

  const g = await gemini(key, prompt, SCHEMAS[kind], 0.2);
  if (g.error) return g.error;
  const model = g.model;
  const parsed = await answerOf(g.res, model);
  if (parsed == null) return json({ error: "Gemini's answer wasn't readable. Try again." }, 502);
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
