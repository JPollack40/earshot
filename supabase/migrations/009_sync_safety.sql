-- Earshot phase 15: sync safety. Run once in the Supabase dashboard after 001-008:
-- SQL Editor > New query > paste > Run. Safe to run again. Nothing existing is removed.
--
-- 1. episodes_keep_played   Played never goes back to unplayed by accident. A device that hasn't heard
--    yet that an episode was finished (for example the web player in a background tab, which doesn't
--    pull) still uploads its play position with played = false. Such a write now keeps the episode
--    played (and out of the queue and New Episodes). Only a deliberate "Mark unplayed", which always
--    sets the position to 0 on the phone and the web, unplays it. The kept row gets a time 1 ms past
--    the write's, so the device that sent it takes the correction at its next sync.
-- 2. new_episodes_insert    "Queue next" no longer stamps the server's clock on the whole queue.
-- 3. episodes_audio         index for the audio-address check the 15-minute job makes per new episode.

create or replace function public.episodes_keep_played() returns trigger
language plpgsql
set search_path = ''
as $$
begin
  if old.played and not new.played and new.position_ms > 0 and new.changed_at >= old.changed_at then
    new.played := true;
    new.position_ms := old.position_ms;
    new.queue_position := old.queue_position;
    new.in_inbox := old.in_inbox;
    new.changed_at := new.changed_at + 1;
  end if;
  return new;
end;
$$;

-- Triggers of the same kind fire in name order, so this runs before episodes_stamp (001).
drop trigger if exists episodes_keep_played on public.episodes;
create trigger episodes_keep_played before update on public.episodes
  for each row execute function public.episodes_keep_played();

create or replace function public.new_episodes_insert(items jsonb)
returns table (user_id uuid, episode_id text, title text, podcast_title text, image_url text, action text, pinned boolean)
language plpgsql
security definer
set search_path = ''
as $$
#variable_conflict use_column
declare
  it jsonb;
  p record;
  eid text;
  act text;
  pos bigint;
  now_ms bigint := (extract(epoch from clock_timestamp()) * 1000)::bigint;
begin
  for it in select * from jsonb_array_elements(items) loop
    for p in
      select pc.user_id, pc.feed_url, pc.title as ptitle, pc.image_url as pimage, pc.pinned,
             pc.new_episode_action, pc.include_words, pc.exclude_words, pc.min_minutes, pc.max_minutes
      from public.podcasts pc
      where pc.subscribed and pc.feed_url = it->>'feed_url'
    loop
      eid := it->>'id';
      if exists (
        select 1 from public.episodes e
        where e.user_id = p.user_id and (e.id = eid or e.audio_url = it->>'audio_url')
      ) then
        continue;
      end if;
      if not public.new_episode_passes(it->>'title', (it->>'duration_ms')::bigint, p.include_words, p.exclude_words, p.min_minutes, p.max_minutes) then
        act := 'skip';
      else
        act := coalesce(p.new_episode_action, 'inbox');
      end if;
      pos := null;
      if act = 'queue_last' then
        select coalesce(max(e.queue_position) + 1, 0) into pos from public.episodes e
          where e.user_id = p.user_id and e.queue_position is not null;
      elsif act = 'queue_next' then
        -- Second place (after what's playing); everything from there moves down one.
        select case when count(*) = 0 then 0 else 1 end into pos from public.episodes e
          where e.user_id = p.user_id and e.queue_position is not null;
        -- Phase 15: each moved row gets 1 ms past its own time (not the server's clock), so a phone
        -- whose clock runs a little behind still has its next position saves accepted.
        update public.episodes e set queue_position = e.queue_position + 1, changed_at = e.changed_at + 1
          where e.user_id = p.user_id and e.queue_position is not null and e.queue_position >= pos;
      end if;
      insert into public.episodes (
        user_id, id, feed_url, guid, title, description, pub_date, audio_url, mime_type, duration_ms,
        image_url, chapters_url, transcript_url, transcript_type, season, episode_no,
        in_inbox, queue_position, changed_at
      ) values (
        p.user_id, eid, p.feed_url, it->>'guid', coalesce(it->>'title', ''), left(coalesce(it->>'description', ''), 20000),
        coalesce((it->>'pub_date')::bigint, 0), it->>'audio_url', it->>'mime_type', coalesce((it->>'duration_ms')::bigint, 0),
        it->>'image_url', it->>'chapters_url', it->>'transcript_url', it->>'transcript_type',
        (it->>'season')::int, (it->>'episode_no')::double precision,
        act = 'inbox', pos, now_ms
      );
      user_id := p.user_id;
      episode_id := eid;
      title := it->>'title';
      podcast_title := p.ptitle;
      image_url := coalesce(it->>'image_url', p.pimage);
      action := act;
      pinned := p.pinned;
      return next;
    end loop;
  end loop;
end;
$$;

revoke execute on function public.new_episodes_insert(jsonb) from public, anon, authenticated;
grant execute on function public.new_episodes_insert(jsonb) to service_role;

create index if not exists episodes_audio on public.episodes (user_id, audio_url);
