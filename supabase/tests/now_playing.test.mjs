// Phase 20 (C5): handing playback between devices (013), in an in-memory Postgres (PGlite).
// Run: cd supabase/tests && npm install && npm test
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { PGlite } from "@electric-sql/pglite";

const sql = (name) => readFileSync(new URL(`../migrations/${name}`, import.meta.url), "utf8");
const A = "00000000-0000-0000-0000-00000000000a";
const B = "00000000-0000-0000-0000-00000000000b";

async function db() {
  const pg = new PGlite();
  await pg.exec(`
    create schema auth;
    create table auth.users (id uuid primary key);
    insert into auth.users values ('${A}'), ('${B}');
    create function auth.uid() returns uuid language sql as $$ select nullif(current_setting('test.uid', true), '')::uuid $$;
    create role anon; create role authenticated; create role service_role;
    grant usage on schema public to authenticated;
  `);
  await pg.exec(sql("013_now_playing.sql"));
  return pg;
}

const as = (pg, user) => pg.exec(`reset role; set test.uid = '${user}'; set role authenticated`);
const hand = async (pg, device, episode, pos, playing, claim) =>
  (await pg.query(`select device, episode_id, position_ms::int pos, playing from public.handoff($1, 'name', $2, $3, $4, $5)`, [device, episode, pos, playing, claim])).rows[0];

test("the first report takes playback; later reports move the position", async () => {
  const pg = await db();
  await as(pg, A);
  assert.deepEqual(await hand(pg, "phone", "ep1", 1000, true, false), { device: "phone", episode_id: "ep1", pos: 1000, playing: true });
  assert.deepEqual(await hand(pg, "phone", "ep1", 11000, true, false), { device: "phone", episode_id: "ep1", pos: 11000, playing: true });
});

test("a claim takes over; the old device's report changes nothing and sees the new holder", async () => {
  const pg = await db();
  await as(pg, A);
  await hand(pg, "phone", "ep1", 5000, true, true);
  assert.equal((await hand(pg, "web", "ep1", 5000, true, true)).device, "web");
  const back = await hand(pg, "phone", "ep1", 15000, true, false);
  assert.deepEqual(back, { device: "web", episode_id: "ep1", pos: 5000, playing: true }, "phone learns it should pause");
  // The phone starts playing again: it takes it back.
  assert.equal((await hand(pg, "phone", "ep2", 0, true, true)).device, "phone");
});

test("each person only sees and changes their own row", async () => {
  const pg = await db();
  await as(pg, A);
  await hand(pg, "phone", "epA", 1, true, true);
  await as(pg, B);
  assert.equal((await pg.query(`select count(*)::int n from public.now_playing`)).rows[0].n, 0);
  await hand(pg, "web", "epB", 2, false, false);
  assert.deepEqual((await pg.query(`select episode_id from public.now_playing`)).rows, [{ episode_id: "epB" }]);
  await assert.rejects(pg.query(`insert into public.now_playing (user_id, device, episode_id) values ('${A}', 'x', 'y')`));
  await assert.rejects(pg.query(`update public.now_playing set episode_id = 'z'`));
  await as(pg, A);
  assert.deepEqual((await pg.query(`select episode_id from public.now_playing`)).rows, [{ episode_id: "epA" }]);
});

test("signed out callers are refused, and the migration can run twice", async () => {
  const pg = await db();
  await pg.exec(`reset role; set test.uid = ''; set role authenticated`);
  await assert.rejects(hand(pg, "phone", "ep1", 0, true, true), /not signed in/);
  await pg.exec(`reset role`);
  await pg.exec(sql("013_now_playing.sql"));
});
