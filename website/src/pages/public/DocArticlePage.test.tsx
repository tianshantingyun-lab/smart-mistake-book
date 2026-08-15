import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { describe, expect, it, vi } from "vitest";
import { AppProvider } from "../../app/AppContext";
import { createSeedState } from "../../data/seed";
import type { ContentGateway } from "../../gateways/contentGateway";
import type { DocumentationPage } from "../../types";
import { DocArticlePage } from "./DocArticlePage";

function makeDoc(
  id: string,
  title: string,
  parentId: string | null,
): DocumentationPage {
  const content = {
    title,
    slug: id,
    summary: `${title}摘要`,
    markdown: `${title}正文`,
    parentId,
    order: 0,
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

function renderArticle(docs: DocumentationPage[], slug: string) {
  const state = createSeedState();
  state.docs = docs;
  const gateway = {
    mode: "local-demo",
    load: vi.fn(async () => state),
    subscribe: vi.fn(() => () => undefined),
  } as unknown as ContentGateway;

  render(
    <MemoryRouter initialEntries={[`/docs/${slug}`]}>
      <AppProvider contentGateway={gateway}>
        <Routes>
          <Route path="/docs" element={<p>文档首页目标</p>} />
          <Route path="/docs/:slug" element={<DocArticlePage />} />
        </Routes>
      </AppProvider>
    </MemoryRouter>,
  );
}

describe("DocArticlePage breadcrumbs", () => {
  it("renders every published ancestor and the current article as semantic links", async () => {
    const user = userEvent.setup();
    renderArticle([
      makeDoc("install", "安装指南", null),
      makeDoc("android", "Android", "install"),
      makeDoc("permission", "权限说明", "android"),
    ], "permission");

    const breadcrumbs = await screen.findByRole("navigation", {
      name: "文档面包屑",
    });
    const links = within(breadcrumbs).getAllByRole("link");

    expect(breadcrumbs.querySelector("ol")).toBeInTheDocument();
    expect(links.map((link) => link.textContent)).toEqual([
      "使用文档",
      "安装指南",
      "Android",
      "权限说明",
    ]);
    expect(links.every((link) => link.closest("li") !== null)).toBe(true);
    expect(links.map((link) => link.getAttribute("href"))).toEqual([
      "/docs",
      "/docs/install",
      "/docs/android",
      "/docs/permission",
    ]);
    expect(links[3]).toHaveAttribute("aria-current", "page");
    expect(
      Array.from(
        breadcrumbs.querySelectorAll(".document-breadcrumbs__separator"),
      ).every((separator) => separator.getAttribute("aria-hidden") === "true"),
    ).toBe(true);

    await user.tab();
    expect(links[0]).toHaveFocus();
    await user.tab();
    expect(links[1]).toHaveFocus();
    await user.tab();
    expect(links[2]).toHaveFocus();
    await user.tab();
    expect(links[3]).toHaveFocus();

    await user.tab({ shift: true });
    expect(links[2]).toHaveFocus();
    await user.keyboard("{Enter}");
    expect(
      await screen.findByRole("heading", { name: "Android", level: 1 }),
    ).toBeVisible();
  });

  it.each([
    {
      name: "缺失上级",
      docs: [makeDoc("orphan", "孤立文档", "missing")],
      slug: "orphan",
      expected: ["使用文档", "孤立文档"],
    },
    {
      name: "循环上级",
      docs: [
        makeDoc("cycle-a", "循环甲", "cycle-b"),
        makeDoc("cycle-b", "循环乙", "cycle-a"),
        makeDoc("child", "循环下的子文档", "cycle-a"),
      ],
      slug: "child",
      expected: ["使用文档", "循环甲", "循环下的子文档"],
    },
  ])("keeps $name breadcrumbs finite and navigable", async ({
    docs,
    slug,
    expected,
  }) => {
    renderArticle(docs, slug);

    const breadcrumbs = await screen.findByRole("navigation", {
      name: "文档面包屑",
    });
    const links = within(breadcrumbs).getAllByRole("link");

    expect(links.map((link) => link.textContent)).toEqual(expected);
    expect(new Set(links.map((link) => link.getAttribute("href"))).size).toBe(
      links.length,
    );
    expect(links.at(-1)).toHaveAttribute("aria-current", "page");
  });
});
