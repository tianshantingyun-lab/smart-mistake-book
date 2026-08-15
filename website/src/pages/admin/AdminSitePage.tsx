import { useEffect, useState } from "react";
import { MdOpenInNew, MdOutlinePublish, MdSave } from "react-icons/md";
import { Link } from "react-router-dom";
import { useApp } from "../../app/AppContext";
import {
  AdminPageHeader,
  FormStatus,
} from "../../components/AdminPrimitives";
import { useUnsavedChangesGuard } from "../../components/UnsavedChangesGuard";
import type { SiteContent } from "../../types";

type PendingAction = "save" | "publish" | null;

function cloneSiteEditor(content: SiteContent) {
  const draft = structuredClone(content);
  return { draft, baseline: structuredClone(draft) };
}

function sameSiteContent(left: SiteContent, right: SiteContent) {
  return JSON.stringify(left) === JSON.stringify(right);
}

export function AdminSitePage() {
  const { state, content } = useApp();
  const [editor, setEditor] = useState(() => cloneSiteEditor(state.site.draft));
  const [status, setStatus] = useState<{ kind: "success" | "error"; message: string } | null>(null);
  const [pendingAction, setPendingAction] = useState<PendingAction>(null);
  const { draft } = editor;
  const isPending = pendingAction !== null;
  const hasUnsavedChanges = !sameSiteContent(draft, editor.baseline);

  useUnsavedChangesGuard({
    dirty: hasUnsavedChanges,
    pending: isPending,
    message: "首页与品牌文案有未保存修改或正在提交，确定离开吗？",
  });

  useEffect(() => {
    setEditor((current) => (
      sameSiteContent(current.draft, current.baseline)
        ? cloneSiteEditor(state.site.draft)
        : current
    ));
  }, [state.site.updatedAt, state.site.draft]);

  function updateDraft(changes: Partial<SiteContent>) {
    setEditor((current) => ({
      ...current,
      draft: { ...current.draft, ...changes },
    }));
  }

  async function submit(action: Exclude<PendingAction, null>) {
    if (isPending) return;
    const submitted = structuredClone(draft);
    setPendingAction(action);
    try {
      if (action === "publish") {
        await content.publishSite(submitted);
      } else {
        await content.saveSiteDraft(submitted);
      }
      setEditor((current) => ({
        ...current,
        baseline: structuredClone(submitted),
      }));
      setStatus({
        kind: "success",
        message: action === "publish" ? "首页内容已发布" : "草稿已保存",
      });
    } catch (error) {
      setStatus({
        kind: "error",
        message: error instanceof Error ? error.message : action === "publish" ? "发布失败" : "保存失败",
      });
    } finally {
      setPendingAction(null);
    }
  }

  return (
    <section className="admin-page">
      <AdminPageHeader
        eyebrow="站点内容"
        title="首页与品牌文案"
        description="编辑草稿不会影响公开官网，只有发布后才会替换当前内容。"
        action={<Link className="button button--ghost" to="/" target="_blank" rel="noreferrer">查看官网 <MdOpenInNew /></Link>}
      />
      <FormStatus status={status} />

      <form className="admin-form" onSubmit={(event) => { event.preventDefault(); void submit("save"); }}>
        <fieldset disabled={isPending}>
          <legend>品牌</legend>
          <div className="field-grid field-grid--2">
            <label>品牌名称<input value={draft.brandName} onChange={(e) => updateDraft({ brandName: e.target.value })} /></label>
            <label>品牌短语<input value={draft.brandTagline} onChange={(e) => updateDraft({ brandTagline: e.target.value })} /></label>
          </div>
        </fieldset>
        <fieldset disabled={isPending}>
          <legend>首页首屏</legend>
          <label>主标题<textarea rows={3} value={draft.heroTitle} onChange={(e) => updateDraft({ heroTitle: e.target.value })} /></label>
          <label>简介<textarea rows={4} value={draft.heroDescription} onChange={(e) => updateDraft({ heroDescription: e.target.value })} /></label>
          <div className="field-grid field-grid--2">
            <label>主按钮文案<input value={draft.primaryActionLabel} onChange={(e) => updateDraft({ primaryActionLabel: e.target.value })} /></label>
            <label>次入口文案<input value={draft.secondaryActionLabel} onChange={(e) => updateDraft({ secondaryActionLabel: e.target.value })} /></label>
          </div>
        </fieldset>
        <fieldset disabled={isPending}>
          <legend>信任说明</legend>
          <label>标题<input value={draft.trustTitle} onChange={(e) => updateDraft({ trustTitle: e.target.value })} /></label>
          <label>正文<textarea rows={4} value={draft.trustBody} onChange={(e) => updateDraft({ trustBody: e.target.value })} /></label>
        </fieldset>
        <div className="sticky-actions">
          <button className="button button--ghost" type="submit" disabled={isPending}><MdSave /> {pendingAction === "save" ? "保存中…" : "保存草稿"}</button>
          <button className="button button--primary" type="button" disabled={isPending} onClick={() => void submit("publish")}><MdOutlinePublish /> {pendingAction === "publish" ? "发布中…" : "保存并发布"}</button>
        </div>
      </form>
    </section>
  );
}
