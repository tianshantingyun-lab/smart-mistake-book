# Known Defects Register

Entries are numbered `KD-n` and marked in their heading as open or resolved.
Resolved entries stay for provenance — they record the evidence that closed the
defect and the condition that would reopen it — so the register is also the
place to check whether a past symptom has a known disposition before reopening
it as new. Only an entry without a `(resolved …)` marker blocks a gate or a
release claim.

## 2026-09-09 audit fixes (closed, see commit history)

The full audit (`scratch/AUDIT-*-2026-09-09.md`) found and closed:

- **P0 · batch import blocked in production** — split recognition threw before
  every page could be imported (zero dimensions + fabricated egress manifest +
  no try/catch). Intake now follows spec `batch-intake` I1: the page lands in
  the library first, split recognition is best-effort afterwards. `fae1a82`.
- **P1 · knowledge-quiz write quota was a lifetime quota** — a global
  conversation id made the 50-write per-conversation cap permanent, so mastery
  writes stopped forever after 50 accepted answers. The quota is now per review
  session. `43ba61b`.
- **Three algorithm defects** — `MasterySmoothing` EMA cancelled age out
  (weakness halved), a "very effortful" self-report was lifted to Good, and
  hint/retry-assisted correct answers earned the independent-recall gain.
  `544b575`.
- **Scheduling settings were unwritable** — `setOptions` / `declareExam` /
  `removeExam` had no caller. New scheduling screen wires retention, the FSRS
  switch and the exam calendar. `141a7e1`.
- Export print ignored the requested page range; release builds logged the
  provider response body; two sources carried literal NUL bytes; `feature:tutor`
  declared an unused `core:data` dependency. `141a7e1`.
- **User-created knowledge nodes were unreusable** — they were permanently
  marked `MODEL_CANDIDATE` and excluded from candidate queries. New
  `USER_CONFIRMED` tier. `7d4adc4`.

The entries below are numbered `KD-n`; those still without a `(resolved …)`
marker are open. The batch above closed on 2026-09-09.

## KD-2 (resolved 2026-08-30) · Wall-clock p95 gate on CI runners

**Symptom.** `KnowledgeContextRetrievalInstrumentedTest#largeSubjectRecallRemainsBoundedOnRoom`
failed on CI: "Mastery snapshot read p95 was 278ms; samples=[278, 224, ...]".
Passed locally (API-34 emulator, WHPX). Absolute wall-clock budgets on shared
CI runners are environment-sensitive by construction; the deterministic parts
of the test (index-usage query-plan assertions) all passed, so the divergence
pointed at runner noise rather than a query regression.

**Resolution.** The mastery/recall p95 budgets are environment-aware — strict
locally (150/250ms), 4x on GitHub runners (`CI=true`), which covers the ~278ms
p95 measured there. The multiplier travels as the `ciSlowRunner`
instrumentation argument because runner environment variables do not propagate
into the on-device test process (`System.getenv("CI")` is always null there);
`android-check.yml` passes `-Pandroid.testInstrumentationRunnerArguments.ciSlowRunner=1`.
The deterministic index-usage query-plan assertions are unchanged.

**Reopen condition.** If a runner slowdown grows beyond the multiplied budget,
revisit with a runner-relative bound or move the wall-clock gate to the
macrobenchmark module, keeping the query-plan assertions here.

## KD-3 (resolved 2026-09-09) · Coverage rows for Android modules in status.md

`:core:domain` line/branch coverage is wired (Kover → generate_status.py).
The `:core:database` and `:core:data` rows were NOT_MEASURED because those
Android modules could not be instrumented. Resolved by the AGP + Jacoco path
described in KD-5: status.md now reports `:core:data` 53.9% / 39.1% and
`:core:database` 5.2% / 6.9%.


## KD-1 (resolved 2026-09-12) · Tutor external-authorization flow regression (instrumented)

**Symptom.** 4 of 57 tests in `CapturedTutorSessionInstrumentedTest`
(feature:tutor connected) fail deterministically on a fresh API-34 emulator:

- `expiredLeaseKeepsExactNextPlanUntilOneConfirmationResumesIt` —
  `captured_tutor_disclosure` not composed (assertIsDisplayed fails).
- `authorizedExternalAutoStartExecutesOnlyTheInitialPlanWithoutAnotherConfirmation` —
  waitUntil(5000ms) timeout.
- `localNoEgressFirstStartNeverShowsAnExternalProviderDisclosure` — waitUntil
  timeout; the panel never calls `executePlan` (fake counter stays 0).
- `restartingAfterRecreationCarriesExactStudentWordsWithStableRequestIdentity` —
  waitUntil timeout.

**Scope evidence (collected 2026-08-30).**

- Reproduces identically at commit `25fb15a` (verified in a worktree), i.e.
  the regression predates everything pushed after 2026-08-26.
- Every android-check run since 2026-08-23 (15+ runs) is failure/cancelled:
  the instrumented job aborts at the first failing connected task, so the
  tutor suite has not executed on CI in that window. No green anchor exists
  for a bounded bisect.
- Unchanged since before the break: `TutorSessionPanel.kt`,
  `CapturedTutorSessionRoute.kt`, `TutorPlanCommands.kt`,
  `ModelGateway.kt` (interface), the test file itself. Post-break diffs to
  `TutorModelTaskPolicy.kt` / `TutorTasks.kt` are additive debrief types.
- Panel gating traced: `currentProvider` ← `modelTasks.capabilities()`
  (fake returns a valid snapshot); `planLeaseApprovedAt` null for
  external-without-lease → `else` branch should compose `TutorDisclosureCard`.
  LOCAL auto-exec requires `observedTask == null` (projection returns null
  for empty task lists — verified).

**Suspicion ranking.** (1) something inside the compose effect/lease-time
semantics that the additive diffs interact with, (2) long-standing break from
the 4eee4d8-era rework that was never gated (no CI coverage), (3) emulator
timing — weakened by the expired-lease failure being a direct assertion.

**Next steps.** Dedicated session: bisect past 25fb15a (build per step
~4-6 min, no known-good anchor — try fixture-era commits first), or add
compose-state logging to `TutorModelPanel` and diff intended vs actual
branch selection for the two LOCAL/EXTERNAL scenarios above.

**Resolution evidence (2026-09-06).** The full suite was re-run twice on
emulator-5554 (Pixel 6 AVD `test_device`, API 34) against the current tree
(`2c62700` + in-flight workspace): `:feature:tutor:connectedDebugAndroidTest`
→ **57/57 passed, twice** (fresh install each run; AGP uninstalls after the
run). All four previously-deterministic failures pass. Suspected fix carriers
are the tool-loop hardening commits landed 2026-09-05/06 (`7cb373e` Lobby
disclosure boundary + T6 write-tool anchoring, `9f267f0` T6 mastery_update
chain, `5a940a1` deterministic evidence ids, `c19b330` indexed gate queries).

**Closed 2026-09-12 — the CI anchor arrived.** Run
[34693827527](https://github.com/tianshantingyun-lab/smart-mistake-book/actions/runs/34693827527)
(commit `5e2c3a9`, push to main) posted a **fully green `instrumented` job**:
`BUILD SUCCESSFUL` once, **0 `FAILED`** in the job log, and
`:feature:tutor:connectedDebugAndroidTest` executed **46 tests with 0 failures**.
That is exactly the closing condition this entry was holding for. `check` was
green in the same run.

One correction to the scope notes above: the entry says the suite "has not
executed on CI" for 15+ runs because the job aborted at the first failing
connected task. That was true before 2026-08-30, when the step gained
`--continue` — after that the suites do run even when an earlier one fails, so
the absence of a tutor anchor was about the run's overall redness, not about
the suite being skipped.

**Reopen condition.** Any recurrence of the four named waitUntil/assertion
failures on CI. They no longer reproduce on the current tree (local twice on
2026-09-06, CI green on 2026-09-12), so a recurrence means a new cause.

## KD-4 (resolved 2026-09-12) · Visual-ui device-acceptance test timed out on CI software rendering

**Symptom.** `TutorVisualComplexCircuitInstrumentedTest#complexCircuitSemanticRedrawPassesDeviceAcceptanceAndSavesStepScreenshots`
fails on CI with `ComposeTimeoutException after 2000 ms` (idle-sync wait).
Passes locally 6/6 on WHPX hardware rendering (2026-08-30). The test has no
explicit waitUntil — the timeout is Compose's internal idle synchronization,
which a continuously-redrawing surface on software GL can fail to satisfy.

**Disposition.** Same family as KD-2 (local/CI timing divergence) but NOT
fixable by a budget constant: the failing wait is implicit. Options for the
dedicated session: (a) disable animation/clock auto-advance for this test,
(b) replace implicit idle waits with explicit `waitUntil(Ns)` on the specific
condition, (c) gate the screenshot-acceptance path on real-GPU devices only.
Until fixed, the instrumented job aborts at core:visual-ui, so downstream
connected tasks (export/capture/tutor/library/app) still lack CI validation.
**Mitigation (2026-08-30)**: the CI instrumented step now runs with `--continue`,
so a visual-ui failure no longer aborts the remaining suites — every other
module keeps getting validated while this defect stays open.

**Reproduction attempt (2026-09-12) — not reproduced, and the hypothesis above
is partly wrong.** The "hardware rendering locally vs software GL on CI" framing
does not hold: the project's own `tools/start-emulator.ps1` already pins
`-gpu swiftshader_indirect`, so local runs are software GL too. What differs is
the CPU virtualisation (WHPX locally, KVM on the runner), not the GPU path.
Tried, all green, cold install each time (AGP uninstalls the test APKs, so the
Filament material cache was never warm):

| Configuration | Result |
|---|---|
| swiftshader + 2 cores, this class only, 4 runs | 4/4 pass |
| swiftshader + 2 cores, whole suite (6 tests, incl. the Filament toggle test) | pass |
| swiftshader + **1 core** + 8 host CPU busy loops, whole suite | pass |

Animations are not the difference either: `TutorAnimationPolicy` reads
`Settings.Global.getFloat(ANIMATOR_DURATION_SCALE, 1f)`, and the setting is unset
on this emulator, so it resolves to "enabled" — the same as a default runner.

**Consequence.** Option (b) remains the right fix on its merits (a surface with
its own render loop should not depend on implicit idle sync), but it cannot be
validated from here: the failure is only observable on the CI runner, so a local
pass would prove nothing and a local green would not close this entry. Apply (b)
in a session that can watch a CI run, and record the CI outcome — do not close it
on local evidence.

**Outcome 2026-09-12 — the suite passed on CI, so the failure has no reproducing
case.** Run
[34693827527](https://github.com/tianshantingyun-lab/smart-mistake-book/actions/runs/34693827527)
(commit `5e2c3a9`) ran `:core:visual-ui:connectedDebugAndroidTest` with
**6 tests, 0 failures**, inside a fully green `instrumented` job (0 `FAILED`
lines, one `BUILD SUCCESSFUL` for all nine suites). The test named in this entry
was among them. This is the same bar that closed KD-1 — one green connected run
on the runner where the failure lived.

**No code change was made for this entry, and that is stated deliberately.** The
green run does not prove an intermittent timing failure is permanently gone; it
proves there is no longer a reproducing case, and the recorded disposition
(options a/b/c) is not worth a blind change to a test that currently passes.
Treat any recurrence as new evidence.

**Reopen condition.** `ComposeTimeoutException after 2000 ms` on the
complex-circuit acceptance test on CI. If it recurs, apply option (b) — explicit
`waitUntil` on the specific condition instead of relying on implicit idle — and
validate it against the run that reproduces it, since local runs (software GL,
2 cores, and even 1 core under host load) do not.

## KD-5 (resolved 2026-09-09) · Android-library coverage was not collectible with Kover 0.9.1 + AGP 9

**Symptom.** `:core:data:koverXmlReport` / `:core:database:koverXmlReport`
produced a report with zero classes (`LINE covered=0 missed=0`), so
`docs/status.md` reported both modules as `NOT_MEASURED` while `:core:domain`
(a `kotlin.jvm` module) reported 72.5% / 51.8%. No variant-specific Kover task
existed, and an explicit `createVariant("unitTest") { add("debug") }` failed
with *"Could not find the provided variant 'debug'"*.

**Cause.** These modules apply `com.android.library` with AGP 9's built-in
Kotlin support (no `org.jetbrains.kotlin.android`), and Kover 0.9.1's variant
detection is built on the Kotlin Android plugin's variant model.

**Resolution (2026-09-09).** Switched the two Android-library modules off Kover
onto AGP's own instrumentation: `buildTypes { debug { enableUnitTestCoverage = true } }`
plus a `JacocoReport` task `unitTestCoverageXmlReport` that writes
`build/reports/coverage/unit-test.xml` (generated `*_Impl`/`*_Factory` classes
excluded, since Room output is not hand-written code).
`tools/ci/generate_status.py` now reads Kover first and falls back to that XML,
so `docs/status.md` reports real numbers: `:core:data` 53.9% line / 39.1%
branch, `:core:database` 5.2% / 6.9%.

**Known limitation.** `:core:database`'s logic lives mostly in DAO default
methods that need a real database; those are covered by instrumented tests,
which neither Kover nor this Jacoco path can measure — the low number is real
for *unit*-test coverage, not a measurement failure.

## KD-6 (resolved 2026-09-10) · `CaptureScreen.kt` exceeded the 1000-line main-file limit

**Symptom.** `feature/capture/.../CaptureScreen.kt` was 1281 lines — one composable
whose command builders (8 `Capture*Commands`, each taking a `*Sink` of
getter/setter lambdas over ~50 `remember`/`rememberSaveable` locals) were
constructed inline.

**Cause.** The builders and their sink lambdas closed over per-field
`remember`/`rememberSaveable` state, so any move risked process-death
restoration semantics.

**Resolution (2026-09-09/10).** Two-step:
1. Commit `c956c6c`: state hoisted into `CaptureScreenState` (own file; every
   field a `mutableStateOf` delegate; a `mapSaver` round-trips exactly the
   fields that were `rememberSaveable`). Screen 1281→1207.
2. Commits `09017e2` + `01cc4ff`: all 8 `Capture*Commands` now take
   `CaptureScreenState` directly. Pure state getters/setters inlined as field
   reads/writes; composite logic (`applyResumeDraft`, `applyAppendedPages`,
   `resetDraftFields`, …) moved into private command methods; cross-command
   steps (`resetDraft`, `persistAdditionalPage`, `afterWorkspaceFlush`,
   `applyReturnedImagePlan`) delegate to the owning command objects; Android/
   ViewModel effects and screen-derived values (`entryGateOpen`,
   `structuredProjection`, request builders) are injected constructor
   functions, so the commands stay free of Android/ViewModel types. The 8
   `*Sink` classes were deleted. Screen 1207→804 (< 1000); 45 stale imports
   removed.

**Verification that closed it.** `python3 tools/ci/check_file_size_gate.py`
no longer lists the path; `:feature:capture:testDebugUnitTest` 99/0;
`:feature:capture:connectedDebugAndroidTest` 23/23 (incl.
`CaptureScreenStateRestorationTest`); `:feature:capture:lintDebug` clean.

## KD-7 (resolved 2026-09-11) · Mistake catalog read per-question memory from the pre-projection table

**Symptom.** `ProblemDao.observeActiveMistakes()` and
`findMistakeBySourceKey()` joined `problem_memory_state`, so every
`next_review_at_epoch_millis` they returned was NULL in production. The
`library_catalog` view had been moved onto `learner_problem_memory_state` back
in migration 35 → 36; these two queries were missed, leaving the same fact
read through two disagreeing paths (the view: real rows but `retrievability`
NULL by design; these queries: an empty table).

**Why it stayed invisible.** The only writer of `problem_memory_state` is
`FixtureSeedDao`. Instrumented tests seed through `seedFixture`, so on device
the join produced real values and every test passed; production has no fixture
seed and returned NULL. `retrievability` is derived from stability plus "now"
and is not stored on the projection, so it is now selected as `NULL` exactly
like the view does, and readers keep computing it from the snapshot.

**Resolution.** Both queries join `learner_problem_memory_state` with
`projection_name = 'study-experience-v1'` (the same predicate and the same
learner-from-the-row resolution the view uses). `MistakeRecord` and its
consumers are unchanged — this was a read-path fix, not a field removal.

**Verification that closed it.** New
`MistakeMemoryProjectionInstrumentedTest` (3 cases) fails on the old join
(2 of 3 red: both projection-backed assertions) and passes after the fix;
`:core:database:connectedDebugAndroidTest` 151/151 and
`:core:database:testDebugUnitTest` green.

**Reopen condition.** Any catalog query that reads a per-question memory field
from a denormalized table instead of the learner projection. The general hazard
is that instrumented tests seed fixtures while production does not, so a
fixture-only writer makes a broken join look healthy on device — prefer a real
`commitProjection` in the test over fixture seeding when the assertion is about
projected state. `retrievability` is intentionally NULL at the SQL layer; a UI
consumer that needs it must go through `StudyExperienceMappers.toCatalogEntry`.

## KD-8 (resolved 2026-09-12) · Delete-all-data left the model API key in the Keystore

**Symptom.** `AndroidBackupRepository.deleteAllData()` swept the Keystore for
aliases starting with `smartmistakebook_`, but the alias the vault actually
writes is `smart_mistake_book_model_api_key_v2`. Nothing ever matched, so the
entry survived "delete all data" — the ciphertext file and DataStore metadata
were gone, so the key was unusable, but the Keystore entry was still there and
the M4 gate ("delete-all leaves an empty app") was not met in the strict sense.

**Cause.** The alias string had two independently written authorities: the vault
(which creates and deletes it correctly via `clear()`) and this sweep, which
re-derived the name and got it wrong.

**Resolution.** `MODEL_SECRET_KEY_ALIAS` is now the one authored value in the
vault file; the vault uses it and `deleteAllData` deletes exactly that alias
instead of matching a prefix.

**Verification that closed it.** New
`BackupRestoreInstrumentedTest#deleteAllDataRemovesTheModelApiKeyKeystoreAlias`
creates the alias through the production vault, asserts it exists, runs
`deleteAllData()`, and asserts it is gone — red before the fix (the fixture
assertion passed, the deletion assertion failed) and green after.
`:core:data:connectedDebugAndroidTest` 98/98; `:core:data:testDebugUnitTest` 395/395.

**Reopen condition.** Any second copy of a Keystore alias string. If the vault
ever gains another alias or a migration path, add it to the sweep by referencing
the constant, and extend the instrumented test to cover it.

## KD-9 (resolved 2026-09-12) · Startup restore-recovery outcome was discarded

**Symptom.** `SmartMistakeBookApplication` called
`BackupRestoreStartupRecovery.recoverOnStartup(this)` and dropped the result. An
interrupted restore that had to be **rolled back** (the restore silently did not
take effect) or **quarantined** (rollback failed, artifacts moved aside) looked
identical to a clean start: the student saw a normal book that was not the one
they left, with no signal that anything happened.

**Resolution.** `RestoreStartupOutcome.attentionRequired()` classifies outcomes
by whether local data moved; the app maps the two that did — `RolledBack` and
`Quarantined`/`Unreadable` — onto a `RecoverableFailure` applied after `Ready`,
so the book stays usable and the student is told the restore did not land.
`NothingToRecover` and `Cleaned` stay silent because the live generation was
never touched. Copy lives in the app layer; only the classification is in
`core:data`.

**Verification that closed it.** `RestoreRecoveryAttentionTest` (5 cases,
`:core:data:testDebugUnitTest`) pins the classification, including that exactly
the two outcomes that move data are reported; both flavors assemble. The
instrumented rollback path
(`BackupRestoreInstrumentedTest#startupRecoveryRollsBackGenerationAfterInterruptedSwap`)
still passes.

**Reopen condition.** A new `RestoreStartupOutcome` variant will fail to compile
in `attentionRequired()`, which is the intended forcing function — classify it
as reporting or silent deliberately. Note the side effect on
`OrphanAssetGcWorker`: it retries unless the state is `Ready`, so the sweep runs
on a later launch rather than during this one.

## KD-10 (resolved 2026-09-12) · "Least mastered" library sort was a no-op

**Symptom.** `LibraryQueryDao` and `RoomLibrarySearchStore` ordered by
`catalog.retrievability` for the `LEAST_MASTERED` sort, but the `library_catalog`
view defines that column as `NULL AS retrievability`. Every row compared equal,
so the sort silently fell through to `updated_at DESC`. The domain enum
`LibrarySort.LEAST_MASTERED` therefore did nothing.

**First assessment was wrong, and worth recording.** This was filed as blocked
because the only fix I could see was exposing a numeric mastery column on the
`@DatabaseView`, which needs a schema bump plus a re-exported `schemas/NN.json` —
impossible locally (`copyRoomSchemas` is `NO-SOURCE`; hand-writing the JSON is
forbidden). But the view *already* exposes the join, and the number is reachable
from the catalog alias by correlation, so no schema change is involved at all.

**Resolution.** All three sort sites order by the weakest
`lower_bound_independent_correct` among the row's bound knowledge points — the
same projection and learner the view's `mastery_id` facet already reads.
`NULL` (no evidence) sorts first, matching the view's status ordering, which
puts `unknown` first. The expression lives in two authored copies:

- `LibraryCatalogSorts.kt` → `LEAST_MASTERED_MASTERY_SQL`, used by the FTS
  store's runtime-built query;
- a `private const val` of the same name in `LibraryQueryDao.kt`, used by both
  `@Query` annotations.

The second copy is forced by Room: a query assembled from a **cross-file**
constant is rejected by KSP ("No property named value was found in annotation
Query" — reproduced for both positional and `value =` concatenation), while a
**same-file** constant compiles. Since textual sharing is unavailable, the sites
are held together by behavior instead.

**Verification that closed it.** New
`LibraryLeastMasteredSortInstrumentedTest` (3 cases) seeds three entries whose
mastery (`0.20 / 0.50 / 0.80`) is deliberately the **reverse** of their
`updated_at` order, so a no-op sort still produces a total order — just the
wrong one. Before the fix the DAO case read
`expected [entry-weak, entry-middle, entry-strong] but was [entry-strong,
entry-middle, entry-weak]`. Each path carries its own assertion, and a mutation
check confirms they bind independently: restoring `catalog.retrievability` in
the FTS store turns **only** the FTS case red, with the same reversed order.
`:core:database:connectedDebugAndroidTest` 154/154 (was 151),
`:core:database:testDebugUnitTest` 67/67, `:core:database:lintDebug` 0 errors,
`:core:data` and `:feature:library` unit tests and lint green, both flavors
assemble.

**Reopen condition.** Any new `LEAST_MASTERED` sort site must carry both a copy
of the expression and its own behavioral assertion — sharing the text is not
available. Note the copy count is the symptom, not the guarantee: if the
expression ever needs to change, change both copies and let the tests confirm
both paths. The `pagingSource` `@Query` shares the DAO's same-file constant with
`page`, so the DAO test covers both.

## KD-11 (resolved 2026-09-12) · API-26 calls in code that must run on API 23

**Symptom.** `minSdk` is 23, but three call sites used APIs that only exist from
26, and core library desugaring does not cover them — `core:data` enables
desugaring, and lint still reports these (lint *is* desugar-aware: the same
report stays silent about the module's many `java.time` usages, which the
desugared library does provide):

- `OpenAiImageGenerationChannel.parseEditResponse` decoded provider images with
  `java.util.Base64.getDecoder()`. **Reachable in production** — attached images
  and the capture clean-redraw path both read this — so on an API 23–25 device
  the decode threw `NoSuchMethodError`.
- `SmbkArchiveCodec.validate` defaulted its scratch directory to
  `Files.createTempDirectory(...)`. Production callers always pass a scratch
  dir, so this was a latent trap rather than a live crash, but the default is
  what the JVM unit tests exercise.
- `TutorVisualMaterialRepository` moved the Filament material cache with
  `Files.move(..., ATOMIC_MOVE)`. Dormant while
  `TutorVisualIsolation.STRUCTURED_SCENE_ISOLATED` is true, live the moment the
  visual path is restored.

**Resolution.** Each call was replaced with an API-23-safe equivalent that keeps
the same semantics: okio's `decodeBase64()` (already how this module encodes, and
it works in JVM tests); `File(System.getProperty("java.io.tmpdir"), "smbk-validate-<uuid>")`
with the same per-call uniqueness and `deleteOnExit`; and a sibling-directory
`renameTo` plus copy-and-delete fallback, which is what `ATOMIC_MOVE` was asking
for since the staging file always sits next to the target. A fourth call site,
`AndroidBackupRepository` reading `context.dataDir` (API 24), went through
`ContextCompat.getDataDir` — also not desugarable, and on the delete-all path.

**Verification that closed it.** `:core:data:lintDebug` and
`:core:visual-ui:lintDebug` report **0 severity=Error** (they reported 4 and 1
NewApi errors plus this severity before); `:core:data:testDebugUnitTest` 395/395
(includes the archive codec, which uses the replaced default);
`:core:visual-ui:testDebugUnitTest` 12/12; `:core:data:connectedDebugAndroidTest`
98/98; both flavors assemble.

**Reopen condition.** Any new `java.*`/`java.nio.file`/`java.time` call on a
minSdk-23 module. To settle coverage, read the spec lint itself reads rather
than guessing from the message: AGP unpacks
`desugar_jdk_libs_configuration-<version>-desugar-lint.txt` under
`.gradle/caches/<gradle-version>/transforms/<hash>/transformed/`. For
`desugar_jdk_libs 2.0.3` it contains 237 `java/time` entries and **no**
`java/nio/file/*` and no `Base64` — which is why `java.time` is safe here while
these four calls were not. Lint's "or core library desugaring" wording is a
heuristic (it appears for modules where desugaring is *off*); the spec file is
the authority. Note the project uses plain `desugar_jdk_libs`, not the `_nio`
variant that would add `java.nio.file`.
**UNVERIFIED**: none of these paths were exercised on an API 23–25 device (only
API 34 is available here); the change is justified by the desugar spec plus
lint's API-level model, not by a device run.

## KD-12 (resolved 2026-09-12) · Room's restricted `useConnection` is used cross-library-group

**Symptom.** `:core:database:lintDebug` reported `RestrictedApi` (severity Error):
`RoomDatabase.useConnection` is restricted to `androidx.room3`'s own library
group, and this app calls it from `SmartMistakeBook.core`. Not one site but
**seven, across three files** — `RoomBackupSupportStore` (4),
`RoomStudyDatabase` (2), `RoomLibrarySearchStore` (1), plus one in
`PerformanceGateTest`'s androidTest.

**Impact.** Not a runtime defect — restricted APIs are a lint-level contract and
those paths work (98/98 instrumented tests). It is a forward-compatibility risk:
the API is outside Room's public surface, so an upgrade may change or remove it
without notice.

**Resolution.** The calls are legitimate — `PRAGMA wal_checkpoint(TRUNCATE)`,
`PRAGMA user_version`, `PRAGMA foreign_keys` and `VACUUM INTO` have no DAO or
query equivalent, and the backup, delete and FTS-trigger paths cannot be built
without them. All eight sites now go through
`RoomDatabase.withRawConnection(isReadOnly) { … }` in `RawConnectionAccess.kt`,
which carries the single `@SuppressLint("RestrictedApi")` and the reason.

A wrapper rather than a suppression per site or per file: the risk deserves
exactly one reviewed decision, and a file- or class-level suppression would also
have silently legalized a *different* restricted API added to those files later.
With the exposure confined to one function, lint keeps working everywhere else;
if Room ever exposes a supported raw-connection API, that one function changes.

**Verification that closed it.** `:core:database:lintDebug` reports **0
severity=Error** (it reported this class before); `:core:database:compileDebugAndroidTestKotlin`
compiles; the module's instrumented suite still passes (:core:database 151/151).

**Reopen condition.** A new direct `useConnection` call outside
`RawConnectionAccess.kt` — lint will flag it, which is the intended forcing
function. If Room's raw-connection API moves, this one function is the change.

## KD-13 (resolved 2026-09-12) · Library-module lint is not gated, so its findings stay invisible

**Symptom.** `.github/workflows/android-check.yml` ran
`lintLocalFirstDebug lintStrictOfflineDebug`, and only `:app` declares the
`localFirst`/`strictOffline` flavors — so ten Android library modules
(`core:data`, `core:database`, `core:ui`, `core:visual-ui`, `core:export`, all
five `feature:*`) were never linted in CI at all. Their `lintDebug` findings,
including every error KD-11 had to fix, accumulated unseen.

**How this was found.** KD-11 was discovered only because a manual
`:core:data:lintDebug` run surfaced four `NewApi` errors that no gate had ever
reported.

**Resolution.** The workflow's Lint step now lists the ten library `lintDebug`
tasks explicitly alongside the two app flavors, with the same reasoning the
unit-test step already documents: a module that is not named is never checked.

**Verification that closed it.** Every module was confirmed clean *before*
wiring — `severity=Error` counts across all ten reports are 0 (warnings remain
advisory: 7/0/3/1/0/5/6/0/3/16, and lint fails the build on errors, so the green
run is itself evidence). The exact command the step will run was then executed
locally as one invocation: `BUILD SUCCESSFUL`. The workflow still parses
(19 steps in the `check` job, Lint step present).

**CI confirmation (2026-09-12).** The step has now actually run on a runner: in
run
[34693827527](https://github.com/tianshantingyun-lab/smart-mistake-book/actions/runs/34693827527)
the `check` job concluded **success** with the Lint step itself reported
`success`, so the ten library modules are linted in CI from this commit on.
Noted gap: `docs/status.md` (generated) still reports only the two app lint
variants — `tools/ci/generate_status.py` reads the app lint XML paths, so the
library results gate the build without appearing in the report. Extending the
generator to include them is optional follow-up, not required for the gate.

**Reopen condition.** A new `core`/`feature` module must be added to that task
list. The step lists modules explicitly rather than using an umbrella task,
which is the convention the Unit-tests step already documents — "the
flavor-named tasks only exist in `:app`; every other module's … are listed
explicitly or they never run in CI" — so the checked set stays reviewable. If a
future finding class is decided to be acceptable rather than fixed, suppress it
at the call site with the reason, the rule KD-12 followed.

## KD-14 (closed 2026-09-19 · host environment boundary) · Deep security scans are systematically inconclusive on this host

**Symptom.** The Mimosa pre-commit/pre-push hook has been reporting
`scanner_enobufs` for many days (scan not completing inside the host tool
window). Full on-demand deep scans do complete — they are sealed — but four
independent runs across 2026-09-14 and 2026-09-17 all report
`runStatus=inconclusive` with the verbatim-same coverage gap: the semantic
phases (`threatModel`, `findingDiscovery`) do not cover entry points /
principals / authorization surfaces, and `pathAnalysis` is N/A. The static
phases cover every file. This is a host/environment gap in the scanner, not a
repository-content problem: the same gap reproduces byte-for-byte across
days, repos states, and run attempts.

**Evidence (sealed artifacts, under `~/.mimosa/security-scans/project-c079ff08106a86cc56edfee2/`):**
2026-09-17 `scan-2026-09-17T16-22-56.206Z-b0fc1c53ff5f`
(seal `sha256:58ff6b04…`) and `scan-2026-09-17T16-28-12.386Z-e9d35c5c3609`
(seal `sha256:88601862…`); 2026-09-14
`scan-2026-09-13T16-35-18.764Z-0b14062db08c` / `scan-2026-09-13T16-38-37.763Z-7ce64768d5b5`.
Findings in all runs: 0 high / 0 medium / 3 low, the lows being CWE-330 in the
untracked parallel-session tool `tools/kb_build/audit_quality.py`
(`random.Random(fixed seed).sample` — deliberately seeded reproducible
sampling for humans, a substantive false positive). Dependency surface: 38
packages, 0 advisories.

**Known discrepancy.** The `security_scan_status` API summary carries
P1/P2/P3/P4 counts (e.g. 1/4/5/8 + 22 excluded) that do not match the sealed
report body (`0 business-logic candidate`, 3 low occurrences). Per the scan
contract, the sealed artifacts (`findings.json`, `report.md`, `seal.json`) are
authoritative; the API summary counts must not be quoted as specific findings.

**Consequence.** No completion claim may say "security audit passed". What is
defensible today: full static-mode scan with 0 high/medium and clean
dependency surface; the semantic threat-model phase is uncovered on this host.

**Resolution / close condition (updated 2026-09-18 with root cause).**
`mimosa doctor` pinpoints the missing runtimes on this host: the bundled
**Semgrep CE is not installed** (`install_metadata_missing`) and **PyCG is
unconfigured**, which is exactly why `--deep` degrades ("PyCG 未配置，--deep
将优雅降级到 Mimosa 静态可达性") and `pathAnalysis` comes back N/A. The
self-repair path is `mimosa semgrep install --accept-license` (and a PyCG
configuration), but that command is currently **blocked by the plugin's own
PreToolUse hook**, which misclassifies running the plugin CLI as a write to
the plugin cache file (two identical rejections observed 2026-09-17). So:
(a) run the install command manually from a terminal outside the agent
hooks, rerun a deep scan, and if it seals with full coverage, close this
entry with its finding summary; or (b) the Mimosa plugin side fixes the
hook false-positive / the local runtime gap. **CI cannot close this entry**:
the semantic review phase is performed by the ZCode host model (per the
plugin manifest), which does not exist on GitHub runners — a CI-hosted scan
would reproduce the same gap, not fill it. Until closed, treat this entry as
an open boundary: git-hook `scanner_enobufs` pass-throughs and any "no
scanner conclusion" note are expected, and every release claim must cite
this entry rather than assert security.

**Closed 2026-09-19 — disposition: known host environment boundary.**
Path (a) was attempted in full and cannot close this entry on this machine:
the install was run manually from a user terminal with
`MIMOSA_SEMGREP_PYTHON` pointed at the host's Python 3.13.14 (`semgrep
install --accept-license`), and `mimosa doctor` still reports
「Semgrep：内置规则检出样例，但 **Semgrep 进程不可用**」 plus 「PyCG：未配置」.
The follow-up deep scan (`scan-2026-09-19T06-17-24.939Z-fd3a2dd1012d`,
sealed `sha256:f1bffd7f…`) finished in 8 seconds and sealed **exactly the
same `runStatus=inconclusive`** as the four rounds recorded above
(`threatModel` partial with 0 entry points/principals/authorization
surfaces, `pathAnalysis` N/A, `completeness=partial`) — i.e. the scanner
process still does not see a usable Semgrep runtime, so the gap is a host
runtime/detection boundary, not repository content. Per the user's decision
(2026-09-19) the entry is **closed as a known environment boundary** rather
than left open indefinitely. Standing rules that outlive the entry:

- **No claim of "security audit passed" is permitted.** The defensible
  statement remains: static phases cover every file (1022 selected = 1022
  parsed, 0 read/parse failures), findings are 3 low with 0 high/medium,
  the dependency surface is clean (38 packages, 0 advisories), and the
  semantic phases (entry points / authorization surfaces / dataflow) are
  **not covered on this host**.
- Git-hook `scanner_enobufs` pass-throughs and any "no scanner conclusion"
  note remain expected and must be cited, not treated as a clean pass.
- The API summary counts and the sealed artifacts keep the KD-14
  discrepancy rule: sealed `findings.json`/`report.md`/`seal.json` are
  authoritative.

**Reopen condition.** Any of: (i) Mimosa ships a scanner that reaches the
full-coverage status on this host (e.g. its runtime detection is fixed, or
Semgrep/PyCG become reachable to the scan process) — a sealed
`runStatus=complete` supersedes this entry with its finding summary; or
(ii) a scan on any host surfaces a high/medium finding — that becomes its
own open KD.

**Reopen condition.** A sealed run that reports `runStatus=complete` (or an
equivalent full-coverage status) supersedes this entry with its finding
summary; any new high/medium finding from such a run becomes its own open KD.

## KD-15 (fixed 2026-09-19) · Bundled teaching-material pack is rejected on import (startup banner)

**Symptom.** A freshly installed `localFirstDebug` app shows the recoverable
startup banner 「本地知识包尚未准备好 / 错题和复习可以继续使用，自动分类会暂缓。」
(诊断编号 `startup:knowledge:<n>`) on every launch. Reproduced 2026-09-18 on a
clean rebuild (`gradlew clean :app:assembleLocalFirstDebug`) with `pm clear`ed
app data — logcat:
`com.tingyun.smartmistakebook.core.database.DatabaseContractViolationException:
Teaching-material review cannot predate its source import` from
`RoomKnowledgeBaseStore.importKnowledgeTeachingMaterials`. The main tree pack
imports fine (device DB: `knowledge_node`=2624, `knowledge_source`=13); only
the teaching-material import fails, and it is one transaction, so one violation
blocks the whole material set.

**Root cause (measured).** The six bundled sidecars
`moe-2025-teaching-support-v2-0{1..6}.json` carry **80 materials whose
`reviewedAtEpochMillis` is a few milliseconds before their source's
`importedAtEpochMillis`** (per file: 20 / 24 / 1 / 19 / 11 / 5), e.g.
`ext-che-96397bfcc6-023` reviewed 1789732985366 < imported 1789732985467. The
contract check (`KnowledgeTeachingMaterialContract.validate`, current source
line 111) requires `reviewedAt >= importedAt`. The inversion is a
stamping-order artifact of the pack builder (review stamped ~0.1 s before the
source import), not a content judgment. Verified by direct scan of the
sidecars; the runtime failure reproduces deterministically.

**Note on the stack line.** The runtime frame names
`KnowledgeTeachingMaterialContract.kt:301` while the check sits at line 112 in
the 228-line file; the number is Kotlin's inline-frame attribution for the
inlined `requireValid` body. A full clean rebuild reproduces it — do not read
it as a stale-build signal.

**Fix direction.** In the pack pipeline, stamp the source import at-or-before
the material review, or clamp `reviewedAt = max(reviewedAt, importedAt)` when
emitting the sidecars; rebuild and let a clean install confirm the banner is
gone. Owner: the knowledge-pack toolchain (parallel session's in-flight pack).

**Reopen condition.** n/a — close when a clean install launches with no banner
and the material import completes without the contract exception.

**Verified fixed (2026-09-19, pack toolchain).** 三处根因一并修复并过真机：①材料 `reviewedAt < importedAt` 494 条全量对齐（并统一同 sourceId 跨卷副本的 `importedAt` 取最早值——聚合 `distinctBy` 首现口径）；②`KnowledgeTeachingMaterialContract` 的**总量上限**才是更早的阻断（材料 2048 / 绑定 16384 / 总字符 4M 实为 11302 条材料），按用户 2026-09-19 决定全部取消，只留结构不变量；③2020 旧包 2 条材料引用主包源、与主包 id/指纹撞唯一性 → 侧车补独立源。新增 `BundledTeachingMaterialsContractTest`（内置包直接过 DB 导入契约）。真机复核：clean install 无横幅，`knowledge_teaching_material` = **10412 行**（此前 0），`knowledge_node` 2605、`knowledge_source` 54。

## KD-16 (fixed 2026-09-19) · Batch organize shows the fallback notice instead of the configure-model one

**Symptom.** `localFirstDebug`, no model configured: 错题本 → 批量导入试卷照片 →
「开始分题」 shows 「这次还没有全部分好，页面都已保留，可以稍后继续。」
(`BatchImportRoute.kt:159`). The precise 「请先在“我的”里配置模型服务，再整理相邻页面。」
(line 157) never appears. Reproduced on the 2026-09-18 clean rebuild.

**Root cause.** The precise message is reachable only through
`BatchOrganizationUnavailableException`, thrown only when
`!modelEgressAllowed()` (`RoomBatchImportRepository.kt:215-216`). The app
injects `modelEgressAllowed = { modelConfigurationStore != null }`
(`SmartMistakeBookApplication.kt:269`), which is true in `localFirst`
regardless of whether a model is configured — so the call passes the egress
gate and dies at `require(provider.canOrganizeBatchPages())`
(`RoomBatchImportRepository.kt:219`); that `IllegalArgumentException` lands in
the generic `catch (_: Exception)`.

**Fix (2026-09-19).** `organizeBatch` 里 `require(provider.canOrganizeBatchPages())`
换成 `if (!...) throw BatchOrganizationUnavailableException()`——能力不足与"未授权出网"
归为同一领域结局，UI 现有的 `catch (BatchOrganizationUnavailableException)` 分支因此显示
精确文案；egress 语义不变。新增 instrumented 用例
`organizeBatchWithAConfiguredButImageIncapableModelReportsCapabilityUnavailable`
钉住 egress-允许 + 能力-不足这一组合，并断言不派发任何模型轮次。

**Reopen condition.** 若 `organizeBatch` 的能力判定再抛 `IllegalArgumentException`
而非 `BatchOrganizationUnavailableException`，则重新落入通用 catch。

## KD-17 (open) · A single-capture draft has no resume entry after leaving the flow

**Symptom.** 错题本 → 拍照或上传 → 拍照并整理 → (no model: the flow parks at
「整理题目」 with the capability gate) → back. The draft persists
(`problem_draft` row, status `EDITING`, source asset kept) but nothing lists or
reopens it: re-entering 拍照或上传 starts fresh; 错题本 shows its plain empty
state (it counts only committed `problem` rows via `intakeBacklogCount`) and
复习 shows 暂无需复习题.

**Evidence.** `Routes.CaptureResume` (`capture/resume/{draftId}`) has exactly
two callers — 批量导入 (`SmartMistakeBookRoot.kt:654`) and 分题复核 (:678). The
batch side works on device (「点此继续」 reopens the page's draft with its
original image, verified 2026-09-18); the single-capture side has no entry.
Device DB after the smoke: 1 batch job `COMPLETED`, 3 pages `READY`, 4 drafts
`EDITING` (3 batch + 1 single-capture).

**Fix direction (needs a product decision).** Either surface single-capture
drafts (reuse the resume route from a pending list or the 错题本 empty state),
or keep the user in-flow / explicitly discard when the flow cannot be reopened.
With a model configured the window is narrow (the flow auto-continues), so
priority is low.

**Reopen condition.** n/a — close by the chosen decision plus a device check
that the draft is reachable or resolved.

## KD-18 (fixed 2026-09-19) · `tools/teaching_sources/epub_audit.py` 在本机 Python 3.13 下恒崩，4 条测试恒红

**Symptom.** `PYTHONPATH=tools python -m pytest tools/tests -q` 里
`test_teaching_sources.py` 的 4 条用例全部 ERROR，异常都是
`TypeError: XMLParser() got an unexpected keyword argument 'resolve_entities'`
（`tools/teaching_sources/epub_audit.py:101`）。

**Evidence.** 2026-09-19 本机 Python 版本 `Python 3.13.14`；单独复现：
`python -c "import xml.etree.ElementTree as ET; ET.XMLParser(resolve_entities=False)"`
同样报错。`epub_audit.py` 最后一次改动在 `2f0b98a9`（与本轮改动无关），
`git diff HEAD -- tools/teaching_sources/epub_audit.py` 为空 —— 缺陷是既有环境兼容问题，
不是新引入的回归。该文件原本是想关掉实体解析做 XXE 加固。

**全量结果（2026-09-19 本轮实测）**：`4 failed, 212 passed`，红的 4 条全部来自本缺陷。

**Fix direction.** ~~三行 try/except 改法~~ **实际改法（更简）**：直接去掉 `resolve_entities=False`，
即 `ElementTree.XMLParser()`。stdlib `xml.etree.ElementTree` 本就从不解析外部 DTD 实体（XXE
安全不依赖这个参数），`resolve_entities` 只是内部实体替换的开关，且已在 3.13 被移除——
所以裸解析器在 3.8–3.13 全都正确。**注意有两处调用点**（`_rootfile_path` 与 `_metadata`，
登记最初只记了 101 行），只改一处仍会在 124 行崩。

**Resolution (2026-09-19).** 两处调用点均改为 `ElementTree.XMLParser()` 并加注释。
`PYTHONPATH=tools python -m pytest tools/tests/test_teaching_sources.py -q` → 4 条转绿；
全量 `python -m pytest tools/tests -q` → 全绿（当时 277 passed，0 failed）。

**Reopen condition.** 若未来引入 `defusedxml` 或重新依赖外部实体解析，需复核这两处。

## KD-19 (fixed 2026-09-19) · 同一来源在多卷的时间戳不一致，导致内置包导入被拒（KD-15 同类复发）

**Symptom.** 视觉转写批次入库后，`:core:data:testDebugUnitTest` 的
`BundledTeachingMaterialsContractTest` 报
`DatabaseContractViolationException: Teaching-material review cannot predate its source import`；
424 条用例里 7 条红（另有 6 条是 KD-18）。

**Root cause.** 同一 `sourceId` 会随不同批次落进不同卷，每卷各带一份 `source` 条目、
各自盖"写入时刻 − 60s"。Kotlin 侧 `distinctBy` 只认先出现的那份，于是后写的那份
（时间更晚）成为生效值，先前批次里 `reviewedAt` 更早的材料就被判成"材料早于来源导入"。

**Evidence.** 修前实测：命中 `desktop-src-3692ca65ed:math`（91 条）与
`desktop-src-5b2dfb6dc5:physics`（193 条）共 284 条材料违反契约；
同 sourceId 的两份副本时间戳分别来自第 1、2 次写入。修后复算违反数 0，
`:core:data` 424 条全绿（结果时间 03:04:17，晚于包改动 02:59:34）。

**Fix.**
1. `tools/kb_coverage/materialize.py` 新增 `_existing_source_entries()`：写入前先扫全卷，
   同一 `sourceId` 已有条目就**复用最早那份**（`importedAt` 取最早），不再各写各的；
2. 数据修复：把每个来源的 `importedAt` 压到
   `min(各副本原值, 该来源全部材料的 reviewedAt 最小值 − 60s)`，改写 76 条来源。

**Reopen condition.** n/a —— 由 `BundledTeachingMaterialsContractTest` 持续守门；
任何再引入该缺陷的写入都会让该用例直接变红。

## KD-20 (fixed 2026-09-19) · 门禁的控制字符集比 App 契约窄，C1/双向控制符只能靠 Kotlin 用例兜住

**Symptom.** 五三 B版批次（3,609 条材料）入库后，
`BundledTeachingMaterialsContractTest` 报
`DatabaseContractViolationException: material.contentMarkdown contains unsupported control characters`，
427 条用例 1 条红。

**Root cause.** `gate.py` 的 `control_chars` 指标只查少量控制符（本轮实测仍报 80，与回修前一致），
而 App 契约（`KnowledgeTeachingMaterialContract.text`）拒的是
**全部 ISO 控制符（Cc，排除 \n\r\t）＋双向控制符**（U+061C、U+200E/F、U+202A–202E、U+2066–2069）。
两条判据不同源，于是"Python 门全绿、App 直接崩"。

**Evidence.** 按契约语义重扫 15,862 条材料：命中 3 个字段 / 2 条材料，均为 C1 控制符
（0x80、0x81、0x88、0x89、0x9C、0x9D —— PDF 编码事故残留）：
`ext-che-e1f44db337-030.contentMarkdown`、`ext-che-1ff1c78002-001c.boundaryMarkdown`、
`ext-che-1ff1c78002-003.contentMarkdown`。清理后重扫 0 命中；`:core:data` 427 条全绿
（结果时间 09:34，晚于清理 09:33）。

**Fix.** 清理脚本 `build/clean_contract_chars.py`（按契约语义清 Cc + 双向控制符，写回卷）。
**未做**：把 gate 的 control_chars 换成与契约同源的判据 —— 这是漏修，记在此处；
下次动 `gate.py` 时应当直接从契约抄集合，否则同类缺陷仍只能靠 Kotlin 用例在最后一刻发现。

**Reopen condition.** n/a —— 由 `BundledTeachingMaterialsContractTest` 持续守门。

## KD-21 (fixed 2026-09-19) · 2,265 处 `\1` 回指残迹随包分发，门没有任何判据看得见

**Symptom.** 数学材料标题里出现 `$f(x)=f(x+a)\Rightarrow\1=a$`。全包普查：473 条材料 / 2,265 处
`\1`，另有 6 处行尾孤立反斜杠、3 处 `\，`、9 处 `f\'(x)`。这些字段是**讲题时模型直接读到的文本**：
`\1` 进去，公式语义就断了；`title` 还进别名表参与检索。

**Root cause.** 残迹形态是「一个空白 + 一串 ASCII 词元」被整体替换成 2 字符 `\1`（原文本用 LaTeX
细空 `\` 时反斜杠残留，于是出现 `\1`）。`gate.latex_damage` 只查"真命令丢了反斜杠"
（`\cos\alpha` → `\coslpha`），**反方向（反斜杠后跟了不该跟的字符）没有任何判据**，
于是整类残迹静默出厂。

**Evidence.** 30 个子代理逐字段修复时取到的独立证据：485 条字段的修复结果与
`cffda989^`（损坏前提交）/ 该卷历史版本 / 入库前 `.agent_*.csv` 产物**逐字节相等**——
即残迹确实是"曾经正确、后来被替换"。53 条无干净前身（首次入库即坏）按材料内互证重建。
`material_judgments.csv` 侧 1,762 处同源（入库上游）一并镜像修好。

**Fix.** 门新增 `invalid_escape` 指标（allowlist 由全包"反斜杠+非字母"普查定：
`\`、`\ `、`\{`、`\}`、`\%`、`\,`、`\_`、`\|`、`\;`、`\:`、`\(` 都是真写法）；
修复工具 `fix_invalid_escapes`（三类机械 + 表驱动语义）；写回器 `apply_text_fixes` 的三层独立复核。

**Reopen condition.** `invalid_escape` 指标非 0（门里的哨兵 + `test_gate_reports_real_defects_not_zero`）。

## KD-22 (fixed 2026-09-19) · `$$` 被 shell 展开成 PID、`$0` 变成 /usr/bin/bash

**Symptom.** 材料正文里出现 `310243n = \frac{a-xb}{2}310243`（同一 6 位数夹住一条公式）、
`只能读到 /usr/bin/bash.1\ \mathrm{g}$`、`必须控制在 .5\sim10.5$`（`$` 计数为奇数）。

**Root cause.** 文本在某一环被丢进 **shell 上下文**：`$$`（行间公式定界符）→ 进程号、
`$0` → 脚本名 `/usr/bin/bash`、`$1`/`$c`/`$p` 这类"$ + 名字"连名字一起被展开掉。
`gate` 既没有 PID 判据也没有 `$` 奇偶判据，所以没有任何一项会红。

**Evidence.** 9 个字段的 PID 残迹（6 位数两端各一）、13 处 `/usr/bin/bash`、20 个字段 `$` 不成对；
其中化学数值类残迹可用材料自身算术反证（`0.05×99/6=82.5%` ⇒ 取样量 `$6\ \mathrm{g}$`、
莫尔法 `pH 6.5~10.5`、`$p-\pi$ 共轭`）。

**Fix.** 门新增 `shell_expansion`（`$0` 残迹 + PID 双重判据）与 `dollar_unbalanced`（`$` 奇偶）两项指标；
判据的三条限定（6–7 位、窗口 ≤120 字、中间夹公式记号）由"真残迹全是 6 位数、两例误报全是 5 位换算系数
（10000/40000）"实测倒逼，并写进单测正反两侧钉住。

**未指认环节（如实记录）.** 当时的命令记录没留下，无法指认是哪个脚本/代理把文本丢进了 shell；
因此防线放在门的判据上，而不是指认单个工具。

**Reopen condition.** `shell_expansion` 或 `dollar_unbalanced` 非 0。

## KD-23 (fixed 2026-09-19) · `\ text{` 被 `\1` 残迹掩盖：修好一类才露出另一类

**Symptom.** 修完 `\1` 后门 `latex_damage` 从 0 变 5（`$9.8\ text{m/s}^2$`、`$a=c\ text{且}\ b=d$` 等）。

**Root cause.** 两处损坏叠在同一个位置：`\ text{` 的坏点特征是 `ext{` 尾部没有反斜杠，
而同一字段里的 `\1` 恰好把 `\ text` 吃成了 `\1`，判据于是看不见它。**修得掉看得见的，才露出被掩盖的。**

**Evidence.** 逐字段比对 HEAD 版与写回后：HEAD 版 `_latex_damaged` 为 False、写回后为 True，
且命中的 tail 全是 `ext`（`text`）；全包 `\ \text{` 140 处（合法写法）vs `\ text{` 7 处（坏）。

**Fix.** `textfix` 新增形态 4（`\ 空格 命令名{` → 补回反斜杠，命令名必须紧跟 `{`，
故合法的 `\ mol` 细空不受影响）；`fix_material_text --judgments` 把同一套修复镜像回判定表
（46 个文本列；非文本列指纹比对不变、仍受损行 0）。

**Reopen condition.** 门 `latex_damage` 非 0。

## KD-24 (open) · 去截断实验（v2）净伤害词面路由已回滚——公共 gram 通胀打掉窄路召回（19 例自由落体）+ 宽路 p95 663ms 超预算；别名截断缺陷（62% 节点）改由已开启的 dense 兜底议题结构性消灭，词面侧不再以去截断方式修（口径更正见末段）

**Symptom.** 2026-09-22 第四轮按 D12 施工去截断索引（v2：删
`MAX_SEARCH_FRAGMENTS=16` / `MAX_NODE_FEATURES=192`）并把 KNOWLEDGE_READ / MASTERY_READ
聚焦解析统一成 B512→A 路由后，档 2 真机两次复跑（数据确定性插入，两次出数一致）实测
两条路都是净伤害：

- **v2 索引 × 裸 B5**：金标主集 0.5222（47/90）尚可，但 **19 例回归的自由落体例在
  v2 窄路失败**——去截断放大公共 gram 通胀（大别名量节点的二值 TF count 被公共
  2/3-gram 抬高），把少公共 gram 的精确节点（"自由落体运动"，题面公共特征只有
  "自由" → count=1）挤出 64 宽网，窄路召回被打掉；
- **v2 索引 × B512→A（统一路由）**：19 例恢复 19/19（`bundled-knowledge-recall
  regression: hit=19/19, cross-subject=0`），但金标主集 **0.3444（31/90）**（比裸 B5
  更差）、**p95=663ms / 695ms**（两次复跑，超 150ms 预算 4.4×；同设备同形路由的
  2 万合成点控制测 p95=62ms——成本来自宽召回 + A 对 512 候选的 Kotlin 计数打分，
  非设备故障）。

**Evidence.** 档 2 复测#1/#2 仪表化日志（逐字存档于 `docs/kb-vector-topic-decision.md`
§3.1）：`route=B512->A-select->top5(生产统一路由) indexVersion=2 cases=90
samplesPerQuery=5`；p95=663ms/p50=457ms/max=40901/overBudget=444/450 与
p95=695ms/p50=467ms/max=35253/overBudget=446/450；v2 索引规模
`totalFeatureRows=873465 maxFeaturesPerNode=1672`（v1 同包：`totalFeatureRows=583271
maxFeaturesPerNode=192`）。JVM 镜像与真 SQL 两次复跑漂移 0，排除镜像口径问题。

**Resolution (2026-09-22, 形状回滚 / FIX2).** 生产回零回归形状，测量设施全保留：

1. 索引回 v1 语义：`KnowledgeSearchFeatureExtractor` 恢复节点侧截断
   （`MAX_SEARCH_FRAGMENTS=16` / `MAX_NODE_FEATURES=192`），`INDEX_VERSION` 2→1；
   WP4 的版本锚点表 `knowledge_search_index_state`（v51 迁移，保留基建）按
   "锚点缺失或不等 ⇒ 整科换血"自动触发——v2 实验库（锚点=2）首次召回即回建 v1，
   Room 51 迁移本身不滚；
2. 路由回滚：KNOWLEDGE_READ（裸 B 路 limit=5）与 MASTERY_READ 聚焦解析（裸 B 路
   limit=24）回到 FIX 前生产形状；归类/拍照路径的 B512→A 不动（既有、消费者不同）；
3. 19 例回归 limit 512→64 回原形状（断言一字不改，v1 上 19/19——历史即 v1-B64→A
   19/19）；金标判分目标回 v1 裸 B5（真 SQL + JVM 镜像同步，零漂移纪律与预注册
   阈值一字不动）；
4. 提取器 v1 截断行为有独立钉（`KnowledgeSearchFeatureExtractorTest`：178 片段
   节点选 16 入预算、特征 ≤192）。

**为什么词面侧不再以去截断方式修。** 去截断的意图是消灭"62% 节点别名不全入索引"
（审计 §1.4），但实测它放大的是同一个二值 TF 排序的病（D-01 无 IDF/无长度归一化）：
公共 gram 通胀挤掉窄路召回，宽路又买不起 p95。词面栈内两条形状（v2-裸B5、
v2-B512→A）都已实测，均不及或不及 v1 生产形状（三组数并列见
`docs/kb-vector-topic-decision.md` §3.2）。

**缺陷的归宿（结构性消灭，不是掩盖）。** 别名截断缺陷改由 D12 首测已触发开启的
**同层 dense 兜底议题**解决：28,932 条向量（3,572 知识点 + 25,360 别名，审计
§2.4-1 口径）×512 维全扫 1.4–2.8ms（内存带宽估算），向量侧**无截断损失**——
"别名只有一部分进了索引"这一失败类在 dense 兜底里不存在，词面索引的截断因此
不再承载召回正确性，只承载 p95 与体积预算。议题形态预案（bge-small-zh int8 +
LiteRT ≈30MB / RRF / 无 ANN）见决策文档 §4，立项需另一次用户裁定。

**Reopen condition.** 任何一条：(i) 再次尝试词面侧去截断/宽召回统一路由而未经金标
三组数对照（`GoldenRetrievalInstrumentedTest` + `GoldenRetrievalJvmTest` 零漂移）；
(ii) `INDEX_VERSION` 被改动但锚点换血语义（不等即重建）被弱化；(iii) 19 例回归
（v1 索引 × B64→A）跌破 19/19——那是窄路召回的真实回归，不是设备噪声（KD-2 的
预算系数不覆盖召回命中断言）。

**口径更正（2026-09-23，WP-A；只更正读法，不改归宿、不改重开条件）。** 本条目（及
标题、审计 §1.4、`KnowledgeSearchFeatureExtractor.kt` 文档注释）引用的「**62%** 节点
别名被索引截断」是**含 boundary 片段**的读法。对成品包
`core/data/src/main/resources/knowledge/moe-2025-four-subjects-v1.json` 的 3,572 个
原子节点、按 `KnowledgeSearchFeatureExtractor.fromNode` 的实际切片规则（名称 + 排序后
别名 + boundary，片段按 `[^\p{L}\p{N}]+` 切分，判据 = 片段数 > 16）复算：

- **纯名称 + 别名超 16 片段：661 / 3,572 = 18.51%**——这才是"别名被截断"的节点面；
- **含 boundary 片段超 16 片段：2,208 / 3,572 = 61.81%**（审计记 2,209，差 1 条为口径
  边界，不影响结论）。

更正后的读法：**"62%" 不等于"62% 的节点的别名进不了索引"**——别名被截断的节点面是
18.51%，被截掉的主要是 boundary 摘录（61.81%）。**归宿不变**（该缺陷仍由已开启的同层
dense 兜底议题结构性消灭，词面侧不再以去截断方式修——v2 实测净伤害即本条登记的事实）；
**重开条件不变**（原文三条一字不动）。复算口径与命令的完整记录见
`docs/kb-vector-topic-decision.md` §7 附录 A.1。
