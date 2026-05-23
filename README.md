# BillGuard

AI-powered subscription auditor. Connects to your bank via Plaid, detects recurring charges with an LLM classifier, and generates cancelation assistance.

## Prerequisites

- [Docker Desktop](https://www.docker.com/products/docker-desktop/)
- [Node.js 20+](https://nodejs.org/)
- [kind](https://kind.sigs.k8s.io/docs/user/quick-start/#installation) (for K8s local dev)
- [kubectl](https://kubernetes.io/docs/tasks/tools/)
- An [Auth0](https://auth0.com) account with:
  - A **Single Page Application** (for the frontend)
  - An **API** with audience `https://api.billguard.dev`
  - SPA app authorized to access the API

## Quick start (docker-compose)

```bash
# 1. Fill in backend env
cp backend/.env.example backend/.env
# Set AUTH0_DOMAIN, AUTH0_AUDIENCE, DATABASE_URL, DATABASE_PASSWORD

# 2. Fill in frontend env
touch frontend/.env.local
# Set VITE_AUTH0_DOMAIN, VITE_AUTH0_CLIENT_ID, VITE_AUTH0_AUDIENCE, VITE_API_URL

# 3. Install dependencies
cd backend && npm install && cd ..
cd frontend && npm install && cd ..

# 4. Start backend + postgres
docker-compose up

# 5. Start frontend in a separate terminal (hot reload)
cd frontend && npm run dev
```

Backend API: http://localhost:3001  
Frontend: http://localhost:5173

> The frontend runs outside Docker for hot reload. The Dockerfile is used for kind/EKS deployment only.

## Quick start (kind cluster — recommended)

```bash
# 1. Create local cluster
kind create cluster --name billguard

# 2. Create and apply secrets
cp k8s/secrets.template.yaml k8s/secrets.yaml
# edit k8s/secrets.yaml with real values

kubectl apply -f k8s/namespaces.yaml
kubectl apply -f k8s/secrets.yaml
kubectl apply -f k8s/configmap.yaml

# 3. Deploy Postgres + backend
kubectl apply -f k8s/postgres.yaml
kubectl apply -f k8s/backend-deployment.yaml

# 4. Run DB migration
kubectl exec -n billguard-dev deploy/postgres -- \
  psql -U billguard -d billguard -f /migrations/001_initial.sql

# 5. Port-forward for local access
kubectl port-forward -n billguard-dev svc/billguard-backend-service 3001:80

# 6. Run frontend as normal
cd frontend && npm run dev
```

## Environment variables

### Backend (`backend/.env`)

| Variable | Example | Notes |
|---|---|---|
| `DATABASE_URL` | `postgresql://billguard:pw@localhost:5432/billguard` | Use `postgres-service` as host inside kind |
| `DATABASE_PASSWORD` | `billguard_dev` | Must match Postgres container password |
| `AUTH0_DOMAIN` | `dev-xxx.ca.auth0.com` | From Auth0 tenant settings |
| `AUTH0_AUDIENCE` | `https://api.billguard.dev` | Your Auth0 API identifier |
| `PORT` | `3001` | |
| `PLAID_CLIENT_ID` | _(Phase 2)_ | Leave empty for now |
| `OPENAI_API_KEY` | _(Phase 3)_ | Leave empty for now |
| `LOCAL_LLM_MODE` | `false` | Set `true` to use Ollama instead of OpenAI |

### Frontend (`frontend/.env.local`)

| Variable | Example | Notes |
|---|---|---|
| `VITE_AUTH0_DOMAIN` | `dev-xxx.ca.auth0.com` | Same as backend |
| `VITE_AUTH0_CLIENT_ID` | `4JPhPAw...` | From Auth0 SPA app settings |
| `VITE_AUTH0_AUDIENCE` | `https://api.billguard.dev` | Same as backend |
| `VITE_API_URL` | `http://localhost:3001` | |

## Project structure

```
billguard/
├── backend/
│   ├── src/
│   │   ├── modules/          # auth, plaid, subscriptions, ai, webhooks
│   │   ├── middleware/       # JWT auth, error handler
│   │   └── utils/           # db, logger
│   ├── migrations/
│   │   └── 001_initial.sql  # Run once after DB starts
│   └── Dockerfile
├── frontend/
│   └── src/
│       ├── routes/           # SvelteKit pages
│       │   ├── callback/     # Auth0 redirect handler
│       │   └── dashboard/    # Main dashboard
│       └── lib/
│           ├── stores/       # auth store (Auth0 SPA SDK wrapper)
│           └── api.ts        # Typed fetch wrapper
├── k8s/
│   ├── namespaces.yaml
│   ├── configmap.yaml
│   ├── secrets.template.yaml # Commit this — not secrets.yaml
│   ├── postgres.yaml
│   └── backend-deployment.yaml
└── .github/workflows/ci.yml
```

## Kubernetes concepts used (CKAD reference)

| Manifest | Concept | Why |
|----------|---------|-----|
| `backend-deployment.yaml` | Deployment + RollingUpdate | Zero-downtime deploys |
| `backend-deployment.yaml` | initContainer | Waits for DB before API starts |
| `backend-deployment.yaml` | Liveness probe | Restart unresponsive pods |
| `backend-deployment.yaml` | Readiness probe | Only route traffic when DB is verified |
| `backend-deployment.yaml` | Startup probe | Gives app time to initialise |
| `backend-deployment.yaml` | Resource requests/limits | Prevents noisy-neighbour issues |
| `backend-deployment.yaml` | envFrom ConfigMap + Secret | Clean config/secret separation |
| `configmap.yaml` | ConfigMap | Non-sensitive app config |
| `secrets.template.yaml` | Secret | Sensitive values; never committed |
| `postgres.yaml` | ClusterIP Service | Internal DNS for DB |
| `namespaces.yaml` | Namespace | dev/prod isolation |
| `k8s/cronjob.yaml` *(Phase 2)* | CronJob | Daily transaction re-sync |
| `k8s/hpa.yaml` *(Phase 3)* | HorizontalPodAutoscaler | Scale on CPU/queue depth |
| `k8s/pdb.yaml` *(Phase 3)* | PodDisruptionBudget | Min availability during rollouts |
| `k8s/network-policy.yaml` *(Phase 4)* | NetworkPolicy | AI service only accepts backend traffic |

## Development phases

- **Phase 1** ✅ Foundation — Express + TypeScript, PostgreSQL, Auth0, Svelte dashboard, kind cluster
- **Phase 2** 🔜 Plaid — Link component, transaction sync, webhooks, BullMQ, CronJob
- **Phase 3** 🔜 AI — Subscription detection microservice, confidence scoring, HPA, PDB
- **Phase 4** 🔜 Cancelation + EKS — Cancelation engine, EKS deploy, NetworkPolicy, CI/CD
- **Phase 5** 🔜 Polish — Sidecar, RBAC, Job migrations, architecture docs

## Estimated monthly cost (EKS, Phase 4+)

| Service | Cost |
|---------|------|
| EC2 t3.medium (1 node) | ~$30 |
| RDS db.t3.micro | ~$15 |
| OpenAI API | ~$20 |
| S3 + misc | ~$3 |
| **Total** | **~$68** |

Set `LOCAL_LLM_MODE=true` and run [Ollama](https://ollama.com) locally to eliminate the OpenAI cost.