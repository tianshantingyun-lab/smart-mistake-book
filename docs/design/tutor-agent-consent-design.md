# 讲题栏智能体化：全局模型同意（配置模型 = 全局同意）

Branch: `feat/model-driven-clean-image` · Worktree: `.worktrees/image-pipeline`
Date: 2026-09-05 · Status: **implemented (ratified by code)**
Scope: tutor conversation becomes a WeChat-style agent — no per-message/per-move egress confirmations; one global "model agent" consent covers capture assess/parse/classify AND tutor plan/respond/visual.

> This design was realized across commits `74bf198` (schema v8 + decode shim), `e680e0a` (gateway /
> asset-source generalization to tutor-visual), `be5930e` (ModelAgentConsentStore + Capability
> switch), `9c7b0a7` / `3a5f8fe` (capture / tutor removal), `0da033c` (drop capture→tutor auto-start),
> then refined by `dc2f77a` (image-bearing → type-level `requestsImageBytes`) and `4300e5b`
> (consent gate → LOCAL-aware `tutorAgentChatEnabled`). The gate and `requiresImageInput` shown below
> are the **ratified** forms, updated where subsequent refactors superseded the original sketch.

> Product decision (settled, not re-litigated): **配置模型 = 全局同意**. The tutor sends
> message/photo and, without any per-message/per-move confirmation, the model begins, understands,
> self-calls tools, and answers. Tutor **visual region-crops are a supported runtime input** (NOT
> crop-blocked) and egress under the same global consent.

> **2026-09-13 增补：同意开关本身已删除（产品裁定「配置模型 = 全局同意」的字面落地）。**
> 曾短暂存在的 `ModelAgentConsentStore` / `DataStoreModelAgentConsentStore` 及其
> `CapabilityScreen` 开关、以及 `tutorAgentChatEnabled` 的 `consentEnabled` 形参**全部删除**：
> 配置好 provider 就是唯一条件，再没有「已配置但已关闭」这一状态。因此：
> - **§1.7 与 §2.2 被取代的部分**：§1.7 描述的 store 接口与开关（"Introduce the store interface
>   exactly where that supplier sits"）**从未保留**，已随本次删除移除；§2.2 的闸门公式去掉
>   `consentEnabled` 一项，现行形式为 `tutorAgentChatEnabled(provider, kind)`。两节的其余内容
>   （eligible kind 集合、manifest 豁免、视觉裁切支持、LOCAL/UNAVAILABLE 的 fail-closed 规则）**继续有效**。
> - **请求信封不变**：`ModelTaskInput.agentConsentGranted` 字段与 schema/fingerprint 处理一律不动，
>   所有 agent-eligible 调用点照常置 `true`（本条只删「谁来决定这个布尔」，不删布尔本身）。
> - **唯一零出网形态**：去掉开关后，能保证「装了就不出网」的只有 `strictOffline` flavor
>   （无 INTERNET 权限）。`localFirst` 下若学生要停掉图片出网，可行手段是删除模型配置，而不是关一个开关。
> - 附带订正：`BatchOrganizationConsentException` → `BatchOrganizationUnavailableException`
>   （触发条件从来是「没有可用模型」而非「未同意」），设置 CTA 文案同步去掉「开启模型智能体」。
> - 相关出处：`docs/model-first-product-boundaries.md` 第 13 行增补的 2026-09-13 附注、
>   `docs/scenario-registry.md` 第 15 行。

---

## 0. Reader map — where the file leads

1. Consent generalization (core model/schema v8 + fingerprint + gateway/asset handling for tutor-visual crops under consent).
2. Exact tutor-UI removal surface: DEAD vs KEEP per file, and the smallest remaining gate set.
3. Behavior matrix for tutor plan/respond/visual under {consent} × {provider capability}.
4. Test impact (delete vs rewrite) across core + feature/tutor.
5. Ordered steps, each ending in a compile/test gate, schema-8 first.
6. Risks + code-judo (is there a simpler framing than per-kind approvedAt?).

---

## 1. Consent generalization

### 1.1 The kind set

Rename the intent of the flag from "capture pipeline round" to "agent-consent eligible round".

- `core/model/.../ModelTasks.kt`
  - `ModelTaskInput.isCapturePipelineKind` → **`isAgentConsentEligible`**, default `false`.
  - `= true` on:
    - `CaptureAssessmentInput`, `CaptureParseInput`, `ImagePipelineClassifyInput` (unchanged — already true today),
    - `TutorPlanInput`, `TutorRespondInput`, `TutorVisualGenerateInput`, `TutorVisualReviewInput` (**new**).
  - Explicitly **excluded** (remain `false`; stay manifest-gated):
    - `TutorLobbyInput` — its own `/tutor` notebook/library route, no question-photo/block-evidence payload; recommend it **stays manifest-gated** (see §1.5).
    - `TutorDebriefInput` — silent post-session summarization; today external providers skip it entirely; keep local-only.
    - `ProblemOrganizationInput` (PROBLEM_CLASSIFY/PROBLEM_RELATE), `TutorEvaluate` (unbuilt), `REVIEW_RERANK`, `LEARNING_SUMMARIZE` — bounded confirmed-doc disclosures; keep manifest.

### 1.2 `ModelTaskRequest` field + schema bump 7 → 8

- `ModelTasks.kt`: rename serialized+property `captureEgressConsentGranted: Boolean = false` → **`agentConsentGranted: Boolean = false`**.
- Bump the companion const:
  - `CAPTURE_CONSENT_SCHEMA_VERSION = 7` → **`AGENT_CONSENT_SCHEMA_VERSION = 8`**,
  - `CURRENT_SCHEMA_VERSION = AGENT_CONSENT_SCHEMA_VERSION`.
- Update `init` guards that reference the const (`require(schemaVersion >= CAPTURE_CONSENT_SCHEMA_VERSION || !captureEgressConsentGranted)` → `schemaVersion >= AGENT_CONSENT_SCHEMA_VERSION || !agentConsentGranted`).

**Why the rename is a schema break, not cosmetic**: `ModelTaskCodec` (both `Json` instances) and the
fingerprint path use `encodeDefaults = true, ignoreUnknownKeys = false`. Under the new field name the
v7 serializer/decoder no longer carries the old key, so a legacy persisted v7 row that still contains
`"captureEgressConsentGranted":false` will **fail `decodeRequest` on the new schema** (unknown key).
The only safe way to ship the rename is to (a) bump to a schema where the old literal is *absent* from
new encodes and (b) teach decode to tolerate it for `schemaVersion<8` rows only.

### 1.3 Decode shim + fingerprint strip for legacy v7 rows

Fingerprint mechanics live in `ModelTaskFingerprint.fingerprintPayload()` and its strip helpers
(`withoutCaptureConsent()` strips `,"captureEgressConsentGranted":false` for `schemaVersion<7`). Three
places must be touched together, and **validate/schema land before feature code** (§5 step 1) because a
decode failure on persisted rows corrupts state otherwise:

1. **Fingerprint strip for v7 rows** — replace `withoutCaptureConsent()` (which strips the old key for
   `schemaVersion<CAPTURE_CONSENT_SCHEMA_VERSION`) with a strip keyed on `schemaVersion <
   AGENT_CONSENT_SCHEMA_VERSION` that removes **the v7 literal** `,"captureEgressConsentGranted":false`
   and also normalizes **the empty new key** when it appears (mirror `withoutEmptyToolCarrier`'s
   precedent). Rationale: a v7 row was encoded by a serializer that emitted the old key; recomputing
   its fingerprint on schema 8 code must produce the same digest that `RoomModelTaskRepository`'s
   snapshot `require(requestFingerprint == ModelTaskFingerprint.of(request))` (ModelTasks.kt:1085)
   demands on read-back.
2. **Transient decode shim** — after the rename, `decodeRequest` runs on v8 default. For a row with
   `schemaVersion==7` carrying `"captureEgressConsentGranted":...`, the v8 decoder would throw on the
   unknown key. Provide one bounded shim: try the v8 decode; on failure containing a
   `schemaVersion:7` payload with the old key, translate the old key to the new field and decode once
   more (or pre-strip the unknown key before decode). `encodeRequest`/`decodeRequest` are the only
   entry points for the room DAO, so one shim inside `ModelTaskCodec` covers both read and (v8) write.
   A heavier Room migration is unnecessary: rows are re-encoded on their next successful terminal
   transition, and schema is per-row (`schemaVersion` field), so old rows remain decodable without a
   table ALTER.
3. **`CaptureAssessment.schemaVersion`/other nested schemas** — not affected (their own versioning is
   separate from `ModelTaskRequest.schemaVersion`).

### 1.4 Permit, authorize, revalidate — rename + widen, split `supportsImageInput`

`core/model/.../ModelEgress.kt`:

- `captureConsentMatches(provider)` → **`agentConsentMatches(provider)`**:
  ```kotlin
  fun ModelTaskRequest.agentConsentMatches(provider: ProviderCapabilitySnapshot): Boolean =
      agentConsentGranted &&
          input.isAgentConsentEligible &&
          provider.executionLocation == ModelExecutionLocation.EXTERNAL_PROVIDER &&
          // NOT here: supportsImageInput. Image-capability is enforced by the specific kind below.
          provider.supports(input.kind)
  ```
- **Split image-capability out of eligibility (decision #3).** A structured-only external provider must
  still run PLAN/RESPOND under consent (their inputs disclose no image bytes — `assets=emptyList()`,
  `TutorPlanInput`/`TutorRespondInput` are text+confirmed-doc+evidence). Visual kinds additionally
  require the provider to accept images. Introduce one predicate for the *visual* gate and keep
  `authorize()` failing closed for them:
  ```kotlin
  // RATIFIED: image-bearing is a type-level property, not an enumerated list.
  // ModelTaskInput.requestsImageBytes() defaults false; the 5 image-bearing inputs
  // (CAPTURE_ASSESS/PARSE/CLASSIFY + TUTOR_VISUAL_GENERATE/REVIEW) override true.
  fun ModelTaskInput.requiresImageInput(): Boolean =
      isAgentConsentEligible && requestsImageBytes
  ```
  (The gateway and asset source consume this shared model predicate — `OpenAiCompatible`
  ModelGateway.kt:`isReadyForNetwork`/`requireImageRequestFits` — instead of a private local list.)
  Then:
  ```kotlin
  // visual/capture *images* need the provider image-capable:
  provider.supportsImageInput && provider.supports(input.kind)
  ```
- `ModelEgressPolicy.authorize()` — external branch: when `request.agentConsentMatches(provider)`
  **and** the kind's image need is satisfied (`!requiresImageInput || provider.supportsImageInput`),
  return `ProviderConsented`; otherwise fall through to the manifest path (lobby, organization,
  evaluate, visual-without-image-capable provider → `EGRESS_AUTHORIZATION_REQUIRED`).
- `requireCurrentExternalAuthorization()` — `ProviderConsented` branch revalidates via
  `agentConsentMatches(provider)` (already revalidates the consent condition at transport time).
- Update the KDoc on `ProviderConsented` ("capture-pipeline kinds…") to the generalized agent wording;
  the semantic stays "carries no per-asset grant; the request's own asset refs + consent flag
  authorize the read."

### 1.5 Tutor LOBBY — recommendation: stays manifest-gated

`TutorLobbyModelTaskPolicy.kt` builds its own manifest (`TUTOR_LOBBY`, `assets=emptyList()`, pure
text, disclosure = `STUDENT_TUTOR_MESSAGE` + `TUTOR_CONVERSATION_CONTEXT`). The lobby is a separate
notebook/library "ask about my notes" route with no current photo, no block evidence, and a different
conversation identity. **Keep it manifest-gated**; the route retains its own consent card. This keeps
the lobby's bounded disclosure honest without entangling it in the tutor-session global agent consent.
(Do not set `isAgentConsentEligible` on `TutorLobbyInput`.)

### 1.6 Gateway and restricted asset source

`core/data/.../model/OpenAiCompatibleModelGateway.kt`:

- `isReadyForNetwork` (lines 516-537): `consentedCapture` → `consentedAgent = permit ==
  ProviderConsented && request.agentConsentMatches(provider)`. Then require the same split:
  `if (!consentedAgent && request.egressManifest == null) return false`, and
  `(!requiresImageInput || provider.supportsImageInput)` where `requiresImageInput` now includes the
  tutor-visual kinds (today's list already includes `TutorVisualGenerateInput`/`TutorVisualReviewInput`
  — good; just consume the shared model predicate instead of the private local list).
- `requireImageRequestFits` ProviderConsented branch (line 584): `check(request.agentConsentGranted)`.
  Same for the External branch — today External enforces a **strict preflight `byteSize`** per grant;
  keep that. The **critical crop gate is not in the gateway**; it is in the asset source (next).

`core/data/.../model/AndroidRestrictedModelAssetSource.kt` (lines 17-55) — **the crop gate lives
here**; it was opened for tutor-visual region crops under both External and ProviderConsented (the
`selectedRegion == null` hard-throw was replaced by the vault `crop` stream for External, and the
consented path was generalized from capture-only kinds to any agent-consent-eligible image input).

- `External` branch: the old `require(grant.selectedRegion == null) { "Region-scoped image egress
  remains blocked until a trusted crop stream is available" }` was replaced by a real crop — the vault
  `AndroidCanonicalAssetVault.crop(...)` / `toPaddedPixelBounds` path already proven by capture split.
  Under a grant carrying `selectedRegion != null`, the canonical record is decoded, crop-verified
  against the grant (hash/dims), and the cropped pixels streamed within the byte budget.
- `ProviderConsented` branch: `check(request.agentConsentGranted)` + `check(request.input.requiresImageInput())`.
  The capture-only `isCapturePipelineKind` check was generalized so any agent-consent-eligible image
  input (capture kinds + `TutorVisualGenerateInput`/`TutorVisualReviewInput`) may open its referenced
  assets, applying the same region-aware read when the request input's asset refs carry a
  `selectedRegion` (TutorVisual inputs carry `sourceAssets[].selectedRegion`).

**Design rule (now enforced)**: *any* tutor send that would egress an image (whole page or crop)
either (a) carries the request's `selectedRegion` scope AND the consent flag AND an image-capable
external provider (→ `ProviderConsented`, asset source crops to `source.selectedRegion`), or (b)
carries a manifest whose grant region equals the request's asset region (→ `External`), or it throws
fail-closed at `authorize`/`open`. Region mismatch is always a `SecurityException`/authorization
failure — never a silent whole-image egress.

### 1.7 App consent-store interface — where the supplier sits

> **2026-09-13：本节描述的 store 与设置开关最终被删除，不再是现状。** 保留本节以记录当时的接线位置；
> 现行形态见文首增补。

`app/.../SmartMistakeBookApplication.kt` wires today:
`captureConsentGranted = { modelConfigurationStore != null }` at the `CaptureWorkflowRepositoryFactory.create`
call (~line 231). There is **no real consent store yet** (comment at ~228: "a Settings toggle will
gate this in a later stage"). Introduce the store interface exactly where that supplier sits, i.e. a
new `core/domain/.../ModelAgentConsentStore.kt`:

```kotlin
interface ModelAgentConsentStore {
    val agentConsentEnabled: Flow<Boolean>          // DataStore-backed, like DataStoreReviewReminderRepository
    suspend fun setAgentConsentEnabled(enabled: Boolean)
}
```
Backing impl (e.g. `DataStoreModelAgentConsentStore`, mirroring `DataStoreModelConfigurationStore` /
`DataStoreReviewReminderRepository`). Expose `val modelAgentConsentStore: ModelAgentConsentStore?` on
the Application (null in the strict-offline/no-network flavor), and feed it to **both** consumers:

- `CaptureWorkflowRepositoryFactory.create(captureConsentGranted = { consentStore?.agentConsentEnabled… })` — the 
  store becomes the single source for the save-round redraw decision too.
- The tutor panel gate (§2.2) reads the same store.

`CapabilityScreen` (SecondaryScreens.kt:104+) is the natural settings surface: add the consent Switch
under the 大模型 API section, shown only when `configuration.isConfigured && capabilityTester`-style
image capability exists, with fail-closed gray text when consent is off but a model is configured.
(Settings/UI detail is out of scope for the tutor-agent removal below, but the store contract is the
required seam.)

---

## 2. Exact tutor-UI removal surface

### 2.1 What the flow looks like today (verified anchors in `TutorSessionPanel.kt`)

Today the whole panel is gated by a **session-scoped `TutorCompositionEgressLease`** (an in-memory
per-kind approved-at set) plus **`autoStartAuthorization`** produced at capture commit
(`captureTutorAutoStartAuthorization`, feature/capture) — both exist only to prove a *per-composition*
approval so that `authorize()` (manifest path) can build a fresh manifest per dispatch. Under global
consent none of this is needed for agent-eligible kinds, because the request carries
`agentConsentGranted=true` and `authorize()` returns `ProviderConsented` without any manifest.

### 2.2 The smallest remaining gate set

Goal: sending/next-move/drawing dispatches immediately when a **compatible model is configured AND
consented**, and fails closed to a "需在设置中配置/开启模型" state (NOT a silent drop) otherwise.

Per send surface the gate collapses to **one boolean** — `tutorAgentChatEnabled(provider, kind)`
（2026-09-13 起不再有 `consentEnabled` 形参；下方代码块保留当时的公式）：

```
tutorAgentChatEnabled(provider, consentEnabled, kind) =
    provider != null &&                          // null → false
    provider.executionLocation != UNAVAILABLE && // unavailable → false
    provider.supports(kind) &&                   // capability check first
    (provider.executionLocation == LOCAL_NO_EGRESS ||   // local never egresses → always dispatch
        (consentEnabled &&                       // global agent consent ON (external only)
         (!kindNeedsImage || provider.supportsImageInput)))  // only PLAN/RESPOND are image-optional
```

Key points of the ratified form (vs. the original EXTERNAL-only sketch):
- **LOCAL_NO_EGRESS dispatches unconditionally** — it never egresses, so global consent is irrelevant.
  This is folded into the single gate rather than handled by a separate `visualAgentEligible` /
  `tutorPlanExecuteCanStart` wrapper (both deleted).
- `supports(kind)` is checked for **all** locations *before* the LOCAL early-return, so a local provider
  that doesn't support the kind still fails closed.
- `UNAVAILABLE` / `null` fail closed up front.

- **PLAN (first turn + cycle continuation + retry):** gate =
  `provider supports TUTOR_PLAN && consentEnabled`. No `planLeaseApprovedAt`, no fresh-approval, no
  `autoStart`. A configured structured-only provider runs plan fine (no image). Missing provider OR
  consent off → the model-entry card shows the existing "需要先连接大模型 / 需在设置中开启" style
  state with the 去设置 CTA (`onOpenModelSettings`) instead of `TutorDisclosureCard`.
- **RESPOND (composer send, hint/continue/move/reveal, retry):** gate =
  `provider supports TUTOR_RESPOND && consentEnabled`. The composer renders as soon as the current plan
  output exists (drop `respondAuthorized`'s approved-at and the disclosure card; keep `chatSending` /
  `interactionBusy` / answer-exposure constraints). If gate false → keep composer hidden and render the
  settings CTA state (reuse the existing failure→`onOpenModelSettings` affordances and
  `requiresModelSettings()` for the persisted-task failure path).
- **VISUAL_GENERATE / VISUAL_REVIEW (auto-region drawing):** gate =
  `provider supports(kind) && consentEnabled && provider.supportsImageInput`. Auto-dispatch
  `LaunchedEffect`s (lines 762-785) continue to fire but drop the `visualGenerateApprovedAt` /
  `visualReviewApprovedAt` dependency and the manifest build; a visual seed whose gate fails is skipped
  silently (visual is decorative enrichment — a missing capability already degrades to text-only
  tutoring; the fail-closed text state is reserved for plan/respond where the student explicitly
  asked to talk).

**Single failing-closed UI helper** replaces the disclosure cards:
- `TutorModelEntryBlockedCard(title = if (consentEnabled) "需要先连接大模型" else "需在设置中开启模型智能体", action = 去设置)`.

### 2.3 DEAD vs KEEP inventory

#### `feature/tutor/.../TutorModelTaskPolicy.kt`
| Symbol | Verdict |
|---|---|
| `TutorCompositionEgressLease` + `approvedAtFor` + `grant` | **DEAD** — remove. |
| `coversCurrentTutorDisclosure` | DEAD for agent kinds (no manifest to compare). Lobby still uses its own manifest policy in `TutorLobbyModelTaskPolicy`, which is separate — **keep** that file untouched. |
| `requiresFreshTutorApproval` | DEAD — no per-task approval freshness; replaced by live consent+provider gate. |
| `matchesTutorProvider` | Still used for `recoverableRespondTask` / execution-matches gating — KEEP but simplify callers (it falls back to `snapshot.provider`; under consent there is no manifest, and `provider` on the snapshot already records who executed). |
| `rebuildTutorRequestAfterApproval` / `tutorRecoveryRequestId` | DEAD for agent kinds (no manifest rebuild). Keep only if lobby needs a manifest rebuild — lobby is out of scope; remove and rely on lobby's own policy. |
| `buildTutorPlanRequest` / `buildTutorRespondRequest` / `buildTutorVisualGenerateRequest` / `buildTutorVisualReviewRequest` | **KEEP but manifest becomes conditional**: external + agent kind → `egressManifest = null` + `agentConsentGranted = true`; everything else (incl. visual when provider not image-capable → callers gate earlier) unchanged. Lobby's `buildTutorLobbyRequest` untouched. |
| `TUTOR_*_PROMPT_POLICY_VERSION` consts | Still referenced by request-id hashing / policy versioning. KEEP (they keep binding the prompt scope even without a manifest; the validator still needs the policy version identity). |
| `visibleTutorContextMarkdown`, answer-exposure helpers, request-id builders | KEEP. |

#### `feature/tutor/.../TutorPlanCommands.kt`
- `TutorPlanSink.lease`, `clearLease` → remove.
- `tutorPlanExecuteCanStart` → **removed entirely**; the plan command now gates on the single
  `tutorAgentChatEnabled(provider, consentEnabled, TUTOR_PLAN)` (location + consent + capability
  owned in one place). In-memory `approvedAt` resolution (`tutorExternalPlanApprovedAt`,
  autoStart branch) → remove; `approvedAtEpochMillis` param on `buildTutorPlanRequest` becomes `0L`
  unused (or drop the param from agent path).
- `executeTurn` when gate false → **stop silently dropping**: surface the blocked-card state (the
  panel-level gate computed in §2.2 feeds the entry card; the command keeps its `hasExecutableProvider`
  style guard but the *UI* now owns the CTA).
- Pending `Plan` action gating (`awaitingResponseAuthorization` in the auto-continuation
  LaunchedEffect at panel 911-935) — see KEEP below; keep the continuation *dedup* but it no longer
  needs authorization to resume.

#### `feature/tutor/.../TutorRespondCommands.kt`
- `TutorRespondSink.lease`, `setForceResponseDisclosure`, `clearLease` → remove.
- `tutorRespondExternalApprovedAt` → remove.
- `tutorRespondCollectCanStart(...)` → the EXTERNAL consent branch now delegates to the single
  `tutorAgentChatEnabled(provider, consentEnabled, TUTOR_RESPOND)` gate (the old
  `respondApprovedAtEpochMillis` param is gone; the leftover args are the respond-specific
  local-recovery-envelope + chat-pending flags).
- `tutorRespondSendAdvance` / send state machine — **KEEP** (idempotency, budget, retry). But the
  `ConsentGranted` action step (see §2.4) becomes a plain "dispatch armed" step.

#### `feature/tutor/.../TutorVisualWorkCommands.kt`
- `TutorVisualWorkSink.generateApprovedAt` / `reviewApprovedAt` → remove; dispatch condition becomes
  `provider != null && tutorAgentChatEnabled(provider, consentEnabled, kind) && assets.isNotEmpty()`.
  `tutorVisualExistingDecision` (uses `coversCurrentTutorDisclosure`) → rewrite to dedup on matching
  input+provider (the function already keys on `request.input` equality; drop the disclosure/manifest
  arm, keep ExecuteExisting for an already-pending identical input). The `visualAgentEligible` wrapper
  (the EXTERNAL-vs-LOCAL split) was **deleted** — local now dispatches inside the single gate.

#### `feature/tutor/.../TutorSessionInteractionPolicy.kt`
- `tutorExternalPlanApprovedAt` → remove.
- `tutorRespondExternalApprovedAt` → remove.
- **`TutorSendPhase.ConsentGranted`** → see §2.4. The `ConsentGranted`/`ConsentRequired` actions and
  `AWAITING_CONSENT` phase are a *core-domain* model used by the send machine; with global consent the
  UI never transitions to AWAITING_CONSENT, but the phase enum is still a legal state for persisted
  rows. Decision: **keep the enum/actions for compatibility of the state machine and tests, but stop
  the UI from emitting `ConsentRequired`**; `tutorRespondSendAdvance` currently forces
  `StudentMessagePersisted → ConsentGranted` in one atomic advance (i.e. the send machine treats
  consent as pre-granted and the real gate is upstream). Cleanest: a new action
  `DispatchAuthorized` (or reuse `ConsentGranted` renamed) is emitted immediately because the *gate
  already passed before persisting*. Minimal-diff option: keep `ConsentGranted` as the name and add a
  comment that under global consent "granted" ≡ "agent gate passed". (Code-judo prefers the rename —
  see §6 — but the row/serialization is transient UI state, so a pure Kotlin rename in
  `TutorTurnSendStateMachine.kt` is safe.)

#### `feature/tutor/.../PendingTutorEgressState.kt`
The `PendingTutorEgressAction` tri-state is process-death *recovery* for "student already pressed
send but consent gate blocked us". Under global consent there is no consent-blocked intermediate for
agent kinds, so:
- `PendingTutorEgressAction.Plan` and `.NewResponse`/`.RetryResponse` are **only** created on the
  consent-blocked branch. Once dispatch no longer waits for a lease, those branches die. **However the
  file's real value — surviving process death for an in-flight send that persisted a message but was
  mid-dispatch — is still provided by the persisted `ModelTaskRequest` + the send state machine +
  `locallyStartedRespondRequestId` + `observeBySubject` rehydration.** So:
  - **DEAD**: `PendingTutorEgressAction.Plan/NewResponse/RetryResponse`, `awaitsResponseAuthorization`,
    the whole Saver, `pendingEgressState` rememberSaveable.
  - **KEEP the equivalent guarantee**: a persisted pending send (task in `WAITING_FOR_MODEL`/`QUEUED`
    matching current provider+consent) is re-dispatched by the existing `recoverableRespondTask`
    LaunchedEffect (panel 854-868) / plan `recoverableLocalPlanTask` logic, now gated only by the
    live agent gate. Drop `PendingTutorEgressState` and let those LaunchedEffects be the sole recovery
    path.
- `tutorRespondNewPendingAllowed` / `tutorRespondRetryPendingAllowed` → DEAD (their only purpose was to
  avoid double-storing while a consent prompt was up); the send machine's logicalOperationId
  dedup now guards double-dispatch.

#### `feature/tutor/.../TutorSessionPanel.kt` (composable)
- **DEAD**: `externalEgressLeaseState`/`externalEgressLease`, `grantExternalEgressLease`,
  the lease-drop LaunchedEffect, `forceResponseDisclosure`, `planLeaseApprovedAt`,
  `planFreshApprovalTask`, `responseFreshApprovalTask`/`responseNeedsFreshAuthorization`,
  `responseLeaseApprovedAt`, `responseDisclosureRequired`, `activeConversationApprovalAt`,
  `visualGenerateApprovedAt`/`visualReviewApprovedAt`, `respondAuthorized` derivation (replaced by the
  live `agentChatEnabled` gate), `matchingAutoStartAuthorization`/`consumeAutoStartAuthorization`,
  `pendingEgressState` + Saver, plan/respond disclosure `item(...)` blocks
  (`tutor_plan_recovery_disclosure`, `tutor_respond_disclosure`), the entry `TutorDisclosureCard`
  branch in the `observedTask == null` when-chain, the `autoStartAuthorization` LaunchedEffect that
  grants a lease on first composition, `clearLease`/lease wiring in every sink.
- **KEEP**: `provider` load + `providerLoadFailed`, the whole `TutorConversationFrame` timeline
  rendering, `buildTutorConversationProjection`, solution-exposure tracking,
  `interactionBusy`/`interactionError`, `chatSubmitPending` + `tutorSendState` +
  `TutorTurnSendStateMachine` send lifecycle, `chatDraft`, `locallyStartedRespondRequestId`,
  answer-exposure keys, visual-work seeds/resolve/report, `conversationEnabled`,
  recovery LaunchedEffects for local/external persisted tasks (now gated by the agent gate),
  `chatStartError` UI, retry affordances. The `onOpenModelSettings`/`onRequestSave`/save/end flows
  are untouched.

#### `feature/tutor/.../components/TutorSessionComponents.kt`
- **DEAD (definition only, no call sites)**: `TutorDisclosureCard` (testTag `captured_tutor_disclosure`)
  and `TutorRespondDisclosureCard` (testTag `tutor_respond_disclosure`) — the panels no longer render
  them, and the androidTest asserts *absence* of those tags. Remove both composables. (`TutorLobbyDisclosureCard`
  lives in `TutorLobbyRoute.kt` and is **KEEP** — lobby stays manifest-gated.)

#### ViewModels & routes
- `TutorSessionViewModel.kt` / `TutorViewModel.kt` — neither holds lease/consent state today
  (verified: no matches for approval/consent/lease in `TutorViewModel.kt`); the tutor model state is
  composition-local in `TutorSessionPanel`. **No ViewModel change required for the removal** — the
  consent store is read at the panel (Compose) layer, mirroring how `provider` is read there today.
  If we prefer it not be a composition-side `LaunchedEffect`-refresh, expose a tiny
  `agentConsentEnabled: StateFlow<Boolean>` via the root and pass it down; not required for
  correctness.
- `CapturedTutorSessionRoute.kt` / `SavedMistakeTutorRoute.kt` / `SmartMistakeBookRoot.kt`:
  - **DEAD**: `autoStartAuthorization` param, `onAutoStartAuthorizationConsumed`, the
    `freshTutorAutoStartAuthorization` remember state in the Root, and the Root plumbing from
    `onTutorSessionReady(sessionId, autoStartAuthorization)` (the capture route's
    `consumeTutorSession` callback can drop the second argument).
  - **KEEP**: navigation, save/end dialogs, source-image toggle, etc.
  - Capture side (`CaptureWorkflowEventCommands.consumeTutorSession`,
    `CaptureDraftLifecyclePolicy.captureTutorAutoStartAuthorization`) — the whole auto-start grant is
    obsolete once tutor first-plan runs under consent. Delete `captureTutorAutoStartAuthorization`,
    and have `consumeTutorSession` navigate without an authorization. (The capture flow's *own* per-photo
    consent chain for assess/parse — `CaptureSourceEgressIntent` / per-photo authorization state — is a
    separate workstream #24; this design only removes the tutor-side auto-start hop.)

### 2.4 Behavior of the send state machine after removal

The send machine (core/domain `TutorTurnSendStateMachine.kt`) is the durable idempotency/recovery
record: it counts dispatch attempts and survives `Composable` re-creation because the panel rehydrates
`tutorSendState` from task snapshots. Under global consent:

- `tutorRespondSendAdvance` still runs `StudentMessagePersisted` → (consent step) → `DISPATCHING`.
  The consent step should be a **no-op naming**: emit `ConsentGranted` immediately after persist, but
  the semantic is "authorized by the global agent gate", which was already true before persist (the
  composer is only visible when the gate is true). Do NOT send the request when the gate is false; the
  composer hides first.
- `RetryResponse` path: `retry()` currently requires an approvedAt lease for EXTERNAL; under consent
  the persisted task can be re-`collect`ed directly (gate checked once). This **simplifies `retry`**:
  remove the External/lease branch that parks a `PendingTutorEgressAction.RetryResponse`.
- Persisted tasks whose `provider` no longer matches the current configured+consented provider keep
  the existing fail-closed "模型配置已变化" → 去设置 surface (`requiresModelSettings()` +
  `matchesTutorProvider`).

---

## 3. Behavior matrix (tutor plan / respond / visual)

| # | Consent | Provider | PLAN | RESPOND | VISUAL_GENERATE/REVIEW | Fail-closed surface |
|---|---|---|---|---|---|---|
| 1 | ON | external image + structured, supports all four kinds | ✅ dispatch immediately | ✅ dispatch immediately | ✅ dispatch immediately (region crops egressible) | — |
| 2 | ON | external **structured-only** (no image) | ✅ dispatch (no image disclosed) | ✅ dispatch (no image disclosed) | ⛔ no dispatch (image required) | visual silently skipped (text-only tutoring); no modal |
| 3 | ON | **none configured** (`provider==null`/`UNAVAILABLE`) | ⛔ | ⛔ | ⛔ | entry card: "需要先连接大模型" + 去设置 |
| 4 | **OFF** | any external configured | ⛔ all sends | ⛔ | ⛔ | entry/composer area: "需在设置中开启模型智能体" + 去设置 (or "配置模型后自动开启" CTA); persisted-task failure path already routes via `requiresModelSettings` |
| 5 | OFF/ON | local provider (`LOCAL_NO_EGRESS`) — production has none today; fakes/tests only | ✅ local-only run (no consent needed; no egress) | ✅ | ✅(local) | — |

Legend: ✅ dispatch (WeChat-style, no per-message card) · ⛔ blocked.

Key cells:
- Cell 1 **removes** every plan/respond disclosure card and the respond lease; the send, hint button,
  continue/next-move, reveal-solution, and auto-visual all fire immediately.
- Cell 2 keeps text tutoring alive under a structured-only provider — the entire point of decision #3.
- Cell 4 is the fail-closed settings CTA (never a silent drop). Since the store's default is OFF and
  today the app wires `captureConsentGranted = { modelConfigurationStore != null }` (consent implied by
  configuring), shipping this design **without** a real toggle would turn the app effectively OFF for
  everyone until the Capability screen switch is added — hence §5 ordering puts the store + switch in
  the same land as the tutor removal (or immediately before), never after.

Persisted-task recovery under the matrix: an existing pending/`RETRYABLE_FAILURE` tutor task rehydrates
and re-dispatches iff the live cell for its kind is ✅. If the model was cleared or consent turned OFF,
the recovery LaunchedEffects do not fire and the UI shows the settings CTA via the task's failure code
(`requiresModelSettings`) — matching today's behavior when a provider disappears.

---

## 4. Test impact

### 4.1 feature/tutor tests (search `feature/tutor/src/test` + `src/androidTest`)

| File | Assertions today (lease/approval/autoStart) | Verdict |
|---|---|---|
| `TutorModelTaskPolicyTest.kt` | tests `TutorCompositionEgressLease.grant/approvedAtFor`, `coversCurrentTutorDisclosure`, `requiresFreshTutorApproval`, `rebuildTutorRequestAfterApproval`, manifest-disclosure matching, `build*Request` with manifests | **Rewrite**: lease/disclosure/fresh-approval/rebuild tests → delete. Keep/repoint request-builder tests at `agentConsentGranted=true` + `egressManifest=null` for external-agent kinds; keep manifest assertions for **lobby** and for non-agent kinds. Add `buildTutorPlanRequest` (external, structured-only provider) has no image manifest but still sets consent. |
| `TutorVisualModelTaskPolicyTest.kt` | builds visual requests with manifests incl. `selectedRegion` grants, `coversCurrentTutorDisclosure` arms | **Rewrite**: manifest/lease assertions → assert `agentConsentGranted` + region passed in input; region **no longer crops-blocked** — replace the old "region throws" expectations (if any) with "region egresses under consent". |
| `TutorSessionInteractionPolicyTest.kt` | `tutorRespondExternalApprovedAt`, `tutorExternalPlanApprovedAt`, `tutorRespondSendAdvance` ConsentGranted phases, `tutorMoveCanStart(awaitingAuthorization=…)` | **Rewrite**: drop approved-at derivations; keep `tutorRespondSendAdvance` state-machine assertions (rewrite as "gate passed ⇒ dispatch"), keep `tutorMoveCanStart` with `awaitingAuthorization` removed. |
| `TutorViewModelTest.kt` (test/kotlin) | fakes `readTutorVisualSourceAssets` (interface surface unchanged) | Mostly **keep**; only rebuild if send-gate moved into a ViewModel (it stays in the panel). |
| `CapturedTutorSessionUiPolicyTest.kt` | `autoStartAuthorization` matching, entry disclosure card states, lease-based first-plan launch | **Rewrite**: remove autoStart/disclosure-approval states; assert immediate plan dispatch when gate ✅ and blocked-card (去设置) when not. |
| `SavedMistakeTutorAnchorTest.kt`, `TutorChatConversationTest.kt`, `TutorConversationTimelineTest.kt`, `TutorVisualPipelineTest.kt`, `TutorLobbyModelTaskPolicyTest.kt`, `TutorSessionViewModelTest.kt`, `TutorSessionViewModelFactoryTest`(if any) | mostly conversation/timeline/lobby-model policy | **Keep** (lobby policy untouched; timeline/chat unaffected). `TutorLobbyModelTaskPolicyTest` stays green unchanged. |
| androidTest: `CapturedTutorSessionInstrumentedTest`, `TutorLocalIntentPanelInstrumentedTest`, `TutorMotionSceneInstrumentedTest`, `TutorSpatialDiagramInstrumentedTest`, `TutorVisualProgramInstrumentedTest`, `TutorHistoryInstrumentedTest` | Compose tests that walk the consent card / respond-disclosure approval to reach composer/visual | **Rewrite the consent steps** (delete `captured_tutor_disclosure` / `tutor_respond_disclosure` taps, assert composer appears when consent ON); keep visual/motion assertions that exercise the actual visual payloads. |

### 4.2 core + capture tests

- `core/model/src/test/.../ModelEgressTest.kt` (only core file referencing consent symbols) +
  `ModelTasksTest.kt`:
  - Add v7→v8 decode-shim cases (old key + `schemaVersion:7` decodes; old key + `schemaVersion:8` is
    rejected). Update fingerprint vectors for the renamed key. `agentConsentMatches` split tests:
    structured-only provider ✅ for PLAN/RESPOND, ❌ for VISUAL kinds; consent-off ❌ always.
  - Keep manifest-path tests for lobby/organization/visual-without-consent.
- `core/data` instrumented/unit:
  - `RoomModelTaskRepository` dispatch treats `ProviderConsented` like External already (verified
    RoomModelTaskRepository.kt:170-172). Add a tutor-visual-under-consent case asserting the 
    *asset source* now crops region-scoped assets instead of throwing.
  - New `AndroidRestrictedModelAssetSource` test: External manifest with `selectedRegion` grant →
    cropped bytes returned (replace the previous throw-test); ProviderConsented tutor-visual request →
    region-scoped crop; region mismatch → SecurityException.
- `CaptureDraftLifecyclePolicyTest.kt` `tutorAutoStartRequiresAFreshMatchingExternalApproval` /
  `…Grants…`: the whole `captureTutorAutoStartAuthorization` fn is deleted → **delete these tests**;
  the capture-consent tests for assess/parse stay (different workstream #24).
- `TutorTurnSendStateMachineTest.kt` (core/domain): if the consent action is renamed to
  `DispatchAuthorized`/consent semantics documented, update action names only; state-transition
  assertions (budget, dedup, restart) unchanged.

---

## 5. Ordered steps (each ends in a compile/test gate)

> All eight steps below are **implemented** on this branch (schema `74bf198` → capture/tutor removal
> `9c7b0a7`/`3a5f8fe` → refinements `dc2f77a`/`4300e5b`). The remaining open item is §2.3
> `TutorSessionComponents.kt`: delete the two dead disclosure-card composables (definition-only, no
> call sites). Everything else in this section is a completed audit trail.

> Gate commands (per module, Gradle from worktree root):
> `./gradlew :core:model:testDebugUnitTest`, `:core:domain:testDebugUnitTest`,
> `:core:data:testDebugUnitTest`, `:feature:tutor:testDebugUnitTest`,
> `:feature:capture:testDebugUnitTest`, `:app:compileDebugKotlin` (+ relevant
> `connectedDebugAndroidTest` where a device is available). **Schema lands first** because persisted
> rows otherwise corrupt on decode.

1. **Schema v8 + decode/fingerprint shim, core/model only.**
   - Rename `isCapturePipelineKind`→`isAgentConsentEligible`, `captureEgressConsentGranted`→
     `agentConsentGranted`, `CAPTURE_CONSENT_SCHEMA_VERSION`→`AGENT_CONSENT_SCHEMA_VERSION=8`,
     update init guards. Add the fingerprint strip for `<8` old-key rows and the transient decode shim.
   - Generalize `ModelEgressPolicy`: `agentConsentMatches` (with the `supportsImageInput` split),
     `authorize` ProviderConsented branch for agent kinds, revalidate.
   - Gate: `:core:model` unit tests incl. new shim/fingerprint vectors. **Do not proceed until green**
     (a red here means persisted rows will corrupt).
2. **Gateway + asset source (core/data), no feature code yet.**
   - `isReadyForNetwork`/`requireImageRequestFits` rename + generalized consent + shared
     `requiresImageInput` predicate.
   - `AndroidRestrictedModelAssetSource`: External-region-crop stream (vault `crop`) and
     ProviderConsented generalization to tutor-visual (region-aware).
   - Gate: `:core:data` unit + instrumented (crop + mismatch cases).
3. **Consent store + Application wiring + Capability switch.**
   - Add `ModelAgentConsentStore` interface + DataStore impl in core; expose on Application; feed
     `CaptureWorkflowRepositoryFactory.create(captureConsentGranted=…)` and the tutor gate; add the
     Switch to `CapabilityScreen`.
   - Gate: `:app:compileDebugKotlin` + app tests; manual Settings toggle sanity.
4. **Remove tutor auto-start + capture auto-start hop (feature/capture + feature/tutor glue).**
   - Delete `captureTutorAutoStartAuthorization`; simplify `consumeTutorSession`; drop
     `autoStartAuthorization` params/plumbing through Root/routes.
   - Gate: `:feature:capture` + `:app` compile; update `CaptureDraftLifecyclePolicyTest`.
5. **Tutor policy/commands: DEAD removal (feature/tutor).**
   - Remove `TutorCompositionEgressLease`, lease sink fields, `coversCurrentTutorDisclosure`,
     `requiresFreshTutorApproval`, `rebuildTutorRequestAfterApproval`/`tutorRecoveryRequestId`;
     make agent-kind builders emit `agentConsentGranted=true` + `egressManifest=null`; single gate
     `tutorAgentChatEnabled(provider, consentEnabled, kind)` in `TutorSessionInteractionPolicy.kt`.
   - Gate: `:feature:tutor:testDebugUnitTest` — this step intentionally breaks many tutor tests; pair
     with §7 test rewrite before declaring green.
6. **Tutor panel UI removal (TutorSessionPanel + components).**
   - Delete lease/disclosure/composer-gating state and the two disclosure `item` blocks; route all
     gate-false surfaces to the blocked-settings card; simplify recovery LaunchedEffects; drop
     `PendingTutorEgressState` + Saver; keep send machine (`tutorSendState`) + idempotency +
     `locallyStartedRespondRequestId`.
   - Gate: `:feature:tutor` unit + androidTest (compose) — rewrite consent-step tests here.
7. **Send-state-machine naming (core/domain) + full-suite sweep.**
   - If renaming `ConsentGranted`→`DispatchAuthorized`, do it in `TutorTurnSendStateMachine.kt` +
     tests; otherwise leave names and document "granted ≡ agent gate". Run the full gate set incl.
     `:core:data`/`:feature:capture`/`:feature:tutor`/`:app`; reconcile behavior matrix cells with
     new tests (esp. cell 4 OFF → settings CTA, cell 2 structured-only).
8. **Regression + user-perspective pass (per memory: test from user perspective).**
   - Fresh capture → tutor auto-starts plan without any card; type a message → no consent card, reply
     streams; region-crop visual appears without a crop-block; toggle consent OFF → composer replaced
     by 去设置 state; process-death mid-send → recovered and re-dispatched on resume.

---

## 6. Risks + code-judo

1. **Schema land first or you corrupt rows.** The rename is a decode-level break under
   `ignoreUnknownKeys=false`. Step 1 is non-negotiable and must be green before anything touches
   persisted `ModelTaskRequest` rows.
2. **Consent OFF default turns the tutor off.** Today "configured ⇒ consented" is implicit. If the new
   store defaults OFF and the Capability switch lands after the tutor removal, every existing
   configured user silently loses tutoring. Mitigation: either default the store to ON when a
   compatible model is configured (carry forward the current semantic, the Switch lets users turn it
   off), or ship steps 3+4 in the same release train. Recommended: **default = "enabled when a model
   is configured"**, i.e. the store starts ON and clearing the model / toggling OFF disables it.
3. **Crops: the current `require(grant.selectedRegion == null)` is a hard block, and it is in the
   asset source, not the manifest builder.** The design replaces it with the vault `crop` stream —
   the same code path the capture split already trusts. The risk is subtle (region padding vs the
   evidence bounding box; byte-budget after crop). Keep the crop within `toPaddedPixelBounds` and
   enforce `MODEL_EGRESS_MAX_ASSET_BYTES`/request budget on the **cropped** bytes, and re-check the
   record hash/dims (not the crop output) against the grant.
4. **`matchesTutorProvider` fallback semantics.** Under consent, snapshots carry no manifest, so
   provider-match relies on `snapshot.provider`. Persisted pre-change tasks that carry a manifest will
   still match when the manifest's provider==current. Both arms survive; tests must cover the
   manifest-less arm.
5. **Visual is decorative — don't over-gate it.** A missing visual capability degrades to text-only;
   the fail-closed CTA is for plan/respond. Do not show a modal when a visual seed can't run.
6. **Fingerprint stability is the sharpest knife.** Renaming the field changes the JSON payload of
   every request. The strip helper must be byte-identical for old rows or every legacy task fails its
   own `require(requestFingerprint == of(request))` on rehydration. Mirror `withoutEmptyToolCarrier`
   (ModelTasks.kt:1286) exactly, including key ORDER (`,"captureEgressConsentGranted":false`).
7. **Code-judo on per-kind approvedAt:** once consent is global, "which kind approved" collapses to
   "provider configured + capable + consent ON" — there is **no per-kind state to persist**. The
   20-ish `*ApprovedAt` derivations, the lease data class, the 15-min TTL re-check, and the
   fresh-approval task all reduce to one live boolean per kind. If a reviewer pushes back on deleting
   the lease, the single strongest justification is: the manifest exists to *prove an exact
   disclosure to an exact provider at an exact time*; `ProviderConsented` exists precisely to replace
   that proof with the Settings toggle, and today it is only wired for capture kinds. We are deleting
   the tutor half of a mechanism the capture half already abandoned. The TTL/freshness job that the
   lease used to do is owned by the *revalidate* step (`requireCurrentExternalAuthorization` → live
   `agentConsentMatches`) at transport time, which never goes away.
8. **Lobby intentionally keeps a manifest.** Don't let the sweep delete lobby's disclosure card; its
   bounded notebook/library route has no photo/evidence and no session. Keep `TutorLobbyModelTaskPolicy`
   and its tests byte-identical.
9. **Consent-OFF-after-persist is blocked at the panel gate (ratified).** `requiresModelSettings()`
   routes `MODEL_NOT_CONFIGURED`/`PROVIDER_CAPABILITY_MISSING`/`AUTHENTICATION_FAILED` → 去设置. For a
   persisted agent task whose consent was ON at persist but OFF now, the **panel gate** is the effective
   blocker: `respondAgentAuthorized = tutorAgentChatEnabled(currentProvider, consentOn, TUTOR_RESPOND)`
   reads the *live* `consentOn`, so `recoverableRespondTask` / the plan auto-exec gate do not re-dispatch
   it, and the entry area shows the consent-CONfigure CTA. The gateway's `EGRESS_AUTHORIZATION_*` codes
   (`_REQUIRED`→`EGRESS_CONSENT_REQUIRED`, `_INVALID`→`EGRESS_LEASE_EXPIRED` in AppFailure.kt) are the
   **manifest-path** revalidation surface (lobby / non-agent kinds), not the agent consent-OFF path.

### Unverified / assumptions
- The exact `CapabilityScreen` insertion point for the consent Switch and its gray-text copy are
  design-level (I did not confirm the screen's layout slots for a switch row beyond the presence of
  `Switch` usage in sibling screens).
- Whether a legacy persisted v7 row actually exists in the field is unverified (schema 7 is new in
  this branch); the decode shim is cheap insurance regardless and is required for the rename to be
  sound.
- The `TutorTurnSendStateMachine` consent-action rename is a judgment call (optional); no persisted
  encoding depends on `TutorSendPhase`, so either choice is safe.
