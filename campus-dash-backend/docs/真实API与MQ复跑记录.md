# 真实 API / MQ 完整复跑

日期：2026-10-20。本轮执行 `campus-dash-frontend/tests/live_api_smoke.py` 的完整真实 HTTP、并发及 MQ 场景；由 `scripts/verify-live-api-mq.py` 创建隔离环境、执行脚本、核对数据库不变式并回收本次进程和容器。

**最终结果：完整通过，未发现业务回归**。最终运行 `20261020-210214-5f5b` 的 31 项真实 API/MQ 检查、14 项流程/数据库检查、资源清理校验均通过。详见本文末尾与 [最终运行报告](../verification/live-api-mq/20261020-210214-5f5b/run-report.json)。

## 隔离及执行方式

- 每轮创建独立 MySQL schema，复用本机 MySQL 8.0.40 服务；Redis 7.4、RocketMQ 5.3.1 的 Broker/NameServer 均为本轮新容器和独立网络。
- 使用已有打包 JAR，保留 SHA-256。API 与 worker 分别启动，使用不同 Snowflake worker ID。
- API 与 worker 都使用 Asia/Shanghai 数据库会话时区，确认超时 30 秒，送达自动结算 45 秒。
- 超时和自动结算兜底扫描间隔均为 600000 毫秒；本轮窗口要求没有扫描处理记录，三个自动流程必须有对应 MQ 消费证据。
- 保持默认跑腿共享限流及本机在途准入；不关闭限流以使脚本通过。
- 专属测试账户只在初始化时建数。业务发布、抢单、确认、结算、退款、仲裁均经 HTTP 执行；运行中不通过 SQL 修改订单或缩短截止时间。
- 验证后保留数据库、HTTP 报告、API/worker 日志、MQ 日志与启动脚本快照。仅停止本轮启动的 Java 进程，删除本轮新容器和网络。

从后端目录执行：

```bash
python3 scripts/verify-live-api-mq.py
```

前提：`dash-mysql` 可用，本机已有 `redis:7.4-alpine` 和 `apache/rocketmq:5.3.1` 镜像，API/worker JAR 已由当前源码打包；修改依赖模块后先全量 `mvn install`。

## 首次冷启动失败与修复

第一轮 `20261020-203700-cf3c` 在业务验收开始之前失败。Broker 已监听端口，但尚未向 NameServer 注册，`init-mq.sh` 的建 Topic 命令输出 `[error] Make sure the specified clusterName exists...`，却返回退出码 0。旧脚本只依赖 `set -e`，继续启动 worker，最终因 `errand-auto-settle` 无路由而失败。

修复 `docker/init-mq.sh`：有界等待指定集群的真实 Master 注册；管理命令设超时并识别输出中的明确错误/异常；初始化结束后确认四个 Topic 都有对应集群的 Master 地址及可读写队列。八项隔离 mock Docker 自检覆盖延迟注册、错误输出、无效路由、错误集群与超时，不操作现有中间件。

首次失败证据：[启动报告](../verification/live-api-mq/20261020-203700-cf3c/run-report.json)、[初始化输出](../verification/live-api-mq/20261020-203700-cf3c/mq-init.log)、[worker 日志](../verification/live-api-mq/20261020-203700-cf3c/worker.log)。该轮未执行业务用例，不能计入 API 测试数量。

## 验收脚本修正

并发抢单检查现在同时核对 HTTP 状态、业务码、原请求 UUID、持久状态、轮次和 Retry-After 响应头，只接受协议约定的业务拒绝与退避，不能把任意 4xx/5xx 当成正常结果。增加赢家原 UUID 重放、结果查询两项检查；遇到合法限流时按响应头退避，保持相同 UUID 和轮次。

第二轮 `20261020-205050-c20b` 冷启动成功，业务已完成自动结算，但第 30 项日志证据检查失败：本机 Java 的 stdout/stderr 原生编码为 US-ASCII，中文日志变成问号，脚本按中文文案匹配不到已有的 `AutoSettleConsumer result=SETTLED`。修复为显式 UTF-8 日志输出，并按消费者类名、完整订单 ID 与结构化结果匹配；扫描来源同样按类名识别。不能把其他消费者或 `ALREADY_SETTLED` 视为本次 MQ 自动结算证据。

第二轮失败证据：[API 报告](../verification/live-api-mq/20261020-205050-c20b/api-results/api-report.json)、[worker 日志](../verification/live-api-mq/20261020-205050-c20b/worker.log)。该轮 29 项成功后失败，不计作完整通过。

收尾复核补齐启动器的失败路径：Docker 可能创建容器后启动失败，必须在调用前登记，并用 run label 核验资源归属；删除命令返回值和删除后不存在的检查都要成功。报告独立保留 `businessPassed` 与 `cleanup`，资源清理失败时整体退出非 0。另增加全部六笔订单/托管的预期终态、没有残留 PROCESSING 请求两项检查，避免候选转单后最终结算仅返回冲突却漏判。

定向验证包括：MQ 初始化 8 组 mock、HTTP 契约 13 个正反例、[MQ 日志识别 7 个正反例](../verification/live-api-mq/mq-proof-validation-20261020.json)、资源清理 4 组 mock（正常删除、删除失败、拒绝删除其他 run、创建成功但启动失败）。均通过；这些检查与真实 HTTP/MQ 执行数量分开记录。

## 完整通过结果

第三轮 `20261020-205442-c540` 在全新环境冷启动并完整通过，HTTP 验收窗口为 20:56:04–20:56:54。**31 项 API/MQ 检查全部成功**，共 238 次真实 HTTP 请求；包装脚本的 12 项流程与数据库检查也全部通过。这 12 项包含对 API 整体通过和 MQ 证据的重复确认，不与 31 项相加宣称独立用例数。

| 场景 | 实际结果 |
|---|---|
| 20 请求并发抢单 | 1 次成功、3 次 SLOT_FULL、16 次 GRAB_RATE_LIMITED；数据库每订单每轮最多一个占用事实 |
| 原 UUID 重放与查询 | 结果与首次成功一致，重放带 replayed=true |
| 20 请求并发结算 | 1 次 SETTLED、19 次 CONFLICT；只入账 950 分，信用只增加一次 |
| 20 请求并发取消 | 1 次 REFUNDED、19 次 CONFLICT；只退款 777 分 |
| 权限与争议仲裁 | 系统账户登录被拒绝、越权结算/仲裁被拒绝、重复裁决不重复入账 |
| 确认超时转候选 | MQ 将 round=0 的 12001 转给 round=1 的 12002；后者可确认并完成订单 |
| 空候选超时释放 | MQ 回退 PUBLISHED，round=1、slotTaken=0、grabberId=-1 |
| 送达超时自动结算 | MQ 结算到账 528 分；后续手动结算返回 ALREADY_SETTLED，不重复入账 |

三个自动流程均有对应订单 ID 的 MQ 消费成功日志；本轮业务窗口没有超时/自动结算兜底扫描记录。数据库状态日志与本地消息截止时间对照如下，均未提前执行：

| MQ 流程 | 截止时间 | 状态落库时间 | 到期后延迟 |
|---|---|---|---|
| 转候选 | 20:56:38.524 | 20:56:39.073 | 549 毫秒 |
| 空候选回池 | 20:56:38.623 | 20:56:39.073 | 450 毫秒 |
| 自动结算 | 20:56:53.762 | 20:56:54.106 | 344 毫秒 |

资金检查：9 个账户总额始终为 200000 分，冻结额 0；逐业务借贷平衡、托管余额与 HELD 悬赏一致、订单与托管终态一致、逐账户余额减流水净额等于建数基线。发单人最终 97612 分，两位跑腿分别 1953、317 分，平台佣金 118 分。

证据：[完整运行报告](../verification/live-api-mq/20261020-205442-c540/run-report.json)、[HTTP 请求与断言](../verification/live-api-mq/20261020-205442-c540/api-results/api-report.json)、[验收输出](../verification/live-api-mq/20261020-205442-c540/script.log)、[worker 业务窗口](../verification/live-api-mq/20261020-205442-c540/api-results/worker-test-window.log)、[资金 SQL 结果](../verification/live-api-mq/20261020-205442-c540/fund-invariants.tsv)。数据库 `campus_dash_verify_live_20261020_205442_c540` 保留；本轮两条 Java 进程、三个容器和网络均已独立核对不存在，原有四个中间件容器保持 healthy。

本轮未改 Java 业务代码；新增独立验收启动器并修复 MQ 初始化、HTTP 契约验证及日志证据识别。此前 219 项后端测试是上轮全量结果，不计入本轮执行数量。本次未重跑浏览器、WebSocket 或独立网关验收。

## 最终脚本复验（21:02–21:04）

补齐资源清理失败判定和终态断言后，再从全新环境执行第四轮 `20261020-210214-5f5b`。最终退出码 0，`businessPassed=true`、`cleanup.passed=true`、`passed=true`。脚本快照与当前源码逐字节一致。

- HTTP 窗口 21:03:25–21:04:16，共 240 次请求，31 项检查全部通过。20 并发抢单仍为 1 成功、3 SLOT_FULL、16 GRAB_RATE_LIMITED；20 并发结算和取消各只有一次实际资金效果。
- 14 项包装检查全部通过，包括新增六笔订单/托管终态和未决请求检查：4 笔 SETTLED/RELEASED、2 笔 CANCELLED/REFUNDED，PROCESSING 为 0。包装检查与 API 检查有交集，不合并计数。
- 三个自动流程均有真实 MQ 消费证据；转候选、空候选回池、自动结算分别在截止时间后 946、865、1012 毫秒落库，没有扫描替代或提前执行。
- 账户总额保持 200000 分，逐业务借贷平衡，托管余额为 0。两位跑腿最终为 1003、1267 分，差异来自本轮并发赢家变为 12002；发单人 97612 分、平台佣金 118 分。
- 两个 Java 进程均已停止；三个本轮容器和网络按归属标签删除，并逐一确认不存在。清理错误列表为空，原有 MySQL/Redis/Broker/NameServer 保持 healthy。
- API/worker 业务窗口无 ERROR；仅有本机 Netty 缺少 macOS 原生 DNS provider 后回退系统解析器的启动 WARN。本轮连接使用本机 IP，实际 MQ 流程成功。

证据：[最终运行报告](../verification/live-api-mq/20261020-210214-5f5b/run-report.json)、[最终 HTTP 报告](../verification/live-api-mq/20261020-210214-5f5b/api-results/api-report.json)、[最终验收输出](../verification/live-api-mq/20261020-210214-5f5b/script.log)、[MQ 业务窗口](../verification/live-api-mq/20261020-210214-5f5b/api-results/worker-test-window.log)、[资金结果](../verification/live-api-mq/20261020-210214-5f5b/fund-invariants.tsv)、[源码快照及清理独立核验](../verification/live-api-mq/20261020-210214-5f5b/final-verification.json)。数据库 `campus_dash_verify_live_20261020_210214_5f5b` 保留供追溯。
