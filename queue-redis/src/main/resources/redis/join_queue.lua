-- KEYS:
-- 1 shard-local sequence
-- 2 shard-local user entry
-- 3 shard-local queue ticket
-- 4 shard-local slot tail hash
-- 5 shard-local pending slot set
-- 6 shard-local waiting marker
--
-- ARGV:
-- 1 candidate queue id
-- 2 queue ttl millis
-- 3 slot id
-- 4 slot start millis
-- 5 waiting marker ttl millis
--
-- Returns:
-- queue_id, local_seq, slot_id, slot_start_millis, created, register_waiting_performance

local existing = redis.call('GET', KEYS[2])
if existing and existing ~= '' then
  local first = string.find(existing, '|', 1, true)
  local second = first and string.find(existing, '|', first + 1, true) or nil
  local third = second and string.find(existing, '|', second + 1, true) or nil
  if first and second and third then
    local queue_id = string.sub(existing, 1, first - 1)
    local local_seq = tonumber(string.sub(existing, first + 1, second - 1))
    local slot_id = tonumber(string.sub(existing, second + 1, third - 1))
    local slot_start_millis = tonumber(string.sub(existing, third + 1))
    if queue_id ~= '' and local_seq and slot_id and slot_start_millis then
      return {queue_id, local_seq, slot_id, slot_start_millis, 0, 0}
    end
  end
  redis.call('DEL', KEYS[2])
end

local queue_id = ARGV[1]
local ttl_millis = tonumber(ARGV[2])
local slot_id = tonumber(ARGV[3])
local slot_start_millis = tonumber(ARGV[4])
local marker_ttl_millis = tonumber(ARGV[5])
local local_seq = redis.call('INCR', KEYS[1])
redis.call('PEXPIRE', KEYS[1], ttl_millis)
-- enter_queue.lua는 두 번째 필드(local_seq)만 읽는다. 예전 6필드 ticket도 그대로 읽힌다.
local ticket_value = queue_id .. '|' .. local_seq .. '|' .. slot_id .. '|' .. slot_start_millis

redis.call('SET', KEYS[2], ticket_value, 'PX', ttl_millis)
redis.call('SET', KEYS[3], ticket_value, 'PX', ttl_millis)

redis.call('HSET', KEYS[4], tostring(slot_id), local_seq)
redis.call('PEXPIRE', KEYS[4], ttl_millis)
redis.call('ZADD', KEYS[5], slot_id, tostring(slot_id))
redis.call('PEXPIRE', KEYS[5], ttl_millis)

local marker_created = redis.call('SET', KEYS[6], '1', 'NX', 'PX', marker_ttl_millis)
local register_waiting_performance = marker_created and 1 or 0
return {queue_id, local_seq, slot_id, slot_start_millis, 1, register_waiting_performance}
