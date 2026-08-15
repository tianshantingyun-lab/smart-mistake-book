# Prototype Instructions

Run the local server yourself and open the preview in the browser available to this environment. Do not give the user server-start instructions when you can run it.

Before making substantial visual changes, use the Product Design plugin's `get-context` skill when the visual source is unclear or no longer matches the current goal. When the user gives durable prototype-specific design feedback, preferences, or decisions, record them in `AGENTS.md`.

When implementing from a selected generated mock, treat that image as the source of truth for layout, component anatomy, density, spacing, color, typography, visible content, and hierarchy.

Build app UI in `src/`. Keep `.openai/hosting.json`, `worker/index.js`, `scripts/prepare-sites-build.mjs`, and `tests/sites-worker.test.mjs` intact so the same local prototype can be handed to Sites. Before a Sites handoff, run `npm run build` and `npm run test:sites`; the build must leave `dist/client/index.html`, `dist/server/index.js`, and `dist/.openai/hosting.json`.

## Durable Product Decisions

- The first displayed Product Design concept is the homepage visual source of truth.
- Keep the public site Simplified Chinese, warm off-white, deep ink, and restrained jade green.
- The public site targets high-school students first and keeps one clear download action.
- Preserve public routes for downloads and documentation even when content is empty.
- Keep existing Android screenshots in the media library but hide them publicly until the product functions and approved visuals are finalized.
- Keep the admin demo explicit: local browser persistence, demo login, and metadata-only APK uploads.
- Use Stable and Beta release channels, Markdown documentation, draft-to-publish workflow, and a single-admin model.
- Keep product explanations out of the public homepage until the modular `/product` page is explicitly published from an initially blank draft.
- Product-page flexibility means controlled block types, ordering and visibility within the existing design system; do not add free-form styling or custom CSS controls.
- Stay Sites-ready but do not deploy unless the user explicitly asks.
