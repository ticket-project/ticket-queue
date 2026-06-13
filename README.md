# Ticket Queue Server

Redis 기반 대기열 서버입니다. 목표는 인기 회차 오픈 시 Ticket Server로 직접 트래픽이 몰리지 않도록, 사용자를 대기열에 세우고 입장 가능한 사용자에게만 admission token을 발급하는 것입니다.

## 핵심 구조

기존 사용자별 status polling 방식은 제거했습니다. 현재 흐름은 public state 방식입니다.

```text
1. Frontend -> Queue Server join
   번호표(queueId, seq, queueToken)를 받습니다.

2. Frontend -> Queue Server state
   모든 사용자가 같은 전광판 상태(admittedUntilSeq, tailSeq)를 조회합니다.

3. Frontend
   내 seq <= admittedUntilSeq 이면 enter를 호출합니다.

4. Frontend -> Queue Server enter
   queueToken 검증 후 admissionToken을 받습니다.

5. Frontend -> Ticket Server
   seat/order API에 X-Admission-Token을 붙여 호출합니다.
```

public state 방식에서는 서버가 사용자마다 현재 순번을 계산하지 않습니다. 서버는 “현재 몇 번까지 입장 가능하다”는 공통 상태만 관리하고, 클라이언트가 자기 번호와 비교합니다.

## API

| Method | Path | Header | 설명 |
| --- | --- | --- | --- |
| `POST` | `/api/v1/queue/performances/{performanceId}/join` | `X-Internal-Auth` | 번호표 발급 |
| `GET` | `/api/v1/queue/performances/{performanceId}/state` | 없음 | public queue state 조회 |
| `POST` | `/api/v1/queue/performances/{performanceId}/enter` | `X-Queue-Token` | 입장 가능 시 admission token 발급 |
| `PUT` | `/internal/v1/queue/performances/{performanceId}/policy` | `X-Internal-Auth` | 회차별 queue policy snapshot 저장 |

`/status`와 `X-Queue-Session` 기반 polling API는 제공하지 않습니다.

### join 응답

```json
{
  "performanceId": 1,
  "queueId": "queue-id",
  "seq": 152300,
  "status": "WAITING",
  "queueToken": "signed-queue-token"
}
```

### state 응답

```json
{
  "performanceId": 1,
  "status": "OPEN",
  "admittedUntilSeq": 150000,
  "tailSeq": 1000000,
  "refreshAfterMs": 5000,
  "serverTimeMillis": 1717000000000
}
```

### enter 응답

```json
{
  "status": "ACTIVE",
  "admissionToken": "signed-admission-token",
  "expiresAtMillis": 1717000900000,
  "redirectUrl": "/booking/seat?performanceId=1"
}
```

## Redis Key

같은 회차의 key는 Redis Cluster에서 같은 slot에 위치하도록 `{performanceId}` hash tag를 사용합니다.

```text
q:{performanceId}:seq                 # 번호표 증가값
q:{performanceId}:state               # public state hash
q:{performanceId}:user:{userIdHash}    # 사용자별 중복 join 방지
q:{performanceId}:queue:{queueId}      # 번호표 정보 hash
q:{performanceId}:entered:{queueId}    # enter 멱등 처리 hash
q:{performanceId}:sessions             # active session ZSET, score=expiresAtMillis
q:{performanceId}:join-stream          # join 기록 stream
queue:{performanceId}:policy           # 회차별 policy snapshot
queue:waiting:performances             # scheduler scan 대상 performanceId set
```

## Scheduler

스케줄러는 waiting set에서 사용자를 active set으로 옮기지 않습니다. 대신 다음 계산으로 `admittedUntilSeq`를 증가시킵니다.

```text
increment = min(
  maxAdmitPerSecond,
  maxActiveSessions - activeSessions,
  tailSeq - admittedUntilSeq
)
```

계산 전에는 `q:{performanceId}:sessions`에서 만료된 session을 먼저 제거합니다.

## 주요 환경변수

운영 secret 기본값은 없습니다. 로컬 실행 시에도 직접 지정해야 합니다.

| 환경변수 | 기본값 | 설명 |
| --- | --- | --- |
| `QUEUE_REDIS_HOST` | `localhost` | Redis host |
| `QUEUE_REDIS_PORT` | `6379` | Redis port |
| `QUEUE_REDIS_CLUSTER_NODES` | empty | Redis Cluster node 목록 |
| `QUEUE_DEFAULT_QUEUE_TTL` | `24h` | queue token/번호표 TTL |
| `QUEUE_SHOPPING_SESSION_TTL` | `15m` | admission token과 active session TTL |
| `QUEUE_DEFAULT_MAX_ACTIVE_SESSIONS` | `5000` | 동시 active session 기본 한도 |
| `QUEUE_DEFAULT_MAX_ADMIT_PER_SECOND` | `500` | 초당 입장 허용 기본값 |
| `QUEUE_DEFAULT_REFRESH_AFTER_MS` | `5000` | 클라이언트 state 재조회 권장 간격 |
| `QUEUE_SCHEDULER_ENABLED` | `true` | scheduler 활성화 여부 |
| `QUEUE_TOKEN_SECRET` | 없음 | queueToken 서명 secret, 32바이트 이상 |
| `ADMISSION_TOKEN_SECRET_KEY` | 없음 | Ticket Server와 공유하는 admission token secret, 32바이트 이상 |
| `INTERNAL_AUTH_SECRET_KEY` | 없음 | Gateway internal auth secret, 32바이트 이상 |
| `QUEUE_TICKETING_URL_TEMPLATE` | `/booking/seat?performanceId={performanceId}` | 입장 후 redirect URL |

로컬 실행 예시:

```powershell
$env:INTERNAL_AUTH_SECRET_KEY="local-internal-secret-key-32bytes"
$env:ADMISSION_TOKEN_SECRET_KEY="local-admission-secret-key-32bytes"
$env:QUEUE_TOKEN_SECRET="local-queue-token-secret-key-32bytes"
.\gradlew.bat bootRun
```

## 검증

```powershell
.\gradlew.bat test
.\gradlew.bat bootJar
```

## 남은 운영 검증

이 구조는 100만 대기자 polling 부하를 줄이기 위한 구조입니다. 단일 회차 join 폭주는 여전히 같은 Redis hash slot으로 몰리므로, 실제 처리 한계는 별도 부하 테스트로 확인해야 합니다.

권장 부하 테스트 순서:

1. Queue join 단독
2. Public state 단독
3. Queue enter 단독
4. Ticket Server capacity
5. Full flow
