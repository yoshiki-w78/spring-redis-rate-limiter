package com.example.ratelimiter;

/**
 * Redisなど外部ストアが死んだときに、どう振る舞うか。
 *
 * OPEN:
 *   外部ストアが死んでも「とりあえず許可」= ユーザー体験優先
 *   (スパムは通るかもしれないけど、サービス停止は避けたい)
 *
 * CLOSED:
 *   外部ストアが死んだら「拒否」= セキュリティ/濫用対策優先
 *   (健全なユーザーも弾かれるけど、システムは守られる)
 */
public enum FailMode {
    OPEN,
    CLOSED
}
