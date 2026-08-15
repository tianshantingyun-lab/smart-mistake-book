import { createSeedState } from "../data/seed";
import {
  collectProductMediaIds,
  documentationArchiveError,
  documentationHierarchyError,
  documentationOrderError,
  documentationSlugError,
  managedPagePublicationError,
  releaseDraftError,
  validateApk,
  validateProductPage,
} from "../domain";
import {
  createPublicWebsiteState,
  hydratePublicWebsiteState,
  legacyStateSchema,
  publicWebsiteStateSchema,
  repairWebsiteState,
  websiteStateSchema,
} from "./contentSchemas";
import { getContentApiBaseUrl } from "./gatewayConfig";
import type {
  DocumentationContent,
  ManagedPageContent,
  MediaAsset,
  ProductPageContent,
  Release,
  SiteContent,
  WebsiteState,
} from "../types";

const STORAGE_KEY = "smart-mistake-book.website.v2";
const LEGACY_STORAGE_KEY = "smart-mistake-book.website.v1";
const RECOVERY_STORAGE_KEY = "smart-mistake-book.website.recovery";
const DATABASE_NAME = "smart-mistake-book.website.media";
const DATABASE_STORE = "assets";
const LOCAL_CONTENT_LOCK = "smart-mistake-book.website.content-mutation";
const LOCAL_CONTENT_CONFLICT_MESSAGE =
  "内容已在另一个标签页更新。为避免覆盖，请复制当前未保存内容后刷新页面，再重新编辑";
const LOCAL_CONTENT_LOCK_UNAVAILABLE_MESSAGE =
  "当前浏览器不支持安全的本地多标签编辑，修改未保存。请使用最新版浏览器后重试";

function withLocalContentLock<T>(operation: () => Promise<T>): Promise<T> {
  if (typeof navigator === "undefined" || !navigator.locks) {
    return Promise.reject(
      new ContentGatewayError(LOCAL_CONTENT_LOCK_UNAVAILABLE_MESSAGE, 503),
    );
  }
  return navigator.locks.request(LOCAL_CONTENT_LOCK, operation);
}

function withOptionalInitializationLock<T>(
  operation: (canPersist: boolean) => Promise<T>,
): Promise<T> {
  if (typeof navigator === "undefined" || !navigator.locks) {
    return operation(false);
  }
  return navigator.locks.request(LOCAL_CONTENT_LOCK, () => operation(true));
}

export type ContentScope = "public" | "admin";
export type AnalyticsSettings = SiteContent["analytics"];
export type MediaChanges = Partial<
  Pick<MediaAsset, "label" | "alt" | "visible">
>;
export type AuthorizationFailureStatus = 401 | 403;
type ContentListener = (state: WebsiteState, scope: ContentScope) => void;
type AuthorizationFailureListener = (
  status: AuthorizationFailureStatus,
) => void;

export interface ContentGateway {
  readonly mode: "local-demo" | "remote";
  load(scope: ContentScope): Promise<WebsiteState>;
  confirmAdminAuthentication(): void;
  subscribe(listener: ContentListener): () => void;
  subscribeAuthorizationFailure(
    listener: AuthorizationFailureListener,
  ): () => void;
  reset(): Promise<void>;
  saveSiteDraft(content: SiteContent): Promise<void>;
  publishSite(content?: SiteContent): Promise<void>;
  saveAnalyticsDraft(analytics: AnalyticsSettings): Promise<void>;
  publishAnalytics(): Promise<void>;
  savePageDraft(id: string, content: ManagedPageContent): Promise<void>;
  publishPage(id: string, content?: ManagedPageContent): Promise<void>;
  archivePage(id: string): Promise<void>;
  updateMedia(id: string, changes: MediaChanges): Promise<void>;
  uploadMedia(file: File, details: Pick<MediaAsset, "label" | "alt" | "role">): Promise<void>;
  deleteMedia(id: string): Promise<void>;
  saveReleaseDraft(release: Release): Promise<void>;
  attachReleasePackage(id: string, file: File): Promise<void>;
  publishRelease(id: string): Promise<void>;
  archiveRelease(id: string): Promise<void>;
  saveDocDraft(id: string, content: DocumentationContent): Promise<void>;
  publishDoc(id: string, content?: DocumentationContent): Promise<void>;
  archiveDoc(id: string): Promise<void>;
  saveProductDraft(content: ProductPageContent): Promise<void>;
  publishProduct(): Promise<void>;
  archiveProduct(): Promise<void>;
}

export class ContentGatewayError extends Error {
  constructor(
    message: string,
    readonly status: number,
  ) {
    super(message);
    this.name = "ContentGatewayError";
  }
}

function now(): string {
  return new Date().toISOString();
}

function clone<T>(value: T): T {
  return structuredClone(value);
}

function normalizeMediaChanges(changes: MediaChanges): MediaChanges {
  const candidate = changes as Record<string, unknown>;
  const unsupportedKey = Object.keys(candidate).find(
    (key) => !["label", "alt", "visible"].includes(key),
  );
  if (unsupportedKey) {
    throw new Error(`媒体字段“${unsupportedKey}”不支持修改`);
  }

  const normalized: MediaChanges = {};
  if (Object.hasOwn(candidate, "label")) {
    if (typeof candidate.label !== "string") {
      throw new Error("资源名称必须是文本");
    }
    normalized.label = candidate.label.trim();
  }
  if (Object.hasOwn(candidate, "alt")) {
    if (typeof candidate.alt !== "string") {
      throw new Error("替代文本必须是文本");
    }
    normalized.alt = candidate.alt.trim();
  }
  if (Object.hasOwn(candidate, "visible")) {
    if (typeof candidate.visible !== "boolean") {
      throw new Error("媒体可见性必须是布尔值");
    }
    normalized.visible = candidate.visible;
  }
  return normalized;
}

function copySitePresentation(
  content: SiteContent,
): Omit<SiteContent, "analytics"> {
  const { analytics: _analytics, ...presentation } = content;
  return clone(presentation);
}

function siteContentWithAnalytics(
  content: SiteContent,
  analytics: AnalyticsSettings,
): SiteContent {
  return {
    ...copySitePresentation(content),
    analytics: clone(analytics),
  };
}

function collectVisibleProductMediaIds(
  content: ProductPageContent,
): Set<string> {
  return new Set(
    collectProductMediaIds({
      ...content,
      blocks: content.blocks.filter((block) => block.enabled),
    }),
  );
}

export function analyticsDraftError(
  analytics: AnalyticsSettings,
): string | null {
  const hasProvider = Boolean(analytics.providerName.trim());
  const hasSiteId = Boolean(analytics.siteId.trim());
  if (hasProvider !== hasSiteId) {
    return "统计平台名称与站点 ID 必须同时填写或同时清空";
  }
  return null;
}

export function analyticsPublicationError(
  analytics: AnalyticsSettings,
  privacyPublished: boolean,
): string | null {
  const draftError = analyticsDraftError(analytics);
  if (draftError) return draftError;
  const enabled = Boolean(analytics.providerName.trim());
  if (enabled && !privacyPublished) {
    return "启用分析前必须先发布隐私说明";
  }
  return null;
}

function copyDocumentationContent(
  content: DocumentationContent,
): DocumentationContent {
  return {
    title: content.title,
    slug: content.slug,
    summary: content.summary,
    markdown: content.markdown,
    parentId: content.parentId,
    order: content.order,
  };
}

function emptyDocumentationContent(order: number): DocumentationContent {
  return {
    title: "",
    slug: "",
    summary: "",
    markdown: "",
    parentId: null,
    order,
  };
}

function neutralSiteContent(value: unknown, fallback: SiteContent): SiteContent {
  const candidate =
    value && typeof value === "object"
      ? (value as Partial<SiteContent> & { faq?: unknown })
      : {};
  return {
    brandName:
      typeof candidate.brandName === "string" ? candidate.brandName : fallback.brandName,
    brandTagline:
      typeof candidate.brandTagline === "string"
        ? candidate.brandTagline
        : fallback.brandTagline,
    heroTitle:
      typeof candidate.heroTitle === "string" ? candidate.heroTitle : fallback.heroTitle,
    heroDescription: fallback.heroDescription,
    primaryActionLabel:
      typeof candidate.primaryActionLabel === "string"
        ? candidate.primaryActionLabel
        : fallback.primaryActionLabel,
    secondaryActionLabel:
      typeof candidate.secondaryActionLabel === "string"
        ? candidate.secondaryActionLabel
        : fallback.secondaryActionLabel,
    trustTitle: fallback.trustTitle,
    trustBody: fallback.trustBody,
    analytics:
      candidate.analytics &&
      typeof candidate.analytics.providerName === "string" &&
      typeof candidate.analytics.siteId === "string"
        ? candidate.analytics
        : fallback.analytics,
  };
}

export function migrateLegacyState(value: unknown): WebsiteState {
  const fallback = createSeedState();
  const legacyResult = legacyStateSchema.safeParse(value);
  if (!legacyResult.success) return fallback;
  const legacy = legacyResult.data;
  const pages = structuredClone(legacy.pages);
  const neutralAbout = fallback.pages.find((page) => page.slug === "about");
  const aboutIndex = pages.findIndex((page) => page.slug === "about");
  if (neutralAbout && aboutIndex >= 0) pages[aboutIndex] = neutralAbout;
  else if (neutralAbout) pages.push(neutralAbout);

  const migrated = {
    schemaVersion: 2,
    site: {
      id: "site",
      draft: neutralSiteContent(legacy.site.draft, fallback.site.draft),
      published: neutralSiteContent(legacy.site.published, fallback.site.published),
      updatedAt: legacy.site.updatedAt,
      publishedAt: legacy.site.publishedAt,
    },
    pages,
    media: structuredClone(legacy.media).map((asset) =>
      asset.role === "hero-review" ||
      asset.role === "hero-tutor" ||
      asset.role === "hero-library"
        ? { ...asset, visible: false }
        : asset,
    ),
    releases: structuredClone(legacy.releases),
    docs: structuredClone(legacy.docs),
    product: structuredClone(fallback.product),
  };
  const result = websiteStateSchema.safeParse(migrated);
  return result.success ? result.data : fallback;
}

interface StoredStateResult {
  state: WebsiteState;
  source: "current" | "legacy" | "seed";
  recovery?: {
    current: string | null;
    legacy: string | null;
  };
}

function parseJson(raw: string | null): unknown {
  if (!raw) return undefined;
  try {
    return JSON.parse(raw);
  } catch {
    return undefined;
  }
}

function parseStoredState(
  raw: string | null,
  legacyRaw: string | null,
): StoredStateResult {
  const parsedCurrent = parseJson(raw);
  const currentResult = websiteStateSchema.safeParse(parsedCurrent);
  if (currentResult.success) {
    return { state: currentResult.data, source: "current" };
  }

  const legacyResult = legacyStateSchema.safeParse(parseJson(legacyRaw));
  if (legacyResult.success) {
    return {
      state: migrateLegacyState(legacyResult.data),
      source: "legacy",
      recovery: raw === null ? undefined : { current: raw, legacy: null },
    };
  }
  const repairedCurrent = repairWebsiteState(parsedCurrent);
  if (repairedCurrent) {
    return {
      state: repairedCurrent,
      source: "current",
      recovery: { current: raw, legacy: legacyRaw },
    };
  }

  return {
    state: createSeedState(),
    source: "seed",
    recovery:
      raw === null && legacyRaw === null
        ? undefined
        : { current: raw, legacy: legacyRaw },
  };
}

function openMediaDatabase(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open(DATABASE_NAME, 1);
    let settled = false;
    request.onerror = () => {
      if (settled) return;
      settled = true;
      reject(request.error);
    };
    request.onblocked = () => {
      if (settled) return;
      settled = true;
      reject(new Error("媒体存储正在被其他标签页占用，请关闭其他页面后重试"));
    };
    request.onupgradeneeded = () => {
      if (!request.result.objectStoreNames.contains(DATABASE_STORE)) {
        request.result.createObjectStore(DATABASE_STORE);
      }
    };
    request.onsuccess = () => {
      if (settled) {
        request.result.close();
        return;
      }
      settled = true;
      request.result.onversionchange = () => request.result.close();
      resolve(request.result);
    };
  });
}

function transactionCompletion(transaction: IDBTransaction): Promise<void> {
  return new Promise((resolve, reject) => {
    transaction.oncomplete = () => resolve();
    transaction.onerror = () => reject(transaction.error);
    transaction.onabort = () =>
      reject(transaction.error ?? new Error("媒体存储事务已中止"));
  });
}

async function putMediaBlob(key: string, blob: Blob): Promise<void> {
  const database = await openMediaDatabase();
  try {
    const transaction = database.transaction(DATABASE_STORE, "readwrite");
    transaction.objectStore(DATABASE_STORE).put(blob, key);
    await transactionCompletion(transaction);
  } finally {
    database.close();
  }
}

async function getMediaBlob(key: string): Promise<Blob | null> {
  const database = await openMediaDatabase();
  try {
    const transaction = database.transaction(DATABASE_STORE, "readonly");
    const request = transaction.objectStore(DATABASE_STORE).get(key);
    await transactionCompletion(transaction);
    return (request.result as Blob | undefined) ?? null;
  } finally {
    database.close();
  }
}

async function clearMediaDatabase(): Promise<void> {
  const database = await openMediaDatabase();
  try {
    const transaction = database.transaction(DATABASE_STORE, "readwrite");
    transaction.objectStore(DATABASE_STORE).clear();
    await transactionCompletion(transaction);
  } finally {
    database.close();
  }
}

async function deleteMediaBlob(key: string): Promise<void> {
  const database = await openMediaDatabase();
  try {
    const transaction = database.transaction(DATABASE_STORE, "readwrite");
    transaction.objectStore(DATABASE_STORE).delete(key);
    await transactionCompletion(transaction);
  } finally {
    database.close();
  }
}

async function listMediaBlobKeys(): Promise<string[]> {
  const database = await openMediaDatabase();
  try {
    const transaction = database.transaction(DATABASE_STORE, "readonly");
    const request = transaction.objectStore(DATABASE_STORE).getAllKeys();
    await transactionCompletion(transaction);
    return request.result.filter((key): key is string => typeof key === "string");
  } finally {
    database.close();
  }
}

export interface MediaBlobStore {
  read(key: string): Promise<Blob | null>;
  write(key: string, blob: Blob): Promise<void>;
  clear(): Promise<void>;
  remove(key: string): Promise<void>;
  listKeys?(): Promise<string[]>;
}

const indexedDbMediaBlobStore: MediaBlobStore = {
  read: getMediaBlob,
  write: putMediaBlob,
  clear: clearMediaDatabase,
  remove: deleteMediaBlob,
  listKeys: listMediaBlobKeys,
};

async function hydrateMedia(
  state: WebsiteState,
  createObjectUrl: (blob: Blob) => string,
  readMediaBlob: (key: string) => Promise<Blob | null>,
): Promise<WebsiteState> {
  const hydrated = clone(state);
  await Promise.all(
    hydrated.media.map(async (asset) => {
      if (!asset.blobKey) return;
      try {
        const blob = await readMediaBlob(asset.blobKey);
        asset.src = blob ? createObjectUrl(blob) : "";
      } catch {
        asset.src = "";
      }
    }),
  );
  return hydrated;
}

function persistable(state: WebsiteState): WebsiteState {
  const stored = clone(state);
  stored.media.forEach((asset) => {
    if (asset.blobKey) asset.src = "";
  });
  return stored;
}

async function removeOrphanedMediaBlobs(
  state: WebsiteState,
  mediaBlobStore: MediaBlobStore,
): Promise<void> {
  if (!mediaBlobStore.listKeys) return;
  const referencedKeys = new Set(
    state.media.flatMap((asset) => asset.blobKey ? [asset.blobKey] : []),
  );
  const orphanKeys = (await mediaBlobStore.listKeys()).filter(
    (key) => key.startsWith("media:") && !referencedKeys.has(key),
  );
  await Promise.all(orphanKeys.map((key) => mediaBlobStore.remove(key)));
}

export class LocalContentGateway implements ContentGateway {
  readonly mode = "local-demo" as const;
  private state: WebsiteState | null = null;
  private listeners = new Set<ContentListener>();
  private mutationQueue: Promise<void> = Promise.resolve();
  private initializationPromise: Promise<WebsiteState> | null = null;
  private objectUrls = new Set<string>();

  constructor(
    private readonly mediaBlobStore: MediaBlobStore = indexedDbMediaBlobStore,
  ) {}

  private createObjectUrl(blob: Blob): string {
    const url = URL.createObjectURL(blob);
    this.objectUrls.add(url);
    return url;
  }

  private releaseObjectUrl(url: string | undefined): void {
    if (!url || !this.objectUrls.delete(url)) return;
    URL.revokeObjectURL(url);
  }

  private releaseStateObjectUrls(state: WebsiteState | null): void {
    state?.media.forEach((asset) => this.releaseObjectUrl(asset.src));
  }

  private notify(state: WebsiteState, scope: ContentScope): void {
    this.listeners.forEach((listener) => {
      try {
        listener(clone(state), scope);
      } catch {
        // Subscriber failures happen after commit and must not invalidate durable state.
      }
    });
  }

  private persistInitializedState(
    state: WebsiteState,
    parsed: StoredStateResult,
    addedRevision: boolean,
  ): void {
    if (
      parsed.source === "current"
      && !addedRevision
      && !parsed.recovery
    ) {
      return;
    }
    if (
      parsed.recovery
      && localStorage.getItem(RECOVERY_STORAGE_KEY) === null
    ) {
      localStorage.setItem(
        RECOVERY_STORAGE_KEY,
        JSON.stringify({ ...parsed.recovery, savedAt: now() }),
      );
    }
    localStorage.setItem(STORAGE_KEY, JSON.stringify(persistable(state)));
    if (parsed.source === "legacy") localStorage.removeItem(LEGACY_STORAGE_KEY);
  }

  private async performInitialization(canPersist: boolean): Promise<WebsiteState> {
    const parsed = parseStoredState(
      localStorage.getItem(STORAGE_KEY),
      localStorage.getItem(LEGACY_STORAGE_KEY),
    );
    const createdObjectUrls = new Set<string>();
    try {
      const hydrated = await hydrateMedia(
        parsed.state,
        (blob) => {
          const url = this.createObjectUrl(blob);
          createdObjectUrls.add(url);
          return url;
        },
        (key) => this.mediaBlobStore.read(key),
      );
      if (this.state) {
        createdObjectUrls.forEach((url) => this.releaseObjectUrl(url));
        return this.state;
      }
      const addedRevision = hydrated.localRevision === undefined;
      if (addedRevision) hydrated.localRevision = 1;
      if (canPersist) {
        this.persistInitializedState(hydrated, parsed, addedRevision);
        if (!parsed.recovery) {
          try {
            await removeOrphanedMediaBlobs(hydrated, this.mediaBlobStore);
          } catch {
            // Orphan cleanup is best-effort and must not prevent content recovery.
          }
        }
      }
      this.state = hydrated;
      return hydrated;
    } catch (error) {
      createdObjectUrls.forEach((url) => this.releaseObjectUrl(url));
      throw error;
    }
  }

  private finishInitialization(initialization: Promise<WebsiteState>): void {
    if (this.initializationPromise === initialization) {
      this.initializationPromise = null;
    }
  }

  private initialize(): Promise<WebsiteState> {
    if (this.state) return Promise.resolve(this.state);
    if (this.initializationPromise) return this.initializationPromise;

    const initialization = withOptionalInitializationLock((canPersist) =>
      this.performInitialization(canPersist)
    );

    this.initializationPromise = initialization;
    void initialization.then(
      () => this.finishInitialization(initialization),
      () => this.finishInitialization(initialization),
    );
    return initialization;
  }

  async load(scope: ContentScope): Promise<WebsiteState> {
    const state = await this.initialize();
    return scope === "public"
      ? hydratePublicWebsiteState(createPublicWebsiteState(state))
      : clone(state);
  }

  subscribe(listener: ContentListener): () => void {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }

  subscribeAuthorizationFailure(
    _listener: AuthorizationFailureListener,
  ): () => void {
    return () => undefined;
  }

  confirmAdminAuthentication(): void {
    // Local demo authentication does not cross a remote authorization boundary.
  }

  private enqueue(operation: () => Promise<void>): Promise<void> {
    const run = async () => {
      await this.initialize();
      await withLocalContentLock(operation);
    };
    const result = this.mutationQueue.then(run, run);
    this.mutationQueue = result.catch(() => undefined);
    return result;
  }

  private mutate(mutator: (state: WebsiteState) => void): Promise<void> {
    return this.enqueue(() => this.commitMutation(mutator));
  }

  private async commitMutation(
    mutator: (state: WebsiteState) => void,
  ): Promise<void> {
    if (!this.state) throw new Error("本地内容尚未初始化");
    const durableResult = websiteStateSchema.safeParse(
      parseJson(localStorage.getItem(STORAGE_KEY)),
    );
    if (!durableResult.success) {
      throw new ContentGatewayError(
        "本地内容存储已损坏。为避免覆盖，当前修改未保存，请刷新页面后检查恢复状态",
        409,
      );
    }
    const durableRevision = durableResult.data.localRevision ?? 0;
    if (durableRevision !== (this.state.localRevision ?? 0)) {
      throw new ContentGatewayError(LOCAL_CONTENT_CONFLICT_MESSAGE, 409);
    }

    const state = clone(this.state);
    mutator(state);
    state.localRevision = durableRevision + 1;
    const stored = persistable(state);
    const validation = websiteStateSchema.safeParse(stored);
    if (!validation.success) {
      throw new Error(
        "内容数据无效，未保存。请检查必填字段和数字是否为允许范围内的整数",
      );
    }
    localStorage.setItem(STORAGE_KEY, JSON.stringify(validation.data));
    this.state = state;
    this.notify(state, "admin");
  }

  reset(): Promise<void> {
    return this.enqueue(async () => {
      await this.initialize();
      const previousCurrent = localStorage.getItem(STORAGE_KEY);
      const previousLegacy = localStorage.getItem(LEGACY_STORAGE_KEY);
      const previousRecovery = localStorage.getItem(RECOVERY_STORAGE_KEY);
      const previousState = this.state;
      const state = createSeedState();
      const durableResult = websiteStateSchema.safeParse(
        parseJson(previousCurrent),
      );
      state.localRevision =
        (durableResult.success
          ? durableResult.data.localRevision ?? 0
          : previousState?.localRevision ?? 0) + 1;
      localStorage.setItem(STORAGE_KEY, JSON.stringify(persistable(state)));
      try {
        localStorage.removeItem(LEGACY_STORAGE_KEY);
        localStorage.removeItem(RECOVERY_STORAGE_KEY);
        await this.mediaBlobStore.clear();
      } catch (error) {
        if (previousCurrent === null) {
          localStorage.removeItem(STORAGE_KEY);
        } else {
          localStorage.setItem(STORAGE_KEY, previousCurrent);
        }
        if (previousLegacy === null) {
          localStorage.removeItem(LEGACY_STORAGE_KEY);
        } else {
          localStorage.setItem(LEGACY_STORAGE_KEY, previousLegacy);
        }
        if (previousRecovery === null) {
          localStorage.removeItem(RECOVERY_STORAGE_KEY);
        } else {
          localStorage.setItem(RECOVERY_STORAGE_KEY, previousRecovery);
        }
        throw error;
      }
      this.state = state;
      this.releaseStateObjectUrls(previousState);
      this.notify(state, "admin");
    });
  }

  saveSiteDraft(content: SiteContent): Promise<void> {
    return this.mutate((state) => {
      state.site.draft = siteContentWithAnalytics(
        content,
        state.site.draft.analytics,
      );
      state.site.updatedAt = now();
    });
  }

  publishSite(content?: SiteContent): Promise<void> {
    return this.mutate((state) => {
      if (content) {
        state.site.draft = siteContentWithAnalytics(
          content,
          state.site.draft.analytics,
        );
      }
      state.site.published = siteContentWithAnalytics(
        state.site.draft,
        state.site.published.analytics,
      );
      state.site.publishedAt = now();
      state.site.updatedAt = now();
    });
  }

  saveAnalyticsDraft(analytics: AnalyticsSettings): Promise<void> {
    const error = analyticsDraftError(analytics);
    if (error) return Promise.reject(new Error(error));
    return this.mutate((state) => {
      state.site.draft.analytics = clone(analytics);
      state.site.updatedAt = now();
    });
  }

  publishAnalytics(): Promise<void> {
    return this.mutate((state) => {
      const privacyPublished = state.pages.some(
        (page) => page.slug === "privacy" && Boolean(page.publishedAt),
      );
      const error = analyticsPublicationError(
        state.site.draft.analytics,
        privacyPublished,
      );
      if (error) throw new Error(error);
      state.site.published.analytics = clone(state.site.draft.analytics);
      state.site.publishedAt = now();
      state.site.updatedAt = now();
    });
  }

  savePageDraft(id: string, content: ManagedPageContent): Promise<void> {
    return this.mutate((state) => {
      const page = state.pages.find((candidate) => candidate.id === id);
      if (!page) throw new Error("页面不存在");
      page.draft = clone(content);
      page.updatedAt = now();
    });
  }

  publishPage(id: string, content?: ManagedPageContent): Promise<void> {
    return this.mutate((state) => {
      const page = state.pages.find((candidate) => candidate.id === id);
      if (!page) throw new Error("页面不存在");
      const publication = content ?? page.draft;
      const error = managedPagePublicationError(publication);
      if (error) throw new Error(error);
      if (content) page.draft = clone(content);
      page.published = clone(page.draft);
      page.publishedAt = now();
      page.updatedAt = now();
    });
  }

  archivePage(id: string): Promise<void> {
    return this.mutate((state) => {
      const page = state.pages.find((candidate) => candidate.id === id);
      if (!page) throw new Error("页面不存在");
      page.publishedAt = null;
      page.updatedAt = now();
    });
  }

  async updateMedia(id: string, changes: MediaChanges): Promise<void> {
    const normalized = normalizeMediaChanges(changes);
    if (!Object.keys(normalized).length) return;

    await this.mutate((state) => {
      const index = state.media.findIndex((candidate) => candidate.id === id);
      if (index < 0) throw new Error("媒体不存在");
      const existing = state.media[index];
      const updated = { ...existing, ...normalized };
      if (!updated.label || !updated.alt) {
        throw new Error("资源名称与替代文本均不能为空");
      }
      const publishedReferences =
        state.product.status === "published" && state.product.published
          ? collectVisibleProductMediaIds(state.product.published)
          : new Set<string>();
      if (existing.visible && !updated.visible && publishedReferences.has(id)) {
        throw new Error("该图片正被已发布产品页使用，请先更新或归档产品页");
      }
      state.media[index] = updated;
    });
  }

  uploadMedia(
    file: File,
    details: Pick<MediaAsset, "label" | "alt" | "role">,
  ): Promise<void> {
    if (!["image/png", "image/jpeg", "image/webp"].includes(file.type)) {
      return Promise.reject(new Error("仅支持 PNG、JPEG 或 WebP 图片"));
    }
    if (file.size > 5 * 1024 * 1024) {
      return Promise.reject(new Error("图片不能超过 5 MB"));
    }
    const id = crypto.randomUUID();
    const blobKey = `media:${id}`;

    return this.enqueue(async () => {
      let objectUrl: string | undefined;
      try {
        await this.mediaBlobStore.write(blobKey, file);
        const stagedObjectUrl = this.createObjectUrl(file);
        objectUrl = stagedObjectUrl;
        await this.commitMutation((state) => {
          state.media.push({
            id,
            ...details,
            src: stagedObjectUrl,
            visible: true,
            bundled: false,
            blobKey,
            mimeType: file.type,
          });
        });
      } catch (error) {
        this.releaseObjectUrl(objectUrl);
        try {
          await this.mediaBlobStore.remove(blobKey);
        } catch {
          // Preserve the original storage error if cleanup is unavailable.
        }
        throw error;
      }
    });
  }

  async deleteMedia(id: string): Promise<void> {
    let blobKey: string | undefined;
    let objectUrl: string | undefined;
    await this.mutate((state) => {
      const asset = state.media.find((candidate) => candidate.id === id);
      if (!asset) throw new Error("媒体不存在");
      if (asset.bundled) throw new Error("内置资源不能删除，可以选择隐藏");

      const referenced = new Set([
        ...collectProductMediaIds(state.product.draft),
        ...(state.product.published
          ? collectProductMediaIds(state.product.published)
          : []),
      ]);
      if (referenced.has(id)) {
        throw new Error("该图片仍被产品页引用，请先从草稿和已发布内容中移除");
      }

      blobKey = asset.blobKey;
      objectUrl = asset.src;
      state.media = state.media.filter((candidate) => candidate.id !== id);
    });

    if (blobKey) {
      try {
        await this.mediaBlobStore.remove(blobKey);
      } catch {
        // Metadata is authoritative. A failed best-effort blob cleanup must not restore a deleted item.
      }
    }
    this.releaseObjectUrl(objectUrl);
  }

  saveReleaseDraft(release: Release): Promise<void> {
    const validationError = releaseDraftError(release);
    if (validationError) return Promise.reject(new Error(validationError));
    return this.mutate((state) => {
      const index = state.releases.findIndex((candidate) => candidate.id === release.id);
      if (index >= 0 && state.releases[index].status !== "draft") {
        throw new Error("已发布或归档版本不可直接修改，请复制为新草稿");
      }
      const draft = { ...clone(release), status: "draft" as const };
      if (index < 0) state.releases.push(draft);
      else state.releases[index] = draft;
    });
  }

  async attachReleasePackage(id: string, file: File): Promise<void> {
    const errors = validateApk(file);
    if (errors.length) throw new Error(errors[0]);
    await this.mutate((state) => {
      const release = state.releases.find((candidate) => candidate.id === id);
      if (!release) throw new Error("请先保存版本草稿");
      if (release.status !== "draft") {
        throw new Error("已发布或归档版本不能替换安装包");
      }
      release.package = {
        fileName: file.name,
        size: file.size,
        mimeType: file.type || "application/vnd.android.package-archive",
        downloadUrl: null,
        sha256: null,
        storageState: "metadata-only",
      };
    });
  }

  publishRelease(id: string): Promise<void> {
    return this.mutate((state) => {
      const release = state.releases.find((candidate) => candidate.id === id);
      if (!release) throw new Error("版本不存在");
      const validationError = releaseDraftError(release);
      if (validationError) throw new Error(validationError);
      if (!release.package) throw new Error("请先选择 APK 文件");
      release.status = "published";
      release.publishedAt = now();
    });
  }

  archiveRelease(id: string): Promise<void> {
    return this.mutate((state) => {
      const release = state.releases.find((candidate) => candidate.id === id);
      if (!release) throw new Error("版本不存在");
      release.status = "archived";
    });
  }

  saveDocDraft(id: string, content: DocumentationContent): Promise<void> {
    const validationError = documentationOrderError(content.order);
    if (validationError) return Promise.reject(new Error(validationError));
    return this.mutate((state) => {
      const doc = state.docs.find((candidate) => candidate.id === id);
      const draft = copyDocumentationContent(content);
      if (doc) {
        doc.draft = draft;
        doc.updatedAt = now();
        return;
      }
      state.docs.push({
        id,
        status: "draft",
        draft,
        published: emptyDocumentationContent(draft.order),
        updatedAt: now(),
        publishedAt: null,
      });
    });
  }

  publishDoc(id: string, content?: DocumentationContent): Promise<void> {
    if (content) {
      const validationError = documentationOrderError(content.order);
      if (validationError) return Promise.reject(new Error(validationError));
    }
    return this.mutate((state) => {
      let doc = state.docs.find((candidate) => candidate.id === id);
      if (content) {
        const draft = copyDocumentationContent(content);
        if (doc) {
          doc.draft = draft;
        } else {
          doc = {
            id,
            status: "draft",
            draft,
            published: emptyDocumentationContent(draft.order),
            updatedAt: now(),
            publishedAt: null,
          };
          state.docs.push(doc);
        }
      }
      if (!doc) throw new Error("文档不存在");
      const orderError = documentationOrderError(doc.draft.order);
      if (orderError) throw new Error(orderError);
      if (!doc.draft.title.trim() || !doc.draft.slug.trim() || !doc.draft.markdown.trim()) {
        throw new Error("标题、路径和正文均不能为空");
      }
      const slugError = documentationSlugError(doc.id, doc.draft.slug, state.docs);
      if (slugError) throw new Error(slugError);
      const hierarchyError = documentationHierarchyError(
        doc.id,
        doc.draft,
        state.docs,
      );
      if (hierarchyError) throw new Error(hierarchyError);
      doc.published = clone(doc.draft);
      doc.status = "published";
      doc.publishedAt = now();
      doc.updatedAt = now();
    });
  }

  archiveDoc(id: string): Promise<void> {
    return this.mutate((state) => {
      const doc = state.docs.find((candidate) => candidate.id === id);
      if (!doc) throw new Error("文档不存在");
      const hierarchyError = documentationArchiveError(id, state.docs);
      if (hierarchyError) throw new Error(hierarchyError);
      doc.status = "archived";
      doc.updatedAt = now();
    });
  }

  saveProductDraft(content: ProductPageContent): Promise<void> {
    return this.mutate((state) => {
      state.product.draft = clone(content);
      if (state.product.status === "archived") state.product.status = "draft";
      state.product.updatedAt = now();
    });
  }

  publishProduct(): Promise<void> {
    return this.mutate((state) => {
      const errors = validateProductPage(state.product.draft, state.media);
      if (errors.length) throw new Error(errors[0]);
      state.product.published = clone(state.product.draft);
      state.product.status = "published";
      state.product.publishedAt = now();
      state.product.updatedAt = now();
    });
  }

  archiveProduct(): Promise<void> {
    return this.mutate((state) => {
      state.product.status = "archived";
      state.product.updatedAt = now();
    });
  }
}

export class RestContentGateway implements ContentGateway {
  readonly mode = "remote" as const;
  private listeners = new Set<ContentListener>();
  private authorizationFailureListeners =
    new Set<AuthorizationFailureListener>();
  private adminQueue: Promise<void> = Promise.resolve();
  private authorizationEpoch = 0;
  private authorizationRevoked = false;
  private adminRecoveryRequired = false;
  private adminRecoveryEpoch: number | null = null;
  private resyncRequired = true;
  private adminEtag: string | null = null;
  private publicRequestSequence = 0;

  constructor(private readonly baseUrl: string) {}

  subscribe(listener: ContentListener): () => void {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }

  subscribeAuthorizationFailure(
    listener: AuthorizationFailureListener,
  ): () => void {
    this.authorizationFailureListeners.add(listener);
    return () => this.authorizationFailureListeners.delete(listener);
  }

  confirmAdminAuthentication(): void {
    this.authorizationEpoch += 1;
    this.adminRecoveryRequired = true;
    this.adminRecoveryEpoch = this.authorizationEpoch;
    this.markResyncRequired();
  }

  private notifyAuthorizationFailure(
    status: AuthorizationFailureStatus,
  ): void {
    this.authorizationFailureListeners.forEach((listener) => {
      try {
        listener(status);
      } catch {
        // Authorization invalidation must reach every subscriber and preserve the HTTP error.
      }
    });
  }

  private notify(state: WebsiteState, scope: ContentScope): WebsiteState {
    this.listeners.forEach((listener) => {
      try {
        listener(clone(state), scope);
      } catch {
        // A subscriber runs after the response is validated and cannot roll back it.
      }
    });
    return state;
  }

  private enqueueAdmin<T>(
    operation: (capturedEpoch: number) => Promise<T>,
  ): Promise<T> {
    const capturedEpoch = this.authorizationEpoch;
    const result = this.adminQueue.then(
      () => operation(capturedEpoch),
      () => operation(capturedEpoch),
    );
    this.adminQueue = result.then(
      () => undefined,
      () => undefined,
    );
    return result;
  }

  private markResyncRequired(): void {
    this.resyncRequired = true;
    this.adminEtag = null;
  }

  private markAuthorizationRevoked(
    status: AuthorizationFailureStatus,
  ): void {
    const shouldNotify = !this.authorizationRevoked;
    this.authorizationRevoked = true;
    this.adminRecoveryRequired = true;
    this.authorizationEpoch += 1;
    this.adminRecoveryEpoch = null;
    this.markResyncRequired();
    if (shouldNotify) this.notifyAuthorizationFailure(status);
  }

  private assertCurrentAdminEpoch(capturedEpoch: number): void {
    if (capturedEpoch === this.authorizationEpoch) return;
    if (this.authorizationRevoked) throw this.staleAuthorizationError();
    throw new ContentGatewayError(
      "管理授权上下文已变化，已忽略旧请求",
      409,
    );
  }

  private staleAuthorizationError(): ContentGatewayError {
    return new ContentGatewayError(
      "管理会话已失效，请重新登录并重新加载管理数据",
      401,
    );
  }

  private resyncRequiredError(): ContentGatewayError {
    return new ContentGatewayError(
      "管理内容状态需要重新同步，请先重新加载管理数据",
      409,
    );
  }

  private isStrongEtag(value: string | null): value is string {
    if (value === null) return false;
    const trimmed = value.trim();
    return /^"[\x21\x23-\x7e\x80-\xff]*"$/.test(trimmed);
  }

  private async fetchResponse(
    path: string,
    init?: RequestInit,
  ): Promise<Response> {
    const hasJsonBody = init?.body != null && !(init.body instanceof FormData);
    const headers = new Headers(init?.headers);
    headers.set("Accept", "application/json");
    if (hasJsonBody) headers.set("Content-Type", "application/json");
    return fetch(`${this.baseUrl}${path}`, {
      ...init,
      headers,
      credentials: "include",
    });
  }

  private async responseError(response: Response): Promise<ContentGatewayError> {
    let message = "服务请求失败";
    try {
      message = (await response.text()) || message;
    } catch {
      // The HTTP status remains authoritative even if its optional body is unreadable.
    }
    return new ContentGatewayError(message, response.status);
  }

  private async publicResponsePayload(path: string): Promise<unknown> {
    const response = await this.fetchResponse(path);
    if (!response.ok) {
      throw await this.responseError(response);
    }
    try {
      return await response.json();
    } catch {
      throw new ContentGatewayError("内容服务返回了无法解析的数据", response.status);
    }
  }

  private async parseAdminResponse(
    response: Response,
    operation: "load" | "mutation",
    capturedEpoch: number,
  ): Promise<WebsiteState> {
    if (!response.ok) {
      if (response.status === 401 || response.status === 403) {
        this.markAuthorizationRevoked(response.status);
      } else if (
        response.status >= 500 ||
        (operation === "mutation" &&
          (response.status === 412 || response.status === 428))
      ) {
        this.markResyncRequired();
      }
      throw await this.responseError(response);
    }

    let payload: unknown;
    try {
      payload = await response.json();
    } catch {
      this.assertCurrentAdminEpoch(capturedEpoch);
      this.markResyncRequired();
      throw new ContentGatewayError(
        "内容服务返回了无法解析的数据，必须重新同步",
        response.status,
      );
    }

    const result = websiteStateSchema.safeParse(payload);
    if (!result.success) {
      this.assertCurrentAdminEpoch(capturedEpoch);
      this.markResyncRequired();
      throw new ContentGatewayError(
        "内容服务返回的数据结构无效，必须重新同步",
        502,
      );
    }

    this.assertCurrentAdminEpoch(capturedEpoch);
    const responseEtag = response.headers.get("ETag");
    if (!this.isStrongEtag(responseEtag)) {
      this.markResyncRequired();
      throw new ContentGatewayError(
        "管理内容响应缺少有效的强 ETag，必须重新同步",
        502,
      );
    }

    this.adminEtag = responseEtag.trim();
    this.resyncRequired = false;
    return result.data;
  }

  private async performAdminLoad(capturedEpoch: number): Promise<WebsiteState> {
    this.assertCurrentAdminEpoch(capturedEpoch);
    const isRecoveryLoad = this.adminRecoveryEpoch === capturedEpoch;
    if (
      (this.authorizationRevoked || this.adminRecoveryRequired) &&
      !isRecoveryLoad
    ) {
      throw this.staleAuthorizationError();
    }
    if (isRecoveryLoad) this.adminRecoveryEpoch = null;

    let response: Response;
    try {
      response = await this.fetchResponse("/api/v1/admin/state");
    } catch (error) {
      this.markResyncRequired();
      throw error;
    }

    this.assertCurrentAdminEpoch(capturedEpoch);
    const state = await this.parseAdminResponse(
      response,
      "load",
      capturedEpoch,
    );
    this.assertCurrentAdminEpoch(capturedEpoch);
    if (isRecoveryLoad) {
      this.authorizationRevoked = false;
      this.adminRecoveryRequired = false;
      this.authorizationEpoch += 1;
    }
    return this.notify(state, "admin");
  }

  private request(
    path: string,
    init?: RequestInit,
  ): Promise<WebsiteState> {
    const revokedAtEnqueue = this.authorizationRevoked;
    return this.enqueueAdmin(async (capturedEpoch) => {
      if (revokedAtEnqueue || this.authorizationRevoked) {
        throw this.staleAuthorizationError();
      }
      this.assertCurrentAdminEpoch(capturedEpoch);
      if (this.resyncRequired || !this.adminEtag) {
        throw this.resyncRequiredError();
      }

      const headers = new Headers(init?.headers);
      headers.set("If-Match", this.adminEtag);

      let response: Response;
      try {
        response = await this.fetchResponse(path, { ...init, headers });
      } catch (error) {
        this.markResyncRequired();
        throw error;
      }

      this.assertCurrentAdminEpoch(capturedEpoch);
      const state = await this.parseAdminResponse(
        response,
        "mutation",
        capturedEpoch,
      );
      this.assertCurrentAdminEpoch(capturedEpoch);
      return this.notify(state, "admin");
    });
  }

  private async requestPublic(requestSequence: number): Promise<WebsiteState> {
    const result = publicWebsiteStateSchema.safeParse(
      await this.publicResponsePayload("/api/v1/public/state"),
    );
    if (!result.success) {
      throw new ContentGatewayError("公开内容服务返回的数据结构无效", 502);
    }
    const state = hydratePublicWebsiteState(result.data);
    if (requestSequence === this.publicRequestSequence) {
      this.notify(state, "public");
    }
    return state;
  }

  load(scope: ContentScope): Promise<WebsiteState> {
    if (scope === "admin") {
      return this.enqueueAdmin((capturedEpoch) =>
        this.performAdminLoad(capturedEpoch),
      );
    }
    const requestSequence = ++this.publicRequestSequence;
    return this.requestPublic(requestSequence);
  }

  async reset(): Promise<void> {
    await this.request("/api/v1/admin/demo/reset", { method: "POST" });
  }

  async saveSiteDraft(content: SiteContent): Promise<void> {
    await this.request("/api/v1/admin/site/draft", {
      method: "PUT",
      body: JSON.stringify(copySitePresentation(content)),
    });
  }

  async publishSite(content?: SiteContent): Promise<void> {
    await this.request("/api/v1/admin/site/publish", {
      method: "POST",
      ...(content ? { body: JSON.stringify(copySitePresentation(content)) } : {}),
    });
  }

  async saveAnalyticsDraft(analytics: AnalyticsSettings): Promise<void> {
    const error = analyticsDraftError(analytics);
    if (error) throw new Error(error);
    await this.request("/api/v1/admin/analytics/draft", {
      method: "PUT",
      body: JSON.stringify(analytics),
    });
  }

  async publishAnalytics(): Promise<void> {
    await this.request("/api/v1/admin/analytics/publish", { method: "POST" });
  }

  async savePageDraft(id: string, content: ManagedPageContent): Promise<void> {
    await this.request(`/api/v1/admin/pages/${id}/draft`, {
      method: "PUT",
      body: JSON.stringify(content),
    });
  }

  async publishPage(
    id: string,
    content?: ManagedPageContent,
  ): Promise<void> {
    const error = content ? managedPagePublicationError(content) : null;
    if (error) throw new Error(error);
    await this.request(`/api/v1/admin/pages/${id}/publish`, {
      method: "POST",
      ...(content ? { body: JSON.stringify(content) } : {}),
    });
  }

  async archivePage(id: string): Promise<void> {
    await this.request(`/api/v1/admin/pages/${id}/archive`, {
      method: "POST",
    });
  }

  async updateMedia(id: string, changes: MediaChanges): Promise<void> {
    const normalized = normalizeMediaChanges(changes);
    if (!Object.keys(normalized).length) return;

    await this.request(`/api/v1/admin/media/${id}`, {
      method: "PATCH",
      body: JSON.stringify(normalized),
    });
  }

  async uploadMedia(
    file: File,
    details: Pick<MediaAsset, "label" | "alt" | "role">,
  ): Promise<void> {
    const form = new FormData();
    form.append("file", file);
    Object.entries(details).forEach(([key, value]) => form.append(key, value));
    await this.request("/api/v1/admin/media", { method: "POST", body: form });
  }

  async deleteMedia(id: string): Promise<void> {
    await this.request(`/api/v1/admin/media/${id}`, { method: "DELETE" });
  }

  async saveReleaseDraft(release: Release): Promise<void> {
    const validationError = releaseDraftError(release);
    if (validationError) throw new Error(validationError);
    await this.request(`/api/v1/admin/releases/${release.id}`, {
      method: "PUT",
      body: JSON.stringify(release),
    });
  }

  async attachReleasePackage(id: string, file: File): Promise<void> {
    const form = new FormData();
    form.append("file", file);
    await this.request(`/api/v1/admin/releases/${id}/package`, {
      method: "POST",
      body: form,
    });
  }

  async publishRelease(id: string): Promise<void> {
    await this.request(`/api/v1/admin/releases/${id}/publish`, { method: "POST" });
  }

  async archiveRelease(id: string): Promise<void> {
    await this.request(`/api/v1/admin/releases/${id}/archive`, { method: "POST" });
  }

  async saveDocDraft(id: string, content: DocumentationContent): Promise<void> {
    const validationError = documentationOrderError(content.order);
    if (validationError) throw new Error(validationError);
    await this.request(`/api/v1/admin/docs/${id}`, {
      method: "PUT",
      body: JSON.stringify(copyDocumentationContent(content)),
    });
  }

  async publishDoc(id: string, content?: DocumentationContent): Promise<void> {
    if (content) {
      const validationError = documentationOrderError(content.order);
      if (validationError) throw new Error(validationError);
    }
    await this.request(`/api/v1/admin/docs/${id}/publish`, {
      method: "POST",
      ...(content
        ? { body: JSON.stringify(copyDocumentationContent(content)) }
        : {}),
    });
  }

  async archiveDoc(id: string): Promise<void> {
    await this.request(`/api/v1/admin/docs/${id}/archive`, { method: "POST" });
  }

  async saveProductDraft(content: ProductPageContent): Promise<void> {
    await this.request("/api/v1/admin/product/draft", {
      method: "PUT",
      body: JSON.stringify(content),
    });
  }

  async publishProduct(): Promise<void> {
    await this.request("/api/v1/admin/product/publish", { method: "POST" });
  }

  async archiveProduct(): Promise<void> {
    await this.request("/api/v1/admin/product/archive", { method: "POST" });
  }
}

export function createContentGateway(): ContentGateway {
  const baseUrl = getContentApiBaseUrl();
  return baseUrl ? new RestContentGateway(baseUrl) : new LocalContentGateway();
}
