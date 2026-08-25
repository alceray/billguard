# BillGuard - Phase Plan

Each phase below has the same shape: **Goal**, **Schema** (the exact migration, or "none"), **Build** (the checklist), **Design notes** (why it is built that way), and **Depends on**.

Flyway in `core-api` is the sole schema owner. Hibernate stays on `ddl-auto: validate`.

## Product principles

Two constraints that shape decisions in every phase. Both exist because the obvious commercial comparison - Rocket Money, which runs the same Plaid pipeline and already ships recurring detection, a calendar view, and price-increase alerts - structurally cannot offer either.

**Self-hostable.** BillGuard runs on the user's own infrastructure, with their own Plaid credentials and their own database. Nothing reaches a service the author operates. A company funded by monetizing attention and transaction data cannot ship that; an MIT-licensed project can. It also raises the bar on the infrastructure work in a useful way: secrets, migrations, and deployment have to work for someone who is not the author.

**No lock-in.** Data leaves as easily as it arrives. A subscribed ICS feed puts charges in the user's own calendar, webhooks push events into their own tooling, and CSV plus a documented REST API make the whole dataset portable. Incumbents build calendar views that exist only inside their app because engagement is the business model. That constraint does not apply here.

Feature parity with a commercial product is not a goal. These two were chosen because they are also the better engineering problems.

## Data model

Every table in the system and the migration that introduces it.

| Table | Migration | Purpose |
|---|---|---|
| `users` | V1 | Auth0 subject mapped to an internal UUID |
| `plaid_items` | V1, extended V2 | One linked bank per row; encrypted access token and sync cursor |
| `accounts` | V1 | Bank accounts belonging to an item |
| `transactions` | V1, extended V2 | Every charge; `merchant_key` is the grouping key detection runs on |
| `webhook_events` | V1 | Webhook dedupe ledger keyed on `sha256(raw_body)` |
| `recurring_charges` | V1 as `subscriptions`, renamed and extended V3 | One detected recurring stream per (user, merchant) |
| `job_queue` | V2 | Transactional work queue drained with `FOR UPDATE SKIP LOCKED` |
| `notification_events` | V4 | What happened, and for which recurring charge |
| `notifications_sent` | V4 | Delivery idempotency ledger |
| `notification_prefs` | V4 | Channel, target, lead time, enabled |
| `calendar_feeds` | V4 | Hashed, revocable ICS capability token |
| `cancelation_methods` | V5 | Per-merchant cancelation catalog, global rather than per-user |
| `cancelation_attempts` | V5 | Per-user cancelation attempt tracking |

## Phase 1 - Foundation (Done)

**Goal:** a working authenticated API, AI service seam, Svelte frontend, and Kubernetes-first local environment.

### Schema - `V1__initial_schema.sql`

Creates `users`, `accounts`, `transactions`, `subscriptions`, `plaid_items`, and `webhook_events`. The last four are created empty and filled by later phases. V1 is already applied, so it is never edited - the V3 rename of `subscriptions` is a new statement, not a change here.

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

## Phase 2 - Plaid Integration (Done)

**Goal:** reliable bank data ingestion. Connecting a Plaid sandbox bank populates transactions safely and idempotently. Turning those transactions into recurring charges is Phase 3.

### Schema - `V2__plaid_and_job_queue.sql`

```sql
ALTER TABLE transactions
  ADD COLUMN updated_at             TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  ADD COLUMN pending_transaction_id TEXT,
  ADD COLUMN merchant_key           TEXT;

ALTER TABLE plaid_items
  ADD COLUMN status     TEXT NOT NULL DEFAULT 'healthy',  -- healthy|item_login_required|error
  ADD COLUMN last_error TEXT;

ALTER TABLE accounts
  ADD CONSTRAINT fk_accounts_plaid_item FOREIGN KEY (plaid_item_id)
    REFERENCES plaid_items (plaid_item_id) ON DELETE CASCADE;

CREATE TABLE job_queue (
  id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  type       TEXT NOT NULL,                    -- sync_item | detect_streams (V3) | notify (V4)
  dedupe_key TEXT NOT NULL,                    -- plaid_item_id | user_id
  payload    JSONB NOT NULL DEFAULT '{}',
  status     TEXT NOT NULL DEFAULT 'pending',  -- pending|running|done|dead
  attempts   INT NOT NULL DEFAULT 0,
  run_after  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  last_error TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- One active job per target: collapses webhook bursts, allows re-enqueue after completion.
CREATE UNIQUE INDEX uq_job_queue_inflight ON job_queue (type, dedupe_key)
  WHERE status IN ('pending', 'running');
```

Plus indexes on `transactions.merchant_key`, the `(date DESC, id DESC)` keyset feed, claimable jobs, and stale `running` jobs.

### Delivered

- [x] Plaid Link Svelte component
- [x] `POST /api/plaid/link-token`
- [x] `POST /api/plaid/exchange`, encrypting access tokens with AES-256-GCM before storage
- [x] `POST /api/plaid/sync` over `/transactions/sync`, using the cursor and Plaid transaction IDs for idempotency
- [x] `POST /api/webhooks/plaid`, ES256 signature verification and event deduplication
- [x] Durable Postgres job queue and a worker Deployment
- [x] Kubernetes CronJob for a daily full sync
- [x] Connected-account, transaction list, and sync status UI
- [x] `transactions.merchant_key` computed at ingest

Automated verification and manifest dry-runs are complete. A real `user_custom` Link session, public webhook delivery, and a live kind rollout remain environment validation rather than implementation work.

### Design notes

**The job queue is Postgres, not Redis.** The previous roadmap assumed BullMQ, whose serialized job format is not a safe cross-language contract for a Java producer and worker. Postgres won on correctness: job rows are inserted in the same transaction as the rows they describe, so there is no dual-write window in which a crash loses or duplicates work. A Redis broker would need its own reconciliation path to recover that guarantee. `SKIP LOCKED` also lets N worker replicas contend safely with no leader election.

**The cursor advances inside the page transaction.** Each `/transactions/sync` page commits its rows and its `next_cursor` together. Hoisting the cursor write out of that transaction is what turns a mid-loop crash into skipped or replayed data.

**The webhook receiver is exempt from auth and rate limiting.** Plaid guarantees at-least-once delivery and stops retrying an endpoint that rejects more than 90% of webhooks over 24 hours. The ES256 signature is the authentication. Plaid bodies carry no universal event-id field, so `webhook_events.event_id` holds `sha256(raw_body)` - an optimization only, since the real guarantee is the sync cursor plus the `plaid_transaction_id` unique constraint.

**Local webhook delivery needs a public URL:** `cloudflared tunnel --url http://localhost:3001`.

### CKAD concepts introduced

CronJob, and a multi-service internal mesh across the API, worker, and AI service.

## Phase 3 - Recurring Charge Detection (Implemented; Sandbox validation pending)

**Goal:** turn ingested transactions into a categorized list of the user's recurring charges, with confidence exposed and user correction possible.

### Scope - what counts as a recurring charge

**Detect and display everything that recurs**, including rent, mortgage, and loan payments. They are the most reliably detectable charges in the dataset - fixed amount, fixed date - and a monthly total that omits a $2,000 rent payment misrepresents what the user actually owes every month. Rent increases are also worth alerting on.

Actionability decides **what the UI offers**, not what exists. Three groups, derived from `category` with no extra column:

| Group | Categories | Action offered |
|---|---|---|
| **Subscriptions** | streaming, ai, software, gaming, news_and_media, fitness_and_health, food_and_delivery, shopping_membership, education, storage_and_hosting, finance | Cancel (Phase 5 engine runs only here) |
| **Bills** | insurance, telecom, utilities | Switch or renegotiate |
| **Fixed** | housing, loan | Track and alert only |

Only three things are excluded, and none for being unactionable:

| Excluded | Plaid categories | Why |
|---|---|---|
| Income | `INCOME_*` | Money in, not a charge |
| Self-transfers | `TRANSFER_*` between the user's own accounts | Moving money is not spending it |
| Credit card payments | `LOAN_PAYMENTS_CREDIT_CARD_PAYMENT` | **Double counting.** If both the card and the funding account are linked, the individual card charges are already captured; the payment that settles them is the same money a second time |

"Subscription" is too narrow for that scope, so the domain term is **recurring charge** and the table is renamed accordingly.

### Schema - `V3__recurring_charge_detection.sql`

```sql
-- The table is still empty at this point, so the rename is free. V1 stays untouched.
ALTER TABLE subscriptions RENAME TO recurring_charges;

ALTER TABLE recurring_charges
  ADD COLUMN merchant_key       TEXT NOT NULL,
  ADD COLUMN detection_state    TEXT NOT NULL DEFAULT 'suspected',
  ADD COLUMN category           TEXT NOT NULL DEFAULT 'other',
  ADD COLUMN category_source    TEXT NOT NULL DEFAULT 'model',
  ADD COLUMN previous_amount    NUMERIC(12,2),
  ADD COLUMN amount_changed_at  TIMESTAMPTZ,
  ADD COLUMN is_variable_amount BOOLEAN NOT NULL DEFAULT FALSE;

-- The detector upserts on this key.
CREATE UNIQUE INDEX uq_recurring_charges_stream
  ON recurring_charges (user_id, merchant_key);
```

Rename the V1 indexes to match (`idx_subscriptions_user_id`, `idx_subscriptions_status`).

**`recurring_charges` column reference** (V1 columns included, so the whole row is visible in one place):

| Column | Values | Notes |
|---|---|---|
| `merchant_key` | normalized merchant string | Written at ingest in Phase 2; joins to `transactions.merchant_key` |
| `detection_state` | `suspected` \| `provisional` \| `confirmed` \| `user_confirmed` | Evidence strength |
| `status` (V1) | `active` \| `cancelled` \| `unknown` | Lifecycle. A **separate axis** from `detection_state`; do not overload one column |
| `category` | closed enum, below | |
| `category_source` | `rule` \| `model` \| `user` | Detector writes only where this is not `user` |
| `user_override` (V1) | `NULL` \| `TRUE` \| `FALSE` | `NULL` means system decision. Detector writes only where `NULL` |
| `amount` (V1) | | Most recent charge amount |
| `previous_amount`, `amount_changed_at` | | Feeds the Phase 4 price-increase alert and a "was $12.99" badge |
| `is_variable_amount` | | Derived from amount coefficient of variation |
| `billing_cycle` (V1) | `weekly` \| `biweekly` \| `semi_monthly` \| `monthly` \| `quarterly` \| `annual` | Computed deterministically, never by the model |
| `next_charge_date` (V1) | | `last_charge + median_gap`. A projection, not a fact |
| `confidence_score` (V1) | 0.00-1.00 | Deterministic formula over interval regularity, amount stability, and charge count |

**`category` enum:**

```text
streaming | ai | software | gaming | news_and_media | fitness_and_health
insurance | storage_and_hosting | telecom | utilities | food_and_delivery
shopping_membership | education | finance | housing | loan | other
```

**`detection_state` tiers:**

| Tier | Evidence | Treatment |
|---|---|---|
| `suspected` | 1 charge plus merchant, descriptor, or price-point signal | Shown separately as "possible"; cycle stays NULL |
| `provisional` | 2 charges, one interval | Estimated cycle, lower score |
| `confirmed` | 3+ charges, regular interval, stable amount | Full record with a projected next charge date |
| `user_confirmed` | The user said so | Authoritative |

### Build

- [x] V3 migration above
- [x] Deterministic recurrence detector over `merchant_key` groups: median gap, median absolute deviation, amount coefficient of variation, cycle bucketing with tolerance bands (7±2 weekly, 14±3 biweekly, 30±5 monthly, 91±10 quarterly, 365±20 annual)
- [x] `detect_streams` job type deduped on `user_id`, dispatched by the existing worker
- [x] Override guard on the detector upsert (`user_override IS NULL`, `category_source <> 'user'`), with integration coverage
- [x] Category assignment in order: Plaid PFC rule mapping, then the model for whatever it cannot resolve, with a user pick overriding both
- [x] Reshape `/internal/classify` to streams and singletons; update its tests
- [x] Replace the classifier stub with an Ollama implementation, defaulting to quantized `gemma3:1b`
- [x] Version and evaluate the classification prompt and structured output; the v2 batch contract returned all labelled keys, with 100% rules accuracy and 60% `gemma3:1b` category accuracy recorded in `ai-service/evals/results.json`
- [x] Recurring charges UI: one continuous confirmed/possible list with group and category tags, group/category filtering, confirmed-only total, and mark/unmark actions
- [x] Accuracy dashboard and false-positive tracking, with an authenticated Plaid `/transactions/recurring/get` benchmark
- [x] Prometheus metrics for the AI service
- [x] HPA for the worker and a PodDisruptionBudget for the AI service

The HPA and PDB manifests are EKS/CKAD exercise artifacts. Do not apply them to the single-node k3s host: Metrics Server and spare schedulable capacity are not guaranteed there, and a one-replica `minAvailable: 1` PDB can block node maintenance.

### Design notes

**Recurrence is arithmetic; the model handles ambiguity.** Periodicity needs no model - it is gaps between dates and variance in amounts, which is cheap, explainable, and unit-testable with fixture data and no network. Plaid carries no subscription or recurring flag on the transaction object (verified; `payment_channel` is location), so the model earns its place on exactly three things: merchant normalization (`AMZN Mktp US*2K4XY7` into a display name), category placement for merchants the rule cannot resolve, and whether a single unmatched charge looks like a signup.

**All three are classification, and that is the whole LLM surface in the product.** Nothing is generated - no letters, no prose, no user-facing text. Every model call maps a short string to a constrained output: a display name or a value from a closed enum. That is exactly what a 1-1.5B quantized model is good at with a tight system prompt, which is why Ollama is the default rather than a fallback, and why the app runs fully offline with no API key. Calls happen inside the `detect_streams` job, so slow CPU inference costs nobody any latency.

**Exclusion is a rule, not a model call.** Income, self-transfers, and credit card payments are dropped by Plaid category before the model sees anything - that is a lookup, not a judgment. Housing and loan payments also land in their categories by rule, since Plaid identifies them reliably. The model is only asked to place merchants the rules cannot, which is a much smaller and better-defined question than "is this a subscription."

**Credit card payments are the subtle exclusion.** The other two are obvious; this one is a double-counting bug waiting to happen. A user who links both a credit card and the checking account that pays it has every card charge captured once on the card, and then the monthly payment captured again as its own recurring charge. Left in, it silently inflates every total on the dashboard.

**Four tiers exist because of cold start.** A three-charge minimum alone makes a new signup invisible for about 60 days, and two years for an annual plan - exactly the trial-converting charges the product exists to catch. Monthly-spend totals count only `confirmed` and `user_confirmed`; a `suspected` row is a false-positive surface, and a wrong number on the dashboard costs more trust than a missed detection.

**The detector must never clobber a user override.** Its upsert runs on every sync. Without the `user_override IS NULL` and `category_source <> 'user'` guards, the failure is silent and reads to the user as "the app keeps undoing my correction." `user_override = FALSE` rows are retained rather than deleted so they cannot be resurrected. This needs a test that fails loudly in CI.

**Category is a closed enum with a source column.** Plaid's taxonomy cannot supply it - there is no software, SaaS, or AI category anywhere in it, and the word "subscription" appears zero times in the published taxonomy CSV. A model allowed to emit free text produces "Streaming", "streaming", "Video Streaming", and "TV & Movies" as four categories inside a week, so the enum is validated Java-side and anything unrecognized falls to `other`. `ai` is split from `software` deliberately: it is the fastest-growing line in most people's recurring spend. `category_source` exists rather than a second boolean because a category is not a yes/no.

**Amount history stays in `transactions`.** Every charge is already stored with its date and amount and grouped by `merchant_key`, so price history is a query. A separate price-history table would duplicate the data and introduce a sync problem for no gain. The three denormalized columns on `recurring_charges` exist only so the dashboard is not recomputing on every render. Variable-amount streams - utilities, phone bills, usage-based AI billing - need different arithmetic: trailing average for dashboard totals, and price-increase alerts suppressed or they fire every month.

### Reshaped `/internal/classify` contract

The Phase 1 stub classifies one transaction at a time, but recurrence is a property of a group over time - a single transaction in isolation cannot be recurring. The contract becomes stream-shaped, carrying both merchant streams and unmatched singletons so one job makes one call.

`billing_cycle` leaves the response entirely because Java computed it. `category` enters it, since placing an unfamiliar merchant in the enum is the same ambiguity judgment as deciding whether the user can act on it at all.

### Open architecture decision

The previous roadmap assumed Socket.IO. Cross-language compatibility between Java and the Node-centric Socket.IO ecosystem adds avoidable complexity. Evaluate server-sent events for one-way dashboard updates, or STOMP/WebSocket for bidirectional messaging, before implementation.

### Depends on

Phase 2's `transactions.merchant_key` and the `job_queue` `type` column, both already shipped.

Plaid's default `user_good` sandbox user has no clean recurring streams. Phase 2 builds a `user_custom` fixture with deliberate monthly, annual, weekly, irregular, and variable-amount charges so this phase does not start blocked on test data.

## Phase 4 - Notifications and Calendar (Planned)

**Goal:** give the product a standing reason to exist. Everything before this is a one-time audit - connect, review, cancel, never return. A summary dashboard, scheduled alerts, and a calendar feed make it ongoing. All three surfaces read the same derived data.

### Schema - `V4__notifications_and_calendar.sql`

```sql
-- What happened. One row per detected event, independent of delivery.
CREATE TABLE notification_events (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id         UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
  charge_id       UUID REFERENCES recurring_charges (id) ON DELETE CASCADE,
  type            TEXT NOT NULL,   -- price_increase | trial_converted
                                   -- | new_charge | charged_after_cancel
  payload         JSONB NOT NULL DEFAULT '{}',
  occurred_at     TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- How it was delivered. One row per (event, channel).
CREATE TABLE notifications_sent (
  id       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  event_id UUID NOT NULL REFERENCES notification_events (id) ON DELETE CASCADE,
  channel  TEXT NOT NULL,   -- discord | slack | email | webhook | push
  sent_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (event_id, channel)
);

CREATE TABLE notification_prefs (
  id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id        UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
  channel        TEXT NOT NULL,
  target         TEXT NOT NULL,   -- webhook URL, email address, push endpoint
  lead_time_days INT NOT NULL DEFAULT 3,
  enabled        BOOLEAN NOT NULL DEFAULT TRUE,
  UNIQUE (user_id, channel)
);

CREATE TABLE calendar_feeds (
  id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id    UUID NOT NULL UNIQUE REFERENCES users (id) ON DELETE CASCADE,
  token_hash TEXT NOT NULL,   -- never the token itself
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  revoked_at TIMESTAMPTZ
);
```

No new job table: notifications use `job_queue` with `run_after` set to the send time.

### Build

- [ ] V4 migration above
- [ ] Summary dashboard: normalized monthly total, active count, and upcoming charges ordered by projected date
- [ ] Longer-horizon surface for annual and quarterly renewals
- [ ] One projection service rendered three ways - dashboard JSON, digest body, ICS feed - so the estimate logic is written once
- [ ] Derive `notification_events` during the Phase 3 detector pass
- [ ] Price-increase detection gated on `is_variable_amount` so utilities do not fire every month
- [ ] ICS calendar feed at a signed, revocable URL
- [ ] Weekly digest job
- [ ] Delivery adapters, cheapest first: Discord/Slack incoming webhook, then email, then user-supplied webhook
- [ ] Notification preferences UI
- [ ] `GET /api/export/charges.csv` and `/api/export/transactions.csv`
- [ ] Published OpenAPI document for the REST API

### Design notes

**Every surface here is an exit, by design.** The ICS feed, the webhooks, the CSV endpoints, and the OpenAPI document all move data out of the app - which is the no-lock-in principle made concrete, and the reason this phase looks different from an incumbent's version of it despite covering similar ground.

**Alert on the unexpected, not the expected.** "Netflix charges $15.99 in 3 days" tells the user something they already know, and twelve subscriptions means twelve interruptions a month - which is how notification settings get switched off. The four `notification_events` types are all derived rather than scheduled, and `charged_after_cancel` is the highest-value one in the product because it catches the failure the user cannot catch once they have stopped looking. Per-charge reminders belong on the calendar feed, where the user's own client decides how to remind them; a weekly digest covers the rest at one job per user per week instead of one per charge per cycle.

**The summary shows two different numbers.** "What do I spend per month" and "what is leaving my account on Tuesday" need different arithmetic, and conflating them is the standard bug in this kind of dashboard. The monthly total normalizes every cycle to a monthly equivalent - a $120 annual plan contributes $10, a $5 weekly plan about $21.70 - because summing raw amounts across mixed cycles produces a meaningless number. Upcoming charges stay unnormalized. `next_charge_date` is a projection, so a two-charge `provisional` stream must not render with the same certainty as a twelve-charge `confirmed` one.

**Annual charges need their own surface.** A 30-day window hides exactly what this product exists to catch: a $200 annual renewal is invisible eleven months out of twelve and is the most forgettable recurring charge there is. Annual and quarterly cycles need a longer horizon and a lead time in weeks.

**The calendar feed is a capability URL.** A calendar client polls unauthenticated, on its own schedule, indefinitely, so it cannot carry a JWT and the URL itself becomes the credential. Hence `token_hash` rather than the token, revocation that 404s the old URL immediately, and its own rate limit - the same exception already carved out for the Plaid webhook. That URL exposes a user's full list of recurring charges and amounts to anyone holding it, and calendar URLs end up in phone backups, shared family calendars, and screenshots, so decide deliberately whether amounts belong in the event title or only the description.

**Write `notifications_sent` before the send, not after.** The worker is at-least-once, so a crash between sending and bookkeeping resends the message. A duplicate transaction row is invisible; a duplicate text at 6am is not. Trading a possible missed notification - detectable and re-drivable - for never double-sending is the right way round. Note the `job_queue` partial unique index does not help here: it covers only `pending` and `running`, so a completed notification job falls out of the index and nothing prevents a re-enqueue.

### Channel notes

- Discord, Slack, and Telegram are each a webhook URL or bot token plus an HTTP POST. Start here.
- Email needs a provider and SPF/DKIM/DMARC or it lands in spam, but it is the channel users expect for financial notices.
- Web Push works in Chrome, Firefox, and iOS Safari 16.4+ (home-screen install required) with no per-message cost.
- SMS carries the most friction: US A2P 10DLC registration, roughly $4 brand and $15 campaign vetting one-time plus $2/month on Twilio's sole-proprietor path, on top of per-message cost and throttled throughput. Last, if ever.

### Depends on

Phase 3 for `next_charge_date`, charge history, and `is_variable_amount`. Nothing else here is blocked.

The `charged_after_cancel` event type is the exception - it needs Phase 5's attempt tracking. Build the type and its delivery path here; switch the derivation on in Phase 5.

The calendar feed also wants a permanently reachable public URL, since clients poll it directly. A tunnel covers development; the real URL arrives with Phase 5's Ingress and TLS.

## Phase 5 - Cancelation Engine and Public Deployment (Planned)

**Goal:** close the loop by helping users actually cancel, and stand up a permanently reachable public demo with HTTPS and continuous delivery.

### Hosting - k3s on one small VPS, ~EUR 5.49/month

The standing instance runs **k3s on a single Hetzner CX23** (2 vCPU / 4 GiB, x86). Not EKS - see the EKS exercise below for why that is separate.

| Workload | Memory |
|---|---|
| core-api with `BILLGUARD_WORKER_ENABLED=true` | ~250 MiB (measured idle: 241.9 MiB) |
| ai-service | ~100 MiB |
| Ollama `gemma3:1b` | Transient; 815 MB model artifact, ~980 MiB measured loaded RSS |
| web-app | ~100 MiB |
| Postgres, in-cluster | ~150 MiB |
| k3s itself | ~500 MiB |
| **Baseline excluding loaded model** | **~1.1 GiB of 4 GiB** |

**Collapsing the worker into the API pod is the whole trick** and it is already a config flag. One user's demo has no reason to run a second JVM. `k8s/worker-deployment.yaml` stays in the repo as the separated-workload demonstration and simply is not scheduled here.

- k3s bundles Traefik ingress and local-path storage, so every existing manifest applies unchanged
- cert-manager plus Let's Encrypt for TLS, free
- In-cluster Postgres via the existing `k8s/postgres.yaml`, not RDS
- Domain roughly $12/year; ACM is not involved

Hetzner raised cloud prices up to 3.1x in June 2026, so older figures are stale. Their ARM CAX11 is now EUR 5.99, *more* than x86, so there is no ARM discount to chase and no multi-arch build burden. Stay on x86.

### The EKS exercise - time-boxed, not standing

EKS is worth doing once and not worth leaving running. Stand it up for roughly two weeks during active interviewing, capture the manifests, screenshots, and notes, then tear it down. About **$45 for the window** against **~$190/month** if left up.

| Item | Monthly |
|---|---|
| EKS control plane | $73, charged whether or not a pod is scheduled |
| 2x t3.medium nodes | ~$60 |
| RDS `db.t4g.micro` | ~$22 |
| ALB | ~$16 plus LCU |
| EBS, ECR, Secrets Manager, Route53, logs, transfer | ~$10 |

**Set a billing alarm on day one.** The $0.10/hour control-plane rate is *standard support*, covering about 14 months after a Kubernetes version ships. After that a cluster silently moves to extended support at **$0.60/hour, roughly $438/month**, on infrastructure you have stopped thinking about.

Nobody checks whether the demo is on EKS today. They ask whether you have deployed to it, and a two-week run answers that identically to a two-year one.

### Deployment tiers

The public instance is a reference deployment and a live demo, not "the product." A self-hoster never touches it - their path is Compose or Helm from Phase 6.

| Tier | Who | Plaid | Data |
|---|---|---|---|
| **Public demo** | Anyone, open Auth0 signup | `PLAID_ENV=sandbox` | Fixture only. No real financial data ever |
| **Author instance** | Ray | Trial plan, 10 Item cap | Real, private, not public |
| **Self-hosted** | Anyone who wants it for real | Their own keys and plan | Theirs, on their infrastructure |

Plaid's Trial plan is free and auto-approved for US and Canada developers but caps at **10 Production Items**, and full Production requires passing a compliance review. Open public signup against real bank data is therefore not available regardless of appetite for the liability - which is precisely why self-hosting is the shipping model rather than a hosted service.

The demo's safety is structural, not policy: with `PLAID_ENV=sandbox`, real bank credentials cannot work. Plaid rejects them at its own Link screen, which BillGuard never sees. There is no path where a visitor accidentally hands the demo real financial data.

A public HTTPS instance is also load-bearing for Phase 4 rather than decorative - calendar clients poll the ICS feed directly, so it needs a permanently reachable URL.

### Schema - `V5__cancelation.sql`

```sql
-- Global catalog, not per-user: how you cancel a given merchant.
CREATE TABLE cancelation_methods (
  id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  merchant_key TEXT NOT NULL UNIQUE,
  method       TEXT NOT NULL,   -- email | link | phone | in_app
  url          TEXT,
  email        TEXT,
  phone        TEXT,
  instructions TEXT,
  updated_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Per-user attempts. Feeds the charged_after_cancel alert from Phase 4.
CREATE TABLE cancelation_attempts (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id         UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
  charge_id       UUID NOT NULL REFERENCES recurring_charges (id) ON DELETE CASCADE,
  method          TEXT NOT NULL,
  status          TEXT NOT NULL DEFAULT 'started',  -- started|sent|confirmed|failed
  generated_body  TEXT,
  attempted_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  resolved_at     TIMESTAMPTZ
);
```

`recurring_charges.cancelation_method` and `cancelation_url` already exist from V1 and become a denormalized copy of the catalog row for the merchant.

### Build

- [ ] V5 migration above
- [ ] Merchant cancelation catalog, seeded for the most common merchants. The engine runs only on the Subscriptions group; Bills get renegotiation guidance and Fixed charges get neither.
- [ ] Cancelation email from a **template** with merchant, account, and charge substitution. Not model-generated - see the design note
- [ ] Mail launch/copy workflow and attempt tracking
- [ ] Switch on the `charged_after_cancel` derivation deferred from Phase 4

Standing deployment:

- [ ] Provision the CX23, install k3s, apply the existing manifests
- [ ] cert-manager plus a Let's Encrypt ClusterIssuer, Traefik Ingress, domain pointed at the box
- [ ] Run core-api with `BILLGUARD_WORKER_ENABLED=true`; leave `worker-deployment.yaml` unscheduled
- [ ] GitHub Actions image push to `ghcr.io` and rollout on merge to main
- [ ] NetworkPolicy allowing AI traffic only from the core workload. **Confirm k3s enforces it** - its bundled controller should, but an unenforced NetworkPolicy is worse than none since it reads as protection that is not there
- [ ] Off-box Postgres backup, since local-path storage dies with the node

EKS exercise, time-boxed:

- [ ] EKS cluster and ECR repositories
- [ ] External Secrets Operator backed by AWS Secrets Manager
- [ ] AWS Load Balancer Controller, Ingress, ACM TLS
- [ ] RDS PostgreSQL
- [ ] IRSA for pod-level IAM
- [ ] Billing alarm before anything else, and a calendar entry for the Kubernetes version EOL
- [ ] Capture manifests, screenshots, and notes, then destroy the cluster

### Design notes

**The catalog is global, attempts are per-user.** How you cancel Netflix is the same fact for everyone, so `cancelation_methods` is keyed on `merchant_key` alone. Only `cancelation_attempts` carries `user_id`. Merging them would duplicate the catalog per user and make it impossible to improve one merchant's instructions for everybody at once.

**Letters are templated, not generated.** A cancelation email goes out under the user's name, to a company, asserting facts about their account. A model that invents an account number, a wrong cancelation policy, or a legal claim the user did not make causes real harm - and the user cannot easily tell, because the output reads fluently either way. Templates with substitution are deterministic, reviewable in a diff, testable, and produce better letters than a 1.5B model would anyway. The same reasoning applies to Phase 6's refund letters, more strongly, since those make legal-adjacent claims.

This also keeps the model's entire job inside one shape: classification into constrained outputs. Nothing the LLM produces is ever shown to a third party as the user's own words.

**Secrets come from an interface, not from AWS.** External Secrets Operator backed by Secrets Manager is one implementation; a `.env` file is another. Wiring the application directly to AWS would make Phase 6 impossible, since a self-hoster has no Secrets Manager. Keeping the seam means the AWS deployment is one consumer of a config surface rather than a hard dependency - the self-hosting constraint improving the AWS work rather than competing with it.

**Sizing.** The non-model baseline uses roughly 1.1 GiB, and `OLLAMA_KEEP_ALIVE=0` unloads `gemma3:1b` after each queued batch. Under Docker Desktop's 3.829 GiB limit, the loaded model used about 980 MiB and the two-JVM Compose stack peaked near 1.46 GiB with no OOM-killed containers. Production collapses the worker into the API, leaving additional headroom. Running both JVMs separately adds about 250 MiB and is the first thing to change if the demo ever needs to show queue concurrency for real.

**Two cheaper floors exist, both with caveats.** Oracle Cloud Always Free still runs this - though Oracle silently cut it from 4 OCPU / 24 GiB to 2 OCPU / 12 GiB on 15 June 2026, Always Free ARM capacity is a lottery by region, and idle accounts have been terminated. A spare always-on machine behind a Cloudflare Tunnel is the more dependable $0 option, with free public HTTPS and no port forwarding. Neither is a good bet for a demo linked from a resume, which is what the EUR 5.49 buys.

## Phase 6 - Self-Hosting and Polish (Planned)

**Goal:** make the project deployable by someone who is not the author, and finish the edges.

### Schema

None.

### Build - self-hosting

The target experience is **two files and one command** - no clone, no build toolchain:

```bash
curl -O https://raw.githubusercontent.com/<repo>/main/docker-compose.yml
curl -o .env https://raw.githubusercontent.com/<repo>/main/.env.example
# edit .env
docker compose up -d
```

- [ ] Publish images to `ghcr.io` from CI, tagged by version and digest. The deploy job in `ci.yml` is still commented out and builds nothing today
- [ ] A published `docker-compose.yml` that pulls those images instead of building from context
- [ ] **Decouple identity from Auth0.** The core API is already a generic OAuth2 resource server validating any RS256 JWKS issuer - only the naming and the frontend SDK are coupled:
  - [ ] Rename `AUTH0_DOMAIN` to `OIDC_ISSUER_URI`, taking the full URL rather than templating `https://${DOMAIN}/`, which is itself an Auth0 assumption. `AUTH0_AUDIENCE` becomes `OIDC_AUDIENCE`
  - [ ] Replace `@auth0/auth0-spa-js` with a generic OIDC client so the browser is not Auth0-bound either
  - [ ] Verify against a second provider - Keycloak or Authentik - since homelab self-hosters usually already run one
- [ ] Setup guide: obtaining Plaid Trial plan credentials, pointing at an OIDC provider, generating `TOKEN_ENCRYPTION_KEY`
- [ ] Document that **webhooks are optional**. A self-hoster behind NAT cannot receive them, but the daily CronJob sync covers the same ground more slowly. Nobody should think a tunnel is mandatory
- [ ] Startup validation that fails fast with an actionable message for every missing or malformed required variable
- [ ] Helm chart or kustomize overlay, so the Kubernetes path is reusable without editing manifests in place
- [ ] No hardcoded hostnames, ports, or author-specific assumptions anywhere in the deployment path
- [ ] Verify the MIT license covers the dependency set

### Build - polish

- [ ] Refund demand letter from a template, same reasoning as the cancelation email in Phase 5
- [ ] Monthly savings summary
- [ ] Loading, empty, and error states across the frontend
- [ ] Log sidecar on the AI service
- [ ] ServiceAccounts, Roles, and RoleBindings
- [ ] Enforced dev/prod namespace separation
- [ ] Consistent resource labels and annotations
- [ ] Architecture diagram and interview reference material

### Design notes

**Self-hosting is a test of the infrastructure, not a feature bolted onto it.** Every configuration shortcut taken for local development surfaces here: an assumed hostname, an undocumented variable, a secret that only exists on one machine. Fixing those is the same work as making the deployment genuinely reproducible, which is why it lands after Phase 5 rather than before - EKS forces most of it into the open first.

**Identity setup is where self-hosters give up.** Today a stranger has to create an Auth0 account, a SPA application, *and* an API with an audience, configure callback URLs, then copy four values across two env files. Generic OIDC turns that into pasting one issuer URL for anyone already running a provider. It is also the better claim: "I implemented OIDC" rather than "I integrated Auth0."

**Plaid credentials stay the user's problem, deliberately.** Proxying Plaid through the author's account would make the author the data controller for every self-hoster's bank data - the exact outcome self-hosting exists to prevent. A ten-minute signup is the correct cost.

Flyway already owns migrations, so the old manual `kubectl exec psql` step and a separate migration Job are unnecessary. Revisit a migration Job only if deployment policy later requires schema changes to finish before any new application pod starts.

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
| HPA / PDB | 3 | `k8s/worker-hpa.yaml`, `k8s/ai-service-pdb.yaml` (EKS exercise only) |
| Ingress / NetworkPolicy | 5 | Traefik + cert-manager on k3s; ALB + ACM during the EKS exercise |
| Sidecar / RBAC | 6 | Planned |
