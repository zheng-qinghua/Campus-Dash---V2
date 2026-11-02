-- ARGV: buildToken, buildLeaseMillis
local clock = redis.call('TIME')
local now = tonumber(clock[1]) * 1000 + math.floor(tonumber(clock[2]) / 1000)
local mode = redis.call('HGET', KEYS[1], 'mode')
if mode == 'READY' then return 0 end
if mode == 'INIT' and tonumber(redis.call('HGET', KEYS[1], 'buildUntil') or '0') > now then return 0 end
redis.call('DEL', KEYS[1])
redis.call('HSET', KEYS[1], 'mode','INIT','buildToken',ARGV[1],'buildUntil',tostring(now+tonumber(ARGV[2])))
return 1
