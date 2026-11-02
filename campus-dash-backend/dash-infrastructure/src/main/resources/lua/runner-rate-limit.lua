-- KEYS 均属于同一 {runner:ID}；ID 始终作为字符串，避免雪花 ID 精度丢失。
-- ARGV 每个桶一组：每秒补充量、容量。先检查所有桶，再一起扣减。
local t = redis.call('TIME')
local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
local tokens = {}
local timestamps = {}
local wait = 0
for i, key in ipairs(KEYS) do
    local rate = tonumber(ARGV[(i - 1) * 2 + 1])
    local capacity = tonumber(ARGV[(i - 1) * 2 + 2])
    local value = redis.call('HMGET', key, 'tokens', 'time')
    local previous = tonumber(value[1]) or capacity
    local last = tonumber(value[2]) or now
    -- 时钟回拨时保留高水位，不能反复补充同一段时间。
    timestamps[i] = math.max(last, now)
    tokens[i] = math.min(capacity, previous + math.max(0, now - last) * rate / 1000)
    if tokens[i] < 1 then
        wait = math.max(wait, math.ceil((1 - tokens[i]) * 1000 / rate) + math.max(0, last - now))
    end
end
for i, key in ipairs(KEYS) do
    local rate = tonumber(ARGV[(i - 1) * 2 + 1])
    local capacity = tonumber(ARGV[(i - 1) * 2 + 2])
    local left = tokens[i]
    if wait == 0 then left = left - 1 end
    redis.call('HSET', key, 'tokens', tostring(left), 'time', tostring(timestamps[i]))
    redis.call('PEXPIRE', key, math.ceil(capacity * 1000 / rate) + 60000 + math.max(0, timestamps[i] - now))
end
if wait > 0 then return {0, wait} end
return {1, 0}
