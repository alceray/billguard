<script lang="ts">
  import { onMount } from "svelte";
  import DashboardShell from "$lib/components/DashboardShell.svelte";
  import RecurringToggle from "$lib/components/RecurringToggle.svelte";
  import SearchField from "$lib/components/SearchField.svelte";
  import { api } from "$lib/api";
  import { notify } from "$stores/notifications";

  interface Transaction {
    id: string;
    amount: number;
    currency: string;
    merchant_name: string | null;
    merchant_key: string | null;
    name: string;
    date: string;
    datetime: string | null;
    pending: boolean;
    account_name: string;
    institution_name: string | null;
    recurring: boolean;
  }
  interface Page {
    transactions: Transaction[];
    next_cursor: string | null;
  }

  const dateFormatter = new Intl.DateTimeFormat(undefined, {
    dateStyle: "medium",
  });
  const timeFormatter = new Intl.DateTimeFormat(undefined, {
    timeStyle: "short",
  });

  let transactions: Transaction[] = [];
  let visibleTransactions: Transaction[] = [];
  let nextCursor: string | null = null;
  let loading = true;
  let search = "";
  let updating = "";

  $: {
    const query = search.trim().toLocaleLowerCase();
    visibleTransactions = query
      ? transactions.filter((transaction) =>
          [
            transaction.merchant_name,
            transaction.name,
            transaction.account_name,
            transaction.institution_name,
          ].some((value) => value?.toLocaleLowerCase().includes(query)),
        )
      : transactions;
  }

  onMount(() => load());

  async function load(cursor?: string) {
    loading = true;
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
      notify.error(message(value, "Could not load transactions."));
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

  function transactionTimestamp(transaction: Transaction): {
    date: string;
    time: string | null;
  } {
    if (transaction.datetime) {
      const timestamp = new Date(transaction.datetime);
      return {
        date: dateFormatter.format(timestamp),
        time: timeFormatter.format(timestamp),
      };
    }

    const [year, month, day] = transaction.date.split("-").map(Number);
    return {
      date: dateFormatter.format(new Date(year, month - 1, day)),
      time: null,
    };
  }

  async function toggleRecurring(transaction: Transaction) {
    if (transaction.pending && !transaction.recurring) return;

    updating = transaction.id;
    const recurring = !transaction.recurring;
    try {
      if (recurring) {
        await api.post("/api/recurring-charges", {
          transaction_id: transaction.id,
          category: "other",
        });
      } else {
        await api.del(`/api/recurring-charges/transactions/${transaction.id}`);
      }

      transactions = transactions.map((candidate) =>
        candidate.id === transaction.id ||
        (transaction.merchant_key !== null &&
          candidate.merchant_key === transaction.merchant_key)
          ? { ...candidate, recurring }
          : candidate,
      );
      notify.success(
        `${transaction.merchant_name ?? transaction.name} marked ${recurring ? "recurring" : "not recurring"}.`,
      );
    } catch (value) {
      notify.error(
        message(
          value,
          recurring
            ? "Could not mark this charge recurring."
            : "Could not unmark this recurring charge.",
        ),
      );
    } finally {
      updating = "";
    }
  }

  function message(value: unknown, fallback: string): string {
    return value instanceof Error ? value.message : fallback;
  }
</script>

<DashboardShell active="transactions">
  <header>
    <div>
      <h1>Transactions</h1>
      <p>Transactions ingested from your connected accounts</p>
    </div>
  </header>
  <div class="toolbar">
    <SearchField
      bind:value={search}
      label="Search transactions"
      placeholder="Search transactions"
    />
  </div>
  {#if loading && transactions.length === 0}
    <div class="state">Loading transactions…</div>
  {:else if transactions.length === 0}
    <div class="state">
      No transactions yet. Return to Overview and schedule a sync.
    </div>
  {:else if visibleTransactions.length === 0}
    <div class="state">No transactions match this search.</div>
  {:else}
    <div class="table-wrap">
      <table>
        <thead
          ><tr
            ><th>Date</th><th>Merchant</th><th>Account</th><th class="amount"
              >Amount</th
            ><th>Recurring</th></tr
          ></thead
        >
        <tbody>
          {#each visibleTransactions as transaction}
            {@const timestamp = transactionTimestamp(transaction)}
            <tr>
              <td class="date">
                <span class="date-value">{timestamp.date}</span>
                {#if timestamp.time}
                  <span class="time-value">{timestamp.time}</span>
                {/if}
              </td>
              <td
                ><strong>{transaction.merchant_name ?? transaction.name}</strong
                ><span>{transaction.institution_name ?? "Bank"}</span></td
              >
              <td>{transaction.account_name}</td>
              <td class="amount">{money(transaction)}</td>
              <td>
                <RecurringToggle
                  active={transaction.recurring}
                  updating={updating === transaction.id}
                  tooltipId={`recurring-tooltip-${transaction.id}`}
                  unavailableMessage={transaction.pending &&
                  !transaction.recurring
                    ? "Available after this pending transaction is posted."
                    : null}
                  on:toggle={() => toggleRecurring(transaction)}
                />
              </td>
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
    margin-bottom: 18px;
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
  .table-wrap {
    background: var(--surface);
    border: 1px solid var(--border);
    border-radius: var(--radius);
  }
  .state {
    padding: 28px;
  }
  .toolbar {
    display: flex;
    margin-bottom: 18px;
  }
  .table-wrap {
    overflow-x: auto;
  }
  table {
    border-collapse: collapse;
    min-width: 680px;
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
  .date {
    white-space: nowrap;
  }
  .date-value {
    color: var(--text-muted);
    font-size: 13px;
  }
  .time-value {
    margin-top: 2px;
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
