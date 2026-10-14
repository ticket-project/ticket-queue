# API 계약

Queue Server의 HTTP 계약과 요청 흐름의 원본이다. 클라이언트(ticket-fe)와 부하 테스트(gatling-test)는 이 계약을
기준으로 한다. 각 경로의 인증·캐시 성격과 구현 규칙은 [development.md](development.md#세-가지-요청-경로의-성격)를 본다.

## 흐름

사용자별 status polling은 제거했다. 지금은 모든 대기자가 같은 public state를 보는 방식이다.

```text
1. Frontend -> Queue Server join
   번호표(queueId, shardId, localSeq, slotId, queueToken)를 받는다.

2. Admission Scheduler -> Redis
   50ms slot 단위로 shard별 serving seq를 round-robin으로 전진시킨다.

3. Frontend -> CDN cached state API
   GET /api/v1/queue/performances/{performanceId}/state
   모든 사용자가 같은 public state API 응답을 조회한다.

4. Frontend
   내 shardId의 localSeq <= serving[shardId] 이면 enter를 호출한다.

5. Frontend -> Queue Server enter
   queueToken과 현재 serving seq를 확인하고, Redis Lua script로 입장 토큰을 원자적으로 발급한다.
   이미 토큰을 받은 번호표가 재시도하면 TTL이 남은 같은 admissionToken을 돌려준다.

6. Frontend -> Ticket Server
   seat/order API에 X-Admission-Token을 붙여 호출한다.
```

서버는 사용자마다 전역 순번을 계산하지 않는다. shard별로 "지금 몇 번까지 입장 가능한가"라는 공통 상태를 만들고,
클라이언트가 자기 shard/localSeq와 비교한다. 대규모 트래픽의 hot path는 CDN에 캐시된 `/state` 응답이다.

## Endpoint

| Method | Path | Header | 설명 |
| --- | --- | --- | --- |
| `POST` | `/api/v1/queue/performances/{performanceId}/join` | `Authorization: Bearer {accessToken}` | 번호표 발급 |
| `GET` | `/api/v1/queue/performances/{performanceId}/state` | 없음 | CDN 캐시 대상 public queue state 조회 |
| `POST` | `/api/v1/queue/performances/{performanceId}/enter` | `X-Queue-Token` | 입장 가능 시 admission token 발급 |

대기 상태 확인은 public state API만 사용한다. `/status`와 `X-Queue-Session` 기반 polling API는 제공하지 않는다.

### join 응답

```json
{
  "performanceId": 1,
  "queueId": "queue-id",
  "status": "WAITING",
  "queueToken": "signed-queue-token",
  "seq": 503,
  "shardId": 17,
  "localSeq": 503,
  "slotId": 34210000000,
  "slotStartMillis": 1710500000000,
  "pollAfterMs": 1000
}
```

### state 응답

```json
{
  "performanceId": 1,
  "status": "OPEN",
  "shardCount": 128,
  "slotSizeMillis": 50,
  "serving": {
    "0": 1500,
    "1": 1480
  },
  "tail": {
    "0": 1700,
    "1": 1660
  },
  "admittedUntilSeq": 1500,
  "tailSeq": 1700,
  "refreshAfterMs": 5000,
  "serverTimeMillis": 1717000000000
}
```

`seq`, `admittedUntilSeq`, `tailSeq`는 기존 부하 테스트와 클라이언트 전환을 위한 호환 필드다. 신규 클라이언트의 입장
가능 여부는 `serving[shardId] >= localSeq` 기준으로 판단한다.

### enter 응답

```json
{
  "status": "ACTIVE",
  "admissionToken": "signed-admission-token",
  "expiresAtMillis": 1717000900000,
  "redirectUrl": "/booking/seat?performanceId=1"
}
```

`/state`의 serving 값은 입장 자격을 뜻한다. 여러 클라이언트가 CDN 지연 뒤 동시에 `/enter`를 호출할 수 있으므로, 실제
admission token 발급 시점의 Lua script가 순서와 멱등성을 최종 확인한다. 같은 queueToken으로 재시도하면 TTL이 남은
기존 token을 반환한다.

## 캐시 경로

```text
GET /api/v1/queue/performances/{performanceId}/state
Origin Cache-Control: no-store
Cloudflare state endpoint: cache only GET /api/v1/queue/performances/*/state with Edge TTL
```

origin은 `Cache-Control: no-store`로 응답하고, Cloudflare를 사용하는 state 전용 endpoint에서만 이 경로의 Edge TTL을
강제한다. `/join`과 `/enter`는 Cloudflare를 거치지 않고 Nginx origin으로 직접 요청한다. TTL은 애플리케이션이나 nginx가
아니라 state endpoint의 Cloudflare Cache Rule에서 조정한다. 설정 기준은
[cloudflare-state-api-cache.md](cloudflare-state-api-cache.md)에 있다.

Queue API와 state가 같은 DNS-only hostname을 쓰면 `/state`도 Cloudflare를 거치지 않는다. 캐시를 적용하려면 직접
origin인 Queue API endpoint와 Cloudflare가 프록시하는 state endpoint를 분리해야 한다.
