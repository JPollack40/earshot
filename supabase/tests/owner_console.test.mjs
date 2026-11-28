// Phase 21 (D1, D2): owner console reads and the credit watch table (014), in PGlite.
// Run: cd supabase/tests && npm install && npm test
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { PGlite } from "@electric-sql/pglite";

const sql = (name) => readFileSync(new URL(`../migrations/${name}`, import.meta.url), "utf8");
const OWNER = "00000000-0000-0000-0000-00000000000a";
const FRIEND = "00000000-0000-0000-0000-00000000000b";

async function db() {
  const pg = new PGlite();
  await pg.exec(`
    create schema auth;
    create table auth.users (id uuid primary key, email text, created_at timestamptz default now(), last_sign_in_at timestamptz);
    insert into auth.users (id, email) values ('${OWNER}', 'owner@example.com'), ('${FRIEND}', 'friend@example.com');
    create function auth.uid() returns uuid language sql as $$ select nullif(current_setting('test.uid', true), '')::uuid $$;
    create role anon; create role authenticated; create role service_role;
    grant usage on schema public to authenticated, service_role;
  `);
  for (const f of ["001_sync.sql", "010_safe_updates.sql", "014_owner_console.sql"]) await pg.exec(sql(f));
  await pg.exec(`insert into public.app_owners (user_id) values ('${OWNER}')`);
  return pg;
}
const as = (pg, user) => pg.exec(`reset role; set test.uid = '${user}'; set role authenticated`);

test("devices report themselves; only the owner sees everyone", async () => {
  const pg = await db();
  await as(pg, FRIEND);
  await pg.exec(`insert into public.app_devices (device_id, app_version, version_code) values ('android:f1', '1.1.0', 1010099)`);
  await pg.exec(`insert into public.crash_reports (report_id, kind) values ('r1', 'crash')`);
  await assert.rejects(pg.query(`select public.owner_overview()`), /owners only/);
  await assert.rejects(pg.query(`select * from public.owner_crashes(10)`), /owners only/);
  assert.equal((await pg.query(`select public.is_owner() o`)).rows[0].o, false);

  await as(pg, OWNER);
  assert.equal((await pg.query(`select public.is_owner() o`)).rows[0].o, true);
  const o = (await pg.query(`select public.owner_overview() o`)).rows[0].o;
  assert.equal(o.devices_90_days, 1);
  assert.equal(o.device_cap, 20);
  const friend = o.people.find((p) => p.email === "friend@example.com");
  assert.equal(friend.devices[0].app_version, "1.1.0");
  assert.equal(friend.crashes_30_days, 1);
  assert.equal(o.people.find((p) => p.email === "owner@example.com").owner, true);
  assert.equal((await pg.query(`select count(*)::int n from public.owner_crashes(10)`)).rows[0].n, 1);
  // The owner's own device list stays limited to their own rows.
  assert.equal((await pg.query(`select count(*)::int n from public.app_devices`)).rows[0].n, 0);
});

test("only owners change the switches and read the credit watch", async () => {
  const pg = await db();
  await as(pg, FRIEND);
  await pg.exec(`update public.app_config set ai_on = false`);
  await pg.exec(`reset role`);
  assert.equal((await pg.query(`select ai_on from public.app_config`)).rows[0].ai_on, true, "friend's update matched no rows");
  await as(pg, FRIEND);
  assert.equal((await pg.query(`select count(*)::int n from public.usage_watch`)).rows[0].n, 0);
  await assert.rejects(pg.query(`select * from public.app_owners`));

  await as(pg, OWNER);
  await pg.exec(`update public.app_config set ai_on = false, banner = 'hello'`);
  await pg.exec(`update public.usage_watch set warn_below_usd = 15`);
  assert.equal((await pg.query(`select warn_below_usd::int w from public.usage_watch`)).rows[0].w, 15);
  await pg.exec(`reset role`);
  assert.deepEqual((await pg.query(`select ai_on, banner from public.app_config`)).rows[0], { ai_on: false, banner: "hello" });
});

test("the migration runs twice", async () => {
  const pg = await db();
  await pg.exec(sql("014_owner_console.sql"));
  assert.equal((await pg.query(`select count(*)::int n from public.usage_watch`)).rows[0].n, 1);
});
