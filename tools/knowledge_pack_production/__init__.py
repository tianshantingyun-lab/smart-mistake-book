"""Fail-closed offline production gate for formal knowledge packs."""

from .formal_pack import (
    FORMAL_PACK_REVIEW_ATTESTATION,
    TEACHING_REVIEW_ATTESTATION,
    artifact_fingerprint,
    calculate_expected_version_fingerprint,
    compile_formal_pack,
    expected_material_id,
    expected_node_identity,
    expected_relation_id,
    production_status,
)

__all__ = [
    "FORMAL_PACK_REVIEW_ATTESTATION",
    "TEACHING_REVIEW_ATTESTATION",
    "artifact_fingerprint",
    "calculate_expected_version_fingerprint",
    "compile_formal_pack",
    "expected_material_id",
    "expected_node_identity",
    "expected_relation_id",
    "production_status",
]
