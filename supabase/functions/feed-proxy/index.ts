// Earshot: RSS feed proxy for the web player (Supabase Edge Function "feed-proxy").
//
// Browsers can't read most podcast feeds directly (the feed servers don't send CORS headers), so the
// web player asks this function, which fetches the feed and passes it back unchanged. The browser
// parses the XML itself.
//
// Only the signed-in user may use it: the request must carry the user's access token
// (Authorization: Bearer ...) and the project's publishable key (apikey), which the function checks
// with Supabase Auth.
//
// Call: GET https://<project-ref>.supabase.co/functions/v1/feed-proxy?url=<feed url>
// Returns: the feed's bytes, with the feed's Content-Type.

const CORS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "apikey, authorization, content-type, x-client-info",
  "Access-Control-Allow-Methods": "GET, OPTIONS",
};
const MAX_BYTES = 40 * 1024 * 1024; // the largest real feeds are around 10-20 MB

function text(body: string, status: number): Response {
  return new Response(body, { status, headers: { ...CORS, "Content-Type": "text/plain; charset=utf-8" } });
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

Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: CORS });
  if (req.method !== "GET") return text("Use GET", 405);
  if (!(await signedIn(req))) return text("Sign in first", 401);

  const target = new URL(req.url).searchParams.get("url") ?? "";
  let feed: URL;
  try {
    feed = new URL(target);
  } catch (_) {
    return text("Missing or invalid url", 400);
  }
  if (feed.protocol !== "https:" && feed.protocol !== "http:") return text("Only http and https feeds", 400);

  let res: Response;
  try {
    res = await fetch(feed, {
      headers: { "User-Agent": "Earshot/0.1 (personal podcast player)", Accept: "application/rss+xml, application/xml, text/xml, */*" },
      redirect: "follow",
    });
  } catch (e) {
    return text(`Couldn't reach the feed: ${e}`, 502);
  }
  if (!res.ok) {
    await res.body?.cancel();
    return text(`The feed returned HTTP ${res.status}`, 502);
  }
  const length = Number(res.headers.get("content-length") ?? "0");
  if (length > MAX_BYTES) {
    await res.body?.cancel();
    return text("The feed is too large", 502);
  }
  // Phase 15: also cap feeds that don't say their size up front, while they stream through.
  let seen = 0;
  const capped = res.body?.pipeThrough(
    new TransformStream<Uint8Array, Uint8Array>({
      transform(chunk, controller) {
        seen += chunk.byteLength;
        if (seen > MAX_BYTES) controller.error(new Error("The feed is too large"));
        else controller.enqueue(chunk);
      },
    }),
  );
  return new Response(capped ?? null, {
    status: 200,
    headers: {
      ...CORS,
      "Content-Type": res.headers.get("content-type") ?? "application/xml",
      "Cache-Control": "private, max-age=300",
    },
  });
});
