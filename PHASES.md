# BillGuard - Phase Plan

Status: Done / Next / Planned

## Phase 1 - Foundation (Done)

Goal: a working authenticated API, AI service seam, Svelte frontend, and Kubernetes-first local environment.

### Delivered

- [x] Java 25 and Spring Boot 4.1 core API with controller/service/repository boundaries
- [x] PostgreSQL schema owned and applied by Flyway
- [x] Auth0 RS256 JWT validation through issuer discovery/JWKS and audience checks
- [x] Atomic, idempotent `POST /api/auth/me` upsert and `GET /api/auth/me`
- [x] Compatible validation, authentication, expected-error, fallback, and 404 envelopes
- [x] Public `/health` and database-aware `/ready` endpoints
- [x] Graceful 25-second Spring shutdown, virtual threads, request size limit, and API-only rate limiting
- [x] Python 3.12/FastAPI AI service skeleton with a stable `/internal/classify` contract
- [x] SvelteKit landing page, Auth0 callback, auth store, API client, and dashboard shell
- [x] Docker Compose and multi-stage non-root images for both backend services
- [x] kind manifests with namespaces, ConfigMap, Secret template, PostgreSQL, and both service Deployments
- [x] Liveness/readiness probes, a core API startup probe, resource bounds, and a database initContainer
- [x] GitHub Actions verification for Java, Python, and the frontend
- [x] JUnit/MockMvc/Testcontainers and pytest/httpx contract coverage

### CKAD concepts introduced

Deployment, RollingUpdate, initContainer, liveness/readiness/startup probes, resource requests and limits, ConfigMap, Secret, ClusterIP Service, and Namespace.

## Phase 2 - Plaid Integration (Next)

Goal: connecting a Plaid sandbox bank account populates transactions safely.

### To build

- [ ] Plaid Link Svelte component
- [ ] `POST /api/plaid/link-token`
- [ ] `POST /api/plaid/exchange`, encrypting access tokens with AES-256 before storage
- [ ] `POST /api/plaid/sync` using Plaid transaction IDs for idempotency
- [ ] `POST /api/webhooks/plaid`, deduplicating webhook event IDs
- [ ] Transaction classification queue and worker
- [ ] Kubernetes CronJob for a daily full sync
- [ ] Connected-account and sync status UI

### Open architecture decision

The previous roadmap assumed BullMQ, but its serialized job format is not a safe cross-language contract for a Java producer/worker design. Before implementing the queue, choose and document either Spring Data Redis/Redisson with a versioned JSON payload or a language-neutral broker. Do not silently reuse the Node-only assumption.

### CKAD concepts to introduce

CronJob and a multi-service internal mesh for the broker and worker.

## Phase 3 - AI Detection (Planned)

Goal: the existing Python contract performs real recurring-charge detection and exposes confidence to users.

### To build

- [ ] Replace the AI classifier stub with OpenAI/Ollama implementations
- [ ] Version and evaluate the classification prompt and structured output
- [ ] Persist classifications through the Java-owned application boundary
- [ ] Accuracy dashboard and false-positive tracking
- [ ] Manual subscription override UI
- [ ] Prometheus metrics for the AI service
- [ ] HPA for classification workers and a PodDisruptionBudget for the AI service

### Open architecture decision

The previous roadmap assumed Socket.IO. Cross-language protocol compatibility between Java and the Node-centric Socket.IO ecosystem adds avoidable complexity. Evaluate server-sent events for one-way dashboard updates or STOMP/WebSocket for bidirectional messaging before implementation.

## Phase 4 - Cancelation Engine and EKS (Planned)

Goal: deploy the product with HTTPS and continuous delivery.

- [ ] Merchant cancelation catalog
- [ ] AI-generated email and instructions per merchant
- [ ] Mail launch/copy workflow and attempt tracking
- [ ] EKS cluster and ECR repositories for core API and AI service
- [ ] External Secrets Operator backed by AWS Secrets Manager
- [ ] AWS Load Balancer Controller, Ingress, ACM TLS
- [ ] RDS PostgreSQL
- [ ] GitHub Actions image push and rollout
- [ ] NetworkPolicy allowing AI traffic only from the core workload

A single t3.medium (2 vCPU/4 GiB) is likely too tight for the JVM API, Python service, Phase 2 worker, and Redis. Re-size from measured working-set data before deployment.

## Phase 5 - Polish and CKAD Sweep (Planned)

- [ ] Refund demand letter generator
- [ ] Monthly savings summary
- [ ] Loading, empty, and error states across the frontend
- [ ] Log sidecar on the AI service
- [ ] ServiceAccounts, Roles, and RoleBindings
- [ ] Enforced dev/prod namespace separation
- [ ] Consistent resource labels and annotations
- [ ] Architecture diagram and interview reference material

Flyway already owns migrations, so the old manual `kubectl exec psql` step and a separate migration Job are no longer required. Revisit a migration Job only if deployment policy later requires schema changes to finish before any new application pod starts.

## CKAD concept tracker

| Concept | Phase | Manifest |
|---|---|---|
| Deployment / RollingUpdate | 1 | `k8s/core-api-deployment.yaml`, `k8s/ai-service-deployment.yaml` |
| initContainer | 1 | `k8s/core-api-deployment.yaml` |
| Liveness / readiness probes | 1 | Both service manifests and `k8s/postgres.yaml` |
| Startup probe | 1 | `k8s/core-api-deployment.yaml` |
| Resource requests / limits | 1 | All workload manifests |
| ConfigMap / Secret | 1 | `k8s/configmap.yaml`, `k8s/secrets.template.yaml` |
| ClusterIP Service | 1 | Both service manifests and `k8s/postgres.yaml` |
| Namespace | 1 | `k8s/namespaces.yaml` |
| CronJob | 2 | `k8s/cronjob.yaml` (planned) |
| HPA / PDB | 3 | `k8s/hpa.yaml`, `k8s/pdb.yaml` (planned) |
| Ingress / NetworkPolicy | 4 | Planned |
| Sidecar / RBAC | 5 | Planned |
