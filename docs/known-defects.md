# Known Defects Register

Only defects that block a gate (CI instrumented) or a release claim live here.
Each entry carries the reproduction evidence collected so far so a dedicated
session can resume without re-deriving context. Fixed entries move to the
commit history.

## KD-1 · Tutor external-authorization flow regression (instrumented)

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

## KD-2 · Wall-clock p95 gate fails on CI runners (instrumented)

**Symptom.** `KnowledgeContextRetrievalInstrumentedTest#largeSubjectRecallRemainsBoundedOnRoom`
fails on CI: "Mastery snapshot read p95 was 278ms; samples=[278, 224, ...]".
Passes locally (API-34 emulator, WHPX). Threshold: `MASTERY_READ_P95_BUDGET_MILLIS`
(core/data androidTest, same file).

**Assessment.** Absolute wall-clock budgets on shared CI runners are
environment-sensitive by construction; the deterministic parts of the test
(index-usage query-plan assertions) all pass. Local green + CI red at ~280ms
points at runner noise, not a query regression — but weakening the budget
unilaterally is forbidden (test-rigor rules).

**Next steps.** Decide one of: (a) runner-relative bound (indexed vs scan
ratio), (b) CI-multiplied budget via a gradle-injected property, or
(c) move the wall-clock gate to the macrobenchmark module and keep query-plan
assertions here. Requires the performance-budget owner's approval.
