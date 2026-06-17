# Queue Azure VM Deployment

`ticket-queue`는 GitHub Actions에서 Docker 이미지를 빌드하고 Docker Hub에 push한 뒤, Azure VM에서 이미지를 pull해 Docker Compose로 재기동한다.

```text
client or Cloudflare -> nginx -> ticket-queue -> Redis
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
sudo chown -R "$USER:$USER" /opt/ticket-queue
```

GitHub Actions가 root가 아닌 사용자로 SSH 접속한다면 해당 사용자는 passwordless sudo로 `docker`를 실행할 수 있어야 한다.

## Server-Owned Compose

운영 `docker-compose.yml`은 VM의 `/opt/ticket-queue/docker-compose.yml`에서 직접 관리한다. GitHub Actions는 이 파일을 업로드하거나 덮어쓰지 않는다.

GitHub Actions가 업로드하는 파일은 아래 하나뿐이다.

```text
deploy/nginx/default.conf -> /opt/ticket-queue/nginx/default.conf
```

따라서 Datadog Agent, `DD_API_KEY`, `DD_SITE`, `JAVA_TOOL_OPTIONS` 같은 운영 인프라 설정은 VM의 `/opt/ticket-queue/docker-compose.yml`에 직접 반영한다.

## Environment

`deploy/env.example`을 기준으로 VM에 `/opt/ticket-queue/.env`를 만들고 애플리케이션 secret 값을 교체한다. 실제 `.env`는 커밋하지 않는다.

Datadog 설정은 core 서버와 동일하게 운영 `docker-compose.yml`에서 직접 관리한다.

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
-> VM의 /opt/ticket-queue/docker-compose.yml로 docker compose up -d --remove-orphans
```

## Verify

배포 후 VM에서 확인한다.

```bash
cd /opt/ticket-queue
sudo docker compose ps
curl -I http://localhost/api/v1/queue/performances/1/state
curl -I https://queue.oneticket.site/api/v1/queue/performances/1/state
```

public state 응답은 아래 헤더를 포함해야 한다.

```text
Cache-Control: public, max-age=1, s-maxage=1, stale-while-revalidate=5
```

Azure network security group은 `22`, `80`, 나중에 `443`만 연다. Redis `6379`와 queue `8090`은 직접 외부에 열지 않는다.
