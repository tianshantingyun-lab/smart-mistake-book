import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { ProductPageContent } from "./types";
import {
  peekProductPreview,
  readProductPreview,
  writeProductPreview,
} from "./productPreview";

const PRODUCT_PREVIEW_KEY = "smart-mistake-book.website.product-preview";

const validPreview: ProductPageContent = {
  navLabel: "产品功能",
  seoTitle: "产品功能",
  seoDescription: "产品功能介绍",
  showHomepageEntry: false,
  homepageEntryLabel: "了解产品",
  blocks: [],
};

describe("product preview storage", () => {
  beforeEach(() => sessionStorage.clear());
  afterEach(() => vi.restoreAllMocks());

  it("returns a valid stored preview", () => {
    writeProductPreview(validPreview);

    expect(peekProductPreview()).toEqual(validPreview);
    expect(sessionStorage.getItem(PRODUCT_PREVIEW_KEY)).not.toBeNull();
    expect(readProductPreview()).toEqual(validPreview);
    expect(sessionStorage.getItem(PRODUCT_PREVIEW_KEY)).toBeNull();
  });

  it("removes preview storage containing invalid JSON", () => {
    sessionStorage.setItem(PRODUCT_PREVIEW_KEY, "{");

    expect(readProductPreview()).toBeNull();
    expect(sessionStorage.getItem(PRODUCT_PREVIEW_KEY)).toBeNull();
  });

  it("removes valid JSON that does not match the product content schema", () => {
    sessionStorage.setItem(
      PRODUCT_PREVIEW_KEY,
      JSON.stringify({ ...validPreview, showHomepageEntry: "yes" }),
    );

    expect(readProductPreview()).toBeNull();
    expect(sessionStorage.getItem(PRODUCT_PREVIEW_KEY)).toBeNull();
  });

  it("fails safely when session storage cannot be read", () => {
    const removeItem = vi.spyOn(sessionStorage, "removeItem");
    vi.spyOn(sessionStorage, "getItem").mockImplementation(() => {
      throw new DOMException("Blocked", "SecurityError");
    });

    expect(readProductPreview()).toBeNull();
    expect(removeItem).toHaveBeenCalledWith(PRODUCT_PREVIEW_KEY);
  });
});
