#!/usr/bin/env bash

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE_FILE="$ROOT/backend/docs/docker-compose.yaml"

usage() {
  cat <<'EOF'
Usage:
  ./scripts/start.sh dev [--infra=docker|homebrew|none] [--with-mineru|--require-mineru] [--skip-check]
  ./scripts/start.sh infra [docker|homebrew] [--skip-init] [--with-mineru|--require-mineru] [--skip-check]
  ./scripts/start.sh backend [profile]
  ./scripts/start.sh frontend
EOF
}

load_env() {
  # shellcheck source=lib/env.sh
  source "$ROOT/scripts/lib/env.sh" "$ROOT"
}

start_docker_infra() {
  local skip_init=false
  local skip_mineru=true
  local require_mineru=false

  while [[ $# -gt 0 ]]; do
    case "$1" in
      --skip-init) skip_init=true ;;
      --with-mineru) skip_mineru=false ;;
      --require-mineru) skip_mineru=false; require_mineru=true ;;
      *) echo "Unknown infra option: $1" >&2; usage >&2; return 2 ;;
    esac
    shift
  done

  echo "Starting NexusMind core infrastructure..."
  docker compose -f "$COMPOSE_FILE" up -d
  [[ "$skip_init" == true ]] && return 0

  echo "Waiting for MySQL..."
  local mysql_ready=false
  for _ in {1..30}; do
    if docker exec -e "MYSQL_PWD=$MYSQL_ROOT_PASSWORD" mysql mysqladmin ping -uroot --silent >/dev/null 2>&1; then
      mysql_ready=true
      break
    fi
    sleep 2
  done
  [[ "$mysql_ready" == true ]] || { echo "MySQL container did not become ready in time." >&2; return 1; }

  docker exec -e "MYSQL_PWD=$MYSQL_ROOT_PASSWORD" mysql mysql -uroot \
    -e "CREATE DATABASE IF NOT EXISTS nexusmind DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;" >/dev/null

  echo "Preparing MinIO bucket uploads..."
  if ! docker exec minio sh -c "mc alias set local http://127.0.0.1:19000 '$MINIO_ACCESS_KEY' '$MINIO_SECRET_KEY' >/dev/null 2>&1 && mc mb -p local/uploads >/dev/null 2>&1 || true" >/dev/null 2>&1; then
    echo "Warning: MinIO bucket initialization failed; create bucket 'uploads' manually." >&2
  fi

  if [[ "$skip_mineru" != true ]]; then
    if ! docker compose -f "$COMPOSE_FILE" --profile mineru up -d mineru-api; then
      [[ "$require_mineru" == true ]] && return 1
      echo "Warning: MinerU did not start; Tika parsing remains available." >&2
    fi
  fi
}

check_command() {
  command -v "$1" >/dev/null 2>&1 || { echo "Missing command: $1" >&2; return 1; }
}

check_port() {
  lsof -nP -iTCP:"$2" -sTCP:LISTEN >/dev/null 2>&1 ||
    echo "Warning: $1 does not appear to be listening on port $2." >&2
}

wait_for_port() {
  local name="$1" port="$2" timeout="${3:-60}" elapsed=0
  while ! lsof -nP -iTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1; do
    (( elapsed >= timeout )) && { echo "$name did not start listening on port $port within ${timeout}s." >&2; return 1; }
    sleep 2
    elapsed=$((elapsed + 2))
  done
}

wait_for_command() {
  local name="$1" timeout="$2" elapsed=0
  shift 2
  until "$@" >/dev/null 2>&1; do
    (( elapsed >= timeout )) && { echo "$name did not become ready within ${timeout}s." >&2; return 1; }
    sleep 2
    elapsed=$((elapsed + 2))
  done
}

ensure_elasticsearch_service_config() {
  local plist="$HOME/Library/LaunchAgents/homebrew.mxcl.elasticsearch-full.plist"
  local config="/opt/homebrew/etc/elasticsearch/elasticsearch.yml"
  local java_home="/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home"

  if [[ -f "$config" ]] && ! grep -q '^xpack\.ml\.enabled:' "$config"; then
    printf '\n# NexusMind local development: ML native code is not required.\nxpack.ml.enabled: false\n' >> "$config"
  fi
  if [[ -f "$plist" ]]; then
    /usr/libexec/PlistBuddy -c "Add :EnvironmentVariables dict" "$plist" 2>/dev/null || true
    /usr/libexec/PlistBuddy -c "Set :EnvironmentVariables:ES_JAVA_HOME $java_home" "$plist" 2>/dev/null ||
      /usr/libexec/PlistBuddy -c "Add :EnvironmentVariables:ES_JAVA_HOME string $java_home" "$plist" 2>/dev/null || true
  fi
}

start_homebrew_infra() {
  local skip_check=false
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --skip-check) skip_check=true ;;
      *) echo "Unknown Homebrew option: $1" >&2; return 2 ;;
    esac
    shift
  done

  if [[ "$skip_check" != true ]]; then
    for command in java mvn node pnpm brew mysql redis-cli kafka-topics mc; do
      check_command "$command"
    done
  fi

  echo "Starting local infrastructure with Homebrew..."
  brew services start mysql
  brew services start redis
  brew services start kafka
  brew services start minio
  brew services start neo4j
  brew services start elastic/tap/elasticsearch-full
  ensure_elasticsearch_service_config

  local uid
  uid="$(id -u)"
  local plist="$HOME/Library/LaunchAgents/homebrew.mxcl.elasticsearch-full.plist"
  if [[ -f "$plist" ]]; then
    launchctl bootout "gui/$uid" "$plist" 2>/dev/null || true
    launchctl bootstrap "gui/$uid" "$plist" 2>/dev/null || true
    launchctl kickstart -k "gui/$uid/homebrew.mxcl.elasticsearch-full" 2>/dev/null || true
  fi

  wait_for_port "MySQL" "${MYSQL_PORT:-3306}" 60
  wait_for_port "Redis" "${REDIS_PORT:-6379}" 60
  wait_for_port "Kafka" "${KAFKA_PORT:-9092}" 90
  wait_for_port "MinIO" "${MINIO_API_PORT:-9000}" 60
  wait_for_port "Neo4j" "${NEO4J_BOLT_HOST_PORT:-7687}" 90
  wait_for_command "Elasticsearch" 120 curl -fsS "http://localhost:${ELASTICSEARCH_PORT:-9200}"

  redis-cli ping >/dev/null 2>&1 && export REDIS_PASSWORD=""
  MYSQL_PWD="${MYSQL_PASSWORD:-}" mysql -u"${MYSQL_USERNAME:-root}" \
    -e "CREATE DATABASE IF NOT EXISTS nexusmind DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
  kafka-topics --bootstrap-server "localhost:${KAFKA_PORT:-9092}" --create \
    --topic "${KAFKA_FILE_PROCESSING_TOPIC:-file-processing-topic1}" --partitions 1 --replication-factor 1 >/dev/null 2>&1 || true
  kafka-topics --bootstrap-server "localhost:${KAFKA_PORT:-9092}" --create \
    --topic "${KAFKA_FILE_PROCESSING_DLT_TOPIC:-file-processing-dlt}" --partitions 1 --replication-factor 1 >/dev/null 2>&1 || true
  mc alias set nexusmind-local "http://localhost:${MINIO_API_PORT:-9000}" \
    "${MINIO_ACCESS_KEY:-minioadmin}" "${MINIO_SECRET_KEY:-minioadmin}" >/dev/null
  mc mb -p "nexusmind-local/${MINIO_BUCKET:-uploads}" >/dev/null 2>&1 || true
}

stop_homebrew_infra() {
  echo "Stopping local infrastructure services..."
  brew services stop elastic/tap/elasticsearch-full || true
  brew services stop kafka || true
  brew services stop minio || true
  brew services stop neo4j || true
  brew services stop mysql || true
  brew services stop redis || true
}

confirm_stop_homebrew_infra() {
  [[ "$active_infra" == homebrew ]] || return 0
  if [[ ! -t 0 ]]; then
    echo "Local infrastructure is still running."
    return 0
  fi
  local answer
  printf "Stop local infrastructure services now? [y/N] "
  read -r answer || answer=""
  case "$answer" in
    y|Y|yes|YES) stop_homebrew_infra ;;
    *) echo "Local infrastructure is still running." ;;
  esac
}

start_backend() {
  local profile="${1:-dev}"
  if lsof -nP -iTCP:"$BACKEND_PORT" -sTCP:LISTEN >/dev/null 2>&1; then
    echo "Backend port $BACKEND_PORT is already in use." >&2
    lsof -nP -iTCP:"$BACKEND_PORT" -sTCP:LISTEN >&2 || true
    return 1
  fi

  cd "$ROOT/backend"
  echo "Starting NexusMind backend on profile '$profile'..."
  mvn spring-boot:run \
    "-Dspring-boot.run.profiles=$profile" \
    "-Dspring-boot.run.jvmArguments=-Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8"
}

start_frontend() {
  cd "$ROOT/frontend"
  if [[ ! -d node_modules ]]; then
    echo "Installing frontend dependencies..."
    pnpm install --frozen-lockfile
  fi
  echo "Starting NexusMind frontend..."
  pnpm dev
}

port_listener_pids() {
  lsof -tiTCP:"$BACKEND_PORT" -sTCP:LISTEN 2>/dev/null || true
}

is_nexusmind_backend_pid() {
  local command
  command="$(ps -p "$1" -ww -o command= 2>/dev/null || true)"
  [[ "$command" == *"$ROOT/backend"* || "$command" == *"com.luky.nexusmind"* ]]
}

stop_existing_backend() {
  local pids=()
  local pid
  while IFS= read -r pid; do
    [[ -n "$pid" ]] && is_nexusmind_backend_pid "$pid" && pids+=("$pid")
  done < <(port_listener_pids)
  [[ ${#pids[@]} -gt 0 ]] || return 0

  echo "Stopping existing NexusMind backend on port $BACKEND_PORT: ${pids[*]}"
  kill "${pids[@]}" 2>/dev/null || true
  sleep 2
  for pid in "${pids[@]}"; do
    kill -0 "$pid" 2>/dev/null && kill -KILL "$pid" 2>/dev/null || true
  done
}

terminate_process_group() {
  [[ -n "$1" ]] || return 0
  kill -TERM "-$1" 2>/dev/null || kill -TERM "$1" 2>/dev/null || true
}

force_kill_process_group() {
  [[ -n "$1" ]] || return 0
  kill -KILL "-$1" 2>/dev/null || kill -KILL "$1" 2>/dev/null || true
}

backend_pid=""
frontend_pid=""
cleanup_started=false
active_infra="none"

cleanup() {
  [[ "$cleanup_started" == false ]] || return 0
  cleanup_started=true
  trap - EXIT INT TERM
  terminate_process_group "$frontend_pid"
  terminate_process_group "$backend_pid"
  sleep 2
  force_kill_process_group "$frontend_pid"
  force_kill_process_group "$backend_pid"
  confirm_stop_homebrew_infra
}

start_dev() {
  local infra="docker"
  local skip_check=false
  local infra_args=()
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --infra=docker) infra="docker" ;;
      --infra=homebrew|--infra=brew) infra="homebrew" ;;
      --infra=none|--no-infra) infra="none" ;;
      --skip-check) skip_check=true ;;
      --with-mineru|--require-mineru) infra_args+=("$1") ;;
      *) echo "Unknown dev option: $1" >&2; usage >&2; return 2 ;;
    esac
    shift
  done

  stop_existing_backend
  active_infra="$infra"
  trap cleanup EXIT INT TERM

  case "$infra" in
    docker) start_docker_infra "${infra_args[@]}" ;;
    homebrew)
      [[ ${#infra_args[@]} -eq 0 ]] || { echo "MinerU startup is only supported by Docker infrastructure." >&2; return 2; }
      [[ -d "$ROOT/.venv" ]] || python3 -m venv "$ROOT/.venv"
      if [[ "$skip_check" == true ]]; then start_homebrew_infra --skip-check; else start_homebrew_infra; fi
      ;;
    none)
      check_port "MySQL" "${MYSQL_PORT:-3306}"
      check_port "Redis" "${REDIS_PORT:-6379}"
      check_port "Kafka" "${KAFKA_PORT:-9092}"
      check_port "Elasticsearch" "${ELASTICSEARCH_PORT:-9200}"
      check_port "MinIO" "${MINIO_API_PORT:-9000}"
      check_port "Neo4j" "${NEO4J_BOLT_HOST_PORT:-7687}"
      ;;
  esac

  set -m
  start_backend dev &
  backend_pid=$!
  start_frontend &
  frontend_pid=$!

  echo "NexusMind development startup launched."
  echo "Backend:  http://localhost:$BACKEND_PORT"
  echo "Frontend: http://localhost:9527"
  wait "$backend_pid" "$frontend_pid"
}

main() {
  local target="${1:-}"
  [[ -n "$target" ]] || { usage >&2; return 2; }
  shift
  if [[ "$target" =~ ^(-h|--help|help)$ ]]; then
    usage
    return 0
  fi
  load_env

  case "$target" in
    dev) start_dev "$@" ;;
    infra)
      local provider="docker"
      if [[ "${1:-}" == "docker" || "${1:-}" == "homebrew" || "${1:-}" == "brew" ]]; then
        provider="$1"
        shift
      fi
      case "$provider" in
        docker) start_docker_infra "$@" ;;
        homebrew|brew) start_homebrew_infra "$@" ;;
      esac
      ;;
    backend)
      [[ $# -le 1 ]] || { usage >&2; return 2; }
      start_backend "${1:-dev}"
      ;;
    frontend)
      [[ $# -eq 0 ]] || { usage >&2; return 2; }
      start_frontend
      ;;
    *) usage >&2; return 2 ;;
  esac
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  main "$@"
fi
