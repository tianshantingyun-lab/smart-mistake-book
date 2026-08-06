"""Stable relation identity, conflict checks, and prerequisite graph validation."""

from __future__ import annotations

import hashlib
from collections import defaultdict
from typing import Any, Iterable, Mapping, Sequence

from .schema import (
    ALLOWED_RELATION_TYPES,
    RELATION_KEYS,
    SYMMETRIC_RELATION_TYPES,
    require_exact_keys,
    require_id,
    require_list,
    require_object,
    require_positive_int,
    require_review_record,
    require_text,
)


def expected_relation_id(
    subject: str,
    from_node_id: str,
    to_node_id: str,
    relation_type: str,
    source_id: str,
    source_locator: str,
) -> str:
    payload = "\u001f".join(
        (
            subject,
            from_node_id,
            to_node_id,
            relation_type,
            source_id,
            source_locator,
        )
    )
    digest = hashlib.sha256(payload.encode("utf-8")).hexdigest()
    return f"knowledge-relation:{digest[:40]}"


def validate_relations(
    raw_relations: Any,
    nodes_by_id: Mapping[str, dict[str, Any]],
    source_context: Mapping[str, dict[str, Any]],
    pack_reviewed_at: int,
    taxonomy_version: str,
) -> list[dict[str, Any]]:
    output: list[dict[str, Any]] = []
    relation_ids: set[str] = set()
    endpoint_keys: set[tuple[str, str, str]] = set()
    prerequisite_edges: list[tuple[str, str]] = []
    for raw in require_list(raw_relations, "knowledge relations"):
        relation = require_object(raw, "knowledge relation")
        require_exact_keys(relation, RELATION_KEYS, "knowledge relation")
        relation_type = str(relation["relationType"])
        if relation_type not in ALLOWED_RELATION_TYPES:
            raise ValueError(f"Unsupported knowledge relation: {relation_type}")
        from_id = require_id(
            relation["fromKnowledgeNodeId"],
            "relation source node id",
        )
        to_id = require_id(
            relation["toKnowledgeNodeId"],
            "relation target node id",
        )
        if from_id == to_id:
            raise ValueError("Knowledge relations cannot point to themselves")
        from_node = nodes_by_id.get(from_id)
        to_node = nodes_by_id.get(to_id)
        source_id = require_id(relation["sourceId"], "relation source id")
        source_info = source_context.get(source_id)
        if from_node is None or to_node is None or source_info is None:
            raise ValueError("Knowledge relation references a missing node or source")
        subject = str(relation["subject"])
        if (
            subject != from_node["subject"]
            or subject != to_node["subject"]
            or subject != source_info["row"]["subject"]
        ):
            raise ValueError("Knowledge relations cannot cross subjects")
        if (
            from_node["granularity"] != "ATOMIC"
            or to_node["granularity"] != "ATOMIC"
        ):
            raise ValueError("Knowledge relations require atomic reviewed nodes")
        if relation_type in SYMMETRIC_RELATION_TYPES and from_id > to_id:
            raise ValueError(
                f"{relation_type} endpoints must use stable lexical order"
            )
        endpoint_key = (from_id, to_id, relation_type)
        if endpoint_key in endpoint_keys:
            raise ValueError("Duplicate knowledge relation endpoints")
        endpoint_keys.add(endpoint_key)
        locator = require_text(
            relation["sourceLocator"],
            "knowledge relation source locator",
            maximum=2_000,
        )
        expected_id = expected_relation_id(
            subject,
            from_id,
            to_id,
            relation_type,
            source_id,
            locator,
        )
        relation_id = require_id(relation["relationId"], "knowledge relation id")
        if relation_id != expected_id:
            raise ValueError(
                "Knowledge relation id does not match its immutable payload"
            )
        if relation_id in relation_ids:
            raise ValueError(f"Duplicate knowledge relation id: {relation_id}")
        relation_ids.add(relation_id)
        reviewed_at = require_positive_int(
            relation["reviewedAtEpochMillis"],
            "knowledge relation review time",
        )
        if (
            reviewed_at < from_node["reviewedAtEpochMillis"]
            or reviewed_at < to_node["reviewedAtEpochMillis"]
            or reviewed_at < source_info["row"]["reviewedAtEpochMillis"]
            or reviewed_at > pack_reviewed_at
        ):
            raise ValueError(
                "Knowledge relation review is outside its reviewed evidence"
            )
        output.append(
            {
                "relationId": relation_id,
                "subject": subject,
                "fromKnowledgeNodeId": from_id,
                "toKnowledgeNodeId": to_id,
                "relationType": relation_type,
                "taxonomyVersion": taxonomy_version,
                "sourceId": source_id,
                "sourceLocator": locator,
                "reviewRecordId": require_review_record(
                    relation["reviewRecordId"],
                    "knowledge relation review record id",
                ),
                "reviewedAtEpochMillis": reviewed_at,
            }
        )
        if relation_type == "PREREQUISITE_OF":
            prerequisite_edges.append((from_id, to_id))
    require_acyclic(nodes_by_id.keys(), prerequisite_edges)
    return sorted(output, key=lambda row: row["relationId"])


def require_acyclic(
    node_ids: Iterable[str],
    edges: Sequence[tuple[str, str]],
) -> None:
    if not edges:
        return
    outgoing: dict[str, list[str]] = defaultdict(list)
    incoming = {node_id: 0 for node_id in node_ids}
    for source, target in edges:
        outgoing[source].append(target)
        incoming[target] += 1
    ready = sorted(node_id for node_id, count in incoming.items() if count == 0)
    visited = 0
    while ready:
        current = ready.pop(0)
        visited += 1
        for target in sorted(outgoing[current]):
            incoming[target] -= 1
            if incoming[target] == 0:
                ready.append(target)
                ready.sort()
    if visited != len(incoming):
        raise ValueError("Knowledge prerequisite graph contains a cycle")
