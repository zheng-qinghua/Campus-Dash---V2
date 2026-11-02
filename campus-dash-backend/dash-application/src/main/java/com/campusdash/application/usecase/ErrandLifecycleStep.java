package com.campusdash.application.usecase;

import com.campusdash.domain.errand.model.*;
import com.campusdash.domain.errand.ports.*;
import com.campusdash.shared.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;

/** 纯数据库状态流转；投影由仓储同事务登记，通知在调用者提交后执行。 */
@Service
public class ErrandLifecycleStep {
    private final ErrandRepository errands;
    private final LocalMessageRepository messages;
    private final SnowflakeIdGenerator ids;
    private final long settleSeconds;
    public ErrandLifecycleStep(ErrandRepository errands, LocalMessageRepository messages, SnowflakeIdGenerator ids,
            @Value("${dash.settle.auto-settle-seconds:86400}") long settleSeconds) {
        this.errands=errands;this.messages=messages;this.ids=ids;this.settleSeconds=settleSeconds;
    }
    @Transactional(rollbackFor=Exception.class)
    public Errand change(long id,long actor,ErrandStatus target) {
        return change(id, actor, target, null);
    }
    @Transactional(rollbackFor=Exception.class)
    public Errand change(long id,long actor,ErrandStatus target,Integer expectedRound) {
        Errand e=errands.findById(id).orElseThrow(()->new BizException(ErrorCode.ERRAND_NOT_FOUND,"id="+id));
        // A→B→A 后归属可能相同，但旧轮次的迟到确认不能替新一轮确认。
        if (expectedRound != null && (expectedRound < 0 || e.round() != expectedRound))
            throw new BizException(ErrorCode.STALE_VERSION,"确认轮次已变化 errandId="+id);
        // 幂等确认也必须检查当前归属，历史抢中者无权确认新跑腿的任务。
        if(target==ErrandStatus.ACCEPTED && e.status()==ErrandStatus.ACCEPTED) {
            if(!Long.valueOf(actor).equals(e.grabberId())) throw new BizException(ErrorCode.NOT_CURRENT_GRABBER,"当前归属已变化");
            return e;
        }
        ErrandStatus from=e.status();long version=e.version();int affected;
        switch(target) {
            case ACCEPTED -> { e.acceptByRunner(actor,version);affected=errands.casAccept(id,actor,version); }
            case PICKED_UP -> { e.pickUp(actor,version);affected=errands.casPickUp(id,actor,version); }
            case DELIVERED -> { e.deliver(actor,version,Instant.now());affected=errands.casDeliver(id,actor,version); }
            case DISPUTED -> { e.raiseDispute(actor,version);affected=errands.casDispute(id,version); }
            default -> throw new IllegalArgumentException("不支持的生命周期动作");
        }
        if(affected!=1) throw new BizException(ErrorCode.STALE_VERSION,"状态或确认截止时间已变化 errandId="+id);
        errands.appendStatusLog(id,from,target,e.round(),actor);
        Errand persisted=errands.findById(id).orElseThrow();
        if(target==ErrandStatus.DELIVERED) messages.enqueueForCampus(ids.nextId(),e.campusId(),
                DelayTaskPolicy.autoSettleKey(id),DelayTaskPolicy.TOPIC_AUTO_SETTLE,
                DelayTaskPolicy.autoSettlePayload(id),persisted.deliveredAt().plusSeconds(settleSeconds));
        return persisted;
    }
}
