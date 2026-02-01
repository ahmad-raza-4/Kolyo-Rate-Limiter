-- Leaky Bucket Algorithm
-- KEYS[1] = bucket key (e.g., "ratelimit:leaky:user:123")
-- ARGV[1] = capacity (max queue size, int)
-- ARGV[2] = leak rate (requests per second, float)
-- ARGV[3] = current timestamp in milliseconds (long)
-- ARGV[4] = requested slots (int, usually 1)
-- ARGV[5] = TTL in seconds (int)

-- Returns: {allowed (0/1), queue_size (int), wait_time_seconds (float)}

local bucket_key = KEYS[1]
local capacity = tonumber(ARGV[1])
local leak_rate = tonumber(ARGV[2])  -- requests per second
local now_ms = tonumber(ARGV[3])
local requested = tonumber(ARGV[4])
local ttl = tonumber(ARGV[5])

-- Fetch current bucket state
local bucket = redis.call('HMGET', bucket_key, 'queue_size', 'last_leak_ms')
local queue_size = tonumber(bucket[1])
local last_leak_ms = tonumber(bucket[2])

-- Initialize bucket if it doesn't exist
if queue_size == nil then
    queue_size = 0
    last_leak_ms = now_ms
end

-- Calculate how much has leaked since last update
local elapsed_ms = math.max(0, now_ms - last_leak_ms)
local elapsed_seconds = elapsed_ms / 1000.0
local leaked = elapsed_seconds * leak_rate

-- Update queue size after leak
local new_queue_size = math.max(0, queue_size - leaked)

-- Check if we can add the request to queue
if new_queue_size + requested <= capacity then
    -- Add request to queue
    new_queue_size = new_queue_size + requested
    
    -- Update bucket state
    redis.call('HMSET', bucket_key,
        'queue_size', tostring(new_queue_size),
        'last_leak_ms', tostring(now_ms),
        'capacity', tostring(capacity),
        'leak_rate', tostring(leak_rate)
    )
    redis.call('EXPIRE', bucket_key, ttl)
    
    -- Calculate wait time (how long until this request is processed)
    local wait_time_seconds = new_queue_size / leak_rate
    
    return {1, math.floor(new_queue_size), wait_time_seconds}  -- allowed, queue_size, wait_time
else
    -- Queue is full, deny request
    local retry_after = (new_queue_size - capacity + requested) / leak_rate
    
    -- Update bucket state without adding request
    redis.call('HMSET', bucket_key,
        'queue_size', tostring(new_queue_size),
        'last_leak_ms', tostring(now_ms)
    )
    redis.call('EXPIRE', bucket_key, ttl)
    
    return {0, math.floor(new_queue_size), retry_after}  -- denied, queue_size, retry_after
end