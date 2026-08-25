<script lang="ts">
  import { notifications } from "$stores/notifications";

  export let sidebarCollapsed = false;

  function title(kind: "success" | "error" | "info"): string {
    if (kind === "success") return "Done";
    if (kind === "error") return "Needs attention";
    return "Update";
  }
</script>

<div
  class:sidebar-collapsed={sidebarCollapsed}
  class="notification-viewport"
  aria-live="polite"
  aria-relevant="additions"
>
  {#each $notifications as notification (notification.id)}
    <div
      class:error={notification.kind === "error"}
      class:info={notification.kind === "info"}
      class:success={notification.kind === "success"}
      class="notification"
      role={notification.kind === "error" ? "alert" : "status"}
    >
      <span class="kind-marker" aria-hidden="true"></span>
      <div class="notification-copy">
        <strong>{title(notification.kind)}</strong>
        <p>{notification.message}</p>
      </div>
      <button
        type="button"
        aria-label="Dismiss notification"
        on:click={() => notifications.dismiss(notification.id)}
        ><span aria-hidden="true">&times;</span></button
      >
    </div>
  {/each}
</div>

<style>
  .notification-viewport {
    bottom: 22px;
    display: grid;
    gap: 10px;
    left: 236px;
    max-width: calc(100vw - 258px);
    pointer-events: none;
    position: fixed;
    transition: left 220ms ease;
    width: min(380px, calc(100vw - 258px));
    z-index: 20;
  }
  .notification-viewport.sidebar-collapsed {
    left: 88px;
    max-width: calc(100vw - 110px);
    width: min(380px, calc(100vw - 110px));
  }
  .notification {
    align-items: flex-start;
    animation: slide-from-sidebar 280ms cubic-bezier(0.22, 1, 0.36, 1);
    background: color-mix(in srgb, var(--surface) 94%, black);
    border: 1px solid var(--border);
    border-radius: var(--radius);
    box-shadow: 0 16px 44px rgba(0 0 0 / 0.42);
    display: flex;
    gap: 11px;
    overflow: hidden;
    padding: 13px 12px;
    pointer-events: auto;
  }
  .kind-marker {
    background: var(--accent);
    border-radius: 999px;
    box-shadow: 0 0 0 4px color-mix(in srgb, var(--accent) 13%, transparent);
    flex: 0 0 auto;
    height: 8px;
    margin: 7px 3px 0 2px;
    width: 8px;
  }
  .notification.error .kind-marker {
    background: var(--danger);
    box-shadow: 0 0 0 4px color-mix(in srgb, var(--danger) 13%, transparent);
  }
  .notification.info .kind-marker {
    background: var(--warning);
    box-shadow: 0 0 0 4px color-mix(in srgb, var(--warning) 13%, transparent);
  }
  .notification-copy {
    min-width: 0;
  }
  strong {
    display: block;
    font-size: 12px;
    font-weight: 650;
    margin-bottom: 1px;
  }
  p {
    color: var(--text-muted);
    font-size: 13px;
    line-height: 1.4;
  }
  button {
    align-items: center;
    background: transparent;
    border: 0;
    border-radius: 5px;
    color: var(--text-muted);
    cursor: pointer;
    display: flex;
    font-size: 19px;
    height: 26px;
    justify-content: center;
    margin-left: auto;
    padding: 0;
    width: 26px;
  }
  button:hover {
    background: var(--surface-2);
    color: var(--text);
  }
  @keyframes slide-from-sidebar {
    from {
      opacity: 0;
      transform: translateX(calc(-100% - 40px));
    }
    to {
      opacity: 1;
      transform: translateX(0);
    }
  }
  @media (max-width: 720px) {
    .notification-viewport,
    .notification-viewport.sidebar-collapsed {
      bottom: 14px;
      left: 14px;
      max-width: calc(100vw - 28px);
      width: calc(100vw - 28px);
    }
  }
  @media (prefers-reduced-motion: reduce) {
    .notification,
    .notification-viewport {
      animation: none;
      transition: none;
    }
  }
</style>
