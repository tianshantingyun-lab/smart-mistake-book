export type PublicationState = "draft" | "published" | "archived";
export type ReleaseChannel = "stable" | "beta";
export type AnalyticsConsent = "unknown" | "granted" | "denied";

export interface SiteContent {
  brandName: string;
  brandTagline: string;
  heroTitle: string;
  heroDescription: string;
  primaryActionLabel: string;
  secondaryActionLabel: string;
  trustTitle: string;
  trustBody: string;
  analytics: {
    providerName: string;
    siteId: string;
  };
}

export interface Versioned<T> {
  draft: T;
  published: T;
  updatedAt: string;
  publishedAt: string | null;
}

export interface SiteSettings extends Versioned<SiteContent> {
  id: "site";
}

export interface ManagedPageContent {
  title: string;
  summary: string;
  body: string;
  seoTitle: string;
  seoDescription: string;
}

export interface ManagedPage extends Versioned<ManagedPageContent> {
  id: string;
  slug: "about" | "privacy" | "contact";
}

export interface MediaAsset {
  id: string;
  label: string;
  role: "brand" | "hero-review" | "hero-tutor" | "hero-library" | "content";
  src: string;
  alt: string;
  visible: boolean;
  bundled: boolean;
  blobKey?: string;
  mimeType?: string;
  width?: number;
  height?: number;
}

export interface ReleasePackageMetadata {
  fileName: string;
  size: number;
  mimeType: string;
  downloadUrl: string | null;
  sha256: string | null;
  storageState: "metadata-only" | "stored";
}

export interface Release {
  id: string;
  channel: ReleaseChannel;
  versionName: string;
  versionCode: number;
  minAndroid: number;
  releaseNotes: string;
  status: PublicationState;
  package: ReleasePackageMetadata | null;
  createdAt: string;
  publishedAt: string | null;
}

export interface DocumentationContent {
  title: string;
  slug: string;
  summary: string;
  markdown: string;
  parentId: string | null;
  order: number;
}

export interface DocumentationPage extends Versioned<DocumentationContent> {
  id: string;
  status: PublicationState;
}

interface ProductBlockBase {
  id: string;
  enabled: boolean;
}

export interface ProductHeroBlock extends ProductBlockBase {
  type: "hero";
  heading: string;
  lead: string;
  mediaId: string | null;
}

export interface ProductMarkdownBlock extends ProductBlockBase {
  type: "markdown";
  title: string;
  markdown: string;
}

export interface ProductImageTextBlock extends ProductBlockBase {
  type: "image-text";
  eyebrow: string;
  title: string;
  markdown: string;
  mediaId: string | null;
  mediaSide: "left" | "right";
}

export interface ProductGalleryBlock extends ProductBlockBase {
  type: "gallery";
  title: string;
  summary: string;
  mediaIds: string[];
  columns: 2 | 3;
}

export interface ProductListItem {
  id: string;
  title: string;
  body: string;
}

export interface ProductStepsBlock extends ProductBlockBase {
  type: "steps";
  title: string;
  summary: string;
  items: ProductListItem[];
}

export interface ProductCardsBlock extends ProductBlockBase {
  type: "cards";
  title: string;
  summary: string;
  items: ProductListItem[];
  columns: 2 | 3;
}

export interface ProductFaqItem {
  id: string;
  question: string;
  answer: string;
}

export interface ProductFaqBlock extends ProductBlockBase {
  type: "faq";
  title: string;
  items: ProductFaqItem[];
}

export interface ProductCtaBlock extends ProductBlockBase {
  type: "cta";
  title: string;
  body: string;
  label: string;
  href: string;
}

export type ProductPageBlock =
  | ProductHeroBlock
  | ProductMarkdownBlock
  | ProductImageTextBlock
  | ProductGalleryBlock
  | ProductStepsBlock
  | ProductCardsBlock
  | ProductFaqBlock
  | ProductCtaBlock;

export interface ProductPageContent {
  navLabel: string;
  seoTitle: string;
  seoDescription: string;
  showHomepageEntry: boolean;
  homepageEntryLabel: string;
  blocks: ProductPageBlock[];
}

export interface ProductPage {
  id: "product";
  slug: "product";
  status: PublicationState;
  draft: ProductPageContent;
  published: ProductPageContent | null;
  updatedAt: string;
  publishedAt: string | null;
}

export interface AdminSession {
  mode: "demo" | "authenticated";
  displayName: string;
  startedAt: string;
}

export interface WebsiteState {
  schemaVersion: 2;
  localRevision?: number;
  site: SiteSettings;
  pages: ManagedPage[];
  media: MediaAsset[];
  releases: Release[];
  docs: DocumentationPage[];
  product: ProductPage;
}

export interface PublicSiteSnapshot {
  content: SiteContent;
  updatedAt: string;
  publishedAt: string;
}

export interface PublicManagedPageSnapshot {
  id: string;
  slug: ManagedPage["slug"];
  content: ManagedPageContent;
  updatedAt: string;
  publishedAt: string;
}

export type PublicMediaAsset = MediaAsset & { visible: true };
export type PublicRelease = Omit<Release, "status"> & { status: "published" };

export interface PublicDocumentationSnapshot {
  id: string;
  content: DocumentationContent;
  updatedAt: string;
  publishedAt: string;
}

export interface PublicProductSnapshot {
  content: ProductPageContent;
  updatedAt: string;
  publishedAt: string;
}

export interface PublicWebsiteState {
  schemaVersion: 2;
  site: PublicSiteSnapshot;
  pages: PublicManagedPageSnapshot[];
  media: PublicMediaAsset[];
  releases: PublicRelease[];
  docs: PublicDocumentationSnapshot[];
  product: PublicProductSnapshot | null;
}
