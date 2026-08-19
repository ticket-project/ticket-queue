# 테스트 기준

이 문서는 무엇을 검증할지 고르는 기준과 새 테스트를 추가할 때의 관례를 정리한다. 모듈 경계는
[architecture.md](architecture.md), 구현 절차는 [development.md](development.md)를 함께 본다.

핵심 규칙 하나 — **Redis key, TTL, Lua script, 또는 API와 scheduler 사이의 상태 계약을 바꿨다면
단위 테스트만으로 확인했다고 보지 않는다.** 그 경우 통합 테스트가 필수다.

## 무엇을 돌릴지

| 상황 | 실행 |
| --- | --- |
| 계산, 응답 조립, token 검증 로직을 고쳤다 | `.\gradlew.bat :queue-api:test` 또는 `:queue-scheduler:test` |
| 전체 단위 테스트와 배포 구조 검증을 함께 본다 | `.\gradlew.bat test` |
| Redis key, TTL, Lua script를 고쳤다 | `.\gradlew.bat test integrationTest` (Docker 필요) |
| API와 scheduler 사이의 상태 계약을 고쳤다 | `.\gradlew.bat test integrationTest` |
| `build.gradle`, `settings.gradle`, 모듈 경계를 건드렸다 | `.\gradlew.bat test` (root 구조 테스트가 잡는다) |
| `deploy/` 설정이나 `.github/workflows/`를 고쳤다 | `.\gradlew.bat test` |
| 특정 테스트만 보고 싶다 | `.\gradlew.bat :queue-api:test --tests "com.ticket.queue.application.*"` |
| 배포 산출물까지 확인한다 | `.\gradlew.bat :queue-api:bootJar :queue-scheduler:bootJar` |
| push·PR 직전 | `.\gradlew.bat test integrationTest :queue-api:bootJar :queue-scheduler:bootJar` (CI와 같은 명령) |
| 문서만 바꿨다 | Java 빌드 대신 `rg -n "찾을_문구"` 와 `git diff --check` |

bash에서는 `./gradlew`를 사용한다.

## 통합 테스트

통합 테스트는 root project의 `src/integrationTest`에 있다. `queue-api`와 `queue-scheduler`의 main 출력을
함께 classpath에 올리므로 **두 모듈 사이의 Redis 상태 계약을 한 테스트에서 검증할 수 있다.**

```bash
./gradlew integrationTest
```

- Testcontainers를 사용하므로 **Docker가 실행 중이어야 한다.** Docker가 없으면 실패의 원인이 코드가 아니다.
- `check`가 `integrationTest`에 의존하므로 `check`를 부르면 Docker 없이 실패한다.
- Lua script를 바꿨다면 반드시 여기까지 돌린다. script의 반환 형태가 바뀌어도 단위 테스트는 통과할 수 있다.
- PR CI는 단위 테스트와 통합 테스트를 모두 통과한 jar만 배포 workflow에 넘긴다. 로컬에서 건너뛰면
  CI에서 같은 실패를 다시 만난다.

## 배포 구조 검증 테스트

root project의 `src/test/java/com/ticket/queue/deploy`에 있는 테스트는 코드가 아니라 **설정 파일의 형태**를
검증한다. 파일을 상대 경로로 읽으므로 Gradle이 정해 주는 작업 디렉터리에서만 통과한다. IDE에서 작업
디렉터리를 바꿔 단독 실행하면 설정이 옳아도 실패하므로, 실패는 Gradle로 다시 확인한 뒤 판단한다.

| 테스트 | 고정하는 것 |
| --- | --- |
| `ModuleArchitectureTest` | 세 모듈의 의존 관계, Spring Boot 플러그인 위치, `@EnableScheduling`의 소유자 |
| `NginxDeployConfigTest` | 배포 nginx 설정의 라우팅 형태 |
| `QueueCdnCacheArchitectureTest` | `/state`만 캐시하는 경계 |
| `ClaudeWorkflowConfigTest` | `claude.yml`과 `claude-code-review.yml`의 트리거와 조건 |

무엇을 막는지는 [architecture.md의 아키텍처 규칙](architecture.md#아키텍처-규칙)에 정리돼 있다.

## 새 테스트를 추가할 때

이 저장소의 관례는 아래와 같다. **형제 저장소 `../ticket`은 한국어 메서드 이름을 쓰지만 이 저장소는 다르다.**

- 테스트 메서드 이름은 **영어 snake_case**로 쓰고, 무엇이 참이어야 하는지를 문장처럼 적는다.

  ```java
  class AdmissionServiceTest {

      @Test
      void enter_rejects_when_sequence_is_not_admitted_yet() { }

      @Test
      void enter_is_idempotent_for_same_queue_id() { }

      @Test
      void public_state_does_not_include_user_specific_status() { }
  }
  ```

- `@DisplayName`과 `@Nested`는 사용하지 않는다. 저장소 전체에서 사용 사례가 없다.
- 시각에 의존하는 계산은 고정 `Clock`을 주입해 검증한다.
- `/enter`를 바꿨다면 성공 경로만 두지 않고 **아직 입장 차례가 아닌 경우, 재시도 멱등성,
  만료·위조 토큰**을 함께 고정한다.
- `/state`를 바꿨다면 사용자별 정보가 응답에 섞이지 않는다는 사실을 테스트로 고정한다.
- scheduler를 바꿨다면 전진 한도와 shard round-robin 경계를 고정한다.
- Redis에 실제로 붙어야 하는 검증은 root `src/integrationTest`에 둔다. 단위 테스트에 섞지 않는다.

## 결과를 보고할 때

- 통과·실패 수를 그대로 적는다. "대부분 통과" 같은 요약은 쓰지 않는다.
- **돌리지 않은 범위를 밝힌다.** 통합 테스트를 돌리지 않았다면 그 사실을 적는다.
  "단위 테스트 통과"와 "Redis 계약까지 확인"은 다른 말이다.
- 실패가 이번 변경 때문인지 기존 상태인지 구분한다. 애매하면 변경 전 커밋에서 같은 명령을 돌려 비교한다.
- Docker 미실행, Gradle 캐시, 작업 디렉터리처럼 코드와 무관한 원인으로 실패했다면 그 사실을 먼저 적는다.
  환경 문제를 코드 결함으로 보고하지 않는다.
- 검증에 실패한 상태로 커밋하지 않는다. 테스트를 건너뛰거나 `--no-verify`로 우회하지 않는다.
