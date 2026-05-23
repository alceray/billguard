# BillGuard — Phase Plan

## Status legend
- ✅ Done
- 🔜 Next
- ⬜ Planned

---

## Phase 1 — Foundation ✅ (Weeks 1–2)

**Goal:** Working backend + auth + K8s-first local env

### Completed
- [x] Express + TypeScript, modular architecture (`modules/`, `middleware/`, `utils/`)
- [x] PostgreSQL schema — users, accounts, transactions, subscriptions, plaid_items, webhook_events
- [x] Auth0 JWT middleware (`validateJwt`, `requireAuth`)
- [x] `/api/auth/me` — upsert user on first login
- [x] `/health` and `/ready` endpoints (used by K8s probes)
- [x] Graceful shutdown on SIGTERM (drains HTTP + DB pool within 25s)
- [x] Svelte + SvelteKit scaffold, landing page, empty dashboard
- [x] Auth store wrapping Auth0 SPA SDK
- [x] Typed API client (`lib/api.ts`)
- [x] `kind` cluster manifests: namespaces, ConfigMap, Secret template, Postgres, backend Deployment
- [x] Liveness + readiness + startup probes on all containers
- [x] Resource requests/limits on all containers
- [x] `initContainer` on backend — waits for Postgres before starting
- [x] GitHub Actions CI — lint + build for backend and frontend
- [x] Multi-stage Dockerfiles for backend and frontend

### CKAD concepts introduced
- Deployment, RollingUpdate strategy
- initContainer
- Liveness, readiness, startup probes
- Resource requests/limits
- ConfigMap + Secret (envFrom)
- ClusterIP Service
- Namespaces

---

## Phase 2 — Plaid Integration 🔜 (Weeks 3–4)

**Goal:** Connecting a sandbox bank account populates real transactions in the DB

### To build
- [ ] Plaid Link — Svelte component wrapping Plaid Link SDK
- [ ] `POST /api/plaid/link-token` — creates a Plaid Link token for the frontend
- [ ] `POST /api/plaid/exchange` — exchanges public token for access token (AES-256 encrypt before storing)
- [ ] `POST /api/plaid/sync` — pulls transactions via `/transactions/sync`, stores with idempotency
- [ ] `POST /api/webhooks/plaid` — handles `SYNC_UPDATES_AVAILABLE`; deduplicates on `webhook_events.event_id`
- [ ] BullMQ + Redis — new transactions enqueued for AI processing
- [ ] BullMQ worker — own Express app + own K8s Deployment
- [ ] `CronJob` — daily full transaction re-sync (K8s native, no app-level cron)
- [ ] Dashboard update — connected accounts list, transaction count, last synced

### CKAD concepts to introduce
- CronJob
- Multi-service ClusterIP mesh (Redis + worker + API)
- Separate Deployment for worker with different resource limits

---

## Phase 3 — AI Detection Microservice ⬜ (Weeks 5–6)

**Goal:** Subscriptions detected automatically after bank sync; confidence scores visible in UI

### To build
- [ ] `ai-service` — separate Express app in `backend/src/modules/ai/`, own Dockerfile
- [ ] LLM prompt: classify transactions as recurring vs one-time, return confidence 0–1
- [ ] `LOCAL_LLM_MODE=true` flag — swaps OpenAI for Ollama transparently
- [ ] Accuracy dashboard — track AI classifications vs user overrides, surface false positive rate
- [ ] Manual override UI — user marks "not a subscription", stored in `subscriptions.user_override`
- [ ] Socket.IO on backend — push new detections to dashboard in real time
- [ ] `/metrics` Prometheus endpoint on AI service

### CKAD concepts to introduce
- HorizontalPodAutoscaler (CPU-based on BullMQ worker)
- PodDisruptionBudget on AI service (minAvailable: 1)
- Separate resource profile for AI service (higher memory limit)

---

## Phase 4 — Cancelation Engine + EKS Deploy ⬜ (Weeks 7–8)

**Goal:** Live on EKS with HTTPS, CI/CD deploys on push to main

### To build
- [ ] Merchant catalog — map subscription names to cancelation method (email, link, phone)
- [ ] LLM-generated cancelation email + step-by-step instructions per merchant
- [ ] One-click mailto: launch or copy-to-clipboard in dashboard
- [ ] Cancelation attempt tracking per user
- [ ] EKS cluster via `eksctl` — single t3.medium node pool
- [ ] External Secrets Operator — AWS Secrets Manager → K8s Secrets
- [ ] Ingress + AWS Load Balancer Controller — TLS via ACM
- [ ] RDS PostgreSQL (replaces in-cluster Postgres)
- [ ] ECR image registry
- [ ] GitHub Actions deploy step — build → push ECR → `kubectl rollout`
- [ ] NetworkPolicy — ai-service only accepts traffic from backend namespace

### CKAD concepts to introduce
- Ingress
- NetworkPolicy
- External Secrets (ESO pattern)

---

## Phase 5 — Polish + CKAD Sweep ⬜ (Weeks 9–10)

**Goal:** Every major CKAD workload type touched; demo-ready UI

### To build
- [ ] Refund demand letter generator (AI)
- [ ] Savings summary — total recovered per month
- [ ] Error states, loading skeletons, empty states across dashboard
- [ ] `Job` manifest for one-time DB migrations (replaces manual `kubectl exec`)
- [ ] Sidecar container on AI service pod — log shipper
- [ ] RBAC — ServiceAccount + Role + RoleBinding per namespace
- [ ] Namespace separation enforced: `billguard-prod` vs `billguard-dev`
- [ ] Label + annotate all resources consistently
- [ ] README: architecture diagram, CKAD concept map, interview Q&A

### CKAD concepts to introduce
- Job (batch workload)
- Sidecar container (multi-container pod)
- RBAC (ServiceAccount, Role, RoleBinding)

---

## CKAD concept tracker

| Concept | Introduced | Manifest |
|---|---|---|
| Deployment | Phase 1 ✅ | `k8s/backend-deployment.yaml` |
| RollingUpdate | Phase 1 ✅ | `k8s/backend-deployment.yaml` |
| initContainer | Phase 1 ✅ | `k8s/backend-deployment.yaml` |
| Liveness probe | Phase 1 ✅ | `k8s/backend-deployment.yaml`, `k8s/postgres.yaml` |
| Readiness probe | Phase 1 ✅ | `k8s/backend-deployment.yaml`, `k8s/postgres.yaml` |
| Startup probe | Phase 1 ✅ | `k8s/backend-deployment.yaml` |
| Resource requests/limits | Phase 1 ✅ | All manifests |
| ConfigMap (envFrom) | Phase 1 ✅ | `k8s/configmap.yaml` |
| Secret (envFrom) | Phase 1 ✅ | `k8s/secrets.template.yaml` |
| ClusterIP Service | Phase 1 ✅ | `k8s/postgres.yaml`, `k8s/backend-deployment.yaml` |
| Namespace | Phase 1 ✅ | `k8s/namespaces.yaml` |
| CronJob | Phase 2 🔜 | `k8s/cronjob.yaml` |
| HPA | Phase 3 ⬜ | `k8s/hpa.yaml` |
| PodDisruptionBudget | Phase 3 ⬜ | `k8s/pdb.yaml` |
| Ingress | Phase 4 ⬜ | `k8s/ingress.yaml` |
| NetworkPolicy | Phase 4 ⬜ | `k8s/network-policy.yaml` |
| Job | Phase 5 ⬜ | `k8s/migration-job.yaml` |
| Sidecar | Phase 5 ⬜ | `k8s/backend-deployment.yaml` update |
| RBAC | Phase 5 ⬜ | `k8s/rbac.yaml` |
