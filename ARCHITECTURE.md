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

Every external model request passes a machine-checkable contract from
`ModelTaskContractRegistry`, an exact egress manifest, and a current in-process
lease before dispatch. The logical operation budget is durable and shared
across retries.

See `docs/current/architecture-contract.md` for the full contract.
