-- Earshot phase 16 (v1.1): kill switch and crash reports. Run once in the Supabase dashboard after
-- 001-009: SQL Editor > New query > paste > Run. Safe to run again. Nothing existing is changed or removed.
--
-- 1. app_config     ONE row (id = 1) that Serena edits in Table Editor > app_config. The phone and the web
--                   read it at start and at most hourly, and keep the last copy they got. A failed read
--                   never turns anything off. The ai, transcribe and browse functions read it too, so a
--                   switch also works on phones that haven't updated.
--      min_supported_version  e.g. '1.1.0': older release builds show "Update needed" (empty = no minimum)
--      hold_version           e.g. '1.1.0': the update check offers nothing newer (pull a bad release)
--      ai_on                  summaries, generated chapters, Ask, bookmark titles (Gemini)
--      ad_finding_on          Find ads (Gemini)
--      transcribe_on          Transcribe with Deepgram
--      browse_ai_on           "You might like" in Browse (Gemini)
--      paused_message         what a switched-off feature says instead
--      banner                 a short note shown at the top of the phone app and the web (empty = none)
--      gemini_model, gemini_fallback_model   (used from phase 16 PR C) override the functions' secrets
-- 2. crash_reports  crashes, "why did it stop" reasons (ANRs, low-memory kills) and Report a problem, sent
--                   by the phone. Each person can add and read only their own; Serena reads them all in
--                   Table Editor > crash_reports (the dashboard isn't limited by row-level security).
--
-- Since 2026-10-30 new tables need explicit GRANTs to be visible to the Data API, so each has them here.

create table if not exists public.app_config (
  id smallint primary key default 1 check (id = 1),
  min_supported_version text,
  hold_version text,
  ai_on boolean not null default true,
  ad_finding_on boolean not null default true,
  transcribe_on boolean not null default true,
  browse_ai_on boolean not null default true,
  paused_message text not null default 'Paused by Serena for now. It will be back soon.',
  banner text,
  gemini_model text,
  gemini_fallback_model text,
  updated_at timestamptz not null default now()
);

insert into public.app_config (id) values (1) on conflict (id) do nothing;

alter table public.app_config enable row level security;
grant select on table public.app_config to anon, authenticated;
drop policy if exists "anyone reads app config" on public.app_config;
create policy "anyone reads app config" on public.app_config for select to anon, authenticated using (true);

create or replace function public.app_config_touch() returns trigger
language plpgsql
set search_path = ''
as $$
begin
  new.updated_at := now();
  return new;
end;
$$;

drop trigger if exists app_config_touch on public.app_config;
create trigger app_config_touch before update on public.app_config
  for each row execute function public.app_config_touch();

create table if not exists public.crash_reports (
  id bigint generated always as identity primary key,
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  report_id text not null,
  created_at timestamptz not null default now(),
  happened_at timestamptz,
  kind text not null check (kind in ('crash', 'anr', 'killed', 'report')),
  app_version text not null default '',
  version_code integer not null default 0,
  build_type text not null default '',
  device text not null default '',
  android text not null default '',
  summary text not null default '' check (length(summary) <= 1000),
  details text not null default '' check (length(details) <= 100000),
  log text not null default '' check (length(log) <= 300000),
  note text not null default '' check (length(note) <= 5000),
  unique (user_id, report_id)
);

create index if not exists crash_reports_created on public.crash_reports (created_at desc);

alter table public.crash_reports enable row level security;
grant select, insert on table public.crash_reports to authenticated;
drop policy if exists "own crash reports read" on public.crash_reports;
create policy "own crash reports read" on public.crash_reports for select to authenticated
  using (user_id = (select auth.uid()));
drop policy if exists "own crash reports add" on public.crash_reports;
create policy "own crash reports add" on public.crash_reports for insert to authenticated
  with check (user_id = (select auth.uid()));

-- Make the Data API see the new tables straight away.
notify pgrst, 'reload schema';
