import { describe, expect, it } from "vitest";
import { createSeedState } from "../data/seed";
import type { DocumentationPage, Release } from "../types";
import {
  createPublicWebsiteState,
  hydratePublicWebsiteState,
  publicWebsiteStateSchema,
  repairWebsiteState,
  websiteStateSchema,
} from "./contentSchemas";

describe("content schemas", () => {
  it("validates the complete nested state and supported ISO date forms", () => {
    const state = createSeedState();
    state.releases.push({
      id: "release-date-only",
      channel: "stable",
      versionName: "1.0.0",
      versionCode: 1,
      minAndroid: 6,
      releaseNotes: "",
      status: "draft",
      package: null,
      createdAt: "2026-07-31",
      publishedAt: null,
    });

    expect(websiteStateSchema.safeParse(state).success).toBe(true);
  });

  it("keeps seeded drafts structurally independent from published content", () => {
    const state = createSeedState();
    state.pages[0].draft.title = "只修改草稿";

    expect(state.pages[0].published.title).toBe("关于智能错题本");
  });

  it("rejects malformed nested values and invalid product discriminants", () => {
    const malformedAnalytics = createSeedState() as unknown as Record<string, unknown>;
    (
      malformedAnalytics.site as {
        published: { analytics: { siteId: unknown } };
      }
    ).published.analytics.siteId = 42;
    expect(websiteStateSchema.safeParse(malformedAnalytics).success).toBe(false);

    const malformedProduct = createSeedState();
    malformedProduct.product.draft.blocks = [
      {
        id: "bad-gallery",
        type: "gallery",
        enabled: true,
        title: "",
        summary: "",
        mediaIds: [],
        columns: 4,
      } as never,
    ];
    expect(websiteStateSchema.safeParse(malformedProduct).success).toBe(false);

    const invalidDate = createSeedState();
    invalidDate.site.updatedAt = "not-a-date";
    expect(websiteStateSchema.safeParse(invalidDate).success).toBe(false);
  });

  it("requires one uniquely identified static page for every fixed slug", () => {
    const duplicateSlug = createSeedState();
    duplicateSlug.pages[1].slug = duplicateSlug.pages[0].slug;
    expect(websiteStateSchema.safeParse(duplicateSlug).success).toBe(false);

    const duplicateId = createSeedState();
    duplicateId.pages[1].id = duplicateId.pages[0].id;
    expect(websiteStateSchema.safeParse(duplicateId).success).toBe(false);

    const missingPage = createSeedState();
    missingPage.pages.pop();
    expect(websiteStateSchema.safeParse(missingPage).success).toBe(false);
  });

  it("rejects duplicate public static-page identities and paths", () => {
    const publicState = createPublicWebsiteState(createSeedState());
    publicState.pages[1] = {
      ...publicState.pages[1],
      id: publicState.pages[0].id,
    };
    expect(publicWebsiteStateSchema.safeParse(publicState).success).toBe(false);

    const duplicateSlug = createPublicWebsiteState(createSeedState());
    duplicateSlug.pages[1] = {
      ...duplicateSlug.pages[1],
      slug: duplicateSlug.pages[0].slug,
    };
    expect(publicWebsiteStateSchema.safeParse(duplicateSlug).success).toBe(false);
  });

  it("hydrates unpublished public pages without breaking admin-state invariants", () => {
    const state = createSeedState();
    state.pages[2].publishedAt = null;

    const hydrated = hydratePublicWebsiteState(createPublicWebsiteState(state));

    expect(websiteStateSchema.safeParse(hydrated).success).toBe(true);
    expect(
      hydrated.pages.find((page) => page.slug === "contact")?.publishedAt,
    ).toBeNull();
  });

  it("repairs only the missing static-page entry when other pages are valid", () => {
    const state = createSeedState();
    state.pages[0].draft.title = "保留的关于页草稿";
    state.pages[2].draft.title = "保留的联系页草稿";
    state.pages[1].id = "";

    const repaired = repairWebsiteState(state);

    expect(repaired?.pages.find((page) => page.slug === "about")?.draft.title)
      .toBe("保留的关于页草稿");
    expect(repaired?.pages.find((page) => page.slug === "privacy")?.id)
      .toBe(createSeedState().pages[1].id);
    expect(repaired?.pages.find((page) => page.slug === "contact")?.draft.title)
      .toBe("保留的联系页草稿");
  });

  it("rejects unsafe documentation and release numeric ranges", () => {
    const fractionalDocOrder = createSeedState();
    fractionalDocOrder.docs.push({
      id: "fractional-order",
      status: "draft",
      draft: {
        title: "",
        slug: "",
        summary: "",
        markdown: "",
        parentId: null,
        order: 1.5,
      },
      published: {
        title: "",
        slug: "",
        summary: "",
        markdown: "",
        parentId: null,
        order: 0,
      },
      updatedAt: "2026-07-31",
      publishedAt: null,
    });
    expect(websiteStateSchema.safeParse(fractionalDocOrder).success).toBe(false);

    const negativeDocOrder = structuredClone(fractionalDocOrder);
    negativeDocOrder.docs[0].draft.order = -1;
    expect(websiteStateSchema.safeParse(negativeDocOrder).success).toBe(false);

    const invalidRelease = createSeedState();
    invalidRelease.releases.push({
      id: "invalid-release",
      channel: "stable",
      versionName: "1.0.0",
      versionCode: 0,
      minAndroid: 5,
      releaseNotes: "说明",
      status: "draft",
      package: null,
      createdAt: "2026-07-31",
      publishedAt: null,
    });
    expect(websiteStateSchema.safeParse(invalidRelease).success).toBe(false);
  });

  it("does not expose unpublished draft values", () => {
    const state = createSeedState();
    state.site.draft.heroTitle = "私密首页草稿";
    state.pages[0].draft.title = "私密页面草稿";

    const publicState = createPublicWebsiteState(state);
    const serialized = JSON.stringify(publicState);

    expect(serialized).not.toContain('"draft"');
    expect(serialized).not.toContain("私密首页草稿");
    expect(serialized).not.toContain("私密页面草稿");
  });

  it("keeps the seeded public snapshot free of unsettled product claims", () => {
    const serialized = JSON.stringify(
      createPublicWebsiteState(createSeedState()),
    );

    expect(serialized).not.toMatch(
      /拍题|拍下|识别|讲题|讲解|复习|学习记录|智能任务|三步/,
    );
  });

  it("filters unpublished releases, draft docs, and hidden media", () => {
    const state = createSeedState();
    const draftRelease: Release = {
      id: "draft-release",
      channel: "beta",
      versionName: "2.0.0",
      versionCode: 2,
      minAndroid: 6,
      releaseNotes: "私密版本",
      status: "draft",
      package: null,
      createdAt: "2026-07-31",
      publishedAt: null,
    };
    const draftDoc: DocumentationPage = {
      id: "draft-doc",
      status: "draft",
      draft: {
        title: "私密文档",
        slug: "private",
        summary: "",
        markdown: "",
        parentId: null,
        order: 0,
      },
      published: {
        title: "",
        slug: "",
        summary: "",
        markdown: "",
        parentId: null,
        order: 0,
      },
      updatedAt: "2026-07-31",
      publishedAt: null,
    };
    state.media[1].visible = false;
    state.releases.push(draftRelease);
    state.docs.push(draftDoc);

    const publicState = createPublicWebsiteState(state);

    expect(publicState.releases).toHaveLength(0);
    expect(publicState.docs).toHaveLength(0);
    expect(publicState.media.some((asset) => asset.id === "media-review")).toBe(false);
  });

  it("validates projected public content and rejects leaked draft fields", () => {
    const state = createSeedState();
    const publicState = createPublicWebsiteState(state);

    expect(publicWebsiteStateSchema.safeParse(publicState).success).toBe(true);

    const leaked = structuredClone(publicState) as unknown as Record<string, unknown>;
    (leaked.site as Record<string, unknown>).draft = state.site.draft;

    expect(publicWebsiteStateSchema.safeParse(leaked).success).toBe(false);
  });

  it("keeps disabled product blocks in admin state but removes them from public content", () => {
    const state = createSeedState();
    state.product.status = "published";
    state.product.publishedAt = "2026-07-30T08:00:00+08:00";
    state.product.published = {
      navLabel: "产品功能",
      seoTitle: "产品功能",
      seoDescription: "已发布说明",
      showHomepageEntry: true,
      homepageEntryLabel: "了解产品",
      blocks: [
        {
          id: "visible-hero",
          type: "hero",
          enabled: true,
          heading: "公开首屏",
          lead: "公开说明",
          mediaId: null,
        },
        {
          id: "visible-body",
          type: "markdown",
          enabled: true,
          title: "公开正文",
          markdown: "公开内容",
        },
        {
          id: "disabled-secret",
          type: "image-text",
          enabled: false,
          eyebrow: "内部",
          title: "尚未公开的功能",
          markdown: "私密产品文案",
          mediaId: "private-media-id",
          mediaSide: "left",
        },
      ],
    };

    const publicState = createPublicWebsiteState(state);
    const serialized = JSON.stringify(publicState);

    expect(state.product.published.blocks).toHaveLength(3);
    expect(publicState.product?.content.blocks).toHaveLength(2);
    expect(publicState.product?.content.blocks.every((block) => block.enabled)).toBe(
      true,
    );
    expect(serialized).not.toContain("私密产品文案");
    expect(serialized).not.toContain("private-media-id");
    expect(publicWebsiteStateSchema.safeParse(publicState).success).toBe(true);
  });

  it("rejects disabled blocks received in a public product response", () => {
    const state = createSeedState();
    state.product.status = "published";
    state.product.publishedAt = "2026-07-30";
    state.product.published = {
      navLabel: "产品功能",
      seoTitle: "产品功能",
      seoDescription: "已发布说明",
      showHomepageEntry: false,
      homepageEntryLabel: "",
      blocks: [
        {
          id: "visible-hero",
          type: "hero",
          enabled: true,
          heading: "公开首屏",
          lead: "公开说明",
          mediaId: null,
        },
        {
          id: "disabled-secret",
          type: "markdown",
          enabled: false,
          title: "内部",
          markdown: "私密产品文案",
        },
      ],
    };
    const publicState = createPublicWebsiteState(state);
    publicState.product!.content.blocks.push(
      structuredClone(state.product.published.blocks[1]),
    );

    expect(publicWebsiteStateSchema.safeParse(publicState).success).toBe(false);
  });

  it("uses publication time for public mutable-content timestamps", () => {
    const state = createSeedState();
    state.site.updatedAt = "2026-07-31";
    state.site.publishedAt = "2026-07-01";
    state.pages[0].updatedAt = "2026-07-31";
    state.pages[0].publishedAt = "2026-07-02";
    state.docs.push({
      id: "published-doc",
      status: "published",
      draft: {
        title: "文档草稿",
        slug: "published",
        summary: "",
        markdown: "",
        parentId: null,
        order: 0,
      },
      published: {
        title: "公开文档",
        slug: "published",
        summary: "",
        markdown: "",
        parentId: null,
        order: 0,
      },
      updatedAt: "2026-07-31",
      publishedAt: "2026-07-03",
    });
    state.product.status = "published";
    state.product.updatedAt = "2026-07-31";
    state.product.publishedAt = "2026-07-04";
    state.product.published = {
      navLabel: "产品功能",
      seoTitle: "产品功能",
      seoDescription: "已发布说明",
      showHomepageEntry: false,
      homepageEntryLabel: "",
      blocks: [],
    };

    const publicState = createPublicWebsiteState(state);

    expect(publicState.site.updatedAt).toBe("2026-07-01");
    expect(publicState.pages[0].updatedAt).toBe("2026-07-02");
    expect(publicState.docs[0].updatedAt).toBe("2026-07-03");
    expect(publicState.product?.updatedAt).toBe("2026-07-04");
  });

  it("hydrates public content without manufacturing a product publication", () => {
    const publicState = createPublicWebsiteState(createSeedState());

    const hydrated = hydratePublicWebsiteState(publicState);

    expect(hydrated.site.draft).toEqual(hydrated.site.published);
    expect(hydrated.product.published).toBeNull();
  });
});
