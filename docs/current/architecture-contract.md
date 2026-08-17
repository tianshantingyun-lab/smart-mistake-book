# Architecture Contract

## Module dependency direction

```text
feature:* -> core:domain + core:model + core:ui
core:data -> core:domain + core:model + core:database
app       -> feature:* + core:data
```

Prohibited:

- `feature:*` depending on concrete `core:data` classes
- Composable calling DAOs or managing database/files/provider workflows
- model parser writing database facts
- persistent egress manifest acting as cross-process send permission

## State rules

- Complex pages use one immutable `UiState` plus one `Action` set.
- `SavedStateHandle` stores small IDs and transient UI state only; business
  state is rebuilt from Room.
- Model results are applied with CAS: expected workflow/session/basis must
  match the current persisted state or the result is stale.
- All sends, saves, attempts, and review advances use explicit command
  idempotency and single transactions where the plan requires them.

## Asset pipeline

Camera, photo picker, PDF page, and tutor attachments all use:

```text
URI -> safe read -> format/size/pixel budget -> orientation fix ->
metadata strip -> content hash -> private vault -> Room SourceAsset
```

There is exactly one tutor/session conversation fact model in production.
