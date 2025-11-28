package com.example.ratelimiter;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

@Service
@ConditionalOnProperty(prefix = "ratelimit", name = "backend", havingValue = "redis")
public class RedisRateLimiterService implements RateLimiterService {

    private static final String SCRIPT = """
        -- KEYS[1] : bucket key (e.g. "rl:{userKey}")
        -- ARGV[1] : capacity (int)
        -- ARGV[2] : refill_per_sec (double)
        -- ARGV[3] : idle_evict_seconds (int, 0 = disable)
        
        local bucket = KEYS[1]
        local capacity = tonumber(ARGV[1])
        local refill_per_sec = tonumber(ARGV[2])
        local idle_evict = tonumber(ARGV[3])
        
        -- use server time to avoid client clock skew
        local t = redis.call('TIME')
        local now_ms = t[1] * 1000 + math.floor(t[2] / 1000)
        
        local tokens = tonumber(redis.call('HGET', bucket, 'tokens'))
        local last_ms = tonumber(redis.call('HGET', bucket, 'last_ms'))
        
        if tokens == nil then
          tokens = capacity
          last_ms = now_ms
        else
          local delta = now_ms - last_ms
          if delta > 0 then
            local refill = (delta / 1000.0) * refill_per_sec
            tokens = math.min(capacity, tokens + refill)
            last_ms = now_ms
          end
        end
        
        local allowed = 0
        local retry_after = 0
        
        if tokens >= 1.0 then
          tokens = tokens - 1.0
          allowed = 1
        else
          local need = 1.0 - tokens
          if refill_per_sec > 0 then
            retry_after = math.max(1, math.ceil(need / refill_per_sec))
          else
            retry_after = 1
          end
        end
        
        redis.call('HSET', bucket, 'tokens', tostring(tokens), 'last_ms', tostring(last_ms))
        if idle_evict and idle_evict > 0 then
          redis.call('PEXPIRE', bucket, idle_evict * 1000)
        end
        
        local remaining = math.floor(tokens + 0.000001)
        local rps = refill_per_sec
        if rps <= 0 then rps = 1 end
        local to_full_sec = (capacity - tokens) / rps
        local reset_ms = now_ms + math.floor(to_full_sec * 1000 + 0.5)
        
        return {allowed, remaining, reset_ms, retry_after}
        """;

    private final RateLimitProperties props;
    private final StringRedisTemplate redis;
    private final Counter allowedCounter;
    private final Counter deniedCounter;

    // ★ コンストラクタで resultType=List を指定（setScriptText/ResultType 不要）
    private final DefaultRedisScript<List> luaScript =
            new DefaultRedisScript<>(SCRIPT, List.class);

    public RedisRateLimiterService(
            RateLimitProperties props,
            StringRedisTemplate redisTemplate,
            MeterRegistry registry
    ) {
        this.props = props;
        this.redis = redisTemplate;
        this.allowedCounter = Counter.builder("ratelimiter_requests_total")
                .tag("outcome", "allowed")
                .register(registry);
        this.deniedCounter = Counter.builder("ratelimiter_requests_total")
                .tag("outcome", "denied")
                .register(registry);
    }

    @Override
    public AllowResult allow(String key) {
        final String bucketKey = "rl:" + key;
        try {
            // execute(script, keys, args...)
            List<?> res = redis.execute(
                    luaScript,
                    Collections.singletonList(bucketKey),
                    String.valueOf(props.getCapacity()),
                    String.valueOf(props.getRefillPerSecond()),
                    String.valueOf(props.getIdleEvictSeconds())
            );

            if (res == null || res.size() < 4) {
                return onScriptFailure();
            }

            boolean allowed = toLong(res.get(0)) == 1L;
            long remaining = toLong(res.get(1));
            long resetAtMillis = toLong(res.get(2));
            long retryAfterSeconds = toLong(res.get(3));

            if (allowed) allowedCounter.increment(); else deniedCounter.increment();

            return new AllowResult(allowed, remaining, resetAtMillis, retryAfterSeconds);

        } catch (DataAccessException e) {
            // Redis障害時: fail-open / fail-closed
            return onRedisDown();
        }
    }

    private AllowResult onScriptFailure() {
        if (props.getFailMode() == FailMode.OPEN) {
            allowedCounter.increment();
            long now = System.currentTimeMillis();
            return new AllowResult(true, 0, now, 0);
        } else {
            deniedCounter.increment();
            long now = System.currentTimeMillis();
            return new AllowResult(false, 0, now + 1000, 1);
        }
    }

    private AllowResult onRedisDown() {
        if (props.getFailMode() == FailMode.OPEN) {
            allowedCounter.increment();
            long now = System.currentTimeMillis();
            return new AllowResult(true, 0, now, 0);
        } else {
            deniedCounter.increment();
            long now = System.currentTimeMillis();
            return new AllowResult(false, 0, now + 1000, 1);
        }
    }

    private long toLong(Object o) {
        if (o instanceof Number n) return n.longValue();
        return Long.parseLong(String.valueOf(o));
    }
}