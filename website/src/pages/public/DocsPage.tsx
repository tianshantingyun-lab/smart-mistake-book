import { useMemo, useState } from "react";
import { MdArrowForward, MdOutlineMenuBook, MdSearch } from "react-icons/md";
import { Link } from "react-router-dom";
import { useApp } from "../../app/AppContext";
import { EmptyState } from "../../components/EmptyState";
import { PageMeta } from "../../components/PageMeta";
import {
  buildPublishedDocumentationTree,
  searchPublishedDocumentation,
  type PublishedDocumentationSearchResult,
  type PublishedDocumentationTreeNode,
} from "../../domain";

function DocumentationLink({
  result,
}: {
  result: PublishedDocumentationSearchResult;
}) {
  const { doc, parentPath } = result;
  const directory = parentPath.length
    ? parentPath.map((parent) => parent.published.title).join(" / ")
    : "顶层";

  return (
    <Link to={`/docs/${doc.published.slug}`}>
      <div>
        <strong>{doc.published.title}</strong>
        <p className="docs-path">所在目录：{directory}</p>
        {doc.published.summary && <p>{doc.published.summary}</p>}
      </div>
      <MdArrowForward aria-hidden="true" />
    </Link>
  );
}

function DocumentationTree({
  nodes,
  nested = false,
}: {
  nodes: PublishedDocumentationTreeNode[];
  nested?: boolean;
}) {
  return (
    <ol className={nested ? "docs-tree-children" : "docs-list docs-tree"}>
      {nodes.map((node) => (
        <li key={node.doc.id}>
          <Link to={`/docs/${node.doc.published.slug}`}>
            <div>
              <strong>{node.doc.published.title}</strong>
              {node.doc.published.summary && <p>{node.doc.published.summary}</p>}
            </div>
            <MdArrowForward aria-hidden="true" />
          </Link>
          {node.children.length > 0 && (
            <DocumentationTree nodes={node.children} nested />
          )}
        </li>
      ))}
    </ol>
  );
}

export function DocsPage() {
  const { state, track } = useApp();
  const [query, setQuery] = useState("");
  const tree = useMemo(
    () => buildPublishedDocumentationTree(state.docs),
    [state.docs],
  );
  const searchResults = useMemo(
    () => searchPublishedDocumentation(state.docs, query),
    [query, state.docs],
  );
  const isSearching = Boolean(query.trim());
  const hasPublishedDocs = tree.length > 0;

  return (
    <div className="standard-page">
      <PageMeta
        title="使用文档｜智能错题本"
        description="查找智能错题本的安装、使用与维护说明。"
      />
      <header className="page-hero page-container">
        <p className="eyebrow">使用文档</p>
        <h1>需要时，快速找到答案</h1>
        <p>文档会随着版本逐步补充。已发布内容支持分层目录与全文搜索。</p>
      </header>

      <section className="page-container docs-index">
        <label className="search-field">
          <MdSearch aria-hidden="true" />
          <span className="sr-only">搜索使用文档</span>
          <input
            type="search"
            value={query}
            placeholder="搜索标题或正文"
            onChange={(event) => {
              const value = event.target.value;
              setQuery(value);
              if (value.trim().length >= 2) track("docs_search", { query: value.trim() });
            }}
            disabled={!hasPublishedDocs}
          />
        </label>
        <p className="sr-only" aria-live="polite">
          {isSearching ? `找到 ${searchResults.length} 篇文档` : ""}
        </p>

        {!hasPublishedDocs && (
          <EmptyState
            icon={<MdOutlineMenuBook />}
            title="使用文档暂未发布"
            body="当前还没有可查看的使用文档。内容准备好后，文档目录和搜索会显示在这里。"
            action={<Link className="text-action" to="/">返回首页</Link>}
          />
        )}

        {hasPublishedDocs && isSearching && searchResults.length === 0 && (
          <EmptyState
            icon={<MdSearch />}
            title="没有找到相关内容"
            body="换一个关键词，或清空搜索查看全部文档。"
          />
        )}

        {hasPublishedDocs && (!isSearching || searchResults.length > 0) && (
          <nav aria-label={isSearching ? "文档搜索结果" : "使用文档目录"}>
            {isSearching ? (
              <ol className="docs-list">
                {searchResults.map((result) => (
                  <li key={result.doc.id}>
                    <DocumentationLink result={result} />
                  </li>
                ))}
              </ol>
            ) : (
              <DocumentationTree nodes={tree} />
            )}
          </nav>
        )}
      </section>
    </div>
  );
}
