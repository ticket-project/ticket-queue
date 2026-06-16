# Cloudflare Cached Queue State

public queue state는 정적 파일로 배포하지 않습니다. Queue Server의 `/state` API를 nginx가 프록시하고, nginx/Cloudflare가 짧게 캐시합니다.

## 목표 구조

```text
User
  -> Cloudflare
     -> /api/v1/queue/performances/{id}/state 1 second cache
     -> /api/v1/queue/** other paths no cache / rate limit
  -> Nginx origin
     -> cached state API proxy
     -> queue service
  -> ticket-queue
  -> Redis
```

캐시 대상은 아래 경로 하나입니다.

```text
/api/v1/queue/performances/{performanceId}/state
```

## Queue Server

운영 환경변수 예시:

```bash
APP_QUEUE_SCHEDULER_ENABLED=true
```

응답 예시:

```json
{"performanceId":1,"status":"OPEN","admittedUntilSeq":152000,"tailSeq":1000000,"refreshAfterMs":5000,"serverTimeMillis":1790000000000}
```

## Nginx

예시:

```nginx
server {
    listen 80;
    server_name queue.example.com;

    location ~ ^/api/v1/queue/performances/[0-9]+/state$ {
        proxy_pass http://queue:8090;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_hide_header Cache-Control;
        add_header Cache-Control "public, max-age=1, s-maxage=1, stale-while-revalidate=5" always;
        add_header X-Content-Type-Options "nosniff" always;
    }

    location /api/v1/queue/ {
        proxy_pass http://queue:8090;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        add_header Cache-Control "no-store" always;
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
curl -I http://queue.example.com/api/v1/queue/performances/1/state
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
queue.example.com -> VM public IP
Proxy status: Proxied
```

Cache Rule:

```text
조건:
  hostname equals queue.example.com
  uri path matches ^/api/v1/queue/performances/[0-9]+/state$

설정:
  cache eligibility: eligible for cache
  edge TTL: 1 second
  browser TTL: respect origin 또는 1 second
  query string: ignore
```

`/api/v1/queue/**/join`, `/api/v1/queue/**/enter`는 캐시 금지와 rate limit 대상입니다.

## Frontend

프론트 환경변수 예시:

```bash
NEXT_PUBLIC_QUEUE_PUBLIC_STATE_BASE_URL=https://queue.example.com
```

대기 화면은 다음 URL을 조회합니다.

```text
https://queue.example.com/api/v1/queue/performances/{performanceId}/state
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
curl -I https://queue.example.com/api/v1/queue/performances/1/state
curl https://queue.example.com/api/v1/queue/performances/1/state
```

Cloudflare 확인:

```text
cf-cache-status: HIT
```

부하 테스트 결과 표에는 최소 아래 값을 남깁니다.

```text
total requests
p95 latency
error rate
Cloudflare cache hit ratio
Nginx origin request count
Spring Boot request count
Redis request count
```
