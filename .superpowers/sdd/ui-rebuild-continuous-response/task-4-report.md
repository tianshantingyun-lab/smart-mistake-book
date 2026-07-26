# Task 4 Implementation Report — Dynamic Visual Delivery and Presentation

- Status: completed
- Baseline: `c8ef3991428a1e44f3ae554b75b4adaca9b272b8`
- Commit: this report's single Task 4 commit (final SHA is reported in the handoff)
- Branch: `codex/ui-rebuild-continuous-response`
- Date: 2026-07-27

## Delivered

- Added a persisted-message `VisualIntent` fallback for explicit diagram, animation, 3D, and
  visualization requests when the text model omits `visualRequest`.
- Added visible preparing, reviewing, ready, and fail-closed fallback presentation while retaining
  hidden for turns with no visual work. Current visuals open expanded and history opens collapsed.
- Kept tutor text first and moved visual presentation outside the assistant message surface.
- Connected Saved Review to its real bounded source assets and made its original image visibly
  openable in the route.
- Limited retries to persisted retryable failures or explicit not-started work, with one
  student-facing retry/original action and no internal implementation language.
- Invalidated persisted generation/review resolution and render cache identity by semantic request,
  provider/configuration, model, schema, question fingerprint, and source hashes.
- Removed preset visual-target evidence actions. Evidence now requires an exact current GUIDED
  request, a Ready unreported scene, and an actual 2D layout hit on the requested target; panels
  without hit-test geometry fail closed. Browsing, reporting, stale turns, missing scenes, and
  DIRECT mode remain read-only.
- Preserved the bounded v1/v2 render paths. Focused v2 viewing retains panel, step, time, camera,
  and play state; opening the original exits focused mode first. Disabled animation clears restored
  play state.
- Made runtime binding failures fail closed: non-finite/out-of-range expressions and properties
  without a renderer consumer are rejected. Particle drawing now honors the declared aggregate
  document budget instead of a hidden lower cap.

## Files Changed

- `core/visual-runtime/.../TutorVisualDocumentCompiler.kt`
- `core/visual-runtime/.../TutorVisualDocumentRuntimeTest.kt`
- `core/visual-ui/.../TutorVisual2DPanel.kt`
- `core/visual-ui/.../TutorVisualDocumentRenderer.kt`
- `core/ui/.../TutorVisualSceneRenderer.kt`
- `feature/tutor/.../CapturedTutorSessionRoute.kt`
- `feature/tutor/.../SavedMistakeTutorRoute.kt`
- `feature/tutor/.../TutorChatConversation.kt`
- `feature/tutor/.../TutorGeneratedTurn.kt`
- `feature/tutor/.../TutorVisualIntent.kt`
- `feature/tutor/.../TutorVisualPipeline.kt`
- `feature/tutor/.../TutorVisualPresentation.kt`
- Focused Task 4 unit tests under `feature/tutor/src/test`.

## Verification

From `S:\.worktrees\ui-rebuild`:

```powershell
& 'S:\.toolchains\gradle\gradle-9.6.1\bin\gradle.bat' `
  :core:visual-runtime:test `
  :core:visual-ui:testDebugUnitTest `
  :core:ui:testDebugUnitTest `
  :feature:tutor:testDebugUnitTest `
  :core:visual-ui:compileDebugAndroidTestKotlin `
  :core:ui:compileDebugAndroidTestKotlin `
  :feature:tutor:compileDebugAndroidTestKotlin
```

Result: `BUILD SUCCESSFUL`.

| Suite | Tests | Failures | Errors | Skipped |
|---|---:|---:|---:|---:|
| `core:visual-runtime` | 11 | 0 | 0 | 0 |
| `core:visual-ui` | 0 (`NO-SOURCE`) | 0 | 0 | 0 |
| `core:ui` | 18 | 0 | 0 | 0 |
| `feature:tutor` | 142 | 0 | 0 | 0 |
| **Total** | **171** | **0** | **0** | **0** |

Android instrumentation sources compiled. Runtime instrumentation was intentionally not executed
in Task 4; device/runtime design QA remains assigned to Task 6.

## Remaining Risk

- Filament/Compose lifecycle and focused-view behavior have compile-time and deterministic policy
  coverage here, but still require device execution in Task 6.
- No real provider call was made; persistence and semantic invalidation are covered by local
  request/cache policy tests.

## Follow-up: ordered provider authority refreshes

- Added a generation-token authority state machine for overlapping initial and `ON_RESUME`
  capability refreshes. Every refresh immediately revokes the prior provider authority and clears
  visual retry egress; only the latest generation may publish success or failure.
- Preserved cancellation propagation and added state-machine coverage for immediate revocation,
  stale success, and stale failure ordering.

### Verification

From `S:\.worktrees\ui-rebuild` with `GRADLE_USER_HOME=S:\.gradle`,
`JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot`, and Android SDK roots set
to `S:\.toolchains\android-sdk`:

```powershell
& 'S:\.toolchains\gradle\gradle-9.6.1\bin\gradle.bat' `
  :feature:tutor:testDebugUnitTest `
  --tests '*TutorVisualPipelineTest' `
  --tests '*PendingTutorEgressStateTest' `
  --tests '*TutorVisualTargetPolicyTest' `
  :feature:tutor:compileDebugAndroidTestKotlin `
  --no-daemon --max-workers=1 --no-configuration-cache --rerun-tasks --console=plain

& 'S:\.toolchains\gradle\gradle-9.6.1\bin\gradle.bat' `
  :feature:tutor:testDebugUnitTest `
  --no-daemon --max-workers=1 --no-configuration-cache --console=plain
```

Both commands completed with `BUILD SUCCESSFUL`. The full tutor XML results contain 166 tests,
0 failures, 0 errors, and 0 skipped tests across 17 files. `git diff --check` passed.

## Follow-up review: exercised scheduling and refresh boundaries

- Routed both visual generation and review collectors through one scheduling action. Blocked work
  now publishes the exact retry-clear result before cancelling scheduled work; replacement retry
  identities remain untouched.
- Routed the initial and `ON_RESUME` capability refreshes through one suspend coordinator. Tests use
  two controlled deferred results to prove newer success survives stale success and stale failure,
  and that cancellation propagates while authority remains fail-closed.
- Added boundary tests for a generation provider block, review with empty sources, refresh overlap,
  completion reordering, immediate authority/retry revocation, and cancellation recovery.

### Verification

- Fresh focused command with `--rerun-tasks`: `BUILD SUCCESSFUL in 1m 8s`; 49 tasks executed,
  including `TutorVisualPipelineTest`, `PendingTutorEgressStateTest`,
  `TutorVisualTargetPolicyTest`, and `compileDebugAndroidTestKotlin`.
- Full `:feature:tutor:testDebugUnitTest`: `BUILD SUCCESSFUL in 17s`.
- Full XML results: 172 tests, 0 failures, 0 errors, 0 skipped across 17 files.
- `git diff --check`: passed.

## Follow-up review 2: refresh failure and source cancellation

- Replaced the redundant newer-success/older-success scenario with the required
  newer-failure/older-success ordering and asserted that the failed, provider-null authority cannot
  be revived by the stale success.
- Changed cancellation coverage so the active capability source throws a specific
  `CancellationException`; the test asserts that exact instance escapes the coordinator and no
  success/failure authority is published before a later current success.
- Mutation proof: the prior 22 tests stayed green when failed authority accepted stale success and
  source cancellation was swallowed. With the revised tests, those mutations produced exactly the
  two expected failures; restoring production returned the suite to green.

### Verification

- Fresh focused command with `--rerun-tasks`: `BUILD SUCCESSFUL in 1m 7s`; 49 tasks executed.
- Full `:feature:tutor:testDebugUnitTest`: `BUILD SUCCESSFUL in 11s`.
- Full XML results: 172 tests, 0 failures, 0 errors, 0 skipped across 17 files.
- `git diff --check`: passed.
