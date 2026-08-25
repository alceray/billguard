<script lang="ts">
  export let label = "Loading";
</script>

<div class="splash">
  <div class="wordmark">BillGuard</div>
  <div class="loader" aria-label={label}></div>
</div>

<style>
  .splash {
    align-items: center;
    display: flex;
    flex-direction: column;
    gap: 24px;
    height: 100dvh;
    justify-content: center;
  }
  .wordmark {
    color: var(--accent);
    font-family: var(--font-mono);
    font-size: 22px;
    letter-spacing: -0.02em;
  }
  .loader {
    animation: spin 0.7s linear infinite;
    border: 2px solid var(--border);
    border-radius: 50%;
    border-top-color: var(--accent);
    height: 20px;
    width: 20px;
  }
  @keyframes spin {
    to {
      transform: rotate(360deg);
    }
  }
</style>
