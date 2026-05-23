# BillGuard — Claude Project Memory

## What this is

AI-powered subscription auditor. Connects to users' bank accounts via Plaid, uses an LLM classifier to detect recurring charges, and generates cancelation assistance. Built by Ray Lin as a full-stack portfolio project targeting fintech and infrastructure engineering roles.

## Current phase

**Phase 1 complete** — foundation is in place. Starting Phase 2 (Plaid integration).

See `PHASES.md` for the full roadmap and what's coming next.

---

## Stack

| Layer | Choice | Notes |
|---|---|---|
| Backend | Express + TypeScript | Modular architecture — one folder per domain |
| Frontend | Svelte + SvelteKit | `@sveltejs/adapter-node` for containerised deploy |
| Database | PostgreSQL 16 | All queries go through `src/utils/db.ts` — never raw `pg` calls in routes |
| Auth | Auth0 | JWT validation via `express-jwt` + `jwks-rsa`; middleware in `src/middleware/auth.ts` |
| Containerisation | Docker (multi-stage) | Non-root user in runtime stage |
| Orchestration | Kubernetes (kind locally, EKS in prod) | Manifests in `k8s/` |
| CI/CD | GitHub Actions | `.github/workflows/ci.yml` — deploy step stubbed for Phase 4 |
| AI/LLM | OpenAI GPT-4o or Ollama (local) | Toggled via `LOCAL_LLM_MODE=true` env var |
| Banking | Plaid | Sandbox → Development; Phase 2 |
| Queue | BullMQ + Redis | Phase 2; worker is its own K8s Deployment |

---

## Project structure

```
billguard/
├── backend/
│   ├── src/
│   │   ├── modules/          # One folder per domain
│   │   │   ├── auth/         # auth.routes.ts — /api/auth/me
│   │   │   ├── plaid/        # Phase 2
│   │   │   ├── subscriptions/# Phase 3
│   │   │   ├── ai/           # Phase 3 — separate microservice
│   │   │   └── webhooks/     # Phase 2
│   │   ├── middleware/
│   │   │   ├── auth.ts       # validateJwt, requireAuth, AuthenticatedRequest
│   │   │   └── errorHandler.ts # HttpError class, global handler
│   │   └── utils/
│   │       ├── db.ts         # pool, query(), withTransaction(), checkDatabaseHealth()
│   │       └── logger.ts     # winston; JSON in prod, coloured in dev
│   ├── migrations/
│   │   └── 001_initial.sql   # All 5 tables: users, accounts, transactions, subscriptions, plaid_items, webhook_events
│   └── Dockerfile
├── frontend/
│   └── src/
│       ├── routes/
│       │   ├── +layout.svelte        # Initialises auth store; global CSS vars
│       │   ├── +page.svelte          # Landing page
│       │   └── dashboard/+page.svelte# Main dashboard (empty state, Phase 1)
│       └── lib/
│           ├── stores/auth.ts        # Auth0 SPA SDK wrapper; login(), logout(), getToken()
│           └── api.ts                # Typed fetch wrapper — api.get/post/patch/del
├── k8s/
│   ├── namespaces.yaml               # billguard-dev + billguard-prod
│   ├── configmap.yaml                # Non-sensitive config
│   ├── secrets.template.yaml         # Template only — real secrets.yaml is gitignored
│   ├── postgres.yaml                 # Dev-only in-cluster Postgres + ClusterIP Service
│   └── backend-deployment.yaml       # Deployment + Service; probes + initContainer
├── docker-compose.yml                # Local fallback (kind cluster is primary dev env)
└── .github/workflows/ci.yml
```

---

## Critical conventions — follow these always

### Backend
- **All DB access via `utils/db.ts`** — use `query()` for reads, `withTransaction()` for writes. Never call `pool.query()` directly in a route.
- **Throw `HttpError`** for expected failures (`throw new HttpError(404, 'Not found')`). The global handler in `errorHandler.ts` converts it to the right HTTP response.
- **Zod for all request validation** — parse body with a schema before touching `req.body`.
- **Idempotency keys on all Plaid-related writes** — use `plaid_transaction_id` as the unique key. Plaid webhooks can fire duplicate events.
- **Never store Plaid `access_token` in plaintext** — column is `access_token_enc`; encrypt with AES-256 before insert.
- **`/health` and `/ready` must stay unauthenticated** — they're called by K8s probes.

### Frontend
- **Auth token via `auth.getToken()`** — never cache the token in a variable; always call the store method so Auth0 handles refresh.
- **All API calls via `lib/api.ts`** — the `api` object handles auth header injection and error normalisation.
- **CSS custom properties only** — all colours reference `--bg`, `--surface`, `--accent`, etc. defined in `+layout.svelte`. No hardcoded hex in components.

### Kubernetes
- **Every container must have `resources.requests` and `resources.limits`** — document the rationale in a comment.
- **Every container must have liveness + readiness probes** — use `/health` for liveness, `/ready` for readiness.
- **Secrets never committed** — `k8s/secrets.yaml` is in `.gitignore`. Only `secrets.template.yaml` is committed.
- **CKAD primitives used: annotate why** — add a comment explaining which CKAD concept each manifest demonstrates. This is intentional for interview prep.

---

## Environment variables

| Variable | Where set | Notes |
|---|---|---|
| `DATABASE_URL` | Secret | Full postgres connection string |
| `AUTH0_DOMAIN` | Secret | e.g. `your-tenant.auth0.com` |
| `AUTH0_AUDIENCE` | Secret | e.g. `https://api.billguard.dev` |
| `LOCAL_LLM_MODE` | ConfigMap | `true` = use Ollama instead of OpenAI |
| `PLAID_CLIENT_ID` | Secret | Phase 2; empty in Phase 1 |
| `OPENAI_API_KEY` | Secret | Phase 3; empty in Phase 1 |
| `PORT` | ConfigMap | Backend port, default `3001` |

Frontend env vars are prefixed `VITE_` and set in `.env.local` (gitignored).

---

## K8s local dev workflow

```bash
# Start cluster (one time)
kind create cluster --name billguard

# Apply manifests
kubectl apply -f k8s/namespaces.yaml
kubectl apply -f k8s/secrets.yaml        # you created this from secrets.template.yaml
kubectl apply -f k8s/configmap.yaml
kubectl apply -f k8s/postgres.yaml
kubectl apply -f k8s/backend-deployment.yaml

# Run migration (one time or after schema change)
kubectl exec -n billguard-dev deploy/postgres -- \
  psql -U billguard -d billguard -f /migrations/001_initial.sql

# Access the API locally
kubectl port-forward -n billguard-dev svc/billguard-backend-service 3001:80
```

---

## What NOT to do

- Don't add a new route directly in `app.ts` — create a module in `src/modules/` with its own router, then mount it in `app.ts`.
- Don't call OpenAI directly from a route handler — all AI calls go through the `ai` module (Phase 3) to keep token usage observable.
- Don't add raw HTML or inline styles to Svelte components — use the CSS custom property system.
- Don't skip probes or resource limits on any new K8s manifest — this is CKAD exam prep, every primitive counts.
- Don't use `any` in TypeScript — use `unknown` and narrow, or define the type explicitly.
