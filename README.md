# Rate Limiter API (Spring Boot)

シンプルなトークンバケット方式の Rate Limiter を **超入門** 向けに用意しました。

## すぐ試す

```bash
# 1) JDK 21 を用意し、プロジェクト直下で
./gradlew bootRun   # ※gradle をお持ちでない場合は IntelliJ で開いて実行でもOK

# 2) 別ターミナル
curl -i -X POST "http://localhost:8080/v1/allow?key=user-123"
```

- 許可: `200 OK` + JSON
- 不許可: `429 Too Many Requests` + `Retry-After` ヘッダ + JSON

レスポンス例:
```json
{ "allowed": true, "remaining": 8, "resetAtMillis": 1730548800000, "retryAfterSeconds": 0 }
```

## 設定

`src/main/resources/application.yml`

```yaml
ratelimit:
  capacity: 10
  refill-per-second: 5.0
  idle-evict-seconds: 1800
```

## Prometheus / Actuator

- メトリクス: `GET /actuator/prometheus`
- ヘルス: `GET /actuator/health`

`ratelimiter_requests_total{outcome="allowed|denied"}` が増加します。

## k6 負荷テスト

```bash
k6 run k6/allow_smoke.js
```

## テスト

```bash
./gradlew test
```

## 仕組み概要

- **TokenBucket**: capacity と refillPerSecond（1秒あたり補充）で管理
- **/v1/allow**: 1トークン消費を試みる。ダメなら 429 + `Retry-After` を返却
- **Micrometer**: allowed/denied をカウント
- **Evict**: 長時間使われていないキーは簡易掃除（任意）

## 次の発展

- Redis バックエンド（複数インスタンス対応）
- per-key のレート設定 `/v1/config/{key}`
- バックオフ＆リトライの比較グラフ（k6 + Grafana）
```

