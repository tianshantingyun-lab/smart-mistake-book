import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { createSeedState } from "../data/seed";
import { PublicLayout } from "./PublicLayout";

const appContext = vi.hoisted(() => ({
  useApp: vi.fn(),
}));

vi.mock("../app/AppContext", () => ({
  useApp: appContext.useApp,
}));

const track = vi.fn();
const setConsent = vi.fn();

function renderLayout() {
  render(
    <MemoryRouter>
      <Routes>
        <Route element={<PublicLayout />}>
          <Route index element={<h1>首页内容</h1>} />
          <Route path="docs" element={<h1>文档内容</h1>} />
          <Route path="privacy" element={<h1>隐私说明内容</h1>} />
        </Route>
      </Routes>
    </MemoryRouter>,
  );
}

describe("PublicLayout mobile navigation", () => {
  beforeEach(() => {
    vi.stubGlobal("scrollTo", vi.fn());
    track.mockClear();
    setConsent.mockClear();
    const state = createSeedState();
    appContext.useApp.mockReturnValue({
      state,
      consent: "unknown",
      analyticsActive: false,
      setConsent,
      track,
      mediaByRole: vi.fn(() => null),
    });
  });

  it("connects the trigger to the menu and restores focus after Escape", async () => {
    const user = userEvent.setup();
    renderLayout();

    const trigger = screen.getByRole("button", { name: "打开导航" });
    expect(trigger).toHaveAttribute("aria-controls", "public-mobile-navigation");
    expect(trigger).toHaveAttribute("aria-expanded", "false");

    await user.click(trigger);

    const navigation = screen.getByRole("navigation", { name: "移动导航" });
    expect(navigation).toHaveAttribute("id", "public-mobile-navigation");
    expect(trigger).toHaveAttribute("aria-expanded", "true");
    await waitFor(() => {
      expect(within(navigation).getByRole("link", { name: "首页" })).toHaveFocus();
    });

    await user.keyboard("{Escape}");

    expect(screen.queryByRole("navigation", { name: "移动导航" })).not.toBeInTheDocument();
    expect(trigger).toHaveAttribute("aria-expanded", "false");
    expect(trigger).toHaveFocus();
  });

  it("keeps closing the menu after mobile route navigation", async () => {
    const user = userEvent.setup();
    renderLayout();

    await user.click(screen.getByRole("button", { name: "打开导航" }));
    const navigation = screen.getByRole("navigation", { name: "移动导航" });
    await user.click(within(navigation).getByRole("link", { name: "使用文档" }));

    expect(await screen.findByRole("heading", { name: "文档内容" })).toBeVisible();
    expect(screen.queryByRole("navigation", { name: "移动导航" })).not.toBeInTheDocument();
  });

  it("closes the menu when visitors choose the current route", async () => {
    const user = userEvent.setup();
    renderLayout();

    const trigger = screen.getByRole("button", { name: "打开导航" });
    await user.click(trigger);
    const navigation = screen.getByRole("navigation", { name: "移动导航" });
    await user.click(within(navigation).getByRole("link", { name: "首页" }));

    expect(
      screen.queryByRole("navigation", { name: "移动导航" }),
    ).not.toBeInTheDocument();
    expect(trigger).toHaveFocus();
  });

  it("lets visitors read privacy details without changing analytics consent", async () => {
    const state = createSeedState();
    state.site.published.analytics = {
      providerName: "自建分析",
      siteId: "site-123",
    };
    appContext.useApp.mockReturnValue({
      state,
      consent: "unknown",
      analyticsActive: false,
      setConsent,
      track,
      mediaByRole: vi.fn(() => null),
    });
    const user = userEvent.setup();
    renderLayout();

    const privacyLink = await screen.findByRole("link", {
      name: "先阅读隐私说明",
    });
    expect(privacyLink).toHaveFocus();
    await user.click(privacyLink);

    expect(
      await screen.findByRole("heading", { name: "隐私说明内容" }),
    ).toBeVisible();
    expect(
      screen.queryByRole("dialog", { name: "分析与 Cookie" }),
    ).not.toBeInTheDocument();
    expect(setConsent).not.toHaveBeenCalled();
  });

  it("removes footer links for managed pages that are not published", () => {
    const state = createSeedState();
    const contact = state.pages.find((page) => page.slug === "contact");
    if (!contact) throw new Error("seed contact page missing");
    contact.publishedAt = null;
    appContext.useApp.mockReturnValue({
      state,
      consent: "unknown",
      analyticsActive: false,
      setConsent,
      track,
      mediaByRole: vi.fn(() => null),
    });

    renderLayout();

    const footerNavigation = screen.getByRole("navigation", {
      name: "页脚导航",
    });
    expect(
      within(footerNavigation).queryByRole("link", { name: "联系" }),
    ).not.toBeInTheDocument();
    expect(
      within(footerNavigation).getByRole("link", { name: "隐私" }),
    ).toBeVisible();
  });
});
