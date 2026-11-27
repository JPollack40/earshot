-- Earshot phase 10: run the "new-episodes" edge function every 15 minutes.
-- Run once AFTER 004 and after deploying the function (see supabase/functions/new-episodes/index.ts).
-- Safe to run again (it replaces the schedule). To stop it: select cron.unschedule('earshot-new-episodes');
--
-- BEFORE running, replace the two placeholders below:
--   <PROJECT_URL>   https://drdpgrdewxcquswuuzfw.supabase.co
--   <CRON_SECRET>   the same random text you saved as the function secret NEW_EPISODES_SECRET
-- Both are kept in Supabase Vault (encrypted), not in the schedule's text. The file stops with an error
-- if they weren't replaced. To fix values later without re-running it:
--   select vault.update_secret((select id from vault.secrets where name = 'earshot_project_url'), 'https://...');
--   select vault.update_secret((select id from vault.secrets where name = 'earshot_new_episodes_secret'), '...');

create extension if not exists pg_cron;
create extension if not exists pg_net;

-- Vault entries (replaced if they already exist).
do $$
declare
  -- The two values to fill in (each appears only here):
  project_url text := '<PROJECT_URL>';
  cron_secret text := '<CRON_SECRET>';
begin
  -- Stop if they weren't filled in (the schedule would call "<PROJECT_URL>/functions/...").
  if project_url not like 'https://%' then
    raise exception 'Put your project URL (https://...supabase.co) in place of the PROJECT_URL placeholder first';
  end if;
  if left(cron_secret, 1) = '<' or length(cron_secret) < 8 then
    raise exception 'Put your NEW_EPISODES_SECRET in place of the CRON_SECRET placeholder first';
  end if;
  delete from vault.secrets where name in ('earshot_project_url', 'earshot_new_episodes_secret');
  perform vault.create_secret(rtrim(project_url, '/'), 'earshot_project_url');
  perform vault.create_secret(cron_secret, 'earshot_new_episodes_secret');
end;
$$;

-- What the schedule runs. Also handy by hand: `select public.run_new_episode_check();` checks now
-- (the function's answer shows a few seconds later in: select * from net._http_response order by id desc limit 1;).
create or replace function public.run_new_episode_check() returns bigint
language sql
security definer
set search_path = ''
as $$
  select net.http_post(
    url := (select decrypted_secret from vault.decrypted_secrets where name = 'earshot_project_url') || '/functions/v1/new-episodes',
    headers := jsonb_build_object(
      'Content-Type', 'application/json',
      'x-earshot-cron', (select decrypted_secret from vault.decrypted_secrets where name = 'earshot_new_episodes_secret')
    ),
    body := '{}'::jsonb,
    timeout_milliseconds := 120000
  );
$$;
revoke execute on function public.run_new_episode_check() from public, anon, authenticated;

select cron.schedule('earshot-new-episodes', '*/15 * * * *', $$ select public.run_new_episode_check(); $$);
