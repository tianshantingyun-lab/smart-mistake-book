import { useEffect, useRef, useState } from "react";
import {
  MdClose,
  MdMenu,
  MdOutlineCookie,
  MdOutlineDownload,
} from "react-icons/md";
import { Link, NavLink, Outlet, useLocation } from "react-router-dom";
import { isProductPublished, publishedPage } from "../domain";
import { useApp } from "../app/AppContext";

const baseNavigation = [
  { to: "/", label: "首页" },
  { to: "/download", label: "下载" },
  { to: "/docs", label: "使用文档" },
  { to: "/changelog", label: "更新日志" },
];

export function PublicLayout() {
  const { state, consent, analyticsActive, setConsent, track, mediaByRole } = useApp();
  const [menuOpen, setMenuOpen] = useState(false);
  const [cookiePanelOpen, setCookiePanelOpen] = useState(false);
  const location = useLocation();
  const mainRef = useRef<HTMLElement>(null);
  const previousPathRef = useRef<string>(location.pathname);
  const menuButtonRef = useRef<HTMLButtonElement>(null);
  const mobileNavRef = useRef<HTMLElement>(null);
  const cookiePanelRef = useRef<HTMLElement>(null);
  const brand = mediaByRole("brand");
  const navigation = [...baseNavigation];
  if (isProductPublished(state.product)) {
    navigation.splice(1, 0, {
      to: "/product",
      label: state.product.published.navLabel,
    });
  }
  const aboutPage = publishedPage(state.pages, "about");
  const privacyPage = publishedPage(state.pages, "privacy");
  const contactPage = publishedPage(state.pages, "contact");
  const analyticsConfigured = Boolean(
    state.site.published.analytics.providerName &&
      state.site.published.analytics.siteId &&
      privacyPage,
  );

  useEffect(() => {
    setMenuOpen(false);
    window.scrollTo({ top: 0, behavior: "instant" });
    track("page_view", { path: location.pathname });
    if (previousPathRef.current !== location.pathname) {
      window.setTimeout(() => mainRef.current?.focus(), 0);
      previousPathRef.current = location.pathname;
    }
  }, [location.pathname, track]);

  useEffect(() => {
    if (analyticsConfigured && consent === "unknown") setCookiePanelOpen(true);
  }, [analyticsConfigured, consent]);

  useEffect(() => {
    if (!menuOpen) return undefined;
    const mobileNav = mobileNavRef.current;
    mobileNav?.querySelector<HTMLAnchorElement>("a")?.focus();

    function handleKeyDown(event: KeyboardEvent) {
      if (event.key !== "Escape") return;
      event.preventDefault();
      setMenuOpen(false);
    }

    document.addEventListener("keydown", handleKeyDown);
    return () => {
      document.removeEventListener("keydown", handleKeyDown);
      menuButtonRef.current?.focus();
    };
  }, [menuOpen]);

  useEffect(() => {
    if (!cookiePanelOpen) return;
    const panel = cookiePanelRef.current;
    const previousFocus =
      document.activeElement instanceof HTMLElement ? document.activeElement : null;
    if (!panel) return;
    const focusable = Array.from(
      panel.querySelectorAll<HTMLElement>(
        'button:not([disabled]), a[href], input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])',
      ),
    );
    focusable[0]?.focus();

    function handleKeyDown(event: KeyboardEvent) {
      if (event.key === "Escape") {
        setCookiePanelOpen(false);
        return;
      }
      if (event.key !== "Tab" || focusable.length === 0) return;
      const first = focusable[0];
      const last = focusable[focusable.length - 1];
      if (event.shiftKey && document.activeElement === first) {
        event.preventDefault();
        last.focus();
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault();
        first.focus();
      }
    }

    panel.addEventListener("keydown", handleKeyDown);
    return () => {
      panel.removeEventListener("keydown", handleKeyDown);
      previousFocus?.focus();
    };
  }, [cookiePanelOpen]);

  return (
    <div className="public-shell">
      <a className="skip-link" href="#main-content">
        跳到主要内容
      </a>
      <header className="site-header" inert={cookiePanelOpen ? true : undefined}>
        <div className="site-header__inner">
          <Link
            className="brand-lockup"
            to="/"
            aria-label={`${state.site.published.brandName}首页`}
          >
            <img src={brand?.src || "/assets/brand/app-icon.png"} alt="" />
            <span>
              <strong>{state.site.published.brandName}</strong>
              <small>{state.site.published.brandTagline}</small>
            </span>
          </Link>

          <nav className="desktop-nav" aria-label="主要导航">
            {navigation.map((item) => (
              <NavLink
                key={item.to}
                to={item.to}
                end={item.to === "/"}
              >
                {item.label}
              </NavLink>
            ))}
          </nav>

          <button
            ref={menuButtonRef}
            className="icon-button menu-button"
            type="button"
            aria-label={menuOpen ? "关闭导航" : "打开导航"}
            aria-controls="public-mobile-navigation"
            aria-expanded={menuOpen}
            onClick={() => setMenuOpen((open) => !open)}
          >
            {menuOpen ? <MdClose aria-hidden="true" /> : <MdMenu aria-hidden="true" />}
          </button>
        </div>

        {menuOpen && (
          <nav
            ref={mobileNavRef}
            id="public-mobile-navigation"
            className="mobile-nav"
            aria-label="移动导航"
          >
            {navigation.map((item) => (
              <NavLink
                key={item.to}
                to={item.to}
                end={item.to === "/"}
                onClick={() => setMenuOpen(false)}
              >
                {item.label}
              </NavLink>
            ))}
          </nav>
        )}
      </header>

      <main
        id="main-content"
        ref={mainRef}
        tabIndex={-1}
        aria-label="主要内容"
        inert={cookiePanelOpen ? true : undefined}
      >
        <Outlet />
      </main>

      <footer className="site-footer" inert={cookiePanelOpen ? true : undefined}>
        <div className="site-footer__inner">
          <div className="footer-brand">
            <img src={brand?.src || "/assets/brand/app-icon.png"} alt="" />
            <div>
              <strong>{state.site.published.brandName}</strong>
              <p>{state.site.published.brandTagline}</p>
            </div>
          </div>
          <nav aria-label="页脚导航">
            {aboutPage && <Link to="/about">关于</Link>}
            {privacyPage && <Link to="/privacy">隐私</Link>}
            {contactPage && <Link to="/contact">联系</Link>}
            <button
              className="footer-link"
              type="button"
              onClick={() => setCookiePanelOpen(true)}
            >
              Cookie 设置
            </button>
          </nav>
          <p className="copyright">© 2026 {state.site.published.brandName}</p>
        </div>
      </footer>

      {cookiePanelOpen && (
        <section
          ref={cookiePanelRef}
          className="cookie-panel"
          role="dialog"
          aria-modal="true"
          aria-labelledby="cookie-title"
        >
          <div className="cookie-panel__icon">
            <MdOutlineCookie aria-hidden="true" />
          </div>
          <div>
            <h2 id="cookie-title">分析与 Cookie</h2>
            <p>
              {analyticsConfigured
                ? "在你同意后，我们才会启用管理员配置的访问分析，用于了解下载和文档使用情况。"
                : "当前没有配置任何第三方分析服务，不会发送访问数据。"}
            </p>
            {privacyPage && (
              <Link to="/privacy" onClick={() => setCookiePanelOpen(false)}>
                先阅读隐私说明
              </Link>
            )}
          </div>
          <div className="cookie-panel__actions">
            {analyticsConfigured && (
              <>
                <button type="button" className="button button--ghost" onClick={() => {
                  setConsent("denied");
                  setCookiePanelOpen(false);
                }}>
                  拒绝非必要分析
                </button>
                <button type="button" className="button button--primary" onClick={() => {
                  setConsent("granted");
                  setCookiePanelOpen(false);
                }}>
                  同意分析
                </button>
              </>
            )}
            {!analyticsConfigured && (
              <button
                type="button"
                className="button button--primary"
                onClick={() => setCookiePanelOpen(false)}
              >
                知道了
              </button>
            )}
          </div>
          {analyticsActive && (
            <p className="cookie-panel__status">
              <MdOutlineDownload aria-hidden="true" /> 分析已启用，可随时撤回。
            </p>
          )}
        </section>
      )}
    </div>
  );
}
