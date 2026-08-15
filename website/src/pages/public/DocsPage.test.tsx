import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { describe, expect, it, vi } from "vitest";
import { AppProvider } from "../../app/AppContext";
import { createSeedState } from "../../data/seed";
import type { ContentGateway } from "../../gateways/contentGateway";
import type { DocumentationPage } from "../../types";
import { DocsPage } from "./DocsPage";

function makeDoc(
  id: string,
  title: string,
  parentId: string | null,
  order: number,
  markdown = title,
): DocumentationPage {
  const content = {
    title,
    slug: id,
    summary: `${title}摘要`,
    markdown,
    parentId,
    order,
  };
  return {
    id,
    status: "published",
    draft: content,
    published: content,
    updatedAt: "2026-01-01",
    publishedAt: "2026-01-01",
  };
}

function renderPage(docs: DocumentationPage[]) {
  const state = createSeedState();
  state.docs = docs;
  const gateway = {
    mode: "local-demo",
    load: vi.fn(async () => state),
    subscribe: vi.fn(() => () => undefined),
  } as unknown as ContentGateway;

  render(
    <MemoryRouter>
      <AppProvider contentGateway={gateway}>
        <DocsPage />
      </AppProvider>
    </MemoryRouter>,
  );
}

describe("DocsPage", () => {
  it("keeps the initial documentation empty state and disables search", async () => {
    renderPage([]);

    expect(
      await screen.findByRole("heading", { name: "使用文档暂未发布" }),
    ).toBeVisible();
    expect(screen.getByRole("searchbox")).toBeDisabled();
    expect(screen.getByRole("link", { name: "返回首页" })).toHaveAttribute("href", "/");
    expect(
      screen.queryByRole("navigation", { name: "使用文档目录" }),
    ).not.toBeInTheDocument();
  });

  it("renders the published hierarchy as nested semantic lists", async () => {
    renderPage([
      makeDoc("root", "安装指南", null, 1),
      makeDoc("child", "Android 安装", "root", 0),
      makeDoc("orphan", "独立说明", "missing", 0),
    ]);

    const directory = await screen.findByRole("navigation", {
      name: "使用文档目录",
    });
    const rootLink = within(directory).getByRole("link", {
      name: /安装指南/,
    });
    const childLink = within(directory).getByRole("link", {
      name: /Android 安装/,
    });
    const rootList = rootLink.closest("ol");
    const childList = childLink.closest("ol");

    expect(rootList).toHaveClass("docs-tree");
    expect(childList).toHaveClass("docs-tree-children");
    expect(childList?.parentElement).toBe(rootLink.closest("li"));
    expect(
      within(rootList!).getAllByRole("link").map((link) => link.textContent),
    ).toEqual([
      expect.stringContaining("独立说明"),
      expect.stringContaining("安装指南"),
      expect.stringContaining("Android 安装"),
    ]);
  });

  it("renders cyclic and orphaned published documents exactly once", async () => {
    renderPage([
      makeDoc("cycle-a", "循环甲", "cycle-b", 0),
      makeDoc("cycle-b", "循环乙", "cycle-a", 1),
      makeDoc("child", "循环下的子文档", "cycle-a", 2),
      makeDoc("orphan", "孤立文档", "missing", 3),
    ]);

    const directory = await screen.findByRole("navigation", {
      name: "使用文档目录",
    });
    const links = within(directory).getAllByRole("link");

    expect(links).toHaveLength(4);
    ["循环甲", "循环乙", "循环下的子文档", "孤立文档"].forEach((title) => {
      expect(
        within(directory).getAllByRole("link", { name: new RegExp(title) }),
      ).toHaveLength(1);
    });
  });

  it("shows a matching document with its complete effective parent path", async () => {
    const user = userEvent.setup();
    renderPage([
      makeDoc("root", "安装指南", null, 0),
      makeDoc("section", "Android", "root", 0),
      makeDoc("permission", "权限说明", "section", 0, "开启系统授权"),
      makeDoc("other", "常见问题", null, 1),
    ]);

    await user.type(await screen.findByRole("searchbox"), "授权");

    const results = screen.getByRole("navigation", { name: "文档搜索结果" });
    expect(screen.getByText("找到 1 篇文档")).toBeInTheDocument();
    expect(within(results).getByRole("link", { name: /权限说明/ })).toBeVisible();
    expect(within(results).getByText("所在目录：安装指南 / Android")).toBeVisible();
    expect(within(results).queryByRole("link", { name: /常见问题/ })).not.toBeInTheDocument();
  });
});
