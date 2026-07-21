# Queue AWS EC2 Deployment

`ticket-queue`는 GitHub Actions에서 Docker 이미지를 빌드하고 Docker Hub에 push한 뒤, AWS EC2에서 이미지를 pull해 Docker Compose로 재기동한다.

```text
client -> nginx -> /api/v1/queue/**/join, /enter -> ticket-queue -> Redis
client -> Cloudflare state endpoint -> cached /api/v1/queue/performances/*/state -> nginx -> ticket-queue -> Redis
```

`/join`과 `/enter`는 Cloudflare를 거치지 않고 Queue origin Nginx로 직접 들어온다. Cloudflare는 별도로 구성한 public state endpoint의 `/state` 조회에만 선택적으로 사용한다. 같은 DNS-only hostname을 Queue API와 state가 함께 사용하면 `/state`도 Cloudflare를 거치지 않는다.

## EC2 Setup

EC2에 Docker와 Compose plugin을 설치하고 배포 디렉터리를 만든다.

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

GitHub Actions가 root가 아닌 사용자로 SSH 접속한다면 해당 사용자는 passwordless sudo로 `docker`를 실행할 수 있어야 한다.

Cloudflare SSL/TLS Full (Strict) 모드를 유지하려면 origin nginx도 443 HTTPS를 제공해야 한다. EC2에는 Let's Encrypt 또는 Cloudflare Origin Certificate를 아래 경로에 준비한다. 인증서와 private key는 커밋하지 않는다.

```text
/etc/letsencrypt/live/queue.oneticket.site/fullchain.pem
/etc/letsencrypt/live/queue.oneticket.site/privkey.pem
```

Certbot HTTP-01 webroot를 사용할 때는 `/home/ubuntu/ticket-queue/certbot/www`를 challenge root로 사용한다.

## GitHub Actions 업로드 파일

GitHub Actions는 배포 시 아래 파일을 EC2의 `/home/ubuntu/ticket-queue` 아래로 업로드하고 덮어쓴다. 런타임 secret은 계속 EC2의 `/home/ubuntu/ticket-queue/.env`에만 둔다.

```text
deploy/docker-compose.yml -> /home/ubuntu/ticket-queue/docker-compose.yml
deploy/nginx/nginx.conf -> /home/ubuntu/ticket-queue/nginx/nginx.conf
deploy/nginx/default.conf -> /home/ubuntu/ticket-queue/nginx/default.conf
deploy/datadog/conf.d/redisdb.d/conf.yaml -> /home/ubuntu/ticket-queue/datadog/conf.d/redisdb.d/conf.yaml
```

따라서 Datadog Agent, `JAVA_TOOL_OPTIONS`, 운영 Docker Redis 연결 설정은 repository의 `deploy/docker-compose.yml`과 Datadog 설정 파일에서 관리한다. 운영 secret 값은 `.env`에만 둔다. 로컬 개발용 `docker-compose.local.yml`은 EC2에 업로드하지 않는다.

## Environment

`deploy/env.example`을 기준으로 EC2에 `/home/ubuntu/ticket-queue/.env`를 만들고 애플리케이션 secret 값을 교체한다. 실제 `.env`는 커밋하지 않는다.

Datadog 설정과 Docker Redis 연결값은 `deploy/docker-compose.yml`에서 관리한다.

운영 compose는 내부 전용 Redis service를 함께 띄운다. Queue Server는 별도 Spring profile 없이 compose 네트워크 안에서 `redis:6379` 단일 Redis에 연결하고, Redis `6379` 포트는 외부에 publish하지 않는다. Datadog Redis integration은 `DD_ENV`를 `env` 태그로 붙여 운영/로컬 모니터링이 섞이지 않게 한다.

로컬 Redis가 필요하면 repository root에서 아래 명령만 실행한다. 이 compose 파일은 Redis 컨테이너만 제공하고 Datadog Agent나 Queue Server를 띄우지 않는다.

```powershell
docker compose -f docker-compose.local.yml up -d redis
```

## GitHub Secrets

GitHub repository 또는 `aws-queue` environment에 아래 secret을 설정한다.

```text
DOCKER_USERNAME
DOCKER_PASSWORD
AWS_VM_HOST
AWS_VM_USER
AWS_VM_SSH_KEY
AWS_VM_PORT
```

SSH가 22 포트를 쓰면 `AWS_VM_PORT`는 생략할 수 있다.

Queue Server는 외부 GitHub Packages를 읽지 않는다. EC2 `.env`에는 Core access token 검증용 `JWT_SECRET`, `JWT_ISSUER`, `JWT_ACCESS_TOKEN_EXPIRATION_SECONDS`와 queue/admission token secret을 설정한다.

Workflow는 `master` push에서 실행되고, GitHub Actions에서 수동 실행도 가능하다.

## Deploy Flow

```text
master push
-> ./gradlew test bootJar
-> docker build
-> docker push {DOCKER_USERNAME}/ticket-queue:latest
-> ssh AWS EC2
-> docker pull
-> EC2의 /home/ubuntu/ticket-queue/docker-compose.yml로 docker compose up -d --remove-orphans
```

## Verify

배포 후 EC2에서 확인한다.

```bash
cd /home/ubuntu/ticket-queue
sudo docker compose ps
sudo ss -lntp | grep -E ':80|:443'
curl -I http://localhost/api/v1/queue/performances/1/state
curl -k -I https://localhost/api/v1/queue/performances/1/state
curl -I https://queue.oneticket.site/api/v1/queue/performances/1/state
```

origin 응답은 아래 헤더를 포함해야 한다.

```text
Cache-Control: no-store
X-Content-Type-Options: nosniff
```

state 전용 Cloudflare endpoint의 Cache Rule은 `/api/v1/queue/performances/*/state` GET 요청만 cache eligible로 두고, Edge TTL은 Cloudflare 설정에서 강제한다. `/join`과 `/enter`에는 이 규칙을 적용하지 않는다. state endpoint에 반복 요청했을 때 아래 값이 보이면 Cloudflare edge cache가 적용된 것이다.

```text
cf-cache-status: HIT
```

AWS security group은 `22`, `80`, `443`만 연다. Redis `6379`와 queue `8090`은 직접 외부에 열지 않는다.
