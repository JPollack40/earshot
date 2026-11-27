// Earshot: Browse Podcasts (Supabase Edge Function "browse"), phase 13.
//
// POST from a signed-in user (phone or web), JSON body { mode, ... }:
//   { mode: "page" }                      the whole Browse page for this user (sections of cards)
//   { mode: "category", category: "History" }   up to 100 trending shows in one category
//   { mode: "categories" }                every Podcast Index category (for the Categories grid)
//   { mode: "similar", feedUrl, title? }  "You might like" for one podcast: its podroll, then popular
//                                         shows in its categories
// A card is { feedUrl, title, author, artworkUrl, reason }. Shows the user follows or marked
// "Not interested" (browse_hidden) are never returned.
//
// Data: Podcast Index (trending, categories, feed lookups, podroll GUIDs), Apple's chart feed
// (rss.marketingtools.apple.com, top 50 shows per country) with feed addresses from iTunes Lookup, the
// podcast:podroll tag read from feeds, and Gemini (only when podrolls and categories give too few
// picks for "You may be into"). Public lists are cached in public.browse_cache (007_browse.sql) as the
// service role: charts 12 h, trending 6 h, lookups and podrolls a day or more.
//
// Secrets: PODCASTINDEX_KEY, PODCASTINDEX_SECRET (already set), GEMINI_API_KEY, optional GEMINI_MODEL and
// GEMINI_FALLBACK_MODEL (as for "ai"). Provided by Supabase: SUPABASE_URL, SUPABASE_SERVICE_ROLE_KEY
// (or SUPABASE_SECRET_KEYS). Verify JWT: on (the default). The function also checks the sign-in itself.

const PI = "https://api.podcastindex.org/api/1.0";
const GEMINI = "https://generativelanguage.googleapis.com/v1beta/models";
const DEFAULT_MODEL = "gemini-3.8-flash";
const HOUR = 60 * 60 * 1000;
const DAY = 24 * HOUR;
const ROW = 30; // cards per row
const DEFAULT_CATEGORIES = ["News", "Music", "History", "Comedy", "True Crime", "Science", "Technology", "Sports"];
/** Section groups, in their default order. Each can be hidden or moved in Browse settings. */
const GROUPS = ["for_you", "because", "friends", "top", "trending", "your_categories", "categories"];

const CORS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "apikey, authorization, content-type, x-client-info",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { ...CORS, "Content-Type": "application/json; charset=utf-8" } });
}

type Card = { feedUrl: string; title: string; author: string; artworkUrl: string | null; reason: string };
type Section = { id: string; group: string; title: string; subtitle?: string; items: Card[]; category?: string };
type Prefs = { country: string; categories: string[]; hidden_sections: string[]; section_order: string[]; friends_opt_in: boolean };
// deno-lint-ignore no-explicit-any
type Any = any;

/** Same idea as the phone's Repository.feedKey: compare feed addresses without scheme, "www." or a trailing slash. */
function feedKey(url: string): string {
  return url.trim().toLowerCase().replace(/^[a-z]+:\/\//, "").replace(/^www\./, "").replace(/\/+$/, "");
}

/** Loose title match for checking Gemini's suggestions: lower case, letters and digits only, no "the". */
function looseTitle(t: string): string {
  return t.toLowerCase().replace(/&/g, "and").replace(/^the\s+/, "").replace(/[^a-z0-9]+/g, "");
}

// ---------------------------------------------------------------- database helpers

function serviceKey(): string {
  const legacy = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
  if (legacy) return legacy;
  try {
    const keys = Object.values(JSON.parse(Deno.env.get("SUPABASE_SECRET_KEYS") ?? "{}") as Record<string, string>);
    if (keys.length) return keys[0];
  } catch (_) {
    // not set
  }
  throw new Error("No service role / secret key in the function's environment");
}

function serviceHeaders(): Record<string, string> {
  const key = serviceKey();
  const h: Record<string, string> = { apikey: key, "Content-Type": "application/json" };
  if (key.startsWith("eyJ")) h.Authorization = `Bearer ${key}`; // legacy JWT keys
  return h;
}

const base = () => Deno.env.get("SUPABASE_URL") ?? "";

/** Memory of cache reads (for 10 minutes, shared by requests in this instance), and writes still running. */
const memo = new Map<string, { v: unknown; at: number }>();
const pendingWrites: Promise<unknown>[] = [];

/** Reads [key] from the shared cache if younger than [maxAgeMs], else runs [load] and stores its answer. */
async function cached<T>(key: string, maxAgeMs: number, load: () => Promise<T>): Promise<T> {
  const m = memo.get(key);
  if (m && Date.now() - m.at < Math.min(maxAgeMs, 10 * 60 * 1000)) return m.v as T;
  try {
    const res = await fetch(`${base()}/rest/v1/browse_cache?select=data,fetched_at&key=eq.${encodeURIComponent(key)}`, { headers: serviceHeaders() });
    if (res.ok) {
      const rows = (await res.json()) as { data: { v: T }; fetched_at: string }[];
      const row = rows[0];
      if (row && Date.now() - Date.parse(row.fetched_at) < maxAgeMs) {
        memo.set(key, { v: row.data.v, at: Date.now() });
        return row.data.v;
      }
    } else {
      await res.body?.cancel();
    }
  } catch (e) {
    console.error(`cache read ${key}: ${e}`);
  }
  const v = await load();
  memo.set(key, { v, at: Date.now() });
  pendingWrites.push(
    fetch(`${base()}/rest/v1/browse_cache?on_conflict=key`, {
      method: "POST",
      headers: { ...serviceHeaders(), Prefer: "resolution=merge-duplicates,return=minimal" },
      body: JSON.stringify({ key, data: { v }, fetched_at: new Date().toISOString() }),
    }).then((r) => r.body?.cancel()).catch((e) => console.error(`cache write ${key}: ${e}`)),
  );
  return v;
}

/** Runs [fn] over [items] with at most [limit] at a time; failures become null. */
async function pool<T, R>(items: T[], limit: number, fn: (t: T) => Promise<R>): Promise<(R | null)[]> {
  const out: (R | null)[] = new Array(items.length).fill(null);
  let next = 0;
  const worker = async () => {
    while (next < items.length) {
      const i = next++;
      try {
        out[i] = await fn(items[i]);
      } catch (e) {
        console.error(`${e}`);
      }
    }
  };
  await Promise.all(Array.from({ length: Math.min(limit, items.length) }, worker));
  return out;
}

// ---------------------------------------------------------------- Podcast Index

async function sha1Hex(text: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-1", new TextEncoder().encode(text));
  return Array.from(new Uint8Array(digest)).map((b) => b.toString(16).padStart(2, "0")).join("");
}

async function pi(path: string): Promise<Any> {
  const key = Deno.env.get("PODCASTINDEX_KEY");
  const secret = Deno.env.get("PODCASTINDEX_SECRET");
  if (!key || !secret) throw new Error("PODCASTINDEX_KEY / PODCASTINDEX_SECRET secrets are not set");
  const date = Math.floor(Date.now() / 1000).toString();
  const res = await fetch(`${PI}${path}`, {
    headers: { "User-Agent": "Earshot/1.0", "X-Auth-Key": key, "X-Auth-Date": date, Authorization: await sha1Hex(key + secret + date) },
  });
  if (!res.ok) {
    await res.body?.cancel();
    throw new Error(`Podcast Index ${path.split("?")[0]}: HTTP ${res.status}`);
  }
  return await res.json();
}

/** A Podcast Index feed object -> what we keep (small, for the cache). */
type PiFeed = { feedUrl: string; title: string; author: string; artworkUrl: string | null; categories: string[]; language: string };

function piFeed(f: Any): PiFeed | null {
  const feedUrl = typeof f?.url === "string" ? f.url : "";
  if (!feedUrl || f.dead === 1) return null;
  const cats = f.categories && typeof f.categories === "object" ? Object.values(f.categories).map(String) : [];
  return {
    feedUrl,
    title: String(f.title ?? "").trim(),
    author: String(f.author || f.ownerName || "").trim(),
    artworkUrl: f.artwork || f.image || null,
    categories: cats,
    language: String(f.language ?? ""),
  };
}

async function categoryList(): Promise<{ id: number; name: string }[]> {
  return await cached("pi:categories", 30 * DAY, async () => {
    const d = await pi("/categories/list");
    const list = (Array.isArray(d?.feeds) ? d.feeds : Array.isArray(d?.categories) ? d.categories : []) as Any[];
    return list.map((c) => ({ id: Number(c.id), name: String(c.name) })).filter((c) => c.id && c.name);
  });
}

/** Podcast Index's id for a category name ("True Crime" may be listed as "True Crime" or as "True" + "Crime"). */
async function categoryId(name: string): Promise<number | null> {
  const list = await categoryList();
  const want = name.trim().toLowerCase();
  const exact = list.find((c) => c.name.toLowerCase() === want);
  if (exact) return exact.id;
  const last = want.split(/\s+/).pop() ?? want;
  return list.find((c) => c.name.toLowerCase() === last)?.id ?? null;
}

/** Trending English shows, overall (category null) or in one category. */
async function trending(category: string | null, max = 100): Promise<PiFeed[]> {
  const id = category ? await categoryId(category) : null;
  if (category && id == null) return [];
  return await cached(`pi:trending:${id ?? "all"}:${max}`, 6 * HOUR, async () => {
    const d = await pi(`/podcasts/trending?max=${max}&lang=en${id != null ? `&cat=${id}` : ""}`);
    return ((d?.feeds ?? []) as Any[]).map(piFeed).filter((f): f is PiFeed => f != null);
  });
}

async function byFeedUrl(url: string): Promise<PiFeed | null> {
  return await cached(`pi:feed:${feedKey(url)}`, 7 * DAY, async () => {
    try {
      const d = await pi(`/podcasts/byfeedurl?url=${encodeURIComponent(url)}`);
      return piFeed(d?.feed);
    } catch (_) {
      return null; // not in the index
    }
  });
}

async function byGuid(guid: string): Promise<PiFeed | null> {
  return await cached(`pi:guid:${guid}`, 7 * DAY, async () => {
    try {
      const d = await pi(`/podcasts/byguid?guid=${encodeURIComponent(guid)}`);
      return piFeed(d?.feed);
    } catch (_) {
      return null;
    }
  });
}

/** A show by name (for Gemini's suggestions): the first search hit whose title matches. */
async function byTitle(title: string): Promise<PiFeed | null> {
  const want = looseTitle(title);
  if (!want) return null;
  return await cached(`pi:title:${want}`, 7 * DAY, async () => {
    const d = await pi(`/search/byterm?q=${encodeURIComponent(title)}&max=8`);
    for (const f of (d?.feeds ?? []) as Any[]) {
      const p = piFeed(f);
      if (p && looseTitle(p.title) === want) return p;
    }
    return null;
  });
}

// ---------------------------------------------------------------- podrolls

/** The feeds a show recommends in its own RSS (<podcast:podroll>), resolved through Podcast Index. */
async function podroll(feedUrl: string): Promise<PiFeed[]> {
  const refs = await cached(`podroll:${feedKey(feedUrl)}`, DAY, async () => {
    const res = await fetch(feedUrl, { headers: { "User-Agent": "Earshot/1.0 (podcast app; podroll)" }, signal: AbortSignal.timeout(10_000) });
    if (!res.ok) {
      await res.body?.cancel();
      return [] as { guid: string; url: string }[];
    }
    const xml = await res.text();
    const block = xml.match(/<podcast:podroll[^>]*>([\s\S]*?)<\/podcast:podroll>/i)?.[1] ?? "";
    const out: { guid: string; url: string }[] = [];
    for (const m of block.matchAll(/<podcast:remoteItem\b([^>]*)\/?>/gi)) {
      const attr = (name: string) => m[1].match(new RegExp(`${name}\\s*=\\s*["']([^"']+)["']`, "i"))?.[1] ?? "";
      const guid = attr("feedGuid");
      const url = attr("feedUrl");
      if (guid || url) out.push({ guid, url });
      if (out.length >= 10) break;
    }
    return out;
  }).catch(() => [] as { guid: string; url: string }[]);
  const feeds = await pool(refs, 6, async (r) => (r.guid ? await byGuid(r.guid) : null) ?? (r.url ? await byFeedUrl(r.url) : null));
  return feeds.filter((f): f is PiFeed => f != null);
}

// ---------------------------------------------------------------- Apple chart

async function appleTop(country: string): Promise<PiFeed[]> {
  const cc = /^[a-z]{2}$/.test(country) ? country : "us";
  return await cached(`apple:top:${cc}`, 12 * HOUR, async () => {
    const chart = await fetch(`https://rss.marketingtools.apple.com/api/v2/${cc}/podcasts/top/50/podcasts.json`);
    if (!chart.ok) {
      await chart.body?.cancel();
      throw new Error(`Apple chart: HTTP ${chart.status}`);
    }
    const rows = ((await chart.json())?.feed?.results ?? []) as Any[];
    const ids = rows.map((r) => String(r.id ?? "")).filter((id) => /^\d+$/.test(id));
    if (!ids.length) return [];
    const look = await fetch(`https://itunes.apple.com/lookup?id=${ids.join(",")}&entity=podcast&country=${cc}`);
    if (!look.ok) {
      await look.body?.cancel();
      throw new Error(`iTunes lookup: HTTP ${look.status}`);
    }
    const byId = new Map<string, Any>();
    for (const r of ((await look.json())?.results ?? []) as Any[]) byId.set(String(r.collectionId ?? r.trackId ?? ""), r);
    const out: PiFeed[] = [];
    for (const r of rows) {
      const l = byId.get(String(r.id));
      if (!l?.feedUrl) continue; // Apple-only shows (no public feed) can't be played
      out.push({
        feedUrl: String(l.feedUrl),
        title: String(r.name ?? l.collectionName ?? ""),
        author: String(r.artistName ?? l.artistName ?? ""),
        artworkUrl: l.artworkUrl600 || String(r.artworkUrl100 ?? "").replace(/\/\d+x\d+bb\./, "/600x600bb.") || null,
        categories: (r.genres ?? []).map((g: Any) => String(g?.name ?? "")).filter((g: string) => g && g !== "Podcasts"),
        language: "",
      });
    }
    return out;
  });
}

// ---------------------------------------------------------------- Gemini (only when picks are thin)

async function geminiSimilar(shows: string[]): Promise<{ title: string; similarTo: string }[]> {
  const key = Deno.env.get("GEMINI_API_KEY");
  if (!key || !shows.length) return [];
  const prompt =
    `A listener's favourite podcasts are: ${shows.map((s) => `"${s}"`).join(", ")}.\n` +
    "Suggest 12 other real, currently available, English-language podcasts they would probably enjoy, that are not in that list. " +
    "Prefer well-known shows that are easy to find in podcast directories. For each, give its exact title as listed in podcast apps " +
    "and which of the listener's podcasts it is most like.";
  const body = JSON.stringify({
    contents: [{ role: "user", parts: [{ text: prompt }] }],
    generationConfig: {
      responseMimeType: "application/json",
      temperature: 0.4,
      responseSchema: {
        type: "OBJECT",
        properties: { shows: { type: "ARRAY", items: { type: "OBJECT", properties: { title: { type: "STRING" }, similarTo: { type: "STRING" } }, required: ["title", "similarTo"] } } },
        required: ["shows"],
      },
    },
  });
  const ask = (m: string) =>
    fetch(`${GEMINI}/${m}:generateContent`, { method: "POST", headers: { "Content-Type": "application/json", "x-goog-api-key": key }, body, signal: AbortSignal.timeout(40_000) });
  let model = Deno.env.get("GEMINI_MODEL") || DEFAULT_MODEL;
  let res = await ask(model);
  const fallback = Deno.env.get("GEMINI_FALLBACK_MODEL");
  if (!res.ok && [429, 500, 503, 404].includes(res.status) && fallback && fallback !== model) {
    console.error(`Gemini ${model} HTTP ${res.status}, using ${fallback}`);
    await res.body?.cancel();
    model = fallback;
    res = await ask(model);
  }
  if (!res.ok) {
    console.error(`Gemini ${model} HTTP ${res.status}: ${(await res.text()).slice(0, 300)}`);
    return [];
  }
  const out = await res.json();
  const text = ((out?.candidates?.[0]?.content?.parts ?? []) as Any[]).map((p) => p?.text ?? "").join("");
  try {
    return ((JSON.parse(text)?.shows ?? []) as Any[])
      .map((s) => ({ title: String(s?.title ?? "").trim(), similarTo: String(s?.similarTo ?? "").trim() }))
      .filter((s) => s.title);
  } catch (_) {
    return [];
  }
}

// ---------------------------------------------------------------- building rows

/** Collects cards for one row, skipping followed, hidden and repeated shows. */
class RowBuilder {
  items: Card[] = [];
  private seen = new Set<string>();
  constructor(private exclude: Set<string>, private max = ROW) {}
  add(f: PiFeed | null | undefined, reason: string): void {
    if (!f || this.items.length >= this.max) return;
    const k = feedKey(f.feedUrl);
    if (this.exclude.has(k) || this.seen.has(k) || !f.title) return;
    this.seen.add(k);
    this.items.push({ feedUrl: f.feedUrl, title: f.title, author: f.author, artworkUrl: f.artworkUrl, reason });
  }
  get full() {
    return this.items.length >= this.max;
  }
}

type TopShow = { feedUrl: string; title: string; ms: number };

/** Category weights from the user's most-listened shows (Podcast Index categories, weighted by time). */
async function topCategories(shows: TopShow[]): Promise<{ name: string; weight: number }[]> {
  const feeds = await pool(shows.slice(0, 10), 5, (s) => byFeedUrl(s.feedUrl));
  const w = new Map<string, number>();
  feeds.forEach((f, i) => {
    for (const c of f?.categories ?? []) w.set(c, (w.get(c) ?? 0) + Math.max(1, shows[i].ms));
  });
  return [...w.entries()].map(([name, weight]) => ({ name, weight })).sort((a, b) => b.weight - a.weight);
}

// ---------------------------------------------------------------- the request

Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: CORS });
  if (req.method !== "POST") return json({ error: "Use POST" }, 405);

  const auth = req.headers.get("authorization") ?? "";
  const apikey = req.headers.get("apikey") ?? "";
  if (!base() || !auth.toLowerCase().startsWith("bearer ") || !apikey) return json({ error: "Sign in first" }, 401);
  const asUser = { Authorization: auth, apikey };
  const who = await fetch(`${base()}/auth/v1/user`, { headers: asUser });
  if (!who.ok) return json({ error: "Sign in first" }, 401);
  await who.body?.cancel();

  let body: { mode?: string; category?: string; feedUrl?: string; title?: string };
  try {
    body = await req.json();
  } catch (_) {
    body = {};
  }
  const mode = body.mode ?? "page";

  /** The user's own rows (row-level security keeps them to theirs). Missing tables (007 not run) read as empty. */
  const mine = async <T>(path: string): Promise<T[]> => {
    const r = await fetch(`${base()}/rest/v1/${path}`, { headers: asUser });
    if (!r.ok) {
      console.error(`read ${path.split("?")[0]}: HTTP ${r.status} ${(await r.text()).slice(0, 200)}`);
      return [];
    }
    return (await r.json()) as T[];
  };

  try {
    if (mode === "categories") {
      return json({ categories: (await categoryList()).map((c) => c.name).sort((a, b) => a.localeCompare(b)) });
    }

    const [podcasts, hidden, prefRows] = await Promise.all([
      mine<{ feed_url: string; title: string; subscribed: boolean; pinned: boolean }>("podcasts?select=feed_url,title,subscribed,pinned&subscribed=eq.true"),
      mine<{ feed_url: string }>("browse_hidden?select=feed_url"),
      mine<Prefs>("browse_prefs?select=country,categories,hidden_sections,section_order,friends_opt_in"),
    ]);
    const exclude = new Set<string>([...podcasts.map((p) => feedKey(p.feed_url)), ...hidden.map((h) => feedKey(h.feed_url))]);
    const prefs: Prefs = {
      country: prefRows[0]?.country || "us",
      categories: prefRows[0]?.categories?.length ? prefRows[0].categories : DEFAULT_CATEGORIES,
      hidden_sections: prefRows[0]?.hidden_sections ?? [],
      section_order: prefRows[0]?.section_order ?? [],
      friends_opt_in: prefRows[0]?.friends_opt_in ?? false,
    };

    if (mode === "category") {
      const name = String(body.category ?? "").trim();
      if (!name) return json({ error: "category is required" }, 400);
      const row = new RowBuilder(exclude, 100);
      for (const f of await trending(name, 200)) row.add(f, `Popular in ${name}`);
      await Promise.all(pendingWrites.splice(0));
      return json({ items: row.items });
    }

    if (mode === "similar") {
      const url = String(body.feedUrl ?? "").trim();
      if (!url) return json({ error: "feedUrl is required" }, 400);
      const self = feedKey(url);
      const row = new RowBuilder(new Set([...exclude, self]), 40);
      const [roll, info] = await Promise.all([podroll(url).catch(() => [] as PiFeed[]), byFeedUrl(url)]);
      const name = body.title || info?.title || "this show";
      for (const f of roll) row.add(f, `Recommended by ${name}`);
      for (const c of (info?.categories ?? []).slice(0, 3)) {
        if (row.full) break;
        for (const f of await trending(c, 100)) row.add(f, `Popular in ${c}`);
      }
      await Promise.all(pendingWrites.splice(0));
      return json({ items: row.items, podroll: roll.length > 0 });
    }

    if (mode !== "page") return json({ error: `Unknown mode "${mode}"` }, 400);

    // ---- The user's listening: most-listened shows over the last 90 days (from the listens log) ----
    let top: TopShow[] = (await mine<{ feed_url: string; podcast_title: string; listened_ms: number }>("rpc/browse_my_top_shows"))
      .map((r) => ({ feedUrl: r.feed_url, title: r.podcast_title, ms: Number(r.listened_ms) || 0 }))
      .filter((r) => r.feedUrl);
    if (top.length === 0) {
      // No listening yet (a new account): pinned podcasts, then the rest of the library.
      top = [...podcasts].sort((a, b) => Number(b.pinned) - Number(a.pinned)).slice(0, 8).map((p) => ({ feedUrl: p.feed_url, title: p.title, ms: 1 }));
    }
    const titleOf = new Map(podcasts.map((p) => [feedKey(p.feed_url), p.title]));
    for (const t of top) t.title = titleOf.get(feedKey(t.feedUrl)) || t.title;

    const want = (group: string) => !prefs.hidden_sections.includes(group);
    const sections: Section[] = [];
    const notes: string[] = [];

    const [cats, rolls] = await Promise.all([
      want("for_you") || want("because") || want("your_categories") ? topCategories(top) : Promise.resolve([]),
      want("for_you") || want("because") ? pool(top.slice(0, 5), 5, (s) => podroll(s.feedUrl)) : Promise.resolve([] as (PiFeed[] | null)[]),
    ]);

    // You may be into: podrolls of the top shows, then trending in the top categories, then Gemini if still thin.
    if (want("for_you")) {
      const row = new RowBuilder(exclude, 24);
      // Take podroll picks round-robin, so one show's long podroll doesn't fill the row.
      for (let i = 0; i < 10; i++) top.slice(0, 5).forEach((s, j) => row.add(rolls[j]?.[i], `Recommended by ${s.title}`));
      for (const c of cats.slice(0, 3)) {
        const list = await trending(c.name, 100);
        for (const f of list.slice(0, 8)) row.add(f, `Popular in ${c.name}`);
      }
      if (row.items.length < 8 && top.length) {
        const names = top.slice(0, 5).map((t) => t.title).filter(Boolean);
        const picks = await cached(`gemini:similar:${names.map(looseTitle).sort().join("|")}`, 3 * DAY, () => geminiSimilar(names));
        const found = await pool(picks.slice(0, 12), 6, (p) => byTitle(p.title));
        found.forEach((f, i) => row.add(f, `Similar to ${picks[i].similarTo || names[0]}`));
      }
      if (row.items.length) sections.push({ id: "for_you", group: "for_you", title: "You may be into", subtitle: "From the shows you listen to most", items: row.items });
    }

    // Because you listen to X: the show's podroll, then trending in its categories.
    if (want("because")) {
      for (const [i, s] of top.slice(0, 3).entries()) {
        const row = new RowBuilder(exclude, 20);
        for (const f of rolls[i] ?? []) row.add(f, `Recommended by ${s.title}`);
        const info = await byFeedUrl(s.feedUrl);
        for (const c of (info?.categories ?? []).slice(0, 2)) {
          if (row.full) break;
          for (const f of (await trending(c, 100)).slice(0, 12)) row.add(f, `Popular in ${c}`);
        }
        if (row.items.length) sections.push({ id: `because:${s.feedUrl}`, group: "because", title: `Because you listen to ${s.title}`, items: row.items });
      }
    }

    // Popular with your friends (opt-in, no names, at least 2 other listeners).
    if (want("friends") && prefs.friends_opt_in) {
      const rows = await mine<{ feed_url: string; podcast_title: string; listeners: number }>("rpc/browse_friends_popular");
      const row = new RowBuilder(exclude, ROW);
      const infos = await pool(rows, 6, (r) => byFeedUrl(r.feed_url));
      rows.forEach((r, i) => {
        const f = infos[i] ?? { feedUrl: r.feed_url, title: r.podcast_title, author: "", artworkUrl: null, categories: [], language: "" };
        row.add({ ...f, feedUrl: r.feed_url }, `${r.listeners} friends listen`);
      });
      sections.push({
        id: "friends",
        group: "friends",
        title: "Popular with your friends",
        subtitle: row.items.length ? "Shows at least 2 friends listen to" : "Nothing yet: a show appears once at least 2 friends who opted in listen to it",
        items: row.items,
      });
    }

    // Top podcasts (Apple) and Trending (Podcast Index).
    if (want("top")) {
      try {
        const row = new RowBuilder(exclude, 50);
        (await appleTop(prefs.country)).forEach((f, i) => row.add(f, `#${i + 1} on Apple Podcasts`));
        if (row.items.length) sections.push({ id: "top", group: "top", title: "Top podcasts", subtitle: `Apple Podcasts chart · ${prefs.country.toUpperCase()}`, items: row.items });
      } catch (e) {
        console.error(`${e}`);
        notes.push("Apple's chart didn't load");
      }
    }
    if (want("trending")) {
      const row = new RowBuilder(exclude, 50);
      for (const f of await trending(null, 100)) row.add(f, "Trending on Podcast Index");
      if (row.items.length) sections.push({ id: "trending", group: "trending", title: "Trending", subtitle: "Podcast Index", items: row.items });
    }

    // Your top categories (automatic).
    if (want("your_categories")) {
      for (const c of cats.slice(0, 3)) {
        const row = new RowBuilder(exclude, ROW);
        for (const f of await trending(c.name, 100)) row.add(f, `Popular in ${c.name}`);
        if (row.items.length) sections.push({ id: `yourcat:${c.name}`, group: "your_categories", title: c.name, subtitle: "One of your top categories", items: row.items, category: c.name });
      }
    }

    // The chosen category rows (editable in Browse settings).
    if (want("categories")) {
      const lists = await pool(prefs.categories.slice(0, 20), 6, (c) => trending(c, 100));
      prefs.categories.slice(0, 20).forEach((c, i) => {
        const row = new RowBuilder(exclude, ROW);
        for (const f of lists[i] ?? []) row.add(f, `Popular in ${c}`);
        if (row.items.length) sections.push({ id: `cat:${c}`, group: "categories", title: c, items: row.items, category: c });
      });
    }

    // Order by the user's section order (unknown groups keep the default order after).
    const order = [...prefs.section_order.filter((g) => GROUPS.includes(g)), ...GROUPS.filter((g) => !prefs.section_order.includes(g))];
    const rank = (g: string) => order.indexOf(g);
    sections.sort((a, b) => rank(a.group) - rank(b.group)); // stable: rows within a group keep their order

    await Promise.all(pendingWrites.splice(0));
    return json({ sections, prefs, groups: order, hiddenCount: hidden.length, notes });
  } catch (e) {
    console.error(`browse ${mode}: ${e}`);
    await Promise.all(pendingWrites.splice(0));
    return json({ error: `Browse couldn't load: ${e instanceof Error ? e.message : e}` }, 502);
  }
});
