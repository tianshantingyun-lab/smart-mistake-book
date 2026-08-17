# Contributing

1. Read `docs/current/` before changing product or architecture behavior.
2. Keep PRs small and single-purpose; do not mix Android, website, logo, schema,
   and prompt changes in one commit.
3. Every behavior change needs a focused test and a fresh verification command.
4. Database changes require a non-destructive migration and exported Room schema.
5. Do not use timestamps to resolve idempotency conflicts.
6. Do not claim "complete" while tests or device gates are unverified.
7. Preserve unrelated user-owned changes in the working tree.
