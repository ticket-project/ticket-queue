-- KEYS:
-- 1 performance-global entered hash
--
-- ARGV:
-- 1 admission token
-- 2 admission ttl millis
-- 3 admit requested flag
--
-- Returns:
-- 1 admitted, 0 no existing admission

local admission_token = ARGV[1]
local admission_ttl_millis = tonumber(ARGV[2])
local admit_requested = tonumber(ARGV[3])
local time = redis.call('TIME')
local now_millis = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
local expires_at_millis = now_millis + admission_ttl_millis

local existing_token = redis.call('HGET', KEYS[1], 'admissionToken')
local existing_expires_at = tonumber(redis.call('HGET', KEYS[1], 'expiresAtMillis') or '0')
if existing_token and existing_expires_at > now_millis then
  return {1, existing_token, existing_expires_at}
end

if existing_token then
  redis.call('DEL', KEYS[1])
end

if admit_requested ~= 1 then
  return {0, '', 0}
end

redis.call('HSET', KEYS[1], 'admissionToken', admission_token, 'expiresAtMillis', expires_at_millis)
redis.call('PEXPIRE', KEYS[1], admission_ttl_millis)

return {1, admission_token, expires_at_millis}
