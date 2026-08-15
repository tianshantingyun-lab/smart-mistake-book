import { useApp } from "../../app/AppContext";
import { MarkdownContent } from "../../components/MarkdownContent";
import { PageMeta } from "../../components/PageMeta";
import { publishedPage } from "../../domain";
import type { ManagedPage } from "../../types";
import { NotFoundPage } from "./NotFoundPage";

export function ManagedPagePage({ slug }: { slug: ManagedPage["slug"] }) {
  const { state } = useApp();
  const page = publishedPage(state.pages, slug);

  if (!page) return <NotFoundPage />;

  return (
    <article className="managed-page page-container">
      <PageMeta title={page.published.seoTitle} description={page.published.seoDescription} />
      <header>
        <p className="eyebrow">{slug === "privacy" ? "数据与隐私" : slug === "contact" ? "支持" : "关于产品"}</p>
        <h1>{page.published.title}</h1>
        <p>{page.published.summary}</p>
      </header>
      <MarkdownContent>{page.published.body}</MarkdownContent>
    </article>
  );
}
