package com.campusdash.infrastructure.persistence;

import com.campusdash.domain.grab.ports.GrabAuditCursorRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public class JdbcGrabAuditCursorRepository implements GrabAuditCursorRepository {
    private static final String JOB="GRAB_PROJECTION";
    private final JdbcTemplate jdbc;
    public JdbcGrabAuditCursorRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    @Override public List<Long> campuses() {
        return jdbc.queryForList("SELECT DISTINCT campus_id FROM errand ORDER BY campus_id",Long.class);
    }
    @Override public Cursor loadOrStart(long campusId,String status) {
        jdbc.update("""
                INSERT INTO grab_audit_cursor(job_name,campus_id,task_status,last_id,upper_id,version)
                VALUES (?,?,?,0,0,0) ON DUPLICATE KEY UPDATE job_name=job_name
                """,JOB,campusId,status);
        Cursor cursor=load(campusId,status);
        if(cursor.upperId()==0) {
            Long upper=jdbc.queryForObject("SELECT COALESCE(MAX(id),0) FROM errand WHERE campus_id=? AND status=?",
                    Long.class,campusId,status);
            jdbc.update("""
                    UPDATE grab_audit_cursor SET upper_id=?,last_id=0,cycle_started_at=CURRENT_TIMESTAMP(3),version=version+1
                    WHERE job_name=? AND campus_id=? AND task_status=? AND version=? AND upper_id=0
                    """,upper,JOB,campusId,status,cursor.version());
            cursor=load(campusId,status);
        }
        return cursor;
    }
    private Cursor load(long campusId,String status) {
        return jdbc.queryForObject("SELECT * FROM grab_audit_cursor WHERE job_name=? AND campus_id=? AND task_status=?",
                (rs,n)->new Cursor(campusId,status,rs.getLong("last_id"),rs.getLong("upper_id"),rs.getLong("version")),JOB,campusId,status);
    }
    @Override public List<Long> nextIds(Cursor c,int limit) {
        return jdbc.queryForList("SELECT id FROM errand WHERE campus_id=? AND status=? AND id>? AND id<=? ORDER BY id LIMIT ?",
                Long.class,c.campusId(),c.taskStatus(),c.lastId(),c.upperId(),limit);
    }
    @Override public boolean advance(Cursor c,long checkedLastId,boolean finished) {
        if(checkedLastId<c.lastId() || checkedLastId>c.upperId()) throw new IllegalArgumentException("游标超出本轮边界");
        return jdbc.update("""
                UPDATE grab_audit_cursor SET last_id=?,upper_id=?,version=version+1
                WHERE job_name=? AND campus_id=? AND task_status=? AND version=?
                """,finished?0:checkedLastId,finished?0:c.upperId(),JOB,c.campusId(),c.taskStatus(),c.version())==1;
    }
}
