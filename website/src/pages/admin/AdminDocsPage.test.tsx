import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { createSeedState } from "../../data/seed";
import type { ContentGateway } from "../../gateways/contentGateway";
import type { DocumentationPage, PublicationState } from "../../types";
import { AdminDocsPage } from "./AdminDocsPage";

const appContext = vi.hoisted(() => ({
  useApp: vi.fn(),
}));

vi.mock("../../app/AppContext", () => ({
  useApp: appContext.useApp,
}));

function documentation(
  id: string,
  title: string,
  order: number,
  status: PublicationState = "draft",
): DocumentationPage {
  const content = {
    title,
    slug: id,
    summary: `${title}摘要`,
    markdown: `${title}正文`,
    parentId: null,
    order,
  };
  return {
    id,
    status,
    draft: structuredClone(content),
    published: structuredClone(content),
    updatedAt: "2026-07-31T00:00:00.000Z",
    publishedAt: status === "published" ? "2026-07-31T00:00:00.000Z" : null,
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
  docs: DocumentationPage[],
  overrides: Partial<ContentGateway> = {},
) {
  const state = createSeedState();
  state.docs = docs;
  const content = {
    saveDocDraft: vi.fn(async () => undefined),
    publishDoc: vi.fn(async () => undefined),
    archiveDoc: vi.fn(async () => undefined),
    ...overrides,
  } as unknown as ContentGateway;
  appContext.useApp.mockReturnValue({ state, content });
  render(
    <MemoryRouter>
      <AdminDocsPage />
    </MemoryRouter>,
  );
  return content;
}

describe("AdminDocsPage unsaved-change protection", () => {
  beforeEach(() => {
    appContext.useApp.mockReset();
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("keeps the current draft when a document switch is cancelled", async () => {
    const confirm = vi.spyOn(window, "confirm").mockReturnValue(false);
    const user = userEvent.setup();
    setup([
      documentation("doc-a", "文档甲", 0),
      documentation("doc-b", "文档乙", 1),
    ]);

    const title = screen.getByRole("textbox", { name: "文档标题" });
    await user.clear(title);
    await user.type(title, "尚未保存的文档甲");
    await user.click(screen.getByRole("button", { name: /文档乙/ }));

    expect(confirm).toHaveBeenCalledWith(
      "当前文档有未保存的修改。切换文档会放弃这些修改，确定继续吗？",
    );
    expect(title).toHaveValue("尚未保存的文档甲");

    confirm.mockReturnValue(true);
    await user.click(screen.getByRole("button", { name: /文档乙/ }));

    expect(title).toHaveValue("文档乙");
  });

  it("does not replace a dirty draft with a new document until confirmed", async () => {
    const confirm = vi.spyOn(window, "confirm").mockReturnValue(false);
    const user = userEvent.setup();
    setup([documentation("doc-a", "文档甲", 0)]);

    const title = screen.getByRole("textbox", { name: "文档标题" });
    await user.clear(title);
    await user.type(title, "尚未保存的新标题");
    await user.click(screen.getByRole("button", { name: "新建文档" }));

    expect(confirm).toHaveBeenCalledWith(
      "当前文档有未保存的修改。新建文档会放弃这些修改，确定继续吗？",
    );
    expect(title).toHaveValue("尚未保存的新标题");

    confirm.mockReturnValue(true);
    await user.click(screen.getByRole("button", { name: "新建文档" }));

    expect(title).toHaveValue("");
  });

  it("retains dirty fields while archiving is pending and after a failure", async () => {
    const pendingArchive = deferred<void>();
    const archiveDoc = vi.fn(() => pendingArchive.promise);
    const confirm = vi.spyOn(window, "confirm").mockReturnValue(true);
    const user = userEvent.setup();
    setup([
      documentation("doc-a", "文档甲", 0, "published"),
      documentation("doc-b", "文档乙", 1),
    ], { archiveDoc });

    const title = screen.getByRole("textbox", { name: "文档标题" });
    await user.clear(title);
    await user.type(title, "归档前尚未保存");
    await user.click(screen.getByRole("button", { name: "归档" }));

    expect(confirm).toHaveBeenCalledWith(
      "当前文档有未保存的修改。归档会放弃这些修改，并立即隐藏公开入口。确定继续归档吗？",
    );
    expect(archiveDoc).toHaveBeenCalledWith("doc-a");
    expect(title).toHaveValue("归档前尚未保存");
    expect(title).toBeDisabled();
    expect(screen.getByRole("button", { name: "新建文档" })).toBeDisabled();
    expect(screen.getByRole("button", { name: /文档乙/ })).toBeDisabled();

    pendingArchive.reject(new Error("归档服务暂时不可用"));

    expect(await screen.findByText("归档服务暂时不可用")).toBeVisible();
    expect(title).toHaveValue("归档前尚未保存");
    expect(title).toBeEnabled();
  });

  it("always confirms a clean archive before changing public visibility", async () => {
    const archiveDoc = vi.fn(async () => undefined);
    const confirm = vi.spyOn(window, "confirm").mockReturnValue(false);
    const user = userEvent.setup();
    setup([documentation("doc-a", "文档甲", 0, "published")], { archiveDoc });

    await user.click(screen.getByRole("button", { name: "归档" }));

    expect(confirm).toHaveBeenCalledWith(
      "归档这篇文档？公开页面和搜索结果会立即隐藏。",
    );
    expect(archiveDoc).not.toHaveBeenCalled();

    confirm.mockReturnValue(true);
    await user.click(screen.getByRole("button", { name: "归档" }));

    expect(archiveDoc).toHaveBeenCalledWith("doc-a");
    expect(await screen.findByText("文档已归档，公开入口已隐藏")).toBeVisible();
  });

  it("blocks editor navigation while saving and stops prompting after save", async () => {
    const pendingSave = deferred<void>();
    const saveDocDraft = vi.fn(() => pendingSave.promise);
    const confirm = vi.spyOn(window, "confirm").mockReturnValue(true);
    const user = userEvent.setup();
    setup([
      documentation("doc-a", "文档甲", 0, "published"),
      documentation("doc-b", "文档乙", 1),
    ], { saveDocDraft });

    const title = screen.getByRole("textbox", { name: "文档标题" });
    await user.clear(title);
    await user.type(title, "已准备保存的标题");
    await user.click(screen.getByRole("button", { name: "保存草稿" }));

    await waitFor(() => expect(saveDocDraft).toHaveBeenCalledOnce());
    expect(title).toBeDisabled();
    expect(screen.getByRole("button", { name: "新建文档" })).toBeDisabled();
    expect(screen.getByRole("button", { name: /文档乙/ })).toBeDisabled();
    expect(screen.getByRole("button", { name: "归档" })).toBeDisabled();

    await user.click(screen.getByRole("button", { name: /文档乙/ }));
    await user.click(screen.getByRole("button", { name: "新建文档" }));
    await user.click(screen.getByRole("button", { name: "归档" }));
    expect(confirm).not.toHaveBeenCalled();

    pendingSave.resolve();

    expect(await screen.findByText("文档草稿已保存")).toBeVisible();
    expect(title).toBeEnabled();
    await user.click(screen.getByRole("button", { name: /文档乙/ }));

    expect(confirm).not.toHaveBeenCalled();
    expect(title).toHaveValue("文档乙");
  });

  it("publishes the current document with one gateway command", async () => {
    const publishDoc = vi.fn(async () => undefined);
    const user = userEvent.setup();
    const content = setup([documentation("doc-a", "文档甲", 0)], { publishDoc });

    const title = screen.getByRole("textbox", { name: "文档标题" });
    await user.clear(title);
    await user.type(title, "原子发布文档");
    await user.click(screen.getByRole("button", { name: "保存并发布" }));

    expect(await screen.findByText("文档已发布并进入公开搜索")).toBeVisible();
    expect(publishDoc).toHaveBeenCalledOnce();
    expect(publishDoc).toHaveBeenCalledWith(
      "doc-a",
      expect.objectContaining({
        title: "原子发布文档",
        slug: "doc-a",
      }),
    );
    expect(content.saveDocDraft).not.toHaveBeenCalled();
  });

  it("retains a dirty document when atomic publish fails", async () => {
    const publishDoc = vi.fn(async () => {
      throw new Error("文档发布服务暂时不可用");
    });
    const confirm = vi.spyOn(window, "confirm").mockReturnValue(false);
    const user = userEvent.setup();
    const content = setup([
      documentation("doc-a", "文档甲", 0),
      documentation("doc-b", "文档乙", 1),
    ], { publishDoc });

    const title = screen.getByRole("textbox", { name: "文档标题" });
    await user.clear(title);
    await user.type(title, "发布失败也要保留");
    await user.click(screen.getByRole("button", { name: "保存并发布" }));

    expect(await screen.findByText("文档发布服务暂时不可用")).toBeVisible();
    expect(title).toHaveValue("发布失败也要保留");
    expect(content.saveDocDraft).not.toHaveBeenCalled();

    await user.click(screen.getByRole("button", { name: /文档乙/ }));
    expect(confirm).toHaveBeenCalledWith(
      "当前文档有未保存的修改。切换文档会放弃这些修改，确定继续吗？",
    );
    expect(title).toHaveValue("发布失败也要保留");
  });

  it("rejects fractional order explicitly and exposes an integer input step", async () => {
    const user = userEvent.setup();
    const content = setup([documentation("doc-a", "文档甲", 0)]);
    const order = screen.getByRole("spinbutton", { name: "排序" });

    expect(order).toHaveAttribute("step", "1");
    await user.clear(order);
    await user.type(order, "1.5");
    await user.click(screen.getByRole("button", { name: "保存草稿" }));

    expect(
      await screen.findByText("文档排序必须是大于或等于 0 的整数"),
    ).toBeVisible();
    expect(content.saveDocDraft).not.toHaveBeenCalled();
  });

  it("labels every possible parent by status and allows draft hierarchy planning", async () => {
    const user = userEvent.setup();
    const content = setup([
      documentation("current", "当前文档", 0),
      documentation("published-parent", "已发布上级", 1, "published"),
      documentation("draft-parent", "草稿上级", 2),
      documentation("archived-parent", "归档上级", 3, "archived"),
    ]);

    const parent = screen.getByRole("combobox", { name: /^上级文档/ });
    const options = within(parent).getAllByRole("option");

    expect(options.map((option) => option.textContent)).toEqual([
      "无上级（顶层）",
      "已发布上级（已发布）",
      "草稿上级（草稿）",
      "归档上级（已归档）",
    ]);
    expect(
      within(parent).queryByRole("option", { name: /当前文档/ }),
    ).not.toBeInTheDocument();
    options.forEach((option) => expect(option).toBeEnabled());
    expect(parent).toHaveAccessibleDescription(
      "可以先选择草稿或已归档文档编排层级；发布当前文档前，上级文档必须先发布。",
    );

    await user.selectOptions(parent, "draft-parent");
    await user.click(screen.getByRole("button", { name: "保存草稿" }));

    expect(await screen.findByText("文档草稿已保存")).toBeVisible();
    expect(content.saveDocDraft).toHaveBeenCalledWith(
      "current",
      expect.objectContaining({ parentId: "draft-parent" }),
    );
  });
});
