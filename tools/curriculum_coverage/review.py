"""Build source-complete review evidence for mechanically ambiguous modules."""

from __future__ import annotations

import hashlib
from pathlib import Path
from typing import Any

from .artifacts import knowledge_base_boundary
from .extractor import load_pdf_lines, slice_module
from .manifest import validate_manifest
from .model import SourceLine, ensure_under_project, normalize_display, require_keys


CANDIDATE_KEYS = {
    "schemaVersion",
    "artifactId",
    "manifestId",
    "targetBaselineId",
    "mappingState",
    "subjects",
}
SUBJECT_KEYS = {"subject", "modules"}
MODULE_KEYS = {"slug", "warnings"}


def _validate_candidate_header(
    candidates: dict[str, Any],
    manifest: dict[str, Any],
) -> None:
    require_keys(candidates, CANDIDATE_KEYS, "coverage candidate artifact")
    if candidates["schemaVersion"] != 1:
        raise ValueError("Only coverage candidate schemaVersion 1 is supported")
    if candidates["artifactId"] != "curriculum-coverage-candidates-2025-v1":
        raise ValueError("Unexpected coverage candidate artifact")
    if candidates["manifestId"] != manifest["manifestId"]:
        raise ValueError("Coverage candidates do not reference the loaded manifest")
    if candidates["targetBaselineId"] != manifest["targetBaselineId"]:
        raise ValueError("Coverage candidates target a different curriculum baseline")
    if candidates["mappingState"] != "DRAFT_UNREVIEWED":
        raise ValueError("Warning evidence can only be built from unreviewed candidates")


def _module_warnings(
    module: Any,
    *,
    subject: str,
    seen_modules: set[str],
) -> tuple[str, list[str]]:
    if not isinstance(module, dict):
        raise ValueError(f"Coverage candidate modules must be objects: {subject}")
    require_keys(module, MODULE_KEYS, f"coverage candidate module {subject}")
    slug = str(module["slug"])
    if slug in seen_modules:
        raise ValueError(f"Duplicate candidate module: {subject}/{slug}")
    seen_modules.add(slug)
    raw_warnings = module["warnings"]
    if not isinstance(raw_warnings, list):
        raise ValueError(
            f"Coverage candidate warnings must be a list: {subject}/{slug}"
        )
    return slug, [str(item) for item in raw_warnings]


def _subject_warnings(
    subject_entry: Any,
    *,
    expected_subjects: set[str],
    seen_subjects: set[str],
) -> tuple[str, dict[str, list[str]]]:
    if not isinstance(subject_entry, dict):
        raise ValueError("Coverage candidate subject entries must be objects")
    require_keys(subject_entry, SUBJECT_KEYS, "coverage candidate subject")
    subject = str(subject_entry["subject"])
    if subject not in expected_subjects:
        raise ValueError(f"Unexpected candidate subject: {subject}")
    if subject in seen_subjects:
        raise ValueError(f"Duplicate candidate subject: {subject}")
    seen_subjects.add(subject)
    modules = subject_entry["modules"]
    if not isinstance(modules, list):
        raise ValueError(f"Coverage candidate modules must be a list: {subject}")
    seen_modules: set[str] = set()
    warning_pairs = [
        _module_warnings(
            module,
            subject=subject,
            seen_modules=seen_modules,
        )
        for module in modules
    ]
    return subject, {slug: warnings for slug, warnings in warning_pairs if warnings}


def _warning_index(
    candidates: dict[str, Any],
    manifest: dict[str, Any],
) -> dict[str, dict[str, list[str]]]:
    _validate_candidate_header(candidates, manifest)
    subjects = candidates["subjects"]
    if not isinstance(subjects, list):
        raise ValueError("Coverage candidate subjects must be a list")
    expected_subjects = {
        str(subject_entry["subject"]) for subject_entry in manifest["subjects"]
    }
    seen_subjects: set[str] = set()
    subject_pairs = [
        _subject_warnings(
            subject_entry,
            expected_subjects=expected_subjects,
            seen_subjects=seen_subjects,
        )
        for subject_entry in subjects
    ]
    if seen_subjects != expected_subjects:
        missing = ", ".join(sorted(expected_subjects - seen_subjects))
        raise ValueError(f"Coverage candidates are missing subjects: {missing}")
    return {
        subject: warnings for subject, warnings in subject_pairs if warnings
    }


def _source_lines(chunk: list[SourceLine]) -> list[dict[str, Any]]:
    return [{"page": line.page, "text": line.text} for line in chunk if line.text]


def _module_evidence(
    source_id: str,
    module: dict[str, Any],
    warnings: list[str],
    chunk: list[SourceLine],
) -> dict[str, Any]:
    source_lines = _source_lines(chunk)
    source_text = normalize_display(" ".join(line["text"] for line in source_lines))
    source_hash = hashlib.sha256(source_text.encode("utf-8")).hexdigest().upper()
    return {
        "slug": module["slug"],
        "name": module["name"],
        "requirementTypes": module["requirementTypes"],
        "sourcePages": module["pages"],
        "sourceLocator": (
            f"{source_id}，PDF 第 {module['pages'][0]}-{module['pages'][1]} 页，"
            f"{module['name']}"
        ),
        "extractionWarnings": warnings,
        "reviewState": "SOURCE_EVIDENCE_ONLY",
        "promotionAllowed": False,
        "sourceLineCount": len(source_lines),
        "sourceTextSha256": source_hash,
        "sourceText": source_text,
        "sourceLines": source_lines,
    }


def _subject_evidence(
    project_root: Path,
    pdf_directory: Path,
    subject_entry: dict[str, Any],
    subject_warnings: dict[str, list[str]],
    source_metadata: dict[str, Any],
) -> tuple[dict[str, Any], set[tuple[str, str]]]:
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
        source_metadata,
        subject_entry["coursePages"],
    )
    modules = []
    resolved_modules: set[tuple[str, str]] = set()
    for module in subject_entry["modules"]:
        slug = str(module["slug"])
        warnings = subject_warnings.get(slug)
        if warnings is None:
            continue
        modules.append(
            _module_evidence(
                source_id,
                module,
                warnings,
                slice_module(lines, module),
            )
        )
        resolved_modules.add((subject, slug))
    return (
        {
            "subject": subject,
            "sourceId": source_id,
            "sourceFile": subject_entry["pdfFile"],
            "pdfPageCount": page_count,
            "modules": modules,
        },
        resolved_modules,
    )


def _ensure_all_warning_modules_resolved(
    warning_index: dict[str, dict[str, list[str]]],
    resolved_modules: set[tuple[str, str]],
) -> None:
    expected_modules = {
        (subject, slug)
        for subject, modules in warning_index.items()
        for slug in modules
    }
    unresolved_modules = sorted(expected_modules - resolved_modules)
    if not unresolved_modules:
        return
    formatted = ", ".join(f"{subject}/{slug}" for subject, slug in unresolved_modules)
    raise ValueError(f"Warning modules are missing from the manifest: {formatted}")


def _evidence_artifact(
    manifest: dict[str, Any],
    candidates: dict[str, Any],
    evidence_subjects: list[dict[str, Any]],
) -> dict[str, Any]:
    return {
        "schemaVersion": 1,
        "artifactId": "curriculum-warning-source-evidence-2025-v1",
        "manifestId": manifest["manifestId"],
        "candidateArtifactId": candidates["artifactId"],
        "targetBaselineId": manifest["targetBaselineId"],
        "updatedAtEpochMillis": manifest["updatedAtEpochMillis"],
        "reviewBoundary": {
            "state": "SOURCE_EVIDENCE_ONLY",
            "humanReviewRequired": True,
            "formalLedgerMutationAllowed": False,
            "notes": [
                "本文件保存完整来源片段，供后续细化审校使用。",
                "来源证据本身不是细化知识点，也不能升级正式覆盖率。",
                "任何建议拆分都必须逐项引用本文件中的原文短语。",
            ],
        },
        "knowledgeBaseBoundary": knowledge_base_boundary(),
        "subjects": evidence_subjects,
    }


def build_warning_evidence(
    project_root: Path,
    pdf_directory: Path,
    manifest: dict[str, Any],
    register: dict[str, Any],
    candidates: dict[str, Any],
) -> tuple[dict[str, Any], dict[str, Any]]:
    sources_by_id = validate_manifest(manifest, register)
    warning_index = _warning_index(candidates, manifest)
    evidence_subjects: list[dict[str, Any]] = []
    resolved_modules: set[tuple[str, str]] = set()

    for subject_entry in manifest["subjects"]:
        subject = str(subject_entry["subject"])
        subject_warnings = warning_index.get(subject)
        if not subject_warnings:
            continue
        source_id = str(subject_entry["sourceId"])
        subject_evidence, subject_resolved = _subject_evidence(
            project_root,
            pdf_directory,
            subject_entry,
            subject_warnings,
            sources_by_id[source_id],
        )
        evidence_subjects.append(subject_evidence)
        resolved_modules.update(subject_resolved)

    _ensure_all_warning_modules_resolved(warning_index, resolved_modules)
    warning_count = sum(
        len(module["extractionWarnings"])
        for subject in evidence_subjects
        for module in subject["modules"]
    )
    module_count = sum(len(subject["modules"]) for subject in evidence_subjects)
    artifact = _evidence_artifact(manifest, candidates, evidence_subjects)
    summary = {
        "artifactId": artifact["artifactId"],
        "reviewState": artifact["reviewBoundary"]["state"],
        "subjectCount": len(evidence_subjects),
        "warningModuleCount": module_count,
        "warningCount": warning_count,
        "formalLedgerMutationAllowed": False,
    }
    return artifact, summary
