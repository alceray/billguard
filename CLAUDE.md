# BillGuard - Project Memory

## What this is

BillGuard is a recurring-charge auditor. It connects to bank accounts through Plaid, detects recurring charges, categorizes them into subscriptions and bills, and generates cancelation assistance. Ray Lin is building it as a full-stack portfolio project for fintech and infrastructure engineering roles.

Two product principles constrain every phase, both chosen because a commercial incumbent structurally cannot offer them: BillGuard is **self-hostable** on the user's own infrastructure with their own Plaid credentials, and it has **no lock-in** - an ICS feed, webhooks, CSV, and a documented REST API all move data out. See `PHASES.md`.

Everything recurring is detected and shown, including rent and loan payments, so the monthly total reflects what the user actually owes. Actionability decides what the UI offers rather than what exists: Subscriptions can be cancelled, Bills can be switched or renegotiated, Fixed charges are tracked only. The only exclusions are income, self-transfers, and credit card payments - the last because the card's own charges are already counted and the payment would double them.

## Current phase

Phase 3 recurring-charge detection is implemented; real Plaid Sandbox and live-cluster validation remain. It persists Plaid PFC data, detects and categorizes streams in queued Java workers, uses an optional local Ollama classifier, and exposes user-correctable recurring-charge API/UI surfaces. See `PHASES.md`.

## Stack

| Layer | Choice | Notes |
|---|---|---|
| Core API | Java 25 + Spring Boot 4.1 | Spring MVC, Security resource server, Data JPA, Flyway |
| AI service | Python 3.12 + FastAPI | Internal-only service; managed with uv |
| Web app | Svelte + SvelteKit | `@sveltejs/adapter-node` for container deployment |
| Database | PostgreSQL 16 | Flyway in `core-api` is the only schema owner |
| Auth | Auth0 | RS256/JWKS with issuer and audience validation |
| Containers | Docker multi-stage builds | Non-root UID/GID 1001 |
| Orchestration | Kubernetes | kind locally, k3s on one small VPS in production; EKS as a time-boxed exercise. Manifests in `k8s/` |
| CI | GitHub Actions | Java verify, Python lint/type/test, web app check/build |
| AI/LLM | Ollama, 1-1.5B quantized | Classification only, never generation. Runs on the 4 GiB host; no API key required. `LOCAL_LLM_MODE=false` selects deterministic rules; hosted OpenAI support is not implemented |
| Banking | Plaid | Link, sync, signed webhooks, and Sandbox fixture implemented in Phase 2 |

## Project structure

```text
billguard/
|-- core-api/
|   |-- src/main/java/dev/billguard/coreapi/
|   |   |-- auth/             # Controller, service, repository, entity, DTOs
|   |   |-- common/           # HttpException and compatible error handlers
|   |   |-- config/           # Security, Plaid/AI clients, encryption, worker properties
|   |   |-- detection/        # Eligibility, category rules, recurrence arithmetic, AI contract
|   |   |-- health/           # /health and database-aware /ready
|   |   |-- jobs/             # Postgres queue, worker, daily enqueue runner
|   |   |-- plaid/            # Link, exchange, sync, webhooks, accounts, transactions
|   |   `-- recurring/        # Auth-scoped unified charges and feedback metrics
|   `-- src/main/resources/db/migration/ # Flyway schema history
|-- ai-service/
|   |-- app/                  # FastAPI contract, Ollama adapter, fallback rules, metrics
|   |-- evals/                # Labelled classification cases
|   `-- tests/                # ASGI contract and degradation tests
|-- web-app/                  # SvelteKit UI and Auth0 SPA client
|-- fixtures/plaid/           # Deterministic user_custom Sandbox data
|-- k8s/                      # Deployments, services, config and secret template
|-- docs/                     # DEVELOPMENT.md: setup, Auth0/Plaid config, kind, env vars
|-- docker-compose.yml
`-- .github/workflows/ci.yml
```

## Critical conventions

### Core API

- Keep controllers thin. Domain flow belongs in a service; database access belongs in a Spring Data repository.
- Throw `HttpException` for expected HTTP failures. `GlobalExceptionHandler` owns the `{error, code?}` envelope.
- Validate request records with Jakarta Bean Validation. Do not hand-parse request maps.
- Keep writes transactional at the service layer. Use native SQL only when JPA cannot express the operation safely, such as the atomic user upsert.
- Do not use raw Java types. Parameterize collections, repository projections, and response types.
- Flyway is the sole schema owner; Hibernate stays on `ddl-auto: validate`.
- Preserve idempotency on Plaid writes using the Plaid transaction ID. Webhooks may repeat.
- Never store Plaid access tokens in plaintext; use the existing encrypted schema column.
- Advance a Plaid sync cursor in the same transaction as the page it covers.
- Keep queue enqueue operations in the caller's transaction; workers claim with `SKIP LOCKED`.
- `/health` and `/ready` stay unauthenticated and outside the API rate limit.

### AI service

- The service is stateless and does not own database migrations.
- Keep browser traffic on the core API. Python endpoints are internal contracts.
- Convert FastAPI/Pydantic default `detail` errors to the shared error envelope.
- Run Ruff, mypy, and pytest for every change; keep `uv.lock` committed.

### Web app

- Obtain tokens through `auth.getToken()` so Auth0 refresh behavior remains correct.
- Make all API calls through `lib/api.ts`.
- Use the CSS custom properties defined in `+layout.svelte`; do not hardcode component colors.

### Kubernetes

- Every container has resource requests and limits with a rationale comment.
- Every application container has liveness and readiness probes. The core API also keeps its startup probe and database initContainer.
- Never commit `k8s/secrets.yaml`; only the template is tracked.
- Explain the CKAD primitive demonstrated by each manifest section.
- The starting JVM envelope is 250m/384Mi requested and 1000m/768Mi limited. Tighten only after measuring Java 25 startup and RSS.

## Environment variables

| Variable | Source | Consumer |
|---|---|---|
| `DATABASE_HOST`, `DATABASE_PORT`, `DATABASE_NAME` | ConfigMap/Compose | Core API JDBC URL |
| `DATABASE_USER`, `DATABASE_PASSWORD` | Secret/Compose | Core API credentials |
| `DATABASE_URL` | Secret | Future Python and psql tooling; not JDBC |
| `AUTH0_DOMAIN`, `AUTH0_AUDIENCE` | Secret | Core API JWT validation |
| `SPRING_PROFILES_ACTIVE` | ConfigMap | Core API logging/runtime profile |
| `PORT` | ConfigMap | Core API port, default 3001 |
| `FRONTEND_URL` | Environment | Core API CORS origin |
| `AI_SERVICE_URL` | ConfigMap | Worker-to-Python classification path |
| `APP_ENV`, `LOCAL_LLM_MODE` | ConfigMap | AI service mode |
| `OPENAI_API_KEY` | Secret | Reserved for a future hosted provider; currently unused |
| `OLLAMA_BASE_URL`, `OLLAMA_MODEL`, `OLLAMA_KEEP_ALIVE` | Environment | Local classifier endpoint, pinned model, and unload policy |
| `PLAID_CLIENT_ID`, `PLAID_SECRET` | Secret | Plaid API authentication |
| `PLAID_ENV`, `PLAID_PRODUCTS`, `PLAID_COUNTRY_CODES` | ConfigMap/Compose | Plaid Link environment and scope |
| `PLAID_WEBHOOK_URL` | ConfigMap/Compose | Public signed-webhook endpoint attached to Link tokens |
| `TOKEN_ENCRYPTION_KEY` | Secret | Base64-encoded 32-byte AES-GCM key for Plaid tokens |
| `BILLGUARD_WORKER_ENABLED` | Workload override | False for API/CronJob; true only for workers |

Web app environment variables are prefixed with `VITE_` and live in `web-app/.env.local`.

## Local Kubernetes workflow

```powershell
kind create cluster --name billguard
Copy-Item k8s/secrets.template.yaml k8s/secrets.yaml

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

Flyway runs on core API startup; there is no `kubectl exec psql` migration step.

## What not to do

- Do not put domain logic or raw persistence work in controllers.
- Do not call a model from a core API route; model work crosses the internal AI service boundary.
- Do not use the model to generate user-facing prose. Letters the user sends under their own name are templates with substitution; the LLM only classifies into constrained outputs.
- Do not let the Python service own the core schema.
- Do not skip probes or resource limits in new Kubernetes workloads.
- Do not add raw HTML or inline styles to Svelte components.

## Workflow Orchestration

### 1. Plan Mode Default

- Enter plan mode for any non-trivial task (3+ steps or architectural decisions).
- If something goes sideways, stop and re-plan immediately.
- Include verification in the plan, not only implementation.
- Write detailed specifications up front to reduce ambiguity.

### 2. Subagent Strategy

- Use subagents when the active system instructions permit delegation.
- Give each subagent one focused task.
- Keep final integration and verification with the primary agent.

### 3. Self-Improvement Loop

- After a correction from the user, update `tasks/lessons.md` with the reusable pattern.
- Write rules that prevent the same mistake and review them at session start.

### 4. Verification Before Done

- Never mark work complete without proving it works.
- Compare behavior before and after when relevant.
- Run tests, inspect logs, and document the evidence.

### 5. Demand Elegance (Balanced)

- For non-trivial changes, pause and consider whether there is a simpler durable design.
- Avoid over-engineering simple and obvious fixes.

### 6. Autonomous Bug Fixing

- When given a bug report, inspect the evidence, fix the root cause, and rerun the failing check.
- Do not ask for information that the repository or logs can answer.

## Task Management

1. Create a new plan file for every task at `tasks/todo-<todo-topic>.md`; never append a new task to or overwrite an existing todo file.
2. Use a unique, descriptive, kebab-case `<todo-topic>` (for example, `tasks/todo-plaid-link-flow.md`). If that path already exists, choose a more specific topic slug.
3. Write the plan with checkable items and verify it before implementation.
4. Track progress in that task's todo file as work is completed.
5. Give high-level progress summaries.
6. Fill in the task file's review section with verification results.
7. Capture reusable lessons after user corrections.

## Core principles

- Simplicity first.
- Find root causes rather than temporary workarounds.
- Minimize unrelated impact and preserve user work.
