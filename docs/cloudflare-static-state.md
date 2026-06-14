# Cloudflare Static Queue State

이 문서는 DigitalOcean 환경에서 public queue state를 정적 파일로 배포하는 1차 운영 기준이다.

## 목표 구조

```text
User
  -> Cloudflare
     -> /queue-state/** 1 second cache
     -> /api/v1/queue/** no cache, rate limit
  -> Nginx origin
     -> /queue-state/** static files
     -> /api/** ticket-gateway
  -> ticket-gateway
  -> ticket-queue
  -> Redis
```

`/queue-state/**`는 gateway, ticket-queue, Redis를 타지 않아야 한다.

## Queue Server

운영 환경변수:

```bash
QUEUE_PUBLIC_STATE_OUTPUT_DIRECTORY=/var/www/ticket-queue-public
QUEUE_PUBLIC_STATE_PATH_TEMPLATE=queue-state/performances/{performanceId}.json
QUEUE_SCHEDULER_ENABLED=true
```

생성 파일:

```text
/var/www/ticket-queue-public/queue-state/performances/1.json
```

응답 예:

```json
{"performanceId":1,"status":"OPEN","admittedUntilSeq":152000,"tailSeq":1000000,"refreshAfterMs":5000,"serverTimeMillis":1790000000000}
```

## Nginx

예시:

```nginx
server {
    listen 80;
    server_name queue.example.com;

    root /var/www/ticket-queue-public;

    location /queue-state/ {
        default_type application/json;
        try_files $uri =404;

        add_header Cache-Control "public, max-age=1, s-maxage=1, stale-while-revalidate=5" always;
        add_header X-Content-Type-Options "nosniff" always;
        add_header Access-Control-Allow-Origin "https://example.com" always;
        access_log /var/log/nginx/queue-state-access.log;
    }

    location /api/ {
        proxy_pass http://127.0.0.1:8000;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }

    location / {
        return 404;
    }
}
```

확인:

```bash
sudo nginx -t
sudo systemctl reload nginx
curl -I http://queue.example.com/queue-state/performances/1.json
```

확인할 헤더:

```text
Content-Type: application/json
Cache-Control: public, max-age=1, s-maxage=1, stale-while-revalidate=5
Set-Cookie: 없음
```

## Cloudflare

DNS:

```text
queue.example.com -> Droplet public IP
Proxy status: Proxied
```

Cache Rule:

```text
조건:
  hostname equals queue.example.com
  uri path starts with /queue-state/

설정:
  cache eligibility: eligible for cache
  edge TTL: 1 second
  browser TTL: respect origin 또는 1 second
  query string: ignore
```

`/api/v1/queue/**/join`, `/api/v1/queue/**/enter`는 캐시 금지와 rate limit 대상이다.

## Frontend

프론트 환경변수:

```bash
NEXT_PUBLIC_QUEUE_PUBLIC_STATE_BASE_URL=https://queue.example.com
```

대기 화면은 다음 URL을 조회한다.

```text
https://queue.example.com/queue-state/performances/{performanceId}.json
```

fetch 조건:

```text
credentials: omit
Authorization header 없음
Cookie 의존 없음
```

## 검증 기준

기능 확인:

```bash
curl -I https://queue.example.com/queue-state/performances/1.json
curl https://queue.example.com/queue-state/performances/1.json
```

Cloudflare 확인:

```text
cf-cache-status: HIT
```

Origin offload 확인:

```text
Cloudflare 요청 수는 증가한다.
Nginx /queue-state/ access log는 요청 수만큼 증가하지 않는다.
ticket-gateway, ticket-queue, Redis에는 /queue-state/ 조회가 들어가지 않는다.
```

부하 테스트 결과 표에는 최소한 아래 값을 남긴다.

```text
total requests
p95 latency
error rate
Cloudflare cache hit ratio
Nginx origin request count
Spring Boot request count
Redis request count
```
