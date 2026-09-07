#!/usr/bin/env bash

set -Eeuo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE_FILE="$ROOT/docker-compose.deploy.yml"
ENV_FILE="$ROOT/.env.deploy.local"
LOCK_FILE="/tmp/nexusmind-deploy.lock"

BACKEND_IMAGE="nexusmind-backend"
FRONTEND_IMAGE="nexusmind-web"
BACKEND_CONTAINER="nexusmind-backend"
FRONTEND_CONTAINER="nexusmind-web"
MINERU_CONTAINER="nexusmind-mineru"
BACKEND_TIMEOUT=300
FRONTEND_TIMEOUT=60
# Shell environment overrides; do not source the deployment secrets file.
FRONTEND_BUILD_HEAP_MB="${FRONTEND_BUILD_HEAP_MB:-2048}"
DEPLOY_BACKEND_IMAGE="${DEPLOY_BACKEND_IMAGE:-}"
DEPLOY_WEB_IMAGE="${DEPLOY_WEB_IMAGE:-}"

NO_CACHE=false
ACTION="deploy"
TARGET=""
START_EPOCH="$(date +%s)"
DEPLOY_STAMP="$(date +%Y%m%d-%H%M%S)"
STEP=0
TOTAL_STEPS=0
SUCCESS=false
TRANSACTION_STARTED=false
ROLLBACK_RUNNING=false
BACKEND_SWITCHED=false
FRONTEND_SWITCHED=false
MINERU_PAUSED=false
BACKEND_PREVIOUS_REF=""
FRONTEND_PREVIOUS_REF=""
BACKEND_CANDIDATE_REF=""
FRONTEND_CANDIDATE_REF=""
DOCKER=()
COMPOSE=()

usage() {
  cat <<'EOF'
用法：
  ./scripts/deploy.sh backend
  ./scripts/deploy.sh frontend
  ./scripts/deploy.sh all
  ./scripts/deploy.sh backend --no-cache
  ./scripts/deploy.sh frontend --no-cache
  ./scripts/deploy.sh all --no-cache
  ./scripts/deploy.sh rollback backend
  ./scripts/deploy.sh rollback frontend
  ./scripts/deploy.sh rollback all

说明：
  backend   重新部署或回滚后端
  frontend  重新部署或回滚前端（Compose 服务名为 web）
  all       将前后端作为同一个部署事务处理
  --no-cache  本次本地构建不复用 Docker 构建缓存

可选环境变量：
  FRONTEND_BUILD_HEAP_MB  前端构建堆上限（MiB，默认 2048）
  DEPLOY_BACKEND_IMAGE    使用已加载到本机的后端镜像，跳过构建
  DEPLOY_WEB_IMAGE        使用已加载到本机的前端镜像，跳过构建
  镜像变量请使用唯一版本标签或镜像 ID；未指定的服务仍在本机构建。
EOF
}

format_duration() {
  local seconds="$1"
  if (( seconds >= 60 )); then
    printf '%dm %ds' "$((seconds / 60))" "$((seconds % 60))"
  else
    printf '%ds' "$seconds"
  fi
}

log() { printf '[NexusMind] %s\n' "$*"; }
ok() { printf '[成功] %s\n' "$*"; }
warn() { printf '[警告] %s\n' "$*" >&2; }
fail() { printf '[失败] %s\n' "$*" >&2; return 1; }

stage_start() {
  STEP=$((STEP + 1))
  STAGE_EPOCH="$(date +%s)"
  printf '\n[NexusMind] [%d/%d] %s\n' "$STEP" "$TOTAL_STEPS" "$1"
}

stage_done() {
  local elapsed=$(( $(date +%s) - STAGE_EPOCH ))
  printf '[耗时] %s\n' "$(format_duration "$elapsed")"
}

selected() {
  [[ "$TARGET" == "$1" || "$TARGET" == "all" ]]
}

image_name() {
  case "$1" in
    backend) printf '%s' "$BACKEND_IMAGE" ;;
    frontend) printf '%s' "$FRONTEND_IMAGE" ;;
  esac
}

container_name() {
  case "$1" in
    backend) printf '%s' "$BACKEND_CONTAINER" ;;
    frontend) printf '%s' "$FRONTEND_CONTAINER" ;;
  esac
}

compose_service() {
  case "$1" in
    backend) printf 'backend' ;;
    frontend) printf 'web' ;;
  esac
}

service_timeout() {
  case "$1" in
    backend) printf '%s' "$BACKEND_TIMEOUT" ;;
    frontend) printf '%s' "$FRONTEND_TIMEOUT" ;;
  esac
}

parse_args() {
  if [[ "${1:-}" == "--help" || "${1:-}" == "-h" ]]; then
    usage
    exit 0
  fi
  if [[ "${1:-}" == "rollback" ]]; then
    ACTION="rollback"
    shift
  fi
  TARGET="${1:-}"
  if [[ ! "$TARGET" =~ ^(backend|frontend|all)$ ]]; then
    usage >&2
    exit 2
  fi
  shift
  if [[ "$ACTION" == "deploy" && $# -eq 1 && "$1" == "--no-cache" ]]; then
    NO_CACHE=true
    shift
  fi
  if [[ $# -ne 0 ]]; then
    usage >&2
    exit 2
  fi
  if [[ "$ACTION" == "deploy" ]]; then
    case "$TARGET" in
      backend) TOTAL_STEPS=6 ;;
      frontend)
        TOTAL_STEPS=6
        if [[ -z "$DEPLOY_WEB_IMAGE" ]]; then
          TOTAL_STEPS=$((TOTAL_STEPS + 1))
        fi
        ;;
      all)
        TOTAL_STEPS=8
        if [[ -z "$DEPLOY_WEB_IMAGE" ]]; then
          TOTAL_STEPS=$((TOTAL_STEPS + 1))
        fi
        ;;
    esac
  else
    [[ "$TARGET" == "all" ]] && TOTAL_STEPS=7 || TOTAL_STEPS=6
  fi
}

acquire_lock() {
  stage_start "获取部署锁"
  command -v flock >/dev/null 2>&1 || fail "缺少 flock 命令，无法保证部署互斥"
  exec 9>>"$LOCK_FILE"
  flock -n 9 || fail "另一个 NexusMind 部署正在运行：$LOCK_FILE"
  : >"$LOCK_FILE"
  printf '%s %s %s\n' "$$" "$ACTION" "$TARGET" >&9
  ok "已获取部署锁：$LOCK_FILE"
  stage_done
}

configure_docker() {
  if docker info >/dev/null 2>&1; then
    DOCKER=(docker)
  elif command -v sudo >/dev/null 2>&1 && sudo docker info >/dev/null 2>&1; then
    DOCKER=(sudo docker)
  else
    fail "Docker 不可用，或当前用户无权访问 Docker daemon"
  fi
  COMPOSE=("${DOCKER[@]}" compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE")
}

available_memory_mb() {
  awk '/^MemAvailable:/ {print int($2 / 1024)}' /proc/meminfo
}

prebuilt_image() {
  case "$1" in
    backend) printf '%s' "$DEPLOY_BACKEND_IMAGE" ;;
    frontend) printf '%s' "$DEPLOY_WEB_IMAGE" ;;
  esac
}

assert_memory_available() {
  local service="$1" available_mb required_mb=1536
  if [[ "$service" == "frontend" ]]; then
    # V8 old space is not total RSS: allow 1024 MiB for native/tools/BuildKit
    # and another 512 MiB for the OS and changes in live service usage.
    required_mb=$((FRONTEND_BUILD_HEAP_MB + 1024 + 512))
  fi
  available_mb="$(available_memory_mb)"
  if [[ -z "$available_mb" || "$available_mb" -lt "$required_mb" ]]; then
    fail "${service} 构建需要至少 ${required_mb} MiB 可用内存，当前 ${available_mb:-unknown} MiB；请在其他机器构建后通过 DEPLOY_BACKEND_IMAGE / DEPLOY_WEB_IMAGE 部署，或增加物理内存。Swap 不计入预算；未停止线上服务"
    return 1
  fi
  ok "${service} 构建内存检查：可用 ${available_mb} MiB，要求 ${required_mb} MiB"
}

assert_no_other_docker_builds() {
  local active_builds
  active_builds="$(
    ps -eo pid=,args= | awk -v self="$$" '
      $1 != self && $0 ~ /(^|[[:space:]\/])docker([[:space:]]+buildx)?[[:space:]]+build([[:space:]]|$)/ {print}
    '
  )"
  if [[ -n "$active_builds" ]]; then
    printf '[失败] 检测到其他 Docker 构建任务，本次部署已停止：\n%s\n' "$active_builds" >&2
    return 1
  fi
  ok "未检测到其他 Docker 构建任务"
}

assert_live_service() {
  local container running
  container="$(container_name "$1")"
  running="$("${DOCKER[@]}" inspect --format '{{.State.Running}}' "$container" 2>/dev/null || true)"
  [[ "$running" == "true" ]] || fail "当前线上容器未运行：$container；该脚本只处理已有部署的重新上线"
  ok "当前线上容器正在运行：$container"
}

preflight() {
  stage_start "执行部署前检查"
  command -v docker >/dev/null 2>&1 || fail "缺少 docker 命令"
  [[ -f "$ENV_FILE" ]] || fail "缺少 $ENV_FILE；首次部署请先运行 scripts/prepare-deployment.sh"
  [[ -f "$COMPOSE_FILE" ]] || fail "缺少 $COMPOSE_FILE"
  configure_docker
  ok "Docker 可用"
  "${COMPOSE[@]}" config -q
  ok "Compose 配置有效"
  if [[ "$ACTION" == "deploy" ]]; then
    local service ref
    for service in backend frontend; do
      selected "$service" || continue
      ref="$(prebuilt_image "$service")"
      if [[ -n "$ref" ]]; then
        "${DOCKER[@]}" image inspect "$ref" >/dev/null || fail "本机不存在镜像 $ref；请先 docker load 或 docker pull"
        ok "${service} 将使用预构建镜像：$ref"
      else
        if [[ "$service" == "frontend" ]]; then
          [[ "$FRONTEND_BUILD_HEAP_MB" =~ ^[1-9][0-9]{0,5}$ ]] || fail "FRONTEND_BUILD_HEAP_MB 必须为正整数（MiB，最多六位）"
        fi
        "${DOCKER[@]}" buildx version >/dev/null 2>&1 || fail "缺少 Docker Buildx；请先安装 docker-buildx"
        assert_no_other_docker_builds
        if [[ "$service" == "frontend" ]]; then
          ok "前端构建将在暂停 MinerU 后检查可用内存"
        else
          assert_memory_available "$service"
        fi
      fi
    done
    [[ "$NO_CACHE" == true ]] && ok "本次本地构建已禁用 Docker 构建缓存"
    ok "所需构建将串行执行；全部检查通过后才开始"
  fi

  selected backend && assert_live_service backend
  selected frontend && assert_live_service frontend
  stage_done
}

build_service() {
  local service="$1" image context candidate label ref
  local build_args=()
  image="$(image_name "$service")"
  if [[ "$service" == "backend" ]]; then
    context="$ROOT/backend"
    label="后端"
  else
    context="$ROOT/frontend"
    label="前端"
  fi
  candidate="$image:candidate-$DEPLOY_STAMP"
  ref="$(prebuilt_image "$service")"
  stage_start "准备${label}候选镜像"
  if [[ -n "$ref" ]]; then
    "${DOCKER[@]}" image tag "$ref" "$candidate"
  else
    assert_no_other_docker_builds
    assert_memory_available "$service"
    [[ "$NO_CACHE" == true ]] && build_args+=(--no-cache)
    if [[ "$service" == "frontend" ]]; then
      build_args+=(--build-arg "FRONTEND_BUILD_HEAP_MB=$FRONTEND_BUILD_HEAP_MB")
    fi
    printf '[信息] Docker BuildKit 进度如下\n'
    "${DOCKER[@]}" buildx build --load "${build_args[@]}" --tag "$candidate" "$context"
  fi
  if [[ "$service" == "backend" ]]; then
    BACKEND_CANDIDATE_REF="$candidate"
  else
    FRONTEND_CANDIDATE_REF="$candidate"
  fi
  ok "${label}候选镜像构建完成：$candidate"
  stage_done
}

pause_mineru_for_frontend_build() {
  local running
  running="$("${DOCKER[@]}" inspect --format '{{.State.Running}}' "$MINERU_CONTAINER" 2>/dev/null || true)"
  if [[ "$running" != "true" ]]; then
    warn "$MINERU_CONTAINER 当前未运行，本次部署不会改变其状态"
    return 0
  fi

  stage_start "暂停 MinerU 以释放前端构建内存"
  MINERU_PAUSED=true
  "${DOCKER[@]}" stop "$MINERU_CONTAINER" >/dev/null
  ok "已暂停 $MINERU_CONTAINER"
  stage_done
}

resume_mineru() {
  [[ "$MINERU_PAUSED" == true ]] || return 0
  printf '[恢复] 启动 %s\n' "$MINERU_CONTAINER"
  "${DOCKER[@]}" start "$MINERU_CONTAINER" >/dev/null || return 1
  MINERU_PAUSED=false
  ok "$MINERU_CONTAINER 已启动"
}

save_live_service() {
  local service="$1" image container live_image_id backup_ref
  image="$(image_name "$service")"
  container="$(container_name "$service")"
  live_image_id="$("${DOCKER[@]}" inspect --format '{{.Image}}' "$container")"
  backup_ref="$image:rollback-$DEPLOY_STAMP"
  "${DOCKER[@]}" image tag "$live_image_id" "$backup_ref"
  if [[ "$service" == "backend" ]]; then
    BACKEND_PREVIOUS_REF="$backup_ref"
  else
    FRONTEND_PREVIOUS_REF="$backup_ref"
  fi
  ok "$container -> $backup_ref"
}

save_live_versions() {
  stage_start "保存当前线上版本"
  selected backend && save_live_service backend
  selected frontend && save_live_service frontend
  TRANSACTION_STARTED=true
  stage_done
}

wait_for_healthy() {
  local service="$1" container timeout elapsed=0 status=""
  container="$(container_name "$service")"
  timeout="$(service_timeout "$service")"
  printf '[信息] 等待 %s 健康检查，超时 %ss……\n' "$container" "$timeout"
  while (( elapsed < timeout )); do
    status="$("${DOCKER[@]}" inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{if .State.Running}}running{{else}}stopped{{end}}{{end}}' "$container" 2>/dev/null || true)"
    case "$status" in
      healthy) ok "$container 状态：healthy"; return 0 ;;
      unhealthy|stopped)
        fail "$container 状态：$status"
        return 1
        ;;
    esac
    sleep 3
    elapsed=$((elapsed + 3))
  done
  fail "$container 未在 ${timeout}s 内通过健康检查（最后状态：${status:-unknown}）"
}

switch_to_ref() {
  local service="$1" source_ref="$2" image compose_name
  image="$(image_name "$service")"
  compose_name="$(compose_service "$service")"
  "${DOCKER[@]}" image tag "$source_ref" "$image:local" || return 1
  if [[ "$service" == "backend" ]]; then
    BACKEND_SWITCHED=true
  else
    FRONTEND_SWITCHED=true
  fi
  "${COMPOSE[@]}" up -d --no-deps --no-build --force-recreate "$compose_name" || return 1
  wait_for_healthy "$service" || return 1
}

deploy_service() {
  local service="$1" candidate label
  if [[ "$service" == "backend" ]]; then
    candidate="$BACKEND_CANDIDATE_REF"; label="后端"
  else
    candidate="$FRONTEND_CANDIDATE_REF"; label="前端"
  fi
  stage_start "部署并检查$label"
  switch_to_ref "$service" "$candidate"
  stage_done
}

latest_rollback_ref() {
  local service="$1" image refs=()
  image="$(image_name "$service")"
  mapfile -t refs < <(
    "${DOCKER[@]}" image ls "$image" --format '{{.Repository}}:{{.Tag}}' |
      awk -v prefix="$image:rollback-" 'index($0, prefix) == 1' | sort -r
  )
  [[ ${#refs[@]} -gt 0 ]] || return 1
  printf '%s' "${refs[0]}"
}

resolve_rollback_targets() {
  stage_start "查找最近的历史版本"
  if selected backend; then
    BACKEND_CANDIDATE_REF="$(latest_rollback_ref backend)" || fail "没有可用的后端回滚镜像"
    ok "后端回滚目标：$BACKEND_CANDIDATE_REF"
  fi
  if selected frontend; then
    FRONTEND_CANDIDATE_REF="$(latest_rollback_ref frontend)" || fail "没有可用的前端回滚镜像"
    ok "前端回滚目标：$FRONTEND_CANDIDATE_REF"
  fi
  stage_done
}

rollback_service_stage() {
  local service="$1" target_ref label
  if [[ "$service" == "backend" ]]; then
    target_ref="$BACKEND_CANDIDATE_REF"; label="后端"
  else
    target_ref="$FRONTEND_CANDIDATE_REF"; label="前端"
  fi
  stage_start "恢复并检查$label"
  switch_to_ref "$service" "$target_ref"
  stage_done
}

restore_previous_service() {
  local service="$1" previous_ref
  if [[ "$service" == "backend" ]]; then
    previous_ref="$BACKEND_PREVIOUS_REF"
  else
    previous_ref="$FRONTEND_PREVIOUS_REF"
  fi
  [[ -n "$previous_ref" ]] || return 0
  printf '[回滚] 恢复 %s\n' "$previous_ref"
  switch_to_ref "$service" "$previous_ref"
}

restore_transaction() {
  [[ "$TRANSACTION_STARTED" == true && "$ROLLBACK_RUNNING" == false ]] || return 0
  ROLLBACK_RUNNING=true
  set +e
  printf '\n[回滚] 开始恢复本次操作前的线上版本\n'
  local failed=false
  if [[ "$BACKEND_SWITCHED" == true ]]; then restore_previous_service backend || failed=true; fi
  if [[ "$FRONTEND_SWITCHED" == true ]]; then restore_previous_service frontend || failed=true; fi
  if [[ "$failed" == true ]]; then
    printf '[失败] 自动恢复未完全成功，请立即人工检查容器状态\n' >&2
  else
    printf '[成功] 本次操作前的线上版本已恢复\n'
  fi
  set -e
}

remove_image_ref() {
  if "${DOCKER[@]}" image rm "$1" >/dev/null 2>&1; then
    printf '[删除] %s\n' "$1"
  else
    warn "无法删除镜像标签（可能仍被容器使用）：$1"
  fi
}

cleanup_service_images() {
  local service="$1" image rollback_refs=() candidate_refs=() index candidate_ref
  image="$(image_name "$service")"
  mapfile -t rollback_refs < <(
    "${DOCKER[@]}" image ls "$image" --format '{{.Repository}}:{{.Tag}}' |
      awk -v prefix="$image:rollback-" 'index($0, prefix) == 1' | sort -r
  )
  for ((index = 2; index < ${#rollback_refs[@]}; index++)); do
    remove_image_ref "${rollback_refs[$index]}"
  done
  mapfile -t candidate_refs < <(
    "${DOCKER[@]}" image ls "$image" --format '{{.Repository}}:{{.Tag}}' |
      awk -v prefix="$image:candidate-" 'index($0, prefix) == 1'
  )
  for candidate_ref in "${candidate_refs[@]}"; do
    remove_image_ref "$candidate_ref"
  done
}

cleanup_selected_images() {
  stage_start "清理 NexusMind 旧镜像"
  selected backend && cleanup_service_images backend
  selected frontend && cleanup_service_images frontend
  ok "每个已选服务保留当前版本及最近 2 个历史版本"
  stage_done
}

cleanup_candidates_after_failure() {
  local ref
  for ref in "$BACKEND_CANDIDATE_REF" "$FRONTEND_CANDIDATE_REF"; do
    [[ "$ref" == *":candidate-"* ]] || continue
    "${DOCKER[@]}" image rm "$ref" >/dev/null 2>&1 || true
  done
}

print_summary() {
  local result="$1" elapsed
  elapsed=$(( $(date +%s) - START_EPOCH ))
  printf '\n==================================================\n'
  printf '[NexusMind] %s\n' "$result"
  printf '操作：%s %s\n' "$ACTION" "$TARGET"
  if [[ ${#DOCKER[@]} -gt 0 ]]; then
    selected backend && printf '后端：%s\n' "$("${DOCKER[@]}" inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$BACKEND_CONTAINER" 2>/dev/null || printf 'unknown')"
    selected frontend && printf '前端：%s\n' "$("${DOCKER[@]}" inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$FRONTEND_CONTAINER" 2>/dev/null || printf 'unknown')"
  fi
  printf '总耗时：%s\n' "$(format_duration "$elapsed")"
  printf '==================================================\n'
}

on_exit() {
  local status=$?
  trap - EXIT INT TERM
  if [[ "$status" -ne 0 && "$SUCCESS" != true ]]; then
    if ! resume_mineru; then
      printf '[失败] MinerU 自动恢复失败，请立即执行：%s start %s\n' "${DOCKER[*]}" "$MINERU_CONTAINER" >&2
    fi
    restore_transaction
    cleanup_candidates_after_failure
    if [[ "$BACKEND_SWITCHED" == true ]]; then
      printf '[诊断] 后端日志：%s logs --tail 200 %s\n' "${DOCKER[*]}" "$BACKEND_CONTAINER" >&2
    fi
    if [[ "$FRONTEND_SWITCHED" == true ]]; then
      printf '[诊断] 前端日志：%s logs --tail 200 %s\n' "${DOCKER[*]}" "$FRONTEND_CONTAINER" >&2
    fi
    print_summary "操作失败"
  fi
  exit "$status"
}

run_deploy() {
  selected backend && build_service backend
  if selected frontend; then
    if [[ -z "$(prebuilt_image frontend)" ]]; then
      pause_mineru_for_frontend_build
      build_service frontend
      resume_mineru
    else
      build_service frontend
    fi
  fi
  save_live_versions
  selected backend && deploy_service backend
  selected frontend && deploy_service frontend
  cleanup_selected_images
}

run_rollback() {
  resolve_rollback_targets
  save_live_versions
  selected backend && rollback_service_stage backend
  selected frontend && rollback_service_stage frontend
  cleanup_selected_images
}

main() {
  parse_args "$@"
  trap on_exit EXIT
  trap 'exit 130' INT TERM
  log "操作：$ACTION $TARGET"
  log "开始时间：$(date '+%F %T')"
  acquire_lock
  preflight
  if [[ "$ACTION" == "deploy" ]]; then run_deploy; else run_rollback; fi
  SUCCESS=true
  print_summary "操作成功"
}

main "$@"
