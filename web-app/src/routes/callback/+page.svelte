<script lang="ts">
  // Auth0 redirect lands here — the auth store handles the callback in +layout.svelte
  // via auth.init() which calls handleRedirectCallback() and redirects to /dashboard
</script>

<div class="splash">
  <div class="wordmark">BillGuard</div>
  <div class="loader" aria-label="Signing you in..."></div>
</div>

<style>
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