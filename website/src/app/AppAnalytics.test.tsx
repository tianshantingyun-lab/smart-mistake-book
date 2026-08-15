import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { createSeedState } from "../data/seed";
import type { AuthGateway } from "../gateways/authGateway";
import { ConfigurableAnalyticsGateway } from "../gateways/analyticsGateway";
import type { ContentGateway } from "../gateways/contentGateway";
import type { AdminSession, WebsiteState } from "../types";
import { AppProvider, useApp } from "./AppContext";

const unusedSession: AdminSession = {
  mode: "authenticated",
  displayName: "测试管理员",
  startedAt: "2026-07-31T00:00:00.000Z",
};

function makeContentGateway(state: WebsiteState): ContentGateway {
  return {
    mode: "remote",
    load: vi.fn(async () => structuredClone(state)),
    subscribe: vi.fn(() => () => undefined),
  } as unknown as ContentGateway;
}

function makeAuthGateway(): AuthGateway {
  return {
    mode: "remote",
    current: vi.fn(async () => null),
    loginDemo: vi.fn(async () => unusedSession),
    logout: vi.fn(async () => undefined),
  };
}

function AnalyticsProbe() {
  const { analyticsActive, setConsent, track } = useApp();

  return (
    <div>
      <p>active:{String(analyticsActive)}</p>
      <button type="button" onClick={() => track("page_view", { path: "/" })}>
        记录访问
      </button>
      <button
        type="button"
        onClick={() => {
          setConsent("denied");
          track("page_view", { path: "/after-withdrawal" });
        }}
      >
        撤回并记录
      </button>
    </div>
  );
}

function renderAnalytics(state: WebsiteState) {
  return render(
    <AppProvider
      contentGateway={makeContentGateway(state)}
      authGateway={makeAuthGateway()}
    >
      <AnalyticsProbe />
    </AppProvider>,
  );
}

describe("AppProvider analytics gate", () => {
  beforeEach(() => {
    localStorage.clear();
    delete window.__smartMistakeBookAnalyticsQueue;
  });

  it("tracks only when published configuration, privacy and consent are all present", async () => {
    const state = createSeedState();
    state.site.published.analytics = {
      providerName: "example",
      siteId: "site-1",
    };
    new ConfigurableAnalyticsGateway().setConsent("granted");
    const user = userEvent.setup();

    renderAnalytics(state);
    expect(await screen.findByText("active:true")).toBeVisible();
    await user.click(screen.getByRole("button", { name: "记录访问" }));

    expect(window.__smartMistakeBookAnalyticsQueue).toEqual([
      { name: "page_view", properties: { path: "/" } },
    ]);
  });

  it.each([
    {
      name: "consent is unknown",
      prepare: (state: WebsiteState) => {
        state.site.published.analytics = {
          providerName: "example",
          siteId: "site-1",
        };
      },
    },
    {
      name: "configuration exists only in the draft",
      prepare: (state: WebsiteState) => {
        state.site.draft.analytics = {
          providerName: "example",
          siteId: "site-1",
        };
        new ConfigurableAnalyticsGateway().setConsent("granted");
      },
    },
    {
      name: "privacy is not published",
      prepare: (state: WebsiteState) => {
        state.site.published.analytics = {
          providerName: "example",
          siteId: "site-1",
        };
        state.pages.find((page) => page.slug === "privacy")!.publishedAt = null;
        new ConfigurableAnalyticsGateway().setConsent("granted");
      },
    },
  ])("does not track when $name", async ({ prepare }) => {
    const state = createSeedState();
    prepare(state);
    const user = userEvent.setup();

    renderAnalytics(state);
    expect(await screen.findByText("active:false")).toBeVisible();
    await user.click(screen.getByRole("button", { name: "记录访问" }));

    expect(window.__smartMistakeBookAnalyticsQueue).toBeUndefined();
  });

  it("stops immediately when consent is withdrawn before React rerenders", async () => {
    const state = createSeedState();
    state.site.published.analytics = {
      providerName: "example",
      siteId: "site-1",
    };
    new ConfigurableAnalyticsGateway().setConsent("granted");
    const user = userEvent.setup();

    renderAnalytics(state);
    expect(await screen.findByText("active:true")).toBeVisible();
    await user.click(screen.getByRole("button", { name: "撤回并记录" }));

    expect(new ConfigurableAnalyticsGateway().consent()).toBe("denied");
    expect(window.__smartMistakeBookAnalyticsQueue).toBeUndefined();
    expect(screen.getByText("active:false")).toBeVisible();
  });
});
