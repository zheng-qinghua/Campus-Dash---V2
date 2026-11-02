-- 跨轮对比：支撑"改动前后同档位对比"
-- 先关闭 shell 调试回显，再从已有环境变量传入口令：
--   set +x
--   export MYSQL_PWD="${SPRING_DATASOURCE_PASSWORD:?请先设置数据库口令环境变量}"
-- 用法：sed -e "s/__RUN_A__/20260818-014200/" -e "s/__RUN_B__/20260818-021500/" compare_runs.sql | docker exec -i -e MYSQL_PWD dash-mysql mysql -uroot -D campus_dash

SET @a = '__RUN_A__';
SET @b = '__RUN_B__';

SELECT '===== 轮次对比 =====' AS header;
SELECT run_id, scenario, status, concurrency,
       TIMESTAMPDIFF(SECOND, started_at, finished_at) AS duration_s,
       JSON_UNQUOTE(JSON_EXTRACT(summary, '$.success'))     AS success,
       JSON_UNQUOTE(JSON_EXTRACT(summary, '$.errors'))      AS errors,
       JSON_UNQUOTE(JSON_EXTRACT(summary, '$.p99Ms'))       AS p99_ms,
       JSON_UNQUOTE(JSON_EXTRACT(summary, '$.throughput'))  AS throughput,
       JSON_UNQUOTE(JSON_EXTRACT(summary, '$.oversold'))    AS oversold
  FROM bench_run WHERE run_id IN (@a, @b) ORDER BY started_at;

SELECT '===== 关键指标差异（B 相对 A）=====' AS header;
SELECT
  CAST(JSON_EXTRACT(b.summary, '$.p99Ms') AS SIGNED) - CAST(JSON_EXTRACT(a.summary, '$.p99Ms') AS SIGNED) AS p99_delta_ms,
  CAST(JSON_EXTRACT(b.summary, '$.throughput') AS SIGNED) - CAST(JSON_EXTRACT(a.summary, '$.throughput') AS SIGNED) AS throughput_delta,
  CAST(JSON_EXTRACT(b.summary, '$.errors') AS SIGNED) - CAST(JSON_EXTRACT(a.summary, '$.errors') AS SIGNED) AS errors_delta
  FROM bench_run a, bench_run b WHERE a.run_id = @a AND b.run_id = @b;

SELECT '===== 每轮产生的数据量 =====' AS header;
SELECT run_id, COUNT(*) AS errand_count
  FROM bench_run_item WHERE run_id IN (@a, @b) AND entity_type = 'ERRAND' GROUP BY run_id;
