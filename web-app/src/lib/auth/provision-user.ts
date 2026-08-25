export interface ProvisioningUser {
  email?: string;
  name?: string;
}

type Fetcher = (
  input: RequestInfo | URL,
  init?: RequestInit,
) => Promise<Response>;
type Pause = (milliseconds: number) => Promise<void>;

const DEFAULT_ATTEMPTS = 3;

export async function provisionUser(
  baseUrl: string,
  user: ProvisioningUser,
  token: string,
  fetcher: Fetcher = fetch,
  attempts = DEFAULT_ATTEMPTS,
  pause: Pause = (milliseconds) =>
    new Promise((resolve) => setTimeout(resolve, milliseconds)),
): Promise<void> {
  let lastError: Error | null = null;

  for (let attempt = 1; attempt <= attempts; attempt += 1) {
    try {
      const response = await fetcher(`${baseUrl}/api/auth/me`, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          Authorization: `Bearer ${token}`,
        },
        body: JSON.stringify({ email: user.email, name: user.name }),
      });

      if (response.ok) return;

      const body = await response.json().catch(() => ({}));
      const message =
        typeof body.error === "string"
          ? body.error
          : `Account setup failed with status ${response.status}`;
      lastError = new Error(message);
      if (response.status < 500) break;
    } catch (cause) {
      lastError =
        cause instanceof Error ? cause : new Error("Account setup failed");
    }

    if (attempt < attempts) {
      await pause(250 * attempt);
    }
  }

  throw new Error("Could not prepare your BillGuard account. Try again.", {
    cause: lastError,
  });
}
