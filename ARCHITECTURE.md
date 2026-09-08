# Architecture

The Android client is modular:

```text
app -> feature:* + core:data
feature:* -> core:domain + core:model + core:ui
core:data -> core:domain + core:model + core:database
```

Room is the single source of truth for learning facts. Composables receive
immutable `UiState` and send `Action` objects; ViewModels and domain use cases
own asynchronous work. Model providers never write database facts.

Every external model request carries an exact egress manifest, a current
in-process lease, and passes the runtime checks in `ModelEgressManifest`
(disclosure set, asset scope, approval freshness, prompt-policy version) before
dispatch. `ModelTaskContractRegistry` is the machine-checkable contract table
those invariants are derived from; it is exercised by contract-parity tests
rather than called on the dispatch path. The logical operation budget is
durable and shared across retries.

See `docs/current/architecture-contract.md` for the full contract.
