"""Fail-closed isolation between knowledge inputs and private acceptance holdouts.

Only keyed, irreversible fingerprints cross the holdout boundary. The compiler
never needs question images, prompts, annotations, question numbers, or labels.
"""

from __future__ import annotations

import hashlib
import hmac
import json
import math
import re
import unicodedata
from copy import deepcopy
from decimal import Decimal, InvalidOperation
from typing import Any, Iterable, Mapping, Sequence

from .input_hashes import artifact_fingerprint, canonical_fingerprint


CONTENT_INPUT_MANIFEST_SCHEMA_VERSION = 1
HOLDOUT_MANIFEST_SCHEMA_VERSION = 2
HOLDOUT_COMPLIANCE_REPORT_SCHEMA_VERSION = 1
HOLDOUT_RELEASE_REGISTRATION_SCHEMA_VERSION = 2
FINGERPRINT_ALGORITHM = "HMAC-SHA-256"
FINGERPRINT_POLICY_VERSION = "knowledge-holdout-isolation-v2"
SIGNATURE_ALGORITHM = "SHA256_WITH_RSA_2048"
HOLDOUT_PURPOSE = "HUMAN_RETRIEVAL_ATTRIBUTION_ACCEPTANCE_HOLDOUT"
FINGERPRINT_KEY_PATH_ENV = "SMART_MISTAKEBOOK_HOLDOUT_FINGERPRINT_KEY_PATH"
PINNED_HOLDOUT_RELEASE_REGISTRATION: tuple[str, int] | None = None

_SHA256 = re.compile(r"^[0-9a-f]{64}$")
_SHA256_IN_TEXT = re.compile(
    r"(?i)(?<![0-9a-f])[0-9a-f]{64}(?![0-9a-f])"
)
_HEX = re.compile(r"^[0-9a-f]+$")
_ID = re.compile(r"^[^\x00-\x1f\x7f]{1,160}$")
_NUMERIC_TOKEN = re.compile(
    r"(?<![\w.])"
    r"(?P<value>[+-]?(?:\d+(?:\.\d+)?|\.\d+)(?:[eE][+-]?\d+)?)"
    r"(?![\w.])"
)
_NUMERIC_TOKEN_FULL = re.compile(
    r"[+-]?(?:\d+(?:\.\d+)?|\.\d+)(?:[eE][+-]?\d+)?"
)
_SYMBOL_TOKENS = (
    "<=>",
    "<=",
    ">=",
    "!=",
    "==",
    "->",
    "<-",
    "=>",
    "⇌",
    "→",
    "←",
    "↔",
    "≤",
    "≥",
    "≠",
    "≈",
    "±",
    "×",
    "÷",
    "=",
    "<",
    ">",
    "+",
    "-",
    "*",
    "/",
)
_SYMBOL_TOKEN_SET = frozenset(_SYMBOL_TOKENS)
_SYMBOL_TOKEN_PATTERN = re.compile(
    "|".join(re.escape(value) for value in _SYMBOL_TOKENS)
)
_DERIVED_SYMBOL_TOKENS = tuple(
    token
    for token in _SYMBOL_TOKENS
    if token not in {"+", "-", "*", "/"}
)
_DERIVED_SYMBOL_TOKEN_PATTERN = re.compile(
    "|".join(re.escape(value) for value in _DERIVED_SYMBOL_TOKENS)
)
_WORD_FRAGMENT = re.compile(r"[^\W_]+", re.UNICODE)
_MAX_JOINED_CANDIDATE_PARTS = 64
_MAX_JOINED_CANDIDATE_LENGTH = 8_192
_MAX_HOLDOUT_ALIASES = 256
_MAX_ORDERED_FRAGMENT_GROUPS_PER_ENTRY = 8
_MAX_ORDERED_FRAGMENTS_PER_GROUP = 6
_MAX_ORDERED_FRAGMENT_SKIPPED_VALUES = 25_000
_MIN_ORDERED_FRAGMENT_LENGTH = 4
_MAX_CONTAINMENT_WINDOW_OPERATIONS = 2_000_000
_MAX_ORDERED_FRAGMENT_WINDOW_OPERATIONS = 1_000_000
MAX_JSON_DEPTH = 32
MAX_JSON_NODES = 50_000
MAX_JSON_UTF8_BYTES = 8 * 1024 * 1024
MAX_JSON_STRINGS = 25_000
MAX_JSON_STRING_BYTES = 1 * 1024 * 1024
MAX_JSON_CONTAINER_ITEMS = 50_000
_CONTENT_INPUT_NAMES = (
    "coverageLedger",
    "sourceRegister",
    "teachingInventory",
    "teachingDecisions",
    "packReviewContent",
)
_SURFACE_NAMES = (
    "rawText",
    "normalizedText",
    "derivedText",
    "declaredDigest",
    "structuredValue",
)
_CONTENT_MANIFEST_KEYS = {
    "schemaVersion",
    "manifestId",
    "manifestVersion",
    "createdAtEpochMillis",
    "fingerprintAlgorithm",
    "fingerprintPolicyVersion",
    "fingerprintKeyId",
    "fingerprintKeyCommitment",
    "inputArtifactFingerprints",
    "surfaceSummaries",
}
_SURFACE_SUMMARY_KEYS = {"itemCount", "setFingerprint"}
_HOLDOUT_MANIFEST_KEYS = {
    "schemaVersion",
    "manifestId",
    "manifestVersion",
    "purpose",
    "createdAtEpochMillis",
    "fingerprintAlgorithm",
    "fingerprintPolicyVersion",
    "fingerprintKeyId",
    "fingerprintKeyCommitment",
    "releaseGeneration",
    "entryCount",
    "entrySetFingerprint",
    "entries",
    "signature",
}
_CONTAINMENT_SIGNAL_KEYS = {"surface", "length", "fingerprint"}
_TYPED_TOKEN_SIGNAL_KEYS = {"domain", "fingerprint"}
_ORDERED_FRAGMENT_KEYS = {"length", "fingerprint"}
_ORDERED_FRAGMENT_GROUP_KEYS = {
    "surface",
    "maxSkippedValues",
    "fragments",
}
_TYPED_TOKEN_DOMAINS = {
    "integerToken": "integer-token",
    "decimalToken": "decimal-token",
    "symbolToken": "symbol-token",
}
_HOLDOUT_ENTRY_KEYS = {
    "acceptanceIdFingerprint",
    "acceptanceIdNormalizedFingerprint",
    "acceptanceIdDerivedFingerprint",
    "imageDigestFingerprint",
    "promptFingerprint",
    "normalizedPromptFingerprint",
    "derivedPromptFingerprint",
    "annotationFingerprint",
    "derivedAliasFingerprints",
    "typedTokenFingerprints",
    "containmentSignals",
    "orderedFragmentGroups",
}
_HOLDOUT_SIGNATURE_KEYS = {
    "keyId",
    "algorithm",
    "signedAtEpochMillis",
    "signatureHex",
}
_TRUSTED_KEY_KEYS = {
    "keyId",
    "algorithm",
    "x509PublicKeyHex",
    "validFromEpochMillis",
    "validUntilEpochMillis",
    "revokedAtEpochMillis",
    "revocationEvidenceFingerprint",
}
_HOLDOUT_RELEASE_REGISTRATION_KEYS = {
    "schemaVersion",
    "registrationId",
    "purpose",
    "releaseGeneration",
    "manifestId",
    "manifestVersion",
    "manifestFingerprint",
    "fingerprintPolicyVersion",
    "fingerprintKeyId",
    "fingerprintKeyCommitment",
    "signingKey",
    "signingKeyFingerprint",
}


def build_content_input_manifest(
    inputs: Mapping[str, Mapping[str, Any]],
    *,
    manifest_id: str,
    manifest_version: str,
    created_at_epoch_millis: int,
    fingerprint_key_id: str,
    fingerprint_key: bytes,
) -> dict[str, Any]:
    """Register exact compiler inputs without emitting any input text or item hash."""

    _preflight_build_inputs(inputs)
    sanitized = _sanitize_build_inputs(inputs)
    surfaces = _scan_content_surfaces(sanitized, fingerprint_key)
    return {
        "schemaVersion": CONTENT_INPUT_MANIFEST_SCHEMA_VERSION,
        "manifestId": _require_id(manifest_id, "content-input manifest id"),
        "manifestVersion": _require_id(
            manifest_version,
            "content-input manifest version",
        ),
        "createdAtEpochMillis": _require_nonnegative_int(
            created_at_epoch_millis,
            "content-input manifest time",
        ),
        "fingerprintAlgorithm": FINGERPRINT_ALGORITHM,
        "fingerprintPolicyVersion": FINGERPRINT_POLICY_VERSION,
        "fingerprintKeyId": _require_id(
            fingerprint_key_id,
            "holdout fingerprint-key id",
        ),
        "fingerprintKeyCommitment": _fingerprint_key_commitment(
            fingerprint_key
        ),
        "inputArtifactFingerprints": {
            name: artifact_fingerprint(sanitized[name])
            for name in _CONTENT_INPUT_NAMES
        },
        "surfaceSummaries": {
            name: _surface_summary(name, surfaces[name])
            for name in _SURFACE_NAMES
        },
    }


def fingerprint_holdout_record(
    *,
    acceptance_id: str,
    fingerprint_key: bytes,
    image_sha256: str | None = None,
    prompt_text: str | None = None,
    annotation: Mapping[str, Any] | Sequence[Any] | None = None,
    derived_aliases: Iterable[str] = (),
) -> dict[str, Any]:
    """Hash one record in memory; returned data cannot reconstruct the record."""

    _require_fingerprint_key(fingerprint_key)
    identifier = _require_text(acceptance_id, "acceptance id")
    identifier_derived = _derive_text(identifier)
    if not identifier_derived:
        raise ValueError("Acceptance id must contain letters or digits")
    image_digest = None
    if image_sha256 is not None:
        image_digest = _require_sha256(image_sha256, "holdout image digest")
    prompt = None if prompt_text is None else _require_text(
        prompt_text,
        "holdout prompt",
    )
    prompt_derived = None if prompt is None else _derive_text(prompt)
    prompt_token = (
        None if prompt is None else _exclusive_typed_token(prompt)
    )
    if prompt is not None and not prompt_derived and prompt_token is None:
        raise ValueError(
            "Holdout prompt must contain text or one supported token"
        )
    annotation_fingerprint = None
    annotation_derived = None
    annotation_json = None
    if annotation is not None:
        if not isinstance(annotation, (Mapping, list, tuple)):
            raise ValueError("Holdout annotation must be a structured value")
    aliases: list[str] = []
    for alias in derived_aliases:
        if len(aliases) >= _MAX_HOLDOUT_ALIASES:
            raise ValueError("Holdout derived aliases exceed the resource budget")
        aliases.append(_require_text(alias, "holdout derived alias"))
    _validate_json_budget(
        {
            "acceptanceId": identifier,
            "imageDigest": image_digest,
            "prompt": prompt,
            "annotation": annotation,
            "derivedAliases": aliases,
        },
        "holdout record",
    )
    if annotation is not None:
        annotation_json = _canonical_json(annotation)
        annotation_fingerprint = _fingerprint(
            fingerprint_key,
            "structured-value",
            annotation_json,
        )
        annotation_derived = _derive_text(annotation_json)
        if not annotation_derived:
            raise ValueError(
                "Holdout annotation must contain letters or digits"
            )
    derived_alias_values: list[str] = []
    typed_token_values: list[tuple[str, str]] = (
        [] if prompt_token is None else [prompt_token]
    )
    ordered_aliases: list[str] = []
    for alias in aliases:
        exclusive_token = _exclusive_typed_token(alias)
        if exclusive_token is not None:
            typed_token_values.append(exclusive_token)
            continue
        derived = _derive_text(alias)
        if not derived:
            raise ValueError(
                "Holdout derived alias must contain text or one supported token"
            )
        derived_alias_values.append(derived)
        ordered_aliases.append(alias)
    if (
        image_digest is None
        and prompt is None
        and annotation_fingerprint is None
        and not aliases
    ):
        raise ValueError("Holdout record requires hashed acceptance evidence")
    containment_values: list[tuple[str, str]] = [
        ("derivedText", identifier_derived),
    ]
    if prompt_derived:
        containment_values.append(("derivedText", prompt_derived))
    if image_digest is not None:
        containment_values.append(("declaredDigest", image_digest))
    if annotation is not None:
        containment_values.append(
            ("derivedText", annotation_derived or "")
        )
    containment_values.extend(
        ("derivedText", alias) for alias in derived_alias_values
    )
    containment_signals = _build_containment_signals(
        fingerprint_key,
        containment_values,
    )
    ordered_fragment_groups = _build_ordered_fragment_groups(
        fingerprint_key,
        [
            identifier,
            *([] if prompt is None else [prompt]),
            *([] if annotation_json is None else [annotation_json]),
            *ordered_aliases,
        ],
    )
    return {
        "acceptanceIdFingerprint": _fingerprint(
            fingerprint_key,
            "raw-text",
            identifier,
        ),
        "acceptanceIdNormalizedFingerprint": _fingerprint(
            fingerprint_key,
            "normalized-text",
            _normalize_text(identifier),
        ),
        "acceptanceIdDerivedFingerprint": _fingerprint(
            fingerprint_key,
            "derived-text",
            identifier_derived,
        ),
        "imageDigestFingerprint": (
            None
            if image_digest is None
            else _fingerprint(
                fingerprint_key,
                "declared-digest",
                image_digest,
            )
        ),
        "promptFingerprint": (
            None
            if prompt is None
            else _fingerprint(fingerprint_key, "raw-text", prompt)
        ),
        "normalizedPromptFingerprint": (
            None
            if prompt is None
            else _fingerprint(
                fingerprint_key,
                "normalized-text",
                _normalize_text(prompt),
            )
        ),
        "derivedPromptFingerprint": (
            None
            if not prompt_derived
            else _fingerprint(
                fingerprint_key,
                "derived-text",
                prompt_derived,
            )
        ),
        "annotationFingerprint": annotation_fingerprint,
        "derivedAliasFingerprints": sorted(
            {
                _fingerprint(
                    fingerprint_key,
                    "derived-text",
                    alias,
                )
                for alias in derived_alias_values
            }
        ),
        "typedTokenFingerprints": [
            {
                "domain": domain,
                "fingerprint": fingerprint,
            }
            for domain, fingerprint in sorted(
                {
                    (
                        domain,
                        _fingerprint(
                            fingerprint_key,
                            _TYPED_TOKEN_DOMAINS[domain],
                            value,
                        ),
                    )
                    for domain, value in typed_token_values
                }
            )
        ],
        "containmentSignals": containment_signals,
        "orderedFragmentGroups": ordered_fragment_groups,
    }


def build_signed_holdout_manifest(
    entries: Sequence[Mapping[str, Any]],
    *,
    manifest_id: str,
    manifest_version: str,
    created_at_epoch_millis: int,
    fingerprint_key_id: str,
    fingerprint_key: bytes,
    release_generation: int,
    signing_key_id: str,
    signed_at_epoch_millis: int,
    private_key_pem: bytes,
    private_key_password: bytes | None = None,
) -> dict[str, Any]:
    """Sign already-fingerprinted entries; no raw holdout content is accepted."""

    _validate_json_budget(entries, "holdout entries")
    canonical_entries = _validate_holdout_entries(entries, require_nonempty=False)
    unsigned: dict[str, Any] = {
        "schemaVersion": HOLDOUT_MANIFEST_SCHEMA_VERSION,
        "manifestId": _require_id(manifest_id, "holdout manifest id"),
        "manifestVersion": _require_id(
            manifest_version,
            "holdout manifest version",
        ),
        "purpose": HOLDOUT_PURPOSE,
        "createdAtEpochMillis": _require_nonnegative_int(
            created_at_epoch_millis,
            "holdout manifest time",
        ),
        "fingerprintAlgorithm": FINGERPRINT_ALGORITHM,
        "fingerprintPolicyVersion": FINGERPRINT_POLICY_VERSION,
        "fingerprintKeyId": _require_id(
            fingerprint_key_id,
            "holdout fingerprint-key id",
        ),
        "fingerprintKeyCommitment": _fingerprint_key_commitment(
            fingerprint_key
        ),
        "releaseGeneration": _require_positive_int(
            release_generation,
            "holdout release generation",
        ),
        "entryCount": len(canonical_entries),
        "entrySetFingerprint": canonical_fingerprint(
            "formal-holdout-entry-set-v2",
            canonical_entries,
        ),
        "entries": canonical_entries,
        "signature": {
            "keyId": _require_id(signing_key_id, "holdout signing-key id"),
            "algorithm": SIGNATURE_ALGORITHM,
            "signedAtEpochMillis": _require_nonnegative_int(
                signed_at_epoch_millis,
                "holdout signature time",
            ),
            "signatureHex": "",
        },
    }
    if unsigned["createdAtEpochMillis"] > signed_at_epoch_millis:
        raise ValueError("Holdout signature cannot predate its manifest")
    signature_hex = _sign_payload(
        private_key_pem,
        private_key_password,
        holdout_signing_payload(
            unsigned,
            allow_unsigned_signature=True,
        ),
    )
    signed = deepcopy(unsigned)
    signed["signature"]["signatureHex"] = signature_hex
    return signed


def build_holdout_release_registration(
    holdout_manifest: Mapping[str, Any],
    *,
    registration_id: str,
    signing_key: Mapping[str, Any],
) -> dict[str, Any]:
    """Build reviewable registration content; it is trusted only when pinned."""

    validate_full_holdout_manifest_before_processing(
        holdout_manifest,
        require_nonempty=True,
    )
    _validate_json_budget(signing_key, "holdout signing key")
    manifest = _require_mapping(holdout_manifest, "holdout manifest")
    key = _validate_trusted_key(signing_key)
    signature = _require_mapping(manifest.get("signature"), "holdout signature")
    if signature.get("keyId") != key["keyId"]:
        raise ValueError("Holdout registration signing key does not match manifest")
    registration = {
        "schemaVersion": HOLDOUT_RELEASE_REGISTRATION_SCHEMA_VERSION,
        "registrationId": _require_id(
            registration_id,
            "holdout release registration id",
        ),
        "purpose": HOLDOUT_PURPOSE,
        "releaseGeneration": _require_positive_int(
            manifest.get("releaseGeneration"),
            "holdout release generation",
        ),
        "manifestId": _require_id(
            manifest.get("manifestId"),
            "holdout manifest id",
        ),
        "manifestVersion": _require_id(
            manifest.get("manifestVersion"),
            "holdout manifest version",
        ),
        "manifestFingerprint": artifact_fingerprint(manifest),
        "fingerprintPolicyVersion": FINGERPRINT_POLICY_VERSION,
        "fingerprintKeyId": _require_id(
            manifest.get("fingerprintKeyId"),
            "holdout fingerprint-key id",
        ),
        "fingerprintKeyCommitment": _require_sha256(
            manifest.get("fingerprintKeyCommitment"),
            "holdout fingerprint-key commitment",
        ),
        "signingKey": key,
        "signingKeyFingerprint": artifact_fingerprint(key),
    }
    return _validate_release_registration_schema(registration)


def holdout_release_registration_fingerprint(
    registration: Mapping[str, Any],
) -> str:
    _preflight_holdout_release_registration(registration)
    normalized = _validate_release_registration_schema(registration)
    return canonical_fingerprint(
        "formal-holdout-release-registration-v2",
        normalized,
    )


def verify_holdout_isolation(
    *,
    build_inputs: Mapping[str, Mapping[str, Any]],
    content_input_manifest: Mapping[str, Any] | None,
    holdout_manifest: Mapping[str, Any] | None,
    fingerprint_key: bytes | None,
    holdout_release_registration: Mapping[str, Any] | None,
) -> dict[str, Any]:
    """Verify manifests and reject exact, normalized, or derived contamination."""

    if content_input_manifest is None:
        raise ValueError("Formal release requires a content-input manifest")
    if holdout_manifest is None:
        raise ValueError("Formal release requires a signed holdout manifest")
    if fingerprint_key is None:
        raise ValueError("Formal release requires the holdout fingerprint key")
    if holdout_release_registration is None:
        raise ValueError(
            "Formal release requires a pinned holdout release registration"
        )
    validate_full_holdout_manifest_before_processing(
        holdout_manifest,
        require_nonempty=True,
    )
    _preflight_build_inputs(build_inputs)
    _preflight_content_input_manifest(content_input_manifest)
    _preflight_holdout_release_registration(
        holdout_release_registration
    )
    _require_fingerprint_key(fingerprint_key)
    registration = _validate_pinned_release_registration(
        holdout_release_registration
    )
    content = _validate_content_input_manifest(
        build_inputs,
        content_input_manifest,
        fingerprint_key,
    )
    holdout = _validate_signed_holdout_manifest(
        holdout_manifest,
        [registration["signingKey"]],
    )
    _require_registered_holdout_manifest(holdout, registration)
    if content["fingerprintKeyId"] != holdout["fingerprintKeyId"]:
        raise ValueError("Build and holdout manifests use different fingerprint keys")
    key_commitment = _fingerprint_key_commitment(fingerprint_key)
    if (
        content["fingerprintKeyCommitment"] != key_commitment
        or holdout["fingerprintKeyCommitment"] != key_commitment
    ):
        raise ValueError(
            "Holdout fingerprint key does not match the signed manifest"
        )

    sanitized_inputs = _sanitize_build_inputs(build_inputs)
    surfaces = _scan_content_surfaces(sanitized_inputs, fingerprint_key)
    holdout_surfaces = _holdout_surface_sets(holdout["entries"])
    overlaps = {
        name: surfaces[name].intersection(holdout_surfaces[name])
        for name in _SURFACE_NAMES
    }
    containment_overlaps = _find_containment_overlaps(
        sanitized_inputs,
        holdout["entries"],
        fingerprint_key,
    )
    for surface, fingerprints in containment_overlaps.items():
        overlaps[surface].update(fingerprints)
    typed_token_overlaps = _find_typed_token_overlaps(
        sanitized_inputs,
        holdout["entries"],
        fingerprint_key,
    )
    overlaps["derivedText"].update(typed_token_overlaps)
    ordered_fragment_overlaps = _find_ordered_fragment_overlaps(
        sanitized_inputs,
        holdout["entries"],
        fingerprint_key,
    )
    overlaps["derivedText"].update(ordered_fragment_overlaps)
    overlap_counts = {
        name: len(overlaps[name]) for name in _SURFACE_NAMES
    }
    overlap_total = sum(overlap_counts.values())
    if overlap_total:
        affected_surfaces = sum(count > 0 for count in overlap_counts.values())
        raise ValueError(
            "Formal content overlaps the private acceptance holdout; "
            f"affectedSurfaces={affected_surfaces}, overlapCount={overlap_total}"
        )

    report_without_fingerprint = {
        "schemaVersion": HOLDOUT_COMPLIANCE_REPORT_SCHEMA_VERSION,
        "contentInputManifestId": content["manifestId"],
        "contentInputManifestVersion": content["manifestVersion"],
        "contentInputManifestFingerprint": artifact_fingerprint(content),
        "holdoutManifestId": holdout["manifestId"],
        "holdoutManifestVersion": holdout["manifestVersion"],
        "holdoutManifestFingerprint": artifact_fingerprint(holdout),
        "holdoutReleaseRegistrationId": registration["registrationId"],
        "holdoutReleaseRegistrationFingerprint": (
            holdout_release_registration_fingerprint(registration)
        ),
        "holdoutReleaseGeneration": registration["releaseGeneration"],
        "fingerprintPolicyVersion": FINGERPRINT_POLICY_VERSION,
        "fingerprintKeyId": content["fingerprintKeyId"],
        "contentSurfaceCounts": {
            name: len(surfaces[name]) for name in _SURFACE_NAMES
        },
        "holdoutEntryCount": holdout["entryCount"],
        "overlapCount": 0,
    }
    return {
        **report_without_fingerprint,
        "complianceFingerprint": canonical_fingerprint(
            "formal-holdout-compliance-report-v1",
            report_without_fingerprint,
        ),
    }


def holdout_signing_payload(
    manifest: Mapping[str, Any],
    *,
    allow_unsigned_signature: bool = False,
) -> bytes:
    validate_full_holdout_manifest_before_processing(
        manifest,
        require_nonempty=False,
        allow_unsigned_signature=allow_unsigned_signature,
    )
    unsigned = deepcopy(dict(manifest))
    signature = unsigned.get("signature")
    if not isinstance(signature, dict):
        raise ValueError("Holdout manifest signature is missing")
    signature["signatureHex"] = ""
    return (
        "formal-private-holdout-manifest-v2\0" + _canonical_json(unsigned)
    ).encode("utf-8")


def _validate_content_input_manifest(
    build_inputs: Mapping[str, Mapping[str, Any]],
    manifest: Mapping[str, Any],
    fingerprint_key: bytes,
) -> dict[str, Any]:
    _preflight_build_inputs(build_inputs)
    _preflight_content_input_manifest(manifest)
    _require_exact_keys(manifest, _CONTENT_MANIFEST_KEYS, "content-input manifest")
    if manifest.get("schemaVersion") != CONTENT_INPUT_MANIFEST_SCHEMA_VERSION:
        raise ValueError("Unsupported content-input manifest schema")
    _require_id(manifest["manifestId"], "content-input manifest id")
    _require_id(manifest["manifestVersion"], "content-input manifest version")
    _require_nonnegative_int(
        manifest["createdAtEpochMillis"],
        "content-input manifest time",
    )
    _require_fingerprint_contract(manifest)
    expected = build_content_input_manifest(
        build_inputs,
        manifest_id=manifest["manifestId"],
        manifest_version=manifest["manifestVersion"],
        created_at_epoch_millis=manifest["createdAtEpochMillis"],
        fingerprint_key_id=manifest["fingerprintKeyId"],
        fingerprint_key=fingerprint_key,
    )
    if dict(manifest) != expected:
        raise ValueError("Content-input manifest does not match compiler inputs")
    return expected


def _validate_signed_holdout_manifest(
    manifest: Mapping[str, Any],
    trusted_signing_keys: Sequence[Mapping[str, Any]],
) -> dict[str, Any]:
    validate_full_holdout_manifest_before_processing(
        manifest,
        require_nonempty=True,
    )
    signature = _require_mapping(manifest["signature"], "holdout signature")
    key_id = _require_id(signature["keyId"], "holdout signing-key id")
    signed_at = _require_nonnegative_int(
        signature["signedAtEpochMillis"],
        "holdout signature time",
    )
    signature_hex = signature.get("signatureHex")
    key_candidates = [
        _validate_trusted_key(key)
        for key in trusted_signing_keys
        if isinstance(key, Mapping) and key.get("keyId") == key_id
    ]
    if len(key_candidates) != 1:
        raise ValueError("Holdout signature key is not uniquely trusted")
    key = key_candidates[0]
    if key["revokedAtEpochMillis"] is not None:
        raise ValueError("Holdout signature key is revoked")
    if signed_at not in range(
        key["validFromEpochMillis"],
        key["validUntilEpochMillis"] + 1,
    ):
        raise ValueError("Holdout signature is outside key validity")
    _verify_signature(
        key["x509PublicKeyHex"],
        holdout_signing_payload(manifest),
        signature_hex,
    )
    return deepcopy(dict(manifest))


def _validate_trusted_key(key: Mapping[str, Any]) -> dict[str, Any]:
    _require_exact_keys(key, _TRUSTED_KEY_KEYS, "trusted holdout key")
    _require_id(key["keyId"], "trusted holdout key id")
    if key.get("algorithm") != SIGNATURE_ALGORITHM:
        raise ValueError("Unsupported trusted holdout key algorithm")
    public_key = key.get("x509PublicKeyHex")
    if (
        not isinstance(public_key, str)
        or len(public_key) % 2
        or not _HEX.fullmatch(public_key)
    ):
        raise ValueError("Trusted holdout public key is invalid")
    valid_from = _require_nonnegative_int(
        key["validFromEpochMillis"],
        "trusted holdout key valid-from time",
    )
    valid_until = _require_nonnegative_int(
        key["validUntilEpochMillis"],
        "trusted holdout key valid-until time",
    )
    if valid_until < valid_from:
        raise ValueError("Trusted holdout key validity is invalid")
    revoked_at = key["revokedAtEpochMillis"]
    revocation_fingerprint = key["revocationEvidenceFingerprint"]
    if (revoked_at is None) != (revocation_fingerprint is None):
        raise ValueError("Holdout key revocation evidence is incomplete")
    if revoked_at is not None:
        _require_nonnegative_int(revoked_at, "holdout key revocation time")
        _require_sha256(
            revocation_fingerprint,
            "holdout key revocation evidence",
        )
    return dict(key)


def _validate_release_registration_schema(
    registration: Mapping[str, Any],
) -> dict[str, Any]:
    _preflight_holdout_release_registration(registration)
    value = _require_mapping(
        registration,
        "holdout release registration",
    )
    _require_exact_keys(
        value,
        _HOLDOUT_RELEASE_REGISTRATION_KEYS,
        "holdout release registration",
    )
    if (
        value.get("schemaVersion")
        != HOLDOUT_RELEASE_REGISTRATION_SCHEMA_VERSION
    ):
        raise ValueError("Unsupported holdout release-registration schema")
    if value.get("purpose") != HOLDOUT_PURPOSE:
        raise ValueError("Holdout release registration has an invalid purpose")
    normalized = {
        "schemaVersion": HOLDOUT_RELEASE_REGISTRATION_SCHEMA_VERSION,
        "registrationId": _require_id(
            value["registrationId"],
            "holdout release registration id",
        ),
        "purpose": HOLDOUT_PURPOSE,
        "releaseGeneration": _require_positive_int(
            value["releaseGeneration"],
            "holdout release generation",
        ),
        "manifestId": _require_id(
            value["manifestId"],
            "registered holdout manifest id",
        ),
        "manifestVersion": _require_id(
            value["manifestVersion"],
            "registered holdout manifest version",
        ),
        "manifestFingerprint": _require_sha256(
            value["manifestFingerprint"],
            "registered holdout manifest fingerprint",
        ),
        "fingerprintPolicyVersion": _require_id(
            value["fingerprintPolicyVersion"],
            "registered holdout fingerprint policy",
        ),
        "fingerprintKeyId": _require_id(
            value["fingerprintKeyId"],
            "registered holdout fingerprint-key id",
        ),
        "fingerprintKeyCommitment": _require_sha256(
            value["fingerprintKeyCommitment"],
            "registered holdout fingerprint-key commitment",
        ),
        "signingKey": _validate_trusted_key(
            _require_mapping(
                value["signingKey"],
                "registered holdout signing key",
            )
        ),
        "signingKeyFingerprint": _require_sha256(
            value["signingKeyFingerprint"],
            "registered holdout signing-key fingerprint",
        ),
    }
    if normalized["fingerprintPolicyVersion"] != FINGERPRINT_POLICY_VERSION:
        raise ValueError("Registered holdout fingerprint policy is unsupported")
    if normalized["signingKeyFingerprint"] != artifact_fingerprint(
        normalized["signingKey"]
    ):
        raise ValueError("Registered holdout signing-key fingerprint is invalid")
    return normalized


def _validate_pinned_release_registration(
    registration: Mapping[str, Any],
) -> dict[str, Any]:
    normalized = _validate_release_registration_schema(registration)
    fingerprint = canonical_fingerprint(
        "formal-holdout-release-registration-v2",
        normalized,
    )
    active_registration = PINNED_HOLDOUT_RELEASE_REGISTRATION
    if (
        active_registration is None
        or active_registration
        != (fingerprint, normalized["releaseGeneration"])
    ):
        raise ValueError(
            "Holdout release registration is not pinned as the active generation"
        )
    return normalized


def _require_registered_holdout_manifest(
    manifest: Mapping[str, Any],
    registration: Mapping[str, Any],
) -> None:
    expected = {
        "manifestId": manifest["manifestId"],
        "manifestVersion": manifest["manifestVersion"],
        "manifestFingerprint": artifact_fingerprint(manifest),
        "releaseGeneration": manifest["releaseGeneration"],
        "fingerprintKeyId": manifest["fingerprintKeyId"],
        "fingerprintKeyCommitment": manifest["fingerprintKeyCommitment"],
        "signingKeyId": manifest["signature"]["keyId"],
    }
    registered = {
        "manifestId": registration["manifestId"],
        "manifestVersion": registration["manifestVersion"],
        "manifestFingerprint": registration["manifestFingerprint"],
        "releaseGeneration": registration["releaseGeneration"],
        "fingerprintKeyId": registration["fingerprintKeyId"],
        "fingerprintKeyCommitment": registration[
            "fingerprintKeyCommitment"
        ],
        "signingKeyId": registration["signingKey"]["keyId"],
    }
    if expected != registered:
        raise ValueError(
            "Holdout manifest does not match the pinned release generation"
        )


def _validate_holdout_entries(
    entries: Any,
    *,
    require_nonempty: bool,
) -> list[dict[str, Any]]:
    if not isinstance(entries, Sequence) or isinstance(entries, (str, bytes)):
        raise ValueError("Holdout entries must be an array")
    canonical: list[dict[str, Any]] = []
    for raw in entries:
        entry = _require_mapping(raw, "holdout entry")
        _require_exact_keys(entry, _HOLDOUT_ENTRY_KEYS, "holdout entry")
        normalized: dict[str, Any] = {}
        for name in (
            "acceptanceIdFingerprint",
            "acceptanceIdNormalizedFingerprint",
            "acceptanceIdDerivedFingerprint",
        ):
            normalized[name] = _require_sha256(entry[name], name)
        evidence_count = 0
        for name in (
            "imageDigestFingerprint",
            "promptFingerprint",
            "normalizedPromptFingerprint",
            "derivedPromptFingerprint",
            "annotationFingerprint",
        ):
            value = entry[name]
            normalized[name] = (
                None if value is None else _require_sha256(value, name)
            )
            evidence_count += value is not None
        aliases = entry["derivedAliasFingerprints"]
        if not isinstance(aliases, list):
            raise ValueError("Derived holdout fingerprints must be an array")
        normalized_aliases = sorted(
            {_require_sha256(value, "derived alias fingerprint") for value in aliases}
        )
        if len(normalized_aliases) != len(aliases):
            raise ValueError("Derived holdout fingerprints must be unique and sorted")
        normalized["derivedAliasFingerprints"] = normalized_aliases
        evidence_count += len(normalized_aliases)
        typed_tokens = entry["typedTokenFingerprints"]
        if not isinstance(typed_tokens, list):
            raise ValueError("Typed holdout fingerprints must be an array")
        normalized_typed_tokens: list[dict[str, str]] = []
        for raw_token in typed_tokens:
            token = _require_mapping(
                raw_token,
                "typed holdout fingerprint",
            )
            _require_exact_keys(
                token,
                _TYPED_TOKEN_SIGNAL_KEYS,
                "typed holdout fingerprint",
            )
            domain = token["domain"]
            if domain not in _TYPED_TOKEN_DOMAINS:
                raise ValueError("Typed holdout fingerprint domain is invalid")
            normalized_typed_tokens.append(
                {
                    "domain": domain,
                    "fingerprint": _require_sha256(
                        token["fingerprint"],
                        "typed holdout fingerprint",
                    ),
                }
            )
        canonical_typed_tokens = sorted(
            normalized_typed_tokens,
            key=lambda token: (token["domain"], token["fingerprint"]),
        )
        if (
            canonical_typed_tokens != typed_tokens
            or len(
                {
                    (token["domain"], token["fingerprint"])
                    for token in canonical_typed_tokens
                }
            )
            != len(canonical_typed_tokens)
        ):
            raise ValueError(
                "Typed holdout fingerprints must be unique and sorted"
            )
        normalized["typedTokenFingerprints"] = canonical_typed_tokens
        evidence_count += len(canonical_typed_tokens)
        signals = entry["containmentSignals"]
        if not isinstance(signals, list):
            raise ValueError("Holdout containment signals must be an array")
        normalized_signals: list[dict[str, Any]] = []
        for raw_signal in signals:
            signal = _require_mapping(
                raw_signal,
                "holdout containment signal",
            )
            _require_exact_keys(
                signal,
                _CONTAINMENT_SIGNAL_KEYS,
                "holdout containment signal",
            )
            surface = signal["surface"]
            if surface not in {
                "normalizedText",
                "derivedText",
                "declaredDigest",
            }:
                raise ValueError("Holdout containment signal surface is invalid")
            normalized_signals.append(
                {
                    "surface": surface,
                    "length": _require_positive_int(
                        signal["length"],
                        "holdout containment signal length",
                    ),
                    "fingerprint": _require_sha256(
                        signal["fingerprint"],
                        "holdout containment signal fingerprint",
                    ),
                }
            )
        canonical_signals = sorted(
            normalized_signals,
            key=lambda signal: (
                signal["surface"],
                signal["length"],
                signal["fingerprint"],
            ),
        )
        if canonical_signals != signals or len(
            {
                (
                    signal["surface"],
                    signal["length"],
                    signal["fingerprint"],
                )
                for signal in canonical_signals
            }
        ) != len(canonical_signals):
            raise ValueError(
                "Holdout containment signals must be unique and sorted"
            )
        normalized["containmentSignals"] = canonical_signals
        groups = entry["orderedFragmentGroups"]
        if not isinstance(groups, list):
            raise ValueError("Ordered holdout fragment groups must be an array")
        if len(groups) > _MAX_ORDERED_FRAGMENT_GROUPS_PER_ENTRY:
            raise ValueError(
                "Ordered holdout fragment groups exceed the resource budget"
            )
        normalized_groups: list[dict[str, Any]] = []
        for raw_group in groups:
            group = _require_mapping(
                raw_group,
                "ordered holdout fragment group",
            )
            _require_exact_keys(
                group,
                _ORDERED_FRAGMENT_GROUP_KEYS,
                "ordered holdout fragment group",
            )
            if group["surface"] != "derivedText":
                raise ValueError(
                    "Ordered holdout fragment group surface is invalid"
                )
            max_skipped = _require_nonnegative_int(
                group["maxSkippedValues"],
                "ordered holdout fragment skip budget",
            )
            if max_skipped > _MAX_ORDERED_FRAGMENT_SKIPPED_VALUES:
                raise ValueError(
                    "Ordered holdout fragment skip budget is invalid"
                )
            fragments = group["fragments"]
            if not isinstance(fragments, list) or not (
                3 <= len(fragments) <= _MAX_ORDERED_FRAGMENTS_PER_GROUP
            ):
                raise ValueError(
                    "Ordered holdout fragment count is invalid"
                )
            normalized_fragments: list[dict[str, Any]] = []
            for raw_fragment in fragments:
                fragment = _require_mapping(
                    raw_fragment,
                    "ordered holdout fragment",
                )
                _require_exact_keys(
                    fragment,
                    _ORDERED_FRAGMENT_KEYS,
                    "ordered holdout fragment",
                )
                length = _require_positive_int(
                    fragment["length"],
                    "ordered holdout fragment length",
                )
                if length < _MIN_ORDERED_FRAGMENT_LENGTH:
                    raise ValueError(
                        "Ordered holdout fragment length is invalid"
                    )
                normalized_fragments.append(
                    {
                        "length": length,
                        "fingerprint": _require_sha256(
                            fragment["fingerprint"],
                            "ordered holdout fragment fingerprint",
                        ),
                    }
                )
            normalized_groups.append(
                {
                    "surface": "derivedText",
                    "maxSkippedValues": max_skipped,
                    "fragments": normalized_fragments,
                }
            )
        canonical_groups = sorted(
            normalized_groups,
            key=_ordered_fragment_group_sort_key,
        )
        if (
            canonical_groups != groups
            or len(
                {
                    _canonical_json(group)
                    for group in canonical_groups
                }
            )
            != len(canonical_groups)
        ):
            raise ValueError(
                "Ordered holdout fragment groups must be unique and sorted"
            )
        normalized["orderedFragmentGroups"] = canonical_groups
        if evidence_count == 0:
            raise ValueError("Holdout entry lacks hashed acceptance evidence")
        canonical.append(normalized)
    canonical.sort(key=lambda row: row["acceptanceIdFingerprint"])
    if len(
        {entry["acceptanceIdFingerprint"] for entry in canonical}
    ) != len(canonical):
        raise ValueError("Holdout entries contain duplicate acceptance ids")
    if require_nonempty and not canonical:
        raise ValueError("Formal release requires a nonempty holdout manifest")
    return canonical


def _sanitize_build_inputs(
    inputs: Mapping[str, Mapping[str, Any]],
) -> dict[str, Mapping[str, Any]]:
    _preflight_build_inputs(inputs)
    if set(inputs) != set(_CONTENT_INPUT_NAMES):
        raise ValueError("Content-input manifest must bind all compiler inputs")
    sanitized = {
        name: deepcopy(dict(_require_mapping(inputs[name], name)))
        for name in _CONTENT_INPUT_NAMES
    }
    review = dict(sanitized["packReviewContent"])
    review.pop("expectedVersionFingerprint", None)
    sanitized["packReviewContent"] = review
    return sanitized


def _scan_content_surfaces(
    inputs: Mapping[str, Mapping[str, Any]],
    fingerprint_key: bytes,
) -> dict[str, set[str]]:
    _require_fingerprint_key(fingerprint_key)
    surfaces = {name: set() for name in _SURFACE_NAMES}

    def visit(value: Any) -> None:
        if isinstance(value, str):
            surfaces["rawText"].add(
                _fingerprint(fingerprint_key, "raw-text", value)
            )
            normalized = _normalize_text(value)
            if normalized:
                surfaces["normalizedText"].add(
                    _fingerprint(
                        fingerprint_key,
                        "normalized-text",
                        normalized,
                    )
                )
            derived = _derive_text(value)
            if derived:
                surfaces["derivedText"].add(
                    _fingerprint(
                        fingerprint_key,
                        "derived-text",
                        derived,
                    )
                )
            for digest_match in _SHA256_IN_TEXT.finditer(value):
                surfaces["declaredDigest"].add(
                    _fingerprint(
                        fingerprint_key,
                        "declared-digest",
                        digest_match.group(0).casefold(),
                    )
                )
            stripped = value.strip()
            if stripped.startswith(("{", "[")):
                try:
                    structured = json.loads(stripped)
                except json.JSONDecodeError:
                    structured = None
                if isinstance(structured, (Mapping, list)):
                    _validate_json_budget(
                        structured,
                        "embedded structured content",
                    )
                    visit(structured)
            return
        if isinstance(value, (int, float)) and not isinstance(value, bool):
            visit(_canonical_json(value))
            return
        if isinstance(value, Mapping):
            surfaces["structuredValue"].add(
                _fingerprint(
                    fingerprint_key,
                    "structured-value",
                    _canonical_json(value),
                )
            )
            for key, child in value.items():
                visit(str(key))
                visit(child)
            return
        if isinstance(value, (list, tuple)):
            surfaces["structuredValue"].add(
                _fingerprint(
                    fingerprint_key,
                    "structured-value",
                    _canonical_json(value),
                )
            )
            for child in value:
                visit(child)

    for input_value in inputs.values():
        visit(input_value)
    return surfaces


def _find_containment_overlaps(
    inputs: Mapping[str, Mapping[str, Any]],
    entries: Sequence[Mapping[str, Any]],
    fingerprint_key: bytes,
) -> dict[str, set[str]]:
    targets: dict[str, dict[int, set[str]]] = {
        "normalizedText": {},
        "derivedText": {},
        "declaredDigest": {},
    }
    for entry in entries:
        for signal in entry["containmentSignals"]:
            targets[signal["surface"]].setdefault(
                signal["length"],
                set(),
            ).add(signal["fingerprint"])

    candidates = _collect_content_text_candidates(inputs)
    matches: dict[str, set[str]] = {
        "normalizedText": set(),
        "derivedText": set(),
        "declaredDigest": set(),
    }
    operation_count = 0
    for surface, targets_by_length in targets.items():
        for length, expected_fingerprints in targets_by_length.items():
            if not expected_fingerprints:
                continue
            seen_windows: set[str] = set()
            candidate_surface = (
                "derivedText"
                if surface == "declaredDigest"
                else surface
            )
            for candidate in candidates[candidate_surface]:
                if len(candidate) < length:
                    continue
                operation_count += len(candidate) - length + 1
                if operation_count > _MAX_CONTAINMENT_WINDOW_OPERATIONS:
                    raise ValueError(
                        "Holdout containment scan exceeds the resource budget"
                    )
                for offset in range(len(candidate) - length + 1):
                    window = candidate[offset : offset + length]
                    if window in seen_windows:
                        continue
                    seen_windows.add(window)
                    fingerprint = _fingerprint(
                        fingerprint_key,
                        (
                            "normalized-text"
                            if surface == "normalizedText"
                            else (
                                "declared-digest"
                                if surface == "declaredDigest"
                                else "derived-text"
                            )
                        ),
                        window,
                    )
                    if fingerprint in expected_fingerprints:
                        matches[surface].add(fingerprint)
    return matches


def _find_typed_token_overlaps(
    inputs: Mapping[str, Mapping[str, Any]],
    entries: Sequence[Mapping[str, Any]],
    fingerprint_key: bytes,
) -> set[str]:
    expected: dict[str, set[str]] = {
        domain: set() for domain in _TYPED_TOKEN_DOMAINS
    }
    for entry in entries:
        for token in entry["typedTokenFingerprints"]:
            expected[token["domain"]].add(token["fingerprint"])
    if not any(expected.values()):
        return set()

    matches: set[str] = set()

    def visit(value: Any) -> None:
        if isinstance(value, str):
            for domain, token_value in _extract_typed_tokens(value):
                fingerprint = _fingerprint(
                    fingerprint_key,
                    _TYPED_TOKEN_DOMAINS[domain],
                    token_value,
                )
                if fingerprint in expected[domain]:
                    matches.add(fingerprint)
            stripped = value.strip()
            if stripped.startswith(("{", "[")):
                try:
                    structured = json.loads(stripped)
                except json.JSONDecodeError:
                    structured = None
                if isinstance(structured, (Mapping, list)):
                    _validate_json_budget(
                        structured,
                        "embedded structured content",
                    )
                    visit(structured)
            return
        if isinstance(value, int) and not isinstance(value, bool):
            fingerprint = _fingerprint(
                fingerprint_key,
                _TYPED_TOKEN_DOMAINS["integerToken"],
                str(value),
            )
            if fingerprint in expected["integerToken"]:
                matches.add(fingerprint)
            return
        if isinstance(value, float):
            token_value = _canonical_numeric_token(
                _canonical_json(value),
                force_decimal=True,
            )
            fingerprint = _fingerprint(
                fingerprint_key,
                _TYPED_TOKEN_DOMAINS["decimalToken"],
                token_value,
            )
            if fingerprint in expected["decimalToken"]:
                matches.add(fingerprint)
            return
        if isinstance(value, Mapping):
            for key in sorted(value, key=str):
                visit(str(key))
                visit(value[key])
            return
        if isinstance(value, (list, tuple)):
            for child in value:
                visit(child)

    for input_name in _CONTENT_INPUT_NAMES:
        visit(inputs[input_name])
    return matches


def _find_ordered_fragment_overlaps(
    inputs: Mapping[str, Mapping[str, Any]],
    entries: Sequence[Mapping[str, Any]],
    fingerprint_key: bytes,
) -> set[str]:
    groups = [
        group
        for entry in entries
        for group in entry["orderedFragmentGroups"]
    ]
    if not groups:
        return set()
    candidates = _collect_ordered_scalar_candidates(inputs)
    target_lengths = sorted(
        {
            fragment["length"]
            for group in groups
            for fragment in group["fragments"]
        }
    )
    target_fingerprints = {
        fragment["fingerprint"]
        for group in groups
        for fragment in group["fragments"]
    }
    positions: dict[str, list[tuple[int, int, int]]] = {
        fingerprint: [] for fingerprint in target_fingerprints
    }
    operation_count = 0
    for value_index, candidate in enumerate(candidates):
        for length in target_lengths:
            if len(candidate) < length:
                continue
            operation_count += len(candidate) - length + 1
            if operation_count > _MAX_ORDERED_FRAGMENT_WINDOW_OPERATIONS:
                raise ValueError(
                    "Ordered holdout scan exceeds the resource budget"
                )
            for offset in range(len(candidate) - length + 1):
                fingerprint = _fingerprint(
                    fingerprint_key,
                    "ordered-derived-fragment",
                    candidate[offset : offset + length],
                )
                if fingerprint in positions:
                    positions[fingerprint].append(
                        (value_index, offset, offset + length)
                    )

    matches: set[str] = set()
    for group in groups:
        if _ordered_fragment_group_matches(group, positions):
            matches.add(
                canonical_fingerprint(
                    "formal-holdout-ordered-fragment-match-v1",
                    group,
                )
            )
    return matches


def _ordered_fragment_group_matches(
    group: Mapping[str, Any],
    positions: Mapping[str, Sequence[tuple[int, int, int]]],
) -> bool:
    fragments = group["fragments"]
    first_positions = positions.get(fragments[0]["fingerprint"], ())
    for first in first_positions:
        previous = first
        skipped_values = 0
        matched = True
        for fragment in fragments[1:]:
            next_position = None
            next_skipped = 0
            for candidate in positions.get(fragment["fingerprint"], ()):
                if candidate[0] < previous[0]:
                    continue
                if (
                    candidate[0] == previous[0]
                    and candidate[1] < previous[2]
                ):
                    continue
                gap = (
                    0
                    if candidate[0] == previous[0]
                    else candidate[0] - previous[0] - 1
                )
                if (
                    skipped_values + gap
                    > group["maxSkippedValues"]
                ):
                    continue
                next_position = candidate
                next_skipped = gap
                break
            if next_position is None:
                matched = False
                break
            previous = next_position
            skipped_values += next_skipped
        if matched:
            return True
    return False


def _collect_ordered_scalar_candidates(
    inputs: Mapping[str, Mapping[str, Any]],
) -> list[str]:
    candidates: list[str] = []

    def collect(value: Any) -> None:
        if isinstance(value, str):
            derived = _derive_text(value)
            if derived:
                candidates.append(derived)
            return
        if isinstance(value, (int, float)) and not isinstance(value, bool):
            collect(_canonical_json(value))
            return
        if isinstance(value, Mapping):
            for key in sorted(value, key=str):
                collect(value[key])
            return
        if isinstance(value, (list, tuple)):
            for child in value:
                collect(child)

    for input_name in _CONTENT_INPUT_NAMES:
        collect(inputs[input_name])
    return candidates


def _collect_content_text_candidates(
    inputs: Mapping[str, Mapping[str, Any]],
) -> dict[str, set[str]]:
    candidates: dict[str, set[str]] = {
        "normalizedText": set(),
        "derivedText": set(),
    }

    def collect(value: Any) -> tuple[list[str], list[str]]:
        if isinstance(value, str):
            normalized = _normalize_text(value)
            derived = _derive_text(value)
            if normalized:
                candidates["normalizedText"].add(normalized)
            if derived:
                candidates["derivedText"].add(derived)
            return (
                [normalized] if normalized else [],
                [derived] if derived else [],
            )
        if isinstance(value, (int, float)) and not isinstance(value, bool):
            return collect(_canonical_json(value))
        if isinstance(value, Mapping):
            normalized_leaves: list[str] = []
            derived_leaves: list[str] = []
            normalized_value_leaves: list[str] = []
            derived_value_leaves: list[str] = []
            for key, child in value.items():
                key_normalized, key_derived = collect(str(key))
                child_normalized, child_derived = collect(child)
                normalized_leaves.extend(key_normalized)
                normalized_leaves.extend(child_normalized)
                derived_leaves.extend(key_derived)
                derived_leaves.extend(child_derived)
                normalized_value_leaves.extend(child_normalized)
                derived_value_leaves.extend(child_derived)
            _register_joined_candidates(
                candidates,
                normalized_leaves,
                derived_leaves,
            )
            _register_joined_candidates(
                candidates,
                normalized_value_leaves,
                derived_value_leaves,
            )
            return normalized_leaves, derived_leaves
        if isinstance(value, (list, tuple)):
            normalized_leaves = []
            derived_leaves = []
            for child in value:
                child_normalized, child_derived = collect(child)
                normalized_leaves.extend(child_normalized)
                derived_leaves.extend(child_derived)
            _register_joined_candidates(
                candidates,
                normalized_leaves,
                derived_leaves,
            )
            return normalized_leaves, derived_leaves
        return [], []

    all_normalized: list[str] = []
    all_derived: list[str] = []
    all_value_derived: list[str] = []
    for input_name, input_value in inputs.items():
        name_normalized, name_derived = collect(input_name)
        value_normalized, value_derived = collect(input_value)
        all_normalized.extend(name_normalized)
        all_normalized.extend(value_normalized)
        all_derived.extend(name_derived)
        all_derived.extend(value_derived)
        _collect_derived_scalar_values(input_value, all_value_derived)
    _register_joined_candidates(candidates, all_normalized, all_derived)
    if all_value_derived:
        candidates["derivedText"].add("".join(all_value_derived))
    return candidates


def _collect_derived_scalar_values(
    value: Any,
    output: list[str],
) -> None:
    if isinstance(value, str):
        derived = _derive_text(value)
        if derived:
            output.append(derived)
        return
    if isinstance(value, (int, float)) and not isinstance(value, bool):
        _collect_derived_scalar_values(_canonical_json(value), output)
        return
    if isinstance(value, Mapping):
        for child in value.values():
            _collect_derived_scalar_values(child, output)
        return
    if isinstance(value, (list, tuple)):
        for child in value:
            _collect_derived_scalar_values(child, output)


def _register_joined_candidates(
    candidates: dict[str, set[str]],
    normalized_values: Sequence[str],
    derived_values: Sequence[str],
) -> None:
    if 1 < len(normalized_values) <= _MAX_JOINED_CANDIDATE_PARTS:
        compact = "".join(normalized_values)
        spaced = " ".join(normalized_values)
        if len(compact) <= _MAX_JOINED_CANDIDATE_LENGTH:
            candidates["normalizedText"].add(compact)
        if len(spaced) <= _MAX_JOINED_CANDIDATE_LENGTH:
            candidates["normalizedText"].add(spaced)
    if 1 < len(derived_values) <= _MAX_JOINED_CANDIDATE_PARTS:
        compact = "".join(derived_values)
        if len(compact) <= _MAX_JOINED_CANDIDATE_LENGTH:
            candidates["derivedText"].add(compact)


def _holdout_surface_sets(
    entries: Sequence[Mapping[str, Any]],
) -> dict[str, set[str]]:
    surfaces = {name: set() for name in _SURFACE_NAMES}
    for entry in entries:
        surfaces["rawText"].add(entry["acceptanceIdFingerprint"])
        surfaces["normalizedText"].add(
            entry["acceptanceIdNormalizedFingerprint"]
        )
        surfaces["derivedText"].add(entry["acceptanceIdDerivedFingerprint"])
        for surface, field in (
            ("declaredDigest", "imageDigestFingerprint"),
            ("rawText", "promptFingerprint"),
            ("normalizedText", "normalizedPromptFingerprint"),
            ("derivedText", "derivedPromptFingerprint"),
            ("structuredValue", "annotationFingerprint"),
        ):
            value = entry[field]
            if value is not None:
                surfaces[surface].add(value)
        surfaces["derivedText"].update(entry["derivedAliasFingerprints"])
    return surfaces


def _surface_summary(name: str, values: set[str]) -> dict[str, Any]:
    return {
        "itemCount": len(values),
        "setFingerprint": canonical_fingerprint(
            f"formal-content-input-{name}-set-v1",
            sorted(values),
        ),
    }


def _require_fingerprint_contract(manifest: Mapping[str, Any]) -> None:
    if manifest.get("fingerprintAlgorithm") != FINGERPRINT_ALGORITHM:
        raise ValueError("Unsupported holdout fingerprint algorithm")
    if manifest.get("fingerprintPolicyVersion") != FINGERPRINT_POLICY_VERSION:
        raise ValueError("Unsupported holdout fingerprint policy")
    _require_id(manifest["fingerprintKeyId"], "holdout fingerprint-key id")
    _require_sha256(
        manifest["fingerprintKeyCommitment"],
        "holdout fingerprint-key commitment",
    )
    if "inputArtifactFingerprints" in manifest:
        fingerprints = _require_mapping(
            manifest["inputArtifactFingerprints"],
            "content input artifact fingerprints",
        )
        if set(fingerprints) != set(_CONTENT_INPUT_NAMES):
            raise ValueError("Content manifest does not bind all compiler inputs")
        for value in fingerprints.values():
            _require_sha256(value, "content input artifact fingerprint")
        summaries = _require_mapping(
            manifest["surfaceSummaries"],
            "content surface summaries",
        )
        if set(summaries) != set(_SURFACE_NAMES):
            raise ValueError("Content manifest lacks a fingerprint surface")
        for name, value in summaries.items():
            summary = _require_mapping(value, f"{name} summary")
            _require_exact_keys(summary, _SURFACE_SUMMARY_KEYS, f"{name} summary")
            _require_nonnegative_int(summary["itemCount"], f"{name} count")
            _require_sha256(summary["setFingerprint"], f"{name} fingerprint")


def _sign_payload(
    private_key_pem: bytes,
    password: bytes | None,
    payload: bytes,
) -> str:
    try:
        from cryptography.hazmat.primitives import hashes, serialization
        from cryptography.hazmat.primitives.asymmetric import padding, rsa

        private_key = serialization.load_pem_private_key(
            private_key_pem,
            password=password,
        )
        if (
            not isinstance(private_key, rsa.RSAPrivateKey)
            or private_key.key_size < 2_048
        ):
            raise ValueError("Holdout signing key must be RSA-2048 or stronger")
        return private_key.sign(
            payload,
            padding.PKCS1v15(),
            hashes.SHA256(),
        ).hex()
    except ValueError:
        raise
    except Exception as error:
        raise ValueError("Unable to sign private holdout manifest") from error


def _verify_signature(
    x509_public_key_hex: str,
    payload: bytes,
    signature_hex: str,
) -> None:
    try:
        from cryptography.hazmat.primitives import hashes, serialization
        from cryptography.hazmat.primitives.asymmetric import padding, rsa

        public_key = serialization.load_der_public_key(
            bytes.fromhex(x509_public_key_hex)
        )
        if (
            not isinstance(public_key, rsa.RSAPublicKey)
            or public_key.key_size < 2_048
        ):
            raise ValueError("Trusted holdout key must be RSA-2048 or stronger")
        public_key.verify(
            bytes.fromhex(signature_hex),
            payload,
            padding.PKCS1v15(),
            hashes.SHA256(),
        )
    except Exception as error:
        raise ValueError("Holdout manifest signature is invalid") from error


def _fingerprint(key: bytes, domain: str, value: str) -> str:
    return hmac.new(
        _require_fingerprint_key(key),
        f"{FINGERPRINT_POLICY_VERSION}\0{domain}\0{value}".encode("utf-8"),
        hashlib.sha256,
    ).hexdigest()


def _build_containment_signals(
    fingerprint_key: bytes,
    values: Iterable[tuple[str, str]],
) -> list[dict[str, Any]]:
    domains = {
        "normalizedText": "normalized-text",
        "derivedText": "derived-text",
        "declaredDigest": "declared-digest",
    }
    signals = {
        (
            surface,
            len(value),
            _fingerprint(fingerprint_key, domains[surface], value),
        )
        for surface, value in values
        if surface in domains and value
    }
    return [
        {
            "surface": surface,
            "length": length,
            "fingerprint": fingerprint,
        }
        for surface, length, fingerprint in sorted(signals)
    ]


def _build_ordered_fragment_groups(
    fingerprint_key: bytes,
    values: Iterable[str],
) -> list[dict[str, Any]]:
    groups: list[dict[str, Any]] = []
    for value in values:
        fragments = _select_ordered_fragments(value)
        if not fragments:
            continue
        groups.append(
            {
                "surface": "derivedText",
                "maxSkippedValues": _MAX_ORDERED_FRAGMENT_SKIPPED_VALUES,
                "fragments": [
                    {
                        "length": len(fragment),
                        "fingerprint": _fingerprint(
                            fingerprint_key,
                            "ordered-derived-fragment",
                            fragment,
                        ),
                    }
                    for fragment in fragments
                ],
            }
        )
        if len(groups) > _MAX_ORDERED_FRAGMENT_GROUPS_PER_ENTRY:
            raise ValueError(
                "Holdout ordered-fragment evidence exceeds the resource budget"
            )
    unique = {
        _canonical_json(group): group
        for group in groups
    }
    return sorted(unique.values(), key=_ordered_fragment_group_sort_key)


def _select_ordered_fragments(value: str) -> list[str]:
    words = [
        _derive_text(match.group(0))
        for match in _WORD_FRAGMENT.finditer(_normalize_text(value))
    ]
    eligible = [
        word for word in words if len(word) >= _MIN_ORDERED_FRAGMENT_LENGTH
    ]
    if len(eligible) >= 3:
        return _evenly_sample(
            eligible,
            _MAX_ORDERED_FRAGMENTS_PER_GROUP,
        )
    derived = _derive_text(value)
    minimum_total = _MIN_ORDERED_FRAGMENT_LENGTH * 3
    if len(derived) < minimum_total:
        return []
    boundaries = (
        0,
        len(derived) // 3,
        (2 * len(derived)) // 3,
        len(derived),
    )
    fragments = [
        derived[boundaries[index] : boundaries[index + 1]]
        for index in range(3)
    ]
    if any(
        len(fragment) < _MIN_ORDERED_FRAGMENT_LENGTH
        for fragment in fragments
    ):
        return []
    return fragments


def _evenly_sample(values: Sequence[str], maximum: int) -> list[str]:
    if len(values) <= maximum:
        return list(values)
    indexes = [
        (index * (len(values) - 1)) // (maximum - 1)
        for index in range(maximum)
    ]
    return [values[index] for index in indexes]


def _ordered_fragment_group_sort_key(
    group: Mapping[str, Any],
) -> tuple[Any, ...]:
    return (
        group["surface"],
        group["maxSkippedValues"],
        tuple(
            (fragment["length"], fragment["fingerprint"])
            for fragment in group["fragments"]
        ),
    )


def _exclusive_typed_token(value: str) -> tuple[str, str] | None:
    normalized = unicodedata.normalize("NFKC", value).strip()
    if _NUMERIC_TOKEN_FULL.fullmatch(normalized):
        force_decimal = "." in normalized or "e" in normalized.casefold()
        return (
            "decimalToken" if force_decimal else "integerToken",
            _canonical_numeric_token(
                normalized,
                force_decimal=force_decimal,
            ),
        )
    if normalized in _SYMBOL_TOKEN_SET:
        return "symbolToken", normalized
    return None


def _extract_typed_tokens(value: str) -> list[tuple[str, str]]:
    normalized = unicodedata.normalize("NFKC", value)
    numeric_matches = list(_NUMERIC_TOKEN.finditer(normalized))
    tokens: list[tuple[str, str]] = []
    for match in numeric_matches:
        raw = match.group("value")
        force_decimal = "." in raw or "e" in raw.casefold()
        tokens.append(
            (
                "decimalToken" if force_decimal else "integerToken",
                _canonical_numeric_token(
                    raw,
                    force_decimal=force_decimal,
                ),
            )
        )
    numeric_spans = [match.span("value") for match in numeric_matches]
    for match in _SYMBOL_TOKEN_PATTERN.finditer(normalized):
        if any(
            start < match.end() and match.start() < end
            for start, end in numeric_spans
        ):
            continue
        tokens.append(("symbolToken", match.group(0)))
    return tokens


def _canonical_numeric_token(
    value: str,
    *,
    force_decimal: bool,
) -> str:
    try:
        number = Decimal(value)
    except InvalidOperation as error:
        raise ValueError("Numeric holdout token is invalid") from error
    if not number.is_finite():
        raise ValueError("Numeric holdout token must be finite")
    if number == 0:
        number = abs(number)
    if not force_decimal:
        return str(int(number))
    normalized = format(number.normalize(), "f")
    if "." not in normalized:
        normalized += ".0"
    return normalized


def _fingerprint_key_commitment(key: bytes) -> str:
    return hashlib.sha256(
        b"formal-holdout-fingerprint-key-v1\0"
        + _require_fingerprint_key(key)
    ).hexdigest()


def _normalize_text(value: str) -> str:
    normalized = unicodedata.normalize("NFKC", value).casefold()
    return " ".join(normalized.split())


def _derive_text(value: str) -> str:
    normalized = _normalize_text(value)
    numeric_matches = list(_NUMERIC_TOKEN.finditer(normalized))
    occupied = [match.span("value") for match in numeric_matches]
    events: list[tuple[int, int, str]] = [
        (
            match.start("value"),
            match.end("value"),
            _typed_token_marker(
                (
                    "decimalToken"
                    if "." in match.group("value")
                    or "e" in match.group("value").casefold()
                    else "integerToken"
                ),
                _canonical_numeric_token(
                    match.group("value"),
                    force_decimal=(
                        "." in match.group("value")
                        or "e" in match.group("value").casefold()
                    ),
                ),
            ),
        )
        for match in numeric_matches
    ]
    for match in _DERIVED_SYMBOL_TOKEN_PATTERN.finditer(normalized):
        if any(
            start < match.end() and match.start() < end
            for start, end in occupied
        ):
            continue
        events.append(
            (
                match.start(),
                match.end(),
                _typed_token_marker("symbolToken", match.group(0)),
            )
        )
    events.sort(key=lambda event: (event[0], -(event[1] - event[0])))
    output: list[str] = []
    cursor = 0
    for start, end, marker in events:
        if start < cursor:
            continue
        output.extend(
            character
            for character in normalized[cursor:start]
            if character.isalnum()
        )
        output.append(marker)
        cursor = end
    output.extend(
        character
        for character in normalized[cursor:]
        if character.isalnum()
    )
    return "".join(output)


def _typed_token_marker(domain: str, value: str) -> str:
    return f"\u241f{domain}:{value}\u241f"


def _canonical_json(value: Any) -> str:
    return json.dumps(
        value,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    )


def _preflight_build_inputs(
    inputs: Mapping[str, Mapping[str, Any]],
) -> None:
    root = _require_mapping(inputs, "compiler inputs")
    _validate_json_budget(root, "compiler inputs")
    if set(root) != set(_CONTENT_INPUT_NAMES):
        raise ValueError("Content-input manifest must bind all compiler inputs")
    for name in _CONTENT_INPUT_NAMES:
        _require_mapping(root[name], name)


def validate_build_inputs_before_processing(
    inputs: Mapping[str, Mapping[str, Any]],
) -> None:
    """Validate the bounded five-input envelope before HMAC or copying."""

    _preflight_build_inputs(inputs)


def _preflight_content_input_manifest(
    manifest: Mapping[str, Any],
) -> None:
    value = _require_mapping(manifest, "content-input manifest")
    _validate_json_budget(value, "content-input manifest")
    _require_exact_keys(
        value,
        _CONTENT_MANIFEST_KEYS,
        "content-input manifest",
    )
    _require_mapping(
        value.get("inputArtifactFingerprints"),
        "content input artifact fingerprints",
    )
    _require_mapping(
        value.get("surfaceSummaries"),
        "content surface summaries",
    )


def validate_content_input_manifest_before_processing(
    manifest: Mapping[str, Any],
) -> None:
    """Validate every manifest field without computing keyed fingerprints."""

    _preflight_content_input_manifest(manifest)
    if manifest.get("schemaVersion") != CONTENT_INPUT_MANIFEST_SCHEMA_VERSION:
        raise ValueError("Unsupported content-input manifest schema")
    _require_id(manifest["manifestId"], "content-input manifest id")
    _require_id(manifest["manifestVersion"], "content-input manifest version")
    _require_nonnegative_int(
        manifest["createdAtEpochMillis"],
        "content-input manifest time",
    )
    _require_fingerprint_contract(manifest)


def validate_full_holdout_manifest_before_processing(
    manifest: Mapping[str, Any],
    *,
    require_nonempty: bool,
    allow_unsigned_signature: bool = False,
) -> list[dict[str, Any]]:
    """Validate the entire bounded manifest before costly or mutating work."""

    value = _require_mapping(manifest, "holdout manifest")
    _validate_json_budget(value, "holdout manifest")
    _require_exact_keys(value, _HOLDOUT_MANIFEST_KEYS, "holdout manifest")
    _require_full_holdout_nested_schema(value.get("entries"))
    signature = _require_mapping(value.get("signature"), "holdout signature")
    _require_exact_keys(
        signature,
        _HOLDOUT_SIGNATURE_KEYS,
        "holdout signature",
    )
    if value.get("schemaVersion") != HOLDOUT_MANIFEST_SCHEMA_VERSION:
        raise ValueError("Unsupported holdout manifest schema")
    _require_id(value["manifestId"], "holdout manifest id")
    _require_id(value["manifestVersion"], "holdout manifest version")
    if value.get("purpose") != HOLDOUT_PURPOSE:
        raise ValueError("Holdout manifest has an invalid purpose")
    created_at = _require_nonnegative_int(
        value["createdAtEpochMillis"],
        "holdout manifest time",
    )
    _require_fingerprint_contract(value)
    _require_positive_int(
        value["releaseGeneration"],
        "holdout release generation",
    )
    _require_id(signature["keyId"], "holdout signing-key id")
    if signature.get("algorithm") != SIGNATURE_ALGORITHM:
        raise ValueError("Unsupported holdout signature algorithm")
    signed_at = _require_nonnegative_int(
        signature["signedAtEpochMillis"],
        "holdout signature time",
    )
    if signed_at < created_at:
        raise ValueError("Holdout signature predates its manifest")
    signature_hex = signature.get("signatureHex")
    if signature_hex == "" and allow_unsigned_signature:
        pass
    elif (
        not isinstance(signature_hex, str)
        or not signature_hex
        or len(signature_hex) % 2
        or not _HEX.fullmatch(signature_hex)
    ):
        raise ValueError("Holdout signature is invalid")
    entries = _validate_holdout_entries(
        value["entries"],
        require_nonempty=require_nonempty,
    )
    if value.get("entryCount") != len(entries):
        raise ValueError("Holdout manifest entry count is invalid")
    expected_entry_fingerprint = canonical_fingerprint(
        "formal-holdout-entry-set-v2",
        entries,
    )
    if value.get("entrySetFingerprint") != expected_entry_fingerprint:
        raise ValueError("Holdout manifest entry-set fingerprint is invalid")
    return entries


def _require_full_holdout_nested_schema(entries: Any) -> None:
    if not isinstance(entries, Sequence) or isinstance(entries, (str, bytes)):
        raise ValueError("Holdout entries must be an array")
    for raw_entry in entries:
        entry = _require_mapping(raw_entry, "holdout entry")
        _require_exact_keys(entry, _HOLDOUT_ENTRY_KEYS, "holdout entry")
        aliases = entry.get("derivedAliasFingerprints")
        if not isinstance(aliases, list):
            raise ValueError("Derived holdout fingerprints must be an array")
        typed_tokens = entry.get("typedTokenFingerprints")
        if not isinstance(typed_tokens, list):
            raise ValueError("Typed holdout fingerprints must be an array")
        for raw_token in typed_tokens:
            token = _require_mapping(
                raw_token,
                "typed holdout fingerprint",
            )
            _require_exact_keys(
                token,
                _TYPED_TOKEN_SIGNAL_KEYS,
                "typed holdout fingerprint",
            )
        signals = entry.get("containmentSignals")
        if not isinstance(signals, list):
            raise ValueError("Holdout containment signals must be an array")
        for raw_signal in signals:
            signal = _require_mapping(
                raw_signal,
                "holdout containment signal",
            )
            _require_exact_keys(
                signal,
                _CONTAINMENT_SIGNAL_KEYS,
                "holdout containment signal",
            )
        groups = entry.get("orderedFragmentGroups")
        if not isinstance(groups, list):
            raise ValueError("Ordered holdout fragment groups must be an array")
        for raw_group in groups:
            group = _require_mapping(
                raw_group,
                "ordered holdout fragment group",
            )
            _require_exact_keys(
                group,
                _ORDERED_FRAGMENT_GROUP_KEYS,
                "ordered holdout fragment group",
            )
            fragments = group.get("fragments")
            if not isinstance(fragments, list):
                raise ValueError("Ordered holdout fragments must be an array")
            for raw_fragment in fragments:
                fragment = _require_mapping(
                    raw_fragment,
                    "ordered holdout fragment",
                )
                _require_exact_keys(
                    fragment,
                    _ORDERED_FRAGMENT_KEYS,
                    "ordered holdout fragment",
                )


def _preflight_holdout_release_registration(
    registration: Mapping[str, Any],
) -> None:
    value = _require_mapping(
        registration,
        "holdout release registration",
    )
    _validate_json_budget(value, "holdout release registration")
    _require_exact_keys(
        value,
        _HOLDOUT_RELEASE_REGISTRATION_KEYS,
        "holdout release registration",
    )
    _require_mapping(
        value.get("signingKey"),
        "registered holdout signing key",
    )


def validate_holdout_release_registration_before_processing(
    registration: Mapping[str, Any],
) -> None:
    """Validate the complete registration before signature-key processing."""

    _validate_release_registration_schema(registration)


def _validate_json_budget(value: Any, label: str) -> None:
    node_count = 0
    string_count = 0
    utf8_bytes = 0
    container_items = 0
    active_containers: set[int] = set()
    stack: list[tuple[Any, int, bool]] = [(value, 1, False)]

    while stack:
        current, depth, exiting = stack.pop()
        if exiting:
            active_containers.remove(id(current))
            continue
        if depth > MAX_JSON_DEPTH:
            raise ValueError(f"{label} exceeds the maximum JSON depth")
        node_count += 1
        if node_count > MAX_JSON_NODES:
            raise ValueError(f"{label} exceeds the JSON node budget")

        if isinstance(current, str):
            encoded_length = len(current.encode("utf-8"))
            string_count += 1
            utf8_bytes += encoded_length
            if encoded_length > MAX_JSON_STRING_BYTES:
                raise ValueError(
                    f"{label} contains a string over the byte budget"
                )
        elif current is None or isinstance(current, bool):
            utf8_bytes += 5
        elif isinstance(current, int):
            utf8_bytes += len(str(current))
        elif isinstance(current, float):
            if not math.isfinite(current):
                raise ValueError(f"{label} contains a non-finite number")
            utf8_bytes += len(repr(current))
        elif isinstance(current, Mapping):
            identity = id(current)
            if identity in active_containers:
                raise ValueError(f"{label} contains a cyclic JSON value")
            active_containers.add(identity)
            stack.append((current, depth, True))
            container_items += len(current)
            utf8_bytes += max(2, len(current) + 1)
            if container_items > MAX_JSON_CONTAINER_ITEMS:
                raise ValueError(
                    f"{label} exceeds the JSON container budget"
                )
            for key, child in reversed(list(current.items())):
                if not isinstance(key, str):
                    raise ValueError(f"{label} contains a non-string object key")
                stack.append((child, depth + 1, False))
                stack.append((key, depth + 1, False))
        elif isinstance(current, (list, tuple)):
            identity = id(current)
            if identity in active_containers:
                raise ValueError(f"{label} contains a cyclic JSON value")
            active_containers.add(identity)
            stack.append((current, depth, True))
            container_items += len(current)
            utf8_bytes += max(2, len(current) + 1)
            if container_items > MAX_JSON_CONTAINER_ITEMS:
                raise ValueError(
                    f"{label} exceeds the JSON container budget"
                )
            for child in reversed(current):
                stack.append((child, depth + 1, False))
        else:
            raise ValueError(f"{label} contains a non-JSON value")

        if string_count > MAX_JSON_STRINGS:
            raise ValueError(f"{label} exceeds the JSON string budget")
        if utf8_bytes > MAX_JSON_UTF8_BYTES:
            raise ValueError(f"{label} exceeds the JSON byte budget")
        if container_items > MAX_JSON_CONTAINER_ITEMS:
            raise ValueError(f"{label} exceeds the JSON container budget")


def validate_json_value_before_processing(value: Any, label: str) -> None:
    """Expose the common bounded-JSON walk without hashing or copying."""

    _validate_json_budget(value, label)


def _require_fingerprint_key(value: bytes) -> bytes:
    if not isinstance(value, bytes) or len(value) < 32:
        raise ValueError("Holdout fingerprint key must contain at least 32 bytes")
    return value


def _require_mapping(value: Any, label: str) -> Mapping[str, Any]:
    if not isinstance(value, Mapping):
        raise ValueError(f"{label} must be an object")
    return value


def _require_exact_keys(
    value: Mapping[str, Any],
    expected: set[str],
    label: str,
) -> None:
    if set(value) != expected:
        raise ValueError(f"{label} has an unexpected schema")


def _require_id(value: Any, label: str) -> str:
    if not isinstance(value, str) or not _ID.fullmatch(value):
        raise ValueError(f"{label} is invalid")
    return value


def _require_text(value: Any, label: str) -> str:
    if (
        not isinstance(value, str)
        or not value
        or len(value) > 65_536
        or any(
            ord(character) < 32 and character not in "\n\t"
            for character in value
        )
    ):
        raise ValueError(f"{label} is invalid")
    return value


def _require_nonnegative_int(value: Any, label: str) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value < 0:
        raise ValueError(f"{label} must be a nonnegative integer")
    return value


def _require_positive_int(value: Any, label: str) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value <= 0:
        raise ValueError(f"{label} must be a positive integer")
    return value


def _require_sha256(value: Any, label: str) -> str:
    if not isinstance(value, str) or not _SHA256.fullmatch(value):
        raise ValueError(f"{label} must be lowercase SHA-256")
    return value
