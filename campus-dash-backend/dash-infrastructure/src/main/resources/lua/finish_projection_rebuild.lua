-- ARGV: buildToken, dbVersion, round, status, slotTaken, ownerRunnerId
local clock = redis.call('TIME')
local now = tonumber(clock[1]) * 1000 + math.floor(tonumber(clock[2]) / 1000)
if redis.call('HGET', KEYS[1], 'mode') ~= 'INIT'
    or redis.call('HGET', KEYS[1], 'buildToken') ~= ARGV[1]
    or tonumber(redis.call('HGET', KEYS[1], 'buildUntil') or '0') <= now then return 0 end
redis.call('HSET', KEYS[1], 'mode','READY','dbVersion',ARGV[2],'round',ARGV[3],
    'status',ARGV[4],'slotTaken',ARGV[5],'ownerRunnerId',ARGV[6])
redis.call('HDEL', KEYS[1], 'buildToken','buildUntil')
return 1
