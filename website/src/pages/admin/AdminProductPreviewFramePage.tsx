import { useEffect, useState } from "react";
import { Navigate, useLocation } from "react-router-dom";
import { useApp } from "../../app/AppContext";
import { PageMeta } from "../../components/PageMeta";
import { ProductPageRenderer } from "../../components/ProductPageRenderer";
import { clearProductPreview, peekProductPreview } from "../../productPreview";
import type { MediaAsset, ProductPageContent } from "../../types";

function CapturedProductPreview({
  fallback,
  media,
}: {
  fallback: ProductPageContent;
  media: MediaAsset[];
}) {
  const [content] = useState(
    () => peekProductPreview() ?? structuredClone(fallback),
  );

  useEffect(() => {
    clearProductPreview();
  }, []);

  return (
    <>
      <PageMeta
        title="产品页草稿画面｜智能错题本"
        description="管理员专用产品页草稿画面。"
        noIndex
      />
      <ProductPageRenderer content={content} media={media} preview />
    </>
  );
}

export function AdminProductPreviewFramePage() {
  const { session, contentScope, state } = useApp();
  const location = useLocation();
  if (!session || contentScope !== "admin") {
    return <Navigate to="/admin/login" replace state={{ from: location }} />;
  }

  return <CapturedProductPreview fallback={state.product.draft} media={state.media} />;
}
