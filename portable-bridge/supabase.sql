-- Commander Bridge Portable RPC adapter.
-- Run once in the existing Supabase Commander Bridge SQL Editor.
-- The RPCs authenticate each request against commander.devices.secret_hash.
-- They do not expose arbitrary SQL or grant direct access to commander tables.
--
-- SECURITY: never grant access to commander.* tables to anon/authenticated.

create or replace function public.commander_portable_claim(
  p_device_id uuid,
  p_secret text
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $body$
declare
  v_job commander.jobs%rowtype;
begin
  if not commander.agent_authorized(p_device_id, p_secret) then
    raise exception 'invalid device credentials' using errcode = '28000';
  end if;

  update commander.devices
     set last_seen_at = now(), agent_version = 'portable-0.1'
   where id = p_device_id and enabled;

  select *
    into v_job
    from commander.jobs
   where device_id = p_device_id
     and status = 'queued'
     and coalesce(cancel_requested, false) = false
     and (available_at is null or available_at <= now())
   order by priority desc, created_at asc
   limit 1
   for update skip locked;

  if not found then
    return null;
  end if;

  update commander.jobs
     set status = 'running',
         leased_at = now(),
         started_at = now(),
         lease_expires_at = now() + make_interval(secs => least(greatest(coalesce(v_job.timeout_ms, 120000), 1000), 900000)::double precision / 1000.0),
         attempt = attempt + 1
   where id = v_job.id;

  return jsonb_build_object(
    'id', v_job.id,
    'action', v_job.action,
    'payload', v_job.payload,
    'timeout_ms', least(greatest(coalesce(v_job.timeout_ms, 120000), 1000), 900000)
  );
end;
$body$;

create or replace function public.commander_portable_finish(
  p_device_id uuid,
  p_secret text,
  p_job_id uuid,
  p_exit_code integer,
  p_result jsonb,
  p_error text default null
)
returns boolean
language plpgsql
security definer
set search_path = ''
as $body$
declare
  v_changed integer;
begin
  if not commander.agent_authorized(p_device_id, p_secret) then
    raise exception 'invalid device credentials' using errcode = '28000';
  end if;

  update commander.jobs
     set status = case when p_exit_code = 0 and p_error is null then 'succeeded' else 'failed' end,
         exit_code = p_exit_code,
         result = coalesce(p_result, '{}'::jsonb),
         error = left(p_error, 4000),
         summary = left(coalesce(p_result->>'stdout', p_error, ''), 500),
         finished_at = now(),
         lease_expires_at = null
   where id = p_job_id
     and device_id = p_device_id
     and status = 'running';

  get diagnostics v_changed = row_count;
  return v_changed = 1;
end;
$body$;

-- Explicitly restrict RPC invocation. Avoid the default PUBLIC EXECUTE grant.
revoke all on function public.commander_portable_claim(uuid, text) from public;
revoke all on function public.commander_portable_finish(uuid, text, uuid, integer, jsonb, text) from public;
grant execute on function public.commander_portable_claim(uuid, text) to anon;
grant execute on function public.commander_portable_finish(uuid, text, uuid, integer, jsonb, text) to anon;

-- No table grants are added. No access is granted to authenticated clients.
