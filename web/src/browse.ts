import { useSyncExternalStore } from "react";
import { callFunction, supabase } from "./supabase";

// Phase 13: Browse Podcasts. The "browse" edge function builds the page for the signed-in account (its
// own listening, podrolls, Podcast Index, Apple's chart); settings (browse_prefs) and "Not interested"
// (browse_hidden) are read and written here directly. Same data as the phone. Online only.

export interface BrowseCard {
  feedUrl: string;
  title: string;
  author: string;
  artworkUrl: string | null;
  /** Why it's shown: "Recommended by Radiolab", "Popular in History", "#3 on Apple Podcasts". */
  reason: string;
}

export interface BrowseSection {
  id: string;
  /** What Browse settings hide and reorder. */
  group: string;
  title: string;
  subtitle?: string;
  items: BrowseCard[];
  /** For category rows: "See all" asks for more of it. */
  category?: string;
}

export interface BrowsePrefs {
  country: string;
  categories: string[];
  hidden_sections: string[];
  section_order: string[];
  friends_opt_in: boolean;
}

export interface BrowsePage {
  sections: BrowseSection[];
  prefs: BrowsePrefs;
  groups: string[];
  hiddenCount: number;
  notes: string[];
}

export interface HiddenShow {
  feed_url: string;
  title: string;
  image_url: string | null;
}

export const DEFAULT_CATEGORIES = ["News", "Music", "History", "Comedy", "True Crime", "Science", "Technology", "Sports"];

export const GROUP_NAMES: Record<string, string> = {
  for_you: "You may be into",
  because: "Because you listen to…",
  friends: "Popular with your friends",
  top: "Top podcasts (Apple)",
  trending: "Trending",
  your_categories: "Your top categories",
  categories: "Category rows",
};

export const COUNTRIES: Record<string, string> = {
  us: "United States",
  gb: "United Kingdom",
  ca: "Canada",
  au: "Australia",
  ie: "Ireland",
  nz: "New Zealand",
  in: "India",
  za: "South Africa",
  de: "Germany",
  fr: "France",
  es: "Spain",
  it: "Italy",
  nl: "Netherlands",
  se: "Sweden",
  br: "Brazil",
  mx: "Mexico",
  jp: "Japan",
};

interface BrowseState {
  page: BrowsePage | null;
  loading: boolean;
  error: string | null;
  loadedAt: number;
  /** Feed addresses hidden on this visit (they vanish before the next load). */
  hiddenNow: Set<string>;
  categories: string[] | null;
  /** "You might like" per feed address. */
  similar: Map<string, { items: BrowseCard[] | null; error?: string }>;
  hidden: HiddenShow[] | null;
  message: string | null;
  version: number;
}

let state: BrowseState = {
  page: null,
  loading: false,
  error: null,
  loadedAt: 0,
  hiddenNow: new Set(),
  categories: null,
  similar: new Map(),
  hidden: null,
  message: null,
  version: 0,
};
const listeners = new Set<() => void>();

function emit(patch: Partial<BrowseState> = {}) {
  state = { ...state, ...patch, version: state.version + 1 };
  for (const l of listeners) l();
}

export function useBrowse(): BrowseState {
  return useSyncExternalStore(
    (l) => {
      listeners.add(l);
      return () => listeners.delete(l);
    },
    () => state,
  );
}

const call = <T>(body: Record<string, unknown>) => callFunction<T>("browse", body);

/** Loads the page, unless one loaded in the last 30 minutes ([force]: always). */
export async function loadPage(force = false) {
  if (state.loading) return;
  if (!force && state.page && Date.now() - state.loadedAt < 30 * 60_000) return;
  emit({ loading: true, error: null });
  try {
    const page = await call<BrowsePage>({ mode: "page" });
    emit({ page, loadedAt: Date.now(), hiddenNow: new Set(), loading: false });
  } catch (e) {
    emit({ error: e instanceof Error ? e.message : String(e), loading: false });
  }
}

export async function notInterested(card: BrowseCard) {
  const hiddenNow = new Set(state.hiddenNow);
  hiddenNow.add(card.feedUrl);
  emit({ hiddenNow });
  const { error } = await supabase.from("browse_hidden").upsert({ feed_url: card.feedUrl, title: card.title, image_url: card.artworkUrl }, { onConflict: "user_id,feed_url" });
  if (error) {
    const back = new Set(state.hiddenNow);
    back.delete(card.feedUrl);
    emit({ hiddenNow: back, message: `Couldn't hide it: ${error.message}` });
  } else {
    emit({ message: `${card.title} won't be suggested again (undo in Browse settings)` });
  }
}

export function clearMessage() {
  emit({ message: null });
}

export async function categoryShows(name: string): Promise<BrowseCard[]> {
  return (await call<{ items: BrowseCard[] }>({ mode: "category", category: name })).items ?? [];
}

export async function loadCategories() {
  if (state.categories) return;
  try {
    emit({ categories: (await call<{ categories: string[] }>({ mode: "categories" })).categories ?? [] });
  } catch (e) {
    emit({ message: e instanceof Error ? e.message : String(e) });
  }
}

export async function loadSimilar(feedUrl: string, title: string) {
  if (state.similar.get(feedUrl)?.items) return;
  const similar = new Map(state.similar);
  similar.set(feedUrl, { items: null });
  emit({ similar });
  let next: { items: BrowseCard[] | null; error?: string };
  try {
    next = { items: (await call<{ items: BrowseCard[] }>({ mode: "similar", feedUrl, title })).items ?? [] };
  } catch (e) {
    next = { items: [], error: e instanceof Error ? e.message : String(e) };
  }
  const after = new Map(state.similar);
  after.set(feedUrl, next);
  emit({ similar: after });
}

export async function loadHidden() {
  const { data, error } = await supabase.from("browse_hidden").select("feed_url,title,image_url").order("created_at", { ascending: false });
  emit(error ? { hidden: [], message: error.message } : { hidden: (data ?? []) as HiddenShow[] });
}

export async function unhide(feedUrl: string) {
  emit({ hidden: state.hidden?.filter((h) => h.feed_url !== feedUrl) ?? null, loadedAt: 0 });
  const { error } = await supabase.from("browse_hidden").delete().eq("feed_url", feedUrl);
  if (error) emit({ message: `Couldn't undo: ${error.message}` });
}

/** Saves Browse settings and reloads the page with them. */
export async function savePrefs(p: BrowsePrefs) {
  if (state.page) emit({ page: { ...state.page, prefs: p } });
  const { error } = await supabase.from("browse_prefs").upsert({ ...p, updated_at: new Date().toISOString() }, { onConflict: "user_id" });
  if (error) {
    emit({ message: `Couldn't save Browse settings: ${error.message}` });
    return;
  }
  await loadPage(true);
}
