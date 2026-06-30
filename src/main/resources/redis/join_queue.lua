local existing = redis.call('GET', KEYS[2])
if existing and existing ~= '' then
  local separator = string.find(existing, '|', 1, true)
  if separator then
    local queue_id = string.sub(existing, 1, separator - 1)
    local seq = tonumber(string.sub(existing, separator + 1))
    if queue_id ~= '' and seq then
      return {queue_id, seq, 0}
    end
  end
  redis.call('DEL', KEYS[2])
end

local queue_id = ARGV[1]
local user_id_hash = ARGV[2]
local ttl_millis = tonumber(ARGV[3])
local marker_ttl_millis = tonumber(ARGV[4])
local time = redis.call('TIME')
local now_millis = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
local seq = redis.call('INCR', KEYS[1])
local ticket_value = queue_id .. '|' .. seq .. '|' .. user_id_hash .. '|' .. now_millis

redis.call('SET', KEYS[2], queue_id .. '|' .. seq, 'PX', ttl_millis)
redis.call('SET', KEYS[3], ticket_value, 'PX', ttl_millis)

local marker_created = redis.call('SET', KEYS[4], '1', 'PX', marker_ttl_millis, 'NX')
if marker_created then
  return {queue_id, seq, 1, 1}
end

return {queue_id, seq, 1, 0}
