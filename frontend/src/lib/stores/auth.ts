import { writable, derived, get } from 'svelte/store';
import { createAuth0Client, type Auth0Client, type User } from '@auth0/auth0-spa-js';
import { browser } from '$app/environment';
import { goto } from '$app/navigation';

interface AuthState {
  client: Auth0Client | null;
  user: User | null;
  isAuthenticated: boolean;
  isLoading: boolean;
  error: string | null;
}

function createAuthStore() {
  const { subscribe, set, update } = writable<AuthState>({
    client: null,
    user: null,
    isAuthenticated: false,
    isLoading: true,
    error: null,
  });

  async function init() {
    if (!browser) return;

    try {
      const client = await createAuth0Client({
        domain: import.meta.env.VITE_AUTH0_DOMAIN,
        clientId: import.meta.env.VITE_AUTH0_CLIENT_ID,
        authorizationParams: {
          redirect_uri: window.location.origin + '/callback',
          audience: import.meta.env.VITE_AUTH0_AUDIENCE,
        },
        cacheLocation: 'localstorage',
      });

      // Handle redirect callback
      if (window.location.search.includes('code=') || window.location.search.includes('error=')) {
        await client.handleRedirectCallback();
        goto('/dashboard', { replaceState: true });
      }

      const isAuthenticated = await client.isAuthenticated();
      const user = isAuthenticated ? await client.getUser() : null;

      update((s) => ({ ...s, client, user: user ?? null, isAuthenticated, isLoading: false }));

      // Upsert user in our DB on first auth
      if (isAuthenticated && user) {
        await upsertUser(client, user);
      }
    } catch (err) {
      update((s) => ({
        ...s,
        isLoading: false,
        error: (err as Error).message,
      }));
    }
  }

  async function upsertUser(client: Auth0Client, user: User) {
    try {
      const token = await client.getTokenSilently();
      await fetch(`${import.meta.env.VITE_API_URL}/api/auth/me`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          Authorization: `Bearer ${token}`,
        },
        body: JSON.stringify({ email: user.email, name: user.name }),
      });
    } catch {
      // Non-fatal — user still authed via Auth0
    }
  }

  async function login() {
    const state = get({ subscribe });
    await state.client?.loginWithRedirect();
  }

  async function logout() {
    const state = get({ subscribe });
    await state.client?.logout({
      logoutParams: { returnTo: window.location.origin },
    });
    set({ client: null, user: null, isAuthenticated: false, isLoading: false, error: null });
  }

  async function getToken(): Promise<string | null> {
    const state = get({ subscribe });
    if (!state.client) return null;
    try {
      return await state.client.getTokenSilently();
    } catch {
      return null;
    }
  }

  return { subscribe, init, login, logout, getToken };
}

export const auth = createAuthStore();
export const isAuthenticated = derived(auth, ($a) => $a.isAuthenticated);
export const currentUser = derived(auth, ($a) => $a.user);
export const authLoading = derived(auth, ($a) => $a.isLoading);
