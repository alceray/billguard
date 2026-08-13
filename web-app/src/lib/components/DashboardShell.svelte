<script lang="ts">
  import { auth, currentUser } from "$stores/auth";

  export let active: "overview" | "transactions" | "subscriptions" = "overview";
</script>

<div class="layout">
  <aside class="sidebar">
    <div class="wordmark">BillGuard</div>
    <nav aria-label="Dashboard">
      <a class:active={active === "overview"} class="nav-item" href="/dashboard"
        >Overview</a
      >
      <a
        class:active={active === "transactions"}
        class="nav-item"
        href="/dashboard/transactions">Transactions</a
      >
      <a
        class:active={active === "subscriptions"}
        class="nav-item"
        href="/dashboard/subscriptions">Subscriptions</a
      >
      <span class="nav-item disabled"
        >Cancelations <span class="pill">Soon</span></span
      >
    </nav>
    <div class="sidebar-footer">
      <div class="avatar">
        {#if $currentUser?.picture}
          <img src={$currentUser.picture} alt={$currentUser.name ?? "User"} />
        {:else}
          <span>{($currentUser?.email ?? "U")[0].toUpperCase()}</span>
        {/if}
      </div>
      <div class="user-info">
        <div class="user-name">
          {$currentUser?.name ?? $currentUser?.email ?? "User"}
        </div>
        <button class="logout-btn" type="button" on:click={() => auth.logout()}
          >Sign out</button
        >
      </div>
    </div>
  </aside>
  <main><slot /></main>
</div>

<style>
  .layout {
    display: grid;
    grid-template-columns: 220px minmax(0, 1fr);
    min-height: 100dvh;
  }
  .sidebar {
    background: var(--surface);
    border-right: 1px solid var(--border);
    display: flex;
    flex-direction: column;
    height: 100dvh;
    overflow-y: auto;
    padding: 24px 16px;
    position: sticky;
    top: 0;
  }
  .wordmark {
    color: var(--accent);
    font-family: var(--font-mono);
    font-size: 15px;
    letter-spacing: -0.02em;
    margin-bottom: 32px;
    padding: 4px 8px;
  }
  nav {
    display: flex;
    flex: 1;
    flex-direction: column;
    gap: 2px;
  }
  .nav-item {
    align-items: center;
    border-radius: 7px;
    color: var(--text-muted);
    display: flex;
    font-size: 14px;
    gap: 10px;
    padding: 9px 10px;
    text-decoration: none;
  }
  .nav-item:hover,
  .nav-item.active {
    background: var(--surface-2);
    color: var(--text);
    text-decoration: none;
  }
  .nav-item.disabled {
    opacity: 0.4;
  }
  .pill {
    background: var(--surface-2);
    border: 1px solid var(--border);
    border-radius: 4px;
    color: var(--text-muted);
    font-family: var(--font-mono);
    font-size: 10px;
    margin-left: auto;
    padding: 2px 6px;
  }
  .sidebar-footer {
    align-items: center;
    border-top: 1px solid var(--border);
    display: flex;
    gap: 10px;
    margin-top: auto;
    padding-top: 16px;
  }
  .avatar {
    align-items: center;
    background: var(--surface-2);
    border: 1px solid var(--border);
    border-radius: 50%;
    color: var(--text-muted);
    display: flex;
    flex-shrink: 0;
    font-size: 13px;
    height: 32px;
    justify-content: center;
    overflow: hidden;
    width: 32px;
  }
  .avatar img {
    height: 100%;
    object-fit: cover;
    width: 100%;
  }
  .user-info {
    min-width: 0;
  }
  .user-name {
    font-size: 13px;
    font-weight: 500;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .logout-btn {
    background: none;
    border: none;
    color: var(--text-muted);
    cursor: pointer;
    font-family: var(--font-sans);
    font-size: 12px;
    padding: 0;
  }
  .logout-btn:hover {
    color: var(--danger);
  }
  main {
    max-width: 1120px;
    min-width: 0;
    padding: 36px 40px;
    width: 100%;
  }
  @media (max-width: 720px) {
    .layout {
      grid-template-columns: 1fr;
    }
    .sidebar {
      height: auto;
      position: static;
    }
    nav {
      flex-direction: row;
      flex-wrap: wrap;
    }
    .sidebar-footer {
      margin-top: 20px;
    }
    main {
      padding: 24px 18px;
    }
  }
</style>
