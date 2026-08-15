import { useEffect, useMemo, useRef, useState } from "react";
import {
  MdAdd,
  MdArchive,
  MdContentCopy,
  MdOutlineCloudUpload,
  MdOutlinePublish,
  MdSave,
} from "react-icons/md";
import { useApp } from "../../app/AppContext";
import { AdminPageHeader, FormStatus } from "../../components/AdminPrimitives";
import { MarkdownContent } from "../../components/MarkdownContent";
import { useUnsavedChangesGuard } from "../../components/UnsavedChangesGuard";
import { formatFileSize, releaseDraftError } from "../../domain";
import type { Release } from "../../types";

function newRelease(): Release {
  return {
    id: crypto.randomUUID(),
    channel: "stable",
    versionName: "",
    versionCode: 1,
    minAndroid: 6,
    releaseNotes: "",
    status: "draft",
    package: null,
    createdAt: new Date().toISOString(),
    publishedAt: null,
  };
}

function copyReleaseAsDraft(release: Release): Release {
  return {
    ...structuredClone(release),
    id: crypto.randomUUID(),
    versionName: "",
    versionCode: release.versionCode + 1,
    status: "draft",
    package: null,
    createdAt: new Date().toISOString(),
    publishedAt: null,
  };
}

type ReleaseMetadata = Pick<
  Release,
  "channel" | "versionName" | "versionCode" | "minAndroid" | "releaseNotes"
>;

function releaseMetadata(release: Release): ReleaseMetadata {
  return {
    channel: release.channel,
    versionName: release.versionName,
    versionCode: release.versionCode,
    minAndroid: release.minAndroid,
    releaseNotes: release.releaseNotes,
  };
}

function hasSameMetadata(left: ReleaseMetadata, right: ReleaseMetadata) {
  return left.channel === right.channel
    && left.versionName === right.versionName
    && left.versionCode === right.versionCode
    && left.minAndroid === right.minAndroid
    && left.releaseNotes === right.releaseNotes;
}

export function AdminReleasesPage() {
  const { state, content } = useApp();
  const [selectedId, setSelectedId] = useState<string | null>(state.releases[0]?.id ?? null);
  const selected = useMemo(
    () => state.releases.find((release) => release.id === selectedId) ?? null,
    [selectedId, state.releases],
  );
  const [draft, setDraft] = useState<Release>(() => selected ? structuredClone(selected) : newRelease());
  const [file, setFile] = useState<File | null>(null);
  const [status, setStatus] = useState<{ kind: "success" | "error"; message: string } | null>(null);
  const [pending, setPending] = useState(false);
  const operationInFlightRef = useRef(false);
  const fileInputRef = useRef<HTMLInputElement>(null);
  const metadataBaselineRef = useRef<ReleaseMetadata>(releaseMetadata(draft));
  const syncedSelectedRef = useRef<Release | null>(selected);
  const preserveDraftAfterFailureRef = useRef(false);
  const locked = selected !== null && selected.status !== "draft";
  const hasUnsavedChanges = file !== null
    || !hasSameMetadata(releaseMetadata(draft), metadataBaselineRef.current);

  useUnsavedChangesGuard({
    dirty: hasUnsavedChanges,
    pending,
    message: "当前版本有未保存更改或正在处理的操作，确定离开吗？",
  });

  function clearSelectedFile() {
    setFile(null);
    if (fileInputRef.current) fileInputRef.current.value = "";
  }

  useEffect(() => {
    if (pending) return;
    if (preserveDraftAfterFailureRef.current) {
      preserveDraftAfterFailureRef.current = false;
      syncedSelectedRef.current = selected;
      return;
    }
    if (selected === syncedSelectedRef.current) return;
    syncedSelectedRef.current = selected;
    if (!selected) return;
    const next = structuredClone(selected);
    setDraft(next);
    metadataBaselineRef.current = releaseMetadata(next);
  }, [pending, selected]);

  function changeEditor(change: () => void) {
    if (pending || operationInFlightRef.current) return;
    if (
      hasUnsavedChanges
      && !window.confirm("当前版本有未保存更改，确定放弃这些更改吗？")
    ) {
      return;
    }
    change();
    clearSelectedFile();
  }

  async function save(publish: boolean) {
    if (pending || operationInFlightRef.current) return;
    if (locked) {
      setStatus({ kind: "error", message: "已发布或归档版本不可直接修改，请复制为新草稿" });
      return;
    }
    const validationError = releaseDraftError(draft);
    if (validationError) {
      setStatus({ kind: "error", message: validationError });
      return;
    }
    operationInFlightRef.current = true;
    setPending(true);
    try {
      await content.saveReleaseDraft(draft);
      if (file) await content.attachReleasePackage(draft.id, file);
      if (publish) await content.publishRelease(draft.id);
      setSelectedId(draft.id);
      metadataBaselineRef.current = releaseMetadata(draft);
      clearSelectedFile();
      setStatus({
        kind: "success",
        message: publish
          ? content.mode === "local-demo"
            ? "演示版本已发布；因没有真实文件地址，公开页不会提供下载链接"
            : "版本已发布"
          : "版本草稿已保存",
      });
    } catch (error) {
      preserveDraftAfterFailureRef.current = true;
      setStatus({ kind: "error", message: error instanceof Error ? error.message : "操作失败" });
    } finally {
      operationInFlightRef.current = false;
      setPending(false);
    }
  }

  async function archiveSelected() {
    if (
      !selected
      || selected.status !== "published"
      || pending
      || operationInFlightRef.current
    ) {
      return;
    }
    if (!window.confirm(`归档版本“${selected.versionName || "未命名版本"}”？公开下载与更新日志入口会立即隐藏。`)) {
      return;
    }
    operationInFlightRef.current = true;
    setPending(true);
    try {
      await content.archiveRelease(selected.id);
      setStatus({ kind: "success", message: "版本已归档" });
    } catch (error) {
      setStatus({
        kind: "error",
        message: error instanceof Error ? error.message : "归档失败",
      });
    } finally {
      operationInFlightRef.current = false;
      setPending(false);
    }
  }

  return (
    <section className="admin-page">
      <AdminPageHeader
        eyebrow="版本发布"
        title="Android 安装包"
        description={content.mode === "local-demo"
          ? "支持正式版与测试版。演示模式只保存 APK 元数据，不保存二进制，也不生成虚假校验值。"
          : "支持正式版与测试版。APK 将上传至已配置的内容服务，发布后由服务提供真实下载信息。"}
        action={<button type="button" className="button button--primary" disabled={pending} onClick={() => {
          changeEditor(() => {
            const next = newRelease();
            setSelectedId(null);
            setDraft(next);
            metadataBaselineRef.current = releaseMetadata(next);
          });
        }}><MdAdd /> 新建版本</button>}
      />
      <FormStatus status={status} />

      <div className="editor-layout">
        <aside className="editor-list" aria-label="版本列表">
          {state.releases.length === 0 && <p className="editor-list__empty">还没有版本草稿</p>}
          {state.releases.map((release) => (
            <button
              type="button"
              disabled={pending}
              className={release.id === selectedId ? "is-active" : ""}
              key={release.id}
              onClick={() => {
                if (release.id !== selectedId) {
                  changeEditor(() => setSelectedId(release.id));
                }
              }}
            >
              <strong>{release.versionName || "未命名版本"}</strong>
              <span>{release.channel === "stable" ? "正式版" : "测试版"} · {release.status === "published" ? "已发布" : release.status === "archived" ? "已归档" : "草稿"}</span>
            </button>
          ))}
        </aside>

        <div className="editor-workspace">
          {locked && (
            <div className="editor-lock-notice" role="note">
              <strong>这是公开快照，不能直接修改</strong>
              <span>复制为新草稿后再调整版本号、安装包或更新说明。</span>
            </div>
          )}
          <div className="admin-form">
            <div className="field-grid field-grid--3">
              <label>发布渠道<select disabled={locked || pending} value={draft.channel} onChange={(e) => setDraft({ ...draft, channel: e.target.value as Release["channel"] })}><option value="stable">正式版</option><option value="beta">测试版</option></select></label>
              <label>版本名称<input disabled={locked || pending} placeholder="例如 1.0.0" value={draft.versionName} onChange={(e) => setDraft({ ...draft, versionName: e.target.value })} /></label>
              <label>版本代码<input disabled={locked || pending} type="number" min={1} step={1} value={draft.versionCode} onChange={(e) => setDraft({ ...draft, versionCode: Number(e.target.value) })} /></label>
            </div>
            <label>最低 Android 版本<input disabled={locked || pending} type="number" min={6} step={1} value={draft.minAndroid} onChange={(e) => setDraft({ ...draft, minAndroid: Number(e.target.value) })} /></label>
            <label className="apk-field">
              <span>APK 安装包</span>
              <span className="file-drop">
                <MdOutlineCloudUpload aria-hidden="true" />
                <strong>{file?.name ?? draft.package?.fileName ?? "选择 .apk 文件"}</strong>
                <small>{file
                  ? formatFileSize(file.size)
                  : draft.package
                    ? `${formatFileSize(draft.package.size)} · ${draft.package.storageState === "stored" ? "已存储" : "仅元数据"}`
                    : "不超过 500 MB"}</small>
                <input ref={fileInputRef} disabled={locked || pending} type="file" accept=".apk,application/vnd.android.package-archive" onChange={(e) => setFile(e.target.files?.[0] ?? null)} />
              </span>
            </label>
            <div className="markdown-editor">
              <label>更新说明（Markdown）<textarea disabled={locked || pending} rows={16} value={draft.releaseNotes} onChange={(e) => setDraft({ ...draft, releaseNotes: e.target.value })} /></label>
              <section className="markdown-preview"><span className="preview-label">实时预览</span><MarkdownContent>{draft.releaseNotes || "更新说明将在这里预览。"}</MarkdownContent></section>
            </div>
          </div>
          <div className="sticky-actions">
            {selected?.status === "published" && (
              <button
                className="button button--danger-ghost"
                type="button"
                disabled={pending}
                onClick={() => void archiveSelected()}
              >
                <MdArchive /> {pending ? "处理中…" : "归档"}
              </button>
            )}
            {locked && selected ? (
              <button
                className="button button--primary"
                type="button"
                disabled={pending}
                onClick={() => {
                  changeEditor(() => {
                    const next = copyReleaseAsDraft(selected);
                    setSelectedId(next.id);
                    setDraft(next);
                    metadataBaselineRef.current = releaseMetadata(next);
                    setStatus({ kind: "success", message: "已复制为新草稿，请填写新的版本名称" });
                  });
                }}
              >
                <MdContentCopy aria-hidden="true" /> 复制为新草稿
              </button>
            ) : (
              <>
                <button className="button button--ghost" type="button" onClick={() => void save(false)} disabled={pending}><MdSave /> 保存草稿</button>
                <button className="button button--primary" type="button" onClick={() => void save(true)} disabled={pending || (!file && !draft.package)}>
                  <MdOutlinePublish /> {content.mode === "local-demo" ? "模拟发布" : "发布版本"}
                </button>
              </>
            )}
          </div>
        </div>
      </div>
    </section>
  );
}
