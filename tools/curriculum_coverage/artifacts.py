"""Build deterministic draft candidate and coverage-ledger artifacts."""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from typing import Any

from .extractor import (
    descriptive_summary,
    extract_numbered_items,
    load_pdf_lines,
    slice_module,
)
from .manifest import validate_manifest
from .model import PendingItem, SourceLine, ensure_under_project, normalize_display


@dataclass(frozen=True, slots=True)
class SubjectArtifacts:
    candidates: dict[str, Any]
    ledger: dict[str, Any]
    summary: dict[str, Any]


def display_name(text: str) -> str:
    normalized = normalize_display(text)
    if len(normalized) <= 220:
        return normalized
    boundary = max(
        normalized.rfind("。", 0, 220),
        normalized.rfind("；", 0, 220),
        normalized.rfind("，", 0, 220),
    )
    cutoff = boundary + 1 if boundary >= 80 else 219
    return normalized[:cutoff].rstrip() + "…"


def source_locator(
    source_id: str,
    page: int,
    module_name: str,
    raw_marker: str | None = None,
) -> str:
    suffix = f"，原始编号 {raw_marker}" if raw_marker else ""
    return (
        f"{source_id}，PDF 第 {page} 页，{module_name}{suffix}；"
        "DRAFT_UNREVIEWED 机械提取候选，尚未完成人工细化与审校"
    )


def _module_items(
    module: dict[str, Any],
    chunk: list[SourceLine],
) -> tuple[list[PendingItem], list[str]]:
    mode = str(module["mode"])
    items = (
        [descriptive_summary(chunk)]
        if mode == "DESCRIPTIVE"
        else extract_numbered_items(chunk, mode)
    )
    warnings: list[str] = []
    if not items:
        items = [descriptive_summary(chunk)]
        warnings.append("NO_NUMBERED_REQUIREMENTS_EXTRACTED")
    if any(item.hierarchy_kind == "SCOPE_SUMMARY" for item in items):
        warnings.append("SCOPE_SUMMARY_REQUIRES_MANUAL_ATOMIZATION")
    return items, sorted(set(warnings))


def _candidate_records(
    source_id: str,
    module: dict[str, Any],
    items: list[PendingItem],
) -> tuple[list[dict[str, Any]], list[dict[str, str]]]:
    candidates: list[dict[str, Any]] = []
    ledger_points: list[dict[str, str]] = []
    for ordinal, item in enumerate(items, start=1):
        full_text = normalize_display(item.parts[0])[:2000]
        slug = f"{module['slug']}-candidate-{ordinal:03d}"
        name = display_name(full_text)
        locator = source_locator(
            source_id,
            item.page,
            str(module["name"]),
            item.raw_marker,
        )
        candidates.append(
            {
                "slug": slug,
                "candidateKind": (
                    "SCOPE_SUMMARY"
                    if item.hierarchy_kind == "SCOPE_SUMMARY"
                    else "CURRICULUM_STATEMENT"
                ),
                "rawMarker": item.raw_marker,
                "sourcePage": item.page,
                "name": name,
                "text": full_text,
                "sourceLocator": locator,
            }
        )
        ledger_points.append(
            {
                "slug": slug,
                "name": name,
                "sourceLocator": locator,
            }
        )
    return candidates, ledger_points


def build_module_draft(
    source_id: str,
    module: dict[str, Any],
    chunk: list[SourceLine],
) -> tuple[dict[str, Any], dict[str, Any]]:
    items, warnings = _module_items(module, chunk)
    candidates, ledger_points = _candidate_records(source_id, module, items)
    module_locator = source_locator(
        source_id,
        int(module["pages"][0]),
        str(module["name"]),
    )
    candidate_module = {
        "slug": module["slug"],
        "name": module["name"],
        "requirementTypes": module["requirementTypes"],
        "sourcePages": module["pages"],
        "sourceLocator": module_locator,
        "extractionMode": module["mode"],
        "warnings": warnings,
        "candidateStatements": candidates,
    }
    ledger_module = {
        "slug": module["slug"],
        "name": module["name"],
        "requirementType": module["requirementTypes"][0],
        "courseStages": module["requirementTypes"],
        "sourceLocator": module_locator,
        "knowledgePoints": ledger_points,
    }
    return candidate_module, ledger_module


def _build_subject_artifacts(
    project_root: Path,
    pdf_directory: Path,
    subject_entry: dict[str, Any],
    source: dict[str, Any],
) -> SubjectArtifacts:
    subject = str(subject_entry["subject"])
    source_id = str(subject_entry["sourceId"])
    pdf_path = ensure_under_project(
        pdf_directory / str(subject_entry["pdfFile"]),
        project_root,
        f"{subject} PDF",
    )
    if not pdf_path.is_file():
        raise ValueError(f"Missing acquired curriculum PDF: {pdf_path}")
    lines, page_count = load_pdf_lines(
        pdf_path,
        source,
        subject_entry["coursePages"],
    )

    module_pairs = [
        build_module_draft(source_id, module, slice_module(lines, module))
        for module in subject_entry["modules"]
    ]
    candidate_modules = [pair[0] for pair in module_pairs]
    ledger_modules = [pair[1] for pair in module_pairs]
    candidates = [
        candidate
        for module in candidate_modules
        for candidate in module["candidateStatements"]
    ]
    summary_count = sum(
        candidate["candidateKind"] == "SCOPE_SUMMARY" for candidate in candidates
    )
    return SubjectArtifacts(
        candidates={
            "subject": subject,
            "sourceId": source_id,
            "sourceFile": subject_entry["pdfFile"],
            "sourceFingerprint": source["contentFingerprint"],
            "sourceBytes": source["contentLengthBytes"],
            "pdfPageCount": page_count,
            "coursePages": subject_entry["coursePages"],
            "mappingState": "DRAFT_UNREVIEWED",
            "modules": candidate_modules,
        },
        ledger={
            "subject": subject,
            "curriculumSourceId": source_id,
            "mappingState": "DRAFT_UNREVIEWED",
            "modules": ledger_modules,
        },
        summary={
            "subject": subject,
            "moduleCount": len(candidate_modules),
            "curriculumStatementCandidateCount": len(candidates) - summary_count,
            "scopeSummaryCandidateCount": summary_count,
            "warningCount": sum(
                len(module["warnings"]) for module in candidate_modules
            ),
        },
    )


def knowledge_base_boundary() -> dict[str, Any]:
    return {
        "identity": "KNOWLEDGE_AND_TEACHING_SUPPORT_NOT_QUESTION_BANK",
        "allowedTeachingSupport": [
            "METHOD_MODEL",
            "WORKED_EXAMPLE",
            "COMPLETE_SOLUTION",
            "DERIVATION",
        ],
        "forbiddenAuthorities": [
            "AUTONOMOUS_QUESTION_GENERATION",
            "ASSESSMENT_ITEM_GENERATION",
            "REVIEW_SCHEDULING",
        ],
        "notes": [
            "例题、完整解答和方法模型可以作为讲解与知识归类证据。",
            "这些材料不能被当作题库，也不能自行触发出题、测评或复习。",
            "本文件只包含课标范围候选，不代表细化知识点已经人工审校完成。",
        ],
    }


def _build_summary(
    manifest: dict[str, Any],
    candidate_artifact: dict[str, Any],
    subjects: list[dict[str, Any]],
) -> dict[str, Any]:
    return {
        "manifestId": manifest["manifestId"],
        "candidateArtifactId": candidate_artifact["artifactId"],
        "mappingState": "DRAFT_UNREVIEWED",
        "subjects": subjects,
        "totalModules": sum(item["moduleCount"] for item in subjects),
        "totalCurriculumStatementCandidates": sum(
            item["curriculumStatementCandidateCount"] for item in subjects
        ),
        "totalScopeSummaryCandidates": sum(
            item["scopeSummaryCandidateCount"] for item in subjects
        ),
        "totalWarnings": sum(item["warningCount"] for item in subjects),
    }


def build_outputs(
    project_root: Path,
    pdf_directory: Path,
    manifest: dict[str, Any],
    register: dict[str, Any],
) -> tuple[dict[str, Any], dict[str, Any], dict[str, Any]]:
    sources_by_id = validate_manifest(manifest, register)
    subjects = [
        _build_subject_artifacts(
            project_root,
            pdf_directory,
            entry,
            sources_by_id[str(entry["sourceId"])],
        )
        for entry in manifest["subjects"]
    ]
    candidate_artifact = {
        "schemaVersion": 1,
        "artifactId": "curriculum-coverage-candidates-2025-v1",
        "manifestId": manifest["manifestId"],
        "targetBaselineId": manifest["targetBaselineId"],
        "sourceRegisterId": manifest["sourceRegisterId"],
        "mappingState": "DRAFT_UNREVIEWED",
        "updatedAtEpochMillis": manifest["updatedAtEpochMillis"],
        "knowledgeBaseBoundary": knowledge_base_boundary(),
        "subjects": [subject.candidates for subject in subjects],
    }
    ledger = {
        "schemaVersion": 1,
        "ledgerId": "knowledge-coverage-ledger-2025-v1",
        "targetBaselineId": manifest["targetBaselineId"],
        "sourceRegisterId": manifest["sourceRegisterId"],
        "updatedAtEpochMillis": manifest["updatedAtEpochMillis"],
        "subjects": [subject.ledger for subject in subjects],
    }
    summary = _build_summary(
        manifest,
        candidate_artifact,
        [subject.summary for subject in subjects],
    )
    return candidate_artifact, ledger, summary
