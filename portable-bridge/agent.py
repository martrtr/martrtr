#!/usr/bin/env python3
"""Supabase Commander portable diagnostics client (no remote command execution)."""
import argparse, getpass, json, os, platform, socket, urllib.request, urllib.error, uuid, time

def main():
    p = argparse.ArgumentParser()
    p.add_argument("--url", required=True, help="Supabase project HTTPS API URL")
    p.add_argument("--key", required=True, help="Publishable/anon key, never service_role")
    p.add_argument("--device-id", required=True, type=uuid.UUID)
    p.add_argument("--interval", type=float, default=5)
    p.add_argument("--once", action="store_true")
    args = p.parse_args()
    if not args.url.startswith("https://") or args.key.startswith("sb_secret_"):
        p.error("Use the HTTPS project URL and an anon/publishable key")
    secret = os.environ.get("COMMANDER_DEVICE_SECRET") or getpass.getpass("Device secret: ")
    headers = {"apikey":args.key, "Content-Type":"application/json"}
    if args.key.count(".") == 2:
        headers["Authorization"] = "Bearer " + args.key
    auth = {"p_device_id": str(args.device_id), "p_secret": secret}
    print("Commander diagnostics client ready; Ctrl+C to stop.", flush=True)
    while True:
        endpoint = "commander_portable_claim"
        data = json.dumps(auth).encode()
        request = urllib.request.Request(args.url.rstrip("/") + "/rest/v1/rpc/" + endpoint, data=data, headers=headers)
        try:
            with urllib.request.urlopen(request, timeout=20) as response:
                job = json.load(response)
            if job:
                info = {"hostname":socket.gethostname(), "system":platform.platform()}
                ok = job.get("action") in ("ping", "health", "system.info")
                result = {"stdout":json.dumps(info) if ok else "", "stderr":"" if ok else "Unsupported action"}
                data = json.dumps(dict(auth, p_job_id=job["id"], p_exit_code=0 if ok else 2, p_result=result, p_error=None if ok else "Unsupported action")).encode()
                req = urllib.request.Request(args.url.rstrip("/") + "/rest/v1/rpc/commander_portable_finish", data=data, headers=headers)
                with urllib.request.urlopen(req, timeout=20) as response:
                    print("Job", job["id"], "finished:", response.read(100), flush=True)
        except urllib.error.HTTPError as error:
            print("HTTP error", error.code, error.read(200).decode("utf-8","replace"), flush=True)
            if error.code in (401,403): return 1
        if args.once: return 0
        time.sleep(max(1, min(60, args.interval)))

if __name__ == "__main__":
    try: raise SystemExit(main())
    except KeyboardInterrupt: print("\nStopped.")
