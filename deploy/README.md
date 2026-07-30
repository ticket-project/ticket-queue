# Queue AWS EC2 배포

이 배포는 `queue-api`와 `queue-scheduler`를 별도 Docker 이미지와 별도 프로세스로 실행합니다. 두 프로세스는 같은 Redis를 사용하지만 역할과 확장 단위가 다릅니다.

```text
client -> nginx -> queue-api ----------+
                                        +-> Redis
                    queue-scheduler ----+
```

- `queue-api`: `8090`을 Nginx에 제공하며 외부 요청을 처리합니다.
- `queue-scheduler`: 외부 요청을 받지 않습니다. 내부 헬스/메트릭용 `8091`만 Compose 네트워크에 노출합니다.
- `redis`: 현재 EC2 Compose에서 실행합니다. ECS 전환 시 두 서비스가 같은 managed Redis endpoint를 보도록 변경합니다.
- `datadog-agent`: 두 애플리케이션을 서로 다른 service tag로 수집합니다.

`/join`과 `/enter`는 Queue origin Nginx로 직접 요청합니다. Cloudflare 캐시는 public `/state` 조회에만 선택적으로 사용하며 자세한 규칙은 `docs/cloudflare-state-api-cache.md`를 참고합니다.

## EC2 준비

EC2에 Docker와 Compose plugin을 설치하고 배포 디렉터리를 만듭니다.

```bash
sudo systemctl stop nginx || true
sudo systemctl disable nginx || true
sudo apt update
sudo apt install -y docker.io docker-compose-plugin
sudo systemctl enable docker
sudo systemctl start docker
sudo mkdir -p /home/ubuntu/datadog-agent/run
sudo chown -R "$USER:$USER" /home/ubuntu/datadog-agent
sudo mkdir -p /home/ubuntu/ticket-queue/nginx
sudo mkdir -p /home/ubuntu/ticket-queue/certbot/www
sudo mkdir -p /home/ubuntu/ticket-queue/datadog/conf.d/redisdb.d
sudo chown -R "$USER:$USER" /home/ubuntu/ticket-queue
```

GitHub Actions가 root가 아닌 사용자로 SSH 접속한다면 해당 사용자는 passwordless sudo로 `docker`를 실행할 수 있어야 합니다.

Cloudflare SSL/TLS Full (Strict) 모드를 사용하려면 origin 인증서를 다음 경로에 준비합니다.

```text
/etc/letsencrypt/live/queue.oneticket.site/fullchain.pem
/etc/letsencrypt/live/queue.oneticket.site/privkey.pem
```

## 서버 파일과 환경변수

Actions가 다음 파일을 `/home/ubuntu/ticket-queue` 아래에 업로드합니다. 실제 secret은 업로드하지 않고 서버의 `.env`에만 둡니다.

```text
deploy/docker-compose.yml -> /home/ubuntu/ticket-queue/docker-compose.yml
deploy/nginx/nginx.conf -> /home/ubuntu/ticket-queue/nginx/nginx.conf
deploy/nginx/default.conf -> /home/ubuntu/ticket-queue/nginx/default.conf
deploy/datadog/conf.d/redisdb.d/conf.yaml -> /home/ubuntu/ticket-queue/datadog/conf.d/redisdb.d/conf.yaml
```

`deploy/env.example`을 기준으로 `.env`를 만듭니다.

API 전용 secret은 `JWT_SECRET`, `QUEUE_TOKEN_SECRET`, `ADMISSION_TOKEN_SECRET_KEY`, `QUEUE_COMPLETION_SECRET`입니다. Compose의 `queue` 서비스만 `.env` 전체를 읽고, `scheduler` 서비스에는 이 값들을 전달하지 않습니다. 스케줄러에는 Redis 주소, 입장 정책, 실행 간격, 관측 설정만 전달합니다. 이 경계를 유지하는 이유는 스케줄러 침해 시 API 서명 키까지 노출되는 것을 막기 위해서입니다.

주요 스케줄러 환경변수:

```text
QUEUE_MAX_ACTIVE_SESSIONS_PER_PERFORMANCE=5000
QUEUE_MAX_ADMIT_PER_SECOND_PER_PERFORMANCE=500
QUEUE_ADVANCE_INTERVAL_MS=1000
```

로컬 Redis만 실행하려면 저장소 루트에서 다음 명령을 사용합니다.

```powershell
docker compose -f docker-compose.local.yml up -d redis
```

## GitHub Secrets

GitHub repository의 `aws-queue` environment에 다음 secret을 설정합니다.

```text
DOCKER_USERNAME
DOCKER_PASSWORD
AWS_VM_HOST
AWS_VM_USER
AWS_VM_SSH_KEY
AWS_VM_PORT
```

`AWS_VM_PORT`는 SSH가 22번 포트를 사용한다면 생략할 수 있습니다.

Queue Server는 외부 GitHub Packages를 읽지 않는다. EC2 `.env`에는 Core access token 검증용 `JWT_SECRET`, `JWT_ISSUER`, `JWT_ACCESS_TOKEN_EXPIRATION_SECONDS`, queue/admission token secret, Core와 공유하는 `QUEUE_COMPLETION_SECRET`, 측정된 입장률·active·burst 한도를 설정한다.

Workflow는 `master` push에서 실행되고, GitHub Actions에서 수동 실행도 가능하다.

## 배포 흐름

```text
master push
-> root/API/scheduler/Redis 모듈 테스트
-> queue-api.jar + queue-scheduler.jar 생성
-> ticket-queue-api:{commit SHA} 이미지 push
-> ticket-queue-scheduler:{commit SHA} 이미지 push
-> EC2에서 두 SHA 이미지 pull
-> 같은 SHA의 두 이미지를 docker compose up -d로 교체
```

`latest` 태그도 발행하지만 실제 배포에는 커밋 SHA 태그를 사용합니다. 왜냐하면 한쪽 이미지만 새 버전으로 바뀌면 Redis key/Lua 규약이 어긋날 수 있기 때문입니다.

## 확인

```bash
cd /home/ubuntu/ticket-queue
sudo docker compose ps
sudo docker compose logs --tail=100 queue scheduler
sudo ss -lntp | grep -E ':80|:443|:8090'
curl -I http://localhost/api/v1/queue/performances/1/state
curl -k -I https://localhost/api/v1/queue/performances/1/state
```

스케줄러의 `8091`은 호스트에 publish하지 않으므로 외부에서 접근되지 않아야 합니다. 컨테이너 내부 상태는 다음처럼 확인할 수 있습니다.

```bash
sudo docker compose exec scheduler wget -qO- http://localhost:8091/actuator/health
```

origin `/state` 응답에는 다음 헤더가 있어야 합니다.

```text
Cache-Control: no-store
X-Content-Type-Options: nosniff
```

AWS security group은 `22`, `80`, `443`만 엽니다. Redis `6379`, API `8090`, 스케줄러 `8091`은 직접 외부에 노출하지 않습니다.

## ECS 전환 시

현재 모듈과 이미지는 그대로 사용하고 인프라만 다음처럼 바꾸면 됩니다.

- `queue-api`: ECS service, 요청량 기준 독립 오토스케일링
- `queue-scheduler`: 별도 ECS service, 기본 1 task에서 시작
- Redis: ElastiCache 같은 managed Redis로 교체
- 두 ECS service에 동일한 Redis endpoint와 동일한 배포 SHA 사용
- scheduler security group은 Redis와 관측 경로만 허용

여러 scheduler task를 실행할 수는 있지만 처리량이 task 수만큼 선형 증가하지는 않습니다. 회차별 Redis 분산 락이 중복 전진을 막기 때문에, 우선 1 task로 운영하고 가용성이나 서로 다른 회차 병렬 처리 필요가 확인될 때 늘리는 편이 안전합니다.
