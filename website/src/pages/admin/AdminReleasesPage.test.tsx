import { act, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { AppProvider } from "../../app/AppContext";
import { createSeedState } from "../../data/seed";
import type { AuthGateway } from "../../gateways/authGateway";
import type { ContentGateway } from "../../gateways/contentGateway";
import type { AdminSession, Release } from "../../types";
import { AdminReleasesPage } from "./AdminReleasesPage";

const adminSession: AdminSession = {
  mode: "authenticated",
  displayName: "测试管理员",
  startedAt: "2026-07-31T00:00:00.000Z",
};

function deferred<T>() {
  let resolve!: (value: T | PromiseLike<T>) => void;
  const promise = new Promise<T>((next) => {
    resolve = next;
  });
  return { promise, resolve };
}

function release(
  id: string,
  versionName: string,
  versionCode: number,
): Release {
  return {
    id,
    channel: "stable",
    versionName,
    versionCode,
    minAndroid: 6,
    releaseNotes: `${versionName} 更新说明`,
    status: "draft",
    package: null,
    createdAt: "2026-07-31T00:00:00.000Z",
    publishedAt: null,
  };
}

describe("AdminReleasesPage package selection", () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it.each([
    {
      mode: "local-demo" as const,
      description: "支持正式版与测试版。演示模式只保存 APK 元数据，不保存二进制，也不生成虚假校验值。",
      packageState: "metadata-only" as const,
      packageLabel: /仅元数据/,
      publishButton: "模拟发布",
      successMessage: "演示版本已发布；因没有真实文件地址，公开页不会提供下载链接",
    },
    {
      mode: "remote" as const,
      description: "支持正式版与测试版。APK 将上传至已配置的内容服务，发布后由服务提供真实下载信息。",
      packageState: "stored" as const,
      packageLabel: /已存储/,
      publishButton: "发布版本",
      successMessage: "版本已发布",
    },
  ])("uses $mode release copy and package state", async ({
    mode,
    description,
    packageState,
    packageLabel,
    publishButton,
    successMessage,
  }) => {
    const state = createSeedState();
    const seededRelease = release("release-a", "1.0.0", 1);
    seededRelease.package = {
      fileName: "existing.apk",
      size: 1024,
      mimeType: "application/vnd.android.package-archive",
      downloadUrl: packageState === "stored" ? "https://downloads.example.com/existing.apk" : null,
      sha256: packageState === "stored" ? "a".repeat(64) : null,
      storageState: packageState,
    };
    state.releases = [seededRelease];
    const content = {
      mode,
      load: vi.fn(async () => structuredClone(state)),
      subscribe: vi.fn(() => () => undefined),
      saveReleaseDraft: vi.fn(async () => undefined),
      publishRelease: vi.fn(async () => undefined),
    } as unknown as ContentGateway;
    const auth: AuthGateway = {
      mode,
      current: vi.fn(async () => adminSession),
      loginDemo: vi.fn(async () => adminSession),
      logout: vi.fn(async () => undefined),
    };
    const user = userEvent.setup();

    render(
      <AppProvider contentGateway={content} authGateway={auth}>
        <AdminReleasesPage />
      </AppProvider>,
    );

    expect(await screen.findByText(description)).toBeVisible();
    expect(screen.getByText(packageLabel)).toBeVisible();

    await user.click(screen.getByRole("button", { name: publishButton }));

    expect(await screen.findByText(successMessage)).toBeVisible();
  });

  it("clears version A's APK before saving version B", async () => {
    vi.spyOn(window, "confirm").mockReturnValue(true);
    const state = createSeedState();
    state.releases = [
      release("release-a", "1.0.0", 1),
      release("release-b", "2.0.0", 2),
    ];
    const saveReleaseDraft = vi.fn(async () => undefined);
    const attachReleasePackage = vi.fn(async () => undefined);
    const content = {
      mode: "remote",
      load: vi.fn(async () => structuredClone(state)),
      subscribe: vi.fn(() => () => undefined),
      saveReleaseDraft,
      attachReleasePackage,
    } as unknown as ContentGateway;
    const auth: AuthGateway = {
      mode: "remote",
      current: vi.fn(async () => adminSession),
      loginDemo: vi.fn(async () => adminSession),
      logout: vi.fn(async () => undefined),
    };
    const user = userEvent.setup();

    render(
      <AppProvider contentGateway={content} authGateway={auth}>
        <AdminReleasesPage />
      </AppProvider>,
    );

    expect(
      await screen.findByRole("heading", { name: "Android 安装包" }),
    ).toBeVisible();

    const apk = new File(["apk"], "version-a.apk", {
      type: "application/vnd.android.package-archive",
    });
    const input = screen.getByLabelText(/APK 安装包/) as HTMLInputElement;
    await user.upload(input, apk);
    expect(screen.getByText("version-a.apk")).toBeVisible();
    expect(input.files).toHaveLength(1);
    expect(input.value).toContain("version-a.apk");

    await user.click(screen.getByRole("button", { name: /2\.0\.0/ }));

    expect(await screen.findByDisplayValue("2.0.0")).toBeVisible();
    await waitFor(() => {
      expect(screen.queryByText("version-a.apk")).not.toBeInTheDocument();
    });
    expect(screen.getByText("选择 .apk 文件")).toBeVisible();
    expect(input.files).toHaveLength(0);
    expect(input.value).toBe("");

    await user.click(screen.getByRole("button", { name: "保存草稿" }));

    await waitFor(() => {
      expect(saveReleaseDraft).toHaveBeenCalledWith(
        expect.objectContaining({ id: "release-b" }),
      );
    });
    expect(attachReleasePackage).not.toHaveBeenCalled();
  });

  it("keeps the APK available for retry when attaching it fails", async () => {
    const state = createSeedState();
    state.releases = [release("release-a", "1.0.0", 1)];
    let notify: Parameters<ContentGateway["subscribe"]>[0] | undefined;
    const saveReleaseDraft = vi.fn(async (next: Release) => {
      state.releases = state.releases.map((item) =>
        item.id === next.id ? structuredClone(next) : item,
      );
      notify?.(structuredClone(state), "admin");
    });
    const attachReleasePackage = vi
      .fn()
      .mockRejectedValueOnce(new Error("安装包附加失败"))
      .mockResolvedValueOnce(undefined);
    const content = {
      mode: "remote",
      load: vi.fn(async () => structuredClone(state)),
      subscribe: vi.fn(
        (listener: Parameters<ContentGateway["subscribe"]>[0]) => {
          notify = listener;
          return () => undefined;
        },
      ),
      saveReleaseDraft,
      attachReleasePackage,
    } as unknown as ContentGateway;
    const auth: AuthGateway = {
      mode: "remote",
      current: vi.fn(async () => adminSession),
      loginDemo: vi.fn(async () => adminSession),
      logout: vi.fn(async () => undefined),
    };
    const user = userEvent.setup();

    render(
      <AppProvider contentGateway={content} authGateway={auth}>
        <AdminReleasesPage />
      </AppProvider>,
    );

    expect(
      await screen.findByRole("heading", { name: "Android 安装包" }),
    ).toBeVisible();

    const apk = new File(["apk"], "retry.apk", {
      type: "application/vnd.android.package-archive",
    });
    const input = screen.getByLabelText(/APK 安装包/) as HTMLInputElement;
    const notes = screen.getByLabelText("更新说明（Markdown）");
    await user.type(notes, "，失败后保留");
    await user.upload(input, apk);
    await user.click(screen.getByRole("button", { name: "保存草稿" }));

    expect(await screen.findByText("安装包附加失败")).toBeVisible();
    expect(screen.getByText("retry.apk")).toBeVisible();
    expect(notes).toHaveValue("1.0.0 更新说明，失败后保留");
    expect(input.files).toHaveLength(1);
    expect(input.value).toContain("retry.apk");
    expect(attachReleasePackage).toHaveBeenCalledWith("release-a", apk);

    await user.click(screen.getByRole("button", { name: "保存草稿" }));

    expect(await screen.findByText("版本草稿已保存")).toBeVisible();
    expect(attachReleasePackage).toHaveBeenCalledTimes(2);
    expect(attachReleasePackage).toHaveBeenNthCalledWith(
      2,
      "release-a",
      apk,
    );
    expect(input.files).toHaveLength(0);
    expect(input.value).toBe("");

    await user.upload(input, apk);
    expect(input.files).toHaveLength(1);
    expect(input.value).toContain("retry.apk");
    expect(screen.getByText("retry.apk")).toBeVisible();
  });

  it("keeps dirty metadata and APK when leaving the editor is canceled", async () => {
    const confirm = vi.spyOn(window, "confirm").mockReturnValue(false);
    const state = createSeedState();
    state.releases = [
      release("release-a", "1.0.0", 1),
      release("release-b", "2.0.0", 2),
    ];
    const content = {
      mode: "remote",
      load: vi.fn(async () => structuredClone(state)),
      subscribe: vi.fn(() => () => undefined),
    } as unknown as ContentGateway;
    const auth: AuthGateway = {
      mode: "remote",
      current: vi.fn(async () => adminSession),
      loginDemo: vi.fn(async () => adminSession),
      logout: vi.fn(async () => undefined),
    };
    const user = userEvent.setup();

    render(
      <AppProvider contentGateway={content} authGateway={auth}>
        <AdminReleasesPage />
      </AppProvider>,
    );

    expect(
      await screen.findByRole("heading", { name: "Android 安装包" }),
    ).toBeVisible();

    const versionName = screen.getByLabelText("版本名称");
    const input = screen.getByLabelText(/APK 安装包/) as HTMLInputElement;
    const apk = new File(["apk"], "unsaved.apk", {
      type: "application/vnd.android.package-archive",
    });
    await user.clear(versionName);
    await user.type(versionName, "1.0.1");
    await user.upload(input, apk);

    await user.click(screen.getByRole("button", { name: /2\.0\.0/ }));
    await user.click(screen.getByRole("button", { name: "新建版本" }));

    expect(confirm).toHaveBeenCalledTimes(2);
    expect(versionName).toHaveValue("1.0.1");
    expect(input.files?.[0]).toBe(apk);
    expect(input.value).toContain("unsaved.apk");
    expect(screen.getByText("unsaved.apk")).toBeVisible();
    expect(screen.getByRole("button", { name: /1\.0\.0/ })).toHaveClass("is-active");
  });

  it("does not copy a locked version when discarding its unsaved APK is canceled", async () => {
    const confirm = vi.spyOn(window, "confirm").mockReturnValue(false);
    const state = createSeedState();
    state.releases = [release("release-a", "1.0.0", 1)];
    let notify: Parameters<ContentGateway["subscribe"]>[0] | undefined;
    const content = {
      mode: "remote",
      load: vi.fn(async () => structuredClone(state)),
      subscribe: vi.fn(
        (listener: Parameters<ContentGateway["subscribe"]>[0]) => {
          notify = listener;
          return () => undefined;
        },
      ),
    } as unknown as ContentGateway;
    const auth: AuthGateway = {
      mode: "remote",
      current: vi.fn(async () => adminSession),
      loginDemo: vi.fn(async () => adminSession),
      logout: vi.fn(async () => undefined),
    };
    const user = userEvent.setup();

    render(
      <AppProvider contentGateway={content} authGateway={auth}>
        <AdminReleasesPage />
      </AppProvider>,
    );

    expect(
      await screen.findByRole("heading", { name: "Android 安装包" }),
    ).toBeVisible();

    const input = screen.getByLabelText(/APK 安装包/) as HTMLInputElement;
    const apk = new File(["apk"], "unsaved.apk", {
      type: "application/vnd.android.package-archive",
    });
    await user.upload(input, apk);

    state.releases[0] = {
      ...state.releases[0],
      status: "published",
      publishedAt: "2026-07-31T01:00:00.000Z",
    };
    act(() => notify?.(structuredClone(state), "admin"));

    const copyButton = await screen.findByRole("button", { name: "复制为新草稿" });
    await user.click(copyButton);

    expect(confirm).toHaveBeenCalledOnce();
    expect(copyButton).toBeVisible();
    expect(input.files?.[0]).toBe(apk);
    expect(input.value).toContain("unsaved.apk");
    expect(screen.getByText("unsaved.apk")).toBeVisible();
  });

  it("advances the dirty baseline after a successful save", async () => {
    const confirm = vi.spyOn(window, "confirm").mockReturnValue(false);
    const state = createSeedState();
    state.releases = [
      release("release-a", "1.0.0", 1),
      release("release-b", "2.0.0", 2),
    ];
    let notify: Parameters<ContentGateway["subscribe"]>[0] | undefined;
    const saveReleaseDraft = vi.fn(async (next: Release) => {
      state.releases = state.releases.map((item) =>
        item.id === next.id ? structuredClone(next) : item,
      );
      notify?.(structuredClone(state), "admin");
    });
    const content = {
      mode: "remote",
      load: vi.fn(async () => structuredClone(state)),
      subscribe: vi.fn(
        (listener: Parameters<ContentGateway["subscribe"]>[0]) => {
          notify = listener;
          return () => undefined;
        },
      ),
      saveReleaseDraft,
    } as unknown as ContentGateway;
    const auth: AuthGateway = {
      mode: "remote",
      current: vi.fn(async () => adminSession),
      loginDemo: vi.fn(async () => adminSession),
      logout: vi.fn(async () => undefined),
    };
    const user = userEvent.setup();

    render(
      <AppProvider contentGateway={content} authGateway={auth}>
        <AdminReleasesPage />
      </AppProvider>,
    );

    expect(
      await screen.findByRole("heading", { name: "Android 安装包" }),
    ).toBeVisible();

    const versionName = screen.getByLabelText("版本名称");
    await user.clear(versionName);
    await user.type(versionName, "1.0.1");
    await user.click(screen.getByRole("button", { name: "保存草稿" }));

    expect(await screen.findByText("版本草稿已保存")).toBeVisible();
    await user.click(screen.getByRole("button", { name: /2\.0\.0/ }));

    expect(confirm).not.toHaveBeenCalled();
    expect(await screen.findByDisplayValue("2.0.0")).toBeVisible();
  });

  it("blocks package and version changes while a save is pending", async () => {
    const state = createSeedState();
    state.releases = [
      release("release-a", "1.0.0", 1),
      release("release-b", "2.0.0", 2),
    ];
    const pendingSave = deferred<void>();
    const saveReleaseDraft = vi.fn(() => pendingSave.promise);
    const attachReleasePackage = vi.fn(async () => undefined);
    const content = {
      mode: "remote",
      load: vi.fn(async () => structuredClone(state)),
      subscribe: vi.fn(() => () => undefined),
      saveReleaseDraft,
      attachReleasePackage,
    } as unknown as ContentGateway;
    const auth: AuthGateway = {
      mode: "remote",
      current: vi.fn(async () => adminSession),
      loginDemo: vi.fn(async () => adminSession),
      logout: vi.fn(async () => undefined),
    };
    const user = userEvent.setup();

    render(
      <AppProvider contentGateway={content} authGateway={auth}>
        <AdminReleasesPage />
      </AppProvider>,
    );

    expect(
      await screen.findByRole("heading", { name: "Android 安装包" }),
    ).toBeVisible();

    const original = new File(["apk"], "original.apk", {
      type: "application/vnd.android.package-archive",
    });
    const replacement = new File(["new"], "replacement.apk", {
      type: "application/vnd.android.package-archive",
    });
    const input = screen.getByLabelText(/APK 安装包/) as HTMLInputElement;
    const saveButton = screen.getByRole("button", { name: "保存草稿" });
    const otherReleaseButton = screen.getByRole("button", { name: /2\.0\.0/ });
    const channel = screen.getByLabelText("发布渠道");
    const versionName = screen.getByLabelText("版本名称");
    const versionCode = screen.getByLabelText("版本代码");
    const minAndroid = screen.getByLabelText("最低 Android 版本");
    const notes = screen.getByLabelText("更新说明（Markdown）");

    await user.upload(input, original);
    await user.click(saveButton);

    await waitFor(() => {
      expect(saveReleaseDraft).toHaveBeenCalledTimes(1);
    });
    expect(input).toBeDisabled();
    expect(saveButton).toBeDisabled();
    expect(otherReleaseButton).toBeDisabled();
    expect(channel).toBeDisabled();
    expect(versionName).toBeDisabled();
    expect(versionCode).toBeDisabled();
    expect(minAndroid).toBeDisabled();
    expect(notes).toBeDisabled();

    await user.upload(input, replacement);
    await user.click(otherReleaseButton);
    await user.click(saveButton);
    await user.selectOptions(channel, "beta");
    await user.type(versionName, "-changed");
    await user.type(versionCode, "9");
    await user.type(minAndroid, "9");
    await user.type(notes, "-changed");

    expect(saveReleaseDraft).toHaveBeenCalledTimes(1);
    expect(screen.getByDisplayValue("1.0.0")).toBeVisible();
    expect(input.files?.[0]).toBe(original);
    expect(screen.getByText("original.apk")).toBeVisible();
    expect(channel).toHaveValue("stable");
    expect(versionName).toHaveValue("1.0.0");
    expect(versionCode).toHaveValue(1);
    expect(minAndroid).toHaveValue(6);
    expect(notes).toHaveValue("1.0.0 更新说明");

    pendingSave.resolve();

    expect(await screen.findByText("版本草稿已保存")).toBeVisible();
    expect(attachReleasePackage).toHaveBeenCalledWith("release-a", original);
    expect(input).toBeEnabled();
    expect(input.files).toHaveLength(0);
    expect(input.value).toBe("");
  });

  it("confirms and locks a published version while archiving", async () => {
    const state = createSeedState();
    const published = release("release-a", "1.0.0", 1);
    published.status = "published";
    published.publishedAt = "2026-07-31T00:30:00.000Z";
    state.releases = [published];
    const pendingArchive = deferred<void>();
    const archiveRelease = vi.fn(() => pendingArchive.promise);
    const content = {
      mode: "remote",
      load: vi.fn(async () => structuredClone(state)),
      subscribe: vi.fn(() => () => undefined),
      archiveRelease,
    } as unknown as ContentGateway;
    const auth: AuthGateway = {
      mode: "remote",
      current: vi.fn(async () => adminSession),
      loginDemo: vi.fn(async () => adminSession),
      logout: vi.fn(async () => undefined),
    };
    const confirm = vi.spyOn(window, "confirm").mockReturnValueOnce(false).mockReturnValue(true);
    const user = userEvent.setup();

    render(
      <AppProvider contentGateway={content} authGateway={auth}>
        <AdminReleasesPage />
      </AppProvider>,
    );

    const archive = await screen.findByRole("button", { name: "归档" });
    await user.click(archive);
    expect(archiveRelease).not.toHaveBeenCalled();

    await user.click(archive);
    expect(confirm).toHaveBeenCalledTimes(2);
    expect(archiveRelease).toHaveBeenCalledOnce();
    expect(screen.getByRole("button", { name: "处理中…" })).toBeDisabled();
    await user.click(screen.getByRole("button", { name: "处理中…" }));
    expect(archiveRelease).toHaveBeenCalledOnce();

    pendingArchive.resolve();
    expect(await screen.findByText("版本已归档")).toBeVisible();
  });

  it("rejects invalid integer metadata explicitly and exposes integer input steps", async () => {
    const state = createSeedState();
    state.releases = [release("release-a", "1.0.0", 1)];
    const saveReleaseDraft = vi.fn(async () => undefined);
    const content = {
      mode: "local-demo",
      load: vi.fn(async () => structuredClone(state)),
      subscribe: vi.fn(() => () => undefined),
      saveReleaseDraft,
    } as unknown as ContentGateway;
    const auth: AuthGateway = {
      mode: "local-demo",
      current: vi.fn(async () => adminSession),
      loginDemo: vi.fn(async () => adminSession),
      logout: vi.fn(async () => undefined),
    };
    const user = userEvent.setup();

    render(
      <AppProvider contentGateway={content} authGateway={auth}>
        <AdminReleasesPage />
      </AppProvider>,
    );

    expect(
      await screen.findByRole("heading", { name: "Android 安装包" }),
    ).toBeVisible();
    const versionCode = screen.getByLabelText("版本代码");
    const minAndroid = screen.getByLabelText("最低 Android 版本");
    expect(versionCode).toHaveAttribute("step", "1");
    expect(minAndroid).toHaveAttribute("step", "1");

    await user.clear(versionCode);
    await user.type(versionCode, "1.5");
    await user.click(screen.getByRole("button", { name: "保存草稿" }));
    expect(
      await screen.findByText("版本代码必须是大于或等于 1 的整数"),
    ).toBeVisible();
    expect(saveReleaseDraft).not.toHaveBeenCalled();

    await user.clear(versionCode);
    await user.type(versionCode, "2");
    await user.clear(minAndroid);
    await user.type(minAndroid, "5");
    await user.click(screen.getByRole("button", { name: "保存草稿" }));
    expect(
      await screen.findByText("最低 Android 版本必须是大于或等于 6 的整数"),
    ).toBeVisible();
    expect(saveReleaseDraft).not.toHaveBeenCalled();
  });
});
