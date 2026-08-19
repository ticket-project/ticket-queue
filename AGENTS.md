# Ticket Queue AI 작업 규칙

이 파일은 `ticket-queue` 저장소에서 지킬 최소 규칙이며 **모든 도구의 단일 진입점**이다.
Codex와 Copilot은 이 파일을 직접 읽고, Claude Code는 루트 `CLAUDE.md`의 `@AGENTS.md` 임포트로 읽는다.
도구별로 다른 지침을 따로 두지 않는다. 규칙을 바꿀 때는 이 파일과 `docs/`만 고친다.

## 기본 원칙

- 모든 응답, 문서, 작업 로그는 한국어로 작성한다.
- 파일은 UTF-8, BOM 없이 유지한다.
- 기존 미커밋 변경은 사용자의 작업으로 보고 되돌리지 않는다.
- 요구 범위 밖의 기능 추가, 대규모 리팩터링, 새 추상화는 하지 않는다.
- 파괴적 작업, 대량 삭제, `git reset`, `git checkout --`은 명시 요청 없이 수행하지 않는다.
- 사용자가 커밋을 명시적으로 요청하지 않으면 커밋하지 않는다.

## 작업별로 먼저 읽을 문서

이 파일에는 공통 규칙만 둔다. 상세한 판단 기준은 아래 문서가 원본이며, **작업을 시작하기 전에 해당 문서를 연다.**

| 작업 성격 | 먼저 읽을 문서 |
| --- | --- |
| 모듈 경계, 코드 위치, 의존 방향, Redis key 소유권 | `docs/architecture.md` |
| join·state·enter 구현, Lua, scheduler 전진, TTL, secret | `docs/development.md` |
| 무엇을 검증할지 고르기, 새 테스트 추가 | `docs/testing.md` |
| `/state` 캐시 설정 | `docs/cloudflare-state-api-cache.md` |
| 커밋·브랜치·PR | `docs/development.md` 의 커밋과 PR 절차·컨벤션 |
| 배포 구성과 EC2 설정 | `deploy/README.md` |

전체 흐름과 API 계약, Redis key 목록, 설정값 기본값은 `README.md`가 원본이다. 실제 모듈 경계는
`settings.gradle`과 각 모듈 `build.gradle`, 강제되는 규칙은 root `src/test`의 테스트가 최종 기준이다.

소스는 단일 모듈이 아니다. `queue-api/src`, `queue-scheduler/src`, `queue-redis/src`에 나뉘어 있고
루트 `src`에는 배포 검증 테스트와 Redis 통합 테스트만 있다.

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
git diff --check
```

**Redis key·TTL·Lua 또는 API와 scheduler 사이의 상태 계약을 바꿨다면 `integrationTest`가 필수다**
(Docker 필요). 선택 기준과 테스트 이름 규칙은 `docs/testing.md`를 따른다.

## 코드 리뷰

리뷰 요청을 받았을 때는 아래 기준을 적용한다. 사람과 리뷰 봇 모두 같은 기준을 따른다.

- 리뷰, 요약, 코멘트, 제안은 항상 한국어로 작성한다.
- 패치만 보지 말고 주변 코드, 호출 흐름, 관련 설정, 배포 구성, 관련 테스트까지 함께 읽는다.
- 취향성 스타일 지적보다 실제 결함 가능성, 회귀 위험, 테스트 공백을 우선한다.
- 모듈 경계 위반(`queue-api` ↔ `queue-scheduler` 상호 의존, `@EnableScheduling` 위치)을 먼저 확인한다.
- Redis key naming과 hash tag, TTL, Lua script 로딩과 반환 형태, 멱등성을 점검한다.
- 공개 `/state`와 인증이 필요한 `/join`·`/enter`의 경계가 깨지지 않았는지 본다.
- findings first 원칙을 따르고 심각도 높은 순서로 적는다. 각 이슈는 왜 문제인지, 어떤 조건에서
  깨지는지, 어디를 봐야 하는지를 짧게 적는다.
- 근거가 약한 코멘트는 남기지 않는다. 치명적 문제가 없으면 그 사실을 명시하고 남은 검증 공백만 덧붙인다.

## 보고

마무리 보고에는 변경 파일, 핵심 변경점, 검증 결과, 남은 리스크를 포함한다.

## 커밋 및 PR

- 커밋 메시지와 PR 제목은 Conventional Commits 기반 `<type>(<scope>): <한국어 설명>` 형식을 따른다.
- `scope`는 선택 사항이며 기존 도메인 또는 모듈 이름을 우선 사용한다.
- 허용 `type`은 `feat`, `fix`, `refactor`, `perf`, `test`, `docs`, `chore`, `build`, `ci`, `security`, `revert`다.
- 설명은 한국어로 작성하고 마침표를 붙이지 않는다. 기술 고유명사는 원문 표기를 허용한다.
- 하나의 커밋에는 하나의 목적만 포함하며 기존 사용자 변경과 섞지 않는다.
- 작업은 작업 브랜치에서 하고 `master`에 직접 커밋하거나 push하지 않는다. `master` push는 곧 운영 배포다.
- PR 반영은 `gh pr merge --squash` 또는 `--rebase`를 쓴다. 선형 이력을 유지하므로 merge commit을 만들지 않는다.
- 커밋 전 변경 범위에 맞는 검증을 실행하고 결과를 확인한다.
- 이미 원격에 올라간 커밋 이력을 변경하려면 먼저 사용자 승인을 받는다.
- 절차 전체는 [커밋과 PR 절차](docs/development.md#커밋과-pr-절차), 메시지 규칙 상세는
  [커밋과 PR 컨벤션](docs/development.md#커밋과-pr-컨벤션)을 따른다.
