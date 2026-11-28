// Earshot: transcribe an episode with Deepgram Nova-3 (Supabase Edge Function "transcribe").
//
// Deepgram fetches the episode itself from its URL (any length or format up to 2 GB) and returns
// every word with its time and speaker, usually in well under a minute for an hour of audio. This
// function groups the words into short segments with times from the start of the episode. The app
// saves the transcript (table public.transcripts), so this runs once per episode, only when asked.
//
// Only a signed-in user of this project may call it (same check as feed-proxy).
// Secret: DEEPGRAM_API_KEY (Edge Functions > Secrets), a key from console.deepgram.com.
//
// Call: POST https://<project-ref>.supabase.co/functions/v1/transcribe
//   body: { "audioUrl": "...", "mimeType": "audio/mpeg", "durationMs": 3600000, "part": 0 }
// Returns: { "part": 0, "parts": 1, "startMs": 0, "endMs": 3600000, "done": true,
//            "segments": [ { "s": 1200, "e": 5400, "t": "Hello and welcome", "sp": "spk_1" }, ... ] }
//   Always one part (the reply shape is kept from the earlier part-by-part version, so older app
//   builds still work). A request for part 1 or later answers done with no segments.
// Errors: { "error": "message" } with HTTP 4xx/5xx (429 and 5xx from Deepgram are passed on, so
//   the app retries).

const CORS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "apikey, authorization, content-type, x-client-info",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};

const LISTEN =
  "https://api.deepgram.com/v1/listen?model=nova-3&language=en&smart_format=true&diarize_model=latest";

export interface Segment {
  s: number;
  e: number;
  t: string;
  sp: string | null;
}

export interface Word {
  t: string;
  s: number;
  e: number;
  sp: string | null;
}

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { ...CORS, "Content-Type": "application/json" } });
}

/** True when the bearer token belongs to a signed-in user of this project. */
async function signedIn(req: Request): Promise<boolean> {
  const auth = req.headers.get("authorization") ?? "";
  const apikey = req.headers.get("apikey") ?? "";
  const base = Deno.env.get("SUPABASE_URL");
  if (!auth.toLowerCase().startsWith("bearer ") || !apikey || !base) return false;
  const res = await fetch(`${base}/auth/v1/user`, { headers: { Authorization: auth, apikey } });
  await res.body?.cancel();
  return res.ok;
}

/** Reads the words out of a Deepgram response. Speakers 0, 1, … become "spk_1", "spk_2", … */
// deno-lint-ignore no-explicit-any
export function wordsOf(res: any): { words: Word[]; text: string } {
  const alt = res?.results?.channels?.[0]?.alternatives?.[0];
  const words: Word[] = [];
  for (const w of alt?.words ?? []) {
    const t = typeof w?.punctuated_word === "string" ? w.punctuated_word : typeof w?.word === "string" ? w.word : "";
    if (!t) continue;
    words.push({
      t,
      s: Math.round((Number(w.start) || 0) * 1000),
      e: Math.round((Number(w.end) || 0) * 1000),
      sp: Number.isInteger(w.speaker) ? `spk_${w.speaker + 1}` : null,
    });
  }
  return { words, text: typeof alt?.transcript === "string" ? alt.transcript : "" };
}

/**
 * Groups words into readable segments: a new one when the speaker changes, after a pause of 2 s,
 * at the end of a sentence once the segment is 6 s long, and always by 25 s.
 */
export function wordsToSegments(words: Word[]): Segment[] {
  const out: Segment[] = [];
  let cur: { s: number; e: number; parts: string[]; sp: string | null } | null = null;
  for (const w of words) {
    const text = w.t.trim();
    if (!text) continue;
    if (cur) {
      const len = cur.e - cur.s;
      const lastWord = cur.parts[cur.parts.length - 1];
      const sentenceEnd = /[.?!]["')\]]?$/.test(lastWord);
      if (w.sp !== cur.sp || w.s - cur.e >= 2000 || (sentenceEnd && len >= 6000) || len >= 25000) {
        out.push({ s: cur.s, e: cur.e, t: cur.parts.join(" "), sp: cur.sp });
        cur = null;
      }
    }
    if (!cur) cur = { s: w.s, e: w.e, parts: [], sp: w.sp };
    cur.parts.push(text);
    cur.e = Math.max(cur.e, w.e);
  }
  if (cur) out.push({ s: cur.s, e: cur.e, t: cur.parts.join(" "), sp: cur.sp });
  return out;
}

/** A Deepgram error as an Error carrying an HTTP status the app understands. */
async function deepgramError(res: Response): Promise<Error> {
  let msg = `HTTP ${res.status}`;
  try {
    const b = await res.json();
    msg = b?.err_msg ?? b?.message ?? b?.error ?? msg;
  } catch (_) {
    // Keep the status.
  }
  const status =
    res.status === 401 || res.status === 403 ? 500 // our key's problem, not the caller's
    : res.status === 402 ? 402
    : res.status === 400 || res.status === 422 ? 422
    : res.status === 429 ? 429
    : 502;
  const hint = res.status === 401 ? " (check DEEPGRAM_API_KEY)" : res.status === 402 ? " (Deepgram credit used up)" : "";
  return Object.assign(new Error(`Deepgram: ${msg}${hint}`), { status });
}

async function handle(req: Request): Promise<Response> {
  const key = Deno.env.get("DEEPGRAM_API_KEY");
  if (!key) return json({ error: "DEEPGRAM_API_KEY isn't set in Supabase (Edge Functions > Secrets)" }, 500);

  // deno-lint-ignore no-explicit-any
  let body: any;
  try {
    body = await req.json();
  } catch (_) {
    return json({ error: "Send JSON" }, 400);
  }
  const part = Number.isInteger(body?.part) && body.part >= 0 ? body.part : 0;
  const durationMs = typeof body?.durationMs === "number" && body.durationMs > 0 ? body.durationMs : 0;
  let url: URL;
  try {
    url = new URL(String(body?.audioUrl ?? ""));
  } catch (_) {
    return json({ error: "Missing or invalid audioUrl" }, 400);
  }
  if (url.protocol !== "https:" && url.protocol !== "http:") return json({ error: "Only http and https audio" }, 400);
  if (part > 0) return json({ part, parts: 1, startMs: durationMs, endMs: durationMs, done: true, segments: [] });

  const res = await fetch(LISTEN, {
    method: "POST",
    headers: { Authorization: `Token ${key}`, "Content-Type": "application/json" },
    body: JSON.stringify({ url: url.toString() }),
  });
  if (!res.ok) throw await deepgramError(res);
  const out = await res.json();
  const { words, text } = wordsOf(out);
  const endMs = Math.round((Number(out?.metadata?.duration) || 0) * 1000) || durationMs;
  let segments = wordsToSegments(words);
  if (segments.length === 0 && text.trim()) segments = [{ s: 0, e: endMs, t: text.trim(), sp: null }];
  return json({ part: 0, parts: 1, startMs: 0, endMs, done: true, segments });
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

if (typeof Deno !== "undefined") {
  Deno.serve(async (req: Request) => {
    if (req.method === "OPTIONS") return new Response("ok", { headers: CORS });
    if (req.method !== "POST") return json({ error: "Use POST" }, 405);
    if (!(await signedIn(req))) return json({ error: "Sign in first" }, 401);
    const paused = await pausedMessage(Deno.env.get("SUPABASE_URL") ?? "", req.headers.get("apikey") ?? "", "transcribe_on");
    if (paused) return json({ error: paused, paused: true }, 503);
    try {
      return await handle(req);
    } catch (e) {
      const status = (e as { status?: number }).status ?? 502;
      return json({ error: e instanceof Error ? e.message : String(e) }, status);
    }
  });
}
