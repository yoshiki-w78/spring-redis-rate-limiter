package com.example.ratelimiter;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@Validated
@RequestMapping("/v1")
public class RateLimiterController {

    private final RateLimiterService service;

    // ここで注入されるのは InMemoryRateLimiterService（今は1種類しか @Service がないので自動でそれが入る）
    public RateLimiterController(RateLimiterService service) {
        this.service = service;
    }

    /**
     * 使い方:
     *  curl -i -X POST "http://localhost:8080/v1/allow?key=user-123"
     *
     * レスポンス:
     *  - 200 OK            → allowed=true
     *  - 429 Too Many ...  → allowed=false + Retry-After ヘッダ
     */
    @PostMapping("/allow")
    public ResponseEntity<AllowResult> allow(@RequestParam("key") String key) {
        AllowResult res = service.allow(key);

        if (res.allowed()) {
            return ResponseEntity.ok(res);
        } else {
            HttpHeaders headers = new HttpHeaders();
            headers.add("Retry-After", String.valueOf(res.retryAfterSeconds()));
            return new ResponseEntity<>(res, headers, HttpStatus.TOO_MANY_REQUESTS);
        }
    }
}
