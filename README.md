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
   queueToken 검증 후 전역/회차별 입장률과 active session 한도를 원자적으로 검사합니다.
   한도 안이면 admissionToken을 받고, 429이면 잠시 뒤 같은 queueToken으로 재시도합니다.

6. Frontend -> Ticket Server
   seat/order API에 X-Admission-Token을 붙여 호출합니다.

7. Ticket Server -> Queue Server internal complete
   주문 생성이 끝나면 신뢰된 서버 간 호출로 active session을 즉시 반환합니다. 호출 실패 시 TTL이 안전망입니다.
```

public state 방식에서는 서버가 사용자마다 전역 순번을 계산하지 않습니다. 서버는 shard별로 “현재 몇 번까지 입장 가능하다”는 공통 상태를 만들고, 클라이언트가 자기 shard/localSeq와 비교합니다. 대규모 트래픽의 hot path는 CDN에 캐시된 `/state` API 응답입니다.

## API

| Method | Path | Header | 설명 |
| --- | --- | --- | --- |
| `POST` | `/api/v1/queue/performances/{performanceId}/join` | `Authorization: Bearer {accessToken}` | 번호표 발급 |
| `GET` | `/api/v1/queue/performances/{performanceId}/state` | 없음 | CDN 캐시 대상 public queue state 조회 |
| `POST` | `/api/v1/queue/performances/{performanceId}/enter` | `X-Queue-Token` | 입장 가능 시 admission token 발급 |
| `POST` | `/api/v1/queue/internal/performances/{performanceId}/sessions/{queueId}/complete` | `X-Queue-Completion-Secret` | Core 전용 active session 반환 |

내부 완료 API는 브라우저에 노출하는 공개 API가 아닙니다. Ticket Server처럼 신뢰된 호출자만 공유 secret을 보내야 하며, 완료된 번호표의 admission token 재발급도 차단합니다.
`app.queue.completion-enabled=false`이면 이 API는 비활성화되고 active session은 shopping session TTL로만 만료됩니다. 즉시 반환을 사용할 때만 Queue와 Core 양쪽에서 완료 콜백을 켜고 같은 32자 이상 secret을 설정합니다.

대기 상태 확인은 public state API만 사용합니다. `/status`와 `X-Queue-Session` 기반 polling API는 제공하지 않습니다.

### queue runtime config

Queue Server는 회차별 queue policy를 Redis에 저장하지 않습니다. 모든 회차는 Queue Server를 통과한다고 보고, 입장 속도와 TTL은 애플리케이션 기본값만 사용합니다.

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
`/state`의 serving 값은 입장 자격을 뜻할 뿐 실제 발급 보장은 아닙니다. 여러 클라이언트가 CDN 지연 뒤 동시에 `/enter`를 호출할 수 있으므로, 실제 admission token 발급 시점의 Lua script가 최종 정확성 경계입니다. active 또는 token bucket 한도를 넘으면 `429 Too Many Requests`를 반환하며, 같은 queueToken으로 재시도해도 이미 발급된 token은 멱등하게 반환됩니다.


## Redis Key

join과 enter readiness hot path는 shard별 key를 사용합니다. key 이름에는 `{performanceId:shardId}` hash tag 형태를 유지하고, public state projection은 회차 단위 `{performanceId}` key를 사용합니다. 회차별·전역 active session 및 rate limit은 한 Lua script에서 원자적으로 갱신할 수 있도록 `{admission}` hash tag를 공유합니다.

`/join`은 shard-local counter, user marker, compact ticket, slot tail, pending slot, waiting marker만 갱신합니다. shard state와 public state는 scheduler가 갱신하므로 join hot path에서 public projection write를 하지 않습니다.

```text
q:{performanceId:shardId}:seq                   # shard-local localSeq counter
q:{performanceId:shardId}:state                 # scheduler가 갱신하는 shard serving/tail state
q:{performanceId:shardId}:user:{userIdHash}     # 사용자별 중복 join 방지 compact value
q:{performanceId:shardId}:queue:{queueId}       # queue ticket compact value
q:{admission}:entered:{performanceId}:{queueId}  # enter 멱등/완료 marker hash
q:{admission}:sessions                           # 전역 active session ZSET
q:{admission}:performance:{performanceId}:sessions # 회차별 active session ZSET
q:{admission}:rate                               # 전역 token bucket
q:{admission}:performance:{performanceId}:rate   # 회차별 token bucket
q:{performanceId:shardId}:slot-tail             # slot별 local tail hash
q:{performanceId:shardId}:pending-slots         # 아직 처리되지 않은 slot ZSET
q:{performanceId:shardId}:waiting-marker        # waiting set 재등록을 줄이는 shard marker
q:{performanceId}:state                         # public state projection hash
queue:waiting:performances                      # scheduler scan 대상 performanceId set
```

## Scheduler

스케줄러는 waiting set에서 사용자를 active set으로 옮기지 않습니다. 닫힌 slot을 찾아 shard별 `servingSeq`를 증가시킵니다. 같은 slot 안에서는 shard round-robin으로 전진합니다.

각 shard snapshot은 `q:{performanceId:shardId}:seq`를 읽어 tail을 계산합니다. 그래서 `/join`은 state hash를 매번 갱신하지 않아도 되고, public projection은 scheduler 주기에서만 만들어집니다.

```text
capacity = min(
  maxAdmitPerSecond,
  maxActiveSessions - totalActiveSessions,
  pendingInClosedSlots
)
```

계산 전에는 `q:{admission}:performance:{performanceId}:sessions`에서 만료된 session을 제거해 회차별 active 수를 계산합니다. 실제 입장 시에는 회차별 제한과 전역 제한을 모두 적용합니다.

advance 이후에는 Redis의 public state가 갱신됩니다. 사용자는 `/state` API를 호출하지만 Cloudflare가 이 응답을 짧게 캐시하므로 대부분의 polling 부하는 CDN에서 흡수합니다.

## 주요 설정

운영 secret 기본값은 없습니다. 로컬 실행 시에도 직접 지정해야 합니다.

| 설정 | 기본값 | 설명 |
| --- | --- | --- |
| `spring.data.redis.host` | `localhost` | Redis host |
| `spring.data.redis.port` | `6379` | Redis port |
| `app.queue.completion-enabled` | `false` | Core 완료 콜백 API 활성화 여부 |
| `app.queue.default-queue-ttl` | `24h` | queue token/번호표 TTL |
| `app.queue.shopping-session-ttl` | `15m` | admission token과 active session TTL |
| `app.queue.default-max-active-sessions` | `5000` | 회차별 동시 active session 한도 |
| `app.queue.global-max-active-sessions` | `5000` | 전역 동시 active session 한도 |
| `app.queue.default-max-admit-per-second` | `500` | 회차별 초당 입장 token refill 속도 |
| `app.queue.global-max-admit-per-second` | `500` | 전역 초당 입장 token refill 속도 |
| `app.queue.default-admission-burst-size` | `50` | 회차별 입장 burst 한도 |
| `app.queue.global-admission-burst-size` | `50` | 전역 입장 burst 한도 |
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
| `QUEUE_COMPLETION_ENABLED` | `false` | Core 완료 콜백 API 활성화 여부 |
| `QUEUE_COMPLETION_SECRET` | 없음 | 완료 콜백을 켤 때 필수인 인증 secret, 32자 이상 |
| `QUEUE_MAX_ACTIVE_SESSIONS_PER_PERFORMANCE` | `5000` | 회차별 active 상한 |
| `QUEUE_DEFAULT_QUEUE_TTL` | `24h` | API와 scheduler가 공유하는 queue 상태 TTL |
| `QUEUE_DEFAULT_REFRESH_AFTER_MS` | `5000` | API와 scheduler가 공유하는 state 재조회 권장 간격 |
| `QUEUE_SHARD_COUNT` | `128` | API와 scheduler가 공유하는 shard 수 |
| `QUEUE_SLOT_SIZE_MILLIS` | `50` | API와 scheduler가 공유하는 slot 크기 |
| `QUEUE_SLOT_CLOSE_GRACE_MILLIS` | `200` | API와 scheduler가 공유하는 slot 확정 grace |
| `QUEUE_MAX_ACTIVE_SESSIONS_GLOBAL` | `5000` | 전역 active 상한 |
| `QUEUE_MAX_ADMIT_PER_SECOND_PER_PERFORMANCE` | `500` | 회차별 실제 입장률 |
| `QUEUE_MAX_ADMIT_PER_SECOND_GLOBAL` | `500` | 전역 실제 입장률 |
| `QUEUE_ADMISSION_BURST_PER_PERFORMANCE` | `50` | 회차별 burst |
| `QUEUE_ADMISSION_BURST_GLOBAL` | `50` | 전역 burst |
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
$env:QUEUE_COMPLETION_ENABLED="true"
$env:QUEUE_COMPLETION_SECRET="local-queue-completion-secret-key-32bytes"

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

기존 버전의 회차별 session key(`q:{performanceId}:sessions`)는 새 `{admission}` key에서 집계되지 않습니다. 운영 전환 시에는 기존 shopping session TTL만큼 입장을 중단해 자연 만료시킨 뒤 새 버전을 활성화하거나, 점검 시간에 기존 session을 통제된 절차로 정리해야 합니다. 두 버전을 동시에 입장 가능 상태로 운영하면 active 수가 과소 집계될 수 있습니다.

기본 `500/s`, `5000명`은 측정 결과가 아닙니다. Core 실제 예매 흐름의 마지막 안정 구간을 찾은 뒤 70~80% 값을 회차별/전역 환경변수에 반영하고, burst는 100ms 단위 발급량 수준으로 작게 시작합니다.

이 구조는 100만 대기자 polling 부하를 Cloudflare 캐시로 흡수하고, 단일 인기 회차의 join hot key를 shard로 분산하기 위한 구조입니다. CDN cache miss는 Spring Boot와 Redis를 통과하므로, cache hit ratio와 origin request count를 별도로 확인해야 합니다. 정확한 전역 FIFO는 제공하지 않고 50ms slot 단위 공정성과 shard round-robin을 사용합니다.

권장 부하 테스트 순서:

1. Queue join 단독
2. CDN cached state API 단독
3. Queue enter 단독
4. Ticket Server capacity
5. Full flow
