import type {
  DocumentationContent,
  DocumentationPage,
  ManagedPage,
  MediaAsset,
  ProductPage,
  ProductPageBlock,
  ProductPageContent,
  Release,
  ReleaseChannel,
  SiteSettings,
} from "./types";

export const MAX_APK_SIZE = 500 * 1024 * 1024;

export function validateApk(file: Pick<File, "name" | "size" | "type">): string[] {
  const errors: string[] = [];
  if (!file.name.toLowerCase().endsWith(".apk")) {
    errors.push("仅支持 .apk 安装包");
  }
  if (file.size <= 0) {
    errors.push("安装包不能为空");
  }
  if (file.size > MAX_APK_SIZE) {
    errors.push("安装包不能超过 500 MB");
  }
  return errors;
}

export function documentationOrderError(order: number): string | null {
  return Number.isSafeInteger(order) && order >= 0
    ? null
    : "文档排序必须是大于或等于 0 的整数";
}

export function releaseDraftError(
  release: Pick<
    Release,
    "versionName" | "versionCode" | "minAndroid" | "releaseNotes"
  >,
): string | null {
  if (!release.versionName.trim() || !release.releaseNotes.trim()) {
    return "请填写版本名和更新说明";
  }
  if (!Number.isSafeInteger(release.versionCode) || release.versionCode < 1) {
    return "版本代码必须是大于或等于 1 的整数";
  }
  if (!Number.isSafeInteger(release.minAndroid) || release.minAndroid < 6) {
    return "最低 Android 版本必须是大于或等于 6 的整数";
  }
  return null;
}

export function latestPublishedRelease(
  releases: Release[],
  channel: ReleaseChannel,
): Release | null {
  return (
    releases
      .filter((release) => release.channel === channel && release.status === "published")
      .sort((a, b) => (b.publishedAt ?? "").localeCompare(a.publishedAt ?? ""))[0] ?? null
  );
}

export interface PublishedDocumentationTreeNode {
  doc: DocumentationPage;
  children: PublishedDocumentationTreeNode[];
}

export interface PublishedDocumentationSearchResult {
  doc: DocumentationPage;
  parentPath: DocumentationPage[];
}

export function publishedDocs(docs: DocumentationPage[]): DocumentationPage[] {
  return docs
    .map((doc, inputIndex) => ({ doc, inputIndex }))
    .filter(({ doc }) => doc.status === "published" && doc.published.title.trim())
    .sort(
      (a, b) =>
        a.doc.published.order - b.doc.published.order ||
        a.inputIndex - b.inputIndex,
    )
    .map(({ doc }) => doc);
}

interface PublishedDocumentationHierarchy {
  available: DocumentationPage[];
  roots: PublishedDocumentationTreeNode[];
  parentByDoc: Map<DocumentationPage, DocumentationPage | null>;
}

function documentationParentPath(
  hierarchy: PublishedDocumentationHierarchy,
  doc: DocumentationPage,
): DocumentationPage[] {
  const parentPath: DocumentationPage[] = [];
  const visited = new Set<DocumentationPage>([doc]);
  let parent = hierarchy.parentByDoc.get(doc) ?? null;

  while (parent && !visited.has(parent)) {
    visited.add(parent);
    parentPath.unshift(parent);
    parent = hierarchy.parentByDoc.get(parent) ?? null;
  }

  return parentPath;
}

function createPublishedDocumentationHierarchy(
  docs: DocumentationPage[],
): PublishedDocumentationHierarchy {
  const available = publishedDocs(docs);
  const firstDocById = new Map<string, DocumentationPage>();
  available.forEach((doc) => {
    if (!firstDocById.has(doc.id)) firstDocById.set(doc.id, doc);
  });

  const candidateParentByDoc = new Map<
    DocumentationPage,
    DocumentationPage | null
  >();
  available.forEach((doc) => {
    const parent = doc.published.parentId
      ? firstDocById.get(doc.published.parentId) ?? null
      : null;
    candidateParentByDoc.set(
      doc,
      parent && parent.id !== doc.id ? parent : null,
    );
  });

  const cycleMembers = new Set<DocumentationPage>();
  available.forEach((doc) => {
    const chain: DocumentationPage[] = [];
    const positionByDoc = new Map<DocumentationPage, number>();
    let current: DocumentationPage | null = doc;

    while (current && !positionByDoc.has(current)) {
      positionByDoc.set(current, chain.length);
      chain.push(current);
      current = candidateParentByDoc.get(current) ?? null;
    }

    if (current) {
      chain
        .slice(positionByDoc.get(current))
        .forEach((cycleMember) => cycleMembers.add(cycleMember));
    }
  });

  const parentByDoc = new Map<DocumentationPage, DocumentationPage | null>();
  available.forEach((doc) => {
    parentByDoc.set(
      doc,
      cycleMembers.has(doc) ? null : candidateParentByDoc.get(doc) ?? null,
    );
  });

  const nodeByDoc = new Map<DocumentationPage, PublishedDocumentationTreeNode>(
    available.map((doc) => [doc, { doc, children: [] }]),
  );
  const roots: PublishedDocumentationTreeNode[] = [];
  available.forEach((doc) => {
    const node = nodeByDoc.get(doc)!;
    const parent = parentByDoc.get(doc);
    if (parent) {
      nodeByDoc.get(parent)!.children.push(node);
    } else {
      roots.push(node);
    }
  });

  return { available, roots, parentByDoc };
}

export function buildPublishedDocumentationTree(
  docs: DocumentationPage[],
): PublishedDocumentationTreeNode[] {
  return createPublishedDocumentationHierarchy(docs).roots;
}

export function publishedDocumentationPath(
  docs: DocumentationPage[],
  doc: DocumentationPage,
): DocumentationPage[] {
  const hierarchy = createPublishedDocumentationHierarchy(docs);
  if (!hierarchy.available.includes(doc)) return [];
  return [...documentationParentPath(hierarchy, doc), doc];
}

export function searchPublishedDocs(
  docs: DocumentationPage[],
  query: string,
): DocumentationPage[] {
  const normalized = query.trim().toLocaleLowerCase("zh-CN");
  const available = publishedDocs(docs);
  if (!normalized) return available;
  return available.filter((doc) =>
    [doc.published.title, doc.published.summary, doc.published.markdown]
      .join("\n")
      .toLocaleLowerCase("zh-CN")
      .includes(normalized),
  );
}

export function searchPublishedDocumentation(
  docs: DocumentationPage[],
  query: string,
): PublishedDocumentationSearchResult[] {
  const normalized = query.trim().toLocaleLowerCase("zh-CN");
  if (!normalized) return [];

  const hierarchy = createPublishedDocumentationHierarchy(docs);
  return hierarchy.available
    .filter((doc) =>
      [doc.published.title, doc.published.summary, doc.published.markdown]
        .join("\n")
        .toLocaleLowerCase("zh-CN")
        .includes(normalized),
    )
    .map((doc) => {
      return { doc, parentPath: documentationParentPath(hierarchy, doc) };
    });
}

export function publishedPage(
  pages: ManagedPage[],
  slug: ManagedPage["slug"],
): ManagedPage | null {
  return pages.find((page) => page.slug === slug && page.publishedAt) ?? null;
}

export function managedPagePublicationError(
  content: ManagedPage["draft"],
): string | null {
  const required = [
    content.title,
    content.summary,
    content.body,
    content.seoTitle,
    content.seoDescription,
  ];
  return required.every((value) => value.trim())
    ? null
    : "请填写页面标题、摘要、正文、SEO 标题和 SEO 描述后再发布";
}

export function analyticsCanStart(
  site: SiteSettings,
  privacyPage: ManagedPage | null,
  consent: "unknown" | "granted" | "denied",
): boolean {
  return Boolean(
    consent === "granted" &&
      site.published.analytics.providerName.trim() &&
      site.published.analytics.siteId.trim() &&
      privacyPage?.publishedAt,
  );
}

export function slugify(value: string): string {
  return value
    .trim()
    .toLocaleLowerCase("zh-CN")
    .replace(/\s+/g, "-")
    .replace(/[^\p{L}\p{N}-]/gu, "")
    .replace(/-+/g, "-");
}

const DOCUMENTATION_SLUG_PATTERN =
  /^[\p{L}\p{N}]+(?:-[\p{L}\p{N}]+)*$/u;

export function documentationSlugError(
  id: string,
  slug: string,
  docs: DocumentationPage[],
): string | null {
  const normalized = slugify(slug);
  if (
    !normalized ||
    normalized !== slug ||
    !DOCUMENTATION_SLUG_PATTERN.test(normalized)
  ) {
    return "文档路径必须是单段、规范化的 URL 路径";
  }

  const duplicate = docs.some(
    (doc) =>
      doc.id !== id &&
      doc.status === "published" &&
      slugify(doc.published.slug) === normalized,
  );
  return duplicate ? "已有已发布文档使用相同路径" : null;
}

export function documentationHierarchyError(
  id: string,
  content: DocumentationContent,
  docs: DocumentationPage[],
): string | null {
  if (!content.parentId) return null;
  if (content.parentId === id) return "上级文档不能是当前文档";

  const publishedById = new Map(
    docs
      .filter((doc) => doc.status === "published")
      .map((doc) => [doc.id, doc] as const),
  );
  const visited = new Set([id]);
  let parentId: string | null = content.parentId;

  while (parentId) {
    if (visited.has(parentId)) return "文档层级不能形成循环";
    visited.add(parentId);

    const parent = publishedById.get(parentId);
    if (!parent) return "上级文档必须先发布";
    parentId = parent.published.parentId;
  }

  return null;
}

export function documentationArchiveError(
  id: string,
  docs: DocumentationPage[],
): string | null {
  const hasPublishedChild = docs.some(
    (doc) =>
      doc.id !== id
      && doc.status === "published"
      && doc.published.parentId === id,
  );
  return hasPublishedChild ? "请先归档或调整该文档下的已发布子文档" : null;
}

export function formatFileSize(bytes: number): string {
  if (bytes >= 1024 ** 3) return `${(bytes / 1024 ** 3).toFixed(1)} GB`;
  if (bytes >= 1024 ** 2) return `${(bytes / 1024 ** 2).toFixed(1)} MB`;
  if (bytes >= 1024) return `${Math.round(bytes / 1024)} KB`;
  return `${bytes} B`;
}

export function isProductPublished(product: ProductPage): product is ProductPage & {
  published: ProductPageContent;
} {
  return product.status === "published" && product.published !== null;
}

export function createProductBlock(type: ProductPageBlock["type"]): ProductPageBlock {
  const base = { id: crypto.randomUUID(), enabled: true };
  switch (type) {
    case "hero":
      return { ...base, type, heading: "", lead: "", mediaId: null };
    case "markdown":
      return { ...base, type, title: "", markdown: "" };
    case "image-text":
      return {
        ...base,
        type,
        eyebrow: "",
        title: "",
        markdown: "",
        mediaId: null,
        mediaSide: "right",
      };
    case "gallery":
      return { ...base, type, title: "", summary: "", mediaIds: [], columns: 3 };
    case "steps":
      return { ...base, type, title: "", summary: "", items: [] };
    case "cards":
      return { ...base, type, title: "", summary: "", items: [], columns: 3 };
    case "faq":
      return { ...base, type, title: "", items: [] };
    case "cta":
      return { ...base, type, title: "", body: "", label: "", href: "/download" };
  }
}

export function duplicateProductBlock(block: ProductPageBlock): ProductPageBlock {
  const duplicate = structuredClone(block);
  duplicate.id = crypto.randomUUID();
  if (duplicate.type === "steps" || duplicate.type === "cards") {
    duplicate.items = duplicate.items.map((item) => ({ ...item, id: crypto.randomUUID() }));
  }
  if (duplicate.type === "faq") {
    duplicate.items = duplicate.items.map((item) => ({ ...item, id: crypto.randomUUID() }));
  }
  return duplicate;
}

export function moveProductBlock(
  blocks: ProductPageBlock[],
  blockId: string,
  direction: -1 | 1,
): ProductPageBlock[] {
  const index = blocks.findIndex((block) => block.id === blockId);
  const destination = index + direction;
  if (index < 0 || destination < 0 || destination >= blocks.length) return blocks;
  const next = [...blocks];
  [next[index], next[destination]] = [next[destination], next[index]];
  return next;
}

function mediaError(
  mediaId: string | null,
  media: MediaAsset[],
  label: string,
  required: boolean,
): string | null {
  if (!mediaId) return required ? `${label}需要选择图片` : null;
  const asset = media.find((candidate) => candidate.id === mediaId);
  if (!asset) return `${label}引用的图片不存在`;
  if (!asset.visible) return `${label}引用的图片当前已隐藏`;
  if (!asset.src.trim()) return `${label}引用的图片文件不可用，请重新上传`;
  if (!asset.alt.trim()) return `${label}引用的图片缺少替代文本`;
  return null;
}

function validCtaHref(href: string): boolean {
  return href.startsWith("/") || /^https:\/\/[^\s]+$/i.test(href);
}

export function collectProductMediaIds(content: ProductPageContent): string[] {
  const ids = new Set<string>();
  content.blocks.forEach((block) => {
    if ((block.type === "hero" || block.type === "image-text") && block.mediaId) {
      ids.add(block.mediaId);
    }
    if (block.type === "gallery") {
      block.mediaIds.forEach((mediaId) => ids.add(mediaId));
    }
  });
  return [...ids];
}

export function validateProductPage(content: ProductPageContent, media: MediaAsset[]): string[] {
  const errors: string[] = [];
  if (!content.navLabel.trim()) errors.push("导航名称不能为空");
  if (!content.seoTitle.trim()) errors.push("SEO 标题不能为空");
  if (!content.seoDescription.trim()) errors.push("SEO 描述不能为空");
  if (content.showHomepageEntry && !content.homepageEntryLabel.trim()) {
    errors.push("开启首页入口时必须填写入口文案");
  }

  const enabled = content.blocks.filter((block) => block.enabled);
  const heroes = enabled.filter((block) => block.type === "hero");
  if (heroes.length !== 1) errors.push("必须且只能发布一个可见的页面首屏区块");
  if (enabled.length < 2) errors.push("除页面首屏外，至少还需要一个可见内容区块");

  enabled.forEach((block, index) => {
    const label = `区块 ${index + 1}`;
    switch (block.type) {
      case "hero": {
        if (!block.heading.trim()) errors.push(`${label}的主标题不能为空`);
        if (!block.lead.trim()) errors.push(`${label}的简介不能为空`);
        const error = mediaError(block.mediaId, media, label, false);
        if (error) errors.push(error);
        break;
      }
      case "markdown":
        if (!block.markdown.trim()) errors.push(`${label}的正文不能为空`);
        break;
      case "image-text": {
        if (!block.title.trim()) errors.push(`${label}的标题不能为空`);
        if (!block.markdown.trim()) errors.push(`${label}的正文不能为空`);
        const error = mediaError(block.mediaId, media, label, true);
        if (error) errors.push(error);
        break;
      }
      case "gallery": {
        if (!block.title.trim()) errors.push(`${label}的标题不能为空`);
        if (!block.mediaIds.length) errors.push(`${label}至少需要一张图片`);
        block.mediaIds.forEach((mediaId) => {
          const error = mediaError(mediaId, media, label, true);
          if (error) errors.push(error);
        });
        break;
      }
      case "steps":
      case "cards":
        if (!block.title.trim()) errors.push(`${label}的标题不能为空`);
        if (!block.items.length) errors.push(`${label}至少需要一个条目`);
        if (block.items.some((item) => !item.title.trim() || !item.body.trim())) {
          errors.push(`${label}的条目标题和正文均不能为空`);
        }
        break;
      case "faq":
        if (!block.title.trim()) errors.push(`${label}的标题不能为空`);
        if (!block.items.length) errors.push(`${label}至少需要一个问题`);
        if (block.items.some((item) => !item.question.trim() || !item.answer.trim())) {
          errors.push(`${label}的问题和回答均不能为空`);
        }
        break;
      case "cta":
        if (!block.title.trim() || !block.label.trim()) {
          errors.push(`${label}的标题和按钮文案不能为空`);
        }
        if (!validCtaHref(block.href.trim())) {
          errors.push(`${label}只能使用站内路径或 HTTPS 链接`);
        }
        break;
    }
  });

  return [...new Set(errors)];
}
