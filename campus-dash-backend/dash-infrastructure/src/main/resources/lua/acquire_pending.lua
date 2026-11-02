-- KEYS: 任务状态、该请求墓碑。所有 ID/版本保留字符串，避免雪花 ID 精度丢失。
-- ARGV: requestDbId, token, baseVersion, round, runnerId, leaseUntilMillis
local clock = redis.call('TIME')
local now = tonumber(clock[1]) * 1000 + math.floor(tonumber(clock[2]) / 1000)
if tonumber(ARGV[6]) <= now then return 5 end
if redis.call('EXISTS', KEYS[2]) == 1 then return 6 end
if redis.call('HGET', KEYS[1], 'mode') ~= 'READY' then return 4 end
if redis.call('HGET', KEYS[1], 'dbVersion') ~= ARGV[3]
    or redis.call('HGET', KEYS[1], 'round') ~= ARGV[4] then return 3 end
if redis.call('HGET', KEYS[1], 'status') ~= 'PUBLISHED'
    or redis.call('HGET', KEYS[1], 'slotTaken') ~= '0' then return 2 end
local existing = redis.call('HGET', KEYS[1], 'reservationToken')
if existing then
    if existing == ARGV[2] and redis.call('HGET', KEYS[1], 'requestDbId') == ARGV[1] then return 1 end
    local deadline = tonumber(redis.call('HGET', KEYS[1], 'leaseUntil') or '0')
    if deadline <= now then return 4 end
    return 2
end
redis.call('HSET', KEYS[1], 'requestDbId', ARGV[1], 'reservationToken', ARGV[2],
    'baseVersion', ARGV[3], 'pendingRound', ARGV[4], 'runnerId', ARGV[5], 'leaseUntil', ARGV[6])
return 0
