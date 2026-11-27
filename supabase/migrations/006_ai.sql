-- Earshot phase 11: AI from transcripts. Run once in the Supabase dashboard after 001-004 (005 is
-- independent): SQL Editor > New query > paste > Run. Safe to run again. Nothing existing changes.
--
-- One row per episode and kind, made on request by the "ai" edge function (Gemini) from the
-- episode's saved transcript and shared by the phone, the web and Android Auto:
--   kind 'chapters': data = { "chapters": [{ "start": ms, "title": text }] }   (labelled "generated")
--   kind 'summary':  data = { "summary": text, "points": [{ "at": ms, "text": text }] }
--   kind 'ads':      data = { "ads": [{ "start": ms, "end": ms, "label": text }] }
-- model: which model made it. The ad-skip setting (Auto / Manual) rides in settings.playback.adSkip.

create table if not exists public.episode_ai (
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  episode_id text not null,
  kind text not null check (kind in ('chapters', 'summary', 'ads')),
  data jsonb not null default '{}'::jsonb,
  model text not null default '',
  created_at bigint not null default 0,
  changed_at bigint not null default 0,
  rev bigint not null default 0,
  primary key (user_id, episode_id, kind)
);

create index if not exists episode_ai_rev on public.episode_ai (user_id, rev);

drop trigger if exists episode_ai_stamp on public.episode_ai;
create trigger episode_ai_stamp before insert or update on public.episode_ai
  for each row execute function public.sync_stamp();

alter table public.episode_ai enable row level security;
revoke all on table public.episode_ai from anon, authenticated;
grant select, insert, update, delete on table public.episode_ai to authenticated;

drop policy if exists "own ai" on public.episode_ai;
create policy "own ai" on public.episode_ai for all to authenticated
  using ((select auth.uid()) = user_id) with check ((select auth.uid()) = user_id);
