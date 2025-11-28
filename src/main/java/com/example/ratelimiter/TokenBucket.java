package com.example.ratelimiter;

import java.util.concurrent.atomic.AtomicLong;

/**
 * シンプルなトークンバケット実装（スレッドセーフ）。
 * capacity: バースト容量
 * refillPerSecond: 1秒あたり補充するトークン数
 */
final class TokenBucket {
    private final int capacity;
    private final double refillPerSecond;

    // tokens は小数（部分トークン）も扱うため double
    private double tokens;
    private long lastRefillNanos;
    private final AtomicLong lastAccessMillis = new AtomicLong(System.currentTimeMillis());

    TokenBucket(int capacity, double refillPerSecond) {
        this.capacity = Math.max(1, capacity);
        this.refillPerSecond = Math.max(0.000001, refillPerSecond); // 0は避ける
        this.tokens = this.capacity;
        this.lastRefillNanos = System.nanoTime();
    }

    long lastAccessMillis() { return lastAccessMillis.get(); }

    /**
     * 1トークン消費を試みる。
     * 許可/不許可、残りトークン、次のトークンが得られる/満タンになる見込み時刻を返す。
     */
    synchronized AllowResult takeOne() {
        refill();
        lastAccessMillis.set(System.currentTimeMillis());

        boolean allowed;
        if (tokens >= 1.0) {
            tokens -= 1.0;
            allowed = true;
        } else {
            allowed = false;
        }

        long now = System.currentTimeMillis();
        long retryAfterSec = 0L;
        long resetAtMillis;

        if (allowed) {
            // 許可時は「満タンになる予測時刻」を resetAt とする
            long msToFull = estimateMillisToFull();
            resetAtMillis = now + msToFull;
        } else {
            // 不許可時は「次の1トークンが得られる時刻」を resetAt とする
            long msToNext = estimateMillisToNextToken();
            resetAtMillis = now + msToNext;
            retryAfterSec = Math.max(0L, (long)Math.ceil(msToNext / 1000.0));
        }

        long remaining = (long) Math.floor(tokens);
        return new AllowResult(allowed, remaining, resetAtMillis, retryAfterSec);
    }

    private void refill() {
        long now = System.nanoTime();
        double seconds = (now - lastRefillNanos) / 1_000_000_000.0;
        if (seconds <= 0) return;
        tokens = Math.min(capacity, tokens + seconds * refillPerSecond);
        lastRefillNanos = now;
    }

    private long estimateMillisToNextToken() {
        if (tokens >= 1.0) return 0L;
        double need = 1.0 - tokens;
        double sec = need / refillPerSecond;
        return (long) Math.ceil(sec * 1000.0);
    }

    private long estimateMillisToFull() {
        double need = capacity - tokens;
        if (need <= 0) return 0L;
        double sec = need / refillPerSecond;
        return (long) Math.ceil(sec * 1000.0);
    }
}
