import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { createSeedState } from "../../data/seed";
import type { ContentGateway } from "../../gateways/contentGateway";
import type { ManagedPage } from "../../types";
import { AdminManagedPagesPage } from "./AdminManagedPagesPage";

const appContext = vi.hoisted(() => ({
  useApp: vi.fn(),
}));

vi.mock("../../app/AppContext", () => ({
  useApp: appContext.useApp,
}));

function page(
  id: string,
  slug: ManagedPage["slug"],
  title: string,
): ManagedPage {
  const content = {
    title,
    summary: `${title}摘要`,
    body: `${title}正文`,
    seoTitle: `${title} SEO`,
    seoDescription: `${title} SEO 描述`,
  };
  return {
    id,
    slug,
    draft: structuredClone(content),
    published: structuredClone(content),
    updatedAt: "2026-07-31T00:00:00.000Z",
    publishedAt: null,
  };
}

function deferred<T>() {
  let resolve!: (value: T | PromiseLike<T>) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((next, fail) => {
    resolve = next;
    reject = fail;
  });
  return { promise, resolve, reject };
}

function setup(
  overrides: Partial<ContentGateway> = {},
  configureState?: (state: ReturnType<typeof createSeedState>) => void,
) {
  const state = createSeedState();
  state.pages = [
    page("about-page", "about", "关于"),
    page("privacy-page", "privacy", "隐私"),
    page("contact-page", "contact", "联系"),
  ];
  configureState?.(state);
  const content = {
    savePageDraft: vi.fn(async () => undefined),
    publishPage: vi.fn(async () => undefined),
    archivePage: vi.fn(async () => undefined),
    ...overrides,
  } as unknown as ContentGateway;
  appContext.useApp.mockReturnValue({ state, content });
  render(
    <MemoryRouter>
      <AdminManagedPagesPage />
    </MemoryRouter>,
  );
  return content;
}

describe("AdminManagedPagesPage edit protection", () => {
  beforeEach(() => {
    appContext.useApp.mockReset();
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("keeps a dirty page selected when switching is cancelled", async () => {
    const confirm = vi.spyOn(window, "confirm").mockReturnValue(false);
    const user = userEvent.setup();
    setup();

    const title = screen.getByRole("textbox", { name: "页面标题" });
    await user.clear(title);
    await user.type(title, "尚未保存的关于页");
    await user.click(screen.getByRole("button", { name: /隐私/ }));

    expect(confirm).toHaveBeenCalledWith(
      "当前页面有未保存的修改。切换页面会放弃这些修改，确定继续吗？",
    );
    expect(title).toHaveValue("尚未保存的关于页");

    confirm.mockReturnValue(true);
    await user.click(screen.getByRole("button", { name: /隐私/ }));
    expect(title).toHaveValue("隐私");
  });

  it("publishes with one command, disables switching, and keeps edits after failure", async () => {
    const pendingPublish = deferred<void>();
    const publishPage = vi.fn(() => pendingPublish.promise);
    const user = userEvent.setup();
    const content = setup({ publishPage });

    const title = screen.getByRole("textbox", { name: "页面标题" });
    await user.clear(title);
    await user.type(title, "待发布的关于页");
    await user.click(screen.getByRole("button", { name: "保存并发布" }));

    await waitFor(() => expect(publishPage).toHaveBeenCalledOnce());
    expect(publishPage).toHaveBeenCalledWith(
      "about-page",
      expect.objectContaining({ title: "待发布的关于页" }),
    );
    expect(content.savePageDraft).not.toHaveBeenCalled();
    expect(title).toBeDisabled();
    expect(screen.getByRole("button", { name: /隐私/ })).toBeDisabled();
    expect(screen.getByRole("button", { name: "发布中…" })).toBeDisabled();

    await user.click(screen.getByRole("button", { name: /隐私/ }));
    expect(title).toHaveValue("待发布的关于页");

    pendingPublish.reject(new Error("页面发布服务暂时不可用"));
    expect(await screen.findByText("页面发布服务暂时不可用")).toBeVisible();
    expect(title).toHaveValue("待发布的关于页");
    expect(title).toBeEnabled();
  });

  it("confirms and archives a published page while retaining its draft", async () => {
    const confirm = vi.spyOn(window, "confirm").mockReturnValue(true);
    const archivePage = vi.fn(async () => undefined);
    const user = userEvent.setup();
    setup(
      { archivePage },
      (state) => {
        state.pages[0].publishedAt = "2026-07-31T00:00:00.000Z";
      },
    );

    const title = screen.getByRole("textbox", { name: "页面标题" });
    await user.clear(title);
    await user.type(title, "仍保留的草稿");
    await user.click(screen.getByRole("button", { name: "下线公开页" }));

    expect(confirm).toHaveBeenCalledWith(
      "下线这个页面？公开入口会立即隐藏，现有草稿仍会保留。",
    );
    await waitFor(() =>
      expect(archivePage).toHaveBeenCalledWith("about-page"),
    );
    expect(title).toHaveValue("仍保留的草稿");
    expect(await screen.findByText("页面已下线，草稿仍保留")).toBeVisible();
  });
});
