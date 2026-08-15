import { useApp } from "../../app/AppContext";
import { PageMeta } from "../../components/PageMeta";
import { ProductPageRenderer } from "../../components/ProductPageRenderer";
import { isProductPublished } from "../../domain";
import { NotFoundPage } from "./NotFoundPage";

export function ProductPage() {
  const { state } = useApp();
  if (!isProductPublished(state.product)) return <NotFoundPage />;

  return (
    <>
      <PageMeta
        title={state.product.published.seoTitle}
        description={state.product.published.seoDescription}
      />
      <ProductPageRenderer content={state.product.published} media={state.media} />
    </>
  );
}
