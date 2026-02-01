-- Sliding Window Counter Algorithm
-- Uses two fixed windows and interpolates between them
-- KEYS[1] = current window key
-- KEYS[2] = previous window key
-- ARGV[1] = max requests in window (int)
-- ARGV[2] = window size in seconds (int)
-- ARGV[3] = current timestamp in seconds (long)
-- ARGV[4] = requested tokens (int)

-- Returns: {allowed (0/1), weighted_count (float), current_count (int)}

local current_key = KEYS[1]
local previous_key = KEYS[2]
local max_requests = tonumber(ARGV[1])
local window_size = tonumber(ARGV[2])
local now_seconds = tonumber(ARGV[3])
local requested_tokens = tonumber(ARGV[4])

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

-- Check if request is allowed (with requested tokens)
if (weighted_count + requested_tokens) <= max_requests then
    -- Increment current window counter by requested tokens
    local new_count = redis.call('INCRBY', current_key, requested_tokens)
    
    -- Set TTL on first request in window
    if current_count == 0 then
        redis.call('EXPIRE', current_key, window_size * 2)  -- Keep for 2 windows
    end
    
    return {1, weighted_count + requested_tokens, new_count}  -- allowed, new weighted count, current
else
    return {0, weighted_count, current_count}  -- denied
end