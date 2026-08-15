import { useEffect } from "react";

export function PageMeta({
  title,
  description,
  noIndex = false,
}: {
  title: string;
  description: string;
  noIndex?: boolean;
}) {
  useEffect(() => {
    document.title = title;
    let meta = document.querySelector<HTMLMetaElement>('meta[name="description"]');
    if (!meta) {
      meta = document.createElement("meta");
      meta.name = "description";
      document.head.append(meta);
    }
    meta.content = description;
    let robots = document.querySelector<HTMLMetaElement>('meta[data-managed-robots="true"]');
    if (noIndex) {
      if (!robots) {
        robots = document.createElement("meta");
        robots.name = "robots";
        robots.dataset.managedRobots = "true";
        document.head.append(robots);
      }
      robots.content = "noindex,nofollow";
    } else {
      robots?.remove();
    }
  }, [description, noIndex, title]);
  return null;
}
