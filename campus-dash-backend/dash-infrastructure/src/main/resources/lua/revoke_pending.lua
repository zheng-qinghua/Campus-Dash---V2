-- ARGV: requestDbId, token, baseVersion, round, originalLeaseUntilMillis, safetyMillis
local clock = redis.call('TIME')
local now = tonumber(clock[1]) * 1000 + math.floor(tonumber(clock[2]) / 1000)
local expires = math.max(tonumber(ARGV[5]) + tonumber(ARGV[6]), now + tonumber(ARGV[6]))
-- 即使当前没有 pending，也必须写墓碑，拦截撤销后才醒来的旧线程。
redis.call('SET', KEYS[2], '1', 'PXAT', tostring(expires))
if redis.call('HGET', KEYS[1], 'requestDbId') == ARGV[1]
    and redis.call('HGET', KEYS[1], 'reservationToken') == ARGV[2]
    and redis.call('HGET', KEYS[1], 'baseVersion') == ARGV[3]
    and redis.call('HGET', KEYS[1], 'pendingRound') == ARGV[4] then
    redis.call('HDEL', KEYS[1], 'requestDbId','reservationToken','baseVersion','pendingRound','runnerId','leaseUntil')
    return 1
end
return 0
