-- Earshot phase 18-pre: one shared copy of each transcript (option 2 of TRANSCRIPT_STORAGE_PLAN.md).
-- Run once in the Supabase dashboard after 001-010: SQL Editor > New query > paste > Run. Safe to run again.
-- Nothing existing is removed: every person's own row keeps its segments for now, so builds from before
-- phase 18-pre keep working. 012 (later, once every device runs the new build) empties the private copies.
--
-- 1. episode_transcripts   one row per episode for everyone, the best copy anyone has saved: Deepgram, then
--                          the podcast's own feed transcript, then the early Gemini ones; then timed; then
--                          the one with more segments. Filled by a trigger, never written by apps directly.
--                          A person can read a shared row only for an episode they have a transcript row for.
-- 2. transcripts_zshare    the trigger: every accepted save of a person's transcript updates the shared copy.
--                          A save older than the stored row (which transcripts_stamp throws away) changes
--                          nothing.
-- 3. backfill              the shared copies, from the transcripts already saved.
-- 4. search_transcripts    reads the shared copy when a person's own row is empty (after 012).
-- Found ads stay per person (episode_ai): inserted ads can shift timings between downloads.

create table if not exists public.episode_transcripts (
  episode_id text primary key,
  source text not null,
  timed boolean not null default true,
  segments jsonb not null default '[]'::jsonb,
  updated_at bigint not null default 0
);

alter table public.episode_transcripts enable row level security;
revoke all on table public.episode_transcripts from anon, authenticated;
grant select on table public.episode_transcripts to authenticated;

drop policy if exists "shared transcripts of my episodes" on public.episode_transcripts;
create policy "shared transcripts of my episodes" on public.episode_transcripts for select to authenticated
  using (exists (
    select 1 from public.transcripts t
    where t.user_id = (select auth.uid()) and t.episode_id = episode_transcripts.episode_id
  ));

create or replace function public.transcript_rank(source text, timed boolean) returns int
language sql immutable
set search_path = ''
as $$
  select (case source when 'deepgram' then 30 when 'feed' then 20 when 'gemini' then 10 else 0 end) + (case when timed then 1 else 0 end)
$$;

-- security definer: the trigger writes the shared table, which people can't write themselves.
create or replace function public.transcripts_share() returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
  -- With insert ... on conflict (how the apps save), BEFORE INSERT triggers see the proposed row even when it
  -- then becomes an update. So: an insert over an existing row is left to the BEFORE UPDATE call, and an
  -- update older than the stored row (which transcripts_stamp throws away) changes nothing.
  if tg_op = 'INSERT' then
    if exists (select 1 from public.transcripts t where t.user_id = new.user_id and t.episode_id = new.episode_id) then
      return new;
    end if;
  elsif new.changed_at < old.changed_at then
    return new;
  end if;
  if jsonb_typeof(new.segments) = 'array' and jsonb_array_length(new.segments) > 0 then
    insert into public.episode_transcripts as e (episode_id, source, timed, segments, updated_at)
    values (new.episode_id, new.source, new.timed, new.segments, new.changed_at)
    on conflict (episode_id) do update
      set source = excluded.source, timed = excluded.timed, segments = excluded.segments, updated_at = excluded.updated_at
      where public.transcript_rank(excluded.source, excluded.timed) > public.transcript_rank(e.source, e.timed)
         or (public.transcript_rank(excluded.source, excluded.timed) = public.transcript_rank(e.source, e.timed)
             and jsonb_array_length(excluded.segments) > jsonb_array_length(e.segments));
  end if;
  return new;
end;
$$;
revoke all on function public.transcripts_share() from public, anon, authenticated;

drop trigger if exists transcripts_zshare on public.transcripts;
create trigger transcripts_zshare before insert or update on public.transcripts
  for each row execute function public.transcripts_share();

-- Backfill: the best existing copy of each episode.
insert into public.episode_transcripts (episode_id, source, timed, segments, updated_at)
select distinct on (t.episode_id) t.episode_id, t.source, t.timed, t.segments, t.changed_at
from public.transcripts t
where jsonb_typeof(t.segments) = 'array' and jsonb_array_length(t.segments) > 0
order by t.episode_id, public.transcript_rank(t.source, t.timed) desc, jsonb_array_length(t.segments) desc, t.changed_at desc
on conflict (episode_id) do nothing;

-- Search: as in 002, but a person's empty row (after 012) is searched through the shared copy.
create or replace function public.search_transcripts(terms text[], max_results int default 300)
returns table (episode_id text, start_ms bigint, text text)
language sql
stable
security invoker
set search_path = ''
as $$
  with mine as (
    select t.episode_id, t.changed_at,
      case when jsonb_array_length(t.segments) > 0 then t.segments else coalesce(e.segments, '[]'::jsonb) end as segments
    from public.transcripts t
    left join public.episode_transcripts e on e.episode_id = t.episode_id
    where t.user_id = (select auth.uid())
  ),
  candidates as (
    -- Whole transcript first (segments joined by spaces), so most transcripts are ruled out quickly.
    select m.episode_id, m.changed_at, m.segments
    from mine m
    where cardinality(terms) > 0
      and (
        select bool_and(strpos(translate(lower(replace(jsonb_path_query_array(m.segments, '$[*].t')::text, '", "', ' ')), '’‘“”', '''''""'), term) > 0)
        from unnest(terms) as term
      )
  ),
  segs as (
    select c.episode_id, c.changed_at, (x.seg ->> 's')::bigint as s, x.seg ->> 't' as t,
      translate(lower(x.seg ->> 't'), '’‘“”', '''''""') as cur,
      coalesce(lead(translate(lower(x.seg ->> 't'), '’‘“”', '''''""')) over (partition by c.episode_id order by x.n), '') as nxt
    from candidates c
    cross join lateral jsonb_array_elements(c.segments) with ordinality as x(seg, n)
  )
  select s2.episode_id, s2.s, s2.t
  from segs s2
  where (select bool_and(strpos(s2.cur || ' ' || s2.nxt, term) > 0) from unnest(terms) as term)
    and (select bool_or(strpos(s2.cur || ' ' || s2.nxt, term) between 1 and length(s2.cur)) from unnest(terms) as term)
  order by s2.changed_at desc, s2.episode_id, s2.s
  limit max_results;
$$;
revoke all on function public.search_transcripts(text[], int) from public, anon;
grant execute on function public.search_transcripts(text[], int) to authenticated;

notify pgrst, 'reload schema';
