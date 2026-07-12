-- KEYS:
-- 1 performance-global active sessions
--
-- Returns:
-- active session count after pruning expired sessions

local time = redis.call('TIME')
local now_millis = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
redis.call('ZREMRANGEBYSCORE', KEYS[1], 0, now_millis)
return redis.call('ZCARD', KEYS[1])
