import { StrictMode } from "react";
import { render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { createSeedState } from "../../data/seed";
import { writeProductPreview } from "../../productPreview";
import type { ProductPageContent } from "../../types";
import { AdminProductPreviewFramePage } from "./AdminProductPreviewFramePage";

const appContext = vi.hoisted(() => ({
  useApp: vi.fn(),
}));

vi.mock("../../app/AppContext", () => ({
  useApp: appContext.useApp,
}));

function productContent(heading: string): ProductPageContent {
  return {
    navLabel: "产品功能",
    seoTitle: "产品功能",
    seoDescription: "产品功能介绍",
    showHomepageEntry: false,
    homepageEntryLabel: "了解产品",
    blocks: [
      {
        id: crypto.randomUUID(),
        enabled: true,
        type: "hero",
        heading,
        lead: "预览内容",
        mediaId: null,
      },
    ],
  };
}

describe("AdminProductPreviewFramePage", () => {
  beforeEach(() => {
    sessionStorage.clear();
    const state = createSeedState();
    state.product.draft = productContent("已保存草稿");
    appContext.useApp.mockReturnValue({
      session: { token: "demo", expiresAt: "2099-01-01" },
      contentScope: "admin",
      state,
    });
  });

  it("keeps the captured preview stable through StrictMode rendering", () => {
    writeProductPreview(productContent("当前未保存草稿"));

    render(
      <StrictMode>
        <MemoryRouter>
          <AdminProductPreviewFramePage />
        </MemoryRouter>
      </StrictMode>,
    );

    expect(screen.getByRole("heading", { name: "当前未保存草稿" })).toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: "已保存草稿" })).not.toBeInTheDocument();
    expect(sessionStorage.getItem("smart-mistake-book.website.product-preview")).toBeNull();
  });
});
