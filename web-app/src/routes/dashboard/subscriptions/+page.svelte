<script lang="ts">
  import { onMount } from "svelte";
  import DashboardShell from "$lib/components/DashboardShell.svelte";
  import Icon from "$lib/components/Icon.svelte";
  import RecurringToggle from "$lib/components/RecurringToggle.svelte";
  import SearchField from "$lib/components/SearchField.svelte";
  import { api } from "$lib/api";
  import { notify } from "$stores/notifications";

  interface Charge {
    id: string;
    merchant_key: string;
    merchant_name: string;
    display_name: string;
    amount: number;
    previous_amount: number | null;
    currency: string;
    billing_cycle: string | null;
    next_charge_date: string | null;
    confidence_score: number;
    detection_state:
      "confirmed" | "provisional" | "suspected" | "user_confirmed";
    category: string;
    category_source: string;
    amount_changed_at: string | null;
    is_variable_amount: boolean;
    first_seen_at: string;
    last_seen_at: string;
    user_override: boolean | null;
    group: Group;
    monthly_equivalent: number;
  }
  interface Accuracy {
    system_detected: number;
    confirmed_feedback: number;
    false_positives: number;
    false_negatives: number;
    precision_feedback: number;
  }
  interface DetectionStatus {
    status: "idle" | "pending" | "running" | "done" | "failed";
  }
  interface PlaidItemStatus {
    last_synced_at: string | null;
  }
  type Group = "subscriptions" | "bills" | "fixed_expenses";
  interface GroupDefinition {
    key: Group;
    title: string;
    categories: string[];
  }

  const groups: GroupDefinition[] = [
    {
      key: "subscriptions",
      title: "Subscriptions",
      categories: [
        "streaming",
        "ai",
        "software",
        "gaming",
        "news_and_media",
        "fitness_and_health",
        "storage_and_hosting",
        "food_and_delivery",
        "shopping_membership",
        "education",
        "finance",
        "other",
      ].sort((left, right) => label(left).localeCompare(label(right))),
    },
    {
      key: "bills",
      title: "Bills",
      categories: ["insurance", "telecom", "utilities"].sort((left, right) =>
        label(left).localeCompare(label(right)),
      ),
    },
    {
      key: "fixed_expenses",
      title: "Fixed expenses",
      categories: ["housing", "loan"].sort((left, right) =>
        label(left).localeCompare(label(right)),
      ),
    },
  ];
  const relativeDateFormatter = new Intl.RelativeTimeFormat(undefined, {
    numeric: "auto",
  });
  const analysisPollIntervalMs = 1_000;
  const analysisTimeoutMs = 120_000;

  let charges: Charge[] = [];
  let visibleCharges: Charge[] = [];
  let visibleChargeCount = 0;
  let filteredConfirmedTotal = 0;
  let accuracy: Accuracy | null = null;
  let selectedFilter = "";
  let search = "";
  let loading = true;
  let analyzing = false;
  let updating = "";
  let loadSequence = 0;
  let destroyed = false;

  $: {
    const selectedGroup = groups.find(
      (group) => selectedFilter === `group:${group.key}`,
    );
    const categoryMatches = selectedGroup
      ? charges.filter((charge) => charge.group === selectedGroup.key)
      : selectedFilter
        ? charges.filter((charge) => charge.category === selectedFilter)
        : charges;
    const query = search.trim().toLocaleLowerCase();
    visibleCharges = query
      ? categoryMatches.filter((charge) =>
          [
            charge.display_name,
            charge.merchant_name,
            label(charge.category),
            groupLabel(charge.group),
          ].some((value) => value.toLocaleLowerCase().includes(query)),
        )
      : categoryMatches;
    visibleCharges = [...visibleCharges].sort(
      (left, right) =>
        Number(isConfirmed(left)) - Number(isConfirmed(right)) ||
        left.display_name.localeCompare(right.display_name, undefined, {
          sensitivity: "base",
        }),
    );
    visibleChargeCount = visibleCharges.length;
    filteredConfirmedTotal = visibleCharges
      .filter(isConfirmed)
      .reduce((sum, charge) => sum + charge.monthly_equivalent, 0);
  }

  onMount(() => {
    destroyed = false;
    void initialize();
    return () => {
      destroyed = true;
    };
  });

  async function initialize() {
    await load();
    try {
      const items = await api.get<PlaidItemStatus[]>("/api/plaid/items");
      if (items.length === 0) return;

      const waitingForFirstSync = items.some((item) => !item.last_synced_at);
      const detection = await api.get<DetectionStatus>(
        "/api/recurring-charges/detect/status",
      );
      if (
        !waitingForFirstSync &&
        detection.status !== "pending" &&
        detection.status !== "running"
      ) {
        return;
      }

      analyzing = true;
      const status = await waitForAnalysis(waitingForFirstSync);
      if (!destroyed && status === "done") await load();
      if (!destroyed && status === "failed") {
        notify.error(
          "Automatic recurring-charge analysis failed. Try Analyze now.",
        );
      }
    } catch {
      // The page remains usable when background-status polling is unavailable.
    } finally {
      if (!destroyed) analyzing = false;
    }
  }

  async function load(): Promise<boolean> {
    const sequence = ++loadSequence;
    loading = true;
    try {
      const [nextCharges, nextAccuracy] = await Promise.all([
        api.get<Charge[]>("/api/recurring-charges"),
        api.get<Accuracy>("/api/recurring-charges/accuracy"),
      ]);

      if (sequence === loadSequence) {
        charges = nextCharges;
        accuracy = nextAccuracy;
        return true;
      }
    } catch (value) {
      if (sequence === loadSequence) {
        notify.error(message(value, "Could not load recurring charges."));
      }
    } finally {
      if (sequence === loadSequence) {
        loading = false;
      }
    }
    return false;
  }

  async function analyze() {
    analyzing = true;
    try {
      await api.post<{ enqueued: boolean }>(
        "/api/recurring-charges/detect",
        {},
      );
      const status = await waitForAnalysis(false);
      if (status === "failed") {
        throw new Error("We couldn't complete the analysis. Please try again.");
      }
      if (status === "timeout") {
        throw new Error(
          "Analysis is taking longer than expected. Please try again shortly.",
        );
      }
      if (await load()) {
        notify.success("Recurring-charge analysis complete.");
      }
    } catch (value) {
      notify.error(message(value, "Could not analyze recurring charges."));
    } finally {
      analyzing = false;
    }
  }

  async function waitForAnalysis(
    waitingForFirstSync: boolean,
  ): Promise<"done" | "failed" | "timeout"> {
    const deadline = Date.now() + analysisTimeoutMs;

    while (!destroyed && Date.now() < deadline) {
      const [detection, items] = await Promise.all([
        api.get<DetectionStatus>("/api/recurring-charges/detect/status"),
        waitingForFirstSync
          ? api.get<PlaidItemStatus[]>("/api/plaid/items")
          : Promise.resolve([]),
      ]);
      const firstSyncPending = items.some((item) => !item.last_synced_at);
      if (
        (!waitingForFirstSync || !firstSyncPending) &&
        (detection.status === "done" || detection.status === "failed")
      ) {
        return detection.status;
      }
      await delay(analysisPollIntervalMs);
    }

    return "timeout";
  }

  function delay(milliseconds: number): Promise<void> {
    return new Promise((resolve) => setTimeout(resolve, milliseconds));
  }

  async function updateCharge(
    id: string,
    body: { recurring?: boolean; category?: string },
  ) {
    updating = id;
    try {
      await api.patch(`/api/recurring-charges/${id}`, body);
      if (await load()) {
        notify.success(updateMessage(body));
      }
    } catch (value) {
      notify.error(message(value, "Could not update recurring charge."));
    } finally {
      updating = "";
    }
  }

  function updateMessage(body: {
    recurring?: boolean;
    category?: string;
  }): string {
    if (body.recurring === true) return "Recurring charge confirmed.";
    if (body.recurring === false) return "Charge marked as not recurring.";
    return "Category updated.";
  }

  function message(value: unknown, fallback: string): string {
    return value instanceof Error ? value.message : fallback;
  }

  function money(amount: number, currency = "USD"): string {
    return new Intl.NumberFormat(undefined, {
      style: "currency",
      currency,
    }).format(amount);
  }

  function label(value: string): string {
    if (value === "ai") return "AI";
    const spaced = value.replaceAll("_", " ");
    return spaced.charAt(0).toUpperCase() + spaced.slice(1);
  }

  function groupLabel(group: Group): string {
    return (
      groups.find((definition) => definition.key === group)?.title ??
      label(group)
    );
  }

  function groupTagLabel(group: Group): string {
    return {
      subscriptions: "Subscription",
      bills: "Bill",
      fixed_expenses: "Fixed expense",
    }[group];
  }

  function isConfirmed(charge: Charge): boolean {
    return (
      charge.detection_state === "confirmed" ||
      charge.detection_state === "user_confirmed"
    );
  }

  function relativeDate(value: string): string {
    const [year, month, day] = value.slice(0, 10).split("-").map(Number);
    if (!year || !month || !day) return "";

    const now = new Date();
    const today = Date.UTC(now.getFullYear(), now.getMonth(), now.getDate());
    const target = Date.UTC(year, month - 1, day);
    const differenceInDays = Math.round((target - today) / 86_400_000);
    const distance = Math.abs(differenceInDays);
    if (distance < 14) {
      return relativeDateFormatter.format(differenceInDays, "day");
    }
    if (distance < 60) {
      return relativeDateFormatter.format(
        Math.round(differenceInDays / 7),
        "week",
      );
    }
    if (distance < 730) {
      return relativeDateFormatter.format(
        Math.round(differenceInDays / 30.4375),
        "month",
      );
    }
    return relativeDateFormatter.format(
      Math.round(differenceInDays / 365.25),
      "year",
    );
  }
</script>

<DashboardShell active="subscriptions">
  <header class="page-header">
    <div>
      <h1>Recurring charges</h1>
      <p>
        Confirmed monthly spend excludes possible charges until you approve
        them.
      </p>
    </div>
    <button
      class="primary"
      type="button"
      disabled={analyzing}
      on:click={analyze}
    >
      {analyzing ? "Analyzing..." : "Analyze now"}
    </button>
  </header>

  <section class="summary" aria-label="Confirmed recurring spending">
    <div>
      <span>Confirmed monthly</span><strong
        >{money(filteredConfirmedTotal)}</strong
      >
    </div>
    <div>
      <span>Automatic detections</span><strong
        >{accuracy?.system_detected ?? 0}</strong
      >
    </div>
    <div>
      <span>Marked not recurring</span><strong
        >{accuracy?.false_positives ?? 0}</strong
      >
    </div>
    <div>
      <span>Manually added</span><strong
        >{accuracy?.false_negatives ?? 0}</strong
      >
    </div>
  </section>

  <div class="toolbar">
    <SearchField
      bind:value={search}
      label="Search recurring charges"
      placeholder="Search recurring charges"
    />
    <label for="charge-filter">Category</label>
    <select id="charge-filter" bind:value={selectedFilter}>
      <option value="">All</option>
      {#each groups as categoryGroup}
        <option value={`group:${categoryGroup.key}`}>
          {categoryGroup.title}
        </option>
        {#each categoryGroup.categories as option}
          <option value={option}>&nbsp;&nbsp;{label(option)}</option>
        {/each}
      {/each}
    </select>
    <span class="filter-status" aria-live="polite">
      Showing {visibleChargeCount} of {charges.length}
    </span>
    <button
      class="secondary refresh-button"
      type="button"
      disabled={loading}
      aria-label="Refresh recurring charges"
      title="Refresh recurring charges"
      on:click={() => load()}
    >
      <Icon name="refresh" />
    </button>
  </div>

  {#if loading && charges.length === 0}
    <div class="empty">Loading recurring charges...</div>
  {:else if charges.length === 0}
    <div class="empty">
      <h2>No recurring charges yet</h2>
      <p>Connect an account and allow its first sync to finish.</p>
    </div>
  {:else if visibleCharges.length === 0}
    <div class="empty">
      <h2>No charges match this filter</h2>
      <p>Choose another filter or select All.</p>
    </div>
  {:else}
    <div class="charge-list">
      {#each visibleCharges as charge}
        <article class="charge-card" class:possible={!isConfirmed(charge)}>
          <div class="charge-main">
            <h2>{charge.display_name}</h2>
            <div class="price">
              <strong>{money(charge.amount, charge.currency)}</strong>
              <span
                >{charge.billing_cycle
                  ? `/${charge.billing_cycle.replaceAll("_", " ")}`
                  : "latest charge"}</span
              >
            </div>
          </div>
          <div class="metadata">
            <span
              class="badge group-badge"
              class:subscriptions-tag={charge.group === "subscriptions"}
              class:bills-tag={charge.group === "bills"}
              class:fixed-expenses-tag={charge.group === "fixed_expenses"}
              >{groupTagLabel(charge.group)}</span
            >
            {#if !isConfirmed(charge)}
              <span class="badge possible-badge">
                Possible &middot; {Math.round(charge.confidence_score * 100)}%
                confidence
              </span>
            {/if}
            {#if charge.is_variable_amount}<span class="badge"
                >Variable amount</span
              >{/if}
            {#if charge.amount_changed_at}<span class="changed"
                >Amount changed {charge.amount_changed_at}</span
              >{/if}
            {#if charge.next_charge_date}
              <span class="row-date">
                Next expected {relativeDate(charge.next_charge_date)} &middot; {charge.next_charge_date}
              </span>
            {:else if !isConfirmed(charge)}
              <span class="row-date">
                Last seen {relativeDate(charge.last_seen_at)} &middot; {charge.last_seen_at}
              </span>
            {/if}
          </div>
          <div class="actions">
            <select
              aria-label={`Category for ${charge.display_name}`}
              value={charge.category}
              disabled={updating === charge.id}
              on:change={(event) =>
                updateCharge(charge.id, {
                  category: event.currentTarget.value,
                })}
            >
              {#each groups as categoryGroup}
                <optgroup label={categoryGroup.title}>
                  {#each categoryGroup.categories as option}
                    <option value={option}>{label(option)}</option>
                  {/each}
                </optgroup>
              {/each}
            </select>
            {#if isConfirmed(charge)}
              <RecurringToggle
                active={true}
                updating={updating === charge.id}
                tooltipId={`charge-recurring-tooltip-${charge.id}`}
                on:toggle={() => updateCharge(charge.id, { recurring: false })}
              />
            {:else}
              <div
                class="recurring-question"
                role="group"
                aria-labelledby={`charge-question-${charge.id}`}
              >
                <span id={`charge-question-${charge.id}`}
                  >Is this recurring?</span
                >
                <button
                  class="answer yes"
                  type="button"
                  disabled={updating === charge.id}
                  on:click={() => updateCharge(charge.id, { recurring: true })}
                  >Yes</button
                >
                <button
                  class="answer no"
                  type="button"
                  disabled={updating === charge.id}
                  on:click={() => updateCharge(charge.id, { recurring: false })}
                  >No</button
                >
              </div>
            {/if}
          </div>
        </article>
      {/each}
    </div>
  {/if}
</DashboardShell>

<style>
  .page-header,
  .charge-main,
  .toolbar,
  .metadata,
  .actions {
    align-items: center;
    display: flex;
  }
  .page-header,
  .charge-main {
    justify-content: space-between;
  }
  .page-header {
    gap: 20px;
    margin-bottom: 24px;
  }
  h1 {
    font-size: 24px;
    letter-spacing: -0.02em;
  }
  h2 {
    font-size: 17px;
  }
  p,
  span,
  label {
    color: var(--text-muted);
    font-size: 12px;
  }
  .primary,
  .secondary,
  select {
    border: 1px solid var(--border);
    border-radius: 7px;
    color: var(--text);
    cursor: pointer;
    font-family: var(--font-sans);
    padding: 8px 12px;
  }
  .primary {
    background: var(--accent);
    color: var(--accent-contrast);
    font-weight: 650;
  }
  .secondary,
  select {
    background: var(--surface-2);
  }
  button:disabled,
  select:disabled {
    cursor: wait;
    opacity: 0.55;
  }
  .summary {
    display: grid;
    gap: 1px;
    grid-template-columns: repeat(4, 1fr);
    margin-bottom: 18px;
    overflow: hidden;
    border: 1px solid var(--border);
    border-radius: var(--radius);
    background: var(--border);
  }
  .summary div {
    background: var(--surface);
    padding: 17px;
  }
  .summary span,
  .summary strong {
    display: block;
  }
  .summary strong {
    font-family: var(--font-mono);
    font-size: 18px;
    margin-top: 3px;
  }
  .toolbar {
    flex-wrap: wrap;
    gap: 9px;
    margin-bottom: 18px;
  }
  .refresh-button {
    align-items: center;
    display: inline-flex;
    height: 36px;
    justify-content: center;
    margin-left: auto;
    padding: 0;
    width: 36px;
  }
  .filter-status {
    white-space: nowrap;
  }
  select {
    text-transform: capitalize;
  }
  .empty {
    background: var(--surface);
    border: 1px solid var(--border);
    border-radius: var(--radius);
    margin-bottom: 18px;
    padding: 18px;
  }
  .empty {
    padding: 40px;
    text-align: center;
  }
  .charge-list {
    background: var(--surface);
    border: 1px solid var(--border);
    border-radius: var(--radius);
    display: grid;
    overflow: hidden;
  }
  .charge-card {
    background: var(--surface);
    padding: 18px;
  }
  .charge-card + .charge-card {
    border-top: 1px solid var(--border);
  }
  .charge-main {
    gap: 16px;
  }
  .price {
    text-align: right;
  }
  .price strong {
    display: block;
    font-family: var(--font-mono);
  }
  .metadata {
    flex-wrap: wrap;
    gap: 7px;
    margin-top: 10px;
  }
  .badge {
    border: 1px solid var(--border);
    border-radius: 999px;
    padding: 2px 7px;
  }
  .group-badge {
    font-weight: 650;
  }
  .subscriptions-tag {
    background: rgba(139 92 246 / 0.15);
    border-color: rgba(167 139 250 / 0.35);
    color: #c4b5fd;
  }
  .bills-tag {
    background: rgba(59 130 246 / 0.15);
    border-color: rgba(96 165 250 / 0.35);
    color: #93c5fd;
  }
  .fixed-expenses-tag {
    background: rgba(249 115 22 / 0.15);
    border-color: rgba(251 146 60 / 0.35);
    color: #fdba74;
  }
  .possible-badge {
    color: var(--warning);
  }
  .changed {
    color: var(--warning);
  }
  .row-date {
    margin-left: auto;
    text-align: right;
  }
  .actions {
    border-top: 1px solid var(--border);
    gap: 8px;
    margin-top: 12px;
    padding-top: 12px;
  }
  .actions select {
    margin-right: auto;
    max-width: 190px;
  }
  .recurring-question {
    align-items: center;
    display: flex;
    gap: 7px;
  }
  .recurring-question > span {
    margin-right: 2px;
  }
  .answer {
    background: transparent;
    border: 1px solid var(--border);
    border-radius: 6px;
    color: var(--text-muted);
    cursor: pointer;
    font-family: var(--font-sans);
    padding: 6px 10px;
  }
  .answer.yes:hover:not(:disabled) {
    border-color: var(--warning);
    color: var(--warning);
  }
  .answer.no:hover:not(:disabled) {
    border-color: var(--danger);
    color: var(--danger);
  }
  .possible {
    box-shadow: inset 3px 0 var(--warning);
  }
  @media (max-width: 760px) {
    .summary {
      grid-template-columns: repeat(2, 1fr);
    }
    .page-header {
      align-items: flex-start;
    }
  }
</style>
