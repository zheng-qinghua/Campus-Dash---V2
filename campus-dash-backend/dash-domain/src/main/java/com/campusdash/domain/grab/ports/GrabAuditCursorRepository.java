package com.campusdash.domain.grab.ports;

import java.util.List;

/** 固定上界巡检游标，检查完成后才使用 version CAS 推进。 */
public interface GrabAuditCursorRepository {
    record Cursor(long campusId, String taskStatus, long lastId, long upperId, long version) {}
    List<Long> campuses();
    Cursor loadOrStart(long campusId, String taskStatus);
    List<Long> nextIds(Cursor cursor, int limit);
    boolean advance(Cursor cursor, long checkedLastId, boolean cycleFinished);
}
