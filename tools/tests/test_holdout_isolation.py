from __future__ import annotations

import copy
import hashlib
import json
import tempfile
import unittest
from contextlib import contextmanager
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import rsa

from knowledge_pack_production import holdout_gate
from knowledge_pack_production.cli import (
    _load_holdout_gate,
    _resolve_repository_root,
)
from knowledge_pack_production.holdout_gate import (
    SIGNATURE_ALGORITHM,
    build_content_input_manifest,
    build_holdout_release_registration,
    build_signed_holdout_manifest,
    fingerprint_holdout_record,
    holdout_signing_payload,
    holdout_release_registration_fingerprint,
    validate_full_holdout_manifest_before_processing,
    verify_holdout_isolation,
)
from knowledge_pack_production.input_hashes import canonical_fingerprint


FINGERPRINT_KEY = hashlib.sha256(b"fixture holdout fingerprint key").digest()
FINGERPRINT_KEY_ID = "fixture-holdout-fingerprint-key-v1"
PROJECT_ROOT = Path(__file__).resolve().parents[2]


def _build_inputs(extra: object | None = None) -> dict[str, dict]:
    return {
        "coverageLedger": {
            "ledgerId": "fixture-ledger",
            "subjects": ["CHINESE", "MATH"],
        },
        "sourceRegister": {
            "registerId": "fixture-register",
            "sources": [{"title": "reviewed curriculum source"}],
        },
        "teachingInventory": {
            "inventoryId": "fixture-inventory",
            "candidates": [{"locator": "chapter/method"}],
        },
        "teachingDecisions": {
            "decisionSetId": "fixture-decisions",
            "decisions": [{"decision": "APPROVE_FOR_HUMAN_SYNTHESIS"}],
        },
        "packReviewContent": {
            "reviewRecordId": "fixture-review",
            "expectedVersionFingerprint": "0" * 64,
            "pack": {
                "aliases": ["general reviewed terminology"],
                "extra": extra,
            },
        },
    }


class HoldoutIsolationGateTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.private_key = rsa.generate_private_key(
            public_exponent=65_537,
            key_size=2_048,
        )
        cls.private_key_pem = cls.private_key.private_bytes(
            serialization.Encoding.PEM,
            serialization.PrivateFormat.PKCS8,
            serialization.NoEncryption(),
        )
        cls.trusted_key = {
            "keyId": "fixture-holdout-signing-key-v1",
            "algorithm": SIGNATURE_ALGORITHM,
            "x509PublicKeyHex": cls.private_key.public_key()
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

    def _content_manifest(self, inputs: dict[str, dict]) -> dict:
        return build_content_input_manifest(
            inputs,
            manifest_id="fixture-content-input-v1",
            manifest_version="fixture-v1",
            created_at_epoch_millis=100,
            fingerprint_key_id=FINGERPRINT_KEY_ID,
            fingerprint_key=FINGERPRINT_KEY,
        )

    def _holdout_manifest(
        self,
        entries: list[dict],
        *,
        release_generation: int = 1,
        manifest_version: str = "fixture-v1",
    ) -> dict:
        return build_signed_holdout_manifest(
            entries,
            manifest_id="fixture-private-holdout-v1",
            manifest_version=manifest_version,
            created_at_epoch_millis=100,
            fingerprint_key_id=FINGERPRINT_KEY_ID,
            fingerprint_key=FINGERPRINT_KEY,
            release_generation=release_generation,
            signing_key_id=self.trusted_key["keyId"],
            signed_at_epoch_millis=200,
            private_key_pem=self.private_key_pem,
        )

    def _registration(self, holdout: dict) -> dict:
        return build_holdout_release_registration(
            holdout,
            registration_id="fixture-holdout-release-registration-v1",
            signing_key=self.trusted_key,
        )

    @contextmanager
    def _pin(self, registration: dict):
        fingerprint = holdout_release_registration_fingerprint(registration)
        with patch.object(
            holdout_gate,
            "PINNED_HOLDOUT_RELEASE_REGISTRATION",
            (fingerprint, registration["releaseGeneration"]),
        ):
            yield

    def _verify(
        self,
        inputs: dict[str, dict],
        holdout: dict,
        content_manifest: dict | None = None,
    ) -> dict:
        registration = self._registration(holdout)
        with self._pin(registration):
            return verify_holdout_isolation(
                build_inputs=inputs,
                content_input_manifest=(
                    self._content_manifest(inputs)
                    if content_manifest is None
                    else content_manifest
                ),
                holdout_manifest=holdout,
                fingerprint_key=FINGERPRINT_KEY,
                holdout_release_registration=registration,
            )

    def test_disjoint_inputs_return_summary_only_compliance_report(self) -> None:
        inputs = _build_inputs()
        reserved = fingerprint_holdout_record(
            acceptance_id="private-acceptance-case-fixture-a",
            fingerprint_key=FINGERPRINT_KEY,
            image_sha256=hashlib.sha256(b"private fixture image").hexdigest(),
            prompt_text="Reserved acceptance prompt fixture",
            annotation={"label": "fixture-private-annotation"},
            derived_aliases=("reserved acceptance relation",),
        )

        report = self._verify(inputs, self._holdout_manifest([reserved]))

        self.assertEqual(0, report["overlapCount"])
        self.assertEqual(1, report["holdoutEntryCount"])
        self.assertRegex(report["complianceFingerprint"], r"^[0-9a-f]{64}$")
        visible = repr(report)
        self.assertNotIn("private-acceptance-case-fixture-a", visible)
        self.assertNotIn("Reserved acceptance prompt fixture", visible)
        self.assertNotIn("fixture-private-annotation", visible)
        self.assertEqual(
            {
                "rawText",
                "normalizedText",
                "derivedText",
                "declaredDigest",
                "structuredValue",
            },
            set(report["contentSurfaceCounts"]),
        )

    def test_exact_prompt_digest_and_stable_id_overlap_fail_closed(self) -> None:
        image_digest = hashlib.sha256(b"private fixture image").hexdigest()
        reserved = fingerprint_holdout_record(
            acceptance_id="private-acceptance-case-fixture-b",
            fingerprint_key=FINGERPRINT_KEY,
            image_sha256=image_digest,
            prompt_text="Reserved exact prompt fixture",
        )
        holdout = self._holdout_manifest([reserved])

        for contaminated in (
            "Reserved exact prompt fixture",
            image_digest,
            "private-acceptance-case-fixture-b",
        ):
            inputs = _build_inputs(extra=contaminated)
            with self.assertRaisesRegex(ValueError, "overlaps"):
                self._verify(inputs, holdout)

    def test_normalized_and_derived_alias_overlap_fail_closed(self) -> None:
        reserved = fingerprint_holdout_record(
            acceptance_id="private-acceptance-case-fixture-c",
            fingerprint_key=FINGERPRINT_KEY,
            prompt_text="Kinetic—Energy  Balance",
            derived_aliases=("Phase / Boundary Shift",),
        )
        holdout = self._holdout_manifest([reserved])

        for contaminated in (
            "  KINETIC—ENERGY　BALANCE ",
            "kinetic energy balance",
            "phase-boundary_shift",
        ):
            inputs = _build_inputs(extra=contaminated)
            with self.assertRaisesRegex(ValueError, "overlaps"):
                self._verify(inputs, holdout)

    def test_embedded_split_numeric_and_cross_surface_overlap_fail_closed(
        self,
    ) -> None:
        image_digest = hashlib.sha256(
            b"private cross-surface fixture image"
        ).hexdigest()
        annotation = {"state": "fixture-transition", "direction": 7}
        reserved = fingerprint_holdout_record(
            acceptance_id="private-acceptance-case-fixture-containment",
            fingerprint_key=FINGERPRINT_KEY,
            image_sha256=image_digest,
            prompt_text="Kinetic Energy Balance",
            annotation=annotation,
            derived_aliases=("2048",),
        )
        holdout = self._holdout_manifest([reserved])

        for contaminated in (
            "prefix Kinetic Energy Balance suffix",
            ["Kinetic", "Energy", "Balance"],
            {
                "segmentA": "Kinetic",
                "segmentB": "Energy",
                "segmentC": "Balance",
            },
            (
                "prefix-private-acceptance-case-fixture-containment-"
                "suffix"
            ),
            2048,
            json.dumps(annotation, separators=(",", ":"), sort_keys=True),
            f"sha256:{image_digest}",
            [image_digest[:32], image_digest[32:]],
        ):
            with self.subTest(contaminated=contaminated):
                with self.assertRaisesRegex(ValueError, "overlaps"):
                    self._verify(_build_inputs(extra=contaminated), holdout)

    def test_nonadjacent_ordered_field_fragments_fail_closed(self) -> None:
        reserved = fingerprint_holdout_record(
            acceptance_id="private-nonadjacent-fragment-fixture",
            fingerprint_key=FINGERPRINT_KEY,
            prompt_text="Kinetic Energy Balance",
        )
        holdout = self._holdout_manifest([reserved])

        for contaminated in (
            {
                "a": "Kinetic",
                "b": "ordinary unrelated metadata",
                "c": "Energy",
                "d": "Balance",
            },
            [
                "Kinetic",
                "ordinary unrelated metadata",
                "Energy",
                "another unrelated value",
                "Balance",
            ],
        ):
            with self.subTest(container=type(contaminated).__name__):
                with self.assertRaisesRegex(ValueError, "overlaps"):
                    self._verify(_build_inputs(extra=contaminated), holdout)

    def test_short_numeric_and_symbol_tokens_use_exact_type_domains(
        self,
    ) -> None:
        cases = (
            ("-3", (-3, "-3"), (3, "3")),
            ("1.5", (1.5, "1.5"), (15, "15")),
            ("≤", ("≤",), ("<", "=", ">")),
        )
        for alias, collisions, disjoint_values in cases:
            with self.subTest(alias=alias):
                reserved = fingerprint_holdout_record(
                    acceptance_id=f"private-typed-token-{ord(alias[0])}",
                    fingerprint_key=FINGERPRINT_KEY,
                    derived_aliases=(alias,),
                )
                holdout = self._holdout_manifest([reserved])
                for contaminated in collisions:
                    with self.assertRaisesRegex(ValueError, "overlaps"):
                        self._verify(
                            _build_inputs(extra=contaminated),
                            holdout,
                        )
                for disjoint in disjoint_values:
                    report = self._verify(
                        _build_inputs(extra=disjoint),
                        holdout,
                    )
                    self.assertEqual(0, report["overlapCount"])

        structured_reserved = fingerprint_holdout_record(
            acceptance_id="private-typed-structured-values",
            fingerprint_key=FINGERPRINT_KEY,
            annotation={"threshold": -3, "relation": "≤"},
        )
        structured_holdout = self._holdout_manifest(
            [structured_reserved]
        )
        with self.assertRaisesRegex(ValueError, "overlaps"):
            self._verify(
                _build_inputs(
                    extra={"threshold": -3, "relation": "≤"}
                ),
                structured_holdout,
            )
        report = self._verify(
            _build_inputs(extra={"threshold": 3, "relation": "="}),
            structured_holdout,
        )
        self.assertEqual(0, report["overlapCount"])

    def test_unpinned_signing_authority_cannot_replace_trust_root(self) -> None:
        legitimate = self._holdout_manifest(
            [
                fingerprint_holdout_record(
                    acceptance_id="private-legitimate-release-fixture",
                    fingerprint_key=FINGERPRINT_KEY,
                    prompt_text="Legitimate reserved prompt fixture",
                )
            ]
        )
        legitimate_registration = self._registration(legitimate)
        attacker_private_key = rsa.generate_private_key(
            public_exponent=65_537,
            key_size=2_048,
        )
        attacker_key = {
            **self.trusted_key,
            "keyId": "fixture-attacker-signing-key-v1",
            "x509PublicKeyHex": attacker_private_key.public_key()
            .public_bytes(
                serialization.Encoding.DER,
                serialization.PublicFormat.SubjectPublicKeyInfo,
            )
            .hex(),
        }
        attacker_manifest = build_signed_holdout_manifest(
            [
                fingerprint_holdout_record(
                    acceptance_id="attacker-selected-placeholder-fixture",
                    fingerprint_key=FINGERPRINT_KEY,
                    prompt_text="Attacker selected placeholder fixture",
                )
            ],
            manifest_id="fixture-attacker-holdout-v1",
            manifest_version="fixture-v1",
            created_at_epoch_millis=100,
            fingerprint_key_id=FINGERPRINT_KEY_ID,
            fingerprint_key=FINGERPRINT_KEY,
            release_generation=1,
            signing_key_id=attacker_key["keyId"],
            signed_at_epoch_millis=200,
            private_key_pem=attacker_private_key.private_bytes(
                serialization.Encoding.PEM,
                serialization.PrivateFormat.PKCS8,
                serialization.NoEncryption(),
            ),
        )
        attacker_registration = build_holdout_release_registration(
            attacker_manifest,
            registration_id="fixture-attacker-release-registration-v1",
            signing_key=attacker_key,
        )

        with self._pin(legitimate_registration):
            with self.assertRaisesRegex(ValueError, "is not pinned"):
                verify_holdout_isolation(
                    build_inputs=_build_inputs(),
                    content_input_manifest=self._content_manifest(
                        _build_inputs()
                    ),
                    holdout_manifest=attacker_manifest,
                    fingerprint_key=FINGERPRINT_KEY,
                    holdout_release_registration=attacker_registration,
                )

    def test_older_signed_holdout_generation_cannot_be_replayed(self) -> None:
        older = self._holdout_manifest(
            [
                fingerprint_holdout_record(
                    acceptance_id="private-older-generation-fixture",
                    fingerprint_key=FINGERPRINT_KEY,
                    prompt_text="Older reserved prompt fixture",
                )
            ],
            release_generation=1,
            manifest_version="fixture-v1",
        )
        current = self._holdout_manifest(
            [
                fingerprint_holdout_record(
                    acceptance_id="private-current-generation-fixture",
                    fingerprint_key=FINGERPRINT_KEY,
                    prompt_text="Current reserved prompt fixture",
                )
            ],
            release_generation=2,
            manifest_version="fixture-v2",
        )
        older_registration = self._registration(older)
        current_registration = self._registration(current)

        with self._pin(current_registration):
            with self.assertRaisesRegex(ValueError, "is not pinned"):
                verify_holdout_isolation(
                    build_inputs=_build_inputs(),
                    content_input_manifest=self._content_manifest(
                        _build_inputs()
                    ),
                    holdout_manifest=older,
                    fingerprint_key=FINGERPRINT_KEY,
                    holdout_release_registration=older_registration,
                )

    def test_forged_holdout_manifest_never_reaches_overlap_check(self) -> None:
        reserved = fingerprint_holdout_record(
            acceptance_id="private-acceptance-case-fixture-d",
            fingerprint_key=FINGERPRINT_KEY,
            prompt_text="Reserved signed prompt fixture",
        )
        forged = self._holdout_manifest([reserved])
        forged["entries"][0]["promptFingerprint"] = "f" * 64
        forged["entrySetFingerprint"] = canonical_fingerprint(
            "formal-holdout-entry-set-v2",
            forged["entries"],
        )

        with self.assertRaisesRegex(ValueError, "signature is invalid"):
            self._verify(_build_inputs(), forged)

    def test_substituted_fingerprint_key_cannot_hide_overlap(self) -> None:
        reserved_prompt = "Reserved key-substitution prompt fixture"
        reserved = fingerprint_holdout_record(
            acceptance_id="private-acceptance-case-fixture-key-substitution",
            fingerprint_key=FINGERPRINT_KEY,
            prompt_text=reserved_prompt,
        )
        holdout = self._holdout_manifest([reserved])
        contaminated_inputs = _build_inputs(extra=reserved_prompt)
        substituted_key = hashlib.sha256(
            b"substituted fixture holdout fingerprint key"
        ).digest()
        substituted_manifest = build_content_input_manifest(
            contaminated_inputs,
            manifest_id="fixture-content-input-v1",
            manifest_version="fixture-v1",
            created_at_epoch_millis=100,
            fingerprint_key_id=FINGERPRINT_KEY_ID,
            fingerprint_key=substituted_key,
        )

        registration = self._registration(holdout)
        with self._pin(registration):
            with self.assertRaisesRegex(
                ValueError,
                "does not match the signed manifest",
            ):
                verify_holdout_isolation(
                    build_inputs=contaminated_inputs,
                    content_input_manifest=substituted_manifest,
                    holdout_manifest=holdout,
                    fingerprint_key=substituted_key,
                    holdout_release_registration=registration,
                )

    def test_empty_or_missing_manifests_are_rejected_for_release(self) -> None:
        inputs = _build_inputs()
        empty = self._holdout_manifest([])
        with self.assertRaisesRegex(ValueError, "nonempty holdout"):
            self._verify(inputs, empty)

        reserved = fingerprint_holdout_record(
            acceptance_id="private-acceptance-case-fixture-e",
            fingerprint_key=FINGERPRINT_KEY,
            prompt_text="Reserved nonempty prompt fixture",
        )
        holdout = self._holdout_manifest([reserved])
        registration = self._registration(holdout)
        with self._pin(registration):
            with self.assertRaisesRegex(ValueError, "content-input manifest"):
                verify_holdout_isolation(
                    build_inputs=inputs,
                    content_input_manifest=None,
                    holdout_manifest=holdout,
                    fingerprint_key=FINGERPRINT_KEY,
                    holdout_release_registration=registration,
                )
            with self.assertRaisesRegex(
                ValueError,
                "signed holdout manifest",
            ):
                verify_holdout_isolation(
                    build_inputs=inputs,
                    content_input_manifest=self._content_manifest(inputs),
                    holdout_manifest=None,
                    fingerprint_key=FINGERPRINT_KEY,
                    holdout_release_registration=registration,
                )

    def test_content_manifest_cannot_omit_late_input_changes(self) -> None:
        original_inputs = _build_inputs()
        stale_manifest = self._content_manifest(original_inputs)
        changed_inputs = copy.deepcopy(original_inputs)
        changed_inputs["packReviewContent"]["pack"]["aliases"].append(
            "new reviewed alias"
        )
        holdout = self._holdout_manifest(
            [
                fingerprint_holdout_record(
                    acceptance_id="private-acceptance-case-fixture-f",
                    fingerprint_key=FINGERPRINT_KEY,
                    prompt_text="Another reserved prompt fixture",
                )
            ]
        )

        with self.assertRaisesRegex(ValueError, "does not match compiler inputs"):
            self._verify(changed_inputs, holdout, stale_manifest)

    def test_untrusted_json_is_bounded_before_hmac_or_deepcopy(self) -> None:
        clean_inputs = _build_inputs()
        content = self._content_manifest(clean_inputs)
        holdout = self._holdout_manifest(
            [
                fingerprint_holdout_record(
                    acceptance_id="private-budget-preflight-fixture",
                    fingerprint_key=FINGERPRINT_KEY,
                    prompt_text="Reserved budget preflight prompt",
                )
            ]
        )
        registration = self._registration(holdout)

        too_deep: object = "leaf"
        for _ in range(holdout_gate.MAX_JSON_DEPTH + 1):
            too_deep = {"next": too_deep}
        deep_inputs = _build_inputs(extra=too_deep)
        cyclic_inputs = _build_inputs()
        cycle: dict[str, object] = {}
        cycle["self"] = cycle
        cyclic_inputs["packReviewContent"]["pack"]["extra"] = cycle

        with self._pin(registration), patch.object(
            holdout_gate,
            "_fingerprint",
            side_effect=AssertionError("HMAC must not run before preflight"),
        ):
            with self.assertRaisesRegex(ValueError, "maximum JSON depth"):
                verify_holdout_isolation(
                    build_inputs=deep_inputs,
                    content_input_manifest=content,
                    holdout_manifest=holdout,
                    fingerprint_key=FINGERPRINT_KEY,
                    holdout_release_registration=registration,
                )
            with self.assertRaisesRegex(ValueError, "cyclic JSON"):
                verify_holdout_isolation(
                    build_inputs=cyclic_inputs,
                    content_input_manifest=content,
                    holdout_manifest=holdout,
                    fingerprint_key=FINGERPRINT_KEY,
                    holdout_release_registration=registration,
                )

        cyclic_annotation: dict[str, object] = {}
        cyclic_annotation["self"] = cyclic_annotation
        with patch.object(
            holdout_gate,
            "_fingerprint",
            side_effect=AssertionError("HMAC must not run before preflight"),
        ):
            with self.assertRaisesRegex(ValueError, "cyclic JSON"):
                fingerprint_holdout_record(
                    acceptance_id="private-cyclic-annotation-fixture",
                    fingerprint_key=FINGERPRINT_KEY,
                    annotation=cyclic_annotation,
                )

        oversized_inputs = _build_inputs(extra="longer than fixture budget")
        with self._pin(registration), patch.object(
            holdout_gate,
            "MAX_JSON_STRING_BYTES",
            8,
        ), patch.object(
            holdout_gate,
            "_fingerprint",
            side_effect=AssertionError("HMAC must not run before preflight"),
        ):
            with self.assertRaisesRegex(ValueError, "string over the byte budget"):
                verify_holdout_isolation(
                    build_inputs=oversized_inputs,
                    content_input_manifest=content,
                    holdout_manifest=holdout,
                    fingerprint_key=FINGERPRINT_KEY,
                    holdout_release_registration=registration,
                )

    def test_malformed_manifest_schema_fails_before_signature_or_hmac(
        self,
    ) -> None:
        inputs = _build_inputs()
        malformed_content = self._content_manifest(inputs)
        malformed_content["callerTrustedExtra"] = True
        holdout = self._holdout_manifest(
            [
                fingerprint_holdout_record(
                    acceptance_id="private-schema-preflight-fixture",
                    fingerprint_key=FINGERPRINT_KEY,
                    prompt_text="Reserved schema preflight prompt",
                )
            ]
        )
        registration = self._registration(holdout)

        with self._pin(registration), patch.object(
            holdout_gate,
            "_fingerprint",
            side_effect=AssertionError("HMAC must not run before schema checks"),
        ), patch.object(
            holdout_gate,
            "_verify_signature",
            side_effect=AssertionError(
                "Signature verification must not run before schema checks"
            ),
        ):
            with self.assertRaisesRegex(ValueError, "unexpected schema"):
                verify_holdout_isolation(
                    build_inputs=inputs,
                    content_input_manifest=malformed_content,
                    holdout_manifest=holdout,
                    fingerprint_key=FINGERPRINT_KEY,
                    holdout_release_registration=registration,
                )

    def test_every_nested_manifest_schema_fails_before_processing(
        self,
    ) -> None:
        inputs = _build_inputs()
        content = self._content_manifest(inputs)
        holdout = self._holdout_manifest(
            [
                fingerprint_holdout_record(
                    acceptance_id="private-full-schema-sentinel-fixture",
                    fingerprint_key=FINGERPRINT_KEY,
                    prompt_text="Kinetic Energy Balance",
                    derived_aliases=("-3",),
                )
            ]
        )
        registration = self._registration(holdout)
        mutations = {
            "entry unknown": lambda value: value["entries"][0].__setitem__(
                "unknownEntryField",
                True,
            ),
            "typed token missing": lambda value: value["entries"][0][
                "typedTokenFingerprints"
            ][0].pop("domain"),
            "containment unknown": lambda value: value["entries"][0][
                "containmentSignals"
            ][0].__setitem__("unknownSignalField", True),
            "group missing": lambda value: value["entries"][0][
                "orderedFragmentGroups"
            ][0].pop("maxSkippedValues"),
            "fragment missing": lambda value: value["entries"][0][
                "orderedFragmentGroups"
            ][0]["fragments"][0].pop("length"),
            "signature unknown": lambda value: value[
                "signature"
            ].__setitem__("callerTrustedKey", True),
        }

        for label, mutate in mutations.items():
            with self.subTest(label=label):
                malformed = copy.deepcopy(holdout)
                mutate(malformed)
                with patch.object(
                    holdout_gate,
                    "deepcopy",
                    side_effect=AssertionError(
                        "deepcopy must not run before full schema validation"
                    ),
                ), patch.object(
                    holdout_gate,
                    "_fingerprint",
                    side_effect=AssertionError(
                        "HMAC must not run before full schema validation"
                    ),
                ), patch.object(
                    holdout_gate,
                    "_verify_signature",
                    side_effect=AssertionError(
                        "signature verification must not run before full schema"
                    ),
                ):
                    with self.assertRaisesRegex(
                        ValueError,
                        "unexpected schema",
                    ):
                        validate_full_holdout_manifest_before_processing(
                            malformed,
                            require_nonempty=True,
                        )
                    with self.assertRaisesRegex(
                        ValueError,
                        "unexpected schema",
                    ):
                        holdout_signing_payload(malformed)
                    with self.assertRaisesRegex(
                        ValueError,
                        "unexpected schema",
                    ):
                        build_holdout_release_registration(
                            malformed,
                            registration_id="fixture-invalid-registration",
                            signing_key=self.trusted_key,
                        )
                    with self._pin(registration):
                        with self.assertRaisesRegex(
                            ValueError,
                            "unexpected schema",
                        ):
                            verify_holdout_isolation(
                                build_inputs=inputs,
                                content_input_manifest=content,
                                holdout_manifest=malformed,
                                fingerprint_key=FINGERPRINT_KEY,
                                holdout_release_registration=registration,
                            )

    def test_containment_windows_have_a_fail_closed_operation_budget(
        self,
    ) -> None:
        inputs = _build_inputs(extra="ordinary content without reserved text")
        content = self._content_manifest(inputs)
        holdout = self._holdout_manifest(
            [
                fingerprint_holdout_record(
                    acceptance_id="private-window-budget-fixture",
                    fingerprint_key=FINGERPRINT_KEY,
                    prompt_text="Long reserved containment phrase",
                )
            ]
        )
        registration = self._registration(holdout)

        with self._pin(registration), patch.object(
            holdout_gate,
            "_MAX_CONTAINMENT_WINDOW_OPERATIONS",
            1,
        ):
            with self.assertRaisesRegex(ValueError, "resource budget"):
                verify_holdout_isolation(
                    build_inputs=inputs,
                    content_input_manifest=content,
                    holdout_manifest=holdout,
                    fingerprint_key=FINGERPRINT_KEY,
                    holdout_release_registration=registration,
                )

    def test_nested_project_root_cannot_treat_repo_file_as_external_key(
        self,
    ) -> None:
        resolved_root = _resolve_repository_root(PROJECT_ROOT / "tools")
        self.assertEqual(PROJECT_ROOT, resolved_root)
        args = SimpleNamespace(
            content_input_manifest=None,
            holdout_manifest=None,
            holdout_release_registration=None,
            holdout_fingerprint_key=PROJECT_ROOT / "build.gradle.kts",
        )

        with self.assertRaisesRegex(ValueError, "outside the repository"):
            _load_holdout_gate(
                resolved_root,
                args,
                require_complete=False,
            )

    def test_external_fingerprint_key_is_a_protected_compiler_input(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            key_path = Path(directory) / "holdout-fingerprint.key"
            key_path.write_bytes(FINGERPRINT_KEY)
            args = SimpleNamespace(
                content_input_manifest=None,
                holdout_manifest=None,
                holdout_release_registration=None,
                holdout_fingerprint_key=key_path,
            )

            _, protected_inputs = _load_holdout_gate(
                PROJECT_ROOT,
                args,
                require_complete=False,
            )

            self.assertIn(key_path.resolve(), protected_inputs)


if __name__ == "__main__":
    unittest.main()
