<script lang="ts">
  import LoadingSplash from "$lib/components/LoadingSplash.svelte";

  // Auth0 redirect lands here - the auth store handles the callback in +layout.svelte
  // via auth.init() which calls handleRedirectCallback() and redirects to /dashboard
</script>

<LoadingSplash label="Signing you in" />
