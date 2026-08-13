<script lang="ts">
  import { createEventDispatcher } from "svelte";
  import { api, ApiError } from "$lib/api";
  import { loadPlaidLink } from "$lib/plaid";

  interface LinkTokenResponse {
    link_token: string;
  }

  const dispatch = createEventDispatcher<{ connected: void }>();
  let loading = false;
  let error = "";

  async function connect() {
    loading = true;
    error = "";
    try {
      const [plaid, token] = await Promise.all([
        loadPlaidLink(),
        api.post<LinkTokenResponse>("/api/plaid/link-token", {}),
      ]);
      const handler = plaid.create({
        token: token.link_token,
        onSuccess: async (publicToken, metadata) => {
          try {
            await api.post("/api/plaid/exchange", {
              public_token: publicToken,
              institution_id: metadata.institution?.institution_id ?? null,
              institution_name: metadata.institution?.name ?? null,
            });
            dispatch("connected");
          } catch (exchangeError) {
            error = message(exchangeError);
          } finally {
            loading = false;
            handler.destroy();
          }
        },
        onExit: (exitError) => {
          if (exitError)
            error =
              exitError.display_message ?? "Bank connection was not completed.";
          loading = false;
          handler.destroy();
        },
      });
      handler.open();
    } catch (connectError) {
      error = message(connectError);
      loading = false;
    }
  }

  function message(value: unknown): string {
    return value instanceof ApiError || value instanceof Error
      ? value.message
      : "Could not connect your bank.";
  }
</script>

<button class="connect-btn" type="button" disabled={loading} on:click={connect}>
  {loading ? "Opening Plaid…" : "Connect bank account"}
</button>
{#if error}<p class="error" role="alert">{error}</p>{/if}

<style>
  .connect-btn {
    border: 1px solid var(--accent);
    border-radius: var(--radius);
    background: var(--accent);
    color: var(--bg);
    cursor: pointer;
    font-family: var(--font-sans);
    font-size: 14px;
    font-weight: 600;
    padding: 11px 18px;
  }
  .connect-btn:disabled {
    cursor: wait;
    opacity: 0.65;
  }
  .error {
    color: var(--danger);
    font-size: 13px;
    margin-top: 10px;
  }
</style>
