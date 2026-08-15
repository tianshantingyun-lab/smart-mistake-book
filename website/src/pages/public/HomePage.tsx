import {
  MdArrowForward,
  MdCheckCircleOutline,
  MdOutlineAndroid,
  MdOutlineAutoAwesome,
  MdOutlineLock,
} from "react-icons/md";
import { Link } from "react-router-dom";
import { useApp } from "../../app/AppContext";
import { PageMeta } from "../../components/PageMeta";
import { isProductPublished } from "../../domain";

export function HomePage() {
  const { state, mediaByRole, track } = useApp();
  const site = state.site.published;
  const brand = mediaByRole("brand");
  const heroReview = mediaByRole("hero-review");
  const heroTutor = mediaByRole("hero-tutor");
  const heroLibrary = mediaByRole("hero-library");
  const hasHeroMedia = Boolean(heroReview && heroTutor && heroLibrary);
  const product = isProductPublished(state.product) ? state.product.published : null;

  return (
    <>
      <PageMeta
        title="智能错题本｜把每次错误，变成下一次进步"
        description={site.heroDescription}
      />
      <section className={`home-hero ${hasHeroMedia ? "" : "home-hero--neutral"}`}>
        <div className="page-container home-hero__inner">
          <div className="hero-copy">
            <p className="eyebrow">智能错题本 Android 官方网站</p>
            <h1>{site.heroTitle}</h1>
            <p className="hero-description">{site.heroDescription}</p>
            <div className="hero-actions">
              <Link
                className="button button--primary button--large"
                to="/download"
                onClick={() => track("download_intent", { source: "hero" })}
              >
                <MdOutlineAndroid aria-hidden="true" />
                {site.primaryActionLabel}
              </Link>
              <Link className="text-action" to="/docs">
                {site.secondaryActionLabel}
                <MdArrowForward aria-hidden="true" />
              </Link>
              {product?.showHomepageEntry && (
                <Link className="text-action" to="/product">
                  {product.homepageEntryLabel}
                  <MdArrowForward aria-hidden="true" />
                </Link>
              )}
            </div>
            <p className="compatibility-note">
              <MdCheckCircleOutline aria-hidden="true" />
              适用于 Android 6.0 及以上版本
            </p>
          </div>

          {hasHeroMedia && (
            <div className="product-stage" aria-label="智能错题本应用界面预览">
              <img className="stage-watermark" src={brand?.src} alt="" />
              <img
                className="product-shot product-shot--review"
                src={heroReview!.src}
                alt={heroReview!.alt}
              />
              <img
                className="product-shot product-shot--tutor"
                src={heroTutor!.src}
                alt={heroTutor!.alt}
              />
              <img
                className="product-shot product-shot--library"
                src={heroLibrary!.src}
                alt={heroLibrary!.alt}
              />
            </div>
          )}
        </div>
      </section>

      <section className="trust-section" aria-labelledby="trust-title">
        <div className="page-container trust-section__inner">
          <div className="trust-illustration">
            <img src={brand?.src || "/assets/brand/app-icon.png"} alt="" loading="lazy" />
          </div>
          <div className="trust-copy">
            <span className="feature-symbol"><MdOutlineLock aria-hidden="true" /></span>
            <p className="eyebrow">当前网站状态</p>
            <h2 id="trust-title">{site.trustTitle}</h2>
            <p>{site.trustBody}</p>
            <Link className="text-action" to="/privacy">
              阅读隐私说明 <MdArrowForward aria-hidden="true" />
            </Link>
          </div>
        </div>
      </section>

      <section className="final-cta">
        <div className="page-container final-cta__inner">
          <MdOutlineAutoAwesome aria-hidden="true" />
          <div>
            <p className="eyebrow">安装包与文档将按发布状态显示</p>
            <h2>首个公开版本正在准备中</h2>
          </div>
          <Link className="button button--primary" to="/download">
            查看发布状态 <MdArrowForward aria-hidden="true" />
          </Link>
        </div>
      </section>
    </>
  );
}
