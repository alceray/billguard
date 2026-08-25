<script lang="ts">
  import { createEventDispatcher } from "svelte";
  import { api, ApiError } from "$lib/api";
  import { loadPlaidLink } from "$lib/plaid";
  import { notify } from "$stores/notifications";

  interface LinkTokenResponse {
    link_token: string;
  }

  const dispatch = createEventDispatcher<{ connected: void }>();
  let loading = false;

  async function connect() {
    loading = true;
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
            notify.success(
              `${metadata.institution?.name ?? "Bank account"} connected.`,
            );
          } catch (exchangeError) {
            notify.error(message(exchangeError));
          } finally {
            loading = false;
            handler.destroy();
          }
        },
        onExit: (exitError) => {
          if (exitError) {
            notify.error(
              exitError.display_message ?? "Bank connection was not completed.",
            );
          }
          loading = false;
          handler.destroy();
        },
      });
      handler.open();
    } catch (connectError) {
      notify.error(message(connectError));
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
</style>
