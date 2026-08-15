import {
  MdArticle,
  MdCheckCircleOutline,
  MdOutlineCollections,
  MdOutlineInventory2,
  MdOutlineRestartAlt,
} from "react-icons/md";
import { useState } from "react";
import { Link } from "react-router-dom";
import { useApp } from "../../app/AppContext";
import {
  AdminPageHeader,
  FormStatus,
} from "../../components/AdminPrimitives";
import { publishedDocs } from "../../domain";

export function AdminDashboardPage() {
  const { state, content, runtimeMode } = useApp();
  const [resetPending, setResetPending] = useState(false);
  const [resetStatus, setResetStatus] = useState<{
    kind: "success" | "error";
    message: string;
  } | null>(null);
  const isLocalDemo = runtimeMode === "local-demo";
  const released = state.releases.filter((release) => release.status === "published").length;
  const docs = publishedDocs(state.docs).length;
  const visibleMedia = state.media.filter((asset) => asset.visible).length;
  const analyticsReady = Boolean(
    state.site.published.analytics.providerName && state.site.published.analytics.siteId,
  );
  const readiness = [
    { label: "首页内容", ready: Boolean(state.site.published.heroTitle), to: "/admin/site" },
    {
      label: "产品讲解页",
      ready: state.product.status === "published" && Boolean(state.product.published),
      to: "/admin/product",
    },
    { label: "正式安装包", ready: released > 0, to: "/admin/releases" },
    { label: "使用文档", ready: docs > 0, to: "/admin/docs" },
    { label: "访问分析", ready: analyticsReady, to: "/admin/analytics" },
  ];

  return (
    <section className="admin-page">
      <AdminPageHeader
        eyebrow="概览"
        title="官网内容状态"
        description={
          isLocalDemo
            ? "只展示当前浏览器中的真实状态，不使用虚构访问量或下载数据。"
            : "展示当前远程内容服务返回的管理状态，不使用虚构访问量或下载数据。"
        }
        action={isLocalDemo ? (
          <button
            type="button"
            className="button button--ghost"
            disabled={resetPending}
            onClick={async () => {
              if (
                resetPending
                || !window.confirm(
                  "恢复初始数据？当前浏览器中的所有演示修改都会清除。",
                )
              ) {
                return;
              }
              setResetPending(true);
              setResetStatus(null);
              try {
                await content.reset();
                setResetStatus({
                  kind: "success",
                  message: "演示数据已恢复",
                });
              } catch (error) {
                setResetStatus({
                  kind: "error",
                  message:
                    error instanceof Error ? error.message : "恢复初始数据失败",
                });
              } finally {
                setResetPending(false);
              }
            }}
          >
            <MdOutlineRestartAlt aria-hidden="true" />
            {resetPending ? "正在恢复…" : "恢复初始数据"}
          </button>
        ) : undefined}
      />
      <FormStatus status={resetStatus} />

      <div className="admin-summary" aria-label="内容摘要">
        <div><MdOutlineInventory2 aria-hidden="true" /><strong>{released}</strong><span>已发布版本</span></div>
        <div><MdArticle aria-hidden="true" /><strong>{docs}</strong><span>已发布文档</span></div>
        <div><MdOutlineCollections aria-hidden="true" /><strong>{visibleMedia}</strong><span>可见媒体</span></div>
      </div>

      <section className="admin-section">
        <div className="admin-section__heading">
          <div>
            <h2>发布准备清单</h2>
            <p>内容准备好后逐项发布，公开页面会立即读取最新发布版本。</p>
          </div>
        </div>
        <ul className="readiness-list">
          {readiness.map((item) => (
            <li key={item.label}>
              <span className={item.ready ? "is-ready" : ""}>
                <MdCheckCircleOutline aria-hidden="true" />
                {item.ready ? "已准备" : "待完善"}
              </span>
              <strong>{item.label}</strong>
              <Link to={item.to}>管理</Link>
            </li>
          ))}
        </ul>
      </section>
    </section>
  );
}
