"""Offline signing and deterministic Android release-registry generation.

The module accepts only a compiler-emitted formal artifact plus a separately
reviewed governance manifest. It never creates curriculum content, review
evidence, source permissions, or private keys.
"""

from __future__ import annotations

import hashlib
import json
import re
from dataclasses import dataclass
from typing import Any, Iterable, Mapping, Sequence

from .input_hashes import canonical_fingerprint
from .runtime_contract import (
    KOTLIN_FORMAL_SUBJECT_ORDER,
    coverage_proof_fingerprint,
    formal_canonical_text,
    formal_sha256,
    validate_formal_artifact_v2,
)
from .schema import REQUIRED_SUBJECTS


RELEASE_GOVERNANCE_SCHEMA_VERSION = 1
RELEASE_RECEIPT_SCHEMA_VERSION = 1
RELEASE_SIGNING_REGISTRATION_SCHEMA_VERSION = 1
SIGNATURE_ALGORITHM = "SHA256_WITH_RSA_2048"
CONTENT_POLICY_ATTESTATION = (
    "ORIGINAL_OR_REVIEWED_SYNTHESIS_WITHOUT_QUESTION_BANK"
)
PRIVATE_KEY_PATH_ENV = "SMART_MISTAKEBOOK_KNOWLEDGE_SIGNING_KEY_PATH"
PRIVATE_KEY_PASSWORD_ENV = "SMART_MISTAKEBOOK_KNOWLEDGE_SIGNING_KEY_PASSWORD"
RELEASE_SIGNING_PURPOSE = "FORMAL_KNOWLEDGE_PACK_RELEASE_SIGNING"
PINNED_RELEASE_SIGNING_REGISTRATION: tuple[str, int] | None = None

_SHA256 = re.compile(r"^[0-9a-f]{64}$")
_HEX = re.compile(r"^[0-9a-f]+$")
_STANDARD_CODE = re.compile(r"^SB\d{4}$")
_ID = re.compile(r"^[^\x00-\x1f\x7f]{1,160}$")
_ALLOWED_LICENSE_CLASSES = {
    "PERMISSIVE",
    "PUBLIC_OFFICIAL_METADATA",
    "NON_COMMERCIAL",
    "NO_DERIVATIVES",
    "REFERENCE_ONLY",
}
_EXPECTED_LICENSE_STATUS = {
    "PERMISSIVE": "LICENSED",
    "PUBLIC_OFFICIAL_METADATA": "PUBLIC_OFFICIAL",
    "NON_COMMERCIAL": "LICENSED",
    "NO_DERIVATIVES": "LICENSED",
    "REFERENCE_ONLY": "REFERENCE_ONLY",
}
_ALLOWED_AUTOMATION = {"ALLOWED", "METADATA_ONLY"}
_GOVERNANCE_KEYS = {
    "schemaVersion",
    "candidate",
    "sourceEvidence",
    "subjectMappings",
    "independentReview",
    "signing",
}
_CANDIDATE_KEYS = {
    "candidateId",
    "producerOrganizationId",
    "preparedAtEpochMillis",
    "activationGeneration",
}
_SOURCE_EVIDENCE_KEYS = {
    "sourceId",
    "sourceVersion",
    "licenseClass",
    "licenseEvidenceFingerprint",
    "evidenceOrigin",
    "automationPermission",
    "reviewStatus",
    "reviewRecordId",
    "reviewedAtEpochMillis",
    "retrievedAtEpochMillis",
    "editionEvidenceLocator",
    "editionEvidenceFingerprint",
}
_SUBJECT_MAPPING_KEYS = {
    "subject",
    "officialStandardCode",
    "rootNodeStableCode",
    "stableCodeNamespace",
    "curriculumSourceId",
    "textbookEditionSourceIds",
}
_INDEPENDENT_REVIEW_KEYS = {
    "reviewRecordId",
    "reviewerOrganizationId",
    "reviewedAtEpochMillis",
    "decision",
    "contentPolicyAttestation",
    "formalArtifactFingerprint",
    "runtimeContentFingerprint",
    "coverageProofFingerprint",
}
_SIGNING_KEYS = {
    "keyId",
    "algorithm",
    "signedAtEpochMillis",
    "validFromEpochMillis",
    "validUntilEpochMillis",
    "revokedAtEpochMillis",
    "revocationEvidenceFingerprint",
}
_RELEASE_SIGNING_KEY_KEYS = {
    "keyId",
    "algorithm",
    "x509PublicKeyHex",
    "validFromEpochMillis",
    "validUntilEpochMillis",
    "revokedAtEpochMillis",
    "revocationEvidenceFingerprint",
}
_RELEASE_SIGNING_REGISTRATION_KEYS = {
    "schemaVersion",
    "registrationId",
    "purpose",
    "releaseGeneration",
    "keyId",
    "publicKeyFingerprint",
    "signingKey",
    "signingKeyFingerprint",
}


@dataclass(frozen=True)
class GeneratedFormalRelease:
    asset_kotlin: str
    registry_kotlin: str
    receipt: dict[str, Any]


def build_release_signing_registration(
    *,
    registration_id: str,
    release_generation: int,
    signing_key: Mapping[str, Any],
) -> dict[str, Any]:
    """Build public registration content; callers cannot make it trusted."""

    key = _validate_release_signing_key(signing_key)
    registration = {
        "schemaVersion": RELEASE_SIGNING_REGISTRATION_SCHEMA_VERSION,
        "registrationId": _require_id(
            registration_id,
            "release signing registration id",
        ),
        "purpose": RELEASE_SIGNING_PURPOSE,
        "releaseGeneration": _require_positive_int(
            release_generation,
            "release signing generation",
        ),
        "keyId": key["keyId"],
        "publicKeyFingerprint": _public_key_fingerprint(
            key["x509PublicKeyHex"]
        ),
        "signingKey": key,
        "signingKeyFingerprint": canonical_fingerprint(
            "formal-release-signing-key-v1",
            key,
        ),
    }
    return _validate_release_signing_registration(registration)


def release_signing_registration_fingerprint(
    registration: Mapping[str, Any],
) -> str:
    normalized = _validate_release_signing_registration(registration)
    return canonical_fingerprint(
        "formal-release-signing-registration-v1",
        normalized,
    )


def validate_release_signing_registration_before_processing(
    registration: Mapping[str, Any],
) -> dict[str, Any]:
    """Validate all registration values without loading its public key."""

    from .holdout_gate import validate_json_value_before_processing

    validate_json_value_before_processing(
        registration,
        "release signing registration",
    )
    value = _require_mapping(
        registration,
        "release signing registration",
    )
    _require_exact_keys(
        value,
        _RELEASE_SIGNING_REGISTRATION_KEYS,
        "release signing registration",
    )
    if (
        value.get("schemaVersion")
        != RELEASE_SIGNING_REGISTRATION_SCHEMA_VERSION
    ):
        raise ValueError("Unsupported release signing-registration schema")
    if value.get("purpose") != RELEASE_SIGNING_PURPOSE:
        raise ValueError("Release signing registration has an invalid purpose")
    key = _preflight_release_signing_key(
        _require_mapping(
            value["signingKey"],
            "registered release signing key",
        )
    )
    normalized = {
        "schemaVersion": RELEASE_SIGNING_REGISTRATION_SCHEMA_VERSION,
        "registrationId": _require_id(
            value["registrationId"],
            "release signing registration id",
        ),
        "purpose": RELEASE_SIGNING_PURPOSE,
        "releaseGeneration": _require_positive_int(
            value["releaseGeneration"],
            "release signing generation",
        ),
        "keyId": _require_id(
            value["keyId"],
            "registered release signing key id",
        ),
        "publicKeyFingerprint": _require_sha256(
            value["publicKeyFingerprint"],
            "registered release public-key fingerprint",
        ),
        "signingKey": key,
        "signingKeyFingerprint": _require_sha256(
            value["signingKeyFingerprint"],
            "registered release signing-key fingerprint",
        ),
    }
    if normalized["keyId"] != key["keyId"]:
        raise ValueError("Registered release signing key id is inconsistent")
    if normalized["publicKeyFingerprint"] != _public_key_fingerprint(
        key["x509PublicKeyHex"]
    ):
        raise ValueError(
            "Registered release public-key fingerprint is invalid"
        )
    if normalized["signingKeyFingerprint"] != canonical_fingerprint(
        "formal-release-signing-key-v1",
        key,
    ):
        raise ValueError(
            "Registered release signing-key fingerprint is invalid"
        )
    return normalized


def generate_signed_release(
    artifact: Mapping[str, Any],
    governance: Mapping[str, Any],
    private_key_pem: bytes,
    private_key_password: bytes | None = None,
    *,
    holdout_build_inputs: Mapping[str, Mapping[str, Any]] | None = None,
    content_input_manifest: Mapping[str, Any] | None = None,
    holdout_manifest: Mapping[str, Any] | None = None,
    holdout_fingerprint_key: bytes | None = None,
    holdout_release_registration: Mapping[str, Any] | None = None,
    release_signing_registration: Mapping[str, Any] | None = None,
) -> GeneratedFormalRelease:
    """Validate, sign, and render one formal release without exposing a key."""

    if release_signing_registration is None:
        raise ValueError(
            "Release signing requires a pinned signing registration"
        )
    if holdout_build_inputs is None:
        raise ValueError(
            "Release signing requires the original formal build inputs"
        )
    if content_input_manifest is None:
        raise ValueError("Release signing requires a content-input manifest")
    if holdout_manifest is None:
        raise ValueError("Release signing requires a signed holdout manifest")
    if holdout_release_registration is None:
        raise ValueError("Release signing requires a holdout registration")

    from .formal_pack import (
        compile_formal_pack,
        validate_formal_build_inputs_before_processing,
    )
    from .holdout_gate import (
        validate_content_input_manifest_before_processing,
        validate_full_holdout_manifest_before_processing,
        validate_holdout_release_registration_before_processing,
        validate_json_value_before_processing,
    )

    validate_json_value_before_processing(artifact, "formal artifact")
    validate_json_value_before_processing(governance, "release governance")
    validate_formal_build_inputs_before_processing(holdout_build_inputs)
    validate_content_input_manifest_before_processing(content_input_manifest)
    validate_full_holdout_manifest_before_processing(
        holdout_manifest,
        require_nonempty=True,
    )
    validate_holdout_release_registration_before_processing(
        holdout_release_registration
    )
    validate_release_signing_registration_before_processing(
        release_signing_registration
    )

    validate_formal_artifact_v2(artifact)
    registered_signing = _validate_pinned_release_signing_registration(
        release_signing_registration
    )
    recompiled_artifact, _ = compile_formal_pack(
        dict(holdout_build_inputs["coverageLedger"]),
        dict(holdout_build_inputs["sourceRegister"]),
        dict(holdout_build_inputs["teachingInventory"]),
        dict(holdout_build_inputs["teachingDecisions"]),
        dict(holdout_build_inputs["packReviewContent"]),
        content_input_manifest=content_input_manifest,
        holdout_manifest=holdout_manifest,
        holdout_fingerprint_key=holdout_fingerprint_key,
        holdout_release_registration=holdout_release_registration,
    )
    if dict(artifact) != recompiled_artifact:
        raise ValueError(
            "Formal artifact does not match independent release recompilation"
        )
    normalized = _normalize_governance(artifact, governance)
    _require_registered_release_signing(normalized, registered_signing)
    candidate = _build_candidate(artifact, normalized)
    review = _build_independent_review(candidate, normalized)
    signing = normalized["signing"]
    unsigned_activation = _build_unsigned_activation(
        artifact,
        candidate,
        review,
        normalized,
    )
    private_key, public_key_hex = _load_rsa_private_key(
        private_key_pem,
        private_key_password,
    )
    if (
        public_key_hex
        != registered_signing["signingKey"]["x509PublicKeyHex"]
        or _public_key_fingerprint(public_key_hex)
        != registered_signing["publicKeyFingerprint"]
    ):
        raise ValueError(
            "Private signing key does not match the pinned signing registration"
        )
    signature_hex = _sign_payload(
        private_key,
        signed_activation_payload(unsigned_activation),
    )
    signed_activation = {
        **unsigned_activation,
        "signatureHex": signature_hex,
    }
    _verify_signature(
        public_key_hex,
        signed_activation_payload(signed_activation),
        signature_hex,
    )
    signing_key = dict(registered_signing["signingKey"])
    asset_input_fingerprint = _release_render_input_fingerprint(
        "formal-release-asset-render-input-v1",
        (artifact, candidate, review),
    )
    registry_input_fingerprint = _release_render_input_fingerprint(
        "formal-release-registry-render-input-v1",
        (
            signing_key,
            signed_activation,
            normalized["candidate"]["activationGeneration"],
        ),
    )
    registration_fingerprint = release_signing_registration_fingerprint(
        registered_signing
    )

    def require_verified_render_inputs() -> None:
        registration = _validate_pinned_release_signing_registration(
            release_signing_registration
        )
        _require_registered_release_signing(normalized, registration)
        if (
            dict(signing_key) != registration["signingKey"]
            or release_signing_registration_fingerprint(registration)
            != registration_fingerprint
            or signed_activation.get("signingKeyId") != registration["keyId"]
            or signed_activation.get("activationGeneration")
            != registration["releaseGeneration"]
            or _release_render_input_fingerprint(
                "formal-release-asset-render-input-v1",
                (artifact, candidate, review),
            )
            != asset_input_fingerprint
            or _release_render_input_fingerprint(
                "formal-release-registry-render-input-v1",
                (
                    signing_key,
                    signed_activation,
                    normalized["candidate"]["activationGeneration"],
                ),
            )
            != registry_input_fingerprint
        ):
            raise ValueError("Formal release render inputs changed after signing")
        render_signature = signed_activation.get("signatureHex")
        if not isinstance(render_signature, str) or not render_signature:
            raise ValueError("Formal release renderer lacks a signature")
        _verify_signature(
            registration["signingKey"]["x509PublicKeyHex"],
            signed_activation_payload(signed_activation),
            render_signature,
        )

    def render_asset() -> str:
        require_verified_render_inputs()
        builder = _KotlinAssetBuilder()
        builder.line(
            "package com.tingyun.smartmistakebook.core.knowledge.database"
        )
        builder.line()
        for imported in (
            "KnowledgeMaterialDerivationKind",
            "KnowledgeMaterialNodeRole",
            "KnowledgeNodeGranularity",
            "KnowledgeNodeKind",
            "KnowledgeNodeVerificationStatus",
            "KnowledgeSourceContentUsePolicy",
            "KnowledgeSourceLicenseStatus",
            "KnowledgeSourceType",
            "KnowledgeTeachingMaterialType",
            "SubjectKind",
        ):
            builder.line(
                f"import com.tingyun.smartmistakebook.core.model.{imported}"
            )
        builder.line()
        builder.line(
            "// Generated from a reviewed formal artifact. Do not edit by hand."
        )
        builder.line("internal object GeneratedFormalKnowledgePackAsset {")
        builder.indent += 1
        _append_formal_pack_asset_body(
            builder,
            artifact,
            candidate,
            review,
            signing_key=signing_key,
            signed_activation=signed_activation,
            governance=normalized,
            release_signing_registration=release_signing_registration,
        )
        builder.indent -= 1
        builder.line("}")
        result = builder.render()
        require_verified_render_inputs()
        return result

    def render_registry() -> str:
        require_verified_render_inputs()
        builder = _KotlinAssetBuilder()
        builder.line(
            "package com.tingyun.smartmistakebook.core.knowledge.database"
        )
        builder.line()
        builder.line(
            "// Generated by the offline formal-pack signer. Contains no private key."
        )
        builder.line("internal object GeneratedFormalKnowledgePackRegistry {")
        builder.indent += 1
        _append_formal_release_registry_body(
            builder,
            signing_key,
            signed_activation,
            normalized["candidate"]["activationGeneration"],
            artifact=artifact,
            candidate=candidate,
            review=review,
            governance=normalized,
            release_signing_registration=release_signing_registration,
        )
        builder.indent -= 1
        builder.line("}")
        result = builder.render()
        require_verified_render_inputs()
        return result

    asset_source = render_asset()
    registry_source = render_registry()
    receipt = {
            "schemaVersion": RELEASE_RECEIPT_SCHEMA_VERSION,
            "artifactType": "FORMAL_KNOWLEDGE_PACK_SIGNING_RECEIPT",
            "packId": artifact["manifest"]["packId"],
            "knowledgePackVersion": artifact["manifest"][
                "knowledgePackVersion"
            ],
            "formalArtifactFingerprint": artifact["manifest"][
                "contentFingerprint"
            ],
            "runtimeContentFingerprint": artifact["manifest"][
                "runtimeContentFingerprint"
            ],
            "coverageProofFingerprint": artifact["coverageProofV2"][
                "proofFingerprint"
            ],
            "candidateGovernanceFingerprint": candidate[
                "governanceFingerprint"
            ],
            "independentReviewFingerprint": review["reviewFingerprint"],
            "activationGeneration": normalized["candidate"][
                "activationGeneration"
            ],
            "releaseSigningRegistrationId": registered_signing[
                "registrationId"
            ],
            "releaseSigningRegistrationFingerprint": (
                release_signing_registration_fingerprint(registered_signing)
            ),
            "signedAtEpochMillis": signing["signedAtEpochMillis"],
            "signingKey": signing_key,
            "signatureHex": signature_hex,
            "signedPayloadFingerprint": hashlib.sha256(
                signed_activation_payload(signed_activation)
            ).hexdigest(),
            "assetSourceFingerprint": hashlib.sha256(
                asset_source.encode("utf-8")
            ).hexdigest(),
            "registrySourceFingerprint": hashlib.sha256(
                registry_source.encode("utf-8")
            ).hexdigest(),
            "containsPrivateKeyMaterial": False,
            "runtimeTrustRegistryMutationAllowed": False,
    }
    _verify_generated_release_bundle(
        receipt=receipt,
        asset_source=asset_source,
        registry_source=registry_source,
        artifact=artifact,
        candidate=candidate,
        review=review,
        signing_key=signing_key,
        signed_activation=signed_activation,
        governance=normalized,
        release_signing_registration=release_signing_registration,
        asset_input_fingerprint=asset_input_fingerprint,
        registry_input_fingerprint=registry_input_fingerprint,
    )
    return GeneratedFormalRelease(
        asset_kotlin=asset_source,
        registry_kotlin=registry_source,
        receipt=receipt,
    )


def coverage_fingerprint(artifact: Mapping[str, Any]) -> str:
    """Mirror FormalKnowledgePackActivationPolicy.coverageFingerprint."""

    values: list[Any] = []
    for subject in KOTLIN_FORMAL_SUBJECT_ORDER:
        nodes = [row for row in artifact["nodes"] if row["subject"] == subject]
        relations = [
            row for row in artifact["relations"] if row["subject"] == subject
        ]
        materials = [
            row
            for row in artifact["teachingMaterials"]
            if row["subject"] == subject
        ]
        values.extend(
            (
                subject,
                sum(row["kind"] == "TOPIC" for row in nodes),
                sum(row["granularity"] == "ATOMIC" for row in nodes),
                sum(len(row["aliases"]) for row in nodes),
                len(relations),
                sum(
                    row["materialType"] == "METHOD_MODEL"
                    for row in materials
                ),
                sum(
                    row["materialType"] == "WORKED_EXAMPLE"
                    for row in materials
                ),
            )
        )
        for node in sorted(nodes, key=lambda row: row["stableCode"]):
            values.append(node["stableCode"])
            values.extend(sorted(node["aliases"]))
        for relation in sorted(relations, key=lambda row: row["relationId"]):
            values.extend((relation["relationId"], relation["relationType"]))
        for material in sorted(materials, key=lambda row: row["stableCode"]):
            values.extend((material["stableCode"], material["materialType"]))
    return formal_sha256(values)


def canonical_source_record_fingerprint(
    source: Mapping[str, Any],
    source_version: str,
) -> str:
    """Mirror FormalKnowledgePackActivationPolicy source metadata digest."""

    return formal_sha256(
        (
            source["sourceId"],
            source_version,
            source["subject"],
            source["sourceType"],
            source["title"],
            source["publisher"],
            source["edition"],
            source["sourceUri"],
            source["licenseStatus"],
            source["contentUsePolicy"],
            source["contentFingerprint"],
            source["licenseExpression"],
            source["licenseUri"],
            source["attributionText"],
            source["reviewedAtEpochMillis"],
        )
    )


def candidate_governance_fingerprint(candidate: Mapping[str, Any]) -> str:
    """Mirror FormalKnowledgePackCandidate.governanceFingerprint."""

    values: list[Any] = [
        candidate["candidateId"],
        candidate["producerOrganizationId"],
        candidate["preparedAtEpochMillis"],
        candidate["packId"],
        candidate["knowledgePackVersion"],
        candidate["taxonomyVersion"],
        candidate["searchIndexVersion"],
        candidate["contentFingerprint"],
        candidate["coverageFingerprint"],
    ]
    for provenance in sorted(
        candidate["sourceProvenance"],
        key=lambda row: row["admissionEvidence"]["sourceId"],
    ):
        evidence = provenance["admissionEvidence"]
        values.extend(
            (
                evidence["sourceId"],
                evidence["sourceVersion"],
                evidence["sourceRecordFingerprint"],
                evidence["canonicalLocator"],
                evidence["publisher"],
                evidence["resourceTitle"],
                evidence["licenseClass"],
                evidence["licenseExpression"],
                evidence["licenseEvidenceLocator"],
                evidence["licenseEvidenceFingerprint"],
                evidence["evidenceOrigin"],
                evidence["automationPermission"],
                evidence["reviewStatus"],
                evidence["reviewRecordId"],
                evidence["reviewedAtEpochMillis"],
                provenance["sourceContentFingerprint"],
                provenance["retrievedAtEpochMillis"],
                provenance["editionEvidenceLocator"],
                provenance["editionEvidenceFingerprint"],
            )
        )
    for mapping in sorted(
        candidate["subjectMappings"],
        key=lambda row: row["subject"],
    ):
        values.extend(
            (
                mapping["subject"],
                mapping["officialStandardCode"],
                mapping["rootNodeStableCode"],
                mapping["stableCodeNamespace"],
                mapping["curriculumSourceId"],
                *sorted(mapping["textbookEditionSourceIds"]),
            )
        )
    values.extend(
        (
            "coverage-proof-v2",
            candidate["coverageProofV2"]["proofFingerprint"],
        )
    )
    return formal_sha256(values)


def independent_review_fingerprint(review: Mapping[str, Any]) -> str:
    """Mirror FormalKnowledgePackIndependentReview.reviewFingerprint."""

    return formal_sha256(
        (
            review["reviewRecordId"],
            review["candidateGovernanceFingerprint"],
            review["candidateContentFingerprint"],
            review["producerOrganizationId"],
            review["reviewerOrganizationId"],
            review["reviewedAtEpochMillis"],
            review["decision"],
            review["contentPolicyAttestation"],
            review["coverageFingerprint"],
            "coverage-proof-v2",
            review["coverageProofFingerprint"],
        )
    )


def signed_activation_payload(activation: Mapping[str, Any]) -> bytes:
    """Mirror SignedFormalKnowledgePackActivation.canonicalPayload."""

    return formal_canonical_text(
        (
            activation["packId"],
            activation["knowledgePackVersion"],
            activation["taxonomyVersion"],
            activation["searchIndexVersion"],
            activation["contentFingerprint"],
            activation["candidateGovernanceFingerprint"],
            activation["independentReviewFingerprint"],
            activation["activationGeneration"],
            activation["signedAtEpochMillis"],
            activation["signingKeyId"],
            activation["algorithm"],
            "coverage-proof-v2",
            activation["coverageProofFingerprint"],
        )
    ).encode("utf-8")


def _validate_signed_release_render_inputs(
    *,
    artifact: Mapping[str, Any],
    candidate: Mapping[str, Any],
    review: Mapping[str, Any],
    signing_key: Mapping[str, Any],
    signed_activation: Mapping[str, Any],
    governance: Mapping[str, Any],
    release_signing_registration: Mapping[str, Any],
) -> None:
    """Require a pinned signature binding every renderer input exactly."""

    validate_formal_artifact_v2(artifact)
    registration = _validate_pinned_release_signing_registration(
        release_signing_registration
    )
    _require_registered_release_signing(governance, registration)
    expected_candidate = _build_candidate(artifact, governance)
    expected_review = _build_independent_review(expected_candidate, governance)
    expected_activation = _build_unsigned_activation(
        artifact,
        expected_candidate,
        expected_review,
        governance,
    )
    actual_activation = dict(signed_activation)
    signature_hex = actual_activation.get("signatureHex")
    actual_activation["signatureHex"] = ""
    if (
        dict(signing_key) != registration["signingKey"]
        or dict(candidate) != expected_candidate
        or dict(review) != expected_review
        or actual_activation != expected_activation
        or not isinstance(signature_hex, str)
        or not signature_hex
    ):
        raise ValueError("Formal release renderer inputs are not signed and pinned")
    _verify_signature(
        registration["signingKey"]["x509PublicKeyHex"],
        signed_activation_payload(signed_activation),
        signature_hex,
    )


def _verify_generated_release_bundle(
    *,
    receipt: Mapping[str, Any],
    asset_source: str,
    registry_source: str,
    artifact: Mapping[str, Any],
    candidate: Mapping[str, Any],
    review: Mapping[str, Any],
    signing_key: Mapping[str, Any],
    signed_activation: Mapping[str, Any],
    governance: Mapping[str, Any],
    release_signing_registration: Mapping[str, Any],
    asset_input_fingerprint: str,
    registry_input_fingerprint: str,
) -> None:
    validate_formal_artifact_v2(artifact)
    registration = _validate_pinned_release_signing_registration(
        release_signing_registration
    )
    _require_registered_release_signing(governance, registration)
    registration_fingerprint = release_signing_registration_fingerprint(
        registration
    )
    signed_payload = signed_activation_payload(signed_activation)
    expected_candidate = _build_candidate(artifact, governance)
    expected_review = _build_independent_review(expected_candidate, governance)
    expected_unsigned_activation = _build_unsigned_activation(
        artifact,
        expected_candidate,
        expected_review,
        governance,
    )
    actual_unsigned_activation = dict(signed_activation)
    actual_unsigned_activation["signatureHex"] = ""
    current_asset_input_fingerprint = _release_render_input_fingerprint(
        "formal-release-asset-render-input-v1",
        (artifact, candidate, review),
    )
    current_registry_input_fingerprint = _release_render_input_fingerprint(
        "formal-release-registry-render-input-v1",
        (
            signing_key,
            signed_activation,
            governance["candidate"]["activationGeneration"],
        ),
    )
    expected = {
        "releaseSigningRegistrationId": registration["registrationId"],
        "releaseSigningRegistrationFingerprint": registration_fingerprint,
        "activationGeneration": registration["releaseGeneration"],
        "signingKey": registration["signingKey"],
        "signatureHex": signed_activation["signatureHex"],
        "signedPayloadFingerprint": hashlib.sha256(
            signed_payload
        ).hexdigest(),
        "assetSourceFingerprint": hashlib.sha256(
            asset_source.encode("utf-8")
        ).hexdigest(),
        "registrySourceFingerprint": hashlib.sha256(
            registry_source.encode("utf-8")
        ).hexdigest(),
        "containsPrivateKeyMaterial": False,
        "runtimeTrustRegistryMutationAllowed": False,
    }
    actual = {key: receipt.get(key) for key in expected}
    if (
        actual != expected
        or dict(signing_key) != registration["signingKey"]
        or dict(candidate) != expected_candidate
        or dict(review) != expected_review
        or actual_unsigned_activation != expected_unsigned_activation
        or current_asset_input_fingerprint != asset_input_fingerprint
        or current_registry_input_fingerprint != registry_input_fingerprint
    ):
        raise ValueError(
            "Generated formal release receipt or source hash is invalid"
        )
    _verify_signature(
        registration["signingKey"]["x509PublicKeyHex"],
        signed_payload,
        signed_activation["signatureHex"],
    )


def _release_render_input_fingerprint(
    domain: str,
    values: Sequence[Any],
) -> str:
    return canonical_fingerprint(domain, list(values))


def render_formal_pack_asset_kotlin(
    artifact: Mapping[str, Any],
    candidate: Mapping[str, Any],
    review: Mapping[str, Any],
) -> str:
    del artifact, candidate, review
    raise ValueError(
        "Direct formal asset rendering is not authorized; "
        "use generate_signed_release"
    )


def _render_formal_pack_asset_kotlin(
    artifact: Mapping[str, Any],
    candidate: Mapping[str, Any],
    review: Mapping[str, Any],
    *,
    authorization: object,
) -> str:
    del artifact, candidate, review, authorization
    raise ValueError(
        "Formal release renderer lacks internal authorization; "
        "use generate_signed_release"
    )


def _append_formal_pack_asset_body(
    builder: _KotlinAssetBuilder,
    artifact: Mapping[str, Any],
    candidate: Mapping[str, Any],
    review: Mapping[str, Any],
    *,
    signing_key: Mapping[str, Any],
    signed_activation: Mapping[str, Any],
    governance: Mapping[str, Any],
    release_signing_registration: Mapping[str, Any],
) -> None:
    _validate_signed_release_render_inputs(
        artifact=artifact,
        candidate=candidate,
        review=review,
        signing_key=signing_key,
        signed_activation=signed_activation,
        governance=governance,
        release_signing_registration=release_signing_registration,
    )
    _render_pack(builder, artifact)
    _render_coverage_proof(builder, artifact["coverageProofV2"])
    _render_candidate(builder, candidate)
    _render_review(builder, review)
    _validate_signed_release_render_inputs(
        artifact=artifact,
        candidate=candidate,
        review=review,
        signing_key=signing_key,
        signed_activation=signed_activation,
        governance=governance,
        release_signing_registration=release_signing_registration,
    )


def render_formal_release_registry_kotlin(
    signing_key: Mapping[str, Any],
    signed_activation: Mapping[str, Any],
    activation_generation: int,
) -> str:
    del signing_key, signed_activation, activation_generation
    raise ValueError(
        "Direct formal registry rendering is not authorized; "
        "use generate_signed_release"
    )


def _render_formal_release_registry_kotlin(
    signing_key: Mapping[str, Any],
    signed_activation: Mapping[str, Any],
    activation_generation: int,
    *,
    authorization: object,
) -> str:
    del signing_key, signed_activation, activation_generation, authorization
    raise ValueError(
        "Formal release renderer lacks internal authorization; "
        "use generate_signed_release"
    )


def _append_formal_release_registry_body(
    builder: _KotlinAssetBuilder,
    signing_key: Mapping[str, Any],
    signed_activation: Mapping[str, Any],
    activation_generation: int,
    *,
    artifact: Mapping[str, Any],
    candidate: Mapping[str, Any],
    review: Mapping[str, Any],
    governance: Mapping[str, Any],
    release_signing_registration: Mapping[str, Any],
) -> None:
    _validate_signed_release_render_inputs(
        artifact=artifact,
        candidate=candidate,
        review=review,
        signing_key=signing_key,
        signed_activation=signed_activation,
        governance=governance,
        release_signing_registration=release_signing_registration,
    )
    builder.line(
        "val formalSigningKeys: List<FormalKnowledgePackSigningKey> ="
    )
    builder.indent += 1
    builder.line("listOf(")
    builder.indent += 1
    builder.line("FormalKnowledgePackSigningKey(")
    builder.indent += 1
    _render_assignments(
        builder,
        (
            ("keyId", _kotlin_string(signing_key["keyId"])),
            (
                "algorithm",
                "FormalKnowledgeSignatureAlgorithm."
                + signing_key["algorithm"],
            ),
            (
                "x509PublicKeyHex",
                _kotlin_string(signing_key["x509PublicKeyHex"]),
            ),
            (
                "validFromEpochMillis",
                _kotlin_long(signing_key["validFromEpochMillis"]),
            ),
            (
                "validUntilEpochMillis",
                _kotlin_long(signing_key["validUntilEpochMillis"]),
            ),
            (
                "revokedAtEpochMillis",
                _kotlin_nullable_long(signing_key["revokedAtEpochMillis"]),
            ),
            (
                "revocationEvidenceFingerprint",
                _kotlin_nullable_string(
                    signing_key["revocationEvidenceFingerprint"]
                ),
            ),
        ),
    )
    builder.indent -= 1
    builder.line("),")
    builder.indent -= 1
    builder.line(")")
    builder.indent -= 1
    builder.line()
    builder.line(
        "private val signedActivation = SignedFormalKnowledgePackActivation("
    )
    builder.indent += 1
    _render_assignments(
        builder,
        (
            ("packId", _kotlin_string(signed_activation["packId"])),
            (
                "knowledgePackVersion",
                _kotlin_string(signed_activation["knowledgePackVersion"]),
            ),
            (
                "taxonomyVersion",
                _kotlin_string(signed_activation["taxonomyVersion"]),
            ),
            (
                "searchIndexVersion",
                _kotlin_string(signed_activation["searchIndexVersion"]),
            ),
            (
                "contentFingerprint",
                _kotlin_string(signed_activation["contentFingerprint"]),
            ),
            (
                "candidateGovernanceFingerprint",
                _kotlin_string(
                    signed_activation["candidateGovernanceFingerprint"]
                ),
            ),
            (
                "independentReviewFingerprint",
                _kotlin_string(
                    signed_activation["independentReviewFingerprint"]
                ),
            ),
            (
                "activationGeneration",
                _kotlin_long(signed_activation["activationGeneration"]),
            ),
            (
                "signedAtEpochMillis",
                _kotlin_long(signed_activation["signedAtEpochMillis"]),
            ),
            (
                "signingKeyId",
                _kotlin_string(signed_activation["signingKeyId"]),
            ),
            (
                "algorithm",
                "FormalKnowledgeSignatureAlgorithm."
                + signed_activation["algorithm"],
            ),
            (
                "signatureHex",
                _kotlin_string(signed_activation["signatureHex"]),
            ),
            (
                "coverageProofFingerprint",
                _kotlin_string(
                    signed_activation["coverageProofFingerprint"]
                ),
            ),
        ),
    )
    builder.indent -= 1
    builder.line(")")
    builder.line()
    builder.line(
        "val entries: List<BuildVariantTrustedKnowledgePackDefinition> ="
    )
    builder.indent += 1
    builder.line("listOf(")
    builder.indent += 1
    builder.line("BuildVariantTrustedKnowledgePackDefinition(")
    builder.indent += 1
    builder.line("pack = GeneratedFormalKnowledgePackAsset.pack,")
    builder.line(
        f"generation = {_kotlin_long(activation_generation)},"
    )
    builder.line("productionCutoverEligible = true,")
    builder.line(
        "purpose = BuiltInKnowledgePackPurpose.FORMAL_HIGH_SCHOOL_RELEASE,"
    )
    builder.line("formalActivationProof = FormalKnowledgePackActivationProof(")
    builder.indent += 1
    builder.line(
        "candidate = GeneratedFormalKnowledgePackAsset.candidate,"
    )
    builder.line(
        "independentReview = "
        "GeneratedFormalKnowledgePackAsset.independentReview,"
    )
    builder.line("signedActivation = signedActivation,")
    builder.indent -= 1
    builder.line("),")
    builder.indent -= 1
    builder.line("),")
    builder.indent -= 1
    builder.line(")")
    builder.indent -= 1
    _validate_signed_release_render_inputs(
        artifact=artifact,
        candidate=candidate,
        review=review,
        signing_key=signing_key,
        signed_activation=signed_activation,
        governance=governance,
        release_signing_registration=release_signing_registration,
    )


def _normalize_governance(
    artifact: Mapping[str, Any],
    governance: Mapping[str, Any],
) -> dict[str, Any]:
    _require_exact_keys(governance, _GOVERNANCE_KEYS, "release governance")
    if governance.get("schemaVersion") != RELEASE_GOVERNANCE_SCHEMA_VERSION:
        raise ValueError("Unsupported release-governance schema")
    candidate = _require_mapping(governance["candidate"], "candidate")
    _require_exact_keys(candidate, _CANDIDATE_KEYS, "candidate")
    _require_id(candidate["candidateId"], "candidate id")
    producer = _require_id(
        candidate["producerOrganizationId"],
        "producer organization id",
    )
    prepared_at = _require_nonnegative_int(
        candidate["preparedAtEpochMillis"],
        "candidate preparation time",
    )
    activation_generation = _require_positive_int(
        candidate["activationGeneration"],
        "activation generation",
    )

    artifact_sources = {
        row["sourceId"]: row for row in artifact["sources"]
    }
    source_evidence_rows = _require_list(
        governance["sourceEvidence"],
        "source evidence",
    )
    normalized_sources: list[dict[str, Any]] = []
    seen_sources: set[str] = set()
    for raw in source_evidence_rows:
        evidence = _require_mapping(raw, "source evidence entry")
        _require_exact_keys(
            evidence,
            _SOURCE_EVIDENCE_KEYS,
            "source evidence entry",
        )
        source_id = _require_id(evidence["sourceId"], "source evidence id")
        if source_id in seen_sources:
            raise ValueError("Duplicate source evidence id")
        seen_sources.add(source_id)
        source = artifact_sources.get(source_id)
        if source is None:
            raise ValueError("Source evidence references an unknown source")
        source_version = _require_id(
            evidence["sourceVersion"],
            "source evidence version",
        )
        if source_version != source.get("edition") or not source_version:
            raise ValueError("Source evidence does not bind the reviewed edition")
        for field, label in (
            ("publisher", "publisher"),
            ("sourceUri", "canonical source URI"),
            ("licenseExpression", "license expression"),
            ("licenseUri", "license evidence URI"),
        ):
            if not isinstance(source.get(field), str) or not source[field]:
                raise ValueError(f"Formal source lacks reviewed {label}")
        license_class = str(evidence["licenseClass"])
        if license_class not in _ALLOWED_LICENSE_CLASSES:
            raise ValueError("Source license class is blocked for the core pack")
        if _EXPECTED_LICENSE_STATUS[license_class] != source["licenseStatus"]:
            raise ValueError("Source license class and catalog status disagree")
        automation = str(evidence["automationPermission"])
        if automation not in _ALLOWED_AUTOMATION:
            raise ValueError("Source automation permission is fail-closed")
        if evidence["evidenceOrigin"] != "HUMAN_VERIFIED":
            raise ValueError("Source license evidence must be human verified")
        if evidence["reviewStatus"] != "HUMAN_REVIEWED":
            raise ValueError("Source evidence must be human reviewed")
        if (
            license_class != "PERMISSIVE" or automation != "ALLOWED"
        ) and source["contentUsePolicy"] != "REVIEWED_SYNTHESIS_ONLY":
            raise ValueError(
                "Metadata/reference sources cannot authorize copied expression"
            )
        evidence_review_id = _require_id(
            evidence["reviewRecordId"],
            "source evidence review record",
        )
        if evidence_review_id != source.get("reviewRecordId"):
            raise ValueError("Source evidence review record does not match artifact")
        reviewed_at = _require_nonnegative_int(
            evidence["reviewedAtEpochMillis"],
            "source evidence review time",
        )
        if reviewed_at != source["reviewedAtEpochMillis"]:
            raise ValueError("Source evidence review time does not match artifact")
        retrieved_at = _require_nonnegative_int(
            evidence["retrievedAtEpochMillis"],
            "source retrieval time",
        )
        if retrieved_at > reviewed_at:
            raise ValueError("Source retrieval cannot postdate source review")
        license_evidence_fingerprint = _require_sha256(
            evidence["licenseEvidenceFingerprint"],
            "source license evidence fingerprint",
        )
        edition_locator = _optional_text(
            evidence["editionEvidenceLocator"],
            "edition evidence locator",
        )
        edition_fingerprint = _optional_sha256(
            evidence["editionEvidenceFingerprint"],
            "edition evidence fingerprint",
        )
        is_textbook = source["sourceType"] == "TEXTBOOK"
        if is_textbook != (
            edition_locator is not None and edition_fingerprint is not None
        ):
            raise ValueError(
                "Textbook sources require exact reviewed edition evidence"
            )
        normalized_sources.append(
            {
                "admissionEvidence": {
                    "sourceId": source_id,
                    "sourceVersion": source_version,
                    "sourceRecordFingerprint": (
                        canonical_source_record_fingerprint(
                            source,
                            source_version,
                        )
                    ),
                    "canonicalLocator": source["sourceUri"],
                    "publisher": source["publisher"],
                    "resourceTitle": source["title"],
                    "licenseClass": license_class,
                    "licenseExpression": source["licenseExpression"],
                    "licenseEvidenceLocator": source["licenseUri"],
                    "licenseEvidenceFingerprint": (
                        license_evidence_fingerprint
                    ),
                    "evidenceOrigin": "HUMAN_VERIFIED",
                    "automationPermission": automation,
                    "reviewStatus": "HUMAN_REVIEWED",
                    "reviewRecordId": evidence_review_id,
                    "reviewedAtEpochMillis": reviewed_at,
                },
                "sourceContentFingerprint": source["contentFingerprint"],
                "retrievedAtEpochMillis": retrieved_at,
                "editionEvidenceLocator": edition_locator,
                "editionEvidenceFingerprint": edition_fingerprint,
            }
        )
    if seen_sources != set(artifact_sources):
        raise ValueError("Every formal source requires exact reviewed evidence")

    mappings = _normalize_subject_mappings(
        artifact,
        governance["subjectMappings"],
    )
    latest_review = _latest_review_time(artifact)
    if prepared_at < latest_review:
        raise ValueError("Candidate predates reviewed artifact content")
    proof_reviewed_at = int(
        artifact["coverageProofV2"]["coverageReviewedAtEpochMillis"]
    )
    if prepared_at < proof_reviewed_at:
        raise ValueError("Candidate predates reviewed coverage proof")

    independent_review = _require_mapping(
        governance["independentReview"],
        "independent review",
    )
    _require_exact_keys(
        independent_review,
        _INDEPENDENT_REVIEW_KEYS,
        "independent review",
    )
    _require_id(
        independent_review["reviewRecordId"],
        "independent review record id",
    )
    reviewer = _require_id(
        independent_review["reviewerOrganizationId"],
        "reviewer organization id",
    )
    if reviewer == producer:
        raise ValueError("Independent reviewer must differ from producer")
    review_time = _require_nonnegative_int(
        independent_review["reviewedAtEpochMillis"],
        "independent review time",
    )
    if review_time < prepared_at:
        raise ValueError("Independent review predates candidate")
    if independent_review["decision"] != "APPROVED":
        raise ValueError("Formal release requires an approved review")
    if (
        independent_review["contentPolicyAttestation"]
        != CONTENT_POLICY_ATTESTATION
    ):
        raise ValueError("Formal release lacks safe content attestation")
    expected_release_fingerprints = {
        "formalArtifactFingerprint": artifact["manifest"][
            "contentFingerprint"
        ],
        "runtimeContentFingerprint": artifact["manifest"][
            "runtimeContentFingerprint"
        ],
        "coverageProofFingerprint": artifact["coverageProofV2"][
            "proofFingerprint"
        ],
    }
    for key, expected in expected_release_fingerprints.items():
        actual = _require_sha256(
            independent_review[key],
            f"independent review {key}",
        )
        if actual != expected:
            raise ValueError(
                "Independent review does not bind the exact formal release"
            )

    signing = _require_mapping(governance["signing"], "signing")
    _require_exact_keys(signing, _SIGNING_KEYS, "signing")
    _require_id(signing["keyId"], "signing key id")
    if signing["algorithm"] != SIGNATURE_ALGORITHM:
        raise ValueError("Unsupported formal-pack signature algorithm")
    signed_at = _require_nonnegative_int(
        signing["signedAtEpochMillis"],
        "signature time",
    )
    valid_from = _require_nonnegative_int(
        signing["validFromEpochMillis"],
        "signing-key valid-from time",
    )
    valid_until = _require_nonnegative_int(
        signing["validUntilEpochMillis"],
        "signing-key valid-until time",
    )
    if valid_until < valid_from or signed_at not in range(
        valid_from,
        valid_until + 1,
    ):
        raise ValueError("Signature time is outside key validity interval")
    if signed_at < review_time:
        raise ValueError("Signature predates independent review")
    revoked_at = signing["revokedAtEpochMillis"]
    revocation_fingerprint = signing["revocationEvidenceFingerprint"]
    if revoked_at is not None or revocation_fingerprint is not None:
        if revoked_at is not None:
            _require_nonnegative_int(revoked_at, "key revocation time")
        if revocation_fingerprint is not None:
            _require_sha256(
                revocation_fingerprint,
                "key revocation evidence fingerprint",
            )
        raise ValueError("Revoked signing keys cannot authorize a release")

    return {
        "schemaVersion": RELEASE_GOVERNANCE_SCHEMA_VERSION,
        "candidate": {
            **candidate,
            "candidateId": str(candidate["candidateId"]),
            "producerOrganizationId": producer,
            "preparedAtEpochMillis": prepared_at,
            "activationGeneration": activation_generation,
        },
        "sourceProvenance": sorted(
            normalized_sources,
            key=lambda row: row["admissionEvidence"]["sourceId"],
        ),
        "subjectMappings": mappings,
        "independentReview": dict(independent_review),
        "signing": {
            **signing,
            "signedAtEpochMillis": signed_at,
            "validFromEpochMillis": valid_from,
            "validUntilEpochMillis": valid_until,
            "revokedAtEpochMillis": None,
            "revocationEvidenceFingerprint": None,
        },
    }


def _validate_release_signing_registration(
    registration: Mapping[str, Any],
) -> dict[str, Any]:
    normalized = validate_release_signing_registration_before_processing(
        registration
    )
    _validate_rsa_public_key(normalized["signingKey"]["x509PublicKeyHex"])
    return normalized


def _validate_release_signing_key(
    signing_key: Mapping[str, Any],
) -> dict[str, Any]:
    normalized = _preflight_release_signing_key(signing_key)
    _validate_rsa_public_key(normalized["x509PublicKeyHex"])
    return normalized


def _preflight_release_signing_key(
    signing_key: Mapping[str, Any],
) -> dict[str, Any]:
    _require_exact_keys(
        signing_key,
        _RELEASE_SIGNING_KEY_KEYS,
        "registered release signing key",
    )
    key_id = _require_id(
        signing_key["keyId"],
        "registered release signing key id",
    )
    if signing_key.get("algorithm") != SIGNATURE_ALGORITHM:
        raise ValueError("Unsupported registered release signing algorithm")
    public_key_hex = signing_key.get("x509PublicKeyHex")
    if (
        not isinstance(public_key_hex, str)
        or len(public_key_hex) > 32_768
        or len(public_key_hex) % 2
        or not _HEX.fullmatch(public_key_hex)
    ):
        raise ValueError("Registered release public key is invalid")
    valid_from = _require_nonnegative_int(
        signing_key["validFromEpochMillis"],
        "registered release key valid-from time",
    )
    valid_until = _require_nonnegative_int(
        signing_key["validUntilEpochMillis"],
        "registered release key valid-until time",
    )
    if valid_until < valid_from:
        raise ValueError("Registered release key validity is invalid")
    revoked_at = signing_key["revokedAtEpochMillis"]
    revocation_fingerprint = signing_key[
        "revocationEvidenceFingerprint"
    ]
    if (revoked_at is None) != (revocation_fingerprint is None):
        raise ValueError("Registered release key revocation is incomplete")
    if revoked_at is not None:
        _require_nonnegative_int(
            revoked_at,
            "registered release key revocation time",
        )
        _require_sha256(
            revocation_fingerprint,
            "registered release key revocation evidence",
        )
        raise ValueError(
            "Revoked signing keys cannot authorize a release"
        )
    return {
        "keyId": key_id,
        "algorithm": SIGNATURE_ALGORITHM,
        "x509PublicKeyHex": public_key_hex,
        "validFromEpochMillis": valid_from,
        "validUntilEpochMillis": valid_until,
        "revokedAtEpochMillis": None,
        "revocationEvidenceFingerprint": None,
    }


def _validate_pinned_release_signing_registration(
    registration: Mapping[str, Any],
) -> dict[str, Any]:
    normalized = _validate_release_signing_registration(registration)
    fingerprint = canonical_fingerprint(
        "formal-release-signing-registration-v1",
        normalized,
    )
    active = PINNED_RELEASE_SIGNING_REGISTRATION
    if (
        active is None
        or active
        != (fingerprint, normalized["releaseGeneration"])
    ):
        raise ValueError(
            "Release signing registration is not pinned as the active generation"
        )
    return normalized


def _require_registered_release_signing(
    governance: Mapping[str, Any],
    registration: Mapping[str, Any],
) -> None:
    signing = governance["signing"]
    expected = {
        "releaseGeneration": governance["candidate"][
            "activationGeneration"
        ],
        "keyId": signing["keyId"],
        "algorithm": signing["algorithm"],
        "validFromEpochMillis": signing["validFromEpochMillis"],
        "validUntilEpochMillis": signing["validUntilEpochMillis"],
        "revokedAtEpochMillis": signing["revokedAtEpochMillis"],
        "revocationEvidenceFingerprint": signing[
            "revocationEvidenceFingerprint"
        ],
    }
    registered_key = registration["signingKey"]
    registered = {
        "releaseGeneration": registration["releaseGeneration"],
        "keyId": registered_key["keyId"],
        "algorithm": registered_key["algorithm"],
        "validFromEpochMillis": registered_key[
            "validFromEpochMillis"
        ],
        "validUntilEpochMillis": registered_key[
            "validUntilEpochMillis"
        ],
        "revokedAtEpochMillis": registered_key[
            "revokedAtEpochMillis"
        ],
        "revocationEvidenceFingerprint": registered_key[
            "revocationEvidenceFingerprint"
        ],
    }
    if expected != registered:
        raise ValueError(
            "Release governance does not match the pinned signing generation"
        )


def _public_key_fingerprint(x509_public_key_hex: str) -> str:
    try:
        encoded = bytes.fromhex(x509_public_key_hex)
    except ValueError as error:
        raise ValueError("Registered release public key is invalid") from error
    return hashlib.sha256(
        b"formal-release-signing-public-key-v1\0" + encoded
    ).hexdigest()


def _validate_rsa_public_key(x509_public_key_hex: str) -> None:
    try:
        from cryptography.hazmat.primitives import serialization
        from cryptography.hazmat.primitives.asymmetric import rsa

        public_key = serialization.load_der_public_key(
            bytes.fromhex(x509_public_key_hex)
        )
        if (
            not isinstance(public_key, rsa.RSAPublicKey)
            or public_key.key_size < 2_048
        ):
            raise ValueError
    except Exception as error:
        raise ValueError(
            "Registered release public key must be RSA-2048 or stronger"
        ) from error


def _normalize_subject_mappings(
    artifact: Mapping[str, Any],
    raw_mappings: Any,
) -> list[dict[str, Any]]:
    mappings = _require_list(raw_mappings, "subject mappings")
    nodes_by_subject: dict[str, list[Mapping[str, Any]]] = {}
    sources_by_subject: dict[str, list[Mapping[str, Any]]] = {}
    for node in artifact["nodes"]:
        nodes_by_subject.setdefault(node["subject"], []).append(node)
    for source in artifact["sources"]:
        sources_by_subject.setdefault(source["subject"], []).append(source)
    normalized: list[dict[str, Any]] = []
    seen: set[str] = set()
    for raw in mappings:
        mapping = _require_mapping(raw, "subject mapping")
        _require_exact_keys(mapping, _SUBJECT_MAPPING_KEYS, "subject mapping")
        subject = str(mapping["subject"])
        if subject not in REQUIRED_SUBJECTS or subject in seen:
            raise ValueError("Subject mappings must cover nine distinct subjects")
        seen.add(subject)
        standard_code = str(mapping["officialStandardCode"])
        if not _STANDARD_CODE.fullmatch(standard_code):
            raise ValueError("Official subject-standard code is invalid")
        root_stable_code = _require_id(
            mapping["rootNodeStableCode"],
            "root stable code",
        )
        namespace = _require_id(
            mapping["stableCodeNamespace"],
            "stable-code namespace",
        )
        if not namespace.endswith((".", ":")):
            raise ValueError(
                "Stable-code namespace requires a supported separator"
            )
        subject_nodes = nodes_by_subject.get(subject, [])
        if not subject_nodes or not all(
            node["stableCode"].startswith(namespace) for node in subject_nodes
        ):
            raise ValueError("Subject nodes do not share reviewed namespace")
        roots = [
            node
            for node in subject_nodes
            if node["stableCode"] == root_stable_code
            and node["kind"] == "TOPIC"
            and node["parentKnowledgeNodeId"] is None
        ]
        if len(roots) != 1:
            raise ValueError("Subject mapping lacks one exact reviewed root")
        subject_sources = sources_by_subject.get(subject, [])
        curriculum_source_id = _require_id(
            mapping["curriculumSourceId"],
            "curriculum source id",
        )
        curriculum_sources = [
            source
            for source in subject_sources
            if source["sourceId"] == curriculum_source_id
            and source["sourceType"] == "OFFICIAL_CURRICULUM_STANDARD"
        ]
        if len(curriculum_sources) != 1:
            raise ValueError("Subject mapping lacks official curriculum source")
        textbook_ids = _require_string_list(
            mapping["textbookEditionSourceIds"],
            "textbook edition source ids",
        )
        if not textbook_ids or len(textbook_ids) != len(set(textbook_ids)):
            raise ValueError("Textbook edition mappings must be nonempty and unique")
        actual_textbooks = {
            source["sourceId"]
            for source in subject_sources
            if source["sourceType"] == "TEXTBOOK"
        }
        if set(textbook_ids) != actual_textbooks:
            raise ValueError("Subject mapping does not bind every textbook edition")
        normalized.append(
            {
                "subject": subject,
                "officialStandardCode": standard_code,
                "rootNodeStableCode": root_stable_code,
                "stableCodeNamespace": namespace,
                "curriculumSourceId": curriculum_source_id,
                "textbookEditionSourceIds": sorted(textbook_ids),
            }
        )
    if seen != set(REQUIRED_SUBJECTS):
        raise ValueError("Subject mappings must cover all nine subjects")
    return sorted(normalized, key=lambda row: row["subject"])


def _build_candidate(
    artifact: Mapping[str, Any],
    governance: Mapping[str, Any],
) -> dict[str, Any]:
    manifest = artifact["manifest"]
    candidate_input = governance["candidate"]
    candidate: dict[str, Any] = {
        "candidateId": candidate_input["candidateId"],
        "producerOrganizationId": candidate_input["producerOrganizationId"],
        "preparedAtEpochMillis": candidate_input["preparedAtEpochMillis"],
        "packId": manifest["packId"],
        "knowledgePackVersion": manifest["knowledgePackVersion"],
        "taxonomyVersion": manifest["taxonomyVersion"],
        "searchIndexVersion": manifest["searchIndexVersion"],
        "contentFingerprint": manifest["runtimeContentFingerprint"],
        "coverageFingerprint": coverage_fingerprint(artifact),
        "sourceProvenance": governance["sourceProvenance"],
        "subjectMappings": governance["subjectMappings"],
        "coverageProofV2": artifact["coverageProofV2"],
    }
    candidate["governanceFingerprint"] = candidate_governance_fingerprint(
        candidate
    )
    return candidate


def _build_independent_review(
    candidate: Mapping[str, Any],
    governance: Mapping[str, Any],
) -> dict[str, Any]:
    reviewed = governance["independentReview"]
    review: dict[str, Any] = {
        "reviewRecordId": reviewed["reviewRecordId"],
        "candidateGovernanceFingerprint": candidate[
            "governanceFingerprint"
        ],
        "candidateContentFingerprint": candidate["contentFingerprint"],
        "producerOrganizationId": candidate["producerOrganizationId"],
        "reviewerOrganizationId": reviewed["reviewerOrganizationId"],
        "reviewedAtEpochMillis": reviewed["reviewedAtEpochMillis"],
        "decision": "APPROVED",
        "contentPolicyAttestation": CONTENT_POLICY_ATTESTATION,
        "coverageFingerprint": candidate["coverageFingerprint"],
        "coverageProofFingerprint": candidate["coverageProofV2"][
            "proofFingerprint"
        ],
    }
    review["reviewFingerprint"] = independent_review_fingerprint(review)
    return review


def _build_unsigned_activation(
    artifact: Mapping[str, Any],
    candidate: Mapping[str, Any],
    review: Mapping[str, Any],
    governance: Mapping[str, Any],
) -> dict[str, Any]:
    signing = governance["signing"]
    manifest = artifact["manifest"]
    return {
        "packId": manifest["packId"],
        "knowledgePackVersion": manifest["knowledgePackVersion"],
        "taxonomyVersion": manifest["taxonomyVersion"],
        "searchIndexVersion": manifest["searchIndexVersion"],
        "contentFingerprint": manifest["runtimeContentFingerprint"],
        "candidateGovernanceFingerprint": candidate[
            "governanceFingerprint"
        ],
        "independentReviewFingerprint": review["reviewFingerprint"],
        "activationGeneration": governance["candidate"][
            "activationGeneration"
        ],
        "signedAtEpochMillis": signing["signedAtEpochMillis"],
        "signingKeyId": signing["keyId"],
        "algorithm": SIGNATURE_ALGORITHM,
        "signatureHex": "",
        "coverageProofFingerprint": artifact["coverageProofV2"][
            "proofFingerprint"
        ],
    }


def _load_rsa_private_key(
    private_key_pem: bytes,
    password: bytes | None,
) -> tuple[Any, str]:
    if not private_key_pem:
        raise ValueError("Private signing key is empty")
    try:
        from cryptography.hazmat.primitives import serialization
        from cryptography.hazmat.primitives.asymmetric import rsa

        private_key = serialization.load_pem_private_key(
            private_key_pem,
            password=password,
        )
        if not isinstance(private_key, rsa.RSAPrivateKey):
            raise ValueError("Formal signing key must be RSA")
        if private_key.key_size < 2_048:
            raise ValueError("Formal signing key must contain at least 2048 bits")
        public_key_hex = private_key.public_key().public_bytes(
            encoding=serialization.Encoding.DER,
            format=serialization.PublicFormat.SubjectPublicKeyInfo,
        ).hex()
        return private_key, public_key_hex
    except ValueError:
        raise
    except Exception as error:
        raise ValueError("Unable to load formal signing key") from error


def _sign_payload(private_key: Any, payload: bytes) -> str:
    from cryptography.hazmat.primitives import hashes
    from cryptography.hazmat.primitives.asymmetric import padding

    return private_key.sign(
        payload,
        padding.PKCS1v15(),
        hashes.SHA256(),
    ).hex()


def _verify_signature(
    public_key_hex: str,
    payload: bytes,
    signature_hex: str,
) -> None:
    try:
        from cryptography.hazmat.primitives import hashes, serialization
        from cryptography.hazmat.primitives.asymmetric import padding, rsa

        public_key = serialization.load_der_public_key(
            bytes.fromhex(public_key_hex)
        )
        if (
            not isinstance(public_key, rsa.RSAPublicKey)
            or public_key.key_size < 2_048
        ):
            raise ValueError("Generated public key is not RSA-2048 or stronger")
        public_key.verify(
            bytes.fromhex(signature_hex),
            payload,
            padding.PKCS1v15(),
            hashes.SHA256(),
        )
    except Exception as error:
        raise ValueError("Generated formal signature failed verification") from error


def _render_pack(
    builder: "_KotlinAssetBuilder",
    artifact: Mapping[str, Any],
) -> None:
    manifest = artifact["manifest"]
    builder.line("val pack: ReviewedKnowledgePack by lazy {")
    builder.indent += 1
    builder.line("ReviewedKnowledgePack(")
    builder.indent += 1
    builder.line("metadata = ReviewedKnowledgePackMetadata(")
    builder.indent += 1
    _render_assignments(
        builder,
        (
            ("packId", _kotlin_string(manifest["packId"])),
            (
                "knowledgePackVersion",
                _kotlin_string(manifest["knowledgePackVersion"]),
            ),
            (
                "taxonomyVersion",
                _kotlin_string(manifest["taxonomyVersion"]),
            ),
            (
                "searchIndexVersion",
                _kotlin_string(manifest["searchIndexVersion"]),
            ),
            (
                "builtAtEpochMillis",
                _kotlin_long(manifest["builtAtEpochMillis"]),
            ),
        ),
    )
    builder.indent -= 1
    builder.line("),")
    builder.line("nodes = reviewedNodes(),")
    builder.line("sources = reviewedSources(),")
    builder.line("nodeSourceBindings = reviewedNodeSourceBindings(),")
    builder.line("relations = reviewedRelations(),")
    builder.line("teachingMaterials = reviewedTeachingMaterials(),")
    builder.line(
        "teachingMaterialBindings = reviewedTeachingMaterialBindings(),"
    )
    builder.indent -= 1
    builder.line(")")
    builder.indent -= 1
    builder.line("}")
    builder.line()

    _render_chunked_function(
        builder,
        "reviewedNodes",
        "ReviewedKnowledgeNode",
        [
            _node_constructor(row)
            for row in sorted(
                artifact["nodes"],
                key=lambda item: item["knowledgeNodeId"],
            )
        ],
    )
    _render_chunked_function(
        builder,
        "reviewedSources",
        "ReviewedKnowledgeSource",
        [
            _source_constructor(row)
            for row in sorted(
                artifact["sources"],
                key=lambda item: item["sourceId"],
            )
        ],
    )
    _render_chunked_function(
        builder,
        "reviewedNodeSourceBindings",
        "ReviewedKnowledgeNodeSourceBinding",
        [
            _node_source_binding_constructor(row)
            for row in sorted(
                artifact["nodeSourceBindings"],
                key=lambda item: (
                    item["knowledgeNodeId"],
                    item["sourceId"],
                    item["sourceLocator"],
                ),
            )
        ],
    )
    _render_chunked_function(
        builder,
        "reviewedRelations",
        "ReviewedKnowledgeRelation",
        [
            _relation_constructor(row)
            for row in sorted(
                artifact["relations"],
                key=lambda item: item["relationId"],
            )
        ],
    )
    _render_chunked_function(
        builder,
        "reviewedTeachingMaterials",
        "ReviewedKnowledgeTeachingMaterial",
        [
            _material_constructor(row)
            for row in sorted(
                artifact["teachingMaterials"],
                key=lambda item: item["materialId"],
            )
        ],
    )
    _render_chunked_function(
        builder,
        "reviewedTeachingMaterialBindings",
        "ReviewedKnowledgeTeachingMaterialBinding",
        [
            _material_binding_constructor(row)
            for row in sorted(
                artifact["teachingMaterialBindings"],
                key=lambda item: (
                    item["materialId"],
                    item["knowledgeNodeId"],
                ),
            )
        ],
    )


def _render_coverage_proof(
    builder: "_KotlinAssetBuilder",
    proof: Mapping[str, Any],
) -> None:
    for subject_proof in sorted(
        proof["subjects"],
        key=lambda row: row["subject"],
    ):
        prefix = subject_proof["subject"].lower()
        _render_chunked_string_function(
            builder,
            f"{prefix}ModuleStableCodes",
            subject_proof["expectedModuleStableCodes"],
        )
        _render_chunked_string_function(
            builder,
            f"{prefix}KnowledgePointStableCodes",
            subject_proof["expectedKnowledgePointStableCodes"],
        )
        builder.line(
            f"private fun {prefix}CoverageProof() ="
        )
        builder.indent += 1
        builder.line("FormalKnowledgeSubjectCoverageProofV2(")
        builder.indent += 1
        builder.line(f"subject = SubjectKind.{subject_proof['subject']},")
        builder.line(
            "expectedModuleStableCodes = "
            f"{prefix}ModuleStableCodes(),"
        )
        builder.line(
            "expectedKnowledgePointStableCodes = "
            f"{prefix}KnowledgePointStableCodes(),"
        )
        for key in (
            "relationCoverage",
            "methodCoverage",
            "workedExampleCoverage",
            "textbookMappingCoverage",
        ):
            builder.line(f"{key} = FormalKnowledgeCoverageSummary(")
            builder.indent += 1
            builder.line(
                f"itemCount = {int(subject_proof[key]['itemCount'])},"
            )
            builder.line(
                "itemFingerprint = "
                f"{_kotlin_string(subject_proof[key]['itemFingerprint'])},"
            )
            builder.indent -= 1
            builder.line("),")
        builder.indent -= 1
        builder.line(")")
        builder.indent -= 1
        builder.line()
    builder.line("private val coverageProofV2 by lazy {")
    builder.indent += 1
    builder.line("FormalKnowledgePackCoverageProofV2(")
    builder.indent += 1
    _render_assignments(
        builder,
        (
            (
                "coverageLedgerId",
                _kotlin_string(proof["coverageLedgerId"]),
            ),
            (
                "targetBaselineId",
                _kotlin_string(proof["targetBaselineId"]),
            ),
            (
                "coverageLedgerFingerprint",
                _kotlin_string(proof["coverageLedgerFingerprint"]),
            ),
            (
                "coverageReviewRecordId",
                _kotlin_string(proof["coverageReviewRecordId"]),
            ),
            (
                "coverageReviewedAtEpochMillis",
                _kotlin_long(proof["coverageReviewedAtEpochMillis"]),
            ),
            (
                "humanReviewSummaryFingerprint",
                _kotlin_string(proof["humanReviewSummaryFingerprint"]),
            ),
            (
                "sourceLicenseReviewSummaryFingerprint",
                _kotlin_string(
                    proof["sourceLicenseReviewSummaryFingerprint"]
                ),
            ),
        ),
    )
    builder.line("subjects = listOf(")
    builder.indent += 1
    for subject in sorted(
        (row["subject"] for row in proof["subjects"])
    ):
        builder.line(f"{subject.lower()}CoverageProof(),")
    builder.indent -= 1
    builder.line("),")
    builder.indent -= 1
    builder.line(")")
    builder.indent -= 1
    builder.line("}")
    builder.line()


def _render_candidate(
    builder: "_KotlinAssetBuilder",
    candidate: Mapping[str, Any],
) -> None:
    _render_chunked_function(
        builder,
        "formalSourceProvenance",
        "FormalKnowledgeSourceProvenance",
        [
            _provenance_constructor(row)
            for row in candidate["sourceProvenance"]
        ],
    )
    _render_chunked_function(
        builder,
        "formalSubjectMappings",
        "FormalKnowledgeSubjectMapping",
        [
            _subject_mapping_constructor(row)
            for row in candidate["subjectMappings"]
        ],
    )
    builder.line("val candidate: FormalKnowledgePackCandidate by lazy {")
    builder.indent += 1
    builder.line("FormalKnowledgePackCandidate(")
    builder.indent += 1
    _render_assignments(
        builder,
        (
            ("candidateId", _kotlin_string(candidate["candidateId"])),
            (
                "producerOrganizationId",
                _kotlin_string(candidate["producerOrganizationId"]),
            ),
            (
                "preparedAtEpochMillis",
                _kotlin_long(candidate["preparedAtEpochMillis"]),
            ),
            ("packId", _kotlin_string(candidate["packId"])),
            (
                "knowledgePackVersion",
                _kotlin_string(candidate["knowledgePackVersion"]),
            ),
            (
                "taxonomyVersion",
                _kotlin_string(candidate["taxonomyVersion"]),
            ),
            (
                "searchIndexVersion",
                _kotlin_string(candidate["searchIndexVersion"]),
            ),
            (
                "contentFingerprint",
                _kotlin_string(candidate["contentFingerprint"]),
            ),
            (
                "coverageFingerprint",
                _kotlin_string(candidate["coverageFingerprint"]),
            ),
        ),
    )
    builder.line("sourceProvenance = formalSourceProvenance(),")
    builder.line("subjectMappings = formalSubjectMappings(),")
    builder.line("coverageProofV2 = coverageProofV2,")
    builder.indent -= 1
    builder.line(")")
    builder.indent -= 1
    builder.line("}")
    builder.line()


def _render_review(
    builder: "_KotlinAssetBuilder",
    review: Mapping[str, Any],
) -> None:
    builder.line(
        "val independentReview: FormalKnowledgePackIndependentReview by lazy {"
    )
    builder.indent += 1
    builder.line("FormalKnowledgePackIndependentReview(")
    builder.indent += 1
    _render_assignments(
        builder,
        (
            (
                "reviewRecordId",
                _kotlin_string(review["reviewRecordId"]),
            ),
            (
                "candidateGovernanceFingerprint",
                _kotlin_string(review["candidateGovernanceFingerprint"]),
            ),
            (
                "candidateContentFingerprint",
                _kotlin_string(review["candidateContentFingerprint"]),
            ),
            (
                "producerOrganizationId",
                _kotlin_string(review["producerOrganizationId"]),
            ),
            (
                "reviewerOrganizationId",
                _kotlin_string(review["reviewerOrganizationId"]),
            ),
            (
                "reviewedAtEpochMillis",
                _kotlin_long(review["reviewedAtEpochMillis"]),
            ),
            (
                "decision",
                "FormalKnowledgePackReviewDecision." + review["decision"],
            ),
            (
                "contentPolicyAttestation",
                "FormalKnowledgeContentPolicyAttestation."
                + review["contentPolicyAttestation"],
            ),
            (
                "coverageFingerprint",
                _kotlin_string(review["coverageFingerprint"]),
            ),
            (
                "coverageProofFingerprint",
                _kotlin_string(review["coverageProofFingerprint"]),
            ),
        ),
    )
    builder.indent -= 1
    builder.line(")")
    builder.indent -= 1
    builder.line("}")


def _node_constructor(row: Mapping[str, Any]) -> str:
    return _constructor(
        "ReviewedKnowledgeNode",
        (
            ("knowledgeNodeId", _kotlin_string(row["knowledgeNodeId"])),
            ("stableCode", _kotlin_string(row["stableCode"])),
            ("subject", f"SubjectKind.{row['subject']}"),
            ("displayName", _kotlin_string(row["displayName"])),
            ("canonicalName", _kotlin_string(row["canonicalName"])),
            ("kind", f"KnowledgeNodeKind.{row['kind']}"),
            (
                "granularity",
                f"KnowledgeNodeGranularity.{row['granularity']}",
            ),
            ("aliases", _kotlin_string_list(row["aliases"])),
            (
                "boundaryMarkdown",
                _kotlin_nullable_string(row["boundaryMarkdown"]),
            ),
            (
                "verificationStatus",
                "KnowledgeNodeVerificationStatus."
                + row["verificationStatus"],
            ),
            (
                "parentKnowledgeNodeId",
                _kotlin_nullable_string(row["parentKnowledgeNodeId"]),
            ),
            (
                "reviewedAtEpochMillis",
                _kotlin_long(row["reviewedAtEpochMillis"]),
            ),
        ),
    )


def _source_constructor(row: Mapping[str, Any]) -> str:
    return _constructor(
        "ReviewedKnowledgeSource",
        (
            ("sourceId", _kotlin_string(row["sourceId"])),
            ("subject", f"SubjectKind.{row['subject']}"),
            ("sourceType", f"KnowledgeSourceType.{row['sourceType']}"),
            ("title", _kotlin_string(row["title"])),
            ("publisher", _kotlin_nullable_string(row["publisher"])),
            ("edition", _kotlin_nullable_string(row["edition"])),
            ("sourceUri", _kotlin_nullable_string(row["sourceUri"])),
            (
                "licenseStatus",
                f"KnowledgeSourceLicenseStatus.{row['licenseStatus']}",
            ),
            (
                "contentUsePolicy",
                "KnowledgeSourceContentUsePolicy."
                + row["contentUsePolicy"],
            ),
            (
                "contentFingerprint",
                _kotlin_string(row["contentFingerprint"]),
            ),
            (
                "licenseExpression",
                _kotlin_nullable_string(row["licenseExpression"]),
            ),
            ("licenseUri", _kotlin_nullable_string(row["licenseUri"])),
            (
                "attributionText",
                _kotlin_nullable_string(row["attributionText"]),
            ),
            (
                "reviewedAtEpochMillis",
                _kotlin_long(row["reviewedAtEpochMillis"]),
            ),
        ),
    )


def _node_source_binding_constructor(row: Mapping[str, Any]) -> str:
    return _constructor(
        "ReviewedKnowledgeNodeSourceBinding",
        (
            (
                "knowledgeNodeId",
                _kotlin_string(row["knowledgeNodeId"]),
            ),
            ("sourceId", _kotlin_string(row["sourceId"])),
            ("sourceLocator", _kotlin_string(row["sourceLocator"])),
            ("derivationNote", _kotlin_string(row["derivationNote"])),
            (
                "reviewedAtEpochMillis",
                _kotlin_long(row["reviewedAtEpochMillis"]),
            ),
        ),
    )


def _relation_constructor(row: Mapping[str, Any]) -> str:
    return _constructor(
        "ReviewedKnowledgeRelation",
        (
            ("relationId", _kotlin_string(row["relationId"])),
            ("subject", f"SubjectKind.{row['subject']}"),
            (
                "fromKnowledgeNodeId",
                _kotlin_string(row["fromKnowledgeNodeId"]),
            ),
            (
                "toKnowledgeNodeId",
                _kotlin_string(row["toKnowledgeNodeId"]),
            ),
            ("relationType", _kotlin_string(row["relationType"])),
            ("sourceId", _kotlin_string(row["sourceId"])),
            ("sourceLocator", _kotlin_string(row["sourceLocator"])),
            (
                "reviewedAtEpochMillis",
                _kotlin_long(row["reviewedAtEpochMillis"]),
            ),
        ),
    )


def _material_constructor(row: Mapping[str, Any]) -> str:
    return _constructor(
        "ReviewedKnowledgeTeachingMaterial",
        (
            ("materialId", _kotlin_string(row["materialId"])),
            ("stableCode", _kotlin_string(row["stableCode"])),
            ("subject", f"SubjectKind.{row['subject']}"),
            (
                "materialType",
                f"KnowledgeTeachingMaterialType.{row['materialType']}",
            ),
            ("title", _kotlin_string(row["title"])),
            (
                "summaryMarkdown",
                _kotlin_string(row["summaryMarkdown"]),
            ),
            (
                "applicabilityMarkdown",
                _kotlin_string(row["applicabilityMarkdown"]),
            ),
            (
                "contentMarkdown",
                _kotlin_string(row["contentMarkdown"]),
            ),
            (
                "boundaryMarkdown",
                _kotlin_string(row["boundaryMarkdown"]),
            ),
            (
                "derivationKind",
                "KnowledgeMaterialDerivationKind." + row["derivationKind"],
            ),
            ("sourceId", _kotlin_string(row["sourceId"])),
            ("sourceLocator", _kotlin_string(row["sourceLocator"])),
            (
                "contentFingerprint",
                _kotlin_string(row["contentFingerprint"]),
            ),
            (
                "reviewedAtEpochMillis",
                _kotlin_long(row["reviewedAtEpochMillis"]),
            ),
        ),
    )


def _material_binding_constructor(row: Mapping[str, Any]) -> str:
    return _constructor(
        "ReviewedKnowledgeTeachingMaterialBinding",
        (
            ("materialId", _kotlin_string(row["materialId"])),
            (
                "knowledgeNodeId",
                _kotlin_string(row["knowledgeNodeId"]),
            ),
            ("role", f"KnowledgeMaterialNodeRole.{row['role']}"),
        ),
    )


def _provenance_constructor(row: Mapping[str, Any]) -> str:
    evidence = row["admissionEvidence"]
    admission = _constructor(
        "KnowledgeSourceAdmissionEvidence",
        (
            ("sourceId", _kotlin_string(evidence["sourceId"])),
            ("sourceVersion", _kotlin_string(evidence["sourceVersion"])),
            (
                "sourceRecordFingerprint",
                _kotlin_string(evidence["sourceRecordFingerprint"]),
            ),
            (
                "canonicalLocator",
                _kotlin_string(evidence["canonicalLocator"]),
            ),
            ("publisher", _kotlin_string(evidence["publisher"])),
            ("resourceTitle", _kotlin_string(evidence["resourceTitle"])),
            (
                "licenseClass",
                "KnowledgeSourceLicenseClass." + evidence["licenseClass"],
            ),
            (
                "licenseExpression",
                _kotlin_nullable_string(evidence["licenseExpression"]),
            ),
            (
                "licenseEvidenceLocator",
                _kotlin_nullable_string(evidence["licenseEvidenceLocator"]),
            ),
            (
                "licenseEvidenceFingerprint",
                _kotlin_nullable_string(
                    evidence["licenseEvidenceFingerprint"]
                ),
            ),
            (
                "evidenceOrigin",
                "KnowledgeSourceEvidenceOrigin." + evidence["evidenceOrigin"],
            ),
            (
                "automationPermission",
                "KnowledgeSourceAutomationPermission."
                + evidence["automationPermission"],
            ),
            (
                "reviewStatus",
                "KnowledgeArtifactReviewStatus." + evidence["reviewStatus"],
            ),
            (
                "reviewRecordId",
                _kotlin_nullable_string(evidence["reviewRecordId"]),
            ),
            (
                "reviewedAtEpochMillis",
                _kotlin_nullable_long(evidence["reviewedAtEpochMillis"]),
            ),
        ),
    )
    return _constructor(
        "FormalKnowledgeSourceProvenance",
        (
            ("admissionEvidence", admission),
            (
                "sourceContentFingerprint",
                _kotlin_string(row["sourceContentFingerprint"]),
            ),
            (
                "retrievedAtEpochMillis",
                _kotlin_long(row["retrievedAtEpochMillis"]),
            ),
            (
                "editionEvidenceLocator",
                _kotlin_nullable_string(row["editionEvidenceLocator"]),
            ),
            (
                "editionEvidenceFingerprint",
                _kotlin_nullable_string(row["editionEvidenceFingerprint"]),
            ),
        ),
    )


def _subject_mapping_constructor(row: Mapping[str, Any]) -> str:
    return _constructor(
        "FormalKnowledgeSubjectMapping",
        (
            ("subject", f"SubjectKind.{row['subject']}"),
            (
                "officialStandardCode",
                _kotlin_string(row["officialStandardCode"]),
            ),
            (
                "rootNodeStableCode",
                _kotlin_string(row["rootNodeStableCode"]),
            ),
            (
                "stableCodeNamespace",
                _kotlin_string(row["stableCodeNamespace"]),
            ),
            (
                "curriculumSourceId",
                _kotlin_string(row["curriculumSourceId"]),
            ),
            (
                "textbookEditionSourceIds",
                _kotlin_string_list(row["textbookEditionSourceIds"]),
            ),
        ),
    )


def _constructor(
    name: str,
    assignments: Sequence[tuple[str, str]],
) -> str:
    lines = [f"{name}("]
    for field, value in assignments:
        value_lines = value.splitlines() or [value]
        if len(value_lines) == 1:
            lines.append(f"    {field} = {value_lines[0]},")
        else:
            lines.append(f"    {field} = {value_lines[0]}")
            lines.extend(f"    {line}" for line in value_lines[1:-1])
            lines.append(f"    {value_lines[-1]},")
    lines.append(")")
    return "\n".join(lines)


def _render_chunked_function(
    builder: "_KotlinAssetBuilder",
    function_name: str,
    type_name: str,
    constructors: Sequence[str],
    chunk_size: int = 48,
) -> None:
    chunks = [
        constructors[index : index + chunk_size]
        for index in range(0, len(constructors), chunk_size)
    ]
    if not chunks:
        builder.line(
            f"private fun {function_name}(): List<{type_name}> = emptyList()"
        )
        builder.line()
        return
    builder.line(f"private fun {function_name}(): List<{type_name}> =")
    builder.indent += 1
    if len(chunks) == 1:
        builder.line(f"{function_name}Part0()")
    else:
        builder.line("buildList {")
        builder.indent += 1
        for index in range(len(chunks)):
            builder.line(f"addAll({function_name}Part{index}())")
        builder.indent -= 1
        builder.line("}")
    builder.indent -= 1
    builder.line()
    for index, chunk in enumerate(chunks):
        builder.line(
            f"private fun {function_name}Part{index}(): "
            f"List<{type_name}> ="
        )
        builder.indent += 1
        builder.line("listOf(")
        builder.indent += 1
        for constructor in chunk:
            builder.block(constructor, suffix=",")
        builder.indent -= 1
        builder.line(")")
        builder.indent -= 1
        builder.line()


def _render_chunked_string_function(
    builder: "_KotlinAssetBuilder",
    function_name: str,
    values: Sequence[str],
    chunk_size: int = 64,
) -> None:
    chunks = [
        values[index : index + chunk_size]
        for index in range(0, len(values), chunk_size)
    ]
    builder.line(f"private fun {function_name}(): List<String> =")
    builder.indent += 1
    if not chunks:
        builder.line("emptyList()")
    elif len(chunks) == 1:
        builder.line(
            "listOf(" + ", ".join(_kotlin_string(value) for value in chunks[0]) + ")"
        )
    else:
        builder.line("buildList {")
        builder.indent += 1
        for chunk in chunks:
            builder.line(
                "addAll(listOf("
                + ", ".join(_kotlin_string(value) for value in chunk)
                + "))"
            )
        builder.indent -= 1
        builder.line("}")
    builder.indent -= 1
    builder.line()


class _KotlinAssetBuilder:
    def __init__(self) -> None:
        self.indent = 0
        self._lines: list[str] = []

    def line(self, text: str = "") -> None:
        self._lines.append(("    " * self.indent + text) if text else "")

    def block(self, text: str, suffix: str = "") -> None:
        lines = text.splitlines()
        for index, line in enumerate(lines):
            self.line(line + (suffix if index == len(lines) - 1 else ""))

    def render(self) -> str:
        return "\n".join(self._lines).rstrip() + "\n"


def _render_assignments(
    builder: _KotlinAssetBuilder,
    assignments: Sequence[tuple[str, str]],
    trailing: bool = True,
) -> None:
    for index, (field, value) in enumerate(assignments):
        suffix = "," if trailing or index < len(assignments) - 1 else ""
        value_lines = value.splitlines()
        if len(value_lines) == 1:
            builder.line(f"{field} = {value}{suffix}")
        else:
            builder.line(f"{field} = {value_lines[0]}")
            for line in value_lines[1:-1]:
                builder.line(line)
            builder.line(value_lines[-1] + suffix)


def _kotlin_string(value: str) -> str:
    if not isinstance(value, str):
        raise ValueError("Expected Kotlin string value")
    return json.dumps(value, ensure_ascii=False).replace("$", "\\$")


def _kotlin_nullable_string(value: Any) -> str:
    return "null" if value is None else _kotlin_string(str(value))


def _kotlin_string_list(values: Iterable[str]) -> str:
    return "listOf(" + ", ".join(_kotlin_string(value) for value in values) + ")"


def _kotlin_long(value: int) -> str:
    return f"{int(value)}L"


def _kotlin_nullable_long(value: Any) -> str:
    return "null" if value is None else _kotlin_long(int(value))


def _latest_review_time(artifact: Mapping[str, Any]) -> int:
    times = [
        int(row["reviewedAtEpochMillis"])
        for key in (
            "nodes",
            "sources",
            "nodeSourceBindings",
            "relations",
            "teachingMaterials",
        )
        for row in artifact[key]
    ]
    return max(times, default=0)


def _require_mapping(value: Any, label: str) -> Mapping[str, Any]:
    if not isinstance(value, Mapping):
        raise ValueError(f"{label} must be an object")
    return value


def _require_list(value: Any, label: str) -> list[Any]:
    if not isinstance(value, list):
        raise ValueError(f"{label} must be an array")
    return value


def _require_exact_keys(
    value: Mapping[str, Any],
    keys: set[str],
    label: str,
) -> None:
    actual = set(value)
    if actual != keys:
        raise ValueError(
            f"{label} has unexpected schema; "
            f"missing={sorted(keys - actual)}, extra={sorted(actual - keys)}"
        )


def _require_id(value: Any, label: str) -> str:
    text = str(value)
    if not _ID.fullmatch(text):
        raise ValueError(f"{label} is invalid")
    return text


def _require_nonnegative_int(value: Any, label: str) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value < 0:
        raise ValueError(f"{label} must be a nonnegative integer")
    return value


def _require_positive_int(value: Any, label: str) -> int:
    result = _require_nonnegative_int(value, label)
    if result == 0:
        raise ValueError(f"{label} must be positive")
    return result


def _require_sha256(value: Any, label: str) -> str:
    if not isinstance(value, str) or not _SHA256.fullmatch(value):
        raise ValueError(f"{label} must be lowercase SHA-256")
    return value


def _optional_sha256(value: Any, label: str) -> str | None:
    return None if value is None else _require_sha256(value, label)


def _optional_text(value: Any, label: str) -> str | None:
    if value is None:
        return None
    if not isinstance(value, str) or not value or len(value) > 4_096:
        raise ValueError(f"{label} is invalid")
    if any(ord(character) < 32 and character not in "\n\t" for character in value):
        raise ValueError(f"{label} contains control characters")
    return value


def _require_string_list(value: Any, label: str) -> list[str]:
    rows = _require_list(value, label)
    return [_require_id(row, label) for row in rows]
