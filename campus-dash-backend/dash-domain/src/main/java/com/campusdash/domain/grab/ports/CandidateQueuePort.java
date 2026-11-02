package com.campusdash.domain.grab.ports;

import java.util.Optional;

/** 候选仅是尽力而为的偏好队列；条目绑定发起轮次和令牌，防止跨回池周期或迟到删除。 */
public interface CandidateQueuePort {
    record Candidate(long runnerId, int requestRound, String token) {}

    /** 兼容既有内部第零轮造数；业务请求必须显式传入发起轮次。 */
    default void offer(long errandId, long runnerId, double score) {
        offer(errandId, runnerId, score, 0);
    }

    void offer(long errandId, long runnerId, double score, int requestRound);

    /** 只读队首；数据库接管提交前不得弹出，避免重复消费者误以为队列为空。 */
    Optional<Candidate> peekBest(long errandId);

    /** 只有条目令牌仍一致才移除；迟到消费者不得删掉同一跑腿新入队的条目。 */
    boolean remove(long errandId, Candidate candidate);

    long size(long errandId);
}
