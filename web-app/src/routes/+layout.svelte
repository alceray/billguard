<script lang="ts">
  import { onMount } from "svelte";
  import LoadingSplash from "$lib/components/LoadingSplash.svelte";
  import { auth, authError, authLoading } from "$stores/auth";

  onMount(() => auth.init());
</script>

{#if $authLoading}
  <LoadingSplash />
{:else if $authError}
  <div class="auth-error" role="alert">
    <h1>BillGuard could not start</h1>
    <p>{$authError}</p>
    <button type="button" on:click={() => auth.retry()}>Try again</button>
  </div>
{:else}
  <slot />
{/if}

<style>
  :global(*) {
    box-sizing: border-box;
    margin: 0;
    padding: 0;
  }

  :global(:root) {
    --bg: #0a0c10;
    --surface: #111318;
    --surface-2: #1a1d24;
    --border: rgba(255 255 255 / 0.07);
    --text: #e8eaf0;
    --text-muted: #7c8090;
    --accent: #4ade80;
    --accent-dim: #166534;
    --accent-contrast: #07110a;
    --danger: #f87171;
    --warning: #fbbf24;
    --radius: 10px;
    --font-mono: "IBM Plex Mono", "Fira Code", monospace;
    --font-sans: "DM Sans", system-ui, sans-serif;
  }

  :global(body) {
    background: var(--bg);
    color: var(--text);
    font-family: var(--font-sans);
    font-size: 15px;
    line-height: 1.6;
    -webkit-font-smoothing: antialiased;
  }

  :global(a) {
    color: var(--accent);
    text-decoration: none;
  }
  :global(a:hover) {
    text-decoration: underline;
  }

  .auth-error {
    align-items: center;
    display: flex;
    flex-direction: column;
    gap: 12px;
    justify-content: center;
    height: 100dvh;
    padding: 24px;
    text-align: center;
  }
  .auth-error p {
    color: var(--text-muted);
  }
  .auth-error button {
    background: var(--accent);
    border: 0;
    border-radius: var(--radius);
    color: var(--accent-contrast);
    cursor: pointer;
    font: inherit;
    font-weight: 600;
    padding: 10px 16px;
  }
</style>
