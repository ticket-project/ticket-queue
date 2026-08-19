# 아키텍처 기준

이 문서는 모듈 경계, 코드 위치, 의존 방향, Redis key 소유권을 정리한다. 전체 흐름과 API 계약,
Redis key 목록, 설정값 기본값은 [README.md](../README.md)가 원본이다. 구현 절차는
[development.md](development.md), 검증은 [testing.md](testing.md)를 함께 본다.

## 모듈 경계

```text
queue-api       join/state/enter HTTP API, access token 인증, queue token과 admission token 발급, API용 Redis 명령
queue-scheduler 상시 scheduler, 입장 인원 계산, shard별 serving sequence 전진, public state 갱신
queue-redis     Redis key, Lua script, Redisson 설정만 공유하는 얇은 라이브러리 (Spring Boot 플러그인 없음)
root project    배포 구조와 저장소 전체 규칙을 검증하는 테스트, Redis·Lua 통합 테스트
```

```text
queue-api ──> queue-redis <── queue-scheduler
```

- `queue-api`는 `queue-scheduler`를 의존하지 않고 그 반대도 성립하지 않는다.
- 두 실행 모듈은 각각 별도의 Spring Boot 애플리케이션이며 별도 Docker 이미지로 배포한다.
- `queue-redis`는 `java-library`다. 여기에 Spring Boot 플러그인이나 실행 코드를 넣지 않는다.
- API 인증 secret을 scheduler 설정이나 컨테이너에 전달하지 않는다.
- 두 실행 모듈은 같은 Redis, 같은 대기열 정책 설정, **같은 커밋에서 만든 이미지**를 사용해야 한다.

## 패키지 경계

두 실행 모듈이 같은 패키지 이름을 쓰지만 물리적으로 다른 모듈이다. 이름이 같다는 이유로 코드를 옮기지 않는다.

| 패키지 | 책임 |
| --- | --- |
| `com.ticket.queue.api` | HTTP endpoint와 응답 DTO |
| `com.ticket.queue.application` | use case, public state 조회, scheduler 전진 계산, admission 응답 조립 |
| `com.ticket.queue.config` | `app.queue` 설정, admission·queue token 설정, 인증 filter, argument resolver |
| `com.ticket.queue.domain` | queue model과 port(`AdmissionStateStore`, `QueueAdvancementStore`) |
| `com.ticket.queue.infra` | Redis Lua·Redisson 저장소, advance lock, signed token 발급 구현 |

`queue-redis`의 `infra`에는 `RedisKey`, `RedisValues`, `RedisScriptLoader`, `RedissonConfig`처럼
두 실행 모듈이 공유해야 하는 규약만 둔다. 특정 모듈만 쓰는 Redis 명령은 그 모듈의 `infra`에 둔다.

## 코드 위치 결정표

| 책임 | 위치 |
| --- | --- |
| `/join`, `/state`, `/enter` endpoint와 응답 DTO | `queue-api` 의 `api` |
| queue token 발급·검증, admission token 발급 | `queue-api` 의 `application` + `infra` |
| access token 검증과 회원 식별 | `queue-api` 의 `config` |
| shard와 slot 계산 | `queue-api` 의 `application` |
| serving sequence 전진과 public state 갱신 | `queue-scheduler` |
| scheduler 실행 주기와 batch 설정 | `queue-scheduler` 의 `config` |
| Redis key 형식과 hash tag | `queue-redis` |
| Lua script 파일 | `queue-redis` 의 `src/main/resources/redis` |
| Redisson 연결 설정 | `queue-redis` |
| 배포 구조와 nginx·CDN 설정 검증 | root project `src/test` |

## 아키텍처 규칙

아래는 권고가 아니라 root project `src/test`의 테스트가 실패시키는 규칙이다. 배포 구조나 워크플로를
건드리면 루트 `test`를 함께 돌린다([testing.md](testing.md#배포-구조-검증-테스트)).

### `ModuleArchitectureTest`

- `settings.gradle`은 세 모듈을 모두 포함한다.
- `queue-api`와 `queue-scheduler`는 각각 Spring Boot 플러그인을 쓰고 `queue-redis`만 의존한다.
  서로를 의존하는 문구가 `build.gradle`에 들어가면 실패한다.
- `queue-redis`는 `java-library`이며 Spring Boot 플러그인을 갖지 않는다.
- `@EnableScheduling`은 `QueueSchedulerApplication`에만 있다. `QueueApiApplication`에 넣으면 실패한다.
- `queue-api`에 `AdvancementScheduler`를, `queue-scheduler`에 `AdmissionController`를 두면 실패한다.

### 그 밖의 배포 검증 테스트

`NginxDeployConfigTest`, `QueueCdnCacheArchitectureTest`, `ClaudeWorkflowConfigTest`가 `deploy/` 설정과
`.github/workflows/`의 형태를 고정한다. 해당 파일을 바꿀 때는 이 테스트가 무엇을 전제하는지 먼저 읽고,
전제 자체를 바꿀 생각이면 테스트를 함께 고친다. 테스트만 지워서 통과시키지 않는다.

## Redis key 소유권

key 형식의 정본은 [README.md의 Redis Key](../README.md#redis-key)다. 경계 판단에서 중요한 것은 두 가지다.

- **hash tag가 원자 연산의 단위다.** shard 단위 hot path는 `{performanceId:shardId}`를 쓰고,
  public state projection과 입장 멱등 marker는 회차 단위 `{performanceId}`를 쓴다.
  hash tag를 바꾸면 같은 Lua script 안에서 함께 다뤄야 하는 key들이 같은 slot에 남는지 확인한다.
  현재 배포는 단일 Redis지만 Redis Cluster를 고려한 형태를 유지한다.
- **누가 쓰는 key인지 구분한다.** `/join`은 shard-local counter, user marker, compact ticket, slot tail,
  pending slot, waiting marker만 갱신한다. shard state와 public state는 scheduler가 갱신한다.
  join hot path에서 public projection을 쓰는 코드는 경계 위반이다.

## 다른 시스템과의 경계

- **Ticket Server(Core).** Queue Server는 Core의 좌석 선택, hold, 주문, refresh token Redis에 접근하지 않는다.
  Core DB나 회차별 정책 snapshot도 조회하지 않는다. 두 시스템은 admission token 계약으로만 만난다.
  secret, issuer, audience가 양쪽에서 일치해야 하며 한쪽만 바꾸지 않는다.
- **Cloudflare.** `/state`만 캐시 대상이다. origin은 `Cache-Control: no-store`로 응답하고 Edge TTL은
  state 전용 endpoint의 Cache Rule에서 정한다. `/join`과 `/enter`는 Cloudflare를 거치지 않고 nginx origin으로
  간다. TTL을 애플리케이션이나 nginx에서 조정하는 변경은 이 경계를 깨뜨린다. 설정 기준은
  [cloudflare-state-api-cache.md](cloudflare-state-api-cache.md)를 따른다.
- **클라이언트.** 대기 상태 확인은 public `/state`만 사용한다. `/status`와 `X-Queue-Session` 기반
  polling API는 제거된 구조이므로 되살리지 않는다.

## 아키텍처 리뷰 질문

- 이 코드가 API 요청 경로, scheduler 주기, 공유 Redis 규약 중 어디에 속하는가
- 두 실행 모듈이 서로를 알게 되지 않았는가
- hot path에 scheduler의 책임이, scheduler에 요청 경로의 책임이 섞이지 않았는가
- 새 Redis key의 hash tag가 함께 다뤄야 하는 key와 같은 단위인가
- Core와의 계약을 한쪽만 바꾸지 않았는가
- `/state`의 캐시 경계와 `/join`·`/enter`의 직접 경로가 유지되는가
