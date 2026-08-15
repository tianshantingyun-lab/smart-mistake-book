import { MdOutlineHistory } from "react-icons/md";
import { Link } from "react-router-dom";
import { useApp } from "../../app/AppContext";
import { EmptyState } from "../../components/EmptyState";
import { MarkdownContent } from "../../components/MarkdownContent";
import { PageMeta } from "../../components/PageMeta";

const changelogDateFormatter = new Intl.DateTimeFormat("zh-CN", {
  timeZone: "Asia/Shanghai",
  year: "numeric",
  month: "2-digit",
  day: "2-digit",
});

function publicationTimestamp(value: string | null): number {
  if (!value) return Number.NEGATIVE_INFINITY;
  const timestamp = Date.parse(value);
  return Number.isNaN(timestamp) ? Number.NEGATIVE_INFINITY : timestamp;
}

function publicationDate(value: string | null): string {
  const timestamp = publicationTimestamp(value);
  if (!Number.isFinite(timestamp)) return "日期待补充";
  const parts = changelogDateFormatter.formatToParts(timestamp);
  const part = (type: Intl.DateTimeFormatPartTypes) =>
    parts.find((candidate) => candidate.type === type)?.value ?? "";
  return `${part("year")}-${part("month")}-${part("day")}`;
}

export function ChangelogPage() {
  const { state } = useApp();
  const releases = state.releases
    .filter((release) => release.status === "published")
    .sort(
      (a, b) =>
        publicationTimestamp(b.publishedAt)
        - publicationTimestamp(a.publishedAt),
    );

  return (
    <div className="standard-page">
      <PageMeta
        title="更新日志｜智能错题本"
        description="查看智能错题本正式版与测试版的版本更新说明。"
      />
      <header className="page-hero page-container">
        <p className="eyebrow">更新日志</p>
        <h1>每次更新，都说清楚改了什么</h1>
        <p>版本说明直接来自发布记录，避免安装包信息与更新日志不一致。</p>
      </header>
      <section className="page-container changelog-list">
        {releases.length === 0 && (
          <EmptyState
            icon={<MdOutlineHistory />}
            title="暂无已发布版本"
            body="当前还没有可查看的版本说明。新版本发布后，更新内容会显示在这里。"
            action={<Link className="text-action" to="/">返回首页</Link>}
          />
        )}
        {releases.map((release) => (
          <article key={release.id} className="changelog-entry">
            <aside>
              <strong>{release.versionName}</strong>
              <span>{release.channel === "stable" ? "正式版" : "测试版"}</span>
              <time dateTime={release.publishedAt ?? undefined}>
                {publicationDate(release.publishedAt)}
              </time>
            </aside>
            <MarkdownContent>{release.releaseNotes}</MarkdownContent>
          </article>
        ))}
      </section>
    </div>
  );
}
