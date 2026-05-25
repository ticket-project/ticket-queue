# Ticket Queue Server

기준일: 2026-05-25

티켓 예매 시작 시점의 순간 트래픽을 흡수하는 별도 Queue Server입니다. Queue Server는 DB를 조회하지 않고, 로그인/JWT/admission token을 검증하지 않으며, Redis 기반 대기열 상태와 admission token 발급만 관리합니다.

## 빠른 요약

- 구조: 단일 Spring Boot/Gradle 프로젝트
- 기본 포트: `8090`
- 저장소: Queue 전용 Redis
- 로컬 Redis: 단일 노드
- 운영 Redis: Queue 전용 Redis Cluster 권장
- Ticket Server Redis와 분리합니다.
- 대기열 적용 여부는 Ticket Server의 공연/회차 상세 응답이 판단합니다.
- Queue Server는 요청이 들어온 회차를 그대로 waiting/active 상태로 관리합니다.

## 시스템 역할

```text
Frontend
  -> Ticket Server 공연 상세 조회
  -> 회차별 entryType(DIRECT/QUEUE) 확인
  -> 예매하기 클릭 시 QUEUE면 Queue Page, DIRECT면 Seat Page 이동
  -> Queue Page가 Queue Server enter/status polling
  -> Queue Server scheduler가 주기마다 waiting 앞쪽 사용자를 active로 승격
  -> ACTIVE 응답의 admissionToken을 저장하고 redirectUrl로 Seat Page 이동
```

Queue Server는 좌석 선택, hold, 주문, refresh token, 공연/회차 정책 조회를 처리하지 않습니다.

## 프로젝트 구조

```text
ticket-queue
└── src/main/java/com/ticket/queue
    ├── api             # enter/status HTTP API와 응답 DTO
    ├── application     # 유스케이스, 조회, 정책 해석, scheduler, 응답 조립
    ├── config          # app.queue 설정 바인딩
    ├── domain          # queue model과 port
    └── infra           # Redis/Redisson, distributed lock, UUID, lock helper 구현
```

멀티모듈이 아니므로 모든 빌드/테스트 명령은 `ticket-queue` 루트에서 실행합니다.

## 주요 흐름

1. 클라이언트가 Ticket Server 상세 응답에서 선택 회차의 `entryType`을 확인합니다.
2. `entryType=QUEUE`이면 `/booking/queue?showId={showId}&performanceId={performanceId}`로 이동합니다.
3. Queue Page가 `POST /api/v1/queue/performances/{performanceId}/enter`를 호출합니다.
4. Queue Server는 `queueSessionId`, `WAITING`만 반환합니다.
5. 이후 polling은 `X-Queue-Session`만 사용하며, 순번/예상 대기 시간/다음 polling 간격은 status API에서 계산합니다.
6. scheduler가 `advance-interval-ms`마다 waiting 앞쪽 사용자를 최대 `admit-limit-per-tick`명 active set으로 승격합니다.
7. 클라이언트는 `pollAfterSeconds` 간격으로 status API를 호출합니다. 순번이 멀수록 느리게, 가까울수록 빠르게 조회합니다.
8. `ACTIVE`가 되면 Queue Server가 admission token과 redirect URL을 반환합니다.
9. 클라이언트는 admission token을 저장한 뒤 Queue Server 호출을 중지하고 redirect URL로 이동합니다.

## API

| Method | Path | Header | 설명 |
| --- | --- | --- | --- |
| `POST` | `/api/v1/queue/performances/{performanceId}/enter` | 없음 | 대기열 진입 |
| `GET` | `/api/v1/queue/performances/{performanceId}/status` | `X-Queue-Session: <queueSessionId>` | 대기열 상태 조회 |

응답 상태는 `WAITING`, `ACTIVE`, `EXPIRED`를 중심으로 사용합니다.

`enter` 응답은 등록만 확인합니다. 순번과 polling 정보는 `status` 응답에서 내려줍니다.

`ACTIVE` 응답 예시:

```json
{
  "status": "ACTIVE",
  "queueSessionId": "...",
  "position": null,
  "estimatedWaitSeconds": 0,
  "pollAfterSeconds": null,
  "admissionToken": "signed-token",
  "redirectUrl": "/booking/seat?performanceId=1"
}
```

## Redis 소유권

Queue Redis는 아래 형태의 대기열 런타임 상태만 소유합니다. 회차별 대기열 적용 여부, 좌석, 주문 정책은 저장하지 않습니다.

```text
queue:{performanceId}:waiting                 # ZSET member=queueSessionId, score=sequence
queue:{performanceId}:active                  # ZSET member=queueSessionId, score=active 만료 epoch millis
queue:{performanceId}:sequence                # monotonically increasing sequence
queue:{performanceId}:member:{queueSessionId} # WAITING 또는 ACTIVE 보조 상태, ACTIVE TTL 적용
queue:session:{queueSessionId}                # performanceId
queue:waiting:performances                    # scheduler scan 대상 performanceId set
```

같은 회차의 waiting, active, member state key는 Redis Cluster에서도 Lua 승격 처리가 가능하도록 `{performanceId}` hash tag를 공유합니다.

Ticket Redis는 별도로 존재하며 seat selection, hold TTL, order lock, ticket-side cache 상태를 담당합니다.

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
.\gradlew.bat bootRun
```

## 주요 환경 변수

| 변수 | 기본값 | 설명 |
| --- | --- | --- |
| `QUEUE_SERVER_PORT` | `8090` | Queue Server listen port |
| `QUEUE_REDIS_HOST` | `localhost` | 로컬 Redis host |
| `QUEUE_REDIS_PORT` | `6379` | 로컬 Redis port |
| `QUEUE_REDIS_CLUSTER_NODES` | empty | 운영 Redis Cluster node 목록, comma-separated |
| `QUEUE_ADMIT_LIMIT_PER_TICK` | `50` | scheduler tick당 active 승격 수 |
| `QUEUE_ACTIVE_TTL` | `5m` | active member TTL이자 Ticket Server 예매 API 사용 가능 TTL |
| `QUEUE_ADVANCE_INTERVAL_MS` | `1000` | scheduler 실행 간격 |
| `QUEUE_SESSION_TTL` | `2h` | queue session TTL |
| `QUEUE_TICKETING_URL_TEMPLATE` | `/booking/seat?performanceId={performanceId}` | active redirect URL template |
| `ADMISSION_TOKEN_ISSUER` | `ticket-queue` | admission token issuer |
| `ADMISSION_TOKEN_AUDIENCE` | `ticket-api` | admission token audience |
| `ADMISSION_TOKEN_SECRET_KEY` | `0123456789abcdef0123456789abcdef` | Ticket Server와 공유하는 admission token 서명 키 |

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
| `src/main/java/com/ticket/queue/api/QueueAdmissionController.java` | enter/status HTTP API |
| `src/main/java/com/ticket/queue/application/DefaultQueueAdmissionService.java` | queue session 기반 응답 조립 |
| `src/main/java/com/ticket/queue/application/QueueAdvancementScheduler.java` | waiting performance 주기적 승격 |
| `src/main/java/com/ticket/queue/application/EnterQueueUseCase.java` | queueSessionId 기반 대기열 등록 |
| `src/main/java/com/ticket/queue/application/QueueAdmissionAdvancer.java` | tick당 승격 수 기반 active 승격 |
| `src/main/java/com/ticket/queue/application/QueueStatusReader.java` | 현재 queue 상태 판정 |
| `src/main/java/com/ticket/queue/infra/RedisQueueTicketStore.java` | Redis 기반 queue 상태 저장소 |

## 작업 메모

- Queue Server는 Ticket Server의 좌석/주문 Redis에 접근하지 않습니다.
- Queue Server는 DB를 조회하지 않습니다.
- Queue Server는 로그인/JWT/admission token을 검증하지 않습니다.
- Queue Server는 active 상태의 남은 TTL만큼 유효한 admission token을 발급합니다.
- waiting에서 active로 승격하는 처리는 Redis Lua script로 waiting 제거, active ZSET 저장, member state 갱신을 한 번에 수행합니다.
- polling은 `queueSessionId` 기반입니다.
- enter API는 등록만 수행하고 순번/예상시간/폴링 간격 계산은 status API에서 수행합니다.
- active 이후에는 Queue Server를 다시 호출하지 않습니다.
- 명시적 leave API는 제공하지 않고 TTL과 scheduler의 stale waiting 정리로 처리합니다.
- waiting/active 전환, TTL, 중복 진입, 순번 계산, tick당 승격 수는 대상 테스트로 검증합니다.
