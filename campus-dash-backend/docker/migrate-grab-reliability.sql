-- v2 幂等抢单与占位恢复。单库部署，所有新业务表保留 campus_id。
-- 可重复执行；不删除已有数据，不伪造历史请求。运行前备份并暂停旧协议写入。
SET NAMES utf8mb4;
CREATE TABLE IF NOT EXISTS schema_migration (
  version VARCHAR(96) NOT NULL PRIMARY KEY,
  applied_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  description VARCHAR(255) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS grab_request (
  id BIGINT NOT NULL PRIMARY KEY,
  campus_id BIGINT NOT NULL,
  errand_id BIGINT NOT NULL,
  runner_id BIGINT NOT NULL,
  operation VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  request_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  payload_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  request_round INT NOT NULL,
  base_errand_version BIGINT NOT NULL,
  status VARCHAR(16) NOT NULL,
  reservation_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  lease_until DATETIME(3) NOT NULL,
  result_code VARCHAR(64) NULL,
  http_status SMALLINT NULL,
  result_json JSON NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  completed_at DATETIME(3) NULL,
  UNIQUE KEY uk_grab_request_scope(campus_id,runner_id,operation,request_id),
  KEY idx_grab_request_expiry(status,lease_until,id),
  KEY idx_grab_request_errand(campus_id,errand_id,status),
  CONSTRAINT ck_grab_request_status CHECK(status IN ('PROCESSING','SUCCEEDED','REJECTED','EXPIRED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='抢单请求及永久幂等守卫';

CREATE TABLE IF NOT EXISTS grab_repair_task (
  id BIGINT NOT NULL PRIMARY KEY,
  campus_id BIGINT NOT NULL,
  errand_id BIGINT NOT NULL,
  event_key VARCHAR(192) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  event_type VARCHAR(24) NOT NULL,
  target_version BIGINT NOT NULL,
  round INT NOT NULL,
  request_db_id BIGINT NULL,
  reservation_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
  payload JSON NOT NULL,
  status VARCHAR(16) NOT NULL,
  next_run_at DATETIME(3) NOT NULL,
  retry_count INT NOT NULL DEFAULT 0,
  worker_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
  locked_until DATETIME(3) NULL,
  last_error VARCHAR(500) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_grab_repair_event(event_key),
  KEY idx_grab_repair_ready(status,next_run_at,id),
  KEY idx_grab_repair_lease(status,locked_until,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='投影和撤销持久补偿任务';

CREATE TABLE IF NOT EXISTS grab_audit_cursor (
  job_name VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  campus_id BIGINT NOT NULL,
  task_status VARCHAR(16) NOT NULL,
  last_id BIGINT NOT NULL DEFAULT 0,
  upper_id BIGINT NOT NULL DEFAULT 0,
  cycle_started_at DATETIME(3) NULL,
  version BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY(job_name,campus_id,task_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='按校区和状态的固定上界巡检游标';


SET @grab_migration_sql = IF(EXISTS(SELECT 1 FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='errand' AND COLUMN_NAME='confirm_deadline'),
  'SELECT 1', 'ALTER TABLE errand ADD COLUMN confirm_deadline DATETIME(3) NULL');
PREPARE grab_migration_stmt FROM @grab_migration_sql;
EXECUTE grab_migration_stmt;
DEALLOCATE PREPARE grab_migration_stmt;


SET @grab_migration_sql = IF(EXISTS(SELECT 1 FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='errand' AND INDEX_NAME='idx_confirm_deadline'),
  'SELECT 1', 'ALTER TABLE errand ADD INDEX idx_confirm_deadline(status,confirm_deadline,id)');
PREPARE grab_migration_stmt FROM @grab_migration_sql;
EXECUTE grab_migration_stmt;
DEALLOCATE PREPARE grab_migration_stmt;


SET @grab_migration_sql = IF(EXISTS(SELECT 1 FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='errand' AND INDEX_NAME='idx_grab_audit'),
  'SELECT 1', 'ALTER TABLE errand ADD INDEX idx_grab_audit(campus_id,status,id)');
PREPARE grab_migration_stmt FROM @grab_migration_sql;
EXECUTE grab_migration_stmt;
DEALLOCATE PREPARE grab_migration_stmt;


SET @grab_migration_sql = IF(EXISTS(SELECT 1 FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='grab_record' AND COLUMN_NAME='request_record_id'),
  'SELECT 1', 'ALTER TABLE grab_record ADD COLUMN request_record_id BIGINT NULL');
PREPARE grab_migration_stmt FROM @grab_migration_sql;
EXECUTE grab_migration_stmt;
DEALLOCATE PREPARE grab_migration_stmt;


SET @grab_migration_sql = IF(EXISTS(SELECT 1 FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='grab_record' AND INDEX_NAME='uk_grab_request_record'),
  'SELECT 1', 'ALTER TABLE grab_record ADD UNIQUE INDEX uk_grab_request_record(request_record_id)');
PREPARE grab_migration_stmt FROM @grab_migration_sql;
EXECUTE grab_migration_stmt;
DEALLOCATE PREPARE grab_migration_stmt;


SET @grab_migration_sql = IF(EXISTS(SELECT 1 FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='local_message' AND COLUMN_NAME='campus_id'),
  'SELECT 1', 'ALTER TABLE local_message ADD COLUMN campus_id BIGINT NULL');
PREPARE grab_migration_stmt FROM @grab_migration_sql;
EXECUTE grab_migration_stmt;
DEALLOCATE PREPARE grab_migration_stmt;


-- 历史消息只按已存在任务事实回填，无法关联的行保持 NULL 交由巡检诊断。
UPDATE local_message m JOIN errand e ON CAST(e.id AS CHAR)=JSON_UNQUOTE(JSON_EXTRACT(m.payload,'$.errandId'))
  SET m.campus_id=e.campus_id WHERE m.campus_id IS NULL;
-- 历史确认窗口必须由操作者核实后提供；未设置变量不会猜测历史 deadline。
-- mysql 同一会话先执行 SET @legacy_confirm_timeout_seconds=300; 再 SOURCE 本脚本。
UPDATE errand SET confirm_deadline=TIMESTAMPADD(SECOND,@legacy_confirm_timeout_seconds,locked_at)
  WHERE status='LOCKED' AND confirm_deadline IS NULL AND locked_at IS NOT NULL
    AND @legacy_confirm_timeout_seconds IS NOT NULL AND @legacy_confirm_timeout_seconds>0;
INSERT INTO schema_migration(version,description)
 VALUES('20261020-grab-reliability-v2','请求幂等、持久补偿、投影巡检及确认截止时间')
 ON DUPLICATE KEY UPDATE version=version;
-- 切换前必须逐项处置这两个结果集，不能静默改变历史多名额任务。
SELECT id,campus_id,status,slot_total FROM errand WHERE slot_total<>1;
SELECT id,campus_id,locked_at FROM errand WHERE status='LOCKED' AND confirm_deadline IS NULL;
