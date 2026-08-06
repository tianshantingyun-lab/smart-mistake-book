"""Canonical bridge between the offline compiler and the Android formal-pack gate."""

from __future__ import annotations

import hashlib
import re
from copy import deepcopy
from typing import Any, Iterable, Mapping, Sequence

from .input_hashes import artifact_fingerprint, canonical_fingerprint
from .schema import EXAMPLE_MATERIAL_TYPES, REQUIRED_SUBJECTS


FORMAL_COVERAGE_PROOF_SCHEMA_VERSION = 2
MIN_POINTS_PER_SUBJECT = 2
KOTLIN_FORMAL_SUBJECT_ORDER = (
    "CHINESE",
    "MATH",
    "ENGLISH",
    "PHYSICS",
    "CHEMISTRY",
    "BIOLOGY",
    "POLITICS",
    "HISTORY",
    "GEOGRAPHY",
)

_REVIEWED_CONTENT_DOMAIN = "formal-reviewed-knowledge-pack-content-v2"
_COVERAGE_PROOF_DOMAIN = "formal-knowledge-coverage-proof-v2"
_RELATION_DOMAIN = "formal-subject-relation-coverage-v2"
_METHOD_DOMAIN = "formal-subject-method-coverage-v2"
_EXAMPLE_DOMAIN = "formal-subject-example-coverage-v2"
_TEXTBOOK_MAPPING_DOMAIN = "formal-subject-textbook-mapping-coverage-v2"
_SHA256 = re.compile(r"^[0-9a-f]{64}$")


def _kotlin_text(value: Any) -> str:
    if value is None:
        return ""
    if isinstance(value, bool):
        return "true" if value else "false"
    return str(value)


def _kotlin_utf16_length(value: str) -> int:
    return len(value.encode("utf-16-le")) // 2


def formal_canonical_text(values: Iterable[Any]) -> str:
    """Mirror FormalCanonicalWriter.field, including Kotlin UTF-16 String.length."""

    parts: list[str] = []
    for value in values:
        text = _kotlin_text(value)
        parts.append(f"{_kotlin_utf16_length(text)}:{text}")
    return "".join(parts)


def formal_sha256(values: Iterable[Any]) -> str:
    return hashlib.sha256(formal_canonical_text(values).encode("utf-8")).hexdigest()


def _canonical_record(values: Sequence[Any]) -> str:
    return formal_canonical_text(values)


def coverage_summary(
    domain: str,
    subject: str,
    records: Iterable[Sequence[Any]],
) -> dict[str, Any]:
    canonical_records = sorted(_canonical_record(record) for record in records)
    return {
        "itemCount": len(canonical_records),
        "itemFingerprint": formal_sha256((domain, subject, *canonical_records)),
    }


def reviewed_content_fingerprint(artifact: Mapping[str, Any]) -> str:
    """Fingerprint fields represented by ReviewedKnowledgePack, excluding search rows."""

    manifest = artifact["manifest"]
    values: list[Any] = [
        _REVIEWED_CONTENT_DOMAIN,
        manifest["packId"],
        manifest["knowledgePackVersion"],
        manifest["taxonomyVersion"],
        manifest["searchIndexVersion"],
        manifest["builtAtEpochMillis"],
    ]
    for node in sorted(artifact["nodes"], key=lambda row: row["knowledgeNodeId"]):
        values.extend(
            (
                node["knowledgeNodeId"],
                node["stableCode"],
                node["subject"],
                node["displayName"],
                node["canonicalName"],
                node["kind"],
                node["granularity"],
                *sorted(node["aliases"]),
                node["boundaryMarkdown"],
                node["verificationStatus"],
                node["parentKnowledgeNodeId"],
                node["reviewedAtEpochMillis"],
            )
        )
    for source in sorted(artifact["sources"], key=lambda row: row["sourceId"]):
        values.extend(
            (
                source["sourceId"],
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
    for binding in sorted(
        artifact["nodeSourceBindings"],
        key=lambda row: (
            row["knowledgeNodeId"],
            row["sourceId"],
            row["sourceLocator"],
        ),
    ):
        values.extend(
            (
                binding["knowledgeNodeId"],
                binding["sourceId"],
                binding["sourceLocator"],
                binding["derivationNote"],
                binding["reviewedAtEpochMillis"],
            )
        )
    for relation in sorted(
        artifact["relations"],
        key=lambda row: row["relationId"],
    ):
        values.extend(
            (
                relation["relationId"],
                relation["subject"],
                relation["fromKnowledgeNodeId"],
                relation["toKnowledgeNodeId"],
                relation["relationType"],
                relation["sourceId"],
                relation["sourceLocator"],
                relation["reviewedAtEpochMillis"],
            )
        )
    for material in sorted(
        artifact["teachingMaterials"],
        key=lambda row: row["materialId"],
    ):
        values.extend(
            (
                material["materialId"],
                material["stableCode"],
                material["subject"],
                material["materialType"],
                material["title"],
                material["summaryMarkdown"],
                material["applicabilityMarkdown"],
                material["contentMarkdown"],
                material["boundaryMarkdown"],
                material["derivationKind"],
                material["sourceId"],
                material["sourceLocator"],
                material["contentFingerprint"],
                material["reviewedAtEpochMillis"],
            )
        )
    for binding in sorted(
        artifact["teachingMaterialBindings"],
        key=lambda row: (row["materialId"], row["knowledgeNodeId"]),
    ):
        values.extend(
            (
                binding["materialId"],
                binding["knowledgeNodeId"],
                binding["role"],
            )
        )
    return formal_sha256(values)


def coverage_proof_fingerprint(proof: Mapping[str, Any]) -> str:
    values: list[Any] = [
        _COVERAGE_PROOF_DOMAIN,
        FORMAL_COVERAGE_PROOF_SCHEMA_VERSION,
        proof["coverageLedgerId"],
        proof["targetBaselineId"],
        proof["coverageLedgerFingerprint"],
        proof["coverageReviewRecordId"],
        proof["coverageReviewedAtEpochMillis"],
        proof["humanReviewSummaryFingerprint"],
        proof["sourceLicenseReviewSummaryFingerprint"],
    ]
    for subject in sorted(proof["subjects"], key=lambda row: row["subject"]):
        values.append(subject["subject"])
        values.extend(sorted(subject["expectedModuleStableCodes"]))
        values.extend(sorted(subject["expectedKnowledgePointStableCodes"]))
        for key in (
            "relationCoverage",
            "methodCoverage",
            "workedExampleCoverage",
            "textbookMappingCoverage",
        ):
            summary = subject[key]
            values.extend((summary["itemCount"], summary["itemFingerprint"]))
    return formal_sha256(values)


def derive_runtime_coverage_subjects(
    artifact: Mapping[str, Any],
) -> list[dict[str, Any]]:
    """Derive the exact runtime coverage surface committed by proof v2."""

    nodes = list(artifact["nodes"])
    sources = list(artifact["sources"])
    node_bindings = list(artifact["nodeSourceBindings"])
    relations = list(artifact["relations"])
    materials = list(artifact["teachingMaterials"])
    material_bindings = list(artifact["teachingMaterialBindings"])
    nodes_by_id = {row["knowledgeNodeId"]: row for row in nodes}
    if len(nodes_by_id) != len(nodes):
        raise ValueError("Formal artifact contains duplicate knowledge-node ids")
    sources_by_id = {row["sourceId"]: row for row in sources}
    if len(sources_by_id) != len(sources):
        raise ValueError("Formal artifact contains duplicate source ids")
    bindings_by_material: dict[str, list[dict[str, Any]]] = {}
    for binding in material_bindings:
        bindings_by_material.setdefault(binding["materialId"], []).append(binding)

    textbook_source_ids = {
        source_id
        for source_id, source in sources_by_id.items()
        if source["sourceType"] == "TEXTBOOK"
    }
    subject_proofs: list[dict[str, Any]] = []
    for subject in REQUIRED_SUBJECTS:
        subject_nodes = [node for node in nodes if node["subject"] == subject]
        module_codes = sorted(
            node["stableCode"]
            for node in subject_nodes
            if node["kind"] == "TOPIC"
        )
        point_codes = sorted(
            node["stableCode"]
            for node in subject_nodes
            if node["granularity"] == "ATOMIC"
        )
        if not module_codes:
            raise ValueError(f"{subject} lacks a reviewed curriculum-module scope")
        if len(point_codes) < MIN_POINTS_PER_SUBJECT:
            raise ValueError(f"{subject} is only a placeholder knowledge scope")

        try:
            relation_records = [
                (
                    relation["relationId"],
                    nodes_by_id[relation["fromKnowledgeNodeId"]]["stableCode"],
                    nodes_by_id[relation["toKnowledgeNodeId"]]["stableCode"],
                    relation["relationType"],
                    relation["sourceId"],
                    relation["sourceLocator"],
                )
                for relation in relations
                if relation["subject"] == subject
            ]
        except KeyError as error:
            raise ValueError(
                "Formal relation references a missing knowledge node"
            ) from error

        def material_records(allowed_types: set[str]) -> list[tuple[Any, ...]]:
            records: list[tuple[Any, ...]] = []
            for material in materials:
                if (
                    material["subject"] != subject
                    or material["materialType"] not in allowed_types
                ):
                    continue
                try:
                    bound_codes = sorted(
                        nodes_by_id[binding["knowledgeNodeId"]]["stableCode"]
                        for binding in bindings_by_material.get(
                            material["materialId"],
                            [],
                        )
                    )
                except KeyError as error:
                    raise ValueError(
                        "Teaching material references a missing knowledge node"
                    ) from error
                records.append(
                    (
                        material["materialId"],
                        material["stableCode"],
                        material["materialType"],
                        material["sourceId"],
                        material["sourceLocator"],
                        material["contentFingerprint"],
                        *bound_codes,
                    )
                )
            return records

        try:
            textbook_mapping_records = [
                (
                    nodes_by_id[binding["knowledgeNodeId"]]["stableCode"],
                    binding["sourceId"],
                    binding["sourceLocator"],
                )
                for binding in node_bindings
                if binding["sourceId"] in textbook_source_ids
                and nodes_by_id[binding["knowledgeNodeId"]]["subject"] == subject
            ]
        except KeyError as error:
            raise ValueError(
                "Textbook mapping references a missing knowledge node"
            ) from error
        textbook_mapped_points = {
            record[0]
            for record in textbook_mapping_records
            if record[0] in point_codes
        }
        if textbook_mapped_points != set(point_codes):
            raise ValueError(
                f"{subject} has atomic knowledge without reviewed textbook mapping"
            )

        relation_summary = coverage_summary(
            _RELATION_DOMAIN,
            subject,
            relation_records,
        )
        method_summary = coverage_summary(
            _METHOD_DOMAIN,
            subject,
            material_records({"METHOD_MODEL"}),
        )
        example_summary = coverage_summary(
            _EXAMPLE_DOMAIN,
            subject,
            material_records(set(EXAMPLE_MATERIAL_TYPES)),
        )
        textbook_summary = coverage_summary(
            _TEXTBOOK_MAPPING_DOMAIN,
            subject,
            textbook_mapping_records,
        )
        for label, summary in (
            ("relation", relation_summary),
            ("method", method_summary),
            ("example", example_summary),
            ("textbook mapping", textbook_summary),
        ):
            if summary["itemCount"] == 0:
                raise ValueError(f"{subject} lacks reviewed {label} coverage")
        subject_proofs.append(
            {
                "subject": subject,
                "expectedModuleStableCodes": module_codes,
                "expectedKnowledgePointStableCodes": point_codes,
                "relationCoverage": relation_summary,
                "methodCoverage": method_summary,
                "workedExampleCoverage": example_summary,
                "textbookMappingCoverage": textbook_summary,
            }
        )
    return subject_proofs


def validate_formal_artifact_v2(artifact: Mapping[str, Any]) -> None:
    """Fail closed unless an artifact exactly satisfies the runtime v2 contract."""

    if artifact.get("schemaVersion") != 2:
        raise ValueError("Formal runtime conversion requires artifact schema v2")
    if artifact.get("artifactType") != "FORMAL_REVIEWED_KNOWLEDGE_PACK":
        raise ValueError("Unsupported formal artifact type")
    manifest = artifact.get("manifest")
    if not isinstance(manifest, Mapping):
        raise ValueError("Formal artifact manifest is missing")
    for key, rows_key in (
        ("nodeCount", "nodes"),
        ("sourceCount", "sources"),
        ("relationCount", "relations"),
        ("materialCount", "teachingMaterials"),
    ):
        rows = artifact.get(rows_key)
        if not isinstance(rows, list) or manifest.get(key) != len(rows):
            raise ValueError(f"Formal artifact {key} does not match its content")
    version_fingerprint = manifest.get("contentFingerprint")
    runtime_fingerprint = manifest.get("runtimeContentFingerprint")
    if not isinstance(version_fingerprint, str) or not _SHA256.fullmatch(
        version_fingerprint
    ):
        raise ValueError("Formal artifact version fingerprint is invalid")
    unsigned = deepcopy(dict(artifact))
    unsigned_manifest = unsigned.get("manifest")
    if not isinstance(unsigned_manifest, dict):
        raise ValueError("Formal artifact manifest is invalid")
    unsigned_manifest.pop("contentFingerprint", None)
    if version_fingerprint != canonical_fingerprint(
        "formal-high-school-knowledge-pack-v2",
        unsigned,
    ):
        raise ValueError("Formal artifact version fingerprint was modified")
    if not isinstance(runtime_fingerprint, str) or not _SHA256.fullmatch(
        runtime_fingerprint
    ):
        raise ValueError("Formal artifact runtime content fingerprint is invalid")
    if runtime_fingerprint != reviewed_content_fingerprint(artifact):
        raise ValueError("Formal artifact runtime content was modified")

    governance = artifact.get("governance")
    if not isinstance(governance, Mapping):
        raise ValueError("Formal artifact governance is missing")
    holdout_compliance = governance.get("holdoutCompliance")
    if not isinstance(holdout_compliance, Mapping):
        raise ValueError("Formal artifact lacks holdout compliance")
    expected_holdout_keys = {
        "schemaVersion",
        "contentInputManifestId",
        "contentInputManifestVersion",
        "contentInputManifestFingerprint",
        "holdoutManifestId",
        "holdoutManifestVersion",
        "holdoutManifestFingerprint",
        "holdoutReleaseRegistrationId",
        "holdoutReleaseRegistrationFingerprint",
        "holdoutReleaseGeneration",
        "fingerprintPolicyVersion",
        "fingerprintKeyId",
        "contentSurfaceCounts",
        "holdoutEntryCount",
        "overlapCount",
        "complianceFingerprint",
    }
    if set(holdout_compliance) != expected_holdout_keys:
        raise ValueError("Formal artifact holdout compliance schema is invalid")
    for key in (
        "contentInputManifestFingerprint",
        "holdoutManifestFingerprint",
        "holdoutReleaseRegistrationFingerprint",
        "complianceFingerprint",
    ):
        value = holdout_compliance.get(key)
        if not isinstance(value, str) or not _SHA256.fullmatch(value):
            raise ValueError(
                f"Formal artifact holdout compliance {key} is invalid"
            )
    content_surface_counts = holdout_compliance.get("contentSurfaceCounts")
    if (
        not isinstance(content_surface_counts, Mapping)
        or set(content_surface_counts)
        != {
            "rawText",
            "normalizedText",
            "derivedText",
            "declaredDigest",
            "structuredValue",
        }
        or any(
            isinstance(count, bool)
            or not isinstance(count, int)
            or count < 0
            for count in content_surface_counts.values()
        )
    ):
        raise ValueError("Formal artifact holdout surface counts are invalid")
    if (
        holdout_compliance.get("schemaVersion") != 1
        or isinstance(holdout_compliance.get("overlapCount"), bool)
        or holdout_compliance.get("overlapCount") != 0
        or isinstance(holdout_compliance.get("holdoutEntryCount"), bool)
        or not isinstance(holdout_compliance.get("holdoutEntryCount"), int)
        or holdout_compliance["holdoutEntryCount"] <= 0
        or isinstance(
            holdout_compliance.get("holdoutReleaseGeneration"),
            bool,
        )
        or not isinstance(
            holdout_compliance.get("holdoutReleaseGeneration"),
            int,
        )
        or holdout_compliance["holdoutReleaseGeneration"] <= 0
    ):
        raise ValueError("Formal artifact holdout compliance is not release-safe")
    compliance_without_fingerprint = dict(holdout_compliance)
    compliance_fingerprint = compliance_without_fingerprint.pop(
        "complianceFingerprint",
        None,
    )
    if compliance_fingerprint != canonical_fingerprint(
        "formal-holdout-compliance-report-v1",
        compliance_without_fingerprint,
    ):
        raise ValueError("Formal artifact holdout compliance was modified")

    coverage = artifact.get("coverage")
    if not isinstance(coverage, Mapping):
        raise ValueError("Formal artifact coverage summary is missing")
    if set(coverage.get("subjects", [])) != set(REQUIRED_SUBJECTS):
        raise ValueError("Formal artifact must cover all nine subjects")
    proof = artifact.get("coverageProofV2")
    if not isinstance(proof, Mapping):
        raise ValueError("Formal artifact lacks coverage proof v2")
    if proof.get("schemaVersion") != FORMAL_COVERAGE_PROOF_SCHEMA_VERSION:
        raise ValueError("Formal artifact coverage proof is not schema v2")
    if proof.get("coverageLedgerId") != coverage.get("ledgerId"):
        raise ValueError("Coverage proof references another ledger")
    if proof.get("targetBaselineId") != coverage.get("targetBaselineId"):
        raise ValueError("Coverage proof references another target baseline")
    for key in (
        "coverageLedgerFingerprint",
        "humanReviewSummaryFingerprint",
        "sourceLicenseReviewSummaryFingerprint",
        "proofFingerprint",
    ):
        value = proof.get(key)
        if not isinstance(value, str) or not _SHA256.fullmatch(value):
            raise ValueError(f"Coverage proof {key} is invalid")
    expected_subjects = derive_runtime_coverage_subjects(artifact)
    actual_subjects = proof.get("subjects")
    if actual_subjects != expected_subjects:
        raise ValueError("Coverage proof does not match formal artifact content")
    if proof["proofFingerprint"] != coverage_proof_fingerprint(proof):
        raise ValueError("Coverage proof fingerprint was modified")


def build_coverage_proof_v2(
    *,
    coverage_ledger: Mapping[str, Any],
    pack_review: Mapping[str, Any],
    artifact: Mapping[str, Any],
) -> dict[str, Any]:
    """Build a complete proof; fail rather than bless a placeholder or unmapped corpus."""

    subject_proofs = derive_runtime_coverage_subjects(artifact)

    human_review_summary = canonical_fingerprint(
        "formal-human-review-summary-v2",
        {
            "packReviewRecordId": pack_review["reviewRecordId"],
            "packReviewedAtEpochMillis": pack_review["reviewedAtEpochMillis"],
            "inputFingerprints": pack_review["inputFingerprints"],
            "holdoutCompliance": artifact["governance"][
                "holdoutCompliance"
            ],
            "nodes": [
                (
                    row["knowledgeNodeId"],
                    row["reviewRecordId"],
                    row["reviewedAtEpochMillis"],
                )
                for row in artifact["nodes"]
            ],
            "nodeSourceBindings": [
                (
                    row["knowledgeNodeId"],
                    row["sourceId"],
                    row["sourceLocator"],
                    row["reviewRecordId"],
                    row["reviewedAtEpochMillis"],
                )
                for row in artifact["nodeSourceBindings"]
            ],
            "relations": [
                (
                    row["relationId"],
                    row["reviewRecordId"],
                    row["reviewedAtEpochMillis"],
                )
                for row in artifact["relations"]
            ],
            "materials": [
                (
                    row["materialId"],
                    row["reviewRecordId"],
                    row["reviewedAtEpochMillis"],
                )
                for row in artifact["teachingMaterials"]
            ],
        },
    )
    source_license_summary = canonical_fingerprint(
        "formal-source-license-review-summary-v2",
        [
            {
                key: source.get(key)
                for key in (
                    "sourceId",
                    "registerSourceId",
                    "sourceType",
                    "licenseStatus",
                    "contentUsePolicy",
                    "contentFingerprint",
                    "licenseExpression",
                    "licenseUri",
                    "attributionText",
                    "registerRecordFingerprint",
                    "reviewRecordId",
                    "reviewedAtEpochMillis",
                )
            }
            for source in sorted(
                artifact["sources"],
                key=lambda row: row["sourceId"],
            )
        ],
    )
    reviewed_times = [
        int(subject["reviewedAtEpochMillis"])
        for subject in coverage_ledger["subjects"]
    ]
    proof: dict[str, Any] = {
        "schemaVersion": FORMAL_COVERAGE_PROOF_SCHEMA_VERSION,
        "coverageLedgerId": coverage_ledger["ledgerId"],
        "targetBaselineId": coverage_ledger["targetBaselineId"],
        "coverageLedgerFingerprint": artifact_fingerprint(coverage_ledger),
        "coverageReviewRecordId": pack_review["reviewRecordId"],
        "coverageReviewedAtEpochMillis": max(reviewed_times),
        "humanReviewSummaryFingerprint": human_review_summary,
        "sourceLicenseReviewSummaryFingerprint": source_license_summary,
        "subjects": subject_proofs,
    }
    proof["proofFingerprint"] = coverage_proof_fingerprint(proof)
    return proof
