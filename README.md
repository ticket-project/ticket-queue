# Ticket Queue

인기 회차의 예매 오픈 때 사용자를 대기열에 세우고, 차례가 된 사용자에게만 admission token을 발급하는 서버다.
[ticket-core](https://github.com/ticket-project/ticket-core)는 이 token으로 예매 API 진입을 제어한다. Core가 버티는
입장률은 [gatling-test](https://github.com/ticket-project/gatling-test)로 잰다.

```text
사용자 ─join·enter─▶ ticket-queue ─admission token─▶ ticket-core (좌석·주문)
   └──── state ────▶ Cloudflare 캐시 ─▶ ticket-queue
```

## 다루는 문제

- **대기자의 상태 조회를 서버 대신 CDN이 받는다.** 사용자별 순번 조회 대신 모든 대기자가 같은 public state를 보고,
  이 응답을 Cloudflare가 캐시한다. 서버는 shard마다 "몇 번까지 입장 가능한가"만 갱신한다. 대기자 100만 명을 가정한
  구조이고, 캐시를 놓친 요청은 서버까지 오므로 운영에서 cache hit 비율을 따로 본다([흐름](docs/api.md#흐름)).
- **오픈 순간 한 회차에 몰리는 join을 Redis key 하나에 모으지 않는다.** 번호표를 여러 shard로 나눠 발급한다. 그 대가로
  정확한 전역 선착순 대신 짧은 시간 slot 단위의 공정성과 shard round-robin을 쓴다.
- **입장 token은 번호표 하나에 한 번만 발급한다.** CDN 지연 때문에 여러 클라이언트가 동시에 `enter`를 불러도 Redis Lua
  script가 순서와 멱등성을 판정하고, 같은 번호표의 재시도에는 같은 token을 돌려준다.
- **Core가 감당하는 속도로만 들여보낸다.** scheduler가 주기마다 회차당 정해진 인원만큼 입장 차례를 전진시킨다. 이 값을
  정하는 절차는 ticket-core [Core 수용량](https://github.com/ticket-project/ticket-core/blob/master/docs/core-capacity.md)에 있다.

## 빠른 시작

필요한 것: JDK 25, Docker. 아래는 Windows PowerShell 기준이다(macOS·Linux는 `.\gradlew.bat` 대신 `./gradlew`).

1. Redis를 띄운다.

   ```powershell
   docker compose -f docker-compose.local.yml up -d redis
   ```

2. 터미널 하나에서 secret을 넣고 API를 띄운다. 로컬은 32바이트 이상 아무 값이면 된다(이유는 [설정](#설정)).

   ```powershell
   $env:JWT_SECRET="local-access-token-secret-key-32bytes"
   $env:ADMISSION_TOKEN_SECRET_KEY="local-admission-secret-key-32bytes"
   $env:QUEUE_TOKEN_SECRET="local-queue-token-secret-key-32bytes"
   .\gradlew.bat :queue-api:bootRun
   ```

3. 다른 터미널에서 scheduler를 띄운다. scheduler에는 secret을 넣지 않는다.

   ```powershell
   .\gradlew.bat :queue-scheduler:bootRun
   ```

4. 세 번째 터미널에서 확인한다. 둘 다 `"status":"UP"`이고 마지막 줄이 회차 1의 public state JSON이면 성공이다(대기자가
   없으면 `"status":"EMPTY"`).

   ```powershell
   curl.exe -s http://localhost:8090/actuator/health
   curl.exe -s http://localhost:8091/actuator/health
   curl.exe -s http://localhost:8090/api/v1/queue/performances/1/state
   ```

`join`은 Core가 발급한 access token이 필요하다. 로컬 Core와 함께 확인하려면 두 서버의 `JWT_SECRET`을 같은 값으로 맞춘다.

## 설정

설정 기본값의 원본은 `queue-api`·`queue-scheduler`의 `application.yml`이다. 기본값이 없는 secret 세 개는 API에만 넣는다.

| 변수 | 왜 필요한가 | 운영 값 |
| --- | --- | --- |
| `JWT_SECRET` | `join` 요청의 Core access token을 검증한다. Core와 같은 값이어야 한다 | EC2의 `.env` |
| `ADMISSION_TOKEN_SECRET_KEY` | 발급한 admission token을 Core가 검증한다. Core와 같은 값이어야 한다 | EC2의 `.env` |
| `QUEUE_TOKEN_SECRET` | 번호표(queueToken)에 서명한다. Queue만 쓴다 | EC2의 `.env` |

- secret은 저장소에 없다. EC2 `.env` 준비는 [배포 안내](deploy/README.md)에 있다.
- 6379를 이미 다른 컨테이너가 쓰고 있으면 Redis를 다른 포트로 띄우고, 두 프로세스에 같은
  `SPRING_DATA_REDIS_PORT`를 넣는다.
- shard 수·slot 크기·입장 batch 같은 나머지 설정과 뜻은 [설정 목록](docs/development.md#설정)에 있다. API와 scheduler가
  같은 값을 써야 하는 항목이 있다.

## 구조

한 저장소 안의 Gradle 멀티모듈이다. API와 scheduler는 서로 다른 Spring Boot 애플리케이션·Docker 이미지로 배포하고,
Redis 규약만 얇은 라이브러리로 공유한다. API를 여러 대로 늘려도 scheduler는 1대로 둔다.

```text
queue-api        join·state·enter HTTP API, 인증, queue·admission token 발급        (포트 8090)
queue-scheduler  주기마다 입장 차례 전진, public state 갱신                          (포트 8091)
queue-redis      Redis key, Lua script, Redisson 설정만 공유하는 라이브러리
src/             배포 구조 검증 테스트, Redis 통합 테스트
```

```text
client -> nginx -> queue-api -----------+
                                         +-> Redis
                     queue-scheduler ----+
```

| 알고 싶은 것 | 문서 |
| --- | --- |
| 요청 흐름, endpoint, 응답 형식 | [API 계약](docs/api.md) |
| 모듈 경계, 코드 위치, Redis key 목록 | [아키텍처](docs/architecture.md) |
| 구현 규칙, TTL, secret, 설정 목록, 커밋 절차 | [개발 기준](docs/development.md) |
| 무엇을 언제 테스트하는가 | [테스트 기준](docs/testing.md) |
| `/state` 캐시 설정 | [Cloudflare 캐시](docs/cloudflare-state-api-cache.md) |
| EC2 배포 | [배포 안내](deploy/README.md) |

## 자주 하는 작업

```powershell
.\gradlew.bat test                                          # 단위 테스트와 배포 구조 검증
.\gradlew.bat test integrationTest                          # Redis key·TTL·Lua·상태 계약을 바꿨을 때. Docker 필요
.\gradlew.bat :queue-api:bootJar :queue-scheduler:bootJar   # 배포 산출물
```

`master`에 push하면 [deploy.yml](.github/workflows/deploy.yml)이 두 이미지를 같은 commit SHA로 만들어 EC2에 배포한다.
Markdown·`docs/`만 바꾼 push는 배포하지 않는다. 그래서 `master`에 직접 커밋하지 않고 작업 브랜치와 PR을 거친다.

## 모르고 바꾸면 사고가 나는 것

- **`/state` 응답에 사용자별 정보를 넣지 않는다.** CDN이 모든 대기자에게 같은 응답을 준다.
- **`/join`에서 public state를 쓰지 않는다.** 오픈 순간 모든 join이 key 하나에 몰린다. public state는 scheduler만 갱신한다.
- **admission token의 secret·issuer·audience를 한쪽만 바꾸지 않는다.** Queue는 정상 응답하는데 Core가 전부 거부한다.
- **`advanceBatchSize`와 `advanceIntervalMs`를 따로 바꾸지 않는다.** 둘이 함께 Core로 가는 초당 인원을 정한다.
- **`queue-api`와 `queue-scheduler`는 서로를 의존하지 않는다.** `@EnableScheduling`은 scheduler에만 둔다. root 테스트가 막는다.

## 기여

브랜치·커밋·PR 절차는 [개발 기준](docs/development.md#커밋과-pr-절차)에 있다. 커밋 제목은
`<type>(<scope>): <한국어 설명>`이고, PR을 열면 리뷰 봇이 자동으로 돈다.
미해결 결함과 제안은 [Issues](https://github.com/ticket-project/ticket-queue/issues)에서 추적한다.

AI 코딩 에이전트용 지침은 [AGENTS.md](AGENTS.md)다. `CLAUDE.md`는 이 파일을 불러오기만 한다.
