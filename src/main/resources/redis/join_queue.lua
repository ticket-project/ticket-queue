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
local user_id_hash = ARGV[2]
local ttl_millis = tonumber(ARGV[3])
local slot_id = tonumber(ARGV[4])
local slot_start_millis = tonumber(ARGV[5])
local marker_ttl_millis = tonumber(ARGV[6])
local time = redis.call('TIME')
local now_millis = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
local local_seq = redis.call('INCR', KEYS[1])
local ticket_value = queue_id .. '|' .. local_seq .. '|' .. slot_id .. '|' .. slot_start_millis .. '|' .. user_id_hash .. '|' .. now_millis

redis.call('SET', KEYS[2], queue_id .. '|' .. local_seq .. '|' .. slot_id .. '|' .. slot_start_millis, 'PX', ttl_millis)
redis.call('SET', KEYS[3], ticket_value, 'PX', ttl_millis)

redis.call('HSET', KEYS[4], tostring(slot_id), local_seq)
redis.call('PEXPIRE', KEYS[4], ttl_millis)
redis.call('ZADD', KEYS[5], slot_id, tostring(slot_id))
redis.call('PEXPIRE', KEYS[5], ttl_millis)

local marker_created = redis.call('SET', KEYS[6], '1', 'PX', marker_ttl_millis, 'NX')
if marker_created then
  return {queue_id, local_seq, slot_id, slot_start_millis, 1, 1}
end

return {queue_id, local_seq, slot_id, slot_start_millis, 1, 0}
