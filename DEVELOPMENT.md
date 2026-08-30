# Smart Mistake Book - Development

## Prerequisites

- JDK 21 (Eclipse Temurin or equivalent)
- Android SDK with `sdk.dir` configured in a local `local.properties`
- Gradle wrapper is committed; run `gradlew` / `gradlew.bat`

The repository uses two product flavors:

```text
localFirst      network and model provider enabled
strictOffline   no model network access
```

## Build

```bash
./gradlew :app:assembleLocalFirstDebug
./gradlew :app:assembleStrictOfflineDebug
```

## Verify

```bash
./gradlew testLocalFirstDebugUnitTest testStrictOfflineDebugUnitTest
./gradlew lintLocalFirstDebug lintStrictOfflineDebug
./gradlew :app:assembleLocalFirstDebug :app:assembleStrictOfflineDebug
```

Room schema files are exported under `core/database/schemas/`. Any database
change must be accompanied by the exported schema JSON and a non-destructive
migration. Normally Room exports schema JSON; never hand-edit the identity hash.
The historical `16.json` in this repository was reconstructed from v15/v17
DDL because the current source no longer contains the v16 entity set, and its
identity hash is a placeholder that must be regenerated from historical source
before release.

## Windows note

On Windows, keep the checkout path free of non-ASCII characters for Gradle test
workers. If the project lives under a Chinese path, run verification from an
ASCII directory junction, for example:

```powershell
New-Item -ItemType Junction -Path D:\smb-build -Target D:\智能错题本
cd D:\smb-build
.\gradlew.bat testLocalFirstDebugUnitTest
```

This is a local environment workaround only; CI runs on an ASCII path.

## File-size and state-ownership rules

The CI file-size gate (`tools/ci/check_file_size_gate.py`) rejects a branch
only when it *introduces* a hard-line oversized file — it is baseline-aware and
does not punish the existing backlog. Keep these limits in mind when writing or
refactoring:

- **Main source** (`src/main/`): warn above 600 lines, block above 1000.
- **Tests** (`src/test/`, `src/androidTest/`): warn above 900, block above 1500.
  Instrumented/black-box tests legitimately run longer than a unit of app code.

Rules of thumb that keep files under these lines:

- **State belongs in the narrowest scope that needs it.** Compose `remember` /
  `rememberSaveable` state should stay in the Composable that owns it; only lift
  it out when two or more functions genuinely share it. Do not move
  `rememberSaveable` state into a plain class — it breaks the saveable registry.
- **Logic lives in Policy/Commands, not inline in the Composable body.** A
  Composable function should mostly *wire* state to UI. Extract a reusable
  decision into a `Policy` (pure function, unit-testable) or a `Commands`
  (side-effecting, driven by a `Sink` of state readers/writers), exactly as
  `feature/capture` already does.
- **Split by cohesion, not by file size alone.** When a file grows, pull out the
  cohesive block (a store, a migration chain, a component) rather than moving
  the largest chunk. Never split a file just to hit a line count.

## Contract and release entry points

Read `docs/current/` before changing product, architecture, model task,
database, or release behavior. Historical progress documents remain in
`docs/` for provenance but are not implementation authority.
