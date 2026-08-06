# Tutor learning-evidence session v44 proposal

Status: implemented after the reviewed v43 schema was frozen and exported.

## Purpose

The legacy `tutor_evidence_request.terminal_source_fact_id` identifies a fact in
`smart-mistake-book.db`. It must remain legacy-read compatibility and must not be reinterpreted as
a learner-mastery receipt.

v44 adds one session-coordination table dedicated to the recoverable sequence:

1. persist a scoped finalization intent;
2. idempotently commit the semantic candidate through the learner-mastery owner;
3. persist the opaque learner-mastery receipt acknowledgement.

There is no cross-database transaction. Replay completes the next missing step.

## Minimal table

`tutor_learning_evidence_finalization_receipt`

| Field | Constraint / meaning |
|---|---|
| `evidence_request_id` | Primary key and foreign key to the session-owned evidence request |
| `learner_id` | Required; exact bound learner scope |
| `conversation_id` | Required |
| `conversation_generation` | Positive |
| `conversation_state_version` | Non-negative |
| `turn_receipt_id` | Required |
| `turn_ordinal` | Positive |
| `subject` | Required specific subject |
| `session_anchor_id` | Required opaque session binding |
| `evidence_kind` | Required typed evidence kind |
| `request_version` | Non-negative |
| `mode_version` | Non-negative |
| `idempotency_key` | Required opaque key |
| `candidate_fingerprint` | Required normalized SHA-256; no candidate payload is stored |
| `state` | `PENDING_MASTERY` or `MASTERY_ACKNOWLEDGED` |
| `mastery_receipt_id` | Nullable only while pending; opaque owner-issued reference |
| `mastery_receipt_fingerprint` | Nullable only while pending; owner-issued SHA-256 |
| `state_version` | `0` for intent, `1` after acknowledgement |
| `intent_created_at_epoch_millis` | Required |
| `acknowledged_at_epoch_millis` | Nullable only while pending |

Required uniqueness:

- exact evidence request primary key;
- `(learner_id, conversation_id, conversation_generation, idempotency_key)`.

The table must not contain `LearningObservationSourceFact`, answer text, response summaries,
knowledge-node attribution, mastery weights, projections, SQL supplied by callers, or
student-mistake identities.

## DAO contract

`begin` is one local session-store transaction:

- insert a `PENDING_MASTERY` intent when absent;
- return the existing row only when every scope/binding/idempotency/fingerprint field is exact;
- reject changed, late, cross-learner, or cross-conversation payloads;
- return an acknowledged row without reopening it.

`acknowledge` is one compare-and-set:

- match the exact pending row, state version, and candidate fingerprint;
- set only the opaque mastery receipt id/fingerprint, acknowledged time, state, and state version;
- exact replay returns the existing receipt;
- a different receipt or fingerprint conflicts.

The normal production API must not expose the old fact-writing finalizer. Legacy fact and anchor
tables remain readable only through migration compatibility.

## Recovery acceptance cases

- crash after intent: replay commits mastery once and acknowledges once;
- crash after mastery commit: owner replay returns the same durable receipt, then session ack wins;
- crash after session ack: replay returns the terminal receipt without another owner call;
- changed late payload conflicts;
- the same request id in another learner or conversation cannot observe or acknowledge the receipt;
- cancellation, browsing, guidance disable, and mode switches never create an intent candidate.
