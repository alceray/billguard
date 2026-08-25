<script lang="ts">
  export let name:
    | "overview"
    | "transactions"
    | "subscriptions"
    | "cancelations"
    | "collapse"
    | "expand"
    | "refresh"
    | "search";
</script>

<svg
  aria-hidden="true"
  fill="none"
  height="18"
  viewBox="0 0 24 24"
  width="18"
  xmlns="http://www.w3.org/2000/svg"
>
  {#if name === "overview"}
    <rect x="4" y="4" width="6" height="6" rx="1"></rect>
    <rect x="14" y="4" width="6" height="6" rx="1"></rect>
    <rect x="4" y="14" width="6" height="6" rx="1"></rect>
    <rect x="14" y="14" width="6" height="6" rx="1"></rect>
  {:else if name === "transactions"}
    <path d="M7 6h13M7 12h13M7 18h13"></path>
    <path d="M4 6h.01M4 12h.01M4 18h.01"></path>
  {:else if name === "subscriptions"}
    <path d="M20 7v5h-5"></path>
    <path d="M4 17v-5h5"></path>
    <path d="M6.1 9A7 7 0 0 1 18.8 7L20 12"></path>
    <path d="M17.9 15A7 7 0 0 1 5.2 17L4 12"></path>
  {:else if name === "cancelations"}
    <circle cx="12" cy="12" r="8"></circle>
    <path d="m9 9 6 6m0-6-6 6"></path>
  {:else if name === "refresh"}
    <path d="M20 11a8 8 0 1 0-2.3 5.7"></path>
    <path d="M20 4v7h-7"></path>
  {:else if name === "search"}
    <circle cx="11" cy="11" r="7"></circle>
    <path d="m20 20-4-4"></path>
  {:else if name === "collapse"}
    <path d="m14 7-5 5 5 5"></path>
  {:else}
    <path d="m10 7 5 5-5 5"></path>
  {/if}
</svg>

<style>
  svg {
    flex: 0 0 auto;
    stroke: currentColor;
    stroke-linecap: round;
    stroke-linejoin: round;
    stroke-width: 1.8;
  }
</style>
