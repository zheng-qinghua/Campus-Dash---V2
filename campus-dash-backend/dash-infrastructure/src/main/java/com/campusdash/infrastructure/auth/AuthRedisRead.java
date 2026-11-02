package com.campusdash.infrastructure.auth;

import io.lettuce.core.RedisBusyException;
import io.lettuce.core.RedisLoadingException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.RedisSystemException;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.function.Supplier;

/** 归一化鉴权读取中的明确暂时故障，不能把 WRONGTYPE 或编程错误改成可重试错误。 */
final class AuthRedisRead {
    private AuthRedisRead() {}

    static <T> T execute(Supplier<T> read) {
        try {
            return read.get();
        } catch (RedisSystemException failure) {
            // Spring Data Redis 将 LOADING/BUSY 与普通执行错误都包装为 RedisSystemException，
            // 所以必须检查具体原因；不能对整个包装异常兜底返回依赖不可用。
            Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
            for (Throwable cause = failure; cause != null && visited.add(cause); cause = cause.getCause()) {
                if (cause instanceof RedisLoadingException || cause instanceof RedisBusyException) {
                    throw new DataAccessResourceFailureException("鉴权 Redis 正在恢复或繁忙", failure);
                }
            }
            throw failure;
        }
    }
}
