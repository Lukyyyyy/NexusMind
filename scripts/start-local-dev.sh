#!/usr/bin/env bash

set -euo pipefail

SKIP_CHECK=false
NO_INFRA=false

while [[ $# -gt 0 ]]; do
  case "$1" in
    --skip-check)
      SKIP_CHECK=true
      ;;
    --no-infra)
      NO_INFRA=true
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

if redis-cli ping >/dev/null 2>&1; then
  export REDIS_PASSWORD=""
fi

check_command() {
  local command="$1"
  if ! command -v "$command" >/dev/null 2>&1; then
    echo "Missing command: $command" >&2
    return 1
  fi
}

check_port() {
  local name="$1"
  local port="$2"
  if ! lsof -nP -iTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1; then
    echo "Warning: $name does not appear to be listening on port $port." >&2
  fi
}

wait_for_port() {
  local name="$1"
  local port="$2"
  local timeout="${3:-60}"
  local elapsed=0

  while ! lsof -nP -iTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1; do
    if (( elapsed >= timeout )); then
      echo "$name did not start listening on port $port within ${timeout}s." >&2
      return 1
    fi
    sleep 2
    elapsed=$((elapsed + 2))
  done
}

wait_for_command() {
  local name="$1"
  local timeout="$2"
  shift 2
  local elapsed=0

  until "$@" >/dev/null 2>&1; do
    if (( elapsed >= timeout )); then
      echo "$name did not become ready within ${timeout}s." >&2
      return 1
    fi
    sleep 2
    elapsed=$((elapsed + 2))
  done
}

ensure_elasticsearch_service_config() {
  local plist="$HOME/Library/LaunchAgents/homebrew.mxcl.elasticsearch-full.plist"
  local es_config="/opt/homebrew/etc/elasticsearch/elasticsearch.yml"
  local java_home="/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home"

  if [[ -f "$es_config" ]] && ! grep -q '^xpack\.ml\.enabled:' "$es_config"; then
    printf '\n# NexusMind local development: ML native code is not required.\nxpack.ml.enabled: false\n' >> "$es_config"
  fi

  if [[ -f "$plist" ]]; then
    /usr/libexec/PlistBuddy -c "Add :EnvironmentVariables dict" "$plist" 2>/dev/null || true
    /usr/libexec/PlistBuddy -c "Set :EnvironmentVariables:ES_JAVA_HOME $java_home" "$plist" 2>/dev/null ||
      /usr/libexec/PlistBuddy -c "Add :EnvironmentVariables:ES_JAVA_HOME string $java_home" "$plist" 2>/dev/null || true
  fi
}

start_infra() {
  echo "Starting local infrastructure..."
  brew services start mysql
  brew services start redis
  brew services start kafka
  brew services start minio
  brew services start neo4j

  brew services start elastic/tap/elasticsearch-full
  ensure_elasticsearch_service_config
  local uid
  uid="$(id -u)"
  if [[ -f "$HOME/Library/LaunchAgents/homebrew.mxcl.elasticsearch-full.plist" ]]; then
    launchctl bootout "gui/$uid" "$HOME/Library/LaunchAgents/homebrew.mxcl.elasticsearch-full.plist" 2>/dev/null || true
    launchctl bootstrap "gui/$uid" "$HOME/Library/LaunchAgents/homebrew.mxcl.elasticsearch-full.plist" 2>/dev/null || true
    launchctl kickstart -k "gui/$uid/homebrew.mxcl.elasticsearch-full" 2>/dev/null || true
  fi

  wait_for_port "MySQL" "${MYSQL_PORT:-3306}" 60
  wait_for_port "Redis" "${REDIS_PORT:-6379}" 60
  wait_for_port "Kafka" "${KAFKA_PORT:-9092}" 90
  wait_for_port "MinIO" "${MINIO_API_PORT:-9000}" 60
  wait_for_port "Neo4j" "${NEO4J_BOLT_HOST_PORT:-7687}" 90
  wait_for_command "Elasticsearch" 120 curl -fsS "http://localhost:${ELASTICSEARCH_PORT:-9200}"

  if redis-cli ping >/dev/null 2>&1; then
    export REDIS_PASSWORD=""
  fi

  MYSQL_PWD="${MYSQL_PASSWORD:-}" mysql -u"${MYSQL_USERNAME:-root}" -e \
    "CREATE DATABASE IF NOT EXISTS nexusmind DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"

  kafka-topics --bootstrap-server "localhost:${KAFKA_PORT:-9092}" --create \
    --topic "${KAFKA_FILE_PROCESSING_TOPIC:-file-processing-topic1}" --partitions 1 --replication-factor 1 >/dev/null 2>&1 || true
  kafka-topics --bootstrap-server "localhost:${KAFKA_PORT:-9092}" --create \
    --topic "${KAFKA_FILE_PROCESSING_DLT_TOPIC:-file-processing-dlt}" --partitions 1 --replication-factor 1 >/dev/null 2>&1 || true

  mc alias set nexusmind-local "http://localhost:${MINIO_API_PORT:-9000}" \
    "${MINIO_ACCESS_KEY:-minioadmin}" "${MINIO_SECRET_KEY:-minioadmin}" >/dev/null
  mc mb -p "nexusmind-local/${MINIO_BUCKET:-uploads}" >/dev/null 2>&1 || true
}

stop_infra() {
  echo "Stopping local infrastructure..."
  brew services stop elastic/tap/elasticsearch-full || true
  brew services stop kafka || true
  brew services stop minio || true
  brew services stop neo4j || true
  brew services stop mysql || true
  brew services stop redis || true
}

confirm_stop_infra() {
  local answer

  if [[ "$NO_INFRA" == true ]]; then
    return 0
  fi

  if [[ ! -t 0 ]]; then
    echo "Local infrastructure is still running."
    return 0
  fi

  printf "Stop local infrastructure services now? [y/N] "
  read -r answer || answer=""
  case "$answer" in
    y|Y|yes|YES)
      stop_infra
      ;;
    *)
      echo "Local infrastructure is still running."
      ;;
  esac
}

if [[ "$SKIP_CHECK" != true ]]; then
  check_command java
  check_command mvn
  check_command node
  check_command pnpm
  check_command brew
  check_command mysql
  check_command redis-cli
  check_command kafka-topics
  check_command mc
fi

backend_pid=""
frontend_pid=""
cleanup_started=false

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
  if [[ "$cleanup_started" == true ]]; then
    return 0
  fi
  cleanup_started=true

  trap - EXIT INT TERM
  terminate_process_group "$frontend_pid"
  terminate_process_group "$backend_pid"
  sleep 2
  force_kill_process_group "$frontend_pid"
  force_kill_process_group "$backend_pid"
  confirm_stop_infra
}
trap cleanup EXIT INT TERM

if lsof -nP -iTCP:"$BACKEND_PORT" -sTCP:LISTEN >/dev/null 2>&1; then
  echo "Backend port $BACKEND_PORT is already in use." >&2
  lsof -nP -iTCP:"$BACKEND_PORT" -sTCP:LISTEN >&2 || true
  exit 1
fi

if [[ ! -d "$ROOT/.venv" ]]; then
  python3 -m venv "$ROOT/.venv"
fi

if [[ "$NO_INFRA" != true ]]; then
  start_infra
else
  check_port "MySQL" "${MYSQL_PORT:-3306}"
  check_port "Redis" "${REDIS_PORT:-6379}"
  check_port "Kafka" "${KAFKA_PORT:-9092}"
  check_port "Elasticsearch" "${ELASTICSEARCH_PORT:-9200}"
  check_port "MinIO" "${MINIO_API_PORT:-9000}"
  check_port "Neo4j" "${NEO4J_BOLT_HOST_PORT:-7687}"
fi

set -m

(
  cd "$ROOT/backend"
  mvn spring-boot:run \
    "-Dspring-boot.run.profiles=local" \
    "-Dspring-boot.run.jvmArguments=-Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8"
) &
backend_pid=$!

(
  cd "$ROOT/frontend"
  if [[ ! -d node_modules ]]; then
    pnpm install --frozen-lockfile
  fi
  pnpm dev
) &
frontend_pid=$!

echo "NexusMind local development startup launched."
echo "Backend:  http://localhost:$BACKEND_PORT"
echo "Frontend: check the Vite URL printed below, usually http://localhost:9527"
echo "Python venv: $ROOT/.venv"

wait "$backend_pid" "$frontend_pid"
