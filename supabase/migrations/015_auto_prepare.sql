-- Earshot phase 21 (D4): auto-prepare pinned shows. Run once in SQL Editor after 014. Safe to run again.
--
-- When a new episode of a pinned show arrives, the new-episodes check (every 15 minutes) transcribes it
-- with Deepgram, then has the ai function find its ads and (without the feed's own) its chapters, so it
-- arrives with Skip ad ready. Episodes whose feed has its own transcript are left to the phone, which
-- reads that for free. OFF until switched on in the owner console.
--   app_config.auto_prepare_on           the switch (default off)
--   app_config.auto_prepare_hours        audio hours a month Deepgram may transcribe for this (default 10)
--   app_config.auto_prepare_owners_only  true (default): only the owners' pinned shows; false: everyone's
--   usage_watch.prepared_month / prepared_ms   what has been used this month

alter table public.app_config add column if not exists auto_prepare_on boolean not null default false;
alter table public.app_config add column if not exists auto_prepare_hours numeric not null default 10;
alter table public.app_config add column if not exists auto_prepare_owners_only boolean not null default true;

alter table public.usage_watch add column if not exists prepared_month date;
alter table public.usage_watch add column if not exists prepared_ms bigint not null default 0;
alter table public.usage_watch add column if not exists prepared_count integer not null default 0;

-- The new-episodes function (service role) reads episodes and transcripts and writes transcripts.
grant select on table public.episodes to service_role;
grant select, insert on table public.transcripts to service_role;
grant select on table public.episode_transcripts to service_role;

notify pgrst, 'reload schema';
