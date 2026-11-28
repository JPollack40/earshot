-- Phase 20 (C5): hand playback between the phone and the web player.
-- One row per person: which device holds playback, the episode and the position.
-- A device takes it (claim) when it starts playing; while playing it reports every few seconds.
-- A report from a device that no longer holds it changes nothing and hands back the holder,
-- so that device knows to pause. Writes go only through handoff().
-- Safe to run more than once.

create table if not exists public.now_playing (
  user_id uuid primary key default auth.uid() references auth.users (id) on delete cascade,
  device text not null,
  device_name text not null default '',
  episode_id text not null,
  position_ms bigint not null default 0,
  playing boolean not null default false,
  claimed_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

alter table public.now_playing enable row level security;

drop policy if exists now_playing_read_own on public.now_playing;
create policy now_playing_read_own on public.now_playing
  for select to authenticated using (user_id = auth.uid());

revoke all on public.now_playing from anon, authenticated;
grant select on public.now_playing to authenticated;

-- [p_claim] true: this device takes playback (it just started playing, or "Continue here").
-- false: a report; it only counts while this device holds playback (or nobody does yet).
-- Returns the row as it now is: if its device isn't the caller's, another device took over.
create or replace function public.handoff(
  p_device text,
  p_name text,
  p_episode text,
  p_position bigint,
  p_playing boolean,
  p_claim boolean default false
) returns public.now_playing
language plpgsql
security definer
set search_path = public
as $$
declare
  me uuid := auth.uid();
  r public.now_playing;
begin
  if me is null then
    raise exception 'not signed in' using errcode = '42501';
  end if;
  if coalesce(p_device, '') = '' or coalesce(p_episode, '') = '' then
    raise exception 'device and episode are required' using errcode = '22023';
  end if;

  if not p_claim then
    update public.now_playing
       set episode_id = p_episode,
           position_ms = greatest(coalesce(p_position, 0), 0),
           playing = coalesce(p_playing, false),
           device_name = coalesce(nullif(p_name, ''), device_name),
           updated_at = now()
     where user_id = me and device = p_device
    returning * into r;
    if found then
      return r;
    end if;
    select * into r from public.now_playing where user_id = me;
    if found then
      return r; -- another device holds playback
    end if;
  end if;

  insert into public.now_playing as n (user_id, device, device_name, episode_id, position_ms, playing, claimed_at, updated_at)
  values (me, p_device, coalesce(p_name, ''), p_episode, greatest(coalesce(p_position, 0), 0), coalesce(p_playing, false), now(), now())
  on conflict (user_id) do update
     set device = excluded.device,
         device_name = excluded.device_name,
         episode_id = excluded.episode_id,
         position_ms = excluded.position_ms,
         playing = excluded.playing,
         claimed_at = now(),
         updated_at = now()
  returning * into r;
  return r;
end;
$$;

revoke all on function public.handoff(text, text, text, bigint, boolean, boolean) from public, anon;
grant execute on function public.handoff(text, text, text, bigint, boolean, boolean) to authenticated;

-- The web player hears changes at once through Realtime (RLS still applies there).
do $$
begin
  if exists (select 1 from pg_publication where pubname = 'supabase_realtime')
     and not exists (select 1 from pg_publication_tables where pubname = 'supabase_realtime' and schemaname = 'public' and tablename = 'now_playing') then
    execute 'alter publication supabase_realtime add table public.now_playing';
  end if;
end $$;

notify pgrst, 'reload schema';
