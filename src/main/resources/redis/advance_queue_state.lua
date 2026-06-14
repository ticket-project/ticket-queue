local max_admit_per_second = tonumber(ARGV[1])
local max_active_sessions = tonumber(ARGV[2])
local time = redis.call('TIME')
local now_millis = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)

redis.call('ZREMRANGEBYSCORE', KEYS[2], 0, now_millis)

local active_count = redis.call('ZCARD', KEYS[2])
local available = max_active_sessions - active_count
if available <= 0 then
  redis.call('HSET', KEYS[1], 'serverTimeMillis', now_millis)
  return 0
end

local admitted_until_seq = tonumber(redis.call('HGET', KEYS[1], 'admittedUntilSeq') or '0')
local tail_seq = tonumber(redis.call('HGET', KEYS[1], 'tailSeq') or '0')
local pending = tail_seq - admitted_until_seq
if pending <= 0 then
  redis.call('HSET', KEYS[1], 'serverTimeMillis', now_millis)
  return 0
end

local increment = math.min(max_admit_per_second, available, pending)
local next_admitted_until_seq = admitted_until_seq + increment
redis.call(
  'HSET',
  KEYS[1],
  'admittedUntilSeq',
  next_admitted_until_seq,
  'status',
  'OPEN',
  'serverTimeMillis',
  now_millis
)

return increment
