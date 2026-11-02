#!/usr/bin/env bash
# RocketMQ 冷启动初始化：等待集群注册，再创建 topic/消费组并验证真实路由。
# mqadmin 某些失败只打印 [error] 却退出 0，因此不能只依赖 set -e。
set -euo pipefail

BROKER_CONTAINER="${BROKER_CONTAINER:-dash-rmqbroker}"
NAMESRV="${NAMESRV:-rmqnamesrv:9876}"
CLUSTER="${CLUSTER:-DefaultCluster}"
TOPIC="${TOPIC:-errand-confirm-timeout}"
TOPIC_AUTO_SETTLE="${TOPIC_AUTO_SETTLE:-errand-auto-settle}"
TOPIC_FUND_EVENT="${TOPIC_FUND_EVENT:-errand-fund-event}"
TOPIC_CACHE_EVICT="${TOPIC_CACHE_EVICT:-errand-cache-evict}"
GROUP="${GROUP:-dash-timeout-consumer}"
MQ_INIT_TIMEOUT_SECONDS="${MQ_INIT_TIMEOUT_SECONDS:-90}"
MQ_ADMIN_TIMEOUT_SECONDS="${MQ_ADMIN_TIMEOUT_SECONDS:-15}"
MQ_INIT_RETRY_SECONDS="${MQ_INIT_RETRY_SECONDS:-1}"

command -v python3 >/dev/null || { echo '初始化需要 python3，用于命令超时和路由 JSON 校验。' >&2; exit 1; }
for setting in MQ_INIT_TIMEOUT_SECONDS MQ_ADMIN_TIMEOUT_SECONDS MQ_INIT_RETRY_SECONDS; do
  if [[ ! "${!setting}" =~ ^[1-9][0-9]*$ ]]; then
    echo "$setting 必须为正整数秒。" >&2
    exit 1
  fi
done

admin() {
  local output rc=0
  output=$(python3 - "$BROKER_CONTAINER" "$MQ_ADMIN_TIMEOUT_SECONDS" "$@" <<'PY'
import subprocess
import sys

container, timeout, *arguments = sys.argv[1:]
# 所有配置均作为独立参数传递，不将 topic/地址插入 shell 程序。
command = ['docker', 'exec', container, 'sh', '-c',
           'cd /home/rocketmq/rocketmq-*/bin && sh mqadmin "$@"', 'mqadmin', *arguments]
try:
    result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                            text=True, timeout=int(timeout))
    print(result.stdout, end='')
    sys.exit(result.returncode)
except subprocess.TimeoutExpired as error:
    output = error.stdout or b''
    print(output.decode(errors='replace') if isinstance(output, bytes) else output, end='')
    print(f'\n[error] mqadmin 命令超过 {timeout} 秒，已终止等待。')
    sys.exit(124)
PY
  ) || rc=$?
  printf '%s\n' "$output"
  if (( rc != 0 )); then
    echo "mqadmin 执行失败：$1，退出码=$rc" >&2
    return "$rc"
  fi
  if printf '%s\n' "$output" | LC_ALL=C grep -Eiq '\[error\]|(^|[[:space:]])ERROR([[:space:]:]|$)|[[:alnum:]_.$]*(Exception|Error)(:|[[:space:]]|$)|Caused by:'; then
    echo "mqadmin 输出错误，拒绝将退出码 0 当作成功：$1" >&2
    return 1
  fi
}

registered_master() {
  # clusterList 的数据行顺序：cluster、brokerName、brokerId、brokerAddr。
  # 表头或空集群不算就绪，必须出现指定集群的实际 master 地址。
  awk -v cluster="$CLUSTER" '$1 == cluster && $3 == "0" && $4 ~ /:[0-9]+$/ { ready=1 } END { exit !ready }'
}

valid_topic_route() {
  python3 -c '
import json
import re
import sys

text = sys.stdin.read()
start = text.find("{")
if start < 0:
    sys.exit(1)
try:
    # mqadmin 的 Fastjson 版本可能将 brokerAddrs 的数字键输出为不带引号的 0。
    raw = re.sub(r"([{,]\s*)([0-9]+)(\s*:)", lambda m: m[1] + chr(34) + m[2] + chr(34) + m[3], text[start:])
    route, _ = json.JSONDecoder().raw_decode(raw)
    brokers = set()
    for broker in route.get("brokerDatas", []):
        addresses = broker.get("brokerAddrs") or {}
        master = addresses.get("0")
        if broker.get("cluster") == sys.argv[1] and isinstance(master, str) and re.search(r":[0-9]+$", master):
            brokers.add(broker.get("brokerName"))
    valid = any(queue.get("brokerName") in brokers
                and int(queue.get("readQueueNums", 0)) > 0
                and int(queue.get("writeQueueNums", 0)) > 0
                and int(queue.get("perm", 0)) & 6 == 6
                for queue in route.get("queueDatas", []))
except (ValueError, TypeError, AttributeError):
    valid = False
sys.exit(0 if valid else 1)
' "$CLUSTER"
}

wait_for_cluster() {
  local deadline=$((SECONDS + MQ_INIT_TIMEOUT_SECONDS)) snapshot=''
  echo "等待集群 $CLUSTER 的 Broker 向 NameServer $NAMESRV 注册（最多 ${MQ_INIT_TIMEOUT_SECONDS} 秒）……"
  while true; do
    if snapshot=$(admin clusterList -n "$NAMESRV" 2>&1) && printf '%s\n' "$snapshot" | registered_master; then
      echo "集群已注册，开始初始化。"
      printf '%s\n' "$snapshot"
      return 0
    fi
    if (( SECONDS >= deadline )); then
      echo "等待集群注册超时，未创建任何 topic/消费组。最后响应：" >&2
      printf '%s\n' "$snapshot" >&2
      return 1
    fi
    sleep "$MQ_INIT_RETRY_SECONDS"
  done
}

wait_for_topic_route() {
  local topic="$1" deadline=$((SECONDS + MQ_INIT_TIMEOUT_SECONDS)) snapshot=''
  while true; do
    if snapshot=$(admin topicRoute -n "$NAMESRV" -t "$topic" 2>&1) && printf '%s\n' "$snapshot" | valid_topic_route; then
      echo "路由已确认：$topic（指定集群存在可读写队列及 master 地址）"
      return 0
    fi
    if (( SECONDS >= deadline )); then
      echo "topic 路由验证超时：$topic。最后响应：" >&2
      printf '%s\n' "$snapshot" >&2
      return 1
    fi
    sleep "$MQ_INIT_RETRY_SECONDS"
  done
}

wait_for_cluster

for topic in "$TOPIC" "$TOPIC_AUTO_SETTLE" "$TOPIC_CACHE_EVICT"; do
  echo "创建 DELAY topic：$topic"
  admin updateTopic -n "$NAMESRV" -c "$CLUSTER" -t "$topic" -a +message.type=DELAY
done
# 事务消息必须使用 TRANSACTION 类型，不能建成 NORMAL 或 DELAY。
echo "创建 TRANSACTION topic：$TOPIC_FUND_EVENT"
admin updateTopic -n "$NAMESRV" -c "$CLUSTER" -t "$TOPIC_FUND_EVENT" -a +message.type=TRANSACTION

for group in "$GROUP" dash-autosettle-consumer dash-fund-event-consumer dash-cache-evict-consumer; do
  echo "创建消费组：$group"
  admin updateSubGroup -n "$NAMESRV" -c "$CLUSTER" -g "$group"
done

for topic in "$TOPIC" "$TOPIC_AUTO_SETTLE" "$TOPIC_FUND_EVENT" "$TOPIC_CACHE_EVICT"; do
  wait_for_topic_route "$topic"
done
echo 'RocketMQ 初始化完成：4 个 topic 的真实路由均已确认。'
