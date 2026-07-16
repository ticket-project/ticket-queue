# Tomcat Datadog Metrics Implementation Plan

**Goal:** Tomcat connection/thread metrics를 Prometheus와 Datadog에서 수집하고 기존 부하 테스트 대시보드에 직접 확인할 수 있는 위젯을 추가한다.

**Approach:** Spring Boot의 Tomcat MBean registry를 활성화하고 설정 회귀 테스트를 추가한다. 애플리케이션을 배포한 뒤 `/actuator/prometheus`의 실제 지표명을 기준으로 Datadog OpenMetrics 이름을 확인하고 대시보드 위젯을 생성한다.

**Tech Stack:** Spring Boot 4.0.2, Gradle, Micrometer Prometheus, Datadog OpenMetrics, GitHub Actions, Docker Compose

---

## Task 1: 설정과 회귀 테스트

- `src/main/resources/application.yml`에서 `server.tomcat.mbeanregistry.enabled=true`를 유지한다.
- `NginxDeployConfigTest`에 설정 존재 여부를 검증하는 테스트를 추가한다.
- 대상 테스트와 전체 테스트를 실행하고 `bootJar`를 생성한다.

## Task 2: 배포와 원본 지표 검증

- 현재 저장소의 배포 경로와 권한을 확인한다.
- 변경 버전을 대상 VM에 배포하고 컨테이너를 재시작한다.
- `/actuator/prometheus`에서 `tomcat_threads_*`, `tomcat_connections_*` 시계열과 태그를 확인한다.

## Task 3: Datadog 대시보드

- Datadog에서 실제 변환된 `ticket_queue.tomcat_*` 메트릭명을 조회한다.
- busy/current/max threads와 current/max connections 위젯을 기존 부하 테스트 그룹에 추가한다.
- 대시보드를 재조회해 위젯 정의와 실제 데이터 유입을 검증한다.
