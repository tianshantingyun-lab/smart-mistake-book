import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import {
  AuthGatewayError,
  DemoAuthGateway,
  RestAuthGateway,
} from "./authGateway";

const session = {
  mode: "authenticated" as const,
  displayName: "远程管理员",
  startedAt: "2026-07-31T00:00:00.000Z",
};

describe("DemoAuthGateway", () => {
  beforeEach(() => sessionStorage.clear());

  it("persists only validated demo sessions", async () => {
    const gateway = new DemoAuthGateway();
    expect(await gateway.current()).toBeNull();

    const loggedIn = await gateway.loginDemo();
    expect(loggedIn.mode).toBe("demo");
    expect(await gateway.current()).toEqual(loggedIn);

    await gateway.logout();
    expect(await gateway.current()).toBeNull();
  });

  it("removes malformed stored sessions", async () => {
    sessionStorage.setItem(
      "smart-mistake-book.website.admin-session",
      JSON.stringify({ mode: "admin", displayName: 42 }),
    );
    const gateway = new DemoAuthGateway();

    expect(await gateway.current()).toBeNull();
    expect(
      sessionStorage.getItem("smart-mistake-book.website.admin-session"),
    ).toBeNull();
  });
});

describe("RestAuthGateway", () => {
  const fetchMock = vi.fn<typeof fetch>();

  beforeEach(() => {
    fetchMock.mockReset();
    vi.stubGlobal("fetch", fetchMock);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it.each([401, 403])(
    "treats a %s session probe as signed out",
    async (status) => {
      fetchMock.mockResolvedValueOnce(new Response("", { status }));
      const gateway = new RestAuthGateway("https://content.example");

      expect(await gateway.current()).toBeNull();
    },
  );

  it("keeps a forbidden login as an explicit failure", async () => {
    fetchMock.mockResolvedValueOnce(
      new Response("当前账号无管理权限", { status: 403 }),
    );
    const gateway = new RestAuthGateway("https://content.example");

    await expect(gateway.loginDemo()).rejects.toMatchObject({
      message: "当前账号无管理权限",
      status: 403,
    });
  });

  it("waits for a validated remote session during login", async () => {
    fetchMock.mockResolvedValueOnce(
      new Response(JSON.stringify(session), {
        status: 200,
        headers: { "Content-Type": "application/json" },
      }),
    );
    const gateway = new RestAuthGateway("https://content.example");

    await expect(gateway.loginDemo()).resolves.toEqual(session);
    expect(fetchMock).toHaveBeenCalledWith(
      "https://content.example/api/v1/admin/session/demo",
      expect.objectContaining({ method: "POST", credentials: "include" }),
    );
  });

  it("rejects malformed remote sessions", async () => {
    fetchMock.mockResolvedValueOnce(
      new Response(
        JSON.stringify({ ...session, startedAt: "not-a-date" }),
        { status: 200 },
      ),
    );
    const gateway = new RestAuthGateway("https://content.example");

    await expect(gateway.loginDemo()).rejects.toBeInstanceOf(AuthGatewayError);
  });

  it("supports a no-content logout response", async () => {
    fetchMock.mockResolvedValueOnce(new Response(null, { status: 204 }));
    const gateway = new RestAuthGateway("https://content.example");

    await expect(gateway.logout()).resolves.toBeUndefined();
    expect(fetchMock).toHaveBeenCalledWith(
      "https://content.example/api/v1/admin/session",
      expect.objectContaining({ method: "DELETE", credentials: "include" }),
    );
  });

  it.each([401, 403])(
    "treats a %s logout response as already signed out",
    async (status) => {
      fetchMock.mockResolvedValueOnce(new Response("", { status }));
      const gateway = new RestAuthGateway("https://content.example");

      await expect(gateway.logout()).resolves.toBeUndefined();
    },
  );
});
