local mode = ARGV[1]
local ttl_millis = tonumber(ARGV[2])
local time = redis.call('TIME')
local now_millis = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
local tail_seq = tonumber(redis.call('GET', KEYS[5]) or '0')

redis.call('ZREMRANGEBYSCORE', KEYS[2], 0, now_millis)

local function snapshot()
  local serving_seq = tonumber(redis.call('HGET', KEYS[1], 'servingSeq') or '0')
  local active_count = redis.call('ZCARD', KEYS[2])
  local first_slot = redis.call('ZRANGE', KEYS[3], 0, 0)[1]
  local first_slot_id = -1
  local first_slot_tail = 0
  if first_slot then
    first_slot_id = tonumber(first_slot)
    first_slot_tail = tonumber(redis.call('HGET', KEYS[4], first_slot) or '0')
  end
  local status = 'EMPTY'
  if tail_seq > serving_seq then
    status = 'OPEN'
  end
  redis.call('HSET', KEYS[1], 'tailSeq', tail_seq, 'status', status, 'serverTimeMillis', now_millis)
  redis.call('PEXPIRE', KEYS[1], ttl_millis)
  return {serving_seq, tail_seq, active_count, first_slot_id, first_slot_tail}
end

if mode == 'ADVANCE' then
  local increment = tonumber(ARGV[3])
  if increment and increment > 0 then
    local current = tonumber(redis.call('HGET', KEYS[1], 'servingSeq') or '0')
    local next_value = current + increment
    redis.call('HSET', KEYS[1], 'servingSeq', next_value, 'status', 'OPEN', 'serverTimeMillis', now_millis)
    local first_slot = redis.call('ZRANGE', KEYS[3], 0, 0)[1]
    if first_slot then
      local first_slot_tail = tonumber(redis.call('HGET', KEYS[4], first_slot) or '0')
      if first_slot_tail <= next_value then
        redis.call('ZREM', KEYS[3], first_slot)
        redis.call('HDEL', KEYS[4], first_slot)
      end
    end
  end
end

return snapshot()
