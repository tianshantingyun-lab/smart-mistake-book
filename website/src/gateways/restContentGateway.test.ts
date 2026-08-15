import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { createSeedState } from "../data/seed";
import { createPublicWebsiteState } from "./contentSchemas";
import {
  ContentGatewayError,
  RestContentGateway,
} from "./contentGateway";
import type { DocumentationContent } from "../types";

function createDeferred<T>() {
  let resolve!: (value: T | PromiseLike<T>) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise;
    reject = rejectPromise;
  });
  return { promise, resolve, reject };
}

function jsonResponse(
  body: unknown,
  status = 200,
  etag: string | null = '"state-1"',
): Response {
  const headers = new Headers({ "Content-Type": "application/json" });
  if (etag !== null) headers.set("ETag", etag);
  return new Response(JSON.stringify(body), {
    status,
    headers,
  });
}

describe("RestContentGateway", () => {
  const fetchMock = vi.fn<typeof fetch>();

  beforeEach(() => {
    fetchMock.mockReset();
    vi.stubGlobal("fetch", fetchMock);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("loads a strict public DTO without exposing draft content", async () => {
    const admin = createSeedState();
    admin.site.draft.heroTitle = "远程私密草稿";
    const publicPayload = createPublicWebsiteState(admin);
    fetchMock.mockResolvedValueOnce(jsonResponse(publicPayload));
    const listener = vi.fn();
    const gateway = new RestContentGateway("https://content.example");
    gateway.subscribe(listener);

    const state = await gateway.load("public");

    expect(fetchMock).toHaveBeenCalledWith(
      "https://content.example/api/v1/public/state",
      expect.objectContaining({ credentials: "include" }),
    );
    expect(state.site.draft).toEqual(state.site.published);
    expect(state.site.published.heroTitle).not.toBe("远程私密草稿");
    expect(listener).toHaveBeenCalledOnce();
    expect(listener).toHaveBeenCalledWith(expect.any(Object), "public");
  });

  it("rejects a public response containing an unknown draft field", async () => {
    const leaked = structuredClone(
      createPublicWebsiteState(createSeedState()),
    ) as unknown as Record<string, unknown>;
    (leaked.site as Record<string, unknown>).draft = { secret: true };
    fetchMock.mockResolvedValueOnce(jsonResponse(leaked));
    const listener = vi.fn();
    const gateway = new RestContentGateway("https://content.example");
    gateway.subscribe(listener);

    await expect(gateway.load("public")).rejects.toMatchObject({
      message: "公开内容服务返回的数据结构无效",
      status: 502,
    });
    expect(listener).not.toHaveBeenCalled();
  });

  it("loads and broadcasts a fully validated admin snapshot", async () => {
    const admin = createSeedState();
    admin.site.draft.heroTitle = "远程管理草稿";
    fetchMock.mockResolvedValueOnce(jsonResponse(admin));
    const listener = vi.fn();
    const gateway = new RestContentGateway("https://content.example");
    gateway.subscribe(listener);

    const state = await gateway.load("admin");

    expect(fetchMock).toHaveBeenCalledWith(
      "https://content.example/api/v1/admin/state",
      expect.objectContaining({ credentials: "include" }),
    );
    expect(state.site.draft.heroTitle).toBe("远程管理草稿");
    expect(listener).toHaveBeenCalledWith(expect.any(Object), "admin");
  });

  it("broadcasts admin authorization failures before reading their body and preserves the HTTP error", async () => {
    const firstListener = vi.fn(() => {
      throw new Error("监听器失败");
    });
    const secondListener = vi.fn();
    const errorText = vi.fn(async () => {
      expect(firstListener).toHaveBeenCalledWith(403);
      expect(secondListener).toHaveBeenCalledWith(403);
      return "管理权限已撤销";
    });
    fetchMock.mockResolvedValueOnce({
      ok: false,
      status: 403,
      text: errorText,
    } as unknown as Response);
    const gateway = new RestContentGateway("https://content.example");
    gateway.subscribeAuthorizationFailure(firstListener);
    gateway.subscribeAuthorizationFailure(secondListener);

    await expect(gateway.load("admin")).rejects.toMatchObject({
      message: "管理权限已撤销",
      status: 403,
    });
    expect(errorText).toHaveBeenCalledOnce();
  });

  it("broadcasts an authorization revocation once and rejects later mutations without network access", async () => {
    fetchMock.mockResolvedValueOnce(
      new Response("会话已过期", { status: 401 }),
    );
    const listener = vi.fn();
    const gateway = new RestContentGateway("https://content.example");
    gateway.subscribeAuthorizationFailure(listener);

    await expect(gateway.load("admin")).rejects.toMatchObject({
      message: "会话已过期",
      status: 401,
    });
    await expect(
      gateway.saveSiteDraft(createSeedState().site.draft),
    ).rejects.toMatchObject({
      message: "管理会话已失效，请重新登录并重新加载管理数据",
      status: 401,
    });
    expect(fetchMock).toHaveBeenCalledOnce();
    expect(listener.mock.calls).toEqual([[401]]);
  });

  it("does not rebroadcast repeated authorization failures before recovery", async () => {
    fetchMock
      .mockResolvedValueOnce(new Response("会话已过期", { status: 401 }))
      .mockResolvedValueOnce(new Response("权限仍未恢复", { status: 403 }));
    const listener = vi.fn();
    const gateway = new RestContentGateway("https://content.example");
    gateway.subscribeAuthorizationFailure(listener);

    await expect(gateway.load("admin")).rejects.toMatchObject({ status: 401 });
    await expect(gateway.load("admin")).rejects.toMatchObject({
      message: "管理会话已失效，请重新登录并重新加载管理数据",
      status: 401,
    });
    expect(fetchMock).toHaveBeenCalledOnce();

    gateway.confirmAdminAuthentication();
    await expect(gateway.load("admin")).rejects.toMatchObject({ status: 403 });
    await expect(gateway.load("admin")).rejects.toMatchObject({ status: 401 });

    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(listener.mock.calls).toEqual([[401]]);
  });

  it("does not reuse an explicit recovery grant after a non-authentication load failure", async () => {
    const admin = createSeedState();
    fetchMock
      .mockResolvedValueOnce(new Response("会话已过期", { status: 401 }))
      .mockRejectedValueOnce(new TypeError("network down"))
      .mockResolvedValueOnce(jsonResponse(admin, 200, '"state-10"'));
    const gateway = new RestContentGateway("https://content.example");
    await expect(gateway.load("admin")).rejects.toMatchObject({ status: 401 });

    gateway.confirmAdminAuthentication();
    await expect(gateway.load("admin")).rejects.toThrow("network down");
    await expect(gateway.load("admin")).rejects.toMatchObject({
      message: "管理会话已失效，请重新登录并重新加载管理数据",
      status: 401,
    });
    expect(fetchMock).toHaveBeenCalledTimes(2);

    gateway.confirmAdminAuthentication();
    await expect(gateway.load("admin")).resolves.toMatchObject({
      schemaVersion: 2,
    });
    expect(fetchMock).toHaveBeenCalledTimes(3);
  });

  it.each([401, 403, 422, 500])(
    "does not broadcast a %s response from a public URL",
    async (status) => {
      fetchMock.mockResolvedValueOnce(
        new Response("公开内容请求失败", { status }),
      );
      const listener = vi.fn();
      const gateway = new RestContentGateway("https://content.example");
      gateway.subscribeAuthorizationFailure(listener);

      await expect(gateway.load("public")).rejects.toMatchObject({ status });
      expect(listener).not.toHaveBeenCalled();
    },
  );

  it("rejects malformed admin responses without notifying subscribers", async () => {
    const malformed = createSeedState();
    malformed.product.draft.blocks = [
      {
        id: "invalid",
        type: "gallery",
        enabled: true,
        title: "",
        summary: "",
        mediaIds: [],
        columns: 4,
      } as never,
    ];
    fetchMock.mockResolvedValueOnce(jsonResponse(malformed));
    const listener = vi.fn();
    const gateway = new RestContentGateway("https://content.example");
    gateway.subscribe(listener);

    await expect(gateway.load("admin")).rejects.toBeInstanceOf(
      ContentGatewayError,
    );
    expect(listener).not.toHaveBeenCalled();
  });

  it("validates management mutation responses through the same boundary", async () => {
    const admin = createSeedState();
    fetchMock
      .mockResolvedValueOnce(jsonResponse(admin, 200, '"state-1"'))
      .mockResolvedValueOnce(
        jsonResponse({ schemaVersion: 2, site: {} }, 200, '"state-2"'),
      );
    const listener = vi.fn();
    const gateway = new RestContentGateway("https://content.example");
    gateway.subscribe(listener);
    await gateway.load("admin");
    listener.mockClear();

    await expect(
      gateway.saveSiteDraft(createSeedState().site.draft),
    ).rejects.toMatchObject({
      message: "内容服务返回的数据结构无效，必须重新同步",
      status: 502,
    });
    expect(listener).not.toHaveBeenCalled();
  });

  it("sends only documentation draft content and exposes the archive endpoint", async () => {
    const admin = createSeedState();
    fetchMock
      .mockResolvedValueOnce(jsonResponse(admin, 200, '"state-1"'))
      .mockResolvedValueOnce(jsonResponse(admin, 200, '"state-2"'))
      .mockResolvedValueOnce(jsonResponse(admin, 200, '"state-3"'));
    const gateway = new RestContentGateway("https://content.example");
    const content = {
      title: "快速开始",
      slug: "quick-start",
      summary: "第一篇文档",
      markdown: "# 快速开始",
      parentId: null,
      order: 0,
      status: "published",
      publishedAt: "2026-07-26T00:00:00.000Z",
    } as DocumentationContent;

    await gateway.load("admin");
    await gateway.saveDocDraft("doc-1", content);
    await gateway.archiveDoc("doc-1");

    expect(fetchMock).toHaveBeenNthCalledWith(
      2,
      "https://content.example/api/v1/admin/docs/doc-1",
      expect.objectContaining({
        method: "PUT",
        body: JSON.stringify({
          title: "快速开始",
          slug: "quick-start",
          summary: "第一篇文档",
          markdown: "# 快速开始",
          parentId: null,
          order: 0,
        }),
      }),
    );
    expect(fetchMock).toHaveBeenNthCalledWith(
      3,
      "https://content.example/api/v1/admin/docs/doc-1/archive",
      expect.objectContaining({ method: "POST" }),
    );
  });

  it("uses analytics-only draft and publish endpoints", async () => {
    const admin = createSeedState();
    fetchMock
      .mockResolvedValueOnce(jsonResponse(admin, 200, '"state-1"'))
      .mockResolvedValueOnce(jsonResponse(admin, 200, '"state-2"'))
      .mockResolvedValueOnce(jsonResponse(admin, 200, '"state-3"'));
    const gateway = new RestContentGateway("https://content.example");
    const analytics = {
      providerName: "自建分析",
      siteId: "site-123",
    };

    await gateway.load("admin");
    await gateway.saveAnalyticsDraft(analytics);
    await gateway.publishAnalytics();

    expect(fetchMock).toHaveBeenNthCalledWith(
      2,
      "https://content.example/api/v1/admin/analytics/draft",
      expect.objectContaining({
        method: "PUT",
        body: JSON.stringify(analytics),
      }),
    );
    expect(fetchMock).toHaveBeenNthCalledWith(
      3,
      "https://content.example/api/v1/admin/analytics/publish",
      expect.objectContaining({ method: "POST" }),
    );
    expect(fetchMock.mock.calls[2][1]?.body).toBeUndefined();
  });

  it("serializes an admin load before a mutation and uses the loaded ETag", async () => {
    const admin = createSeedState();
    const loadResponse = createDeferred<Response>();
    fetchMock
      .mockImplementationOnce(() => loadResponse.promise)
      .mockResolvedValueOnce(jsonResponse(admin, 200, '"state-2"'));
    const gateway = new RestContentGateway("https://content.example");

    const load = gateway.load("admin");
    const mutation = gateway.saveSiteDraft(admin.site.draft);

    await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledOnce());
    loadResponse.resolve(jsonResponse(admin, 200, '"state-1"'));
    await load;
    await mutation;

    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(
      new Headers(fetchMock.mock.calls[1][1]?.headers).get("If-Match"),
    ).toBe('"state-1"');
  });

  it("serializes a mutation before a later admin load", async () => {
    const admin = createSeedState();
    const mutationResponse = createDeferred<Response>();
    fetchMock
      .mockResolvedValueOnce(jsonResponse(admin, 200, '"state-1"'))
      .mockImplementationOnce(() => mutationResponse.promise)
      .mockResolvedValueOnce(jsonResponse(admin, 200, '"state-3"'));
    const gateway = new RestContentGateway("https://content.example");
    await gateway.load("admin");

    const mutation = gateway.saveSiteDraft(admin.site.draft);
    const reload = gateway.load("admin");

    await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2));
    mutationResponse.resolve(jsonResponse(admin, 200, '"state-2"'));
    await mutation;
    await reload;

    expect(fetchMock).toHaveBeenCalledTimes(3);
    expect(fetchMock.mock.calls[2][0]).toBe(
      "https://content.example/api/v1/admin/state",
    );
  });

  it("chains each successful mutation from the preceding strong ETag", async () => {
    const admin = createSeedState();
    fetchMock
      .mockResolvedValueOnce(jsonResponse(admin, 200, '"state-1"'))
      .mockResolvedValueOnce(jsonResponse(admin, 200, '"state-2"'))
      .mockResolvedValueOnce(jsonResponse(admin, 200, '"state-3"'));
    const gateway = new RestContentGateway("https://content.example");

    await gateway.load("admin");
    await gateway.saveSiteDraft(admin.site.draft);
    await gateway.publishSite();

    expect(
      new Headers(fetchMock.mock.calls[1][1]?.headers).get("If-Match"),
    ).toBe('"state-1"');
    expect(
      new Headers(fetchMock.mock.calls[2][1]?.headers).get("If-Match"),
    ).toBe('"state-2"');
  });

  it("does not let an ordinary conflict poison the FIFO tail", async () => {
    const admin = createSeedState();
    fetchMock
      .mockResolvedValueOnce(jsonResponse(admin, 200, '"state-1"'))
      .mockResolvedValueOnce(new Response("字段冲突", { status: 409 }))
      .mockResolvedValueOnce(jsonResponse(admin, 200, '"state-2"'));
    const gateway = new RestContentGateway("https://content.example");
    await gateway.load("admin");

    await expect(
      gateway.saveSiteDraft(admin.site.draft),
    ).rejects.toMatchObject({ status: 409, message: "字段冲突" });
    await expect(gateway.load("admin")).resolves.toMatchObject({
      schemaVersion: 2,
    });
    expect(fetchMock).toHaveBeenCalledTimes(3);
  });

  it("cancels queued work after an authorization failure and accepts the authenticated recovery load scheduled by the login controller", async () => {
    const admin = createSeedState();
    const unauthorized = createDeferred<Response>();
    fetchMock
      .mockResolvedValueOnce(jsonResponse(admin, 200, '"state-1"'))
      .mockImplementationOnce(() => unauthorized.promise);
    const listener = vi.fn();
    const gateway = new RestContentGateway("https://content.example");
    gateway.subscribeAuthorizationFailure(listener);
    await gateway.load("admin");

    const failing = gateway.saveSiteDraft(admin.site.draft);
    const queued = gateway.publishSite();
    unauthorized.resolve(new Response("会话已过期", { status: 401 }));

    await expect(failing).rejects.toMatchObject({ status: 401 });
    await expect(queued).rejects.toMatchObject({
      message: "管理会话已失效，请重新登录并重新加载管理数据",
      status: 401,
    });
    await expect(gateway.publishSite()).rejects.toMatchObject({ status: 401 });
    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(listener).toHaveBeenCalledTimes(1);

    fetchMock
      .mockResolvedValueOnce(jsonResponse(admin, 200, '"state-10"'))
      .mockResolvedValueOnce(jsonResponse(admin, 200, '"state-11"'));
    await expect(gateway.load("admin")).rejects.toMatchObject({ status: 401 });
    expect(fetchMock).toHaveBeenCalledTimes(2);

    gateway.confirmAdminAuthentication();
    await gateway.load("admin");
    await gateway.publishSite();

    expect(fetchMock).toHaveBeenCalledTimes(4);
    expect(
      new Headers(fetchMock.mock.calls[3][1]?.headers).get("If-Match"),
    ).toBe('"state-10"');
  });

  it("ignores an old in-flight response after explicit authentication starts a new epoch", async () => {
    const admin = createSeedState();
    const oldMutationResponse = createDeferred<Response>();
    fetchMock
      .mockResolvedValueOnce(jsonResponse(admin, 200, '"state-1"'))
      .mockImplementationOnce(() => oldMutationResponse.promise)
      .mockResolvedValueOnce(jsonResponse(admin, 200, '"state-10"'))
      .mockResolvedValueOnce(jsonResponse(admin, 200, '"state-11"'));
    const listener = vi.fn();
    const gateway = new RestContentGateway("https://content.example");
    gateway.subscribe(listener);
    await gateway.load("admin");
    listener.mockClear();

    const oldMutation = gateway.saveSiteDraft(admin.site.draft);
    await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2));
    gateway.confirmAdminAuthentication();
    const recoveryLoad = gateway.load("admin");
    const prematurelyQueuedWrite = gateway.publishSite();
    const prematurelyQueuedWriteResult = expect(
      prematurelyQueuedWrite,
    ).rejects.toMatchObject({
      message: "管理授权上下文已变化，已忽略旧请求",
      status: 409,
    });

    oldMutationResponse.resolve(jsonResponse(admin, 200, '"state-2"'));
    await expect(oldMutation).rejects.toMatchObject({
      message: "管理授权上下文已变化，已忽略旧请求",
      status: 409,
    });
    await recoveryLoad;
    await prematurelyQueuedWriteResult;

    expect(fetchMock).toHaveBeenCalledTimes(3);
    expect(listener).toHaveBeenCalledOnce();

    await gateway.publishSite();
    expect(fetchMock).toHaveBeenCalledTimes(4);
    expect(
      new Headers(fetchMock.mock.calls[3][1]?.headers).get("If-Match"),
    ).toBe('"state-10"');
  });

  it("does not parse or broadcast an old in-flight admin load after a new authentication signal", async () => {
    const admin = createSeedState();
    const oldLoadResponse = createDeferred<Response>();
    fetchMock
      .mockResolvedValueOnce(jsonResponse(admin, 200, '"state-1"'))
      .mockImplementationOnce(() => oldLoadResponse.promise)
      .mockResolvedValueOnce(jsonResponse(admin, 200, '"state-10"'));
    const listener = vi.fn();
    const gateway = new RestContentGateway("https://content.example");
    gateway.subscribe(listener);
    await gateway.load("admin");
    listener.mockClear();

    const oldLoad = gateway.load("admin");
    await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2));
    gateway.confirmAdminAuthentication();
    const recoveryLoad = gateway.load("admin");
    const oldJson = vi.fn(async () => admin);
    oldLoadResponse.resolve({
      ok: true,
      status: 200,
      headers: new Headers({ ETag: '"state-2"' }),
      json: oldJson,
    } as unknown as Response);

    await expect(oldLoad).rejects.toMatchObject({
      message: "管理授权上下文已变化，已忽略旧请求",
      status: 409,
    });
    await recoveryLoad;

    expect(fetchMock).toHaveBeenCalledTimes(3);
    expect(oldJson).not.toHaveBeenCalled();
    expect(listener).toHaveBeenCalledOnce();
  });

  it("isolates content subscribers and gives each subscriber its own snapshot", async () => {
    const admin = createSeedState();
    const first = vi.fn((state) => {
      state.site.draft.heroTitle = "监听器污染";
      throw new Error("监听器失败");
    });
    const second = vi.fn();
    fetchMock.mockResolvedValueOnce(jsonResponse(admin, 200, '"state-1"'));
    const gateway = new RestContentGateway("https://content.example");
    gateway.subscribe(first);
    gateway.subscribe(second);

    const state = await gateway.load("admin");

    expect(state.site.draft.heroTitle).toBe(admin.site.draft.heroTitle);
    expect(second).toHaveBeenCalledWith(
      expect.objectContaining({
        site: expect.objectContaining({
          draft: expect.objectContaining({
            heroTitle: admin.site.draft.heroTitle,
          }),
        }),
      }),
      "admin",
    );
  });

  it.each([
    ["missing", null],
    ["weak", 'W/"state-1"'],
  ])("requires a %s admin ETag to be replaced by a strong ETag", async (_label, etag) => {
    const admin = createSeedState();
    fetchMock
      .mockResolvedValueOnce(jsonResponse(admin, 200, etag))
      .mockResolvedValueOnce(jsonResponse(admin, 200, '"state-2"'))
      .mockResolvedValueOnce(jsonResponse(admin, 200, '"state-3"'));
    const gateway = new RestContentGateway("https://content.example");

    await expect(gateway.load("admin")).rejects.toMatchObject({
      message: "管理内容响应缺少有效的强 ETag，必须重新同步",
      status: 502,
    });
    await expect(gateway.publishSite()).rejects.toMatchObject({
      message: "管理内容状态需要重新同步，请先重新加载管理数据",
      status: 409,
    });
    expect(fetchMock).toHaveBeenCalledOnce();

    await gateway.load("admin");
    await gateway.publishSite();
    expect(fetchMock).toHaveBeenCalledTimes(3);
  });

  it.each([
    {
      label: "network failure",
      failure: () => Promise.reject(new TypeError("network down")),
    },
    {
      label: "server failure",
      failure: () => Promise.resolve(new Response("服务异常", { status: 500 })),
    },
    {
      label: "invalid JSON success",
      failure: () =>
        Promise.resolve(
          new Response("not-json", {
            status: 200,
            headers: { ETag: '"state-2"' },
          }),
        ),
    },
    {
      label: "invalid schema success",
      failure: () =>
        Promise.resolve(
          jsonResponse({ schemaVersion: 2, site: {} }, 200, '"state-2"'),
        ),
    },
    {
      label: "missing mutation ETag",
      failure: () => Promise.resolve(jsonResponse(createSeedState(), 200, null)),
    },
  ])("requires an admin reload after $label", async ({ failure }) => {
    const admin = createSeedState();
    fetchMock
      .mockResolvedValueOnce(jsonResponse(admin, 200, '"state-1"'))
      .mockImplementationOnce(failure)
      .mockResolvedValueOnce(jsonResponse(admin, 200, '"state-10"'))
      .mockResolvedValueOnce(jsonResponse(admin, 200, '"state-11"'));
    const gateway = new RestContentGateway("https://content.example");
    await gateway.load("admin");

    await expect(gateway.saveSiteDraft(admin.site.draft)).rejects.toBeDefined();
    await expect(gateway.publishSite()).rejects.toMatchObject({
      message: "管理内容状态需要重新同步，请先重新加载管理数据",
      status: 409,
    });
    expect(fetchMock).toHaveBeenCalledTimes(2);

    await gateway.load("admin");
    await gateway.publishSite();
    expect(fetchMock).toHaveBeenCalledTimes(4);
  });

  it.each([
    [412, "版本已变化"],
    [428, "服务端要求重新建立写入前置条件"],
  ])(
    "treats a %s response as stale state until a new admin load succeeds",
    async (status, message) => {
      const admin = createSeedState();
      fetchMock
        .mockResolvedValueOnce(jsonResponse(admin, 200, '"state-1"'))
        .mockResolvedValueOnce(new Response(message, { status }))
        .mockResolvedValueOnce(jsonResponse(admin, 200, '"state-5"'))
        .mockResolvedValueOnce(jsonResponse(admin, 200, '"state-6"'));
      const gateway = new RestContentGateway("https://content.example");
      await gateway.load("admin");

      await expect(gateway.publishSite()).rejects.toMatchObject({
        status,
        message,
      });
      await expect(gateway.publishSite()).rejects.toMatchObject({ status: 409 });
      expect(fetchMock).toHaveBeenCalledTimes(2);

      await gateway.load("admin");
      await gateway.publishSite();
      expect(fetchMock).toHaveBeenCalledTimes(4);
    },
  );

  it("suppresses a stale public broadcast while still resolving both callers", async () => {
    const firstResponse = createDeferred<Response>();
    const secondResponse = createDeferred<Response>();
    const firstPublic = createPublicWebsiteState(createSeedState());
    const secondAdmin = createSeedState();
    secondAdmin.site.published.heroTitle = "较新的公开内容";
    const secondPublic = createPublicWebsiteState(secondAdmin);
    fetchMock
      .mockImplementationOnce(() => firstResponse.promise)
      .mockImplementationOnce(() => secondResponse.promise);
    const listener = vi.fn();
    const gateway = new RestContentGateway("https://content.example");
    gateway.subscribe(listener);

    const first = gateway.load("public");
    const second = gateway.load("public");
    secondResponse.resolve(jsonResponse(secondPublic));
    await second;
    firstResponse.resolve(jsonResponse(firstPublic));
    await first;

    expect(listener).toHaveBeenCalledOnce();
    expect(listener.mock.calls[0][0].site.published.heroTitle).toBe(
      "较新的公开内容",
    );
  });

  it("surfaces non-JSON success responses as a contract error", async () => {
    fetchMock.mockResolvedValueOnce(
      new Response("not-json", {
        status: 200,
        headers: { "Content-Type": "text/plain" },
      }),
    );
    const gateway = new RestContentGateway("https://content.example");

    await expect(gateway.load("public")).rejects.toMatchObject({
      message: "内容服务返回了无法解析的数据",
      status: 200,
    });
  });
});
