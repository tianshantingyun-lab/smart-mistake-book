# Release Guide

## Dry-run gate

Before tagging a release:

1. Run both debug flavors: assemble, unit tests, lint.
2. Run `:app:assembleRelease` / bundle dry run with R8 enabled.
3. Verify the merged release manifest has no debug-only component (activity,
   receiver, service or provider declared under app/src/debug) and no
   over-broad FileProvider path. `python tools/ci/check_release_manifest.py`
   automates both checks.
4. Run the complete Room migration matrix for every historical schema.
5. Run backup create/validate/restore round trip on a device.
6. Confirm no API key, Keystore secret, lease, or user content appears in
   backup archives or release logs.
7. Confirm the version name and code are bumped and the release branch only
   receives blocking fixes.

Release signing is intentionally not committed. Configure `keystore.properties`
locally and keep the keystore out of the repository.

## Known migration debt: v16 reconstructed schema

Schema `16.json` carries a reconstructed identity hash
(`00000000000000000000000000000000`) because the original v16 export was not
retained; its DDL was rebuilt from the v15/v17 exports and
`ExportedSchemaContractTest` scopes this exception to v16 only.

Risk boundary, verified 2026-08-30:

- Runtime migration is version-driven, so the placeholder never participates in
  the hash check of a live device; after migrating to the current version Room
  revalidates the final schema and rewrites the hash.
  `FullMigrationMatrixInstrumentedTest` proves the whole 1..current chain,
  including v16, migrates without destructive fallback.
- What cannot be proven: the reconstructed v16 DDL byte-matched whatever a real
  v16-era APK wrote. If any device still runs that vintage, a migration failure
  would surface as a Room `IllegalStateException` on that device only.
- Mitigation before shipping to such a device: run a backup export on it, do a
  fresh install, then restore. If no v16-era install exists (all development
  stayed on newer builds), the debt is archival only.

## Rollback

Each release must be installable over the previous version with no destructive
database migration. If a migration cannot be made non-destructive, do not ship
it.
