import { describe, expect, it, vi } from "vitest";
import { provisionUser } from "./provision-user";

describe("provisionUser", () => {
  it("sends the authenticated user to the core API", async () => {
    const fetcher = vi
      .fn()
      .mockResolvedValue(new Response(null, { status: 200 }));

    await provisionUser(
      "http://localhost:3001",
      { email: "ray@example.com", name: "Ray" },
      "token",
      fetcher,
    );

    expect(fetcher).toHaveBeenCalledWith(
      "http://localhost:3001/api/auth/me",
      expect.objectContaining({
        method: "POST",
        headers: expect.objectContaining({ Authorization: "Bearer token" }),
      }),
    );
  });

  it("rejects a non-successful response instead of mounting protected pages", async () => {
    const fetcher = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ error: "Invalid or missing token" }), {
        status: 401,
        headers: { "Content-Type": "application/json" },
      }),
    );

    await expect(
      provisionUser("http://localhost:3001", {}, "bad-token", fetcher),
    ).rejects.toThrow("Could not prepare your BillGuard account");
    expect(fetcher).toHaveBeenCalledTimes(1);
  });

  it("retries transient failures", async () => {
    const fetcher = vi
      .fn()
      .mockRejectedValueOnce(new Error("connection refused"))
      .mockResolvedValueOnce(new Response(null, { status: 200 }));
    const pause = vi.fn().mockResolvedValue(undefined);

    await provisionUser(
      "http://localhost:3001",
      {},
      "token",
      fetcher,
      3,
      pause,
    );

    expect(fetcher).toHaveBeenCalledTimes(2);
    expect(pause).toHaveBeenCalledOnce();
  });
});
