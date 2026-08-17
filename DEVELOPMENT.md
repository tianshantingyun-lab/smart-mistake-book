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

## Contract and release entry points

Read `docs/current/` before changing product, architecture, model task,
database, or release behavior. Historical progress documents remain in
`docs/` for provenance but are not implementation authority.
