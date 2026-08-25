<script lang="ts">
  import { createEventDispatcher } from "svelte";
  import Icon from "$lib/components/Icon.svelte";

  export let active: boolean;
  export let updating = false;
  export let tooltipId: string;
  export let unavailableMessage: string | null = null;

  const dispatch = createEventDispatcher<{ toggle: void }>();

  $: action = updating
    ? "Updating recurring status"
    : active
      ? "Unmark recurring"
      : "Mark recurring";

  function toggle() {
    if (!updating && unavailableMessage === null) dispatch("toggle");
  }
</script>

<span class="control">
  <button
    class:active
    class:updating
    type="button"
    disabled={updating}
    aria-pressed={active}
    aria-disabled={unavailableMessage !== null}
    aria-label={action}
    aria-describedby={tooltipId}
    on:click={toggle}
  >
    <Icon name="subscriptions" />
  </button>
  <span class="tooltip" id={tooltipId} role="tooltip">
    {unavailableMessage ?? action}
  </span>
</span>

<style>
  .control {
    display: inline-block;
    position: relative;
  }
  button {
    align-items: center;
    background: transparent;
    border: 1px solid var(--border);
    border-radius: 6px;
    color: var(--text-muted);
    cursor: pointer;
    display: inline-flex;
    height: 30px;
    justify-content: center;
    padding: 0;
    width: 30px;
  }
  button.active {
    border-color: var(--accent-dim);
    color: var(--accent);
  }
  button:hover:not(:disabled):not([aria-disabled="true"]) {
    border-color: var(--accent-dim);
    color: var(--accent);
  }
  button.active:hover:not(:disabled):not([aria-disabled="true"]) {
    border-color: var(--danger);
    color: var(--danger);
  }
  button:disabled,
  button[aria-disabled="true"] {
    cursor: default;
    opacity: 0.55;
  }
  button.updating :global(svg) {
    animation: spin 750ms linear infinite;
  }
  .tooltip {
    background: var(--surface-2);
    border: 1px solid var(--border);
    border-radius: 6px;
    bottom: calc(100% + 8px);
    box-shadow: 0 8px 24px rgba(0 0 0 / 0.35);
    color: var(--text);
    font-size: 11px;
    left: 50%;
    line-height: 1.35;
    max-width: 210px;
    opacity: 0;
    padding: 7px 9px;
    pointer-events: none;
    position: absolute;
    text-align: left;
    transform: translate(-50%, 4px);
    transition:
      opacity 140ms ease,
      transform 140ms ease;
    visibility: hidden;
    white-space: normal;
    width: max-content;
    z-index: 5;
  }
  .control:hover .tooltip,
  .control:focus-within .tooltip {
    opacity: 1;
    transform: translate(-50%, 0);
    visibility: visible;
  }
  @keyframes spin {
    to {
      transform: rotate(360deg);
    }
  }
  @media (prefers-reduced-motion: reduce) {
    button.updating :global(svg) {
      animation: none;
    }
  }
</style>
