#!/usr/bin/env bash
set -Eeuo pipefail

TARGET=/opt/footdraw-v11
SERVICE_NAME=footdraw-v11.service
SERVICE_FILE=/etc/systemd/system/footdraw-v11.service
LEGACY_NAME=footdraw.service
LEGACY_FILE=/etc/systemd/system/footdraw.service
LEGACY_DIR=/root/footdraw
PORT=4950

TMP="$(mktemp -d /tmp/footdraw-v11-install.XXXXXX)"
NEW_TARGET="${TMP}/footdraw-v11"
LEGACY_WAS_ACTIVE=0
V11_WAS_ACTIVE=0
INSTALLED=0

cleanup() {
  rm -rf "$TMP"
  rm -f /etc/systemd/system/footdraw-v11.service.new
}
trap cleanup EXIT

fail() {
  echo "ERROR: $*" >&2
  exit 1
}

echo "=== FootDraw V11 safe installer ==="
echo "No Minecraft service/process/firewall rules will be changed."

MC_BEFORE="$(ss -ltnp 2>/dev/null | awk '$4 ~ /:25565$/ {print}' || true)"
echo "Minecraft listener before:"
printf '%s\n' "${MC_BEFORE:-<not detected on 25565>}"

install -d -m 0755 "$NEW_TARGET" "$NEW_TARGET/data"

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
python3 -m py_compile "$ROOT/footdraw_server_v4_1.py"
install -m 0755 "$ROOT/footdraw_server_v4_1.py" "$NEW_TARGET/footdraw_server.py"
install -m 0644 "$ROOT/footdraw-v11.service" /etc/systemd/system/footdraw-v11.service.new

port_line="$(ss -ltnp 2>/dev/null | awk -v p=":$PORT" '$4 ~ p"$" {print}' || true)"
owner_unit=""

if [[ -n "$port_line" ]]; then
  echo "Port $PORT currently in use:"
  printf '%s\n' "$port_line"

  pids="$(printf '%s\n' "$port_line" | grep -oE 'pid=[0-9]+' | cut -d= -f2 | sort -u || true)"
  [[ -n "$pids" ]] || fail "Port $PORT is occupied, but owning PID could not be determined. Nothing changed."

  for pid in $pids; do
    unit="$(systemctl status "$pid" 2>/dev/null | sed -n 's/.*CGroup: .*system\.slice\/\([^ ]*\.service\).*/\1/p' | head -n1 || true)"
    if [[ -z "$unit" && -r "/proc/$pid/cgroup" ]]; then
      unit="$(sed -n 's#.*system\.slice/\([^/]*\.service\).*#\1#p' "/proc/$pid/cgroup" | head -n1 || true)"
    fi

    case "$unit" in
      "$SERVICE_NAME")
        owner_unit="$SERVICE_NAME"
        ;;
      "$LEGACY_NAME")
        owner_unit="$LEGACY_NAME"
        ;;
      *)
        fail "Port $PORT belongs to PID $pid / unit '${unit:-unknown}', not FootDraw. Nothing changed."
        ;;
    esac
  done
fi

legacy_matches=0
if systemctl cat "$LEGACY_NAME" >/dev/null 2>&1; then
  if systemctl cat "$LEGACY_NAME" 2>/dev/null | grep -Fq '/root/footdraw/footdraw_server.py'; then
    legacy_matches=1
  fi
fi

if [[ "$owner_unit" == "$LEGACY_NAME" && "$legacy_matches" -ne 1 ]]; then
  fail "Legacy footdraw.service owns $PORT but does not match the known FootDraw relay path. Refusing to touch it."
fi

if systemctl is-active --quiet "$LEGACY_NAME" 2>/dev/null; then
  LEGACY_WAS_ACTIVE=1
fi
if systemctl is-active --quiet "$SERVICE_NAME" 2>/dev/null; then
  V11_WAS_ACTIVE=1
fi

rollback() {
  rc=$?
  if [[ "$INSTALLED" -ne 1 ]]; then
    echo "Install failed; attempting FootDraw-only rollback..." >&2
    systemctl stop "$SERVICE_NAME" >/dev/null 2>&1 || true
    if [[ "$LEGACY_WAS_ACTIVE" -eq 1 && "$legacy_matches" -eq 1 ]]; then
      systemctl start "$LEGACY_NAME" >/dev/null 2>&1 || true
    elif [[ "$V11_WAS_ACTIVE" -eq 1 ]]; then
      systemctl start "$SERVICE_NAME" >/dev/null 2>&1 || true
    fi
  fi
  exit "$rc"
}
trap rollback ERR

if [[ "$owner_unit" == "$LEGACY_NAME" ]]; then
  echo "Stopping known legacy FootDraw relay only..."
  systemctl stop "$LEGACY_NAME"
elif [[ "$owner_unit" == "$SERVICE_NAME" || "$V11_WAS_ACTIVE" -eq 1 ]]; then
  echo "Stopping existing FootDraw V11 relay only..."
  systemctl stop "$SERVICE_NAME" || true
fi

# Atomic directory replacement, preserving only intentional V11 state.
if [[ -d "$TARGET/data" ]]; then
  cp -a "$TARGET/data/." "$NEW_TARGET/data/" 2>/dev/null || true
fi

OLD_TARGET=""
if [[ -e "$TARGET" ]]; then
  OLD_TARGET="${TMP}/old-target"
  mv "$TARGET" "$OLD_TARGET"
fi
mv "$NEW_TARGET" "$TARGET"
chown -R root:root "$TARGET"
chmod 0755 "$TARGET" "$TARGET/footdraw_server.py"
chmod 0755 "$TARGET/data"

install -o root -g root -m 0644 /etc/systemd/system/footdraw-v11.service.new "$SERVICE_FILE"
rm -f /etc/systemd/system/footdraw-v11.service.new
systemctl daemon-reload
systemctl enable "$SERVICE_NAME" >/dev/null
systemctl start "$SERVICE_NAME"
sleep 1

systemctl is-active --quiet "$SERVICE_NAME" || fail "FootDraw V11 service did not become active."
ss -ltnp 2>/dev/null | grep -Eq ":$PORT[[:space:]]" || fail "FootDraw TCP $PORT is not listening."
ss -lunp 2>/dev/null | grep -Eq ":$PORT[[:space:]]" || fail "FootDraw UDP $PORT is not listening."

# Only after V11 is confirmed healthy: remove the exact known legacy FootDraw installation.
if [[ "$legacy_matches" -eq 1 ]]; then
  systemctl disable "$LEGACY_NAME" >/dev/null 2>&1 || true
  rm -f "$LEGACY_FILE"
  if [[ -d "$LEGACY_DIR" ]]; then
    rm -rf --one-file-system "$LEGACY_DIR"
  fi
  systemctl daemon-reload
fi

# Remove any old replacement directory held only for rollback.
[[ -z "$OLD_TARGET" ]] || rm -rf --one-file-system "$OLD_TARGET"

INSTALLED=1
trap - ERR

MC_AFTER="$(ss -ltnp 2>/dev/null | awk '$4 ~ /:25565$/ {print}' || true)"
echo "Minecraft listener after:"
printf '%s\n' "${MC_AFTER:-<not detected on 25565>}"

if [[ "$MC_BEFORE" != "$MC_AFTER" ]]; then
  echo "WARNING: Minecraft listener snapshot changed during install." >&2
  echo "The installer did not issue any command against Minecraft; inspect it manually." >&2
fi

echo "=== FootDraw V11 installed successfully ==="
echo "Service: $SERVICE_NAME"
echo "Files:   $TARGET"
echo "Port:    $PORT/tcp + $PORT/udp"
echo "Temporary installer files will be removed automatically."
systemctl --no-pager --full status "$SERVICE_NAME" | sed -n '1,12p'
ss -lntup 2>/dev/null | grep -E ":($PORT|25565)[[:space:]]" || true
