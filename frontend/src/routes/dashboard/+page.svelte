<script lang="ts">
  import { isAuthenticated, currentUser } from '$stores/auth';
  import { goto } from '$app/navigation';
  import { onMount } from 'svelte';
  import { auth } from '$stores/auth';

  onMount(() => {
    if (!$isAuthenticated) goto('/');
  });

  const stats = [
    { label: 'Active subscriptions', value: '—', mono: false },
    { label: 'Monthly spend',        value: '—',  mono: true  },
    { label: 'Detected this week',   value: '—',   mono: false },
  ];
</script>

<div class="layout">
  <!-- Sidebar -->
  <aside class="sidebar">
    <div class="wordmark">BillGuard</div>

    <nav>
      <a class="nav-item active" href="/dashboard">
        <span class="icon">▦</span> Overview
      </a>
      <a class="nav-item" href="/dashboard/subscriptions">
        <span class="icon">↻</span> Subscriptions
      </a>
      <a class="nav-item disabled" href="#">
        <span class="icon">⚡</span> Cancelations
        <span class="pill">Soon</span>
      </a>
    </nav>

    <div class="sidebar-footer">
      <div class="user-row">
        <div class="avatar">
          {#if $currentUser?.picture}
            <img src={$currentUser.picture} alt={$currentUser.name ?? 'User'} />
          {:else}
            <span>{($currentUser?.email ?? 'U')[0].toUpperCase()}</span>
          {/if}
        </div>
        <div class="user-info">
          <div class="user-name">{$currentUser?.name ?? $currentUser?.email ?? 'User'}</div>
          <button class="logout-btn" on:click={() => auth.logout()}>Sign out</button>
        </div>
      </div>
    </div>
  </aside>

  <!-- Main content -->
  <main>
    <header class="page-header">
      <div>
        <h2>Overview</h2>
        <p class="subtitle">Your subscription activity at a glance</p>
      </div>
    </header>

    <!-- Stats row -->
    <div class="stats-row">
      {#each stats as stat}
        <div class="stat-card">
          <div class="stat-label">{stat.label}</div>
          <div class="stat-value" class:mono={stat.mono}>{stat.value}</div>
        </div>
      {/each}
    </div>

    <!-- Empty state -->
    <div class="empty-state">
      <div class="empty-icon">🔗</div>
      <h3>Connect your bank to get started</h3>
      <p>
        BillGuard uses Plaid to securely read your transactions.<br />
        We never store your credentials.
      </p>
      <!-- Plaid Link button added in Phase 2 -->
      <button class="connect-btn" disabled>
        Connect bank account
        <span class="tag">Coming in Phase 2</span>
      </button>
    </div>
  </main>
</div>

<style>
  .layout {
    display: grid;
    grid-template-columns: 220px 1fr;
    min-height: 100dvh;
  }

  /* ── Sidebar ─────────────────────────────── */
  .sidebar {
    background: var(--surface);
    border-right: 1px solid var(--border);
    display: flex;
    flex-direction: column;
    padding: 24px 16px;
    position: sticky;
    top: 0;
    height: 100dvh;
    overflow-y: auto;
  }

  .wordmark {
    font-family: var(--font-mono);
    font-size: 15px;
    color: var(--accent);
    letter-spacing: -0.02em;
    padding: 4px 8px;
    margin-bottom: 32px;
  }

  nav {
    display: flex;
    flex-direction: column;
    gap: 2px;
    flex: 1;
  }

  .nav-item {
    display: flex;
    align-items: center;
    gap: 10px;
    padding: 9px 10px;
    border-radius: 7px;
    color: var(--text-muted);
    font-size: 14px;
    text-decoration: none;
    transition: background 0.1s, color 0.1s;
  }

  .nav-item:hover { background: var(--surface-2); color: var(--text); text-decoration: none; }
  .nav-item.active { background: var(--surface-2); color: var(--text); }
  .nav-item.disabled { opacity: 0.4; pointer-events: none; }

  .icon { font-size: 14px; width: 18px; text-align: center; }

  .pill {
    margin-left: auto;
    font-size: 10px;
    font-family: var(--font-mono);
    background: var(--surface-2);
    border: 1px solid var(--border);
    border-radius: 4px;
    padding: 2px 6px;
    color: var(--text-muted);
  }

  .sidebar-footer { margin-top: auto; padding-top: 16px; border-top: 1px solid var(--border); }

  .user-row { display: flex; align-items: center; gap: 10px; }

  .avatar {
    width: 32px; height: 32px; border-radius: 50%;
    background: var(--surface-2);
    border: 1px solid var(--border);
    overflow: hidden;
    display: flex; align-items: center; justify-content: center;
    font-size: 13px; color: var(--text-muted); flex-shrink: 0;
  }
  .avatar img { width: 100%; height: 100%; object-fit: cover; }

  .user-info { min-width: 0; }
  .user-name { font-size: 13px; font-weight: 500; truncate: ellipsis; overflow: hidden; white-space: nowrap; }
  .logout-btn {
    font-size: 12px; color: var(--text-muted); background: none;
    border: none; cursor: pointer; padding: 0; font-family: var(--font-sans);
  }
  .logout-btn:hover { color: var(--danger); }

  /* ── Main ────────────────────────────────── */
  main {
    padding: 36px 40px;
    max-width: 960px;
  }

  .page-header {
    display: flex;
    align-items: flex-start;
    justify-content: space-between;
    margin-bottom: 32px;
  }

  h2 { font-size: 22px; font-weight: 600; letter-spacing: -0.02em; margin-bottom: 4px; }
  .subtitle { color: var(--text-muted); font-size: 14px; }

  /* ── Stats ───────────────────────────────── */
  .stats-row {
    display: grid;
    grid-template-columns: repeat(3, 1fr);
    gap: 16px;
    margin-bottom: 40px;
  }

  .stat-card {
    background: var(--surface);
    border: 1px solid var(--border);
    border-radius: var(--radius);
    padding: 20px 24px;
  }

  .stat-label { font-size: 12px; color: var(--text-muted); margin-bottom: 8px; }
  .stat-value { font-size: 28px; font-weight: 600; letter-spacing: -0.03em; }
  .stat-value.mono { font-family: var(--font-mono); }

  /* ── Empty state ─────────────────────────── */
  .empty-state {
    background: var(--surface);
    border: 1px dashed var(--border);
    border-radius: var(--radius);
    padding: 64px 40px;
    text-align: center;
  }

  .empty-icon { font-size: 36px; margin-bottom: 16px; }
  h3 { font-size: 18px; font-weight: 500; margin-bottom: 10px; }
  .empty-state p { color: var(--text-muted); font-size: 14px; line-height: 1.7; margin-bottom: 28px; }

  .connect-btn {
    display: inline-flex;
    align-items: center;
    gap: 10px;
    background: var(--surface-2);
    color: var(--text-muted);
    border: 1px solid var(--border);
    border-radius: var(--radius);
    padding: 12px 24px;
    font-family: var(--font-sans);
    font-size: 14px;
    cursor: not-allowed;
  }

  .tag {
    font-family: var(--font-mono);
    font-size: 10px;
    background: var(--bg);
    border: 1px solid var(--border);
    border-radius: 4px;
    padding: 2px 7px;
    color: var(--text-muted);
  }
</style>
