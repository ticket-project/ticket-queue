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

- `com.ticket.queue.api`: enter/status HTTP API와 응답 DTO
- `com.ticket.queue.application`: use case, status 조회, scheduler, admission 응답 조립
- `com.ticket.queue.config`: app.queue와 admission token 설정
- `com.ticket.queue.domain`: queue model과 port
- `com.ticket.queue.infra`: Redis/Redisson 저장소, distributed lock AOP, signed admission token 발급 구현

Queue Server는 Ticket Server의 좌석 선택, hold, 주문, refresh token Redis에 접근하지 않는다.

## 고위험 영역

- waiting/active 전환은 TTL, 중복 진입, 순번 계산, tick당 승격 수와 함께 검증한다.
- queue session 만료는 queue entry 만료와 동일하지 않다.
- admission token secret, issuer, audience는 Ticket Server 설정과 일치해야 한다.
- `activeTtl`은 active member TTL이자 Ticket Server 예매 API 사용 가능 TTL이다.
- Queue Server의 enter/status는 access token을 검증하지 않는다. status polling은 `X-Queue-Session` 기반이다.
- active 응답 이후 클라이언트는 Queue Server polling을 중지하고 Ticket Server 예매 흐름으로 이동한다.
- Redis Cluster 운영을 고려해 key naming과 자료구조 변경은 신중하게 검토한다.

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
