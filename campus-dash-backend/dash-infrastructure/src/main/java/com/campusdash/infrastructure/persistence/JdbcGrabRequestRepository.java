package com.campusdash.infrastructure.persistence;

import com.campusdash.domain.grab.model.GrabRequest;
import com.campusdash.domain.grab.model.GrabRequestStatus;
import com.campusdash.domain.grab.ports.GrabRequestRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class JdbcGrabRequestRepository implements GrabRequestRepository {
    private final JdbcTemplate jdbc;
    public JdbcGrabRequestRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    private static final RowMapper<GrabRequest> MAPPER = (rs, row) -> new GrabRequest(
            rs.getLong("id"), rs.getLong("campus_id"), rs.getLong("errand_id"), rs.getLong("runner_id"),
            rs.getString("request_id"), rs.getString("payload_hash"), rs.getInt("request_round"),
            rs.getLong("base_errand_version"), GrabRequestStatus.valueOf(rs.getString("status")),
            rs.getString("reservation_token"), rs.getTimestamp("lease_until").toInstant(),
            rs.getString("result_code"), rs.getInt("http_status"), rs.getString("result_json"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("completed_at") == null ? null : rs.getTimestamp("completed_at").toInstant());
    @Override public boolean create(GrabRequest.NewRequest r) {
        if (r.leaseSeconds() < 1) throw new IllegalArgumentException("请求租约必须大于零");
        try {
            jdbc.update("""
                    INSERT INTO grab_request (id,campus_id,errand_id,runner_id,operation,request_id,
                    payload_hash,request_round,base_errand_version,status,reservation_token,lease_until)
                    VALUES (?,?,?,?, 'GRAB',?,?,?,?, 'PROCESSING',?,TIMESTAMPADD(SECOND,?,CURRENT_TIMESTAMP(3)))
                    """, r.id(),r.campusId(),r.errandId(),r.runnerId(),r.requestId(),r.payloadHash(),
                    r.requestRound(),r.baseErrandVersion(),r.reservationToken(),r.leaseSeconds());
            return true;
        } catch (DuplicateKeyException e) { return false; }
    }
    @Override public Optional<GrabRequest> find(long campusId,long runnerId,String requestId) {
        return jdbc.query("SELECT * FROM grab_request WHERE campus_id=? AND runner_id=? AND operation='GRAB' AND request_id=?",
                MAPPER,campusId,runnerId,requestId).stream().findFirst();
    }
    @Override public Optional<GrabRequest> findById(long campusId,long id) {
        return jdbc.query("SELECT * FROM grab_request WHERE campus_id=? AND id=?", MAPPER,campusId,id).stream().findFirst();
    }
    @Override public Optional<GrabRequest> lockById(long campusId,long id) {
        return jdbc.query("SELECT * FROM grab_request WHERE campus_id=? AND id=? FOR UPDATE", MAPPER,campusId,id).stream().findFirst();
    }
    @Override public Instant databaseNow() {
        return jdbc.queryForObject("SELECT CURRENT_TIMESTAMP(3)", (rs,n) -> rs.getTimestamp(1).toInstant());
    }
    @Override public boolean finish(long campusId,long id,String token,GrabRequestStatus status,
            String resultCode,int httpStatus,String resultJson) {
        if (!status.terminal()) throw new IllegalArgumentException("只能写入请求终态");
        // 获取请求锁后再执行本语句：数据库当前时间是最终提交资格裁决依据。
        String deadline = status == GrabRequestStatus.SUCCEEDED ? " AND lease_until>CURRENT_TIMESTAMP(3)"
                : status == GrabRequestStatus.EXPIRED ? " AND lease_until<=CURRENT_TIMESTAMP(3)" : "";
        return jdbc.update("UPDATE grab_request SET status=?,result_code=?,http_status=?,result_json=?,completed_at=CURRENT_TIMESTAMP(3) "
                + "WHERE campus_id=? AND id=? AND reservation_token=? AND status='PROCESSING'" + deadline,
                status.name(),resultCode,httpStatus,resultJson,campusId,id,token)==1;
    }
    @Override public List<GrabRequest> findExpired(int limit) {
        return jdbc.query("SELECT * FROM grab_request WHERE status='PROCESSING' AND lease_until<=CURRENT_TIMESTAMP(3) "
                + "ORDER BY lease_until,id LIMIT ?",MAPPER,limit);
    }
}
