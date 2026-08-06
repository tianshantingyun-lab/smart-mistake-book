"""Public orchestration for the fail-closed offline formal pack compiler."""

from __future__ import annotations

from typing import Any, Mapping

from .canonical_output import (
    build_sources,
    expected_material_id,
    expected_node_identity,
    validate_material_bindings,
    validate_materials,
    validate_node_bindings,
    validate_nodes,
    validate_teaching_coverage,
)
from .input_hashes import (
    artifact_fingerprint,
    canonical_fingerprint,
    deep_copy_json,
)
from .holdout_gate import (
    validate_build_inputs_before_processing,
    verify_holdout_isolation,
)
from .relations import expected_relation_id, validate_relations
from .review_gate import (
    index_coverage,
    index_inventory,
    index_register_sources,
    reviewed_teaching_source_counts,
    validate_coverage_sources,
    validate_review_header,
    validate_teaching_decisions,
)
from .runtime_contract import (
    build_coverage_proof_v2,
    reviewed_content_fingerprint,
)
from .schema import (
    FORMAL_PACK_REVIEW_ATTESTATION,
    PACK_KEYS,
    REQUIRED_SUBJECTS,
    SHA256_PATTERN,
    TEACHING_REVIEW_ATTESTATION,
    reject_prohibited_question_fields,
    require_exact_keys,
    require_id,
    require_list,
    require_object,
    require_positive_int,
)


def production_status(
    coverage_ledger: dict[str, Any],
    source_register: dict[str, Any],
    teaching_inventory: dict[str, Any],
    teaching_decisions: dict[str, Any],
    pack_review: dict[str, Any] | None = None,
    *,
    content_input_manifest: Mapping[str, Any] | None = None,
    holdout_manifest: Mapping[str, Any] | None = None,
    holdout_fingerprint_key: bytes | None = None,
    holdout_release_registration: Mapping[str, Any] | None = None,
) -> dict[str, Any]:
    """Report blockers without creating or approving a pack."""

    coverage = index_coverage(coverage_ledger, require_reviewed=False)
    inventory = index_inventory(teaching_inventory)
    raw_decisions = require_list(
        teaching_decisions.get("decisions"),
        "teaching review decisions",
    )
    decision_ids = {
        str(entry.get("candidateId"))
        for entry in raw_decisions
        if isinstance(entry, dict) and entry.get("candidateId") is not None
    }
    pending_decisions = max(0, len(inventory.candidates) - len(decision_ids))

    source_by_id = index_register_sources(source_register)
    current_source_states = {
        subject: str(
            source_by_id.get(
                str(coverage.subject_entries[subject]["curriculumSourceId"]),
                {},
            ).get("acquisitionState", "MISSING")
        )
        for subject in REQUIRED_SUBJECTS
    }
    teaching_counts = reviewed_teaching_source_counts(
        source_register,
        source_by_id,
    )
    insufficient_teaching_subjects = [
        subject
        for subject in REQUIRED_SUBJECTS
        if teaching_counts[subject]["independentTeachingReferences"]
        < teaching_counts[subject]["required"]
        or teaching_counts[subject]["methodReferences"] == 0
        or teaching_counts[subject]["workedExampleReferences"] == 0
    ]

    blockers: list[str] = []
    if coverage.unreviewed_subjects:
        unreviewed_points = sum(
            len(module["knowledgePoints"])
            for subject in coverage.unreviewed_subjects
            for module in coverage.subject_entries[subject]["modules"]
        )
        blockers.append(
            "Coverage ledger has "
            f"{len(REQUIRED_SUBJECTS) - len(coverage.unreviewed_subjects)}/"
            f"{len(REQUIRED_SUBJECTS)} reviewed subjects; "
            f"{unreviewed_points}/{coverage.point_count} points remain unreviewed"
        )
    unreviewed_source_subjects = [
        subject
        for subject, state in current_source_states.items()
        if state != "ACQUIRED_REVIEWED"
    ]
    if unreviewed_source_subjects:
        blockers.append(
            "Current curriculum sources are not human-reviewed for: "
            + ", ".join(unreviewed_source_subjects)
        )
    review_state = str(teaching_decisions.get("reviewState", "MISSING"))
    if review_state != "COMPLETED" or pending_decisions:
        blockers.append(
            f"Teaching review is {review_state}: "
            f"{len(decision_ids)}/{len(inventory.candidates)} decisions, "
            f"{pending_decisions} pending"
        )
    if insufficient_teaching_subjects:
        blockers.append(
            "Reviewed method/worked-example source coverage is incomplete for: "
            + ", ".join(insufficient_teaching_subjects)
        )
    if pack_review is None:
        blockers.append("Formal pack human-review artifact is missing")
    elif not blockers:
        try:
            compile_formal_pack(
                coverage_ledger,
                source_register,
                teaching_inventory,
                teaching_decisions,
                pack_review,
                content_input_manifest=content_input_manifest,
                holdout_manifest=holdout_manifest,
                holdout_fingerprint_key=holdout_fingerprint_key,
                holdout_release_registration=holdout_release_registration,
            )
        except ValueError as error:
            blockers.append(f"Formal pack review is invalid: {error}")

    inventory_gaps = [
        subject for subject in REQUIRED_SUBJECTS if subject not in inventory.subjects
    ]
    return {
        "productionReady": not blockers,
        "formalPackEmitted": False,
        "coverage": {
            "ledgerId": coverage.ledger_id,
            "subjectCount": len(coverage.subject_entries),
            "reviewedSubjectCount": (
                len(REQUIRED_SUBJECTS) - len(coverage.unreviewed_subjects)
            ),
            "unreviewedSubjects": list(coverage.unreviewed_subjects),
            "moduleCount": coverage.module_count,
            "pointCount": coverage.point_count,
        },
        "curriculumSources": {
            "statesBySubject": current_source_states,
            "reviewedSubjectCount": sum(
                state == "ACQUIRED_REVIEWED"
                for state in current_source_states.values()
            ),
        },
        "teachingReview": {
            "inventoryId": inventory.inventory_id,
            "candidateCount": len(inventory.candidates),
            "inventorySubjects": sorted(inventory.subjects),
            "inventorySubjectGaps": inventory_gaps,
            "reviewState": review_state,
            "decisionCount": len(decision_ids),
            "pendingDecisionCount": pending_decisions,
        },
        "reviewedTeachingSources": teaching_counts,
        "packReviewPresent": pack_review is not None,
        "blockers": blockers,
    }


def validate_formal_build_inputs_before_processing(
    build_inputs: Mapping[str, Mapping[str, Any]],
) -> None:
    """Fully validate formal inputs before holdout HMAC or JSON copying."""

    validate_build_inputs_before_processing(build_inputs)
    coverage_ledger = build_inputs["coverageLedger"]
    source_register = build_inputs["sourceRegister"]
    teaching_inventory = build_inputs["teachingInventory"]
    teaching_decisions = build_inputs["teachingDecisions"]
    pack_review = build_inputs["packReviewContent"]

    reject_prohibited_question_fields(pack_review, "formal pack review")
    reject_prohibited_question_fields(teaching_inventory, "teaching inventory")
    coverage = index_coverage(coverage_ledger, require_reviewed=True)
    register_sources = index_register_sources(source_register)
    inventory = index_inventory(teaching_inventory)
    register_id = require_id(
        source_register.get("registerId"),
        "source register id",
    )
    if coverage_ledger["sourceRegisterId"] != register_id:
        raise ValueError("Coverage ledger references a different source register")
    if teaching_inventory["sourceRegisterId"] != register_id:
        raise ValueError("Teaching inventory references a different source register")
    if source_register.get("targetBaselineId") != coverage.baseline_id:
        raise ValueError("Coverage ledger and source register baselines do not match")
    validate_coverage_sources(coverage, register_sources)
    decisions = validate_teaching_decisions(teaching_decisions, inventory)
    _, pack_reviewed_at = validate_review_header(
        pack_review,
        coverage_ledger,
        source_register,
        teaching_inventory,
        teaching_decisions,
    )

    pack = require_object(pack_review["pack"], "formal pack")
    require_exact_keys(pack, PACK_KEYS, "formal pack")
    require_id(pack["packId"], "formal pack id")
    require_id(pack["knowledgePackVersion"], "formal knowledge-pack version")
    taxonomy_version = require_id(
        pack["taxonomyVersion"],
        "formal taxonomy version",
    )
    require_id(pack["searchIndexVersion"], "formal search-index version")
    built_at = require_positive_int(
        pack["builtAtEpochMillis"],
        "formal pack builtAtEpochMillis",
    )
    if built_at < pack_reviewed_at:
        raise ValueError("Formal pack cannot be built before its human review")

    _, source_context = build_sources(
        pack["sources"],
        register_sources,
        pack_reviewed_at,
    )
    _, nodes_by_id, nodes_by_coverage_key = validate_nodes(
        pack["nodes"],
        coverage,
        taxonomy_version,
        pack_reviewed_at,
    )
    validate_node_bindings(
        pack["nodeSourceBindings"],
        coverage,
        nodes_by_id,
        nodes_by_coverage_key,
        source_context,
        pack_reviewed_at,
    )
    validate_relations(
        pack["relations"],
        nodes_by_id,
        source_context,
        pack_reviewed_at,
        taxonomy_version,
    )
    materials, materials_by_id = validate_materials(
        pack["teachingMaterials"],
        source_context,
        inventory,
        decisions,
        pack_reviewed_at,
    )
    validate_material_bindings(
        pack["teachingMaterialBindings"],
        materials_by_id,
        nodes_by_id,
    )
    validate_teaching_coverage(
        source_register,
        materials,
        source_context,
    )


def calculate_expected_version_fingerprint(
    coverage_ledger: dict[str, Any],
    source_register: dict[str, Any],
    teaching_inventory: dict[str, Any],
    teaching_decisions: dict[str, Any],
    pack_review: dict[str, Any],
    *,
    content_input_manifest: Mapping[str, Any] | None = None,
    holdout_manifest: Mapping[str, Any] | None = None,
    holdout_fingerprint_key: bytes | None = None,
    holdout_release_registration: Mapping[str, Any] | None = None,
) -> str:
    """Validate and calculate the digest a human review record must pin."""

    validate_formal_build_inputs_before_processing(
        _formal_build_inputs(
            coverage_ledger,
            source_register,
            teaching_inventory,
            teaching_decisions,
            pack_review,
        )
    )
    holdout_compliance = _verify_compiler_holdout(
        coverage_ledger=coverage_ledger,
        source_register=source_register,
        teaching_inventory=teaching_inventory,
        teaching_decisions=teaching_decisions,
        pack_review=pack_review,
        content_input_manifest=content_input_manifest,
        holdout_manifest=holdout_manifest,
        holdout_fingerprint_key=holdout_fingerprint_key,
        holdout_release_registration=holdout_release_registration,
    )
    unsigned, _ = _build_unsigned_artifact(
        coverage_ledger,
        source_register,
        teaching_inventory,
        teaching_decisions,
        pack_review,
        holdout_compliance,
    )
    return canonical_fingerprint(
        "formal-high-school-knowledge-pack-v2",
        unsigned,
    )


def compile_formal_pack(
    coverage_ledger: dict[str, Any],
    source_register: dict[str, Any],
    teaching_inventory: dict[str, Any],
    teaching_decisions: dict[str, Any],
    pack_review: dict[str, Any],
    *,
    content_input_manifest: Mapping[str, Any] | None = None,
    holdout_manifest: Mapping[str, Any] | None = None,
    holdout_fingerprint_key: bytes | None = None,
    holdout_release_registration: Mapping[str, Any] | None = None,
) -> tuple[dict[str, Any], dict[str, Any]]:
    """Return a formal reviewed artifact, or fail before emitting any pack."""

    validate_formal_build_inputs_before_processing(
        _formal_build_inputs(
            coverage_ledger,
            source_register,
            teaching_inventory,
            teaching_decisions,
            pack_review,
        )
    )
    holdout_compliance = _verify_compiler_holdout(
        coverage_ledger=coverage_ledger,
        source_register=source_register,
        teaching_inventory=teaching_inventory,
        teaching_decisions=teaching_decisions,
        pack_review=pack_review,
        content_input_manifest=content_input_manifest,
        holdout_manifest=holdout_manifest,
        holdout_fingerprint_key=holdout_fingerprint_key,
        holdout_release_registration=holdout_release_registration,
    )
    unsigned, summary = _build_unsigned_artifact(
        coverage_ledger,
        source_register,
        teaching_inventory,
        teaching_decisions,
        pack_review,
        holdout_compliance,
    )
    actual_fingerprint = canonical_fingerprint(
        "formal-high-school-knowledge-pack-v2",
        unsigned,
    )
    expected_fingerprint = str(pack_review["expectedVersionFingerprint"])
    if not SHA256_PATTERN.fullmatch(expected_fingerprint):
        raise ValueError(
            "Formal pack expectedVersionFingerprint must be lowercase SHA-256"
        )
    if actual_fingerprint != expected_fingerprint:
        raise ValueError(
            "Formal pack version fingerprint does not match all canonical "
            "content and review evidence"
        )

    artifact = deep_copy_json(unsigned)
    artifact["manifest"]["contentFingerprint"] = actual_fingerprint
    return artifact, {
        **summary,
        "contentFingerprint": actual_fingerprint,
        "formalPackEmitted": True,
        "runtimeActivationAuthorized": False,
    }


def _build_unsigned_artifact(
    coverage_ledger: dict[str, Any],
    source_register: dict[str, Any],
    teaching_inventory: dict[str, Any],
    teaching_decisions: dict[str, Any],
    pack_review: dict[str, Any],
    holdout_compliance: Mapping[str, Any],
) -> tuple[dict[str, Any], dict[str, Any]]:
    reject_prohibited_question_fields(pack_review, "formal pack review")
    reject_prohibited_question_fields(teaching_inventory, "teaching inventory")
    coverage = index_coverage(coverage_ledger, require_reviewed=True)
    register_sources = index_register_sources(source_register)
    inventory = index_inventory(teaching_inventory)
    register_id = require_id(
        source_register.get("registerId"),
        "source register id",
    )
    if coverage_ledger["sourceRegisterId"] != register_id:
        raise ValueError("Coverage ledger references a different source register")
    if teaching_inventory["sourceRegisterId"] != register_id:
        raise ValueError("Teaching inventory references a different source register")
    if source_register.get("targetBaselineId") != coverage.baseline_id:
        raise ValueError("Coverage ledger and source register baselines do not match")
    validate_coverage_sources(coverage, register_sources)
    decisions = validate_teaching_decisions(teaching_decisions, inventory)
    review_record_id, pack_reviewed_at = validate_review_header(
        pack_review,
        coverage_ledger,
        source_register,
        teaching_inventory,
        teaching_decisions,
    )

    pack = require_object(pack_review["pack"], "formal pack")
    require_exact_keys(pack, PACK_KEYS, "formal pack")
    pack_id = require_id(pack["packId"], "formal pack id")
    knowledge_pack_version = require_id(
        pack["knowledgePackVersion"],
        "formal knowledge-pack version",
    )
    taxonomy_version = require_id(
        pack["taxonomyVersion"],
        "formal taxonomy version",
    )
    search_index_version = require_id(
        pack["searchIndexVersion"],
        "formal search-index version",
    )
    built_at = require_positive_int(
        pack["builtAtEpochMillis"],
        "formal pack builtAtEpochMillis",
    )
    if built_at < pack_reviewed_at:
        raise ValueError("Formal pack cannot be built before its human review")

    sources, source_context = build_sources(
        pack["sources"],
        register_sources,
        pack_reviewed_at,
    )
    nodes, nodes_by_id, nodes_by_coverage_key = validate_nodes(
        pack["nodes"],
        coverage,
        taxonomy_version,
        pack_reviewed_at,
    )
    node_bindings = validate_node_bindings(
        pack["nodeSourceBindings"],
        coverage,
        nodes_by_id,
        nodes_by_coverage_key,
        source_context,
        pack_reviewed_at,
    )
    relations = validate_relations(
        pack["relations"],
        nodes_by_id,
        source_context,
        pack_reviewed_at,
        taxonomy_version,
    )
    materials, materials_by_id = validate_materials(
        pack["teachingMaterials"],
        source_context,
        inventory,
        decisions,
        pack_reviewed_at,
    )
    material_bindings = validate_material_bindings(
        pack["teachingMaterialBindings"],
        materials_by_id,
        nodes_by_id,
    )
    validate_teaching_coverage(
        source_register,
        materials,
        source_context,
    )

    input_fingerprints = {
        key: str(value)
        for key, value in sorted(pack_review["inputFingerprints"].items())
    }
    artifact = {
        "schemaVersion": 2,
        "artifactType": "FORMAL_REVIEWED_KNOWLEDGE_PACK",
        "manifest": {
            "packId": pack_id,
            "knowledgePackVersion": knowledge_pack_version,
            "taxonomyVersion": taxonomy_version,
            "searchIndexVersion": search_index_version,
            "builtAtEpochMillis": built_at,
            "nodeCount": len(nodes),
            "sourceCount": len(sources),
            "relationCount": len(relations),
            "materialCount": len(materials),
        },
        "coverage": {
            "ledgerId": coverage.ledger_id,
            "targetBaselineId": coverage.baseline_id,
            "subjects": list(REQUIRED_SUBJECTS),
            "moduleCount": coverage.module_count,
            "knowledgePointCount": coverage.point_count,
        },
        "governance": {
            "reviewState": "HUMAN_REVIEWED",
            "reviewRecordId": review_record_id,
            "reviewedAtEpochMillis": pack_reviewed_at,
            "inputFingerprints": input_fingerprints,
            "teachingDecisionSetId": decisions.decision_set_id,
            "originalThirdPartyQuestionTextIncluded": False,
            "originalThirdPartyAnswerTextIncluded": False,
            "searchFeaturesRebuiltByInstaller": True,
            "runtimeTrustRegistryMutationAllowed": False,
            "versionFingerprintAlgorithm": "SHA-256",
            "versionFingerprintDomain": "formal-high-school-knowledge-pack-v2",
            "holdoutCompliance": deep_copy_json(holdout_compliance),
        },
        "nodes": nodes,
        "sources": sources,
        "nodeSourceBindings": node_bindings,
        "relations": relations,
        "teachingMaterials": materials,
        "teachingMaterialBindings": material_bindings,
    }
    artifact["manifest"]["runtimeContentFingerprint"] = (
        reviewed_content_fingerprint(artifact)
    )
    artifact["coverageProofV2"] = build_coverage_proof_v2(
        coverage_ledger=coverage_ledger,
        pack_review=pack_review,
        artifact=artifact,
    )
    return artifact, {
        "packId": pack_id,
        "knowledgePackVersion": knowledge_pack_version,
        "subjectCount": len(REQUIRED_SUBJECTS),
        "moduleCount": coverage.module_count,
        "knowledgePointCount": coverage.point_count,
        "nodeCount": len(nodes),
        "sourceCount": len(sources),
        "relationCount": len(relations),
        "teachingMaterialCount": len(materials),
        "coverageProofSchemaVersion": 2,
        "coverageProofFingerprint": artifact["coverageProofV2"][
            "proofFingerprint"
        ],
        "runtimeContentFingerprint": artifact["manifest"][
            "runtimeContentFingerprint"
        ],
        "humanReviewRequired": True,
        "holdoutComplianceFingerprint": holdout_compliance[
            "complianceFingerprint"
        ],
    }


def _verify_compiler_holdout(
    *,
    coverage_ledger: Mapping[str, Any],
    source_register: Mapping[str, Any],
    teaching_inventory: Mapping[str, Any],
    teaching_decisions: Mapping[str, Any],
    pack_review: Mapping[str, Any],
    content_input_manifest: Mapping[str, Any] | None,
    holdout_manifest: Mapping[str, Any] | None,
    holdout_fingerprint_key: bytes | None,
    holdout_release_registration: Mapping[str, Any] | None,
) -> dict[str, Any]:
    return verify_holdout_isolation(
        build_inputs=_formal_build_inputs(
            coverage_ledger,
            source_register,
            teaching_inventory,
            teaching_decisions,
            pack_review,
        ),
        content_input_manifest=content_input_manifest,
        holdout_manifest=holdout_manifest,
        fingerprint_key=holdout_fingerprint_key,
        holdout_release_registration=holdout_release_registration,
    )


def _formal_build_inputs(
    coverage_ledger: Mapping[str, Any],
    source_register: Mapping[str, Any],
    teaching_inventory: Mapping[str, Any],
    teaching_decisions: Mapping[str, Any],
    pack_review: Mapping[str, Any],
) -> dict[str, Mapping[str, Any]]:
    return {
        "coverageLedger": coverage_ledger,
        "sourceRegister": source_register,
        "teachingInventory": teaching_inventory,
        "teachingDecisions": teaching_decisions,
        "packReviewContent": pack_review,
    }
