#!/usr/bin/env bash

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BACKEND_PORT="${BACKEND_PORT:-19081}"
TMP_DIR="$(mktemp -d)"
FUNCTIONS_FILE="$TMP_DIR/start-dev-functions.sh"
PID_FILE="$TMP_DIR/backend.pid"

cleanup_test() {
  if [[ -f "$PID_FILE" ]]; then
    local pid
    pid="$(cat "$PID_FILE")"
    kill "$pid" 2>/dev/null || true
  fi
  rm -rf "$TMP_DIR"
}
trap cleanup_test EXIT

awk '
  /^(kill_backend_listeners|cleanup)\(\) \{/ { in_function = 1 }
  in_function { print }
  in_function && /^}/ { in_function = 0 }
' "$ROOT/scripts/start-dev.sh" > "$FUNCTIONS_FILE"

# shellcheck source=/dev/null
source "$FUNCTIONS_FILE"

sh -c '
  python3 -m http.server "$1" --bind 127.0.0.1 >"$2/backend.log" 2>&1 &
  echo "$!" > "$3"
' sh "$BACKEND_PORT" "$TMP_DIR" "$PID_FILE"

for _ in {1..50}; do
  if lsof -nP -iTCP:"$BACKEND_PORT" -sTCP:LISTEN >/dev/null 2>&1; then
    break
  fi
  sleep 0.1
done

if ! lsof -nP -iTCP:"$BACKEND_PORT" -sTCP:LISTEN >/dev/null 2>&1; then
  echo "test setup failed: fake backend did not listen on $BACKEND_PORT" >&2
  exit 1
fi

INITIAL_BACKEND_LISTENER_PIDS="$(cat "$PID_FILE")"

cleanup

for _ in {1..50}; do
  if ! lsof -nP -iTCP:"$BACKEND_PORT" -sTCP:LISTEN >/dev/null 2>&1; then
    exit 0
  fi
  sleep 0.1
done

echo "cleanup left a backend listener on port $BACKEND_PORT" >&2
exit 1
