#!/usr/bin/env bash
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if command -v python3 >/dev/null 2>&1; then
  exec python3 "$here/agent.py" "$@"
fi
if command -v nix >/dev/null 2>&1; then
  exec nix --extra-experimental-features "nix-command flakes" shell nixpkgs#python3 --command python3 "$here/agent.py" "$@"
fi
echo "Requires Python 3 or Nix (for temporary Python 3 runtime)." >&2
exit 1
