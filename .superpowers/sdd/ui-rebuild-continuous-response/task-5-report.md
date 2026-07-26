# Task 5 report — Review, Library, Profile, and copy reduction

## Baseline and scope

- BASE: `92f0fa83a82aca6913882044949d29b62bfc6df9`
- Worktree: `D:\智能错题本\.worktrees\ui-rebuild`
- Production changes stayed within the approved Task 5 files.
- No changes were made to `SmartMistakeBookRoot`, Review planner, Room/database, import/export protocols, provider/security, or VisualDocument.

## Files changed

Production:

- `core/ui/src/main/java/com/tingyun/smartmistakebook/core/ui/MasteryLabels.kt`
- `feature/review/src/main/kotlin/com/tingyun/smartmistakebook/feature/review/ReviewRoute.kt`
- `feature/library/src/main/kotlin/com/tingyun/smartmistakebook/feature/library/LibraryModels.kt`
- `feature/library/src/main/kotlin/com/tingyun/smartmistakebook/feature/library/LibraryRoute.kt`
- `feature/profile/src/main/java/com/tingyun/smartmistakebook/feature/profile/ProfileRoute.kt`
- `app/src/main/kotlin/com/tingyun/smartmistakebook/LearningMasteryScreen.kt`

Tests:

- `core/ui/src/test/java/com/tingyun/smartmistakebook/core/ui/MasteryLabelsTest.kt`
- `feature/review/src/test/kotlin/com/tingyun/smartmistakebook/feature/review/ReviewRoutePolicyTest.kt`
- `feature/library/src/test/kotlin/com/tingyun/smartmistakebook/feature/library/LibraryCatalogTest.kt`
- `feature/library/src/androidTest/kotlin/com/tingyun/smartmistakebook/feature/library/LibraryBatchExportEntryInstrumentedTest.kt`
- `feature/profile/src/test/java/com/tingyun/smartmistakebook/feature/profile/ProfileLearningStatusTest.kt`
- `feature/profile/src/test/java/com/tingyun/smartmistakebook/feature/profile/LearningMasterySubtitleTest.kt`
- Removed obsolete `feature/profile/src/test/java/com/tingyun/smartmistakebook/feature/profile/TutorCapabilitySubtitleTest.kt`, which locked the deleted root-page diagnostic copy.
- `app/src/androidTest/kotlin/com/tingyun/smartmistakebook/LearningMasteryScreenInstrumentedTest.kt`
- `app/src/androidTest/kotlin/com/tingyun/smartmistakebook/ReviewContinuityInstrumentedTest.kt`
- `app/src/androidTest/kotlin/com/tingyun/smartmistakebook/RootExperienceInstrumentedTest.kt`
- `app/src/androidTest/kotlin/com/tingyun/smartmistakebook/RootTutorFailClosedInstrumentedTest.kt`

## Behavior implemented

- Added one `core:ui` mapping for all five `MasteryStatus` values and restoration of stable IDs, canonical labels, and the five legacy labels.
- Review now renders only today’s count, estimated minutes, clamped `K / N` progress, and one start/resume/terminal action. Empty `0 / 0` and completed `N / N` retain the same structure.
- Library now leads with capture, search, and the explicit `科目 → 板块/章节 → 知识点 → 掌握程度` path. One `更多` menu owns `批量录入`, `图片转文档`, and `导出`; only one compact `N 道待处理` status remains outside it.
- Library export snapshots the current visible stable-ID order and returns no export request for empty results. Existing ViewModel hierarchy clearing, independent mastery selection, and saved state remain unchanged.
- Profile order is fixed to subject mastery, recent real activity/current status, at most two weakness summaries, and settings. The four retained setting callbacks are `模型与 API`, `数据与隐私`, `提醒`, and `存储与导出`.
- Learning mastery uses the shared status words and retains subject grouping, recent activity, back navigation, and true timestamps without counts, percentages, progress bars, or internal explanations.

## TDD evidence

RED:

- Shared mapping tests failed in `:core:ui:compileDebugUnitTestKotlin` on the intentionally missing `MasteryStatus.studentLabel()` and `masteryStatusFromStoredUiValue()` symbols (`BUILD FAILED in 12s`).
- Library policy tests failed in `:feature:library:compileDebugUnitTestKotlin` on the intentionally missing `nextLibraryFacet` and `libraryExportIds` symbols (`BUILD FAILED in 14s`).
- Profile policy tests failed in `:feature:profile:compileDebugUnitTestKotlin` on the intentionally missing subject, weakness, recent-change, and activity-label helpers (`BUILD FAILED in 15s`).

Segmented GREEN:

- `:core:ui:testDebugUnitTest :feature:review:testDebugUnitTest :feature:library:testDebugUnitTest` — passed.
- `:feature:library:testDebugUnitTest :feature:library:compileDebugAndroidTestKotlin` — passed.
- `:feature:profile:testDebugUnitTest` — passed.
- `:app:compileLocalFirstDebugAndroidTestKotlin` — passed after correcting test API imports.

Final fresh verification from `S:\.worktrees\ui-rebuild` used JDK 21, `GRADLE_USER_HOME=S:\.gradle`, and the requested Android SDK:

```text
gradle.bat \
  :core:ui:testDebugUnitTest \
  :feature:review:testDebugUnitTest \
  :feature:library:testDebugUnitTest \
  :feature:profile:testDebugUnitTest \
  :app:testLocalFirstDebugUnitTest \
  :feature:library:compileDebugAndroidTestKotlin \
  :feature:library:assembleDebugAndroidTest \
  :app:compileLocalFirstDebugAndroidTestKotlin \
  :app:assembleLocalFirstDebug \
  :app:assembleLocalFirstDebugAndroidTest \
  --no-daemon --max-workers=1 --no-configuration-cache --rerun-tasks
```

Result: `BUILD SUCCESSFUL in 3m 14s`; `314 actionable tasks: 314 executed`.

JUnit XML totals:

- `core:ui`: 21 tests
- `feature:review`: 12 tests
- `feature:library`: 37 tests
- `feature:profile`: 4 tests
- `app` local-first: 15 tests
- Total: 89 tests, 0 failures, 0 errors, 0 skipped

Fresh artifacts:

- `app/build/outputs/apk/localFirst/debug/app-localFirst-debug.apk`
- `app/build/outputs/apk/androidTest/localFirst/debug/app-localFirst-debug-androidTest.apk`
- `feature/library/build/outputs/apk/androidTest/debug/library-debug-androidTest.apk`

## Copy, semantics, scope, and self-review

- C4 forbidden visible/semantic strings: 0 hits.
- Private per-screen mastery copy mappings: 0 hits.
- Learning mastery percentage/progress/inventory patterns: 0 hits.
- Review greeting/continuity/weakness/capture/percentage patterns: 0 hits.
- Library cause/source/type patterns: 0 hits.
- Visible Library `更多`: exactly 1.
- Locked-scope changed files: 0.
- `git diff --check`: passed.
- Reuse review kept one shared mastery mapping. Quality review removed dead helper copy and obsolete diagnostic tests. Efficiency/behavior review fixed Library deselection advancing to the wrong hierarchy step and removed Review progress semantics that would announce a percentage.
- Vibecop was attempted but does not support Kotlin; the three review passes were completed locally against the captured full diff.

## Not verified in Task 5

- Connected-device tests, screenshot rendering, large-font rendering, and live TalkBack traversal were intentionally left to Task 6.
- Android instrumentation sources were compiled and both Android-test APKs were assembled, but not executed on a device in this task.

## Fix round 1/5 — study-surface accessibility gaps

Baseline commit: `ad223f2f96eb99776c315783e41ebb2f3b7053ca`.

Inputs reviewed in full:

- `.superpowers/sdd/ui-rebuild-continuous-response/task-5-spec-review.md`
- `.superpowers/sdd/ui-rebuild-continuous-response/task-5-ux-review.md`

All six unique Important findings were addressed:

- Empty Profile data with a zero streak now omits the divider and `最近变化` section while retaining the single learning empty state and all settings actions.
- Library recreation coverage now writes query plus subject/chapter/knowledge/mastery into `SavedStateHandle`, recreates the ViewModel, hydrates the catalog, and checks the exact visible stable-ID order. A separate invalid-hierarchy case proves only downstream values clear and mastery remains independent.
- All five legacy mastery labels are covered through the Library restoration boundary by a parameterized test.
- Review, Library, Profile, and Learning Mastery Compose tests now check the complete C4 forbidden list in both visible text and content descriptions with the unmerged semantics tree, including the percentage pattern.
- Review metrics expose `今日题量，N 道`, `预计时间，M 分钟`, and `今日进度，K / N` as complete semantics without duplicate naked-number semantics. The two metric columns have equal weight, centered wrapping labels, and a 200% font-scale viewport regression.
- Library facet rows and filter chips now expose the Compose selected semantic state.

Files changed in this fix round:

- `feature/review/src/main/kotlin/com/tingyun/smartmistakebook/feature/review/ReviewRoute.kt`
- `feature/library/src/main/kotlin/com/tingyun/smartmistakebook/feature/library/LibraryRoute.kt`
- `feature/profile/src/main/java/com/tingyun/smartmistakebook/feature/profile/ProfileRoute.kt`
- `feature/review/src/test/kotlin/com/tingyun/smartmistakebook/feature/review/ReviewRoutePolicyTest.kt`
- `feature/library/src/test/kotlin/com/tingyun/smartmistakebook/feature/library/LibraryCatalogTest.kt`
- `feature/profile/src/test/java/com/tingyun/smartmistakebook/feature/profile/ProfileLearningStatusTest.kt`
- `feature/library/src/androidTest/kotlin/com/tingyun/smartmistakebook/feature/library/LibraryBatchExportEntryInstrumentedTest.kt`
- `app/src/androidTest/kotlin/com/tingyun/smartmistakebook/ReviewContinuityInstrumentedTest.kt`
- `app/src/androidTest/kotlin/com/tingyun/smartmistakebook/LearningMasteryScreenInstrumentedTest.kt`

Fresh TDD and verification evidence:

- RED: `:feature:profile:compileDebugUnitTestKotlin` failed on the intentionally missing `shouldShowProfileRecentChanges` symbol.
- Targeted GREEN: `:feature:profile:testDebugUnitTest :feature:review:testDebugUnitTest :feature:library:testDebugUnitTest` passed.
- Android-test source GREEN: both app flavors and Library compiled with `:app:compileLocalFirstDebugAndroidTestKotlin :app:compileStrictOfflineDebugAndroidTestKotlin :feature:library:compileDebugAndroidTestKotlin`.
- Final fresh command repeated the original Task 5 five-module JVM suite, Library Android-test compile/assemble, and app local-first compile/assemble with `--rerun-tasks`.
- Final result: `BUILD SUCCESSFUL in 3m 10s`; `314 actionable tasks: 314 executed`.
- JUnit XML: 98 tests, 0 failures, 0 errors, 0 skipped (`core:ui` 21, Review 13, Library 44, Profile 5, app local-first 15).
- C4 production-code scan: 0 hits.
- Locked-scope changed files: 0.
- `git diff --check`: passed.

Connected-device execution, screenshots, physical touch-target measurement, and live TalkBack traversal remain explicitly deferred to Task 6.
