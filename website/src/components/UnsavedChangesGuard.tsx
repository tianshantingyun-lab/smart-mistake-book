import {
  createContext,
  type RefObject,
  type ReactNode,
  useCallback,
  useContext,
  useEffect,
  useId,
  useMemo,
  useRef,
  useState,
} from "react";
import {
  type BlockerFunction,
  UNSAFE_DataRouterContext,
  useBlocker,
} from "react-router-dom";

const defaultMessage = "当前页面有未保存的修改或正在处理的操作。确定离开吗？";

interface UnsavedChangesState {
  dirty: boolean;
  pending: boolean;
  message: string;
}

type Registration = UnsavedChangesState | null;
type RegisterGuard = (id: string, state: Registration) => void;
type RunConfirmedExit = (onAccepted: () => void) => boolean;

const UnsavedChangesContext = createContext<RegisterGuard | null>(null);
const RequestExitContext = createContext<RunConfirmedExit>((onAccepted) => {
  onAccepted();
  return true;
});

function RouteLeavePrompt({
  message,
  pending,
  shouldBlock,
}: {
  message: string;
  pending: boolean;
  shouldBlock: BlockerFunction;
}) {
  const blocker = useBlocker(shouldBlock);

  useEffect(() => {
    if (blocker.state !== "blocked") return;
    if (pending) {
      window.alert("操作正在进行，请等待完成后再离开。");
      blocker.reset();
      return;
    }
    if (window.confirm(message)) {
      blocker.proceed();
    } else {
      blocker.reset();
    }
  }, [blocker, message, pending]);

  return null;
}

function OptionalRouteLeavePrompt(props: {
  message: string;
  pending: boolean;
  shouldBlock: BlockerFunction;
}) {
  const dataRouter = useContext(UNSAFE_DataRouterContext);
  return dataRouter ? <RouteLeavePrompt {...props} /> : null;
}

function useGuardRegistry() {
  const [registrations, setRegistrations] = useState<Map<string, UnsavedChangesState>>(
    () => new Map(),
  );
  const register = useCallback<RegisterGuard>((id, state) => {
    setRegistrations((current) => {
      const next = new Map(current);
      if (state) next.set(id, state);
      else next.delete(id);
      return next;
    });
  }, []);

  const activeState = useMemo(
    () => Array.from(registrations.values()).find((entry) => entry.pending)
      ?? Array.from(registrations.values()).find((entry) => entry.dirty),
    [registrations],
  );
  return { activeState, register };
}

function useShouldBlockNavigation(
  shouldBlock: boolean,
  bypassNextNavigation: RefObject<boolean>,
) {
  return useCallback<BlockerFunction>(({
    currentLocation,
    nextLocation,
  }) => {
    const currentUrl = `${currentLocation.pathname}${currentLocation.search}${currentLocation.hash}`;
    const nextUrl = `${nextLocation.pathname}${nextLocation.search}${nextLocation.hash}`;
    if (currentUrl === nextUrl) return false;
    if (bypassNextNavigation.current) {
      bypassNextNavigation.current = false;
      return false;
    }
    return shouldBlock;
  }, [shouldBlock]);
}

function useConfirmedExit(
  activeState: UnsavedChangesState | undefined,
  bypassNextNavigation: RefObject<boolean>,
) {
  return useCallback<RunConfirmedExit>((onAccepted) => {
    if (!activeState) {
      onAccepted();
      return true;
    }
    if (activeState.pending) {
      window.alert("操作正在进行，请等待完成后再退出。");
      return false;
    }
    if (activeState.dirty && !window.confirm(activeState.message)) return false;
    bypassNextNavigation.current = true;
    try {
      onAccepted();
      return true;
    } finally {
      queueMicrotask(() => {
        bypassNextNavigation.current = false;
      });
    }
  }, [activeState]);
}

function useBeforeUnloadProtection(
  message: string,
  shouldBlock: boolean,
) {
  useEffect(() => {
    if (!shouldBlock) return undefined;
    const preventUnload = (event: BeforeUnloadEvent) => {
      event.preventDefault();
      event.returnValue = message;
    };
    window.addEventListener("beforeunload", preventUnload);
    return () => window.removeEventListener("beforeunload", preventUnload);
  }, [message, shouldBlock]);
}

export function UnsavedChangesProvider({ children }: { children: ReactNode }) {
  const { activeState, register } = useGuardRegistry();
  const shouldBlock = Boolean(activeState);
  const message = activeState?.message ?? defaultMessage;
  const bypassNextNavigation = useRef(false);
  const shouldBlockNavigation = useShouldBlockNavigation(
    shouldBlock,
    bypassNextNavigation,
  );
  const requestExit = useConfirmedExit(activeState, bypassNextNavigation);
  useBeforeUnloadProtection(message, shouldBlock);

  return (
    <RequestExitContext.Provider value={requestExit}>
      <UnsavedChangesContext.Provider value={register}>
        <OptionalRouteLeavePrompt
          message={message}
          pending={Boolean(activeState?.pending)}
          shouldBlock={shouldBlockNavigation}
        />
        {children}
      </UnsavedChangesContext.Provider>
    </RequestExitContext.Provider>
  );
}

export function useUnsavedChangesGuard({
  dirty,
  message = defaultMessage,
  pending = false,
}: {
  dirty: boolean;
  message?: string;
  pending?: boolean;
}) {
  const id = useId();
  const register = useContext(UnsavedChangesContext);

  useEffect(() => {
    if (!register) return undefined;
    register(id, { dirty, message, pending });
    return () => register(id, null);
  }, [dirty, id, message, pending, register]);
}

export function useUnsavedChangesExitRequest() {
  return useContext(RequestExitContext);
}
