"""Validation for the extraction manifest and its source register links."""

from __future__ import annotations

import re
from collections import Counter
from typing import Any

from .model import (
    ALLOWED_MODES,
    ALLOWED_REQUIREMENT_TYPES,
    ALLOWED_SUBJECTS,
    require_keys,
)


SLUG_PATTERN = re.compile(r"^[a-z0-9]+(?:-[a-z0-9]+)*$")
MANIFEST_KEYS = {
    "schemaVersion",
    "manifestId",
    "targetBaselineId",
    "sourceRegisterId",
    "updatedAtEpochMillis",
    "subjects",
}
SUBJECT_KEYS = {"subject", "sourceId", "pdfFile", "coursePages", "modules"}
MODULE_KEYS = {
    "slug",
    "name",
    "requirementTypes",
    "pages",
    "startMarker",
    "mode",
}


def _duplicate_values(values: list[str]) -> list[str]:
    return sorted(value for value, count in Counter(values).items() if count > 1)


def _validate_positive_range(
    value: Any,
    label: str,
    enclosing_range: list[int] | None = None,
) -> list[int]:
    if (
        not isinstance(value, list)
        or len(value) != 2
        or not all(isinstance(item, int) and item > 0 for item in value)
        or value[0] > value[1]
    ):
        raise ValueError(f"{label} must be a positive [start, end]")
    if enclosing_range and (
        value[0] < enclosing_range[0] or value[1] > enclosing_range[1]
    ):
        raise ValueError(f"{label} exceeds the subject course page bounds")
    return value


def _validate_module(
    subject: str,
    module: dict[str, Any],
    course_pages: list[int],
) -> None:
    require_keys(module, MODULE_KEYS, f"{subject} module")
    slug = str(module["slug"])
    if not SLUG_PATTERN.fullmatch(slug):
        raise ValueError(f"{subject} module slug is invalid: {slug}")

    requirement_types = module["requirementTypes"]
    if (
        not isinstance(requirement_types, list)
        or not requirement_types
        or len(set(requirement_types)) != len(requirement_types)
        or any(item not in ALLOWED_REQUIREMENT_TYPES for item in requirement_types)
    ):
        raise ValueError(f"{subject}/{slug} has invalid requirementTypes")

    _validate_positive_range(
        module["pages"],
        f"{subject}/{slug} PDF page bounds",
        course_pages,
    )
    if module["mode"] not in ALLOWED_MODES:
        raise ValueError(f"{subject}/{slug} has an invalid extraction mode")


def _validate_subject(
    subject_entry: dict[str, Any],
    manifest: dict[str, Any],
    sources_by_id: dict[str, dict[str, Any]],
) -> str:
    require_keys(subject_entry, SUBJECT_KEYS, "manifest subject")
    subject = str(subject_entry["subject"])
    if subject not in ALLOWED_SUBJECTS:
        raise ValueError(f"Unsupported subject in extraction manifest: {subject}")

    source = sources_by_id.get(str(subject_entry["sourceId"]))
    if source is None:
        raise ValueError(f"{subject} references an unknown curriculum source")
    if (
        source.get("baselineId") != manifest["targetBaselineId"]
        or source.get("subjects") != [subject]
        or "CURRENT_CURRICULUM_TEXT" not in source.get("purposes", [])
    ):
        raise ValueError(f"{subject} must reference its own current curriculum text")
    if source.get("acquisitionState") not in {
        "ACQUIRED_UNREVIEWED",
        "ACQUIRED_REVIEWED",
    }:
        raise ValueError(f"{subject} curriculum PDF has not been acquired")

    course_pages = _validate_positive_range(
        subject_entry["coursePages"],
        f"{subject}.coursePages",
    )
    modules = subject_entry["modules"]
    if not isinstance(modules, list) or not modules:
        raise ValueError(f"{subject}.modules must be a non-empty list")
    module_slugs = [str(module.get("slug", "")) for module in modules]
    duplicate_slugs = _duplicate_values(module_slugs)
    if duplicate_slugs:
        raise ValueError(
            f"{subject} module slug is duplicated: {', '.join(duplicate_slugs)}"
        )
    for module in modules:
        _validate_module(subject, module, course_pages)
    return subject


def validate_manifest(
    manifest: dict[str, Any],
    register: dict[str, Any],
) -> dict[str, dict[str, Any]]:
    require_keys(manifest, MANIFEST_KEYS, "extraction manifest")
    if manifest["schemaVersion"] != 1:
        raise ValueError("Only extraction manifest schemaVersion 1 is supported")
    if manifest["targetBaselineId"] != "moe-high-school-2017-2025":
        raise ValueError("Extraction manifest must target the 2025 curriculum baseline")
    if manifest["sourceRegisterId"] != register.get("registerId"):
        raise ValueError("Extraction manifest must reference the loaded source register")

    subjects = manifest["subjects"]
    if not isinstance(subjects, list):
        raise ValueError("Extraction manifest subjects must be a list")
    subject_names = [str(entry.get("subject", "")) for entry in subjects]
    duplicate_subjects = _duplicate_values(subject_names)
    if duplicate_subjects:
        raise ValueError(
            f"Duplicate subject in extraction manifest: {', '.join(duplicate_subjects)}"
        )

    sources_by_id = {
        str(source["sourceId"]): source for source in register.get("sources", [])
    }
    validated_subjects = {
        _validate_subject(entry, manifest, sources_by_id) for entry in subjects
    }
    if validated_subjects != ALLOWED_SUBJECTS:
        missing = sorted(ALLOWED_SUBJECTS - validated_subjects)
        raise ValueError(f"Extraction manifest is missing subjects: {', '.join(missing)}")
    return sources_by_id
