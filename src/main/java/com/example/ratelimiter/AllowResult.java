package com.example.ratelimiter;

public record AllowResult(boolean allowed, long remaining, long resetAtMillis, long retryAfterSeconds) {}
