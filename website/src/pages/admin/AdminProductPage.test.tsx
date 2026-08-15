import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { createSeedState } from "../../data/seed";
import type { ProductPageContent, WebsiteState } from "../../types";
import { AdminProductPage } from "./AdminProductPage";

const appContext = vi.hoisted(() => ({
  useApp: vi.fn(),
}));
const unsavedChanges = vi.hoisted(() => ({
  useUnsavedChangesGuard: vi.fn(),
}));

vi.mock("../../app/AppContext", () => ({
  useApp: appContext.useApp,
}));
vi.mock("../../components/UnsavedChangesGuard", () => ({
  useUnsavedChangesGuard: unsavedChanges.useUnsavedChangesGuard,
}));

function deferred() {
  let resolve!: () => void;
  let reject!: (reason: unknown) => void;
  const promise = new Promise<void>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise;
    reject = rejectPromise;
  });
  return { promise, reject, resolve };
}

function makePublishable(content: ProductPageContent): ProductPageContent {
  return {
    ...structuredClone(content),
    seoDescription: "产品页搜索说明",
    blocks: [
      {
        id: "hero",
        type: "hero",
        enabled: true,
        heading: "产品标题",
        lead: "产品简介",
        mediaId: null,
      },
      {
        id: "body",
        type: "markdown",
        enabled: true,
        title: "",
        markdown: "产品正文",
      },
    ],
  };
}

describe("AdminProductPage operation boundaries", () => {
  const archiveProduct = vi.fn<() => Promise<void>>();
  const publishProduct = vi.fn<() => Promise<void>>();
  const saveProductDraft = vi.fn<(draft: ProductPageContent) => Promise<void>>();
  let state: WebsiteState;

  beforeEach(() => {
    sessionStorage.clear();
    state = createSeedState();
    state.product.status = "published";
    state.product.published = structuredClone(state.product.draft);
    appContext.useApp.mockImplementation(() => ({
      state,
      content: { archiveProduct, publishProduct, saveProductDraft },
    }));
    archiveProduct.mockReset();
    publishProduct.mockReset();
    saveProductDraft.mockReset();
    unsavedChanges.useUnsavedChangesGuard.mockReset();
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("keeps the published page when archiving with unsaved changes is cancelled", async () => {
    const confirm = vi.spyOn(window, "confirm").mockReturnValue(false);
    const user = userEvent.setup();
    render(<AdminProductPage />);

    await user.clear(screen.getByRole("textbox", { name: "导航名称" }));
    await user.type(screen.getByRole("textbox", { name: "导航名称" }), "新导航名称");
    await user.click(screen.getByRole("button", { name: "归档" }));

    expect(confirm).toHaveBeenCalledWith(
      "当前产品页有未保存的修改。归档会放弃这些修改，并立即隐藏公开入口。确定继续归档吗？",
    );
    expect(archiveProduct).not.toHaveBeenCalled();
  });

  it("archives after the ordinary confirmation when there are no unsaved changes", async () => {
    const confirm = vi.spyOn(window, "confirm").mockReturnValue(true);
    archiveProduct.mockResolvedValue();
    const user = userEvent.setup();
    render(<AdminProductPage />);

    await user.click(screen.getByRole("button", { name: "归档" }));

    expect(confirm).toHaveBeenCalledWith(
      "归档产品讲解页？公开入口会立即隐藏。",
    );
    expect(archiveProduct).toHaveBeenCalledOnce();
    expect(await screen.findByRole("status")).toHaveTextContent(
      "产品页已归档，公开入口已隐藏",
    );
  });

  it("shows the archive failure without leaving an unhandled rejection", async () => {
    vi.spyOn(window, "confirm").mockReturnValue(true);
    archiveProduct.mockRejectedValue(new Error("内容服务暂时不可用"));
    const user = userEvent.setup();
    render(<AdminProductPage />);

    await user.click(screen.getByRole("button", { name: "归档" }));

    expect(archiveProduct).toHaveBeenCalledOnce();
    expect(await screen.findByRole("status")).toHaveTextContent(
      "内容服务暂时不可用",
    );
    expect(screen.getByRole("button", { name: "归档" })).toBeEnabled();
  });

  it("disables every mutable surface and ignores repeated saves while a save is pending", async () => {
    const save = deferred();
    saveProductDraft.mockReturnValue(save.promise);
    const user = userEvent.setup();
    render(<AdminProductPage />);

    const navLabel = screen.getByRole("textbox", { name: "导航名称" });
    await user.clear(navLabel);
    await user.type(navLabel, "待保存导航");
    await user.click(screen.getByRole("button", { name: "保存草稿" }));

    expect(await screen.findByText("正在保存产品页草稿，请勿离开当前页面。")).toBeVisible();
    expect(navLabel).toBeDisabled();
    expect(screen.getByRole("button", { name: "添加区块" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "整页预览" })).toBeDisabled();
    const pendingSave = screen.getByRole("button", { name: "保存中…" });
    expect(pendingSave).toBeDisabled();

    await user.click(pendingSave);
    expect(saveProductDraft).toHaveBeenCalledOnce();

    save.resolve();
    expect(await screen.findByText("产品页草稿已保存")).toBeVisible();
    expect(navLabel).toBeEnabled();
  });

  it("does not let a remote broadcast overwrite a dirty local draft", async () => {
    const user = userEvent.setup();
    const view = render(<AdminProductPage />);
    const navLabel = screen.getByRole("textbox", { name: "导航名称" });

    await user.clear(navLabel);
    await user.type(navLabel, "本地未保存导航");
    state = structuredClone(state);
    state.product.draft.navLabel = "远端导航";
    state.product.updatedAt = "2026-07-31T09:30:00.000Z";
    view.rerender(<AdminProductPage />);

    expect(navLabel).toHaveValue("本地未保存导航");
    expect(unsavedChanges.useUnsavedChangesGuard).toHaveBeenLastCalledWith({
      dirty: true,
      message: "当前产品页有未保存的修改或正在提交。确定离开产品页管理吗？",
      pending: false,
    });
  });

  it("advances the baseline and clears the leave guard after a successful save", async () => {
    saveProductDraft.mockResolvedValue();
    const user = userEvent.setup();
    render(<AdminProductPage />);

    const navLabel = screen.getByRole("textbox", { name: "导航名称" });
    await user.clear(navLabel);
    await user.type(navLabel, "已保存导航");
    await user.click(screen.getByRole("button", { name: "保存草稿" }));

    expect(await screen.findByText("产品页草稿已保存")).toBeVisible();
    expect(saveProductDraft).toHaveBeenCalledWith(
      expect.objectContaining({ navLabel: "已保存导航" }),
    );
    await waitFor(() => {
      expect(unsavedChanges.useUnsavedChangesGuard).toHaveBeenLastCalledWith({
        dirty: false,
        message: "当前产品页有未保存的修改或正在提交。确定离开产品页管理吗？",
        pending: false,
      });
    });
    expect(navLabel).toHaveValue("已保存导航");
  });

  it("keeps one pending boundary from draft save through publication", async () => {
    state.product.draft = makePublishable(state.product.draft);
    const save = deferred();
    saveProductDraft.mockReturnValue(save.promise);
    publishProduct.mockResolvedValue();
    const user = userEvent.setup();
    render(<AdminProductPage />);

    await user.click(screen.getByRole("button", { name: "保存并发布" }));

    expect(await screen.findByText("正在保存并发布产品页，请勿离开当前页面。")).toBeVisible();
    expect(saveProductDraft).toHaveBeenCalledOnce();
    expect(publishProduct).not.toHaveBeenCalled();
    expect(screen.getByRole("textbox", { name: "导航名称" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "保存并发布中…" })).toBeDisabled();

    save.resolve();
    expect(await screen.findByText("产品讲解页已整页发布")).toBeVisible();
    expect(publishProduct).toHaveBeenCalledOnce();
    expect(saveProductDraft.mock.invocationCallOrder[0]).toBeLessThan(
      publishProduct.mock.invocationCallOrder[0],
    );
  });

  it("does not publish or discard the local draft when saving before publish fails", async () => {
    state.product.draft = makePublishable(state.product.draft);
    saveProductDraft.mockRejectedValue(new Error("草稿保存失败"));
    const user = userEvent.setup();
    render(<AdminProductPage />);

    const navLabel = screen.getByRole("textbox", { name: "导航名称" });
    await user.clear(navLabel);
    await user.type(navLabel, "失败后仍保留");
    await user.click(screen.getByRole("button", { name: "保存并发布" }));

    expect(await screen.findByText("草稿保存失败")).toBeVisible();
    expect(publishProduct).not.toHaveBeenCalled();
    expect(navLabel).toHaveValue("失败后仍保留");
    expect(unsavedChanges.useUnsavedChangesGuard).toHaveBeenLastCalledWith(
      expect.objectContaining({ dirty: true, pending: false }),
    );
  });

  it("opens the captured draft without disabling session storage inheritance", async () => {
    const previewWindow = { opener: window };
    let capturedPreview: string | null = null;
    const open = vi.spyOn(window, "open").mockImplementation(() => {
      capturedPreview = sessionStorage.getItem("smart-mistake-book.website.product-preview");
      return previewWindow as unknown as Window;
    });
    const user = userEvent.setup();
    render(<AdminProductPage />);

    await user.clear(screen.getByRole("textbox", { name: "导航名称" }));
    await user.type(screen.getByRole("textbox", { name: "导航名称" }), "待预览导航");
    await user.click(screen.getByRole("button", { name: "整页预览" }));

    expect(open.mock.calls[0]).toEqual(["/admin/product/preview", "_blank"]);
    expect(previewWindow.opener).toBeNull();
    expect(capturedPreview).toContain("待预览导航");
    expect(sessionStorage.getItem("smart-mistake-book.website.product-preview")).toBeNull();
    expect(screen.queryByText("浏览器阻止了预览窗口，请允许打开新标签页")).not.toBeInTheDocument();
  });

  it("captures each consecutive preview without retaining stale parent data", async () => {
    const capturedPreviews: string[] = [];
    vi.spyOn(window, "open").mockImplementation(() => {
      capturedPreviews.push(
        sessionStorage.getItem("smart-mistake-book.website.product-preview") ?? "",
      );
      return { opener: window } as unknown as Window;
    });
    const user = userEvent.setup();
    render(<AdminProductPage />);
    const navLabel = screen.getByRole("textbox", { name: "导航名称" });

    await user.clear(navLabel);
    await user.type(navLabel, "第一次预览");
    await user.click(screen.getByRole("button", { name: "整页预览" }));
    await user.clear(navLabel);
    await user.type(navLabel, "第二次预览");
    await user.click(screen.getByRole("button", { name: "整页预览" }));

    expect(capturedPreviews.map((value) => JSON.parse(value).navLabel)).toEqual([
      "第一次预览",
      "第二次预览",
    ]);
    expect(sessionStorage.getItem("smart-mistake-book.website.product-preview")).toBeNull();
  });

  it("reports a blocked preview only when window.open returns null", async () => {
    vi.spyOn(window, "open").mockReturnValue(null);
    const user = userEvent.setup();
    render(<AdminProductPage />);

    await user.click(screen.getByRole("button", { name: "整页预览" }));

    expect(await screen.findByRole("status")).toHaveTextContent(
      "浏览器阻止了预览窗口，请允许打开新标签页",
    );
    expect(sessionStorage.getItem("smart-mistake-book.website.product-preview")).toBeNull();
  });

  it("does not open a preview when its session storage capture fails", async () => {
    sessionStorage.setItem("smart-mistake-book.website.product-preview", "stale");
    vi.spyOn(sessionStorage, "setItem").mockImplementation(() => {
      throw new DOMException("Blocked", "SecurityError");
    });
    const open = vi.spyOn(window, "open");
    const user = userEvent.setup();
    render(<AdminProductPage />);

    await user.click(screen.getByRole("button", { name: "整页预览" }));

    expect(open).not.toHaveBeenCalled();
    expect(await screen.findByRole("status")).toHaveTextContent(
      "浏览器无法保存本次预览内容，请检查隐私或存储设置后重试",
    );
    expect(sessionStorage.getItem("smart-mistake-book.website.product-preview")).toBeNull();
  });

  it("cleans the capture and reports when opening the preview throws", async () => {
    vi.spyOn(window, "open").mockImplementation(() => {
      throw new DOMException("Blocked", "SecurityError");
    });
    const user = userEvent.setup();
    render(<AdminProductPage />);

    await user.click(screen.getByRole("button", { name: "整页预览" }));

    expect(await screen.findByRole("status")).toHaveTextContent(
      "浏览器无法打开预览窗口，请检查弹窗设置后重试",
    );
    expect(sessionStorage.getItem("smart-mistake-book.website.product-preview")).toBeNull();
  });

  it("closes the preview and clears its capture when disconnecting opener fails", async () => {
    const close = vi.fn();
    const previewWindow = { close };
    Object.defineProperty(previewWindow, "opener", {
      set() {
        throw new DOMException("Blocked", "SecurityError");
      },
    });
    vi.spyOn(window, "open").mockReturnValue(previewWindow as unknown as Window);
    const user = userEvent.setup();
    render(<AdminProductPage />);

    await user.click(screen.getByRole("button", { name: "整页预览" }));

    expect(close).toHaveBeenCalledOnce();
    expect(await screen.findByRole("status")).toHaveTextContent(
      "浏览器无法安全打开预览窗口，请重试或检查弹窗设置",
    );
    expect(sessionStorage.getItem("smart-mistake-book.website.product-preview")).toBeNull();
  });
});
