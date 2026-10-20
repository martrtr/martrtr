# Portable Commander Bridge

Prototype for the existing Supabase Commander Bridge project.

**Current scope:** device diagnostics (`ping`, `health`, `system.info`).
**Not implemented:** remote shell, system administration, file editing.
**Deployment:** the SQL adapter in `supabase.sql` is NOT applied to Supabase yet.

To test manually:
1. Review and apply `portable-bridge/supabase.sql` in the project's SQL Editor.
2. Execute `select commander.create_device('my-laptop-portable');` and store the returned ID and secret privately.
3. Obtain the Supabase publishable/anon API key (NOT service_role or database password).
4. Clone this branch and run:

```sh
bash portable-bridge/start.sh --url https://PROJECT_REF.supabase.co --key PUBLISHABLE_KEY --device-id DEVICE_UUID
```

Enter the device secret at the hidden prompt. Stop with Ctrl+C.
Requires Python 3; on NixOS, the launcher can obtain Python temporarily with `nix shell`.

To test a connected device, enqueue `ping` using `commander.enqueue` and check its result.
Do not assume the prototype grants remote control of a computer.
