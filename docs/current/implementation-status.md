# Implementation Status

> **IMPORTANT**: This document contains hand-written status notes from development sessions.
> For the authoritative, CI-generated build status, see `docs/status.md`
> (auto-generated from commit SHA, test XML results, APK/AAB hashes, and benchmark results).
> Never claim "build passed" or "tests passed" based on this document alone.

Updated from the remediation plan for the Android client on `main`.

## Done and verified this turn

- MOD-P1-013 continued: prompt/parse per task is OpenAiModelTaskAdapters. Protocol only builds
  the chat envelope and reads the SSE/JSON shell. Protocol is 243 lines; adapters 407.
  :core:data:testDebugUnitTest 170/170.
- CAP-P1-017 continued: assess observe/split/egress bind/dispatch and parse observe/dispatch/adopt
  are CaptureModelTaskCommands. CaptureScreen is 1532 lines.
  :feature:capture:testDebugUnitTest 117/117.

- MOD-P1-013 started: tutor visual response parsers moved to OpenAiTutorVisualResponseParsers.kt
  (631 lines). Capture/text parsers remain in OpenAiModelResponseParsers.kt (498 lines).
  :core:data:testDebugUnitTest 170/170.
- CAP-P1-017 continued: process-death URI recovery and draft resume/hydrate are CaptureResumeCommands.
  Owned-URI recovery is a pure map. CaptureScreen is 1587 lines.
  :feature:capture:testDebugUnitTest 117/117.

- TUT-P1-020 continued: choice/move/restart are TutorInteractionCommands; visual generate/review
  dispatch is TutorVisualWorkCommands with existing-task reuse policy.
  TutorSessionPanel is 1392 lines. :feature:tutor:testDebugUnitTest 86/86.
- CAP-P1-017 continued: imported/saved/failed/tutor-session workflow events are
  CaptureWorkflowEventCommands. CaptureScreen is 1655 lines.
  :feature:capture:testDebugUnitTest 117/117.

- CAP-P1-017 continued: returned-image application is now CaptureReturnedImageCommands plus a
  pure captureReturnedImageApplication map. CaptureScreen only applies the sink. Screen is
  1683 lines. CaptureReturnedImageApplicationTest 4/4; :feature:capture:testDebugUnitTest 117/117.
- TUT-P1-020 continued: tutor respond collect/execute/retry moved to TutorRespondCommands with
  student-facing send/limit/validation/network copy and lease/pending gates.
- TUT-P1-020 continued: tutor plan execute moved to TutorPlanCommands; first-cycle auto-start
  approval is tutorPlanAutoStartApprovedAt and still uses tutorExternalPlanApprovedAt.
  TutorSessionPanel is 1533 lines.
  TutorSessionInteractionPolicyTest 6/6; :feature:tutor:testDebugUnitTest 86/86.

- CAP-P1-016: after process death, owned capture URIs that no longer have files
  are cleared with student-facing copy; picker URIs are left alone. Rotation
  still keeps files via isChangingConfigurations. CaptureDraftLifecyclePolicyTest 11/11.
- Tutor plan dispatch now uses tutorExternalPlanApprovedAt (lease then auto-start).
- Orphan asset GC is unique WorkManager work (`orphan-asset-gc`, KEEP, battery-not-low).
  App startup and the storage screen enqueue the same job; the storage page shows a
  student-facing status line. OrphanAssetGcTest 2/2.
- Tutor session choice/move/restart gates and student-facing save errors are now
  TutorSessionInteractionPolicy; TutorModelPanel delegates to them.
- OpenAI-compatible Gateway now speaks SSE for tutor text tasks: request bodies
  carry stream=true, transport reads text/event-stream, deltas are concatenated
  back into a chat-completion envelope, and cancelling the coroutine still
  cancels the OkHttp call. Capture/visual/organization stay non-stream.
  Configured external providers advertise supportsStreaming=true.
  OpenAiSseTest 2/2; OpenAiModelTransportCancellationTest 2/2;
  OpenAiCompatibleModelGatewayTest and protocol MockWebServer tests.
- Capture source import/replace/append/commit moved to CaptureSourceImportCommands.
  Request identity reuse, busy/incomplete commit, and student-facing page-limit
  copy are policy. CaptureScreen is 1654 lines.
  CaptureSourceImportPolicyTest 3/3; :feature:capture:testDebugUnitTest 112/112.
- Capture draft lifecycle policy extracted: resume load/apply, imported new/append
  request IDs, saved/failed workflow, parse text adoption, and tutor auto-start
  authorization. CaptureScreen is 1685 lines.
  CaptureDraftLifecyclePolicyTest 10/10; :feature:capture:testDebugUnitTest 109/109.
- Capture model-task split/parse policy is now wired: observe, split, assess/parse
  dispatch, append snapshots, and resume page selection go through
  CaptureModelTaskPolicy. CaptureScreen is 1699 lines.
  CaptureModelTaskPolicyTest 8/8; :feature:capture:testDebugUnitTest 99/99.
- Capture workspace save/flush moved to `CaptureWorkspaceCommands`; failed writes map to `CAPTURE_WORKSPACE_SAVE_ERROR`. Screen is 1754 lines.
- Capture launch commands moved to `CaptureAcquisitionCommands`; `CaptureScreen` now delegates camera/picker start. Screen is 1769 lines.
- Capture create/launch outcomes: camera create vs launch-fail vs picker unavailable are explicit; `CaptureAcquisitionLaunchPolicyTest` 9/9.
- Capture Activity launchers extracted to `rememberCaptureAcquisitionLaunchers`; `CaptureReturnedImagePlanTest` now 7/7.
- Capture launch prep/failure copy: replace-draft request allocation and camera/picker unavailable messages are policy; `CaptureAcquisitionLaunchPolicyTest` 7/7.
- Capture returned-image plans: camera/picker results map to Apply/Replace/Append/KeepCurrent; `CaptureReturnedImagePlanTest` 6/6.
- Capture workspace flush/leave/append policy extracted; `CaptureWorkspaceFlushPolicyTest` 4/4.
- Capture acquisition launch policy: camera/picker now share one busy/flush
  gate; `CaptureAcquisitionLaunchPolicyTest`.
- Capture recovery policy: approving egress after a failed assess/parse
  now goes through `captureRecoveryApplication`; unit tests.
- Gateway HTTP cancellation: cancelling the coroutine cancels the OkHttp
  call immediately; unit test.
- Capture retry policy extracted from `CaptureScreen` into
  `nextCaptureTaskRetry` with unit tests.
- Learning DAO split (DB-P1-011): query/fact, attempt, projection, models,
  and mappings live in separate files; compile and unit tests.
- Old-schema backup restore (BAK-P1-008): v30 fixture archive migrates on
  restore; device test `oldSchemaArchiveMigratesOnRestore`.
- Tutor lobby drafts (TUT-P0-011): Room v31 `student_draft`, repository
  save/clear, Lobby restore/clear-on-send, JVM contract tests.
- Visual work failure/limit UI (TUT-P1-026): planner keeps the latest 8
  items, overflow and per-item failures are visible, JVM pipeline tests.
- PR-00 engineering baseline: `.gitattributes`, Android CI with unit,
  assemble, lint, release dry-run, and emulator instrumentation jobs, PR
  template, `DEVELOPMENT.md`, `RELEASE.md`, `ARCHITECTURE.md`,
  `CONTRIBUTING.md`, `PRIVACY-DATA-FLOW.md`, and `docs/current/` contract
  entry points.
- P0 tutor cleanup: production route restored, temporary SimpleTutor route and
  parallel image system removed, Lobby is text-only, FileProvider narrowed.
- PR-03 tutor persistence: Room v29 adds `tutor_conversation` and
  `tutor_message`; domain/data repository, stable IDs, Lobby sends persist the
  student message before dispatch, history list route is wired.
- PR-02/06 contract work: `ModelTaskContractRegistry` plus parity tests lock
  prompt policy, asset policy, disclosure sets, and shared dispatch budget.
- Startup: `StartupState` is surfaced in the root UI; initialization no longer
  silently swallows knowledge-package failure.
- Backup: `.smbk` v1 codec creates, validates, unpacks, and restores archives
  with manifest, database, assets, and SHA-256 checksums. Restore stages into
  a temp directory, swaps the database and assets with rollback, and closes the
  old database so the user can restart. Delete-all-data covers the database,
  canonical assets, preferences, and temp files.
- Library: Room v30 adds a `library_catalog` view and `LibraryQueryDao` with
  SQL filtering, sorting, real facet counts, `PagingSource`, and paged UI.
  Silent `.take(8/12/12/6)` truncation is removed from the production path.
- Capture workflow: `CaptureViewModel`, `CaptureWorkflowUiState`, and
  `CaptureAction` now own the durable workflow state machine, resume loading,
  source import/replacement/append, and library/tutor confirmation. The
  workflow failure card is wired into `CaptureScreen`; `CaptureScreen` now
  delegates new-source import, append-page, replacement, and commit to the
  ViewModel and only applies emitted draft/session events to local UI state.
  Unit tests cover source import, append/replace, retry, missing resume,
  idempotent library confirmation, and commit-failure replay.
- Capture request contract: assessment/parse `ModelTaskRequest` construction is
  extracted to pure factories with per-field unit tests, and `CaptureScreen`
  now builds those requests through the factories instead of inline code.
- Capture execution: `CaptureModelTaskCoordinator` owns assessment/parse
  dispatch, duplicate-request suppression, and the egress launch guard.
  `CaptureScreen` now delegates model-task execution to it and only consumes
  emitted snapshots. Tests cover assessment, parse, duplicate suppression, and
  fail-closed external dispatch, plus retry-after-completion semantics.
- File structure: `CaptureScreen.kt` fell from 2385 to 1869 lines with the
  policy helpers and status/action components moved to `CaptureScreenPolicy.kt`
  and `components/CaptureScreenComponents.kt`. `CapturedTutorSessionRoute.kt`
  fell from 2441 to 522 lines with the tail task/disclosure/status components
  moved to `components/TutorSessionComponents.kt` and the 1497-line
  `TutorModelPanel` moved to `components/TutorSessionPanel.kt`.
- Tutor session state: `TutorSessionViewModel` now owns session loading,
  conversation anchoring, save, end-without-save, save/end errors, and the
  long-term-write guard; `CapturedTutorSessionRoute` only renders those states
  and emits the end event. Unit tests cover load, save, and end lifecycle.
- Tutor send contract: `TutorTurnSendStateMachine` freezes the double-tap,
  three-dispatch budget, and restart-requires-continue rules as a pure domain
  state machine with unit tests. It is now wired into `TutorModelPanel`'s
  response send/retry path: first sends freeze one logical operation, retries
  preserve the dispatch budget, and terminal model-task snapshots update the
  state instead of leaving the UI stuck.
- Save-to-library contract: `SaveTutorDraftToLibraryUseCase` makes saving an
  explicit, idempotent command with a deterministic request id; a second tap
  reports `alreadySaved` instead of creating a second error-book entry.
  `TutorSessionViewModel` now routes its save button through this use case.
- Error contract: the two previously separate error systems are merged into a
  single `AppFailure` in `core:model`, carrying the `AppFailureCode` enum,
  `dataPreserved`, `retryability`, and typed recovery `actions` that reuse
  `ActionType`; the failure value still rejects raw exception text.
  `ModelFailureCode` maps to stable `AppFailureCode`s, and `CaptureViewModel`
  now emits `AppFailure` for import/commit failures instead of raw strings,
  and `CaptureScreen` displays only the safe business message.
  `TutorSessionViewModel` and `TutorLobbyRoute` use the same failure type for
  save/end/send failures; `TutorModelPanel` chat-start errors also use it.
  `ModelTaskFailure` now maps to `AppFailure` with retry-aware recovery
  actions. Library automatic-organization failures use it too, and Review
  submission/reveal retry messages have a contract-level mapping that the
  Review screens now consume. `reserveModelTaskRemoteDispatch` and
  `splitDraft` are abstract required methods instead of
  `UnsupportedOperationException` defaults, and `StudyDatabasePort` is now an
  aggregate of responsibility-domain sub-interfaces.
- Protocol: `OpenAiModelProtocolMockWebServerTest` now proves the outbound chat
  body contains the authorized base64 image and rejects malformed provider
  responses before UI success, using a real local MockWebServer. The Gateway
  test additionally walks the full authorize → read asset → enqueue → parse
  path against MockWebServer, proving the authorized image reaches the actual
  HTTP request.
- Library scale: `LibraryCatalogPagingInstrumentedTest` seeds 50,000 error-book
  rows and verifies SQL paging, search, and subject facet counts without
  loading the full catalog into memory. It compiles as part of the database
  androidTest source set; the device run remains part of the emulator matrix.
- Accessibility: `CaptureTouchTargetsInstrumentedTest` asserts the primary
  capture action, outline pick action, and top-bar back button meet the 48dp
  touch-target minimum.
- Performance scaffold: a `benchmark` module now compiles a
  `StartupBenchmark` (cold startup metric) and `BaselineProfileGenerator`;
  `app` depends on `androidx.profileinstaller`, and CI compiles the module so
  device-side macrobenchmark runs are the only remaining gate.
- Workflow CAS: `CaptureWorkflowStateMachine` now accepts a basis-revision
  advance only when the incoming result belongs to the same active operation,
  so append/replace can move revision 1 to 2 without letting stale results
  overwrite a newer draft.
- Schema: `16.json` was reconstructed from the v15 schema plus the v15-to-v16
  DDL (model-task operation tables) and the historical v17 exposure schema.
  Its identity hash is a placeholder and was not generated by Room, so schema
  validation for a real v16 device fixture still needs a device test.
- Schema contract: `ExportedSchemaContractTest` now validates every checked-in
  schema on the JVM (version/filename parity, generated-looking identity hash,
  entity createSql and primary key, Room master table) and fails if any
  placeholder hash appears outside the explicitly scoped v16 exception.
- Backup codec: unit coverage now includes missing-manifest, tampered
  checksum, and future-format-version rejection, in addition to the existing
  round-trip, tampered asset, unpack, and path-traversal cases.
- Gateway protocol: unit coverage now sends 429, 5xx, 401/403, connection
  failure, and timeout through the real gateway execution path and asserts the
  stable failure codes (`RATE_LIMITED`, retryable service failure,
  `AUTHENTICATION_FAILED`, `NETWORK_UNAVAILABLE`, `TIMEOUT`) that UI consumes.
- Orphan cleanup: `PendingCaptureDao` finds and deletes canonical source assets
  with no `problem_revision_source_asset` or `problem_draft_source_asset`
  reference, `BackupRepository.cleanupOrphanAssets()` removes vault files
  (tolerating already-missing files) before deleting the rows, and the storage
  screen exposes a cleanup action that refreshes the inventory. A device test
  proves unreferenced assets are removed while the referenced catalog stays
  intact (`CAP-P1-014`, `BAK-P1-012`).
- Backup missing-file contracts: the codec test suite now also rejects an
  archive whose manifest lists an asset that is absent and an archive missing
  `database.sqlite`, so restore cannot proceed with an incomplete file set
  (`BAK-P1-010`).
- Conversation delete: `TutorConversationRepository.deleteConversation` is
  implemented through the DAO (messages then conversation in one transaction),
  exposed in the history screen with a confirmation dialog, and covered by a
  database test proving the anchored problem, revision, messages are removed
  only for the conversation while the library entry survives, plus two
  instrumented UI tests for confirm/cancel (`TUT-P1-025`).

## Verification

Continuation on 2026-08-17 (session handoff) re-verified from `D:\smb-build`:

- `:app:compileLocalFirstDebugKotlin` and `:feature:tutor:compileDebugKotlin`
- `:core:domain:test` including `TutorConversationContractTest` 7/7
- `:feature:tutor:testDebugUnitTest` `TutorVisualPipelineTest` 7/7 and
  `TutorLobbyModelTaskPolicyTest` 2/2
- `:core:database:testDebugUnitTest` `ExportedSchemaContractTest`
- `testLocalFirstDebugUnitTest` and `testStrictOfflineDebugUnitTest`
- `:app:assembleLocalFirstDebug` and `:app:assembleStrictOfflineDebug`
- `lintLocalFirstDebug` and `lintStrictOfflineDebug`
- `:app:assembleLocalFirstRelease` and `:app:assembleStrictOfflineRelease` (R8)
- Device on `emulator-5556` (API 34):
  `TutorConversationMigrationInstrumentedTest` 6/6
  and `:feature:tutor:connectedDebugAndroidTest` 57/57


Run from an ASCII directory junction on Windows:

```powershell
cd D:\smb-build
.\gradlew.bat testLocalFirstDebugUnitTest testStrictOfflineDebugUnitTest
.\gradlew.bat :app:assembleLocalFirstDebug :app:assembleStrictOfflineDebug
.\gradlew.bat lintLocalFirstDebug lintStrictOfflineDebug
```

Current-turn compilation and unit tests for:

- `:core:database:compileDebugKotlin`
- `:core:database:compileDebugAndroidTestKotlin`
- `:core:data:compileDebugKotlin` and `:core:data:testDebugUnitTest`
- `:core:domain:compileKotlin`
- `:feature:library:compileDebugKotlin` and `:feature:library:testDebugUnitTest`
- `:feature:capture:compileDebugKotlin` and `:feature:capture:testDebugUnitTest`
- `:feature:tutor:compileDebugKotlin` and `:feature:tutor:testDebugUnitTest`
- `:app:compileLocalFirstDebugKotlin` and `:app:compileStrictOfflineDebugKotlin`

The full two-flavor gate also this turn:

- `testLocalFirstDebugUnitTest` and `testStrictOfflineDebugUnitTest`
- `:app:assembleLocalFirstDebug` and `:app:assembleStrictOfflineDebug`
- `lintLocalFirstDebug` and `lintStrictOfflineDebug`
- `:app:assembleLocalFirstRelease` and `:app:assembleStrictOfflineRelease` (R8)
- Capture/Tutor/database/data debug-android-test Kotlin compilation

> **Note**: The above build status was recorded during a development session.
> For authoritative, reproducible build verification, always run:
> ```
> ./gradlew clean testLocalFirstDebugUnitTest testStrictOfflineDebugUnitTest
> ./gradlew assembleLocalFirstRelease assembleStrictOfflineRelease
> ```
> and verify the output matches the commit SHA being evaluated.
> Never claim "build passed" or "tests passed" based on this document alone.

## Not yet complete (do not claim release)

- **v16 Schema Limitation**: The v16 identity hash is a reconstructed placeholder
  (not Room-generated) and is explicitly locked to v16 by `ExportedSchemaContractTest`.
  v16 is NOT supported for production use. Users upgrading from v16 must use the
  safe import migration path which reconstructs data from available evidence rather
  than performing a direct schema migration. The migration test
  `versionSixteenMigratesToCurrentWithoutDestructiveFallback` verifies that the
  import path works, but this is not equivalent to having a real v16 fixture.
  **Action required**: If any real v16 APKs exist in the wild, the identity hash
  must be recovered from those APKs and a proper fixture created. Until then,
  v16 migration should be documented as "best-effort import" rather than
  "guaranteed migration".
- Backup restore and delete-all-data now pass the device round-trip test; the
  backup pipeline performs a WAL checkpoint before packaging so committed rows
  are captured. Interrupted-swap and low-space matrices still need more device
  coverage. `BAK-P1-008` now restores a v30 exported-schema `.smbk` fixture
  through the current restore path; `oldSchemaArchiveMigratesOnRestore`
  on the API 34 emulator.
- Capture workflow split continues: retry and egress-recovery decisions are
  now pure functions (`nextCaptureTaskRetry`, `captureRecoveryApplication`)
  with unit tests. `CaptureScreen` still owns UI mutation for imported-draft / resume / confirm, but those
  decisions now go through CaptureDraftLifecyclePolicy. Split/parse/observe
  go through CaptureModelTaskPolicy. The screen is no longer the source of
  those request IDs or recovery rules.
- The model Gateway is split into protocol/parser/parsing/asset files.
  HTTP call cancellation now uses `suspendCancellableCoroutine` +
  `invokeOnCancellation { cancel() }`; `OpenAiModelTransportCancellationTest`
  proves a cancelled coroutine cancels the in-flight OkHttp call. SSE parser and
  cancellable stream reads are now unit-tested for tutor text tasks. Per-task
  adapters and real Provider protocol/quality evaluation still have not run.
- `DB-P1-011` is done: `LearningDao.kt` is split into query/fact, attempt
  transaction, projection transaction, models, and mapping files. Database
  compile and unit tests.
- Device suites on an API 34 emulator: `core:database` 125/125
  (including the full v1→v31 draft-column migration), `core:data` 67/67 (including
  backup round-trip and orphan cleanup), `feature:capture` 25/25 (including
  48dp touch targets), `feature:tutor` 57/57 (rerun this continuation after visual-work UI), `feature:library` 25/25, and `app` 26/26.
- App root fixes: bottom-bar tab selection now compares against the root
  destination of the current route, so switching tabs from a pushed
  saved-mistake tutor session keeps the session instead of resetting to the
  tutor lobby; `CapabilityScreen` restored the direct-connection/secret
  disclosure notices; `StudyDatabasePort.clearAllData()` gives tests a
  connection-safe way to reset business data, so the empty-new-user scenario
  is isolated from other test fixtures.
- Macrobenchmark: the `benchmark` module now self-instruments and runs the
  cold-start benchmark on the API 34 emulator. Five iterations completed with
  `timeToInitialDisplayMs` median 1836 ms (min 1824, max 2105); numbers are
  emulator/debug-only and are not release performance evidence. Baseline
  profile collection needs a profile-capable device/rooted image and remains
  unverified.
- Tutor archive: `TutorConversationRepository.archiveConversation` is
  implemented through the Room DAO and exposed in the history screen as an
  archive action. An instrumented test proves archiving keeps the anchored
  problem, its revision, messages, and the library catalog intact while the
  conversation status becomes ARCHIVED.
- Macrobenchmark, Baseline Profile, Android vitals, TalkBack/large-font audit,
  real Provider protocol, and closed-beta release gates remain.
