import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { createSeedState } from "../../data/seed";
import type { ContentGateway } from "../../gateways/contentGateway";
import { AdminSitePage } from "./AdminSitePage";

const appContext = vi.hoisted(() => ({
  useApp: vi.fn(),
}));

vi.mock("../../app/AppContext", () => ({
  useApp: appContext.useApp,
}));

function deferred<T>() {
  let resolve!: (value: T | PromiseLike<T>) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((next, fail) => {
    resolve = next;
    reject = fail;
  });
  return { promise, resolve, reject };
}

function setup(overrides: Partial<ContentGateway> = {}) {
  const state = createSeedState();
  const content = {
    saveSiteDraft: vi.fn(async () => undefined),
    publishSite: vi.fn(async () => undefined),
    ...overrides,
  } as unknown as ContentGateway;
  appContext.useApp.mockReturnValue({ state, content });
  render(
    <MemoryRouter>
      <AdminSitePage />
    </MemoryRouter>,
  );
  return content;
}

describe("AdminSitePage atomic commands", () => {
  beforeEach(() => {
    appContext.useApp.mockReset();
  });

  it("publishes the current form with one command and blocks duplicate edits", async () => {
    const pendingPublish = deferred<void>();
    const publishSite = vi.fn(() => pendingPublish.promise);
    const user = userEvent.setup();
    const content = setup({ publishSite });

    const title = screen.getByRole("textbox", { name: "主标题" });
    await user.clear(title);
    await user.type(title, "准备原子发布的首页");
    await user.click(screen.getByRole("button", { name: "保存并发布" }));

    await waitFor(() => expect(publishSite).toHaveBeenCalledOnce());
    expect(publishSite).toHaveBeenCalledWith(expect.objectContaining({
      heroTitle: "准备原子发布的首页",
    }));
    expect(content.saveSiteDraft).not.toHaveBeenCalled();
    expect(title).toBeDisabled();
    expect(screen.getByRole("button", { name: "发布中…" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "保存草稿" })).toBeDisabled();

    await user.click(screen.getByRole("button", { name: "发布中…" }));
    expect(publishSite).toHaveBeenCalledOnce();

    pendingPublish.resolve();
    expect(await screen.findByText("首页内容已发布")).toBeVisible();
    expect(title).toBeEnabled();
  });

  it("retains the dirty form when the atomic publish fails", async () => {
    const publishSite = vi.fn(async () => {
      throw new Error("首页发布服务暂时不可用");
    });
    const user = userEvent.setup();
    const content = setup({ publishSite });

    const title = screen.getByRole("textbox", { name: "主标题" });
    await user.clear(title);
    await user.type(title, "失败后仍需保留");
    await user.click(screen.getByRole("button", { name: "保存并发布" }));

    expect(await screen.findByText("首页发布服务暂时不可用")).toBeVisible();
    expect(title).toHaveValue("失败后仍需保留");
    expect(title).toBeEnabled();
    expect(publishSite).toHaveBeenCalledOnce();
    expect(content.saveSiteDraft).not.toHaveBeenCalled();
  });
});
