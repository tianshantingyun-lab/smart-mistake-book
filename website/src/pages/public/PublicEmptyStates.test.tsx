import { render, screen, within } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { createSeedState } from "../../data/seed";
import type { Release, WebsiteState } from "../../types";
import { ChangelogPage } from "./ChangelogPage";
import { DownloadPage } from "./DownloadPage";
import { ManagedPagePage } from "./ManagedPagePage";

const appContext = vi.hoisted(() => ({
  useApp: vi.fn(),
}));

vi.mock("../../app/AppContext", () => ({
  useApp: appContext.useApp,
}));

function useState(state: WebsiteState) {
  appContext.useApp.mockReturnValue({
    state,
    track: vi.fn(),
  });
}

function renderPage(page: React.ReactNode) {
  render(<MemoryRouter>{page}</MemoryRouter>);
}

describe("public empty states", () => {
  beforeEach(() => {
    appContext.useApp.mockReset();
  });

  it("gives download visitors a clear status and a useful route home", () => {
    useState(createSeedState());
    renderPage(<DownloadPage />);

    expect(screen.getByRole("heading", { name: "正式版暂未发布" })).toBeVisible();
    expect(screen.getByRole("link", { name: "返回首页" })).toHaveAttribute("href", "/");
    expect(screen.queryByText(/管理员|发布入口/)).not.toBeInTheDocument();
  });

  it("gives changelog visitors a useful route home without operator language", () => {
    useState(createSeedState());
    renderPage(<ChangelogPage />);

    expect(screen.getByRole("heading", { name: "暂无已发布版本" })).toBeVisible();
    expect(screen.getByRole("link", { name: "返回首页" })).toHaveAttribute("href", "/");
    expect(screen.queryByText(/管理员/)).not.toBeInTheDocument();
  });

  it("renders machine-readable changelog publication times", () => {
    const state = createSeedState();
    const release: Release = {
      id: "release-stable",
      channel: "stable",
      versionName: "1.0.0",
      versionCode: 1,
      minAndroid: 6,
      releaseNotes: "首个版本",
      status: "published",
      package: null,
      createdAt: "2026-07-29T08:00:00.000Z",
      publishedAt: "2026-07-30T08:00:00.000Z",
    };
    state.releases = [release];
    useState(state);
    renderPage(<ChangelogPage />);

    expect(screen.getByText("2026-07-30").closest("time")).toHaveAttribute(
      "datetime",
      release.publishedAt,
    );
  });

  it("shows Shanghai publication dates and sorts mixed offsets by instant", () => {
    const state = createSeedState();
    const earlier: Release = {
      id: "release-earlier",
      channel: "beta",
      versionName: "较早版本",
      versionCode: 1,
      minAndroid: 6,
      releaseNotes: "较早",
      status: "published",
      package: null,
      createdAt: "2026-07-30T10:00:00.000Z",
      publishedAt: "2026-07-31T00:30:00+14:00",
    };
    const later: Release = {
      ...earlier,
      id: "release-later",
      channel: "stable",
      versionName: "较晚版本",
      versionCode: 2,
      releaseNotes: "较晚",
      publishedAt: "2026-07-30T23:00:00.000Z",
    };
    state.releases = [earlier, later];
    useState(state);
    renderPage(<ChangelogPage />);

    const entries = screen.getAllByRole("article");
    expect(within(entries[0]).getByText("较晚版本")).toBeVisible();
    expect(within(entries[0]).getByText("2026-07-31")).toBeVisible();
    expect(within(entries[1]).getByText("较早版本")).toBeVisible();
    expect(within(entries[1]).getByText("2026-07-30")).toBeVisible();
  });

  it("does not present metadata-only releases as a disabled download", () => {
    const state = createSeedState();
    state.releases = [{
      id: "metadata-release",
      channel: "stable",
      versionName: "1.0.0",
      versionCode: 1,
      minAndroid: 6,
      releaseNotes: "版本说明",
      status: "published",
      package: {
        fileName: "app.apk",
        size: 1024,
        mimeType: "application/vnd.android.package-archive",
        downloadUrl: null,
        sha256: null,
        storageState: "metadata-only",
      },
      createdAt: "2026-07-30T08:00:00.000Z",
      publishedAt: "2026-07-30T09:00:00.000Z",
    }];
    useState(state);
    renderPage(<DownloadPage />);

    expect(
      screen.getByRole("status"),
    ).toHaveTextContent("安装文件暂未开放下载");
    expect(
      screen.queryByRole("button", { name: /下载|演示版本/ }),
    ).not.toBeInTheDocument();
    expect(screen.queryByText(/真实存储|SHA-256/)).not.toBeInTheDocument();
  });

  it("does not render an analytics-only contact control", () => {
    useState(createSeedState());
    renderPage(<ManagedPagePage slug="contact" />);

    expect(screen.getByRole("heading", { name: "联系" })).toBeVisible();
    expect(screen.queryByRole("button")).not.toBeInTheDocument();
  });

  it("treats an unpublished managed page as not found", () => {
    const state = createSeedState();
    const contact = state.pages.find((page) => page.slug === "contact");
    if (!contact) throw new Error("seed contact page missing");
    contact.publishedAt = null;
    useState(state);
    renderPage(<ManagedPagePage slug="contact" />);

    expect(screen.getByRole("heading", { name: "页面未找到" })).toBeVisible();
    expect(screen.getByRole("link", { name: "返回首页" })).toHaveAttribute("href", "/");
    expect(screen.queryByText(/管理员/)).not.toBeInTheDocument();
  });
});
