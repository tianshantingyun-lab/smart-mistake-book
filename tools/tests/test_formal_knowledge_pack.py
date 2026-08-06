from __future__ import annotations

import copy
import hashlib
import json
import unittest
from pathlib import Path
from unittest.mock import patch

from knowledge_pack_production.formal_pack import (
    FORMAL_PACK_REVIEW_ATTESTATION,
    REQUIRED_SUBJECTS,
    TEACHING_REVIEW_ATTESTATION,
    artifact_fingerprint,
    calculate_expected_version_fingerprint as _calculate_expected_version_fingerprint,
    compile_formal_pack as _compile_formal_pack,
    expected_material_id,
    expected_node_identity,
    expected_relation_id,
    production_status as _production_status,
)
from knowledge_pack_production.runtime_contract import (
    validate_formal_artifact_v2,
)
from tests.formal_holdout_fixture import (
    formal_holdout_gate_kwargs,
    pinned_formal_holdout_release,
)


PROJECT_ROOT = Path(__file__).resolve().parents[2]
REVIEW_TIME = 300
SOURCE_REVIEW_TIME = 100
ITEM_REVIEW_TIME = 200
BUILD_TIME = 400
TAXONOMY_VERSION = "fixture-taxonomy-v1"


def calculate_expected_version_fingerprint(
    coverage_ledger: dict,
    source_register: dict,
    teaching_inventory: dict,
    teaching_decisions: dict,
    pack_review: dict,
) -> str:
    with pinned_formal_holdout_release():
        return _calculate_expected_version_fingerprint(
            coverage_ledger,
            source_register,
            teaching_inventory,
            teaching_decisions,
            pack_review,
            **formal_holdout_gate_kwargs(
                coverage_ledger,
                source_register,
                teaching_inventory,
                teaching_decisions,
                pack_review,
            ),
        )


def compile_formal_pack(
    coverage_ledger: dict,
    source_register: dict,
    teaching_inventory: dict,
    teaching_decisions: dict,
    pack_review: dict,
) -> tuple[dict, dict]:
    with pinned_formal_holdout_release():
        return _compile_formal_pack(
            coverage_ledger,
            source_register,
            teaching_inventory,
            teaching_decisions,
            pack_review,
            **formal_holdout_gate_kwargs(
                coverage_ledger,
                source_register,
                teaching_inventory,
                teaching_decisions,
                pack_review,
            ),
        )


def production_status(
    coverage_ledger: dict,
    source_register: dict,
    teaching_inventory: dict,
    teaching_decisions: dict,
    pack_review: dict | None = None,
) -> dict:
    if pack_review is None:
        return _production_status(
            coverage_ledger,
            source_register,
            teaching_inventory,
            teaching_decisions,
        )
    with pinned_formal_holdout_release():
        return _production_status(
            coverage_ledger,
            source_register,
            teaching_inventory,
            teaching_decisions,
            pack_review,
            **formal_holdout_gate_kwargs(
                coverage_ledger,
                source_register,
                teaching_inventory,
                teaching_decisions,
                pack_review,
            ),
        )


def _fingerprint(value: str) -> str:
    return hashlib.sha256(value.encode()).hexdigest().upper()


def _candidate_id(source_id: str, entry_path: str, content_hash: str) -> str:
    payload = f"{source_id}\0{entry_path}\0{content_hash}".encode()
    return f"teaching-review:{hashlib.sha256(payload).hexdigest()[:32]}"


def _register_source(
    source_id: str,
    subject: str,
    *,
    purpose: str,
    independence_group: str,
) -> dict[str, object]:
    purposes = ["TEACHING_REFERENCE", purpose]
    return {
        "sourceId": source_id,
        "title": f"{subject} reviewed source {purpose}",
        "publisher": "Fixture Publisher",
        "baselineId": "fixture-reviewed-v1",
        "subjects": [subject],
        "purposes": purposes,
        "authorityLevel": "THIRD_PARTY_EDUCATION",
        "acquisitionState": "ACQUIRED_REVIEWED",
        "discoveryUri": f"https://example.invalid/{source_id}",
        "documentUri": f"https://example.invalid/{source_id}.epub",
        "contentLengthBytes": 1024,
        "contentFingerprint": _fingerprint(source_id),
        "sourceLocator": f"Reviewed local locator for {source_id}",
        "licenseStatus": "LICENSED",
        "licenseExpression": "CC BY 4.0",
        "licenseUri": "https://creativecommons.org/licenses/by/4.0/",
        "attributionText": f"Fixture attribution for {source_id}",
        "contentUsePolicy": "ADAPTATION_ALLOWED",
        "modelUsePolicy": "FULL_CONTENT_ALLOWED",
        "reviewedAtEpochMillis": SOURCE_REVIEW_TIME,
        "independenceGroup": independence_group,
    }


def _curriculum_source(subject: str) -> dict[str, object]:
    subject_key = subject.lower()
    source_id = f"curriculum:{subject_key}"
    return {
        "sourceId": source_id,
        "title": f"{subject} reviewed curriculum",
        "publisher": "Fixture Ministry",
        "baselineId": "fixture-current-baseline",
        "subjects": [subject],
        "purposes": ["CURRENT_CURRICULUM_TEXT"],
        "authorityLevel": "OFFICIAL",
        "acquisitionState": "ACQUIRED_REVIEWED",
        "discoveryUri": f"https://example.invalid/{subject_key}/curriculum",
        "documentUri": f"https://example.invalid/{subject_key}/curriculum.pdf",
        "contentLengthBytes": 2048,
        "contentFingerprint": _fingerprint(source_id),
        "sourceLocator": f"Reviewed curriculum locator for {subject}",
        "licenseStatus": "PUBLIC_OFFICIAL",
        "licenseExpression": "Fixture official metadata review terms",
        "licenseUri": (
            f"https://example.invalid/{subject_key}/curriculum-license-review"
        ),
        "attributionText": f"Fixture official attribution for {subject}",
        "contentUsePolicy": "REVIEWED_SYNTHESIS_ONLY",
        "modelUsePolicy": "DERIVED_CONTENT_ONLY",
        "reviewedAtEpochMillis": SOURCE_REVIEW_TIME,
        "independenceGroup": f"curriculum-{subject_key}",
    }


def _textbook_source(subject: str) -> dict[str, object]:
    subject_key = subject.lower()
    source_id = f"textbook:{subject_key}:reviewed-edition"
    return {
        "sourceId": source_id,
        "title": f"{subject} reviewed textbook edition mapping",
        "publisher": "Fixture Textbook Publisher",
        "baselineId": "fixture-textbook-edition-v1",
        "subjects": [subject],
        "purposes": ["TEXTBOOK_EDITION_MAPPING"],
        "authorityLevel": "AUTHORIZED_EDUCATION",
        "acquisitionState": "ACQUIRED_REVIEWED",
        "discoveryUri": f"https://example.invalid/{subject_key}/textbook",
        "documentUri": f"https://example.invalid/{subject_key}/textbook-metadata",
        "contentLengthBytes": 1024,
        "contentFingerprint": _fingerprint(source_id),
        "sourceLocator": f"Reviewed textbook edition locator for {subject}",
        "licenseStatus": "REFERENCE_ONLY",
        "licenseExpression": "Fixture reference-only review terms",
        "licenseUri": (
            f"https://example.invalid/{subject_key}/textbook-license-review"
        ),
        "attributionText": f"Fixture textbook attribution for {subject}",
        "contentUsePolicy": "REVIEWED_SYNTHESIS_ONLY",
        "modelUsePolicy": "DERIVED_CONTENT_ONLY",
        "reviewedAtEpochMillis": SOURCE_REVIEW_TIME,
        "independenceGroup": f"textbook-{subject_key}",
    }


def _fixture() -> tuple[dict, dict, dict, dict, dict]:
    ledger_subjects: list[dict] = []
    sources: list[dict] = []
    inventory_candidates: list[dict] = []
    decisions: list[dict] = []
    pack_sources: list[dict] = []
    nodes: list[dict] = []
    node_bindings: list[dict] = []
    relations: list[dict] = []
    materials: list[dict] = []
    material_bindings: list[dict] = []

    teaching_review_id = "review:teaching:fixture"
    for subject in REQUIRED_SUBJECTS:
        subject_key = subject.lower()
        module_slug = "foundation"
        module_key = f"{subject}/{module_slug}"
        module_locator = f"curriculum/{subject_key}/foundation"
        points = [
            {
                "slug": slug,
                "name": f"{subject} {slug.title()}",
                "sourceLocator": f"curriculum/{subject_key}/{slug}",
            }
            for slug in ("alpha", "beta", "gamma")
        ]
        ledger_subjects.append(
            {
                "subject": subject,
                "curriculumSourceId": f"curriculum:{subject_key}",
                "mappingState": "REVIEWED",
                "reviewedAtEpochMillis": ITEM_REVIEW_TIME,
                "modules": [
                    {
                        "slug": module_slug,
                        "name": f"{subject} Foundation",
                        "requirementType": "REQUIRED",
                        "courseStages": ["REQUIRED"],
                        "sourceLocator": module_locator,
                        "knowledgePoints": points,
                    }
                ],
            }
        )

        curriculum = _curriculum_source(subject)
        textbook = _textbook_source(subject)
        method_source = _register_source(
            f"teaching:{subject_key}:method",
            subject,
            purpose="METHOD_REFERENCE",
            independence_group=f"method-{subject_key}",
        )
        example_source = _register_source(
            f"teaching:{subject_key}:example",
            subject,
            purpose="WORKED_EXAMPLE_REFERENCE",
            independence_group=f"example-{subject_key}",
        )
        sources.extend((curriculum, textbook, method_source, example_source))
        for source in (curriculum, textbook, method_source, example_source):
            source_id = str(source["sourceId"])
            pack_sources.append(
                {
                    "sourceId": source_id,
                    "registerSourceId": source_id,
                    "subject": subject,
                    "reviewRecordId": f"review:source:{subject_key}:{source_id.rsplit(':', 1)[-1]}",
                    "reviewedAtEpochMillis": ITEM_REVIEW_TIME,
                }
            )

        topic_id, topic_stable = expected_node_identity(
            TAXONOMY_VERSION,
            module_key,
        )
        nodes.append(
            {
                "coverageKey": module_key,
                "knowledgeNodeId": topic_id,
                "stableCode": topic_stable,
                "subject": subject,
                "displayName": f"{subject} Foundation",
                "canonicalName": f"{subject} Foundation",
                "kind": "TOPIC",
                "granularity": "TOPIC",
                "aliases": [],
                "boundaryMarkdown": None,
                "verificationStatus": "CURATED",
                "parentKnowledgeNodeId": None,
                "reviewRecordId": f"review:node:{subject_key}:foundation",
                "reviewedAtEpochMillis": ITEM_REVIEW_TIME,
            }
        )
        node_bindings.append(
            {
                "knowledgeNodeId": topic_id,
                "sourceId": str(curriculum["sourceId"]),
                "sourceLocator": module_locator,
                "derivationNote": "Human-reviewed curriculum topic mapping.",
                "reviewRecordId": f"review:binding:{subject_key}:foundation",
                "reviewedAtEpochMillis": ITEM_REVIEW_TIME,
            }
        )

        point_ids: dict[str, str] = {}
        for point in points:
            coverage_key = f"{module_key}/{point['slug']}"
            node_id, stable_code = expected_node_identity(
                TAXONOMY_VERSION,
                coverage_key,
            )
            point_ids[str(point["slug"])] = node_id
            nodes.append(
                {
                    "coverageKey": coverage_key,
                    "knowledgeNodeId": node_id,
                    "stableCode": stable_code,
                    "subject": subject,
                    "displayName": point["name"],
                    "canonicalName": point["name"],
                    "kind": "CONCEPT",
                    "granularity": "ATOMIC",
                    "aliases": [f"{subject} {point['slug']} alias"],
                    "boundaryMarkdown": f"Boundary for {subject} {point['slug']}.",
                    "verificationStatus": "SOURCE_GROUNDED",
                    "parentKnowledgeNodeId": topic_id,
                    "reviewRecordId": f"review:node:{subject_key}:{point['slug']}",
                    "reviewedAtEpochMillis": ITEM_REVIEW_TIME,
                }
            )
            node_bindings.append(
                {
                    "knowledgeNodeId": node_id,
                    "sourceId": str(curriculum["sourceId"]),
                    "sourceLocator": point["sourceLocator"],
                    "derivationNote": "Human-reviewed atomic curriculum mapping.",
                    "reviewRecordId": (
                        f"review:binding:{subject_key}:{point['slug']}"
                    ),
                    "reviewedAtEpochMillis": ITEM_REVIEW_TIME,
                }
            )
            node_bindings.append(
                {
                    "knowledgeNodeId": node_id,
                    "sourceId": str(textbook["sourceId"]),
                    "sourceLocator": (
                        f"textbook/{subject_key}/chapter/{point['slug']}"
                    ),
                    "derivationNote": (
                        "Human-reviewed textbook edition/chapter mapping only."
                    ),
                    "reviewRecordId": (
                        f"review:textbook-binding:{subject_key}:{point['slug']}"
                    ),
                    "reviewedAtEpochMillis": ITEM_REVIEW_TIME,
                }
            )

        relation_specs = (
            ("alpha", "beta", "PREREQUISITE_OF"),
            ("beta", "gamma", "RELATED_TO"),
            ("alpha", "gamma", "CONFUSABLE_WITH"),
        )
        for source_slug, target_slug, relation_type in relation_specs:
            source_locator = f"curriculum/{subject_key}/{target_slug}"
            relation_id = expected_relation_id(
                subject,
                point_ids[source_slug],
                point_ids[target_slug],
                relation_type,
                str(curriculum["sourceId"]),
                source_locator,
            )
            relations.append(
                {
                    "relationId": relation_id,
                    "subject": subject,
                    "fromKnowledgeNodeId": point_ids[source_slug],
                    "toKnowledgeNodeId": point_ids[target_slug],
                    "relationType": relation_type,
                    "sourceId": str(curriculum["sourceId"]),
                    "sourceLocator": source_locator,
                    "reviewRecordId": (
                        f"review:relation:{subject_key}:"
                        f"{source_slug}:{target_slug}:{relation_type.lower()}"
                    ),
                    "reviewedAtEpochMillis": ITEM_REVIEW_TIME,
                }
            )

        for material_kind, source, form, content_origin in (
            (
                "method",
                method_source,
                "METHOD_MODEL",
                "INDEPENDENT_HUMAN_SYNTHESIS",
            ),
            (
                "example",
                example_source,
                "WORKED_EXAMPLE",
                "SELF_AUTHORED_ABSTRACT_EXAMPLE",
            ),
        ):
            source_id = str(source["sourceId"])
            entry_path = f"chapters/{subject_key}-{material_kind}.xhtml"
            content_hash = _fingerprint(entry_path)
            candidate_id = _candidate_id(source_id, entry_path, content_hash)
            inventory_candidates.append(
                {
                    "candidateId": candidate_id,
                    "sourceId": source_id,
                    "subjects": [subject],
                    "entryPath": entry_path,
                    "sectionTitle": f"{subject} {material_kind} locator",
                    "normalizedTextSha256": content_hash,
                    "normalizedTextCharacterCount": 100,
                    "candidateTeachingForms": [form],
                    "markerCounts": {"method": 1},
                    "reviewState": "UNREVIEWED_LOCATOR_ONLY",
                }
            )
            decisions.append(
                {
                    "candidateId": candidate_id,
                    "decision": "APPROVE_FOR_HUMAN_SYNTHESIS",
                    "resolvedSubject": subject,
                    "approvedTeachingForms": [form],
                    "sourceLocatorVerified": True,
                    "reviewRecordId": teaching_review_id,
                    "reviewedAtEpochMillis": ITEM_REVIEW_TIME,
                }
            )
            stable_code = (
                f"{TAXONOMY_VERSION}:{subject_key}:teaching:{material_kind}"
            )
            material_id = expected_material_id(stable_code)
            materials.append(
                {
                    "materialId": material_id,
                    "stableCode": stable_code,
                    "subject": subject,
                    "materialType": form,
                    "title": f"{subject} {material_kind.title()}",
                    "summaryMarkdown": (
                        f"Reviewed {material_kind} summary for {subject}."
                    ),
                    "applicabilityMarkdown": (
                        f"Use this {material_kind} when studying {subject}."
                    ),
                    "contentMarkdown": (
                        f"Independently authored {material_kind} pattern for "
                        f"{subject}; it contains no source exercise."
                    ),
                    "boundaryMarkdown": (
                        "Explanation only; never an assessment item or answer key."
                    ),
                    "derivationKind": "REVIEWED_SYNTHESIS",
                    "contentOrigin": content_origin,
                    "sourceId": source_id,
                    "sourceLocator": entry_path,
                    "inventoryCandidateIds": [candidate_id],
                    "originalQuestionIncluded": False,
                    "originalAnswerIncluded": False,
                    "reviewRecordId": (
                        f"review:material:{subject_key}:{material_kind}"
                    ),
                    "reviewedAtEpochMillis": ITEM_REVIEW_TIME,
                }
            )
            material_bindings.append(
                {
                    "materialId": material_id,
                    "knowledgeNodeId": point_ids["alpha"],
                    "role": "PRIMARY",
                }
            )

    ledger = {
        "schemaVersion": 1,
        "ledgerId": "fixture-coverage-ledger-v1",
        "targetBaselineId": "fixture-current-baseline",
        "sourceRegisterId": "fixture-source-register-v1",
        "updatedAtEpochMillis": REVIEW_TIME,
        "subjects": ledger_subjects,
    }
    register = {
        "schemaVersion": 1,
        "registerId": "fixture-source-register-v1",
        "targetBaselineId": "fixture-current-baseline",
        "reviewedAtEpochMillis": REVIEW_TIME,
        "requiredSubjects": list(REQUIRED_SUBJECTS),
        "sourceRequirements": {
            "minimumReviewedTeachingReferencesPerSubject": 2,
            "requireReviewedMethodReferencePerSubject": True,
            "requireReviewedWorkedExampleReferencePerSubject": True,
        },
        "sources": sources,
    }
    inventory = {
        "schemaVersion": 1,
        "inventoryId": "fixture-teaching-inventory-v1",
        "manifestId": "fixture-teaching-manifest-v1",
        "sourceRegisterId": "fixture-source-register-v1",
        "updatedAtEpochMillis": REVIEW_TIME,
        "boundary": {
            "state": "AI_LOCATOR_INVENTORY_REQUIRES_HUMAN_REVIEW",
            "rawTeachingTextIncluded": False,
            "humanReviewerMustOpenPinnedLocalSource": True,
            "allowedTeachingForms": [
                "METHOD_MODEL",
                "WORKED_EXAMPLE",
                "COMPLETE_SOLUTION",
                "DERIVATION",
            ],
            "questionBankAuthority": False,
            "autonomousQuestionGenerationAuthority": False,
            "assessmentAuthority": False,
            "reviewSchedulingAuthority": False,
            "learningEvidenceWriteAuthority": False,
            "formalKnowledgeCoverageContribution": 0,
        },
        "candidates": inventory_candidates,
    }
    teaching_decisions = {
        "schemaVersion": 1,
        "decisionSetId": "fixture-teaching-decisions-v1",
        "inventoryId": "fixture-teaching-inventory-v1",
        "inventoryCandidateCount": len(inventory_candidates),
        "reviewState": "COMPLETED",
        "reviewer": {"reviewRecordId": teaching_review_id},
        "reviewedAtEpochMillis": ITEM_REVIEW_TIME,
        "sourceTextAttestation": TEACHING_REVIEW_ATTESTATION,
        "decisions": decisions,
        "automaticKnowledgePackMutationAllowed": False,
    }
    review = {
        "schemaVersion": 1,
        "artifactId": "fixture-formal-pack-review-v1",
        "reviewState": "HUMAN_REVIEWED",
        "reviewRecordId": "review:formal-pack:fixture",
        "reviewedAtEpochMillis": REVIEW_TIME,
        "attestation": FORMAL_PACK_REVIEW_ATTESTATION,
        "inputFingerprints": {
            "coverageLedger": artifact_fingerprint(ledger),
            "sourceRegister": artifact_fingerprint(register),
            "teachingInventory": artifact_fingerprint(inventory),
            "teachingDecisions": artifact_fingerprint(teaching_decisions),
        },
        "expectedVersionFingerprint": "0" * 64,
        "pack": {
            "packId": "fixture-formal-pack-v1",
            "knowledgePackVersion": "fixture-formal-pack-v1",
            "taxonomyVersion": TAXONOMY_VERSION,
            "searchIndexVersion": "fixture-search-v1",
            "builtAtEpochMillis": BUILD_TIME,
            "sources": pack_sources,
            "nodes": nodes,
            "nodeSourceBindings": node_bindings,
            "relations": relations,
            "teachingMaterials": materials,
            "teachingMaterialBindings": material_bindings,
        },
    }
    review["expectedVersionFingerprint"] = (
        calculate_expected_version_fingerprint(
            ledger,
            register,
            inventory,
            teaching_decisions,
            review,
        )
    )
    return ledger, register, inventory, teaching_decisions, review


def _load_json(relative_path: str) -> dict:
    return json.loads((PROJECT_ROOT / relative_path).read_text(encoding="utf-8"))


class FormalKnowledgePackCompilerTest(unittest.TestCase):
    def test_direct_compile_rejects_invalid_content_before_holdout_hmac(self) -> None:
        ledger, register, inventory, decisions, review = _fixture()
        invalid_review = copy.deepcopy(review)
        invalid_review["pack"]["teachingMaterials"][0]["questionText"] = (
            "Untrusted copied question content"
        )

        with patch(
            "knowledge_pack_production.holdout_gate._fingerprint",
            side_effect=AssertionError(
                "Holdout HMAC must not run before formal semantic validation"
            ),
        ):
            with self.assertRaisesRegex(
                ValueError,
                "prohibited reconstructable question field",
            ):
                _compile_formal_pack(
                    ledger,
                    register,
                    inventory,
                    decisions,
                    invalid_review,
                )

    def test_release_compiler_rejects_missing_holdout_isolation_proof(self) -> None:
        ledger, register, inventory, decisions, review = _fixture()

        with self.assertRaisesRegex(
            ValueError,
            "requires a content-input manifest",
        ):
            _compile_formal_pack(
                ledger,
                register,
                inventory,
                decisions,
                review,
            )

    def test_current_repository_status_is_explicitly_fail_closed(self) -> None:
        report = production_status(
            _load_json(
                "knowledge-production/knowledge-coverage-ledger-2025-v1.json"
            ),
            _load_json(
                "core/data/src/main/resources/knowledge/"
                "source-register-2025-v1.json"
            ),
            _load_json(
                "knowledge-production/open-teaching-review-inventory-2026-v1.json"
            ),
            _load_json(
                "knowledge-production/"
                "open-teaching-human-review-decisions-2026-v1.json"
            ),
        )

        self.assertFalse(report["productionReady"])
        self.assertFalse(report["formalPackEmitted"])
        self.assertEqual(9, report["coverage"]["subjectCount"])
        self.assertEqual(0, report["coverage"]["reviewedSubjectCount"])
        self.assertEqual(1322, report["coverage"]["pointCount"])
        self.assertEqual(666, report["teachingReview"]["candidateCount"])
        self.assertEqual(666, report["teachingReview"]["pendingDecisionCount"])
        self.assertIn(
            "Coverage ledger has 0/9 reviewed subjects; "
            "1322/1322 points remain unreviewed",
            report["blockers"],
        )

    def test_complete_human_review_compiles_deterministically(self) -> None:
        ledger, register, inventory, decisions, review = _fixture()

        artifact, summary = compile_formal_pack(
            ledger,
            register,
            inventory,
            decisions,
            review,
        )
        status = production_status(
            ledger,
            register,
            inventory,
            decisions,
            review,
        )
        second_artifact, _ = compile_formal_pack(
            ledger,
            register,
            inventory,
            decisions,
            copy.deepcopy(review),
        )

        self.assertEqual(artifact, second_artifact)
        validate_formal_artifact_v2(artifact)
        self.assertEqual(2, artifact["schemaVersion"])
        self.assertEqual(2, artifact["coverageProofV2"]["schemaVersion"])
        self.assertRegex(
            artifact["manifest"]["runtimeContentFingerprint"],
            r"^[0-9a-f]{64}$",
        )
        self.assertRegex(
            artifact["coverageProofV2"]["proofFingerprint"],
            r"^[0-9a-f]{64}$",
        )
        self.assertEqual(
            set(REQUIRED_SUBJECTS),
            {
                subject["subject"]
                for subject in artifact["coverageProofV2"]["subjects"]
            },
        )
        self.assertEqual(
            review["expectedVersionFingerprint"],
            artifact["manifest"]["contentFingerprint"],
        )
        self.assertEqual(9, summary["subjectCount"])
        self.assertEqual(27, summary["knowledgePointCount"])
        self.assertTrue(summary["formalPackEmitted"])
        self.assertFalse(summary["runtimeActivationAuthorized"])
        self.assertTrue(status["productionReady"])
        self.assertFalse(status["formalPackEmitted"])
        self.assertFalse(
            artifact["governance"]["runtimeTrustRegistryMutationAllowed"]
        )
        self.assertNotIn("reviewer", json.dumps(artifact).casefold())

    def test_rejects_draft_coverage_and_empty_human_decisions(self) -> None:
        ledger, register, inventory, decisions, review = _fixture()
        draft = copy.deepcopy(ledger)
        draft["subjects"][0]["mappingState"] = "DRAFT_UNREVIEWED"
        with self.assertRaisesRegex(ValueError, "DRAFT_UNREVIEWED"):
            compile_formal_pack(draft, register, inventory, decisions, review)

        empty = copy.deepcopy(decisions)
        empty.update(
            {
                "reviewState": "NOT_STARTED",
                "reviewer": None,
                "reviewedAtEpochMillis": None,
                "sourceTextAttestation": None,
                "decisions": [],
            }
        )
        with self.assertRaisesRegex(ValueError, "not COMPLETED"):
            compile_formal_pack(ledger, register, inventory, empty, review)

    def test_rejects_missing_subject_and_unreviewed_source(self) -> None:
        ledger, register, inventory, decisions, review = _fixture()
        missing_subject = copy.deepcopy(ledger)
        missing_subject["subjects"].pop()
        with self.assertRaisesRegex(ValueError, "all nine subjects"):
            compile_formal_pack(
                missing_subject,
                register,
                inventory,
                decisions,
                review,
            )

        unreviewed = copy.deepcopy(register)
        unreviewed["sources"][0]["acquisitionState"] = "ACQUIRED_UNREVIEWED"
        unreviewed_review = copy.deepcopy(review)
        unreviewed_review["inputFingerprints"]["sourceRegister"] = (
            artifact_fingerprint(unreviewed)
        )
        with self.assertRaisesRegex(ValueError, "not ACQUIRED_REVIEWED"):
            compile_formal_pack(
                ledger,
                unreviewed,
                inventory,
                decisions,
                unreviewed_review,
            )

    def test_rejects_stable_id_alias_and_relation_conflicts(self) -> None:
        ledger, register, inventory, decisions, review = _fixture()
        bad_id = copy.deepcopy(review)
        bad_id["pack"]["nodes"][1]["stableCode"] = (
            bad_id["pack"]["nodes"][0]["stableCode"]
        )
        with self.assertRaisesRegex(ValueError, "stable identity"):
            compile_formal_pack(
                ledger,
                register,
                inventory,
                decisions,
                bad_id,
            )

        alias_collision = copy.deepcopy(review)
        first_subject_nodes = [
            node
            for node in alias_collision["pack"]["nodes"]
            if node["subject"] == REQUIRED_SUBJECTS[0]
            and node["granularity"] == "ATOMIC"
        ]
        first_subject_nodes[1]["aliases"].append(
            first_subject_nodes[0]["canonicalName"]
        )
        with self.assertRaisesRegex(ValueError, "alias/name conflict"):
            compile_formal_pack(
                ledger,
                register,
                inventory,
                decisions,
                alias_collision,
            )

        cycle = copy.deepcopy(review)
        prerequisite = next(
            relation
            for relation in cycle["pack"]["relations"]
            if relation["relationType"] == "PREREQUISITE_OF"
        )
        reverse = copy.deepcopy(prerequisite)
        reverse["fromKnowledgeNodeId"], reverse["toKnowledgeNodeId"] = (
            reverse["toKnowledgeNodeId"],
            reverse["fromKnowledgeNodeId"],
        )
        reverse["relationId"] = expected_relation_id(
            reverse["subject"],
            reverse["fromKnowledgeNodeId"],
            reverse["toKnowledgeNodeId"],
            reverse["relationType"],
            reverse["sourceId"],
            reverse["sourceLocator"],
        )
        cycle["pack"]["relations"].append(reverse)
        with self.assertRaisesRegex(ValueError, "contains a cycle"):
            compile_formal_pack(
                ledger,
                register,
                inventory,
                decisions,
                cycle,
            )

    def test_rejects_reconstructable_third_party_question_fields(self) -> None:
        ledger, register, inventory, decisions, review = _fixture()
        question = copy.deepcopy(review)
        question["pack"]["teachingMaterials"][0]["questionText"] = (
            "Copied third-party exercise"
        )
        with self.assertRaisesRegex(
            ValueError,
            "prohibited reconstructable question field",
        ):
            compile_formal_pack(
                ledger,
                register,
                inventory,
                decisions,
                question,
            )

        original = copy.deepcopy(review)
        original["pack"]["teachingMaterials"][0][
            "originalQuestionIncluded"
        ] = True
        with self.assertRaisesRegex(ValueError, "original question"):
            compile_formal_pack(
                ledger,
                register,
                inventory,
                decisions,
                original,
            )

    def test_each_method_requires_source_and_human_review_record(self) -> None:
        ledger, register, inventory, decisions, review = _fixture()
        missing_source = copy.deepcopy(review)
        missing_source["pack"]["teachingMaterials"][0]["sourceId"] = (
            "missing:source"
        )
        with self.assertRaisesRegex(ValueError, "missing source"):
            compile_formal_pack(
                ledger,
                register,
                inventory,
                decisions,
                missing_source,
            )

        missing_review = copy.deepcopy(review)
        missing_review["pack"]["teachingMaterials"][0]["reviewRecordId"] = ""
        with self.assertRaisesRegex(ValueError, "review record id"):
            compile_formal_pack(
                ledger,
                register,
                inventory,
                decisions,
                missing_review,
            )

    def test_version_fingerprint_covers_aliases_and_review_proofs(self) -> None:
        ledger, register, inventory, decisions, review = _fixture()
        stale_alias = copy.deepcopy(review)
        stale_alias["pack"]["nodes"][1]["aliases"].append("new reviewed alias")
        with self.assertRaisesRegex(ValueError, "version fingerprint"):
            compile_formal_pack(
                ledger,
                register,
                inventory,
                decisions,
                stale_alias,
            )

        stale_proof = copy.deepcopy(review)
        stale_proof["pack"]["nodes"][1]["reviewRecordId"] = (
            "review:node:changed-proof"
        )
        with self.assertRaisesRegex(ValueError, "version fingerprint"):
            compile_formal_pack(
                ledger,
                register,
                inventory,
                decisions,
                stale_proof,
            )

        stale_input = copy.deepcopy(review)
        stale_input["inputFingerprints"]["sourceRegister"] = "f" * 64
        with self.assertRaisesRegex(ValueError, "stale or mismatched"):
            compile_formal_pack(
                ledger,
                register,
                inventory,
                decisions,
                stale_input,
            )


if __name__ == "__main__":
    unittest.main()
