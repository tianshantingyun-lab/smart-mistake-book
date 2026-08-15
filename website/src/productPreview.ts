import type { ProductPageContent } from "./types";
import { productPageContentSchema } from "./gateways/contentSchemas";

const PRODUCT_PREVIEW_KEY = "smart-mistake-book.website.product-preview";

export function writeProductPreview(content: ProductPageContent): void {
  sessionStorage.setItem(PRODUCT_PREVIEW_KEY, JSON.stringify(content));
}

export function clearProductPreview(): void {
  try {
    sessionStorage.removeItem(PRODUCT_PREVIEW_KEY);
  } catch {
    // An inaccessible session store cannot expose a cached preview.
  }
}

export function peekProductPreview(): ProductPageContent | null {
  let value: string | null;
  try {
    value = sessionStorage.getItem(PRODUCT_PREVIEW_KEY);
  } catch {
    return null;
  }
  if (!value) return null;

  try {
    const result = productPageContentSchema.safeParse(JSON.parse(value));
    if (result.success) return result.data;
  } catch {
    // Invalid JSON returns the persisted draft fallback.
  }

  return null;
}

export function readProductPreview(): ProductPageContent | null {
  const content = peekProductPreview();
  clearProductPreview();
  return content;
}
