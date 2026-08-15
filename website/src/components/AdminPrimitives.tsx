import type { ReactNode } from "react";

export function AdminPageHeader({
  eyebrow,
  title,
  description,
  action,
}: {
  eyebrow: string;
  title: string;
  description: string;
  action?: ReactNode;
}) {
  return (
    <header className="admin-page-header">
      <div>
        <p className="eyebrow">{eyebrow}</p>
        <h1>{title}</h1>
        <p>{description}</p>
      </div>
      {action}
    </header>
  );
}

export function FormStatus({
  status,
}: {
  status: { kind: "success" | "error"; message: string } | null;
}) {
  if (!status) return null;
  return (
    <p className={`form-status form-status--${status.kind}`} role="status">
      {status.message}
    </p>
  );
}
