import { MdArrowForward, MdOutlineImageNotSupported } from "react-icons/md";
import { Link } from "react-router-dom";
import type {
  MediaAsset,
  ProductPageBlock,
  ProductPageContent,
} from "../types";
import { MarkdownContent } from "./MarkdownContent";

function mediaFor(media: MediaAsset[], mediaId: string | null): MediaAsset | null {
  if (!mediaId) return null;
  return media.find((asset) => asset.id === mediaId && asset.visible) ?? null;
}

function MissingMedia({ preview }: { preview: boolean }) {
  if (!preview) return null;
  return (
    <div className="product-missing-media" role="status">
      <MdOutlineImageNotSupported aria-hidden="true" />
      <span>图片缺失或当前已隐藏</span>
    </div>
  );
}

function ProductBlock({
  block,
  media,
  preview,
}: {
  block: ProductPageBlock;
  media: MediaAsset[];
  preview: boolean;
}) {
  switch (block.type) {
    case "hero": {
      const asset = mediaFor(media, block.mediaId);
      return (
        <section className={`product-page-hero ${asset ? "" : "product-page-hero--text"}`}>
          <div>
            <h1>{block.heading || (preview ? "页面首屏标题" : "")}</h1>
            <p>{block.lead}</p>
          </div>
          {asset ? <img src={asset.src} alt={asset.alt} /> : <MissingMedia preview={preview && Boolean(block.mediaId)} />}
        </section>
      );
    }
    case "markdown":
      return (
        <section className="product-rich-text">
          {block.title && <h2>{block.title}</h2>}
          <MarkdownContent>{block.markdown}</MarkdownContent>
        </section>
      );
    case "image-text": {
      const asset = mediaFor(media, block.mediaId);
      return (
        <section className={`product-image-text product-image-text--${block.mediaSide}`}>
          <div className="product-image-text__copy">
            {block.eyebrow && <p className="eyebrow">{block.eyebrow}</p>}
            <h2>{block.title}</h2>
            <MarkdownContent>{block.markdown}</MarkdownContent>
          </div>
          <div className="product-image-text__media">
            {asset ? <img src={asset.src} alt={asset.alt} loading="lazy" /> : <MissingMedia preview={preview} />}
          </div>
        </section>
      );
    }
    case "gallery": {
      const assets = block.mediaIds
        .map((mediaId) => mediaFor(media, mediaId))
        .filter((asset): asset is MediaAsset => Boolean(asset));
      return (
        <section className="product-section product-gallery-section">
          <header>
            <h2>{block.title}</h2>
            {block.summary && <p>{block.summary}</p>}
          </header>
          <div className={`product-gallery product-gallery--${block.columns}`}>
            {assets.map((asset) => (
              <figure key={asset.id}>
                <img src={asset.src} alt={asset.alt} loading="lazy" />
                <figcaption>{asset.label}</figcaption>
              </figure>
            ))}
            {preview && assets.length !== block.mediaIds.length && <MissingMedia preview />}
          </div>
        </section>
      );
    }
    case "steps":
      return (
        <section className="product-section product-steps-section">
          <header>
            <h2>{block.title}</h2>
            {block.summary && <p>{block.summary}</p>}
          </header>
          <ol className="product-steps">
            {block.items.map((item, index) => (
              <li key={item.id}>
                <span>{String(index + 1).padStart(2, "0")}</span>
                <div>
                  <h3>{item.title}</h3>
                  <p>{item.body}</p>
                </div>
              </li>
            ))}
          </ol>
        </section>
      );
    case "cards":
      return (
        <section className="product-section product-cards-section">
          <header>
            <h2>{block.title}</h2>
            {block.summary && <p>{block.summary}</p>}
          </header>
          <div className={`product-cards product-cards--${block.columns}`}>
            {block.items.map((item) => (
              <article key={item.id}>
                <h3>{item.title}</h3>
                <p>{item.body}</p>
              </article>
            ))}
          </div>
        </section>
      );
    case "faq":
      return (
        <section className="product-section product-faq-section">
          <header><h2>{block.title}</h2></header>
          <div className="product-faq">
            {block.items.map((item) => (
              <details key={item.id}>
                <summary>{item.question}</summary>
                <p>{item.answer}</p>
              </details>
            ))}
          </div>
        </section>
      );
    case "cta": {
      const action = (
        <>
          {block.label}
          <MdArrowForward aria-hidden="true" />
        </>
      );
      return (
        <section className="product-page-cta">
          <div>
            <h2>{block.title}</h2>
            {block.body && <p>{block.body}</p>}
          </div>
          {block.href.startsWith("/") ? (
            <Link className="button button--primary" to={block.href}>{action}</Link>
          ) : (
            <a className="button button--primary" href={block.href} target="_blank" rel="noreferrer">
              {action}
            </a>
          )}
        </section>
      );
    }
  }
}

export function ProductPageRenderer({
  content,
  media,
  preview = false,
}: {
  content: ProductPageContent;
  media: MediaAsset[];
  preview?: boolean;
}) {
  const blocks = content.blocks.filter((block) => block.enabled);
  return (
    <article className="product-page page-container">
      {blocks.map((block) => (
        <ProductBlock key={block.id} block={block} media={media} preview={preview} />
      ))}
    </article>
  );
}
