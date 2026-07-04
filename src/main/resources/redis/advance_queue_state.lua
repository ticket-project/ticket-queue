-- KEYS:
-- 1 shard state
-- 2 shard-local pending slots
-- 3 shard-local slot tail hash
-- 4 shard-local sequence
--
-- ARGV:
-- 1 mode: SNAPSHOT or ADVANCE
-- 2 state ttl millis
-- 3 increment, only for ADVANCE

local mode = ARGV[1]
local ttl_millis = tonumber(ARGV[2])
local time = redis.call('TIME')
local now_millis = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
local tail_seq = tonumber(redis.call('GET', KEYS[4]) or '0')

local function first_pending_slot(serving_seq)
  local first_slot = redis.call('ZRANGE', KEYS[2], 0, 0)[1]
  while first_slot do
    local first_slot_tail = tonumber(redis.call('HGET', KEYS[3], first_slot) or '0')
    if first_slot_tail > serving_seq then
      return tonumber(first_slot), first_slot_tail
    end
    redis.call('ZREM', KEYS[2], first_slot)
    redis.call('HDEL', KEYS[3], first_slot)
    first_slot = redis.call('ZRANGE', KEYS[2], 0, 0)[1]
  end
  return -1, 0
end

local function snapshot()
  local serving_seq = tonumber(redis.call('HGET', KEYS[1], 'servingSeq') or '0')
  local first_slot_id, first_slot_tail = first_pending_slot(serving_seq)
  local status = 'EMPTY'
  if first_slot_id >= 0 and tail_seq > serving_seq then
    status = 'OPEN'
  end
  redis.call('HSET', KEYS[1], 'tailSeq', tail_seq, 'status', status, 'serverTimeMillis', now_millis)
  redis.call('PEXPIRE', KEYS[1], ttl_millis)
  if tail_seq > 0 then
    redis.call('PEXPIRE', KEYS[4], ttl_millis)
  end
  return {serving_seq, tail_seq, 0, first_slot_id, first_slot_tail}
end

if mode == 'ADVANCE' then
  local increment = tonumber(ARGV[3])
  if increment and increment > 0 then
    local current = tonumber(redis.call('HGET', KEYS[1], 'servingSeq') or '0')
    local next_value = current + increment
    redis.call('HSET', KEYS[1], 'servingSeq', next_value, 'status', 'OPEN', 'serverTimeMillis', now_millis)
  end
end

return snapshot()
