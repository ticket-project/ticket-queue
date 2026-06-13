# Queue Azure VM Deployment

이 배포 구성은 Azure VM에서 `ticket-queue`, Redis, Nginx만 실행합니다.

```text
nginx -> ticket-queue -> redis
```

GitHub Actions workflow는 Spring Boot jar를 빌드하고, Compose 파일과 함께 `/opt/ticket-queue`로 복사한 뒤 stack을 재시작합니다.

## VM Setup

OS에 설치된 Nginx가 있으면 Docker Nginx와 80 포트가 충돌하므로 중지합니다.

```bash
sudo systemctl stop nginx || true
sudo systemctl disable nginx || true
sudo apt update
sudo apt install -y docker.io docker-compose-plugin
sudo systemctl enable docker
sudo systemctl start docker
sudo mkdir -p /opt/ticket-queue
sudo chown -R "$USER:$USER" /opt/ticket-queue
```

`deploy/env.example`을 기준으로 VM에 `/opt/ticket-queue/.env`를 만들고 모든 secret 값을 교체합니다. 실제 `.env`는 커밋하지 않습니다.

## GitHub Secrets

GitHub repository 또는 `azure-queue` environment에 아래 secret을 설정합니다.

```text
AZURE_VM_HOST
AZURE_VM_USER
AZURE_VM_SSH_KEY
AZURE_VM_PORT
```

SSH가 22 포트를 쓰면 `AZURE_VM_PORT`는 생략할 수 있습니다.

Gradle build는 GitHub Packages를 `GITHUB_ACTOR`, `GITHUB_TOKEN`으로 읽습니다. common package가 private이면 이 repository에 package read 권한을 부여하거나 package read token을 설정해야 합니다.

Workflow는 `main`, `master` push에서 실행되고, GitHub Actions에서 수동 실행도 가능합니다.

## Verify

배포 후 VM에서 확인합니다.

```bash
cd /opt/ticket-queue
docker compose ps
curl -I http://localhost/queue-state/performances/1.json
```

public state 응답은 아래 헤더를 포함해야 합니다.

```text
Cache-Control: public, max-age=1, s-maxage=1, stale-while-revalidate=5
```

Azure network security group은 `22`, `80`, 나중에 `443`만 엽니다. Redis `6379`와 queue `8090`은 직접 외부에 열지 않습니다.
