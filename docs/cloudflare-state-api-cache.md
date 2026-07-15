# Cloudflare Queue State API Cache

이 문서는 public queue state를 정적 파일로 배포하지 않고, Queue API의 `/state` 응답을 Cloudflare에서 짧게 캐시하는 운영 기준이다.

## 목표 구조

```text
Join/Enter write path
  User
    -> Nginx origin
       -> /api/v1/queue/**/join, /enter no-store proxy
    -> ticket-queue
    -> Redis

Public state read path
  User
    -> Cloudflare state endpoint
       -> /api/v1/queue/performances/*/state 1-5 second edge cache
    -> Nginx origin
       -> /api/v1/queue/** no-store proxy
    -> ticket-queue
    -> Redis
```

`/state` 응답은 사용자별 정보를 포함하지 않아야 한다. `Authorization`, cookie, query string 없이 모든 사용자가 같은 응답을 공유한다.

`/join`과 `/enter`는 Cloudflare를 거치지 않는다. 두 요청은 Queue API endpoint를 통해 Nginx origin으로 직접 들어온다. Cloudflare DNS proxy는 hostname 단위로 적용되므로, `/state`만 Cloudflare에 태우려면 Queue API origin과 state endpoint를 서로 다른 hostname 또는 별도 CDN/static endpoint로 분리해야 한다.

```text
Queue API endpoint
  -> DNS-only/direct origin
  -> /join, /enter

Queue state endpoint
  -> Cloudflare proxied
  -> /state
```

## Nginx

origin은 API를 proxy하되 브라우저와 일반 CDN 정책 기준으로는 캐시 금지 응답을 내려준다.

```nginx
server {
    listen 80;
    server_name _;

    location /api/v1/queue/ {
        proxy_pass http://queue:8090;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_hide_header Cache-Control;
        add_header Cache-Control "no-store" always;
        add_header X-Content-Type-Options "nosniff" always;
    }

    location / {
        return 404;
    }
}
```

Cloudflare 캐시는 origin의 `Cache-Control`을 따르는 방식이 아니라 Cache Rule에서 `/state` 경로만 명시적으로 override한다.

## Cloudflare

Cache Rule:

```text
조건:
  hostname equals <Cloudflare가 프록시하는 state 전용 hostname>
  uri path starts with /api/v1/queue/performances/
  uri path ends with /state
  request method equals GET

설정:
  cache eligibility: eligible for cache
  edge TTL: ignore cache-control header and use this TTL
  input TTL: 1-5 seconds
  browser TTL: respect origin 또는 짧은 TTL
  custom cache key: query string 제외
```

`/api/v1/queue/**/join`, `/api/v1/queue/**/enter`는 이 Cloudflare endpoint를 사용하지 않는다. 해당 경로의 rate limit과 접근 제어가 필요하면 Queue origin의 Nginx, 애플리케이션 또는 별도 인프라 계층에서 구성한다.

## Frontend

대기 화면은 아래 URL을 조회한다.

```text
https://<state-endpoint>/api/v1/queue/performances/{performanceId}/state
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
curl -I https://<state-endpoint>/api/v1/queue/performances/1/state
curl https://<state-endpoint>/api/v1/queue/performances/1/state
```

Cloudflare 확인:

```text
cf-cache-status: HIT
```

Origin offload 확인:

```text
Cloudflare 요청 수는 증가한다.
queue-api /state origin request count는 Cloudflare 요청 수만큼 증가하지 않는다.
Redis read count는 CDN cache hit ratio에 비례해 낮아진다.
```

부하 테스트 결과에는 최소한 아래 값을 포함한다.

```text
total requests
p95 latency
error rate
Cloudflare cache hit ratio
queue-api origin request count
Redis read count
```
