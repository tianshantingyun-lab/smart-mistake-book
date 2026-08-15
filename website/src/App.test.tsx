import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { BrowserRouter } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { App } from "./App";
import { AppProvider } from "./app/AppContext";
import { createSeedState } from "./data/seed";
import type { ContentGateway } from "./gateways/contentGateway";

describe("website flows", () => {
  beforeEach(() => {
    localStorage.clear();
    sessionStorage.clear();
    window.history.pushState({}, "", "/");
    vi.stubGlobal("scrollTo", vi.fn());
  });

  it("shows a useful release empty state without a dead download", async () => {
    window.history.pushState({}, "", "/download");
    render(<BrowserRouter><App /></BrowserRouter>);
    expect(await screen.findByRole("heading", { name: "正式版暂未发布" })).toBeVisible();
    expect(screen.queryByRole("link", { name: "下载安装包" })).not.toBeInTheDocument();
  });

  it("keeps unfinished product explanations and screenshots off the public site", async () => {
    render(<BrowserRouter><App /></BrowserRouter>);
    expect(await screen.findByRole("heading", { name: /把每次错误/ })).toBeVisible();
    expect(screen.queryByText("从保存错题，到真正学会")).not.toBeInTheDocument();
    expect(screen.queryByText("错题变知识")).not.toBeInTheDocument();
    expect(screen.queryByRole("img", { name: "智能错题本分层讲题界面" })).not.toBeInTheDocument();
    expect(screen.queryByRole("link", { name: "产品功能" })).not.toBeInTheDocument();
  });

  it("treats the unpublished product route as not found", async () => {
    window.history.pushState({}, "", "/product");
    render(<BrowserRouter><App /></BrowserRouter>);
    expect(await screen.findByRole("heading", { name: "页面未找到" })).toBeVisible();
  });

  it("marks an unavailable documentation article as noindex", async () => {
    window.history.pushState({}, "", "/docs/not-published");
    render(<BrowserRouter><App /></BrowserRouter>);
    expect(
      await screen.findByRole("heading", { name: "这篇文档尚未发布" }),
    ).toBeVisible();
    expect(document.querySelector('meta[name="robots"]')).toHaveAttribute(
      "content",
      "noindex,nofollow",
    );
  });

  it.each([
    ["/about", "关于智能错题本"],
    ["/privacy", "隐私说明"],
    ["/contact", "联系"],
  ])("renders the correct managed page at %s", async (path, heading) => {
    window.history.pushState({}, "", path);
    render(<BrowserRouter><App /></BrowserRouter>);
    expect(await screen.findByRole("heading", { name: heading })).toBeVisible();
  });

  it("moves focus to public content after client-side navigation", async () => {
    const user = userEvent.setup();
    render(<BrowserRouter><App /></BrowserRouter>);
    await screen.findByRole("heading", { name: /把每次错误/ });

    await user.click(screen.getByRole("link", { name: "隐私" }));
    expect(await screen.findByRole("heading", { name: "隐私说明" })).toBeVisible();
    await waitFor(() =>
      expect(screen.getByRole("main", { name: "主要内容" })).toHaveFocus(),
    );
  });

  it("moves focus into the cookie dialog and restores it on Escape", async () => {
    const user = userEvent.setup();
    render(<BrowserRouter><App /></BrowserRouter>);
    await screen.findByRole("heading", { name: /把每次错误/ });
    const settings = screen.getByRole("button", { name: "Cookie 设置" });

    await user.click(settings);
    expect(screen.getByRole("dialog", { name: "分析与 Cookie" })).toBeVisible();
    expect(screen.getByRole("link", { name: "先阅读隐私说明" })).toHaveFocus();

    await user.keyboard("{Escape}");
    await waitFor(() =>
      expect(screen.queryByRole("dialog", { name: "分析与 Cookie" })).not.toBeInTheDocument(),
    );
    expect(settings).toHaveFocus();
  });

  it("protects the product preview route with the demo session", async () => {
    window.history.pushState({}, "", "/admin/product/preview");
    render(<BrowserRouter><App /></BrowserRouter>);
    expect(await screen.findByRole("heading", { name: "管理员登录" })).toBeVisible();
  });

  it("completes the explicit demo-login flow", async () => {
    const user = userEvent.setup();
    window.history.pushState({}, "", "/admin/login");
    render(<BrowserRouter><App /></BrowserRouter>);
    await user.click(await screen.findByRole("button", { name: /进入演示后台/ }));
    expect(await screen.findByRole("heading", { name: "官网内容状态" })).toBeVisible();
    expect(screen.getByText(/演示模式/)).toBeVisible();
  });

  it("shows a recoverable error when content bootstrap fails", async () => {
    const user = userEvent.setup();
    const load = vi
      .fn<ContentGateway["load"]>()
      .mockRejectedValueOnce(new Error("本地存储不可用"))
      .mockResolvedValueOnce(createSeedState());
    const gateway = {
      mode: "local-demo",
      load,
      subscribe: () => () => undefined,
    } as unknown as ContentGateway;

    render(
      <AppProvider contentGateway={gateway}>
        <p>内容已恢复</p>
      </AppProvider>,
    );
    expect(
      await screen.findByRole("heading", { name: "官网内容暂时无法加载" }),
    ).toBeVisible();
    expect(screen.getByText("本地存储不可用")).toBeVisible();

    await user.click(screen.getByRole("button", { name: "重新尝试" }));
    expect(await screen.findByText("内容已恢复")).toBeVisible();
    expect(load).toHaveBeenCalledTimes(2);
  });
});
