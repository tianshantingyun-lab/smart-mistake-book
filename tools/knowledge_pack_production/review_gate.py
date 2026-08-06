"""Human-review and source-evidence gates for formal pack production."""

from __future__ import annotations

import hashlib
import re
from collections import defaultdict
from typing import Any, Mapping

from .input_hashes import artifact_fingerprint
from .schema import (
    DECISION_DOCUMENT_KEYS,
    DECISION_KEYS,
    DECISION_REVIEWER_KEYS,
    FORMAL_PACK_REVIEW_ATTESTATION,
    INPUT_FINGERPRINT_KEYS,
    INVENTORY_CANDIDATE_KEYS,
    REQUIRED_SUBJECTS,
    REQUIRED_SUBJECT_SET,
    REVIEW_KEYS,
    SHA256_PATTERN,
    TEACHING_REVIEW_ATTESTATION,
    CoverageIndex,
    InventoryIndex,
    TeachingDecisionIndex,
    require_exact_keys,
    require_id,
    require_list,
    require_object,
    require_positive_int,
    require_review_record,
    require_slug,
    require_string_list,
    require_text,
)


def index_coverage(
    ledger: dict[str, Any],
    *,
    require_reviewed: bool,
) -> CoverageIndex:
    require_exact_keys(
        ledger,
        {
            "schemaVersion",
            "ledgerId",
            "targetBaselineId",
            "sourceRegisterId",
            "updatedAtEpochMillis",
            "subjects",
        },
        "coverage ledger",
    )
    if ledger["schemaVersion"] != 1:
        raise ValueError("Only coverage-ledger schemaVersion 1 is supported")
    ledger_id = require_id(ledger["ledgerId"], "coverage ledger id")
    baseline_id = require_id(
        ledger["targetBaselineId"],
        "coverage target baseline id",
    )
    require_positive_int(
        ledger["updatedAtEpochMillis"],
        "coverage ledger updatedAtEpochMillis",
    )
    subject_entries: dict[str, dict[str, Any]] = {}
    modules: dict[str, dict[str, Any]] = {}
    points: dict[str, dict[str, Any]] = {}
    point_slugs_by_subject: dict[str, set[str]] = defaultdict(set)
    unreviewed_subjects: list[str] = []
    for raw_subject in require_list(ledger["subjects"], "coverage subjects"):
        entry = require_object(raw_subject, "coverage subject")
        require_exact_keys(
            entry,
            {"subject", "curriculumSourceId", "mappingState", "modules"},
            "coverage subject",
            optional={"reviewedAtEpochMillis"},
        )
        subject = str(entry["subject"])
        if subject not in REQUIRED_SUBJECT_SET:
            raise ValueError(f"Unsupported coverage subject: {subject}")
        if subject in subject_entries:
            raise ValueError(f"Duplicate coverage subject: {subject}")
        subject_entries[subject] = entry
        state = str(entry["mappingState"])
        if state != "REVIEWED":
            unreviewed_subjects.append(subject)
        if require_reviewed:
            if state != "REVIEWED":
                raise ValueError(
                    f"Coverage subject {subject} is {state}; "
                    "DRAFT_UNREVIEWED data cannot enter a formal pack"
                )
            require_positive_int(
                entry.get("reviewedAtEpochMillis"),
                f"coverage subject {subject} reviewedAtEpochMillis",
            )
        for raw_module in require_list(
            entry["modules"],
            f"coverage modules for {subject}",
        ):
            module = require_object(raw_module, f"coverage module for {subject}")
            require_exact_keys(
                module,
                {
                    "slug",
                    "name",
                    "requirementType",
                    "sourceLocator",
                    "knowledgePoints",
                },
                f"coverage module for {subject}",
                optional={"courseStages"},
            )
            module_slug = require_slug(
                module["slug"],
                f"coverage module slug for {subject}",
            )
            require_text(module["name"], f"coverage module {module_slug} name")
            require_text(
                module["sourceLocator"],
                f"coverage module {module_slug} source locator",
                maximum=2_000,
            )
            module_key = f"{subject}/{module_slug}"
            if module_key in modules:
                raise ValueError(f"Duplicate coverage module key: {module_key}")
            modules[module_key] = module
            for raw_point in require_list(
                module["knowledgePoints"],
                f"coverage points for {module_key}",
            ):
                point = require_object(raw_point, f"coverage point for {module_key}")
                require_exact_keys(
                    point,
                    {"slug", "name", "sourceLocator"},
                    f"coverage point for {module_key}",
                )
                point_slug = require_slug(
                    point["slug"],
                    f"coverage point slug for {module_key}",
                )
                if point_slug in point_slugs_by_subject[subject]:
                    raise ValueError(
                        f"Duplicate coverage point slug in {subject}: {point_slug}"
                    )
                point_slugs_by_subject[subject].add(point_slug)
                require_text(point["name"], f"coverage point {point_slug} name")
                require_text(
                    point["sourceLocator"],
                    f"coverage point {point_slug} source locator",
                    maximum=2_000,
                )
                point_key = f"{module_key}/{point_slug}"
                if point_key in points:
                    raise ValueError(f"Duplicate coverage point key: {point_key}")
                points[point_key] = point
    if set(subject_entries) != REQUIRED_SUBJECT_SET:
        missing = sorted(REQUIRED_SUBJECT_SET - set(subject_entries))
        extra = sorted(set(subject_entries) - REQUIRED_SUBJECT_SET)
        raise ValueError(
            "Coverage ledger must declare all nine subjects exactly once; "
            f"missing={missing}, extra={extra}"
        )
    if not points:
        raise ValueError("Coverage ledger must contain reviewed knowledge points")
    return CoverageIndex(
        baseline_id=baseline_id,
        ledger_id=ledger_id,
        modules=modules,
        points=points,
        subject_entries=subject_entries,
        unreviewed_subjects=tuple(unreviewed_subjects),
    )


def index_inventory(inventory: dict[str, Any]) -> InventoryIndex:
    require_exact_keys(
        inventory,
        {
            "schemaVersion",
            "inventoryId",
            "manifestId",
            "sourceRegisterId",
            "updatedAtEpochMillis",
            "boundary",
            "candidates",
        },
        "teaching inventory",
    )
    if inventory["schemaVersion"] != 1:
        raise ValueError("Only teaching-inventory schemaVersion 1 is supported")
    inventory_id = require_id(inventory["inventoryId"], "teaching inventory id")
    boundary = require_object(inventory["boundary"], "teaching inventory boundary")
    required_false = (
        "rawTeachingTextIncluded",
        "questionBankAuthority",
        "autonomousQuestionGenerationAuthority",
        "assessmentAuthority",
        "reviewSchedulingAuthority",
        "learningEvidenceWriteAuthority",
    )
    for key in required_false:
        if boundary.get(key) is not False:
            raise ValueError(f"Teaching inventory boundary {key} must remain false")
    if boundary.get("humanReviewerMustOpenPinnedLocalSource") is not True:
        raise ValueError(
            "Teaching inventory must require opening each pinned local source"
        )
    if boundary.get("formalKnowledgeCoverageContribution") != 0:
        raise ValueError("Teaching inventory cannot contribute formal coverage")

    candidates: dict[str, dict[str, Any]] = {}
    subjects: set[str] = set()
    for raw in require_list(inventory["candidates"], "teaching candidates"):
        candidate = require_object(raw, "teaching candidate")
        require_exact_keys(
            candidate,
            INVENTORY_CANDIDATE_KEYS,
            "teaching candidate",
        )
        candidate_id = require_id(candidate.get("candidateId"), "candidate id")
        if candidate_id in candidates:
            raise ValueError(f"Duplicate teaching candidate id: {candidate_id}")
        source_id = require_id(candidate.get("sourceId"), "candidate source id")
        entry_path = require_text(
            candidate.get("entryPath"),
            "candidate entry path",
            maximum=2_000,
        )
        normalized_hash = str(candidate.get("normalizedTextSha256", ""))
        if not re.fullmatch(r"[0-9A-F]{64}", normalized_hash):
            raise ValueError(
                "Candidate normalizedTextSha256 must be uppercase SHA-256"
            )
        identity = f"{source_id}\0{entry_path}\0{normalized_hash}".encode()
        expected_id = (
            "teaching-review:" + hashlib.sha256(identity).hexdigest()[:32]
        )
        if candidate_id != expected_id:
            raise ValueError(
                f"Teaching candidate id is not stable for {candidate_id}"
            )
        if candidate.get("reviewState") != "UNREVIEWED_LOCATOR_ONLY":
            raise ValueError(
                "Teaching inventory candidates must remain locator-only and unreviewed"
            )
        candidate_subjects = set(
            require_string_list(candidate.get("subjects"), "candidate subjects")
        )
        if not candidate_subjects or not candidate_subjects <= REQUIRED_SUBJECT_SET:
            raise ValueError(f"Candidate {candidate_id} has invalid subjects")
        subjects.update(candidate_subjects)
        forms = set(
            require_string_list(
                candidate.get("candidateTeachingForms"),
                "candidate teaching forms",
            )
        )
        if not forms or not forms <= {
            "METHOD_MODEL",
            "WORKED_EXAMPLE",
            "COMPLETE_SOLUTION",
            "DERIVATION",
        }:
            raise ValueError(f"Candidate {candidate_id} has invalid teaching forms")
        require_positive_int(
            candidate.get("normalizedTextCharacterCount"),
            f"candidate {candidate_id} normalized text count",
        )
        candidates[candidate_id] = candidate
    return InventoryIndex(
        inventory_id=inventory_id,
        candidates=candidates,
        subjects=frozenset(subjects),
    )


def validate_teaching_decisions(
    document: dict[str, Any],
    inventory: InventoryIndex,
) -> TeachingDecisionIndex:
    require_exact_keys(document, DECISION_DOCUMENT_KEYS, "teaching decisions")
    if document["schemaVersion"] != 1:
        raise ValueError("Only teaching decision schemaVersion 1 is supported")
    if document["inventoryId"] != inventory.inventory_id:
        raise ValueError("Teaching decisions reference a different inventory")
    if document["inventoryCandidateCount"] != len(inventory.candidates):
        raise ValueError("Teaching decision candidate count is stale")
    if document["automaticKnowledgePackMutationAllowed"] is not False:
        raise ValueError("Teaching decisions cannot authorize automatic pack mutation")
    if document["reviewState"] != "COMPLETED":
        raise ValueError(
            "Teaching review is not COMPLETED; a formal pack cannot be emitted"
        )
    if document["sourceTextAttestation"] != TEACHING_REVIEW_ATTESTATION:
        raise ValueError("Teaching source-text review attestation is missing")
    reviewer = require_object(document["reviewer"], "teaching reviewer record")
    require_exact_keys(reviewer, DECISION_REVIEWER_KEYS, "teaching reviewer record")
    reviewer_record_id = require_review_record(
        reviewer["reviewRecordId"],
        "teaching reviewer record id",
    )
    reviewed_at = require_positive_int(
        document["reviewedAtEpochMillis"],
        "teaching review completion time",
    )

    decisions: dict[str, dict[str, Any]] = {}
    for raw in require_list(document["decisions"], "teaching decisions"):
        decision = require_object(raw, "teaching decision")
        require_exact_keys(decision, DECISION_KEYS, "teaching decision")
        candidate_id = require_id(decision["candidateId"], "decision candidate id")
        candidate = inventory.candidates.get(candidate_id)
        if candidate is None:
            raise ValueError(f"Unknown teaching candidate decision: {candidate_id}")
        if candidate_id in decisions:
            raise ValueError(f"Duplicate teaching candidate decision: {candidate_id}")
        decision_record_id = require_review_record(
            decision["reviewRecordId"],
            "teaching decision review record id",
        )
        if decision_record_id != reviewer_record_id:
            raise ValueError(
                "Teaching decisions must bind the completed review record"
            )
        decision_time = require_positive_int(
            decision["reviewedAtEpochMillis"],
            "teaching decision review time",
        )
        if decision_time > reviewed_at:
            raise ValueError("Teaching decision cannot postdate review completion")
        if decision["sourceLocatorVerified"] is not True:
            raise ValueError(
                "Every teaching decision must verify the pinned source locator"
            )
        approved_forms = require_string_list(
            decision["approvedTeachingForms"],
            "approved teaching forms",
        )
        action = str(decision["decision"])
        resolved_subject = decision["resolvedSubject"]
        if action == "REJECT":
            if resolved_subject is not None or approved_forms:
                raise ValueError(
                    "Rejected teaching candidates cannot approve forms or a subject"
                )
        elif action == "APPROVE_FOR_HUMAN_SYNTHESIS":
            if resolved_subject not in candidate["subjects"]:
                raise ValueError(
                    "Approved teaching candidate needs one reviewed source subject"
                )
            if not approved_forms:
                raise ValueError(
                    "Approved teaching candidate needs reviewed teaching forms"
                )
            if not set(approved_forms) <= set(candidate["candidateTeachingForms"]):
                raise ValueError(
                    "Approved teaching forms exceed locator-only evidence"
                )
        else:
            raise ValueError(
                "Teaching decisions may only reject or approve for human synthesis"
            )
        decisions[candidate_id] = decision
    missing = inventory.candidates.keys() - decisions.keys()
    if missing:
        raise ValueError(
            f"COMPLETED teaching review is missing {len(missing)} decisions"
        )
    return TeachingDecisionIndex(
        decision_set_id=require_id(
            document["decisionSetId"],
            "teaching decision set id",
        ),
        decisions=decisions,
        reviewed_at=reviewed_at,
    )


def validate_review_header(
    review: dict[str, Any],
    coverage_ledger: dict[str, Any],
    source_register: dict[str, Any],
    teaching_inventory: dict[str, Any],
    teaching_decisions: dict[str, Any],
) -> tuple[str, int]:
    require_exact_keys(review, REVIEW_KEYS, "formal pack review")
    if review["schemaVersion"] != 1:
        raise ValueError("Only formal pack review schemaVersion 1 is supported")
    require_id(review["artifactId"], "formal pack review artifact id")
    if review["reviewState"] != "HUMAN_REVIEWED":
        raise ValueError("Formal pack reviewState must be HUMAN_REVIEWED")
    if review["attestation"] != FORMAL_PACK_REVIEW_ATTESTATION:
        raise ValueError("Formal pack human-review attestation is missing")
    review_record_id = require_review_record(
        review["reviewRecordId"],
        "formal pack review record id",
    )
    reviewed_at = require_positive_int(
        review["reviewedAtEpochMillis"],
        "formal pack review time",
    )
    evidence_times = {
        "coverage ledger": coverage_ledger.get("updatedAtEpochMillis"),
        "source register": source_register.get("reviewedAtEpochMillis"),
        "teaching inventory": teaching_inventory.get("updatedAtEpochMillis"),
        "teaching decisions": teaching_decisions.get("reviewedAtEpochMillis"),
    }
    for label, value in evidence_times.items():
        evidence_time = require_positive_int(value, f"{label} evidence time")
        if evidence_time > reviewed_at:
            raise ValueError(f"Formal pack review predates the {label}")
    fingerprints = require_object(
        review["inputFingerprints"],
        "formal pack input fingerprints",
    )
    require_exact_keys(
        fingerprints,
        INPUT_FINGERPRINT_KEYS,
        "formal pack input fingerprints",
    )
    expected_inputs = {
        "coverageLedger": artifact_fingerprint(coverage_ledger),
        "sourceRegister": artifact_fingerprint(source_register),
        "teachingInventory": artifact_fingerprint(teaching_inventory),
        "teachingDecisions": artifact_fingerprint(teaching_decisions),
    }
    for key, expected in expected_inputs.items():
        actual = str(fingerprints[key])
        if not SHA256_PATTERN.fullmatch(actual):
            raise ValueError(f"Input fingerprint {key} must be lowercase SHA-256")
        if actual != expected:
            raise ValueError(f"Input fingerprint {key} is stale or mismatched")
    return review_record_id, reviewed_at


def index_register_sources(
    register: dict[str, Any],
) -> dict[str, dict[str, Any]]:
    sources = require_list(register.get("sources"), "source register sources")
    result: dict[str, dict[str, Any]] = {}
    for raw in sources:
        source = require_object(raw, "source register source")
        source_id = require_id(source.get("sourceId"), "register source id")
        if source_id in result:
            raise ValueError(f"Duplicate source-register id: {source_id}")
        result[source_id] = source
    return result


def validate_coverage_sources(
    coverage: CoverageIndex,
    register_sources: Mapping[str, dict[str, Any]],
) -> None:
    for subject in REQUIRED_SUBJECTS:
        source_id = str(coverage.subject_entries[subject]["curriculumSourceId"])
        source = register_sources.get(source_id)
        if source is None:
            raise ValueError(
                f"Coverage subject {subject} references an unknown source"
            )
        if (
            source.get("baselineId") != coverage.baseline_id
            or source.get("subjects") != [subject]
            or "CURRENT_CURRICULUM_TEXT" not in source.get("purposes", [])
        ):
            raise ValueError(
                f"Coverage subject {subject} must use its reviewed current "
                "curriculum source"
            )
        if source.get("acquisitionState") != "ACQUIRED_REVIEWED":
            raise ValueError(
                f"Coverage source {source_id} is not ACQUIRED_REVIEWED"
            )


def reviewed_teaching_source_counts(
    register: dict[str, Any],
    source_by_id: Mapping[str, dict[str, Any]],
) -> dict[str, dict[str, int]]:
    requirements = register.get("sourceRequirements")
    minimum = (
        int(requirements.get("minimumReviewedTeachingReferencesPerSubject", 1))
        if isinstance(requirements, dict)
        else 1
    )
    result: dict[str, dict[str, int]] = {}
    for subject in REQUIRED_SUBJECTS:
        reviewed = [
            source
            for source in source_by_id.values()
            if source.get("acquisitionState") == "ACQUIRED_REVIEWED"
            and subject in source.get("subjects", [])
            and "TEACHING_REFERENCE" in source.get("purposes", [])
        ]
        result[subject] = {
            "required": minimum,
            "independentTeachingReferences": len(
                {
                    str(source.get("independenceGroup", source.get("sourceId")))
                    for source in reviewed
                }
            ),
            "methodReferences": sum(
                "METHOD_REFERENCE" in source.get("purposes", [])
                for source in reviewed
            ),
            "workedExampleReferences": sum(
                "WORKED_EXAMPLE_REFERENCE" in source.get("purposes", [])
                for source in reviewed
            ),
        }
    return result
