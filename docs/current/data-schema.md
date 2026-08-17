# Data Schema and Migration

The current Room schema is versioned in `core/database/schemas/`. Every schema
version from 1 to current must exist as exported JSON.

Rules:

- Never use destructive migration for user data.
- Every historical version has a migration chain test to current.
- Critical commands use single transactions and idempotent receipts.
- Conversation/message ordinals and logical operation identities are unique.
- Asset cross references are indexed in both directions.
- Deleting a tutor conversation never deletes a saved problem or its assets.
- Backup format version is independent of Room schema version.

When adding a schema change:

1. bump `STUDY_DATABASE_VERSION`
2. add a non-destructive `Migration`
3. let Room export the new schema JSON
4. add migration/instrumented tests
5. run `git diff --exit-code -- core/database/schemas` after a clean build
