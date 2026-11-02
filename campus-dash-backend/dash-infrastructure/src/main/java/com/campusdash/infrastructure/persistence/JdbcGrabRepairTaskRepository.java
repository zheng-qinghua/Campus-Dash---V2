package com.campusdash.infrastructure.persistence;

import com.campusdash.domain.grab.model.GrabRepairTask;
import com.campusdash.domain.grab.ports.GrabRepairTaskRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Repository
public class JdbcGrabRepairTaskRepository implements GrabRepairTaskRepository {
    private final JdbcTemplate jdbc;
    public JdbcGrabRepairTaskRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    private static final RowMapper<GrabRepairTask> MAPPER=(rs,n)->new GrabRepairTask(
            rs.getLong("id"),rs.getLong("campus_id"),rs.getLong("errand_id"),rs.getString("event_key"),
            rs.getString("event_type"),rs.getLong("target_version"),rs.getInt("round"),
            rs.getObject("request_db_id",Long.class),rs.getString("reservation_token"),
            rs.getString("payload"),rs.getInt("retry_count"),rs.getString("worker_token"));
    @Override public void ensurePending(GrabRepairTask t) {
        jdbc.update("""
                INSERT INTO grab_repair_task (id,campus_id,errand_id,event_key,event_type,target_version,
                    round,request_db_id,reservation_token,payload,status,next_run_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,'PENDING',CURRENT_TIMESTAMP(3))
                ON DUPLICATE KEY UPDATE
                    retry_count=IF(status='DONE',0,retry_count),
                    next_run_at=IF(status='DONE',CURRENT_TIMESTAMP(3),next_run_at),
                    last_error=IF(status='DONE',NULL,last_error),
                    status=IF(status='DONE','PENDING',status)
                """,t.id(),t.campusId(),t.errandId(),t.eventKey(),t.eventType(),t.targetVersion(),
                t.round(),t.requestDbId(),t.reservationToken(),t.payload());
    }
    @Override public List<GrabRepairTask> claim(int limit,int leaseSeconds) {
        if(limit<1 || leaseSeconds<1) throw new IllegalArgumentException("领取批次与租约必须为正数");
        List<GrabRepairTask> candidates=jdbc.query("""
                SELECT * FROM grab_repair_task
                 WHERE (status='PENDING' AND next_run_at<=CURRENT_TIMESTAMP(3))
                    OR (status='RUNNING' AND locked_until<=CURRENT_TIMESTAMP(3))
                 ORDER BY next_run_at,id LIMIT ?
                """,MAPPER,limit);
        List<GrabRepairTask> claimed=new ArrayList<>();
        for(GrabRepairTask t:candidates) {
            String token=UUID.randomUUID().toString();
            // 条件更新即领取裁决点；两个 Worker 读到同一候选时最多一方获得租约。
            int updated=jdbc.update("""
                    UPDATE grab_repair_task SET status='RUNNING',worker_token=?,
                      locked_until=TIMESTAMPADD(SECOND,?,CURRENT_TIMESTAMP(3))
                    WHERE id=? AND ((status='PENDING' AND next_run_at<=CURRENT_TIMESTAMP(3))
                      OR (status='RUNNING' AND locked_until<=CURRENT_TIMESTAMP(3)))
                    """,token,leaseSeconds,t.id());
            if(updated==1) claimed.add(new GrabRepairTask(t.id(),t.campusId(),t.errandId(),t.eventKey(),
                    t.eventType(),t.targetVersion(),t.round(),t.requestDbId(),t.reservationToken(),
                    t.payload(),t.retryCount(),token));
        }
        return claimed;
    }
    @Override public boolean complete(long id,String token) {
        return jdbc.update("""
                UPDATE grab_repair_task SET status='DONE',worker_token=NULL,locked_until=NULL,last_error=NULL
                WHERE id=? AND status='RUNNING' AND worker_token=? AND locked_until>CURRENT_TIMESTAMP(3)
                """,id,token)==1;
    }
    @Override public boolean retry(long id,String token,String error,int maxRetries) {
        String safeError=error==null?null:error.substring(0,Math.min(500,error.length()));
        // MySQL 单表赋值从左到右，状态判定必须放在 retry_count 自增之前。
        return jdbc.update("""
                UPDATE grab_repair_task SET status=IF(retry_count+1>=?,'DEAD','PENDING'),
                  next_run_at=TIMESTAMPADD(SECOND,LEAST(POW(2,retry_count+1),300),CURRENT_TIMESTAMP(3)),
                  retry_count=retry_count+1,last_error=?,worker_token=NULL,locked_until=NULL
                WHERE id=? AND status='RUNNING' AND worker_token=? AND locked_until>CURRENT_TIMESTAMP(3)
                """,maxRetries,safeError,id,token)==1;
    }
    @Override public boolean replayDead(long id) {
        return jdbc.update("UPDATE grab_repair_task SET status='PENDING',retry_count=0,next_run_at=CURRENT_TIMESTAMP(3),"
                +"last_error=NULL WHERE id=? AND status='DEAD'",id)==1;
    }
}
