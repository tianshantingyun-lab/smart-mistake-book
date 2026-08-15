import { z } from "zod";
import { createSeedState } from "../data/seed";
import type {
  DocumentationContent,
  DocumentationPage,
  ManagedPage,
  ManagedPageContent,
  MediaAsset,
  ProductPage,
  ProductPageContent,
  PublicDocumentationSnapshot,
  PublicManagedPageSnapshot,
  PublicMediaAsset,
  PublicProductSnapshot,
  PublicRelease,
  PublicSiteSnapshot,
  PublicWebsiteState,
  Release,
  SiteContent,
  SiteSettings,
  WebsiteState,
} from "../types";

const identifierSchema = z.string().min(1);
const timestampSchema = z.union([
  z.iso.datetime({ offset: true }),
  z.iso.date(),
]);
const nullableTimestampSchema = timestampSchema.nullable();
const publicationStateSchema = z.enum(["draft", "published", "archived"]);

export const siteContentSchema: z.ZodType<SiteContent> = z
  .object({
    brandName: z.string(),
    brandTagline: z.string(),
    heroTitle: z.string(),
    heroDescription: z.string(),
    primaryActionLabel: z.string(),
    secondaryActionLabel: z.string(),
    trustTitle: z.string(),
    trustBody: z.string(),
    analytics: z
      .object({
        providerName: z.string(),
        siteId: z.string(),
      })
      .strict(),
  })
  .strict();

const siteSettingsSchema: z.ZodType<SiteSettings> = z
  .object({
    id: z.literal("site"),
    draft: siteContentSchema,
    published: siteContentSchema,
    updatedAt: timestampSchema,
    publishedAt: nullableTimestampSchema,
  })
  .strict();

const managedPageContentSchema: z.ZodType<ManagedPageContent> = z
  .object({
    title: z.string(),
    summary: z.string(),
    body: z.string(),
    seoTitle: z.string(),
    seoDescription: z.string(),
  })
  .strict();

const managedPageSchema: z.ZodType<ManagedPage> = z
  .object({
    id: identifierSchema,
    slug: z.enum(["about", "privacy", "contact"]),
    draft: managedPageContentSchema,
    published: managedPageContentSchema,
    updatedAt: timestampSchema,
    publishedAt: nullableTimestampSchema,
  })
  .strict();

const managedPagesSchema = z
  .array(managedPageSchema)
  .length(3)
  .superRefine((pages, context) => {
    const ids = new Set<string>();
    const slugs = new Set<ManagedPage["slug"]>();
    pages.forEach((page, index) => {
      if (ids.has(page.id)) {
        context.addIssue({
          code: "custom",
          message: "静态页面 ID 不能重复",
          path: [index, "id"],
        });
      }
      if (slugs.has(page.slug)) {
        context.addIssue({
          code: "custom",
          message: "静态页面路径不能重复",
          path: [index, "slug"],
        });
      }
      ids.add(page.id);
      slugs.add(page.slug);
    });
  });

const mediaAssetShape = {
  id: identifierSchema,
  label: z.string(),
  role: z.enum([
    "brand",
    "hero-review",
    "hero-tutor",
    "hero-library",
    "content",
  ]),
  src: z.string(),
  alt: z.string(),
  visible: z.boolean(),
  bundled: z.boolean(),
  blobKey: z.string().optional(),
  mimeType: z.string().optional(),
  width: z.number().int().positive().optional(),
  height: z.number().int().positive().optional(),
};

export const mediaAssetSchema: z.ZodType<MediaAsset> = z
  .object(mediaAssetShape)
  .strict();

const releasePackageSchema = z
  .object({
    fileName: z.string(),
    size: z.number().nonnegative(),
    mimeType: z.string(),
    downloadUrl: z.string().nullable(),
    sha256: z.string().nullable(),
    storageState: z.enum(["metadata-only", "stored"]),
  })
  .strict();

const releaseShape = {
  id: identifierSchema,
  channel: z.enum(["stable", "beta"]),
  versionName: z.string(),
  versionCode: z.number().int().min(1),
  minAndroid: z.number().int().min(6),
  releaseNotes: z.string(),
  status: publicationStateSchema,
  package: releasePackageSchema.nullable(),
  createdAt: timestampSchema,
  publishedAt: nullableTimestampSchema,
};

export const releaseSchema: z.ZodType<Release> = z
  .object(releaseShape)
  .strict();

const documentationContentSchema: z.ZodType<DocumentationContent> = z
  .object({
    title: z.string(),
    slug: z.string(),
    summary: z.string(),
    markdown: z.string(),
    parentId: z.string().nullable(),
    order: z.number().int().nonnegative(),
  })
  .strict();

const documentationPageSchema: z.ZodType<DocumentationPage> = z
  .object({
    id: identifierSchema,
    status: publicationStateSchema,
    draft: documentationContentSchema,
    published: documentationContentSchema,
    updatedAt: timestampSchema,
    publishedAt: nullableTimestampSchema,
  })
  .strict();

const productListItemSchema = z
  .object({
    id: identifierSchema,
    title: z.string(),
    body: z.string(),
  })
  .strict();

const productFaqItemSchema = z
  .object({
    id: identifierSchema,
    question: z.string(),
    answer: z.string(),
  })
  .strict();

const productBlockSchema = z.discriminatedUnion("type", [
  z
    .object({
      id: identifierSchema,
      type: z.literal("hero"),
      enabled: z.boolean(),
      heading: z.string(),
      lead: z.string(),
      mediaId: z.string().nullable(),
    })
    .strict(),
  z
    .object({
      id: identifierSchema,
      type: z.literal("markdown"),
      enabled: z.boolean(),
      title: z.string(),
      markdown: z.string(),
    })
    .strict(),
  z
    .object({
      id: identifierSchema,
      type: z.literal("image-text"),
      enabled: z.boolean(),
      eyebrow: z.string(),
      title: z.string(),
      markdown: z.string(),
      mediaId: z.string().nullable(),
      mediaSide: z.enum(["left", "right"]),
    })
    .strict(),
  z
    .object({
      id: identifierSchema,
      type: z.literal("gallery"),
      enabled: z.boolean(),
      title: z.string(),
      summary: z.string(),
      mediaIds: z.array(z.string()),
      columns: z.union([z.literal(2), z.literal(3)]),
    })
    .strict(),
  z
    .object({
      id: identifierSchema,
      type: z.literal("steps"),
      enabled: z.boolean(),
      title: z.string(),
      summary: z.string(),
      items: z.array(productListItemSchema),
    })
    .strict(),
  z
    .object({
      id: identifierSchema,
      type: z.literal("cards"),
      enabled: z.boolean(),
      title: z.string(),
      summary: z.string(),
      items: z.array(productListItemSchema),
      columns: z.union([z.literal(2), z.literal(3)]),
    })
    .strict(),
  z
    .object({
      id: identifierSchema,
      type: z.literal("faq"),
      enabled: z.boolean(),
      title: z.string(),
      items: z.array(productFaqItemSchema),
    })
    .strict(),
  z
    .object({
      id: identifierSchema,
      type: z.literal("cta"),
      enabled: z.boolean(),
      title: z.string(),
      body: z.string(),
      label: z.string(),
      href: z.string(),
    })
    .strict(),
]);

export const productPageContentSchema: z.ZodType<ProductPageContent> = z
  .object({
    navLabel: z.string(),
    seoTitle: z.string(),
    seoDescription: z.string(),
    showHomepageEntry: z.boolean(),
    homepageEntryLabel: z.string(),
    blocks: z.array(productBlockSchema),
  })
  .strict();

const productPageSchema: z.ZodType<ProductPage> = z
  .object({
    id: z.literal("product"),
    slug: z.literal("product"),
    status: publicationStateSchema,
    draft: productPageContentSchema,
    published: productPageContentSchema.nullable(),
    updatedAt: timestampSchema,
    publishedAt: nullableTimestampSchema,
  })
  .strict();

export const websiteStateSchema: z.ZodType<WebsiteState> = z
  .object({
    schemaVersion: z.literal(2),
    localRevision: z.number().int().nonnegative().optional(),
    site: siteSettingsSchema,
    pages: managedPagesSchema,
    media: z.array(mediaAssetSchema),
    releases: z.array(releaseSchema),
    docs: z.array(documentationPageSchema),
    product: productPageSchema,
  })
  .strict();

function recordValue(value: unknown): Record<string, unknown> {
  return typeof value === "object" && value !== null
    ? value as Record<string, unknown>
    : {};
}

function repairSiteContent(
  value: unknown,
  fallback: SiteContent,
): SiteContent {
  const candidate = recordValue(value);
  const analytics = recordValue(candidate.analytics);
  const stringValue = (key: keyof Omit<SiteContent, "analytics">) =>
    typeof candidate[key] === "string" ? candidate[key] : fallback[key];
  return {
    brandName: stringValue("brandName"),
    brandTagline: stringValue("brandTagline"),
    heroTitle: stringValue("heroTitle"),
    heroDescription: stringValue("heroDescription"),
    primaryActionLabel: stringValue("primaryActionLabel"),
    secondaryActionLabel: stringValue("secondaryActionLabel"),
    trustTitle: stringValue("trustTitle"),
    trustBody: stringValue("trustBody"),
    analytics: {
      providerName:
        typeof analytics.providerName === "string"
          ? analytics.providerName
          : fallback.analytics.providerName,
      siteId:
        typeof analytics.siteId === "string"
          ? analytics.siteId
          : fallback.analytics.siteId,
    },
  };
}

function repairList<T>(
  schema: z.ZodType<T>,
  value: unknown,
  fallback: T[],
): T[] {
  if (!Array.isArray(value)) return structuredClone(fallback);
  return value.flatMap((item) => {
    const parsed = schema.safeParse(item);
    return parsed.success ? [parsed.data] : [];
  });
}

export function repairWebsiteState(value: unknown): WebsiteState | null {
  const candidate = recordValue(value);
  if (candidate.schemaVersion !== 2) return null;

  const fallback = createSeedState();
  const candidateSite = recordValue(candidate.site);
  const repairedSite = siteSettingsSchema.safeParse({
    ...candidateSite,
    draft: repairSiteContent(candidateSite.draft, fallback.site.draft),
    published: repairSiteContent(
      candidateSite.published,
      fallback.site.published,
    ),
  });
  const repairedPages = repairList(
    managedPageSchema,
    candidate.pages,
    fallback.pages,
  );
  const repairedPageBySlug = new Map(
    repairedPages.map((page) => [page.slug, page]),
  );
  const validPages = managedPagesSchema.safeParse(
    fallback.pages.map(
      (page) => repairedPageBySlug.get(page.slug) ?? structuredClone(page),
    ),
  );
  const repaired = {
    schemaVersion: 2 as const,
    localRevision:
      typeof candidate.localRevision === "number"
      && Number.isInteger(candidate.localRevision)
      && candidate.localRevision >= 0
        ? candidate.localRevision
        : fallback.localRevision,
    site: repairedSite.success ? repairedSite.data : fallback.site,
    pages: validPages.success ? validPages.data : fallback.pages,
    media: repairList(mediaAssetSchema, candidate.media, fallback.media),
    releases: repairList(releaseSchema, candidate.releases, fallback.releases),
    docs: repairList(
      documentationPageSchema,
      candidate.docs,
      fallback.docs,
    ),
    product:
      productPageSchema.safeParse(candidate.product).data ?? fallback.product,
  };
  const result = websiteStateSchema.safeParse(repaired);
  return result.success ? result.data : null;
}

const publicSiteSchema: z.ZodType<PublicSiteSnapshot> = z
  .object({
    content: siteContentSchema,
    updatedAt: timestampSchema,
    publishedAt: timestampSchema,
  })
  .strict();

const publicManagedPageSchema: z.ZodType<PublicManagedPageSnapshot> = z
  .object({
    id: identifierSchema,
    slug: z.enum(["about", "privacy", "contact"]),
    content: managedPageContentSchema,
    updatedAt: timestampSchema,
    publishedAt: timestampSchema,
  })
  .strict();

const publicManagedPagesSchema = z
  .array(publicManagedPageSchema)
  .max(3)
  .superRefine((pages, context) => {
    const ids = new Set<string>();
    const slugs = new Set<ManagedPage["slug"]>();
    pages.forEach((page, index) => {
      if (ids.has(page.id)) {
        context.addIssue({
          code: "custom",
          message: "公共静态页面 ID 不能重复",
          path: [index, "id"],
        });
      }
      if (slugs.has(page.slug)) {
        context.addIssue({
          code: "custom",
          message: "公共静态页面路径不能重复",
          path: [index, "slug"],
        });
      }
      ids.add(page.id);
      slugs.add(page.slug);
    });
  });

const publicMediaSchema: z.ZodType<PublicMediaAsset> = z
  .object({
    ...mediaAssetShape,
    visible: z.literal(true),
  })
  .strict();

const publicReleaseSchema: z.ZodType<PublicRelease> = z
  .object({
    ...releaseShape,
    status: z.literal("published"),
  })
  .strict();

const publicDocumentationSchema: z.ZodType<PublicDocumentationSnapshot> = z
  .object({
    id: identifierSchema,
    content: documentationContentSchema,
    updatedAt: timestampSchema,
    publishedAt: timestampSchema,
  })
  .strict();

const publicProductSchema: z.ZodType<PublicProductSnapshot> = z
  .object({
    content: productPageContentSchema.refine(
      (content) => content.blocks.every((block) => block.enabled),
      {
        message: "公共产品内容不能包含已禁用区块",
        path: ["blocks"],
      },
    ),
    updatedAt: timestampSchema,
    publishedAt: timestampSchema,
  })
  .strict();

export const publicWebsiteStateSchema: z.ZodType<PublicWebsiteState> = z
  .object({
    schemaVersion: z.literal(2),
    site: publicSiteSchema,
    pages: publicManagedPagesSchema,
    media: z.array(publicMediaSchema),
    releases: z.array(publicReleaseSchema),
    docs: z.array(publicDocumentationSchema),
    product: publicProductSchema.nullable(),
  })
  .strict();

export const legacyStateSchema = z
  .object({
    schemaVersion: z.literal(1),
    site: z
      .object({
        id: z.literal("site"),
        draft: z.unknown(),
        published: z.unknown(),
        updatedAt: timestampSchema,
        publishedAt: nullableTimestampSchema,
      })
      .strict(),
    pages: managedPagesSchema,
    media: z.array(mediaAssetSchema),
    releases: z.array(releaseSchema),
    docs: z.array(documentationPageSchema),
  })
  .strict();

export type LegacyWebsiteState = z.infer<typeof legacyStateSchema>;

function compact<T>(items: Array<T | null>): T[] {
  return items.filter((item): item is T => item !== null);
}

function projectSite(site: SiteSettings): PublicSiteSnapshot {
  if (!site.publishedAt) throw new Error("站点内容尚未发布");

  return {
    content: structuredClone(site.published),
    updatedAt: site.publishedAt,
    publishedAt: site.publishedAt,
  };
}

function projectPage(page: ManagedPage): PublicManagedPageSnapshot | null {
  if (!page.publishedAt) return null;

  return {
    id: page.id,
    slug: page.slug,
    content: structuredClone(page.published),
    updatedAt: page.publishedAt,
    publishedAt: page.publishedAt,
  };
}

function projectMedia(asset: MediaAsset): PublicMediaAsset | null {
  if (!asset.visible) return null;

  return { ...structuredClone(asset), visible: true };
}

function projectRelease(release: Release): PublicRelease | null {
  if (release.status !== "published") return null;

  return { ...structuredClone(release), status: "published" };
}

function projectDocumentation(
  doc: DocumentationPage,
): PublicDocumentationSnapshot | null {
  if (doc.status !== "published" || !doc.publishedAt) return null;

  return {
    id: doc.id,
    content: structuredClone(doc.published),
    updatedAt: doc.publishedAt,
    publishedAt: doc.publishedAt,
  };
}

function projectProduct(product: ProductPage): PublicProductSnapshot | null {
  if (
    product.status !== "published" ||
    !product.published ||
    !product.publishedAt
  ) {
    return null;
  }

  const content = structuredClone(product.published);
  content.blocks = content.blocks.filter((block) => block.enabled);

  return {
    content,
    updatedAt: product.publishedAt,
    publishedAt: product.publishedAt,
  };
}

export function createPublicWebsiteState(state: WebsiteState): PublicWebsiteState {
  return {
    schemaVersion: 2,
    site: projectSite(state.site),
    pages: compact(state.pages.map(projectPage)),
    media: compact(state.media.map(projectMedia)),
    releases: compact(state.releases.map(projectRelease)),
    docs: compact(state.docs.map(projectDocumentation)),
    product: projectProduct(state.product),
  };
}

export function hydratePublicWebsiteState(state: PublicWebsiteState): WebsiteState {
  const fallback = createSeedState();
  const publishedPageBySlug = new Map(
    state.pages.map((page) => [page.slug, page]),
  );
  const usedPageIds = new Set(state.pages.map((page) => page.id));
  const blankPageContent: ManagedPageContent = {
    title: "",
    summary: "",
    body: "",
    seoTitle: "",
    seoDescription: "",
  };
  const pages = fallback.pages.map((fallbackPage) => {
    const page = publishedPageBySlug.get(fallbackPage.slug);
    if (page) {
      return {
        id: page.id,
        slug: page.slug,
        draft: structuredClone(page.content),
        published: structuredClone(page.content),
        updatedAt: page.updatedAt,
        publishedAt: page.publishedAt,
      };
    }
    let id = `unpublished-${fallbackPage.slug}`;
    while (usedPageIds.has(id)) id = `${id}-placeholder`;
    usedPageIds.add(id);
    return {
      id,
      slug: fallbackPage.slug,
      draft: structuredClone(blankPageContent),
      published: structuredClone(blankPageContent),
      updatedAt: fallbackPage.updatedAt,
      publishedAt: null,
    };
  });

  return {
    schemaVersion: 2,
    site: {
      id: "site",
      draft: structuredClone(state.site.content),
      published: structuredClone(state.site.content),
      updatedAt: state.site.updatedAt,
      publishedAt: state.site.publishedAt,
    },
    pages,
    media: structuredClone(state.media),
    releases: structuredClone(state.releases),
    docs: state.docs.map((doc) => ({
      id: doc.id,
      status: "published",
      draft: structuredClone(doc.content),
      published: structuredClone(doc.content),
      updatedAt: doc.updatedAt,
      publishedAt: doc.publishedAt,
    })),
    product: state.product
      ? {
          id: "product",
          slug: "product",
          status: "published",
          draft: structuredClone(state.product.content),
          published: structuredClone(state.product.content),
          updatedAt: state.product.updatedAt,
          publishedAt: state.product.publishedAt,
        }
      : structuredClone(fallback.product),
  };
}
