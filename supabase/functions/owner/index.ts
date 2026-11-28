// Earshot: owner console actions (Supabase Edge Function "owner"), phase 21.
//
// POST from a signed-in owner (public.app_owners, see supabase/migrations/014_owner_console.sql):
//   { action: "create_friend", email, password }   makes a friend's account (sign-ups stay closed)
//   { action: "check_credit" }                     reads the Deepgram credit now (also done every
//                                                  6 hours by the new-episodes function)
// Anyone else gets 403. The console's reads (people, devices, crash reports, switches) go straight to
// the database through owner_overview(), owner_crashes() and app_config.
//
// Secrets: DEEPGRAM_API_KEY (already set). Optional DEEPGRAM_ADMIN_KEY: a Deepgram key that may read
// billing, if the transcribe key can't. Provided by Supabase: SUPABASE_URL, SUPABASE_SERVICE_ROLE_KEY
// (or SUPABASE_SECRET_KEYS). Verify JWT: OFF (the function checks the sign-in itself, as the others).

const CORS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "apikey, authorization, content-type, x-client-info",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { ...CORS, "Content-Type": "application/json; charset=utf-8" } });
}

const base = () => Deno.env.get("SUPABASE_URL") ?? "";

/** The new secret key first; the legacy service_role key only while no secret key exists. */
function serviceKey(): string {
  try {
    const keys = Object.values(JSON.parse(Deno.env.get("SUPABASE_SECRET_KEYS") ?? "{}") as Record<string, string>);
    if (keys.length) return keys[0];
  } catch (_) {
    // not set
  }
  const legacy = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
  if (legacy) return legacy;
  throw new Error("No service role / secret key in the function's environment");
}

function serviceHeaders(): Record<string, string> {
  const key = serviceKey();
  const h: Record<string, string> = { apikey: key, "Content-Type": "application/json" };
  if (key.startsWith("eyJ")) h.Authorization = `Bearer ${key}`;
  return h;
}

async function db(path: string, init: RequestInit = {}): Promise<unknown> {
  const res = await fetch(`${base()}/rest/v1/${path}`, { ...init, headers: { ...serviceHeaders(), ...(init.headers as Record<string, string> ?? {}) } });
  const text = await res.text();
  if (!res.ok) throw new Error(`Database ${path.split("?")[0]}: HTTP ${res.status} ${text.slice(0, 300)}`);
  return text ? JSON.parse(text) : null;
}

// ---------------------------------------------------------------- Deepgram credit (same code as in new-episodes)

/** Deepgram credit left in USD, summed over the key's first project's balances. */
async function deepgramBalance(): Promise<number> {
  const key = Deno.env.get("DEEPGRAM_ADMIN_KEY") || Deno.env.get("DEEPGRAM_API_KEY");
  if (!key) throw new Error("No DEEPGRAM_API_KEY secret");
  const headers = { Authorization: `Token ${key}` };
  const pr = await fetch("https://api.deepgram.com/v1/projects", { headers });
  if (!pr.ok) throw new Error(`Deepgram projects: HTTP ${pr.status}${pr.status === 403 ? " (this key can't list projects: add a DEEPGRAM_ADMIN_KEY)" : ""}`);
  const projects = ((await pr.json()) as { projects?: { project_id?: string }[] }).projects ?? [];
  const id = projects[0]?.project_id;
  if (!id) throw new Error("Deepgram: no project for this key");
  const br = await fetch(`https://api.deepgram.com/v1/projects/${id}/balances`, { headers });
  const text = await br.text();
  if (!br.ok) throw new Error(`Deepgram balances: HTTP ${br.status}${br.status === 403 ? " (this key can't read billing: add a DEEPGRAM_ADMIN_KEY)" : ""}`);
  const balances = (JSON.parse(text) as { balances?: { amount?: number | string; units?: string }[] }).balances;
  if (!Array.isArray(balances)) throw new Error(`Deepgram balances: unexpected answer ${text.slice(0, 200)}`);
  return balances
    .filter((b) => !b.units || b.units.toLowerCase() === "usd")
    .reduce((sum, b) => sum + (Number(b.amount) || 0), 0);
}

/** Reads the credit and saves it in usage_watch. Returns the row as saved. */
async function checkCredit(): Promise<Record<string, unknown>> {
  const now = new Date().toISOString();
  try {
    const usd = await deepgramBalance();
    await db("usage_watch?id=eq.1", { method: "PATCH", body: JSON.stringify({ deepgram_balance_usd: Math.round(usd * 100) / 100, deepgram_checked_at: now, deepgram_error: null, updated_at: now }) });
  } catch (e) {
    await db("usage_watch?id=eq.1", { method: "PATCH", body: JSON.stringify({ deepgram_checked_at: now, deepgram_error: String((e as Error).message ?? e).slice(0, 300), updated_at: now }) });
  }
  const rows = await db("usage_watch?id=eq.1&select=*") as Record<string, unknown>[];
  return rows[0] ?? {};
}

// ---------------------------------------------------------------- main

Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: CORS });
  if (req.method !== "POST") return json({ error: "Use POST" }, 405);

  const auth = req.headers.get("authorization") ?? "";
  const apikey = req.headers.get("apikey") ?? "";
  if (!base() || !auth.toLowerCase().startsWith("bearer ") || !apikey) return json({ error: "Sign in first" }, 401);
  const asUser = { Authorization: auth, apikey, "Content-Type": "application/json" };
  const owner = await fetch(`${base()}/rest/v1/rpc/is_owner`, { method: "POST", headers: asUser, body: "{}" });
  if (!owner.ok) return json({ error: owner.status === 404 ? "Run 014_owner_console.sql first" : "Sign in first" }, owner.status === 404 ? 500 : 401);
  if ((await owner.json()) !== true) return json({ error: "Owners only" }, 403);

  let body: { action?: string; email?: string; password?: string };
  try {
    body = await req.json();
  } catch (_) {
    body = {};
  }

  try {
    if (body.action === "create_friend") {
      const email = (body.email ?? "").trim().toLowerCase();
      const password = body.password ?? "";
      if (!/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(email)) return json({ error: "That email address doesn't look right" }, 400);
      if (password.length < 8) return json({ error: "The password needs at least 8 characters" }, 400);
      const res = await fetch(`${base()}/auth/v1/admin/users`, {
        method: "POST",
        headers: serviceHeaders(),
        body: JSON.stringify({ email, password, email_confirm: true }),
      });
      const text = await res.text();
      if (!res.ok) {
        let msg = text.slice(0, 200);
        try {
          const o = JSON.parse(text);
          msg = o.msg ?? o.message ?? o.error_description ?? o.error ?? msg;
        } catch (_) {
          // keep the text
        }
        return json({ error: `Couldn't create the account: ${msg}` }, res.status === 422 ? 409 : 502);
      }
      const user = JSON.parse(text) as { id?: string; email?: string };
      return json({ ok: true, id: user.id, email: user.email });
    }
    if (body.action === "check_credit") {
      return json({ ok: true, usage: await checkCredit() });
    }
    return json({ error: "Unknown action" }, 400);
  } catch (e) {
    console.error(e);
    return json({ error: String((e as Error).message ?? e) }, 500);
  }
});
