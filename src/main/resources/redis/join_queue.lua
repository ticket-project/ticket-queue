local existing = redis.call('GET', KEYS[3])
if existing and existing ~= '' then
  local separator = string.find(existing, '|', 1, true)
  if separator then
    local queue_id = string.sub(existing, 1, separator - 1)
    local seq = tonumber(string.sub(existing, separator + 1))
    if queue_id ~= '' and seq then
      return {queue_id, seq, 0}
    end
  end
  redis.call('DEL', KEYS[3])
end

local queue_id = ARGV[1]
local user_id_hash = ARGV[2]
local ttl_millis = tonumber(ARGV[3])
local refresh_after_millis = tonumber(ARGV[4])
local time = redis.call('TIME')
local now_millis = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
local seq = redis.call('INCR', KEYS[1])

redis.call('SET', KEYS[3], queue_id .. '|' .. seq, 'PX', ttl_millis)
redis.call(
  'HSET',
  KEYS[4],
  'performanceId',
  string.match(KEYS[1], '{([^}]+)}') or '',
  'queueId',
  queue_id,
  'seq',
  seq,
  'userIdHash',
  user_id_hash,
  'createdAtMillis',
  now_millis
)
redis.call('PEXPIRE', KEYS[4], ttl_millis)

redis.call('HSETNX', KEYS[2], 'admittedUntilSeq', 0)
redis.call(
  'HSET',
  KEYS[2],
  'status',
  'OPEN',
  'tailSeq',
  seq,
  'refreshAfterMs',
  refresh_after_millis,
  'serverTimeMillis',
  now_millis
)
redis.call('PEXPIRE', KEYS[2], ttl_millis)

redis.call('XADD', KEYS[5], '*', 'queueId', queue_id, 'seq', seq, 'userIdHash', user_id_hash)
redis.call('PEXPIRE', KEYS[5], ttl_millis)

return {queue_id, seq, 1}
