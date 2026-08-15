import {
  type KeyboardEvent as ReactKeyboardEvent,
  type RefObject,
  useCallback,
  useEffect,
  useRef,
  useState,
} from "react";
import {
  MdAnalytics,
  MdArticle,
  MdClose,
  MdDashboard,
  MdLogout,
  MdMenu,
  MdOutlineCollections,
  MdOutlineHome,
  MdOutlineRocketLaunch,
  MdSettings,
  MdViewQuilt,
} from "react-icons/md";
import {
  Link,
  NavLink,
  Navigate,
  Outlet,
  useLocation,
  useNavigate,
} from "react-router-dom";
import { useUnsavedChangesExitRequest } from "./UnsavedChangesGuard";
import { useApp } from "../app/AppContext";

const adminNavigation = [
  { to: "/admin", label: "概览", icon: MdDashboard, end: true },
  { to: "/admin/site", label: "站点内容", icon: MdOutlineHome },
  { to: "/admin/product", label: "产品页面", icon: MdViewQuilt },
  { to: "/admin/pages", label: "静态页面", icon: MdArticle },
  { to: "/admin/media", label: "媒体资源", icon: MdOutlineCollections },
  { to: "/admin/releases", label: "版本发布", icon: MdOutlineRocketLaunch },
  { to: "/admin/docs", label: "使用文档", icon: MdSettings },
  { to: "/admin/analytics", label: "访问分析", icon: MdAnalytics },
];

const adminDrawerMediaQuery = "(max-width: 960px)";
const focusableSelector =
  'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])';

interface AdminSidebarProps {
  brandSrc: string;
  closeButtonRef: RefObject<HTMLButtonElement | null>;
  drawerRef: RefObject<HTMLElement | null>;
  isDrawer: boolean;
  isOpen: boolean;
  logout: () => void;
  onClose: () => void;
  onKeyDown: (event: ReactKeyboardEvent<HTMLElement>) => void;
  runtimeMode: "local-demo" | "remote";
}

interface AdminTopbarProps {
  displayName: string;
  isOpen: boolean;
  menuButtonRef: RefObject<HTMLButtonElement | null>;
  onToggle: () => void;
  runtimeMode: "local-demo" | "remote";
}

function useAdminDrawerMode() {
  const [isDrawer, setIsDrawer] = useState(
    () =>
      typeof window !== "undefined" &&
      typeof window.matchMedia === "function" &&
      window.matchMedia(adminDrawerMediaQuery).matches,
  );

  useEffect(() => {
    if (typeof window.matchMedia !== "function") return;

    const mediaQuery = window.matchMedia(adminDrawerMediaQuery);
    const updateMode = (event: MediaQueryListEvent) => setIsDrawer(event.matches);
    setIsDrawer(mediaQuery.matches);
    mediaQuery.addEventListener("change", updateMode);
    return () => mediaQuery.removeEventListener("change", updateMode);
  }, []);

  return isDrawer;
}

function containDrawerFocus(
  event: ReactKeyboardEvent<HTMLElement>,
  drawer: HTMLElement | null,
  closeDrawer: () => void,
) {
  if (event.key === "Escape") {
    event.preventDefault();
    closeDrawer();
    return;
  }
  if (event.key !== "Tab") return;

  const focusableElements = Array.from(
    drawer?.querySelectorAll<HTMLElement>(focusableSelector) ?? [],
  );
  const firstElement = focusableElements[0];
  const lastElement = focusableElements.at(-1);
  if (!firstElement || !lastElement) {
    event.preventDefault();
    return;
  }

  if (event.shiftKey && document.activeElement === firstElement) {
    event.preventDefault();
    lastElement.focus();
  } else if (!event.shiftKey && document.activeElement === lastElement) {
    event.preventDefault();
    firstElement.focus();
  }
}

function useAdminDrawerController(pathname: string) {
  const [sidebarOpen, setSidebarOpen] = useState(false);
  const isDrawer = useAdminDrawerMode();
  const menuButtonRef = useRef<HTMLButtonElement>(null);
  const drawerRef = useRef<HTMLElement>(null);
  const drawerCloseRef = useRef<HTMLButtonElement>(null);
  const previousPathRef = useRef(pathname);

  const closeSidebar = useCallback((restoreFocus = true) => {
    setSidebarOpen(false);
    if (restoreFocus) {
      window.setTimeout(() => menuButtonRef.current?.focus(), 0);
    }
  }, []);

  useEffect(() => {
    if (!isDrawer) {
      setSidebarOpen(false);
      return;
    }
    if (!sidebarOpen) return;

    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    const focusTimer = window.setTimeout(() => drawerCloseRef.current?.focus(), 0);

    return () => {
      window.clearTimeout(focusTimer);
      document.body.style.overflow = previousOverflow;
    };
  }, [isDrawer, sidebarOpen]);

  useEffect(() => {
    if (previousPathRef.current === pathname) return;
    previousPathRef.current = pathname;
    if (isDrawer && sidebarOpen) closeSidebar();
  }, [closeSidebar, isDrawer, pathname, sidebarOpen]);

  return {
    closeButtonRef: drawerCloseRef,
    close: closeSidebar,
    drawerRef,
    isDrawer,
    isOpen: sidebarOpen,
    menuButtonRef,
    onKeyDown: (event: ReactKeyboardEvent<HTMLElement>) =>
      containDrawerFocus(event, drawerRef.current, closeSidebar),
    toggle: () => {
      if (sidebarOpen) closeSidebar();
      else setSidebarOpen(true);
    },
  };
}

function AdminSidebar({
  brandSrc,
  closeButtonRef,
  drawerRef,
  isDrawer,
  isOpen,
  logout,
  onClose,
  onKeyDown,
  runtimeMode,
}: AdminSidebarProps) {
  const handleNavigation = () => {
    if (isDrawer) onClose();
  };

  return (
    <aside
      id="admin-navigation-drawer"
      ref={drawerRef}
      className={`admin-sidebar ${isOpen ? "is-open" : ""}`}
      role={isDrawer ? "dialog" : undefined}
      aria-modal={isDrawer ? true : undefined}
      aria-label={isDrawer ? "后台导航" : undefined}
      onKeyDown={isDrawer ? onKeyDown : undefined}
    >
      {isDrawer ? (
        <button
          ref={closeButtonRef}
          className="icon-button admin-sidebar__close"
          type="button"
          aria-label="关闭导航面板"
          onClick={onClose}
        >
          <MdClose aria-hidden="true" />
        </button>
      ) : null}
      <Link className="admin-brand" to="/admin" onClick={handleNavigation}>
        <img src={brandSrc} alt="" />
        <span>
          <strong>智能错题本</strong>
          <small>官网管理</small>
        </span>
      </Link>
      <nav aria-label="后台导航">
        {adminNavigation.map(({ to, label, icon: Icon, end }) => (
          <NavLink key={to} to={to} end={end} onClick={handleNavigation}>
            <Icon aria-hidden="true" />
            {label}
          </NavLink>
        ))}
      </nav>
      <div className="admin-sidebar__footer">
        <Link to="/" target="_blank" rel="noreferrer">
          查看公开官网
        </Link>
        <button type="button" onClick={logout}>
          <MdLogout aria-hidden="true" />
          {runtimeMode === "local-demo" ? "退出演示" : "退出登录"}
        </button>
      </div>
    </aside>
  );
}

function AdminTopbar({
  displayName,
  isOpen,
  menuButtonRef,
  onToggle,
  runtimeMode,
}: AdminTopbarProps) {
  return (
    <header className="admin-topbar">
      <button
        ref={menuButtonRef}
        className="icon-button"
        type="button"
        aria-label={isOpen ? "关闭后台导航" : "打开后台导航"}
        aria-controls="admin-navigation-drawer"
        aria-expanded={isOpen}
        onClick={onToggle}
      >
        <MdMenu aria-hidden="true" />
      </button>
      <span className="demo-badge">
        {runtimeMode === "local-demo"
          ? "演示模式 · 数据仅保存在当前浏览器"
          : "远程内容服务"}
      </span>
      <span className="admin-user">{displayName}</span>
    </header>
  );
}

export function AdminLayout() {
  const {
    session,
    contentScope,
    logout,
    mediaByRole,
    runtimeMode,
  } = useApp();
  const location = useLocation();
  const navigate = useNavigate();
  const requestExit = useUnsavedChangesExitRequest();
  const drawer = useAdminDrawerController(location.pathname);
  const brand = mediaByRole("brand");
  const handleLogout = () => {
    requestExit(() => {
      navigate("/", { replace: true });
      void logout();
    });
  };

  if (!session || contentScope !== "admin") {
    return <Navigate to="/admin/login" replace state={{ from: location }} />;
  }

  return (
    <div className="admin-shell">
      {drawer.isDrawer && drawer.isOpen ? (
        <button
          className="admin-drawer-backdrop"
          type="button"
          tabIndex={-1}
          aria-hidden="true"
          onClick={() => drawer.close()}
        />
      ) : null}

      {!drawer.isDrawer || drawer.isOpen ? (
        <AdminSidebar
          brandSrc={brand?.src || "/assets/brand/app-icon.png"}
          closeButtonRef={drawer.closeButtonRef}
          drawerRef={drawer.drawerRef}
          isDrawer={drawer.isDrawer}
          isOpen={drawer.isOpen}
          logout={handleLogout}
          onClose={() => drawer.close()}
          onKeyDown={drawer.onKeyDown}
          runtimeMode={runtimeMode}
        />
      ) : null}

      <div
        className="admin-main"
        inert={drawer.isDrawer && drawer.isOpen ? true : undefined}
      >
        <AdminTopbar
          displayName={session.displayName}
          isOpen={drawer.isOpen}
          menuButtonRef={drawer.menuButtonRef}
          onToggle={drawer.toggle}
          runtimeMode={runtimeMode}
        />
        <main>
          <Outlet />
        </main>
      </div>
    </div>
  );
}
