# Release Guide

## Dry-run gate

Before tagging a release:

1. Run both debug flavors: assemble, unit tests, lint.
2. Run `:app:assembleRelease` / bundle dry run with R8 enabled.
3. Verify the merged release manifest has no debug activity and no over-broad
   FileProvider path.
4. Run the complete Room migration matrix for every historical schema.
5. Run backup create/validate/restore round trip on a device.
6. Confirm no API key, Keystore secret, lease, or user content appears in
   backup archives or release logs.
7. Confirm the version name and code are bumped and the release branch only
   receives blocking fixes.

Release signing is intentionally not committed. Configure `keystore.properties`
locally and keep the keystore out of the repository.

## Rollback

Each release must be installable over the previous version with no destructive
database migration. If a migration cannot be made non-destructive, do not ship
it.
