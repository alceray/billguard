import { writable, derived, get } from "svelte/store";
import {
  createAuth0Client,
  type Auth0Client,
  type User,
} from "@auth0/auth0-spa-js";
import { browser } from "$app/environment";
import { goto } from "$app/navigation";
import { provisionUser } from "$lib/auth/provision-user";

interface AuthState {
  client: Auth0Client | null;
  user: User | null;
  isAuthenticated: boolean;
  isLoading: boolean;
  error: string | null;
}

export class AuthSessionError extends Error {
  constructor(message: string, options?: ErrorOptions) {
    super(message, options);
    this.name = "AuthSessionError";
  }
}

const SESSION_ENDING_ERRORS = new Set([
  "account_selection_required",
  "consent_required",
  "interaction_required",
  "invalid_grant",
  "invalid_token",
  "login_required",
  "mfa_required",
  "missing_refresh_token",
]);

const LAST_ACTIVITY_KEY = "billguard.auth.lastActivityAt";
const INACTIVITY_LIMIT_MS = 7 * 24 * 60 * 60 * 1_000;
const ACTIVITY_WRITE_INTERVAL_MS = 60 * 1_000;

function requiresInteractiveLogin(cause: unknown): boolean {
  if (typeof cause !== "object" || cause === null || !("error" in cause)) {
    return false;
  }

  return SESSION_ENDING_ERRORS.has(String(cause.error));
}

function createAuthStore() {
  const { subscribe, set, update } = writable<AuthState>({
    client: null,
    user: null,
    isAuthenticated: false,
    isLoading: true,
    error: null,
  });

  let initPromise: Promise<void> | null = null;
  let inactivityTimer: number | null = null;
  let trackedClient: Auth0Client | null = null;
  let sessionEnding = false;

  function init(): Promise<void> {
    if (!browser) return Promise.resolve();

    initPromise ??= initialize();
    return initPromise;
  }

  async function initialize() {
    let client: Auth0Client | null = null;
    try {
      client = await createAuth0Client({
        domain: import.meta.env.VITE_AUTH0_DOMAIN,
        clientId: import.meta.env.VITE_AUTH0_CLIENT_ID,
        authorizationParams: {
          redirect_uri: window.location.origin + "/callback",
          audience: import.meta.env.VITE_AUTH0_AUDIENCE,
        },
        cacheLocation: "localstorage",
        useRefreshTokens: true,
        useRefreshTokensFallback: true,
      });

      let handledRedirect = false;
      if (
        window.location.search.includes("code=") ||
        window.location.search.includes("error=")
      ) {
        await client.handleRedirectCallback();
        handledRedirect = true;
        writeLastActivity(Date.now());
      } else if (inactivityExpired()) {
        await expireInactiveSession(client);
        return;
      }

      const isAuthenticated = await client.isAuthenticated();
      const user = isAuthenticated ? await client.getUser() : null;

      if (isAuthenticated) {
        try {
          // Validate or refresh the cached token before protected routes mount.
          const token = await client.getTokenSilently();
          if (user) {
            await provisionUser(
              import.meta.env.VITE_API_URL ?? "http://localhost:3001",
              user,
              token,
            );
          }
        } catch (cause) {
          if (requiresInteractiveLogin(cause)) {
            await clearLocalSession(client);
            return;
          }

          throw cause;
        }
      }

      set({
        client,
        user: user ?? null,
        isAuthenticated,
        isLoading: false,
        error: null,
      });

      if (isAuthenticated) {
        startActivityTracking(client);
      } else {
        clearLastActivity();
      }

      if (handledRedirect) {
        await goto("/dashboard", { replaceState: true });
      }
    } catch (err) {
      update((s) => ({
        ...s,
        client,
        user: null,
        isAuthenticated: false,
        isLoading: false,
        error: (err as Error).message,
      }));
    }
  }

  function retry() {
    initPromise = null;
    sessionEnding = false;
    update((state) => ({ ...state, isLoading: true, error: null }));
    return init();
  }

  async function login() {
    const state = get({ subscribe });
    await state.client?.loginWithRedirect();
  }

  async function logout() {
    const state = get({ subscribe });
    if (state.client) {
      await endSession(state.client, true);
    }
  }

  async function clearLocalSession(client: Auth0Client) {
    await client.logout({ openUrl: false });
    stopActivityTracking();
    clearLastActivity();
    set({
      client,
      user: null,
      isAuthenticated: false,
      isLoading: false,
      error: null,
    });

    if (window.location.pathname !== "/") {
      await goto("/", { replaceState: true });
    }
  }

  async function getToken(): Promise<string> {
    const state = get({ subscribe });
    if (!state.client || !state.isAuthenticated) {
      throw new AuthSessionError(
        "Your sign-in session is unavailable. Sign in again.",
      );
    }
    if (inactivityExpired()) {
      await expireInactiveSession(state.client);
      throw new AuthSessionError(
        "Your sign-in session expired after 7 days of inactivity.",
      );
    }
    try {
      return await state.client.getTokenSilently();
    } catch (cause) {
      if (requiresInteractiveLogin(cause)) {
        await clearLocalSession(state.client);
        throw new AuthSessionError("Your sign-in session expired.", { cause });
      }

      throw new AuthSessionError(
        "The sign-in service is temporarily unavailable. Try again.",
        {
          cause,
        },
      );
    }
  }

  function readLastActivity(): number | null {
    const value = window.localStorage.getItem(LAST_ACTIVITY_KEY);
    if (value === null) return null;

    const timestamp = Number(value);
    if (!Number.isFinite(timestamp) || timestamp <= 0) {
      clearLastActivity();
      return null;
    }
    return timestamp;
  }

  function writeLastActivity(timestamp: number) {
    window.localStorage.setItem(LAST_ACTIVITY_KEY, String(timestamp));
  }

  function clearLastActivity() {
    window.localStorage.removeItem(LAST_ACTIVITY_KEY);
  }

  function inactivityExpired(now = Date.now()): boolean {
    const lastActivity = readLastActivity();
    return lastActivity !== null && now - lastActivity >= INACTIVITY_LIMIT_MS;
  }

  function recordActivity() {
    const state = get({ subscribe });
    if (!state.isAuthenticated || !state.client || sessionEnding) return;

    const now = Date.now();
    const lastActivity = readLastActivity();
    if (
      lastActivity !== null &&
      now - lastActivity < ACTIVITY_WRITE_INTERVAL_MS
    ) {
      return;
    }
    if (lastActivity !== null && now - lastActivity >= INACTIVITY_LIMIT_MS) {
      void expireInactiveSession(state.client);
      return;
    }

    writeLastActivity(now);
    scheduleInactivityExpiration(state.client, now);
  }

  function startActivityTracking(client: Auth0Client) {
    stopActivityTracking();
    trackedClient = client;
    window.addEventListener("pointerdown", recordActivity);
    window.addEventListener("keydown", recordActivity);
    window.addEventListener("focus", recordActivity);
    window.addEventListener("storage", handleActivityStorageChange);

    const now = Date.now();
    writeLastActivity(now);
    scheduleInactivityExpiration(client, now);
  }

  function stopActivityTracking() {
    window.removeEventListener("pointerdown", recordActivity);
    window.removeEventListener("keydown", recordActivity);
    window.removeEventListener("focus", recordActivity);
    window.removeEventListener("storage", handleActivityStorageChange);
    trackedClient = null;
    if (inactivityTimer !== null) {
      window.clearTimeout(inactivityTimer);
      inactivityTimer = null;
    }
  }

  function handleActivityStorageChange(event: StorageEvent) {
    if (event.key !== LAST_ACTIVITY_KEY || !trackedClient) {
      return;
    }
    if (!event.newValue) {
      void clearLocalSession(trackedClient);
      return;
    }
    const timestamp = Number(event.newValue);
    if (Number.isFinite(timestamp) && timestamp > 0) {
      scheduleInactivityExpiration(trackedClient, timestamp);
    }
  }

  function scheduleInactivityExpiration(
    client: Auth0Client,
    lastActivity: number,
  ) {
    if (inactivityTimer !== null) {
      window.clearTimeout(inactivityTimer);
    }
    const remaining = Math.max(
      0,
      lastActivity + INACTIVITY_LIMIT_MS - Date.now(),
    );
    inactivityTimer = window.setTimeout(
      () => void expireInactiveSession(client),
      remaining,
    );
  }

  async function expireInactiveSession(client: Auth0Client) {
    await endSession(client, true);
  }

  async function endSession(client: Auth0Client, redirect: boolean) {
    if (sessionEnding) return;
    sessionEnding = true;
    stopActivityTracking();
    clearLastActivity();
    set({
      client,
      user: null,
      isAuthenticated: false,
      isLoading: false,
      error: null,
    });

    try {
      await client.logout(
        redirect
          ? { logoutParams: { returnTo: window.location.origin } }
          : { openUrl: false },
      );
    } catch {
      sessionEnding = false;
      if (window.location.pathname !== "/") {
        await goto("/", { replaceState: true });
      }
    }
  }

  return { subscribe, init, retry, login, logout, getToken };
}

export const auth = createAuthStore();
export const isAuthenticated = derived(auth, ($a) => $a.isAuthenticated);
export const currentUser = derived(auth, ($a) => $a.user);
export const authLoading = derived(auth, ($a) => $a.isLoading);
export const authError = derived(auth, ($a) => $a.error);
