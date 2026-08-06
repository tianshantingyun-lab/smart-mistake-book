"""Synthetic, non-question holdout proof used only by compiler unit tests."""

from __future__ import annotations

import hashlib
from contextlib import contextmanager
from typing import Any, Mapping
from unittest.mock import patch

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import rsa

from knowledge_pack_production import holdout_gate
from knowledge_pack_production.holdout_gate import (
    SIGNATURE_ALGORITHM,
    build_content_input_manifest,
    build_holdout_release_registration,
    build_signed_holdout_manifest,
    fingerprint_holdout_record,
    holdout_release_registration_fingerprint,
)


_FINGERPRINT_KEY = hashlib.sha256(
    b"formal compiler synthetic holdout fingerprint key"
).digest()
_FINGERPRINT_KEY_ID = "fixture-formal-compiler-holdout-key-v1"
_PRIVATE_KEY = rsa.generate_private_key(public_exponent=65_537, key_size=2_048)
_PRIVATE_KEY_PEM = _PRIVATE_KEY.private_bytes(
    serialization.Encoding.PEM,
    serialization.PrivateFormat.PKCS8,
    serialization.NoEncryption(),
)
_TRUSTED_KEY = {
    "keyId": "fixture-formal-compiler-holdout-signing-v1",
    "algorithm": SIGNATURE_ALGORITHM,
    "x509PublicKeyHex": _PRIVATE_KEY.public_key()
    .public_bytes(
        serialization.Encoding.DER,
        serialization.PublicFormat.SubjectPublicKeyInfo,
    )
    .hex(),
    "validFromEpochMillis": 0,
    "validUntilEpochMillis": 10_000,
    "revokedAtEpochMillis": None,
    "revocationEvidenceFingerprint": None,
}
_HOLDOUT_MANIFEST = build_signed_holdout_manifest(
    [
        fingerprint_holdout_record(
            acceptance_id="synthetic-private-holdout-sentinel-v1",
            fingerprint_key=_FINGERPRINT_KEY,
            prompt_text="Synthetic reserved holdout sentinel, not a question",
            annotation={"kind": "synthetic-isolation-sentinel"},
        )
    ],
    manifest_id="fixture-formal-compiler-holdout-v1",
    manifest_version="fixture-v1",
    created_at_epoch_millis=250,
    fingerprint_key_id=_FINGERPRINT_KEY_ID,
    fingerprint_key=_FINGERPRINT_KEY,
    release_generation=1,
    signing_key_id=_TRUSTED_KEY["keyId"],
    signed_at_epoch_millis=260,
    private_key_pem=_PRIVATE_KEY_PEM,
)
_HOLDOUT_RELEASE_REGISTRATION = build_holdout_release_registration(
    _HOLDOUT_MANIFEST,
    registration_id="fixture-formal-compiler-holdout-release-v1",
    signing_key=_TRUSTED_KEY,
)
_HOLDOUT_RELEASE_REGISTRATION_FINGERPRINT = (
    holdout_release_registration_fingerprint(
        _HOLDOUT_RELEASE_REGISTRATION
    )
)


@contextmanager
def pinned_formal_holdout_release():
    with patch.object(
        holdout_gate,
        "PINNED_HOLDOUT_RELEASE_REGISTRATION",
        (_HOLDOUT_RELEASE_REGISTRATION_FINGERPRINT, 1),
    ):
        yield


def formal_holdout_gate_kwargs(
    coverage_ledger: Mapping[str, Any],
    source_register: Mapping[str, Any],
    teaching_inventory: Mapping[str, Any],
    teaching_decisions: Mapping[str, Any],
    pack_review: Mapping[str, Any],
) -> dict[str, Any]:
    inputs = formal_holdout_build_inputs(
        coverage_ledger,
        source_register,
        teaching_inventory,
        teaching_decisions,
        pack_review,
    )
    return {
        "content_input_manifest": build_content_input_manifest(
            inputs,
            manifest_id="fixture-formal-content-input-v1",
            manifest_version="fixture-v1",
            created_at_epoch_millis=250,
            fingerprint_key_id=_FINGERPRINT_KEY_ID,
            fingerprint_key=_FINGERPRINT_KEY,
        ),
        "holdout_manifest": _HOLDOUT_MANIFEST,
        "holdout_fingerprint_key": _FINGERPRINT_KEY,
        "holdout_release_registration": _HOLDOUT_RELEASE_REGISTRATION,
    }


def formal_holdout_build_inputs(
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


def formal_holdout_release_gate_kwargs(
    coverage_ledger: Mapping[str, Any],
    source_register: Mapping[str, Any],
    teaching_inventory: Mapping[str, Any],
    teaching_decisions: Mapping[str, Any],
    pack_review: Mapping[str, Any],
) -> dict[str, Any]:
    inputs = formal_holdout_build_inputs(
        coverage_ledger,
        source_register,
        teaching_inventory,
        teaching_decisions,
        pack_review,
    )
    compiler_kwargs = formal_holdout_gate_kwargs(
        coverage_ledger,
        source_register,
        teaching_inventory,
        teaching_decisions,
        pack_review,
    )
    return {
        "holdout_build_inputs": inputs,
        **compiler_kwargs,
    }
