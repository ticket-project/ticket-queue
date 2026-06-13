# ticket-queue
티켓 대기열 서버

## Azure VM 배포

`deploy/`와 `.github/workflows/deploy-queue.yml`은 Azure VM에서 `ticket-queue`, Redis, Nginx를 Docker Compose로 실행하기 위한 배포 구성입니다.

```text
client or Cloudflare -> nginx -> ticket-queue -> Redis
                    \-> /queue-state/** static JSON
```

실제 secret은 VM의 `/opt/ticket-queue/.env`에만 둡니다. GitHub Actions는 jar와 Compose 파일을 VM에 복사한 뒤 `docker compose up -d --remove-orphans`를 실행합니다.

VM 준비와 GitHub Secrets 목록은 `deploy/README.md`를 확인합니다.
