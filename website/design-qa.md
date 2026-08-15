# Design QA

## Visual target

- Accepted reference: `design/reference-home-option-1.png`
- Final 1440 × 1024 implementation: `qa/home-neutral-1440x1024.png`
- Side-by-side comparison: `qa/home-neutral-reference-comparison.png`
- Screenshot method: Codex in-app Browser viewport capture at 1440 × 1024
- Visual inspection method: original-resolution inspection of both the accepted reference and final capture

## Fidelity ledger

1. Preserved the reference's warm paper background, deep-ink typography, jade primary action, restrained borders, and low-saturation supporting surfaces.
2. Preserved the compact brand lockup, four-item public navigation, active underline, generous header spacing, and existing App icon.
3. Preserved the large two-line product promise and the clear Android download/document action hierarchy.
4. Intentionally changed the hero from a two-column product montage to a centered single column because all three unfinished feature screenshots are now hidden. No empty frame, fake screenshot, or “建设中” placeholder is rendered.
5. Intentionally removed the three-step strip, feature descriptions, feature FAQ, and all copy about photographing, recognition, explanation, review, or mistake-book behavior.
6. Replaced the reference marketing paragraph with the approved neutral copy: “产品功能仍在确认中；下载、文档与正式说明将在准备完成后通过官网发布。”
7. Preserved the design system for the new modular product page; administrators may change content, order, visibility, and limited layout presets, but not colors, type, spacing, or custom CSS.

## Findings and fixes

### P0

- None.

### P1

- Resolved: the product page preview initially depended on the current draft tab. The dedicated preview route now renders the captured draft and offers real 375, 768, and 1440 px frame widths.
- Resolved: product media validation now rejects hidden, missing, or deleted media before an atomic page publish.

### P2

- Resolved: the visibility control now exposes its actual visible state through `aria-pressed`.
- Resolved: Testing Library roots are cleaned up after every test, preventing stale pages from contaminating route and login assertions.
- Resolved: the five administrator dashboard “管理” actions measured only 32 × 21 px at 375 px; each now has a 44 × 44 px minimum touch target.

## Browser verification

- Initial `/product` returned the normal “页面未找到” state and “产品功能” was absent from public navigation.
- Demo login, protected preview routing, blank product draft, SEO validation, and empty-state copy were checked.
- Added a page hero and Markdown block, then exercised copy, hide, move, and confirmed delete controls.
- Draft preview rendered the unpublished page at 375, 768, and 1440 px; the preview carries `noindex,nofollow`.
- Published the whole page and confirmed `/product`, “产品功能” navigation, and the separately enabled homepage entry appeared together.
- Archived the page and confirmed the public route, navigation item, and homepage entry disappeared again.
- Used “恢复初始数据” after the flow; the final state has an empty unpublished product draft, zero releases, zero documents, one visible brand asset, and three hidden feature screenshots.
- Public homepage checks at 375, 768, 1024, and 1440 px found no horizontal overflow and no visible interactive control shorter than 44 px.
- The public DOM contains a skip link, one page-level heading, semantic landmarks, responsive navigation, visible-focus rules, and reduced-motion rules.
- The final public homepage console contained no application errors or warnings.

## Regression checks

- Public source search found no hard-coded `learning-flow`, `featureRows`, three-step copy, or unfinished feature claims.
- Download, documentation, changelog, privacy, about, contact, media, releases, documents, and analytics routes remain available.
- Existing Android files were not modified.
- No production deployment was created.

## Maintenance regression — 2026-07-31

- Expanded the unfinished-feature search to the initial HTML metadata and every public page; the seed public snapshot contains none of the blocked product claims, while the three screenshots remain hidden in the admin media library.
- Rechecked the neutral homepage at 375, 768, 1024, and 1440 px: no horizontal overflow and no premature “产品功能” navigation entry.
- Saved a private homepage draft, then published analytics settings through their independent boundary; the public homepage kept the previous approved title.
- Published a temporary document, verified its public URL, archived it, and verified both the directory entry and public route disappeared.
- Restored the local demo seed after the workflow. The final homepage had no QA draft, no product navigation, no consent dialog, and no console warnings or errors.
- Rejected a fractional document order in the live administrator UI, then published a root and child document through the atomic command path.
- Verified the public document directory renders the child as a nested semantic list and search reports `所在目录：根文档验收`.
- Rechecked 375, 768, 1024, and 1440 px after the navigation-target adjustment: no horizontal overflow and every visible interactive target measured at least 44 × 44 px.
- Restored the neutral seed again after the hierarchy flow and visually inspected the final 1440 × 1024 warm-paper homepage; the public console remained free of application warnings and errors.
- Verified a dirty administrator edit is protected on normal navigation and browser Back; cancellation retains the exact field value and confirmation performs the requested navigation.
- Verified the public document article breadcrumb links to its published parent and marks the current article with `aria-current="page"`.
- Rechecked the public homepage at 375, 768, 1024, and 1440 px and the administrator dashboard at 375 px after the dashboard-target fix: no horizontal overflow and no visible interactive target below 44 × 44 px.
- Opened both public and administrator mobile navigation at 375 px, restored the neutral seed, reset the temporary viewport, and loaded a fresh browser tab; application console warnings/errors remained 0.

## Final two-round closeout — 2026-07-31

- Round 1 hardened local-demo recovery and media persistence: malformed v2 data now preserves a recovery snapshot, keeps valid homepage/page/document/media entries, and cleans unreferenced `media:` Blob records only outside recovery paths.
- Round 2 hardened managed-page publication: incomplete content is rejected, published pages can be taken offline while drafts remain, unpublished routes use the normal not-found treatment, and footer entries follow publication state.
- In the live browser, an empty Markdown body was rejected with the full publication requirement, the About page was archived, its public action and footer link disappeared, and `/about` rendered “页面未找到”.
- Used the administrator recovery action after that flow. The final homepage again showed the neutral product statement, no “产品功能” navigation, no horizontal overflow, all three published static-page footer links, and no application warning or error.
- Final verification: 28 test files / 284 tests, TypeScript typecheck, production build, and all 5 Sites worker tests passed.
- The final public preview remains local only; no Sites production deployment was created.

final result: passed
