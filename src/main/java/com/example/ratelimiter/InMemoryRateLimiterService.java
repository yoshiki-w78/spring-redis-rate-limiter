package com.example.ratelimiter;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 単一インスタンス用のレートリミッター。
 * アプリ内メモリ (ConcurrentHashMap) にトークンバケットを保持する。
 *
 * backend=memory のときだけ有効になる。
 */
@Service
@ConditionalOnProperty(prefix = "ratelimit", name = "backend", havingValue = "memory", matchIfMissing = true)
public class InMemoryRateLimiterService implements RateLimiterService {

    private final RateLimitProperties props;
    private final Counter allowedCounter;
    private final Counter deniedCounter;

    private final Map<String, TokenBucket> buckets = new ConcurrentHashMap<>();

    public InMemoryRateLimiterService(RateLimitProperties props, MeterRegistry registry) {
        this.props = props;
        this.allowedCounter = Counter.builder("ratelimiter_requests_total")
                .tag("outcome", "allowed")
                .register(registry);
        this.deniedCounter = Counter.builder("ratelimiter_requests_total")
                .tag("outcome", "denied")
                .register(registry);
    }

    @Override
    public AllowResult allow(String key) {
        TokenBucket bucket = buckets.computeIfAbsent(
                key,
                k -> new TokenBucket(props.getCapacity(), props.getRefillPerSecond())
        );

        AllowResult res = bucket.takeOne();

        if (res.allowed()) {
            allowedCounter.increment();
        } else {
            deniedCounter.increment();
        }

        evictIfIdle();
        return res;
    }

    private void evictIfIdle() {
        long idleSec = props.getIdleEvictSeconds();
        if (idleSec <= 0) return;

        long now = System.currentTimeMillis();
        long cutoff = now - idleSec * 1000L;

        for (Map.Entry<String, TokenBucket> e : buckets.entrySet()) {
            if (e.getValue().lastAccessMillis() < cutoff) {
                buckets.remove(e.getKey(), e.getValue());
            }
        }
    }
}