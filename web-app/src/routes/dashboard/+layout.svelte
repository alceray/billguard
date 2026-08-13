<script lang="ts">
  import { onMount } from "svelte";
  import { goto } from "$app/navigation";
  import { isAuthenticated } from "$stores/auth";

  onMount(() => {
    if (!$isAuthenticated) void goto("/");
  });
</script>

{#if $isAuthenticated}
  <slot />
{/if}
