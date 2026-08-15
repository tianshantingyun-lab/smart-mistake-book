import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { createSeedState } from "../../data/seed";
import { AdminDashboardPage } from "./AdminDashboardPage";

const appContext = vi.hoisted(() => ({
  useApp: vi.fn(),
}));

vi.mock("../../app/AppContext", () => ({
  useApp: appContext.useApp,
}));

describe("AdminDashboardPage", () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  it("locks reset while pending and surfaces storage failures", async () => {
    let rejectReset!: (error: Error) => void;
    const reset = vi.fn(
      () =>
        new Promise<void>((_resolve, reject) => {
          rejectReset = reject;
        }),
    );
    appContext.useApp.mockReturnValue({
      state: createSeedState(),
      content: { reset },
      runtimeMode: "local-demo",
    });
    vi.spyOn(window, "confirm").mockReturnValue(true);
    const user = userEvent.setup();

    render(
      <MemoryRouter>
        <AdminDashboardPage />
      </MemoryRouter>,
    );

    await user.click(screen.getByRole("button", { name: "恢复初始数据" }));
    expect(reset).toHaveBeenCalledOnce();
    expect(
      screen.getByRole("button", { name: "正在恢复…" }),
    ).toBeDisabled();

    rejectReset(new Error("本地存储不可用"));
    expect(await screen.findByRole("status")).toHaveTextContent(
      "本地存储不可用",
    );
    expect(
      screen.getByRole("button", { name: "恢复初始数据" }),
    ).toBeEnabled();
  });
});
