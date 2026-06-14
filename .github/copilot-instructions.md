# Ticket Queue 저장소 Copilot 지침

이 저장소에서 Copilot Chat, Copilot code review, Copilot coding agent는 루트 [AGENTS.md](../AGENTS.md)를 공통 기준으로 따른다.

## 문서 우선순위

1. `AGENTS.md`
2. `README.md`
3. `docs/cloudflare-static-state.md`
4. `docs/superpowers/specs/2026-06-06-queue-simplification-design.md`
5. `build.gradle`, `settings.gradle`, 테스트 코드

## Copilot 전용 리뷰 기준

- 모든 리뷰, 제안, 설명은 한국어로 작성한다.
- 패치만 보지 말고 주변 코드, 관련 설정, 관련 테스트, 배포 구성을 함께 본다.
- 요청 범위를 벗어난 기능 추가, 리팩터링, 추상화는 제안하지 않는다.
- 스타일 취향보다 실제 결함 가능성, 회귀 위험, 테스트 공백을 우선 본다.

## 우선 검토 영역

- 대기열 상태 전이와 admission token 발급/검증
- Redis key naming, TTL, 만료 처리, Lua script 로딩
- scheduler, admission advance, public state 발행 흐름
- Cloudflare static state, nginx, docker-compose, 운영 환경 변수
- 공개 API와 내부 인증 경계

## 권장 검증

- 빠른 검증: `./gradlew test`
- 회귀 확인: `./gradlew test --rerun-tasks`
