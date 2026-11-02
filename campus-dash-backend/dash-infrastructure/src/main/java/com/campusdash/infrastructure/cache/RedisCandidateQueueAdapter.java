package com.campusdash.infrastructure.cache;

import com.campusdash.domain.grab.ports.CandidateQueuePort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 候选分数与轮次令牌原子读写；数据库接管成功后才精确删除。 */
@Component
public class RedisCandidateQueueAdapter implements CandidateQueuePort {
    private static final DefaultRedisScript<Long> OFFER = new DefaultRedisScript<>("""
            if redis.call('ZSCORE', KEYS[1], ARGV[1]) then
                local current = redis.call('HGET', KEYS[2], ARGV[1])
                local currentRound = current and tonumber(string.match(current, '^(%d+):')) or 0
                local offeredRound = tonumber(string.match(ARGV[3], '^(%d+):'))
                -- 同轮次不重排；新轮次显式重试可替换旧资格，旧请求不能覆盖新资格。
                if current and offeredRound <= currentRound then return 0 end
            end
            redis.call('ZADD', KEYS[1], ARGV[2], ARGV[1])
            redis.call('HSET', KEYS[2], ARGV[1], ARGV[3])
            return 1
            """, Long.class);
    @SuppressWarnings("rawtypes")
    private static final DefaultRedisScript<List> PEEK = new DefaultRedisScript<>("""
            local first = redis.call('ZRANGE', KEYS[1], 0, 0)
            if #first == 0 then return {} end
            local meta = redis.call('HGET', KEYS[2], first[1])
            if not meta then
                -- 兼容旧队列：只能作为第零轮条目，回池后会被周期检查淘汰。
                meta = '0:' .. ARGV[1]
                redis.call('HSET', KEYS[2], first[1], meta)
            end
            return {first[1], meta}
            """, List.class);
    private static final DefaultRedisScript<Long> REMOVE = new DefaultRedisScript<>("""
            if redis.call('HGET', KEYS[2], ARGV[1]) ~= ARGV[2] then return 0 end
            redis.call('ZREM', KEYS[1], ARGV[1])
            redis.call('HDEL', KEYS[2], ARGV[1])
            return 1
            """, Long.class);
    private final StringRedisTemplate redis;

    public RedisCandidateQueueAdapter(StringRedisTemplate redis) { this.redis = redis; }

    @Override
    public void offer(long errandId, long runnerId, double score, int requestRound) {
        if (requestRound < 0 || runnerId <= 0 || !Double.isFinite(score))
            throw new IllegalArgumentException("非法候选条目");
        redis.execute(OFFER, keys(errandId), String.valueOf(runnerId), String.valueOf(score),
                requestRound + ":" + UUID.randomUUID());
    }

    @Override
    public Optional<Candidate> peekBest(long errandId) {
        List<?> row = redis.execute(PEEK, keys(errandId), UUID.randomUUID().toString());
        if (row == null || row.isEmpty()) return Optional.empty();
        String meta = row.get(1).toString();
        int separator = meta.indexOf(':');
        return Optional.of(new Candidate(Long.parseLong(row.get(0).toString()),
                Integer.parseInt(meta.substring(0, separator)), meta.substring(separator + 1)));
    }

    @Override
    public boolean remove(long errandId, Candidate candidate) {
        return Long.valueOf(1).equals(redis.execute(REMOVE, keys(errandId),
                String.valueOf(candidate.runnerId()), candidate.requestRound() + ":" + candidate.token()));
    }

    @Override
    public long size(long errandId) {
        Long n = redis.opsForZSet().zCard(keys(errandId).getFirst());
        return n == null ? 0L : n;
    }

    private List<String> keys(long errandId) {
        return List.of("errand:candidates:{" + errandId + "}", "errand:candidate-meta:{" + errandId + "}");
    }
}
