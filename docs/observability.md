# Queue 관측성 기준

Queue API와 scheduler의 운영 지표는 `/actuator/prometheus`에서 노출되고, 배포 환경에서는 Datadog Agent OpenMetrics 설정이 이를 수집한다.

## 지표 계약

| Micrometer 이름 | Prometheus 이름 | 태그 | 의미 |
| --- | --- | --- | --- |
| `queue.admission.join` | `queue_admission_join_total` | `result=created\|duplicate` | Redis join 결과 |
| `queue.admission.enter` | `queue_admission_enter_total` | `path=sharded\|legacy\|unknown`, `result=admitted\|not_admitted\|expired\|invalid_token\|performance_mismatch` | 입장 판정과 legacy 경로 사용량 |
| `queue.scheduler.waiting.performances` | `queue_scheduler_waiting_performances` | 없음 | 현재 scheduler가 발견한 대기 회차 수 |
| `queue.scheduler.advance.batch.configured` | `queue_scheduler_advance_batch_configured` | 없음 | 회차당 설정된 최대 입장 처리 인원 |
| `queue.scheduler.admitted` | `queue_scheduler_admitted_total` | 없음 | 실제 입장 처리 인원 누계 |
| `queue.scheduler.backlog` | `queue_scheduler_backlog` | 없음 | 관측에 성공한 대기 회차의 잔여 인원 합계 |
| `queue.scheduler.oldest.pending.slot.age` | `queue_scheduler_oldest_pending_slot_age_seconds` | 없음 | 가장 오래된 미처리 slot의 나이(초) |
| `queue.scheduler.advance` | `queue_scheduler_advance_total` | `result=success\|skipped\|advance_failure` | 회차별 전진 시도 결과 |
| `queue.scheduler.cycle.duration` | `queue_scheduler_cycle_duration_seconds_*` | `result=success\|partial_failure\|waiting_performance_discovery_failure` | scheduler 한 주기의 지연과 결과 |

`skipped`는 다른 scheduler 인스턴스가 같은 회차의 분산 락을 보유한 경우처럼, 상태를 읽거나 변경하지 못한 정상적인 경쟁 결과다. 모든 회차가 `skipped`된 주기에는 직전 backlog와 slot age를 유지해 알 수 없는 값을 0으로 오인하지 않게 한다.

## 카디널리티와 reason code 정책

- `memberId`, `queueId`, `performanceId`, token 원문은 metric 태그로 사용하지 않는다. 요청량에 비례해 시계열이 늘어나면 모니터링 저장소와 대시보드가 먼저 장애 지점이 될 수 있기 때문이다.
- 태그 값은 위 표에 열거한 애플리케이션 상수만 허용한다. 외부 입력이나 예외 메시지를 태그로 전달하지 않는다.
- scheduler 오류 로그의 `reason`은 지표의 `result`와 같은 `advance_failure`, `waiting_performance_discovery_failure`를 사용한다.
- API의 예상 가능한 입장 거절은 고빈도 경로이므로 개별 WARN 로그를 남기지 않고 counter와 HTTP 상태로 집계한다.

## 운영 판독 순서

1. `queue.scheduler.waiting.performances` 또는 `queue.scheduler.backlog`가 증가하는데 `rate(queue.scheduler.admitted[5m])`가 따라오지 않으면 처리 용량 또는 Redis 상태를 확인한다.
2. `queue.scheduler.oldest.pending.slot.age`가 `app.queue.advance-interval-ms`의 여러 배로 계속 증가하면 scheduler 지연으로 판단한다.
3. `advance_failure` 또는 `waiting_performance_discovery_failure` 증가 시 같은 reason code의 로그를 조회한다.
4. `path=legacy` 비율이 충분한 기간 0으로 유지되는 것을 확인한 뒤 legacy token 경로 제거를 별도 변경으로 검토한다.
