import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { MarkdownContent } from "./MarkdownContent";

describe("MarkdownContent", () => {
  it("wraps GFM tables in a keyboard-accessible horizontal scroller", () => {
    render(
      <MarkdownContent>
        {"| 项目 | 状态 |\n| --- | --- |\n| 安装包 | 准备中 |"}
      </MarkdownContent>,
    );

    const region = screen.getByRole("region", { name: "可横向滚动的表格" });
    expect(region).toHaveAttribute("tabindex", "0");
    expect(screen.getByRole("table")).toBeVisible();
  });
});
