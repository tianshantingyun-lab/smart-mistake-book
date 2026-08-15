# Continuous Review Ledger

This ledger keeps the website's ongoing self-challenge work evidence-based. An item is only closed after a source-level fix and fresh verification.

## Iteration — 2026-07-31

### Evidence consulted

- GitHub remote: `tianshantingyun-lab/smart-mistake-book`
  - Remote `main` and local `HEAD` both point to `6776a79`.
  - GitHub reported no open pull requests.
  - The `website/` project is still untracked locally, so the current website changes are not yet present on GitHub.
- CodeRabbit CLI:
  - Installed in Ubuntu WSL, version `0.7.1`.
  - A review was not started because the fresh agent authentication check reports `not_authenticated`; the login attempt did not complete within the available interactive window.
- Vibecop:
  - Scanned 69 source files after the current challenge pass.
  - The shared unsaved-edit guard is clean; remaining findings are concentrated in large layout, product-block, document, media, release, product editor, REST-response, and analytics components.
- Independent read-only audits:
  - Admin workflow and content-management audit.
  - Public routes, accessibility, responsive-content, and visitor-flow audit.
  - Gateway concurrency, persistence, schema, and REST-boundary audit.
  - Runtime-schema, public/private REST boundary, documentation lifecycle, and unsaved-edit concurrency audits.
- In-app browser:
  - Verified `/about`, `/privacy`, and `/contact` render distinct published content.
  - Verified Cookie-dialog focus entry, Tab containment, Escape dismissal, and focus restoration.
  - Verified SPA navigation moves focus to `#main-content`.
  - Verified media search/filtering and dirty-state behavior without persisting test changes.
  - Verified the admin drawer at 375 px, including focus entry/containment, Escape and route dismissal, and trigger-focus restoration.
  - Verified no horizontal overflow at 375, 768, 1024, or 1440 px.
  - Verified an unpublished homepage draft stays private when analytics settings are published.
  - Verified a document appears only after explicit publication and disappears after archive.
  - Rejected a fractional document order without changing durable content.
  - Published a root document and child, then verified the public nested directory and search-result parent path.
  - Verified public document-article breadcrumbs link through the published parent hierarchy and mark the current article with `aria-current="page"`.
  - Verified a dirty media-row edit survives filtering the row out and back into the list.
  - Verified shared dirty-route protection on normal links and browser Back: cancel retains the page and value; confirm discards and navigates.
  - Rechecked 375, 768, 1024, and 1440 px after increasing narrow navigation targets; every visible control measured at least 44 × 44 px and no viewport overflowed.
  - Found five 32 × 21 px mobile dashboard actions, increased them to 44 × 44 px, and rechecked the 375 px administrator view with no remaining undersized target or horizontal overflow.
  - Verified both public and administrator mobile navigation open correctly at 375 px.
  - Restored the browser demo to its neutral seed after the workflow.
  - A fresh browser tab contained no application warnings or errors after the final public and admin flows.
  - Reproduced a stale two-tab edit, confirmed the second tab receives a 409 conflict without losing its typed value, and confirmed only the first edit reaches durable storage.
  - Rejected an incomplete static-page publication in the live administrator UI with an explicit field-completeness error.
  - Archived the published About page, confirmed its administrator public-link action disappeared, its footer entry disappeared, and `/about` rendered the not-found state.
  - Restored the neutral seed after the archive flow; About returned to the footer, the unsettled-product neutral copy remained intact, product navigation stayed hidden, horizontal overflow remained absent, and the fresh application warning/error log was empty.

### Closed in this iteration

- Serialized local gateway mutations so concurrent saves cannot overwrite each other.
- Persisted state before replacing the in-memory snapshot, preventing split-brain state after storage failure.
- Rolled back a staged IndexedDB media blob when its metadata commit fails.
- Added a recoverable application bootstrap error with a retry action instead of an endless loading screen.
- Corrected `/about`, `/privacy`, and `/contact` so each route renders its own managed page.
- Made published and archived releases immutable; operators must copy them into a new draft before editing.
- Prevented “保存并发布” on the site editor from publishing an older draft after save failure.
- Added media search, visibility filtering, dirty-state feedback, safe upload-resource deletion, and reference guards.
- Blocked product publication when referenced media is hidden, missing, lacks a file, or lacks alternative text.
- Added keyboard-modal behavior to the Cookie dialog and moved focus to the main landmark after SPA navigation.
- Increased small-text and focus-indicator contrast.
- Added responsive, keyboard-accessible wrappers and basic styling for Markdown tables.
- Marked missing documentation articles as `noindex,nofollow`.
- Made footer branding follow published site settings.
- Gave standalone not-found and unpublished states a level-one heading while retaining level-two headings for embedded empty states.
- Replaced the inaccessible off-canvas mobile admin sidebar with a modal drawer that is absent from the keyboard tree while closed.
- Increased async UI-test tolerance to avoid lazy-route timing flakes under parallel verification.
- Replaced the public REST `WebsiteState` cast with a strict, independent `PublicWebsiteState` DTO that structurally cannot contain drafts; authenticated admin state now has its own endpoint and scope.
- Added complete nested Zod validation for local v2, legacy v1, public REST, admin REST, sessions, all eight product blocks, media, releases, documents, and timestamps.
- Made corrupt-v2 recovery try a valid v1, persist the repaired v2 before deleting v1, and reject invalid REST responses without notifying subscribers.
- Coupled content and authentication runtime modes, added cookie-backed remote authentication, and ensured login waits for admin state while logout discards every admin draft before returning to public state.
- Split the content-session state machine out of `AppProvider`; both files now pass the targeted quality scan with no findings.
- Fixed seeded managed-page draft/published aliasing and validated captured product previews before rendering them.
- Completed the IndexedDB media lifecycle: successful reload hydration, object-URL cleanup, atomic reset behavior, and subscriber-error isolation.
- Added end-to-end analytics consent gate coverage and separated analytics draft/publication from homepage publication, preventing an analytics action from exposing an unrelated homepage draft.
- Restricted document draft commands to `id + DocumentationContent`, rejected invalid or duplicate public slugs, preserved old public snapshots on failure, and added archive support to local, REST, admin, and public flows.
- Cleared an APK selection when switching releases while preserving it after an attachment failure so the same file can be retried safely.
- Preserved dirty edits in one media row when another row is saved, including edits made while a save is in flight.
- Added explicit dirty confirmation, pending protection, and error handling before archiving a product page.
- Removed unsettled product claims from the initial HTML metadata, public documentation description, and seed privacy text; hidden feature screenshots remain admin-only.
- Added `noreferrer`/`noopener` protection to admin links and preview windows that open new tabs.
- Made first-load hydration, local reset, media Blob writes, and metadata commits share ordered initialization/mutation boundaries so late hydration cannot overwrite committed work.
- Removed disabled product blocks and private edit timestamps from the public DTO while retaining the complete administrator snapshot.
- Made site, managed-page, and document “保存并发布” commands atomic in both local and REST adapters; analytics draft/publication is now isolated in both directions.
- Added a real public documentation tree with stable sibling order, orphan/cycle recovery, parent-path search results, and accessible result announcements.
- Rejected missing, unpublished, self-referencing, or cyclic documentation parents at publication, and blocked parent archival while published children remain.
- Added field-level media PATCH semantics, per-field three-way editor merging, published-product media visibility protection, and save/delete mutual exclusion.
- Added release-editor dirty confirmation, native file-input cleanup, full pending locking, retry-safe failures, and mode-accurate local/remote package copy.
- Made product previews one-time and StrictMode-safe, cleared both parent and child caches, and fail-closed when storage, popup creation, or opener isolation fails.
- Made runtime administrator 401/403 responses synchronously discard private state, deduplicate recovery, ignore late responses, and remain public-only across a refresh after an unconfirmed remote logout.
- Added integer/range rules for document order, version code, and minimum Android; every local mutation now passes the complete v2 schema before durable state or subscribers are updated.
- Added a global test `scrollTo` shim and selected the stable Vitest thread pool, removing spurious environment output and fork-shutdown warnings.
- Increased narrow public navigation links to a 44 px minimum width as well as height.
- Added strong `ETag`/`If-Match` optimistic concurrency to the remote administrator gateway, serialized remote reads and writes, invalidated stale authorization epochs, forced resynchronization after ambiguous or conflicting writes, and isolated every subscriber callback.
- Added a shared multi-editor unsaved-change registry covering normal route changes, browser Back, refresh/close, pending operations, and confirmed administrator logout without leaking navigation bypass state.
- Added cycle-safe published documentation breadcrumbs and status-labelled administrator parent choices for published, draft, and archived candidates.
- Added pending locks and external-broadcast protection across product, analytics, release, and media editors while retaining retryable local inputs after failed operations.
- Increased administrator readiness-list actions to a 44 × 44 px minimum touch target.
- Added durable local revisions guarded by the Web Locks API so separate tabs cannot silently overwrite each other; unsupported browsers remain readable but reject writes instead of using an unsafe process-local fallback.
- Strengthened the concurrency tests with an asynchronous critical section shared by two gateway instances, and verified every IndexedDB connection is closed exactly once by object identity.
- Preserved malformed local v2 snapshots before repair, recovered valid fields and list entries instead of replacing the whole site, preferred a valid v1 snapshot when available, and prevented a reset-era stale tab from resurrecting old content.
- Added bounded-namespace orphan cleanup for unreferenced `media:` Blob records while skipping cleanup during a recovery path.
- Reworked public download, documentation, changelog, contact, and unpublished-page states around visitor actions rather than implementation terminology; metadata-only releases no longer resemble fake downloads.
- Made the public mobile menu expose its controlled navigation, focus the first item, close on Escape or same-route selection, and restore focus to the trigger.
- Added a privacy link inside the Cookie dialog without changing consent, and tied managed-page footer links to actual publication state.
- Added complete static-page publication validation, local and REST archive commands, administrator confirmation/pending/error handling, public not-found behavior, and automatic footer-link removal after archive.
- Required exactly one uniquely identified About, Privacy, and Contact page in administrator state; public responses now allow at most three unique static pages and hydrate unpublished pages as private-free placeholders that preserve runtime invariants.
- Restored the neutral demo seed after every live mutation used for the final browser acceptance flow.

### Next challenge queue

1. Bind analytics consent to the provider, site ID, and approved privacy-policy version; fail closed on persistence errors and require substantive disclosure approval before a future real script adapter can start.
2. Add authenticated static-page draft preview and strengthen published-content schemas so a remote snapshot cannot mark blank content as published.
3. Surface missing media Blob/read failures in the administrator UI, limit orphan cleanup per pass, and repair duplicate cross-slug page IDs without falling back all three pages.
4. Consolidate product block defaults, validation, media references, editor fields, and renderers into one typed registry.
5. Extract the Cookie dialog/focus trap from `PublicLayout`, then split the remaining large document, media, release, and product-editor components without changing behavior.
6. Reduce response-state complexity in `parseAdminResponse`, add production-service integration tests, CSRF protection, server-rendered/edge metadata, and deployment security headers when the real backend is introduced.
7. Add cancellation, failure, and deferred-pending UI tests for static-page archive.
8. Run a fresh CodeRabbit `uncommitted` review after CLI authentication and feed only confirmed findings back into this ledger.

### Verification

- `npm run typecheck`: passed.
- `npm test -- --reporter=dot`: 28 files, 284 tests passed.
- `npm run build`: 415 modules transformed; Sites package prepared.
- `npm run test:sites`: 5 tests passed, including the neutral initial SEO regression.
- In-app browser: stale-tab conflict protection, static-page publication rejection, archive/public removal, neutral-seed restoration, prior public 375/768/1024/1440 px checks, and administrator 375 px overflow/touch-target checks passed; final fresh-tab application console warnings/errors: 0.
- Vibecop scanned 69 source files. It still reports large-component, product-registry, and response-state complexity; these are recorded above rather than treated as completion claims.
- CodeRabbit review was not started: the final CLI agent authentication check returned `not_authenticated`.
- `.openai/hosting.json` remains unbound (`d1` and `r2` are `null`); no production Sites deployment was created.
