#!/usr/bin/env bash
set -euo pipefail

HOST="${FOOTDRAW_HOST:-root@31.77.251.51}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
STAMP="$(date +%Y%m%d-%H%M%S)"

scp -q "$ROOT/footdraw_server_unified.py" "$HOST:/root/footdraw/footdraw_server.py.new"
scp -q "$ROOT/server/footdraw.service" "$HOST:/root/footdraw/footdraw.service.new"

ssh -o BatchMode=yes "$HOST" "set -e
cd /root/footdraw
python3 -m py_compile footdraw_server.py.new
cp -a footdraw_server.py footdraw_server.py.backup-$STAMP
chown root:root footdraw_server.py.new
chmod 0755 footdraw_server.py.new
mv -f footdraw_server.py.new footdraw_server.py
install -o root -g root -m 0644 footdraw.service.new /etc/systemd/system/footdraw.service
rm -f footdraw.service.new
systemctl daemon-reload
systemctl enable footdraw.service >/dev/null
systemctl restart footdraw.service
sleep 1
systemctl is-active --quiet footdraw.service
sha256sum /root/footdraw/footdraw_server.py
ss -lntup | grep ':4950'
"

echo "FootDraw relay deployed to $HOST"
echo "Backup: /root/footdraw/footdraw_server.py.backup-$STAMP"
