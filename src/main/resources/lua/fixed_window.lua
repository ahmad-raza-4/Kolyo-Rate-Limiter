-- Fixed Window Algorithm
-- KEYS[1] = counter key (e.g., "ratelimit:fixed:user:123:1705315200")
-- ARGV[1] = max requests in window (int)
-- ARGV[2] = window size in seconds (int)
-- ARGV[3] = requested tokens (int)

-- Returns: {allowed (0/1), remaining_requests (int)}

local counter_key = KEYS[1]
local max_requests = tonumber(ARGV[1])
local window_size = tonumber(ARGV[2])
local requested = tonumber(ARGV[3])

local current_count = tonumber(redis.call('GET', counter_key)) or 0

if (current_count + requested) <= max_requests then
    local new_count = redis.call('INCRBY', counter_key, requested)
    if new_count == requested then
        redis.call('EXPIRE', counter_key, window_size)
    end
    local remaining = max_requests - new_count
    return {1, remaining}
else
    local remaining = max_requests - current_count
    if remaining < 0 then
        remaining = 0
    end
    return {0, remaining}
end