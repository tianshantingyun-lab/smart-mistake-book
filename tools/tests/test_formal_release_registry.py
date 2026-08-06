from __future__ import annotations

import copy
import hashlib
import io
import json
import os
import subprocess
import tempfile
import unittest
from contextlib import contextmanager, redirect_stdout
from pathlib import Path
from unittest.mock import patch

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import rsa

from knowledge_pack_production.holdout_gate import FINGERPRINT_KEY_PATH_ENV
from knowledge_pack_production.input_hashes import canonical_fingerprint
from knowledge_pack_production import (
    bounded_io,
    cli as formal_cli,
    formal_pack,
    holdout_gate,
    release_cli,
    release_registry,
)
from knowledge_pack_production.release_registry import (
    CONTENT_POLICY_ATTESTATION,
    PRIVATE_KEY_PASSWORD_ENV,
    PRIVATE_KEY_PATH_ENV,
    SIGNATURE_ALGORITHM,
    build_release_signing_registration,
    generate_signed_release as _generate_signed_release,
    release_signing_registration_fingerprint,
    render_formal_pack_asset_kotlin,
    render_formal_release_registry_kotlin,
)
from knowledge_pack_production.release_cli import main as release_cli_main
from tests.formal_release_kotlin_stubs import (
    DATABASE_STUB,
    MODEL_STUB,
    RUNTIME_DATABASE_STUB,
)
from tests.formal_holdout_fixture import (
    formal_holdout_release_gate_kwargs,
    pinned_formal_holdout_release,
)
from tests.test_formal_knowledge_pack import _fixture, compile_formal_pack


STANDARD_CODES = {
    "CHINESE": "SB0101",
    "ENGLISH": "SB0102",
    "MATH": "SB0201",
    "HISTORY": "SB0307",
    "GEOGRAPHY": "SB0308",
    "POLITICS": "SB0310",
    "PHYSICS": "SB0401",
    "CHEMISTRY": "SB0402",
    "BIOLOGY": "SB0403",
}
PROJECT_ROOT = Path(__file__).resolve().parents[2]
_LATEST_RELEASE_HOLDOUT_KWARGS: dict = {}


def _artifact() -> dict:
    global _LATEST_RELEASE_HOLDOUT_KWARGS
    ledger, register, inventory, decisions, review = _fixture()
    artifact, _ = compile_formal_pack(
        ledger,
        register,
        inventory,
        decisions,
        review,
    )
    _LATEST_RELEASE_HOLDOUT_KWARGS = formal_holdout_release_gate_kwargs(
        ledger,
        register,
        inventory,
        decisions,
        review,
    )
    return artifact


def generate_signed_release(
    artifact: dict,
    governance: dict,
    private_key_pem: bytes,
    private_key_password: bytes | None = None,
):
    if not _LATEST_RELEASE_HOLDOUT_KWARGS:
        raise AssertionError("Release holdout fixture was not initialized")
    registration = _fixture_release_signing_registration()
    with pinned_formal_holdout_release(), _pinned_release_signing(
        registration
    ):
        return _generate_signed_release(
            artifact,
            governance,
            private_key_pem,
            private_key_password,
            **_LATEST_RELEASE_HOLDOUT_KWARGS,
            release_signing_registration=registration,
        )


def _governance(artifact: dict) -> dict:
    source_evidence = []
    for source in artifact["sources"]:
        license_class = {
            "PUBLIC_OFFICIAL": "PUBLIC_OFFICIAL_METADATA",
            "REFERENCE_ONLY": "REFERENCE_ONLY",
            "LICENSED": "PERMISSIVE",
        }[source["licenseStatus"]]
        is_textbook = source["sourceType"] == "TEXTBOOK"
        source_evidence.append(
            {
                "sourceId": source["sourceId"],
                "sourceVersion": source["edition"],
                "licenseClass": license_class,
                "licenseEvidenceFingerprint": hashlib.sha256(
                    f"license:{source['sourceId']}".encode()
                ).hexdigest(),
                "evidenceOrigin": "HUMAN_VERIFIED",
                "automationPermission": (
                    "ALLOWED"
                    if source["licenseStatus"] == "LICENSED"
                    else "METADATA_ONLY"
                ),
                "reviewStatus": "HUMAN_REVIEWED",
                "reviewRecordId": source["reviewRecordId"],
                "reviewedAtEpochMillis": source["reviewedAtEpochMillis"],
                "retrievedAtEpochMillis": 100,
                "editionEvidenceLocator": (
                    f"{source['sourceUri']}#edition" if is_textbook else None
                ),
                "editionEvidenceFingerprint": (
                    hashlib.sha256(
                        f"edition:{source['sourceId']}".encode()
                    ).hexdigest()
                    if is_textbook
                    else None
                ),
            }
        )

    mappings = []
    for subject, standard_code in STANDARD_CODES.items():
        subject_nodes = [
            node for node in artifact["nodes"] if node["subject"] == subject
        ]
        subject_sources = [
            source
            for source in artifact["sources"]
            if source["subject"] == subject
        ]
        root = next(node for node in subject_nodes if node["kind"] == "TOPIC")
        mappings.append(
            {
                "subject": subject,
                "officialStandardCode": standard_code,
                "rootNodeStableCode": root["stableCode"],
                "stableCodeNamespace": (
                    f"{artifact['manifest']['taxonomyVersion']}:"
                    f"{subject.lower()}:"
                ),
                "curriculumSourceId": next(
                    source["sourceId"]
                    for source in subject_sources
                    if source["sourceType"]
                    == "OFFICIAL_CURRICULUM_STANDARD"
                ),
                "textbookEditionSourceIds": sorted(
                    source["sourceId"]
                    for source in subject_sources
                    if source["sourceType"] == "TEXTBOOK"
                ),
            }
        )
    return {
        "schemaVersion": 1,
        "candidate": {
            "candidateId": "candidate.fixture.release.v1",
            "producerOrganizationId": "producer.fixture",
            "preparedAtEpochMillis": 400,
            "activationGeneration": 7,
        },
        "sourceEvidence": source_evidence,
        "subjectMappings": mappings,
        "independentReview": {
            "reviewRecordId": "review.fixture.release.v1",
            "reviewerOrganizationId": "reviewer.fixture",
            "reviewedAtEpochMillis": 500,
            "decision": "APPROVED",
            "contentPolicyAttestation": CONTENT_POLICY_ATTESTATION,
            "formalArtifactFingerprint": artifact["manifest"][
                "contentFingerprint"
            ],
            "runtimeContentFingerprint": artifact["manifest"][
                "runtimeContentFingerprint"
            ],
            "coverageProofFingerprint": artifact["coverageProofV2"][
                "proofFingerprint"
            ],
        },
        "signing": {
            "keyId": "fixture-formal-key-v1",
            "algorithm": SIGNATURE_ALGORITHM,
            "signedAtEpochMillis": 600,
            "validFromEpochMillis": 0,
            "validUntilEpochMillis": 1_000,
            "revokedAtEpochMillis": None,
            "revocationEvidenceFingerprint": None,
        },
    }


def _private_key_pem(size: int = 2_048) -> bytes:
    private_key = rsa.generate_private_key(
        public_exponent=65_537,
        key_size=size,
    )
    return private_key.private_bytes(
        encoding=serialization.Encoding.PEM,
        format=serialization.PrivateFormat.PKCS8,
        encryption_algorithm=serialization.NoEncryption(),
    )


_FIXTURE_RELEASE_PRIVATE_KEY_PEM = _private_key_pem()


def _fixture_release_signing_registration(
    private_key_pem: bytes = _FIXTURE_RELEASE_PRIVATE_KEY_PEM,
) -> dict:
    private_key = serialization.load_pem_private_key(
        private_key_pem,
        password=None,
    )
    signing_key = {
        "keyId": "fixture-formal-key-v1",
        "algorithm": SIGNATURE_ALGORITHM,
        "x509PublicKeyHex": private_key.public_key()
        .public_bytes(
            serialization.Encoding.DER,
            serialization.PublicFormat.SubjectPublicKeyInfo,
        )
        .hex(),
        "validFromEpochMillis": 0,
        "validUntilEpochMillis": 1_000,
        "revokedAtEpochMillis": None,
        "revocationEvidenceFingerprint": None,
    }
    return build_release_signing_registration(
        registration_id="fixture-formal-release-signing-v1",
        release_generation=7,
        signing_key=signing_key,
    )


@contextmanager
def _pinned_release_signing(registration: dict):
    fingerprint = release_signing_registration_fingerprint(registration)
    with patch.object(
        release_registry,
        "PINNED_RELEASE_SIGNING_REGISTRATION",
        (fingerprint, registration["releaseGeneration"]),
    ):
        yield


def _refresh_artifact_version_fingerprint(artifact: dict) -> None:
    unsigned = copy.deepcopy(artifact)
    unsigned["manifest"].pop("contentFingerprint", None)
    artifact["manifest"]["contentFingerprint"] = canonical_fingerprint(
        "formal-high-school-knowledge-pack-v2",
        unsigned,
    )


class FormalReleaseRegistryTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.private_key_pem = _FIXTURE_RELEASE_PRIVATE_KEY_PEM

    def test_generates_deterministic_public_asset_registry_and_receipt(self) -> None:
        artifact = _artifact()
        governance = _governance(artifact)

        first = generate_signed_release(
            artifact,
            governance,
            self.private_key_pem,
        )
        second = generate_signed_release(
            copy.deepcopy(artifact),
            copy.deepcopy(governance),
            self.private_key_pem,
        )

        self.assertEqual(first, second)
        self.assertIn("ReviewedKnowledgePack(", first.asset_kotlin)
        self.assertIn("FormalKnowledgePackCoverageProofV2(", first.asset_kotlin)
        self.assertIn("GeneratedFormalKnowledgePackAsset.pack", first.registry_kotlin)
        self.assertIn("FormalKnowledgePackSigningKey(", first.registry_kotlin)
        self.assertIn("signatureHex =", first.registry_kotlin)
        self.assertNotIn("ReviewedKnowledgeNode(", first.registry_kotlin)
        self.assertNotIn("PRIVATE KEY", first.asset_kotlin)
        self.assertNotIn("PRIVATE KEY", first.registry_kotlin)
        self.assertFalse(first.receipt["containsPrivateKeyMaterial"])
        self.assertRegex(
            first.receipt["signingKey"]["x509PublicKeyHex"],
            r"^[0-9a-f]+$",
        )
        self.assertRegex(first.receipt["signatureHex"], r"^[0-9a-f]+$")
        self.assertEqual(
            artifact["manifest"]["runtimeContentFingerprint"],
            first.receipt["runtimeContentFingerprint"],
        )

    def test_direct_render_entry_points_cannot_create_eligible_sources(
        self,
    ) -> None:
        artifact = _artifact()
        ordinary_candidate = {"candidateId": "caller-controlled"}
        ordinary_review = {"decision": "APPROVED"}
        ordinary_key = {
            "keyId": "caller-controlled",
            "x509PublicKeyHex": "00",
        }
        ordinary_activation = {
            "activationGeneration": 7,
            "signatureHex": "00",
        }

        with self.assertRaisesRegex(ValueError, "not authorized"):
            render_formal_pack_asset_kotlin(
                artifact,
                ordinary_candidate,
                ordinary_review,
            )
        with self.assertRaisesRegex(ValueError, "not authorized"):
            render_formal_release_registry_kotlin(
                ordinary_key,
                ordinary_activation,
                7,
            )
        with self.assertRaisesRegex(ValueError, "internal authorization"):
            release_registry._render_formal_pack_asset_kotlin(
                artifact,
                ordinary_candidate,
                ordinary_review,
                authorization=object(),
            )
        with self.assertRaisesRegex(ValueError, "internal authorization"):
            release_registry._render_formal_release_registry_kotlin(
                ordinary_key,
                ordinary_activation,
                7,
                authorization=object(),
            )

    def test_reflection_cannot_forge_renderer_authorization(self) -> None:
        artifact = _artifact()
        forged = object()
        with patch.object(
            release_registry,
            "_ACTIVE_RENDER_AUTHORIZATIONS",
            {forged: object()},
            create=True,
        ):
            with self.assertRaisesRegex(ValueError, "internal authorization"):
                release_registry._render_formal_pack_asset_kotlin(
                    artifact,
                    {"candidateId": "forged"},
                    {"decision": "APPROVED"},
                    authorization=forged,
                )
            with self.assertRaisesRegex(ValueError, "internal authorization"):
                release_registry._render_formal_release_registry_kotlin(
                    {"keyId": "forged"},
                    {"signatureHex": "00"},
                    7,
                    authorization=forged,
                )

    def test_release_preflights_every_untrusted_input_before_crypto_or_copy(
        self,
    ) -> None:
        artifact = _artifact()
        governance = _governance(artifact)
        registration = _fixture_release_signing_registration()
        base = copy.deepcopy(_LATEST_RELEASE_HOLDOUT_KWARGS)

        malformed_build = copy.deepcopy(base)
        malformed_build["holdout_build_inputs"]["packReviewContent"]["pack"][
            "nodes"
        ][0]["callerTrusted"] = True
        malformed_content = copy.deepcopy(base)
        malformed_content["content_input_manifest"]["surfaceSummaries"][
            "rawText"
        ]["callerTrusted"] = True
        malformed_holdout = copy.deepcopy(base)
        malformed_holdout["holdout_manifest"]["entries"][0][
            "callerTrusted"
        ] = True
        malformed_holdout_registration = copy.deepcopy(base)
        malformed_holdout_registration["holdout_release_registration"][
            "signingKey"
        ]["callerTrusted"] = True
        malformed_release_registration = copy.deepcopy(registration)
        malformed_release_registration["signingKey"]["callerTrusted"] = True
        cases = (
            ("build inputs", malformed_build, registration),
            ("content manifest", malformed_content, registration),
            ("holdout manifest", malformed_holdout, registration),
            (
                "holdout registration",
                malformed_holdout_registration,
                registration,
            ),
            ("release registration", base, malformed_release_registration),
        )

        with pinned_formal_holdout_release(), _pinned_release_signing(
            registration
        ):
            for label, kwargs, candidate_registration in cases:
                with self.subTest(label=label), patch.object(
                    release_registry,
                    "validate_formal_artifact_v2",
                    side_effect=AssertionError(
                        "artifact validation ran before complete preflight"
                    ),
                ), patch.object(
                    formal_pack,
                    "deep_copy_json",
                    side_effect=AssertionError(
                        "deepcopy ran before complete preflight"
                    ),
                ), patch.object(
                    holdout_gate,
                    "_fingerprint",
                    side_effect=AssertionError(
                        "HMAC ran before complete preflight"
                    ),
                ), patch.object(
                    holdout_gate,
                    "_verify_signature",
                    side_effect=AssertionError(
                        "holdout key read ran before complete preflight"
                    ),
                ), patch.object(
                    release_registry,
                    "_validate_rsa_public_key",
                    side_effect=AssertionError(
                        "public-key read ran before complete preflight"
                    ),
                ), patch.object(
                    release_registry,
                    "_load_rsa_private_key",
                    side_effect=AssertionError(
                        "private-key read ran before complete preflight"
                    ),
                ):
                    with self.assertRaises(ValueError):
                        _generate_signed_release(
                            artifact,
                            governance,
                            self.private_key_pem,
                            **kwargs,
                            release_signing_registration=(
                                candidate_registration
                            ),
                        )

    def test_release_cli_rejects_output_input_and_key_path_aliases(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            output = root / "generated"
            paths = {
                "formal": root / "formal.json",
                "governance": root / "governance.json",
                "build": root / "build.json",
                "content": root / "content.json",
                "holdout": root / "holdout.json",
                "holdout_registration": root / "holdout-registration.json",
                "release_registration": root / "release-registration.json",
                "private_key": root / "private.pem",
                "fingerprint_key": root / "fingerprint.bin",
            }

            def arguments(*, formal: Path, receipt: Path) -> tuple[str, ...]:
                return (
                    "--formal-pack",
                    str(formal),
                    "--governance",
                    str(paths["governance"]),
                    "--formal-build-inputs",
                    str(paths["build"]),
                    "--content-input-manifest",
                    str(paths["content"]),
                    "--holdout-manifest",
                    str(paths["holdout"]),
                    "--holdout-release-registration",
                    str(paths["holdout_registration"]),
                    "--release-signing-registration",
                    str(paths["release_registration"]),
                    "--holdout-fingerprint-key",
                    str(paths["fingerprint_key"]),
                    "--private-key",
                    str(paths["private_key"]),
                    "--output-directory",
                    str(output),
                    "--receipt",
                    str(receipt),
                )

            asset = output / release_cli.ASSET_FILE_NAME
            for label, formal, receipt in (
                ("receipt equals asset", paths["formal"], asset),
                ("asset overwrites input", asset, root / "receipt.json"),
                (
                    "receipt overwrites key",
                    paths["formal"],
                    paths["private_key"],
                ),
            ):
                with self.subTest(label=label), self.assertRaisesRegex(
                    ValueError,
                    "overlap",
                ):
                    release_cli_main(arguments(formal=formal, receipt=receipt))

            paths["private_key"].write_bytes(b"fixture")
            symlink = root / "private-key-alias.pem"
            try:
                symlink.symlink_to(paths["private_key"])
            except OSError:
                pass
            else:
                with self.assertRaisesRegex(ValueError, "overlap"):
                    release_cli._reject_release_path_overlaps(
                        {
                            "private signing key": paths["private_key"].resolve(),
                            "release receipt": symlink.resolve(),
                        }
                    )

            protected_input = root / "protected-input.json"
            protected_input.write_text('{"protected":true}\n', encoding="utf-8")
            hard_link_output = root / "hard-link-output.json"
            os.link(protected_input, hard_link_output)
            with self.assertRaisesRegex(ValueError, "overwrite an input artifact"):
                formal_cli._reject_output_input_overlaps(
                    hard_link_output,
                    {protected_input},
                )
            with self.assertRaisesRegex(ValueError, "overlap"):
                release_cli._reject_release_path_overlaps(
                    {
                        "formal pack": protected_input,
                        "release receipt": hard_link_output,
                    }
                )
            bounded_io.atomic_write_text(
                hard_link_output,
                '{"replacement":true}\n',
            )
            self.assertEqual(
                '{"protected":true}\n',
                protected_input.read_text(encoding="utf-8"),
            )
            self.assertEqual(
                '{"replacement":true}\n',
                hard_link_output.read_text(encoding="utf-8"),
            )

    def test_cli_rejects_oversized_json_before_opening_it(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            oversized = Path(directory) / "oversized.json"
            with oversized.open("wb") as handle:
                handle.truncate(bounded_io.MAX_JSON_FILE_BYTES + 1)
            with patch.object(
                bounded_io,
                "_read_file_chunk",
                side_effect=AssertionError(
                    "oversized file must be rejected from stat metadata"
                ),
            ):
                with self.assertRaisesRegex(ValueError, "file byte budget"):
                    release_cli._read_json(oversized, "oversized release input")
                with self.assertRaisesRegex(ValueError, "file byte budget"):
                    formal_cli._read_json_path(oversized, "oversized compiler input")

    def test_rejects_content_tamper_even_when_runtime_digest_is_rewritten(self) -> None:
        artifact = _artifact()
        governance = _governance(artifact)
        artifact["nodes"][0]["displayName"] = "tampered"

        with self.assertRaisesRegex(ValueError, "version fingerprint"):
            generate_signed_release(
                artifact,
                governance,
                self.private_key_pem,
            )

        from knowledge_pack_production.runtime_contract import (
            reviewed_content_fingerprint,
        )

        artifact["manifest"]["runtimeContentFingerprint"] = (
            reviewed_content_fingerprint(artifact)
        )
        _refresh_artifact_version_fingerprint(artifact)
        with self.assertRaisesRegex(
            ValueError,
            "does not match independent release recompilation",
        ):
            generate_signed_release(
                artifact,
                governance,
                self.private_key_pem,
            )

    def test_release_revalidates_holdout_instead_of_trusting_artifact(self) -> None:
        artifact = _artifact()
        governance = _governance(artifact)
        compliance = artifact["governance"]["holdoutCompliance"]
        compliance["holdoutEntryCount"] += 1
        compliance_without_fingerprint = dict(compliance)
        compliance_without_fingerprint.pop("complianceFingerprint")
        compliance["complianceFingerprint"] = canonical_fingerprint(
            "formal-holdout-compliance-report-v1",
            compliance_without_fingerprint,
        )
        from knowledge_pack_production.runtime_contract import (
            build_coverage_proof_v2,
        )

        build_inputs = _LATEST_RELEASE_HOLDOUT_KWARGS[
            "holdout_build_inputs"
        ]
        artifact["coverageProofV2"] = build_coverage_proof_v2(
            coverage_ledger=build_inputs["coverageLedger"],
            pack_review=build_inputs["packReviewContent"],
            artifact=artifact,
        )
        _refresh_artifact_version_fingerprint(artifact)

        with self.assertRaisesRegex(
            ValueError,
            "does not match independent release recompilation",
        ):
            generate_signed_release(
                artifact,
                governance,
                self.private_key_pem,
            )

    def test_rejects_missing_or_tampered_coverage_proof(self) -> None:
        artifact = _artifact()
        governance = _governance(artifact)
        artifact.pop("coverageProofV2")
        _refresh_artifact_version_fingerprint(artifact)
        with self.assertRaisesRegex(ValueError, "lacks coverage proof v2"):
            generate_signed_release(
                artifact,
                governance,
                self.private_key_pem,
            )

        artifact = _artifact()
        governance = _governance(artifact)
        artifact["coverageProofV2"]["subjects"][0][
            "expectedKnowledgePointStableCodes"
        ].pop()
        _refresh_artifact_version_fingerprint(artifact)
        with self.assertRaisesRegex(ValueError, "does not match"):
            generate_signed_release(
                artifact,
                governance,
                self.private_key_pem,
            )

    def test_rejects_expired_or_revoked_key_governance(self) -> None:
        artifact = _artifact()
        expired = _governance(artifact)
        expired["signing"]["validUntilEpochMillis"] = 599
        with self.assertRaisesRegex(ValueError, "validity interval"):
            generate_signed_release(
                artifact,
                expired,
                self.private_key_pem,
            )

        revoked = _governance(artifact)
        revoked["signing"]["revokedAtEpochMillis"] = 550
        revoked["signing"]["revocationEvidenceFingerprint"] = "a" * 64
        with self.assertRaisesRegex(ValueError, "Revoked signing keys"):
            generate_signed_release(
                artifact,
                revoked,
                self.private_key_pem,
            )

    def test_rejects_weak_empty_and_non_rsa_private_keys(self) -> None:
        artifact = _artifact()
        governance = _governance(artifact)
        with self.assertRaisesRegex(ValueError, "empty"):
            generate_signed_release(artifact, governance, b"")
        with self.assertRaisesRegex(ValueError, "at least 2048"):
            generate_signed_release(
                artifact,
                governance,
                _private_key_pem(1_024),
            )

        from cryptography.hazmat.primitives.asymmetric import ec

        ec_key = ec.generate_private_key(ec.SECP256R1())
        ec_pem = ec_key.private_bytes(
            serialization.Encoding.PEM,
            serialization.PrivateFormat.PKCS8,
            serialization.NoEncryption(),
        )
        with self.assertRaisesRegex(ValueError, "must be RSA"):
            generate_signed_release(artifact, governance, ec_pem)

    def test_caller_cannot_replace_the_pinned_release_signing_key(
        self,
    ) -> None:
        artifact = _artifact()
        governance = _governance(artifact)
        legitimate_registration = _fixture_release_signing_registration()
        attacker_private_key_pem = _private_key_pem()
        attacker_registration = _fixture_release_signing_registration(
            attacker_private_key_pem
        )

        with pinned_formal_holdout_release(), _pinned_release_signing(
            legitimate_registration
        ):
            with self.assertRaisesRegex(ValueError, "is not pinned"):
                _generate_signed_release(
                    artifact,
                    governance,
                    attacker_private_key_pem,
                    **_LATEST_RELEASE_HOLDOUT_KWARGS,
                    release_signing_registration=attacker_registration,
                )
            with self.assertRaisesRegex(ValueError, "does not match"):
                _generate_signed_release(
                    artifact,
                    governance,
                    attacker_private_key_pem,
                    **_LATEST_RELEASE_HOLDOUT_KWARGS,
                    release_signing_registration=legitimate_registration,
                )

            wrong_generation = copy.deepcopy(governance)
            wrong_generation["candidate"]["activationGeneration"] = 8
            with self.assertRaisesRegex(
                ValueError,
                "pinned signing generation",
            ):
                _generate_signed_release(
                    artifact,
                    wrong_generation,
                    self.private_key_pem,
                    **_LATEST_RELEASE_HOLDOUT_KWARGS,
                    release_signing_registration=legitimate_registration,
                )

    def test_rejects_unknown_sources_or_incomplete_textbook_mapping(self) -> None:
        artifact = _artifact()
        unknown = _governance(artifact)
        unknown["sourceEvidence"][0]["sourceId"] = "source.unknown"
        with self.assertRaisesRegex(ValueError, "unknown source"):
            generate_signed_release(
                artifact,
                unknown,
                self.private_key_pem,
            )

        incomplete = _governance(artifact)
        incomplete["subjectMappings"][0]["textbookEditionSourceIds"] = []
        with self.assertRaisesRegex(ValueError, "nonempty"):
            generate_signed_release(
                artifact,
                incomplete,
                self.private_key_pem,
            )

    def test_rejects_independent_review_digest_mismatch(self) -> None:
        artifact = _artifact()
        governance = _governance(artifact)
        governance["independentReview"]["runtimeContentFingerprint"] = "f" * 64
        with self.assertRaisesRegex(ValueError, "does not bind"):
            generate_signed_release(
                artifact,
                governance,
                self.private_key_pem,
            )

    def test_cli_reads_encrypted_key_without_logging_key_or_password(self) -> None:
        artifact = _artifact()
        governance = _governance(artifact)
        private_key = serialization.load_pem_private_key(
            self.private_key_pem,
            password=None,
        )
        password = "fixture-secret-password"
        encrypted_pem = private_key.private_bytes(
            serialization.Encoding.PEM,
            serialization.PrivateFormat.PKCS8,
            serialization.BestAvailableEncryption(password.encode()),
        )
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            artifact_path = root / "formal-pack.json"
            governance_path = root / "governance.json"
            build_inputs_path = root / "formal-build-inputs.json"
            content_manifest_path = root / "content-input-manifest.json"
            holdout_manifest_path = root / "holdout-manifest.json"
            release_registration_path = root / "holdout-registration.json"
            release_signing_registration_path = (
                root / "release-signing-registration.json"
            )
            holdout_key_path = root / "holdout-fingerprint-key.bin"
            private_key_path = root / "signing-key.pem"
            output = root / "generated"
            receipt_path = root / "receipt.json"
            artifact_path.write_text(
                json.dumps(artifact, ensure_ascii=False),
                encoding="utf-8",
            )
            governance_path.write_text(
                json.dumps(governance, ensure_ascii=False),
                encoding="utf-8",
            )
            build_inputs_path.write_text(
                json.dumps(
                    _LATEST_RELEASE_HOLDOUT_KWARGS[
                        "holdout_build_inputs"
                    ],
                    ensure_ascii=False,
                ),
                encoding="utf-8",
            )
            content_manifest_path.write_text(
                json.dumps(
                    _LATEST_RELEASE_HOLDOUT_KWARGS[
                        "content_input_manifest"
                    ],
                    ensure_ascii=False,
                ),
                encoding="utf-8",
            )
            holdout_manifest_path.write_text(
                json.dumps(
                    _LATEST_RELEASE_HOLDOUT_KWARGS["holdout_manifest"],
                    ensure_ascii=False,
                ),
                encoding="utf-8",
            )
            release_registration_path.write_text(
                json.dumps(
                    _LATEST_RELEASE_HOLDOUT_KWARGS[
                        "holdout_release_registration"
                    ],
                    ensure_ascii=False,
                ),
                encoding="utf-8",
            )
            release_signing_registration = (
                _fixture_release_signing_registration()
            )
            release_signing_registration_path.write_text(
                json.dumps(
                    release_signing_registration,
                    ensure_ascii=False,
                ),
                encoding="utf-8",
            )
            holdout_key_path.write_bytes(
                _LATEST_RELEASE_HOLDOUT_KWARGS[
                    "holdout_fingerprint_key"
                ]
            )
            private_key_path.write_bytes(encrypted_pem)
            stdout = io.StringIO()
            with patch.dict(
                os.environ,
                {
                    PRIVATE_KEY_PATH_ENV: str(private_key_path),
                    PRIVATE_KEY_PASSWORD_ENV: password,
                    FINGERPRINT_KEY_PATH_ENV: str(holdout_key_path),
                },
                clear=False,
            ), redirect_stdout(stdout), pinned_formal_holdout_release(), (
                _pinned_release_signing(release_signing_registration)
            ):
                result = release_cli_main(
                    (
                        "--formal-pack",
                        str(artifact_path),
                        "--governance",
                        str(governance_path),
                        "--formal-build-inputs",
                        str(build_inputs_path),
                        "--content-input-manifest",
                        str(content_manifest_path),
                        "--holdout-manifest",
                        str(holdout_manifest_path),
                        "--holdout-release-registration",
                        str(release_registration_path),
                        "--release-signing-registration",
                        str(release_signing_registration_path),
                        "--output-directory",
                        str(output),
                        "--receipt",
                        str(receipt_path),
                    )
                )

            self.assertEqual(0, result)
            self.assertTrue(
                (output / "GeneratedFormalKnowledgePackAsset.kt").is_file()
            )
            self.assertTrue(
                (output / "GeneratedFormalKnowledgePackRegistry.kt").is_file()
            )
            receipt = json.loads(receipt_path.read_text(encoding="utf-8"))
            self.assertFalse(receipt["containsPrivateKeyMaterial"])
            self.assertEqual(
                receipt["assetSourceFingerprint"],
                hashlib.sha256(
                    (
                        output / "GeneratedFormalKnowledgePackAsset.kt"
                    ).read_bytes()
                ).hexdigest(),
            )
            self.assertEqual(
                receipt["registrySourceFingerprint"],
                hashlib.sha256(
                    (
                        output / "GeneratedFormalKnowledgePackRegistry.kt"
                    ).read_bytes()
                ).hexdigest(),
            )
            visible = (
                stdout.getvalue()
                + receipt_path.read_text(encoding="utf-8")
                + (output / "GeneratedFormalKnowledgePackRegistry.kt").read_text(
                    encoding="utf-8"
                )
            )
            self.assertNotIn(password, visible)
            self.assertNotIn(str(private_key_path), visible)
            self.assertNotIn(str(holdout_key_path), visible)
            self.assertNotIn("PRIVATE KEY-----", visible)

    def test_generated_sources_are_accepted_by_bundled_kotlin_parser(self) -> None:
        compiler_jars = list(
            PROJECT_ROOT.glob(
                ".toolchains/gradle/gradle-*/lib/"
                "kotlin-compiler-embeddable-*.jar"
            )
        )
        stdlib_jars = list(
            PROJECT_ROOT.glob(
                ".toolchains/gradle/gradle-*/lib/kotlin-stdlib-*.jar"
            )
        )
        if not compiler_jars or not stdlib_jars:
            self.skipTest("Bundled Kotlin compiler is unavailable")
        lib_directory = compiler_jars[0].parent
        artifact = _artifact()
        generated = generate_signed_release(
            artifact,
            _governance(artifact),
            self.private_key_pem,
        )
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            model_stub = root / "ModelStub.kt"
            database_stub = root / "DatabaseStub.kt"
            asset = root / "GeneratedFormalKnowledgePackAsset.kt"
            registry = root / "GeneratedFormalKnowledgePackRegistry.kt"
            output = root / "generated.jar"
            model_stub.write_text(MODEL_STUB, encoding="utf-8")
            database_stub.write_text(DATABASE_STUB, encoding="utf-8")
            asset.write_text(generated.asset_kotlin, encoding="utf-8")
            registry.write_text(generated.registry_kotlin, encoding="utf-8")
            result = subprocess.run(
                (
                    "java",
                    "-cp",
                    str(lib_directory / "*"),
                    "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
                    "-no-stdlib",
                    "-no-reflect",
                    "-classpath",
                    str(stdlib_jars[0]),
                    "-d",
                    str(output),
                    str(model_stub),
                    str(database_stub),
                    str(asset),
                    str(registry),
                ),
                cwd=root,
                capture_output=True,
                text=True,
                timeout=60,
                check=False,
            )
            self.assertEqual(
                0,
                result.returncode,
                msg=result.stdout + result.stderr,
            )
            self.assertTrue(output.is_file())

    def test_generated_release_passes_actual_kotlin_activation_policy(self) -> None:
        compiler_jars = list(
            PROJECT_ROOT.glob(
                ".toolchains/gradle/gradle-*/lib/"
                "kotlin-compiler-embeddable-*.jar"
            )
        )
        stdlib_jars = list(
            PROJECT_ROOT.glob(
                ".toolchains/gradle/gradle-*/lib/kotlin-stdlib-*.jar"
            )
        )
        if not compiler_jars or not stdlib_jars:
            self.skipTest("Bundled Kotlin compiler is unavailable")
        lib_directory = compiler_jars[0].parent
        artifact = _artifact()
        generated = generate_signed_release(
            artifact,
            _governance(artifact),
            self.private_key_pem,
        )
        package_path = (
            PROJECT_ROOT
            / "core/knowledge-database/src/main/kotlin/com/tingyun/"
            "smartmistakebook/core/knowledge/database"
        )
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            model_stub = root / "ModelStub.kt"
            database_stub = root / "RuntimeDatabaseStub.kt"
            asset = root / "GeneratedFormalKnowledgePackAsset.kt"
            registry = root / "GeneratedFormalKnowledgePackRegistry.kt"
            main = root / "GeneratedActivationMain.kt"
            output = root / "generated-activation.jar"
            model_stub.write_text(MODEL_STUB, encoding="utf-8")
            database_stub.write_text(RUNTIME_DATABASE_STUB, encoding="utf-8")
            asset.write_text(generated.asset_kotlin, encoding="utf-8")
            registry.write_text(generated.registry_kotlin, encoding="utf-8")
            main.write_text(
                (
                    "package com.tingyun.smartmistakebook.core.knowledge.database\n"
                    "\n"
                    "fun main() {\n"
                    "    val definition = "
                    "GeneratedFormalKnowledgePackRegistry.entries.single()\n"
                    "    val proof = requireNotNull("
                    "definition.formalActivationProof)\n"
                    "    check(proof.candidate.governanceFingerprint == "
                    f"\"{generated.receipt['candidateGovernanceFingerprint']}\")\n"
                    "    check(proof.independentReview.reviewFingerprint == "
                    f"\"{generated.receipt['independentReviewFingerprint']}\")\n"
                    "    check(FormalKnowledgePackActivationPolicy."
                    "reviewedContentFingerprint(definition.pack) == "
                    f"\"{generated.receipt['runtimeContentFingerprint']}\")\n"
                    "    definition.requireGovernance(\n"
                    "        expectedContentFingerprint = \"0\".repeat(64),\n"
                    "        trustedSigningKeys = "
                    "GeneratedFormalKnowledgePackRegistry.formalSigningKeys,\n"
                    "    )\n"
                    "    fun expectSecurity(block: () -> Unit) {\n"
                    "        check(runCatching(block).exceptionOrNull() "
                    "is SecurityException)\n"
                    "    }\n"
                    "    expectSecurity {\n"
                    "        definition.requireGovernance(\n"
                    "            expectedContentFingerprint = \"0\".repeat(64),\n"
                    "            trustedSigningKeys = emptyList(),\n"
                    "        )\n"
                    "    }\n"
                    "    val trustedKey = "
                    "GeneratedFormalKnowledgePackRegistry."
                    "formalSigningKeys.single()\n"
                    "    expectSecurity {\n"
                    "        definition.requireGovernance(\n"
                    "            expectedContentFingerprint = \"0\".repeat(64),\n"
                    "            trustedSigningKeys = listOf(\n"
                    "                trustedKey.copy(\n"
                    "                    revokedAtEpochMillis = 1L,\n"
                    "                    revocationEvidenceFingerprint = "
                    "\"f\".repeat(64),\n"
                    "                ),\n"
                    "            ),\n"
                    "        )\n"
                    "    }\n"
                    "    expectSecurity {\n"
                    "        definition.requireGovernance(\n"
                    "            expectedContentFingerprint = \"0\".repeat(64),\n"
                    "            trustedSigningKeys = listOf(\n"
                    "                trustedKey.copy(\n"
                    "                    validUntilEpochMillis = "
                    "proof.signedActivation.signedAtEpochMillis - 1L,\n"
                    "                ),\n"
                    "            ),\n"
                    "        )\n"
                    "    }\n"
                    "    println(\"ACTIVATED\")\n"
                    "}\n"
                ),
                encoding="utf-8",
            )
            source_files = (
                model_stub,
                database_stub,
                package_path / "FormalKnowledgePackGovernance.kt",
                package_path / "FormalKnowledgePackActivationPipeline.kt",
                package_path / "BuildVariantTrustedKnowledgePackDefinition.kt",
                asset,
                registry,
                main,
            )
            compile_result = subprocess.run(
                (
                    "java",
                    "-cp",
                    str(lib_directory / "*"),
                    "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
                    "-no-stdlib",
                    "-no-reflect",
                    "-classpath",
                    str(stdlib_jars[0]),
                    "-d",
                    str(output),
                    *(str(path) for path in source_files),
                ),
                cwd=root,
                capture_output=True,
                text=True,
                timeout=60,
                check=False,
            )
            self.assertEqual(
                0,
                compile_result.returncode,
                msg=compile_result.stdout + compile_result.stderr,
            )
            run_result = subprocess.run(
                (
                    "java",
                    "-cp",
                    os.pathsep.join((str(output), str(stdlib_jars[0]))),
                    "com.tingyun.smartmistakebook.core.knowledge.database."
                    "GeneratedActivationMainKt",
                ),
                cwd=root,
                capture_output=True,
                text=True,
                timeout=60,
                check=False,
            )
            self.assertEqual(
                0,
                run_result.returncode,
                msg=run_result.stdout + run_result.stderr,
            )
            self.assertEqual("ACTIVATED", run_result.stdout.strip())


if __name__ == "__main__":
    unittest.main()
