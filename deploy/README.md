# Queue Azure VM Deployment

`ticket-queue`는 GitHub Actions에서 Docker 이미지를 빌드하고 Docker Hub에 push한 뒤, Azure VM에서 이미지를 pull해 Docker Compose로 재기동한다.

```text
client -> Cloudflare cached /api/v1/queue/performances/*/state -> nginx -> ticket-queue -> Redis
client -> Cloudflare bypass/no-store /api/v1/queue/** -> nginx -> ticket-queue -> Redis
```

## VM Setup

VM에 Docker와 Compose plugin을 설치하고 배포 디렉터리를 만든다.

```bash
sudo systemctl stop nginx || true
sudo systemctl disable nginx || true
sudo apt update
sudo apt install -y docker.io docker-compose-plugin
sudo systemctl enable docker
sudo systemctl start docker
sudo mkdir -p /opt/ticket-queue/nginx
sudo mkdir -p /opt/ticket-queue/certbot/www
sudo mkdir -p /opt/ticket-queue/datadog/conf.d/redisdb.d
sudo chown -R "$USER:$USER" /opt/ticket-queue
```

GitHub Actions가 root가 아닌 사용자로 SSH 접속한다면 해당 사용자는 passwordless sudo로 `docker`를 실행할 수 있어야 한다.

Cloudflare SSL/TLS Full (Strict) 모드를 유지하려면 origin nginx도 443 HTTPS를 제공해야 한다. VM에는 Let's Encrypt 또는 Cloudflare Origin Certificate를 아래 경로에 준비한다. 인증서와 private key는 커밋하지 않는다.

```text
/etc/letsencrypt/live/queue.oneticket.site/fullchain.pem
/etc/letsencrypt/live/queue.oneticket.site/privkey.pem
```

Certbot HTTP-01 webroot를 사용할 때는 `/opt/ticket-queue/certbot/www`를 challenge root로 사용한다.

## GitHub Actions 업로드 파일

GitHub Actions는 배포 시 아래 파일을 VM의 `/opt/ticket-queue` 아래로 업로드하고 덮어쓴다. 런타임 secret은 계속 VM의 `/opt/ticket-queue/.env`에만 둔다.

```text
deploy/docker-compose.yml -> /opt/ticket-queue/docker-compose.yml
deploy/nginx/default.conf -> /opt/ticket-queue/nginx/default.conf
deploy/datadog/conf.d/redisdb.d/conf.yaml -> /opt/ticket-queue/datadog/conf.d/redisdb.d/conf.yaml
```

따라서 Datadog Agent, `JAVA_TOOL_OPTIONS`, Redis 연동 설정은 repository의 `deploy/docker-compose.yml`과 Datadog 설정 파일에서 관리한다. 운영 secret 값은 `.env`에만 둔다.

## Environment

`deploy/env.example`을 기준으로 VM에 `/opt/ticket-queue/.env`를 만들고 애플리케이션 secret 값을 교체한다. 실제 `.env`는 커밋하지 않는다.

Datadog과 Managed Redis 설정은 `deploy/docker-compose.yml`이 참조하는 환경변수로 주입한다.

## GitHub Secrets

GitHub repository 또는 `azure-queue` environment에 아래 secret을 설정한다.

```text
DOCKER_USERNAME
DOCKER_PASSWORD
AZURE_VM_HOST
AZURE_VM_USER
AZURE_VM_SSH_KEY
AZURE_VM_PORT
```

SSH가 22 포트를 쓰면 `AZURE_VM_PORT`는 생략할 수 있다.

Queue Server는 외부 GitHub Packages를 읽지 않는다. VM `.env`에는 Core access token 검증용 `JWT_SECRET`, `JWT_ISSUER`, `JWT_ACCESS_TOKEN_EXPIRATION_SECONDS`와 queue/admission token secret을 설정한다.

Workflow는 `master` push에서 실행되고, GitHub Actions에서 수동 실행도 가능하다.

## Deploy Flow

```text
master push
-> ./gradlew test bootJar
-> docker build
-> docker push {DOCKER_USERNAME}/ticket-queue:latest
-> ssh Azure VM
-> docker pull
-> VM의 /opt/ticket-queue/docker-compose.yml로 docker compose up -d --remove-orphans
```

## Verify

배포 후 VM에서 확인한다.

```bash
cd /opt/ticket-queue
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

Cloudflare Cache Rule은 `/api/v1/queue/performances/*/state` GET 요청만 cache eligible로 두고, Edge TTL은 Cloudflare 설정에서 강제한다. 반복 요청에서 아래 값이 보이면 Cloudflare edge cache가 적용된 것이다.

```text
cf-cache-status: HIT
```

Azure network security group은 `22`, `80`, `443`만 연다. Redis `6379`와 queue `8090`은 직접 외부에 열지 않는다.
