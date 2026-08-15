import { render, screen } from "@testing-library/react";
import { BrowserRouter } from "react-router-dom";
import { describe, expect, it, vi } from "vitest";
import { AppProvider } from "../../app/AppContext";
import { createSeedState } from "../../data/seed";
import type { AuthGateway } from "../../gateways/authGateway";
import type { ContentGateway } from "../../gateways/contentGateway";
import type { AdminSession } from "../../types";
import { AdminDashboardPage } from "./AdminDashboardPage";
import { AdminLoginPage } from "./AdminLoginPage";

const remoteSession: AdminSession = {
  mode: "authenticated",
  displayName: "远程管理员",
  startedAt: "2026-07-31T00:00:00.000Z",
};

function remoteContent(): ContentGateway {
  return {
    mode: "remote",
    load: vi.fn(async () => createSeedState()),
    subscribe: vi.fn(() => () => undefined),
  } as unknown as ContentGateway;
}

function remoteAuth(
  current: AdminSession | null,
): AuthGateway {
  return {
    mode: "remote",
    current: vi.fn(async () => current),
    loginDemo: vi.fn(async () => remoteSession),
    logout: vi.fn(async () => undefined),
  };
}

describe("remote admin mode", () => {
  it("does not claim that remote login data stays in the browser", async () => {
    render(
      <AppProvider
        contentGateway={remoteContent()}
        authGateway={remoteAuth(null)}
      >
        <BrowserRouter>
          <AdminLoginPage />
        </BrowserRouter>
      </AppProvider>,
    );

    expect(await screen.findByRole("heading", { name: "管理员登录" })).toBeVisible();
    expect(screen.getByText("远程管理员会话")).toBeVisible();
    expect(
      screen.getByText("登录与内容操作会发送到已配置的内容服务。"),
    ).toBeVisible();
    expect(
      screen.queryByText("数据保存在当前浏览器，不会发送到服务器。"),
    ).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: /进入远程后台/ })).toBeVisible();
  });

  it("hides local reset controls from a remote administrator", async () => {
    render(
      <AppProvider
        contentGateway={remoteContent()}
        authGateway={remoteAuth(remoteSession)}
      >
        <BrowserRouter>
          <AdminDashboardPage />
        </BrowserRouter>
      </AppProvider>,
    );

    expect(
      await screen.findByRole("heading", { name: "官网内容状态" }),
    ).toBeVisible();
    expect(
      screen.getByText(/当前远程内容服务返回的管理状态/),
    ).toBeVisible();
    expect(
      screen.queryByRole("button", { name: "恢复初始数据" }),
    ).not.toBeInTheDocument();
  });
});
