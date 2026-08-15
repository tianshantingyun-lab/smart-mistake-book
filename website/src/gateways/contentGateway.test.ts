import { beforeEach, describe, expect, it, vi } from "vitest";
import { createSeedState } from "../data/seed";
import { websiteStateSchema } from "./contentSchemas";
import {
  ContentGatewayError,
  LocalContentGateway,
  RestContentGateway,
} from "./contentGateway";
import type { MediaBlobStore } from "./contentGateway";
import type { DocumentationContent, ProductPageContent, Release } from "../types";

function createDeferred<T>() {
  let resolve!: (value: T | PromiseLike<T>) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise;
    reject = rejectPromise;
  });
  return { promise, resolve, reject };
}

function jsonResponse(
  body: unknown,
  status = 200,
  etag: string | null = '"state-1"',
): Response {
  const headers = new Headers({ "Content-Type": "application/json" });
  if (etag !== null) headers.set("ETag", etag);
  return new Response(JSON.stringify(body), {
    status,
    headers,
  });
}

function createMemoryMediaBlobStore() {
  const blobs = new Map<string, Blob>();
  const store: MediaBlobStore = {
    read: async (key) => blobs.get(key) ?? null,
    write: async (key, blob) => {
      blobs.set(key, blob);
    },
    clear: async () => {
      blobs.clear();
    },
    remove: async (key) => {
      blobs.delete(key);
    },
  };
  return { blobs, store };
}

async function countMediaBlobs(): Promise<number> {
  const database = await new Promise<IDBDatabase>((resolve, reject) => {
    const request = indexedDB.open("smart-mistake-book.website.media", 1);
    request.onupgradeneeded = () => {
      if (!request.result.objectStoreNames.contains("assets")) {
        request.result.createObjectStore("assets");
      }
    };
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
  });
  const count = await new Promise<number>((resolve, reject) => {
    const request = database.transaction("assets", "readonly").objectStore("assets").count();
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
  });
  database.close();
  return count;
}

async function clearMediaBlobs(): Promise<void> {
  const database = await new Promise<IDBDatabase>((resolve, reject) => {
    const request = indexedDB.open("smart-mistake-book.website.media", 1);
    request.onupgradeneeded = () => {
      if (!request.result.objectStoreNames.contains("assets")) {
        request.result.createObjectStore("assets");
      }
    };
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
  });
  await new Promise<void>((resolve, reject) => {
    const transaction = database.transaction("assets", "readwrite");
    transaction.objectStore("assets").clear();
    transaction.oncomplete = () => resolve();
    transaction.onerror = () => reject(transaction.error);
  });
  database.close();
}

describe("LocalContentGateway", () => {
  beforeEach(async () => {
    localStorage.clear();
    await clearMediaBlobs();
  });

  it("falls back to seeded state when storage is corrupt", async () => {
    localStorage.setItem("smart-mistake-book.website.v2", "{broken");
    const gateway = new LocalContentGateway();
    const state = await gateway.load("admin");
    expect(state.schemaVersion).toBe(2);
    expect(state.site.published.brandName).toBe("智能错题本");
    expect(
      websiteStateSchema.safeParse(
        JSON.parse(localStorage.getItem("smart-mistake-book.website.v2")!),
      ).success,
    ).toBe(true);
    expect(
      JSON.parse(
        localStorage.getItem("smart-mistake-book.website.recovery")!,
      ),
    ).toMatchObject({
      current: "{broken",
      legacy: null,
      savedAt: expect.any(String),
    });
  });

  it("removes unreferenced media blobs after a valid initialization", async () => {
    const { blobs, store } = createMemoryMediaBlobStore();
    blobs.set("media:orphan", new Blob(["orphan"]));
    blobs.set("future-store:key", new Blob(["unrelated"]));
    store.listKeys = async () => [...blobs.keys()];
    const gateway = new LocalContentGateway(store);

    await gateway.load("admin");

    expect(blobs.has("media:orphan")).toBe(false);
    expect(blobs.has("future-store:key")).toBe(true);
  });

  it("repairs parseable storage with malformed nested data", async () => {
    const malformed = createSeedState();
    malformed.site.draft.heroTitle = "保留的自定义首页";
    malformed.site.published.analytics.siteId = 42 as never;
    localStorage.setItem(
      "smart-mistake-book.website.v2",
      JSON.stringify(malformed),
    );

    const gateway = new LocalContentGateway();
    const state = await gateway.load("admin");
    expect(state.site.draft.heroTitle).toBe("保留的自定义首页");
    expect(state.site.published.analytics.siteId).toBe("");
    expect(state.media).toHaveLength(malformed.media.length);
    expect(
      websiteStateSchema.safeParse(
        JSON.parse(localStorage.getItem("smart-mistake-book.website.v2")!),
      ).success,
    ).toBe(true);
  });

  it("uses a valid legacy snapshot when the current snapshot is invalid", async () => {
    const seeded = createSeedState();
    const { product: _product, ...legacyBase } = seeded;
    const legacy = {
      ...legacyBase,
      schemaVersion: 1,
      site: {
        ...seeded.site,
        draft: { ...seeded.site.draft, brandName: "迁移后的品牌" },
        published: { ...seeded.site.published, brandName: "迁移后的品牌" },
      },
    };
    localStorage.setItem(
      "smart-mistake-book.website.v2",
      JSON.stringify({ schemaVersion: 2, site: {} }),
    );
    localStorage.setItem(
      "smart-mistake-book.website.v1",
      JSON.stringify(legacy),
    );

    const gateway = new LocalContentGateway();
    const state = await gateway.load("admin");
    expect(state.site.published.brandName).toBe("迁移后的品牌");
    expect(localStorage.getItem("smart-mistake-book.website.v1")).toBeNull();
    expect(
      websiteStateSchema.safeParse(
        JSON.parse(localStorage.getItem("smart-mistake-book.website.v2")!),
      ).success,
    ).toBe(true);
  });

  it("migrates v1 data while removing legacy feature claims", async () => {
    const seeded = createSeedState();
    const { product: _product, ...legacyBase } = seeded;
    const legacy = {
      ...legacyBase,
      schemaVersion: 1,
      site: {
        ...seeded.site,
        draft: {
          ...seeded.site.draft,
          heroDescription: "拍下错题，智能识别与整理。",
          faq: [{ question: "会直接讲题吗？", answer: "会。" }],
        },
        published: {
          ...seeded.site.published,
          heroDescription: "拍下错题，智能识别与整理。",
          faq: [{ question: "会直接讲题吗？", answer: "会。" }],
        },
      },
      media: seeded.media.map((asset) => ({ ...asset, visible: true })),
    };
    localStorage.setItem("smart-mistake-book.website.v1", JSON.stringify(legacy));

    const gateway = new LocalContentGateway();
    const state = await gateway.load("admin");
    expect(state.schemaVersion).toBe(2);
    expect(state.site.published.heroDescription).toContain("功能仍在确认中");
    expect(state.pages.find((page) => page.slug === "about")?.published.body).not.toContain(
      "讲解",
    );
    expect(
      state.media
        .filter((asset) => asset.role.startsWith("hero-"))
        .every((asset) => !asset.visible),
    ).toBe(true);
    expect(state.product.status).toBe("draft");
    expect(state.product.published).toBeNull();
    expect(localStorage.getItem("smart-mistake-book.website.v2")).not.toBeNull();
    expect(localStorage.getItem("smart-mistake-book.website.v1")).toBeNull();
  });

  it("returns a public projection that cannot expose local drafts", async () => {
    const gateway = new LocalContentGateway();
    const admin = await gateway.load("admin");
    await gateway.saveSiteDraft({
      ...admin.site.draft,
      heroTitle: "仅管理员可见的标题",
    });

    const publicState = await gateway.load("public");
    expect(publicState.site.draft.heroTitle).toBe(
      publicState.site.published.heroTitle,
    );
    expect(publicState.site.published.heroTitle).not.toBe("仅管理员可见的标题");
    expect(publicState.media.every((asset) => asset.visible)).toBe(true);
    expect(publicState.releases.every((release) => release.status === "published")).toBe(
      true,
    );
  });

  it("keeps drafts private until publish", async () => {
    const gateway = new LocalContentGateway();
    const state = await gateway.load("admin");
    await gateway.saveSiteDraft({ ...state.site.draft, heroTitle: "新的草稿标题" });
    expect((await gateway.load("admin")).site.published.heroTitle).not.toBe("新的草稿标题");
    await gateway.publishSite();
    expect((await gateway.load("admin")).site.published.heroTitle).toBe("新的草稿标题");
  });

  it("publishes analytics without exposing an unapproved homepage draft", async () => {
    const gateway = new LocalContentGateway();
    const initial = await gateway.load("admin");
    const publicHero = initial.site.published.heroTitle;
    await gateway.saveSiteDraft({
      ...initial.site.draft,
      heroTitle: "仅管理员可见的首页草稿",
    });
    await gateway.saveAnalyticsDraft({
      providerName: "自建分析",
      siteId: "site-123",
    });

    await gateway.publishAnalytics();

    const admin = await gateway.load("admin");
    const publicState = await gateway.load("public");
    expect(admin.site.draft.heroTitle).toBe("仅管理员可见的首页草稿");
    expect(admin.site.published.heroTitle).toBe(publicHero);
    expect(publicState.site.published.heroTitle).toBe(publicHero);
    expect(publicState.site.published.analytics).toEqual({
      providerName: "自建分析",
      siteId: "site-123",
    });

    await gateway.saveAnalyticsDraft({ providerName: "", siteId: "" });
    await expect(gateway.publishAnalytics()).resolves.toBeUndefined();
    expect((await gateway.load("public")).site.published.analytics).toEqual({
      providerName: "",
      siteId: "",
    });
  });

  it("keeps site saves and publishes isolated from analytics in both directions", async () => {
    const gateway = new LocalContentGateway();
    const initial = await gateway.load("admin");
    await gateway.saveAnalyticsDraft({
      providerName: "已批准分析",
      siteId: "approved-site",
    });
    await gateway.publishAnalytics();
    await gateway.saveAnalyticsDraft({
      providerName: "尚未批准分析",
      siteId: "draft-site",
    });

    await gateway.saveSiteDraft({
      ...initial.site.draft,
      heroTitle: "新的首页草稿",
      analytics: {
        providerName: "来自过期表单的值",
        siteId: "stale-site",
      },
    });
    expect((await gateway.load("admin")).site.draft.analytics).toEqual({
      providerName: "尚未批准分析",
      siteId: "draft-site",
    });

    await gateway.publishSite();
    const saved = await gateway.load("admin");
    expect(saved.site.published.heroTitle).toBe("新的首页草稿");
    expect(saved.site.published.analytics).toEqual({
      providerName: "已批准分析",
      siteId: "approved-site",
    });
    expect(saved.site.draft.analytics).toEqual({
      providerName: "尚未批准分析",
      siteId: "draft-site",
    });
  });

  it("atomically publishes supplied site and static-page content", async () => {
    const gateway = new LocalContentGateway();
    const initial = await gateway.load("admin");
    const about = initial.pages.find((page) => page.slug === "about")!;
    const publishedSite = {
      ...initial.site.draft,
      heroTitle: "原子发布的首页",
    };
    const laterSiteDraft = {
      ...initial.site.draft,
      heroTitle: "随后保存的首页草稿",
    };
    const publishedPage = {
      ...about.draft,
      title: "原子发布的关于页",
    };
    const laterPageDraft = {
      ...about.draft,
      title: "随后保存的关于页草稿",
    };

    await Promise.all([
      gateway.publishSite(publishedSite),
      gateway.saveSiteDraft(laterSiteDraft),
      gateway.publishPage(about.id, publishedPage),
      gateway.savePageDraft(about.id, laterPageDraft),
    ]);

    const saved = await gateway.load("admin");
    expect(saved.site.published.heroTitle).toBe("原子发布的首页");
    expect(saved.site.draft.heroTitle).toBe("随后保存的首页草稿");
    const savedAbout = saved.pages.find((page) => page.id === about.id)!;
    expect(savedAbout.published.title).toBe("原子发布的关于页");
    expect(savedAbout.draft.title).toBe("随后保存的关于页草稿");
  });

  it("rejects incomplete static pages and can archive a published page", async () => {
    const gateway = new LocalContentGateway();
    const initial = await gateway.load("admin");
    const about = initial.pages.find((page) => page.slug === "about")!;

    await expect(
      gateway.publishPage(about.id, { ...about.draft, body: " " }),
    ).rejects.toThrow(
      "请填写页面标题、摘要、正文、SEO 标题和 SEO 描述后再发布",
    );

    await gateway.publishPage(about.id, about.draft);
    await gateway.archivePage(about.id);

    const admin = await gateway.load("admin");
    expect(
      admin.pages.find((page) => page.id === about.id)?.publishedAt,
    ).toBeNull();
    const publicState = await gateway.load("public");
    expect(publicState.pages.some((page) => page.id === about.id)).toBe(false);
  });

  it("rejects incomplete analytics and enabled analytics without published privacy", async () => {
    const gateway = new LocalContentGateway();
    await expect(
      gateway.saveAnalyticsDraft({
        providerName: "只填写平台",
        siteId: "",
      }),
    ).rejects.toThrow(
      "统计平台名称与站点 ID 必须同时填写或同时清空",
    );
    expect((await gateway.load("admin")).site.draft.analytics).toEqual({
      providerName: "",
      siteId: "",
    });

    const withoutPrivacy = createSeedState();
    withoutPrivacy.pages.find((page) => page.slug === "privacy")!.publishedAt =
      null;
    localStorage.setItem(
      "smart-mistake-book.website.v2",
      JSON.stringify(withoutPrivacy),
    );
    const privacyGateway = new LocalContentGateway();
    await privacyGateway.saveAnalyticsDraft({
      providerName: "自建分析",
      siteId: "site-123",
    });
    await expect(privacyGateway.publishAnalytics()).rejects.toThrow(
      "启用分析前必须先发布隐私说明",
    );
    expect(
      (await privacyGateway.load("admin")).site.published.analytics,
    ).toEqual({ providerName: "", siteId: "" });
  });

  it("serializes overlapping saves without losing either change", async () => {
    const gateway = new LocalContentGateway();
    const state = await gateway.load("admin");
    const about = state.pages.find((page) => page.slug === "about")!;

    await Promise.all([
      gateway.saveSiteDraft({ ...state.site.draft, heroTitle: "并发保存后的首页" }),
      gateway.savePageDraft(about.id, { ...about.draft, title: "并发保存后的关于页" }),
    ]);

    const saved = await gateway.load("admin");
    expect(saved.site.draft.heroTitle).toBe("并发保存后的首页");
    expect(saved.pages.find((page) => page.id === about.id)?.draft.title).toBe(
      "并发保存后的关于页",
    );
    const persisted = JSON.parse(
      localStorage.getItem("smart-mistake-book.website.v2")!,
    ) as ReturnType<typeof createSeedState>;
    expect(persisted.site.draft.heroTitle).toBe("并发保存后的首页");
    expect(persisted.pages.find((page) => page.id === about.id)?.draft.title).toBe(
      "并发保存后的关于页",
    );
  });

  it("rejects a stale gateway instead of overwriting another tab's save", async () => {
    const firstTab = new LocalContentGateway();
    const secondTab = new LocalContentGateway();
    const firstState = await firstTab.load("admin");
    const secondState = await secondTab.load("admin");
    const about = secondState.pages.find((page) => page.slug === "about")!;

    await firstTab.saveSiteDraft({
      ...firstState.site.draft,
      heroTitle: "标签页 A 保存的首页",
    });

    await expect(
      secondTab.savePageDraft(about.id, {
        ...about.draft,
        title: "标签页 B 的过期关于页",
      }),
    ).rejects.toMatchObject({
      message: expect.stringContaining("另一个标签页"),
      status: 409,
    });

    const persisted = websiteStateSchema.parse(
      JSON.parse(localStorage.getItem("smart-mistake-book.website.v2")!),
    );
    expect(persisted.site.draft.heroTitle).toBe("标签页 A 保存的首页");
    expect(persisted.pages.find((page) => page.id === about.id)?.draft.title).toBe(
      about.draft.title,
    );
    expect(persisted.localRevision).toBeGreaterThan(
      secondState.localRevision ?? 0,
    );
  });

  it("keeps content readable but fails closed when cross-tab locking is unavailable", async () => {
    const lockManager = navigator.locks;
    Object.defineProperty(navigator, "locks", {
      configurable: true,
      value: undefined,
    });
    try {
      const gateway = new LocalContentGateway();
      const state = await gateway.load("admin");
      expect(state.site.published.brandName).toBe("智能错题本");

      await expect(
        gateway.saveSiteDraft({
          ...state.site.draft,
          heroTitle: "不应在无锁环境保存",
        }),
      ).rejects.toMatchObject({
        message: expect.stringContaining("不支持安全的本地多标签编辑"),
        status: 503,
      });
      expect(localStorage.getItem("smart-mistake-book.website.v2")).toBeNull();
    } finally {
      Object.defineProperty(navigator, "locks", {
        configurable: true,
        value: lockManager,
      });
    }
  });

  it("serializes separate gateways so only one same-revision writer can commit", async () => {
    const firstTab = new LocalContentGateway();
    const secondTab = new LocalContentGateway();
    const firstState = await firstTab.load("admin");
    const secondState = await secondTab.load("admin");
    const about = secondState.pages.find((page) => page.slug === "about")!;

    const results = await Promise.allSettled([
      firstTab.saveSiteDraft({
        ...firstState.site.draft,
        heroTitle: "跨标签并发首页",
      }),
      secondTab.savePageDraft(about.id, {
        ...about.draft,
        title: "跨标签并发关于页",
      }),
    ]);

    expect(results.filter((result) => result.status === "fulfilled")).toHaveLength(1);
    expect(results.filter((result) => result.status === "rejected")).toHaveLength(1);
    const persisted = websiteStateSchema.parse(
      JSON.parse(localStorage.getItem("smart-mistake-book.website.v2")!),
    );
    const committedChanges = [
      persisted.site.draft.heroTitle === "跨标签并发首页",
      persisted.pages.find((page) => page.id === about.id)?.draft.title
        === "跨标签并发关于页",
    ];
    expect(committedChanges.filter(Boolean)).toHaveLength(1);
  });

  it("holds the shared lock across asynchronous work from separate gateways", async () => {
    const writeStarted = createDeferred<void>();
    const releaseWrite = createDeferred<void>();
    const { blobs, store } = createMemoryMediaBlobStore();
    store.write = async (key, blob) => {
      writeStarted.resolve();
      await releaseWrite.promise;
      blobs.set(key, blob);
    };
    const uploadTab = new LocalContentGateway(store);
    const editTab = new LocalContentGateway();
    await uploadTab.load("admin");
    const editState = await editTab.load("admin");
    const createObjectUrl = vi
      .spyOn(URL, "createObjectURL")
      .mockReturnValue("blob:locked-upload");

    const upload = uploadTab.uploadMedia(
      new File(["image"], "locked.png", { type: "image/png" }),
      {
        label: "锁内图片",
        alt: "锁内图片替代文本",
        role: "content",
      },
    );
    await writeStarted.promise;

    let editSettled = false;
    const edit = editTab
      .saveSiteDraft({
        ...editState.site.draft,
        heroTitle: "不应越过上传锁",
      })
      .finally(() => {
        editSettled = true;
      });
    await Promise.resolve();
    await Promise.resolve();

    expect(editSettled).toBe(false);
    expect(
      websiteStateSchema.parse(
        JSON.parse(localStorage.getItem("smart-mistake-book.website.v2")!),
      ).site.draft.heroTitle,
    ).not.toBe("不应越过上传锁");

    releaseWrite.resolve();
    await expect(upload).resolves.toBeUndefined();
    await expect(edit).rejects.toMatchObject({ status: 409 });
    createObjectUrl.mockRestore();
  });

  it("removes a staged upload when another tab has already advanced the revision", async () => {
    const firstTab = new LocalContentGateway();
    const staleTab = new LocalContentGateway();
    const firstState = await firstTab.load("admin");
    await staleTab.load("admin");
    const createObjectUrl = vi
      .spyOn(URL, "createObjectURL")
      .mockReturnValue("blob:stale-tab-upload");
    const revokeObjectUrl = vi
      .spyOn(URL, "revokeObjectURL")
      .mockImplementation(() => undefined);

    await firstTab.saveSiteDraft({
      ...firstState.site.draft,
      heroTitle: "先提交的标签页",
    });

    await expect(
      staleTab.uploadMedia(
        new File(["stale image"], "stale.png", { type: "image/png" }),
        {
          label: "不应遗留的图片",
          alt: "不应遗留的图片替代文本",
          role: "content",
        },
      ),
    ).rejects.toThrow("另一个标签页");

    expect(await countMediaBlobs()).toBe(0);
    expect(revokeObjectUrl).toHaveBeenCalledWith("blob:stale-tab-upload");
    const persisted = websiteStateSchema.parse(
      JSON.parse(localStorage.getItem("smart-mistake-book.website.v2")!),
    );
    expect(
      persisted.media.some((asset) => asset.label === "不应遗留的图片"),
    ).toBe(false);

    createObjectUrl.mockRestore();
    revokeObjectUrl.mockRestore();
  });

  it("prevents a stale tab from resurrecting content after reset", async () => {
    const resetTab = new LocalContentGateway();
    const staleTab = new LocalContentGateway();
    const staleState = await staleTab.load("admin");
    await resetTab.load("admin");

    await resetTab.reset();
    await expect(
      staleTab.saveSiteDraft({
        ...staleState.site.draft,
        heroTitle: "不应复活的旧内容",
      }),
    ).rejects.toThrow("另一个标签页");

    const persisted = websiteStateSchema.parse(
      JSON.parse(localStorage.getItem("smart-mistake-book.website.v2")!),
    );
    expect(persisted.site.draft.heroTitle).not.toBe("不应复活的旧内容");
  });

  it("shares first hydration and prevents a later hydration from undoing reset", async () => {
    const stored = createSeedState();
    stored.media.push({
      id: "hydration-race",
      label: "旧媒体",
      alt: "旧媒体替代文本",
      role: "content",
      src: "",
      visible: true,
      bundled: false,
      blobKey: "media:hydration-race",
      mimeType: "image/png",
    });
    localStorage.setItem(
      "smart-mistake-book.website.v2",
      JSON.stringify(stored),
    );

    const hydration = createDeferred<Blob | null>();
    const { blobs, store: memoryStore } = createMemoryMediaBlobStore();
    blobs.set("media:hydration-race", new Blob(["old image"], { type: "image/png" }));
    const read = vi.fn(() => hydration.promise);
    const clear = vi.fn(memoryStore.clear);
    const store: MediaBlobStore = { ...memoryStore, read, clear };
    const createObjectUrl = vi
      .spyOn(URL, "createObjectURL")
      .mockReturnValue("blob:hydration-race");
    const revokeObjectUrl = vi
      .spyOn(URL, "revokeObjectURL")
      .mockImplementation(() => undefined);
    const gateway = new LocalContentGateway(store);

    const firstLoad = gateway.load("admin");
    const secondLoad = gateway.load("admin");
    await vi.waitFor(() => expect(read).toHaveBeenCalledOnce());
    const reset = gateway.reset();
    hydration.resolve(blobs.get("media:hydration-race") ?? null);

    await Promise.all([firstLoad, secondLoad, reset]);

    expect(read).toHaveBeenCalledOnce();
    expect(clear).toHaveBeenCalledOnce();
    expect(createObjectUrl).toHaveBeenCalledOnce();
    expect(revokeObjectUrl).toHaveBeenCalledWith("blob:hydration-race");
    expect((await gateway.load("admin")).media.some((asset) => asset.id === "hydration-race"))
      .toBe(false);
    expect(
      (await new LocalContentGateway(store).load("admin")).media.some(
        (asset) => asset.id === "hydration-race",
      ),
    ).toBe(false);
    expect(blobs.size).toBe(0);

    createObjectUrl.mockRestore();
    revokeObjectUrl.mockRestore();
  });

  it("retries failed shared initialization and releases its object URLs", async () => {
    const seeded = createSeedState();
    const { product: _product, ...legacyBase } = seeded;
    const legacy = {
      ...legacyBase,
      schemaVersion: 1,
      media: [
        ...seeded.media,
        {
          id: "retry-media",
          label: "重试媒体",
          alt: "重试媒体替代文本",
          role: "content",
          src: "",
          visible: true,
          bundled: false,
          blobKey: "media:retry",
          mimeType: "image/png",
        },
      ],
    };
    localStorage.setItem("smart-mistake-book.website.v1", JSON.stringify(legacy));

    const hydration = createDeferred<Blob | null>();
    const blob = new Blob(["retry image"], { type: "image/png" });
    const { store: memoryStore } = createMemoryMediaBlobStore();
    const read = vi
      .fn()
      .mockReturnValueOnce(hydration.promise)
      .mockResolvedValue(blob);
    const store: MediaBlobStore = { ...memoryStore, read };
    const createdUrls = ["blob:failed-initialization", "blob:retried-initialization"];
    const createObjectUrl = vi
      .spyOn(URL, "createObjectURL")
      .mockImplementation(() => createdUrls.shift() ?? "blob:unexpected");
    const revokeObjectUrl = vi
      .spyOn(URL, "revokeObjectURL")
      .mockImplementation(() => undefined);
    const storageError = vi
      .spyOn(localStorage, "setItem")
      .mockImplementationOnce(() => {
        throw new Error("initialization storage unavailable");
      });
    const gateway = new LocalContentGateway(store);

    const firstLoad = gateway.load("admin");
    const secondLoad = gateway.load("admin");
    await vi.waitFor(() => expect(read).toHaveBeenCalledOnce());
    hydration.resolve(blob);

    const failedLoads = await Promise.allSettled([firstLoad, secondLoad]);
    expect(failedLoads.every((result) => result.status === "rejected")).toBe(true);
    expect(read).toHaveBeenCalledOnce();
    expect(revokeObjectUrl).toHaveBeenCalledWith("blob:failed-initialization");

    storageError.mockRestore();
    const retried = await gateway.load("admin");
    expect(read).toHaveBeenCalledTimes(2);
    expect(retried.media.find((asset) => asset.id === "retry-media")?.src).toBe(
      "blob:retried-initialization",
    );
    expect(localStorage.getItem("smart-mistake-book.website.v1")).toBeNull();

    createObjectUrl.mockRestore();
    revokeObjectUrl.mockRestore();
  });

  it("keeps in-memory and persisted state aligned when storage rejects a save", async () => {
    const gateway = new LocalContentGateway();
    const original = await gateway.load("admin");
    const storageError = vi
      .spyOn(localStorage, "setItem")
      .mockImplementationOnce(() => {
        throw new Error("storage unavailable");
      });

    await expect(
      gateway.saveSiteDraft({ ...original.site.draft, heroTitle: "不应进入内存" }),
    ).rejects.toThrow("storage unavailable");
    expect((await gateway.load("admin")).site.draft.heroTitle).toBe(
      original.site.draft.heroTitle,
    );

    storageError.mockRestore();
    const fresh = new LocalContentGateway();
    expect((await fresh.load("admin")).site.draft.heroTitle).toBe(
      original.site.draft.heroTitle,
    );
    await gateway.saveSiteDraft({ ...original.site.draft, heroTitle: "队列仍然可用" });
    expect((await gateway.load("admin")).site.draft.heroTitle).toBe("队列仍然可用");
  });

  it("removes a staged media blob when its metadata commit fails", async () => {
    const gateway = new LocalContentGateway();
    const original = await gateway.load("admin");
    const beforeBlobCount = await countMediaBlobs();
    const createObjectUrl = vi
      .spyOn(URL, "createObjectURL")
      .mockReturnValue("blob:test-media");
    const revokeObjectUrl = vi.spyOn(URL, "revokeObjectURL").mockImplementation(() => undefined);
    const storageError = vi
      .spyOn(localStorage, "setItem")
      .mockImplementationOnce(() => {
        throw new Error("metadata storage unavailable");
      });

    await expect(
      gateway.uploadMedia(
        new File(["image"], "test.png", { type: "image/png" }),
        { label: "测试图片", alt: "测试图片替代文本", role: "content" },
      ),
    ).rejects.toThrow("metadata storage unavailable");

    storageError.mockRestore();
    createObjectUrl.mockRestore();
    expect((await gateway.load("admin")).media).toHaveLength(original.media.length);
    expect(await countMediaBlobs()).toBe(beforeBlobCount);
    expect(revokeObjectUrl).toHaveBeenCalledOnce();
    expect(revokeObjectUrl).toHaveBeenCalledWith("blob:test-media");
    revokeObjectUrl.mockRestore();
  });

  it("closes the IndexedDB connection when a media write fails", async () => {
    const open = vi.spyOn(indexedDB, "open");
    const close = vi.spyOn(IDBDatabase.prototype, "close");
    const put = vi
      .spyOn(IDBObjectStore.prototype, "put")
      .mockImplementationOnce(() => {
        throw new Error("media write setup failed");
      });
    const gateway = new LocalContentGateway();

    await expect(
      gateway.uploadMedia(
        new File(["image"], "failed.png", { type: "image/png" }),
        {
          label: "写入失败图片",
          alt: "写入失败图片替代文本",
          role: "content",
        },
      ),
    ).rejects.toThrow("media write setup failed");

    const openedDatabases = open.mock.results.map(
      ({ value }) => (value as IDBOpenDBRequest).result,
    );
    expect(openedDatabases.length).toBeGreaterThan(1);
    openedDatabases.forEach((database) => {
      expect(
        close.mock.instances.filter((instance) => instance === database),
      ).toHaveLength(1);
    });
    expect(close.mock.instances).toHaveLength(openedDatabases.length);
    put.mockRestore();
    close.mockRestore();
    open.mockRestore();
  });

  it("hydrates a successfully uploaded media blob after a fresh gateway load", async () => {
    const createdUrls = ["blob:upload-live", "blob:upload-reloaded"];
    const createObjectUrl = vi
      .spyOn(URL, "createObjectURL")
      .mockImplementation(() => createdUrls.shift() ?? "blob:unexpected");
    const gateway = new LocalContentGateway();

    await gateway.uploadMedia(
      new File(["persistent image"], "persisted.png", { type: "image/png" }),
      { label: "持久化图片", alt: "持久化图片替代文本", role: "content" },
    );

    const persisted = JSON.parse(
      localStorage.getItem("smart-mistake-book.website.v2")!,
    ) as ReturnType<typeof createSeedState>;
    const persistedAsset = persisted.media.find((asset) => asset.label === "持久化图片");
    expect(persistedAsset?.src).toBe("");
    expect(persistedAsset?.blobKey).toMatch(/^media:/);
    expect(await countMediaBlobs()).toBe(1);

    const reloaded = await new LocalContentGateway().load("admin");
    expect(reloaded.media.find((asset) => asset.label === "持久化图片")?.src).toBe(
      "blob:upload-reloaded",
    );
    expect(createObjectUrl).toHaveBeenCalledTimes(2);
    createObjectUrl.mockRestore();
  });

  it("serializes a deferred media write with reset so metadata and blobs stay aligned", async () => {
    const writeStarted = createDeferred<void>();
    const allowWrite = createDeferred<void>();
    const { blobs, store: memoryStore } = createMemoryMediaBlobStore();
    const write = vi.fn(async (key: string, blob: Blob) => {
      writeStarted.resolve(undefined);
      await allowWrite.promise;
      blobs.set(key, blob);
    });
    const clear = vi.fn(memoryStore.clear);
    const store: MediaBlobStore = { ...memoryStore, write, clear };
    const createObjectUrl = vi
      .spyOn(URL, "createObjectURL")
      .mockReturnValue("blob:queued-upload");
    const revokeObjectUrl = vi
      .spyOn(URL, "revokeObjectURL")
      .mockImplementation(() => undefined);
    const gateway = new LocalContentGateway(store);

    const upload = gateway.uploadMedia(
      new File(["queued image"], "queued.png", { type: "image/png" }),
      { label: "队列图片", alt: "队列图片替代文本", role: "content" },
    );
    await writeStarted.promise;
    const reset = gateway.reset();
    allowWrite.resolve(undefined);

    await Promise.all([upload, reset]);

    const persisted = websiteStateSchema.parse(
      JSON.parse(localStorage.getItem("smart-mistake-book.website.v2")!),
    );
    const reloaded = await new LocalContentGateway(store).load("admin");
    expect(write).toHaveBeenCalledOnce();
    expect(clear).toHaveBeenCalledOnce();
    expect(persisted.media.some((asset) => asset.label === "队列图片")).toBe(false);
    expect(reloaded.media.some((asset) => asset.label === "队列图片")).toBe(false);
    expect(blobs.size).toBe(0);
    expect(revokeObjectUrl).toHaveBeenCalledWith("blob:queued-upload");

    createObjectUrl.mockRestore();
    revokeObjectUrl.mockRestore();
  });

  it("keeps a committed upload intact when one subscriber throws", async () => {
    let objectUrlIndex = 0;
    const createObjectUrl = vi
      .spyOn(URL, "createObjectURL")
      .mockImplementation(() => `blob:subscriber-${++objectUrlIndex}`);
    const revokeObjectUrl = vi.spyOn(URL, "revokeObjectURL").mockImplementation(() => undefined);
    const gateway = new LocalContentGateway();
    await gateway.load("admin");
    const healthyListener = vi.fn();
    gateway.subscribe(() => {
      throw new Error("broken subscriber");
    });
    gateway.subscribe(healthyListener);

    await expect(
      gateway.uploadMedia(
        new File(["image"], "subscriber.png", { type: "image/png" }),
        { label: "订阅者图片", alt: "订阅者图片替代文本", role: "content" },
      ),
    ).resolves.toBeUndefined();

    expect(healthyListener).toHaveBeenCalledOnce();
    expect(await countMediaBlobs()).toBe(1);
    expect(revokeObjectUrl).not.toHaveBeenCalled();
    const reloaded = await new LocalContentGateway().load("admin");
    expect(reloaded.media.find((asset) => asset.label === "订阅者图片")?.src).toBe(
      "blob:subscriber-2",
    );

    createObjectUrl.mockRestore();
    revokeObjectUrl.mockRestore();
  });

  it("resets metadata and blobs together, then emits one fresh admin snapshot", async () => {
    const createObjectUrl = vi
      .spyOn(URL, "createObjectURL")
      .mockReturnValue("blob:reset-media");
    const revokeObjectUrl = vi.spyOn(URL, "revokeObjectURL").mockImplementation(() => undefined);
    const gateway = new LocalContentGateway();
    await gateway.uploadMedia(
      new File(["image"], "reset.png", { type: "image/png" }),
      { label: "待重置图片", alt: "待重置图片替代文本", role: "content" },
    );
    localStorage.setItem("smart-mistake-book.website.v1", '{"schemaVersion":1}');
    const notifications: Array<{ state: ReturnType<typeof createSeedState>; scope: string }> = [];
    gateway.subscribe((state, scope) => notifications.push({ state, scope }));

    await gateway.reset();

    const persisted = websiteStateSchema.parse(
      JSON.parse(localStorage.getItem("smart-mistake-book.website.v2")!),
    );
    expect(localStorage.getItem("smart-mistake-book.website.v1")).toBeNull();
    expect(persisted.media.some((asset) => asset.label === "待重置图片")).toBe(false);
    expect(await countMediaBlobs()).toBe(0);
    expect(notifications).toHaveLength(1);
    expect(notifications[0].scope).toBe("admin");
    expect(notifications[0].state.site.published.brandName).toBe("智能错题本");
    expect(revokeObjectUrl).toHaveBeenCalledWith("blob:reset-media");

    createObjectUrl.mockRestore();
    revokeObjectUrl.mockRestore();
  });

  it("keeps metadata, blobs, and subscribers unchanged when reset cannot persist", async () => {
    const createObjectUrl = vi
      .spyOn(URL, "createObjectURL")
      .mockReturnValue("blob:reset-rollback");
    const revokeObjectUrl = vi.spyOn(URL, "revokeObjectURL").mockImplementation(() => undefined);
    const gateway = new LocalContentGateway();
    await gateway.uploadMedia(
      new File(["image"], "rollback.png", { type: "image/png" }),
      { label: "保留图片", alt: "保留图片替代文本", role: "content" },
    );
    localStorage.setItem("smart-mistake-book.website.v1", '{"schemaVersion":1}');
    const previous = localStorage.getItem("smart-mistake-book.website.v2");
    const listener = vi.fn();
    gateway.subscribe(listener);
    const storageError = vi
      .spyOn(localStorage, "setItem")
      .mockImplementationOnce(() => {
        throw new Error("reset storage unavailable");
      });

    await expect(gateway.reset()).rejects.toThrow("reset storage unavailable");

    storageError.mockRestore();
    expect(localStorage.getItem("smart-mistake-book.website.v2")).toBe(previous);
    expect(localStorage.getItem("smart-mistake-book.website.v1")).toBe('{"schemaVersion":1}');
    expect(await countMediaBlobs()).toBe(1);
    expect((await gateway.load("admin")).media.some((asset) => asset.label === "保留图片")).toBe(
      true,
    );
    expect(listener).not.toHaveBeenCalled();
    expect(revokeObjectUrl).not.toHaveBeenCalled();

    createObjectUrl.mockRestore();
    revokeObjectUrl.mockRestore();
  });

  it("releases an uploaded media URL after deleting its metadata and blob", async () => {
    const createObjectUrl = vi
      .spyOn(URL, "createObjectURL")
      .mockReturnValue("blob:delete-media");
    const revokeObjectUrl = vi.spyOn(URL, "revokeObjectURL").mockImplementation(() => undefined);
    const gateway = new LocalContentGateway();
    await gateway.uploadMedia(
      new File(["image"], "delete.png", { type: "image/png" }),
      { label: "待删除图片", alt: "待删除图片替代文本", role: "content" },
    );
    const uploaded = (await gateway.load("admin")).media.find(
      (asset) => asset.label === "待删除图片",
    )!;

    await gateway.deleteMedia(uploaded.id);

    expect(await countMediaBlobs()).toBe(0);
    expect(revokeObjectUrl).toHaveBeenCalledOnce();
    expect(revokeObjectUrl).toHaveBeenCalledWith("blob:delete-media");
    createObjectUrl.mockRestore();
    revokeObjectUrl.mockRestore();
  });

  it("creates documentation as a draft even when callers forge publication fields", async () => {
    const gateway = new LocalContentGateway();
    const content = {
      title: "快速开始",
      slug: "quick-start",
      summary: "第一篇文档",
      markdown: "# 快速开始\n\n欢迎使用。",
      parentId: null,
      order: 0,
    };
    const forged = {
      ...content,
      status: "published",
      published: { ...content, title: "伪造公开内容" },
      publishedAt: "2026-07-26T00:00:00.000Z",
    } as DocumentationContent;

    await gateway.saveDocDraft("doc-1", forged);
    const saved = (await gateway.load("admin")).docs[0];

    expect(saved.status).toBe("draft");
    expect(saved.published.title).toBe("");
    expect(saved.publishedAt).toBeNull();
    expect(saved.draft).toEqual(content);
  });

  it("keeps the published documentation unchanged when only its draft is saved", async () => {
    const gateway = new LocalContentGateway();
    const publishedContent = {
      title: "快速开始",
      slug: "quick-start",
      summary: "第一篇文档",
      markdown: "# 快速开始\n\n欢迎使用。",
      parentId: null,
      order: 0,
    };
    await gateway.saveDocDraft("doc-1", publishedContent);
    await gateway.publishDoc("doc-1");
    const before = (await gateway.load("admin")).docs[0];

    await gateway.saveDocDraft("doc-1", {
      ...publishedContent,
      title: "尚未发布的新标题",
      markdown: "# 私密草稿",
    });
    const after = (await gateway.load("admin")).docs[0];
    const publicState = await gateway.load("public");

    expect(after.status).toBe("published");
    expect(after.published).toEqual(before.published);
    expect(after.publishedAt).toBe(before.publishedAt);
    expect(after.draft.title).toBe("尚未发布的新标题");
    expect(publicState.docs[0].published.title).toBe("快速开始");
  });

  it("atomically saves, validates, and publishes supplied documentation content", async () => {
    const gateway = new LocalContentGateway();
    const publishedContent = {
      title: "原子发布文档",
      slug: "atomic-guide",
      summary: "一次请求保存并发布",
      markdown: "# 原子发布\n\n公开版本。",
      parentId: null,
      order: 0,
    };
    const laterDraft = {
      ...publishedContent,
      title: "随后保存的草稿",
      markdown: "# 尚未发布",
    };

    await Promise.all([
      gateway.publishDoc("atomic-doc", publishedContent),
      gateway.saveDocDraft("atomic-doc", laterDraft),
    ]);

    const saved = (await gateway.load("admin")).docs[0];
    expect(saved.status).toBe("published");
    expect(saved.published).toEqual(publishedContent);
    expect(saved.draft).toEqual(laterDraft);
    expect((await gateway.load("public")).docs[0].published).toEqual(
      publishedContent,
    );

    const beforeInvalidPublish = structuredClone(saved);
    await expect(
      gateway.publishDoc("atomic-doc", {
        ...laterDraft,
        slug: "nested/path",
      }),
    ).rejects.toThrow("文档路径必须是单段、规范化的 URL 路径");
    expect((await gateway.load("admin")).docs[0]).toEqual(beforeInvalidPublish);
  });

  it.each([
    ["negative", -1],
    ["fractional", 1.5],
    ["NaN", Number.NaN],
  ])("rejects %s documentation order before changing local state", async (_label, order) => {
    const gateway = new LocalContentGateway();
    const content = {
      title: "数字校验",
      slug: "numeric-validation",
      summary: "",
      markdown: "# 数字校验",
      parentId: null,
      order,
    };

    await expect(gateway.saveDocDraft("invalid-order", content)).rejects.toThrow(
      "文档排序必须是大于或等于 0 的整数",
    );
    expect((await gateway.load("admin")).docs).toEqual([]);
  });

  it("rejects illegal and duplicate documentation slugs without changing public content", async () => {
    const existingContent = {
      title: "旧版快速开始",
      slug: "Quick Start",
      summary: "",
      markdown: "# 旧版",
      parentId: null,
      order: 0,
    };
    const seeded = createSeedState();
    seeded.docs = [{
      id: "existing",
      status: "published",
      draft: structuredClone(existingContent),
      published: structuredClone(existingContent),
      updatedAt: "2026-07-26T00:00:00.000Z",
      publishedAt: "2026-07-26T00:00:00.000Z",
    }];
    localStorage.setItem(
      "smart-mistake-book.website.v2",
      JSON.stringify(seeded),
    );
    const gateway = new LocalContentGateway();

    await gateway.saveDocDraft("existing", {
      ...existingContent,
      title: "非法路径",
      slug: "nested/path",
      markdown: "# 非法路径",
    });
    await expect(gateway.publishDoc("existing")).rejects.toThrow(
      "文档路径必须是单段、规范化的 URL 路径",
    );

    await gateway.saveDocDraft("duplicate", {
      ...existingContent,
      title: "重复路径",
      slug: "quick-start",
      markdown: "# 重复路径",
    });
    await expect(gateway.publishDoc("duplicate")).rejects.toThrow(
      "已有已发布文档使用相同路径",
    );

    const publicState = await gateway.load("public");
    expect(publicState.docs).toHaveLength(1);
    expect(publicState.docs[0].published.title).toBe("旧版快速开始");
  });

  it("archives documentation and removes it from the public snapshot", async () => {
    const gateway = new LocalContentGateway();
    const content = {
      title: "快速开始",
      slug: "quick-start",
      summary: "第一篇文档",
      markdown: "# 快速开始\n\n欢迎使用。",
      parentId: null,
      order: 0,
    };
    await gateway.saveDocDraft("doc-1", content);
    await gateway.publishDoc("doc-1");
    const published = (await gateway.load("admin")).docs[0];
    expect(published.status).toBe("published");
    expect(published.published.markdown).toContain("欢迎使用");

    await gateway.archiveDoc("doc-1");

    expect((await gateway.load("admin")).docs[0].status).toBe("archived");
    expect((await gateway.load("public")).docs).toEqual([]);
  });

  it("rejects invalid documentation hierarchies and keeps public parents intact", async () => {
    const gateway = new LocalContentGateway();
    const root = {
      title: "根文档",
      slug: "root",
      summary: "",
      markdown: "# 根文档",
      parentId: null,
      order: 0,
    };
    const child = {
      title: "子文档",
      slug: "child",
      summary: "",
      markdown: "# 子文档",
      parentId: "root",
      order: 0,
    };

    await gateway.publishDoc("root", root);
    await gateway.publishDoc("child", child);

    await expect(
      gateway.publishDoc("root", { ...root, parentId: "child" }),
    ).rejects.toThrow("文档层级不能形成循环");
    await expect(
      gateway.publishDoc("orphan", {
        ...child,
        title: "孤立文档",
        slug: "orphan",
        parentId: "missing",
      }),
    ).rejects.toThrow("上级文档必须先发布");
    await gateway.saveDocDraft("draft-parent", {
      ...root,
      title: "草稿父文档",
      slug: "draft-parent",
    });
    await expect(
      gateway.publishDoc("draft-child", {
        ...child,
        title: "草稿的子文档",
        slug: "draft-child",
        parentId: "draft-parent",
      }),
    ).rejects.toThrow("上级文档必须先发布");

    const state = await gateway.load("admin");
    expect(state.docs.find((doc) => doc.id === "root")?.published.parentId).toBeNull();
    expect(state.docs.some((doc) => doc.id === "orphan")).toBe(false);
    expect(state.docs.some((doc) => doc.id === "draft-child")).toBe(false);
  });

  it("prevents archiving a document while it has published children", async () => {
    const gateway = new LocalContentGateway();
    const root = {
      title: "根文档",
      slug: "root",
      summary: "",
      markdown: "# 根文档",
      parentId: null,
      order: 0,
    };
    await gateway.publishDoc("root", root);
    await gateway.publishDoc("child", {
      ...root,
      title: "子文档",
      slug: "child",
      parentId: "root",
    });

    await expect(gateway.archiveDoc("root")).rejects.toThrow(
      "请先归档或调整该文档下的已发布子文档",
    );
    expect((await gateway.load("public")).docs).toHaveLength(2);

    await gateway.archiveDoc("child");
    await gateway.archiveDoc("root");
    expect((await gateway.load("public")).docs).toEqual([]);
  });

  it("never invents a download URL for metadata-only releases", async () => {
    const gateway = new LocalContentGateway();
    const release: Release = {
      id: "release-1",
      channel: "stable",
      versionName: "1.0.0",
      versionCode: 1,
      minAndroid: 6,
      releaseNotes: "首个版本",
      status: "draft",
      package: null,
      createdAt: "2026-07-26",
      publishedAt: null,
    };
    await gateway.saveReleaseDraft(release);
    await gateway.attachReleasePackage(
      release.id,
      new File(["apk"], "smart-mistake-book.apk", {
        type: "application/vnd.android.package-archive",
      }),
    );
    await gateway.publishRelease(release.id);
    const saved = (await gateway.load("admin")).releases[0];
    expect(saved.status).toBe("published");
    expect(saved.package?.storageState).toBe("metadata-only");
    expect(saved.package?.downloadUrl).toBeNull();
    expect(saved.package?.sha256).toBeNull();

    await expect(
      gateway.saveReleaseDraft({ ...saved, releaseNotes: "不应直接覆盖公开版本" }),
    ).rejects.toThrow("已发布或归档版本不可直接修改");
    const stillPublished = (await gateway.load("admin")).releases[0];
    expect(stillPublished.status).toBe("published");
    expect(stillPublished.releaseNotes).toBe("首个版本");
  });

  it.each([
    ["fractional version code", { versionCode: 1.5 }, "版本代码必须是大于或等于 1 的整数"],
    ["zero version code", { versionCode: 0 }, "版本代码必须是大于或等于 1 的整数"],
    ["NaN minimum Android", { minAndroid: Number.NaN }, "最低 Android 版本必须是大于或等于 6 的整数"],
    ["unsupported minimum Android", { minAndroid: 5 }, "最低 Android 版本必须是大于或等于 6 的整数"],
    ["missing version name", { versionName: " " }, "请填写版本名和更新说明"],
    ["missing release notes", { releaseNotes: "" }, "请填写版本名和更新说明"],
  ])("rejects $0 before saving local release metadata", async (_label, changes, message) => {
    const gateway = new LocalContentGateway();
    const release: Release = {
      id: "invalid-release",
      channel: "stable",
      versionName: "1.0.0",
      versionCode: 1,
      minAndroid: 6,
      releaseNotes: "版本说明",
      status: "draft",
      package: null,
      createdAt: "2026-07-31",
      publishedAt: null,
      ...changes,
    };

    await expect(gateway.saveReleaseDraft(release)).rejects.toThrow(message);
    expect((await gateway.load("admin")).releases).toEqual([]);
  });

  it("rolls back schema-invalid mutations and keeps the durable library reloadable", async () => {
    const gateway = new LocalContentGateway();
    await gateway.saveDocDraft("kept-doc", {
      title: "保留文档",
      slug: "kept-doc",
      summary: "",
      markdown: "# 保留文档",
      parentId: null,
      order: 0,
    });
    const before = localStorage.getItem("smart-mistake-book.website.v2");
    const listener = vi.fn();
    gateway.subscribe(listener);
    const state = await gateway.load("admin");
    const invalidProduct = {
      ...state.product.draft,
      navLabel: 42,
    } as unknown as ProductPageContent;

    await expect(gateway.saveProductDraft(invalidProduct)).rejects.toThrow(
      "内容数据无效，未保存",
    );

    expect(localStorage.getItem("smart-mistake-book.website.v2")).toBe(before);
    expect(listener).not.toHaveBeenCalled();
    expect((await gateway.load("admin")).docs[0].draft.title).toBe("保留文档");

    const reloaded = new LocalContentGateway();
    const freshState = await reloaded.load("admin");
    expect(freshState.docs[0].draft.title).toBe("保留文档");
    expect(freshState.product.draft.navLabel).toBe(
      state.product.draft.navLabel,
    );
  });

  it("keeps a product draft private, then publishes and archives it atomically", async () => {
    const gateway = new LocalContentGateway();
    const state = await gateway.load("admin");
    const product: ProductPageContent = {
      ...state.product.draft,
      seoDescription: "公开产品页说明",
      blocks: [
        {
          id: "hero",
          type: "hero",
          enabled: true,
          heading: "产品页标题",
          lead: "产品页简介",
          mediaId: null,
        },
        {
          id: "markdown",
          type: "markdown",
          enabled: true,
          title: "",
          markdown: "正式内容",
        },
        {
          id: "secret",
          type: "image-text",
          enabled: false,
          eyebrow: "内部",
          title: "尚未公开",
          markdown: "私密产品文案",
          mediaId: "private-media-id",
          mediaSide: "left",
        },
      ],
    };

    await gateway.saveProductDraft(product);
    expect((await gateway.load("admin")).product.published).toBeNull();
    await gateway.publishProduct();
    expect((await gateway.load("admin")).product.published?.blocks).toHaveLength(3);
    const publicProduct = (await gateway.load("public")).product.published;
    expect(publicProduct?.blocks).toHaveLength(2);
    expect(JSON.stringify(publicProduct)).not.toContain("私密产品文案");
    expect(JSON.stringify(publicProduct)).not.toContain("private-media-id");

    await gateway.saveProductDraft({ ...product, navLabel: "新的草稿导航" });
    expect((await gateway.load("admin")).product.published?.navLabel).toBe("产品功能");
    await gateway.archiveProduct();
    expect((await gateway.load("admin")).product.status).toBe("archived");
  });

  it("rejects hiding media used by visible published product blocks without committing sibling fields", async () => {
    const seeded = createSeedState();
    const brand = seeded.media.find((asset) => asset.id === "media-brand")!;
    const draftOnly = seeded.media.find((asset) => asset.id === "media-review")!;
    const disabledOnly = seeded.media.find((asset) => asset.id === "media-library")!;
    draftOnly.visible = true;
    disabledOnly.visible = true;
    seeded.product.status = "published";
    seeded.product.publishedAt = "2026-07-26T00:00:00.000Z";
    seeded.product.published = {
      ...seeded.product.draft,
      blocks: [
        {
          id: "public-hero",
          type: "hero",
          enabled: true,
          heading: "已发布首屏",
          lead: "已发布说明",
          mediaId: brand.id,
        },
        {
          id: "disabled-gallery",
          type: "gallery",
          enabled: false,
          title: "",
          summary: "",
          mediaIds: [disabledOnly.id],
          columns: 2,
        },
      ],
    };
    seeded.product.draft = {
      ...seeded.product.draft,
      blocks: [
        {
          id: "draft-hero",
          type: "hero",
          enabled: true,
          heading: "草稿首屏",
          lead: "草稿说明",
          mediaId: draftOnly.id,
        },
      ],
    };
    localStorage.setItem("smart-mistake-book.website.v2", JSON.stringify(seeded));
    const gateway = new LocalContentGateway();

    await expect(
      gateway.updateMedia(brand.id, {
        label: "不应部分保存的名称",
        visible: false,
      }),
    ).rejects.toThrow("该图片正被已发布产品页使用");
    const afterRejection = await gateway.load("admin");
    expect(afterRejection.media.find((asset) => asset.id === brand.id)).toEqual(
      brand,
    );

    await expect(
      gateway.updateMedia(draftOnly.id, { visible: false }),
    ).resolves.toBeUndefined();
    await expect(
      gateway.updateMedia(disabledOnly.id, { visible: false }),
    ).resolves.toBeUndefined();
  });

  it("merges concurrent media patches against the latest asset without losing metadata", async () => {
    const gateway = new LocalContentGateway();
    const original = (await gateway.load("admin")).media.find(
      (asset) => asset.id === "media-brand",
    )!;

    await Promise.all([
      gateway.updateMedia(original.id, { label: "更新后的名称" }),
      gateway.updateMedia(original.id, {
        alt: "更新后的替代文本",
        visible: false,
      }),
    ]);

    const updated = (await gateway.load("admin")).media.find(
      (asset) => asset.id === original.id,
    );
    expect(updated).toEqual({
      ...original,
      label: "更新后的名称",
      alt: "更新后的替代文本",
      visible: false,
    });
  });

  it("validates the merged media result and rejects the whole patch atomically", async () => {
    const gateway = new LocalContentGateway();
    const original = (await gateway.load("admin")).media.find(
      (asset) => asset.id === "media-brand",
    )!;

    await expect(
      gateway.updateMedia(original.id, {
        label: "   ",
        visible: false,
      }),
    ).rejects.toThrow("资源名称与替代文本均不能为空");
    expect(
      (await gateway.load("admin")).media.find(
        (asset) => asset.id === original.id,
      ),
    ).toEqual(original);
  });

  it("only deletes uploaded media after all product references are removed", async () => {
    const seeded = createSeedState();
    seeded.media.push({
      id: "uploaded-media",
      label: "上传图片",
      role: "content",
      src: "data:image/png;base64,AA==",
      alt: "上传图片替代文本",
      visible: true,
      bundled: false,
      mimeType: "image/png",
    });
    localStorage.setItem("smart-mistake-book.website.v2", JSON.stringify(seeded));
    const gateway = new LocalContentGateway();

    await expect(gateway.deleteMedia("media-brand")).rejects.toThrow(
      "内置资源不能删除",
    );
    const state = await gateway.load("admin");
    await gateway.saveProductDraft({
      ...state.product.draft,
      blocks: [
        {
          id: "gallery",
          type: "gallery",
          enabled: false,
          title: "",
          summary: "",
          mediaIds: ["uploaded-media"],
          columns: 2,
        },
      ],
    });
    await expect(gateway.deleteMedia("uploaded-media")).rejects.toThrow(
      "仍被产品页引用",
    );

    await gateway.saveProductDraft({ ...state.product.draft, blocks: [] });
    await gateway.deleteMedia("uploaded-media");
    expect((await gateway.load("admin")).media.some((asset) => asset.id === "uploaded-media")).toBe(
      false,
    );
  });
});

describe("RestContentGateway command boundaries", () => {
  it.each([
    ["negative", -1],
    ["fractional", 1.5],
    ["NaN", Number.NaN],
  ])("rejects %s documentation order before sending REST commands", async (_label, order) => {
    const fetchMock = vi.spyOn(globalThis, "fetch");
    const gateway = new RestContentGateway("https://content.example");
    const content = {
      title: "远程数字校验",
      slug: "remote-numeric-validation",
      summary: "",
      markdown: "# 远程数字校验",
      parentId: null,
      order,
    };

    try {
      await expect(gateway.saveDocDraft("invalid-order", content)).rejects.toThrow(
        "文档排序必须是大于或等于 0 的整数",
      );
      await expect(gateway.publishDoc("invalid-order", content)).rejects.toThrow(
        "文档排序必须是大于或等于 0 的整数",
      );
      expect(fetchMock).not.toHaveBeenCalled();
    } finally {
      fetchMock.mockRestore();
    }
  });

  it.each([
    ["fractional version code", { versionCode: 1.5 }, "版本代码必须是大于或等于 1 的整数"],
    ["zero version code", { versionCode: 0 }, "版本代码必须是大于或等于 1 的整数"],
    ["NaN minimum Android", { minAndroid: Number.NaN }, "最低 Android 版本必须是大于或等于 6 的整数"],
    ["unsupported minimum Android", { minAndroid: 5 }, "最低 Android 版本必须是大于或等于 6 的整数"],
    ["missing version name", { versionName: "" }, "请填写版本名和更新说明"],
    ["missing release notes", { releaseNotes: " " }, "请填写版本名和更新说明"],
  ])("rejects $0 before sending a REST release command", async (_label, changes, message) => {
    const fetchMock = vi.spyOn(globalThis, "fetch");
    const gateway = new RestContentGateway("https://content.example");
    const release: Release = {
      id: "invalid-release",
      channel: "stable",
      versionName: "1.0.0",
      versionCode: 1,
      minAndroid: 6,
      releaseNotes: "版本说明",
      status: "draft",
      package: null,
      createdAt: "2026-07-31",
      publishedAt: null,
      ...changes,
    };

    try {
      await expect(gateway.saveReleaseDraft(release)).rejects.toThrow(message);
      expect(fetchMock).not.toHaveBeenCalled();
    } finally {
      fetchMock.mockRestore();
    }
  });

  it("omits analytics from site commands and sends atomic publish payloads", async () => {
    const admin = createSeedState();
    let responseRevision = 0;
    const fetchMock = vi
      .spyOn(globalThis, "fetch")
      .mockImplementation(async () =>
        jsonResponse(admin, 200, `"state-${++responseRevision}"`),
      );
    const gateway = new RestContentGateway("https://content.example");
    const site = {
      ...admin.site.draft,
      heroTitle: "远程原子发布首页",
      analytics: {
        providerName: "不得进入站点命令",
        siteId: "private-analytics",
      },
    };
    const about = admin.pages.find((page) => page.slug === "about")!;
    const page = { ...about.draft, title: "远程原子发布页面" };
    const doc = {
      title: "远程原子发布文档",
      slug: "remote-atomic",
      summary: "",
      markdown: "# 远程原子发布",
      parentId: null,
      order: 0,
      status: "published",
    } as DocumentationContent;

    try {
      await gateway.load("admin");
      await gateway.saveSiteDraft(site);
      await gateway.publishSite(site);
      await gateway.publishPage(about.id, page);
      await gateway.archivePage(about.id);
      await gateway.publishDoc("doc-1", doc);

      const savedSiteBody = JSON.parse(
        fetchMock.mock.calls[1][1]?.body as string,
      ) as Record<string, unknown>;
      const publishedSiteBody = JSON.parse(
        fetchMock.mock.calls[2][1]?.body as string,
      ) as Record<string, unknown>;
      expect(savedSiteBody).not.toHaveProperty("analytics");
      expect(publishedSiteBody).not.toHaveProperty("analytics");
      expect(publishedSiteBody.heroTitle).toBe("远程原子发布首页");
      expect(fetchMock.mock.calls[2][0]).toBe(
        "https://content.example/api/v1/admin/site/publish",
      );
      expect(fetchMock.mock.calls[3][0]).toBe(
        `https://content.example/api/v1/admin/pages/${about.id}/publish`,
      );
      expect(JSON.parse(fetchMock.mock.calls[3][1]?.body as string)).toEqual(
        page,
      );
      expect(fetchMock.mock.calls[4][0]).toBe(
        `https://content.example/api/v1/admin/pages/${about.id}/archive`,
      );
      expect(fetchMock.mock.calls[5][0]).toBe(
        "https://content.example/api/v1/admin/docs/doc-1/publish",
      );
      expect(JSON.parse(fetchMock.mock.calls[5][1]?.body as string)).toEqual({
        title: "远程原子发布文档",
        slug: "remote-atomic",
        summary: "",
        markdown: "# 远程原子发布",
        parentId: null,
        order: 0,
      });
    } finally {
      fetchMock.mockRestore();
    }
  });

  it("surfaces a remote published-media conflict without broadcasting a partial state", async () => {
    const admin = createSeedState();
    const fetchMock = vi
      .spyOn(globalThis, "fetch")
      .mockResolvedValueOnce(jsonResponse(admin, 200, '"state-1"'))
      .mockResolvedValueOnce(
        new Response("该图片正被已发布产品页使用，请先更新或归档产品页", {
          status: 409,
        }),
      );
    const gateway = new RestContentGateway("https://content.example");
    const listener = vi.fn();
    gateway.subscribe(listener);
    const asset = admin.media.find(
      (candidate) => candidate.id === "media-brand",
    )!;

    try {
      await gateway.load("admin");
      listener.mockClear();
      await expect(
        gateway.updateMedia(asset.id, { visible: false }),
      ).rejects.toMatchObject({
        status: 409,
        message: "该图片正被已发布产品页使用，请先更新或归档产品页",
      } satisfies Partial<ContentGatewayError>);
      expect(listener).not.toHaveBeenCalled();
    } finally {
      fetchMock.mockRestore();
    }
  });

  it("sends only changed media fields and preserves the server snapshot", async () => {
    const serverState = createSeedState();
    const serverAsset = serverState.media.find(
      (candidate) => candidate.id === "media-brand",
    )!;
    serverAsset.label = "服务端规范化名称";
    serverAsset.src = "/server/latest-app-icon.webp";
    serverAsset.mimeType = "image/webp";
    serverAsset.width = 1024;
    serverAsset.height = 1024;
    const fetchMock = vi
      .spyOn(globalThis, "fetch")
      .mockResolvedValueOnce(jsonResponse(serverState, 200, '"state-1"'))
      .mockResolvedValueOnce(jsonResponse(serverState, 200, '"state-2"'));
    const gateway = new RestContentGateway("https://content.example");
    const listener = vi.fn();
    gateway.subscribe(listener);

    try {
      await gateway.load("admin");
      listener.mockClear();
      await gateway.updateMedia(serverAsset.id, {
        label: "  服务端规范化名称  ",
      });

      expect(fetchMock).toHaveBeenCalledTimes(2);
      expect(fetchMock.mock.calls[1][0]).toBe(
        "https://content.example/api/v1/admin/media/media-brand",
      );
      expect(fetchMock.mock.calls[1][1]).toMatchObject({ method: "PATCH" });
      expect(JSON.parse(fetchMock.mock.calls[1][1]?.body as string)).toEqual({
        label: "服务端规范化名称",
      });
      expect(listener).toHaveBeenCalledWith(
        expect.objectContaining({
          media: expect.arrayContaining([
            expect.objectContaining({
              id: serverAsset.id,
              src: "/server/latest-app-icon.webp",
              mimeType: "image/webp",
              width: 1024,
              height: 1024,
            }),
          ]),
        }),
        "admin",
      );
    } finally {
      fetchMock.mockRestore();
    }
  });

  it("treats an empty media patch as a no-op in both adapters", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch");
    const remote = new RestContentGateway("https://content.example");
    const local = new LocalContentGateway();

    try {
      await expect(
        remote.updateMedia("media-brand", {}),
      ).resolves.toBeUndefined();
      await expect(
        local.updateMedia("media-brand", {}),
      ).resolves.toBeUndefined();
      expect(fetchMock).not.toHaveBeenCalled();
    } finally {
      fetchMock.mockRestore();
    }
  });
});
