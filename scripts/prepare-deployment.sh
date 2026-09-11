#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="$ROOT/.env.deploy.local"
SHARED_NETWORK="shared_services"

random_hex() {
  openssl rand -hex "$1"
}

if [[ ! -f "$ENV_FILE" ]]; then
  umask 077
  mysql_password="$(random_hex 24)"
  minio_access_key="nexusmind$(random_hex 6)"
  minio_secret_key="$(random_hex 24)"
  elasticsearch_password="$(random_hex 24)"
  neo4j_password="$(random_hex 24)"
  jwt_secret="$(random_hex 48)"
  smtp_secret="$(random_hex 32)"
  admin_password="$(random_hex 12)"

  tee "$ENV_FILE" >/dev/null <<ENV
MYSQL_DATABASE=nexusmind
MYSQL_USERNAME=nexusmind
MYSQL_PASSWORD=$mysql_password
REDIS_DATABASE=1
REDIS_PASSWORD=
MINIO_ACCESS_KEY=$minio_access_key
MINIO_SECRET_KEY=$minio_secret_key
MINIO_BUCKET=nexusmind-uploads
ELASTICSEARCH_PASSWORD=$elasticsearch_password
NEO4J_USERNAME=neo4j
NEO4J_PASSWORD=$neo4j_password
JWT_SECRET_KEY=$jwt_secret
SMTP_CRYPTO_SECRET=$smtp_secret
MAIL_PROVIDER=smtp
TENCENT_SES_SECRET_ID=
TENCENT_SES_SECRET_KEY=
TENCENT_SES_REGION=ap-hongkong
TENCENT_SES_FROM_ADDRESS=
TENCENT_SES_VERIFICATION_TEMPLATE_ID=0
TENCENT_SES_ORGANIZATION_APPLICATION_TEMPLATE_ID=0
TENCENT_SES_ORGANIZATION_RESULT_TEMPLATE_ID=0
TENCENT_SES_MEMBERSHIP_CHANGE_TEMPLATE_ID=0
TENCENT_SES_ROLE_CHANGE_TEMPLATE_ID=0
TENCENT_SES_EMAIL_CHANGED_TEMPLATE_ID=0
TENCENT_SES_TEST_TEMPLATE_ID=0
ADMIN_USERNAME=admin
ADMIN_EMAIL=admin@nexusmind.local
ADMIN_PASSWORD=$admin_password
BACKEND_PORT=18081
APP_PUBLIC_URL=https://nexusmind.lukybetter.com
APP_WEBSOCKET_ALLOWED_ORIGINS=https://nexusmind.lukybetter.com
DEEPSEEK_API_KEY=
EMBEDDING_API_KEY=
LANGFUSE_TRACING_ENABLED=false
LANGFUSE_BASE_URL=https://us.cloud.langfuse.com
LANGFUSE_PUBLIC_KEY=
LANGFUSE_SECRET_KEY=
LANGFUSE_TRACING_ENVIRONMENT=prod
LANGFUSE_CAPTURE_CONTENT=false
AI_RETRIEVAL_FUSION_MODE=rrf
AI_RETRIEVAL_RERANK_ENABLED=true
AI_RETRIEVAL_RERANK_TIMEOUT_MS=8000
AI_AGENT_TOOL_CALLING_ENABLED=true
AI_AGENT_MAX_TOOL_CALLS=6
CHAT_HISTORY_CACHE_TTL=2h
FILE_DOWNLOAD_TICKET_TTL=5m
FILE_DOWNLOAD_TICKET_SINGLE_USE=true
MINERU_BACKEND=pipeline
MINERU_PARSE_PATH=/file_parse
KNOWLEDGE_GRAPH_ENABLED=true
MINERU_PARSE_METHOD=auto
MINERU_OCR=true
MINERU_ENABLE_TABLE=true
MINERU_ENABLE_FORMULA=true
SERVER_TOMCAT_THREADS_MAX=24
SERVER_TOMCAT_THREADS_MIN_SPARE=2
SERVER_TOMCAT_MAX_CONNECTIONS=256
SERVER_TOMCAT_ACCEPT_COUNT=32
MYSQL_POOL_MAX_SIZE=6
MYSQL_POOL_MIN_IDLE=1
ENV
  echo "Created $ENV_FILE"
else
  chmod 600 "$ENV_FILE"
  echo "Reusing existing $ENV_FILE"
fi

set -a
# shellcheck disable=SC1090
source "$ENV_FILE"
set +a

sudo docker volume create nexusmind_mineru-cache >/dev/null

for required_container in mysql redis minio; do
  if ! sudo docker inspect "$required_container" >/dev/null 2>&1; then
    echo "Required shared container '$required_container' is not running or does not exist." >&2
    exit 1
  fi
done

if ! sudo docker network inspect "$SHARED_NETWORK" >/dev/null 2>&1; then
  sudo docker network create "$SHARED_NETWORK" >/dev/null
  echo "Created Docker network $SHARED_NETWORK"
fi

for service in mysql redis minio; do
  if ! sudo docker inspect "$service" --format '{{json .NetworkSettings.Networks}}' | grep -q '"shared_services"'; then
    sudo docker network connect --alias "$service" "$SHARED_NETWORK" "$service"
    echo "Connected $service to $SHARED_NETWORK"
  fi
done

sudo docker exec \
  -e NEXUSMIND_DATABASE="$MYSQL_DATABASE" \
  -e NEXUSMIND_USERNAME="$MYSQL_USERNAME" \
  -e NEXUSMIND_PASSWORD="$MYSQL_PASSWORD" \
  mysql sh -ec '
    mysql -uroot -p"$MYSQL_ROOT_PASSWORD" <<SQL
CREATE DATABASE IF NOT EXISTS \`$NEXUSMIND_DATABASE\` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
SET @schema_table_count = (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = "$NEXUSMIND_DATABASE");
SET @bootstrap_sql = IF(@schema_table_count = 0, "CREATE TABLE `$NEXUSMIND_DATABASE`.nexusmind_schema_bootstrap (id TINYINT PRIMARY KEY)", "SELECT 1");
PREPARE bootstrap_stmt FROM @bootstrap_sql; EXECUTE bootstrap_stmt; DEALLOCATE PREPARE bootstrap_stmt;
CREATE USER IF NOT EXISTS "$NEXUSMIND_USERNAME"@"%" IDENTIFIED BY "$NEXUSMIND_PASSWORD";
ALTER USER "$NEXUSMIND_USERNAME"@"%" IDENTIFIED BY "$NEXUSMIND_PASSWORD";
GRANT ALL PRIVILEGES ON \`$NEXUSMIND_DATABASE\`.* TO "$NEXUSMIND_USERNAME"@"%";
FLUSH PRIVILEGES;
SQL
  '

echo "MySQL database and user are ready."

sudo docker exec \
  -e NEXUSMIND_MINIO_USER="$MINIO_ACCESS_KEY" \
  -e NEXUSMIND_MINIO_PASSWORD="$MINIO_SECRET_KEY" \
  -e NEXUSMIND_MINIO_BUCKET="$MINIO_BUCKET" \
  minio sh -ec '
    mc alias set shared http://127.0.0.1:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" >/dev/null
    mc mb --ignore-existing "shared/$NEXUSMIND_MINIO_BUCKET" >/dev/null
    mc admin user add shared "$NEXUSMIND_MINIO_USER" "$NEXUSMIND_MINIO_PASSWORD" >/dev/null 2>&1 \
      || mc admin user enable shared "$NEXUSMIND_MINIO_USER" >/dev/null
    mc admin policy attach shared readwrite --user "$NEXUSMIND_MINIO_USER" >/dev/null
  '

echo "MinIO bucket and application user are ready."
echo
echo "NexusMind initial login:"
echo "  URL:      $APP_PUBLIC_URL"
echo "  Email:    $ADMIN_EMAIL"
echo "  Password: $ADMIN_PASSWORD"
