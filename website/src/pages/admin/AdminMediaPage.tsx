import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  MdDeleteOutline,
  MdImage,
  MdOutlineCloudUpload,
  MdSave,
  MdVisibility,
  MdVisibilityOff,
} from "react-icons/md";
import { useApp } from "../../app/AppContext";
import { AdminPageHeader, FormStatus } from "../../components/AdminPrimitives";
import { useUnsavedChangesGuard } from "../../components/UnsavedChangesGuard";
import type { MediaChanges } from "../../gateways/contentGateway";
import type { MediaAsset } from "../../types";

type FormState = { kind: "success" | "error"; message: string } | null;
type VisibilityFilter = "all" | "visible" | "hidden";
type MediaEditorState = {
  synced: MediaAsset;
  draft: MediaAsset;
};
type MediaRowActivity = {
  dirty: boolean;
  pending: boolean;
};

function mergeAssetUpdate(
  current: MediaEditorState,
  asset: MediaAsset,
): MediaEditorState {
  return {
    synced: asset,
    draft: {
      ...asset,
      label:
        current.draft.label !== current.synced.label
          ? current.draft.label
          : asset.label,
      alt:
        current.draft.alt !== current.synced.alt
          ? current.draft.alt
          : asset.alt,
      visible:
        current.draft.visible !== current.synced.visible
          ? current.draft.visible
          : asset.visible,
    },
  };
}

function changedMediaFields(
  asset: MediaAsset,
  draft: MediaAsset,
): MediaChanges {
  const changes: MediaChanges = {};
  if (asset.label !== draft.label) changes.label = draft.label;
  if (asset.alt !== draft.alt) changes.alt = draft.alt;
  if (asset.visible !== draft.visible) changes.visible = draft.visible;
  return changes;
}

function committedMediaFields(changes: MediaChanges): MediaChanges {
  return {
    ...(changes.label === undefined ? {} : { label: changes.label.trim() }),
    ...(changes.alt === undefined ? {} : { alt: changes.alt.trim() }),
    ...(changes.visible === undefined ? {} : { visible: changes.visible }),
  };
}

function advanceSavedFields(
  current: MediaEditorState,
  submitted: MediaChanges,
): MediaEditorState {
  const committed = committedMediaFields(submitted);
  const nextDraft = { ...current.draft };

  if (
    submitted.label !== undefined &&
    current.draft.label === submitted.label
  ) {
    nextDraft.label = committed.label!;
  }
  if (submitted.alt !== undefined && current.draft.alt === submitted.alt) {
    nextDraft.alt = committed.alt!;
  }
  if (
    submitted.visible !== undefined &&
    current.draft.visible === submitted.visible
  ) {
    nextDraft.visible = committed.visible!;
  }

  return {
    synced: { ...current.synced, ...committed },
    draft: nextDraft,
  };
}

function MediaRow({
  asset,
  hidden,
  onActivityChange,
  onStatus,
}: {
  asset: MediaAsset;
  hidden: boolean;
  onActivityChange: (id: string, activity: MediaRowActivity | null) => void;
  onStatus: (status: FormState) => void;
}) {
  const { content } = useApp();
  const [editor, setEditor] = useState<MediaEditorState>({
    synced: asset,
    draft: asset,
  });
  const [saving, setSaving] = useState(false);
  const [deleting, setDeleting] = useState(false);
  const { draft } = editor;
  const dirty = Object.keys(changedMediaFields(editor.synced, draft)).length > 0;
  const pending = saving || deleting;

  useEffect(() => {
    setEditor((current) => mergeAssetUpdate(current, asset));
  }, [asset]);

  useEffect(() => {
    onActivityChange(
      asset.id,
      dirty || pending ? { dirty, pending } : null,
    );
  }, [asset.id, dirty, onActivityChange, pending]);

  useEffect(
    () => () => onActivityChange(asset.id, null),
    [asset.id, onActivityChange],
  );

  function updateDraft(changes: MediaChanges) {
    setEditor((current) => ({
      ...current,
      draft: { ...current.draft, ...changes },
    }));
  }

  async function save() {
    if (saving || deleting) return;
    if (!draft.label.trim() || !draft.alt.trim()) {
      onStatus({ kind: "error", message: "资源名称与替代文本均不能为空" });
      return;
    }
    const savedDraft = draft;
    const submittedChanges = changedMediaFields(editor.synced, savedDraft);
    if (!Object.keys(submittedChanges).length) return;
    setSaving(true);
    try {
      await content.updateMedia(asset.id, submittedChanges);
      setEditor((current) =>
        advanceSavedFields(current, submittedChanges),
      );
      onStatus({
        kind: "success",
        message: `“${savedDraft.label.trim()}”已保存`,
      });
    } catch (error) {
      onStatus({
        kind: "error",
        message: error instanceof Error ? error.message : "媒体保存失败",
      });
    } finally {
      setSaving(false);
    }
  }

  async function remove() {
    if (saving || deleting) return;
    if (!window.confirm(`永久删除“${asset.label}”？此操作不能撤销。`)) return;
    setDeleting(true);
    try {
      await content.deleteMedia(asset.id);
      onStatus({ kind: "success", message: `“${asset.label}”已从媒体库删除` });
    } catch (error) {
      onStatus({
        kind: "error",
        message: error instanceof Error ? error.message : "媒体删除失败",
      });
    } finally {
      setDeleting(false);
    }
  }

  return (
    <article className="media-row" hidden={hidden}>
      <div className="media-thumb">
        {draft.src ? <img src={draft.src} alt="" /> : <MdImage aria-hidden="true" />}
      </div>
      <div className="media-row__content">
        <div className="media-row__meta">
          <span className="status-tag">{asset.bundled ? "内置资源" : "上传资源"}</span>
          {dirty && <span className="status-tag status-tag--draft">有未保存修改</span>}
        </div>
        <div className="media-row__fields">
          <label>
            名称
            <input
              value={draft.label}
              onChange={(event) => updateDraft({ label: event.target.value })}
            />
          </label>
          <label>
            替代文本
            <input
              value={draft.alt}
              onChange={(event) => updateDraft({ alt: event.target.value })}
            />
          </label>
        </div>
      </div>
      <div className="media-row__actions">
        <button
          className="icon-button"
          type="button"
          title={draft.visible ? "在官网隐藏" : "在官网显示"}
          aria-label={draft.visible ? `隐藏${draft.label}` : `显示${draft.label}`}
          disabled={saving || deleting}
          onClick={() => updateDraft({ visible: !draft.visible })}
        >
          {draft.visible ? <MdVisibility aria-hidden="true" /> : <MdVisibilityOff aria-hidden="true" />}
        </button>
        {!asset.bundled && (
          <button
            className="icon-button icon-button--danger"
            type="button"
            aria-label={`删除${asset.label}`}
            title={deleting ? "删除中…" : "永久删除上传资源"}
            disabled={saving || deleting}
            onClick={() => void remove()}
          >
            <MdDeleteOutline aria-hidden="true" />
          </button>
        )}
        <button
          className="button button--ghost"
          type="button"
          disabled={!dirty || saving || deleting}
          onClick={() => void save()}
        >
          <MdSave aria-hidden="true" /> {saving ? "保存中…" : "保存修改"}
        </button>
      </div>
    </article>
  );
}

export function AdminMediaPage() {
  const { state, content } = useApp();
  const [file, setFile] = useState<File | null>(null);
  const [label, setLabel] = useState("");
  const [alt, setAlt] = useState("");
  const [query, setQuery] = useState("");
  const [visibility, setVisibility] = useState<VisibilityFilter>("all");
  const [status, setStatus] = useState<FormState>(null);
  const [uploading, setUploading] = useState(false);
  const [rowActivity, setRowActivity] = useState<Map<string, MediaRowActivity>>(
    () => new Map(),
  );
  const uploadInFlightRef = useRef(false);

  const onRowActivityChange = useCallback(
    (id: string, activity: MediaRowActivity | null) => {
      setRowActivity((current) => {
        const previous = current.get(id);
        if (
          (!activity && !previous)
          || (
            activity
            && previous?.dirty === activity.dirty
            && previous.pending === activity.pending
          )
        ) {
          return current;
        }
        const next = new Map(current);
        if (activity) next.set(id, activity);
        else next.delete(id);
        return next;
      });
    },
    [],
  );

  const hasDirtyRow = useMemo(
    () => Array.from(rowActivity.values()).some((activity) => activity.dirty),
    [rowActivity],
  );
  const hasPendingRow = useMemo(
    () => Array.from(rowActivity.values()).some((activity) => activity.pending),
    [rowActivity],
  );
  const uploadDirty = file !== null || label.length > 0 || alt.length > 0;

  useUnsavedChangesGuard({
    dirty: uploadDirty || hasDirtyRow,
    pending: uploading || hasPendingRow,
    message: "媒体库有未保存修改或正在处理的操作，确定离开吗？",
  });

  const filteredMedia = useMemo(() => {
    const normalizedQuery = query.trim().toLocaleLowerCase();
    return state.media.filter((asset) => {
      const matchesQuery =
        !normalizedQuery ||
        asset.label.toLocaleLowerCase().includes(normalizedQuery) ||
        asset.alt.toLocaleLowerCase().includes(normalizedQuery);
      const matchesVisibility =
        visibility === "all" ||
        (visibility === "visible" ? asset.visible : !asset.visible);
      return matchesQuery && matchesVisibility;
    });
  }, [query, state.media, visibility]);
  const filteredMediaIds = useMemo(
    () => new Set(filteredMedia.map((asset) => asset.id)),
    [filteredMedia],
  );

  return (
    <section className="admin-page">
      <AdminPageHeader
        eyebrow="媒体资源"
        title="产品图片与品牌素材"
        description="内置 App 素材可以隐藏，上传素材可以维护或安全删除；产品页引用中的图片不能直接删除。"
      />
      <FormStatus status={status} />

      <form
        className="upload-panel"
        aria-busy={uploading}
        onSubmit={async (event) => {
          event.preventDefault();
          if (uploadInFlightRef.current) return;
          const form = event.currentTarget;
          if (!file || !label.trim() || !alt.trim()) {
            setStatus({ kind: "error", message: "请选择图片，并填写名称与替代文本" });
            return;
          }
          uploadInFlightRef.current = true;
          setUploading(true);
          try {
            await content.uploadMedia(file, {
              label: label.trim(),
              alt: alt.trim(),
              role: "content",
            });
            setFile(null);
            setLabel("");
            setAlt("");
            const input = form.elements.namedItem("media-file") as HTMLInputElement;
            input.value = "";
            setStatus({ kind: "success", message: "图片已保存到当前浏览器" });
          } catch (error) {
            setStatus({
              kind: "error",
              message: error instanceof Error ? error.message : "上传失败",
            });
          } finally {
            uploadInFlightRef.current = false;
            setUploading(false);
          }
        }}
      >
        <div>
          <MdOutlineCloudUpload aria-hidden="true" />
          <div>
            <strong>上传图片</strong>
            <span>PNG、JPEG 或 WebP，单张不超过 5 MB</span>
          </div>
        </div>
        <div className="field-grid field-grid--3">
          <label>
            图片文件
            <input
              name="media-file"
              type="file"
              accept="image/png,image/jpeg,image/webp"
              disabled={uploading}
              required
              onChange={(event) => setFile(event.target.files?.[0] ?? null)}
            />
          </label>
          <label>
            资源名称
            <input disabled={uploading} required value={label} onChange={(event) => setLabel(event.target.value)} />
          </label>
          <label>
            替代文本
            <input disabled={uploading} required value={alt} onChange={(event) => setAlt(event.target.value)} />
          </label>
        </div>
        <button className="button button--primary" type="submit" disabled={uploading}>
          {uploading ? "保存中…" : "保存到媒体库"}
        </button>
      </form>

      <section className="admin-section">
        <div className="admin-section__heading">
          <div>
            <h2>全部资源</h2>
            <p>显示 {filteredMedia.length} / {state.media.length} 项资源</p>
          </div>
        </div>
        <div className="media-toolbar">
          <label>
            搜索媒体
            <input
              type="search"
              placeholder="按名称或替代文本搜索"
              value={query}
              onChange={(event) => setQuery(event.target.value)}
            />
          </label>
          <label>
            显示状态
            <select
              value={visibility}
              onChange={(event) => setVisibility(event.target.value as VisibilityFilter)}
            >
              <option value="all">全部</option>
              <option value="visible">仅公开显示</option>
              <option value="hidden">仅已隐藏</option>
            </select>
          </label>
        </div>
        {state.media.length > 0 && (
          <div className="media-list">
            {state.media.map((asset) => (
              <MediaRow
                key={asset.id}
                asset={asset}
                hidden={!filteredMediaIds.has(asset.id)}
                onActivityChange={onRowActivityChange}
                onStatus={setStatus}
              />
            ))}
          </div>
        )}
        {filteredMedia.length === 0 && (
          <div className="builder-empty">
            <p>没有符合当前条件的媒体。</p>
            <button
              className="button button--ghost"
              type="button"
              onClick={() => {
                setQuery("");
                setVisibility("all");
              }}
            >
              清除筛选
            </button>
          </div>
        )}
      </section>
    </section>
  );
}
