# Ticket Queue 저장소 Copilot 지침

이 저장소의 규칙은 루트 [AGENTS.md](../AGENTS.md)와 `docs/`가 단일 원본이다. Copilot Chat, Copilot code
review, Copilot coding agent는 모두 그 기준을 따른다. **이 파일에 규칙을 복제하지 않는다** — 검증 명령이나
모듈 경계처럼 바뀌는 사실은 이 파일이 아니라 `AGENTS.md`와 `docs/`에서 읽는다.

## 규칙을 확인할 곳

| 알아야 하는 것 | 읽을 곳 |
| --- | --- |
| 작업별로 먼저 읽을 문서 | `AGENTS.md` 의 "작업별로 먼저 읽을 문서" |
| 리뷰 기준 전체 | `AGENTS.md` 의 "코드 리뷰" |
| 모듈 경계와 강제되는 규칙 | `docs/architecture.md` |
| 구현 절차, Lua·TTL·secret 규칙, 완료 조건 | `docs/development.md` |
| 검증 명령과 테스트 관례 | `docs/testing.md` |
| API 계약, Redis key 목록, 설정값 기본값 | `README.md` |
| `/state` 캐시 설정 | `docs/cloudflare-state-api-cache.md` |

## 문서를 열 수 없는 경우에도 적용할 최소 기준

- 모든 리뷰, 제안, 설명은 한국어로 작성한다.
- 패치만 보지 말고 주변 코드, 관련 설정, 관련 테스트, 배포 구성을 함께 본다.
- 스타일 취향보다 실제 결함 가능성, 회귀 위험, 테스트 공백을 우선 본다.
- 요청 범위를 벗어난 기능 추가, 리팩터링, 추상화는 제안하지 않는다.
- findings first 원칙을 따르고 심각도 높은 순서로 적는다.

## 우선 검토 영역

- **모듈 경계**: `queue-api`와 `queue-scheduler`는 서로 의존하지 않고 `queue-redis`만 공유한다.
  `@EnableScheduling`은 scheduler에만 둔다. `queue-redis`에 Spring Boot 플러그인이 들어오면 위반이다.
- **소스 위치**: 애플리케이션 코드는 `queue-api/src`, `queue-scheduler/src`, `queue-redis/src`에 있다.
  루트 `src`에는 배포 구조 검증 테스트와 Redis 통합 테스트만 있다.
- `/state`는 공개 API다. 사용자별 정보가 응답에 섞이면 CDN 캐시 전제가 깨진다.
- `/join` hot path가 public state projection을 쓰면 위반이다. public state는 scheduler가 갱신한다.
- Redis key naming과 hash tag, TTL, Lua script 로딩과 반환 형태, 입장 멱등성
- `defaultQueueTtl`과 `shoppingSessionTtl`은 목적이 다르다. 한쪽 기준으로 맞추는 변경은 지적한다.
- `advanceBatchSize`와 `advanceIntervalMs`는 함께 유입량을 결정한다. 하나만 바뀌면 근거를 요구한다.
- admission token의 secret·issuer·audience는 Ticket Server 설정과 일치해야 한다.
- API와 scheduler는 같은 커밋 SHA 이미지로 배포된다. scheduler에 API 인증 secret을 넘기지 않는다.
- **Redis key·TTL·Lua 또는 API와 scheduler 사이의 상태 계약을 바꿨다면 통합 테스트가 필수다.**
