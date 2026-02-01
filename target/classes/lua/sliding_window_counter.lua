-- Sliding Window Counter Algorithm
-- Uses two fixed windows and interpolates between them
-- KEYS[1] = current window key
-- KEYS[2] = previous window key
-- ARGV[1] = max requests in window (int)
-- ARGV[2] = window size in seconds (int)
-- ARGV[3] = current timestamp in seconds (long)

-- Returns: {allowed (0/1), weighted_count (float), current_count (int)}

local current_key = KEYS[1]
local previous_key = KEYS[2]
local max_requests = tonumber(ARGV[1])
local window_size = tonumber(ARGV[2])
local now_seconds = tonumber(ARGV[3])

-- Get current window count
local current_count = tonumber(redis.call('GET', current_key)) or 0

-- Get previous window count
local previous_count = tonumber(redis.call('GET', previous_key)) or 0

-- Calculate position in current window
local current_window_start = now_seconds - (now_seconds % window_size)
local elapsed_in_current_window = now_seconds - current_window_start
local previous_window_weight = (window_size - elapsed_in_current_window) / window_size

-- Calculate weighted request count
local weighted_count = (previous_count * previous_window_weight) + current_count

-- Check if request is allowed
if weighted_count < max_requests then
    -- Increment current window counter
    redis.call('INCR', current_key)
    current_count = current_count + 1
    
    -- Set TTL on first request in window
    if current_count == 1 then
        redis.call('EXPIRE', current_key, window_size * 2)  -- Keep for 2 windows
    end
    
    return {1, weighted_count + 1, current_count}  -- allowed, new weighted count, current
else
    return {0, weighted_count, current_count}  -- denied
end