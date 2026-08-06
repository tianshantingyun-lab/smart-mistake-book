"""Schemas, constants, and primitive validators for formal pack production."""

from __future__ import annotations

import re
import unicodedata
from dataclasses import dataclass
from typing import Any, Mapping
from urllib.parse import urlparse


REQUIRED_SUBJECTS = (
    "CHINESE",
    "MATH",
    "ENGLISH",
    "POLITICS",
    "HISTORY",
    "GEOGRAPHY",
    "PHYSICS",
    "CHEMISTRY",
    "BIOLOGY",
)
REQUIRED_SUBJECT_SET = frozenset(REQUIRED_SUBJECTS)

FORMAL_PACK_REVIEW_ATTESTATION = (
    "I_HUMAN_REVIEWED_THE_COMPLETE_NINE_SUBJECT_PACK_AND_ITS_SOURCE_GROUNDING"
)
TEACHING_REVIEW_ATTESTATION = (
    "I_OPENED_EACH_PINNED_LOCAL_SOURCE_AND_REVIEWED_EACH_DECISION"
)

ALLOWED_NODE_KINDS = {
    "TOPIC",
    "CONCEPT",
    "PROCEDURE",
    "REASONING",
    "REPRESENTATION",
    "EXPERIMENT",
    "EXPRESSION",
}
ALLOWED_RELATION_TYPES = {
    "PREREQUISITE_OF",
    "RELATED_TO",
    "CONFUSABLE_WITH",
}
SYMMETRIC_RELATION_TYPES = {"RELATED_TO", "CONFUSABLE_WITH"}
ALLOWED_MATERIAL_TYPES = {
    "CONCEPT_EXPLANATION",
    "METHOD_MODEL",
    "WORKED_EXAMPLE",
    "COMPLETE_SOLUTION",
    "DERIVATION",
    "MISCONCEPTION_GUIDE",
    "REPRESENTATION_GUIDE",
}
ALLOWED_MATERIAL_ROLES = {"PRIMARY", "SUPPORTING", "PREREQUISITE"}
ALLOWED_CONTENT_ORIGINS = {
    "INDEPENDENT_HUMAN_SYNTHESIS",
    "SELF_AUTHORED_ABSTRACT_EXAMPLE",
}
EXAMPLE_MATERIAL_TYPES = {"WORKED_EXAMPLE", "COMPLETE_SOLUTION"}
ALLOWED_LICENSE_STATUSES = {"PUBLIC_OFFICIAL", "LICENSED", "REFERENCE_ONLY"}
ALLOWED_CONTENT_USE_POLICIES = {
    "REVIEWED_SYNTHESIS_ONLY",
    "EXCERPT_ALLOWED",
    "ADAPTATION_ALLOWED",
}
ALLOWED_SOURCE_TYPES = {
    "OFFICIAL_CURRICULUM_STANDARD",
    "TEXTBOOK",
    "AUTHORIZED_EDUCATION_MATERIAL",
    "MANUAL_RESEARCH",
}
MATERIAL_FORM_REQUIREMENT = {
    "METHOD_MODEL": "METHOD_MODEL",
    "WORKED_EXAMPLE": "WORKED_EXAMPLE",
    "COMPLETE_SOLUTION": "COMPLETE_SOLUTION",
    "DERIVATION": "DERIVATION",
}

SHA256_PATTERN = re.compile(r"^[0-9a-f]{64}$")
SOURCE_SHA256_PATTERN = re.compile(r"^[0-9A-Fa-f]{64}$")
SLUG_PATTERN = re.compile(r"^[a-z0-9]+(?:-[a-z0-9]+)*$")
REVIEW_RECORD_PATTERN = re.compile(r"^[a-z0-9][a-z0-9._:-]{2,159}$")
CONTROL_PATTERN = re.compile(r"[\x00-\x08\x0b\x0c\x0e-\x1f\x7f]")

PROHIBITED_QUESTION_FIELDS = {
    "answer",
    "answerkey",
    "answertext",
    "bankid",
    "choices",
    "correctanswer",
    "exerciseid",
    "originalanswer",
    "originalquestion",
    "originaltext",
    "options",
    "problemid",
    "problemtext",
    "prompt",
    "prompttext",
    "question",
    "questionid",
    "questionstem",
    "questiontext",
    "rawtext",
    "solution",
    "solutiontext",
    "sourcetext",
}

INPUT_FINGERPRINT_KEYS = {
    "coverageLedger",
    "sourceRegister",
    "teachingInventory",
    "teachingDecisions",
}
REVIEW_KEYS = {
    "schemaVersion",
    "artifactId",
    "reviewState",
    "reviewRecordId",
    "reviewedAtEpochMillis",
    "attestation",
    "inputFingerprints",
    "expectedVersionFingerprint",
    "pack",
}
PACK_KEYS = {
    "packId",
    "knowledgePackVersion",
    "taxonomyVersion",
    "searchIndexVersion",
    "builtAtEpochMillis",
    "sources",
    "nodes",
    "nodeSourceBindings",
    "relations",
    "teachingMaterials",
    "teachingMaterialBindings",
}
SOURCE_REFERENCE_KEYS = {
    "sourceId",
    "registerSourceId",
    "subject",
    "reviewRecordId",
    "reviewedAtEpochMillis",
}
NODE_KEYS = {
    "coverageKey",
    "knowledgeNodeId",
    "stableCode",
    "subject",
    "displayName",
    "canonicalName",
    "kind",
    "granularity",
    "aliases",
    "boundaryMarkdown",
    "verificationStatus",
    "parentKnowledgeNodeId",
    "reviewRecordId",
    "reviewedAtEpochMillis",
}
NODE_BINDING_KEYS = {
    "knowledgeNodeId",
    "sourceId",
    "sourceLocator",
    "derivationNote",
    "reviewRecordId",
    "reviewedAtEpochMillis",
}
RELATION_KEYS = {
    "relationId",
    "subject",
    "fromKnowledgeNodeId",
    "toKnowledgeNodeId",
    "relationType",
    "sourceId",
    "sourceLocator",
    "reviewRecordId",
    "reviewedAtEpochMillis",
}
MATERIAL_KEYS = {
    "materialId",
    "stableCode",
    "subject",
    "materialType",
    "title",
    "summaryMarkdown",
    "applicabilityMarkdown",
    "contentMarkdown",
    "boundaryMarkdown",
    "derivationKind",
    "contentOrigin",
    "sourceId",
    "sourceLocator",
    "inventoryCandidateIds",
    "originalQuestionIncluded",
    "originalAnswerIncluded",
    "reviewRecordId",
    "reviewedAtEpochMillis",
}
MATERIAL_BINDING_KEYS = {"materialId", "knowledgeNodeId", "role"}
DECISION_DOCUMENT_KEYS = {
    "schemaVersion",
    "decisionSetId",
    "inventoryId",
    "inventoryCandidateCount",
    "reviewState",
    "reviewer",
    "reviewedAtEpochMillis",
    "sourceTextAttestation",
    "decisions",
    "automaticKnowledgePackMutationAllowed",
}
DECISION_REVIEWER_KEYS = {"reviewRecordId"}
DECISION_KEYS = {
    "candidateId",
    "decision",
    "resolvedSubject",
    "approvedTeachingForms",
    "sourceLocatorVerified",
    "reviewRecordId",
    "reviewedAtEpochMillis",
}
INVENTORY_CANDIDATE_KEYS = {
    "candidateId",
    "sourceId",
    "subjects",
    "entryPath",
    "sectionTitle",
    "normalizedTextSha256",
    "normalizedTextCharacterCount",
    "candidateTeachingForms",
    "markerCounts",
    "reviewState",
}


@dataclass(frozen=True, slots=True)
class CoverageIndex:
    baseline_id: str
    ledger_id: str
    modules: Mapping[str, dict[str, Any]]
    points: Mapping[str, dict[str, Any]]
    subject_entries: Mapping[str, dict[str, Any]]
    unreviewed_subjects: tuple[str, ...]

    @property
    def module_count(self) -> int:
        return len(self.modules)

    @property
    def point_count(self) -> int:
        return len(self.points)

    @property
    def expected_node_keys(self) -> frozenset[str]:
        return frozenset((*self.modules.keys(), *self.points.keys()))


@dataclass(frozen=True, slots=True)
class InventoryIndex:
    inventory_id: str
    candidates: Mapping[str, dict[str, Any]]
    subjects: frozenset[str]


@dataclass(frozen=True, slots=True)
class TeachingDecisionIndex:
    decision_set_id: str
    decisions: Mapping[str, dict[str, Any]]
    reviewed_at: int


def reject_prohibited_question_fields(value: Any, label: str) -> None:
    if isinstance(value, dict):
        for key, child in value.items():
            normalized_key = re.sub(r"[^a-z0-9]", "", str(key).casefold())
            if normalized_key in PROHIBITED_QUESTION_FIELDS:
                raise ValueError(
                    f"{label} contains prohibited reconstructable question field: {key}"
                )
            reject_prohibited_question_fields(child, label)
    elif isinstance(value, list):
        for child in value:
            reject_prohibited_question_fields(child, label)


def require_exact_keys(
    value: Mapping[str, Any],
    required: set[str],
    label: str,
    *,
    optional: set[str] | None = None,
) -> None:
    allowed = required | (optional or set())
    actual = set(value)
    missing = sorted(required - actual)
    unknown = sorted(actual - allowed)
    if missing:
        raise ValueError(f"{label} is missing keys: {', '.join(missing)}")
    if unknown:
        raise ValueError(f"{label} contains unknown keys: {', '.join(unknown)}")


def require_object(value: Any, label: str) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise ValueError(f"{label} must be a JSON object")
    return value


def require_list(value: Any, label: str) -> list[Any]:
    if not isinstance(value, list):
        raise ValueError(f"{label} must be a JSON array")
    return value


def require_string_list(value: Any, label: str) -> list[str]:
    values = require_list(value, label)
    result = [require_text(item, label) for item in values]
    if len(result) != len(set(result)):
        raise ValueError(f"{label} must not contain duplicates")
    return result


def require_id(value: Any, label: str) -> str:
    return require_text(value, label, maximum=256)


def require_review_record(value: Any, label: str) -> str:
    result = require_text(value, label, maximum=160)
    if not REVIEW_RECORD_PATTERN.fullmatch(result):
        raise ValueError(f"{label} must be an opaque stable review-record id")
    return result


def require_slug(value: Any, label: str) -> str:
    result = require_text(value, label, maximum=120)
    if not SLUG_PATTERN.fullmatch(result):
        raise ValueError(f"{label} must be lowercase kebab-case")
    return result


def require_text(
    value: Any,
    label: str,
    *,
    maximum: int = 4_096,
) -> str:
    if (
        not isinstance(value, str)
        or not value.strip()
        or value != value.strip()
        or len(value) > maximum
        or CONTROL_PATTERN.search(value)
    ):
        raise ValueError(
            f"{label} must be trimmed non-blank text of at most {maximum} characters"
        )
    return value


def optional_text(value: Any) -> str | None:
    if value is None:
        return None
    return require_text(value, "optional source text")


def require_positive_int(value: Any, label: str) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value <= 0:
        raise ValueError(f"{label} must be a positive integer")
    return value


def require_https_uri(value: Any, label: str) -> str:
    uri = require_text(value, label, maximum=4_096)
    parsed = urlparse(uri)
    if parsed.scheme != "https" or not parsed.hostname or parsed.username is not None:
        raise ValueError(f"{label} must be a public HTTPS locator")
    return uri


def normalized_term(value: str) -> str:
    return " ".join(unicodedata.normalize("NFKC", value).casefold().split())
