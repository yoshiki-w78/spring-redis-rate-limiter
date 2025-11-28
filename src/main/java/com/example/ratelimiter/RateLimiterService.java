package com.example.ratelimiter;

public interface RateLimiterService {
    //指定されたkeyに対して１リクエスト分の許可を取るイメージ
    AllowResult allow(String key);
}
