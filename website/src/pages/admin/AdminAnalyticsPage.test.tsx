import { act, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { AppProvider } from "../../app/AppContext";
import { createSeedState } from "../../data/seed";
import type { AuthGateway } from "../../gateways/authGateway";
import type { ContentGateway } from "../../gateways/contentGateway";
import type { AdminSession, WebsiteState } from "../../types";
import { AdminAnalyticsPage } from "./AdminAnalyticsPage";

const adminSession: AdminSession = {
  mode: "authenticated",
  displayName: "测试管理员",
  startedAt: "2026-07-31T00:00:00.000Z",
};

function deferred<T>() {
  let resolve!: (value: T | PromiseLike<T>) => void;
  const promise = new Promise<T>((next) => {
    resolve = next;
  });
  return { promise, resolve };
}

function analyticsRuntime(options: {
  saveAnalyticsDraft?: ContentGateway["saveAnalyticsDraft"];
} = {}) {
  const initial = createSeedState();
  let listener:
    | ((state: WebsiteState, scope: "admin" | "public") => void)
    | undefined;
  const content = {
    mode: "remote",
    load: vi.fn(async () => structuredClone(initial)),
    subscribe: vi.fn((next) => {
      listener = next;
      return () => undefined;
    }),
    saveAnalyticsDraft: options.saveAnalyticsDraft ?? vi.fn(async () => undefined),
    publishAnalytics: vi.fn(async () => undefined),
  } as unknown as ContentGateway;
  const auth: AuthGateway = {
    mode: "remote",
    current: vi.fn(async () => adminSession),
    loginDemo: vi.fn(async () => adminSession),
    logout: vi.fn(async () => undefined),
  };
  return {
    auth,
    content,
    emit(state: WebsiteState) {
      act(() => listener?.(structuredClone(state), "admin"));
    },
    initial,
  };
}

describe("AdminAnalyticsPage", () => {
  it("does not submit an incomplete analytics configuration", async () => {
    const saveAnalyticsDraft = vi.fn(async () => undefined);
    const publishAnalytics = vi.fn(async () => undefined);
    const saveSiteDraft = vi.fn(async () => undefined);
    const publishSite = vi.fn(async () => undefined);
    const content = {
      mode: "remote",
      load: vi.fn(async () => createSeedState()),
      subscribe: vi.fn(() => () => undefined),
      saveAnalyticsDraft,
      publishAnalytics,
      saveSiteDraft,
      publishSite,
    } as unknown as ContentGateway;
    const auth: AuthGateway = {
      mode: "remote",
      current: vi.fn(async () => adminSession),
      loginDemo: vi.fn(async () => adminSession),
      logout: vi.fn(async () => undefined),
    };
    const user = userEvent.setup();

    render(
      <AppProvider contentGateway={content} authGateway={auth}>
        <AdminAnalyticsPage />
      </AppProvider>,
    );

    await user.type(
      await screen.findByLabelText("统计平台名称"),
      "自建分析",
    );
    await user.click(
      screen.getByRole("button", { name: "保存并发布" }),
    );

    expect(
      await screen.findByText("统计平台名称与站点 ID 必须同时填写或同时清空"),
    ).toBeVisible();
    expect(saveAnalyticsDraft).not.toHaveBeenCalled();
    expect(publishAnalytics).not.toHaveBeenCalled();
    expect(saveSiteDraft).not.toHaveBeenCalled();
    expect(publishSite).not.toHaveBeenCalled();
  });

  it("does not replace a dirty analytics draft with an unrelated site broadcast", async () => {
    const runtime = analyticsRuntime();
    const user = userEvent.setup();
    render(
      <AppProvider contentGateway={runtime.content} authGateway={runtime.auth}>
        <AdminAnalyticsPage />
      </AppProvider>,
    );

    const provider = await screen.findByLabelText("统计平台名称");
    await user.type(provider, "本地分析");
    const external = structuredClone(runtime.initial);
    external.site.draft.analytics = {
      providerName: "远端分析",
      siteId: "remote-id",
    };
    external.site.updatedAt = "2026-07-31T01:00:00.000Z";
    runtime.emit(external);

    expect(provider).toHaveValue("本地分析");
    expect(screen.getByLabelText("站点 ID")).toHaveValue("");
  });

  it("locks the form and prevents duplicate submissions while saving", async () => {
    const pendingSave = deferred<void>();
    const saveAnalyticsDraft = vi.fn(() => pendingSave.promise);
    const runtime = analyticsRuntime({ saveAnalyticsDraft });
    const user = userEvent.setup();
    render(
      <AppProvider contentGateway={runtime.content} authGateway={runtime.auth}>
        <AdminAnalyticsPage />
      </AppProvider>,
    );

    const provider = await screen.findByLabelText("统计平台名称");
    const siteId = screen.getByLabelText("站点 ID");
    await user.type(provider, "自建分析");
    await user.type(siteId, "site-1");
    const save = screen.getByRole("button", { name: "保存草稿" });
    await user.click(save);

    expect(provider).toBeDisabled();
    expect(siteId).toBeDisabled();
    expect(screen.getByRole("button", { name: "保存中…" })).toBeDisabled();
    await user.click(screen.getByRole("button", { name: "保存中…" }));
    expect(saveAnalyticsDraft).toHaveBeenCalledOnce();

    pendingSave.resolve();
    await waitFor(() => expect(provider).toBeEnabled());
    expect(await screen.findByText("分析设置草稿已保存")).toBeVisible();
  });
});
