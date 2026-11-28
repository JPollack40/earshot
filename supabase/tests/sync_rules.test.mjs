// Phase 15: tests of the server's sync rules, in an in-memory Postgres (PGlite).
// Run: cd supabase/tests && npm install && npm test
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { PGlite } from "@electric-sql/pglite";

const sql = (name) => readFileSync(new URL(`../migrations/${name}`, import.meta.url), "utf8");
const USER = "00000000-0000-0000-0000-000000000001";

async function db() {
  const pg = new PGlite();
  // Just enough of Supabase for the migrations: auth.users, auth.uid() and the roles.
  await pg.exec(`
    create schema auth;
    create table auth.users (id uuid primary key);
    insert into auth.users values ('${USER}');
    create function auth.uid() returns uuid language sql as $$ select '${USER}'::uuid $$;
    create role anon; create role authenticated; create role service_role;
  `);
  for (const f of ["001_sync.sql", "003_automation_playlists.sql", "004_listening_upgrades.sql", "009_sync_safety.sql"]) {
    await pg.exec(sql(f));
  }
  return pg;
}

/** Upserts an episode the way the apps do (PostgREST merge-duplicates = insert ... on conflict do update). */
async function put(pg, row) {
  const r = { id: "ep1", feed_url: "https://f", guid: "g", audio_url: "https://a.mp3", position_ms: 0, played: false, in_inbox: false, queue_position: null, ...row };
  await pg.query(
    `insert into public.episodes (user_id, id, feed_url, guid, audio_url, position_ms, played, in_inbox, queue_position, changed_at)
     values ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10)
     on conflict (user_id, id) do update set position_ms = excluded.position_ms, played = excluded.played,
       in_inbox = excluded.in_inbox, queue_position = excluded.queue_position, changed_at = excluded.changed_at`,
    [USER, r.id, r.feed_url, r.guid, r.audio_url, r.position_ms, r.played, r.in_inbox, r.queue_position, r.changed_at],
  );
}
const get = async (pg, id = "ep1") => (await pg.query(`select * from public.episodes where id = $1`, [id])).rows[0];

test("a stale position save doesn't unplay a finished episode", async () => {
  const pg = await db();
  await put(pg, { position_ms: 600_000, queue_position: 0, changed_at: 1000 });
  await put(pg, { played: true, position_ms: 0, queue_position: null, changed_at: 2000 }); // finished on the phone
  await put(pg, { played: false, position_ms: 605_000, queue_position: 0, changed_at: 3000 }); // web tab that never heard
  const e = await get(pg);
  assert.equal(e.played, true);
  assert.equal(Number(e.position_ms), 0);
  assert.equal(e.queue_position, null);
  assert.equal(Number(e.changed_at), 3001, "1 ms past the stale write, so its sender pulls the correction");
});

test("Mark unplayed (position 0) still unplays", async () => {
  const pg = await db();
  await put(pg, { played: true, changed_at: 2000 });
  await put(pg, { played: false, position_ms: 0, changed_at: 3000 });
  const e = await get(pg);
  assert.equal(e.played, false);
  assert.equal(Number(e.changed_at), 3000);
});

test("an older write is still ignored (last change wins)", async () => {
  const pg = await db();
  await put(pg, { position_ms: 50_000, changed_at: 5000 });
  await put(pg, { position_ms: 10_000, changed_at: 4000 });
  assert.equal(Number((await get(pg)).position_ms), 50_000);
});

test("replaying a played episode keeps it played and saves the position", async () => {
  const pg = await db();
  await put(pg, { played: true, changed_at: 2000 });
  await put(pg, { played: true, position_ms: 90_000, changed_at: 3000 });
  const e = await get(pg);
  assert.equal(e.played, true);
  assert.equal(Number(e.position_ms), 90_000);
});

test("queue next from the server moves rows by 1 ms past their own time", async () => {
  const pg = await db();
  await pg.query(`insert into public.podcasts (user_id, feed_url, title, subscribed, new_episode_action, changed_at) values ($1, 'https://f', 'Show', true, 'queue_next', 1)`, [USER]);
  await put(pg, { id: "a", audio_url: "https://a1.mp3", queue_position: 0, changed_at: 1_000 });
  await put(pg, { id: "b", audio_url: "https://b1.mp3", queue_position: 1, changed_at: 2_000 });
  const items = [{ feed_url: "https://f", id: "new", guid: "n", title: "New one", audio_url: "https://n.mp3", duration_ms: 60_000, pub_date: 1 }];
  await pg.query(`select * from public.new_episodes_insert($1::jsonb)`, [JSON.stringify(items)]);
  const b = await get(pg, "b");
  const n = await get(pg, "new");
  assert.equal(Number(b.queue_position), 2);
  assert.equal(Number(b.changed_at), 2_001);
  assert.equal(Number(n.queue_position), 1);
  assert.equal(Number((await get(pg, "a")).queue_position), 0);
});
