#!/usr/bin/env bash

set -euo pipefail

ROOT="${1:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}"

import_dotenv_file() {
  local file="$1"

  [[ -f "$file" ]] || return 0

  while IFS= read -r line || [[ -n "$line" ]]; do
    line="${line#"${line%%[![:space:]]*}"}"
    line="${line%"${line##*[![:space:]]}"}"

    [[ -n "$line" ]] || continue
    [[ "$line" == \#* ]] && continue
    [[ "$line" == *=* ]] || continue

    local name="${line%%=*}"
    local value="${line#*=}"

    name="${name#"${name%%[![:space:]]*}"}"
    name="${name%"${name##*[![:space:]]}"}"
    value="${value#"${value%%[![:space:]]*}"}"
    value="${value%"${value##*[![:space:]]}"}"
    value="${value%\"}"
    value="${value#\"}"
    value="${value%\'}"
    value="${value#\'}"

    [[ -n "$name" ]] || continue
    export "$name=$value"
  done < "$file"
}

set_default_env() {
  local name="$1"
  local value="$2"

  if [[ -z "${!name:-}" ]]; then
    export "$name=$value"
  fi
}

import_dotenv_file "$ROOT/.env.local"

set_default_env "MYSQL_ROOT_PASSWORD" "nexusmind-local"
set_default_env "MYSQL_PASSWORD" "$MYSQL_ROOT_PASSWORD"
set_default_env "MYSQL_HOST_PORT" "13306"
set_default_env "MYSQL_PORT" "$MYSQL_HOST_PORT"
set_default_env "REDIS_PASSWORD" "nexusmind-local"
set_default_env "REDIS_HOST_PORT" "6379"
set_default_env "REDIS_PORT" "$REDIS_HOST_PORT"
set_default_env "ELASTICSEARCH_PASSWORD" "nexusmind-local"
set_default_env "ELASTICSEARCH_HOST_PORT" "9200"
set_default_env "ELASTICSEARCH_PORT" "$ELASTICSEARCH_HOST_PORT"
set_default_env "MINIO_ROOT_USER" "admin"
set_default_env "MINIO_ROOT_PASSWORD" "nexusmind-local"
set_default_env "MINIO_ACCESS_KEY" "$MINIO_ROOT_USER"
set_default_env "MINIO_SECRET_KEY" "$MINIO_ROOT_PASSWORD"
set_default_env "MINIO_API_HOST_PORT" "19000"
set_default_env "MINIO_CONSOLE_HOST_PORT" "19001"
set_default_env "MINIO_API_PORT" "$MINIO_API_HOST_PORT"
set_default_env "KAFKA_HOST_PORT" "9092"
set_default_env "KAFKA_CONTROLLER_HOST_PORT" "9093"
set_default_env "KAFKA_PORT" "$KAFKA_HOST_PORT"
set_default_env "JWT_SECRET_KEY" "nexusmind-local-development-secret-key-change-before-production"
set_default_env "BACKEND_PORT" "18081"
set_default_env "ADMIN_USERNAME" "admin"
set_default_env "ADMIN_PASSWORD" "$MYSQL_PASSWORD"
set_default_env "VITE_SERVICE_BASE_URL" "http://localhost:$BACKEND_PORT/api/v1"
set_default_env "VITE_OTHER_SERVICE_BASE_URL" '{"api":"http://localhost:'"$BACKEND_PORT"'/api/v1","ws":"ws://localhost:'"$BACKEND_PORT"'"}'
set_default_env "LANGFUSE_TRACING_ENABLED" "false"
set_default_env "LANGFUSE_BASE_URL" "https://us.cloud.langfuse.com"
set_default_env "LANGFUSE_PUBLIC_KEY" ""
set_default_env "LANGFUSE_SECRET_KEY" ""
set_default_env "LANGFUSE_TRACING_ENVIRONMENT" "dev"
set_default_env "LANGFUSE_CAPTURE_CONTENT" "false"
set_default_env "DEEPSEEK_API_KEY" ""
set_default_env "EMBEDDING_API_KEY" ""
set_default_env "MINERU_BASE_URL" "http://localhost:18000"
set_default_env "MINERU_PARSE_PATH" "/file_parse"
set_default_env "MINERU_BACKEND" "hybrid-auto-engine"
set_default_env "MINERU_PARSE_METHOD" "auto"
set_default_env "MINERU_OCR" "false"
set_default_env "MINERU_ENABLE_TABLE" "false"
set_default_env "MINERU_ENABLE_FORMULA" "false"
set_default_env "MINERU_API_HOST_PORT" "18000"
set_default_env "MINERU_MODEL_SOURCE" "local"
set_default_env "MINERU_MODEL_DOWNLOAD_TYPE" "pipeline"
set_default_env "MINERU_API_ENABLE_FASTAPI_DOCS" "1"

if [[ -z "$DEEPSEEK_API_KEY" || -z "$EMBEDDING_API_KEY" ]]; then
  echo "Warning: DEEPSEEK_API_KEY or EMBEDDING_API_KEY is not configured. Basic features can start, but AI chat and vectorization will not work." >&2
fi
