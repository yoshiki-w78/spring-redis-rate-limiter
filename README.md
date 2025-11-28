# Rate Limiter API (Spring Boot / Token Bucket)

[![Java](https://img.shields.io/badge/Java-21-007396)](#)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.x-6DB33F)](#)
[![Redis](https://img.shields.io/badge/Redis-Lua%20script-D82C20)](#)
[![k6](https://img.shields.io/badge/k6-load%20test-5D5CE6)](#)
[![Prometheus/Grafana](https://img.shields.io/badge/Prometheus%2FGrafana-Observability-orange)](#)

## TL;DR

- `POST /v1/allow?key=...` に対して **許可/拒否** を返すレートリミッタ
- 実装：**In-Memory** と **Redis+Lua（atomic）** の2系統
- 観測：**Micrometer →** `/actuator/prometheus` **→ Prometheus/Grafana**
- 失敗時：`fail-mode` で **OPEN（許可）/CLOSED（429）** を切替

---

## Requirements
- JDK 21
- Gradle Wrapper（同梱）
- k6 (負荷試験) — `brew install k6`
- Redis (Redis版を使う場合) — `brew install redis`
- Prometheus / Grafana（観測する場合）

## Quick Start

```bash
# In-Memory で起動
./gradlew bootRun

# 動作確認
curl -i -X POST "http://localhost:8080/v1/allow?key=user-123"
# → 200 OK（許可）または 429 Too Many Requests（拒否 + Retry-After）
```

---

## Redis 版で動かす（Homebrew 例）

```bash
brew services start redis
# application.yml で ratelimit.backend=redis にして再起動
./gradlew bootRun
```

## API
```
POST /v1/allow?key={userKey}

200:
{ "allowed": true,  "remaining": 9, "resetAtMillis": 123, "retryAfterSeconds": 0 }

429:
{ "allowed": false, "remaining": 0, "resetAtMillis": 123, "retryAfterSeconds": N }
Headers(429): Retry-After: N
```

## 設定（src/main/resources/application.yml 抜粋）
```yaml
server:
  port: 8080

ratelimit:
  backend: in-memory   # in-memory | redis
  fail-mode: OPEN      # OPEN=許可 / CLOSED=一律429
  capacity: 10
  refill-per-second: 5.0
  idle-evict-seconds: 1800

spring:
  data:
    redis:
      host: localhost
      port: 6379

management:
  endpoints:
    web:
      exposure:
        include: health, prometheus, metrics, info
  metrics:
    tags:
      application: rate-limiter
    distribution:
      percentiles-histogram:
        http:
          server:
            requests: true  # p95/p99 の可視化
```

## 負荷試験（k6）
```bash
k6 run k6/allow_smoke.js
```

## 観測（Prometheus / Grafana）
### Prometheus（最小構成）
```yaml
global: { scrape_interval: 15s }
scrape_configs:
  - job_name: "rate-limiter"
    metrics_path: /actuator/prometheus
    scrape_interval: 5s
    static_configs:
      - targets: ["localhost:8080"]
```

### Grafana（PromQL スニペット）
```promql
# p95 (for /v1/allow)
histogram_quantile(0.95,
  sum by (le) (rate(http_server_requests_seconds_bucket{uri="/v1/allow"}[1m]))
)

# 許可率(%)
100 * sum(increase(ratelimiter_requests_total{outcome="allowed"}[1m]))
  / sum(increase(ratelimiter_requests_total[1m]))

# RPS（合計/200/429）
sum(rate(http_server_requests_seconds_count{uri="/v1/allow"}[1m]))
sum(rate(http_server_requests_seconds_count{uri="/v1/allow",status="200"}[1m]))
sum(rate(http_server_requests_seconds_count{uri="/v1/allow",status="429"}[1m]))
```

## 参考結果（ローカル例／埋め替え推奨）

| Backend   | allowed率 | p95(ms) | RPS   | 備考                  |
|-----------|----------:|--------:|------:|-----------------------|
| In-Memory |   **34.2%** | **6.54** | **286.2** | 単一インスタンス向け  |
| Redis+Lua |   **33.4%** | **11.53** | **277.4** | 原子更新（複数台向け） |

> ※ あなたの k6 実行結果（`http_req_failed` ≒ 429 率）から算出。環境差が出るので数値は各自で更新してください。

**Redis 停止 × fail-mode 検証**

| fail-mode | 挙動                  | allowed率 | p95(ms) | RPS   |
|-----------|-----------------------|----------:|--------:|------:|
| OPEN      | 許可でフォールバック  | **100.0%** | **9.66** | **285.5** |
| CLOSED    | 一律 429              | **0.0%**   | **9.24** | **284.0** |

> 停止手順（Homebrew例）：`brew services stop redis` → k6 実行 → `brew services start redis`

## 実装の要点

- **Token Bucket**：`capacity` と `refill-per-second` で残量を管理  
- **/v1/allow**：1トークン消費を試み、不可なら **429 + `Retry-After`** を返却  
- **In-Memory**：単体インスタンス向け（高速）  
- **Redis + Lua（atomic）**：補充→消費→残量を **1トランザクション** で実行（複数インスタンス対応）  
- **fail-mode**：バックエンド障害時の方針を選択  
  - `OPEN` = 可用性優先（許可フォールバック）  
  - `CLOSED` = 濫用防止優先（一律 429）  
- **メトリクス**：`ratelimiter_requests_total{outcome="allowed|denied"}`、`http_server_requests_seconds_*` を公開

---

## テスト

```bash
./gradlew test
# 枯渇 → 少し待機（トークン補充）→ 再許可 のユニットテストを含む
```

## ディレクトリ構成
```
src/main/java/com/example/ratelimiter/
 ├─ RateLimiterApplication.java
 ├─ RateLimiterController.java         # /v1/allow
 ├─ RateLimiterService.java            # IF
 ├─ InMemoryRateLimiterService.java
 ├─ RedisRateLimiterService.java       # Redis + Lua
 ├─ TokenBucket.java
 ├─ AllowResult.java / FailMode.java
 └─ RateLimitProperties.java
src/main/resources/application.yml
k6/allow_smoke.js
```

## ベンチの取り方（メモ）

### 1) In-Memory（ベースライン）
```bash
# application.yml: ratelimit.backend=in-memory
./gradlew bootRun
k6 run --vus 30 --duration 30s \
  --summary-trend-stats "avg,min,med,max,p(90),p(95)" \
  --summary-export inmem-summary.json \
  k6/allow_smoke.js
```

### 2) Redis（atomic）
```bash
brew services start redis
# application.yml: ratelimit.backend=redis
./gradlew bootRun
k6 run --vus 30 --duration 30s \
  --summary-trend-stats "avg,min,med,max,p(90),p(95)" \
  --summary-export redis-summary.json \
  k6/allow_smoke.js
```

### 3) Redis 停止 × fail-mode の挙動
```bash
# OPEN検証：停止しても許可でフォールバック
brew services stop redis
k6 run --vus 30 --duration 30s \
  --summary-trend-stats "avg,min,med,max,p(90),p(95)" \
  --summary-export open-failover.json \
  k6/allow_smoke.js
brew services start redis

# CLOSED検証：一律429
# application.yml: ratelimit.fail-mode=CLOSED に変更 → 再起動して同様に計測
```

### 4) 数値の算出（k6 summary から）
```bash
# p95(ms)
jq '.metrics.http_req_duration["p(95)"]' inmem-summary.json

# 失敗率(= 429率の近似)。allowed率 ≒ 100 - 失敗率
jq '.metrics.http_req_failed.rate * 100' inmem-summary.json
```

## ライセンス
MIT License — `LICENSE` に MIT を追加してください（任意）。

