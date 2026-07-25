"""Validate AI-authored decomposition proposals against captured source evidence."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any, cast

from .model import marker_key, require_keys


PROPOSAL_KEYS = {
    "schemaVersion",
    "artifactId",
    "sourceEvidenceArtifactId",
    "targetBaselineId",
    "proposalState",
    "reviewBoundary",
    "knowledgeBaseBoundary",
    "subjects",
}
REVIEW_BOUNDARY_KEYS = {
    "humanReviewRequired",
    "promotionAllowed",
    "formalLedgerMutationAllowed",
    "formalCoverageContribution",
}
SUBJECT_KEYS = {"subject", "modules"}
MODULE_KEYS = {
    "slug",
    "sourceTextSha256",
    "proposalState",
    "reviewDecision",
    "candidates",
}
CANDIDATE_KEYS = {
    "candidateId",
    "displayName",
    "kind",
    "evidencePhrase",
}
ALLOWED_KINDS = {
    "CONCEPT",
    "PRINCIPLE",
    "PROCESS",
    "METHOD",
    "SKILL",
    "RELATIONSHIP",
    "APPLICATION_SCOPE",
}


@dataclass(slots=True)
class ProposalValidationContext:
    evidence_modules: dict[tuple[str, str], dict[str, Any]]
    seen_modules: set[tuple[str, str]]
    seen_candidate_ids: set[str]


def _evidence_modules(
    evidence: dict[str, Any],
) -> dict[tuple[str, str], dict[str, Any]]:
    if evidence.get("schemaVersion") != 1:
        raise ValueError("Only warning evidence schemaVersion 1 is supported")
    if evidence.get("artifactId") != "curriculum-warning-source-evidence-2025-v1":
        raise ValueError("Unexpected warning evidence artifact")
    review_boundary = evidence.get("reviewBoundary")
    if not isinstance(review_boundary, dict):
        raise ValueError("Warning evidence reviewBoundary must be an object")
    if review_boundary.get("state") != "SOURCE_EVIDENCE_ONLY":
        raise ValueError("Warning evidence must remain SOURCE_EVIDENCE_ONLY")
    if review_boundary.get("formalLedgerMutationAllowed") is not False:
        raise ValueError("Warning evidence must not mutate the formal ledger")

    result: dict[tuple[str, str], dict[str, Any]] = {}
    subjects = evidence.get("subjects")
    if not isinstance(subjects, list):
        raise ValueError("Warning evidence subjects must be a list")
    for subject_entry in subjects:
        if not isinstance(subject_entry, dict):
            raise ValueError("Warning evidence subject entries must be objects")
        subject = str(subject_entry.get("subject", ""))
        modules = subject_entry.get("modules")
        if not isinstance(modules, list):
            raise ValueError(f"Warning evidence modules must be a list: {subject}")
        for module in modules:
            if not isinstance(module, dict):
                raise ValueError(
                    f"Warning evidence module entries must be objects: {subject}"
                )
            slug = str(module.get("slug", ""))
            key = (subject, slug)
            if key in result:
                raise ValueError(f"Duplicate warning evidence module: {subject}/{slug}")
            if module.get("promotionAllowed") is not False:
                raise ValueError(
                    f"Warning evidence module permits promotion: {subject}/{slug}"
                )
            result[key] = module
    return result


def _validate_review_boundary(boundary: Any) -> None:
    if not isinstance(boundary, dict):
        raise ValueError("Proposal reviewBoundary must be an object")
    require_keys(boundary, REVIEW_BOUNDARY_KEYS, "proposal reviewBoundary")
    expected = {
        "humanReviewRequired": True,
        "promotionAllowed": False,
        "formalLedgerMutationAllowed": False,
        "formalCoverageContribution": 0,
    }
    for key, value in expected.items():
        if boundary[key] != value:
            raise ValueError(f"Proposal reviewBoundary has unsafe {key}")


def _validate_candidate(
    candidate: Any,
    *,
    subject: str,
    module_slug: str,
    source_text_key: str,
    context: ProposalValidationContext,
) -> None:
    if not isinstance(candidate, dict):
        raise ValueError(f"Proposal candidates must be objects: {subject}/{module_slug}")
    require_keys(
        candidate,
        CANDIDATE_KEYS,
        f"proposal candidate {subject}/{module_slug}",
    )
    candidate_id = _validate_candidate_identity(
        candidate,
        subject=subject,
        module_slug=module_slug,
        seen_candidate_ids=context.seen_candidate_ids,
    )
    _validate_candidate_content(candidate, candidate_id, source_text_key)


def _validate_candidate_identity(
    candidate: dict[str, Any],
    *,
    subject: str,
    module_slug: str,
    seen_candidate_ids: set[str],
) -> str:
    candidate_id = str(candidate["candidateId"])
    expected_prefix = f"{subject.lower()}:{module_slug}:"
    if not candidate_id.startswith(expected_prefix):
        raise ValueError(f"Proposal candidateId has wrong scope: {candidate_id}")
    if candidate_id in seen_candidate_ids:
        raise ValueError(f"Duplicate proposal candidateId: {candidate_id}")
    seen_candidate_ids.add(candidate_id)
    return candidate_id


def _validate_candidate_content(
    candidate: dict[str, Any],
    candidate_id: str,
    source_text_key: str,
) -> None:
    display_name = str(candidate["displayName"]).strip()
    if not display_name:
        raise ValueError(f"Proposal displayName is empty: {candidate_id}")
    if "原子" in display_name:
        raise ValueError(f"Internal terminology leaked into displayName: {candidate_id}")
    if candidate["kind"] not in ALLOWED_KINDS:
        raise ValueError(f"Unsupported proposal kind: {candidate_id}")

    evidence_phrase = str(candidate["evidencePhrase"]).strip()
    evidence_key = marker_key(evidence_phrase)
    if len(evidence_key) < 2:
        raise ValueError(f"Proposal evidencePhrase is too short: {candidate_id}")
    if evidence_key not in source_text_key:
        raise ValueError(
            f"Proposal evidencePhrase is absent from source: {candidate_id}"
        )


def _validate_proposal_header(
    proposals: dict[str, Any],
    evidence: dict[str, Any],
) -> None:
    require_keys(proposals, PROPOSAL_KEYS, "decomposition proposal artifact")
    if proposals["schemaVersion"] != 1:
        raise ValueError("Only decomposition proposal schemaVersion 1 is supported")
    if (
        proposals["artifactId"]
        != "curriculum-warning-decomposition-proposals-2025-v1"
    ):
        raise ValueError("Unexpected decomposition proposal artifact")
    if proposals["sourceEvidenceArtifactId"] != evidence.get("artifactId"):
        raise ValueError("Proposal artifact references different source evidence")
    if proposals["targetBaselineId"] != evidence.get("targetBaselineId"):
        raise ValueError("Proposal artifact targets a different curriculum baseline")
    if proposals["proposalState"] != "AI_DRAFT_REQUIRES_HUMAN_REVIEW":
        raise ValueError("Proposal artifact must remain an unreviewed AI draft")
    _validate_review_boundary(proposals["reviewBoundary"])
    if proposals["knowledgeBaseBoundary"] != evidence.get("knowledgeBaseBoundary"):
        raise ValueError("Proposal knowledge-base boundary differs from source evidence")


def _source_module_for_proposal(
    module: Any,
    *,
    subject: str,
    context: ProposalValidationContext,
) -> tuple[str, dict[str, Any]]:
    if not isinstance(module, dict):
        raise ValueError(f"Proposal modules must be objects: {subject}")
    require_keys(module, MODULE_KEYS, f"proposal module {subject}")
    slug = str(module["slug"])
    key = (subject, slug)
    if key in context.seen_modules:
        raise ValueError(f"Duplicate proposal module: {subject}/{slug}")
    context.seen_modules.add(key)
    source_module = context.evidence_modules.get(key)
    if source_module is None:
        raise ValueError(f"Proposal module has no source evidence: {subject}/{slug}")
    if module["sourceTextSha256"] != source_module.get("sourceTextSha256"):
        raise ValueError(f"Proposal source hash is stale: {subject}/{slug}")
    return slug, source_module


def _module_candidates(
    module: dict[str, Any],
    *,
    subject: str,
    slug: str,
) -> list[Any]:
    if module["proposalState"] != "AI_DRAFT_REQUIRES_HUMAN_REVIEW":
        raise ValueError(f"Proposal module is not an AI draft: {subject}/{slug}")
    if module["reviewDecision"] != "PENDING_HUMAN_REVIEW":
        raise ValueError(
            f"Proposal module has an unverified review decision: {subject}/{slug}"
        )
    candidates = module["candidates"]
    if not isinstance(candidates, list) or len(candidates) < 2:
        raise ValueError(
            f"Proposal module needs at least two candidates: {subject}/{slug}"
        )
    return candidates


def _validate_proposal_module(
    module: Any,
    *,
    subject: str,
    context: ProposalValidationContext,
) -> int:
    slug, source_module = _source_module_for_proposal(
        module,
        subject=subject,
        context=context,
    )
    proposal_module = cast(dict[str, Any], module)
    candidates = _module_candidates(proposal_module, subject=subject, slug=slug)
    source_text_key = marker_key(str(source_module.get("sourceText", "")))
    for candidate in candidates:
        _validate_candidate(
            candidate,
            subject=subject,
            module_slug=slug,
            source_text_key=source_text_key,
            context=context,
        )
    return len(candidates)


def _validate_proposal_subject(
    subject_entry: Any,
    *,
    context: ProposalValidationContext,
) -> int:
    if not isinstance(subject_entry, dict):
        raise ValueError("Proposal subject entries must be objects")
    require_keys(subject_entry, SUBJECT_KEYS, "proposal subject")
    subject = str(subject_entry["subject"])
    modules = subject_entry["modules"]
    if not isinstance(modules, list):
        raise ValueError(f"Proposal modules must be a list: {subject}")
    return sum(
        _validate_proposal_module(
            module,
            subject=subject,
            context=context,
        )
        for module in modules
    )


def _ensure_complete_module_coverage(
    evidence_modules: dict[tuple[str, str], dict[str, Any]],
    seen_modules: set[tuple[str, str]],
) -> None:
    missing_modules = sorted(evidence_modules.keys() - seen_modules)
    extra_modules = sorted(seen_modules - evidence_modules.keys())
    if not missing_modules and not extra_modules:
        return
    missing = ", ".join(f"{s}/{m}" for s, m in missing_modules) or "none"
    extra = ", ".join(f"{s}/{m}" for s, m in extra_modules) or "none"
    raise ValueError(
        f"Proposal module coverage mismatch; missing={missing}; extra={extra}"
    )


def validate_decomposition_proposals(
    proposals: dict[str, Any],
    evidence: dict[str, Any],
) -> dict[str, Any]:
    _validate_proposal_header(proposals, evidence)
    evidence_modules = _evidence_modules(evidence)
    subjects = proposals["subjects"]
    if not isinstance(subjects, list):
        raise ValueError("Proposal subjects must be a list")
    context = ProposalValidationContext(
        evidence_modules=evidence_modules,
        seen_modules=set(),
        seen_candidate_ids=set(),
    )
    candidate_count = sum(
        _validate_proposal_subject(
            subject_entry,
            context=context,
        )
        for subject_entry in subjects
    )
    _ensure_complete_module_coverage(evidence_modules, context.seen_modules)

    return {
        "artifactId": proposals["artifactId"],
        "proposalState": proposals["proposalState"],
        "subjectCount": len({subject for subject, _ in context.seen_modules}),
        "warningModuleCount": len(context.seen_modules),
        "candidateCount": candidate_count,
        "humanReviewRequired": True,
        "formalLedgerMutationAllowed": False,
        "formalCoverageContribution": 0,
    }
