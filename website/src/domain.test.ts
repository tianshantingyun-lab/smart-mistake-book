import { describe, expect, it } from "vitest";
import { createSeedState } from "./data/seed";
import {
  analyticsCanStart,
  buildPublishedDocumentationTree,
  collectProductMediaIds,
  createProductBlock,
  documentationArchiveError,
  documentationHierarchyError,
  documentationOrderError,
  documentationSlugError,
  duplicateProductBlock,
  latestPublishedRelease,
  managedPagePublicationError,
  moveProductBlock,
  publishedDocumentationPath,
  releaseDraftError,
  searchPublishedDocumentation,
  searchPublishedDocs,
  slugify,
  validateApk,
  validateProductPage,
} from "./domain";
import type { DocumentationPage, ProductPageContent, Release } from "./types";

describe("domain rules", () => {
  it("accepts APK files and rejects invalid or oversized files", () => {
    expect(validateApk({ name: "app.apk", size: 129 * 1024 * 1024, type: "" })).toEqual([]);
    expect(validateApk({ name: "app.zip", size: 1, type: "application/zip" })).toContain(
      "仅支持 .apk 安装包",
    );
    expect(validateApk({ name: "app.apk", size: 501 * 1024 * 1024, type: "" })).toContain(
      "安装包不能超过 500 MB",
    );
  });

  it.each([
    [0, null],
    [12, null],
    [-1, "文档排序必须是大于或等于 0 的整数"],
    [1.5, "文档排序必须是大于或等于 0 的整数"],
    [Number.NaN, "文档排序必须是大于或等于 0 的整数"],
    [Number.POSITIVE_INFINITY, "文档排序必须是大于或等于 0 的整数"],
  ])("validates documentation order %s", (order, expected) => {
    expect(documentationOrderError(order)).toBe(expected);
  });

  it("validates required and integer release metadata", () => {
    const valid = {
      versionName: "1.0.0",
      versionCode: 1,
      minAndroid: 6,
      releaseNotes: "首个版本",
    };

    expect(releaseDraftError(valid)).toBeNull();
    expect(releaseDraftError({ ...valid, versionName: " " })).toBe(
      "请填写版本名和更新说明",
    );
    expect(releaseDraftError({ ...valid, releaseNotes: "" })).toBe(
      "请填写版本名和更新说明",
    );
    expect(releaseDraftError({ ...valid, versionCode: 1.5 })).toBe(
      "版本代码必须是大于或等于 1 的整数",
    );
    expect(releaseDraftError({ ...valid, versionCode: 0 })).toBe(
      "版本代码必须是大于或等于 1 的整数",
    );
    expect(releaseDraftError({ ...valid, minAndroid: Number.NaN })).toBe(
      "最低 Android 版本必须是大于或等于 6 的整数",
    );
    expect(releaseDraftError({ ...valid, minAndroid: 5 })).toBe(
      "最低 Android 版本必须是大于或等于 6 的整数",
    );
  });

  it("requires complete static-page content before publication", () => {
    const page = createSeedState().pages[0].draft;
    expect(managedPagePublicationError(page)).toBeNull();
    expect(
      managedPagePublicationError({ ...page, body: " " }),
    ).toBe("请填写页面标题、摘要、正文、SEO 标题和 SEO 描述后再发布");
  });

  it("selects the newest published release by channel", () => {
    const releases: Release[] = [
      {
        id: "older",
        channel: "stable",
        versionName: "0.9.0",
        versionCode: 9,
        minAndroid: 6,
        releaseNotes: "older",
        status: "published",
        package: null,
        createdAt: "2026-01-01",
        publishedAt: "2026-01-01",
      },
      {
        id: "newer",
        channel: "stable",
        versionName: "1.0.0",
        versionCode: 10,
        minAndroid: 6,
        releaseNotes: "newer",
        status: "published",
        package: null,
        createdAt: "2026-02-01",
        publishedAt: "2026-02-01",
      },
    ];
    expect(latestPublishedRelease(releases, "stable")?.id).toBe("newer");
    expect(latestPublishedRelease(releases, "beta")).toBeNull();
  });

  it("searches only published documentation", () => {
    const base = {
      title: "安装指南",
      slug: "install",
      summary: "安装 Android 应用",
      markdown: "下载 APK 并按提示安装。",
      parentId: null,
      order: 0,
    };
    const docs: DocumentationPage[] = [
      {
        id: "published",
        status: "published",
        draft: base,
        published: base,
        updatedAt: "2026-01-01",
        publishedAt: "2026-01-01",
      },
      {
        id: "draft",
        status: "draft",
        draft: { ...base, title: "秘密草稿", markdown: "不应被搜索" },
        published: { ...base, title: "", markdown: "" },
        updatedAt: "2026-01-01",
        publishedAt: null,
      },
    ];
    expect(searchPublishedDocs(docs, "APK").map((doc) => doc.id)).toEqual(["published"]);
    expect(searchPublishedDocs(docs, "秘密")).toEqual([]);
  });

  it("builds a stable published documentation tree and promotes orphaned children", () => {
    const makeDoc = (
      id: string,
      order: number,
      parentId: string | null,
      status: DocumentationPage["status"] = "published",
    ): DocumentationPage => {
      const content = {
        title: id,
        slug: id,
        summary: "",
        markdown: id,
        parentId,
        order,
      };
      return {
        id,
        status,
        draft: content,
        published: content,
        updatedAt: "2026-01-01",
        publishedAt: status === "published" ? "2026-01-01" : null,
      };
    };
    const docs = [
      makeDoc("root", 2, null),
      makeDoc("child-first", 1, "root"),
      makeDoc("child-second", 1, "root"),
      makeDoc("orphan", 0, "missing"),
      makeDoc("hidden-parent", 0, null, "draft"),
      makeDoc("promoted", 1, "hidden-parent"),
    ];

    const tree = buildPublishedDocumentationTree(docs);

    expect(tree.map((node) => node.doc.id)).toEqual([
      "orphan",
      "promoted",
      "root",
    ]);
    expect(tree[2].children.map((node) => node.doc.id)).toEqual([
      "child-first",
      "child-second",
    ]);
  });

  it("keeps every published document when parent links contain cycles", () => {
    const content = (
      title: string,
      parentId: string | null,
      order: number,
    ) => ({
      title,
      slug: title,
      summary: "",
      markdown: title,
      parentId,
      order,
    });
    const docs: DocumentationPage[] = [
      {
        id: "cycle-a",
        status: "published",
        draft: content("cycle-a", "cycle-b", 1),
        published: content("cycle-a", "cycle-b", 1),
        updatedAt: "2026-01-01",
        publishedAt: "2026-01-01",
      },
      {
        id: "cycle-b",
        status: "published",
        draft: content("cycle-b", "cycle-a", 2),
        published: content("cycle-b", "cycle-a", 2),
        updatedAt: "2026-01-01",
        publishedAt: "2026-01-01",
      },
      {
        id: "child",
        status: "published",
        draft: content("child", "cycle-a", 0),
        published: content("child", "cycle-a", 0),
        updatedAt: "2026-01-01",
        publishedAt: "2026-01-01",
      },
      {
        id: "self-cycle",
        status: "published",
        draft: content("self-cycle", "self-cycle", 3),
        published: content("self-cycle", "self-cycle", 3),
        updatedAt: "2026-01-01",
        publishedAt: "2026-01-01",
      },
    ];

    const tree = buildPublishedDocumentationTree(docs);
    const flattened = tree.flatMap((root) => [
      root.doc.id,
      ...root.children.map((child) => child.doc.id),
    ]);

    expect(tree.map((node) => node.doc.id)).toEqual([
      "cycle-a",
      "cycle-b",
      "self-cycle",
    ]);
    expect(flattened).toEqual([
      "cycle-a",
      "child",
      "cycle-b",
      "self-cycle",
    ]);
  });

  it("includes the effective published parent path with documentation search results", () => {
    const makeDoc = (
      id: string,
      title: string,
      parentId: string | null,
      markdown: string,
    ): DocumentationPage => {
      const content = {
        title,
        slug: id,
        summary: "",
        markdown,
        parentId,
        order: 0,
      };
      return {
        id,
        status: "published",
        draft: content,
        published: content,
        updatedAt: "2026-01-01",
        publishedAt: "2026-01-01",
      };
    };
    const docs = [
      makeDoc("install", "安装指南", null, "安装"),
      makeDoc("android", "Android", "install", "Android"),
      makeDoc("permission", "权限说明", "android", "开启授权"),
      makeDoc("orphan", "孤立文档", "missing", "开启授权"),
    ];

    expect(
      searchPublishedDocumentation(docs, "授权").map(({ doc, parentPath }) => ({
        id: doc.id,
        path: parentPath.map((parent) => parent.published.title),
      })),
    ).toEqual([
      { id: "permission", path: ["安装指南", "Android"] },
      { id: "orphan", path: [] },
    ]);
  });

  it("builds a complete published documentation path from every effective parent", () => {
    const makeDoc = (
      id: string,
      parentId: string | null,
      status: DocumentationPage["status"] = "published",
    ): DocumentationPage => {
      const content = {
        title: id,
        slug: id,
        summary: "",
        markdown: id,
        parentId,
        order: 0,
      };
      return {
        id,
        status,
        draft: content,
        published: content,
        updatedAt: "2026-01-01",
        publishedAt: status === "published" ? "2026-01-01" : null,
      };
    };
    const root = makeDoc("root", null);
    const section = makeDoc("section", "root");
    const article = makeDoc("article", "section");
    const draft = makeDoc("draft", null, "draft");
    const docs = [root, section, article, draft];

    expect(
      publishedDocumentationPath(docs, article).map((doc) => doc.id),
    ).toEqual(["root", "section", "article"]);
    expect(publishedDocumentationPath(docs, draft)).toEqual([]);
  });

  it("keeps documentation paths finite when parents are missing or cyclic", () => {
    const makeDoc = (
      id: string,
      parentId: string | null,
    ): DocumentationPage => {
      const content = {
        title: id,
        slug: id,
        summary: "",
        markdown: id,
        parentId,
        order: 0,
      };
      return {
        id,
        status: "published",
        draft: content,
        published: content,
        updatedAt: "2026-01-01",
        publishedAt: "2026-01-01",
      };
    };
    const cycleA = makeDoc("cycle-a", "cycle-b");
    const cycleB = makeDoc("cycle-b", "cycle-a");
    const child = makeDoc("child", "cycle-a");
    const orphan = makeDoc("orphan", "missing");
    const docs = [cycleA, cycleB, child, orphan];

    expect(
      publishedDocumentationPath(docs, cycleA).map((doc) => doc.id),
    ).toEqual(["cycle-a"]);
    expect(
      publishedDocumentationPath(docs, child).map((doc) => doc.id),
    ).toEqual(["cycle-a", "child"]);
    expect(
      publishedDocumentationPath(docs, orphan).map((doc) => doc.id),
    ).toEqual(["orphan"]);
  });

  it("accepts only canonical single-segment documentation slugs", () => {
    expect(documentationSlugError("new", "quick-start", [])).toBeNull();
    expect(documentationSlugError("new", "nested/path", [])).toBe(
      "文档路径必须是单段、规范化的 URL 路径",
    );
    expect(documentationSlugError("new", "Quick Start", [])).toBe(
      "文档路径必须是单段、规范化的 URL 路径",
    );
    expect(documentationSlugError("new", "-", [])).toBe(
      "文档路径必须是单段、规范化的 URL 路径",
    );
  });

  it("detects documentation slug collisions against normalized published paths", () => {
    const content = {
      title: "快速开始",
      slug: "Quick Start",
      summary: "",
      markdown: "# 快速开始",
      parentId: null,
      order: 0,
    };
    const docs: DocumentationPage[] = [
      {
        id: "published",
        status: "published",
        draft: content,
        published: content,
        updatedAt: "2026-01-01",
        publishedAt: "2026-01-01",
      },
    ];

    expect(documentationSlugError("new", "quick-start", docs)).toBe(
      "已有已发布文档使用相同路径",
    );
    expect(documentationSlugError("published", "quick-start", docs)).toBeNull();
  });

  it("requires a published, acyclic parent when publishing documentation", () => {
    const makeDoc = (
      id: string,
      parentId: string | null,
      status: DocumentationPage["status"] = "published",
    ): DocumentationPage => {
      const content = {
        title: id,
        slug: id,
        summary: "",
        markdown: `# ${id}`,
        parentId,
        order: 0,
      };
      return {
        id,
        status,
        draft: content,
        published: content,
        updatedAt: "2026-01-01",
        publishedAt: status === "published" ? "2026-01-01" : null,
      };
    };
    const docs = [
      makeDoc("root", null),
      makeDoc("child", "root"),
      makeDoc("draft-parent", null, "draft"),
    ];
    const candidate = {
      title: "候选",
      slug: "candidate",
      summary: "",
      markdown: "# 候选",
      parentId: "root",
      order: 0,
    };

    expect(documentationHierarchyError("candidate", candidate, docs)).toBeNull();
    expect(
      documentationHierarchyError("candidate", { ...candidate, parentId: "missing" }, docs),
    ).toBe("上级文档必须先发布");
    expect(
      documentationHierarchyError(
        "candidate",
        { ...candidate, parentId: "draft-parent" },
        docs,
      ),
    ).toBe("上级文档必须先发布");
    expect(
      documentationHierarchyError("candidate", { ...candidate, parentId: "candidate" }, docs),
    ).toBe("上级文档不能是当前文档");
    expect(
      documentationHierarchyError("root", { ...candidate, parentId: "child" }, docs),
    ).toBe("文档层级不能形成循环");
  });

  it("prevents archiving a parent that still has a published child", () => {
    const content = {
      title: "文档",
      slug: "doc",
      summary: "",
      markdown: "# 文档",
      parentId: null,
      order: 0,
    };
    const parent: DocumentationPage = {
      id: "parent",
      status: "published",
      draft: content,
      published: content,
      updatedAt: "2026-01-01",
      publishedAt: "2026-01-01",
    };
    const publishedChild: DocumentationPage = {
      ...structuredClone(parent),
      id: "published-child",
      published: { ...content, parentId: "parent" },
    };
    const draftChild: DocumentationPage = {
      ...structuredClone(publishedChild),
      id: "draft-child",
      status: "draft",
      publishedAt: null,
    };

    expect(documentationArchiveError("parent", [parent, publishedChild])).toBe(
      "请先归档或调整该文档下的已发布子文档",
    );
    expect(documentationArchiveError("parent", [parent, draftChild])).toBeNull();
    expect(documentationArchiveError("published-child", [parent, publishedChild])).toBeNull();
  });

  it("allows analytics only with consent, complete published configuration and a published privacy page", () => {
    const state = createSeedState();
    const privacy = state.pages.find((page) => page.slug === "privacy")!;
    state.site.published.analytics = { providerName: "example", siteId: "site-1" };

    expect(analyticsCanStart(state.site, privacy, "granted")).toBe(true);
  });

  it.each(["unknown", "denied"] as const)(
    "blocks analytics when consent is %s",
    (consent) => {
      const state = createSeedState();
      const privacy = state.pages.find((page) => page.slug === "privacy")!;
      state.site.published.analytics = {
        providerName: "example",
        siteId: "site-1",
      };

      expect(analyticsCanStart(state.site, privacy, consent)).toBe(false);
    },
  );

  it.each([
    { providerName: "", siteId: "site-1" },
    { providerName: "   ", siteId: "site-1" },
    { providerName: "example", siteId: "" },
    { providerName: "example", siteId: "   " },
  ])("blocks analytics for incomplete published configuration %#", (analytics) => {
    const state = createSeedState();
    const privacy = state.pages.find((page) => page.slug === "privacy")!;
    state.site.published.analytics = analytics;

    expect(analyticsCanStart(state.site, privacy, "granted")).toBe(false);
  });

  it("blocks analytics until the privacy page is published", () => {
    const state = createSeedState();
    const privacy = state.pages.find((page) => page.slug === "privacy")!;
    state.site.published.analytics = {
      providerName: "example",
      siteId: "site-1",
    };
    privacy.publishedAt = null;

    expect(analyticsCanStart(state.site, privacy, "granted")).toBe(false);
    expect(analyticsCanStart(state.site, null, "granted")).toBe(false);
  });

  it("creates stable document slugs", () => {
    expect(slugify("  快速 开始！ ")).toBe("快速-开始");
  });

  it("creates, duplicates and reorders stable product blocks", () => {
    const hero = createProductBlock("hero");
    const markdown = createProductBlock("markdown");
    const duplicate = duplicateProductBlock(markdown);
    expect(duplicate.id).not.toBe(markdown.id);
    expect(moveProductBlock([hero, markdown], markdown.id, -1).map((block) => block.id)).toEqual([
      markdown.id,
      hero.id,
    ]);
    expect(moveProductBlock([hero, markdown], hero.id, -1)).toEqual([hero, markdown]);
  });

  it("validates a complete product page and rejects hidden media", () => {
    const state = createSeedState();
    const content: ProductPageContent = {
      ...state.product.draft,
      seoDescription: "产品页说明",
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
          id: "image",
          type: "image-text",
          enabled: true,
          eyebrow: "",
          title: "图文区块",
          markdown: "正文",
          mediaId: "media-review",
          mediaSide: "right",
        },
      ],
    };
    expect(validateProductPage(content, state.media)).toContain(
      "区块 2引用的图片当前已隐藏",
    );
    state.media.find((asset) => asset.id === "media-review")!.visible = true;
    expect(validateProductPage(content, state.media)).toEqual([]);

    state.media.find((asset) => asset.id === "media-review")!.alt = "";
    expect(validateProductPage(content, state.media)).toContain(
      "区块 2引用的图片缺少替代文本",
    );
    state.media.find((asset) => asset.id === "media-review")!.alt = "复习界面";
    state.media.find((asset) => asset.id === "media-review")!.src = "";
    expect(validateProductPage(content, state.media)).toContain(
      "区块 2引用的图片文件不可用，请重新上传",
    );
    expect(collectProductMediaIds(content)).toEqual(["media-review"]);
  });
});
