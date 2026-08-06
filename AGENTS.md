# Ticket Queue AI 작업 규칙

이 파일은 AI 에이전트가 `ticket-queue` 저장소에서 반드시 지킬 최소 규칙입니다.

## 기본 원칙

- 모든 응답, 문서, 작업 로그는 한국어로 작성한다.
- 파일은 UTF-8, BOM 없이 유지한다.
- 기존 미커밋 변경은 사용자의 작업으로 보고 되돌리지 않는다.
- 요구 범위 밖의 기능 추가, 대규모 리팩터링, 새 추상화는 하지 않는다.
- 파괴적 작업, 대량 삭제, `git reset`, `git checkout --`은 명시 요청 없이 수행하지 않는다.
- 사용자가 커밋을 명시적으로 요청하지 않으면 커밋하지 않는다.

## 먼저 읽을 순서

1. `README.md`
2. `settings.gradle`
3. `build.gradle`
4. 관련 `src/main/java` 소스
5. 관련 `src/test/java` 테스트

## 프로젝트 경계

`ticket-queue`는 한 저장소 안의 Gradle 멀티모듈 프로젝트다. API와 scheduler는 별도 Spring Boot 애플리케이션·Docker 이미지로 실행하고, Redis 규약만 얇은 라이브러리로 공유한다.

- `queue-api`: join/state/enter HTTP API, 인증, queue/admission token, API용 Redis 명령
- `queue-scheduler`: 상시 scheduler, 입장 인원 계산, public state 갱신, scheduler용 Redis 명령
- `queue-redis`: Redis key, Lua script, Redisson 설정 같은 공통 인프라 규약
- root project: 배포 구조와 저장소 전체 규칙을 검증하는 테스트

`queue-api`는 `queue-scheduler`에 의존하지 않고, `queue-scheduler`도 `queue-api`에 의존하지 않는다. 두 실행 모듈은 `queue-redis`에만 의존한다. API 인증 secret을 scheduler 설정이나 컨테이너에 전달하지 않는다.

- `com.ticket.queue.api`: join/state/enter HTTP API와 응답 DTO
- `com.ticket.queue.application`: use case, public state 조회, scheduler, admission 응답 조립
- `com.ticket.queue.config`: app.queue와 admission token 설정
- `com.ticket.queue.domain`: queue model과 port
- `com.ticket.queue.infra`: Redis Lua/Redisson 저장소, 직접 획득하는 advance lock, signed admission token 발급 구현

Queue Server는 Ticket Server의 좌석 선택, hold, 주문, refresh token Redis에 접근하지 않는다.

## 고위험 영역

- scheduler의 shard별 serving sequence 전진과 `enter`의 admission token 발급은 별도 단계이므로 순서 검증과 멱등성을 함께 검증한다.
- queue ticket TTL(`defaultQueueTtl`)과 예매 가능 session TTL(`shoppingSessionTtl`)은 목적이 다르다.
- admission token secret, issuer, audience는 Ticket Server 설정과 일치해야 한다.
- `advanceBatchSize`는 scheduler의 회차별 1회 실행 한도다. `advanceIntervalMs`와 함께 실제 유입량을 결정하므로 둘을 함께 검증한다.
- Queue Server의 `state`는 공개 API라 access token/header를 요구하지 않고, `enter`는 `X-Queue-Token`만 검증한다.
- `/state` polling은 Cloudflare/nginx 캐시 경유를 전제로 하며, `join`/`enter`는 캐시하지 않는다.
- `enter` 성공 응답을 받은 뒤 클라이언트는 admission token을 들고 Ticket Server 예매 흐름으로 이동한다.
- 현재 배포는 단일 Redis다. Redis Cluster를 고려한 hash tag를 바꿀 때는 shard key와 회차 단위 public state·entered marker가 원자 연산에 필요한 slot을 함께 검토한다.

## 검증

작업 범위에 맞는 가장 좁은 명령부터 실행한다.

```powershell
.\gradlew.bat clean test :queue-redis:test :queue-api:test :queue-scheduler:test
.\gradlew.bat :queue-api:bootJar :queue-scheduler:bootJar
```

문서만 변경한 경우 Java 빌드 대신 아래를 우선한다.

```powershell
rg -n "include\\(|project\\(" README.md AGENTS.md settings.gradle build.gradle
```

## 보고

마무리 보고에는 변경 파일, 핵심 변경점, 검증 결과, 남은 리스크를 포함한다.

## 커밋 및 PR

- 커밋 메시지와 PR 제목은 Conventional Commits 기반 `<type>(<scope>): <한국어 설명>` 형식을 따른다.
- `scope`는 선택 사항이며 기존 도메인 또는 모듈 이름을 우선 사용한다.
- 허용 `type`은 `feat`, `fix`, `refactor`, `perf`, `test`, `docs`, `chore`, `build`, `ci`, `security`, `revert`다.
- 설명은 한국어로 작성하고 마침표를 붙이지 않는다. 기술 고유명사는 원문 표기를 허용한다.
- 하나의 커밋에는 하나의 목적만 포함하며 기존 사용자 변경과 섞지 않는다.
- 커밋 또는 PR을 만들기 전에 [커밋과 PR 컨벤션](docs/development.md#커밋과-pr-컨벤션)을 확인한다.
- 이미 원격에 올라간 커밋 이력을 변경하려면 먼저 사용자 승인을 받는다.
