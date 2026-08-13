# Ticket Queue Server

Redis 기반 대기열 서버입니다. 목표는 인기 회차 오픈 시 Ticket Server로 직접 트래픽이 몰리지 않도록, 사용자를 대기열에 세우고 입장 가능한 사용자에게만 admission token을 발급하는 것입니다.

## 멀티모듈과 실행 프로세스

하나의 저장소에서 Redis 규약을 공유하되 API와 스케줄러는 서로 다른 Spring Boot 애플리케이션과 Docker 이미지로 배포합니다.

```text
queue-api       HTTP API, 인증, queue/admission token, API용 Redis 명령
queue-scheduler 상시 스케줄링, 입장 인원 계산, public state 갱신
queue-redis     Redis key, Lua script, Redisson 설정만 공유하는 얇은 라이브러리
```

API를 여러 ECS task로 늘려도 스케줄러 수와 함께 늘어나지 않습니다. 스케줄러는 별도 ECS service에서 기본 1 task로 운영합니다. Redis 분산 락은 롤링 배포나 장애 교체 중 일시적으로 task가 겹칠 때 같은 회차의 동시 전진을 막는 안전장치이며, 여러 scheduler task의 상시 active-active 운영을 보장하지는 않습니다. 두 애플리케이션은 같은 Redis, 같은 대기열 정책 설정, 같은 커밋에서 만들어진 이미지를 사용해야 합니다.

```text
client -> nginx -> queue-api -----------+
                                         +-> Redis
                     queue-scheduler ----+
```

## 핵심 구조

기존 사용자별 status polling 방식은 제거했습니다. 현재 흐름은 public state 방식입니다.

```text
1. Frontend -> Queue Server join
   번호표(queueId, shardId, localSeq, slotId, queueToken)를 받습니다.

2. Admission Scheduler -> Redis
   50ms slot 단위로 shard별 serving seq를 round-robin으로 전진시킵니다.

3. Frontend -> CDN cached state API
   GET /api/v1/queue/performances/{performanceId}/state
   모든 사용자가 같은 public state API 응답을 조회합니다.

4. Frontend
   내 shardId의 localSeq <= serving[shardId] 이면 enter를 호출합니다.

5. Frontend -> Queue Server enter
   queueToken과 현재 serving seq를 확인하고, Redis Lua script로 입장 토큰을 원자적으로 발급합니다.
   이미 토큰을 받은 번호표가 재시도하면 TTL이 남은 같은 admissionToken을 돌려줍니다.

6. Frontend -> Ticket Server
   seat/order API에 X-Admission-Token을 붙여 호출합니다.
```

public state 방식에서는 서버가 사용자마다 전역 순번을 계산하지 않습니다. 서버는 shard별로 “현재 몇 번까지 입장 가능하다”는 공통 상태를 만들고, 클라이언트가 자기 shard/localSeq와 비교합니다. 대규모 트래픽의 hot path는 CDN에 캐시된 `/state` API 응답입니다.

## API

| Method | Path | Header | 설명 |
| --- | --- | --- | --- |
| `POST` | `/api/v1/queue/performances/{performanceId}/join` | `Authorization: Bearer {accessToken}` | 번호표 발급 |
| `GET` | `/api/v1/queue/performances/{performanceId}/state` | 없음 | CDN 캐시 대상 public queue state 조회 |
| `POST` | `/api/v1/queue/performances/{performanceId}/enter` | `X-Queue-Token` | 입장 가능 시 admission token 발급 |

대기 상태 확인은 public state API만 사용합니다. `/status`와 `X-Queue-Session` 기반 polling API는 제공하지 않습니다.

### queue runtime config

Queue Server는 회차별 처리량 정책을 Redis에 저장하지 않습니다. 입장량은 scheduler 공통 `advanceBatchSize`, TTL은 애플리케이션 공통값으로 관리합니다.

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

### public state 응답

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

`seq`, `admittedUntilSeq`, `tailSeq`는 기존 부하 테스트와 클라이언트 전환을 위한 호환 필드다. 신규 클라이언트의 입장 가능 여부는 `serving[shardId] >= localSeq` 기준으로 판단한다.

운영 캐시 경로는 아래 API 응답입니다.

```text
GET /api/v1/queue/performances/{performanceId}/state
Origin Cache-Control: no-store
Cloudflare state endpoint: cache only GET /api/v1/queue/performances/*/state with Edge TTL
```

origin은 `Cache-Control: no-store`로 응답하고, Cloudflare를 사용하는 state 전용 endpoint에서만 이 경로의 Edge TTL을 강제합니다. `/join`과 `/enter`는 Cloudflare를 거치지 않고 Nginx origin으로 직접 요청합니다. TTL은 애플리케이션이나 nginx가 아니라 state endpoint의 Cloudflare Cache Rule에서 조정합니다.

Cloudflare 설정 기준은 `docs/cloudflare-state-api-cache.md`에 정리합니다.

### enter 응답

```json
{
  "status": "ACTIVE",
  "admissionToken": "signed-admission-token",
  "expiresAtMillis": 1717000900000,
  "redirectUrl": "/booking/seat?performanceId=1"
}
```
`/state`의 serving 값은 입장 자격을 뜻합니다. 여러 클라이언트가 CDN 지연 뒤 동시에 `/enter`를 호출할 수 있으므로, 실제 admission token 발급 시점의 Lua script가 순서와 멱등성을 최종 확인합니다. 같은 queueToken으로 재시도하면 TTL이 남은 기존 token을 반환합니다.


## Redis Key

join과 enter readiness hot path는 shard별 key를 사용합니다. key 이름에는 `{performanceId:shardId}` hash tag 형태를 유지하고, public state projection과 입장 멱등 marker는 회차 단위 `{performanceId}` key를 사용합니다.

`/join`은 shard-local counter, user marker, compact ticket, slot tail, pending slot, waiting marker만 갱신합니다. shard state와 public state는 scheduler가 갱신하므로 join hot path에서 public projection write를 하지 않습니다.

```text
q:{performanceId:shardId}:seq                   # shard-local localSeq counter; defaultQueueTtl
q:{performanceId:shardId}:state                 # scheduler가 갱신하는 shard serving/tail state; defaultQueueTtl
q:{performanceId:shardId}:user:{userIdHash}     # 사용자별 중복 join 방지 compact value; defaultQueueTtl
q:{performanceId:shardId}:queue:{queueId}       # queue ticket compact value; defaultQueueTtl
q:{performanceId:shardId}:slot-tail             # slot별 local tail hash; defaultQueueTtl
q:{performanceId:shardId}:pending-slots         # 아직 처리되지 않은 slot ZSET; defaultQueueTtl
q:{performanceId:shardId}:waiting-marker        # waiting set 재등록을 줄이는 shard marker; 10초
q:{performanceId}:state                         # public state projection hash; defaultQueueTtl
q:{performanceId}:entered:{queueId}             # enter 멱등 marker hash; shoppingSessionTtl
q:{performanceId}:queue:{queueId}               # 이전 queue ticket 호환 조회용; 새 join은 생성하지 않음
queue:waiting:performances                       # scheduler scan 대상 performanceId set; TTL 없이 비면 제거
```

## Scheduler

스케줄러는 waiting set에서 사용자를 active set으로 옮기지 않습니다. 닫힌 slot을 찾아 shard별 `servingSeq`를 증가시킵니다. 같은 slot 안에서는 shard round-robin으로 전진합니다.

각 shard snapshot은 `q:{performanceId:shardId}:seq`를 읽어 tail을 계산합니다. 그래서 `/join`은 state hash를 매번 갱신하지 않아도 되고, public projection은 scheduler 주기에서만 만들어집니다.

```text
capacity = min(advanceBatchSize, pendingInClosedSlots)
```

`advanceBatchSize`는 회차별 운영 정책이 아니라 scheduler 전체에 적용하는 기술 설정입니다. 기본 실행 주기가 1초이고 batch가 500이면 한 회차는 최대 약 500명/초 전진합니다. 동시에 처리하는 회차가 여러 개면 합계는 더 커질 수 있으므로, 실제 Core 처리량을 측정해 보수적으로 조정해야 합니다.

advance 이후에는 Redis의 public state가 갱신됩니다. 사용자는 `/state` API를 호출하지만 Cloudflare가 이 응답을 짧게 캐시하므로 대부분의 polling 부하는 CDN에서 흡수합니다.

## 주요 설정

운영 secret 기본값은 없습니다. 로컬 실행 시에도 직접 지정해야 합니다.

| 설정 | 기본값 | 설명 |
| --- | --- | --- |
| `spring.data.redis.host` | `localhost` | Redis host |
| `spring.data.redis.port` | `6379` | Redis port |
| `app.queue.default-queue-ttl` | `24h` | queue token/번호표 TTL |
| `app.queue.shopping-session-ttl` | `15m` | admission token과 중복 입장 방지 marker TTL |
| `app.queue.advance-batch-size` | `500` | scheduler가 회차 하나를 한 번에 전진시킬 최대 인원 |
| `app.queue.default-refresh-after-ms` | `5000` | 클라이언트 state 재조회 권장 간격 |
| `app.queue.shard-count` | `128` | 회차별 queue shard 수 |
| `app.queue.slot-size-millis` | `50` | 공정성 time slot 크기 |
| `app.queue.slot-close-grace-millis` | `200` | slot 확정 전 대기 grace |
| `app.queue.join-poll-after-ms` | `1000` | join 응답 후 state 재조회 권장 간격 |
| `app.queue.advance-interval-ms` | `1000` | scheduler 실행 간격(ms), scheduler 모듈 전용 |
| `app.queue.redirect.ticketing-url-template` | `/booking/seat?performanceId={performanceId}` | 입장 후 redirect URL |

| 환경변수 | 기본값 | 설명 |
| --- | --- | --- |
| `QUEUE_TOKEN_SECRET` | 없음 | queueToken 서명 secret, 32바이트 이상 |
| `QUEUE_DEFAULT_QUEUE_TTL` | `24h` | API와 scheduler가 공유하는 queue 상태 TTL |
| `QUEUE_DEFAULT_REFRESH_AFTER_MS` | `5000` | API와 scheduler가 공유하는 state 재조회 권장 간격 |
| `QUEUE_SHARD_COUNT` | `128` | API와 scheduler가 공유하는 shard 수 |
| `QUEUE_SLOT_SIZE_MILLIS` | `50` | API와 scheduler가 공유하는 slot 크기 |
| `QUEUE_SLOT_CLOSE_GRACE_MILLIS` | `200` | API와 scheduler가 공유하는 slot 확정 grace |
| `QUEUE_ADVANCE_BATCH_SIZE` | `500` | scheduler의 회차별 1회 전진 인원 |
| `ADMISSION_TOKEN_SECRET_KEY` | 없음 | Ticket Server와 공유하는 admission token secret, 32바이트 이상 |
| `JWT_SECRET` | 없음 | Core access token 검증용 JWT secret, 32바이트 이상 |
| `JWT_ISSUER` | `ticket` | Core access token issuer |
| `JWT_ACCESS_TOKEN_EXPIRATION_SECONDS` | `1800` | Core access token expiration seconds |
| `QUEUE_ADVANCE_INTERVAL_MS` | `1000` | scheduler 실행 간격(ms) |

로컬 실행 예시:

```powershell
docker compose -f docker-compose.local.yml up -d redis

$env:JWT_SECRET="local-access-token-secret-key-32bytes"
$env:JWT_ISSUER="ticket"
$env:JWT_ACCESS_TOKEN_EXPIRATION_SECONDS="1800"
$env:ADMISSION_TOKEN_SECRET_KEY="local-admission-secret-key-32bytes"
$env:QUEUE_TOKEN_SECRET="local-queue-token-secret-key-32bytes"

# 터미널 1: API
.\gradlew.bat :queue-api:bootRun

# 터미널 2: 상시 스케줄러
.\gradlew.bat :queue-scheduler:bootRun
```

로컬에서 두 프로세스를 직접 실행하면 기본값으로 같은 Redis(`localhost:6379`)를 사용하고 Datadog Agent는 띄우지 않습니다. 운영 compose는 현재 내부 Docker Redis(`redis:6379`)를 함께 띄우며, ECS 전환 시 두 서비스의 Redis 주소를 같은 managed Redis endpoint로 바꾸면 됩니다.

## 검증과 패키징

```powershell
.\gradlew.bat clean test :queue-redis:test :queue-api:test :queue-scheduler:test
.\gradlew.bat :queue-api:bootJar :queue-scheduler:bootJar
```

결과물은 각각 `queue-api/build/libs/queue-api.jar`, `queue-scheduler/build/libs/queue-scheduler.jar`입니다.

## AWS EC2 Deployment

`deploy/` and `.github/workflows/deploy.yml` provide a two-image deployment for running `ticket-queue-api`, `ticket-queue-scheduler`, Nginx, Redis, and Datadog Agent on AWS EC2. Redis는 compose 내부 service로 띄우며 외부 포트는 publish하지 않는다.

```text
client -> nginx -> /api/v1/queue/**/join, /enter -> ticket-queue-api -> Docker Redis
client -> Cloudflare state endpoint -> cached /api/v1/queue/performances/*/state -> nginx -> ticket-queue-api -> Docker Redis
queue-scheduler -> Docker Redis
```

`/join`과 `/enter`는 Cloudflare 경로가 아니다. Cloudflare 캐시는 public `/state` 조회에만 선택적으로 사용하며, 이를 적용하려면 직접 origin인 Queue API endpoint와 Cloudflare가 프록시하는 state endpoint를 분리해야 한다. Queue API와 state가 같은 DNS-only hostname을 사용하면 `/state`도 Cloudflare를 거치지 않는다.

Real secrets stay in `/home/ubuntu/ticket-queue/.env` on the EC2 instance. GitHub Actions builds and pushes both images with the same commit SHA, uploads the nginx config to the EC2 instance, then runs `docker compose up -d --remove-orphans` with the server-owned Compose file.

See `deploy/README.md` for EC2 setup and required GitHub Secrets.

## 남은 운영 검증

기본 `advanceBatchSize=500`, `advanceIntervalMs=1000`은 측정 결과가 아닙니다. Core 실제 예매 흐름의 마지막 안정 구간을 찾은 뒤 여유를 둔 값으로 조정합니다.

현재 batch 한도는 대기 중인 회차마다 각각 적용됩니다. 인기 회차가 동시에 여러 개 열릴 가능성이 생기면, 그때 전체 Core 유입량을 제한하는 전역 예산을 별도 설계합니다.

이 구조는 100만 대기자 polling 부하를 Cloudflare 캐시로 흡수하고, 단일 인기 회차의 join hot key를 shard로 분산하기 위한 구조입니다. CDN cache miss는 Spring Boot와 Redis를 통과하므로, cache hit ratio와 origin request count를 별도로 확인해야 합니다. 정확한 전역 FIFO는 제공하지 않고 50ms slot 단위 공정성과 shard round-robin을 사용합니다.

권장 부하 테스트 순서:

1. Queue join 단독
2. CDN cached state API 단독
3. Queue enter 단독
4. Ticket Server capacity
5. Full flow
