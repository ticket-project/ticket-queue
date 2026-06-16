# Ticket Queue Server

Redis 기반 대기열 서버입니다. 예매 시작 시점의 순간 트래픽을 흡수하고, 입장 가능한 사용자에게만 admission token을 발급합니다.

## 핵심 구조

현재 public state 흐름은 정적 파일 서빙이 아니라 **Queue Server state API를 nginx/Cloudflare가 짧게 캐시하는 구조**입니다.

```text
1. Frontend -> Queue Server join
   queueId, seq, queueToken을 받습니다.

2. Admission Scheduler
   Redis public state의 admittedUntilSeq를 증가시킵니다.

3. Frontend -> Cloudflare/nginx cached state API
   GET /api/v1/queue/performances/{performanceId}/state
   모든 사용자가 같은 public state 응답을 조회합니다.

4. Frontend
   내 seq <= admittedUntilSeq이면 enter를 호출합니다.

5. Frontend -> Queue Server enter
   queueToken을 검증하고 admissionToken을 받습니다.

6. Frontend -> Ticket Server
   seat/order API에 X-Admission-Token을 붙여 호출합니다.
```

서버는 사용자마다 현재 순번을 계산하지 않습니다. 서버는 “현재 몇 번까지 입장 가능하다”는 공통 상태를 Redis에 유지하고, 클라이언트가 자기 번호와 비교합니다. 대규모 polling hot path는 Queue Server API 앞단의 CDN/nginx 캐시로 흡수합니다.

## API

| Method | Path | Header | 설명 |
| --- | --- | --- | --- |
| `POST` | `/api/v1/queue/performances/{performanceId}/join` | `X-Internal-Auth` | 번호표 발급 |
| `GET` | `/api/v1/queue/performances/{performanceId}/state` | 없음 | public queue state 조회 |
| `POST` | `/api/v1/queue/performances/{performanceId}/enter` | `X-Queue-Token` | 입장 가능 여부 확인 및 admission token 발급 |

`/status` 기반 `X-Queue-Session` polling API는 제공하지 않습니다.

### Join 응답

```json
{
  "performanceId": 1,
  "queueId": "queue-id",
  "seq": 152300,
  "status": "WAITING",
  "queueToken": "signed-queue-token"
}
```

### Public State 응답

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

운영 배포에서는 nginx가 아래 경로만 캐시합니다.

```text
GET /api/v1/queue/performances/{performanceId}/state
Cache-Control: public, max-age=1, s-maxage=1, stale-while-revalidate=5
```

`join`, `enter` 등 변경성 API는 캐시하지 않습니다.

### Enter 응답

```json
{
  "status": "ACTIVE",
  "admissionToken": "signed-admission-token",
  "expiresAtMillis": 1717000900000,
  "redirectUrl": "/booking/seat?performanceId=1"
}
```

## Redis Key

같은 회차의 key가 Redis Cluster에서 같은 slot에 위치하도록 `{performanceId}` hash tag를 사용합니다.

```text
q:{performanceId}:seq                 # 번호표 증가값
q:{performanceId}:state               # public state hash
q:{performanceId}:user:{userIdHash}    # 사용자별 중복 join 방지
q:{performanceId}:queue:{queueId}      # 번호표 정보 hash
q:{performanceId}:entered:{queueId}    # enter 멱등 처리 hash
q:{performanceId}:sessions             # active session ZSET, score=expiresAtMillis
q:{performanceId}:join-stream          # join 기록 stream
queue:waiting:performances             # scheduler scan 대상 performanceId set
```

## Scheduler

스케줄러는 waiting queue를 active set으로 직접 옮기지 않습니다. 아래 계산으로 Redis public state의 `admittedUntilSeq`를 증가시킵니다.

```text
increment = min(
  maxAdmitPerSecond,
  maxActiveSessions - activeSessions,
  tailSeq - admittedUntilSeq
)
```

계산 전에 `q:{performanceId}:sessions`에서 만료된 session을 먼저 제거합니다. 이후 `/state` API가 Redis의 public state를 읽고, nginx/Cloudflare가 이 응답을 짧게 캐시합니다.

## 주요 설정

운영 secret 기본값은 없습니다. 로컬 실행 시 직접 지정해야 합니다.

| 설정 | 기본값 | 설명 |
| --- | --- | --- |
| `spring.data.redis.host` | `localhost` | Redis host |
| `spring.data.redis.port` | `6379` | Redis port |
| `app.queue.default-queue-ttl` | `24h` | queue token/번호표 TTL |
| `app.queue.shopping-session-ttl` | `15m` | admission token과 active session TTL |
| `app.queue.default-max-active-sessions` | `5000` | 동시 active session 기본 한도 |
| `app.queue.default-max-admit-per-second` | `500` | 초당 입장 허용 기본값 |
| `app.queue.default-refresh-after-ms` | `5000` | 클라이언트 state 재조회 권장 간격 |
| `app.queue.scheduler-enabled` | `true` | scheduler 활성화 여부 |
| `app.queue.redirect.ticketing-url-template` | `/booking/seat?performanceId={performanceId}` | 입장 후 redirect URL |

| 환경변수 | 기본값 | 설명 |
| --- | --- | --- |
| `QUEUE_TOKEN_SECRET` | 없음 | queueToken 서명 secret, 32바이트 이상 |
| `ADMISSION_TOKEN_SECRET_KEY` | 없음 | Ticket Server와 공유하는 admission token secret, 32바이트 이상 |
| `INTERNAL_AUTH_SECRET_KEY` | 없음 | Gateway internal auth secret, 32바이트 이상 |

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

## Azure VM Deployment

`deploy/`와 `.github/workflows/deploy.yml`은 Azure VM에서 `ticket-queue`, Redis, nginx만 실행하는 Docker 이미지 기반 배포를 제공합니다.

```text
client or Cloudflare -> nginx cached /api/v1/queue/**/state -> ticket-queue -> Redis
```

실제 secret은 VM의 `/opt/ticket-queue/.env`에 둡니다. GitHub Actions는 Docker 이미지를 빌드/푸시하고, Compose 파일을 VM에 업로드한 뒤 `docker compose up -d --remove-orphans`를 실행합니다.

자세한 VM 설정과 GitHub Secrets는 [deploy/README.md](deploy/README.md)를 참고합니다.

## 운영 부하 검증

이 구조는 100만 대기자 polling 부하를 Queue Server/Redis에서 완전히 제거하지는 않습니다. 대신 `/state` 응답을 nginx/Cloudflare가 짧게 캐시해 origin 요청 수를 낮춥니다. 단일 회차 join 폭주는 여전히 같은 Redis hash slot으로 몰리므로 별도 부하 테스트가 필요합니다.

권장 부하 테스트 순서:

1. Queue join 단독
2. Cached public state API 단독
3. Queue enter 단독
4. Ticket Server capacity
5. Full flow
