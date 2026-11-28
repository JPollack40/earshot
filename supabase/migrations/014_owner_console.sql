-- Earshot phase 21 (D1, D2): the owner console and the credit watch.
-- Run once in SQL Editor after 013. Safe to run again. Nothing existing is changed or removed.
--
-- 1. app_owners     who may open the owner console (Serena). Add yourself once, in SQL Editor:
--                     insert into public.app_owners (user_id) select id from auth.users where email = 'you@example.com';
--                   No app can read or change this table.
-- 2. app_devices    each phone reports its app version, Android version and last sync (about hourly),
--                   so the console can show friends' versions and the device count against the 20 allowed.
-- 3. usage_watch    the credit watch: Deepgram credit left, checked every 6 hours by the new-episodes
--                   function (service role), with a push to the owners' phones below the warning level.
--                   Only owners read it.
-- 4. Owners may change app_config (the phase 16 switches) from the console.
-- 5. owner_overview() and owner_crashes(): the console's reads, refused for anyone but an owner.

create table if not exists public.app_owners (
  user_id uuid primary key references auth.users (id) on delete cascade,
  added_at timestamptz not null default now()
);
alter table public.app_owners enable row level security;
revoke all on table public.app_owners from anon, authenticated;
grant select on table public.app_owners to service_role; -- new-episodes sends the credit warning to owners

create or replace function public.is_owner() returns boolean
language sql
stable
security definer
set search_path = ''
as $$
  select exists (select 1 from public.app_owners where user_id = (select auth.uid()));
$$;
revoke all on function public.is_owner() from public, anon;
grant execute on function public.is_owner() to authenticated;

create table if not exists public.app_devices (
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  device_id text not null,
  app_version text not null default '',
  version_code integer not null default 0,
  build_type text not null default '',
  device text not null default '',
  android text not null default '',
  last_sync_at timestamptz,
  last_seen_at timestamptz not null default now(),
  primary key (user_id, device_id)
);
alter table public.app_devices enable row level security;
revoke all on table public.app_devices from anon, authenticated;
grant select, insert, update on table public.app_devices to authenticated;
drop policy if exists "own app devices" on public.app_devices;
create policy "own app devices" on public.app_devices for all to authenticated
  using ((select auth.uid()) = user_id) with check ((select auth.uid()) = user_id);

create table if not exists public.usage_watch (
  id smallint primary key default 1 check (id = 1),
  deepgram_balance_usd numeric,
  deepgram_checked_at timestamptz,
  deepgram_error text,
  warn_below_usd numeric not null default 20,
  warned_at timestamptz,
  updated_at timestamptz not null default now()
);
insert into public.usage_watch (id) values (1) on conflict (id) do nothing;
alter table public.usage_watch enable row level security;
revoke all on table public.usage_watch from anon, authenticated;
grant select, update on table public.usage_watch to authenticated;
grant select, insert, update on table public.usage_watch to service_role;
drop policy if exists "owners read usage" on public.usage_watch;
create policy "owners read usage" on public.usage_watch for select to authenticated using ((select public.is_owner()));
drop policy if exists "owners set the warning level" on public.usage_watch;
create policy "owners set the warning level" on public.usage_watch for update to authenticated
  using ((select public.is_owner())) with check ((select public.is_owner()));

-- The phase 16 switches, from the console.
grant update on table public.app_config to authenticated;
drop policy if exists "owners change app config" on public.app_config;
create policy "owners change app config" on public.app_config for update to authenticated
  using ((select public.is_owner())) with check ((select public.is_owner()));

-- Everyone's accounts, devices and recent problems, for the console.
create or replace function public.owner_overview() returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  result jsonb;
begin
  if not public.is_owner() then
    raise exception 'owners only' using errcode = '42501';
  end if;
  select jsonb_build_object(
    'people', coalesce(jsonb_agg(p order by p ->> 'email'), '[]'::jsonb),
    'devices_90_days', (select count(*) from public.app_devices where last_seen_at > now() - interval '90 days'),
    'device_cap', 20
  ) into result
  from (
    select jsonb_build_object(
      'user_id', u.id,
      'email', u.email,
      'created_at', u.created_at,
      'last_sign_in_at', u.last_sign_in_at,
      'owner', exists (select 1 from public.app_owners o where o.user_id = u.id),
      'podcasts', (select count(*) from public.podcasts x where x.user_id = u.id and x.subscribed),
      'crashes_30_days', (select count(*) from public.crash_reports c where c.user_id = u.id and c.created_at > now() - interval '30 days'),
      'devices', coalesce((
        select jsonb_agg(jsonb_build_object(
          'device_id', d.device_id, 'app_version', d.app_version, 'version_code', d.version_code,
          'build_type', d.build_type, 'device', d.device, 'android', d.android,
          'last_sync_at', d.last_sync_at, 'last_seen_at', d.last_seen_at
        ) order by d.last_seen_at desc)
        from public.app_devices d where d.user_id = u.id
      ), '[]'::jsonb)
    ) as p
    from auth.users u
  ) people;
  return result;
end;
$$;
revoke all on function public.owner_overview() from public, anon;
grant execute on function public.owner_overview() to authenticated;

create or replace function public.owner_crashes(p_limit integer default 50) returns setof public.crash_reports
language plpgsql
stable
security definer
set search_path = ''
as $$
begin
  if not public.is_owner() then
    raise exception 'owners only' using errcode = '42501';
  end if;
  return query select * from public.crash_reports order by created_at desc limit least(greatest(p_limit, 1), 200);
end;
$$;
revoke all on function public.owner_crashes(integer) from public, anon;
grant execute on function public.owner_crashes(integer) to authenticated;

notify pgrst, 'reload schema';
