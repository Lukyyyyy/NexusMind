#!/usr/bin/env bash

set -euo pipefail

NO_INFRA=false
MINERU_ARGS=()
BACKEND_PROFILE="docker"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --no-infra)
      NO_INFRA=true
      ;;
    --with-mineru)
      MINERU_ARGS+=(--with-mineru)
      ;;
    --require-mineru)
      MINERU_ARGS+=(--require-mineru)
      ;;
    --backend-profile)
      shift
      BACKEND_PROFILE="${1:-}"
      if [[ -z "$BACKEND_PROFILE" ]]; then
        echo "--backend-profile requires a value" >&2
        exit 1
      fi
      ;;
    *)
      echo "Unknown option: $1" >&2
      exit 1
      ;;
  esac
  shift
done

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# shellcheck source=lib/env.sh
source "$ROOT/scripts/lib/env.sh" "$ROOT"

if [[ "$NO_INFRA" != true ]]; then
  if [[ ${#MINERU_ARGS[@]} -gt 0 ]]; then
    "$ROOT/scripts/start-infra.sh" "${MINERU_ARGS[@]}"
  else
    "$ROOT/scripts/start-infra.sh"
  fi
fi

kill_backend_listeners() {
  local pids pid
  pids="$(lsof -tiTCP:"$BACKEND_PORT" -sTCP:LISTEN 2>/dev/null || true)"
  if [[ -z "$pids" ]]; then
    return 0
  fi

  for pid in $pids; do
    kill "$pid" 2>/dev/null || true
  done
}

cleanup() {
  local pids
  pids="$(jobs -p)"
  if [[ -n "$pids" ]]; then
    kill $pids 2>/dev/null || true
  fi
  kill_backend_listeners
}
trap cleanup EXIT INT TERM

"$ROOT/scripts/start-backend.sh" "$BACKEND_PROFILE" &
backend_pid=$!

"$ROOT/scripts/start-frontend.sh" &
frontend_pid=$!

echo "NexusMind startup launched."
echo "Backend:  http://localhost:$BACKEND_PORT"
echo "Frontend: check the Vite URL printed below, usually http://localhost:9527"

wait "$backend_pid" "$frontend_pid"
