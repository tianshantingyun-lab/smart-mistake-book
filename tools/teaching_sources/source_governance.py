"""Fail-closed governance for prospective textbook-edition source evidence."""

from __future__ import annotations

import hashlib
import json
import os
import re
from pathlib import Path
from typing import Any, Mapping
from urllib.parse import urlparse

from curriculum_coverage.model import ALLOWED_SUBJECTS


SHA256_PATTERN = re.compile(r"^[A-F0-9]{64}$")
CONCRETE_EDITION_PATTERN = re.compile(
    r"(?:19|20)\d{2}|(?:^|[-_])v\d+(?:$|[-_])",
    re.IGNORECASE,
)
GENERIC_EDITION_TOKENS = {
    "baseline",
    "edition",
    "generic",
    "latest",
    "pending",
    "textbook",
    "unknown",
}

SOURCE_REQUIRED_KEYS = {
    "sourceId",
    "title",
    "publisher",
    "baselineId",
    "textbookEditionId",
    "subjects",
    "purposes",
    "authorityLevel",
    "acquisitionState",
    "discoveryUri",
    "documentUri",
    "contentLengthBytes",
    "contentFingerprint",
    "sourceLocator",
    "licenseStatus",
    "contentUsePolicy",
    "modelUsePolicy",
    "reviewedAtEpochMillis",
    "independenceGroup",
}
SOURCE_OPTIONAL_KEYS = {
    "licenseExpression",
    "licenseUri",
    "attributionText",
}
SOURCE_ARTIFACT_KEYS = {
    "schemaVersion",
    "artifactId",
    "submittedById",
    "sourceRegisterId",
    "artifactState",
    "proposedSource",
    "reviewBoundary",
}
SOURCE_REVIEW_BOUNDARY_KEYS = {
    "state",
    "humanReviewRequired",
    "automaticSourceRegisterMutationAllowed",
    "automaticKnowledgePackMutationAllowed",
    "formalCoverageContribution",
    "productionReady",
}
SOURCE_REVIEW_BOUNDARY = {
    "state": "NON_PROMOTING",
    "humanReviewRequired": True,
    "automaticSourceRegisterMutationAllowed": False,
    "automaticKnowledgePackMutationAllowed": False,
    "formalCoverageContribution": 0,
    "productionReady": False,
}

DECISION_ARTIFACT_KEYS = {
    "schemaVersion",
    "decisionSetId",
    "sourceArtifactId",
    "sourceArtifactSha256",
    "reviewState",
    "reviewer",
    "reviewedAtEpochMillis",
    "decision",
    "reviewBoundary",
}
DECISION_REVIEW_BOUNDARY_KEYS = {
    "state",
    "publicationAllowed",
    "automaticSourceRegisterMutationAllowed",
    "automaticKnowledgePackMutationAllowed",
    "promotionReady",
    "productionReady",
}
DECISION_REVIEW_BOUNDARY = {
    "state": "AUDIT_ONLY_NON_PUBLISHING",
    "publicationAllowed": False,
    "automaticSourceRegisterMutationAllowed": False,
    "automaticKnowledgePackMutationAllowed": False,
    "promotionReady": False,
    "productionReady": False,
}
REVIEWER_KEYS = {"reviewerId", "displayName", "role", "attestation"}
REVIEW_ATTESTATION = (
    "I_REVIEWED_THIS_TEXTBOOK_EDITION_MAPPING_AGAINST_THE_PINNED_SOURCE_ARTIFACT"
)


def _require_object(value: Any, label: str) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise ValueError(f"{label} must be an object")
    return value


def _require_exact_keys(
    value: Mapping[str, Any],
    required: set[str],
    label: str,
    optional: set[str] | None = None,
) -> None:
    optional = optional or set()
    actual = set(value)
    missing = sorted(required - actual)
    unknown = sorted(actual - required - optional)
    if missing:
        raise ValueError(f"{label} is missing keys: {', '.join(missing)}")
    if unknown:
        raise ValueError(f"{label} contains unknown keys: {', '.join(unknown)}")


def _trimmed_text(value: Any, label: str, maximum: int = 4096) -> str:
    if (
        not isinstance(value, str)
        or not value
        or value != value.strip()
        or len(value) > maximum
    ):
        raise ValueError(
            f"{label} must be a trimmed non-empty string of at most {maximum} characters"
        )
    return value


def _https_uri(value: Any, label: str) -> str:
    uri = _trimmed_text(value, label, 2048)
    parsed = urlparse(uri)
    if parsed.scheme != "https" or not parsed.netloc:
        raise ValueError(f"{label} must be an absolute HTTPS URI")
    return uri


def _positive_int(value: Any, label: str) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value <= 0:
        raise ValueError(f"{label} must be a positive integer")
    return value


def _is_concrete_edition_id(value: str) -> bool:
    if CONCRETE_EDITION_PATTERN.search(value) is None:
        return False
    tokens = [
        token
        for token in re.split(r"[-_:./\s]+", value.casefold())
        if token
    ]
    return any(
        token not in GENERIC_EDITION_TOKENS
        and re.fullmatch(r"(?:19|20)\d{2}", token) is None
        and re.fullmatch(r"v\d+", token) is None
        for token in tokens
    )


def _require_fixed_boundary(
    value: Any,
    *,
    keys: set[str],
    expected: Mapping[str, Any],
    label: str,
) -> None:
    boundary = _require_object(value, label)
    _require_exact_keys(boundary, keys, label)
    for key, expected_value in expected.items():
        if boundary[key] != expected_value:
            raise ValueError(f"{label} has unsafe {key}")


def _validate_licensed_metadata(source: Mapping[str, Any]) -> None:
    declared = SOURCE_OPTIONAL_KEYS & set(source)
    if source["licenseStatus"] == "LICENSED":
        if declared != SOURCE_OPTIONAL_KEYS:
            raise ValueError(
                "licensed textbook mapping requires licenseExpression, licenseUri, and attributionText"
            )
    elif declared and declared != SOURCE_OPTIONAL_KEYS:
        raise ValueError("license metadata must be declared as a complete set")
    if declared:
        _trimmed_text(source["licenseExpression"], "licenseExpression", 256)
        _https_uri(source["licenseUri"], "licenseUri")
        _trimmed_text(source["attributionText"], "attributionText", 2048)


def validate_textbook_edition_mapping_source(source: Any) -> dict[str, Any]:
    """Validate one reviewed, single-subject textbook-edition mapping candidate."""

    source = _require_object(source, "textbook edition mapping source")
    _require_exact_keys(
        source,
        SOURCE_REQUIRED_KEYS,
        "textbook edition mapping source",
        SOURCE_OPTIONAL_KEYS,
    )
    source_id = _trimmed_text(source["sourceId"], "sourceId", 256)
    _trimmed_text(source["title"], "title", 512)
    _trimmed_text(source["publisher"], "publisher", 256)
    _trimmed_text(source["baselineId"], "baselineId", 256)
    edition_id = _trimmed_text(source["textbookEditionId"], "textbookEditionId", 256)
    if not _is_concrete_edition_id(edition_id):
        raise ValueError("textbookEditionId must identify a concrete version")

    subjects = source["subjects"]
    if (
        not isinstance(subjects, list)
        or len(subjects) != 1
        or subjects[0] not in ALLOWED_SUBJECTS
    ):
        raise ValueError("textbook edition mapping must bind exactly one high-school subject")
    if source["purposes"] != ["TEXTBOOK_EDITION_MAPPING"]:
        raise ValueError(
            "textbook edition mapping must use only TEXTBOOK_EDITION_MAPPING"
        )
    if source["authorityLevel"] not in {"OFFICIAL", "AUTHORIZED_EDUCATION"}:
        raise ValueError("textbook edition mapping requires official or authorized authority")
    if source["acquisitionState"] != "ACQUIRED_REVIEWED":
        raise ValueError("textbook edition mapping must be ACQUIRED_REVIEWED")

    _https_uri(source["discoveryUri"], "discoveryUri")
    document_uri = _https_uri(source["documentUri"], "documentUri")
    content_length = _positive_int(source["contentLengthBytes"], "contentLengthBytes")
    fingerprint = _trimmed_text(source["contentFingerprint"], "contentFingerprint", 64)
    if SHA256_PATTERN.fullmatch(fingerprint) is None:
        raise ValueError("contentFingerprint must be an uppercase SHA-256")
    locator = _trimmed_text(source["sourceLocator"], "sourceLocator")
    if len(locator) < 12:
        raise ValueError("sourceLocator must identify a concrete reviewed location")
    _positive_int(source["reviewedAtEpochMillis"], "reviewedAtEpochMillis")
    _trimmed_text(source["independenceGroup"], "independenceGroup", 256)

    if source["licenseStatus"] not in {"PUBLIC_OFFICIAL", "LICENSED", "REFERENCE_ONLY"}:
        raise ValueError("unsupported licenseStatus")
    if source["contentUsePolicy"] != "REVIEWED_SYNTHESIS_ONLY":
        raise ValueError("textbook edition mapping must remain REVIEWED_SYNTHESIS_ONLY")
    if source["modelUsePolicy"] != "DERIVED_CONTENT_ONLY":
        raise ValueError("textbook edition mapping must remain DERIVED_CONTENT_ONLY")
    _validate_licensed_metadata(source)

    return {
        "sourceId": source_id,
        "subject": subjects[0],
        "textbookEditionId": edition_id,
        "documentUri": document_uri,
        "contentLengthBytes": content_length,
        "contentFingerprint": fingerprint,
        "sourceLocator": locator,
        "acquisitionState": "ACQUIRED_REVIEWED",
    }


def canonical_sha256(value: Mapping[str, Any]) -> str:
    payload = json.dumps(
        value,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")
    return hashlib.sha256(payload).hexdigest().upper()


def validate_source_artifact(artifact: Any) -> dict[str, Any]:
    """Audit a prospective source artifact without granting promotion authority."""

    artifact = _require_object(artifact, "source artifact")
    _require_exact_keys(artifact, SOURCE_ARTIFACT_KEYS, "source artifact")
    if artifact["schemaVersion"] != 1:
        raise ValueError("only source artifact schemaVersion 1 is supported")
    artifact_id = _trimmed_text(artifact["artifactId"], "artifactId", 256)
    submitted_by_id = _trimmed_text(
        artifact["submittedById"], "submittedById", 256
    )
    register_id = _trimmed_text(artifact["sourceRegisterId"], "sourceRegisterId", 256)
    if artifact["artifactState"] != "NON_PROMOTING":
        raise ValueError("source artifact must remain NON_PROMOTING")
    _require_fixed_boundary(
        artifact["reviewBoundary"],
        keys=SOURCE_REVIEW_BOUNDARY_KEYS,
        expected=SOURCE_REVIEW_BOUNDARY,
        label="source artifact reviewBoundary",
    )
    source = validate_textbook_edition_mapping_source(artifact["proposedSource"])
    return {
        "artifactId": artifact_id,
        "submittedById": submitted_by_id,
        "sourceRegisterId": register_id,
        "artifactSha256": canonical_sha256(artifact),
        **source,
        "artifactState": "NON_PROMOTING",
        "humanReviewRequired": True,
        "automaticSourceRegisterMutationAllowed": False,
        "automaticKnowledgePackMutationAllowed": False,
        "formalCoverageContribution": 0,
        "productionReady": False,
    }


def verify_source_artifact_content(
    artifact: Any,
    content_path: Path,
) -> dict[str, Any]:
    """Verify the acquired project-local bytes pinned by a source artifact."""

    summary = validate_source_artifact(artifact)
    path = Path(content_path)
    if not path.is_file():
        raise ValueError(f"source content artifact is not a regular file: {path}")

    expected_length = summary["contentLengthBytes"]
    expected_fingerprint = summary["contentFingerprint"]
    digest = hashlib.sha256()
    observed_length = 0
    try:
        with path.open("rb") as stream:
            before = os.fstat(stream.fileno())
            while chunk := stream.read(1024 * 1024):
                observed_length += len(chunk)
                digest.update(chunk)
            after = os.fstat(stream.fileno())
    except OSError as error:
        raise ValueError(f"cannot read source content artifact {path}: {error}") from error

    if before.st_size != after.st_size or before.st_mtime_ns != after.st_mtime_ns:
        raise ValueError("source content artifact changed while it was being verified")
    if observed_length != expected_length:
        raise ValueError(
            "source content artifact size differs from contentLengthBytes"
        )
    if digest.hexdigest().upper() != expected_fingerprint:
        raise ValueError(
            "source content artifact SHA-256 differs from contentFingerprint"
        )
    return {
        **summary,
        "contentArtifactVerified": True,
    }


def _validate_reviewer(value: Any) -> None:
    reviewer = _require_object(value, "source reviewer")
    _require_exact_keys(reviewer, REVIEWER_KEYS, "source reviewer")
    for key in ("reviewerId", "displayName", "role"):
        _trimmed_text(reviewer[key], f"source reviewer {key}", 256)
    if reviewer["attestation"] != REVIEW_ATTESTATION:
        raise ValueError("source reviewer attestation is missing or invalid")


def _validate_decision_state(decisions: Mapping[str, Any]) -> None:
    state = decisions["reviewState"]
    reviewer = decisions["reviewer"]
    reviewed_at = decisions["reviewedAtEpochMillis"]
    decision = decisions["decision"]
    if state == "NOT_STARTED":
        if reviewer is not None or reviewed_at is not None or decision is not None:
            raise ValueError("NOT_STARTED review must not contain reviewer or decision")
        return
    if state == "IN_PROGRESS":
        _validate_reviewer(reviewer)
        if reviewed_at is not None or decision is not None:
            raise ValueError("IN_PROGRESS review must not contain completion data")
        return
    if state != "COMPLETED":
        raise ValueError(f"unsupported source review state: {state}")
    _validate_reviewer(reviewer)
    _positive_int(reviewed_at, "reviewedAtEpochMillis")
    if decision not in {"APPROVE", "REJECT"}:
        raise ValueError("COMPLETED review requires APPROVE or REJECT")


def audit_review_decision(
    source_artifact: Any,
    decisions: Any,
) -> dict[str, Any]:
    """Audit a human decision while retaining a non-publishing boundary."""

    source_summary = validate_source_artifact(source_artifact)
    decisions = _require_object(decisions, "source review decision")
    _require_exact_keys(decisions, DECISION_ARTIFACT_KEYS, "source review decision")
    if decisions["schemaVersion"] != 1:
        raise ValueError("only source review decision schemaVersion 1 is supported")
    decision_set_id = _trimmed_text(decisions["decisionSetId"], "decisionSetId", 256)
    if decisions["sourceArtifactId"] != source_summary["artifactId"]:
        raise ValueError("source review decision references another artifact")
    if decisions["sourceArtifactSha256"] != source_summary["artifactSha256"]:
        raise ValueError("source review decision artifact fingerprint is stale")
    _require_fixed_boundary(
        decisions["reviewBoundary"],
        keys=DECISION_REVIEW_BOUNDARY_KEYS,
        expected=DECISION_REVIEW_BOUNDARY,
        label="source review decision reviewBoundary",
    )
    _validate_decision_state(decisions)
    reviewer = decisions["reviewer"]
    if reviewer is not None:
        reviewer_id = reviewer["reviewerId"].casefold()
        if reviewer_id == source_summary["submittedById"].casefold():
            raise ValueError("source artifact submitter cannot review the same artifact")
    return {
        "decisionSetId": decision_set_id,
        "sourceArtifactId": source_summary["artifactId"],
        "sourceArtifactSha256": source_summary["artifactSha256"],
        "sourceId": source_summary["sourceId"],
        "subject": source_summary["subject"],
        "textbookEditionId": source_summary["textbookEditionId"],
        "reviewState": decisions["reviewState"],
        "decision": decisions["decision"],
        "publicationAllowed": False,
        "automaticSourceRegisterMutationAllowed": False,
        "automaticKnowledgePackMutationAllowed": False,
        "promotionReady": False,
        "productionReady": False,
    }
