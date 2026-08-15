import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import {
  BrowserRouter,
  createMemoryRouter,
  RouterProvider,
} from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { App } from "../App";

function useMobileViewport() {
  vi.stubGlobal(
    "matchMedia",
    vi.fn((query: string) => ({
      matches: query === "(max-width: 960px)",
      media: query,
      onchange: null,
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
      addListener: vi.fn(),
      removeListener: vi.fn(),
      dispatchEvent: vi.fn(),
    })),
  );
}

describe("mobile admin navigation", () => {
  beforeEach(() => {
    localStorage.clear();
    sessionStorage.clear();
    window.history.pushState({}, "", "/admin/login");
    useMobileViewport();
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("behaves as a modal drawer and restores focus when closed", async () => {
    const user = userEvent.setup();
    render(
      <BrowserRouter>
        <App />
      </BrowserRouter>,
    );
    await user.click(await screen.findByRole("button", { name: /进入演示后台/ }));
    expect(await screen.findByRole("heading", { name: "官网内容状态" })).toBeVisible();

    const menuButton = screen.getByRole("button", { name: "打开后台导航" });
    expect(menuButton).toHaveAttribute("aria-controls", "admin-navigation-drawer");
    expect(menuButton).toHaveAttribute("aria-expanded", "false");
    expect(screen.queryByRole("dialog", { name: "后台导航" })).not.toBeInTheDocument();

    await user.click(menuButton);
    expect(screen.getByRole("button", { name: "关闭后台导航" })).toHaveAttribute(
      "aria-expanded",
      "true",
    );
    expect(screen.getByRole("dialog", { name: "后台导航" })).toHaveAttribute(
      "aria-modal",
      "true",
    );
    const closeButton = screen.getByRole("button", { name: "关闭导航面板" });
    await waitFor(() => expect(closeButton).toHaveFocus());
    expect(document.querySelector(".admin-main")).toHaveAttribute("inert");

    await user.tab({ shift: true });
    expect(screen.getByRole("button", { name: "退出演示" })).toHaveFocus();
    await user.tab();
    expect(closeButton).toHaveFocus();

    await user.keyboard("{Escape}");
    await waitFor(() =>
      expect(screen.queryByRole("dialog", { name: "后台导航" })).not.toBeInTheDocument(),
    );
    expect(menuButton).toHaveFocus();
    expect(menuButton).toHaveAttribute("aria-expanded", "false");
  });

  it("closes from the backdrop and after navigation", async () => {
    const user = userEvent.setup();
    render(
      <BrowserRouter>
        <App />
      </BrowserRouter>,
    );
    await user.click(await screen.findByRole("button", { name: /进入演示后台/ }));
    await screen.findByRole("heading", { name: "官网内容状态" });
    const menuButton = screen.getByRole("button", { name: "打开后台导航" });

    await user.click(menuButton);
    const backdrop = document.querySelector<HTMLButtonElement>(".admin-drawer-backdrop");
    expect(backdrop).not.toBeNull();
    await user.click(backdrop!);
    await waitFor(() =>
      expect(screen.queryByRole("dialog", { name: "后台导航" })).not.toBeInTheDocument(),
    );
    expect(menuButton).toHaveFocus();

    await user.click(menuButton);
    await user.click(screen.getByRole("link", { name: "媒体资源" }));
    expect(
      await screen.findByRole("heading", { name: "产品图片与品牌素材" }),
    ).toBeVisible();
    expect(screen.queryByRole("dialog", { name: "后台导航" })).not.toBeInTheDocument();
    await waitFor(() => expect(menuButton).toHaveFocus());
  });

  it("navigates to the public homepage when logout starts", async () => {
    const user = userEvent.setup();
    render(
      <BrowserRouter>
        <App />
      </BrowserRouter>,
    );
    await user.click(await screen.findByRole("button", { name: /进入演示后台/ }));
    await screen.findByRole("heading", { name: "官网内容状态" });
    await user.click(screen.getByRole("button", { name: "打开后台导航" }));

    await user.click(screen.getByRole("button", { name: "退出演示" }));

    expect(window.location.pathname).toBe("/");
    expect(
      await screen.findByRole("heading", {
        name: /把每次错误/,
      }),
    ).toBeVisible();
  });

  it("asks before logout and only discards a dirty editor after confirmation", async () => {
    const user = userEvent.setup();
    const confirm = vi
      .spyOn(window, "confirm")
      .mockReturnValueOnce(false)
      .mockReturnValueOnce(true);
    const router = createMemoryRouter(
      [{ path: "*", element: <App /> }],
      { initialEntries: ["/admin/login"] },
    );
    render(<RouterProvider router={router} />);

    await user.click(await screen.findByRole("button", { name: /进入演示后台/ }));
    await screen.findByRole("heading", { name: "官网内容状态" });
    await user.click(screen.getByRole("button", { name: "打开后台导航" }));
    await user.click(screen.getByRole("link", { name: "站点内容" }));
    const heroTitle = await screen.findByLabelText("主标题");
    await user.type(heroTitle, "未保存");

    await user.click(screen.getByRole("button", { name: "打开后台导航" }));
    await user.click(screen.getByRole("button", { name: "退出演示" }));
    expect(router.state.location.pathname).toBe("/admin/site");
    expect((heroTitle as HTMLTextAreaElement).value).toContain("未保存");

    await user.click(screen.getByRole("button", { name: "退出演示" }));
    expect(await screen.findByRole("heading", { name: /把每次错误/ })).toBeVisible();
    expect(router.state.location.pathname).toBe("/");
    expect(confirm).toHaveBeenCalledTimes(2);
  });
});
