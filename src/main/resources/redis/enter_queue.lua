local local_seq = tonumber(ARGV[1])
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
  local queue_id_start = string.find(KEYS[2], ':entered:', 1, true)
  if queue_id_start then
    redis.call('ZADD', KEYS[3], existing_expires_at, string.sub(KEYS[2], queue_id_start + 9))
  end
  return {1, existing_token, existing_expires_at}
end

if existing_token then
  redis.call('DEL', KEYS[2])
end

local ticket_value = redis.call('GET', KEYS[4])
local stored_local_seq = -1
local queue_id = ''
if ticket_value and ticket_value ~= '' then
  local first = string.find(ticket_value, '|', 1, true)
  local second = first and string.find(ticket_value, '|', first + 1, true) or nil
  if first and second then
    queue_id = string.sub(ticket_value, 1, first - 1)
    stored_local_seq = tonumber(string.sub(ticket_value, first + 1, second - 1)) or -1
  end
else
  stored_local_seq = tonumber(redis.call('HGET', KEYS[4], 'localSeq') or '-1')
  queue_id = redis.call('HGET', KEYS[4], 'queueId') or ''
end

if stored_local_seq ~= local_seq then
  return {3, '', 0}
end

local serving_seq = tonumber(redis.call('HGET', KEYS[1], 'servingSeq') or '0')
if local_seq > serving_seq then
  return {0, '', 0}
end

local active_count = redis.call('ZCARD', KEYS[3])
if active_count >= max_active_sessions then
  return {2, '', 0}
end

redis.call('HSET', KEYS[2], 'admissionToken', admission_token, 'expiresAtMillis', expires_at_millis)
redis.call('PEXPIRE', KEYS[2], session_ttl_millis)
redis.call('ZADD', KEYS[3], expires_at_millis, queue_id)

return {1, admission_token, expires_at_millis}
