# Core 입장 속도 조절 설계: 운영 중 변경과 과부하 자동 멈춤

| 항목 | 내용 |
|---|---|
| 작성일 | 2026-10-17 |
| 상태 | A 구현(브랜치 `claude/runtime-admission-rate`, 커밋 `e264f74`, 배포 전). B는 제안이고 구현 전이다 |
| 대상 | `queue-scheduler`, `queue-api`, `queue-redis`. 과부하 신호는 ticket-core에도 변경이 필요하다 |
| 관련 | ticket-core [Core 수용량과 Queue 입장률](https://github.com/ticket-project/ticket-core/blob/master/docs/core-capacity.md), gatling-test [capacity-log](https://github.com/ticket-project/gatling-test/blob/master/docs/capacity-log/README.md) |

## 배경

Core 수용량 측정의 목적은 Queue가 Core에 1초에 들여보낼 인원 R을 정하는 것이다. R은 운영에서 잰 Core 한계 C에
안전계수 0.7을 곱해 정한다(ticket-core `core-capacity.md`). 그런데 지금 Queue에는 R을 운영 중에 다루는 수단이 없다.

- 입장 속도는 scheduler 환경 변수 `QUEUE_ADVANCE_BATCH_SIZE`(기본 500)와 `QUEUE_ADVANCE_INTERVAL_MS`(기본 1000)로 정한다.
  `AdvancementProperties`가 기동할 때 한 번 읽으므로 바꾸려면 scheduler를 다시 띄워야 한다.
- 이 한도는 대기 중인 회차마다 각각 적용된다([scheduler 전진 규칙](../../development.md#scheduler-전진-규칙)).
- Core가 느려져도 scheduler는 같은 속도로 계속 내보낸다. 2026-08-04 운영 측정에서 Core 안에 동시에 1,395명이 쌓였다.
  로컬 측정에서는 같은 코드로 400 u/s가 실행마다 PASS와 FAIL로 갈렸다. 모든 실행이 시작 5~7초 동안 막혔고, 그 뒤 회복했는지가
  판정을 갈랐다(capacity-log P-016).

## 업계 조사 요약 (2026-10-17)

| 서비스 | 들여보내는 방식 | 숫자를 정하고 다루는 법 |
|---|---|---|
| Queue-it | 속도 방식(분당 N명) | 부하 테스트로 정하라고 권한다. 운영 중 API·관리 화면으로 바로 바꾼다 |
| Cloudflare Waiting Room | 동시 활성 사용자 상한 + 분당 신규 사용자 상한 | 마지막 요청 후 세션 시간(1~30분, 기본 5분)이 지나면 나간 것으로 본다. 나가는 신호를 따로 받지 않는다 |
| AWS Virtual Waiting Room | 1분마다 고정 수 증가, 또는 완료·이탈 수만큼 증가 | 고정 증가는 CloudWatch 경보가 정상일 때만 하도록 설정할 수 있다 |
| SeatGeek | 정해진 속도로 이동, 또는 보호 구역 동시 N명 + FIFO | 판매 전 발급하는 입장 토큰 수를 판매 가능한 티켓 수에 맞춘다 |
| Ticketmaster Smart Queue | 오픈 전 대기실, 오픈 순간 줄 세움. 공연 날짜마다 대기열이 따로 있다 | 대기실에 들어온 시각이 순서를 정하지 않는다. 차례가 오면 10분 동안 구매할 시간을 준다 |
| NetFUNNEL | 진입 허용 수(동시 인원) | 완료 신호(`nfStop`) 또는 제한 시간으로 자리를 돌려받는다. 실시간 트래픽에 따라 조절하는 모드가 있다 |
| G마켓 Redcarpet | 속도 방식(초당 유입량) | 전체 유입량을 진행 중인 행사들에 나눈다. 관리 도구로 실시간 수정한다 |

정리하면 다음과 같다.

- 속도 방식이 주류다. 동시 인원 상한은 함께 쓰더라도 안전장치 역할에 가깝다.
- 숫자는 부하 테스트로 정하고, 낮게 시작해 운영 중에 지켜보며 올린다. NetFUNNEL 가이드는 자원이 남으면 10~20% 올리고
  5~10분 관찰하라고 한다.
- 운영 중 조절과 과부하 시 멈춤은 대부분의 서비스가 갖고 있다. 이 설계의 범위가 이 두 가지다.

## 범위

### A. 운영 중 입장 속도 변경

**동작**

```text
scheduler 실행마다
  Redis에서 회차당 1회 실행 한도를 한 번 읽는다
    값이 있으면 그 값을 쓴다. 0이면 아무도 전진시키지 않는다
    값이 없으면 QUEUE_ADVANCE_BATCH_SIZE를 쓴다 (지금 동작)
  대기 중인 회차마다 그 한도로 advancePublicState를 호출한다
```

- 한도는 값 하나이고 지금처럼 회차마다 같은 값이 적용된다. 회차별 값이나 회차 간 분배는 이 범위에 넣지 않는다(비범위 1).
- 한도가 0이어도 `advancePublicState`는 호출한다. 전진 없이 public state의 tail과 status를 갱신해야 새로 줄 선 사람이 보이기 때문이다.

- 값이 정수가 아니거나 음수면 무시하고 직전 값을 유지한다. Redis 읽기에 실패해도 직전 값을 유지한다.
  기본값으로 돌아가면 운영자가 0으로 멈춰 둔 입장이 풀리기 때문이다.
- 값이 바뀌면 scheduler가 `advance batch size changed from {이전} to {새 값} source={redis|config}` INFO 로그를 남긴다.

**기존 규칙과 충돌하는 점**

[development.md의 설정](../../development.md#설정)은 "회차별 처리량 정책은 Redis에 저장하지 않는다. 입장량은 scheduler 공통
`advanceBatchSize`로 관리한다"고 정한다. 이 설계는 회차별 값이 아니라 전체 값 하나를 Redis에 두므로 회차별 정책 금지와는
맞지만, "입장량은 환경 변수로만 관리한다"는 전제는 바꾼다. 구현과 함께 그 문단을 고쳤다.

**값을 바꾸는 경로: `redis-cli`**

처음에는 `queue-api`에 관리 API를 두는 안을 검토했다. 구현에서는 EC2에서 `redis-cli`로 key를 직접 쓰는 방식으로 줄였다.

- `queue-api`는 nginx를 거쳐 외부에 열려 있다. 관리 API를 넣으면 새 secret, nginx 차단 규칙, 인증 테스트가 함께 필요하다.
- `redis-cli`는 EC2 SSH 접근이 있어야만 쓸 수 있다. 접근 통제를 새로 만들지 않아도 된다.
- 대신 입력을 쓰는 순간에 검증하지 못한다. scheduler가 읽을 때 검증하고, 반영 여부는 로그로 확인한다.
- 화면이나 자동화(B의 Datadog 경보 방식 등)에서 값을 바꿔야 하면 그때 관리 API를 더한다.

운영 명령은 [deploy/README.md](../../../deploy/README.md#운영-중-입장-한도-바꾸기)에 있다.

**바뀐 곳**

| 위치 | 내용 |
|---|---|
| `queue-redis` `RedisKey` | `advanceBatchSizeOverride()` = `queue:config:advance-batch-size`. [architecture.md key 목록](../../architecture.md#redis-key-소유권)에 더했다 |
| `queue-scheduler` `QueueAdvancementStore` | `findAdvanceBatchSizeOverride()`. Redis 구현은 key의 문자열을 그대로 돌려준다. `advancePublicState`는 실제로 전진시킨 인원을 돌려준다 |
| `queue-scheduler` `AdvancementScheduler` | 실행마다 한 번 읽고 회차별 락 밖에서 값을 정한다. 검증과 직전 값 유지, 변경 로그, Datadog 지표를 맡는다 |
| 문서 | development.md scheduler 전진 규칙·설정 문단·표, architecture.md key 목록, AGENTS.md 고위험 영역, deploy/README.md 운영 절차 |

**Datadog 지표**

모든 상태를 Datadog에서 볼 수 있게 scheduler가 지표 6개를 내보낸다(사용자 결정 2026-10-17). `advancePublicState`는 실제로
전진시킨 인원을 돌려주도록 바꿨다. 지표 목록은 [development.md의 scheduler 전진 규칙](../../development.md#scheduler-전진-규칙)에 있다.
로그(변경 INFO, 잘못된 값·읽기 실패 WARN)는 기존 Datadog 로그 수집으로 들어간다.

**검증 (2026-10-17 실행)**

- `AdvancementSchedulerTest` 8건: 기본값 사용, override 사용, 0이면 0으로 호출, override 삭제 뒤 기본값 복귀, 잘못된 값 무시,
  읽기 실패 시 멈춘 상태 유지, 지표 값(적용 한도·override 여부·대기 회차 수·입장 허용 누적), 잘못된 값·읽기 실패 횟수.
- `RedisQueueContractIntegrationTest`에 2건 추가(전체 6건): override key 읽기·삭제, 한도 0일 때 전진 없이 public state 갱신과 반환 인원.
- `./gradlew test integrationTest :queue-scheduler:bootJar` 통과.
- scheduler jar를 임시 Redis와 띄워 `SET queue:config:advance-batch-size 70` 뒤 `/actuator/prometheus`에 지표 6개가 나오고
  `advance batch size changed from 500 to 70 source=redis` 로그가 남는 것을 확인했다. Datadog에 실제로 들어오는지는 배포 뒤 확인한다.

### B. Core 과부하 자동 멈춤

**동작**

```text
scheduler 실행마다
  Core 상태를 짧은 timeout(예: 300ms)으로 묻는다
    BUSY 또는 응답 없음이 연속 N번이면 멈춤으로 바꾼다
    멈춘 뒤 OK가 연속 M번이면 다시 내보낸다
  실제 한도 = A의 한도 × (멈춤이면 0, 아니면 1)
```

- A의 값을 덮어쓰지 않는다. 멈춤은 scheduler의 별도 상태라서, 풀리면 운영자가 정한 값으로 돌아간다.
- 멈춤 기준(N)과 재개 기준(M)을 다르게 둔다. 같으면 매초 멈췄다 풀렸다를 반복할 수 있다.
- 응답이 없으면 BUSY로 본다. Core가 막히면 상태 요청도 같은 요청 스레드를 기다리다 늦어진다(capacity-log P-009).
- 기능 켜기 설정을 두고 기본값은 꺼짐이다. 기준값을 측정으로 정하기 전에는 켜지 않는다.

**신호를 받는 방법**

| 방법 | 장점 | 단점 |
|---|---|---|
| ① Core 상태 API를 scheduler가 매 실행마다 호출 (제안) | 1초 안에 반응한다. 응답 없음도 신호가 된다 | Core와 Queue 양쪽을 고친다. Core 계약이 하나 는다 |
| ② Datadog 경보가 A의 한도를 0으로 바꿈 | 코드가 적다 | 경보는 보통 분 단위로 판단해 느리다. 5~7초 막힘에 늦다. A는 `redis-cli`로만 바꿀 수 있어서 경보가 호출할 관리 API를 먼저 만들어야 한다 |
| ③ scheduler가 Core `/actuator/prometheus`를 매번 읽음 | Core를 고치지 않는다 | 응답이 약 50KB라 무겁고, 외부에 열면 안 되는 경로를 쓴다 |

**BUSY 기준 후보**

| 지표 | 근거 |
|---|---|
| Hikari 연결 대기 수 | 지금까지의 FAIL 대부분이 연결 대기에서 시작했다(capacity-log P-001, P-008, P-011, P-013) |
| Tomcat 요청 스레드 사용률 | 2026-10-10 로컬 측정에서 busy 200/200일 때 모든 요청이 함께 늦었다 |

기준값은 설정으로 두고 지금 정하지 않는다. 운영 C 측정 뒤 core-capacity.md 9단계의 05 순간 부하(R → 2R)에서 Core가 무너지기
직전의 지표를 보고 정한다.

**바뀌는 곳**

| 위치 | 내용 |
|---|---|
| ticket-core | 상태 API. 외부에 열지 않는다. Core 공개 계약이므로 ticket-core의 API 계약 변경 절차를 따른다. actuator health group으로 만들 경우 컨테이너 liveness·readiness 그룹에 넣지 않는다. BUSY가 재기동으로 이어지면 안 된다 |
| `queue-scheduler` | Core 상태 client(timeout), 멈춤 상태 판단(연속 횟수), 설정(URL, timeout, N, M, 켜기), 멈춤 여부 지표와 전환 로그 |
| 문서 | development.md 설정 표, scheduler 전진 규칙에 멈춤 조건 추가 |

**검증**

- OK·BUSY·timeout 순서를 넣었을 때 멈춤 전환이 기대와 같다(순수 로직 단위 테스트).
- 멈춘 동안 A의 값이 그대로 남고, 풀리면 그 값으로 전진한다.
- 기능이 꺼져 있으면 지금과 똑같이 동작한다.

**확인할 것**: scheduler에서 Core 상태 API로 가는 네트워크 경로. [deploy/README.md](../../../deploy/README.md)에는 EC2 compose와
ECS 서비스 설명이 함께 있어 운영 배치를 이 문서에서 확정하지 않았다.

### 진행 순서

```text
[Queue]  A 구현 ───────────────────────────────┐
[Core]   상태 API ─▶ [Queue] B 구현(꺼진 상태) ──┤
[측정]   운영 C 측정 ────────────────────────────┴─▶ core-capacity.md 9단계
                                                    A: 운영 중 한도 변경 확인
                                                    B: 05 순간 부하로 기준값을 정하고 켠다
```

운영 C 측정은 대기열을 끈 회차(`FORCE_OFF`)에서 하므로 Queue 변경과 함께 진행할 수 있다.

## 비범위: 나중에 다시 정할 것

2026-10-17 논의에서 나왔지만 이번에 하지 않기로 한 것이다. 다시 다룰 때 여기서 시작한다.

### 1. 여러 회차 동시 오픈과 Core 전체 예산

- **문제**: 한도가 회차마다 적용되므로 회차 3개가 함께 열리면 Core로 3R이 들어간다. 한 공연의 여러 회차는 보통 같은 시각에 열린다.
- **지금**: 이번 범위에서 다루지 않는다. Core가 넘치면 B가 멈춰 준다. 이때 입장이 멈췄다 풀렸다를 반복해 대기 시간이 고르지 않을 수 있다.
- **대기열 단위**: 회차마다 대기열을 두는 지금 구조는 유지한다. 좌석 재고와 순서가 회차별이고, Ticketmaster도 공연 날짜마다 대기열을 연다.
- **한도 단위 선택지**: Queue-it·Cloudflare는 대기실마다, CrowdHandler는 도메인 단위, G마켓은 전체 유입량을 행사들에 나눈다.
  Core는 모든 회차가 함께 쓰는 서버 하나이므로 Core 전체 예산 B 하나를 두는 방향이 논의됐다.
- **나누는 방식**: 예산 70, 회차 A 대기 40,000명, B 10,000명, C는 거의 비어 있고 초당 5명씩 새로 줄을 선다고 가정한 비교다.

  | 방식 | 1초 동안 A / B / C | 합계 | 문제 |
  |---|---|---|---|
  | 균등, 재분배 없음 | 23 / 23 / 5 | 51 | C가 남긴 몫이 매초 버려진다 |
  | 대기 인원 비례 | 55 / 13 / 0 | 68 | 작은 회차가 거의 들어가지 못한다 |
  | 균등 + 남는 몫 재분배 | 32 / 33 / 5 | 70 | 예산을 버리지 않는다. 논의 당시 추천안 |

- **함께 풀 문제**: 회차별 락이 실행마다 풀리므로 scheduler 두 개가 겹치면 같은 회차가 두 번 전진할 수 있다([deploy/README.md](../../../deploy/README.md)).
  전체 예산을 도입하면 "같은 1초의 예산은 한 번만 쓴다"는 보장이 필요하다.

### 2. 매진 시 입장 중지와 재개

- 15,000석 회차에 초당 70명이 평균 1.5석씩 사면 약 143초 만에 1만 명이 입장해 매진된다. 대기자 5만 명이면 마지막 사람은 약 714초를 기다린다.
- 매진 뒤에도 내보내면 Core는 거절만 처리하고 대기자는 의미 없이 기다린다. 선점 만료(600초)나 결제 실패로 좌석이 다시 생기면 다시 내보내야 한다.
- SeatGeek은 입장 토큰 수를 판매 가능한 티켓 수에 맞춘다. 구현하려면 Core가 남은 좌석 정보를 Queue에 알려야 한다.

### 3. 오픈 전 대기자 순서 무작위 배정

- 지금은 도착 시각을 50ms slot으로 나눠 그 순서로 전진한다([api.md](../../api.md)). 순서를 섞는 코드는 없다.
- 몇 ms 먼저 도착한 빠른 회선과 봇이 유리하다. Queue-it과 NetFUNNEL은 오픈 전 대기자를 오픈 순간 무작위로 섞고, 이후 도착자는 도착 순으로 세운다.
- 무작위 배정은 "일찍 왔는데 밀렸다"는 불만을 낳는다. Ticketmaster는 순서 결정 방식을 밝히지 않아 2026년에 투명성 논란을 겪었다.

### 4. 봇 방지

- 지금은 계정당 중복 join만 막는다. Queue 코드에 CAPTCHA나 봇 판별은 없다. Core와 ticket-fe는 확인하지 않았다.
- 업계 예: Ticketmaster Verified Fan(사전 등록·사람 확인), Queue-it 작업 증명 CAPTCHA, 확인된 사용자당 번호표 하나.
- 2024-03-22 시행 개정 공연법은 매크로로 산 입장권의 부정판매를 처벌한다(1년 이하 징역 또는 1천만 원 이하 벌금). 탐지는 여전히 서비스가 해야 한다.

### 5. `/state` 확인 시점 흩뜨리기(jitter) 확인

- serving은 실행마다 오르지만 클라이언트는 `refreshAfterMs`(기본 5,000ms)마다 확인하고, Cloudflare Edge TTL이 1~5초 더해진다.
- 오픈 순간 함께 줄 선 사람들의 확인 시점이 겹치면 Core에는 1초에 R명씩이 아니라 몇 초마다 묶음으로 들어올 수 있다. 미확인이다.
- ticket-fe가 확인 시점을 흩뜨리는지 먼저 본다. 묶음 입장이 맞다면 R을 정할 때 그 묶음도 견디는지 확인한다.

### 6. 동시 인원 상한 방식과 Closed Model 실험

- Gatling Closed Model(`constantConcurrentUsers(N)`)로 "한 명이 나가면 한 명이 들어오는" 방식을 Queue 변경 없이 시험할 수 있다.
- 우선순위를 낮춘 이유: 업계 다수가 속도 방식을 기본으로 쓴다. Core는 완료 신호를 보내지 않고 `shoppingSessionTtl`(기본 15분)로만
  정리되므로 동시 인원을 정확히 셀 수 없다. 사지 않는 사람이 자리를 차지해 처리량이 떨어질 수 있다는 업계 비교도 있다.
- ticket-core `core-capacity.md`는 "Closed Model의 동시 사용자 수는 입장률로 바로 쓰지 않는다"고 정한다. 이 방식을 검토하려면 그 규칙도 바꾼다.
- 다시 볼 때: 05 순간 부하에서 Core가 회복하지 못하고 B로도 해결되지 않을 때.

### 7. 그 밖에 확인할 것

- **결제 단계**: 지금 측정 흐름(요약 → 좌석 상태 → 선택 → 주문 생성 → 주문 조회)에 실제 결제(PG 연동)가 없다. 결제가 붙으면 다시 잰다.
  Taylor Swift 사전 판매(2022-11)는 대기열이 있었지만 코드 검증 단계 오류로 장바구니의 표를 잃는 사람이 생겼다.
- **입장 토큰 검증**: 운영 Core의 `ADMISSION_TOKEN_ENFORCEMENT_ENABLED` 값을 확인하지 않았다. core-capacity.md 9단계에서 켠다.
- **운영 설정값**: 운영 `.env`의 `QUEUE_ADVANCE_BATCH_SIZE`와 `shoppingSessionTtl`을 확인하지 않았다.

## 출처

- [Queue-it: Rate-based queuing](https://queue-it.com/blog/rate-based-queuing/)
- [Queue-it: How does Queue-it work](https://www.queue-it.com/how-does-queue-it-work/)
- [Queue-it: Pre-queue](https://queue-it.com/pre-queue/)
- [CrowdHandler: The problem with One In, One Out](https://website-origin.crowdhandler.com/blog/one-in-one-out-problems)
- [CrowdHandler: rate 설정 문서](https://support.crowdhandler.com/support/solutions/articles/80001016979)
- [CrowdHandler: 대기열 활성화 전 진입 시 동작](https://support.crowdhandler.com/en/support/solutions/articles/80000061556)
- [Cloudflare Waiting Room 설정 항목](https://developers.cloudflare.com/waiting-room/reference/configuration-settings/index.md)
- [Cloudflare: How Waiting Room queues](https://blog.cloudflare.com/how-waiting-room-queues)
- [AWS Virtual Waiting Room: Solution components](https://docs.aws.amazon.com/solutions/latest/virtual-waiting-room-on-aws/solution-components.html)
- [AWS Architecture Blog: SeatGeek virtual waiting room](https://aws.amazon.com/blogs/architecture/build-a-virtual-waiting-room-with-amazon-dynamodb-and-aws-lambda-at-seatgeek/)
- [Ticketmaster: How A Ticketmaster Queue Works](https://blog.ticketmaster.com/how-ticketmaster-queue-works/)
- [Ticketmaster: Taylor Swift 사전 판매 관련 발표](https://business.ticketmaster.com/?p=51362)
- [TicketNews: Smart Queue 순서 결정 방식 보도(2026-05)](https://www.ticketnews.com/2026/05/ticketmaster-presidents-queue-comments-open-new-transparency-questions-over-ticket-sales-process/)
- [NetFUNNEL: Entry control model settings](https://docs.stclab.com/products/netfunnel/user-guide/segment/basic-control-segment/entry-control-model-settings)
- [NetFUNNEL: Real-time monitoring best practices](https://docs.stclab.com/products/netfunnel/user-guide/monitoring/real-time-monitoring/best-practices)
- [G마켓 대기열 시스템 파헤치기](https://dev.gmarket.com/46)
- [문화체육관광부 보도자료: 매크로 이용 암표 처벌](https://www.mcst.go.kr/site/s_notice/press/pressView.jsp?pSeq=20924)
