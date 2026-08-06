from __future__ import annotations

import copy
import hashlib
import io
import json
import shutil
import subprocess
import sys
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path

TOOLS_ROOT = Path(__file__).resolve().parents[1]
if str(TOOLS_ROOT) not in sys.path:
    sys.path.insert(0, str(TOOLS_ROOT))

from teaching_sources.review_decision_cli import main as review_decision_cli
from teaching_sources.source_artifact_cli import main as source_artifact_cli
from teaching_sources.source_governance import (
    DECISION_REVIEW_BOUNDARY,
    REVIEW_ATTESTATION,
    SOURCE_REVIEW_BOUNDARY,
    audit_review_decision,
    canonical_sha256,
    validate_source_artifact,
    validate_textbook_edition_mapping_source,
)
from curriculum_coverage.model import ALLOWED_SUBJECTS


PROJECT_ROOT = Path(__file__).resolve().parents[2]
REGISTER_PATH = (
    PROJECT_ROOT
    / "core/data/src/main/resources/knowledge/source-register-2025-v1.json"
)


def _fingerprint(character: str = "A") -> str:
    return character * 64


def _mapping_source() -> dict:
    return {
        "sourceId": "candidate:publisher:math-a-2019-v1",
        "title": "普通高中数学 A 版 2019 年审定教材映射",
        "publisher": "Fixture Textbook Publisher",
        "baselineId": "publisher-high-school-math-a-2019",
        "textbookEditionId": "publisher-math-a-2019-v1",
        "subjects": ["MATH"],
        "purposes": ["TEXTBOOK_EDITION_MAPPING"],
        "authorityLevel": "AUTHORIZED_EDUCATION",
        "acquisitionState": "ACQUIRED_REVIEWED",
        "discoveryUri": "https://example.invalid/math-a-2019",
        "documentUri": "https://example.invalid/math-a-2019/metadata.json",
        "contentLengthBytes": 4096,
        "contentFingerprint": _fingerprint(),
        "sourceLocator": "resourceId=math-a-2019; volume=required-1; reviewed=all-metadata",
        "licenseStatus": "REFERENCE_ONLY",
        "contentUsePolicy": "REVIEWED_SYNTHESIS_ONLY",
        "modelUsePolicy": "DERIVED_CONTENT_ONLY",
        "reviewedAtEpochMillis": 1_784_908_800_000,
        "independenceGroup": "fixture-textbook-publisher",
    }


def _source_artifact() -> dict:
    return {
        "schemaVersion": 1,
        "artifactId": "textbook-edition-source-artifact-math-a-2019-v1",
        "submittedById": "source-submitter-1",
        "sourceRegisterId": "high-school-knowledge-source-register-2025-v1",
        "artifactState": "NON_PROMOTING",
        "proposedSource": _mapping_source(),
        "reviewBoundary": copy.deepcopy(SOURCE_REVIEW_BOUNDARY),
    }


def _pin_content(artifact: dict, content: bytes) -> None:
    artifact["proposedSource"]["contentLengthBytes"] = len(content)
    artifact["proposedSource"]["contentFingerprint"] = (
        hashlib.sha256(content).hexdigest().upper()
    )


def _completed_decision(artifact: dict) -> dict:
    return {
        "schemaVersion": 1,
        "decisionSetId": "textbook-edition-source-review-math-a-2019-v1",
        "sourceArtifactId": artifact["artifactId"],
        "sourceArtifactSha256": canonical_sha256(artifact),
        "reviewState": "COMPLETED",
        "reviewer": {
            "reviewerId": "reviewer-1",
            "displayName": "Fixture Reviewer",
            "role": "TEXTBOOK_SOURCE_REVIEWER",
            "attestation": REVIEW_ATTESTATION,
        },
        "reviewedAtEpochMillis": 1_784_995_200_000,
        "decision": "APPROVE",
        "reviewBoundary": copy.deepcopy(DECISION_REVIEW_BOUNDARY),
    }


class TextbookEditionMappingSourceTest(unittest.TestCase):
    def test_accepts_only_a_pinned_reviewed_single_subject_mapping(self) -> None:
        accepted_subjects = set()
        for subject in sorted(ALLOWED_SUBJECTS):
            with self.subTest(subject=subject):
                source = _mapping_source()
                subject_key = subject.lower()
                source.update(
                    {
                        "sourceId": f"candidate:publisher:{subject_key}-a-2019-v1",
                        "subjects": [subject],
                        "textbookEditionId": (
                            f"publisher-{subject_key}-a-2019-v1"
                        ),
                    }
                )
                summary = validate_textbook_edition_mapping_source(source)
                accepted_subjects.add(summary["subject"])
                self.assertEqual(
                    f"publisher-{subject_key}-a-2019-v1",
                    summary["textbookEditionId"],
                )
                self.assertEqual("ACQUIRED_REVIEWED", summary["acquisitionState"])
                self.assertEqual(_fingerprint(), summary["contentFingerprint"])

        self.assertEqual(ALLOWED_SUBJECTS, accepted_subjects)

    def test_rejects_generic_ids_even_when_they_contain_a_year_or_version(self) -> None:
        for edition_id in (
            "baseline-2025",
            "edition-2025-v1",
            "textbook-2025",
            "latest-2025-v1",
            "unknown-v1",
            "pending-2025",
        ):
            with self.subTest(edition_id=edition_id):
                source = _mapping_source()
                source["textbookEditionId"] = edition_id
                with self.assertRaisesRegex(ValueError, "concrete version"):
                    validate_textbook_edition_mapping_source(source)

    def test_rejects_unsafe_or_ambiguous_mapping_fields(self) -> None:
        mutations = {
            "multiple subjects": lambda value: value.update(
                {"subjects": ["MATH", "PHYSICS"]}
            ),
            "unreviewed": lambda value: value.update(
                {"acquisitionState": "ACQUIRED_UNREVIEWED"}
            ),
            "generic edition": lambda value: value.update(
                {"textbookEditionId": "latest"}
            ),
            "mixed purpose": lambda value: value.update(
                {"purposes": ["TEXTBOOK_EDITION_MAPPING", "TEXTBOOK_CATALOG"]}
            ),
            "missing document": lambda value: value.pop("documentUri"),
            "invalid length": lambda value: value.update({"contentLengthBytes": 0}),
            "lowercase hash": lambda value: value.update(
                {"contentFingerprint": "a" * 64}
            ),
            "vague locator": lambda value: value.update({"sourceLocator": "catalog"}),
            "raw model use": lambda value: value.update(
                {"modelUsePolicy": "FULL_CONTENT_ALLOWED"}
            ),
            "direct content use": lambda value: value.update(
                {"contentUsePolicy": "ADAPTATION_ALLOWED"}
            ),
            "unapproved authority": lambda value: value.update(
                {"authorityLevel": "THIRD_PARTY_EDUCATION"}
            ),
            "unknown key": lambda value: value.update({"productionReady": True}),
        }
        for label, mutate in mutations.items():
            with self.subTest(label=label):
                source = _mapping_source()
                mutate(source)
                with self.assertRaises(ValueError):
                    validate_textbook_edition_mapping_source(source)

    def test_licensed_mapping_requires_complete_license_metadata(self) -> None:
        source = _mapping_source()
        source["licenseStatus"] = "LICENSED"

        with self.assertRaisesRegex(ValueError, "requires licenseExpression"):
            validate_textbook_edition_mapping_source(source)

        source.update(
            {
                "licenseExpression": "CC BY 4.0",
                "licenseUri": "https://creativecommons.org/licenses/by/4.0/",
                "attributionText": "Fixture Textbook Publisher",
            }
        )
        self.assertEqual(
            source["sourceId"],
            validate_textbook_edition_mapping_source(source)["sourceId"],
        )


class SourceArtifactGateTest(unittest.TestCase):
    def test_source_artifact_is_always_non_promoting(self) -> None:
        summary = validate_source_artifact(_source_artifact())

        self.assertEqual("NON_PROMOTING", summary["artifactState"])
        self.assertTrue(summary["humanReviewRequired"])
        self.assertFalse(summary["automaticSourceRegisterMutationAllowed"])
        self.assertFalse(summary["automaticKnowledgePackMutationAllowed"])
        self.assertEqual(0, summary["formalCoverageContribution"])
        self.assertFalse(summary["productionReady"])

    def test_rejects_every_attempt_to_claim_promotion_or_production(self) -> None:
        mutations = {
            "state": lambda value: value.update({"artifactState": "PROMOTABLE"}),
            "register mutation": lambda value: value["reviewBoundary"].update(
                {"automaticSourceRegisterMutationAllowed": True}
            ),
            "pack mutation": lambda value: value["reviewBoundary"].update(
                {"automaticKnowledgePackMutationAllowed": True}
            ),
            "coverage": lambda value: value["reviewBoundary"].update(
                {"formalCoverageContribution": 1}
            ),
            "production": lambda value: value["reviewBoundary"].update(
                {"productionReady": True}
            ),
            "unknown": lambda value: value.update({"promotionReady": True}),
        }
        for label, mutate in mutations.items():
            with self.subTest(label=label):
                artifact = _source_artifact()
                mutate(artifact)
                with self.assertRaises(ValueError):
                    validate_source_artifact(artifact)


class ReviewDecisionAuditTest(unittest.TestCase):
    def test_completed_approval_remains_a_non_publishing_audit(self) -> None:
        artifact = _source_artifact()
        summary = audit_review_decision(artifact, _completed_decision(artifact))

        self.assertEqual("COMPLETED", summary["reviewState"])
        self.assertEqual("APPROVE", summary["decision"])
        self.assertFalse(summary["publicationAllowed"])
        self.assertFalse(summary["automaticSourceRegisterMutationAllowed"])
        self.assertFalse(summary["automaticKnowledgePackMutationAllowed"])
        self.assertFalse(summary["promotionReady"])
        self.assertFalse(summary["productionReady"])

    def test_rejects_stale_forged_or_self_promoting_decisions(self) -> None:
        artifact = _source_artifact()
        mutations = {
            "other artifact": lambda value: value.update(
                {"sourceArtifactId": "another-artifact"}
            ),
            "stale hash": lambda value: value.update(
                {"sourceArtifactSha256": _fingerprint("B")}
            ),
            "bad attestation": lambda value: value["reviewer"].update(
                {"attestation": "I_APPROVED_IT"}
            ),
            "publishing": lambda value: value["reviewBoundary"].update(
                {"publicationAllowed": True}
            ),
            "promotion": lambda value: value["reviewBoundary"].update(
                {"promotionReady": True}
            ),
            "unknown": lambda value: value.update(
                {"automaticLedgerMutationAllowed": True}
            ),
        }
        for label, mutate in mutations.items():
            with self.subTest(label=label):
                decision = _completed_decision(artifact)
                mutate(decision)
                with self.assertRaises(ValueError):
                    audit_review_decision(artifact, decision)

    def test_artifact_tampering_invalidates_an_existing_decision(self) -> None:
        artifact = _source_artifact()
        decision = _completed_decision(artifact)
        artifact["proposedSource"]["sourceLocator"] += "; changed=true"

        with self.assertRaisesRegex(ValueError, "fingerprint is stale"):
            audit_review_decision(artifact, decision)

    def test_artifact_submitter_cannot_review_the_same_artifact(self) -> None:
        artifact = _source_artifact()
        decision = _completed_decision(artifact)
        decision["reviewer"]["reviewerId"] = artifact["submittedById"].upper()

        with self.assertRaisesRegex(ValueError, "submitter cannot review"):
            audit_review_decision(artifact, decision)

    def test_review_states_are_exact(self) -> None:
        artifact = _source_artifact()
        not_started = _completed_decision(artifact)
        not_started.update(
            {
                "reviewState": "NOT_STARTED",
                "reviewer": None,
                "reviewedAtEpochMillis": None,
                "decision": None,
            }
        )
        in_progress = _completed_decision(artifact)
        in_progress.update(
            {
                "reviewState": "IN_PROGRESS",
                "reviewedAtEpochMillis": None,
                "decision": None,
            }
        )

        self.assertEqual(
            "NOT_STARTED",
            audit_review_decision(artifact, not_started)["reviewState"],
        )
        self.assertEqual(
            "IN_PROGRESS",
            audit_review_decision(artifact, in_progress)["reviewState"],
        )


class SourceGovernanceCliTest(unittest.TestCase):
    def test_read_only_clis_emit_deterministic_non_publishing_summaries(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            content = b"reviewed textbook fixture bytes\n"
            artifact = _source_artifact()
            _pin_content(artifact, content)
            decision = _completed_decision(artifact)
            (root / "artifact.json").write_text(
                json.dumps(artifact), encoding="utf-8"
            )
            (root / "decision.json").write_text(
                json.dumps(decision), encoding="utf-8"
            )
            (root / "content.bin").write_bytes(content)
            initial_files = sorted(path.name for path in root.iterdir())

            source_stdout = io.StringIO()
            with redirect_stdout(source_stdout):
                source_exit = source_artifact_cli(
                    [
                        "--project-root",
                        str(root),
                        "--artifact",
                        "artifact.json",
                        "--content-artifact",
                        "content.bin",
                    ]
                )
            decision_stdout = io.StringIO()
            with redirect_stdout(decision_stdout):
                decision_exit = review_decision_cli(
                    [
                        "--project-root",
                        str(root),
                        "--source-artifact",
                        "artifact.json",
                        "--content-artifact",
                        "content.bin",
                        "--decisions",
                        "decision.json",
                    ]
                )

            self.assertEqual(0, source_exit)
            self.assertEqual(0, decision_exit)
            source_summary = json.loads(source_stdout.getvalue())
            self.assertTrue(source_summary["contentArtifactVerified"])
            self.assertFalse(source_summary["productionReady"])
            review_summary = json.loads(decision_stdout.getvalue())
            self.assertTrue(review_summary["contentArtifactVerified"])
            self.assertFalse(review_summary["promotionReady"])
            self.assertFalse(review_summary["productionReady"])
            self.assertEqual(initial_files, sorted(path.name for path in root.iterdir()))

    def test_clis_reject_paths_outside_project_and_unsafe_artifacts(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            project = root / "project"
            project.mkdir()
            (project / "content.bin").write_bytes(b"not consulted for invalid artifacts")
            outside = root / "outside.json"
            outside.write_text(json.dumps(_source_artifact()), encoding="utf-8")
            unsafe = _source_artifact()
            unsafe["reviewBoundary"]["productionReady"] = True
            (project / "unsafe.json").write_text(
                json.dumps(unsafe), encoding="utf-8"
            )

            with redirect_stderr(io.StringIO()):
                outside_exit = source_artifact_cli(
                    [
                        "--project-root",
                        str(project),
                        "--artifact",
                        str(outside),
                        "--content-artifact",
                        "content.bin",
                    ]
                )
                unsafe_exit = source_artifact_cli(
                    [
                        "--project-root",
                        str(project),
                        "--artifact",
                        "unsafe.json",
                        "--content-artifact",
                        "content.bin",
                    ]
                )

            self.assertEqual(1, outside_exit)
            self.assertEqual(1, unsafe_exit)

    def test_clis_reject_content_bytes_that_do_not_match_the_artifact(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            artifact = _source_artifact()
            expected_content = b"reviewed bytes"
            _pin_content(artifact, expected_content)
            decision = _completed_decision(artifact)
            (root / "artifact.json").write_text(
                json.dumps(artifact), encoding="utf-8"
            )
            (root / "decision.json").write_text(
                json.dumps(decision), encoding="utf-8"
            )
            (root / "content.bin").write_bytes(b"tampered bytes")

            with redirect_stderr(io.StringIO()):
                source_exit = source_artifact_cli(
                    [
                        "--project-root",
                        str(root),
                        "--artifact",
                        "artifact.json",
                        "--content-artifact",
                        "content.bin",
                    ]
                )
                decision_exit = review_decision_cli(
                    [
                        "--project-root",
                        str(root),
                        "--source-artifact",
                        "artifact.json",
                        "--content-artifact",
                        "content.bin",
                        "--decisions",
                        "decision.json",
                    ]
                )

            self.assertEqual(1, source_exit)
            self.assertEqual(1, decision_exit)

    def test_powershell_register_gate_accepts_only_strict_mapping_shape(self) -> None:
        shell = shutil.which("pwsh") or shutil.which("powershell")
        if shell is None:
            self.skipTest("PowerShell is unavailable")
        register = json.loads(REGISTER_PATH.read_text(encoding="utf-8"))
        register["sources"].append(_mapping_source())
        with tempfile.TemporaryDirectory() as directory:
            register_path = Path(directory) / "register.json"
            register_path.write_text(json.dumps(register), encoding="utf-8")
            result = subprocess.run(
                [
                    shell,
                    "-NoProfile",
                    "-File",
                    str(PROJECT_ROOT / "tools/audit-knowledge-source-register.ps1"),
                    "-ProjectRoot",
                    str(Path(directory)),
                    "-RegisterPath",
                    str(register_path),
                ],
                check=False,
                capture_output=True,
                text=True,
                encoding="utf-8",
                errors="replace",
            )

        self.assertEqual(0, result.returncode, result.stderr)
        report = json.loads(result.stdout)
        self.assertFalse(report["sourceProductionReady"])

    def test_powershell_register_gate_rejects_a_generic_textbook_edition(self) -> None:
        shell = shutil.which("pwsh") or shutil.which("powershell")
        if shell is None:
            self.skipTest("PowerShell is unavailable")
        register = json.loads(REGISTER_PATH.read_text(encoding="utf-8"))
        for edition_id in (
            "baseline-2025",
            "edition-2025-v1",
            "textbook-2025",
        ):
            with self.subTest(edition_id=edition_id):
                unsafe_register = copy.deepcopy(register)
                mapping = _mapping_source()
                mapping["textbookEditionId"] = edition_id
                unsafe_register["sources"].append(mapping)
                with tempfile.TemporaryDirectory() as directory:
                    register_path = Path(directory) / "register.json"
                    register_path.write_text(
                        json.dumps(unsafe_register), encoding="utf-8"
                    )
                    result = subprocess.run(
                        [
                            shell,
                            "-NoProfile",
                            "-File",
                            str(
                                PROJECT_ROOT
                                / "tools/audit-knowledge-source-register.ps1"
                            ),
                            "-ProjectRoot",
                            str(Path(directory)),
                            "-RegisterPath",
                            str(register_path),
                        ],
                        check=False,
                        capture_output=True,
                        text=True,
                        encoding="utf-8",
                        errors="replace",
                    )

                self.assertNotEqual(0, result.returncode)
                self.assertIn("concrete version", result.stderr)

    def test_powershell_register_gate_rejects_paths_outside_project_root(self) -> None:
        shell = shutil.which("pwsh") or shutil.which("powershell")
        if shell is None:
            self.skipTest("PowerShell is unavailable")
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            project = root / "project"
            project.mkdir()
            outside_register = root / "outside-register.json"
            outside_register.write_text(
                REGISTER_PATH.read_text(encoding="utf-8"), encoding="utf-8"
            )
            result = subprocess.run(
                [
                    shell,
                    "-NoProfile",
                    "-File",
                    str(PROJECT_ROOT / "tools/audit-knowledge-source-register.ps1"),
                    "-ProjectRoot",
                    str(project),
                    "-RegisterPath",
                    str(project / ".." / outside_register.name),
                ],
                check=False,
                capture_output=True,
                text=True,
                encoding="utf-8",
                errors="replace",
            )

        self.assertNotEqual(0, result.returncode)
        self.assertIn("RegisterPath must stay within ProjectRoot", result.stderr)

    def test_powershell_and_python_enforce_the_same_licensed_metadata(self) -> None:
        shell = shutil.which("pwsh") or shutil.which("powershell")
        if shell is None:
            self.skipTest("PowerShell is unavailable")
        licensed = _mapping_source()
        licensed["licenseStatus"] = "LICENSED"
        with self.assertRaisesRegex(ValueError, "requires licenseExpression"):
            validate_textbook_edition_mapping_source(licensed)

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)

            def audit(mapping: dict, filename: str) -> subprocess.CompletedProcess[str]:
                register = json.loads(REGISTER_PATH.read_text(encoding="utf-8"))
                register["sources"].append(mapping)
                register_path = root / filename
                register_path.write_text(json.dumps(register), encoding="utf-8")
                return subprocess.run(
                    [
                        shell,
                        "-NoProfile",
                        "-File",
                        str(
                            PROJECT_ROOT
                            / "tools/audit-knowledge-source-register.ps1"
                        ),
                        "-ProjectRoot",
                        str(root),
                        "-RegisterPath",
                        str(register_path),
                    ],
                    check=False,
                    capture_output=True,
                    text=True,
                    encoding="utf-8",
                    errors="replace",
                )

            rejected = audit(licensed, "missing-license-metadata.json")
            complete = copy.deepcopy(licensed)
            complete.update(
                {
                    "licenseExpression": "CC BY 4.0",
                    "licenseUri": "https://creativecommons.org/licenses/by/4.0/",
                    "attributionText": "Fixture Textbook Publisher",
                }
            )
            validate_textbook_edition_mapping_source(complete)
            accepted = audit(complete, "complete-license-metadata.json")

        self.assertNotEqual(0, rejected.returncode)
        self.assertIn("requires licenseExpression", rejected.stderr)
        self.assertEqual(0, accepted.returncode, accepted.stderr)


if __name__ == "__main__":
    unittest.main()
