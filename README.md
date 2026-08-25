# BillGuard

Recurring-charge auditor. BillGuard connects to a bank through Plaid, detects recurring charges, categorizes them into subscriptions and bills, and generates cancelation assistance.

## Stack

| Layer      | Technology                                                         |
| ---------- | ------------------------------------------------------------------ |
| Core API   | Java 25, Spring Boot 4.1, Spring Security, Spring Data JPA, Flyway |
| AI service | Python 3.12, FastAPI, uv                                           |
| Web app    | SvelteKit                                                          |
| Database   | PostgreSQL 16                                                      |
| Auth       | Auth0 RS256/JWKS                                                   |
| Runtime    | Docker, Kubernetes (kind locally; k3s in production)               |

The browser talks only to the core API on port 3001. The Python service listens internally on port 8000 and uses a local Ollama `gemma3:1b` classifier by default. Model failure degrades to deterministic rules; it does not fail ingestion or detection jobs.

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

The first start downloads the approximately 815 MB quantized [`gemma3:1b`](https://ollama.com/library/gemma3:1b) model into the `ollama_data` volume. The AI service uses Ollama's [JSON-schema structured output](https://docs.ollama.com/capabilities/structured-outputs). Set `OLLAMA_MODEL` to test another locally hosted model; no transaction data is sent to a hosted model by default.

In a second terminal:

```powershell
Set-Location web-app
npm ci
npm run dev
```

Core API: http://localhost:3001

AI service: http://localhost:8000

Web app: http://localhost:5173

### VS Code

Open the repository root, select **BillGuard: Run app** in **Run and Debug**, and press `F5`. VS Code builds and starts Docker Compose, launches the SvelteKit development server from `web-app`, and opens its URL. Stopping the debug session stops the web development server but leaves Docker running; use **Terminal > Run Task > BillGuard: Docker Compose Down** to stop the containers.

PostgreSQL is exposed to host database tools on `localhost:54320`; services inside
Compose continue to use `postgres:5432`.

Connected banks are stored in the named `postgres_data` volume, including each
encrypted Plaid access token, so normal container rebuilds and restarts do not
require reconnection. Keep `TOKEN_ENCRYPTION_KEY` unchanged so those tokens remain
decryptable. Do not run `docker compose down -v` unless you intend to erase local
users, bank connections, transactions, and detected charges.

Flyway applies the database schema when `core-api` starts; there is no manual migration step. Compose starts a separate worker from the same core API image. Only that worker polls the Postgres job queue. Every completed Plaid sync enqueues one deduplicated `detect_streams` job for the owning user.

## Auth0 setup

Configure the Auth0 Single Page Application with `http://localhost:5173/callback`
as an allowed callback URL and `http://localhost:5173` as an allowed logout URL
and web origin. Enable refresh-token rotation and the Refresh Token grant for the
application, and allow offline access for the BillGuard API. Under **Refresh Token
Expiration**, enable inactivity expiration and set the inactivity lifetime to
`604800` seconds (7 days); make sure any absolute lifetime is longer than 7 days.
The web app stores rotating refresh tokens in its local cache so sessions can
survive reloads and enforces the same 7-day inactivity limit in the browser. Keep
the application free of untrusted scripts because browser local storage is
accessible to JavaScript.

After enabling refresh tokens, sign out and sign in once to replace any older
cached session that did not receive one. If Auth0 later rejects or revokes a
refresh token, BillGuard silently clears the local session and returns to the
landing page even if the inactivity limit has not elapsed, because that session
can no longer be renewed safely.

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

The fixture includes fixed monthly, weekly, and annual streams, a variable utility, an irregular near-miss, one-off noise, salary, and credit-card payments. Plaid enriches the fixture descriptions with PFC data; the sync explicitly requests PFCv2 and persists both `primary` and `detailed`. After Link succeeds, the worker imports transaction pages, advances the cursor atomically, and schedules detection.

Existing Items whose cursor was fully consumed before Phase 3 need a one-time Sandbox backfill. Stop the worker, then run this in pgAdmin or the VS Code PostgreSQL extension:

```sql
UPDATE plaid_items
SET cursor = NULL, updated_at = NOW();

INSERT INTO job_queue (type, dedupe_key, payload)
SELECT 'sync_item', plaid_item_id, jsonb_build_object('plaid_item_id', plaid_item_id)
FROM plaid_items
ON CONFLICT (type, dedupe_key) WHERE status IN ('pending', 'running') DO NOTHING;
```

Restart the worker. The upserts are idempotent. In production, backfill in bounded batches rather than clearing every Item at once.

After the worker completes, open `/dashboard/subscriptions`. Plaid recurring streams form the baseline, and BillGuard runs its AI-assisted detector on the remaining merchants to add any extra recurring charges. Confirmed streams contribute to monthly totals; suspected and provisional streams remain in “Possible” until confirmed. Category edits, rejection, and manual “Mark recurring” actions are stored as user overrides and survive later detector runs.

Useful validation queries:

```sql
SELECT merchant_key, pfc_primary, pfc_detailed, COUNT(*)
FROM transactions
GROUP BY merchant_key, pfc_primary, pfc_detailed
ORDER BY merchant_key;

SELECT display_name, detection_state, category, billing_cycle,
       amount, previous_amount, is_variable_amount, confidence_score,
       user_override, classification_prompt_version
FROM recurring_charges
ORDER BY detection_state, display_name;

SELECT type, dedupe_key, status, attempts, last_error
FROM job_queue
WHERE type = 'detect_streams'
ORDER BY created_at DESC;
```

Verification commands:

```powershell
Set-Location core-api
.\mvnw.cmd -B verify

Set-Location ..\ai-service
uv run ruff check .
uv run mypy app
uv run pytest
uv run python -m scripts.evaluate_prompt --require-model --write-results

Set-Location ..\web-app
npm run lint
npm run test
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

The worker HPA and AI PDB are EKS/CKAD exercise manifests, not single-node k3s defaults. In an EKS exercise with Metrics Server and spare capacity:

```powershell
kubectl apply -f k8s/worker-hpa.yaml
kubectl apply -f k8s/ai-service-pdb.yaml
```

The HPA targets 70% CPU with 1-4 workers. The PDB protects the classifier/Ollama pod during voluntary disruptions; applying `minAvailable: 1` on a one-node cluster can block node maintenance.

## Environment variables

| Variable                                          | Consumer                   | Notes                                                              |
| ------------------------------------------------- | -------------------------- | ------------------------------------------------------------------ |
| `DATABASE_HOST`, `DATABASE_PORT`, `DATABASE_NAME` | Core API                   | JDBC connection components                                         |
| `DATABASE_USER`, `DATABASE_PASSWORD`              | Core API                   | Database credentials                                               |
| `DATABASE_URL`                                    | Future Python/psql tooling | libpq URL; the Java service does not parse it                      |
| `AUTH0_DOMAIN`, `AUTH0_AUDIENCE`                  | Core API                   | Auth0 issuer and API audience                                      |
| `FRONTEND_URL`                                    | Core API                   | Allowed CORS origin; defaults to `http://localhost:5173`           |
| `PORT`                                            | Core API                   | Defaults to `3001`                                                 |
| `SPRING_PROFILES_ACTIVE`                          | Core API                   | Use `dev` locally and `prod` in Kubernetes                         |
| `LOCAL_LLM_MODE`                                  | AI service                 | Defaults to `true`; `false` uses deterministic rules only          |
| `OLLAMA_BASE_URL`                                 | AI service                 | Ollama endpoint; internal Compose/Kubernetes networking by default |
| `OLLAMA_MODEL`, `OLLAMA_KEEP_ALIVE`               | AI service                 | Defaults to `gemma3:1b` and immediate unload (`0`)                 |
| `OPENAI_API_KEY`                                  | AI service                 | Reserved for a future hosted-model switch; currently unused        |
| `AI_SERVICE_URL`                                  | Core API/worker            | Internal FastAPI classifier URL                                    |
| `PLAID_CLIENT_ID`, `PLAID_SECRET`                 | Core API/worker            | Plaid application credentials                                      |
| `PLAID_ENV`                                       | Core API/worker            | `sandbox` locally; only `production` is also accepted              |
| `PLAID_PRODUCTS`, `PLAID_COUNTRY_CODES`           | Core API/worker            | Comma-separated Link configuration                                 |
| `PLAID_WEBHOOK_URL`                               | Core API                   | Public `/api/webhooks/plaid` URL attached to Link tokens           |
| `TOKEN_ENCRYPTION_KEY`                            | Core API/worker/CronJob    | Required base64-encoded 32-byte AES key                            |
| `BILLGUARD_WORKER_ENABLED`                        | Core API/worker            | False on API replicas; true only on queue workers                  |

Web app variables live in `web-app/.env.local`: `VITE_AUTH0_DOMAIN`, `VITE_AUTH0_CLIENT_ID`, `VITE_AUTH0_AUDIENCE`, and `VITE_API_URL`.

## Project structure

```text
billguard/
|-- core-api/                 # Java/Spring API, Plaid ingestion, jobs, Flyway schema
|-- ai-service/               # Python/FastAPI classifier, Ollama adapter, metrics, prompt eval
|-- web-app/                  # SvelteKit browser application
|-- fixtures/plaid/           # Deterministic Plaid Sandbox custom user
|-- k8s/
|   |-- core-api-deployment.yaml
|   |-- ai-service-deployment.yaml
|   |-- worker-deployment.yaml
|   |-- worker-hpa.yaml
|   |-- ai-service-pdb.yaml
|   |-- cronjob.yaml
|   |-- postgres.yaml
|   |-- configmap.yaml
|   `-- secrets.template.yaml
|-- docker-compose.yml
`-- .github/workflows/ci.yml
```

## Kubernetes concepts used

| Manifest                                    | Concepts                                                                                    |
| ------------------------------------------- | ------------------------------------------------------------------------------------------- |
| `core-api-deployment.yaml`                  | Deployment, RollingUpdate, initContainer, three probe types, resources, ConfigMap/Secret    |
| `ai-service-deployment.yaml`                | Internal ClusterIP, Ollama sidecar, metrics annotations, probes, separate resource profiles |
| `worker-deployment.yaml`, `worker-hpa.yaml` | Pull-based workers, queue concurrency, probes, resource bounds, CPU autoscaling             |
| `ai-service-pdb.yaml`                       | Voluntary-disruption protection for the single local-model pod                              |
| `cronjob.yaml`                              | Scheduled headless enqueue job, forbidden overlap, retry/history policies                   |
| `postgres.yaml`                             | Stateful storage and internal database service                                              |
| `namespaces.yaml`                           | Environment isolation                                                                       |

Local 768 MiB container measurements on 2026-08-07 showed a 4.33-second cold Spring startup in both modes and idle memory of 241.9 MiB without compact object headers versus 232.2 MiB with them. The 384 MiB request and 768 MiB limit therefore retain headroom, but the observed idle saving was about 4%, not the theoretical 20%. Re-profile under Phase 2 load before tightening. A single t3.medium will likely be tight once the worker is added - it is a second JVM with its own heap, though the Postgres-backed job queue avoids a separate broker workload.

See [PHASES.md](PHASES.md) for the roadmap.
