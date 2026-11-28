-- Earshot phase 18-pre, step 2. Run ONLY after 011, after a database backup, and once every phone and the
-- web player run a build from phase 18-pre or later (they read the shared copy when their own row is empty).
-- SQL Editor > New query > paste > Run. Safe to run again.
--
-- 1. From now on a person's saved transcript is moved into the shared copy, and their own row keeps only
--    the pointer (source, timed, changed_at, rev) with empty segments.
-- 2. The existing private copies are emptied where the shared copy has the episode. This doesn't change
--    changed_at or rev, so no device downloads anything again.

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
    new.segments := '[]'::jsonb; -- step 2: the shared copy holds the text
  end if;
  return new;
end;
$$;
revoke all on function public.transcripts_share() from public, anon, authenticated;

-- Empty the existing private copies without the triggers (no new rev, nothing re-synced).
alter table public.transcripts disable trigger transcripts_stamp;
alter table public.transcripts disable trigger transcripts_zshare;
update public.transcripts t set segments = '[]'::jsonb
where jsonb_array_length(t.segments) > 0
  and exists (select 1 from public.episode_transcripts e where e.episode_id = t.episode_id);
alter table public.transcripts enable trigger transcripts_stamp;
alter table public.transcripts enable trigger transcripts_zshare;

notify pgrst, 'reload schema';
