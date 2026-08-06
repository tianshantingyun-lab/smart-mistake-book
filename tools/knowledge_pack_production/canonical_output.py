"""Normalize reviewed sources, nodes, bindings, and teaching materials."""

from __future__ import annotations

import hashlib
from collections import defaultdict
from typing import Any, Mapping, Sequence

from .input_hashes import artifact_fingerprint, canonical_fingerprint
from .schema import (
    ALLOWED_CONTENT_ORIGINS,
    ALLOWED_CONTENT_USE_POLICIES,
    ALLOWED_LICENSE_STATUSES,
    ALLOWED_MATERIAL_ROLES,
    ALLOWED_MATERIAL_TYPES,
    ALLOWED_NODE_KINDS,
    ALLOWED_SOURCE_TYPES,
    EXAMPLE_MATERIAL_TYPES,
    MATERIAL_BINDING_KEYS,
    MATERIAL_FORM_REQUIREMENT,
    MATERIAL_KEYS,
    NODE_BINDING_KEYS,
    NODE_KEYS,
    REQUIRED_SUBJECTS,
    REQUIRED_SUBJECT_SET,
    SOURCE_REFERENCE_KEYS,
    SOURCE_SHA256_PATTERN,
    CoverageIndex,
    InventoryIndex,
    TeachingDecisionIndex,
    normalized_term,
    optional_text,
    require_exact_keys,
    require_https_uri,
    require_id,
    require_list,
    require_object,
    require_positive_int,
    require_review_record,
    require_string_list,
    require_text,
)


def expected_node_identity(
    taxonomy_version: str,
    coverage_key: str,
) -> tuple[str, str]:
    parts = coverage_key.split("/")
    if len(parts) not in {2, 3} or parts[0] not in REQUIRED_SUBJECT_SET:
        raise ValueError(f"Invalid coverage key: {coverage_key}")
    subject_key = parts[0].lower()
    if len(parts) == 2:
        suffix = f"{subject_key}:topic:{parts[1]}"
    else:
        suffix = f"{subject_key}:atomic:{parts[2]}"
    return f"kb:{taxonomy_version}:{suffix}", f"{taxonomy_version}:{suffix}"


def expected_material_id(stable_code: str) -> str:
    digest = hashlib.sha256(stable_code.encode("utf-8")).hexdigest()
    return f"kb-material:{digest[:40]}"


def build_sources(
    raw_sources: Any,
    register_sources: Mapping[str, dict[str, Any]],
    pack_reviewed_at: int,
) -> tuple[list[dict[str, Any]], dict[str, dict[str, Any]]]:
    output: list[dict[str, Any]] = []
    context: dict[str, dict[str, Any]] = {}
    fingerprints: set[str] = set()
    for raw in require_list(raw_sources, "formal pack sources"):
        reference = require_object(raw, "formal pack source reference")
        require_exact_keys(
            reference,
            SOURCE_REFERENCE_KEYS,
            "formal pack source reference",
        )
        source_id = require_id(reference["sourceId"], "formal source id")
        if source_id in context:
            raise ValueError(f"Duplicate formal source id: {source_id}")
        register_id = require_id(
            reference["registerSourceId"],
            "register source reference id",
        )
        source = register_sources.get(register_id)
        if source is None:
            raise ValueError(f"Unknown source-register reference: {register_id}")
        subject = str(reference["subject"])
        if subject not in REQUIRED_SUBJECT_SET or subject not in source.get(
            "subjects",
            [],
        ):
            raise ValueError(f"Formal source {source_id} has an invalid subject")
        source_subjects = list(source.get("subjects", []))
        expected_source_id = (
            register_id
            if source_subjects == [subject]
            else f"{register_id}:{subject.lower()}"
        )
        if source_id != expected_source_id:
            raise ValueError(
                f"Formal source id must be stable: expected {expected_source_id}"
            )
        if source.get("acquisitionState") != "ACQUIRED_REVIEWED":
            raise ValueError(f"Source {register_id} is not ACQUIRED_REVIEWED")
        source_reviewed_at = require_positive_int(
            source.get("reviewedAtEpochMillis"),
            f"source {register_id} register review time",
        )
        reviewed_at = require_positive_int(
            reference["reviewedAtEpochMillis"],
            f"source {source_id} pack review time",
        )
        if reviewed_at < source_reviewed_at or reviewed_at > pack_reviewed_at:
            raise ValueError(
                f"Source {source_id} review time is outside its reviewed evidence"
            )
        review_record_id = require_review_record(
            reference["reviewRecordId"],
            f"source {source_id} review record id",
        )
        content_fingerprint = str(source.get("contentFingerprint", ""))
        if not SOURCE_SHA256_PATTERN.fullmatch(content_fingerprint):
            raise ValueError(f"Source {register_id} lacks a valid SHA-256")
        normalized_fingerprint = content_fingerprint.lower()
        if normalized_fingerprint in fingerprints:
            raise ValueError(
                "Formal pack sources have duplicate content fingerprints; "
                "multi-subject sources require independently reviewed records"
            )
        fingerprints.add(normalized_fingerprint)
        license_status = str(source.get("licenseStatus", ""))
        content_use_policy = str(source.get("contentUsePolicy", ""))
        if license_status not in ALLOWED_LICENSE_STATUSES:
            raise ValueError(f"Source {register_id} has an unsupported license")
        if content_use_policy not in ALLOWED_CONTENT_USE_POLICIES:
            raise ValueError(f"Source {register_id} has an unsupported use policy")
        if (
            license_status == "REFERENCE_ONLY"
            and content_use_policy != "REVIEWED_SYNTHESIS_ONLY"
        ):
            raise ValueError(
                f"Reference-only source {register_id} must use reviewed synthesis"
            )
        license_expression = source.get("licenseExpression")
        license_uri = source.get("licenseUri")
        attribution = source.get("attributionText")
        if (
            license_status == "LICENSED"
            and content_use_policy != "REVIEWED_SYNTHESIS_ONLY"
        ):
            require_text(
                license_expression,
                f"source {register_id} license expression",
            )
            require_https_uri(
                license_uri,
                f"source {register_id} license URI",
            )
            require_text(attribution, f"source {register_id} attribution")
        source_uri = source.get("documentUri") or source.get("discoveryUri")
        require_https_uri(source_uri, f"source {register_id} URI")
        source_locator = require_text(
            source.get("sourceLocator"),
            f"source {register_id} locator",
            maximum=4_096,
        )
        source_type = derive_source_type(source)
        if source_type not in ALLOWED_SOURCE_TYPES:
            raise ValueError(f"Source {register_id} has an invalid source type")
        row = {
            "sourceId": source_id,
            "registerSourceId": register_id,
            "subject": subject,
            "sourceType": source_type,
            "title": require_text(source.get("title"), f"source {register_id} title"),
            "publisher": optional_text(source.get("publisher")),
            "edition": optional_text(source.get("baselineId")),
            "sourceUri": str(source_uri),
            "sourceRecordLocator": source_locator,
            "licenseStatus": license_status,
            "contentUsePolicy": content_use_policy,
            "contentFingerprint": normalized_fingerprint,
            "licenseExpression": optional_text(license_expression),
            "licenseUri": optional_text(license_uri),
            "attributionText": optional_text(attribution),
            "registerRecordFingerprint": artifact_fingerprint(source),
            "reviewRecordId": review_record_id,
            "reviewedAtEpochMillis": reviewed_at,
        }
        output.append(row)
        context[source_id] = {"row": row, "register": source}
    if not output:
        raise ValueError("Formal pack must contain reviewed sources")
    return sorted(output, key=lambda row: row["sourceId"]), context


def validate_nodes(
    raw_nodes: Any,
    coverage: CoverageIndex,
    taxonomy_version: str,
    pack_reviewed_at: int,
) -> tuple[
    list[dict[str, Any]],
    dict[str, dict[str, Any]],
    dict[str, dict[str, Any]],
]:
    nodes_by_id: dict[str, dict[str, Any]] = {}
    nodes_by_coverage_key: dict[str, dict[str, Any]] = {}
    stable_codes: set[str] = set()
    terms_by_subject: dict[str, dict[str, str]] = defaultdict(dict)
    output: list[dict[str, Any]] = []
    for raw in require_list(raw_nodes, "formal knowledge nodes"):
        node = require_object(raw, "formal knowledge node")
        require_exact_keys(node, NODE_KEYS, "formal knowledge node")
        coverage_key = require_text(
            node["coverageKey"],
            "knowledge-node coverage key",
            maximum=400,
        )
        if coverage_key not in coverage.expected_node_keys:
            raise ValueError(f"Knowledge node has an unknown coverage key: {coverage_key}")
        if coverage_key in nodes_by_coverage_key:
            raise ValueError(f"Duplicate knowledge-node coverage key: {coverage_key}")
        subject = str(node["subject"])
        if subject != coverage_key.split("/", 1)[0]:
            raise ValueError("Knowledge-node subject does not match its coverage key")
        expected_id, expected_stable_code = expected_node_identity(
            taxonomy_version,
            coverage_key,
        )
        node_id = require_id(node["knowledgeNodeId"], "knowledge-node id")
        stable_code = require_id(node["stableCode"], "knowledge-node stable code")
        if node_id != expected_id or stable_code != expected_stable_code:
            raise ValueError(
                f"Knowledge node {coverage_key} does not use its stable identity"
            )
        if node_id in nodes_by_id:
            raise ValueError(f"Duplicate knowledge-node id: {node_id}")
        if stable_code in stable_codes:
            raise ValueError(f"Duplicate knowledge-node stable code: {stable_code}")
        stable_codes.add(stable_code)
        is_topic = coverage_key in coverage.modules
        expected_name = (
            coverage.modules[coverage_key]["name"]
            if is_topic
            else coverage.points[coverage_key]["name"]
        )
        display_name = require_text(
            node["displayName"],
            f"knowledge node {node_id} display name",
        )
        if display_name != expected_name:
            raise ValueError(
                f"Knowledge node {coverage_key} does not match reviewed ledger name"
            )
        canonical_name = require_text(
            node["canonicalName"],
            f"knowledge node {node_id} canonical name",
        )
        kind = str(node["kind"])
        granularity = str(node["granularity"])
        verification = str(node["verificationStatus"])
        expected_parent: str | None
        if is_topic:
            if kind != "TOPIC" or granularity != "TOPIC":
                raise ValueError("Coverage modules must compile as TOPIC nodes")
            if verification != "CURATED":
                raise ValueError("Topic nodes must be CURATED")
            expected_parent = None
        else:
            if kind not in ALLOWED_NODE_KINDS - {"TOPIC"} or granularity != "ATOMIC":
                raise ValueError("Coverage points must compile as non-topic ATOMIC nodes")
            if verification != "SOURCE_GROUNDED":
                raise ValueError("Atomic nodes must be SOURCE_GROUNDED")
            parent_key = coverage_key.rsplit("/", 1)[0]
            expected_parent = expected_node_identity(taxonomy_version, parent_key)[0]
        if node["parentKnowledgeNodeId"] != expected_parent:
            raise ValueError(
                f"Knowledge node {coverage_key} has the wrong reviewed parent"
            )
        aliases = require_string_list(
            node["aliases"],
            f"knowledge node {node_id} aliases",
        )
        if len(aliases) > 12 or len(aliases) != len(set(aliases)):
            raise ValueError(f"Knowledge node {node_id} aliases are invalid")
        aliases = sorted(aliases)
        if canonical_name in aliases:
            raise ValueError(
                f"Knowledge node {node_id} aliases repeat its canonical name"
            )
        for term in (canonical_name, *aliases):
            normalized = normalized_term(term)
            owner = terms_by_subject[subject].get(normalized)
            if owner is not None and owner != node_id:
                raise ValueError(
                    f"Knowledge alias/name conflict in {subject}: {term}"
                )
            terms_by_subject[subject][normalized] = node_id
        boundary = node["boundaryMarkdown"]
        if is_topic:
            if boundary is not None:
                boundary = require_text(
                    boundary,
                    f"topic node {node_id} boundary",
                    maximum=16_384,
                )
        else:
            boundary = require_text(
                boundary,
                f"atomic node {node_id} boundary",
                maximum=16_384,
            )
        review_record_id = require_review_record(
            node["reviewRecordId"],
            f"knowledge node {node_id} review record id",
        )
        reviewed_at = require_positive_int(
            node["reviewedAtEpochMillis"],
            f"knowledge node {node_id} review time",
        )
        ledger_reviewed_at = require_positive_int(
            coverage.subject_entries[subject].get("reviewedAtEpochMillis"),
            f"coverage subject {subject} review time",
        )
        if reviewed_at < ledger_reviewed_at or reviewed_at > pack_reviewed_at:
            raise ValueError(
                "Knowledge-node review is outside its reviewed ledger evidence"
            )
        row = {
            "coverageKey": coverage_key,
            "knowledgeNodeId": node_id,
            "stableCode": stable_code,
            "subject": subject,
            "displayName": display_name,
            "canonicalName": canonical_name,
            "kind": kind,
            "granularity": granularity,
            "aliases": aliases,
            "boundaryMarkdown": boundary,
            "verificationStatus": verification,
            "parentKnowledgeNodeId": expected_parent,
            "reviewRecordId": review_record_id,
            "reviewedAtEpochMillis": reviewed_at,
        }
        output.append(row)
        nodes_by_id[node_id] = row
        nodes_by_coverage_key[coverage_key] = row
    missing = coverage.expected_node_keys - nodes_by_coverage_key.keys()
    extra = nodes_by_coverage_key.keys() - coverage.expected_node_keys
    if missing or extra:
        raise ValueError(
            "Formal pack nodes must exactly cover every reviewed ledger module "
            f"and point; missing={len(missing)}, extra={len(extra)}"
        )
    return (
        sorted(output, key=lambda row: row["knowledgeNodeId"]),
        nodes_by_id,
        nodes_by_coverage_key,
    )


def validate_node_bindings(
    raw_bindings: Any,
    coverage: CoverageIndex,
    nodes_by_id: Mapping[str, dict[str, Any]],
    nodes_by_coverage_key: Mapping[str, dict[str, Any]],
    source_context: Mapping[str, dict[str, Any]],
    pack_reviewed_at: int,
) -> list[dict[str, Any]]:
    del nodes_by_coverage_key
    output: list[dict[str, Any]] = []
    seen: set[tuple[str, str, str]] = set()
    bound_nodes: set[str] = set()
    exact_curriculum_grounding: set[str] = set()
    for raw in require_list(raw_bindings, "node-source bindings"):
        binding = require_object(raw, "node-source binding")
        require_exact_keys(binding, NODE_BINDING_KEYS, "node-source binding")
        node_id = require_id(binding["knowledgeNodeId"], "binding node id")
        source_id = require_id(binding["sourceId"], "binding source id")
        node = nodes_by_id.get(node_id)
        source_info = source_context.get(source_id)
        if node is None or source_info is None:
            raise ValueError("Node-source binding references a missing row")
        source = source_info["row"]
        if node["subject"] != source["subject"]:
            raise ValueError("Node-source binding crosses subjects")
        locator = require_text(
            binding["sourceLocator"],
            "node-source binding locator",
            maximum=2_000,
        )
        key = (node_id, source_id, locator)
        if key in seen:
            raise ValueError("Duplicate node-source binding")
        seen.add(key)
        reviewed_at = require_positive_int(
            binding["reviewedAtEpochMillis"],
            "node-source binding review time",
        )
        if (
            reviewed_at < node["reviewedAtEpochMillis"]
            or reviewed_at < source["reviewedAtEpochMillis"]
            or reviewed_at > pack_reviewed_at
        ):
            raise ValueError(
                "Node-source binding review is outside its reviewed evidence"
            )
        output.append(
            {
                "knowledgeNodeId": node_id,
                "sourceId": source_id,
                "sourceLocator": locator,
                "derivationNote": require_text(
                    binding["derivationNote"],
                    "node-source binding derivation note",
                ),
                "reviewRecordId": require_review_record(
                    binding["reviewRecordId"],
                    "node-source binding review record id",
                ),
                "reviewedAtEpochMillis": reviewed_at,
            }
        )
        bound_nodes.add(node_id)
        coverage_key = node["coverageKey"]
        subject = node["subject"]
        expected_register_source = str(
            coverage.subject_entries[subject]["curriculumSourceId"]
        )
        expected_locator = (
            coverage.modules[coverage_key]["sourceLocator"]
            if coverage_key in coverage.modules
            else coverage.points[coverage_key]["sourceLocator"]
        )
        if (
            source["registerSourceId"] == expected_register_source
            and locator == expected_locator
        ):
            exact_curriculum_grounding.add(node_id)
    if bound_nodes != nodes_by_id.keys():
        raise ValueError("Every formal knowledge node needs a reviewed source binding")
    if exact_curriculum_grounding != nodes_by_id.keys():
        raise ValueError(
            "Every formal knowledge node must bind its exact reviewed curriculum locator"
        )
    return sorted(
        output,
        key=lambda row: (
            row["knowledgeNodeId"],
            row["sourceId"],
            row["sourceLocator"],
        ),
    )


def validate_materials(
    raw_materials: Any,
    source_context: Mapping[str, dict[str, Any]],
    inventory: InventoryIndex,
    decisions: TeachingDecisionIndex,
    pack_reviewed_at: int,
) -> tuple[list[dict[str, Any]], dict[str, dict[str, Any]]]:
    output: list[dict[str, Any]] = []
    materials_by_id: dict[str, dict[str, Any]] = {}
    stable_codes: set[str] = set()
    fingerprints: set[str] = set()
    inventory_sources = {
        str(candidate["sourceId"]) for candidate in inventory.candidates.values()
    }
    for raw in require_list(raw_materials, "teaching materials"):
        material = require_object(raw, "teaching material")
        require_exact_keys(material, MATERIAL_KEYS, "teaching material")
        stable_code = require_id(
            material["stableCode"],
            "teaching material stable code",
        )
        material_id = require_id(material["materialId"], "teaching material id")
        if material_id != expected_material_id(stable_code):
            raise ValueError("Teaching material id does not match its stable code")
        if material_id in materials_by_id or stable_code in stable_codes:
            raise ValueError("Duplicate teaching material id or stable code")
        stable_codes.add(stable_code)
        source_id = require_id(material["sourceId"], "teaching material source id")
        source_info = source_context.get(source_id)
        if source_info is None:
            raise ValueError("Teaching material references a missing source")
        source_purposes = set(source_info["register"].get("purposes", []))
        if "TEACHING_REFERENCE" not in source_purposes:
            raise ValueError(
                "Teaching materials require a reviewed teaching-reference source"
            )
        subject = str(material["subject"])
        if subject != source_info["row"]["subject"]:
            raise ValueError("Teaching material source crosses subjects")
        material_type = str(material["materialType"])
        if material_type not in ALLOWED_MATERIAL_TYPES:
            raise ValueError(f"Unsupported teaching material type: {material_type}")
        if (
            material_type == "METHOD_MODEL"
            and "METHOD_REFERENCE" not in source_purposes
        ):
            raise ValueError(
                "Method models require a reviewed method-reference source"
            )
        if (
            material_type in EXAMPLE_MATERIAL_TYPES
            and "WORKED_EXAMPLE_REFERENCE" not in source_purposes
        ):
            raise ValueError(
                "Worked examples require a reviewed worked-example source"
            )
        if material["derivationKind"] != "REVIEWED_SYNTHESIS":
            raise ValueError(
                "Formal teaching materials must be independently reviewed synthesis"
            )
        content_origin = str(material["contentOrigin"])
        if content_origin not in ALLOWED_CONTENT_ORIGINS:
            raise ValueError("Teaching material has an unsafe content origin")
        if (
            material_type in EXAMPLE_MATERIAL_TYPES
            and content_origin != "SELF_AUTHORED_ABSTRACT_EXAMPLE"
        ):
            raise ValueError(
                "Worked examples and solutions must be self-authored abstract examples"
            )
        if (
            material["originalQuestionIncluded"] is not False
            or material["originalAnswerIncluded"] is not False
        ):
            raise ValueError(
                "Third-party original question and answer text is prohibited"
            )
        source_locator = require_text(
            material["sourceLocator"],
            "teaching material source locator",
            maximum=2_000,
        )
        candidate_ids = sorted(
            require_string_list(
                material["inventoryCandidateIds"],
                "teaching material inventory candidate ids",
            )
        )
        register_source_id = source_info["row"]["registerSourceId"]
        if register_source_id in inventory_sources and not candidate_ids:
            raise ValueError(
                "Inventory-backed teaching material needs approved locator evidence"
            )
        approved_candidates: list[dict[str, Any]] = []
        for candidate_id in candidate_ids:
            candidate = inventory.candidates.get(candidate_id)
            decision = decisions.decisions.get(candidate_id)
            if candidate is None or decision is None:
                raise ValueError(
                    "Teaching material references unknown review inventory evidence"
                )
            if candidate["sourceId"] != register_source_id:
                raise ValueError("Teaching material candidate uses another source")
            if decision["decision"] != "APPROVE_FOR_HUMAN_SYNTHESIS":
                raise ValueError("Rejected teaching candidates cannot enter a pack")
            if decision["resolvedSubject"] != subject:
                raise ValueError("Teaching candidate subject was not human-resolved")
            required_form = MATERIAL_FORM_REQUIREMENT.get(material_type)
            if (
                required_form is not None
                and required_form not in decision["approvedTeachingForms"]
            ):
                raise ValueError(
                    "Teaching material form was not approved by human review"
                )
            approved_candidates.append(candidate)
        title = require_text(material["title"], "teaching material title")
        summary = require_text(
            material["summaryMarkdown"],
            "teaching material summary",
            maximum=16_384,
        )
        applicability = require_text(
            material["applicabilityMarkdown"],
            "teaching material applicability",
            maximum=16_384,
        )
        content = require_text(
            material["contentMarkdown"],
            "teaching material content",
            maximum=16_384,
        )
        boundary = require_text(
            material["boundaryMarkdown"],
            "teaching material boundary",
            maximum=16_384,
        )
        combined = "\n".join((summary, applicability, content, boundary))
        for candidate in approved_candidates:
            source_title = str(candidate.get("sectionTitle", "")).strip()
            if (
                len(source_title) >= 80
                and source_title.casefold() in combined.casefold()
            ):
                raise ValueError(
                    "Teaching material appears to copy a third-party source section"
                )
        review_record_id = require_review_record(
            material["reviewRecordId"],
            "teaching material review record id",
        )
        reviewed_at = require_positive_int(
            material["reviewedAtEpochMillis"],
            "teaching material review time",
        )
        if (
            reviewed_at < source_info["row"]["reviewedAtEpochMillis"]
            or reviewed_at < decisions.reviewed_at
            or reviewed_at > pack_reviewed_at
        ):
            raise ValueError(
                "Teaching material review is outside its reviewed source/decision evidence"
            )
        material_fingerprint = canonical_fingerprint(
            "reviewed-teaching-material-v1",
            {
                "materialId": material_id,
                "stableCode": stable_code,
                "subject": subject,
                "materialType": material_type,
                "title": title,
                "summaryMarkdown": summary,
                "applicabilityMarkdown": applicability,
                "contentMarkdown": content,
                "boundaryMarkdown": boundary,
                "contentOrigin": content_origin,
                "sourceId": source_id,
                "sourceLocator": source_locator,
                "inventoryCandidateIds": candidate_ids,
                "reviewRecordId": review_record_id,
                "reviewedAtEpochMillis": reviewed_at,
            },
        )
        if material_fingerprint in fingerprints:
            raise ValueError("Duplicate teaching material content fingerprint")
        fingerprints.add(material_fingerprint)
        row = {
            "materialId": material_id,
            "stableCode": stable_code,
            "subject": subject,
            "materialType": material_type,
            "title": title,
            "summaryMarkdown": summary,
            "applicabilityMarkdown": applicability,
            "contentMarkdown": content,
            "boundaryMarkdown": boundary,
            "derivationKind": "REVIEWED_SYNTHESIS",
            "contentOrigin": content_origin,
            "sourceId": source_id,
            "sourceLocator": source_locator,
            "inventoryCandidateIds": candidate_ids,
            "originalQuestionIncluded": False,
            "originalAnswerIncluded": False,
            "contentFingerprint": material_fingerprint,
            "reviewRecordId": review_record_id,
            "reviewedAtEpochMillis": reviewed_at,
        }
        output.append(row)
        materials_by_id[material_id] = row
    if not output:
        raise ValueError("Formal pack must contain reviewed teaching materials")
    return sorted(output, key=lambda row: row["materialId"]), materials_by_id


def validate_material_bindings(
    raw_bindings: Any,
    materials_by_id: Mapping[str, dict[str, Any]],
    nodes_by_id: Mapping[str, dict[str, Any]],
) -> list[dict[str, Any]]:
    output: list[dict[str, Any]] = []
    seen: set[tuple[str, str]] = set()
    bound_materials: set[str] = set()
    for raw in require_list(raw_bindings, "teaching material bindings"):
        binding = require_object(raw, "teaching material binding")
        require_exact_keys(
            binding,
            MATERIAL_BINDING_KEYS,
            "teaching material binding",
        )
        material_id = require_id(binding["materialId"], "material binding id")
        node_id = require_id(
            binding["knowledgeNodeId"],
            "material binding node id",
        )
        material = materials_by_id.get(material_id)
        node = nodes_by_id.get(node_id)
        if material is None or node is None:
            raise ValueError("Teaching material binding references a missing row")
        if material["subject"] != node["subject"]:
            raise ValueError("Teaching material binding crosses subjects")
        key = (material_id, node_id)
        if key in seen:
            raise ValueError("Duplicate teaching material binding")
        seen.add(key)
        role = str(binding["role"])
        if role not in ALLOWED_MATERIAL_ROLES:
            raise ValueError(f"Unsupported teaching material role: {role}")
        output.append(
            {
                "materialId": material_id,
                "knowledgeNodeId": node_id,
                "role": role,
            }
        )
        bound_materials.add(material_id)
    if bound_materials != materials_by_id.keys():
        raise ValueError(
            "Every teaching material must bind at least one reviewed knowledge node"
        )
    return sorted(
        output,
        key=lambda row: (row["materialId"], row["knowledgeNodeId"]),
    )


def validate_teaching_coverage(
    source_register: dict[str, Any],
    materials: Sequence[dict[str, Any]],
    source_context: Mapping[str, dict[str, Any]],
) -> None:
    requirements = require_object(
        source_register.get("sourceRequirements"),
        "source teaching requirements",
    )
    minimum = require_positive_int(
        requirements.get("minimumReviewedTeachingReferencesPerSubject"),
        "minimum reviewed teaching references",
    )
    by_subject: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for material in materials:
        by_subject[material["subject"]].append(material)
    missing: list[str] = []
    for subject in REQUIRED_SUBJECTS:
        subject_materials = by_subject[subject]
        independence_groups = {
            str(
                source_context[material["sourceId"]]["register"].get(
                    "independenceGroup",
                    material["sourceId"],
                )
            )
            for material in subject_materials
        }
        has_method = any(
            material["materialType"] == "METHOD_MODEL"
            for material in subject_materials
        )
        has_example = any(
            material["materialType"] in EXAMPLE_MATERIAL_TYPES
            for material in subject_materials
        )
        if (
            len(independence_groups) < minimum
            or (
                requirements.get("requireReviewedMethodReferencePerSubject") is True
                and not has_method
            )
            or (
                requirements.get("requireReviewedWorkedExampleReferencePerSubject")
                is True
                and not has_example
            )
        ):
            missing.append(subject)
    if missing:
        raise ValueError(
            "Formal pack lacks reviewed method/worked-example coverage for: "
            + ", ".join(missing)
        )


def derive_source_type(source: Mapping[str, Any]) -> str:
    purposes = set(source.get("purposes", []))
    if "TEXTBOOK_EDITION_MAPPING" in purposes:
        return "TEXTBOOK"
    if (
        source.get("authorityLevel") == "OFFICIAL"
        and (
            "CURRENT_CURRICULUM_TEXT" in purposes
            or "COVERAGE_BASELINE" in purposes
        )
    ):
        return "OFFICIAL_CURRICULUM_STANDARD"
    if "TEACHING_REFERENCE" in purposes:
        return "AUTHORIZED_EDUCATION_MATERIAL"
    return "MANUAL_RESEARCH"
