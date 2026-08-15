import { useState } from "react";
import {
  MdCheckCircleOutline,
  MdOutlineAndroid,
  MdOutlineInventory2,
} from "react-icons/md";
import { Link } from "react-router-dom";
import { useApp } from "../../app/AppContext";
import { EmptyState } from "../../components/EmptyState";
import { MarkdownContent } from "../../components/MarkdownContent";
import { PageMeta } from "../../components/PageMeta";
import { formatFileSize, latestPublishedRelease } from "../../domain";
import type { ReleaseChannel } from "../../types";

export function DownloadPage() {
  const { state, track } = useApp();
  const [channel, setChannel] = useState<ReleaseChannel>("stable");
  const release = latestPublishedRelease(state.releases, channel);

  return (
    <div className="standard-page">
      <PageMeta
        title="下载｜智能错题本"
        description="查看智能错题本 Android 正式版与测试版发布状态。"
      />
      <header className="page-hero page-container">
        <p className="eyebrow">Android 应用</p>
        <h1>下载智能错题本</h1>
        <p>正式版适合日常使用，测试版用于提前体验正在验证的新能力。</p>
      </header>

      <section className="page-container download-panel" aria-labelledby="release-channel-title">
        <div className="segmented-control" role="group" aria-labelledby="release-channel-title">
          <span id="release-channel-title" className="sr-only">选择发布渠道</span>
          <button
            type="button"
            className={channel === "stable" ? "is-active" : ""}
            aria-pressed={channel === "stable"}
            onClick={() => setChannel("stable")}
          >
            正式版
          </button>
          <button
            type="button"
            className={channel === "beta" ? "is-active" : ""}
            aria-pressed={channel === "beta"}
            onClick={() => setChannel("beta")}
          >
            测试版
          </button>
        </div>

        {!release && (
          <EmptyState
            icon={<MdOutlineInventory2 />}
            title={channel === "stable" ? "正式版暂未发布" : "测试版暂未发布"}
            body="当前没有可下载的安装包。新版本准备好后，版本信息和下载入口会显示在这里。"
            action={<Link className="text-action" to="/">返回首页</Link>}
          />
        )}

        {release && (
          <article className="release-detail">
            <div className="release-detail__heading">
              <span className="release-icon"><MdOutlineAndroid aria-hidden="true" /></span>
              <div>
                <p className="eyebrow">{release.channel === "stable" ? "正式版" : "测试版"}</p>
                <h2>版本 {release.versionName}</h2>
                <p>Android {release.minAndroid}.0 及以上 · 版本代码 {release.versionCode}</p>
              </div>
            </div>
            {release.package && (
              <dl className="release-metadata">
                <div><dt>文件</dt><dd>{release.package.fileName}</dd></div>
                <div><dt>大小</dt><dd>{formatFileSize(release.package.size)}</dd></div>
                {release.package.sha256 && (
                  <div><dt>SHA-256</dt><dd>{release.package.sha256}</dd></div>
                )}
              </dl>
            )}
            {release.package?.downloadUrl ? (
              <a
                className="button button--primary"
                href={release.package.downloadUrl}
                onClick={() => track("download_intent", { source: "download-page", channel })}
              >
                <MdCheckCircleOutline aria-hidden="true" /> 下载安装包
              </a>
            ) : (
              <p className="release-availability" role="status">
                安装文件暂未开放下载，请稍后再来查看。
              </p>
            )}
            <MarkdownContent>{release.releaseNotes}</MarkdownContent>
          </article>
        )}
      </section>
    </div>
  );
}
