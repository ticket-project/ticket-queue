# Ticket Queue 개발 기준

## 커밋과 PR 컨벤션

커밋 메시지와 PR 제목은 Conventional Commits를 기반으로 작성한다. 이 규칙은 사람과 AI 에이전트 모두에게 동일하게 적용한다.

### 기본 형식

```text
<type>(<scope>): <한국어 설명>
```

`scope`는 선택 사항이다.

```text
feat(queue-api): 대기열 참가 API 추가
fix(queue-scheduler): 입장 순번 전진 오류 수정
refactor(queue-redis): Redis 키 조립 책임 분리
perf(queue): 공개 상태 조회 부하 완화
test(queue): shard 입장 경계 테스트 추가
docs: 커밋 및 PR 컨벤션 문서화
```

### type

`type`은 변경 파일의 종류가 아니라 변경 목적을 기준으로 선택한다.

| type | 사용 기준 |
| --- | --- |
| `feat` | 새로운 기능 또는 외부 동작 추가 |
| `fix` | 잘못된 동작이나 결함 수정 |
| `refactor` | 기능 변경 없는 코드 구조 개선 |
| `perf` | 처리량, 응답 시간, Redis 명령, 락 등 성능 개선 |
| `test` | 테스트만 추가하거나 수정 |
| `docs` | 문서만 변경 |
| `chore` | 제품 동작과 무관한 유지보수 작업 |
| `build` | Gradle, 의존성 또는 빌드 설정 변경 |
| `ci` | CI 워크플로우 변경 |
| `security` | 인증, 권한 또는 토큰 보안 강화 |
| `revert` | 기존 변경 되돌리기 |

### scope

`scope`는 변경의 주된 책임 영역을 나타낸다. 가장 작은 적절한 범위를 선택한다.

1. 한 실행 모듈에 국한되면 `queue-api` 또는 `queue-scheduler`를 사용한다.
2. 공통 Redis 규약 변경이면 `queue-redis`를 사용한다.
3. 여러 Queue 모듈에 걸친 변경이면 `queue`를 사용한다.
4. 배포나 저장소 공통 작업은 `deploy`, `ci`, `review`, `codex` 등 기존 범위를 사용한다.
5. 특정 범위를 정하기 어려운 저장소 전체 작업은 scope를 생략한다.

권장 scope:

```text
queue, queue-api, queue-scheduler, queue-redis, admission, redis, deploy, ci, review, codex
```

기존 scope로 표현할 수 있으면 새로운 scope를 임의로 만들지 않는다. 새 scope가 필요하면 실제 모듈이나 안정적인 하위 시스템 이름을 사용한다.

### 설명과 본문

- 설명은 한국어로 작성하고 마침표를 붙이지 않는다.
- `Redis`, `Lua`, `Cloudflare`, `Nginx` 같은 기술 고유명사는 원문 표기를 허용한다.
- `수정`, `개선`, `작업`처럼 대상이 드러나지 않는 표현만 사용하지 않는다.
- `추가`, `수정`, `분리`, `축소`, `최적화`처럼 변경 결과가 드러나게 작성한다.
- 하나의 커밋에는 하나의 목적만 포함한다. API, scheduler, Redis 규약의 독립적인 변경은 가능한 한 분리한다.

코드만 보고 이유를 알기 어려운 변경은 빈 줄 다음에 한국어 본문을 추가한다.

```text
perf(queue-scheduler): 입장 처리 락 범위 축소

Redis 조회와 계산을 락 밖에서 처리해 scheduler가
입장 순번을 전진시키는 임계 구역만 짧게 유지한다.
```

호환성을 깨는 변경은 type 또는 scope 뒤에 `!`를 붙이고 본문 하단에 `BREAKING CHANGE:`를 작성한다.

### PR

- PR 제목도 `<type>(<scope>): <한국어 설명>` 형식을 사용한다.
- type과 scope는 PR 전체의 주된 목적과 영향 범위를 기준으로 선택한다.
- 테스트와 문서가 함께 포함돼도 주된 목적이 성능 개선이면 `perf`를 사용한다.
- 서로 관계없는 기능, 버그 수정, 리팩터링이 섞이면 PR을 분리한다.
- PR 본문에는 Queue API, scheduler, Redis 키·TTL·Lua, admission token, 배포 영향과 검증 결과를 기록한다.

### AI 에이전트 작업 규칙

- 기존 히스토리를 추측만으로 모방하지 말고 이 문서의 type, scope, 언어 규칙을 우선 적용한다.
- type은 변경 목적, scope는 주된 책임 영역을 기준으로 선택한다.
- 여러 커밋을 만들 때는 작업 책임별로 분리하고 각각의 메시지를 독립적으로 작성한다.
- 사용자가 커밋을 명시적으로 요청하지 않으면 커밋하지 않는다.
- 이미 푸시한 커밋 메시지를 변경하려면 영향과 이력 변경을 설명하고 사용자 승인 후 진행한다.
- force push가 필요하면 원격이 예상한 상태일 때만 갱신하는 `--force-with-lease`를 사용한다.
