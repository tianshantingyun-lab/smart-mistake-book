import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";

export function MarkdownContent({ children }: { children: string }) {
  return (
    <div className="markdown-content">
      <ReactMarkdown
        remarkPlugins={[remarkGfm]}
        components={{
          h1: ({ node: _node, ...props }) => <h2 {...props} />,
          table: ({ node: _node, ...props }) => (
            <div
              className="markdown-table-scroll"
              role="region"
              aria-label="可横向滚动的表格"
              tabIndex={0}
            >
              <table {...props} />
            </div>
          ),
        }}
      >
        {children}
      </ReactMarkdown>
    </div>
  );
}
