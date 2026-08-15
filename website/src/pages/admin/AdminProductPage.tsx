import { useEffect, useMemo, useRef, useState } from "react";
import {
  MdAdd,
  MdArrowDownward,
  MdArrowUpward,
  MdContentCopy,
  MdDeleteOutline,
  MdOutlineArchive,
  MdOutlinePublish,
  MdPreview,
  MdSave,
  MdVisibility,
  MdVisibilityOff,
} from "react-icons/md";
import { useApp } from "../../app/AppContext";
import { AdminPageHeader, FormStatus } from "../../components/AdminPrimitives";
import { useUnsavedChangesGuard } from "../../components/UnsavedChangesGuard";
import {
  createProductBlock,
  duplicateProductBlock,
  moveProductBlock,
  validateProductPage,
} from "../../domain";
import { clearProductPreview, writeProductPreview } from "../../productPreview";
import type {
  MediaAsset,
  ProductPageBlock,
  ProductPageContent,
} from "../../types";

const blockLabels: Record<ProductPageBlock["type"], string> = {
  hero: "页面首屏",
  markdown: "Markdown 富文本",
  "image-text": "左右图文",
  gallery: "截图画廊",
  steps: "步骤",
  cards: "功能卡片",
  faq: "FAQ",
  cta: "行动入口",
};

const blockTypes = Object.keys(blockLabels) as ProductPageBlock["type"][];

type PendingAction = "archive" | "publish" | "save" | null;

const pendingMessages: Record<Exclude<PendingAction, null>, string> = {
  archive: "正在归档产品页，请勿离开当前页面。",
  publish: "正在保存并发布产品页，请勿离开当前页面。",
  save: "正在保存产品页草稿，请勿离开当前页面。",
};

function sameProductContent(left: ProductPageContent, right: ProductPageContent) {
  return JSON.stringify(left) === JSON.stringify(right);
}

function MediaSelect({
  label,
  value,
  media,
  optional = false,
  onChange,
}: {
  label: string;
  value: string | null;
  media: MediaAsset[];
  optional?: boolean;
  onChange: (value: string | null) => void;
}) {
  const selected = value ? media.find((asset) => asset.id === value) : null;
  return (
    <label>
      {label}
      <select value={value ?? ""} onChange={(event) => onChange(event.target.value || null)}>
        <option value="">{optional ? "不使用图片" : "请选择图片"}</option>
        {media.map((asset) => (
          <option key={asset.id} value={asset.id}>
            {asset.label}{asset.visible ? "" : "（已隐藏）"}
          </option>
        ))}
      </select>
      {value && !selected && <small className="field-error">所选图片已不存在</small>}
      {selected && !selected.visible && <small className="field-error">所选图片当前已隐藏，不能发布</small>}
    </label>
  );
}

function ItemEditor({
  prefix,
  items,
  onChange,
}: {
  prefix: string;
  items: Array<{ id: string; title: string; body: string }>;
  onChange: (items: Array<{ id: string; title: string; body: string }>) => void;
}) {
  return (
    <div className="product-item-editor">
      {items.map((item, index) => (
        <div className="repeat-field" key={item.id}>
          <div className="repeat-field__heading">
            <strong>{prefix} {index + 1}</strong>
            <button
              className="text-button text-button--danger"
              type="button"
              onClick={() => onChange(items.filter((candidate) => candidate.id !== item.id))}
            >
              删除
            </button>
          </div>
          <label>
            标题
            <input
              value={item.title}
              onChange={(event) =>
                onChange(items.map((candidate) =>
                  candidate.id === item.id ? { ...candidate, title: event.target.value } : candidate
                ))
              }
            />
          </label>
          <label>
            正文
            <textarea
              rows={3}
              value={item.body}
              onChange={(event) =>
                onChange(items.map((candidate) =>
                  candidate.id === item.id ? { ...candidate, body: event.target.value } : candidate
                ))
              }
            />
          </label>
        </div>
      ))}
      <button
        className="button button--ghost"
        type="button"
        onClick={() =>
          onChange([...items, { id: crypto.randomUUID(), title: "", body: "" }])
        }
      >
        <MdAdd aria-hidden="true" /> 添加{prefix}
      </button>
    </div>
  );
}

function ProductBlockFields({
  block,
  media,
  onChange,
}: {
  block: ProductPageBlock;
  media: MediaAsset[];
  onChange: (block: ProductPageBlock) => void;
}) {
  switch (block.type) {
    case "hero":
      return (
        <>
          <label>主标题<input value={block.heading} onChange={(event) => onChange({ ...block, heading: event.target.value })} /></label>
          <label>简介<textarea rows={4} value={block.lead} onChange={(event) => onChange({ ...block, lead: event.target.value })} /></label>
          <MediaSelect label="首屏图片（可选）" value={block.mediaId} media={media} optional onChange={(mediaId) => onChange({ ...block, mediaId })} />
        </>
      );
    case "markdown":
      return (
        <>
          <label>区块标题（可选）<input value={block.title} onChange={(event) => onChange({ ...block, title: event.target.value })} /></label>
          <label>Markdown 正文<textarea rows={9} value={block.markdown} onChange={(event) => onChange({ ...block, markdown: event.target.value })} /></label>
        </>
      );
    case "image-text":
      return (
        <>
          <div className="field-grid field-grid--2">
            <label>短标签（可选）<input value={block.eyebrow} onChange={(event) => onChange({ ...block, eyebrow: event.target.value })} /></label>
            <label>图片位置
              <select value={block.mediaSide} onChange={(event) => onChange({ ...block, mediaSide: event.target.value as "left" | "right" })}>
                <option value="right">右侧</option>
                <option value="left">左侧</option>
              </select>
            </label>
          </div>
          <label>标题<input value={block.title} onChange={(event) => onChange({ ...block, title: event.target.value })} /></label>
          <label>Markdown 正文<textarea rows={7} value={block.markdown} onChange={(event) => onChange({ ...block, markdown: event.target.value })} /></label>
          <MediaSelect label="图片" value={block.mediaId} media={media} onChange={(mediaId) => onChange({ ...block, mediaId })} />
        </>
      );
    case "gallery":
      return (
        <>
          <div className="field-grid field-grid--2">
            <label>标题<input value={block.title} onChange={(event) => onChange({ ...block, title: event.target.value })} /></label>
            <label>列数
              <select value={block.columns} onChange={(event) => onChange({ ...block, columns: Number(event.target.value) as 2 | 3 })}>
                <option value={2}>两列</option>
                <option value={3}>三列</option>
              </select>
            </label>
          </div>
          <label>说明（可选）<textarea rows={3} value={block.summary} onChange={(event) => onChange({ ...block, summary: event.target.value })} /></label>
          <fieldset className="media-checklist">
            <legend>选择图片</legend>
            {media.length ? media.map((asset) => (
              <label key={asset.id}>
                <input
                  type="checkbox"
                  checked={block.mediaIds.includes(asset.id)}
                  onChange={(event) =>
                    onChange({
                      ...block,
                      mediaIds: event.target.checked
                        ? [...block.mediaIds, asset.id]
                        : block.mediaIds.filter((id) => id !== asset.id),
                    })
                  }
                />
                <span>{asset.label}{asset.visible ? "" : "（已隐藏）"}</span>
              </label>
            )) : <p>媒体库还没有可选图片。</p>}
          </fieldset>
        </>
      );
    case "steps":
    case "cards":
      return (
        <>
          <div className="field-grid field-grid--2">
            <label>标题<input value={block.title} onChange={(event) => onChange({ ...block, title: event.target.value })} /></label>
            {block.type === "cards" && (
              <label>列数
                <select value={block.columns} onChange={(event) => onChange({ ...block, columns: Number(event.target.value) as 2 | 3 })}>
                  <option value={2}>两列</option>
                  <option value={3}>三列</option>
                </select>
              </label>
            )}
          </div>
          <label>说明（可选）<textarea rows={3} value={block.summary} onChange={(event) => onChange({ ...block, summary: event.target.value })} /></label>
          <ItemEditor
            prefix={block.type === "steps" ? "步骤" : "卡片"}
            items={block.items}
            onChange={(items) => onChange({ ...block, items })}
          />
        </>
      );
    case "faq":
      return (
        <>
          <label>标题<input value={block.title} onChange={(event) => onChange({ ...block, title: event.target.value })} /></label>
          <div className="product-item-editor">
            {block.items.map((item, index) => (
              <div className="repeat-field" key={item.id}>
                <div className="repeat-field__heading">
                  <strong>问题 {index + 1}</strong>
                  <button className="text-button text-button--danger" type="button" onClick={() => onChange({ ...block, items: block.items.filter((candidate) => candidate.id !== item.id) })}>删除</button>
                </div>
                <label>问题<input value={item.question} onChange={(event) => onChange({ ...block, items: block.items.map((candidate) => candidate.id === item.id ? { ...candidate, question: event.target.value } : candidate) })} /></label>
                <label>回答<textarea rows={3} value={item.answer} onChange={(event) => onChange({ ...block, items: block.items.map((candidate) => candidate.id === item.id ? { ...candidate, answer: event.target.value } : candidate) })} /></label>
              </div>
            ))}
            <button className="button button--ghost" type="button" onClick={() => onChange({ ...block, items: [...block.items, { id: crypto.randomUUID(), question: "", answer: "" }] })}>
              <MdAdd aria-hidden="true" /> 添加问题
            </button>
          </div>
        </>
      );
    case "cta":
      return (
        <>
          <label>标题<input value={block.title} onChange={(event) => onChange({ ...block, title: event.target.value })} /></label>
          <label>说明（可选）<textarea rows={3} value={block.body} onChange={(event) => onChange({ ...block, body: event.target.value })} /></label>
          <div className="field-grid field-grid--2">
            <label>按钮文案<input value={block.label} onChange={(event) => onChange({ ...block, label: event.target.value })} /></label>
            <label>链接<input placeholder="/download 或 https://…" value={block.href} onChange={(event) => onChange({ ...block, href: event.target.value })} /></label>
          </div>
        </>
      );
  }
}

function ProductBlockEditor({
  block,
  index,
  total,
  media,
  onChange,
  onMove,
  onDuplicate,
  onDelete,
}: {
  block: ProductPageBlock;
  index: number;
  total: number;
  media: MediaAsset[];
  onChange: (block: ProductPageBlock) => void;
  onMove: (direction: -1 | 1) => void;
  onDuplicate: () => void;
  onDelete: () => void;
}) {
  return (
    <article className={`product-block-editor ${block.enabled ? "" : "is-disabled"}`}>
      <header>
        <div>
          <span>区块 {index + 1}</span>
          <h3>{blockLabels[block.type]}</h3>
        </div>
        <div className="product-block-actions">
          <button className="icon-button" type="button" aria-label={`上移${blockLabels[block.type]}`} disabled={index === 0} onClick={() => onMove(-1)}><MdArrowUpward /></button>
          <button className="icon-button" type="button" aria-label={`下移${blockLabels[block.type]}`} disabled={index === total - 1} onClick={() => onMove(1)}><MdArrowDownward /></button>
          <button className="icon-button" type="button" aria-label={`复制${blockLabels[block.type]}`} onClick={onDuplicate}><MdContentCopy /></button>
          <button className="icon-button icon-button--danger" type="button" aria-label={`删除${blockLabels[block.type]}`} onClick={onDelete}><MdDeleteOutline /></button>
        </div>
      </header>
      <button
        className="block-visibility"
        type="button"
        aria-pressed={block.enabled}
        onClick={() => onChange({ ...block, enabled: !block.enabled })}
      >
        {block.enabled ? <MdVisibility aria-hidden="true" /> : <MdVisibilityOff aria-hidden="true" />}
        {block.enabled ? "公开时显示" : "当前已隐藏"}
      </button>
      <div className="product-block-fields">
        <ProductBlockFields block={block} media={media} onChange={onChange} />
      </div>
    </article>
  );
}

export function AdminProductPage() {
  const { state, content } = useApp();
  const [draft, setDraft] = useState<ProductPageContent>(() =>
    structuredClone(state.product.draft),
  );
  const [baseline, setBaseline] = useState<ProductPageContent>(() =>
    structuredClone(state.product.draft),
  );
  const [blockType, setBlockType] = useState<ProductPageBlock["type"]>("hero");
  const [status, setStatus] = useState<{ kind: "success" | "error"; message: string } | null>(null);
  const [pendingAction, setPendingAction] = useState<PendingAction>(null);
  const operationInFlight = useRef(false);
  const lastAcceptedRemoteUpdate = useRef(state.product.updatedAt);

  const validationErrors = useMemo(
    () => validateProductPage(draft, state.media),
    [draft, state.media],
  );
  const hasUnsavedChanges = useMemo(
    () => !sameProductContent(draft, baseline),
    [baseline, draft],
  );
  const isPending = pendingAction !== null;

  useUnsavedChangesGuard({
    dirty: hasUnsavedChanges,
    pending: isPending,
    message: "当前产品页有未保存的修改或正在提交。确定离开产品页管理吗？",
  });

  useEffect(() => {
    if (
      lastAcceptedRemoteUpdate.current === state.product.updatedAt
      || hasUnsavedChanges
      || isPending
    ) {
      return;
    }
    const incoming = structuredClone(state.product.draft);
    lastAcceptedRemoteUpdate.current = state.product.updatedAt;
    setDraft(incoming);
    setBaseline(structuredClone(incoming));
  }, [
    hasUnsavedChanges,
    isPending,
    state.product.draft,
    state.product.updatedAt,
  ]);

  function updateBlock(next: ProductPageBlock) {
    setDraft((current) => ({
      ...current,
      blocks: current.blocks.map((block) => block.id === next.id ? next : block),
    }));
  }

  function beginAction(action: Exclude<PendingAction, null>) {
    if (operationInFlight.current) return false;
    operationInFlight.current = true;
    setPendingAction(action);
    setStatus(null);
    return true;
  }

  function finishAction() {
    operationInFlight.current = false;
    setPendingAction(null);
  }

  async function saveDraft() {
    if (!beginAction("save")) return;
    const snapshot = structuredClone(draft);
    try {
      await content.saveProductDraft(snapshot);
      setBaseline(structuredClone(snapshot));
      setStatus({ kind: "success", message: "产品页草稿已保存" });
    } catch (error) {
      setStatus({ kind: "error", message: error instanceof Error ? error.message : "保存失败" });
    } finally {
      finishAction();
    }
  }

  async function publishProduct() {
    if (validationErrors.length > 0 || !beginAction("publish")) return;
    const snapshot = structuredClone(draft);
    try {
      await content.saveProductDraft(snapshot);
      setBaseline(structuredClone(snapshot));
      await content.publishProduct();
      setStatus({ kind: "success", message: "产品讲解页已整页发布" });
    } catch (error) {
      setStatus({
        kind: "error",
        message: error instanceof Error ? error.message : "发布失败",
      });
    } finally {
      finishAction();
    }
  }

  async function archiveProduct() {
    if (operationInFlight.current) return;
    const confirmation = hasUnsavedChanges
      ? "当前产品页有未保存的修改。归档会放弃这些修改，并立即隐藏公开入口。确定继续归档吗？"
      : "归档产品讲解页？公开入口会立即隐藏。";
    if (!window.confirm(confirmation)) return;

    if (!beginAction("archive")) return;
    const savedDraft = structuredClone(state.product.draft);
    try {
      await content.archiveProduct();
      setDraft(savedDraft);
      setBaseline(structuredClone(savedDraft));
      setStatus({ kind: "success", message: "产品页已归档，公开入口已隐藏" });
    } catch (error) {
      setStatus({
        kind: "error",
        message: error instanceof Error ? error.message : "归档失败",
      });
    } finally {
      finishAction();
    }
  }

  return (
    <section className="admin-page">
      <AdminPageHeader
        eyebrow="产品页面"
        title="模块化产品讲解页"
        description="当前没有预设功能讲解。先在草稿中编排区块，确认整页预览后再一次发布。"
      />
      <FormStatus status={status} />
      {pendingAction && (
        <p className="form-status" role="status">
          {pendingMessages[pendingAction]}
        </p>
      )}

      <fieldset
        aria-busy={isPending}
        disabled={isPending}
        style={{ border: 0, margin: 0, minInlineSize: 0, padding: 0 }}
      >
        <legend className="sr-only">产品页可编辑内容</legend>
        <section className="admin-section product-page-settings">
          <div className="admin-section__heading">
            <div>
              <h2>页面设置</h2>
              <p>固定公开地址为 /product，未发布时按页面未找到处理。</p>
            </div>
            <span className={`status-tag status-tag--${state.product.status}`}>
              {state.product.status === "published" ? "已发布" : state.product.status === "archived" ? "已归档" : "草稿"}
            </span>
          </div>
          <div className="field-grid field-grid--2">
            <label>导航名称<input value={draft.navLabel} onChange={(event) => setDraft({ ...draft, navLabel: event.target.value })} /></label>
            <label>SEO 标题<input value={draft.seoTitle} onChange={(event) => setDraft({ ...draft, seoTitle: event.target.value })} /></label>
          </div>
          <label>SEO 描述<textarea rows={3} value={draft.seoDescription} onChange={(event) => setDraft({ ...draft, seoDescription: event.target.value })} /></label>
          <div className="homepage-entry-settings">
            <label className="switch-field">
              <input type="checkbox" checked={draft.showHomepageEntry} onChange={(event) => setDraft({ ...draft, showHomepageEntry: event.target.checked })} />
              <span>发布后在首页显示轻量入口</span>
            </label>
            <label>首页入口文案<input disabled={!draft.showHomepageEntry} value={draft.homepageEntryLabel} onChange={(event) => setDraft({ ...draft, homepageEntryLabel: event.target.value })} /></label>
          </div>
        </section>

        <section className="admin-section product-block-builder">
          <div className="admin-section__heading">
            <div>
              <h2>内容区块</h2>
              <p>区块样式由官网统一控制，可自由增删、复制、隐藏和调整顺序。</p>
            </div>
            <div className="add-block-control">
              <label className="sr-only" htmlFor="new-block-type">新区块类型</label>
              <select id="new-block-type" value={blockType} onChange={(event) => setBlockType(event.target.value as ProductPageBlock["type"])}>
                {blockTypes.map((type) => <option key={type} value={type}>{blockLabels[type]}</option>)}
              </select>
              <button className="button button--primary" type="button" onClick={() => setDraft({ ...draft, blocks: [...draft.blocks, createProductBlock(blockType)] })}>
                <MdAdd aria-hidden="true" /> 添加区块
              </button>
            </div>
          </div>

          {draft.blocks.length ? (
            <div className="product-block-list">
              {draft.blocks.map((block, index) => (
                <ProductBlockEditor
                  key={block.id}
                  block={block}
                  index={index}
                  total={draft.blocks.length}
                  media={state.media}
                  onChange={updateBlock}
                  onMove={(direction) => setDraft({ ...draft, blocks: moveProductBlock(draft.blocks, block.id, direction) })}
                  onDuplicate={() => {
                    const next = [...draft.blocks];
                    next.splice(index + 1, 0, duplicateProductBlock(block));
                    setDraft({ ...draft, blocks: next });
                  }}
                  onDelete={() => {
                    if (window.confirm(`删除“${blockLabels[block.type]}”区块？`)) {
                      setDraft({ ...draft, blocks: draft.blocks.filter((candidate) => candidate.id !== block.id) });
                    }
                  }}
                />
              ))}
            </div>
          ) : (
            <div className="builder-empty">
              <p>产品讲解页当前为空白草稿。</p>
              <span>功能内容确定后，从“页面首屏”开始添加区块。</span>
            </div>
          )}
        </section>
      </fieldset>

      {validationErrors.length > 0 && (
        <section className="validation-panel" aria-label="发布前检查">
          <h2>发布前还需完善</h2>
          <ul>{validationErrors.map((error) => <li key={error}>{error}</li>)}</ul>
        </section>
      )}

      <div className="sticky-actions">
        {state.product.status === "published" && (
          <button
            className="button button--danger-ghost"
            type="button"
            disabled={isPending}
            onClick={() => void archiveProduct()}
          >
            <MdOutlineArchive aria-hidden="true" /> {pendingAction === "archive" ? "归档中…" : "归档"}
          </button>
        )}
        <button className="button button--ghost" type="button" disabled={isPending} onClick={() => void saveDraft()}>
          <MdSave aria-hidden="true" /> {pendingAction === "save" ? "保存中…" : "保存草稿"}
        </button>
        <button
          className="button button--ghost"
          type="button"
          disabled={isPending}
          onClick={() => {
            try {
              writeProductPreview(draft);
            } catch {
              clearProductPreview();
              setStatus({
                kind: "error",
                message: "浏览器无法保存本次预览内容，请检查隐私或存储设置后重试",
              });
              return;
            }
            let preview: Window | null;
            try {
              preview = window.open("/admin/product/preview", "_blank");
            } catch {
              clearProductPreview();
              setStatus({
                kind: "error",
                message: "浏览器无法打开预览窗口，请检查弹窗设置后重试",
              });
              return;
            }
            if (!preview) {
              clearProductPreview();
              setStatus({ kind: "error", message: "浏览器阻止了预览窗口，请允许打开新标签页" });
              return;
            }
            try {
              preview.opener = null;
            } catch {
              try {
                preview.close();
              } catch {
                // The browser may also deny control of the newly opened window.
              }
              setStatus({
                kind: "error",
                message: "浏览器无法安全打开预览窗口，请重试或检查弹窗设置",
              });
              return;
            } finally {
              clearProductPreview();
            }
          }}
        >
          <MdPreview aria-hidden="true" /> 整页预览
        </button>
        <button
          className="button button--primary"
          type="button"
          disabled={isPending || validationErrors.length > 0}
          onClick={() => void publishProduct()}
        >
          <MdOutlinePublish aria-hidden="true" /> {pendingAction === "publish" ? "保存并发布中…" : "保存并发布"}
        </button>
      </div>
    </section>
  );
}
