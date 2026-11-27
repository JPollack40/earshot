// Earshot: Podcast Index search proxy (Supabase Edge Function "podcast-search").
//
// The phone calls this function; the function signs the request to Podcast Index with the API key and
// secret, so the secret never sits in the app or the repo.
//
// Secrets (Dashboard > Edge Functions > Secrets): PODCASTINDEX_KEY, PODCASTINDEX_SECRET
// Call: GET https://<project-ref>.supabase.co/functions/v1/podcast-search?q=<terms>
//       with header  apikey: <the project's publishable key>
// Returns: { results: [{ title, author, feedUrl, artworkUrl, episodeCount, itunesId }] }
//
// Podcast Index auth (docs: podcastindex-org.github.io/docs-api, "Authentication"):
//   X-Auth-Key, X-Auth-Date (unix seconds), User-Agent, Authorization = sha1(key + secret + date) as lowercase hex.

const API = "https://api.podcastindex.org/api/1.0";

// The web player calls this from the browser, so it needs CORS headers (and an answer to the preflight).
const CORS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "apikey, authorization, content-type, x-client-info",
  "Access-Control-Allow-Methods": "GET, OPTIONS",
};

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...CORS, "Content-Type": "application/json; charset=utf-8" },
  });
}

/** True when the request carries one of this project's publishable (or secret) keys. */
function hasProjectKey(req: Request): boolean {
  const sent = req.headers.get("apikey") ?? "";
  if (!sent) return false;
  const known: string[] = [];
  for (const name of ["SUPABASE_PUBLISHABLE_KEYS", "SUPABASE_SECRET_KEYS"]) {
    try {
      known.push(...Object.values(JSON.parse(Deno.env.get(name) ?? "{}") as Record<string, string>));
    } catch (_) {
      // not set or not JSON: ignore
    }
  }
  for (const name of ["SUPABASE_ANON_KEY", "SUPABASE_SERVICE_ROLE_KEY"]) {
    const v = Deno.env.get(name);
    if (v) known.push(v);
  }
  return known.includes(sent);
}

async function sha1Hex(text: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-1", new TextEncoder().encode(text));
  return Array.from(new Uint8Array(digest)).map((b) => b.toString(16).padStart(2, "0")).join("");
}

Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: CORS });
  if (req.method !== "GET") return json({ error: "Use GET" }, 405);
  if (!hasProjectKey(req)) return json({ error: "Missing or unknown apikey" }, 401);

  const q = new URL(req.url).searchParams.get("q")?.trim() ?? "";
  if (!q) return json({ results: [] });

  const key = Deno.env.get("PODCASTINDEX_KEY");
  const secret = Deno.env.get("PODCASTINDEX_SECRET");
  if (!key || !secret) return json({ error: "PODCASTINDEX_KEY / PODCASTINDEX_SECRET secrets are not set" }, 500);

  const date = Math.floor(Date.now() / 1000).toString();
  const url = `${API}/search/byterm?q=${encodeURIComponent(q)}&max=40`;
  const res = await fetch(url, {
    headers: {
      "User-Agent": "Earshot/0.1",
      "X-Auth-Key": key,
      "X-Auth-Date": date,
      "Authorization": await sha1Hex(key + secret + date),
    },
  });
  if (!res.ok) return json({ error: `Podcast Index returned HTTP ${res.status}` }, 502);

  // deno-lint-ignore no-explicit-any
  const data: any = await res.json();
  // deno-lint-ignore no-explicit-any
  const feeds: any[] = Array.isArray(data?.feeds) ? data.feeds : [];
  const seen = new Set<string>();
  const results = [];
  for (const f of feeds) {
    const feedUrl = typeof f.url === "string" ? f.url : "";
    if (!feedUrl || f.dead === 1 || seen.has(feedUrl)) continue;
    seen.add(feedUrl);
    results.push({
      title: f.title ?? "",
      author: f.author || f.ownerName || "",
      feedUrl,
      artworkUrl: f.artwork || f.image || null,
      episodeCount: typeof f.episodeCount === "number" ? f.episodeCount : null,
      itunesId: typeof f.itunesId === "number" ? f.itunesId : null,
    });
  }
  return json({ results });
});
