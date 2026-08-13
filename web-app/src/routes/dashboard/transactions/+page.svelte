<script lang="ts">
  import { onMount } from "svelte";
  import DashboardShell from "$lib/components/DashboardShell.svelte";
  import { api } from "$lib/api";

  interface Transaction {
    id: string;
    amount: number;
    currency: string;
    merchant_name: string | null;
    name: string;
    date: string;
    pending: boolean;
    account_name: string;
    institution_name: string | null;
  }
  interface Page {
    transactions: Transaction[];
    next_cursor: string | null;
  }

  let transactions: Transaction[] = [];
  let nextCursor: string | null = null;
  let loading = true;
  let error = "";

  onMount(() => load());

  async function load(cursor?: string) {
    loading = true;
    error = "";
    try {
      const query = cursor
        ? `?limit=50&cursor=${encodeURIComponent(cursor)}`
        : "?limit=50";
      const page = await api.get<Page>(`/api/transactions${query}`);
      transactions = cursor
        ? [...transactions, ...page.transactions]
        : page.transactions;
      nextCursor = page.next_cursor;
    } catch (value) {
      error =
        value instanceof Error ? value.message : "Could not load transactions.";
    } finally {
      loading = false;
    }
  }

  function money(transaction: Transaction): string {
    return new Intl.NumberFormat(undefined, {
      style: "currency",
      currency: transaction.currency,
    }).format(transaction.amount);
  }
</script>

<DashboardShell active="transactions">
  <header>
    <div>
      <h1>Transactions</h1>
      <p>Transactions ingested from your connected accounts</p>
    </div>
  </header>
  {#if error}<div class="error" role="alert">{error}</div>{/if}
  {#if loading && transactions.length === 0}
    <div class="state">Loading transactions…</div>
  {:else if transactions.length === 0}
    <div class="state">
      No transactions yet. Return to Overview and schedule a sync.
    </div>
  {:else}
    <div class="table-wrap">
      <table>
        <thead
          ><tr
            ><th>Date</th><th>Merchant</th><th>Account</th><th>Status</th><th
              class="amount">Amount</th
            ></tr
          ></thead
        >
        <tbody>
          {#each transactions as transaction}
            <tr>
              <td>{transaction.date}</td>
              <td
                ><strong>{transaction.merchant_name ?? transaction.name}</strong
                ><span>{transaction.institution_name ?? "Bank"}</span></td
              >
              <td>{transaction.account_name}</td>
              <td
                ><span class:pending={transaction.pending} class="status"
                  >{transaction.pending ? "Pending" : "Posted"}</span
                ></td
              >
              <td class="amount">{money(transaction)}</td>
            </tr>
          {/each}
        </tbody>
      </table>
    </div>
    {#if nextCursor}
      <button
        class="load-more"
        type="button"
        disabled={loading}
        on:click={() => load(nextCursor ?? undefined)}
      >
        {loading ? "Loading…" : "Load more"}
      </button>
    {/if}
  {/if}
</DashboardShell>

<style>
  header {
    margin-bottom: 28px;
  }
  h1 {
    font-size: 24px;
    letter-spacing: -0.02em;
  }
  p,
  td {
    color: var(--text-muted);
    font-size: 13px;
  }
  .state,
  .error,
  .table-wrap {
    background: var(--surface);
    border: 1px solid var(--border);
    border-radius: var(--radius);
  }
  .state,
  .error {
    padding: 28px;
  }
  .error {
    color: var(--danger);
    margin-bottom: 18px;
  }
  .table-wrap {
    overflow-x: auto;
  }
  table {
    border-collapse: collapse;
    min-width: 720px;
    width: 100%;
  }
  th {
    color: var(--text-muted);
    font-size: 11px;
    font-weight: 500;
    letter-spacing: 0.04em;
    text-align: left;
    text-transform: uppercase;
  }
  th,
  td {
    border-bottom: 1px solid var(--border);
    padding: 14px 16px;
  }
  tbody tr:last-child td {
    border-bottom: none;
  }
  td strong {
    color: var(--text);
    display: block;
    font-size: 13px;
  }
  td span {
    display: block;
    font-size: 11px;
  }
  .amount {
    color: var(--text);
    font-family: var(--font-mono);
    text-align: right;
  }
  .status {
    color: var(--accent);
  }
  .status.pending {
    color: var(--text-muted);
  }
  .load-more {
    background: var(--surface-2);
    border: 1px solid var(--border);
    border-radius: 7px;
    color: var(--text);
    cursor: pointer;
    font-family: var(--font-sans);
    margin-top: 16px;
    padding: 9px 15px;
  }
</style>
