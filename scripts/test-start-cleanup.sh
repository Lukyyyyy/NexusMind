#!/usr/bin/env bash

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BACKEND_PORT="${BACKEND_PORT:-19081}"
TMP_DIR="$(mktemp -d)"

# shellcheck source=start.sh
source "$ROOT/scripts/start.sh"

cleanup_test() {
  [[ -n "${fake_pid:-}" ]] && kill "$fake_pid" 2>/dev/null || true
  rm -rf "$TMP_DIR"
}
trap cleanup_test EXIT

python3 -c 'import http.server,sys; http.server.ThreadingHTTPServer(("127.0.0.1", int(sys.argv[1])), http.server.SimpleHTTPRequestHandler).serve_forever()' \
  "$BACKEND_PORT" com.luky.nexusmind >"$TMP_DIR/backend.log" 2>&1 &
fake_pid=$!

for _ in {1..50}; do
  lsof -nP -iTCP:"$BACKEND_PORT" -sTCP:LISTEN >/dev/null 2>&1 && break
  sleep 0.1
done
lsof -nP -iTCP:"$BACKEND_PORT" -sTCP:LISTEN >/dev/null 2>&1 || { echo "test setup failed" >&2; exit 1; }

stop_existing_backend

for _ in {1..50}; do
  lsof -nP -iTCP:"$BACKEND_PORT" -sTCP:LISTEN >/dev/null 2>&1 || exit 0
  sleep 0.1
done
echo "cleanup left a backend listener on port $BACKEND_PORT" >&2
exit 1
