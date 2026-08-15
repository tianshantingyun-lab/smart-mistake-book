import { MdOutlineExploreOff } from "react-icons/md";
import { Link } from "react-router-dom";
import { EmptyState } from "../../components/EmptyState";
import { PageMeta } from "../../components/PageMeta";

export function NotFoundPage() {
  return (
    <div className="standard-page page-container">
      <PageMeta
        title="页面未找到｜智能错题本"
        description="请求的页面不存在或尚未发布。"
        noIndex
      />
      <EmptyState
        icon={<MdOutlineExploreOff />}
        title="页面未找到"
        body="这个页面不存在，或内容尚未正式发布。"
        headingLevel={1}
        action={<Link className="button button--primary" to="/">返回首页</Link>}
      />
    </div>
  );
}
