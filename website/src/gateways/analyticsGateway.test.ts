import { beforeEach, describe, expect, it } from "vitest";
import { ConfigurableAnalyticsGateway } from "./analyticsGateway";

describe("ConfigurableAnalyticsGateway", () => {
  beforeEach(() => {
    localStorage.clear();
    delete window.__smartMistakeBookAnalyticsQueue;
  });

  it("uses unknown when no consent has been stored", () => {
    expect(new ConfigurableAnalyticsGateway().consent()).toBe("unknown");
  });

  it.each(["", "GRANTED", "accepted", "null", "{\"value\":\"granted\"}"])(
    "treats invalid stored consent %j as unknown",
    (storedConsent) => {
      localStorage.setItem(
        "smart-mistake-book.website.analytics-consent",
        storedConsent,
      );

      expect(new ConfigurableAnalyticsGateway().consent()).toBe("unknown");
    },
  );

  it.each(["granted", "denied"] as const)(
    "persists %s consent across gateway instances",
    (consent) => {
      new ConfigurableAnalyticsGateway().setConsent(consent);

      expect(new ConfigurableAnalyticsGateway().consent()).toBe(consent);
    },
  );

  it("queues events only after consent is granted", () => {
    const gateway = new ConfigurableAnalyticsGateway();

    gateway.track("page_view", { path: "/" });
    expect(window.__smartMistakeBookAnalyticsQueue).toBeUndefined();

    gateway.setConsent("denied");
    gateway.track("docs_search", { queryLength: 4 });
    expect(window.__smartMistakeBookAnalyticsQueue).toBeUndefined();

    gateway.setConsent("granted");
    gateway.track("download_intent", { channel: "stable" });
    expect(window.__smartMistakeBookAnalyticsQueue).toEqual([
      {
        name: "download_intent",
        properties: { channel: "stable" },
      },
    ]);
  });

  it("stops queuing immediately after consent is withdrawn", () => {
    const gateway = new ConfigurableAnalyticsGateway();
    gateway.setConsent("granted");
    gateway.track("page_view", { path: "/" });

    gateway.setConsent("denied");
    gateway.track("page_view", { path: "/download" });
    new ConfigurableAnalyticsGateway().track("contact_link");

    expect(window.__smartMistakeBookAnalyticsQueue).toEqual([
      { name: "page_view", properties: { path: "/" } },
    ]);
  });
});
