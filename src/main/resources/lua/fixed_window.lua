-- Fixed Window Algorithm
-- KEYS[1] = counter key (e.g., "ratelimit:fixed:user:123:1705315200")
-- ARGV[1] = max requests in window (int)
-- ARGV[2] = window size in seconds (int)

-- Returns: {allowed (0/1), remaining_requests (int)}

local counter_key = KEYS[1]
local max_requests = tonumber(ARGV[1])
local window_size = tonumber(ARGV[2])

-- Increment counter
local current_count = redis.call('INCR', counter_key)

-- Set TTL on first request in window
if current_count == 1 then
    redis.call('EXPIRE', counter_key, window_size)
end

-- Check if within limit
if current_count <= max_requests then
    local remaining = max_requests - current_count
    return {1, remaining}  -- allowed=1, remaining
else
    return {0, 0}  -- allowed=0, remaining=0
end