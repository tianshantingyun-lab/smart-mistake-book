import type { ReactNode } from "react";

export function EmptyState({
  icon,
  title,
  body,
  action,
  headingLevel = 2,
}: {
  icon: ReactNode;
  title: string;
  body: string;
  action?: ReactNode;
  headingLevel?: 1 | 2;
}) {
  const Heading = headingLevel === 1 ? "h1" : "h2";

  return (
    <section className="empty-state">
      <span className="empty-state__icon" aria-hidden="true">
        {icon}
      </span>
      <Heading>{title}</Heading>
      <p>{body}</p>
      {action}
    </section>
  );
}
