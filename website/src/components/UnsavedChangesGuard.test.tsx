import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useState } from "react";
import {
  createMemoryRouter,
  Link,
  Outlet,
  RouterProvider,
} from "react-router-dom";
import { afterEach, describe, expect, it, vi } from "vitest";
import {
  UnsavedChangesProvider,
  useUnsavedChangesGuard,
  useUnsavedChangesExitRequest,
} from "./UnsavedChangesGuard";

function GuardedEditor({ pending = false }: { pending?: boolean }) {
  const [dirty, setDirty] = useState(false);
  useUnsavedChangesGuard({
    dirty,
    message: "放弃当前编辑？",
    pending,
  });
  return (
    <>
      <button type="button" onClick={() => setDirty(true)}>修改内容</button>
      <Link to="/next">前往下一页</Link>
    </>
  );
}

function ExitControl({ onExit }: { onExit: () => void }) {
  const requestExit = useUnsavedChangesExitRequest();
  return (
    <button type="button" onClick={() => requestExit(onExit)}>
      退出
    </button>
  );
}

function Root({
  onExit = () => undefined,
  pending = false,
}: {
  onExit?: () => void;
  pending?: boolean;
}) {
  return (
    <UnsavedChangesProvider>
      <ExitControl onExit={onExit} />
      <GuardedEditor pending={pending} />
      <Outlet />
    </UnsavedChangesProvider>
  );
}

function renderRouter(options: { onExit?: () => void; pending?: boolean } = {}) {
  const router = createMemoryRouter([
    {
      path: "/",
      element: <Root {...options} />,
      children: [
        { index: true, element: null },
        { path: "next", element: <h1>下一页</h1> },
      ],
    },
  ]);
  render(<RouterProvider router={router} />);
  return router;
}

afterEach(() => {
  vi.restoreAllMocks();
});

describe("UnsavedChangesProvider", () => {
  it("keeps the editor open when an internal navigation is cancelled", async () => {
    const user = userEvent.setup();
    const confirm = vi.spyOn(window, "confirm").mockReturnValue(false);
    const router = renderRouter();

    await user.click(screen.getByRole("button", { name: "修改内容" }));
    await user.click(screen.getByRole("link", { name: "前往下一页" }));

    expect(confirm).toHaveBeenCalledWith("放弃当前编辑？");
    expect(router.state.location.pathname).toBe("/");
    expect(screen.getByRole("button", { name: "修改内容" })).toBeVisible();
  });

  it("continues an internal navigation after confirmation", async () => {
    const user = userEvent.setup();
    vi.spyOn(window, "confirm").mockReturnValue(true);
    const router = renderRouter();

    await user.click(screen.getByRole("button", { name: "修改内容" }));
    await user.click(screen.getByRole("link", { name: "前往下一页" }));

    expect(await screen.findByRole("heading", { name: "下一页" })).toBeVisible();
    expect(router.state.location.pathname).toBe("/next");
  });

  it("prevents a native unload while an editor is dirty", async () => {
    const user = userEvent.setup();
    renderRouter();
    await user.click(screen.getByRole("button", { name: "修改内容" }));

    const event = new Event("beforeunload", { cancelable: true });
    expect(window.dispatchEvent(event)).toBe(false);
    expect(event.defaultPrevented).toBe(true);
  });

  it("does not run a destructive exit when dirty confirmation is cancelled", async () => {
    const user = userEvent.setup();
    const onExit = vi.fn();
    vi.spyOn(window, "confirm").mockReturnValue(false);
    renderRouter({ onExit });

    await user.click(screen.getByRole("button", { name: "修改内容" }));
    await user.click(screen.getByRole("button", { name: "退出" }));

    expect(onExit).not.toHaveBeenCalled();
  });

  it("does not leak an unused exit bypass into a later navigation", async () => {
    const user = userEvent.setup();
    const onExit = vi.fn();
    const confirm = vi
      .spyOn(window, "confirm")
      .mockReturnValueOnce(true)
      .mockReturnValueOnce(false);
    const router = renderRouter({ onExit });

    await user.click(screen.getByRole("button", { name: "修改内容" }));
    await user.click(screen.getByRole("button", { name: "退出" }));
    await Promise.resolve();
    await user.click(screen.getByRole("link", { name: "前往下一页" }));

    expect(onExit).toHaveBeenCalledOnce();
    expect(confirm).toHaveBeenCalledTimes(2);
    expect(router.state.location.pathname).toBe("/");
  });

  it("hard-blocks navigation and destructive exit while an operation is pending", async () => {
    const user = userEvent.setup();
    const onExit = vi.fn();
    const alert = vi.spyOn(window, "alert").mockImplementation(() => undefined);
    const confirm = vi.spyOn(window, "confirm");
    const router = renderRouter({ onExit, pending: true });

    await user.click(screen.getByRole("link", { name: "前往下一页" }));
    await user.click(screen.getByRole("button", { name: "退出" }));

    expect(router.state.location.pathname).toBe("/");
    expect(onExit).not.toHaveBeenCalled();
    expect(confirm).not.toHaveBeenCalled();
    expect(alert).toHaveBeenCalledTimes(2);
  });
});
