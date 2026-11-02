package com.campusdash.domain.grab.ports;

import com.campusdash.domain.grab.model.GrabRepairTask;
import java.util.List;

public interface GrabRepairTaskRepository {
    /** 在业务事务内调用。首次创建或重开 DONE，不抢占 RUNNING，不自动重开 DEAD。 */
    void ensurePending(GrabRepairTask task);
    /** 原子领取过期租约或待运行事件；外部 IO 必须在返回后执行。 */
    List<GrabRepairTask> claim(int limit, int leaseSeconds);
    boolean complete(long id, String workerToken);
    boolean retry(long id, String workerToken, String error, int maxRetries);
    boolean replayDead(long id);
}
