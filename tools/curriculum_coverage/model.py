"""Shared data contracts and normalization helpers."""

from __future__ import annotations

import json
import re
import unicodedata
from dataclasses import dataclass
from pathlib import Path
from typing import Any


ALLOWED_SUBJECTS = {
    "CHINESE",
    "MATH",
    "ENGLISH",
    "POLITICS",
    "HISTORY",
    "GEOGRAPHY",
    "PHYSICS",
    "CHEMISTRY",
    "BIOLOGY",
}
ALLOWED_REQUIREMENT_TYPES = {"REQUIRED", "SELECTIVE_REQUIRED", "ELECTIVE"}
ALLOWED_MODES = {"GOALS", "REQUIREMENTS", "NUMBERED", "DESCRIPTIVE"}
PRIVATE_USE_PATTERN = re.compile(r"[\ue000-\uf8ff]")
CONTROL_CHARACTER_PATTERN = re.compile(r"[\x00-\x08\x0b\x0c\x0e-\x1f\x7f]")
HEADER_PATTERN = re.compile(r"^\d+\s*普通高中.+课程标准")
PAGE_HEADER_PATTERN = re.compile(r"^[|│]五、课程内容[|│]\d*$")


@dataclass(frozen=True, slots=True)
class SourceLine:
    page: int
    raw: str
    text: str
    key: str


@dataclass(slots=True)
class PendingItem:
    page: int
    raw_marker: str
    hierarchy: tuple[int, ...] | None
    hierarchy_kind: str
    parts: list[str]


@dataclass(frozen=True, slots=True)
class ParsedItemStart:
    marker: str
    text: str
    hierarchy: tuple[int, ...] | None
    kind: str
    current_unit: int | None
    current_parent: int | None


def normalize_display(value: str) -> str:
    normalized = unicodedata.normalize("NFKC", value)
    normalized = PRIVATE_USE_PATTERN.sub(".", normalized)
    normalized = CONTROL_CHARACTER_PATTERN.sub(" ", normalized)
    normalized = normalized.replace("\u00a0", " ")
    return " ".join(normalized.split()).strip()


def marker_key(value: str) -> str:
    normalized = normalize_display(value)
    normalized = normalized.replace("：", ":")
    normalized = normalized.replace("“", '"').replace("”", '"')
    normalized = normalized.replace("‘", "'").replace("’", "'")
    return re.sub(r"\s+", "", normalized)


def strip_marker_brackets(value: str) -> str:
    return value.strip("[]【】")


def is_noise(line: SourceLine) -> bool:
    if not line.text or line.key in {"五、课程内容", "续表"}:
        return True
    if HEADER_PATTERN.match(line.text):
        return True
    if re.match(r"^\d+普通高中.+课程标准", line.key):
        return True
    if PAGE_HEADER_PATTERN.match(line.key):
        return True
    return bool(re.match(r"^五、课程内容\d+$", line.key))


def read_json(path: Path) -> dict[str, Any]:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise ValueError(f"Cannot read valid JSON from {path}: {error}") from error


def ensure_under_project(path: Path, project_root: Path, label: str) -> Path:
    resolved = path.resolve()
    try:
        resolved.relative_to(project_root.resolve())
    except ValueError as error:
        raise ValueError(f"{label} must stay under project root: {resolved}") from error
    return resolved


def require_keys(value: dict[str, Any], required: set[str], label: str) -> None:
    missing = sorted(required - value.keys())
    if missing:
        raise ValueError(f"{label} is missing keys: {', '.join(missing)}")
