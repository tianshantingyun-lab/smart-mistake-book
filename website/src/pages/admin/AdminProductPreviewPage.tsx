import { useState } from "react";
import { MdArrowBack, MdDesktopWindows, MdPhoneAndroid, MdTabletMac } from "react-icons/md";
import { Link, Navigate, useLocation } from "react-router-dom";
import { useApp } from "../../app/AppContext";
import { PageMeta } from "../../components/PageMeta";

const viewportOptions = [
  { width: 375, label: "手机", icon: MdPhoneAndroid },
  { width: 768, label: "平板", icon: MdTabletMac },
  { width: 1440, label: "桌面", icon: MdDesktopWindows },
];

export function AdminProductPreviewPage() {
  const { session, contentScope } = useApp();
  const location = useLocation();
  const [width, setWidth] = useState(1440);

  if (!session || contentScope !== "admin") {
    return <Navigate to="/admin/login" replace state={{ from: location }} />;
  }

  return (
    <main className="product-preview-shell">
      <PageMeta
        title="产品页草稿预览｜智能错题本"
        description="管理员专用产品页草稿预览。"
        noIndex
      />
      <header className="product-preview-toolbar">
        <Link className="button button--ghost" to="/admin/product">
          <MdArrowBack aria-hidden="true" /> 返回编辑
        </Link>
        <div>
          <strong>产品页草稿预览</strong>
          <span>{width} px</span>
        </div>
        <div className="preview-viewport-switcher" role="group" aria-label="预览宽度">
          {viewportOptions.map(({ width: optionWidth, label, icon: Icon }) => (
            <button
              key={optionWidth}
              className={width === optionWidth ? "is-active" : ""}
              type="button"
              aria-pressed={width === optionWidth}
              onClick={() => setWidth(optionWidth)}
            >
              <Icon aria-hidden="true" /> {label}
            </button>
          ))}
        </div>
      </header>
      <div className="product-preview-canvas">
        <iframe
          title={`产品页${width}像素预览`}
          src="/admin/product/preview/frame"
          style={{ width }}
        />
      </div>
    </main>
  );
}
