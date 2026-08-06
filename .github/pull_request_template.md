<!--
PR 제목 형식: <type>(<scope>): <한국어 설명>
예: perf(queue): 공개 상태 조회 부하 완화
상세 기준: docs/development.md의 "커밋과 PR 컨벤션"
-->

## 변경 목적

- 이 PR이 해결하는 문제와 목표를 한두 문장으로 적어 주세요.

## 주요 변경 내용

- API, scheduler, Redis 규약, 배포 변경을 구분해 적어 주세요.

## 리뷰 포인트

- 상태 전이, 동시성, TTL, 토큰 또는 운영 판단 중 특히 확인할 내용을 적어 주세요.

## 영향 범위

- [ ] `queue-api` API 또는 인증 변경
- [ ] `queue-scheduler` 입장 처리 변경
- [ ] Redis key, TTL 또는 Lua script 변경
- [ ] admission token 계약 변경
- [ ] Cloudflare, Nginx 또는 배포 설정 변경

## 테스트

- [ ] `./gradlew test :queue-redis:test :queue-api:test :queue-scheduler:test`
- [ ] `./gradlew :queue-api:bootJar :queue-scheduler:bootJar`

### 테스트 결과

- 실행한 명령과 결과를 적어 주세요.

## 배포 및 롤백

- 배포 순서, 설정 변경, 롤백 방법을 적어 주세요.

## 체크리스트

- [ ] PR 제목이 `<type>(<scope>): <한국어 설명>` 형식을 따릅니다.
- [ ] 요청 범위를 벗어난 변경이 없습니다.
- [ ] 동시성, TTL, 상태 전이의 회귀 가능성을 확인했습니다.
- [ ] 문서와 리뷰 설명을 한국어로 작성했습니다.
