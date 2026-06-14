---
applyTo: "src/**/*.java"
---

# Java/Spring 리뷰 강화 지침

- Java 변경은 패치만 보지 말고 관련 service, controller, infra, config, test까지 함께 확인한다.
- Redis 상태 저장, key 전략, TTL/만료 처리, Lua script 실행 경계를 우선 검토한다.
- admission token과 queue token의 발급/검증, 공개 API와 내부 인증 경계가 깨지지 않는지 확인한다.
- scheduler, admission advance, public state 발행 흐름은 동시 실행과 장애 복구 관점에서 본다.
- 테스트가 없다면 어떤 테스트가 빠졌는지 구체적으로 적는다.
