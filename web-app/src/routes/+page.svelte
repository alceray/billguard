<script lang="ts">
  import { isAuthenticated } from '$stores/auth';
  import { goto } from '$app/navigation';
  import { onMount } from 'svelte';
  import { auth } from '$stores/auth';

  onMount(() => {
    if ($isAuthenticated) goto('/dashboard');
  });
</script>

<main>
  <div class="hero">
    <div class="badge">Personal finance, automated</div>

    <h1>
      Stop paying for<br />
      subscriptions<br />
      <span class="highlight">you forgot about</span>
    </h1>

    <p class="sub">
      BillGuard connects to your bank, finds recurring charges,<br />
      and helps you cancel them — in one place.
    </p>

    <button class="cta" on:click={() => auth.login()}>
      Connect your bank
      <span class="arrow">→</span>
    </button>

    <p class="disclaimer">Read-only access · No credentials stored · Self-hostable</p>
  </div>

  <div class="grid-bg" aria-hidden="true"></div>
</main>

<style>
  main {
    min-height: 100dvh;
    display: flex;
    align-items: center;
    justify-content: center;
    position: relative;
    overflow: hidden;
    padding: 40px 24px;
  }

  .grid-bg {
    position: absolute;
    inset: 0;
    background-image:
      linear-gradient(rgba(255 255 255 / 0.03) 1px, transparent 1px),
      linear-gradient(90deg, rgba(255 255 255 / 0.03) 1px, transparent 1px);
    background-size: 60px 60px;
    mask-image: radial-gradient(ellipse 80% 60% at 50% 50%, black, transparent);
    z-index: 0;
  }

  .hero {
    position: relative;
    z-index: 1;
    max-width: 640px;
    text-align: center;
  }

  .badge {
    display: inline-block;
    font-family: var(--font-mono);
    font-size: 11px;
    letter-spacing: 0.08em;
    text-transform: uppercase;
    color: var(--accent);
    border: 1px solid var(--accent-dim);
    border-radius: 20px;
    padding: 5px 14px;
    margin-bottom: 32px;
  }

  h1 {
    font-size: clamp(38px, 7vw, 64px);
    font-weight: 600;
    line-height: 1.1;
    letter-spacing: -0.03em;
    margin-bottom: 24px;
    color: var(--text);
  }

  .highlight {
    color: var(--accent);
  }

  .sub {
    color: var(--text-muted);
    font-size: 16px;
    line-height: 1.7;
    margin-bottom: 40px;
  }

  .cta {
    display: inline-flex;
    align-items: center;
    gap: 10px;
    background: var(--accent);
    color: #0a0c10;
    font-family: var(--font-sans);
    font-size: 15px;
    font-weight: 600;
    border: none;
    border-radius: var(--radius);
    padding: 14px 28px;
    cursor: pointer;
    transition: opacity 0.15s, transform 0.15s;
    margin-bottom: 20px;
  }

  .cta:hover { opacity: 0.9; transform: translateY(-1px); }
  .cta:active { transform: translateY(0); }

  .arrow { font-size: 18px; }

  .disclaimer {
    font-size: 12px;
    color: var(--text-muted);
    font-family: var(--font-mono);
    opacity: 0.6;
  }
</style>
