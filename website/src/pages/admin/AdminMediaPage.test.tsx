import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useEffect, useState } from "react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { createSeedState } from "../../data/seed";
import type {
  ContentGateway,
  MediaChanges,
} from "../../gateways/contentGateway";
import type { MediaAsset, WebsiteState } from "../../types";
import { AdminMediaPage } from "./AdminMediaPage";

const appContext = vi.hoisted(() => ({
  useApp: vi.fn(),
}));

vi.mock("../../app/AppContext", () => ({
  useApp: appContext.useApp,
}));

function MediaHarness({
  onUpdate,
  onDelete,
  externalAsset,
}: {
  onUpdate: (id: string, changes: MediaChanges) => void | Promise<void>;
  onDelete?: (id: string) => void | Promise<void>;
  externalAsset?: MediaAsset;
}) {
  const [state, setState] = useState<WebsiteState>(() => createSeedState());

  useEffect(() => {
    if (!externalAsset) return;
    setState((current) => {
      const next = structuredClone(current);
      next.media = next.media.map((item) =>
        item.id === externalAsset.id ? structuredClone(externalAsset) : item,
      );
      return next;
    });
  }, [externalAsset]);

  const content = {
    updateMedia: async (id: string, changes: MediaChanges) => {
      await onUpdate(id, changes);
      setState((current) => {
        const next = structuredClone(current);
        next.media = next.media.map((item) =>
          item.id === id
            ? {
                ...item,
                ...(changes.label === undefined
                  ? {}
                  : { label: changes.label.trim() }),
                ...(changes.alt === undefined
                  ? {}
                  : { alt: changes.alt.trim() }),
                ...(changes.visible === undefined
                  ? {}
                  : { visible: changes.visible }),
              }
            : item,
        );
        return next;
      });
    },
    deleteMedia: async (id: string) => {
      await onDelete?.(id);
      setState((current) => {
        const next = structuredClone(current);
        next.media = next.media.filter((item) => item.id !== id);
        return next;
      });
    },
  } as ContentGateway;

  appContext.useApp.mockReturnValue({ state, content });
  return <AdminMediaPage />;
}

describe("AdminMediaPage row drafts", () => {
  beforeEach(() => {
    appContext.useApp.mockReset();
  });

  it("keeps row B's unsaved edit when saving row A broadcasts cloned media", async () => {
    const updateMedia = vi.fn<
      (id: string, changes: MediaChanges) => void
    >();
    const user = userEvent.setup();
    render(<MediaHarness onUpdate={updateMedia} />);

    const rowA = screen.getByDisplayValue("应用图标").closest("article");
    const rowB = screen.getByDisplayValue("今日复习界面").closest("article");
    expect(rowA).not.toBeNull();
    expect(rowB).not.toBeNull();

    const rowAName = within(rowA!).getByRole("textbox", { name: "名称" });
    const rowBName = within(rowB!).getByRole("textbox", { name: "名称" });
    await user.clear(rowBName);
    await user.type(rowBName, "尚未保存的复习图");
    await user.clear(rowAName);
    await user.type(rowAName, "已保存的应用图标");

    await user.click(
      within(rowA!).getByRole("button", { name: "保存修改" }),
    );

    await waitFor(() => {
      expect(updateMedia).toHaveBeenCalledOnce();
      expect(screen.getByRole("status")).toHaveTextContent(
        "“已保存的应用图标”已保存",
      );
      expect(
        within(rowA!).queryByText("有未保存修改"),
      ).not.toBeInTheDocument();
    });
    expect(updateMedia).toHaveBeenCalledWith("media-brand", {
      label: "已保存的应用图标",
    });
    expect(rowAName).toHaveValue("已保存的应用图标");
    expect(rowBName).toHaveValue("尚未保存的复习图");
    expect(within(rowB!).getByText("有未保存修改")).toBeVisible();
  });

  it("adopts a same-resource update when no editable field is dirty", async () => {
    const user = userEvent.setup();
    const seedAsset = createSeedState().media[0];
    const remoteAsset: MediaAsset = {
      ...seedAsset,
      label: "远端名称",
      alt: "远端替代文本",
      visible: false,
      src: "/remote/app-icon.png",
      mimeType: "image/webp",
      width: 640,
      height: 640,
    };
    const { rerender } = render(
      <MediaHarness onUpdate={vi.fn()} />,
    );

    rerender(
      <MediaHarness onUpdate={vi.fn()} externalAsset={remoteAsset} />,
    );

    const name = await screen.findByDisplayValue("远端名称");
    const row = name.closest("article");
    expect(row).not.toBeNull();
    expect(within(row!).getByRole("textbox", { name: "替代文本" })).toHaveValue(
      "远端替代文本",
    );
    expect(within(row!).getByRole("button", { name: "显示远端名称" })).toBeEnabled();
    expect(row!.querySelector("img")).toHaveAttribute(
      "src",
      "/remote/app-icon.png",
    );
    expect(within(row!).queryByText("有未保存修改")).not.toBeInTheDocument();

    await user.click(within(row!).getByRole("button", { name: "显示远端名称" }));
    expect(within(row!).getByText("有未保存修改")).toBeVisible();
  });

  it("preserves only locally edited fields while adopting the rest of a same-resource update", async () => {
    const user = userEvent.setup();
    const seedAsset = createSeedState().media[0];
    const remoteAsset: MediaAsset = {
      ...seedAsset,
      label: "远端名称",
      alt: "远端替代文本",
      visible: false,
      src: "/remote/updated-icon.png",
    };
    const onUpdate = vi.fn<
      (id: string, changes: MediaChanges) => void
    >();
    const { rerender } = render(<MediaHarness onUpdate={onUpdate} />);
    const name = screen.getByDisplayValue("应用图标");
    const row = name.closest("article");
    expect(row).not.toBeNull();

    await user.clear(name);
    await user.type(name, "本地名称");
    rerender(
      <MediaHarness onUpdate={onUpdate} externalAsset={remoteAsset} />,
    );

    await waitFor(() => {
      expect(name).toHaveValue("本地名称");
      expect(within(row!).getByRole("textbox", { name: "替代文本" })).toHaveValue(
        "远端替代文本",
      );
      expect(within(row!).getByRole("button", { name: "显示本地名称" })).toBeEnabled();
      expect(row!.querySelector("img")).toHaveAttribute(
        "src",
        "/remote/updated-icon.png",
      );
    });
    expect(within(row!).getByText("有未保存修改")).toBeVisible();

    await user.clear(name);
    await user.type(name, "远端名称");
    expect(within(row!).queryByText("有未保存修改")).not.toBeInTheDocument();
  });

  it("keeps edits made while a save is pending when the saved asset is broadcast", async () => {
    let resolveUpdate!: () => void;
    const updatePending = new Promise<void>((resolve) => {
      resolveUpdate = resolve;
    });
    const onUpdate = vi.fn(
      (_id: string, _changes: MediaChanges) => updatePending,
    );
    const user = userEvent.setup();
    render(<MediaHarness onUpdate={onUpdate} />);
    const name = screen.getByDisplayValue("应用图标");
    const row = name.closest("article");
    expect(row).not.toBeNull();

    await user.clear(name);
    await user.type(name, "正在保存的名称");
    await user.click(within(row!).getByRole("button", { name: "保存修改" }));
    expect(within(row!).getByRole("button", { name: "保存中…" })).toBeDisabled();

    await user.clear(name);
    await user.type(name, "保存期间继续编辑");
    resolveUpdate();

    await waitFor(() => {
      expect(onUpdate).toHaveBeenCalledOnce();
      expect(within(row!).getByRole("button", { name: "保存修改" })).toBeEnabled();
    });
    expect(onUpdate).toHaveBeenCalledWith("media-brand", {
      label: "正在保存的名称",
    });
    expect(name).toHaveValue("保存期间继续编辑");
    expect(within(row!).getByText("有未保存修改")).toBeVisible();
  });

  it("preserves a concurrent same-resource update while applying only the saved patch", async () => {
    let resolveUpdate!: () => void;
    const updatePending = new Promise<void>((resolve) => {
      resolveUpdate = resolve;
    });
    const onUpdate = vi.fn(
      (_id: string, _changes: MediaChanges) => updatePending,
    );
    const user = userEvent.setup();
    const seedAsset = createSeedState().media[0];
    const remoteAsset: MediaAsset = {
      ...seedAsset,
      alt: "远端更新的替代文本",
      visible: false,
      src: "/remote/concurrent-icon.png",
      mimeType: "image/webp",
      width: 512,
      height: 512,
    };
    const { rerender } = render(<MediaHarness onUpdate={onUpdate} />);
    const name = screen.getByDisplayValue("应用图标");
    const row = name.closest("article");
    expect(row).not.toBeNull();

    await user.clear(name);
    await user.type(name, "仅更新名称");
    await user.click(within(row!).getByRole("button", { name: "保存修改" }));
    rerender(
      <MediaHarness onUpdate={onUpdate} externalAsset={remoteAsset} />,
    );

    await waitFor(() => {
      expect(
        within(row!).getByRole("textbox", { name: "替代文本" }),
      ).toHaveValue("远端更新的替代文本");
      expect(row!.querySelector("img")).toHaveAttribute(
        "src",
        "/remote/concurrent-icon.png",
      );
    });
    resolveUpdate();

    await waitFor(() => {
      expect(within(row!).queryByText("有未保存修改")).not.toBeInTheDocument();
    });
    expect(onUpdate).toHaveBeenCalledWith("media-brand", {
      label: "仅更新名称",
    });
    expect(name).toHaveValue("仅更新名称");
    expect(
      within(row!).getByRole("textbox", { name: "替代文本" }),
    ).toHaveValue("远端更新的替代文本");
    expect(within(row!).getByRole("button", { name: "显示仅更新名称" })).toBeEnabled();
    expect(row!.querySelector("img")).toHaveAttribute(
      "src",
      "/remote/concurrent-icon.png",
    );
  });

  it("disables delete and visibility actions while saving", async () => {
    let resolveUpdate!: () => void;
    const updatePending = new Promise<void>((resolve) => {
      resolveUpdate = resolve;
    });
    const user = userEvent.setup();
    const uploadedAsset: MediaAsset = {
      ...createSeedState().media[0],
      bundled: false,
      blobKey: "uploaded-media",
    };
    const { rerender } = render(
      <MediaHarness onUpdate={() => updatePending} />,
    );
    rerender(
      <MediaHarness
        onUpdate={() => updatePending}
        externalAsset={uploadedAsset}
      />,
    );
    const name = await screen.findByDisplayValue("应用图标");
    const row = name.closest("article");
    expect(row).not.toBeNull();

    await user.type(name, "（修改）");
    await user.click(within(row!).getByRole("button", { name: "保存修改" }));

    expect(within(row!).getByRole("button", { name: "隐藏应用图标（修改）" })).toBeDisabled();
    expect(within(row!).getByRole("button", { name: "删除应用图标" })).toBeDisabled();

    resolveUpdate();
    await waitFor(() => {
      expect(
        within(row!).getByRole("button", { name: "删除应用图标（修改）" }),
      ).toBeEnabled();
    });
  });

  it("keeps a row draft mounted while filters temporarily hide it", async () => {
    const user = userEvent.setup();
    render(<MediaHarness onUpdate={vi.fn()} />);
    const name = screen.getByDisplayValue("今日复习界面");
    const row = name.closest("article");
    expect(row).not.toBeNull();

    await user.clear(name);
    await user.type(name, "尚未保存的复习图片");
    await user.type(screen.getByLabelText("搜索媒体"), "应用图标");

    expect(row).toHaveAttribute("hidden");
    await user.clear(screen.getByLabelText("搜索媒体"));

    expect(row).not.toHaveAttribute("hidden");
    expect(name).toHaveValue("尚未保存的复习图片");
    expect(within(row!).getByText("有未保存修改")).toBeVisible();
  });
});
