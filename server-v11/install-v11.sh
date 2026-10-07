#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TARGET=/opt/footdraw-v11
SERVICE=/etc/systemd/system/footdraw-v11.service

if ss -lntup | grep -qE '[:.]4950[[:space:]]'; then
  echo "ERROR: TCP/UDP port 4950 is already in use. Nothing was changed."
  ss -lntup | grep -E '[:.]4950[[:space:]]' || true
  exit 2
fi

install -d -o root -g root -m 0755 "$TARGET" "$TARGET/data"
python3 -m py_compile "$ROOT/footdraw_server_v4_1.py"
install -o root -g root -m 0755 "$ROOT/footdraw_server_v4_1.py" "$TARGET/footdraw_server.py"
install -o root -g root -m 0644 "$ROOT/footdraw-v11.service" "$SERVICE"

systemctl daemon-reload
systemctl enable --now footdraw-v11.service
sleep 1
systemctl is-active --quiet footdraw-v11.service
echo "FootDraw V11 relay installed without touching Minecraft."
echo "Service: footdraw-v11.service"
echo "Data: $TARGET/data"
ss -lntup | grep -E '[:.]4950[[:space:]]' || true
