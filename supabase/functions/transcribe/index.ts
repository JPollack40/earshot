// Earshot: transcribe one part of an episode with Gemini 3.5 Transcribe (Supabase Edge Function "transcribe").
//
// Gemini 3.5 Transcribe takes at most 30 minutes of audio per request when word timings are on, and an
// Edge Function has a short time limit, so the app transcribes an episode in 15-minute parts: it calls
// this function with part = 0, 1, 2, ... until the answer says done. Each call:
//   1. downloads the episode (MP3) only as far as the end of that part, reading the MP3 frames to
//      cut it exactly at the part's start and end times;
//   2. uploads that piece to the Gemini Files API, asks Gemini 3.5 Transcribe for a verbatim
//      transcript with word timings and speaker labels, then deletes the uploaded piece;
//   3. returns the words grouped into short segments, with times from the start of the episode.
// The app joins the parts and saves the transcript (table public.transcripts), so this runs once per episode.
//
// Only a signed-in user of this project may call it (same check as feed-proxy).
// Secret: GEMINI_API_KEY (Edge Functions > Secrets), a key from Google AI Studio.
//
// Call: POST https://<project-ref>.supabase.co/functions/v1/transcribe
//   body: { "audioUrl": "...", "mimeType": "audio/mpeg", "durationMs": 3600000, "part": 0 }
// Returns: { "part": 0, "parts": 4, "startMs": 0, "endMs": 900000, "done": false,
//            "segments": [ { "s": 1200, "e": 5400, "t": "Hello and welcome", "sp": "spk_1" }, ... ] }
//   parts is an estimate from durationMs (0 if unknown); done is true on the last part.
// Errors: { "error": "message" } with HTTP 4xx/5xx.

const CORS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "apikey, authorization, content-type, x-client-info",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};

const GEMINI = "https://generativelanguage.googleapis.com";
const MODEL = "gemini-3.5-transcribe";
export const PART_MS = 15 * 60 * 1000;
/** Non-MP3 files can't be cut into parts, so they're sent whole if they're short enough. */
const MAX_WHOLE_MS = 29 * 60 * 1000;
const MAX_WHOLE_BYTES = 90 * 1024 * 1024;
/** Give up looking for MP3 frames after this many bytes past the ID3 tag. */
const MAX_SYNC_SEARCH = 512 * 1024;

export interface Segment {
  s: number;
  e: number;
  t: string;
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

// ------------------------------------------------------------------ MP3 frames

const BITRATES: Record<string, number[]> = {
  "1-1": [0, 32, 64, 96, 128, 160, 192, 224, 256, 288, 320, 352, 384, 416, 448],
  "1-2": [0, 32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384],
  "1-3": [0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320],
  "2-1": [0, 32, 48, 56, 64, 80, 96, 112, 128, 144, 160, 176, 192, 224, 256],
  "2-2": [0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160],
  "2-3": [0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160],
};
const SAMPLE_RATES: Record<number, number[]> = {
  3: [44100, 48000, 32000], // MPEG 1
  2: [22050, 24000, 16000], // MPEG 2
  0: [11025, 12000, 8000], // MPEG 2.5
};

export interface FrameHeader {
  size: number;
  samples: number;
  sampleRate: number;
}

/** Reads an MPEG audio frame header at b[i..i+3], or null if there isn't a valid one. */
export function frameHeader(b: Uint8Array, i: number): FrameHeader | null {
  if (b[i] !== 0xff || (b[i + 1] & 0xe0) !== 0xe0) return null;
  const version = (b[i + 1] >> 3) & 3; // 3 = MPEG1, 2 = MPEG2, 0 = MPEG2.5, 1 = reserved
  const layerBits = (b[i + 1] >> 1) & 3; // 3 = Layer I, 2 = Layer II, 1 = Layer III
  if (version === 1 || layerBits === 0) return null;
  const layer = 4 - layerBits;
  const bitrateIndex = b[i + 2] >> 4;
  const srIndex = (b[i + 2] >> 2) & 3;
  if (bitrateIndex === 0 || bitrateIndex === 15 || srIndex === 3) return null; // free format isn't supported
  const padding = (b[i + 2] >> 1) & 1;
  const kbps = BITRATES[`${version === 3 ? 1 : 2}-${layer}`][bitrateIndex];
  const sampleRate = SAMPLE_RATES[version][srIndex];
  const bps = kbps * 1000;
  let size: number;
  let samples: number;
  if (layer === 1) {
    size = (Math.floor((12 * bps) / sampleRate) + padding) * 4;
    samples = 384;
  } else if (layer === 2 || version === 3) {
    size = Math.floor((144 * bps) / sampleRate) + padding;
    samples = 1152;
  } else {
    size = Math.floor((72 * bps) / sampleRate) + padding;
    samples = 576;
  }
  if (size < 24) return null;
  return { size, samples, sampleRate };
}

/** True when an MP3 frame is a Xing / Info / VBRI header frame (metadata, not audio). */
function isInfoFrame(b: Uint8Array, start: number, size: number): boolean {
  const end = Math.min(start + size, start + 64) - 4;
  for (let i = start + 4; i <= end; i++) {
    const s = String.fromCharCode(b[i], b[i + 1], b[i + 2], b[i + 3]);
    if (s === "Xing" || s === "Info" || s === "VBRI") return true;
  }
  return false;
}

export interface Piece {
  /** MP3 frames from the part's start up to its end. */
  frames: Uint8Array[];
  bytes: number;
  /** Time of the first and after the last frame included, in ms from the start of the episode. */
  startMs: number;
  endMs: number;
  /** True when the file ended before endMs: this is the last part. */
  eof: boolean;
}

/**
 * Reads an MP3 stream frame by frame and keeps the frames between [fromMs] and [toMs], counting
 * samples for exact times (works for constant and variable bit rates). Stops reading at [toMs].
 * Throws NotMp3 if no MPEG audio frames are found.
 */
export async function cutMp3(stream: ReadableStream<Uint8Array>, fromMs: number, toMs: number): Promise<Piece> {
  const reader = stream.getReader();
  let buf: Uint8Array = new Uint8Array(0);
  let off = 0;
  let id3Checked = false;
  let skip = 0; // bytes of an ID3 tag still to skip
  let locked = false; // in step with the frames
  let searched = 0;
  let samples = 0;
  let sampleRate = 0;
  let first = true;
  const frames: Uint8Array[] = [];
  let bytes = 0;
  let startMs = -1;
  let endMs = 0;
  let eof = false;

  const now = () => (sampleRate ? (samples / sampleRate) * 1000 : 0);

  outer: while (true) {
    const { value, done } = await reader.read();
    if (done) {
      eof = true;
      break;
    }
    let chunk = value;
    if (skip > 0) {
      if (chunk.length <= skip) {
        skip -= chunk.length;
        continue;
      }
      chunk = chunk.subarray(skip);
      skip = 0;
    }
    // Keep only what wasn't used yet, plus the new bytes.
    const rest = buf.length - off;
    const next = new Uint8Array(rest + chunk.length);
    next.set(buf.subarray(off), 0);
    next.set(chunk, rest);
    buf = next;
    off = 0;

    if (!id3Checked) {
      if (buf.length < 10) continue;
      id3Checked = true;
      if (buf[0] === 0x49 && buf[1] === 0x44 && buf[2] === 0x33) {
        // ID3v2: 10-byte header, syncsafe size, optional 10-byte footer.
        const size = ((buf[6] & 0x7f) << 21) | ((buf[7] & 0x7f) << 14) | ((buf[8] & 0x7f) << 7) | (buf[9] & 0x7f);
        const total = 10 + size + ((buf[5] & 0x10) ? 10 : 0);
        if (total >= buf.length) {
          skip = total - buf.length;
          buf = new Uint8Array(0);
          continue;
        }
        off = total;
      }
    }

    while (buf.length - off >= 4) {
      const h = frameHeader(buf, off);
      if (h && !locked) {
        // Confirm a new sync with the frame that follows, so random bytes aren't taken for a frame.
        if (buf.length - off < h.size + 4) continue outer;
        if (!frameHeader(buf, off + h.size)) {
          off++;
          searched++;
          if (searched > MAX_SYNC_SEARCH && samples === 0) throw new NotMp3();
          continue;
        }
        locked = true;
      }
      if (!h) {
        locked = false;
        off++;
        searched++;
        if (searched > MAX_SYNC_SEARCH && samples === 0) throw new NotMp3();
        continue;
      }
      if (buf.length - off < h.size) continue outer; // wait for the rest of this frame
      if (first) {
        first = false;
        if (isInfoFrame(buf, off, h.size)) {
          off += h.size;
          continue;
        }
      }
      sampleRate = h.sampleRate;
      const t = now();
      if (t >= toMs) {
        endMs = t;
        break outer;
      }
      if (t >= fromMs) {
        if (startMs < 0) startMs = t;
        frames.push(buf.subarray(off, off + h.size));
        bytes += h.size;
      }
      samples += h.samples;
      off += h.size;
    }
  }
  if (eof) endMs = now();
  try {
    await reader.cancel();
  } catch (_) {
    // already closed
  }
  if (sampleRate === 0) throw new NotMp3();
  return { frames, bytes, startMs: Math.max(0, startMs), endMs, eof };
}

export class NotMp3 extends Error {
  constructor() {
    super("not an MP3 file");
  }
}

// ------------------------------------------------------------------ Gemini

/** "12.340s" → 12340 */
export function offsetMs(v: unknown): number {
  if (typeof v === "number") return Math.round(v * 1000);
  if (typeof v !== "string") return 0;
  const n = parseFloat(v.replace(/s$/, ""));
  return isFinite(n) ? Math.round(n * 1000) : 0;
}

export interface Word {
  t: string;
  s: number;
  e: number;
  sp: string | null;
}

/** Reads the words (with times and speakers) out of a completed Gemini interaction. */
export function wordsOf(interaction: any): { words: Word[]; text: string } {
  const words: Word[] = [];
  const texts: string[] = [];
  for (const step of interaction?.steps ?? []) {
    for (const c of step?.content ?? []) {
      if (c?.type !== "text") continue;
      if (typeof c.text === "string") texts.push(c.text);
      for (const a of c.annotations ?? []) {
        if (a?.type !== "word_info" || typeof a.text !== "string") continue;
        words.push({ t: a.text, s: offsetMs(a.start_offset), e: offsetMs(a.end_offset), sp: a.speaker ?? null });
      }
    }
  }
  const text = texts.join(" ").trim() || (typeof interaction?.output_text === "string" ? interaction.output_text : "");
  return { words, text };
}

/**
 * Groups words into readable segments: a new one when the speaker changes, after a pause of 2 s,
 * at the end of a sentence once the segment is 6 s long, and always by 25 s. Times are shifted by [baseMs].
 */
export function wordsToSegments(words: Word[], baseMs: number): Segment[] {
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
        out.push({ s: cur.s + baseMs, e: cur.e + baseMs, t: cur.parts.join(" "), sp: cur.sp });
        cur = null;
      }
    }
    if (!cur) cur = { s: w.s, e: w.e, parts: [], sp: w.sp };
    cur.parts.push(text);
    cur.e = Math.max(cur.e, w.e);
  }
  if (cur) out.push({ s: cur.s + baseMs, e: cur.e + baseMs, t: cur.parts.join(" "), sp: cur.sp });
  return out;
}

async function geminiError(res: Response, what: string): Promise<Error> {
  const body = await res.text().catch(() => "");
  let msg = body;
  try {
    msg = JSON.parse(body)?.error?.message ?? body;
  } catch (_) {
    // not JSON
  }
  return new Error(`${what}: HTTP ${res.status} ${msg}`.slice(0, 500));
}

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

/** Uploads audio to the Gemini Files API (resumable upload, one request). Returns the file resource. */
async function uploadToGemini(key: string, data: Blob, mime: string): Promise<{ uri: string; name: string }> {
  const start = await fetch(`${GEMINI}/upload/v1beta/files`, {
    method: "POST",
    headers: {
      "x-goog-api-key": key,
      "X-Goog-Upload-Protocol": "resumable",
      "X-Goog-Upload-Command": "start",
      "X-Goog-Upload-Header-Content-Length": String(data.size),
      "X-Goog-Upload-Header-Content-Type": mime,
      "Content-Type": "application/json",
    },
    body: JSON.stringify({ file: { display_name: "earshot-part" } }),
  });
  if (!start.ok) throw await geminiError(start, "Gemini upload (start)");
  await start.body?.cancel();
  const uploadUrl = start.headers.get("x-goog-upload-url");
  if (!uploadUrl) throw new Error("Gemini upload: no upload URL returned");
  const up = await fetch(uploadUrl, {
    method: "POST",
    headers: {
      "Content-Length": String(data.size),
      "X-Goog-Upload-Offset": "0",
      "X-Goog-Upload-Command": "upload, finalize",
    },
    body: data,
  });
  if (!up.ok) throw await geminiError(up, "Gemini upload");
  let file = (await up.json())?.file;
  if (!file?.uri || !file?.name) throw new Error("Gemini upload: no file returned");
  // Audio is usually ready at once; wait briefly if it's still processing.
  for (let i = 0; i < 15 && file.state === "PROCESSING"; i++) {
    await sleep(2000);
    const r = await fetch(`${GEMINI}/v1beta/${file.name}`, { headers: { "x-goog-api-key": key } });
    if (!r.ok) throw await geminiError(r, "Gemini file status");
    file = await r.json();
  }
  if (file.state === "FAILED") throw new Error("Gemini couldn't process the audio");
  return { uri: file.uri, name: file.name };
}

async function deleteGeminiFile(key: string, name: string) {
  try {
    const r = await fetch(`${GEMINI}/v1beta/${name}`, { method: "DELETE", headers: { "x-goog-api-key": key } });
    await r.body?.cancel();
  } catch (_) {
    // Files are deleted by Google after 48 hours anyway.
  }
}

async function transcribeFile(key: string, uri: string, mime: string): Promise<any> {
  const res = await fetch(`${GEMINI}/v1beta/interactions`, {
    method: "POST",
    headers: { "x-goog-api-key": key, "Content-Type": "application/json" },
    body: JSON.stringify({
      model: MODEL,
      input: [{ type: "audio", uri, mime_type: mime }],
      generation_config: {
        transcription_config: {
          mode: { type: "verbatim", diarization_mode: "speaker", timestamp_granularities: ["word"] },
        },
      },
    }),
  });
  if (!res.ok) throw await geminiError(res, "Gemini transcribe");
  let interaction = await res.json();
  // Normally complete at once; if Gemini answers before it's finished, check back.
  for (let i = 0; i < 40 && interaction?.status && interaction.status !== "completed"; i++) {
    if (interaction.status === "failed" || interaction.status === "cancelled") {
      throw new Error(`Gemini transcribe ${interaction.status}`);
    }
    await sleep(2000);
    const r = await fetch(`${GEMINI}/v1beta/${interaction.id}`, { headers: { "x-goog-api-key": key } });
    if (!r.ok) throw await geminiError(r, "Gemini transcribe status");
    interaction = await r.json();
  }
  return interaction;
}

// ------------------------------------------------------------------ request

async function fetchAudio(url: URL, range?: string): Promise<Response> {
  const res = await fetch(url, {
    headers: { "User-Agent": "Earshot/0.1 (personal podcast player)", ...(range ? { Range: range } : {}) },
    redirect: "follow",
  });
  if (!res.ok) {
    await res.body?.cancel();
    throw Object.assign(new Error(`The audio host returned HTTP ${res.status}`), { status: 502 });
  }
  if (!res.body) throw Object.assign(new Error("The audio host sent nothing"), { status: 502 });
  return res;
}

async function handle(req: Request): Promise<Response> {
  const key = Deno.env.get("GEMINI_API_KEY");
  if (!key) return json({ error: "GEMINI_API_KEY isn't set in Supabase (Edge Functions > Secrets)" }, 500);

  let body: any;
  try {
    body = await req.json();
  } catch (_) {
    return json({ error: "Send JSON" }, 400);
  }
  const part = Number.isInteger(body?.part) && body.part >= 0 ? body.part : 0;
  const durationMs = typeof body?.durationMs === "number" && body.durationMs > 0 ? body.durationMs : 0;
  const mimeIn = typeof body?.mimeType === "string" ? body.mimeType.toLowerCase() : "";
  let url: URL;
  try {
    url = new URL(String(body?.audioUrl ?? ""));
  } catch (_) {
    return json({ error: "Missing or invalid audioUrl" }, 400);
  }
  if (url.protocol !== "https:" && url.protocol !== "http:") return json({ error: "Only http and https audio" }, 400);

  const parts = durationMs ? Math.ceil(durationMs / PART_MS) : 0;
  const fromMs = part * PART_MS;
  const toMs = fromMs + PART_MS;
  const looksMp4 = /mp4|m4a|aac|x-m4a/.test(mimeIn) || /\.(m4a|mp4|aac)(\?|$)/i.test(url.pathname);

  let data: Blob;
  let mime = "audio/mp3";
  let piece: { startMs: number; endMs: number; eof: boolean };
  try {
    if (looksMp4) throw new NotMp3();
    const res = await fetchAudio(url);
    const p = await cutMp3(res.body!, fromMs, toMs);
    if (p.bytes === 0) {
      // Nothing left: the previous part was the last one.
      return json({ part, parts, startMs: fromMs, endMs: fromMs, done: true, segments: [] });
    }
    data = new Blob(p.frames, { type: mime });
    piece = p;
  } catch (e) {
    if (!(e instanceof NotMp3)) throw e;
    // Not MP3: can't be cut, so send the whole file if it's short enough.
    if (part > 0) return json({ part, parts, startMs: fromMs, endMs: fromMs, done: true, segments: [] });
    if (!durationMs || durationMs > MAX_WHOLE_MS) {
      return json({ error: "Only MP3 episodes can be transcribed when they're longer than 29 minutes" }, 415);
    }
    const res = await fetchAudio(url);
    const length = Number(res.headers.get("content-length") ?? "0");
    if (length > MAX_WHOLE_BYTES) {
      await res.body?.cancel();
      return json({ error: "The audio file is too large" }, 413);
    }
    data = await res.blob();
    mime = (res.headers.get("content-type") ?? mimeIn) || "audio/mp4";
    piece = { startMs: 0, endMs: durationMs, eof: true };
  }

  const file = await uploadToGemini(key, data, mime);
  try {
    const interaction = await transcribeFile(key, file.uri, mime);
    const { words, text } = wordsOf(interaction);
    let segments = wordsToSegments(words, piece.startMs);
    if (segments.length === 0 && text) {
      segments = [{ s: piece.startMs, e: piece.endMs, t: text, sp: null }];
    }
    return json({ part, parts, startMs: piece.startMs, endMs: piece.endMs, done: piece.eof, segments });
  } finally {
    await deleteGeminiFile(key, file.name);
  }
}

if (typeof Deno !== "undefined") {
  Deno.serve(async (req: Request) => {
    if (req.method === "OPTIONS") return new Response("ok", { headers: CORS });
    if (req.method !== "POST") return json({ error: "Use POST" }, 405);
    if (!(await signedIn(req))) return json({ error: "Sign in first" }, 401);
    try {
      return await handle(req);
    } catch (e) {
      const status = (e as { status?: number }).status ?? 502;
      return json({ error: e instanceof Error ? e.message : String(e) }, status);
    }
  });
}
