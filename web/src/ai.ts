import { useEffect, useSyncExternalStore } from "react";
import type { Chapter } from "./feed";
import { callFunction, supabase } from "./supabase";

// Phase 11: chapters, summaries and ad spans made from an episode's transcript by the "ai" edge
// function (Gemini). Results live in public.episode_ai (006_ai.sql), shared with the phone. Rows are
// fetched per episode when it's shown, and kept for the session.

export type AiKind = "chapters" | "summary" | "ads";
export interface AdSpan {
  startMs: number;
  endMs: number;
  label: string;
}
export interface SummaryPoint {
  atMs: number;
  text: string;
}
export interface EpisodeAi {
  chapters?: Chapter[];
  summary?: { text: string; points: SummaryPoint[]; model: string };
  ads?: AdSpan[];
}
interface AiState {
  byEpisode: Map<string, EpisodeAi>;
  /** "episodeId|kind" being made right now. */
  running: Set<string>;
  /** Last error per "episodeId|kind". */
  errors: Map<string, string>;
  version: number;
}

let state: AiState = { byEpisode: new Map(), running: new Set(), errors: new Map(), version: 0 };
const listeners = new Set<() => void>();
const fetched = new Set<string>();

function emit() {
  state = { ...state, version: state.version + 1 };
  for (const l of listeners) l();
}

export function useAiState(): AiState {
  return useSyncExternalStore(
    (l) => {
      listeners.add(l);
      return () => listeners.delete(l);
    },
    () => state,
  );
}

/** What has been made for this episode (fetched from the server the first time it's asked for). */
export function useEpisodeAi(episodeId: string | null | undefined): EpisodeAi {
  const s = useAiState();
  useEffect(() => {
    if (episodeId) void load(episodeId);
  }, [episodeId]);
  return (episodeId && s.byEpisode.get(episodeId)) || {};
}

// Loose parsing of the stored shapes (times in ms).
// eslint-disable-next-line @typescript-eslint/no-explicit-any
function apply(episodeId: string, kind: string, data: any, model: string) {
  const cur = { ...(state.byEpisode.get(episodeId) ?? {}) };
  if (kind === "chapters") {
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    cur.chapters = (Array.isArray(data?.chapters) ? data.chapters : []).map((c: any) => ({ startMs: Number(c.start) || 0, title: String(c.title ?? ""), generated: true }))
      .filter((c: Chapter) => c.title)
      .sort((a: Chapter, b: Chapter) => a.startMs - b.startMs);
  } else if (kind === "summary") {
    cur.summary = {
      text: String(data?.summary ?? ""),
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      points: (Array.isArray(data?.points) ? data.points : []).map((p: any) => ({ atMs: Number(p.at) || 0, text: String(p.text ?? "") })).filter((p: SummaryPoint) => p.text),
      model,
    };
  } else if (kind === "ads") {
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    cur.ads = (Array.isArray(data?.ads) ? data.ads : []).map((a: any) => ({ startMs: Number(a.start) || 0, endMs: Number(a.end) || 0, label: String(a.label || "Ad") }))
      .filter((a: AdSpan) => a.endMs > a.startMs)
      .sort((a: AdSpan, b: AdSpan) => a.startMs - b.startMs);
  }
  state.byEpisode.set(episodeId, cur);
}

async function load(episodeId: string) {
  if (fetched.has(episodeId)) return;
  fetched.add(episodeId);
  const { data, error } = await supabase.from("episode_ai").select("kind,data,model").eq("episode_id", episodeId);
  if (error) return; // table not there yet (006_ai.sql), or offline: nothing to show
  for (const r of (data ?? []) as { kind: string; data: unknown; model: string }[]) apply(episodeId, r.kind, r.data, r.model ?? "");
  emit();
}

/** Asks the server to make [kind] from the episode's transcript. Errors are kept in state.errors. */
export async function generate(e: { id: string; title: string }, podcastTitle: string, kind: AiKind) {
  const key = `${e.id}|${kind}`;
  if (state.running.has(key)) return;
  state.running.add(key);
  state.errors.delete(key);
  emit();
  try {
    const { data, error } = await supabase.functions.invoke("ai", { body: { episodeId: e.id, kind, title: e.title, podcast: podcastTitle } });
    if (error) {
      let msg = error.message;
      const ctx = (error as { context?: Response }).context;
      if (ctx && typeof ctx.json === "function") {
        try {
          msg = ((await ctx.json()) as { error?: string }).error ?? msg;
        } catch {
          // Keep the general message.
        }
      }
      throw new Error(msg);
    }
    const res = data as { data: unknown; model?: string };
    apply(e.id, kind, res.data, res.model ?? "");
  } catch (err) {
    state.errors.set(key, err instanceof Error ? err.message : String(err));
  } finally {
    state.running.delete(key);
    emit();
  }
}

/** Same rule as the phone's AdSkip.current: an ad "plays" from its start to 1.5 s before its end. */
export function currentAd(ads: AdSpan[] | undefined, pos: number): AdSpan | null {
  return ads?.find((a) => pos >= a.startMs && pos < a.endMs - 1500) ?? null;
}

/** Same rule as the phone's AdSkip.skipTarget: back-to-back ads (gap under 5 s) go together. */
export function adSkipTarget(ads: AdSpan[], pos: number, alreadySkipped: Set<number>): number | null {
  let ad = currentAd(ads, pos);
  if (!ad || alreadySkipped.has(ad.startMs)) return null;
  let end = ad.endMs;
  for (;;) {
    const next = ads.find((a) => a.startMs > ad!.startMs && a.startMs <= end + 5000 && a.endMs > end);
    if (!next) break;
    ad = next;
    end = next.endMs;
  }
  return end;
}

export const AD_SKIP_LABELS: Record<string, string> = { auto: "Skip automatically", button: "Show a Skip ad button", off: "Off" };

/** Phase 13: an answer to a typed question, from the transcript, with supporting quotes. Not saved. */
export interface EpisodeAnswer {
  question: string;
  answer: string;
  quotes: SummaryPoint[];
}

export async function askEpisode(e: { id: string; title: string }, podcastTitle: string, question: string): Promise<EpisodeAnswer> {
  const res = await callFunction<{ data?: { answer?: string; quotes?: { at: number; text: string }[] } }>("ai", {
    episodeId: e.id,
    kind: "ask",
    question,
    title: e.title,
    podcast: podcastTitle,
  });
  return {
    question,
    answer: (res.data?.answer ?? "").trim(),
    quotes: (res.data?.quotes ?? []).map((q) => ({ atMs: Number(q.at) || 0, text: String(q.text ?? "").trim() })).filter((q) => q.text),
  };
}
