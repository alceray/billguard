<script lang="ts">
  import { onMount } from "svelte";
  import DashboardShell from "$lib/components/DashboardShell.svelte";
  import ConnectBankButton from "$lib/components/ConnectBankButton.svelte";
  import { api } from "$lib/api";
  import { notify } from "$stores/notifications";

  interface Account {
    id: string;
    name: string;
    official_name: string | null;
    type: string;
    subtype: string | null;
    mask: string | null;
  }
  interface PlaidItem {
    id: string;
    item_id: string;
    institution_name: string | null;
    status: string;
    last_error: string | null;
    last_synced_at: string | null;
    accounts: Account[];
  }

  let items: PlaidItem[] = [];
  let loading = true;
  let syncing = false;
  let disconnectingId: string | null = null;

  onMount(loadItems);

  async function loadItems() {
    loading = true;
    try {
      items = await api.get<PlaidItem[]>("/api/plaid/items");
    } catch (value) {
      notify.error(message(value, "Could not load connected accounts."));
    } finally {
      loading = false;
    }
  }

  async function syncNow() {
    syncing = true;
    try {
      await api.post("/api/plaid/sync", {});
      notify.success("Bank sync is running in the background.");
    } catch (value) {
      notify.error(message(value, "Could not start a bank sync."));
    } finally {
      syncing = false;
    }
  }

  async function disconnect(item: PlaidItem) {
    const institution = item.institution_name ?? "this institution";
    const confirmed = window.confirm(
      `Disconnect ${institution}? BillGuard will revoke access and delete its accounts and transactions.`,
    );
    if (!confirmed) return;

    disconnectingId = item.id;
    try {
      await api.del<void>(`/api/plaid/items/${encodeURIComponent(item.id)}`);
      items = items.filter((connectedItem) => connectedItem.id !== item.id);
      notify.success(`${institution} was disconnected.`);
    } catch (value) {
      notify.error(message(value, "Could not disconnect the bank."));
    } finally {
      disconnectingId = null;
    }
  }

  function formatTime(value: string | null): string {
    return value ? new Date(value).toLocaleString() : "Waiting for first sync";
  }

  function message(value: unknown, fallback: string): string {
    return value instanceof Error ? value.message : fallback;
  }
</script>

<DashboardShell active="overview">
  <header class="page-header">
    <div>
      <h1>Overview</h1>
      <p>Your connected banks and ingestion status</p>
    </div>
    <ConnectBankButton on:connected={loadItems} />
  </header>

  {#if loading}
    <div class="state">Loading connected accounts…</div>
  {:else if items.length === 0}
    <div class="empty-state">
      <div class="empty-icon">↗</div>
      <h2>Connect your bank to get started</h2>
      <p>
        BillGuard uses Plaid to read transactions. Your bank credentials never
        reach BillGuard.
      </p>
      <ConnectBankButton on:connected={loadItems} />
    </div>
  {:else}
    <div class="section-heading">
      <div>
        <h2>Connected accounts</h2>
        <p>
          {items.length} connected institution{items.length === 1 ? "" : "s"}
        </p>
      </div>
      <button
        class="secondary"
        type="button"
        disabled={syncing}
        on:click={syncNow}
      >
        {syncing ? "Scheduling…" : "Sync now"}
      </button>
    </div>
    <div class="items">
      {#each items as item}
        <section class="item-card">
          <div class="item-header">
            <div>
              <h3>{item.institution_name ?? "Connected institution"}</h3>
              <p>Last synced: {formatTime(item.last_synced_at)}</p>
            </div>
            <div class="item-actions">
              <span
                class:error-status={item.status !== "healthy"}
                class="status">{item.status.replaceAll("_", " ")}</span
              >
              <button
                class="disconnect"
                type="button"
                disabled={disconnectingId === item.id}
                on:click={() => disconnect(item)}
              >
                {disconnectingId === item.id
                  ? "Disconnecting..."
                  : "Disconnect"}
              </button>
            </div>
          </div>
          {#if item.last_error}<p class="item-error">{item.last_error}</p>{/if}
          <div class="accounts">
            {#each item.accounts as account}
              <div class="account-row">
                <div>
                  <strong>{account.name}</strong><span
                    >{account.subtype ?? account.type}</span
                  >
                </div>
                <span class="mask"
                  >{account.mask ? `•••• ${account.mask}` : "No mask"}</span
                >
              </div>
            {/each}
          </div>
        </section>
      {/each}
    </div>
    <a class="transactions-link" href="/dashboard/transactions"
      >View transactions →</a
    >
  {/if}
</DashboardShell>

<style>
  .page-header,
  .section-heading,
  .item-header,
  .account-row {
    align-items: center;
    display: flex;
    justify-content: space-between;
    gap: 20px;
  }
  .page-header {
    margin-bottom: 32px;
  }
  h1 {
    font-size: 24px;
    letter-spacing: -0.02em;
  }
  h2 {
    font-size: 18px;
  }
  h3 {
    font-size: 16px;
  }
  p {
    color: var(--text-muted);
    font-size: 13px;
  }
  .state,
  .empty-state,
  .item-card {
    background: var(--surface);
    border: 1px solid var(--border);
    border-radius: var(--radius);
  }
  .state {
    color: var(--text-muted);
    padding: 36px;
  }
  .empty-state {
    padding: 64px 40px;
    text-align: center;
  }
  .empty-state p {
    margin: 10px auto 24px;
    max-width: 520px;
  }
  .empty-icon {
    color: var(--accent);
    font-size: 32px;
    margin-bottom: 12px;
  }
  .section-heading {
    margin-bottom: 16px;
  }
  .secondary {
    background: var(--surface-2);
    border: 1px solid var(--border);
    border-radius: 7px;
    color: var(--text);
    cursor: pointer;
    font-family: var(--font-sans);
    padding: 8px 14px;
  }
  .secondary:disabled {
    cursor: wait;
    opacity: 0.6;
  }
  .items {
    display: grid;
    gap: 14px;
  }
  .item-card {
    padding: 20px;
  }
  .item-header {
    border-bottom: 1px solid var(--border);
    padding-bottom: 14px;
  }
  .status {
    background: var(--accent-dim);
    border-radius: 999px;
    color: var(--accent);
    font-family: var(--font-mono);
    font-size: 11px;
    padding: 4px 9px;
    text-transform: capitalize;
  }
  .status.error-status {
    background: var(--surface-2);
    color: var(--danger);
  }
  .item-actions {
    align-items: center;
    display: flex;
    gap: 10px;
  }
  .disconnect {
    background: transparent;
    border: 1px solid color-mix(in srgb, var(--danger) 45%, transparent);
    border-radius: 7px;
    color: var(--danger);
    cursor: pointer;
    font-family: var(--font-sans);
    padding: 6px 10px;
  }
  .disconnect:hover:not(:disabled) {
    background: color-mix(in srgb, var(--danger) 10%, transparent);
  }
  .disconnect:disabled {
    cursor: wait;
    opacity: 0.6;
  }
  .item-error {
    color: var(--danger);
    margin-top: 10px;
  }
  .accounts {
    display: grid;
  }
  .account-row {
    border-bottom: 1px solid var(--border);
    padding: 14px 2px;
  }
  .account-row:last-child {
    border-bottom: none;
    padding-bottom: 0;
  }
  .account-row strong {
    display: block;
    font-size: 14px;
  }
  .account-row span {
    color: var(--text-muted);
    font-size: 12px;
    text-transform: capitalize;
  }
  .mask {
    font-family: var(--font-mono);
  }
  .transactions-link {
    display: inline-block;
    margin-top: 20px;
  }
</style>
