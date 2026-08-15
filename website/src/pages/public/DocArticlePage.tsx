import { MdOutlineMenuBook } from "react-icons/md";
import { Link, useParams } from "react-router-dom";
import { useApp } from "../../app/AppContext";
import { EmptyState } from "../../components/EmptyState";
import { MarkdownContent } from "../../components/MarkdownContent";
import { PageMeta } from "../../components/PageMeta";
import { publishedDocumentationPath, publishedDocs } from "../../domain";
import type { DocumentationPage } from "../../types";

function DocumentationBreadcrumbs({
  docs,
  current,
}: {
  docs: DocumentationPage[];
  current: DocumentationPage;
}) {
  const path = publishedDocumentationPath(docs, current);

  return (
    <nav className="document-breadcrumbs" aria-label="文档面包屑">
      <ol>
        <li><Link to="/docs">使用文档</Link></li>
        {path.map((pathDoc, index) => {
          const isCurrent = pathDoc === current;
          return (
            <li key={`${pathDoc.id}-${index}`}>
              <span className="document-breadcrumbs__separator" aria-hidden="true">/</span>
              <Link
                to={`/docs/${pathDoc.published.slug}`}
                aria-current={isCurrent ? "page" : undefined}
              >
                {pathDoc.published.title}
              </Link>
            </li>
          );
        })}
      </ol>
    </nav>
  );
}

export function DocArticlePage() {
  const { state } = useApp();
  const { slug } = useParams();
  const doc = publishedDocs(state.docs).find((candidate) => candidate.published.slug === slug);

  if (!doc) {
    return (
      <div className="standard-page page-container">
        <PageMeta
          title="文档未找到｜智能错题本"
          description="这篇文档尚未发布或不存在。"
          noIndex
        />
        <EmptyState
          icon={<MdOutlineMenuBook />}
          title="这篇文档尚未发布"
          body="返回文档首页查看当前可用内容。"
          headingLevel={1}
          action={<Link className="button button--primary" to="/docs">返回使用文档</Link>}
        />
      </div>
    );
  }

  return (
    <article className="document-page page-container">
      <PageMeta
        title={`${doc.published.title}｜智能错题本使用文档`}
        description={doc.published.summary}
      />
      <DocumentationBreadcrumbs docs={state.docs} current={doc} />
      <header>
        <p className="eyebrow">使用文档</p>
        <h1>{doc.published.title}</h1>
        <p>{doc.published.summary}</p>
      </header>
      <MarkdownContent>{doc.published.markdown}</MarkdownContent>
    </article>
  );
}
