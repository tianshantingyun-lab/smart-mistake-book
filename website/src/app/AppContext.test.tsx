import { act, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useState } from "react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { createSeedState } from "../data/seed";
import type { AuthGateway } from "../gateways/authGateway";
import type {
  AuthorizationFailureStatus,
  ContentGateway,
  ContentScope,
} from "../gateways/contentGateway";
import { ContentGatewayError } from "../gateways/contentGateway";
import type { AdminSession, WebsiteState } from "../types";
import { AppProvider, useApp } from "./AppContext";

const remoteSession: AdminSession = {
  mode: "authenticated",
  displayName: "远程管理员",
  startedAt: "2026-07-31T00:00:00.000Z",
};

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason: unknown) => void;
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise;
    reject = rejectPromise;
  });
  return { promise, resolve, reject };
}

type ContentListener = Parameters<ContentGateway["subscribe"]>[0];
type AuthorizationFailureListener = Parameters<
  ContentGateway["subscribeAuthorizationFailure"]
>[0];

interface TestContentGateway extends ContentGateway {
  emitAuthorizationFailure(status: AuthorizationFailureStatus): void;
  emitState(state: WebsiteState, scope: ContentScope): void;
}

function makeContentGateway(
  load: (scope: ContentScope) => Promise<WebsiteState>,
): TestContentGateway {
  const contentListeners = new Set<ContentListener>();
  const authorizationFailureListeners =
    new Set<AuthorizationFailureListener>();

  return {
    mode: "remote" as const,
    load: vi.fn(load),
    confirmAdminAuthentication: vi.fn(),
    subscribe: vi.fn((listener: ContentListener) => {
      contentListeners.add(listener);
      return () => contentListeners.delete(listener);
    }),
    subscribeAuthorizationFailure: vi.fn(
      (listener: AuthorizationFailureListener) => {
        authorizationFailureListeners.add(listener);
        return () => authorizationFailureListeners.delete(listener);
      },
    ),
    emitAuthorizationFailure(status: AuthorizationFailureStatus) {
      authorizationFailureListeners.forEach((listener) => listener(status));
    },
    emitState(state: WebsiteState, scope: ContentScope) {
      contentListeners.forEach((listener) => listener(state, scope));
    },
  } as unknown as TestContentGateway;
}

function makeAuthGateway(
  overrides: Partial<AuthGateway> = {},
): AuthGateway {
  return {
    mode: "remote",
    current: vi.fn(async () => null),
    loginDemo: vi.fn(async () => remoteSession),
    logout: vi.fn(async () => undefined),
    ...overrides,
  };
}

function ContextProbe() {
  const { state, contentScope, session, loginDemo, logout, runtimeMode } =
    useApp();
  const [error, setError] = useState("");

  return (
    <div>
      <p>scope:{contentScope}</p>
      <p>mode:{runtimeMode}</p>
      <p>session:{session?.displayName ?? "none"}</p>
      <p>draft:{state.site.draft.heroTitle}</p>
      <p role="alert">{error}</p>
      <button
        type="button"
        onClick={async () => {
          try {
            await loginDemo();
          } catch (caught) {
            setError(caught instanceof Error ? caught.message : "登录失败");
          }
        }}
      >
        登录
      </button>
      <button
        type="button"
        onClick={async () => {
          try {
            await logout();
          } catch (caught) {
            setError(caught instanceof Error ? caught.message : "退出失败");
          }
        }}
      >
        退出
      </button>
    </div>
  );
}

async function renderAdminWithPendingLogout() {
  const publicState = createSeedState();
  const adminState = createSeedState();
  adminState.site.draft.heroTitle = "远程私密管理草稿";
  const pendingLogout = deferred<void>();
  const content = makeContentGateway(async (scope) =>
    structuredClone(scope === "admin" ? adminState : publicState),
  );
  const auth = makeAuthGateway({
    logout: vi.fn(() => pendingLogout.promise),
  });
  const user = userEvent.setup();

  const view = render(
    <AppProvider contentGateway={content} authGateway={auth}>
      <ContextProbe />
    </AppProvider>,
  );
  await screen.findByText("scope:public");
  await user.click(screen.getByRole("button", { name: "登录" }));
  await screen.findByText("draft:远程私密管理草稿");

  return { auth, content, pendingLogout, user, unmount: view.unmount };
}

describe("AppProvider content scopes", () => {
  beforeEach(() => {
    localStorage.clear();
    sessionStorage.clear();
  });

  it("loads public content first, then swaps to admin and discards it on logout", async () => {
    const publicState = createSeedState();
    publicState.site.draft = structuredClone(publicState.site.published);
    const adminState = createSeedState();
    adminState.site.draft.heroTitle = "远程私密管理草稿";
    const content = makeContentGateway(async (scope) =>
      structuredClone(scope === "admin" ? adminState : publicState),
    );
    const auth = makeAuthGateway();
    const user = userEvent.setup();

    render(
      <AppProvider contentGateway={content} authGateway={auth}>
        <ContextProbe />
      </AppProvider>,
    );

    expect(await screen.findByText("scope:public")).toBeVisible();
    expect(screen.getByText("session:none")).toBeVisible();
    expect(screen.getByText("mode:remote")).toBeVisible();

    await user.click(screen.getByRole("button", { name: "登录" }));
    expect(await screen.findByText("scope:admin")).toBeVisible();
    expect(screen.getByText("session:远程管理员")).toBeVisible();
    expect(screen.getByText("draft:远程私密管理草稿")).toBeVisible();

    await user.click(screen.getByRole("button", { name: "退出" }));
    expect(await screen.findByText("scope:public")).toBeVisible();
    expect(screen.getByText("session:none")).toBeVisible();
    expect(screen.queryByText("draft:远程私密管理草稿")).not.toBeInTheDocument();
    expect(auth.logout).toHaveBeenCalledOnce();
    expect(content.load).toHaveBeenNthCalledWith(1, "public");
    expect(content.load).toHaveBeenNthCalledWith(2, "admin");
    expect(content.load).toHaveBeenNthCalledWith(3, "public");
    expect(content.confirmAdminAuthentication).toHaveBeenCalledOnce();
    const loginOrder = vi.mocked(auth.loginDemo).mock.invocationCallOrder[0];
    const confirmationOrder = vi.mocked(content.confirmAdminAuthentication)
      .mock.invocationCallOrder[0];
    const adminLoadOrder = vi.mocked(content.load).mock.invocationCallOrder[1];
    expect(loginOrder).toBeLessThan(confirmationOrder);
    expect(confirmationOrder).toBeLessThan(adminLoadOrder);
  });

  it("does not confirm content authentication when the login request fails", async () => {
    const publicState = createSeedState();
    const content = makeContentGateway(async () =>
      structuredClone(publicState),
    );
    const auth = makeAuthGateway({
      loginDemo: vi.fn(async () => {
        throw new Error("登录凭据无效");
      }),
    });
    const user = userEvent.setup();

    render(
      <AppProvider contentGateway={content} authGateway={auth}>
        <ContextProbe />
      </AppProvider>,
    );
    await screen.findByText("scope:public");

    await user.click(screen.getByRole("button", { name: "登录" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("登录凭据无效");
    expect(content.confirmAdminAuthentication).not.toHaveBeenCalled();
    expect(content.load).toHaveBeenCalledTimes(1);
  });

  it("keeps the public snapshot when admin loading fails after login", async () => {
    const publicState = createSeedState();
    const content = makeContentGateway(async (scope) => {
      if (scope === "admin") throw new Error("管理内容加载失败");
      return structuredClone(publicState);
    });
    const auth = makeAuthGateway();
    const user = userEvent.setup();

    render(
      <AppProvider contentGateway={content} authGateway={auth}>
        <ContextProbe />
      </AppProvider>,
    );
    await screen.findByText("scope:public");

    await user.click(screen.getByRole("button", { name: "登录" }));
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "管理内容加载失败",
    );
    expect(screen.getByText("scope:public")).toBeVisible();
    expect(screen.getByText("session:none")).toBeVisible();
    expect(auth.logout).toHaveBeenCalledOnce();
    expect(
      sessionStorage.getItem("smart-mistake-book.website.remote-signed-out"),
    ).toBe("1");
  });

  it("does not duplicate logout when an admin login load broadcasts its failure", async () => {
    const publicState = createSeedState();
    let content!: TestContentGateway;
    content = makeContentGateway(async (scope) => {
      if (scope === "admin") {
        content.emitAuthorizationFailure(403);
        throw new ContentGatewayError("管理权限已撤销", 403);
      }
      return structuredClone(publicState);
    });
    const auth = makeAuthGateway();
    const user = userEvent.setup();

    render(
      <AppProvider contentGateway={content} authGateway={auth}>
        <ContextProbe />
      </AppProvider>,
    );
    await screen.findByText("scope:public");

    await user.click(screen.getByRole("button", { name: "登录" }));
    expect(await screen.findByText("scope:public")).toBeVisible();
    expect(auth.logout).toHaveBeenCalledOnce();
  });

  it("clears a stale authenticated session when admin state returns 401", async () => {
    const publicState = createSeedState();
    const content = makeContentGateway(async (scope) => {
      if (scope === "admin") throw new ContentGatewayError("未授权", 401);
      return structuredClone(publicState);
    });
    const auth = makeAuthGateway({
      current: vi.fn(async () => remoteSession),
    });

    render(
      <AppProvider contentGateway={content} authGateway={auth}>
        <ContextProbe />
      </AppProvider>,
    );

    expect(await screen.findByText("scope:public")).toBeVisible();
    expect(screen.getByText("session:none")).toBeVisible();
    expect(auth.logout).toHaveBeenCalledOnce();
    expect(content.load).toHaveBeenNthCalledWith(1, "admin");
    expect(content.load).toHaveBeenNthCalledWith(2, "public");
  });

  it("ignores a late admin response after logout starts", async () => {
    const publicState = createSeedState();
    const adminState = createSeedState();
    adminState.site.draft.heroTitle = "迟到的管理草稿";
    const pendingAdmin = deferred<WebsiteState>();
    const content = makeContentGateway((scope) =>
      scope === "admin"
        ? pendingAdmin.promise
        : Promise.resolve(structuredClone(publicState)),
    );
    const auth = makeAuthGateway();
    const user = userEvent.setup();

    render(
      <AppProvider contentGateway={content} authGateway={auth}>
        <ContextProbe />
      </AppProvider>,
    );
    await screen.findByText("scope:public");

    await user.click(screen.getByRole("button", { name: "登录" }));
    await user.click(screen.getByRole("button", { name: "退出" }));
    expect(await screen.findByText("scope:public")).toBeVisible();

    pendingAdmin.resolve(adminState);
    await Promise.resolve();
    expect(screen.getByText("scope:public")).toBeVisible();
    expect(screen.getByText("session:none")).toBeVisible();
    expect(screen.queryByText("draft:迟到的管理草稿")).not.toBeInTheDocument();
  });

  it("clears admin content before remote logout settles", async () => {
    const { content, user } = await renderAdminWithPendingLogout();
    await user.click(screen.getByRole("button", { name: "退出" }));

    expect(screen.getByText("正在准备官网内容…")).toBeVisible();
    expect(
      screen.queryByText("draft:远程私密管理草稿"),
    ).not.toBeInTheDocument();
    expect(content.load).toHaveBeenCalledTimes(2);
  });

  it("fails closed immediately and deduplicates concurrent authorization failures", async () => {
    const publicState = createSeedState();
    const adminState = createSeedState();
    adminState.site.draft.heroTitle = "远程私密管理草稿";
    const pendingLogout = deferred<void>();
    const content = makeContentGateway(async (scope) =>
      structuredClone(scope === "admin" ? adminState : publicState),
    );
    const auth = makeAuthGateway({
      logout: vi.fn(() => pendingLogout.promise),
    });
    const user = userEvent.setup();

    render(
      <AppProvider contentGateway={content} authGateway={auth}>
        <ContextProbe />
      </AppProvider>,
    );
    await screen.findByText("scope:public");
    await user.click(screen.getByRole("button", { name: "登录" }));
    await screen.findByText("draft:远程私密管理草稿");

    act(() => {
      content.emitAuthorizationFailure(401);
      content.emitAuthorizationFailure(403);
    });

    expect(screen.getByText("正在准备官网内容…")).toBeVisible();
    expect(
      screen.queryByText("draft:远程私密管理草稿"),
    ).not.toBeInTheDocument();
    expect(auth.logout).toHaveBeenCalledOnce();
    expect(content.load).toHaveBeenCalledTimes(2);

    pendingLogout.resolve();
    expect(await screen.findByText("scope:public")).toBeVisible();
    expect(content.load).toHaveBeenCalledTimes(3);
  });

  it("keeps retries public-only and restores admin access only after a new login", async () => {
    const publicState = createSeedState();
    const adminState = createSeedState();
    adminState.site.draft.heroTitle = "重新认证后的管理草稿";
    let publicLoads = 0;
    const content = makeContentGateway(async (scope) => {
      if (scope === "admin") return structuredClone(adminState);
      publicLoads += 1;
      if (publicLoads === 2) throw new Error("公开内容暂时无法加载");
      return structuredClone(publicState);
    });
    const auth = makeAuthGateway();
    const user = userEvent.setup();

    render(
      <AppProvider contentGateway={content} authGateway={auth}>
        <ContextProbe />
      </AppProvider>,
    );
    await screen.findByText("scope:public");
    await user.click(screen.getByRole("button", { name: "登录" }));
    await screen.findByText("scope:admin");

    act(() => content.emitAuthorizationFailure(403));
    expect(
      await screen.findByText("公开内容暂时无法加载"),
    ).toBeVisible();

    await user.click(screen.getByRole("button", { name: "重新尝试" }));
    expect(await screen.findByText("scope:public")).toBeVisible();
    expect(auth.current).toHaveBeenCalledOnce();
    expect(content.load).toHaveBeenLastCalledWith("public");

    await user.click(screen.getByRole("button", { name: "登录" }));
    expect(await screen.findByText("scope:admin")).toBeVisible();
    expect(
      screen.getByText("draft:重新认证后的管理草稿"),
    ).toBeVisible();

    act(() => content.emitAuthorizationFailure(401));
    expect(await screen.findByText("scope:public")).toBeVisible();
    expect(auth.logout).toHaveBeenCalledTimes(2);
  });

  it("ignores late admin loads and notifications after authorization is lost", async () => {
    const publicState = createSeedState();
    const lateAdminState = createSeedState();
    lateAdminState.site.draft.heroTitle = "迟到的管理快照";
    const pendingAdmin = deferred<WebsiteState>();
    const content = makeContentGateway((scope) =>
      scope === "admin"
        ? pendingAdmin.promise
        : Promise.resolve(structuredClone(publicState)),
    );
    const auth = makeAuthGateway({
      current: vi.fn(async () => remoteSession),
    });

    render(
      <AppProvider contentGateway={content} authGateway={auth}>
        <ContextProbe />
      </AppProvider>,
    );
    await waitFor(() => {
      expect(content.load).toHaveBeenCalledWith("admin");
    });

    act(() => content.emitAuthorizationFailure(401));
    expect(await screen.findByText("scope:public")).toBeVisible();

    await act(async () => {
      pendingAdmin.resolve(lateAdminState);
      content.emitState(lateAdminState, "admin");
      await Promise.resolve();
    });
    expect(screen.getByText("scope:public")).toBeVisible();
    expect(screen.getByText("session:none")).toBeVisible();
    expect(screen.queryByText("draft:迟到的管理快照")).not.toBeInTheDocument();
  });

  it("keeps a failed remote logout public-only across remounts until explicit login succeeds", async () => {
    const { auth, content, pendingLogout, unmount, user } =
      await renderAdminWithPendingLogout();
    await user.click(screen.getByRole("button", { name: "退出" }));
    pendingLogout.reject(new Error("远程会话服务不可用"));

    expect(
      await screen.findByText(
        "管理内容已从当前标签页清除，但远程退出未确认：远程会话服务不可用",
      ),
    ).toBeVisible();

    unmount();
    const remounted = render(
      <AppProvider contentGateway={content} authGateway={auth}>
        <ContextProbe />
      </AppProvider>,
    );
    expect(await screen.findByText("scope:public")).toBeVisible();
    expect(auth.current).toHaveBeenCalledOnce();
    expect(content.load).toHaveBeenLastCalledWith("public");
    expect(
      screen.queryByText("draft:远程私密管理草稿"),
    ).not.toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "登录" }));
    expect(await screen.findByText("scope:admin")).toBeVisible();
    vi.mocked(auth.current).mockResolvedValue(remoteSession);

    remounted.unmount();
    render(
      <AppProvider contentGateway={content} authGateway={auth}>
        <ContextProbe />
      </AppProvider>,
    );
    expect(await screen.findByText("scope:admin")).toBeVisible();
    expect(auth.current).toHaveBeenCalledTimes(2);
  });

  it("keeps the current mount fail-closed when the signed-out marker cannot be stored", async () => {
    const publicState = createSeedState();
    const adminState = createSeedState();
    adminState.site.draft.heroTitle = "不得恢复的管理草稿";
    const content = makeContentGateway(async (scope) =>
      structuredClone(scope === "admin" ? adminState : publicState),
    );
    const auth = makeAuthGateway();
    const user = userEvent.setup();

    render(
      <AppProvider contentGateway={content} authGateway={auth}>
        <ContextProbe />
      </AppProvider>,
    );
    await screen.findByText("scope:public");
    await user.click(screen.getByRole("button", { name: "登录" }));
    await screen.findByText("scope:admin");

    const storageFailure = vi
      .spyOn(sessionStorage, "setItem")
      .mockImplementation(() => {
        throw new Error("session storage unavailable");
      });
    act(() => content.emitAuthorizationFailure(403));
    storageFailure.mockRestore();

    expect(await screen.findByText("scope:public")).toBeVisible();
    act(() => content.emitAuthorizationFailure(401));
    expect(auth.logout).toHaveBeenCalledOnce();
    expect(
      screen.queryByText("draft:不得恢复的管理草稿"),
    ).not.toBeInTheDocument();
  });
});
