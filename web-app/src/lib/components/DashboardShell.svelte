<script lang="ts">
  import { onMount } from "svelte";
  import Icon from "$lib/components/Icon.svelte";
  import NotificationViewport from "$lib/components/NotificationViewport.svelte";
  import { auth, currentUser } from "$stores/auth";

  export let active: "overview" | "transactions" | "subscriptions" = "overview";

  const SIDEBAR_STORAGE_KEY = "billguard.dashboard.sidebarCollapsed";
  let collapsed = false;

  onMount(() => {
    collapsed = window.localStorage.getItem(SIDEBAR_STORAGE_KEY) === "true";
  });

  function toggleSidebar() {
    collapsed = !collapsed;
    window.localStorage.setItem(SIDEBAR_STORAGE_KEY, String(collapsed));
  }
</script>

<div class:collapsed class="layout">
  <aside class:collapsed class="sidebar">
    <div class="sidebar-header">
      <div class="wordmark">
        <span class="wordmark-full">BillGuard</span>
        <span class="wordmark-short">BG</span>
      </div>
      <button
        class="collapse-toggle"
        type="button"
        aria-label={collapsed ? "Expand sidebar" : "Collapse sidebar"}
        aria-pressed={collapsed}
        title={collapsed ? "Expand sidebar" : "Collapse sidebar"}
        on:click={toggleSidebar}
      >
        <Icon name={collapsed ? "expand" : "collapse"} />
      </button>
    </div>
    <nav aria-label="Dashboard">
      <a
        class:active={active === "overview"}
        class="nav-item"
        href="/dashboard"
        title={collapsed ? "Overview" : undefined}
      >
        <Icon name="overview" />
        <span class="nav-label">Overview</span></a
      >
      <a
        class:active={active === "transactions"}
        class="nav-item"
        href="/dashboard/transactions"
        title={collapsed ? "Transactions" : undefined}
      >
        <Icon name="transactions" />
        <span class="nav-label">Transactions</span></a
      >
      <a
        class:active={active === "subscriptions"}
        class="nav-item"
        href="/dashboard/subscriptions"
        title={collapsed ? "Recurring charges" : undefined}
      >
        <Icon name="subscriptions" />
        <span class="nav-label">Recurring charges</span></a
      >
      <span
        class="nav-item disabled"
        title={collapsed ? "Cancelations" : undefined}
      >
        <Icon name="cancelations" />
        <span class="nav-label">Cancelations</span>
        <span class="pill">Soon</span></span
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
  <NotificationViewport sidebarCollapsed={collapsed} />
</div>

<style>
  .layout {
    --sidebar-width: 220px;
    display: grid;
    grid-template-columns: var(--sidebar-width) minmax(0, 1fr);
    min-height: 100dvh;
    transition: grid-template-columns 220ms ease;
  }
  .layout.collapsed {
    --sidebar-width: 72px;
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
    transition: padding 220ms ease;
    z-index: 30;
  }
  .sidebar.collapsed {
    padding-left: 10px;
    padding-right: 10px;
  }
  .sidebar-header {
    align-items: center;
    display: flex;
    gap: 6px;
    justify-content: space-between;
    margin-bottom: 32px;
  }
  .wordmark {
    color: var(--accent);
    font-family: var(--font-mono);
    font-size: 15px;
    letter-spacing: -0.02em;
    padding: 4px 8px;
    white-space: nowrap;
  }
  .wordmark-short {
    display: none;
  }
  .collapse-toggle {
    align-items: center;
    background: transparent;
    border: 1px solid transparent;
    border-radius: 6px;
    color: var(--text-muted);
    cursor: pointer;
    display: flex;
    flex: 0 0 auto;
    height: 30px;
    justify-content: center;
    padding: 0;
    width: 30px;
  }
  .collapse-toggle:hover {
    background: var(--surface-2);
    border-color: var(--border);
    color: var(--text);
  }
  .sidebar.collapsed .sidebar-header {
    flex-direction: column;
    gap: 9px;
  }
  .sidebar.collapsed .wordmark {
    padding-left: 0;
    padding-right: 0;
  }
  .sidebar.collapsed .wordmark-full {
    display: none;
  }
  .sidebar.collapsed .wordmark-short {
    display: inline;
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
    white-space: nowrap;
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
  .sidebar.collapsed .nav-item {
    justify-content: center;
    padding-left: 0;
    padding-right: 0;
  }
  .sidebar.collapsed .nav-label,
  .sidebar.collapsed .pill {
    display: none;
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
  .sidebar.collapsed .sidebar-footer {
    justify-content: center;
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
  .sidebar.collapsed .user-info {
    display: none;
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
    .layout,
    .layout.collapsed {
      grid-template-columns: 1fr;
    }
    .sidebar {
      height: auto;
      position: static;
    }
    .sidebar.collapsed {
      padding: 24px 16px;
    }
    .sidebar.collapsed .sidebar-header {
      flex-direction: row;
    }
    .sidebar.collapsed .wordmark-full,
    .sidebar.collapsed .nav-label,
    .sidebar.collapsed .pill,
    .sidebar.collapsed .user-info {
      display: block;
    }
    .sidebar.collapsed .wordmark-short {
      display: none;
    }
    .sidebar.collapsed .nav-item {
      justify-content: flex-start;
      padding: 9px 10px;
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
  @media (prefers-reduced-motion: reduce) {
    .layout,
    .sidebar {
      transition: none;
    }
  }
</style>
