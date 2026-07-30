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

`ticket-queue`는 단일 Spring Boot/Gradle 프로젝트다. 이전 멀티모듈 구조를 사용하지 않는다.

패키지 경계는 다음과 같이 유지한다.

- `com.ticket.queue.api`: join/state/enter 및 내부 session 완료 HTTP API와 응답 DTO
- `com.ticket.queue.application`: use case, public state 조회, scheduler, admission 응답 조립
- `com.ticket.queue.config`: app.queue와 admission token 설정
- `com.ticket.queue.domain`: queue model과 port
- `com.ticket.queue.infra`: Redis Lua/Redisson 저장소, 직접 획득하는 advance lock, signed admission token 발급 구현

Queue Server는 Ticket Server의 좌석 선택, hold, 주문, refresh token Redis에 접근하지 않는다.

## 고위험 영역

- scheduler의 shard별 serving sequence 전진과 `enter`의 active session 등록은 별도 단계이므로 둘의 제한값을 함께 검증한다.
- queue ticket TTL(`defaultQueueTtl`)과 예매 가능 session TTL(`shoppingSessionTtl`)은 목적이 다르다.
- admission token secret, issuer, audience는 Ticket Server 설정과 일치해야 한다.
- 회차별·전역 active session/rate/burst 제한은 Lua에서 함께 갱신되며, 내부 완료 API는 공유 secret 검증 후 session을 조기 해제한다.
- Queue Server의 `state`는 공개 API라 access token/header를 요구하지 않고, `enter`는 `X-Queue-Token`만 검증한다.
- `/state` polling은 Cloudflare/nginx 캐시 경유를 전제로 하며, `join`/`enter`는 캐시하지 않는다.
- `enter` 성공 응답을 받은 뒤 클라이언트는 admission token을 들고 Ticket Server 예매 흐름으로 이동한다.
- 현재 배포는 단일 Redis다. Redis Cluster를 고려한 hash tag를 바꿀 때는 shard key뿐 아니라 원자 갱신되는 `{admission}` session/rate key의 slot도 함께 검토한다.

## 검증

작업 범위에 맞는 가장 좁은 명령부터 실행한다.

```powershell
.\gradlew.bat test
.\gradlew.bat bootJar
```

문서만 변경한 경우 Java 빌드 대신 아래를 우선한다.

```powershell
rg -n "include\\(|project\\(" README.md AGENTS.md settings.gradle build.gradle
```

## 보고

마무리 보고에는 변경 파일, 핵심 변경점, 검증 결과, 남은 리스크를 포함한다.
