# Ticket Queue 개발 기준

이 문서는 구현 절차와 위험 지점을 담는다. 모듈 경계와 코드 위치는 [architecture.md](architecture.md),
검증은 [testing.md](testing.md)를 따른다. 전체 흐름, API 계약, Redis key 목록, 설정값 기본값은
[README.md](../README.md)가 원본이며 이 문서에서 값을 외워 쓰지 않는다.

## 기술 기준

- JDK 25, Gradle wrapper, Spring Boot 4.0.7
- Redisson + Redis Lua script (`RedisScriptLoader`가 `resources/redis`에서 읽는다)
- JJWT는 `queue-api`에만 둔다

## 세 가지 요청 경로의 성격

| 경로 | 인증 | 캐시 | 성격 |
| --- | --- | --- | --- |
| `POST /join` | `Authorization: Bearer` access token | 캐시하지 않음 | 번호표 발급. 회차 오픈 순간의 hot path |
| `GET /state` | 없음(공개) | Cloudflare Edge TTL | 모든 대기자가 같은 응답을 본다. 대기 부하의 대부분 |
| `POST /enter` | `X-Queue-Token` | 캐시하지 않음 | admission token 발급. 원자성과 멱등성이 핵심 |

`/state`는 공개 API이므로 access token이나 사용자별 정보를 요구하거나 응답에 담지 않는다.
사용자별 상태를 `/state`에 넣는 변경은 CDN 캐시 전제를 깨뜨린다.

## 작업 시작 전

1. 바꿀 경로가 위 셋 중 무엇인지 정하고, 그 경로가 만지는 Redis key를 `README.md`에서 확인한다.
2. 관련 Lua script 원문을 읽는다. 원자성과 멱등성의 실제 판정은 Java 코드가 아니라 script에 있다.
3. 그 key를 다른 쪽(API 또는 scheduler)이 함께 읽고 쓰는지 확인한다.
4. 설정값을 추가·변경한다면 API와 scheduler가 **공유해야 하는 값인지** 한쪽 전용인지 먼저 정한다.
5. 같은 흐름을 검증하는 테스트가 어디에 있는지 확인한다. 없으면 그 공백을 작업 범위에 포함한다.

## 기능 개발 순서

1. **계약 확정** — endpoint, 헤더, 요청·응답 필드, 상태 코드, 오류를 먼저 정한다.
   기존 호환 필드(`seq`, `admittedUntilSeq`, `tailSeq`)를 지우려면 클라이언트와 부하 테스트 영향을 먼저 확인한다.
2. **port 정의** — `domain`의 `AdmissionStateStore` 또는 `QueueAdvancementStore` 계약을 먼저 넓힌다.
3. **Lua와 Redis 구현** — 원자적으로 처리해야 하는 단계를 script 하나로 묶는다.
   여러 명령을 Java에서 순서대로 부르는 형태로 나누지 않는다.
4. **application** — use case와 계산을 구현한다. 시각은 주입된 `Clock`을 쓰고 `System.currentTimeMillis()`를
   직접 부르지 않는다. 테스트가 시각을 고정할 수 없게 된다.
5. **api 또는 scheduler** — 요청 검증과 응답 조립, 또는 주기 실행 트리거만 담당한다.
6. **설정** — `app.queue` 아래에 두고 환경 변수 이름을 `README.md` 표에 함께 적는다.
7. **테스트와 검증** — [testing.md](testing.md)의 기준대로 실행한다.

## Lua script — 원자성과 멱등성의 최종 판정자

- **`/state`의 serving 값은 입장 자격일 뿐이고 순서의 확정이 아니다.** CDN 지연 때문에 여러 클라이언트가
  동시에 `/enter`를 호출할 수 있다. 발급 시점의 script가 순서와 멱등성을 최종 확인한다.
- **같은 queueToken으로 재시도하면 TTL이 남은 기존 admission token을 돌려준다.** 이 성질을 깨는 변경은
  네트워크 재시도만으로 중복 입장을 만든다.
- script를 수정하면 **되돌아오는 값의 형태와 개수**가 Java 쪽 파싱과 맞는지 함께 확인한다.
- `legacy_enter_queue.lua`는 이전 queue token 경로를 위한 호환 경로다. 새 기능의 선례로 삼지 않고,
  지우려면 이전 토큰의 TTL이 모두 지났는지 확인한다.
- script를 새로 추가하면 `RedisScriptLoader`가 읽는 경로와 이름 규칙을 따른다.

## join hot path 규칙

- `/join`은 shard-local counter, user marker, compact ticket, slot tail, pending slot, waiting marker만 갱신한다.
- **shard state와 public state는 scheduler가 갱신한다.** join에서 public projection을 쓰지 않는다.
  이 분리가 단일 인기 회차의 hot key 문제를 막는 핵심이다.
- 같은 사용자의 중복 join은 user marker로 막는다. 응답을 새로 발급하는 형태로 바꾸지 않는다.
- shard는 회원 기준으로 안정적으로 결정한다. 같은 회원이 재요청할 때 shard가 바뀌면 번호표가 무의미해진다.
- `waiting-marker`는 scheduler 등록을 줄이는 장치다. TTL이 짧다는 사실을 전제로 동작하므로
  이 값을 늘릴 때는 scheduler가 회차를 놓치지 않는지 확인한다.

## scheduler 전진 규칙

- scheduler는 waiting set에서 사용자를 옮기지 않는다. **닫힌 slot을 찾아 shard별 `servingSeq`를 올린다.**
  같은 slot 안에서는 shard round-robin으로 전진한다.
- `capacity = min(advanceBatchSize, pendingInClosedSlots)`다. `advanceBatchSize`는 회차별 운영 정책이 아니라
  scheduler 전체에 적용되는 기술 설정이며 **회차 하나의 1회 실행 한도**다.
- **`advanceBatchSize`와 `advanceIntervalMs`는 함께 실제 유입량을 결정한다.** 하나만 바꾸고 검증했다고
  판단하지 않는다. 두 값의 조합으로 초당 입장량을 계산해 보고한다.
- batch 한도는 **대기 중인 회차마다 각각** 적용된다. 동시에 여러 회차가 열리면 Core로 가는 합계는 더 커진다.
- scheduler는 별도 ECS service에서 기본 1 task로 운영한다. Redis 분산 락은 롤링 배포나 장애 교체 중
  일시적으로 task가 겹칠 때의 안전장치이며 상시 active-active 운영을 보장하지 않는다.
  락을 없애거나 범위를 넓히는 변경은 이 전제를 먼저 확인한다.
- 락 안에서 외부 I/O를 늘리지 않는다. 조회와 계산은 락 밖에서 하고 임계 구역은 짧게 유지한다.
- 전진과 admission token 발급은 **별도 단계**다. 순서 검증과 멱등성을 함께 확인한다.

## TTL 두 종류를 섞지 않는다

| 설정 | 대상 | 성격 |
| --- | --- | --- |
| `defaultQueueTtl` | queue token, 번호표, shard state, public state | 대기열 자체의 수명 |
| `shoppingSessionTtl` | admission token, 입장 멱등 marker | 입장 후 예매 가능 시간 |

목적이 다르므로 한쪽을 다른 쪽 기준으로 맞추지 않는다. `shoppingSessionTtl`을 늘리면 동시에 예매 흐름에
머무는 사용자 수가 늘어난다. Core는 주문 후 session 완료 요청을 보내지 않고 이 TTL로만 정리되므로,
이 값의 변경은 Core 유입량 변경과 같다.

## token과 secret

- `QUEUE_TOKEN_SECRET`, `ADMISSION_TOKEN_SECRET_KEY`, `JWT_SECRET`은 운영 기본값이 없다. 로컬에서도 직접 지정한다.
- 코드나 저장소에 secret 기본값을 넣지 않는다. 없으면 기동을 실패시키는 쪽이 옳다.
- **admission token의 secret, issuer, audience는 Ticket Server 설정과 일치해야 한다.** 한쪽만 바꾸면
  Queue는 정상 응답하는데 Core가 전부 거부하는 상태가 된다.
- API 인증 secret을 scheduler 설정이나 컨테이너에 전달하지 않는다.
- secret, 토큰 원문, 사용자 식별자를 로그나 오류 응답에 남기지 않는다. user marker는 hash 형태를 유지한다.

## Redis key와 hash tag

- key 형식과 TTL은 `queue-redis`가 소유한다. 문자열을 각 모듈에서 조립하지 않는다.
- shard 단위는 `{performanceId:shardId}`, 회차 단위는 `{performanceId}` hash tag를 유지한다.
- **hash tag를 바꾸면** 같은 script 안에서 원자적으로 다뤄야 하는 key들이 같은 slot에 남는지 확인한다.
  회차 단위 public state와 입장 멱등 marker가 특히 그렇다.
- `queue:waiting:performances`는 TTL이 없고 비면 제거되는 scan 대상 set이다. 이 성질을 바꾸면
  scheduler가 회차를 영구히 순회하거나 놓친다.
- 운영 Redis에서 전체 key를 훑는 명령을 쓰지 않는다.

## 완료로 판정하지 않는 조건

- [architecture.md의 아키텍처 규칙](architecture.md#아키텍처-규칙) 중 하나라도 어겼다.
- `queue-api`와 `queue-scheduler`가 서로를 의존하거나 `@EnableScheduling`이 API에 들어갔다.
- `/state` 응답에 사용자별 정보가 들어갔다.
- `/join`이 public state projection을 쓴다.
- 원자적으로 처리해야 하는 단계를 Lua 밖에서 여러 명령으로 나눴다.
- 같은 queueToken 재시도가 새 admission token을 발급한다.
- `advanceBatchSize`나 `advanceIntervalMs`를 바꿨는데 초당 입장량을 계산하지 않았다.
- TTL 두 종류를 같은 값으로 맞췄거나 목적을 섞었다.
- Core와 공유하는 token 계약을 이 저장소에서만 바꿨다.
- Redis·Lua·계약을 바꿨는데 통합 테스트를 돌리지 않았다.

## 커밋과 PR 절차

기본 흐름은 **작업 브랜치 → 작업 단위 커밋들 → PR → squash 또는 rebase 반영**이다.
기준 브랜치는 `master`(= `origin/HEAD`)이며 **`master`에 직접 커밋하지 않는다.**
이 저장소는 merge 커밋이 하나도 없는 선형 이력이므로 **merge commit으로 반영하지 않고**
`gh pr merge --squash` 또는 `--rebase`를 사용한다. 브랜치 최신화도 rebase로 한다.

커밋과 PR은 **사용자가 명시적으로 요청할 때만** 시작한다.

### master push는 곧 배포다

`.github/workflows/deploy.yml`이 `master` push에 붙어 있다. 반영하면 다음이 일어난다.

1. `ci.yml`이 `./gradlew test integrationTest :queue-api:bootJar :queue-scheduler:bootJar`를 실행한다.
2. 검증된 두 jar로 `ticket-queue-api:<SHA>`와 `ticket-queue-scheduler:<SHA>` 이미지를 빌드해 push한다.
3. EC2에 compose와 nginx·Datadog 설정을 올린 뒤 두 컨테이너를 새 이미지로 교체한다.
4. **smoke 검증** — 두 컨테이너의 health를 기다린 다음 실제 `/state` endpoint를 `curl`로 호출한다.
5. 중간에 실패하면 **자동 롤백** — 이전 이미지와 이전 설정으로 되돌리고 다시 health를 확인한다.

자동 롤백이 있지만 되돌아가는 대상은 직전 상태뿐이고 smoke는 `/state` 한 경로만 본다.
`/join`과 `/enter`의 회귀는 잡지 못한다. 반영 전에 운영에 나간다는 사실을 알린다.

두 실행 모듈은 같은 커밋에서 만든 이미지로 함께 배포된다. Redis 규약을 바꾸는 변경은 한 커밋에 담아
배포 사이의 불일치 구간을 만들지 않는다.

### PR을 열면 자동 리뷰가 돈다

`.github/workflows/claude-code-review.yml`이 PR `opened`, `ready_for_review`, `reopened`에서 실행된다
(draft는 제외). `claude.yml`은 본문이 `@claude`로 시작하는 코멘트에서 실행된다. 초안 상태로 올리려면
draft로 만든다. 두 workflow의 형태는 `ClaudeWorkflowConfigTest`가 고정하므로 트리거를 바꾸면
그 테스트를 함께 고친다.

### 순서

1. **준비물 확인** — `gh auth status`(PR 단계 전), `git --version` 2.38 이상, 그리고 통합 테스트가
   필요한 변경이면 Docker 실행 여부.
2. **현황 파악** — `git status`와 `git diff`로 워킹트리 전체를 본다. `.worktrees/`에 별도 작업본이 있으므로
   지금 어느 트리에 있는지 먼저 확인한다. 기존 미커밋 변경은 사용자의 작업으로 보고 되돌리지 않는다.
3. **범위별 검증** — [testing.md](testing.md#무엇을-돌릴지)의 기준을 따른다. Redis·Lua·계약 변경은
   `integrationTest`까지 통과해야 한다. 실패하면 커밋하지 않고 실패 내용을 그대로 보고한다.
4. **작업 단위로 쪼개기** — API, scheduler, Redis 규약의 독립적인 변경은 가능한 한 분리한다.
   다만 함께 배포되어야 정합성이 유지되는 변경(Lua script 형태와 그것을 읽는 Java 코드)은 나누지 않는다.
5. **명시적 스테이징** — 파일 경로를 지정해 `git add`한다. `build/`, `.gradle/`, `.env*`는 스테이징하지 않는다.
   **secret 값이 담긴 파일은 절대 커밋하지 않는다.**
6. **작업 브랜치** — `git fetch origin && git checkout -b <prefix>/<주제> origin/HEAD`.
   `<prefix>`는 변경 성격이나 작업 주체(`claude`, `codex`)를 쓴다. force push는 자기 작업 브랜치에 한해
   `--force-with-lease`로 한다.
7. **충돌 검증** — `git merge-tree --write-tree origin/HEAD HEAD`. exit 1이면 임의로 해결하지 않고,
   충돌 파일마다 내 변경과 `master` 변경을 한 줄 한국어로 정리해 선택을 받는다.
8. **PR 생성과 반영** — 본문은 `.github/pull_request_template.md`의 항목을 채우고 Queue API·scheduler·
   Redis key/TTL/Lua·admission token·배포 영향과 검증 결과를 기록한다. 반영은 `--squash` 또는 `--rebase`.
9. **뒷정리와 보고** — 커밋 해시, 변경 통계, 검증 결과(통합 테스트 실행 여부 포함), 충돌 검증 결과,
   PR URL과 반영 여부를 보고하고 남은 unstaged·untracked 파일을 알린다.

### 하지 않을 것

- 명시적 요청 없이 커밋 절차를 시작하기
- `master`에 직접 커밋하거나 push하기
- merge commit으로 PR 반영하기, 브랜치 최신화를 merge로 하기
- Redis·Lua·계약 변경을 통합 테스트 없이 커밋하기
- Lua script와 그것을 읽는 Java 코드를 다른 커밋으로 쪼개 배포 사이 불일치를 만들기
- secret 값이나 `.env` 파일을 스테이징하기
- `git add -A`로 뭉텅이 스테이징하기, 확인하지 않은 파일 커밋하기
- 영어 커밋 메시지, "Update files" 류의 무의미한 제목
- `--no-verify`, 훅과 서명 우회

멀티라인 커밋 메시지는 bash heredoc으로 작성한다. PowerShell에서 heredoc 문법을 흉내 내지 않는다.

```bash
git commit -m "$(cat <<'EOF'
perf(queue-scheduler): 입장 처리 락 범위 축소

Redis 조회와 계산을 락 밖에서 처리해 scheduler가
입장 순번을 전진시키는 임계 구역만 짧게 유지한다.

Co-Authored-By: <작업한 에이전트 표기>
EOF
)"
```

## 커밋과 PR 컨벤션

커밋 메시지와 PR 제목은 Conventional Commits를 기반으로 작성한다. 이 규칙은 사람과 AI 에이전트 모두에게 동일하게 적용한다.

### 기본 형식

```text
<type>(<scope>): <한국어 설명>
```

`scope`는 선택 사항이다.

```text
feat(queue-api): 대기열 참가 API 추가
fix(queue-scheduler): 입장 순번 전진 오류 수정
refactor(queue-redis): Redis 키 조립 책임 분리
perf(queue): 공개 상태 조회 부하 완화
test(queue): shard 입장 경계 테스트 추가
docs: 커밋 및 PR 컨벤션 문서화
```

### type

`type`은 변경 파일의 종류가 아니라 변경 목적을 기준으로 선택한다.

| type | 사용 기준 |
| --- | --- |
| `feat` | 새로운 기능 또는 외부 동작 추가 |
| `fix` | 잘못된 동작이나 결함 수정 |
| `refactor` | 기능 변경 없는 코드 구조 개선 |
| `perf` | 처리량, 응답 시간, Redis 명령, 락 등 성능 개선 |
| `test` | 테스트만 추가하거나 수정 |
| `docs` | 문서만 변경 |
| `chore` | 제품 동작과 무관한 유지보수 작업 |
| `build` | Gradle, 의존성 또는 빌드 설정 변경 |
| `ci` | CI 워크플로우 변경 |
| `security` | 인증, 권한 또는 토큰 보안 강화 |
| `revert` | 기존 변경 되돌리기 |

### scope

`scope`는 변경의 주된 책임 영역을 나타낸다. 가장 작은 적절한 범위를 선택한다.

1. 한 실행 모듈에 국한되면 `queue-api` 또는 `queue-scheduler`를 사용한다.
2. 공통 Redis 규약 변경이면 `queue-redis`를 사용한다.
3. 여러 Queue 모듈에 걸친 변경이면 `queue`를 사용한다.
4. 배포나 저장소 공통 작업은 `deploy`, `ci`, `review`, `codex` 등 기존 범위를 사용한다.
5. 특정 범위를 정하기 어려운 저장소 전체 작업은 scope를 생략한다.

권장 scope:

```text
queue, queue-api, queue-scheduler, queue-redis, admission, redis, deploy, ci, review, codex
```

기존 scope로 표현할 수 있으면 새로운 scope를 임의로 만들지 않는다. 새 scope가 필요하면 실제 모듈이나 안정적인 하위 시스템 이름을 사용한다.

### 설명과 본문

- 설명은 한국어로 작성하고 마침표를 붙이지 않는다.
- `Redis`, `Lua`, `Cloudflare`, `Nginx` 같은 기술 고유명사는 원문 표기를 허용한다.
- `수정`, `개선`, `작업`처럼 대상이 드러나지 않는 표현만 사용하지 않는다.
- `추가`, `수정`, `분리`, `축소`, `최적화`처럼 변경 결과가 드러나게 작성한다.
- 하나의 커밋에는 하나의 목적만 포함한다. API, scheduler, Redis 규약의 독립적인 변경은 가능한 한 분리한다.

코드만 보고 이유를 알기 어려운 변경은 빈 줄 다음에 한국어 본문을 추가한다.

```text
perf(queue-scheduler): 입장 처리 락 범위 축소

Redis 조회와 계산을 락 밖에서 처리해 scheduler가
입장 순번을 전진시키는 임계 구역만 짧게 유지한다.
```

호환성을 깨는 변경은 type 또는 scope 뒤에 `!`를 붙이고 본문 하단에 `BREAKING CHANGE:`를 작성한다.

### PR

- PR 제목도 `<type>(<scope>): <한국어 설명>` 형식을 사용한다.
- type과 scope는 PR 전체의 주된 목적과 영향 범위를 기준으로 선택한다.
- 테스트와 문서가 함께 포함돼도 주된 목적이 성능 개선이면 `perf`를 사용한다.
- 서로 관계없는 기능, 버그 수정, 리팩터링이 섞이면 PR을 분리한다.
- PR 본문에는 Queue API, scheduler, Redis 키·TTL·Lua, admission token, 배포 영향과 검증 결과를 기록한다.

### AI 에이전트 작업 규칙

- 기존 히스토리를 추측만으로 모방하지 말고 이 문서의 type, scope, 언어 규칙을 우선 적용한다.
- type은 변경 목적, scope는 주된 책임 영역을 기준으로 선택한다.
- 여러 커밋을 만들 때는 작업 책임별로 분리하고 각각의 메시지를 독립적으로 작성한다.
- 사용자가 커밋을 명시적으로 요청하지 않으면 커밋하지 않는다.
- 이미 푸시한 커밋 메시지를 변경하려면 영향과 이력 변경을 설명하고 사용자 승인 후 진행한다.
- force push가 필요하면 원격이 예상한 상태일 때만 갱신하는 `--force-with-lease`를 사용한다.

## 검증 명령

무엇을 언제 돌릴지, 통합 테스트가 필수인 조건, 결과 보고 방식은 [testing.md](testing.md)를 따른다.
가장 좁은 검증부터 실행하되 Redis key·TTL·Lua 또는 API와 scheduler 사이의 상태 계약을 바꿨다면
Docker가 실행 중인 환경에서 `integrationTest`까지 돌린다.

```powershell
.\gradlew.bat test
.\gradlew.bat integrationTest
```

PR CI는 단위 테스트와 Redis 통합 테스트를 모두 통과한 jar만 배포 workflow에 전달한다.
