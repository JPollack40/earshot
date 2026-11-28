// Phase 21 (D1, D2): the owner console. Only accounts in public.app_owners can open it (the database
// refuses everyone else; see supabase/migrations/014_owner_console.sql). Friends' accounts and app
// versions, the device count against the 20 allowed, crash reports, the phase 16 switches, the
// Deepgram credit, and Create friend account (through the "owner" function).
import { useEffect, useState } from "react";
import { callFunction, supabase } from "../supabase";
import type { Nav } from "./common";

let ownerCache: boolean | null = null;
const ownerListeners = new Set<(v: boolean) => void>();

/** Whether the signed-in person is an owner (asked once per visit; false until known or if 014 isn't run). */
export function useIsOwner(): boolean {
  const [v, setV] = useState<boolean>(ownerCache ?? false);
  useEffect(() => {
    ownerListeners.add(setV);
    if (ownerCache === null) {
      ownerCache = false;
      void supabase.rpc("is_owner").then(({ data, error }) => {
        ownerCache = !error && data === true;
        for (const l of ownerListeners) l(ownerCache);
      });
    }
    return () => {
      ownerListeners.delete(setV);
    };
  }, []);
  return v;
}

interface Device {
  device_id: string;
  app_version: string;
  version_code: number;
  build_type: string;
  device: string;
  android: string;
  last_sync_at: string | null;
  last_seen_at: string;
}
interface Person {
  user_id: string;
  email: string;
  created_at: string;
  last_sign_in_at: string | null;
  owner: boolean;
  podcasts: number;
  crashes_30_days: number;
  devices: Device[];
}
interface Overview {
  people: Person[];
  devices_90_days: number;
  device_cap: number;
}
interface Crash {
  id: number;
  user_id: string;
  created_at: string;
  kind: string;
  app_version: string;
  device: string;
  android: string;
  summary: string;
  details: string;
  note: string;
}
interface Config {
  min_supported_version: string | null;
  hold_version: string | null;
  ai_on: boolean;
  ad_finding_on: boolean;
  transcribe_on: boolean;
  browse_ai_on: boolean;
  paused_message: string;
  banner: string | null;
  gemini_model: string | null;
  gemini_fallback_model: string | null;
}
interface Usage {
  deepgram_balance_usd: number | null;
  deepgram_checked_at: string | null;
  deepgram_error: string | null;
  warn_below_usd: number;
  warned_at: string | null;
}

const when = (iso: string | null) => (iso ? new Date(iso).toLocaleString(undefined, { dateStyle: "medium", timeStyle: "short" }) : "never");

const SWITCHES: { key: keyof Config; label: string }[] = [
  { key: "ai_on", label: "AI (summaries, chapters, Ask, bookmark titles)" },
  { key: "ad_finding_on", label: "Find ads" },
  { key: "transcribe_on", label: "Transcribe with Deepgram" },
  { key: "browse_ai_on", label: "Browse suggestions (Gemini)" },
];
const TEXTS: { key: keyof Config; label: string; hint: string }[] = [
  { key: "banner", label: "Banner", hint: "A short note at the top of everyone's app and web player. Empty = none." },
  { key: "paused_message", label: "Paused message", hint: "What a switched-off feature says." },
  { key: "min_supported_version", label: "Lowest supported version", hint: "e.g. 1.1.0: older release builds show Update needed (they keep working)." },
  { key: "hold_version", label: "Hold updates at", hint: "e.g. 1.1.0: the update check offers nothing newer." },
  { key: "gemini_model", label: "Gemini model", hint: "Empty = the function's secret or default." },
  { key: "gemini_fallback_model", label: "Gemini fallback model", hint: "Empty = the function's secret or default." },
];

export function OwnerPage({ nav }: { nav: Nav }) {
  const owner = useIsOwner();
  const [overview, setOverview] = useState<Overview | null>(null);
  const [crashes, setCrashes] = useState<Crash[]>([]);
  const [config, setConfig] = useState<Config | null>(null);
  const [draft, setDraft] = useState<Partial<Config>>({});
  const [usage, setUsage] = useState<Usage | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [note, setNote] = useState<string | null>(null);
  const [tick, setTick] = useState(0);

  useEffect(() => {
    if (!owner) return;
    setError(null);
    void (async () => {
      const [o, c, cfg, u] = await Promise.all([
        supabase.rpc("owner_overview"),
        supabase.rpc("owner_crashes", { p_limit: 50 }),
        supabase.from("app_config").select("*").eq("id", 1).maybeSingle(),
        supabase.from("usage_watch").select("*").eq("id", 1).maybeSingle(),
      ]);
      const failed = [o, c, cfg, u].find((r) => r.error);
      if (failed?.error) setError(failed.error.message);
      setOverview((o.data as Overview) ?? null);
      setCrashes((c.data as Crash[]) ?? []);
      setConfig((cfg.data as Config) ?? null);
      setDraft({});
      setUsage((u.data as Usage) ?? null);
    })();
  }, [owner, tick]);

  const back = (
    <button className="icon" title="Back" onClick={nav.back}>
      ←
    </button>
  );
  if (!owner) {
    return (
      <div className="page">
        <header className="bar">
          {back}
          <h1>Owner console</h1>
        </header>
        <p className="muted pad">This page is only for the app's owner.</p>
      </div>
    );
  }

  const saveConfig = async (patch: Partial<Config>) => {
    setNote(null);
    const { error: e } = await supabase.from("app_config").update(patch).eq("id", 1);
    if (e) setError(e.message);
    else {
      setNote("Saved. Phones pick it up within the hour (or at their next start).");
      setTick((t) => t + 1);
    }
  };

  const emailOf = new Map((overview?.people ?? []).map((p) => [p.user_id, p.email]));

  return (
    <div className="page owner">
      <header className="bar">
        {back}
        <div className="grow">
          <h1>Owner console</h1>
          <div className="meta">Only you can see this page.</div>
        </div>
        <button className="text" onClick={() => setTick((t) => t + 1)}>
          Refresh
        </button>
      </header>
      {error && <p className="error pad">{error}</p>}
      {note && <p className="pad">{note}</p>}

      <section className="owner-card">
        <h2>Deepgram credit</h2>
        {usage ? (
          <>
            <p className="owner-big">{usage.deepgram_balance_usd == null ? "Not checked yet" : `$${Number(usage.deepgram_balance_usd).toFixed(2)}`}</p>
            <p className="muted">
              Checked {when(usage.deepgram_checked_at)} (every 6 hours by the new-episodes check).
              {usage.warned_at ? ` Warning sent ${when(usage.warned_at)}.` : ""}
            </p>
            {usage.deepgram_error && <p className="error">{usage.deepgram_error}</p>}
            <WarnLevel
              value={Number(usage.warn_below_usd)}
              onSave={async (v) => {
                const { error: e } = await supabase.from("usage_watch").update({ warn_below_usd: v }).eq("id", 1);
                if (e) setError(e.message);
                else setTick((t) => t + 1);
              }}
            />
            <button
              className="text"
              onClick={() =>
                void callFunction<{ usage: Usage }>("owner", { action: "check_credit" })
                  .then((r) => setUsage(r.usage))
                  .catch((e: Error) => setError(e.message))
              }
            >
              Check now
            </button>
          </>
        ) : (
          <p className="muted">Run 014_owner_console.sql to turn this on.</p>
        )}
      </section>

      <section className="owner-card">
        <h2>
          People and devices · {overview?.devices_90_days ?? "–"} of {overview?.device_cap ?? 20} devices
        </h2>
        <p className="muted">Devices seen in the last 90 days, against Google's limit for your developer account.</p>
        <table className="owner-table">
          <thead>
            <tr>
              <th>Account</th>
              <th>Podcasts</th>
              <th>Problems (30 days)</th>
              <th>Devices</th>
            </tr>
          </thead>
          <tbody>
            {(overview?.people ?? []).map((p) => (
              <tr key={p.user_id}>
                <td>
                  {p.email}
                  {p.owner ? " (owner)" : ""}
                  <div className="muted">Last sign-in {when(p.last_sign_in_at)}</div>
                </td>
                <td>{p.podcasts}</td>
                <td>{p.crashes_30_days || ""}</td>
                <td>
                  {p.devices.length === 0 && <span className="muted">none reported</span>}
                  {p.devices.map((d) => (
                    <div key={d.device_id}>
                      {d.device} · {d.app_version} {d.build_type === "release" ? "" : `(${d.build_type})`} · Android {d.android}
                      <div className="muted">Last sync {when(d.last_sync_at)}</div>
                    </div>
                  ))}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </section>

      <CreateFriend onDone={() => setTick((t) => t + 1)} />

      <section className="owner-card">
        <h2>Switches</h2>
        {config ? (
          <>
            {SWITCHES.map((s) => (
              <label key={s.key} className="owner-row">
                <input type="checkbox" checked={Boolean(config[s.key])} onChange={(e) => void saveConfig({ [s.key]: e.target.checked })} />
                {s.label}
              </label>
            ))}
            {TEXTS.map((t) => (
              <label key={t.key} className="owner-field">
                <span>{t.label}</span>
                <input
                  value={String(draft[t.key] ?? config[t.key] ?? "")}
                  onChange={(e) => setDraft((d) => ({ ...d, [t.key]: e.target.value }))}
                />
                <small className="muted">{t.hint}</small>
              </label>
            ))}
            <button
              className="text"
              disabled={Object.keys(draft).length === 0}
              onClick={() => {
                const patch: Record<string, string | null> = {};
                for (const [k, v] of Object.entries(draft)) patch[k] = k === "paused_message" ? String(v) : String(v).trim() || null;
                void saveConfig(patch as Partial<Config>);
              }}
            >
              Save
            </button>
          </>
        ) : (
          <p className="muted">Loading…</p>
        )}
      </section>

      <section className="owner-card">
        <h2>Problem reports (latest 50)</h2>
        {crashes.length === 0 && <p className="muted">None.</p>}
        {crashes.map((c) => (
          <details key={c.id} className="owner-crash">
            <summary>
              {when(c.created_at)} · {c.kind} · {emailOf.get(c.user_id) ?? "someone"} · {c.app_version} · {c.summary || c.note.slice(0, 80)}
            </summary>
            <p className="muted">
              {c.device} · Android {c.android}
            </p>
            {c.note && <p>{c.note}</p>}
            <pre>{c.details}</pre>
          </details>
        ))}
      </section>
    </div>
  );
}

function WarnLevel({ value, onSave }: { value: number; onSave: (v: number) => void }) {
  const [v, setV] = useState(String(value));
  return (
    <label className="owner-row">
      Warn me below $
      <input className="owner-num" value={v} inputMode="decimal" onChange={(e) => setV(e.target.value)} />
      <button className="text" disabled={!Number.isFinite(Number(v)) || Number(v) === value} onClick={() => onSave(Number(v))}>
        Save
      </button>
    </label>
  );
}

function CreateFriend({ onDone }: { onDone: () => void }) {
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState<string | null>(null);
  return (
    <section className="owner-card">
      <h2>Create a friend's account</h2>
      <p className="muted">Sign-ups stay closed. The account works at once; you send them the email and password.</p>
      <div className="owner-row">
        <input placeholder="Email" value={email} onChange={(e) => setEmail(e.target.value)} />
        <input placeholder="Password (8 or more characters)" value={password} onChange={(e) => setPassword(e.target.value)} />
        <button
          className="text"
          disabled={busy || !email || password.length < 8}
          onClick={() => {
            setBusy(true);
            setResult(null);
            callFunction<{ email: string }>("owner", { action: "create_friend", email, password })
              .then((r) => {
                setResult(`Created ${r.email}.`);
                setEmail("");
                setPassword("");
                onDone();
              })
              .catch((e: Error) => setResult(e.message))
              .finally(() => setBusy(false));
          }}
        >
          {busy ? "Creating…" : "Create"}
        </button>
      </div>
      {result && <p>{result}</p>}
    </section>
  );
}
