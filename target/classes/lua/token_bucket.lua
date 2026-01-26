-- Token Bucket Algorithm
-- KEYS[1] = bucket key (e.g., "ratelimit:bucket:user:123")
-- ARGV[1] = requested tokens (int)
-- ARGV[2] = capacity (int)
-- ARGV[3] = refill rate (tokens per second, float)
-- ARGV[4] = current timestamp (milliseconds, long)
-- ARGV[5] = TTL in seconds (int)

-- Returns: {allowed (0/1), remaining_tokens (float), retry_after_seconds (float or 0)}

local bucket_key = KEYS[1]
local requested = tonumber(ARGV[1])
local capacity = tonumber(ARGV[2])
local refill_rate = tonumber(ARGV[3])
local now_millis = tonumber(ARGV[4])
local ttl = tonumber(ARGV[5])

-- Fetch current bucket state
local bucket = redis.call('HMGET', bucket_key, 'tokens', 'last_refill_ms')
local current_tokens = tonumber(bucket[1])
local last_refill_ms = tonumber(bucket[2])

-- Initialize bucket if it doesn't exist
if current_tokens == nil then
    current_tokens = capacity
    last_refill_ms = now_millis
end

-- Calculate elapsed time and refill
local elapsed_ms = math.max(0, now_millis - last_refill_ms)
local elapsed_seconds = elapsed_ms / 1000.0
local tokens_to_add = elapsed_seconds * refill_rate
local new_tokens = math.min(capacity, current_tokens + tokens_to_add)

-- Check if request can be allowed
if new_tokens >= requested then
    -- Allow request: deduct tokens
    local remaining = new_tokens - requested
    redis.call('HMSET', bucket_key, 
        'tokens', tostring(remaining),
        'last_refill_ms', tostring(now_millis),
        'capacity', tostring(capacity),
        'refill_rate', tostring(refill_rate)
    )
    redis.call('EXPIRE', bucket_key, ttl)
    return {1, remaining, 0}  -- allowed=1, remaining, retry_after=0
else
    -- Deny request: calculate retry-after
    local tokens_needed = requested - new_tokens
    local retry_after_seconds = tokens_needed / refill_rate
    
    -- Update tokens without deducting
    redis.call('HMSET', bucket_key,
        'tokens', tostring(new_tokens),
        'last_refill_ms', tostring(now_millis)
    )
    redis.call('EXPIRE', bucket_key, ttl)
    return {0, new_tokens, retry_after_seconds}  -- allowed=0, remaining, retry_after
end
