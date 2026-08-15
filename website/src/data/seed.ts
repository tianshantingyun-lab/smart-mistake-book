import type { ManagedPage, SiteContent, WebsiteState } from "../types";

const updatedAt = "2026-07-26T00:00:00.000Z";

const siteContent: SiteContent = {
  brandName: "智能错题本",
  brandTagline: "高中学习好帮手",
  heroTitle: "把每次错误，\n变成下一次进步",
  heroDescription:
    "产品功能仍在确认中；下载、文档与正式说明将在准备完成后通过官网发布。",
  primaryActionLabel: "下载 Android 版",
  secondaryActionLabel: "查看使用文档",
  trustTitle: "隐私信息以正式说明为准",
  trustBody:
    "网站当前未接入真实账号、安装包存储或第三方分析服务；正式发布前会补充完整隐私说明。",
  analytics: {
    providerName: "",
    siteId: "",
  },
};

function page(
  id: string,
  slug: ManagedPage["slug"],
  title: string,
  summary: string,
  body: string,
): ManagedPage {
  const content = {
    title,
    summary,
    body,
    seoTitle: `${title}｜智能错题本`,
    seoDescription: summary,
  };

  return {
    id,
    slug,
    draft: structuredClone(content),
    published: structuredClone(content),
    updatedAt,
    publishedAt: updatedAt,
  };
}

export function createSeedState(): WebsiteState {
  return {
    schemaVersion: 2,
    site: {
      id: "site",
      draft: structuredClone(siteContent),
      published: structuredClone(siteContent),
      updatedAt,
      publishedAt: updatedAt,
    },
    pages: [
      page(
        "page-about",
        "about",
        "关于智能错题本",
        "智能错题本官方网站的信息说明。",
        "本网站用于发布智能错题本的安装包、使用文档、更新日志和隐私信息。",
      ),
      page(
        "page-privacy",
        "privacy",
        "隐私说明",
        "正式发布前，这里会提供完整、可核对的隐私说明。",
        "> 当前网站处于本地预览阶段，尚未接入账号、安装包存储、联系表单或第三方分析服务。\n\n## 当前官网\n\n公开页面只展示管理员已发布的官网内容；本地演示后台的数据保存在当前浏览器中，不会作为正式线上服务收集。\n\n## 软件说明\n\n软件本身的数据处理方式仍在确认中，本页暂不作功能或数据流声明。\n\n## 正式发布前\n\n完整的数据处理范围、服务提供方、保存期限、用户权利和删除方式会在正式版本发布前补充并经审核后发布。",
      ),
      page(
        "page-contact",
        "contact",
        "联系",
        "联系方式尚未配置，可在管理员后台发布后显示。",
        "## 联系方式正在准备\n\n正式支持邮箱与其他联系渠道将在管理员后台配置后显示。",
      ),
    ],
    media: [
      {
        id: "media-brand",
        label: "应用图标",
        role: "brand",
        src: "/assets/brand/app-icon.png",
        alt: "智能错题本图标：打开的错题本与绿色钢笔",
        visible: true,
        bundled: true,
      },
      {
        id: "media-review",
        label: "今日复习界面",
        role: "hero-review",
        src: "/assets/product/review-home.png",
        alt: "智能错题本今日复习界面",
        visible: false,
        bundled: true,
      },
      {
        id: "media-tutor",
        label: "分层讲题界面",
        role: "hero-tutor",
        src: "/assets/product/tutor-chat.png",
        alt: "智能错题本分层讲题界面",
        visible: false,
        bundled: true,
      },
      {
        id: "media-library",
        label: "错题本界面",
        role: "hero-library",
        src: "/assets/product/mistake-library.png",
        alt: "智能错题本错题管理界面",
        visible: false,
        bundled: true,
      },
    ],
    releases: [],
    docs: [],
    product: {
      id: "product",
      slug: "product",
      status: "draft",
      draft: {
        navLabel: "产品功能",
        seoTitle: "产品功能｜智能错题本",
        seoDescription: "",
        showHomepageEntry: false,
        homepageEntryLabel: "了解产品功能",
        blocks: [],
      },
      published: null,
      updatedAt,
      publishedAt: null,
    },
  };
}
