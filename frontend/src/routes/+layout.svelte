<script lang="ts">
  import { onMount } from 'svelte';
  import { auth, authLoading } from '$stores/auth';

  onMount(() => auth.init());
</script>

{#if $authLoading}
  <div class="splash">
    <div class="wordmark">BillGuard</div>
    <div class="loader" aria-label="Loading"></div>
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
    --bg:        #0a0c10;
    --surface:   #111318;
    --surface-2: #1a1d24;
    --border:    rgba(255 255 255 / 0.07);
    --text:      #e8eaf0;
    --text-muted:#7c8090;
    --accent:    #4ade80;
    --accent-dim:#166534;
    --danger:    #f87171;
    --radius:    10px;
    --font-mono: 'IBM Plex Mono', 'Fira Code', monospace;
    --font-sans: 'DM Sans', system-ui, sans-serif;
  }

  :global(body) {
    background: var(--bg);
    color: var(--text);
    font-family: var(--font-sans);
    font-size: 15px;
    line-height: 1.6;
    -webkit-font-smoothing: antialiased;
  }

  :global(a) { color: var(--accent); text-decoration: none; }
  :global(a:hover) { text-decoration: underline; }

  .splash {
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    height: 100dvh;
    gap: 24px;
  }

  .wordmark {
    font-family: var(--font-mono);
    font-size: 22px;
    letter-spacing: -0.02em;
    color: var(--accent);
  }

  .loader {
    width: 20px;
    height: 20px;
    border: 2px solid var(--border);
    border-top-color: var(--accent);
    border-radius: 50%;
    animation: spin 0.7s linear infinite;
  }

  @keyframes spin { to { transform: rotate(360deg); } }
</style>
