import { useEffect, useRef, useState } from "react";
import { MdOutlineCookie, MdOutlinePublish, MdSave } from "react-icons/md";
import { useApp } from "../../app/AppContext";
import { AdminPageHeader, FormStatus } from "../../components/AdminPrimitives";
import { useUnsavedChangesGuard } from "../../components/UnsavedChangesGuard";
import {
  analyticsDraftError,
  analyticsPublicationError,
  type AnalyticsSettings,
} from "../../gateways/contentGateway";

type PendingAction = "publish" | "save" | null;

function cloneAnalyticsEditor(settings: AnalyticsSettings) {
  const draft = structuredClone(settings);
  return { baseline: structuredClone(draft), draft };
}

function sameAnalytics(left: AnalyticsSettings, right: AnalyticsSettings) {
  return left.providerName === right.providerName && left.siteId === right.siteId;
}

export function AdminAnalyticsPage() {
  const { state, content, analyticsActive, consent } = useApp();
  const [editor, setEditor] = useState(
    () => cloneAnalyticsEditor(state.site.draft.analytics),
  );
  const [status, setStatus] = useState<{ kind: "success" | "error"; message: string } | null>(null);
  const [pendingAction, setPendingAction] = useState<PendingAction>(null);
  const operationInFlight = useRef(false);
  const { draft: analytics } = editor;
  const isPending = pendingAction !== null;
  const hasUnsavedChanges = !sameAnalytics(analytics, editor.baseline);
  const privacyPublished = Boolean(state.pages.find((page) => page.slug === "privacy")?.publishedAt);

  useUnsavedChangesGuard({
    dirty: hasUnsavedChanges,
    pending: isPending,
    message: "分析配置有未保存修改或正在提交，确定离开吗？",
  });

  useEffect(() => {
    setEditor((current) => (
      !isPending && sameAnalytics(current.draft, current.baseline)
        ? cloneAnalyticsEditor(state.site.draft.analytics)
        : current
    ));
  }, [isPending, state.site.draft.analytics, state.site.updatedAt]);

  function updateAnalytics(changes: Partial<AnalyticsSettings>) {
    setEditor((current) => ({
      ...current,
      draft: { ...current.draft, ...changes },
    }));
  }

  async function save(publish: boolean) {
    if (operationInFlight.current) return;
    const submitted = structuredClone(analytics);
    try {
      const error = publish
        ? analyticsPublicationError(submitted, privacyPublished)
        : analyticsDraftError(submitted);
      if (error) throw new Error(error);
      operationInFlight.current = true;
      setPendingAction(publish ? "publish" : "save");
      await content.saveAnalyticsDraft(submitted);
      setEditor((current) => ({
        ...current,
        baseline: structuredClone(submitted),
      }));
      if (publish) await content.publishAnalytics();
      setStatus({ kind: "success", message: publish ? "分析设置已发布" : "分析设置草稿已保存" });
    } catch (error) {
      setStatus({ kind: "error", message: error instanceof Error ? error.message : "操作失败" });
    } finally {
      operationInFlight.current = false;
      setPendingAction(null);
    }
  }

  return (
    <section className="admin-page">
      <AdminPageHeader
        eyebrow="访问分析"
        title="平台无关分析适配"
        description="本项目不加载真实第三方脚本；这里只验证配置、隐私说明与用户同意的门控流程。"
      />
      <FormStatus status={status} />
      <div className="analytics-status">
        <MdOutlineCookie aria-hidden="true" />
        <div>
          <strong>{analyticsActive ? "分析适配已满足启用条件" : "当前不会发送任何访问数据"}</strong>
          <span>隐私说明：{privacyPublished ? "已发布" : "未发布"} · 用户同意：{consent === "granted" ? "已同意" : consent === "denied" ? "已拒绝" : "未选择"}</span>
        </div>
      </div>
      <form className="admin-form admin-form--narrow" aria-busy={isPending} onSubmit={(e) => { e.preventDefault(); void save(false); }}>
        <fieldset disabled={isPending}>
          <legend className="sr-only">分析服务配置</legend>
          <label>统计平台名称<input placeholder="例如：百度统计、GA4 或自建分析" value={analytics.providerName} onChange={(e) => updateAnalytics({ providerName: e.target.value })} /></label>
          <label>站点 ID<input placeholder="未填写时不启用" value={analytics.siteId} onChange={(e) => updateAnalytics({ siteId: e.target.value })} /></label>
          <p className="helper-text">正式接入时由 `AnalyticsGateway` 适配具体平台；同意前不得注入供应商脚本。</p>
          <div className="sticky-actions">
            <button className="button button--ghost" type="submit"><MdSave /> {pendingAction === "save" ? "保存中…" : "保存草稿"}</button>
            <button className="button button--primary" type="button" onClick={() => void save(true)}><MdOutlinePublish /> {pendingAction === "publish" ? "发布中…" : "保存并发布"}</button>
          </div>
        </fieldset>
      </form>
    </section>
  );
}
