import {
  createContext,
  type PropsWithChildren,
  useContext,
  useMemo,
  useState,
} from "react";
import { analyticsCanStart, publishedPage } from "../domain";
import {
  ConfigurableAnalyticsGateway,
  type AnalyticsEventName,
} from "../gateways/analyticsGateway";
import {
  createAuthGateway,
  type AuthGateway,
} from "../gateways/authGateway";
import {
  createContentGateway,
  type ContentGateway,
  type ContentScope,
} from "../gateways/contentGateway";
import type {
  AdminSession,
  AnalyticsConsent,
  MediaAsset,
  WebsiteState,
} from "../types";
import { useContentSession } from "./useContentSession";

interface AppContextValue {
  state: WebsiteState;
  content: ContentGateway;
  session: AdminSession | null;
  contentScope: ContentScope;
  runtimeMode: ContentGateway["mode"];
  loginDemo: () => Promise<void>;
  logout: () => Promise<void>;
  consent: AnalyticsConsent;
  analyticsActive: boolean;
  setConsent: (consent: Exclude<AnalyticsConsent, "unknown">) => void;
  track: (name: AnalyticsEventName, properties?: Record<string, string | number>) => void;
  mediaByRole: (role: MediaAsset["role"]) => MediaAsset | null;
}

const AppContext = createContext<AppContextValue | null>(null);
const defaultContentGateway = createContentGateway();
const defaultAuthGateway = createAuthGateway();
const analyticsGateway = new ConfigurableAnalyticsGateway();
type ContentSession = ReturnType<typeof useContentSession>;

function AppLoadingState({
  error,
  onRetry,
}: {
  error: string | null;
  onRetry: () => void;
}) {
  if (error) {
    return (
      <main className="loading-screen loading-screen--error" role="alert">
        <img src="/assets/brand/app-icon.png" alt="" />
        <h1>官网内容暂时无法加载</h1>
        <p>{error}</p>
        <button
          className="button button--primary"
          type="button"
          onClick={onRetry}
        >
          重新尝试
        </button>
      </main>
    );
  }

  return (
    <main className="loading-screen" aria-busy="true">
      <img src="/assets/brand/app-icon.png" alt="" />
      <p>正在准备官网内容…</p>
    </main>
  );
}

function useAppContextValue(
  contentGateway: ContentGateway,
  contentSession: ContentSession,
): AppContextValue | null {
  const { state, contentScope, session, loginDemo, logout } = contentSession;
  const [consent, setConsentState] = useState<AnalyticsConsent>(() =>
    analyticsGateway.consent(),
  );

  return useMemo<AppContextValue | null>(() => {
    if (!state || !contentScope) return null;
    const privacy = publishedPage(state.pages, "privacy");
    const analyticsActive = analyticsCanStart(state.site, privacy, consent);

    return {
      state,
      content: contentGateway,
      session,
      contentScope,
      runtimeMode: contentGateway.mode,
      loginDemo,
      logout,
      consent,
      analyticsActive,
      setConsent: (next) => {
        analyticsGateway.setConsent(next);
        setConsentState(next);
      },
      track: (name, properties) => {
        if (analyticsActive) analyticsGateway.track(name, properties);
      },
      mediaByRole: (role) =>
        state.media.find((asset) => asset.role === role && asset.visible) ?? null,
    };
  }, [consent, contentGateway, contentScope, loginDemo, logout, session, state]);
}

export function AppProvider({
  children,
  contentGateway = defaultContentGateway,
  authGateway = defaultAuthGateway,
}: PropsWithChildren<{
  contentGateway?: ContentGateway;
  authGateway?: AuthGateway;
}>) {
  if (contentGateway.mode !== authGateway.mode) {
    throw new Error("内容网关与认证网关的运行模式不一致");
  }

  const contentSession = useContentSession(contentGateway, authGateway);
  const value = useAppContextValue(contentGateway, contentSession);

  if (contentSession.loadError || !value) {
    return (
      <AppLoadingState
        error={contentSession.loadError}
        onRetry={contentSession.retry}
      />
    );
  }

  return <AppContext.Provider value={value}>{children}</AppContext.Provider>;
}

export function useApp(): AppContextValue {
  const context = useContext(AppContext);
  if (!context) throw new Error("useApp 必须在 AppProvider 内使用");
  return context;
}
