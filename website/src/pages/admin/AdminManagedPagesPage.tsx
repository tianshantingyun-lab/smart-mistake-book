import { useEffect, useState } from "react";
import {
  MdOpenInNew,
  MdOutlineArchive,
  MdOutlinePublish,
  MdSave,
} from "react-icons/md";
import { Link } from "react-router-dom";
import { useApp } from "../../app/AppContext";
import { AdminPageHeader, FormStatus } from "../../components/AdminPrimitives";
import { MarkdownContent } from "../../components/MarkdownContent";
import { useUnsavedChangesGuard } from "../../components/UnsavedChangesGuard";
import type { ManagedPage, ManagedPageContent } from "../../types";

type PendingAction = "save" | "publish" | "archive" | null;

const discardChangesMessage = "当前页面有未保存的修改。切换页面会放弃这些修改，确定继续吗？";

function clonePageEditor(page: ManagedPage) {
  const draft = structuredClone(page.draft);
  return { draft, baseline: structuredClone(draft) };
}

function samePageContent(left: ManagedPageContent, right: ManagedPageContent) {
  return (
    left.title === right.title
    && left.seoTitle === right.seoTitle
    && left.summary === right.summary
    && left.seoDescription === right.seoDescription
    && left.body === right.body
  );
}

export function AdminManagedPagesPage() {
  const { state, content } = useApp();
  const [selectedId, setSelectedId] = useState(state.pages[0]?.id ?? "");
  const selected = state.pages.find((page) => page.id === selectedId) ?? state.pages[0];
  const [editor, setEditor] = useState(() => clonePageEditor(selected));
  const [status, setStatus] = useState<{ kind: "success" | "error"; message: string } | null>(null);
  const [pendingAction, setPendingAction] = useState<PendingAction>(null);
  const { draft } = editor;
  const hasUnsavedChanges = !samePageContent(draft, editor.baseline);
  const isPending = pendingAction !== null;

  useUnsavedChangesGuard({
    dirty: hasUnsavedChanges,
    pending: isPending,
    message: "当前静态页面有未保存修改或正在提交，确定离开吗？",
  });

  useEffect(() => {
    setEditor((current) => (
      samePageContent(current.draft, current.baseline)
        ? clonePageEditor(selected)
        : current
    ));
  }, [selected, selected.updatedAt]);

  function updateDraft(changes: Partial<ManagedPageContent>) {
    setEditor((current) => ({
      ...current,
      draft: { ...current.draft, ...changes },
    }));
  }

  function selectPage(page: ManagedPage) {
    if (isPending || page.id === selected.id) return;
    if (hasUnsavedChanges && !window.confirm(discardChangesMessage)) return;
    setSelectedId(page.id);
    setEditor(clonePageEditor(page));
    setStatus(null);
  }

  async function submit(action: Exclude<PendingAction, null>) {
    if (isPending) return;
    if (action === "archive") {
      if (
        !window.confirm(
          "下线这个页面？公开入口会立即隐藏，现有草稿仍会保留。",
        )
      ) {
        return;
      }
      setPendingAction(action);
      try {
        await content.archivePage(selected.id);
        setStatus({ kind: "success", message: "页面已下线，草稿仍保留" });
      } catch (error) {
        setStatus({
          kind: "error",
          message: error instanceof Error ? error.message : "页面下线失败",
        });
      } finally {
        setPendingAction(null);
      }
      return;
    }
    const submitted = structuredClone(draft);
    setPendingAction(action);
    try {
      if (action === "publish") {
        await content.publishPage(selected.id, submitted);
      } else {
        await content.savePageDraft(selected.id, submitted);
      }
      setEditor((current) => ({
        ...current,
        baseline: structuredClone(submitted),
      }));
      setStatus({
        kind: "success",
        message: action === "publish" ? "页面已发布" : "页面草稿已保存",
      });
    } catch (error) {
      setStatus({ kind: "error", message: error instanceof Error ? error.message : "操作失败" });
    } finally {
      setPendingAction(null);
    }
  }

  return (
    <section className="admin-page">
      <AdminPageHeader
        eyebrow="静态页面"
        title="关于、隐私与联系"
        description="正文使用 Markdown。发布前会检查标题、摘要、正文与 SEO 信息是否完整。"
        action={selected.publishedAt ? <Link className="button button--ghost" to={`/${selected.slug}`} target="_blank" rel="noreferrer">查看公开页 <MdOpenInNew /></Link> : undefined}
      />
      <FormStatus status={status} />
      <div className="editor-layout">
        <aside className="editor-list" aria-label="页面列表">
          {state.pages.map((page) => (
            <button type="button" className={page.id === selected.id ? "is-active" : ""} disabled={isPending} key={page.id} onClick={() => selectPage(page)}>
              <strong>{page.draft.title}</strong>
              <span>{page.publishedAt ? "已发布" : "草稿"}</span>
            </button>
          ))}
        </aside>
        <div className="editor-workspace">
          <div className="admin-form">
            <div className="field-grid field-grid--2">
              <label>页面标题<input disabled={isPending} value={draft.title} onChange={(e) => updateDraft({ title: e.target.value })} /></label>
              <label>SEO 标题<input disabled={isPending} value={draft.seoTitle} onChange={(e) => updateDraft({ seoTitle: e.target.value })} /></label>
            </div>
            <label>摘要<textarea disabled={isPending} rows={2} value={draft.summary} onChange={(e) => updateDraft({ summary: e.target.value })} /></label>
            <label>SEO 描述<textarea disabled={isPending} rows={2} value={draft.seoDescription} onChange={(e) => updateDraft({ seoDescription: e.target.value })} /></label>
            <div className="markdown-editor">
              <label>Markdown 正文<textarea disabled={isPending} rows={18} value={draft.body} onChange={(e) => updateDraft({ body: e.target.value })} /></label>
              <section className="markdown-preview" aria-label="Markdown 预览">
                <span className="preview-label">实时预览</span>
                <MarkdownContent>{draft.body}</MarkdownContent>
              </section>
            </div>
          </div>
          <div className="sticky-actions">
            {selected.publishedAt && (
              <button className="button button--ghost" type="button" disabled={isPending} onClick={() => void submit("archive")}><MdOutlineArchive /> {pendingAction === "archive" ? "下线中…" : "下线公开页"}</button>
            )}
            <button className="button button--ghost" type="button" disabled={isPending} onClick={() => void submit("save")}><MdSave /> {pendingAction === "save" ? "保存中…" : "保存草稿"}</button>
            <button className="button button--primary" type="button" disabled={isPending} onClick={() => void submit("publish")}><MdOutlinePublish /> {pendingAction === "publish" ? "发布中…" : "保存并发布"}</button>
          </div>
        </div>
      </div>
    </section>
  );
}
