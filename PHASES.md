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

## Phase 2 - Plaid Integration (Implemented; Sandbox Validation Pending)

Goal: reliable bank data ingestion. Connecting a Plaid sandbox bank account populates transactions safely and idempotently. Turning those transactions into subscriptions is Phase 3.

### Delivered

- [x] Plaid Link Svelte component
- [x] `POST /api/plaid/link-token`
- [x] `POST /api/plaid/exchange`, encrypting access tokens with AES-256-GCM before storage
- [x] `POST /api/plaid/sync` over `/transactions/sync`, using the cursor and Plaid transaction IDs for idempotency
- [x] `POST /api/webhooks/plaid`, ES256 signature verification and event deduplication
- [x] Durable Postgres job queue and a worker Deployment
- [x] Kubernetes CronJob for a daily full sync
- [x] Connected-account, transaction list, and sync status UI
- [x] `transactions.merchant_key` computed at ingest - the grouping key Phase 3 detection runs on

Automated verification and manifest dry-runs are complete. A real `user_custom` Link session, public webhook delivery, and a live kind rollout remain environment validation rather than implementation work.

### Resolved architecture decision - the job queue

The previous roadmap assumed BullMQ, whose serialized job format is not a safe cross-language contract for a Java producer/worker design.

**Chosen: a Postgres `job_queue` table drained with `SELECT ... FOR UPDATE SKIP LOCKED`.**

The argument is correctness, not minimalism. Job rows are inserted in the same transaction as the rows they describe, so there is no dual-write window in which a crash loses or duplicates work. A Redis broker would need its own reconciliation path to recover that guarantee. `SKIP LOCKED` also lets N worker replicas contend safely with no leader election, and the table carries generic `type` and `dedupe_key` columns so Phase 3 adds its job type without a migration or a second queue.

A partial unique index on `(type, dedupe_key) WHERE status IN ('pending','running')` allows one in-flight job per target while permitting re-enqueue after completion - this is what collapses a burst of webhook-triggered syncs into a single pending job.

### Notes for implementation

- Plaid guarantees at-least-once webhook delivery and stops retrying an endpoint that rejects more than 90% of webhooks over 24 hours. The receiver must be exempt from the per-IP API rate limit and from JWT authentication; the ES256 signature is the authentication.
- Plaid webhook bodies carry no universal event-id field, so `webhook_events.event_id` holds `sha256(raw_body)`. This is an optimization - the real idempotency guarantee is the sync cursor plus the `plaid_transaction_id` unique constraint.
- Local webhook delivery needs a public URL (`cloudflared tunnel --url http://localhost:3001`).

### CKAD concepts introduced

CronJob, and a multi-service internal mesh across the API, worker, and AI service.

## Phase 3 - Subscription Detection (Planned)

Goal: turn ingested transactions into a list of the user's subscriptions, with confidence exposed and user correction possible.

Detection is a single feature and is built here in one pass. An earlier draft split the deterministic half into Phase 2 and left the model half here; that seam cuts through the feature and would mean revisiting confidence semantics, state transitions, and upsert logic twice.

### Design decision - recurrence is deterministic, the model handles ambiguity

Periodicity is arithmetic over dates and needs no model. Group a user's posted transactions by `merchant_key`, then:

- gaps between consecutive charges -> median gap and median absolute deviation
- amounts -> coefficient of variation
- regular gaps plus stable amounts -> a cycle bucket with tolerance bands (7±2 weekly, 14±3 biweekly, 30±5 monthly, 91±10 quarterly, 365±20 annual)
- `next_charge_date` = last charge + median gap
- `confidence_score` from a documented formula over interval regularity, amount stability, and charge count - not a model output

This is cheap, explainable, and unit-testable with fixture data and no network.

The model is reserved for the three things that are genuinely ambiguous:

1. **Merchant normalization** - `AMZN Mktp US*2K4XY7` and `SQ *BLUE BOTTLE #47` into display names.
2. **Subscription versus merely recurring** - Netflix is a subscription; rent, payroll, and utility bills are perfectly periodic and are not.
3. **Single-charge signals** - whether one unmatched charge looks like a signup.

Plaid carries no subscription or recurring flag on the transaction object (verified; `payment_channel` is location). `personal_finance_category.detailed` is a hint only. Detection is entirely ours.

### Design decision - four evidence tiers

A three-charge minimum alone makes a new signup invisible for about 60 days, and two years for an annual plan. Those are exactly the trial-converting charges the product exists to catch.

| `detection_state` | Evidence | Treatment |
|---|---|---|
| `suspected` | 1 charge plus merchant, descriptor, or price-point signal | Shown separately as "possible"; cycle stays NULL |
| `provisional` | 2 charges, one interval | Estimated cycle, lower score |
| `confirmed` | 3+ charges, regular interval, stable amount | Full subscription with next charge date |
| `user_confirmed` | The user said so | Authoritative |

`detection_state` is a separate axis from the existing `status` column, which is lifecycle (active / cancelled). Do not overload one column.

Monthly-spend totals count only `confirmed` and `user_confirmed`. A `suspected` row is a false-positive surface - a one-off $9.99 purchase looks like a signup - and a wrong number on the dashboard costs more trust than a missed detection.

### Design decision - the detector must not clobber user overrides

`subscriptions.user_override` already exists (`NULL` = system decision, `TRUE`/`FALSE` = user override). The detector's upsert runs on every sync, so it must carry `WHERE subscriptions.user_override IS NULL` on every column it owns, and `user_override = FALSE` rows must be retained rather than deleted so they cannot be resurrected.

Without that guard the failure is silent and reads to the user as "the app keeps undoing my correction." It needs an integration test that fails loudly in CI.

### Reshaped `/internal/classify` contract

The Phase 1 stub classifies one transaction at a time, but recurrence is a property of a group over time - a single transaction in isolation cannot be recurring. The contract becomes stream-shaped, carrying both merchant streams and unmatched singletons so one job makes one call. `billing_cycle` leaves the response entirely, because Java computed it.

### To build

- [ ] Deterministic recurrence detector over `merchant_key` groups
- [ ] `detect_streams` job type, deduped on `user_id`, dispatched by the existing worker
- [ ] V3 migration: `subscriptions.merchant_key` with `UNIQUE (user_id, merchant_key)`, and `detection_state`
- [ ] Override guard on the detector upsert, with a test asserting an overridden row survives a pass
- [ ] Reshape `/internal/classify` to streams and singletons; update its tests
- [ ] Replace the AI classifier stub with OpenAI/Ollama implementations
- [ ] Version and evaluate the classification prompt and structured output
- [ ] Subscriptions UI with confirmed and possible sections, plus mark/unmark actions
- [ ] Accuracy dashboard and false-positive tracking, benchmarked against Plaid `/transactions/recurring/get` as ground truth
- [ ] Prometheus metrics for the AI service
- [ ] HPA for the worker and a PodDisruptionBudget for the AI service

### Open architecture decision

The previous roadmap assumed Socket.IO. Cross-language protocol compatibility between Java and the Node-centric Socket.IO ecosystem adds avoidable complexity. Evaluate server-sent events for one-way dashboard updates or STOMP/WebSocket for bidirectional messaging before implementation.

### Testing note

Plaid's default `user_good` sandbox user has no clean recurring streams. Phase 2 builds a `user_custom` fixture with deliberate monthly, annual, weekly, irregular, and variable-amount charges so this phase does not start blocked on test data.

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

A single t3.medium (2 vCPU/4 GiB) is likely too tight for the JVM API, the Python service, and the Phase 2 worker. Re-size from measured working-set data before deployment. The resolved Postgres job queue removes what would have been a fourth workload, but the worker is a second JVM and carries a full heap.

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
| Pull-based worker Deployment | 2 | `k8s/worker-deployment.yaml` |
| CronJob | 2 | `k8s/cronjob.yaml` |
| HPA / PDB | 3 | `k8s/hpa.yaml`, `k8s/pdb.yaml` (planned) |
| Ingress / NetworkPolicy | 4 | Planned |
| Sidecar / RBAC | 5 | Planned |
