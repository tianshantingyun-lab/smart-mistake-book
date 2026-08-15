import { useMemo, useState } from "react";
import {
  MdAdd,
  MdArchive,
  MdOpenInNew,
  MdOutlinePublish,
  MdSave,
} from "react-icons/md";
import { Link } from "react-router-dom";
import { useApp } from "../../app/AppContext";
import { AdminPageHeader, FormStatus } from "../../components/AdminPrimitives";
import { MarkdownContent } from "../../components/MarkdownContent";
import { useUnsavedChangesGuard } from "../../components/UnsavedChangesGuard";
import { documentationOrderError, slugify } from "../../domain";
import type {
  DocumentationContent,
  DocumentationPage,
  PublicationState,
} from "../../types";

type PendingAction = "save" | "publish" | "archive" | null;
type DraftExitAction = "switch" | "create";

const draftExitMessages: Record<DraftExitAction, string> = {
  switch: "当前文档有未保存的修改。切换文档会放弃这些修改，确定继续吗？",
  create: "当前文档有未保存的修改。新建文档会放弃这些修改，确定继续吗？",
};

const documentationStatusLabels: Record<PublicationState, string> = {
  draft: "草稿",
  published: "已发布",
  archived: "已归档",
};

function documentationOptionLabel(document: DocumentationPage) {
  const title = document.draft.title.trim()
    || document.published.title.trim()
    || "未命名文档";
  return `${title}（${documentationStatusLabels[document.status]}）`;
}

function DocumentationParentField({
  docs,
  currentId,
  parentId,
  disabled,
  onChange,
}: {
  docs: DocumentationPage[];
  currentId: string;
  parentId: string | null;
  disabled: boolean;
  onChange: (parentId: string | null) => void;
}) {
  return (
    <label>
      上级文档
      <select
        aria-describedby="documentation-parent-help"
        disabled={disabled}
        value={parentId ?? ""}
        onChange={(event) => onChange(event.target.value || null)}
      >
        <option value="">无上级（顶层）</option>
        {docs
          .filter((doc) => doc.id !== currentId)
          .map((doc) => (
            <option key={doc.id} value={doc.id}>
              {documentationOptionLabel(doc)}
            </option>
          ))}
      </select>
      <small className="field-help" id="documentation-parent-help">
        可以先选择草稿或已归档文档编排层级；发布当前文档前，上级文档必须先发布。
      </small>
    </label>
  );
}

function newDoc(order: number): DocumentationPage {
  const empty = { title: "", slug: "", summary: "", markdown: "", parentId: null, order };
  return {
    id: crypto.randomUUID(),
    status: "draft",
    draft: structuredClone(empty),
    published: structuredClone(empty),
    updatedAt: new Date().toISOString(),
    publishedAt: null,
  };
}

function cloneEditorDocument(document: DocumentationPage) {
  const draft = structuredClone(document);
  return { draft, baseline: structuredClone(draft.draft) };
}

function sameContent(left: DocumentationContent, right: DocumentationContent) {
  return (
    left.title === right.title
    && left.slug === right.slug
    && left.summary === right.summary
    && left.markdown === right.markdown
    && left.parentId === right.parentId
    && left.order === right.order
  );
}

export function AdminDocsPage() {
  const { state, content } = useApp();
  const [selectedId, setSelectedId] = useState<string | null>(state.docs[0]?.id ?? null);
  const selected = useMemo(() => state.docs.find((doc) => doc.id === selectedId) ?? null, [selectedId, state.docs]);
  const [editor, setEditor] = useState(() => cloneEditorDocument(
    selected ?? newDoc(state.docs.length),
  ));
  const [status, setStatus] = useState<{ kind: "success" | "error"; message: string } | null>(null);
  const [pendingAction, setPendingAction] = useState<PendingAction>(null);
  const { draft } = editor;
  const hasUnsavedChanges = !sameContent(draft.draft, editor.baseline);
  const isPending = pendingAction !== null;

  useUnsavedChangesGuard({
    dirty: hasUnsavedChanges,
    pending: isPending,
    message: "当前文档有未保存的修改或正在提交。确定离开文档管理吗？",
  });

  function updateDraft(changes: Partial<DocumentationContent>) {
    setEditor((current) => ({
      ...current,
      draft: {
        ...current.draft,
        draft: { ...current.draft.draft, ...changes },
      },
    }));
  }

  function mayLeaveDraft(action: DraftExitAction) {
    return !hasUnsavedChanges || window.confirm(draftExitMessages[action]);
  }

  function selectDocument(document: DocumentationPage) {
    if (isPending || document.id === selectedId || !mayLeaveDraft("switch")) return;
    setSelectedId(document.id);
    setEditor(cloneEditorDocument(document));
    setStatus(null);
  }

  function createDocument() {
    if (isPending || !mayLeaveDraft("create")) return;
    setSelectedId(null);
    setEditor(cloneEditorDocument(newDoc(state.docs.length)));
    setStatus(null);
  }

  async function save(publish: boolean) {
    if (isPending) return;
    const normalized = structuredClone(draft);
    normalized.draft.slug ||= slugify(normalized.draft.title);
    const orderError = documentationOrderError(normalized.draft.order);
    if (orderError) {
      setStatus({ kind: "error", message: orderError });
      return;
    }
    if (!normalized.draft.title.trim() || !normalized.draft.slug.trim()) {
      setStatus({ kind: "error", message: "请填写文档标题和路径" });
      return;
    }
    setPendingAction(publish ? "publish" : "save");
    try {
      if (publish) {
        await content.publishDoc(normalized.id, normalized.draft);
      } else {
        await content.saveDocDraft(normalized.id, normalized.draft);
      }
      setSelectedId(normalized.id);
      setEditor(cloneEditorDocument(normalized));
      setStatus({ kind: "success", message: publish ? "文档已发布并进入公开搜索" : "文档草稿已保存" });
    } catch (error) {
      setStatus({ kind: "error", message: error instanceof Error ? error.message : "操作失败" });
    } finally {
      setPendingAction(null);
    }
  }

  async function archiveSelected() {
    if (!selected || selected.status !== "published" || isPending) return;
    const message = hasUnsavedChanges
      ? "当前文档有未保存的修改。归档会放弃这些修改，并立即隐藏公开入口。确定继续归档吗？"
      : "归档这篇文档？公开页面和搜索结果会立即隐藏。";
    if (!window.confirm(message)) return;

    const savedDocument = structuredClone(selected);
    setPendingAction("archive");
    try {
      await content.archiveDoc(selected.id);
      savedDocument.status = "archived";
      setEditor(cloneEditorDocument(savedDocument));
      setStatus({ kind: "success", message: "文档已归档，公开入口已隐藏" });
    } catch (error) {
      setStatus({
        kind: "error",
        message: error instanceof Error ? error.message : "归档失败",
      });
    } finally {
      setPendingAction(null);
    }
  }

  return (
    <section className="admin-page">
      <AdminPageHeader
        eyebrow="使用文档"
        title="Markdown 文档库"
        description="公开站按顺序展示已发布文档，并对标题、摘要和正文执行全文搜索。"
        action={<button className="button button--primary" type="button" disabled={isPending} onClick={createDocument}><MdAdd /> 新建文档</button>}
      />
      <FormStatus status={status} />

      <div className="editor-layout">
        <aside className="editor-list" aria-label="文档列表">
          {state.docs.length === 0 && <p className="editor-list__empty">还没有文档草稿</p>}
          {state.docs.slice().sort((a, b) => a.draft.order - b.draft.order).map((doc) => (
            <button type="button" className={doc.id === selectedId ? "is-active" : ""} disabled={isPending} key={doc.id} onClick={() => selectDocument(doc)}>
              <strong>{doc.draft.title || "未命名文档"}</strong>
              <span>{doc.status === "published" ? "已发布" : doc.status === "archived" ? "已归档" : "草稿"} · 顺序 {doc.draft.order + 1}</span>
            </button>
          ))}
        </aside>
        <div className="editor-workspace">
          <div className="admin-form">
            <div className="field-grid field-grid--2">
              <label>文档标题<input disabled={isPending} value={draft.draft.title} onChange={(event) => updateDraft({ title: event.target.value })} /></label>
              <label>URL 路径<input disabled={isPending} value={draft.draft.slug} placeholder="保存时可由标题生成" onChange={(event) => updateDraft({ slug: slugify(event.target.value) })} /></label>
            </div>
            <div className="field-grid field-grid--2">
              <DocumentationParentField
                docs={state.docs}
                currentId={draft.id}
                parentId={draft.draft.parentId}
                disabled={isPending}
                onChange={(parentId) => updateDraft({ parentId })}
              />
              <label>排序<input disabled={isPending} type="number" min={0} step={1} value={draft.draft.order} onChange={(event) => updateDraft({ order: Number(event.target.value) })} /></label>
            </div>
            <label>摘要<textarea disabled={isPending} rows={2} value={draft.draft.summary} onChange={(event) => updateDraft({ summary: event.target.value })} /></label>
            <div className="markdown-editor">
              <label>Markdown 正文<textarea disabled={isPending} rows={20} value={draft.draft.markdown} onChange={(event) => updateDraft({ markdown: event.target.value })} /></label>
              <section className="markdown-preview"><span className="preview-label">实时预览</span><MarkdownContent>{draft.draft.markdown || "文档正文将在这里预览。"}</MarkdownContent></section>
            </div>
          </div>
          <div className="sticky-actions">
            {selected?.status === "published" && selected.published.slug && <Link className="button button--ghost" to={`/docs/${selected.published.slug}`} target="_blank" rel="noreferrer">查看公开页 <MdOpenInNew /></Link>}
            {selected?.status === "published" && (
              <button className="button button--danger-ghost" type="button" disabled={isPending} onClick={() => void archiveSelected()}><MdArchive /> {pendingAction === "archive" ? "归档中…" : "归档"}</button>
            )}
            <button className="button button--ghost" type="button" disabled={isPending} onClick={() => void save(false)}><MdSave /> {pendingAction === "save" ? "保存中…" : "保存草稿"}</button>
            <button className="button button--primary" type="button" disabled={isPending} onClick={() => void save(true)}><MdOutlinePublish /> {pendingAction === "publish" ? "发布中…" : "保存并发布"}</button>
          </div>
        </div>
      </div>
    </section>
  );
}
