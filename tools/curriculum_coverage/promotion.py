"""Human-review gate for promoting decomposition proposals into reviewed scope."""

from __future__ import annotations

from typing import Any

from .model import require_keys
from .proposals import validate_decomposition_proposals


DECISION_ARTIFACT_KEYS = {
    "schemaVersion",
    "artifactId",
    "sourceProposalArtifactId",
    "reviewState",
    "reviewer",
    "reviewedAtEpochMillis",
    "decisions",
}
DECISION_KEYS = {"candidateId", "decision"}
REVIEWER_KEYS = {"reviewerId", "displayName", "role", "attestation"}
ALLOWED_REVIEW_STATES = {"NOT_STARTED", "IN_PROGRESS", "COMPLETED"}
ALLOWED_DECISIONS = {"APPROVE", "REJECT"}
REVIEW_ATTESTATION = (
    "I_REVIEWED_EACH_DECISION_AGAINST_THE_CAPTURED_CURRICULUM_SOURCE"
)


def _candidate_index(proposals: dict[str, Any]) -> dict[str, dict[str, Any]]:
    result: dict[str, dict[str, Any]] = {}
    for subject_entry in proposals["subjects"]:
        subject = str(subject_entry["subject"])
        for module in subject_entry["modules"]:
            module_slug = str(module["slug"])
            for candidate in module["candidates"]:
                candidate_id = str(candidate["candidateId"])
                result[candidate_id] = {
                    "subject": subject,
                    "moduleSlug": module_slug,
                    **candidate,
                }
    return result


def _validate_reviewer(reviewer: Any) -> dict[str, Any]:
    if not isinstance(reviewer, dict):
        raise ValueError("Completed or in-progress review requires a reviewer object")
    require_keys(reviewer, REVIEWER_KEYS, "curriculum reviewer")
    for key in ("reviewerId", "displayName", "role"):
        if not str(reviewer[key]).strip():
            raise ValueError(f"Curriculum reviewer {key} must not be empty")
    if reviewer["attestation"] != REVIEW_ATTESTATION:
        raise ValueError("Curriculum reviewer attestation is missing or invalid")
    return reviewer


def _decision_index(
    raw_decisions: Any,
    candidates: dict[str, dict[str, Any]],
) -> dict[str, str]:
    if not isinstance(raw_decisions, list):
        raise ValueError("Curriculum review decisions must be a list")
    result: dict[str, str] = {}
    for entry in raw_decisions:
        if not isinstance(entry, dict):
            raise ValueError("Curriculum review decision entries must be objects")
        require_keys(entry, DECISION_KEYS, "curriculum review decision")
        candidate_id = str(entry["candidateId"])
        if candidate_id not in candidates:
            raise ValueError(f"Unknown curriculum proposal candidate: {candidate_id}")
        if candidate_id in result:
            raise ValueError(f"Duplicate curriculum review decision: {candidate_id}")
        decision = str(entry["decision"])
        if decision not in ALLOWED_DECISIONS:
            raise ValueError(f"Unsupported curriculum review decision: {decision}")
        result[candidate_id] = decision
    return result


def _validate_review_state(
    review_state: str,
    *,
    reviewer: Any,
    reviewed_at: Any,
    decision_index: dict[str, str],
    candidates: dict[str, dict[str, Any]],
) -> None:
    if review_state == "NOT_STARTED":
        _validate_not_started_review(reviewer, reviewed_at, decision_index)
        return
    if review_state == "IN_PROGRESS":
        _validate_in_progress_review(reviewer, reviewed_at)
        return
    _validate_completed_review(reviewer, reviewed_at, decision_index, candidates)


def _validate_not_started_review(
    reviewer: Any,
    reviewed_at: Any,
    decision_index: dict[str, str],
) -> None:
    if reviewer is not None or reviewed_at is not None or decision_index:
        raise ValueError("NOT_STARTED review must not contain reviewer or decisions")


def _validate_in_progress_review(reviewer: Any, reviewed_at: Any) -> None:
    _validate_reviewer(reviewer)
    if reviewed_at is not None:
        raise ValueError("IN_PROGRESS review must not have a completion timestamp")


def _validate_completed_review(
    reviewer: Any,
    reviewed_at: Any,
    decision_index: dict[str, str],
    candidates: dict[str, dict[str, Any]],
) -> None:
    _validate_reviewer(reviewer)
    if not isinstance(reviewed_at, int) or reviewed_at <= 0:
        raise ValueError("COMPLETED review requires reviewedAtEpochMillis")
    missing_count = len(candidates.keys() - decision_index.keys())
    if missing_count:
        raise ValueError(
            f"COMPLETED review is missing decisions for {missing_count} candidates"
        )


def _promotion_status(
    decisions: dict[str, Any],
    proposals: dict[str, Any],
    candidate_count: int,
    decision_index: dict[str, str],
) -> dict[str, Any]:
    approved_count = sum(
        1 for decision in decision_index.values() if decision == "APPROVE"
    )
    rejected_count = sum(
        1 for decision in decision_index.values() if decision == "REJECT"
    )
    pending_count = candidate_count - len(decision_index)
    review_state = str(decisions["reviewState"])
    return {
        "decisionArtifactId": decisions["artifactId"],
        "sourceProposalArtifactId": proposals["artifactId"],
        "reviewState": review_state,
        "candidateCount": candidate_count,
        "approvedCount": approved_count,
        "rejectedCount": rejected_count,
        "pendingCount": pending_count,
        "promotionReady": review_state == "COMPLETED" and pending_count == 0,
        "automaticLedgerMutationAllowed": False,
    }


def review_promotion_status(
    evidence: dict[str, Any],
    proposals: dict[str, Any],
    decisions: dict[str, Any],
) -> tuple[dict[str, Any], dict[str, dict[str, Any]], dict[str, str]]:
    proposal_summary = validate_decomposition_proposals(proposals, evidence)
    require_keys(decisions, DECISION_ARTIFACT_KEYS, "curriculum review decisions")
    if decisions["schemaVersion"] != 1:
        raise ValueError("Only curriculum review decision schemaVersion 1 is supported")
    if (
        decisions["artifactId"]
        != "curriculum-warning-human-review-decisions-2025-v1"
    ):
        raise ValueError("Unexpected curriculum review decision artifact")
    if decisions["sourceProposalArtifactId"] != proposals["artifactId"]:
        raise ValueError("Curriculum review decisions reference different proposals")
    review_state = str(decisions["reviewState"])
    if review_state not in ALLOWED_REVIEW_STATES:
        raise ValueError(f"Unsupported curriculum review state: {review_state}")

    candidates = _candidate_index(proposals)
    decision_index = _decision_index(decisions["decisions"], candidates)
    reviewer = decisions["reviewer"]
    reviewed_at = decisions["reviewedAtEpochMillis"]
    _validate_review_state(
        review_state,
        reviewer=reviewer,
        reviewed_at=reviewed_at,
        decision_index=decision_index,
        candidates=candidates,
    )
    status = _promotion_status(
        decisions,
        proposals,
        proposal_summary["candidateCount"],
        decision_index,
    )
    return status, candidates, decision_index


def build_reviewed_scope(
    evidence: dict[str, Any],
    proposals: dict[str, Any],
    decisions: dict[str, Any],
) -> tuple[dict[str, Any], dict[str, Any]]:
    status, candidates, decision_index = review_promotion_status(
        evidence,
        proposals,
        decisions,
    )
    if not status["promotionReady"]:
        raise ValueError(
            "Curriculum review is not complete; reviewed scope cannot be emitted"
        )

    approved_points = [
        {
            "candidateId": candidate_id,
            "subject": candidate["subject"],
            "moduleSlug": candidate["moduleSlug"],
            "displayName": candidate["displayName"],
            "kind": candidate["kind"],
            "evidencePhrase": candidate["evidencePhrase"],
        }
        for candidate_id, candidate in sorted(candidates.items())
        if decision_index[candidate_id] == "APPROVE"
    ]
    artifact = {
        "schemaVersion": 1,
        "artifactId": "curriculum-warning-reviewed-scope-2025-v1",
        "sourceEvidenceArtifactId": evidence["artifactId"],
        "sourceProposalArtifactId": proposals["artifactId"],
        "reviewDecisionArtifactId": decisions["artifactId"],
        "targetBaselineId": proposals["targetBaselineId"],
        "reviewState": "HUMAN_REVIEWED",
        "reviewer": decisions["reviewer"],
        "reviewedAtEpochMillis": decisions["reviewedAtEpochMillis"],
        "automaticLedgerMutationAllowed": False,
        "eligibleForFormalLedgerMerge": True,
        "approvedPoints": approved_points,
    }
    summary = {
        **status,
        "reviewedScopeArtifactId": artifact["artifactId"],
        "reviewedPointCount": len(approved_points),
        "eligibleForFormalLedgerMerge": True,
    }
    return artifact, summary
