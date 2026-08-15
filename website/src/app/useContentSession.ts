import {
  type Dispatch,
  type SetStateAction,
  useCallback,
  useEffect,
  useRef,
  useState,
} from "react";
import type { AuthGateway } from "../gateways/authGateway";
import {
  ContentGatewayError,
  type ContentGateway,
  type ContentScope,
} from "../gateways/contentGateway";
import type { AdminSession, WebsiteState } from "../types";

const REMOTE_SIGNED_OUT_KEY =
  "smart-mistake-book.website.remote-signed-out";

interface ContentSessionSnapshot {
  state: WebsiteState | null;
  contentScope: ContentScope | null;
  loadError: string | null;
  session: AdminSession | null;
}

interface ContentSessionController extends ContentSessionSnapshot {
  loginDemo: () => Promise<void>;
  logout: () => Promise<void>;
  retry: () => void;
}

interface LoadedContent {
  state: WebsiteState;
  contentScope: ContentScope;
  session: AdminSession | null;
}

interface ContentSessionRuntime {
  authGateway: AuthGateway;
  contentGateway: ContentGateway;
  activeScopeRef: { current: ContentScope | null };
  operationRef: { current: number };
  publicOnlyRef: { current: boolean };
  setSnapshot: Dispatch<SetStateAction<ContentSessionSnapshot>>;
}

type ContentBootstrapRuntime = Omit<ContentSessionRuntime, "operationRef">;

const emptySession: ContentSessionSnapshot = {
  state: null,
  contentScope: null,
  loadError: null,
  session: null,
};

function errorMessage(error: unknown, fallback: string) {
  return error instanceof Error ? error.message : fallback;
}

function hasRemoteSignedOutMarker(authGateway: AuthGateway): boolean {
  if (authGateway.mode !== "remote") return false;
  try {
    return sessionStorage.getItem(REMOTE_SIGNED_OUT_KEY) !== null;
  } catch {
    return false;
  }
}

function setRemoteSignedOutMarker(authGateway: AuthGateway): void {
  if (authGateway.mode !== "remote") return;
  try {
    sessionStorage.setItem(REMOTE_SIGNED_OUT_KEY, "1");
  } catch {
    // The in-memory public-only guard remains authoritative for this mount.
  }
}

function clearRemoteSignedOutMarker(authGateway: AuthGateway): void {
  if (authGateway.mode !== "remote") return;
  try {
    sessionStorage.removeItem(REMOTE_SIGNED_OUT_KEY);
  } catch {
    // A stale marker is fail-closed and can only require another explicit login.
  }
}

async function loadInitialContent(
  authGateway: AuthGateway,
  contentGateway: ContentGateway,
  isCurrent: () => boolean,
  publicOnly: boolean,
  onAuthorizationFailure: () => void,
): Promise<LoadedContent | null> {
  if (publicOnly) {
    const state = await contentGateway.load("public");
    return { contentScope: "public", session: null, state };
  }

  let session: AdminSession | null = null;

  try {
    session = await authGateway.current();
    const contentScope: ContentScope = session ? "admin" : "public";
    const state = await contentGateway.load(contentScope);
    return { contentScope, session, state };
  } catch (error) {
    if (!isCurrent()) return null;
    if (
      !session ||
      !(error instanceof ContentGatewayError) ||
      (error.status !== 401 && error.status !== 403)
    ) {
      throw error;
    }

    onAuthorizationFailure();
    return null;
  }
}

async function runContentBootstrap(
  runtime: ContentSessionRuntime,
  isCurrent: () => boolean,
) {
  const {
    activeScopeRef,
    authGateway,
    contentGateway,
    publicOnlyRef,
    setSnapshot,
  } = runtime;

  try {
    const loaded = await loadInitialContent(
      authGateway,
      contentGateway,
      isCurrent,
      publicOnlyRef.current,
      () => recoverFromAuthorizationFailure(runtime),
    );
    if (!loaded || !isCurrent()) return;
    activeScopeRef.current = loaded.contentScope;
    setSnapshot({ ...loaded, loadError: null });
  } catch (error) {
    if (!isCurrent()) return;
    setSnapshot((current) => ({
      ...current,
      loadError: errorMessage(error, "内容服务暂时不可用"),
    }));
  }
}

function subscribeToActiveContent(
  runtime: ContentBootstrapRuntime,
  isActive: () => boolean,
) {
  const { activeScopeRef, contentGateway, setSnapshot } = runtime;
  return contentGateway.subscribe((state, contentScope) => {
    if (!isActive() || activeScopeRef.current !== contentScope) return;
    setSnapshot((current) => ({ ...current, state }));
  });
}

function recoverFromAuthorizationFailure(runtime: ContentSessionRuntime): void {
  if (runtime.publicOnlyRef.current) return;

  const operation = beginLogout(runtime);
  void (async () => {
    const logoutError = await captureLogoutError(runtime.authGateway);
    if (runtime.operationRef.current !== operation) return;
    await loadPublicAfterLogout(runtime, operation, logoutError);
  })();
}

function useContentBootstrap(
  runtime: ContentSessionRuntime,
  loadAttempt: number,
) {
  const {
    activeScopeRef,
    authGateway,
    contentGateway,
    operationRef,
    publicOnlyRef,
    setSnapshot,
  } = runtime;
  useEffect(() => {
    let active = true;
    const operation = ++operationRef.current;
    const isCurrent = () => active && operationRef.current === operation;
    activeScopeRef.current = null;
    setSnapshot(emptySession);
    void runContentBootstrap(runtime, isCurrent);
    const unsubscribe = subscribeToActiveContent(
      runtime,
      () => active,
    );
    const unsubscribeAuthorizationFailure =
      contentGateway.subscribeAuthorizationFailure?.(() => {
        if (active) recoverFromAuthorizationFailure(runtime);
      }) ?? (() => undefined);

    return () => {
      active = false;
      unsubscribe();
      unsubscribeAuthorizationFailure();
    };
  }, [
    activeScopeRef,
    authGateway,
    contentGateway,
    loadAttempt,
    operationRef,
    publicOnlyRef,
    setSnapshot,
  ]);
}

function useLoginDemo(runtime: ContentSessionRuntime) {
  const {
    activeScopeRef,
    authGateway,
    contentGateway,
    operationRef,
    publicOnlyRef,
    setSnapshot,
  } = runtime;
  return useCallback(async () => {
    const operation = ++operationRef.current;
    const session = await authGateway.loginDemo();
    if (operationRef.current !== operation) return;
    contentGateway.confirmAdminAuthentication();

    try {
      const state = await contentGateway.load("admin");
      if (operationRef.current !== operation) return;
      clearRemoteSignedOutMarker(authGateway);
      publicOnlyRef.current = false;
      activeScopeRef.current = "admin";
      setSnapshot({
        state,
        contentScope: "admin",
        loadError: null,
        session,
      });
    } catch (error) {
      if (operationRef.current === operation) {
        publicOnlyRef.current = true;
        setRemoteSignedOutMarker(authGateway);
        try {
          await authGateway.logout();
        } catch {
          // The original load error is the useful error to surface.
        }
      }
      throw error;
    }
  }, [
    activeScopeRef,
    authGateway,
    contentGateway,
    operationRef,
    publicOnlyRef,
    setSnapshot,
  ]);
}

function beginLogout(runtime: ContentSessionRuntime) {
  const operation = ++runtime.operationRef.current;
  runtime.publicOnlyRef.current = true;
  runtime.activeScopeRef.current = null;
  setRemoteSignedOutMarker(runtime.authGateway);
  runtime.setSnapshot(emptySession);
  return operation;
}

async function captureLogoutError(authGateway: AuthGateway) {
  try {
    await authGateway.logout();
    return null;
  } catch (error) {
    return error;
  }
}

function logoutFailureMessage(
  error: unknown,
  mode: AuthGateway["mode"],
) {
  if (!error) return null;
  const detail = errorMessage(error, "退出请求失败");
  return mode === "remote"
    ? `管理内容已从当前标签页清除，但远程退出未确认：${detail}`
    : `管理内容已从当前标签页清除，但演示会话清除失败：${detail}`;
}

async function loadPublicAfterLogout(
  runtime: ContentSessionRuntime,
  operation: number,
  logoutError: unknown,
) {
  const isCurrent = () => runtime.operationRef.current === operation;
  const logoutFailure = logoutFailureMessage(
    logoutError,
    runtime.authGateway.mode,
  );

  try {
    const state = await runtime.contentGateway.load("public");
    if (!isCurrent()) return;
    runtime.activeScopeRef.current = "public";
    runtime.setSnapshot({
      state,
      contentScope: "public",
      loadError: logoutFailure,
      session: null,
    });
  } catch (error) {
    if (!isCurrent()) return;
    const publicLoadError = errorMessage(error, "公开内容暂时无法加载");
    runtime.setSnapshot((current) => ({
      ...current,
      loadError: logoutFailure
        ? `${logoutFailure}；${publicLoadError}`
        : publicLoadError,
    }));
  }
}

function useLogout(runtime: ContentSessionRuntime) {
  const {
    activeScopeRef,
    authGateway,
    contentGateway,
    operationRef,
    publicOnlyRef,
    setSnapshot,
  } = runtime;
  return useCallback(async () => {
    const operation = beginLogout(runtime);
    const logoutError = await captureLogoutError(authGateway);
    if (operationRef.current !== operation) return;
    await loadPublicAfterLogout(runtime, operation, logoutError);
  }, [
    activeScopeRef,
    authGateway,
    contentGateway,
    operationRef,
    publicOnlyRef,
    setSnapshot,
  ]);
}

export function useContentSession(
  contentGateway: ContentGateway,
  authGateway: AuthGateway,
): ContentSessionController {
  const [snapshot, setSnapshot] =
    useState<ContentSessionSnapshot>(emptySession);
  const [loadAttempt, setLoadAttempt] = useState(0);
  const activeScopeRef = useRef<ContentScope | null>(null);
  const operationRef = useRef(0);
  const publicOnlyRef = useRef(hasRemoteSignedOutMarker(authGateway));
  const runtime = {
    activeScopeRef,
    authGateway,
    contentGateway,
    operationRef,
    publicOnlyRef,
    setSnapshot,
  };

  useContentBootstrap(runtime, loadAttempt);
  const loginDemo = useLoginDemo(runtime);
  const logout = useLogout(runtime);
  const retry = useCallback(() => {
    setLoadAttempt((attempt) => attempt + 1);
  }, []);

  return { ...snapshot, loginDemo, logout, retry };
}
