#!/usr/bin/env bash
# 为整轮验收创建独立数据库；保留结果以便复核，不清理共享业务表。
set +x
set -euo pipefail
: "${SPRING_DATASOURCE_PASSWORD:?必须先设置非空环境变量 SPRING_DATASOURCE_PASSWORD}"
if [[ -z "${SPRING_DATASOURCE_PASSWORD//[[:space:]]/}" ]]; then
  printf '%s\n' 'SPRING_DATASOURCE_PASSWORD 不能只包含空白字符' >&2
  exit 1
fi
export MYSQL_PWD="$SPRING_DATASOURCE_PASSWORD"
# 仅本轮验收缺少 JWT 密钥时生成随机密钥；保留调用者已有配置，不写入文件或回显。
# 生产启动入口不会自动生成密钥，仍须由部署环境显式提供。
if [[ -z "${DASH_JWT_SECRET:-}" ]]; then
  DASH_JWT_SECRET="$(python3 -c 'import secrets; print(secrets.token_hex(32))')"
fi
export DASH_JWT_SECRET
cd "$(dirname "$0")/.."
TASK_DB="campus_dash_verify_$(date +%Y%m%d_%H%M%S)"
TASK_CONTAINER="${MYSQL_CONTAINER:-dash-mysql}"
TASK_MVN="${MVN_BIN:-mvn}"
docker exec -e MYSQL_PWD "$TASK_CONTAINER" mysql -uroot -e "CREATE DATABASE \`$TASK_DB\` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci"
docker exec -i -e MYSQL_PWD "$TASK_CONTAINER" mysql -uroot "$TASK_DB" < docker/init.sql
mkdir -p verification/grab-v2
printf 'database=%s\n' "$TASK_DB" > verification/grab-v2/latest-run.txt
"$TASK_MVN" -DrequireMiddleware=true "-DrequireMQ=${REQUIRE_MQ:-false}" \
  "-Dspring.datasource.url=jdbc:mysql://127.0.0.1:3307/$TASK_DB?useSSL=false&serverTimezone=Asia/Shanghai" \
  install "$@" 2>&1 | tee "verification/grab-v2/$TASK_DB.log"
printf '\n验收数据库保留：%s；逐项结果见各模块 target/surefire-reports。\n' "$TASK_DB"
