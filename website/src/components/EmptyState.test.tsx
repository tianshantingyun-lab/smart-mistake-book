import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { EmptyState } from "./EmptyState";

describe("EmptyState", () => {
  it("keeps embedded empty states at heading level two by default", () => {
    render(<EmptyState icon={null} title="暂无内容" body="稍后再来查看。" />);

    expect(screen.getByRole("heading", { level: 2, name: "暂无内容" })).toBeVisible();
  });

  it("supports a level-one heading for standalone error pages", () => {
    render(
      <EmptyState
        icon={null}
        title="页面未找到"
        body="这个页面不存在。"
        headingLevel={1}
      />,
    );

    expect(screen.getByRole("heading", { level: 1, name: "页面未找到" })).toBeVisible();
  });
});
