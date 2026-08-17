# Product Contract

The product turns a mistake into a local learning asset that can be revised,
searched, explained, re-practiced, scheduled, and migrated.

## Non-negotiable facts

- Room is the sole source of truth for learning facts.
- All question images go through the canonical asset pipeline and are recorded
  in Room before use.
- Tutor lobby is text-only. A photo/selected image starts the Capture semantic
  pipeline, not a fake image-capable lobby.
- Models propose local actions; only the user can save to the mistake book,
  update mastery, navigate, or delete.
- A logical external operation has one stable identity and at most three
  provider dispatches across retries and process recreation.
- Process recreation requires an explicit user "continue" before any external
  send resumes.
- No timestamp may be used to bypass idempotency conflicts.
- No release build exposes debug activities or broad FileProvider roots.
- No silent in-memory truncation of knowledge points or facet options.
- Backup is explicit, validated, restorable, and never contains provider keys
  or leases.

## Empty and error language

Users see business impact, not internal pipeline terms. Error copy explains
what happened, whether data is safe, and the next action.
