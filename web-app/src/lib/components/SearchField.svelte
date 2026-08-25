<script lang="ts">
  import Icon from "$lib/components/Icon.svelte";

  export let value = "";
  export let label = "Search";
  export let placeholder = "Search";
</script>

<label>
  <Icon name="search" />
  <input type="search" bind:value aria-label={label} {placeholder} />
</label>

<style>
  label {
    align-items: center;
    background: var(--surface-2);
    border: 1px solid var(--border);
    border-radius: 7px;
    color: var(--text-muted);
    display: flex;
    gap: 8px;
    min-width: 220px;
    padding: 0 10px;
  }
  label:focus-within {
    border-color: var(--accent-dim);
    color: var(--accent);
  }
  input {
    background: transparent;
    border: 0;
    color: var(--text);
    font-family: var(--font-sans);
    font-size: 13px;
    min-width: 0;
    outline: 0;
    padding: 9px 0;
    width: 100%;
  }
  input::placeholder {
    color: var(--text-muted);
  }
</style>
