// Earshot: server-side new-episode check (Supabase Edge Function "new-episodes"), phase 10.
//
// Every 15 minutes (pg_cron, see supabase/migrations/005_schedule_new_episodes.sql) this asks Podcast
// Index for new episodes of every subscribed feed at once (/episodes/byfeedid takes up to 200 feed ids
// and a "since" time), adds them to each subscriber's library with their new-episode rule applied
// (SQL function new_episodes_insert in 004), so the web player stays current without the phone, and
// sends a "New episode" notification with a Play button to phones that registered for them.
//
// Secrets (Dashboard > Edge Functions > Secrets):
//   PODCASTINDEX_KEY, PODCASTINDEX_SECRET   (already there for podcast-search)
//   NEW_EPISODES_SECRET                     random text; the schedule sends it in x-earshot-cron
//   FCM_SERVICE_ACCOUNT                     optional: the Firebase service account JSON (whole file).
//                                           Without it, episodes are still added; no notifications.
// Provided by Supabase: SUPABASE_URL, SUPABASE_SERVICE_ROLE_KEY (or SUPABASE_SECRET_KEYS).
// Settings: turn "Verify JWT" OFF for this function (the schedule sends its own secret, not a JWT).
//
// Returns { feeds, lookedUp, checked, found, added, notified, errors }.

const API = "https://api.podcastindex.org/api/1.0";
const BATCH = 200; // Podcast Index's limit of feed ids per byfeedid call
const OVERLAP_S = 30 * 60; // ask for a little more than the last 15 minutes; duplicates are ignored
const FIRST_LOOK_S = 24 * 60 * 60; // a feed seen for the first time: only the last day counts as new

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json; charset=utf-8" } });
}

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

/** PostgREST call as the service role (bypasses row-level security; only used for the RPCs and feed_checks). */
async function db(path: string, init: RequestInit = {}): Promise<unknown> {
  const key = serviceKey();
  const headers: Record<string, string> = {
    apikey: key,
    "Content-Type": "application/json",
    ...(init.headers as Record<string, string> ?? {}),
  };
  if (key.startsWith("eyJ")) headers.Authorization = `Bearer ${key}`; // legacy JWT keys
  const res = await fetch(`${Deno.env.get("SUPABASE_URL")}/rest/v1/${path}`, { ...init, headers });
  const text = await res.text();
  if (!res.ok) throw new Error(`Database ${path.split("?")[0]}: HTTP ${res.status} ${text.slice(0, 300)}`);
  return text ? JSON.parse(text) : null;
}

async function sha1Hex(text: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-1", new TextEncoder().encode(text));
  return Array.from(new Uint8Array(digest)).map((b) => b.toString(16).padStart(2, "0")).join("");
}

/** The app's episode id: first 10 bytes of SHA-1(feed url + "\n" + guid), as hex. */
async function episodeId(feedUrl: string, guid: string): Promise<string> {
  return (await sha1Hex(feedUrl + "\n" + guid)).slice(0, 20);
}

// deno-lint-ignore no-explicit-any
async function podcastIndex(path: string): Promise<any> {
  const key = Deno.env.get("PODCASTINDEX_KEY");
  const secret = Deno.env.get("PODCASTINDEX_SECRET");
  if (!key || !secret) throw new Error("PODCASTINDEX_KEY / PODCASTINDEX_SECRET secrets are not set");
  const date = Math.floor(Date.now() / 1000).toString();
  const res = await fetch(`${API}${path}`, {
    headers: {
      "User-Agent": "Earshot/0.1",
      "X-Auth-Key": key,
      "X-Auth-Date": date,
      "Authorization": await sha1Hex(key + secret + date),
    },
  });
  if (!res.ok) throw new Error(`Podcast Index ${path.split("?")[0]}: HTTP ${res.status}`);
  return await res.json();
}

type FeedRow = { feed_url: string; pi_feed_id: number | null; looked_up_at: string | null; checked_since: number };

// ---------------------------------------------------------------- Firebase Cloud Messaging (HTTP v1)

type ServiceAccount = { project_id: string; client_email: string; private_key: string; token_uri?: string };

function b64url(data: Uint8Array | string): string {
  const bytes = typeof data === "string" ? new TextEncoder().encode(data) : data;
  let s = "";
  for (const b of bytes) s += String.fromCharCode(b);
  return btoa(s).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

async function fcmAccessToken(sa: ServiceAccount): Promise<string> {
  const now = Math.floor(Date.now() / 1000);
  const aud = sa.token_uri ?? "https://oauth2.googleapis.com/token";
  const unsigned = b64url(JSON.stringify({ alg: "RS256", typ: "JWT" })) + "." + b64url(JSON.stringify({
    iss: sa.client_email,
    scope: "https://www.googleapis.com/auth/firebase.messaging",
    aud,
    iat: now,
    exp: now + 3600,
  }));
  const pem = sa.private_key.replace(/-----[^-]+-----/g, "").replace(/\s+/g, "");
  const der = Uint8Array.from(atob(pem), (c) => c.charCodeAt(0));
  const key = await crypto.subtle.importKey("pkcs8", der, { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["sign"]);
  const sig = new Uint8Array(await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key, new TextEncoder().encode(unsigned)));
  const res = await fetch(aud, {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({ grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer", assertion: unsigned + "." + b64url(sig) }),
  });
  if (!res.ok) throw new Error(`Google token: HTTP ${res.status} ${(await res.text()).slice(0, 200)}`);
  return (await res.json()).access_token;
}

type Added = { user_id: string; episode_id: string; title: string; podcast_title: string; image_url: string | null; action: string; pinned: boolean };
type Device = { user_id: string; token: string; notify: string };

/** Sends one data message per new episode to each of the user's phones that wants it. Returns how many were sent. */
async function notify(added: Added[], errors: string[]): Promise<number> {
  const raw = Deno.env.get("FCM_SERVICE_ACCOUNT");
  const wanted = added.filter((a) => a.action !== "skip");
  if (!raw || wanted.length === 0) return 0;
  const sa = JSON.parse(raw) as ServiceAccount;
  const users = [...new Set(wanted.map((a) => a.user_id))];
  const devices = await db(
    `push_devices?select=user_id,token,notify&notify=neq.off&user_id=in.(${users.join(",")})`,
  ) as Device[];
  if (devices.length === 0) return 0;
  const token = await fcmAccessToken(sa);
  let sent = 0;
  for (const a of wanted) {
    for (const d of devices.filter((d) => d.user_id === a.user_id)) {
      if (d.notify === "pinned" && !a.pinned) continue;
      const res = await fetch(`https://fcm.googleapis.com/v1/projects/${sa.project_id}/messages:send`, {
        method: "POST",
        headers: { Authorization: `Bearer ${token}`, "Content-Type": "application/json" },
        body: JSON.stringify({
          message: {
            token: d.token,
            // Data only: the app builds the notification itself (with its Play button).
            data: {
              type: "new_episode",
              episode_id: a.episode_id,
              title: a.title ?? "",
              podcast: a.podcast_title ?? "",
              image: a.image_url ?? "",
              queued: a.action.startsWith("queue") ? "1" : "0",
            },
            android: { priority: "high", ttl: "86400s" },
          },
        }),
      });
      if (res.ok) {
        sent++;
      } else {
        const body = await res.text();
        // The app was uninstalled or the token replaced: forget this phone.
        if (res.status === 404 || body.includes("UNREGISTERED")) {
          await db(`push_devices?token=eq.${encodeURIComponent(d.token)}`, { method: "DELETE" });
        } else {
          errors.push(`FCM: HTTP ${res.status} ${body.slice(0, 200)}`);
        }
      }
    }
  }
  return sent;
}

// ---------------------------------------------------------------- the check

Deno.serve(async (req: Request) => {
  const expected = Deno.env.get("NEW_EPISODES_SECRET");
  if (!expected || req.headers.get("x-earshot-cron") !== expected) return json({ error: "Not allowed" }, 401);

  const errors: string[] = [];
  const now = Math.floor(Date.now() / 1000);
  let lookedUp = 0;
  let checked = 0;
  let found = 0;
  let added: Added[] = [];
  let feedCount = 0;
  try {
    const feeds = await db("rpc/new_episodes_feeds", { method: "POST", body: "{}" }) as FeedRow[];
    feedCount = feeds.length;

    // 1. Podcast Index ids for feeds we haven't looked up (or that weren't found, once a day).
    for (const f of feeds) {
      const stale = !f.looked_up_at || Date.now() - Date.parse(f.looked_up_at) > 24 * 3600 * 1000;
      if (f.pi_feed_id || !stale) continue;
      if (lookedUp >= 50) break; // spread a big first run over a few checks
      lookedUp++;
      try {
        // Podcast Index answers 400 or 404 for a feed it doesn't have: record it as not found
        // (looked up again after a day) instead of retrying every 15 minutes.
        const r = await podcastIndex(`/podcasts/byfeedurl?url=${encodeURIComponent(f.feed_url)}`)
          .catch((e: Error) => {
            if (/HTTP 40[04]$/.test(e.message)) return null;
            throw e;
          });
        const id = typeof r?.feed?.id === "number" && r.feed.id > 0 ? r.feed.id : null;
        f.pi_feed_id = id;
        await db("feed_checks?on_conflict=feed_url", {
          method: "POST",
          headers: { Prefer: "resolution=merge-duplicates" },
          body: JSON.stringify({ feed_url: f.feed_url, pi_feed_id: id, looked_up_at: new Date().toISOString(), checked_since: f.checked_since || now - FIRST_LOOK_S, last_error: id ? null : "not in Podcast Index" }),
        });
        if (!f.checked_since) f.checked_since = now - FIRST_LOOK_S;
      } catch (e) {
        errors.push(`${f.feed_url}: ${(e as Error).message}`);
      }
    }

    // 2. New episodes, 200 feeds per call, since the oldest "checked since" in the batch.
    const known = feeds.filter((f) => f.pi_feed_id);
    const byPiId = new Map<number, FeedRow[]>();
    for (const f of known) byPiId.set(f.pi_feed_id!, [...(byPiId.get(f.pi_feed_id!) ?? []), f]);
    const ids = [...byPiId.keys()];
    for (let i = 0; i < ids.length; i += BATCH) {
      const batch = ids.slice(i, i + BATCH);
      const since = Math.min(...batch.flatMap((id) => byPiId.get(id)!.map((f) => f.checked_since || now))) - OVERLAP_S;
      const r = await podcastIndex(`/episodes/byfeedid?id=${batch.join(",")}&since=${since}&max=1000&fulltext`);
      // deno-lint-ignore no-explicit-any
      const items: any[] = Array.isArray(r?.items) ? r.items : [];
      checked += batch.length;
      const rows = [];
      // Oldest first, so "play next" rules leave the newest at the top, as on the phone.
      items.sort((a, b) => (a.datePublished ?? 0) - (b.datePublished ?? 0));
      for (const it of items) {
        const audio = typeof it.enclosureUrl === "string" ? it.enclosureUrl : "";
        if (!audio) continue;
        for (const f of byPiId.get(it.feedId) ?? []) {
          if ((it.datePublished ?? 0) < (f.checked_since || now) - OVERLAP_S) continue;
          const guid = (typeof it.guid === "string" && it.guid.trim()) ? it.guid.trim() : audio;
          found++;
          rows.push({
            feed_url: f.feed_url,
            id: await episodeId(f.feed_url, guid),
            guid,
            title: it.title ?? "",
            description: it.description ?? "",
            pub_date: (it.datePublished ?? 0) * 1000,
            audio_url: audio,
            mime_type: it.enclosureType || null,
            duration_ms: typeof it.duration === "number" ? it.duration * 1000 : 0,
            image_url: it.image || it.feedImage || null,
            chapters_url: it.chaptersUrl || null,
            transcript_url: it.transcriptUrl || null,
            transcript_type: null,
            season: typeof it.season === "number" && it.season > 0 ? it.season : null,
            episode_no: typeof it.episode === "number" ? it.episode : null,
          });
        }
      }
      if (rows.length) {
        added = added.concat(await db("rpc/new_episodes_insert", { method: "POST", body: JSON.stringify({ items: rows }) }) as Added[]);
      }
      // Done with these feeds up to now.
      const urls = batch.flatMap((id) => byPiId.get(id)!.map((f) => f.feed_url));
      await db("feed_checks?on_conflict=feed_url", {
        method: "POST",
        headers: { Prefer: "resolution=merge-duplicates" },
        body: JSON.stringify(urls.map((u) => ({ feed_url: u, checked_since: now, last_error: null }))),
      });
    }
  } catch (e) {
    errors.push((e as Error).message);
  }

  let notified = 0;
  try {
    notified = await notify(added, errors);
  } catch (e) {
    errors.push(`Notifications: ${(e as Error).message}`);
  }
  const result = { feeds: feedCount, lookedUp, checked, found, added: added.length, notified, errors: errors.slice(0, 20) };
  console.log(JSON.stringify(result));
  return json(result, errors.length && !checked ? 500 : 200);
});
