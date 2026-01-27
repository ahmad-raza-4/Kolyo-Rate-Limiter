-- Sliding Window Algorithm
-- KEYS[1] = window key (e.g., "ratelimit:sliding:user:123")
-- ARGV[1] = max requests in window (int)
-- ARGV[2] = window size in milliseconds (long)
-- ARGV[3] = current timestamp in milliseconds (long)
-- ARGV[4] = unique request ID (string)
-- ARGV[5] = TTL in seconds (int)

-- Returns: {allowed (0/1), remaining_requests (int), oldest_request_ms (long)}

local window_key = KEYS[1]
local max_requests = tonumber(ARGV[1])
local window_ms = tonumber(ARGV[2])
local now_ms = tonumber(ARGV[3])
local request_id = ARGV[4]
local ttl = tonumber(ARGV[5])

-- Calculate window start time
local window_start = now_ms - window_ms

-- Remove expired entries (older than window start)
redis.call('ZREMRANGEBYSCORE', window_key, 0, window_start)

-- Count current requests in window
local current_count = redis.call('ZCARD', window_key)

-- Check if request can be allowed
if current_count < max_requests then
    -- Add new request
    redis.call('ZADD', window_key, now_ms, request_id)
    redis.call('EXPIRE', window_key, ttl)
    
    local remaining = max_requests - current_count - 1
    local oldest = redis.call('ZRANGE', window_key, 0, 0, 'WITHSCORES')
    local oldest_ms = 0
    if oldest[2] then
        oldest_ms = tonumber(oldest[2])
    end
    return {1, remaining, oldest_ms}  -- allowed=1, remaining, oldest
else
    -- Deny request
    local oldest = redis.call('ZRANGE', window_key, 0, 0, 'WITHSCORES')
    local oldest_ms = 0
    if oldest[2] then
        oldest_ms = tonumber(oldest[2])
    end
    return {0, 0, oldest_ms}  -- allowed=0, remaining=0, oldest
end