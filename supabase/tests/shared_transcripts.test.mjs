// Phase 18-pre: one shared transcript per episode (011, 012), in an in-memory Postgres (PGlite).
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
  // auth.uid() follows a setting, so each test can act as either person.
  await pg.exec(`
    create schema auth;
    create table auth.users (id uuid primary key);
    insert into auth.users values ('${A}'), ('${B}');
    create function auth.uid() returns uuid language sql as $$ select nullif(current_setting('test.uid', true), '')::uuid $$;
    create role anon; create role authenticated; create role service_role;
  `);
  for (const f of ["001_sync.sql", "002_transcripts_bookmarks.sql"]) await pg.exec(sql(f));
  return pg;
}

const segs = (n, word = "hello") => JSON.stringify(Array.from({ length: n }, (_, i) => ({ s: i * 1000, e: i * 1000 + 900, t: `${word} ${i}` })));

async function save(pg, user, episode, source, n, changedAt, word) {
  await pg.query(
    `insert into public.transcripts (user_id, episode_id, source, timed, segments, changed_at) values ($1,$2,$3,true,$4::jsonb,$5)
     on conflict (user_id, episode_id) do update set source = excluded.source, segments = excluded.segments, changed_at = excluded.changed_at`,
    [user, episode, source, segs(n, word), changedAt],
  );
}
const shared = async (pg, ep) => (await pg.query(`select source, jsonb_array_length(segments) n from public.episode_transcripts where episode_id = $1`, [ep])).rows[0];
const own = async (pg, user, ep) => (await pg.query(`select jsonb_array_length(segments) n, rev from public.transcripts where user_id = $1 and episode_id = $2`, [user, ep])).rows[0];

test("011 backfills the best existing copy per episode", async () => {
  const pg = await db();
  await save(pg, A, "ep1", "gemini", 50, 1000);
  await save(pg, B, "ep1", "deepgram", 40, 900);
  await save(pg, A, "ep2", "feed", 10, 1000);
  await pg.exec(sql("011_shared_transcripts.sql"));
  assert.deepEqual(await shared(pg, "ep1"), { source: "deepgram", n: 40 });
  assert.deepEqual(await shared(pg, "ep2"), { source: "feed", n: 10 });
  // Private copies are untouched by 011.
  assert.equal((await own(pg, A, "ep1")).n, 50);
});

test("a better save replaces the shared copy, a worse one doesn't", async () => {
  const pg = await db();
  await pg.exec(sql("011_shared_transcripts.sql"));
  await save(pg, A, "ep1", "feed", 20, 1000);
  assert.deepEqual(await shared(pg, "ep1"), { source: "feed", n: 20 });
  await save(pg, B, "ep1", "gemini", 99, 2000);
  assert.deepEqual(await shared(pg, "ep1"), { source: "feed", n: 20 }, "Gemini doesn't beat the feed");
  await save(pg, B, "ep1", "deepgram", 30, 3000);
  assert.deepEqual(await shared(pg, "ep1"), { source: "deepgram", n: 30 });
});

test("a stale save (older changed_at) doesn't touch the shared copy", async () => {
  const pg = await db();
  await pg.exec(sql("011_shared_transcripts.sql"));
  await save(pg, A, "ep1", "feed", 20, 5000);
  await save(pg, A, "ep1", "deepgram", 30, 4000); // older: the stamp trigger keeps the stored row
  assert.deepEqual(await shared(pg, "ep1"), { source: "feed", n: 20 });
});

test("search finds text through the shared copy once the private copy is emptied (012)", async () => {
  const pg = await db();
  await pg.exec(sql("011_shared_transcripts.sql"));
  await save(pg, A, "ep1", "deepgram", 5, 1000, "octopus");
  const revBefore = (await own(pg, A, "ep1")).rev;
  await pg.exec(sql("012_drop_private_transcripts.sql"));
  const mine = await own(pg, A, "ep1");
  assert.equal(mine.n, 0, "private copy emptied");
  assert.equal(String(mine.rev), String(revBefore), "no new rev, so nothing re-syncs");
  await pg.exec(`set test.uid = '${A}'`);
  const hits = (await pg.query(`select * from public.search_transcripts(array['octopus 3'], 10)`)).rows;
  assert.equal(hits.length, 1);
  assert.equal(Number(hits[0].start_ms), 3000);
  // After 012 a new save keeps only the pointer.
  await save(pg, A, "ep2", "feed", 4, 2000);
  assert.equal((await own(pg, A, "ep2")).n, 0);
  assert.deepEqual(await shared(pg, "ep2"), { source: "feed", n: 4 });
});

test("a person reads a shared copy only for episodes they have a row for", async () => {
  const pg = await db();
  await pg.exec(sql("011_shared_transcripts.sql"));
  await save(pg, A, "ep1", "feed", 3, 1000);
  await pg.exec(`grant usage on schema public to authenticated; set test.uid = '${B}'; set role authenticated`);
  assert.equal((await pg.query(`select * from public.episode_transcripts`)).rows.length, 0, "B has no row for ep1");
  await pg.exec(`reset role; set test.uid = '${A}'; set role authenticated`);
  assert.equal((await pg.query(`select * from public.episode_transcripts`)).rows.length, 1);
});
