import type { AnalyticsConsent } from "../types";

const CONSENT_KEY = "smart-mistake-book.website.analytics-consent";

export type AnalyticsEventName =
  | "page_view"
  | "download_intent"
  | "docs_search"
  | "contact_link";

export interface AnalyticsGateway {
  consent(): AnalyticsConsent;
  setConsent(consent: Exclude<AnalyticsConsent, "unknown">): void;
  track(name: AnalyticsEventName, properties?: Record<string, string | number>): void;
}

declare global {
  interface Window {
    __smartMistakeBookAnalyticsQueue?: Array<{
      name: AnalyticsEventName;
      properties?: Record<string, string | number>;
    }>;
  }
}

export class ConfigurableAnalyticsGateway implements AnalyticsGateway {
  consent(): AnalyticsConsent {
    const value = localStorage.getItem(CONSENT_KEY);
    return value === "granted" || value === "denied" ? value : "unknown";
  }

  setConsent(consent: Exclude<AnalyticsConsent, "unknown">): void {
    localStorage.setItem(CONSENT_KEY, consent);
  }

  track(name: AnalyticsEventName, properties?: Record<string, string | number>): void {
    if (this.consent() !== "granted") return;
    window.__smartMistakeBookAnalyticsQueue ??= [];
    window.__smartMistakeBookAnalyticsQueue.push({ name, properties });
  }
}
