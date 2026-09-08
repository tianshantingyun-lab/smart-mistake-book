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

Every external model request is authorized by `ModelEgressPolicy`. An
agent-eligible round (capture assess/parse/classify, tutor plan/respond/visual)
dispatches under the global model-agent consent with no per-item manifest and
must satisfy `agentConsentMatches` (consent on, configured external provider
supporting the kind, image-capable for image-bearing kinds); every other round
carries an exact egress manifest and passes the runtime checks in
`ModelEgressManifest` (disclosure set, asset scope, approval freshness,
prompt-policy version) before dispatch. `ModelTaskContractRegistry` is the
machine-checkable contract table those invariants are derived from; it is
exercised by contract-parity tests rather than called on the dispatch path. The
logical operation budget is durable and shared across retries
(`ModelTaskRemoteDispatchPolicy.MAX_DISPATCHES = 6`).

See `docs/current/architecture-contract.md` for the full contract.
