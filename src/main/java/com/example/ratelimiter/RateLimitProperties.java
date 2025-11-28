package com.example.ratelimiter;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * アプリ全体のレートリミッター設定値。
 *
 * backend:
 *   "memory" -> InMemoryRateLimiterService を使う
 *   "redis"  -> RedisRateLimiterService を使う（＝将来的に複数インスタンス対応）
 *
 * failMode:
 *   Redis等が落ちた時に OPEN or CLOSED どちらで振る舞うか
 */
@ConfigurationProperties(prefix = "ratelimit")
public class RateLimitProperties {
    /** バースト容量（トークンの最大保持量） */
    private int capacity = 10;

    /** 1秒あたり補充されるトークン数 */
    private double refillPerSecond = 5.0;

    /** 使われなくなったバケツを掃除するまでの秒数 (InMemory用, 0で無効) */
    private long idleEvictSeconds = 0L;

    /**
     * backend 実装の選択肢:
     *  - "memory" (デフォルト)
     *  - "redis"
     */
    private String backend = "memory";

    /**
     * 外部ストア障害時の振る舞い:
     *  - OPEN: 許可方向 (サービス継続を優先)
     *  - CLOSED: 拒否方向 (スパム耐性を優先)
     */
    private FailMode failMode = FailMode.OPEN;

    public int getCapacity() { return capacity; }
    public void setCapacity(int capacity) { this.capacity = capacity; }

    public double getRefillPerSecond() { return refillPerSecond; }
    public void setRefillPerSecond(double refillPerSecond) { this.refillPerSecond = refillPerSecond; }

    public long getIdleEvictSeconds() { return idleEvictSeconds; }
    public void setIdleEvictSeconds(long idleEvictSeconds) { this.idleEvictSeconds = idleEvictSeconds; }

    public String getBackend() { return backend; }
    public void setBackend(String backend) { this.backend = backend; }

    public FailMode getFailMode() { return failMode; }
    public void setFailMode(FailMode failMode) { this.failMode = failMode; }
}