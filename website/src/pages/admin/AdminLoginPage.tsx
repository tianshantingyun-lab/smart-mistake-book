import { MdArrowForward, MdOutlineAdminPanelSettings } from "react-icons/md";
import { useState } from "react";
import { Navigate, useLocation, useNavigate } from "react-router-dom";
import { useApp } from "../../app/AppContext";
import { FormStatus } from "../../components/AdminPrimitives";
import { PageMeta } from "../../components/PageMeta";

export function AdminLoginPage() {
  const { session, contentScope, loginDemo, runtimeMode } = useApp();
  const navigate = useNavigate();
  const location = useLocation();
  const [submitting, setSubmitting] = useState(false);
  const [status, setStatus] = useState<{ kind: "error"; message: string } | null>(
    null,
  );
  const isLocalDemo = runtimeMode === "local-demo";

  if (session && contentScope === "admin") {
    return <Navigate to="/admin" replace />;
  }

  const login = async () => {
    setSubmitting(true);
    setStatus(null);
    try {
      await loginDemo();
      const from = (location.state as { from?: { pathname?: string } } | null)
        ?.from?.pathname;
      navigate(from && from !== "/admin/login" ? from : "/admin", {
        replace: true,
      });
    } catch (error) {
      setStatus({
        kind: "error",
        message: error instanceof Error ? error.message : "管理员登录失败",
      });
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <main className="admin-login">
      <PageMeta
        title="管理员登录｜智能错题本"
        description="智能错题本官网演示管理后台。"
      />
      <section className="admin-login__panel">
        <img src="/assets/brand/app-icon.png" alt="" />
        <p className="eyebrow">官网管理</p>
        <h1>管理员登录</h1>
        <p>
          {isLocalDemo
            ? "当前为演示模式。它用于验证内容管理流程，不代表真实的账号或安全保护。"
            : "当前连接到远程内容服务。登录成功并加载完整管理内容后才会进入后台。"}
        </p>
        <div className="demo-login-notice">
          <MdOutlineAdminPanelSettings aria-hidden="true" />
          <div>
            <strong>{isLocalDemo ? "单管理员演示会话" : "远程管理员会话"}</strong>
            <span>
              {isLocalDemo
                ? "数据保存在当前浏览器，不会发送到服务器。"
                : "登录与内容操作会发送到已配置的内容服务。"}
            </span>
          </div>
        </div>
        <FormStatus status={status} />
        <button
          type="button"
          className="button button--primary button--full"
          disabled={submitting}
          onClick={() => void login()}
        >
          {submitting
            ? "正在加载管理内容…"
            : isLocalDemo
              ? "进入演示后台"
              : "进入远程后台"}{" "}
          <MdArrowForward aria-hidden="true" />
        </button>
        <a className="text-action" href="/">返回公开官网</a>
      </section>
    </main>
  );
}
