# BillGuard

AI-powered subscription auditor. BillGuard connects to a bank through Plaid, detects recurring charges with an AI classifier, and generates cancelation assistance.

## Stack

| Layer | Technology |
|---|---|
| Core API | Java 25, Spring Boot 4.1, Spring Security, Spring Data JPA, Flyway |
| AI service | Python 3.12, FastAPI, uv |
| Web app | SvelteKit |
| Database | PostgreSQL 16 |
| Auth | Auth0 RS256/JWKS |
| Runtime | Docker, Kubernetes (kind locally; EKS planned) |

The browser talks only to the core API on port 3001. The Python service listens internally on port 8000.

## Prerequisites

- Docker Desktop
- Temurin JDK 25 (Maven is supplied by the wrapper)
- Python 3.12 and uv
- Node.js 20+
- kind and kubectl for the Kubernetes workflow
- An Auth0 Single Page Application and API
- A Plaid Sandbox application
- cloudflared (only for receiving Plaid webhooks during local testing)

## Quick start with Docker Compose

```powershell
Copy-Item .env.example .env
# Set the Auth0 and Plaid values in .env.
# Generate TOKEN_ENCRYPTION_KEY with: openssl rand -base64 32

docker compose up --build
```

In a second terminal:

```powershell
Set-Location web-app
npm ci
npm run dev
```

Core API: http://localhost:3001

AI service: http://localhost:8000

Web app: http://localhost:5173

Flyway applies the database schema when `core-api` starts; there is no manual migration step. Compose starts a separate worker from the same core API image. Only that worker polls the Postgres job queue.

## Run services natively

```powershell
# Core API (requires a local PostgreSQL and environment variables from .env.example)
Set-Location core-api
.\mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=dev

# AI service
Set-Location ..\ai-service
uv sync --locked
uv run uvicorn app.main:app --reload --port 8000
```

## Plaid Sandbox setup

Set `PLAID_CLIENT_ID`, `PLAID_SECRET`, and a base64-encoded 32-byte `TOKEN_ENCRYPTION_KEY` in `.env`. Keep `PLAID_ENV=sandbox`.

For local webhooks, start a public tunnel before connecting an Item:

```powershell
cloudflared tunnel --url http://localhost:3001
```

Set `PLAID_WEBHOOK_URL` to the generated URL plus `/api/webhooks/plaid`, then restart the core API and worker. The webhook URL is attached when BillGuard creates the Link token.

The Phase 2 fixture is [fixtures/plaid/user_custom.json](fixtures/plaid/user_custom.json). In Plaid Link's Sandbox credential screen, use `user_custom` as the username and paste the minified fixture JSON as the password. This PowerShell command copies it in that form:

```powershell
Get-Content fixtures/plaid/user_custom.json -Raw |
  ConvertFrom-Json |
  ConvertTo-Json -Depth 20 -Compress |
  Set-Clipboard
```

The fixture includes fixed monthly, weekly, and annual streams plus a variable utility, an irregular near-miss, and one-off noise. After Link succeeds, the exchange creates a durable `sync_item` job; the worker imports transaction pages and advances the Plaid cursor atomically with each page.

Verification commands:

```powershell
Set-Location core-api
.\mvnw.cmd -B verify

Set-Location ..\ai-service
uv run ruff check .
uv run mypy app
uv run pytest

Set-Location ..\web-app
npm run check
npm run build
```

## kind workflow

```powershell
kind create cluster --name billguard
Copy-Item k8s/secrets.template.yaml k8s/secrets.yaml
# Fill in k8s/secrets.yaml before applying it.

kubectl apply -f k8s/namespaces.yaml
kubectl apply -f k8s/secrets.yaml
kubectl apply -f k8s/configmap.yaml
kubectl apply -f k8s/postgres.yaml
kubectl apply -f k8s/core-api-deployment.yaml
kubectl apply -f k8s/ai-service-deployment.yaml
kubectl apply -f k8s/worker-deployment.yaml
kubectl apply -f k8s/cronjob.yaml

kubectl port-forward -n billguard-dev svc/billguard-core-api-service 3001:80
```

Build and load `billguard-core-api:latest` and `billguard-ai-service:latest` into kind before applying their deployments. Each core API replica runs the same Flyway migrations safely under Flyway's PostgreSQL advisory lock.

## Environment variables

| Variable | Consumer | Notes |
|---|---|---|
| `DATABASE_HOST`, `DATABASE_PORT`, `DATABASE_NAME` | Core API | JDBC connection components |
| `DATABASE_USER`, `DATABASE_PASSWORD` | Core API | Database credentials |
| `DATABASE_URL` | Future Python/psql tooling | libpq URL; the Java service does not parse it |
| `AUTH0_DOMAIN`, `AUTH0_AUDIENCE` | Core API | Auth0 issuer and API audience |
| `FRONTEND_URL` | Core API | Allowed CORS origin; defaults to `http://localhost:5173` |
| `PORT` | Core API | Defaults to `3001` |
| `SPRING_PROFILES_ACTIVE` | Core API | Use `dev` locally and `prod` in Kubernetes |
| `LOCAL_LLM_MODE` | AI service | Future switch between Ollama and OpenAI |
| `OLLAMA_BASE_URL` | AI service | Future local model endpoint |
| `OPENAI_API_KEY` | AI service | Unused until Phase 3 |
| `AI_SERVICE_URL` | Core API | In-cluster AI service URL for Phase 3 |
| `PLAID_CLIENT_ID`, `PLAID_SECRET` | Core API/worker | Plaid application credentials |
| `PLAID_ENV` | Core API/worker | `sandbox` locally; only `production` is also accepted |
| `PLAID_PRODUCTS`, `PLAID_COUNTRY_CODES` | Core API/worker | Comma-separated Link configuration |
| `PLAID_WEBHOOK_URL` | Core API | Public `/api/webhooks/plaid` URL attached to Link tokens |
| `TOKEN_ENCRYPTION_KEY` | Core API/worker/CronJob | Required base64-encoded 32-byte AES key |
| `BILLGUARD_WORKER_ENABLED` | Core API/worker | False on API replicas; true only on queue workers |

Web app variables live in `web-app/.env.local`: `VITE_AUTH0_DOMAIN`, `VITE_AUTH0_CLIENT_ID`, `VITE_AUTH0_AUDIENCE`, and `VITE_API_URL`.

## Project structure

```text
billguard/
|-- core-api/                 # Java/Spring API, Plaid ingestion, jobs, Flyway schema
|-- ai-service/               # Python/FastAPI internal classifier contract
|-- web-app/                  # SvelteKit browser application
|-- fixtures/plaid/           # Deterministic Plaid Sandbox custom user
|-- k8s/
|   |-- core-api-deployment.yaml
|   |-- ai-service-deployment.yaml
|   |-- worker-deployment.yaml
|   |-- cronjob.yaml
|   |-- postgres.yaml
|   |-- configmap.yaml
|   `-- secrets.template.yaml
|-- docker-compose.yml
`-- .github/workflows/ci.yml
```

## Kubernetes concepts used

| Manifest | Concepts |
|---|---|
| `core-api-deployment.yaml` | Deployment, RollingUpdate, initContainer, three probe types, resources, ConfigMap/Secret |
| `ai-service-deployment.yaml` | Internal ClusterIP, RollingUpdate, liveness/readiness probes, separate resource profile |
| `worker-deployment.yaml` | Pull-based Deployment without a Service, queue concurrency, probes and resource bounds |
| `cronjob.yaml` | Scheduled headless enqueue job, forbidden overlap, retry/history policies |
| `postgres.yaml` | Stateful storage and internal database service |
| `namespaces.yaml` | Environment isolation |

Local 768 MiB container measurements on 2026-08-07 showed a 4.33-second cold Spring startup in both modes and idle memory of 241.9 MiB without compact object headers versus 232.2 MiB with them. The 384 MiB request and 768 MiB limit therefore retain headroom, but the observed idle saving was about 4%, not the theoretical 20%. Re-profile under Phase 2 load before tightening. A single t3.medium will likely be tight once the worker is added - it is a second JVM with its own heap, though the Postgres-backed job queue avoids a separate broker workload.

See [PHASES.md](PHASES.md) for the roadmap.
