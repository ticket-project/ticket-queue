-- KEYS:
-- 1 performance-global entered hash
-- 2 performance-global active sessions
--
-- ARGV:
-- 1 admission token
-- 2 session ttl millis
-- 3 max active sessions
-- 4 admit requested flag
-- 5 queue id
--
-- Returns:
-- 1 admitted, 2 full, 0 no existing admission

local admission_token = ARGV[1]
local session_ttl_millis = tonumber(ARGV[2])
local max_active_sessions = tonumber(ARGV[3])
local admit_requested = tonumber(ARGV[4])
local queue_id = ARGV[5]
local time = redis.call('TIME')
local now_millis = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
local expires_at_millis = now_millis + session_ttl_millis

redis.call('ZREMRANGEBYSCORE', KEYS[2], 0, now_millis)

local existing_token = redis.call('HGET', KEYS[1], 'admissionToken')
local existing_expires_at = tonumber(redis.call('HGET', KEYS[1], 'expiresAtMillis') or '0')
if existing_token and existing_expires_at > now_millis then
  redis.call('ZADD', KEYS[2], existing_expires_at, queue_id)
  return {1, existing_token, existing_expires_at}
end

if existing_token then
  redis.call('DEL', KEYS[1])
end

if admit_requested ~= 1 then
  return {0, '', 0}
end

local active_count = redis.call('ZCARD', KEYS[2])
if active_count >= max_active_sessions then
  return {2, '', 0}
end

redis.call('HSET', KEYS[1], 'admissionToken', admission_token, 'expiresAtMillis', expires_at_millis)
redis.call('PEXPIRE', KEYS[1], session_ttl_millis)
redis.call('ZADD', KEYS[2], expires_at_millis, queue_id)
redis.call('PEXPIRE', KEYS[2], session_ttl_millis)

return {1, admission_token, expires_at_millis}
