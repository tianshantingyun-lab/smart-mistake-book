# Task 4B3a report

## Delivered

- Added optional, backward-compatible `TutorRespondInput.selectedChoiceId` and preserved it in request identity, task serialization, retries, and recovered requests.
- Added the locally controlled `TutorResponseMessage`; directive choice clicks issue it only from the visible `Choices` whitelist, while free text carries no choice ID.
- Passed the value through `TutorGeneratedTurn`, `TutorChatConversation`, and `CapturedTutorSessionRoute`.
- `buildTutorRespondRequest` rejects forged/stale IDs and ID/label mismatches before any model request. The model-visible student message remains only `labelMarkdown`.
- Preserved the choice ID across a pending external-egress authorization and revalidates it against the current visible directive before continuing.

## Tests added

- Same-label/different-ID choices produce different request IDs and retain the selected ID.
- Forged/stale and label-mismatched choices are rejected.
- New and legacy serialized respond inputs respectively retain and default the optional ID.
- Compose route test clicks a directive choice and asserts the captured response request contains its ID.

## Verification

- PASS: from `T:\`, `:feature:tutor:compileDebugKotlin :feature:tutor:compileDebugAndroidTestKotlin --no-daemon --no-configuration-cache '-Pksp.incremental=false'`.
- PASS: from `T:\` with `ANDROID_SERIAL=emulator-5558`, the exact Compose test `CapturedTutorSessionInstrumentedTest#directiveChoiceClickSendsTheCapturedChoiceId` via `:feature:tutor:connectedDebugAndroidTest --no-daemon --no-configuration-cache '-Pksp.incremental=false'`.
- BLOCKED: the required JVM unit suites compile, but their test executor fails before running tests even after a forced single-worker retry: `ClassNotFoundException: worker.org.gradle.process.internal.worker.GradleWorkerMain` from `:core:model:test` on `T:\`. No cache was removed.

## Fix round

- Versioned the request schema for `selectedChoiceId`; legacy schema-6 inputs retain their pre-field request and logical-operation fingerprints after decoding.
- A selected choice is now resolved from the current tail timeline task and its request ID before the builder accepts it; stale callbacks fail closed.
- Pending external responses now retain the directive source request ID as well as the choice ID.
- PASS from `T:\`: `:core:model:compileKotlin :core:model:compileTestKotlin :feature:tutor:compileDebugKotlin :feature:tutor:compileDebugUnitTestKotlin`.
- JVM execution remains blocked before tests begin by the documented Gradle worker classpath failure.
- Remaining verification: follow-up directive external-authorization Compose recovery still needs its dedicated route test.
