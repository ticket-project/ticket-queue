# Queue Azure VM Deployment

이 배포 구성은 GitHub Actions에서 `ticket-queue` Docker 이미지를 Docker Hub에 push하고, Azure VM에서는 이미지를 pull한 뒤 Docker Compose로 재기동한다.

```text
client or Cloudflare -> nginx -> ticket-queue -> Redis
                    \-> /queue-state/** static JSON
```

## VM Setup

OS에 설치된 Nginx가 있으면 Docker Nginx와 80 포트가 충돌하므로 중지한다.

```bash
sudo systemctl stop nginx || true
sudo systemctl disable nginx || true
sudo apt update
sudo apt install -y docker.io docker-compose-plugin
sudo systemctl enable docker
sudo systemctl start docker
sudo mkdir -p /opt/ticket-queue/nginx /opt/ticket-queue/public-state/queue-state/performances
sudo chown -R "$USER:$USER" /opt/ticket-queue
```

GitHub Actions가 root가 아닌 사용자로 SSH 접속한다면 해당 사용자는 passwordless sudo로 `docker`를 실행할 수 있어야 한다.

`deploy/docker-compose.yml`은 VM의 `/opt/ticket-queue/docker-compose.yml`에, `deploy/nginx/default.conf`는 `/opt/ticket-queue/nginx/default.conf`에 둔다.

`deploy/env.example`을 기준으로 VM에 `/opt/ticket-queue/.env`를 만들고 모든 secret 값을 교체한다. 실제 `.env`는 커밋하지 않는다.

## GitHub Secrets

GitHub repository 또는 `azure-queue` environment에 아래 secret을 설정한다.

```text
DOCKER_USERNAME
DOCKER_PASSWORD
TICKET_COMMON_READ_USER
TICKET_COMMON_READ_TOKEN
AZURE_VM_HOST
AZURE_VM_USER
AZURE_VM_SSH_KEY
AZURE_VM_PORT
```

SSH가 22 포트를 쓰면 `AZURE_VM_PORT`는 생략할 수 있다.

Gradle build는 GitHub Packages를 `GITHUB_PACKAGES_USER`, `GITHUB_PACKAGES_TOKEN`으로 읽는다. `ticket-common` package가 private이면 `ticket-queue` repository에 package read 권한을 부여하거나, `read:packages` 권한이 있는 token을 `TICKET_COMMON_READ_TOKEN`에 설정한다.

Workflow는 `master` push에서 실행되고, GitHub Actions에서 수동 실행도 가능하다.

## Deploy Flow

```text
master push
-> ./gradlew test bootJar
-> docker build
-> docker push {DOCKER_USERNAME}/ticket-queue:latest
-> ssh Azure VM
-> docker pull
-> docker compose up -d --remove-orphans
```

## Verify

배포 후 VM에서 확인한다.

```bash
cd /opt/ticket-queue
sudo docker compose ps
curl -I http://localhost/queue-state/performances/1.json
```

public state 응답은 아래 헤더를 포함해야 한다.

```text
Cache-Control: public, max-age=1, s-maxage=1, stale-while-revalidate=5
```

Azure network security group은 `22`, `80`, 나중에 `443`만 연다. Redis `6379`와 queue `8090`은 직접 외부에 열지 않는다.
