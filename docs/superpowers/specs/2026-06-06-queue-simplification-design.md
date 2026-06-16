# ticket-queue 군더더기 복잡도 제거 설계

> 보관 문서: 이 문서는 2026-06-06 당시 이전 queue 구현을 기준으로 한 리팩터링 기록입니다. 현재 Queue Server API와 public state 캐시 구조는 [README.md](../../../README.md)와 [cloudflare-cached-state.md](../../cloudflare-cached-state.md)를 기준으로 봅니다.

| 항목 | 내용 |
|---|---|
| 작성일 | 2026-06-06 |
| 대상 | `ticket-queue` (branch `feat/queue`) |
| 원칙 | karpathy-guidelines ② 단순화, ③ 외과적 수정 |
| 성격 | 동작 보존 리팩터링 (기능·성능·확장성 유지, 군더더기만 제거) |

## 배경

대규모 트래픽 점검 중 코드에서 두 종류의 복잡도가 확인됨.

- **정당한 복잡도(유지)**: Lua 원자적 승격, `{performanceId}` 해시태그 샤딩, 분산락 개념, 포트/어댑터 계층, 정책 스냅샷. 문제 자체가 어려워서 생긴 본질적 복잡도.
- **우발적 복잡도(제거)**: 단일 사용처를 위한 과한 추상화.

이 설계는 우발적 복잡도 중 합의된 두 가지(A, B)만 제거한다.

## 범위

- **A. 분산락 AOP 인프라 제거** → Redisson 직접 호출로 대체
- **B. status 경로의 중복 ZRANK 제거**

### 비범위 (이번에 하지 않음)

- C. 정책 4필드 하위호환 디코딩 제거
- D. `UuidSupplier` 인라인화 (테스트 이음새로 정당, 유지)
- E. `findSession`의 `contains(":")` 레거시 가드 제거
- 대규모 트래픽 개선(슬롯 조기 반납, 정책 캐시, 샤드 한계 등)은 별도 작업

## A. 분산락 AOP 제거

### 현재 구조

`@DistributedLock` 애노테이션 + AOP + SpEL 파서 6개 클래스가 오직 `QueueAdmissionAdvancer.advance()` 한 곳을 위해 존재한다. `ErrorType`은 값이 하나(`LOCK_ACQUISITION_FAILED`), `ErrorCode`도 하나(`E409`)뿐인 범용 에러 프레임워크.

### 락을 유지하는 이유

락은 단순 중복 방지가 아니라 **`admitLimitPerTick`을 인스턴스 전체에서 전역으로 강제**한다. 락이 없으면 N개 인스턴스가 각자 승격을 실행해 틱당 `limit × N`명까지 승격될 수 있다. (Lua가 `maxActiveUsers` 총량은 막지만, 틱당 승격 속도는 못 막는다.) 따라서 락 자체는 남기고, AOP 포장지만 제거한다.

### 결정 사항

- **락 위치: infra의 `RedisQueueTicketStore.admitWaitingBatch` 내부.** Lua를 감싸는 락을 그 Lua가 사는 곳에 둬서 포트/어댑터 경계를 유지한다(application은 Redisson을 직접 알지 않음). `QueueAdmissionAdvancer`의 생성자/시그니처는 불변 → 기존 advancer 테스트 무변경.
- **락 실패 시 동작: 건너뛰기(skip-on-fail).** 다른 인스턴스가 이미 승격 중이면 이 공연만 조용히 건너뛰고 다음 공연을 계속 처리한다.

### 동작 변경

기존: 락 실패 시 `CoreException`을 던져 스케줄러 `forEach`가 통째로 중단(그 틱의 나머지 공연 스킵) — 잠복 버그.
변경: 락 실패 시 해당 공연만 건너뜀. 나머지 공연은 정상 처리. **버그 수정이자 의도된 동작.**

락 파라미터는 보존: waitTime 0, leaseTime 5000ms, 키 `lock:queue:advance:{performanceId}`(기존 동일).

### 변경 상세

삭제(7):
- `infra/DistributedLock.java`
- `infra/DistributedLockAop.java`
- `infra/CustomSpringELParser.java`
- `infra/CoreException.java`
- `infra/ErrorType.java`
- `infra/ErrorCode.java`
- `test/.../infra/CustomSpringELParserTest.java`

수정:
- `application/QueueAdmissionAdvancer.java`: `@DistributedLock` 애노테이션 및 import 제거. 메서드 본문 불변.
- `infra/QueueRedisKey.java`: `advanceLock(Long performanceId)` 추가 → `"lock:queue:advance:" + performanceId`.
- `infra/RedisQueueTicketStore.java`: `admitWaitingBatch`에서 기존 검증 후 `RLock`을 `tryLock(0, 5000, MILLISECONDS)`로 획득, 실패 시 `return`(건너뛰기), 성공 시 try/finally로 Lua eval + `removeWaitingPerformanceIfEmpty` 수행 후 `isHeldByCurrentThread()` 확인하고 unlock. `InterruptedException`은 인터럽트 복원 후 return.
- `build.gradle`: 락 전용 의존성 `spring-aop`, `aspectjweaver`, `spring-expression` 제거(빌드로 검증). spring-context가 transitive로 제공하므로 안전 예상.

## B. status 중복 ZRANK 제거

### 현재 구조

`findTicket`이 `waitingSet.rank()`로 순번을 구하지만 버리고 WAITING ticket만 반환 → `QueueStatusReader`가 `findWaitingPosition`으로 같은 `rank()`를 **한 번 더** 호출. 폴링 1회당 ZRANK 2회.

### 변경 상세

- `domain/QueueTicket.java`: `Long position` 필드 추가(마지막). 기존 3-arg/4-arg 편의 생성자 유지(각각 position=null). WAITING이 아닐 때 position은 null.
- `infra/RedisQueueTicketStore.java#findTicket`: waiting 분기에서 `position = rank + 1`을 ticket에 담아 반환.
- `application/QueueStatusReader.java`: waiting일 때 `ticket.position()`을 그대로 사용. `findWaitingPosition` 호출 제거.
- `domain/QueueTicketStore.java`: `findWaitingPosition` 포트 메서드 제거(죽은 코드).
- `infra/RedisQueueTicketStore.java`: `findWaitingPosition` 구현 제거.
- `test/.../application/QueueStatusReaderTest.java`: waiting 테스트에서 `findWaitingPosition` 스텁 제거, `findTicket`이 position을 담은 ticket을 반환하도록 수정.

### 효과

- 폴링당 Redis 왕복 ZRANK 2회 → 1회.
- 순번을 단일 시점에 한 번만 읽어 일관성 소폭 개선(기존의 "ticket=WAITING이지만 직후 rank=null → EXPIRED" 경쟁 창 제거). downstream `QueueAdmissionService.waitingResponse`가 null position을 1L로 정규화하므로 안전망 유지.

## 영향 파일 요약

| 구분 | 파일 |
|---|---|
| 삭제 | DistributedLock, DistributedLockAop, CustomSpringELParser, CoreException, ErrorType, ErrorCode, CustomSpringELParserTest |
| 수정(main) | QueueAdmissionAdvancer, QueueRedisKey, RedisQueueTicketStore, QueueTicket, QueueStatusReader, QueueTicketStore, build.gradle |
| 수정(test) | QueueStatusReaderTest (필요 시 RedisQueueTicketStoreTest의 findTicket 단언 보정) |

## 검증 기준 (성공 정의)

1. `./gradlew.bat test` 전부 통과
2. `./gradlew.bat bootJar` 빌드 성공
3. 락 7개 파일 + `findWaitingPosition` 완전 제거, 잔존 참조 0 (grep 확인)
4. 동작 보존: 승격은 여전히 틱당 `admitLimitPerTick` 한도를 전역 유지(락 유지), status는 올바른 순번 반환
5. 동작 개선: 락 실패가 스케줄러 틱 전체를 중단시키지 않음

## 리스크

- build.gradle 의존성 제거 후 컴파일 깨질 가능성 → `./gradlew.bat test`로 즉시 확인, 깨지면 해당 의존성만 복구.
- `RedisQueueTicketStoreTest`가 QueueTicket을 record 동등성으로 비교하면 position 추가로 단언 수정 필요 → 테스트 실행으로 확인.
