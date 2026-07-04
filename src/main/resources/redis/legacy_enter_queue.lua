-- KEYS:
-- 1 performance public state
-- 2 performance-global entered hash
-- 3 performance-global active sessions
-- 4 legacy queue ticket
--
-- ARGV:
-- 1 legacy seq
-- 2 admission token
-- 3 session ttl millis
-- 4 max active sessions
--
-- Returns:
-- 1 admitted, 2 full, 3 expired, 0 not admitted

local seq = tonumber(ARGV[1])
local admission_token = ARGV[2]
local session_ttl_millis = tonumber(ARGV[3])
local max_active_sessions = tonumber(ARGV[4])
local time = redis.call('TIME')
local now_millis = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
local expires_at_millis = now_millis + session_ttl_millis

redis.call('ZREMRANGEBYSCORE', KEYS[3], 0, now_millis)

local existing_token = redis.call('HGET', KEYS[2], 'admissionToken')
local existing_expires_at = tonumber(redis.call('HGET', KEYS[2], 'expiresAtMillis') or '0')
if existing_token and existing_expires_at > now_millis then
  redis.call('ZADD', KEYS[3], existing_expires_at, redis.call('HGET', KEYS[4], 'queueId') or '')
  return {1, existing_token, existing_expires_at}
end

if existing_token then
  redis.call('DEL', KEYS[2])
end

local stored_seq = tonumber(redis.call('HGET', KEYS[4], 'seq') or '-1')
if stored_seq ~= seq then
  return {3, '', 0}
end

local admitted_until_seq = tonumber(redis.call('HGET', KEYS[1], 'admittedUntilSeq') or '0')
if seq > admitted_until_seq then
  return {0, '', 0}
end

local active_count = redis.call('ZCARD', KEYS[3])
if active_count >= max_active_sessions then
  return {2, '', 0}
end

local queue_id = redis.call('HGET', KEYS[4], 'queueId')
redis.call('HSET', KEYS[2], 'admissionToken', admission_token, 'expiresAtMillis', expires_at_millis)
redis.call('PEXPIRE', KEYS[2], session_ttl_millis)
redis.call('ZADD', KEYS[3], expires_at_millis, queue_id)
redis.call('PEXPIRE', KEYS[3], session_ttl_millis)

return {1, admission_token, expires_at_millis}
