from __future__ import annotations

import json
import unittest
from pathlib import Path

from curriculum_coverage.artifacts import knowledge_base_boundary
from curriculum_coverage.extractor import parse_item_start, remove_parent_items
from curriculum_coverage.manifest import validate_manifest
from curriculum_coverage.model import (
    ALLOWED_SUBJECTS,
    PendingItem,
    SourceLine,
    normalize_display,
)
from curriculum_coverage.review import _warning_index
from curriculum_coverage.proposals import validate_decomposition_proposals
from curriculum_coverage.promotion import (
    REVIEW_ATTESTATION,
    build_reviewed_scope,
    review_promotion_status,
)


class CurriculumItemParserTest(unittest.TestCase):
    def test_pdf_control_characters_are_removed_from_review_text(self) -> None:
        self.assertEqual("建模 基本过程", normalize_display("建模\u0007基本过程"))

    def test_decimal_item_retains_full_hierarchy(self) -> None:
        parsed = parse_item_start(
            SourceLine(
                page=12,
                raw="1.2 理解函数概念",
                text="1.2 理解函数概念",
                key="1.2理解函数概念",
            ),
            "NUMBERED",
            None,
            None,
        )

        self.assertIsNotNone(parsed)
        assert parsed is not None
        self.assertEqual((1, 2), parsed.hierarchy)
        self.assertEqual(1, parsed.current_unit)
        self.assertEqual(2, parsed.current_parent)

    def test_circled_item_inherits_numbered_parent(self) -> None:
        parsed = parse_item_start(
            SourceLine(
                page=12,
                raw="① 能结合实例说明",
                text="1 能结合实例说明",
                key="1能结合实例说明",
            ),
            "NUMBERED",
            3,
            2,
        )

        self.assertIsNotNone(parsed)
        assert parsed is not None
        self.assertEqual((3, 2, 1), parsed.hierarchy)
        self.assertEqual("能结合实例说明", parsed.text)

    def test_parent_filter_keeps_only_leaf_requirements(self) -> None:
        def item(hierarchy: tuple[int, ...] | None) -> PendingItem:
            return PendingItem(
                page=1,
                raw_marker="marker",
                hierarchy=hierarchy,
                hierarchy_kind="NUMBERED",
                parts=["text"],
            )

        result = remove_parent_items(
            [
                item((1,)),
                item((1, 1)),
                item((1, 1, 1)),
                item((2,)),
                item(None),
            ]
        )

        self.assertEqual([(1, 1, 1), (2,), None], [entry.hierarchy for entry in result])


class CurriculumManifestTest(unittest.TestCase):
    @staticmethod
    def _manifest_and_register() -> tuple[dict, dict]:
        subjects = []
        sources = []
        for index, subject in enumerate(sorted(ALLOWED_SUBJECTS), start=1):
            source_id = f"source-{index}"
            sources.append(
                {
                    "sourceId": source_id,
                    "baselineId": "moe-high-school-2017-2025",
                    "subjects": [subject],
                    "purposes": ["CURRENT_CURRICULUM_TEXT"],
                    "acquisitionState": "ACQUIRED_UNREVIEWED",
                }
            )
            subjects.append(
                {
                    "subject": subject,
                    "sourceId": source_id,
                    "pdfFile": f"{subject.lower()}.pdf",
                    "coursePages": [1, 2],
                    "modules": [
                        {
                            "slug": "module-one",
                            "name": "模块一",
                            "requirementTypes": ["REQUIRED"],
                            "pages": [1, 2],
                            "startMarker": "内容要求",
                            "mode": "REQUIREMENTS",
                        }
                    ],
                }
            )
        manifest = {
            "schemaVersion": 1,
            "manifestId": "test-manifest",
            "targetBaselineId": "moe-high-school-2017-2025",
            "sourceRegisterId": "test-register",
            "updatedAtEpochMillis": 1,
            "subjects": subjects,
        }
        return manifest, {"registerId": "test-register", "sources": sources}

    def test_valid_manifest_resolves_all_nine_curriculum_sources(self) -> None:
        manifest, register = self._manifest_and_register()

        sources = validate_manifest(manifest, register)

        self.assertEqual(9, len(sources))

    def test_duplicate_subject_is_rejected_before_extraction(self) -> None:
        manifest, register = self._manifest_and_register()
        manifest["subjects"][-1]["subject"] = manifest["subjects"][0]["subject"]

        with self.assertRaisesRegex(ValueError, "Duplicate subject"):
            validate_manifest(manifest, register)


class KnowledgeBaseBoundaryTest(unittest.TestCase):
    def test_teaching_examples_do_not_grant_question_bank_authority(self) -> None:
        boundary = knowledge_base_boundary()

        self.assertEqual(
            {
                "METHOD_MODEL",
                "WORKED_EXAMPLE",
                "COMPLETE_SOLUTION",
                "DERIVATION",
            },
            set(boundary["allowedTeachingSupport"]),
        )
        self.assertEqual(
            {
                "AUTONOMOUS_QUESTION_GENERATION",
                "ASSESSMENT_ITEM_GENERATION",
                "REVIEW_SCHEDULING",
            },
            set(boundary["forbiddenAuthorities"]),
        )


class WarningEvidenceBoundaryTest(unittest.TestCase):
    @staticmethod
    def _manifest() -> dict:
        return {
            "manifestId": "test-manifest",
            "targetBaselineId": "moe-high-school-2017-2025",
            "subjects": [{"subject": "MATH"}],
        }

    @staticmethod
    def _candidates() -> dict:
        return {
            "schemaVersion": 1,
            "artifactId": "curriculum-coverage-candidates-2025-v1",
            "manifestId": "test-manifest",
            "targetBaselineId": "moe-high-school-2017-2025",
            "mappingState": "DRAFT_UNREVIEWED",
            "subjects": [
                {
                    "subject": "MATH",
                    "modules": [
                        {
                            "slug": "modeling",
                            "warnings": ["REQUIRES_MANUAL_ATOMIZATION"],
                        }
                    ],
                }
            ],
        }

    def test_warning_index_preserves_only_explicit_warning_modules(self) -> None:
        self.assertEqual(
            {"MATH": {"modeling": ["REQUIRES_MANUAL_ATOMIZATION"]}},
            _warning_index(self._candidates(), self._manifest()),
        )

    def test_warning_index_rejects_duplicate_modules(self) -> None:
        candidates = self._candidates()
        candidates["subjects"][0]["modules"].append(
            {"slug": "modeling", "warnings": []}
        )

        with self.assertRaisesRegex(ValueError, "Duplicate candidate module"):
            _warning_index(candidates, self._manifest())


class DecompositionProposalBoundaryTest(unittest.TestCase):
    @staticmethod
    def _evidence_and_proposals() -> tuple[dict, dict]:
        boundary = knowledge_base_boundary()
        evidence = {
            "schemaVersion": 1,
            "artifactId": "curriculum-warning-source-evidence-2025-v1",
            "targetBaselineId": "moe-high-school-2017-2025",
            "reviewBoundary": {
                "state": "SOURCE_EVIDENCE_ONLY",
                "formalLedgerMutationAllowed": False,
            },
            "knowledgeBaseBoundary": boundary,
            "subjects": [
                {
                    "subject": "MATH",
                    "modules": [
                        {
                            "slug": "modeling",
                            "promotionAllowed": False,
                            "sourceTextSha256": "ABC",
                            "sourceText": "发现问题、提出问题，分析问题、建立模型。",
                        }
                    ],
                }
            ],
        }
        proposals = {
            "schemaVersion": 1,
            "artifactId": "curriculum-warning-decomposition-proposals-2025-v1",
            "sourceEvidenceArtifactId": evidence["artifactId"],
            "targetBaselineId": evidence["targetBaselineId"],
            "proposalState": "AI_DRAFT_REQUIRES_HUMAN_REVIEW",
            "reviewBoundary": {
                "humanReviewRequired": True,
                "promotionAllowed": False,
                "formalLedgerMutationAllowed": False,
                "formalCoverageContribution": 0,
            },
            "knowledgeBaseBoundary": boundary,
            "subjects": [
                {
                    "subject": "MATH",
                    "modules": [
                        {
                            "slug": "modeling",
                            "sourceTextSha256": "ABC",
                            "proposalState": "AI_DRAFT_REQUIRES_HUMAN_REVIEW",
                            "reviewDecision": "PENDING_HUMAN_REVIEW",
                            "candidates": [
                                {
                                    "candidateId": "math:modeling:discover",
                                    "displayName": "发现并提出问题",
                                    "kind": "PROCESS",
                                    "evidencePhrase": "发现问题、提出问题",
                                },
                                {
                                    "candidateId": "math:modeling:build",
                                    "displayName": "分析问题并建立模型",
                                    "kind": "METHOD",
                                    "evidencePhrase": "分析问题、建立模型",
                                },
                            ],
                        }
                    ],
                }
            ],
        }
        return evidence, proposals

    def test_valid_unreviewed_proposals_contribute_no_formal_coverage(self) -> None:
        evidence, proposals = self._evidence_and_proposals()

        summary = validate_decomposition_proposals(proposals, evidence)

        self.assertEqual(2, summary["candidateCount"])
        self.assertEqual(0, summary["formalCoverageContribution"])
        self.assertFalse(summary["formalLedgerMutationAllowed"])

    def test_proposal_audit_rejects_promotion_before_human_review(self) -> None:
        evidence, proposals = self._evidence_and_proposals()
        proposals["reviewBoundary"]["promotionAllowed"] = True

        with self.assertRaisesRegex(ValueError, "unsafe promotionAllowed"):
            validate_decomposition_proposals(proposals, evidence)


class HumanReviewPromotionGateTest(unittest.TestCase):
    @staticmethod
    def _not_started() -> tuple[dict, dict, dict]:
        evidence, proposals = (
            DecompositionProposalBoundaryTest._evidence_and_proposals()
        )
        decisions = {
            "schemaVersion": 1,
            "artifactId": "curriculum-warning-human-review-decisions-2025-v1",
            "sourceProposalArtifactId": proposals["artifactId"],
            "reviewState": "NOT_STARTED",
            "reviewer": None,
            "reviewedAtEpochMillis": None,
            "decisions": [],
        }
        return evidence, proposals, decisions

    def test_not_started_review_cannot_promote_or_mutate_ledger(self) -> None:
        evidence, proposals, decisions = self._not_started()

        status, _, _ = review_promotion_status(evidence, proposals, decisions)

        self.assertFalse(status["promotionReady"])
        self.assertFalse(status["automaticLedgerMutationAllowed"])
        self.assertEqual(2, status["pendingCount"])
        with self.assertRaisesRegex(ValueError, "review is not complete"):
            build_reviewed_scope(evidence, proposals, decisions)

    def test_completed_review_emits_only_approved_scope(self) -> None:
        evidence, proposals, decisions = self._not_started()
        candidate_ids = [
            candidate["candidateId"]
            for candidate in proposals["subjects"][0]["modules"][0]["candidates"]
        ]
        decisions.update(
            {
                "reviewState": "COMPLETED",
                "reviewer": {
                    "reviewerId": "reviewer-1",
                    "displayName": "课程审校员",
                    "role": "CURRICULUM_REVIEWER",
                    "attestation": REVIEW_ATTESTATION,
                },
                "reviewedAtEpochMillis": 1,
                "decisions": [
                    {"candidateId": candidate_ids[0], "decision": "APPROVE"},
                    {"candidateId": candidate_ids[1], "decision": "REJECT"},
                ],
            }
        )

        artifact, summary = build_reviewed_scope(evidence, proposals, decisions)

        self.assertTrue(summary["promotionReady"])
        self.assertEqual(1, summary["reviewedPointCount"])
        self.assertEqual(candidate_ids[0], artifact["approvedPoints"][0]["candidateId"])
        self.assertFalse(artifact["automaticLedgerMutationAllowed"])

    def test_completed_review_rejects_missing_decisions(self) -> None:
        evidence, proposals, decisions = self._not_started()
        first_candidate = proposals["subjects"][0]["modules"][0]["candidates"][0]
        decisions.update(
            {
                "reviewState": "COMPLETED",
                "reviewer": {
                    "reviewerId": "reviewer-1",
                    "displayName": "课程审校员",
                    "role": "CURRICULUM_REVIEWER",
                    "attestation": REVIEW_ATTESTATION,
                },
                "reviewedAtEpochMillis": 1,
                "decisions": [
                    {
                        "candidateId": first_candidate["candidateId"],
                        "decision": "APPROVE",
                    }
                ],
            }
        )

        with self.assertRaisesRegex(ValueError, "missing decisions"):
            review_promotion_status(evidence, proposals, decisions)


class SourceRegisterLocationTest(unittest.TestCase):
    """来源登记已迁出 APK 资源（R5：Kotlin 零引用，只有 Python 生产管线在读）。

    4 个 CLI 的 `--source-register` 默认参数都指向这个新路径
    （curriculum_coverage/cli.py、curriculum_coverage/warning_evidence_cli.py、
    teaching_sources/epub_cli.py、teaching_sources/review_inventory_cli.py），
    路径一旦回退或丢失，默认值就全部指向空。这条用例钉住：
    新路径可读且是同一份登记（按 registerId 认身份），旧路径不再存在
    （存在 = 它还会被打进 APK 资源，迁移等于没做）。
    """

    @staticmethod
    def _repo_root() -> Path:
        return Path(__file__).resolve().parents[2]

    def test_source_register_lives_in_knowledge_production_and_reads(self) -> None:
        path = self._repo_root() / "knowledge-production" / "source-register-2025-v1.json"
        self.assertTrue(path.is_file(), f"来源登记不在新路径：{path}")
        register = json.loads(path.read_text(encoding="utf-8"))
        self.assertEqual("high-school-knowledge-source-register-2025-v1",
                         register["registerId"])
        self.assertTrue(register["sources"])

    def test_source_register_is_not_packaged_as_apk_resource(self) -> None:
        old_path = (self._repo_root() / "core" / "data" / "src" / "main"
                    / "resources" / "knowledge" / "source-register-2025-v1.json")
        self.assertFalse(old_path.exists(),
                         "来源登记还留在 APK 资源里（会随包发行，迁移未生效）")


if __name__ == "__main__":
    unittest.main()
