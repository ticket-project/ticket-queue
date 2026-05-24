# Ticket Queue Server

기준일: 2026-05-24

티켓 예매 시작 시점의 순간 트래픽을 흡수하는 별도 Queue Server입니다. 로그인된 사용자를 공연 회차별 waiting/active 상태로 관리하고, 입장 가능한 사용자에게 Ticket Server 예매 API 진입용 admission token과 redirect URL을 발급합니다.

## 빠른 요약

- 구조: 단일 Spring Boot/Gradle 프로젝트
- 기본 포트: `8090`
- 저장소: Queue 전용 Redis
- 로컬 Redis: 단일 노드
- 운영 Redis: Queue 전용 Redis Cluster 권장
- Ticket Server Redis와는 분리합니다.
- Ticket Server와 공유해야 하는 값은 access token 검증용 JWT secret, admission token 발급/검증용 secret입니다.
- 대기열 적용 여부는 Ticket Server의 `booking-entry` API가 판단합니다. Queue Server는 자신에게 라우팅된 요청을 항상 대기열 대상으로 처리합니다.

## 시스템 역할

```text
Frontend
  -> Gateway /api/v1/queue/**
  -> Queue Server
  -> Queue Redis
  -> admission token + redirect URL 발급
  -> Frontend가 Queue Server polling 중지 후 redirect URL로 이동
  -> Ticket Server 예매 API 호출 시 Authorization + X-Admission-Token 전달
  -> Ticket Server가 admission token 검증
```

Queue Server는 좌석 선택, hold, 주문, refresh token을 처리하지 않습니다. 또한 회차가 대기열 대상인지 여부도 판단하지 않습니다. 해당 책임은 `ticket` 저장소의 Ticket Server에 남아 있습니다.

## 프로젝트 구조

```text
ticket-queue
├── src/main/java/com/ticket/queue
│   ├── controller      # enter/status/leave HTTP API
│   ├── service         # JWT 최초 검증, session 기반 status, admission 응답 조립
│   ├── scheduler       # waiting performance 주기적 active 승격
│   ├── domain          # use case, policy, model, port, lock annotation
│   ├── infra           # Redisson 저장소, Redis 설정, distributed lock AOP
│   └── config          # security, JWT/admission/redirect 설정
└── src/main/java/com/ticket/support/security
    ├── jwt             # access token 검증 유틸
    └── admission       # admission token 발급/검증 유틸
```

멀티모듈이 아니므로 모든 빌드/테스트/실행 명령은 루트 프로젝트에서 실행합니다.

## 주요 흐름

1. 클라이언트가 `POST /api/v1/queue/performances/{performanceId}/enter`를 호출합니다.
2. Queue Server가 `Authorization: Bearer ...` access token을 최초 1회 검증해 `memberId`를 얻습니다.
3. Redis waiting ZSET에 `memberId`를 등록하고 `queueSessionId`, `WAITING`, 현재 순번을 반환합니다.
4. 이후 polling은 access token 없이 `X-Queue-Session`으로만 수행합니다.
5. Queue Server 내부 scheduler가 주기적으로 waiting 앞쪽 사용자를 active set으로 승격합니다.
6. 클라이언트는 `pollAfterSeconds` 간격으로 status API를 호출합니다. 순번이 멀수록 polling 간격이 길고, 가까울수록 짧습니다.
7. `ACTIVE`가 되면 Queue Server가 admission token과 redirect URL을 반환합니다.
8. 클라이언트는 Queue Server 호출을 중지하고 redirect URL로 이동합니다.
9. Ticket Server 예매 API 진입 시 기존 access token과 admission token을 함께 전달합니다.
10. Ticket Server는 access token의 memberId와 admission token의 memberId/performanceId/scope/audience/issuer/signature를 검증합니다.

## API

| Method | Path | Header | 설명 |
| --- | --- | --- | --- |
| `POST` | `/api/v1/queue/performances/{performanceId}/enter` | `Authorization: Bearer <accessToken>` | 대기열 진입 |
| `GET` | `/api/v1/queue/performances/{performanceId}/status` | `X-Queue-Session: <queueSessionId>` | 대기열 상태 조회 |
| `POST` | `/api/v1/queue/performances/{performanceId}/leave` | `X-Queue-Session: <queueSessionId>` | 대기열 이탈 |

응답 상태는 `WAITING`, `ACTIVE`, `EXPIRED`를 중심으로 사용합니다.

`ACTIVE` 응답 예시:

```json
{
  "status": "ACTIVE",
  "queueSessionId": "...",
  "position": null,
  "estimatedWaitSeconds": 0,
  "pollAfterSeconds": null,
  "admissionToken": "...",
  "redirectUrl": "/booking/seat?performanceId=1"
}
```

## Redis 소유권

Queue Redis는 아래 런타임 상태만 소유합니다. 회차별 대기열 적용 여부 같은 정책 값은 저장하지 않습니다.

```text
queue:waiting:{performanceId}           # ZSET member=memberId, score=sequence
queue:active:{performanceId}            # TTL set member=memberId
queue:sequence:{performanceId}          # monotonically increasing sequence
queue:member:{performanceId}:{memberId} # WAITING 또는 ACTIVE 보조 상태
queue:session:{queueSessionId}          # performanceId:memberId
queue:waiting:performances              # scheduler scan 대상 performanceId set
```

Ticket Redis는 별도로 유지하며 seat selection, hold TTL, order lock, ticket-side cache 상태를 담당합니다.

## 로컬 실행

전제:

- JDK 25
- Redis 7
- Gradle wrapper 사용

Redis:

```powershell
docker run --name ticket-queue-redis -p 6379:6379 -d redis:7
```

Queue Server:

```powershell
$env:JWT_SECRET_KEY="same-secret-used-by-ticket-access-token"
$env:ADMISSION_TOKEN_SECRET_KEY="same-secret-used-by-ticket-server-admission-validation"

.\gradlew.bat bootRun
```

## 주요 환경 변수

| 변수 | 기본값 | 설명 |
| --- | --- | --- |
| `QUEUE_SERVER_PORT` | `8090` | Queue Server listen port |
| `QUEUE_REDIS_HOST` | `localhost` | 로컬 Redis host |
| `QUEUE_REDIS_PORT` | `6379` | 로컬 Redis port |
| `QUEUE_REDIS_CLUSTER_NODES` | empty | 운영 Redis Cluster node 목록, comma-separated |
| `JWT_ISSUER` | `ticket` | Ticket access token issuer |
| `JWT_SECRET_KEY` | local dev secret | Ticket access token 검증용 secret |
| `ADMISSION_TOKEN_ISSUER` | `ticket-queue` | admission token issuer |
| `ADMISSION_TOKEN_AUDIENCE` | `ticket-api` | admission token audience |
| `ADMISSION_TOKEN_SECRET_KEY` | local dev secret | Ticket Server와 공유하는 admission token secret |
| `ADMISSION_TOKEN_EXPIRATION_SECONDS` | `300` | admission token TTL |
| `QUEUE_DEFAULT_MAX_ACTIVE_USERS` | `300` | 공연 회차별 기본 active 허용 수 |
| `QUEUE_ACTIVE_TTL` | `5m` | active member TTL |
| `QUEUE_ADVANCE_INTERVAL_MS` | `1000` | scheduler 실행 간격 |
| `QUEUE_SESSION_TTL` | `1h` | queue session TTL |
| `QUEUE_TICKETING_URL_TEMPLATE` | `/booking/seat?performanceId={performanceId}` | active 시 redirect URL template |

운영 cluster mode에서는 `QUEUE_REDIS_CLUSTER_NODES`를 설정합니다.

```powershell
$env:QUEUE_REDIS_CLUSTER_NODES="redis-queue-1:6379,redis-queue-2:6379,redis-queue-3:6379"
```

## 검증 명령

```powershell
.\gradlew.bat test
.\gradlew.bat bootJar
```

## 주요 파일

| 파일 | 역할 |
| --- | --- |
| `src/main/java/com/ticket/queue/QueueApiApplication.java` | 실행 진입점과 scheduler 활성화 |
| `src/main/java/com/ticket/queue/controller/QueueAdmissionController.java` | enter/status/leave HTTP API |
| `src/main/java/com/ticket/queue/service/DefaultQueueAdmissionService.java` | 최초 JWT 검증, session 기반 status, admission token/redirect 발급 |
| `src/main/java/com/ticket/queue/scheduler/QueueAdvancementScheduler.java` | waiting performance를 주기적으로 승격 |
| `src/main/java/com/ticket/queue/domain/command/enter/EnterQueueUseCase.java` | memberId 기반 대기열 등록 |
| `src/main/java/com/ticket/queue/domain/command/QueueAdmissionAdvancer.java` | performance별 분산락 기반 active 승격 |
| `src/main/java/com/ticket/queue/domain/query/status/QueueStatusReader.java` | 현재 queue 상태 판정 |
| `src/main/java/com/ticket/queue/infra/queue/RedisQueueTicketStore.java` | Redis 기반 queue 상태 저장소 |

## 작업 메모

- Queue Server가 Ticket Server의 좌석/주문 Redis에 접근하면 안 됩니다.
- admission token secret은 Ticket Server와 반드시 일치해야 합니다.
- polling은 `queueSessionId` 기반입니다. 대기열 등록 이후 매 polling마다 access token을 검증하지 않습니다.
- active 이후에는 Queue Server를 다시 호출하지 않습니다.
- waiting/active 전환, TTL, 중복 진입, 순번 계산, active 정원은 항상 함께 검증합니다.