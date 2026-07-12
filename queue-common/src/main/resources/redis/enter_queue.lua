-- KEYS:
-- 1 shard state
-- 2 shard-local queue ticket
--
-- ARGV:
-- 1 local seq
--
-- Returns:
-- 1 admitted, 0 not admitted, 3 expired

local local_seq = tonumber(ARGV[1])

local ticket_value = redis.call('GET', KEYS[2])
local stored_local_seq = -1
if ticket_value and ticket_value ~= '' then
  local first = string.find(ticket_value, '|', 1, true)
  local second = first and string.find(ticket_value, '|', first + 1, true) or nil
  if first and second then
    stored_local_seq = tonumber(string.sub(ticket_value, first + 1, second - 1)) or -1
  end
else
  stored_local_seq = tonumber(redis.call('HGET', KEYS[2], 'localSeq') or '-1')
end

if stored_local_seq ~= local_seq then
  return {3}
end

local serving_seq = tonumber(redis.call('HGET', KEYS[1], 'servingSeq') or '0')
if local_seq > serving_seq then
  return {0}
end

return {1}
