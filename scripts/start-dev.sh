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

backend_pid=""
frontend_pid=""
cleanup_started=false

port_listener_pids() {
  lsof -tiTCP:"$BACKEND_PORT" -sTCP:LISTEN 2>/dev/null || true
}

is_nexusmind_backend_pid() {
  local pid="$1"
  local command

  command="$(ps -p "$pid" -ww -o command= 2>/dev/null || true)"
  [[ "$command" == *"$ROOT/backend"* || "$command" == *"com.luky.nexusmind"* ]]
}

stop_nexusmind_backend_port_listeners() {
  local pids=()
  local pid

  while IFS= read -r pid; do
    [[ -n "$pid" ]] || continue
    if is_nexusmind_backend_pid "$pid"; then
      pids+=("$pid")
    fi
  done < <(port_listener_pids)

  [[ ${#pids[@]} -gt 0 ]] || return 0

  echo "Stopping existing NexusMind backend on port $BACKEND_PORT: ${pids[*]}"
  kill "${pids[@]}" 2>/dev/null || true
  sleep 2

  for pid in "${pids[@]}"; do
    if kill -0 "$pid" 2>/dev/null; then
      kill -KILL "$pid" 2>/dev/null || true
    fi
  done
}

terminate_process_group() {
  local pid="$1"

  [[ -n "$pid" ]] || return 0

  kill -TERM "-$pid" 2>/dev/null || kill -TERM "$pid" 2>/dev/null || true
}

force_kill_process_group() {
  local pid="$1"

  [[ -n "$pid" ]] || return 0

  kill -KILL "-$pid" 2>/dev/null || kill -KILL "$pid" 2>/dev/null || true
}

cleanup() {
  local pid
  local remaining_backend_pids=()

  if [[ "$cleanup_started" == true ]]; then
    return 0
  fi
  cleanup_started=true

  trap - EXIT INT TERM

  for pid in "$frontend_pid" "$backend_pid"; do
    terminate_process_group "$pid"
  done

  sleep 2

  for pid in "$frontend_pid" "$backend_pid"; do
    force_kill_process_group "$pid"
  done

  while IFS= read -r pid; do
    [[ -n "$pid" ]] || continue
    if is_nexusmind_backend_pid "$pid"; then
      remaining_backend_pids+=("$pid")
    fi
  done < <(port_listener_pids)

  if [[ ${#remaining_backend_pids[@]} -gt 0 ]]; then
    echo "Stopping remaining NexusMind backend on port $BACKEND_PORT: ${remaining_backend_pids[*]}"
    kill "${remaining_backend_pids[@]}" 2>/dev/null || true
    sleep 1
    kill -KILL "${remaining_backend_pids[@]}" 2>/dev/null || true
  fi
}
trap cleanup EXIT INT TERM

stop_nexusmind_backend_port_listeners

if [[ "$NO_INFRA" != true ]]; then
  if [[ ${#MINERU_ARGS[@]} -gt 0 ]]; then
    "$ROOT/scripts/start-infra.sh" "${MINERU_ARGS[@]}"
  else
    "$ROOT/scripts/start-infra.sh"
  fi
fi

# Put backend and frontend into their own process groups so Ctrl+C cleanup reaches
# Maven/Spring Boot and Vite child processes reliably.
set -m
"$ROOT/scripts/start-backend.sh" "$BACKEND_PROFILE" &
backend_pid=$!

"$ROOT/scripts/start-frontend.sh" &
frontend_pid=$!

echo "NexusMind startup launched."
echo "Backend:  http://localhost:$BACKEND_PORT"
echo "Frontend: check the Vite URL printed below, usually http://localhost:9527"

wait "$backend_pid" "$frontend_pid"
