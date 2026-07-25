"""Build a locator-only human-review inventory from audited teaching EPUBs."""

from __future__ import annotations

import hashlib
import re
import zipfile
from collections import Counter
from html.parser import HTMLParser
from pathlib import Path
from typing import Any

from .epub_audit import MARKERS, audit_manifest


TEACHING_FORM_MARKERS = {
    "METHOD_MODEL": (
        "method",
        "strategy",
        "scientificMethod",
        "writingProcess",
    ),
    "WORKED_EXAMPLE": ("workedExample", "example"),
    "COMPLETE_SOLUTION": ("solution", "answer"),
    "DERIVATION": ("derivation",),
}
TEXT_SUFFIXES = {".xhtml", ".html", ".htm"}


class _SectionTextParser(HTMLParser):
    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self._ignored_depth = 0
        self._heading_depth = 0
        self._title_depth = 0
        self.text_parts: list[str] = []
        self.heading_parts: list[str] = []
        self.title_parts: list[str] = []

    def handle_starttag(
        self,
        tag: str,
        attrs: list[tuple[str, str | None]],
    ) -> None:
        del attrs
        lowered = tag.lower()
        if lowered in {"script", "style", "svg"}:
            self._ignored_depth += 1
        elif lowered in {"h1", "h2", "h3"}:
            self._heading_depth += 1
        elif lowered == "title":
            self._title_depth += 1

    def handle_endtag(self, tag: str) -> None:
        lowered = tag.lower()
        if lowered in {"script", "style", "svg"} and self._ignored_depth:
            self._ignored_depth -= 1
        elif lowered in {"h1", "h2", "h3"} and self._heading_depth:
            self._heading_depth -= 1
        elif lowered == "title" and self._title_depth:
            self._title_depth -= 1

    def handle_data(self, data: str) -> None:
        if self._ignored_depth:
            return
        self.text_parts.append(data)
        if self._heading_depth:
            self.heading_parts.append(data)
        if self._title_depth:
            self.title_parts.append(data)


def _normalized_text(parts: list[str]) -> str:
    return re.sub(r"\s+", " ", " ".join(parts)).strip()


def _section_text(raw: bytes) -> tuple[str, str]:
    parser = _SectionTextParser()
    parser.feed(raw.decode("utf-8", errors="ignore"))
    parser.close()
    text = _normalized_text(parser.text_parts)
    title = _normalized_text(parser.heading_parts) or _normalized_text(parser.title_parts)
    return text, title


def _candidate_forms(marker_counts: dict[str, int]) -> list[str]:
    return [
        form
        for form, markers in TEACHING_FORM_MARKERS.items()
        if any(marker_counts[marker] > 0 for marker in markers)
    ]


def _candidate_id(source_id: str, entry_path: str, content_hash: str) -> str:
    identity = f"{source_id}\0{entry_path}\0{content_hash}".encode()
    return f"teaching-review:{hashlib.sha256(identity).hexdigest()[:32]}"


def _section_candidates(
    project_root: Path,
    artifact_audit: dict[str, Any],
) -> list[dict[str, Any]]:
    path = (project_root / str(artifact_audit["localPath"])).resolve()
    candidates: list[dict[str, Any]] = []
    with zipfile.ZipFile(path) as archive:
        for entry in archive.infolist():
            if Path(entry.filename).suffix.lower() not in TEXT_SUFFIXES:
                continue
            text, title = _section_text(archive.read(entry))
            if not text:
                continue
            marker_counts = {
                name: len(pattern.findall(text))
                for name, pattern in MARKERS.items()
            }
            forms = _candidate_forms(marker_counts)
            if not forms:
                continue
            content_hash = hashlib.sha256(text.encode("utf-8")).hexdigest().upper()
            candidates.append(
                {
                    "candidateId": _candidate_id(
                        str(artifact_audit["sourceId"]),
                        entry.filename,
                        content_hash,
                    ),
                    "sourceId": artifact_audit["sourceId"],
                    "subjects": artifact_audit["subjects"],
                    "entryPath": entry.filename,
                    "sectionTitle": title or Path(entry.filename).stem,
                    "normalizedTextSha256": content_hash,
                    "normalizedTextCharacterCount": len(text),
                    "candidateTeachingForms": forms,
                    "markerCounts": {
                        name: count
                        for name, count in marker_counts.items()
                        if count > 0
                    },
                    "reviewState": "UNREVIEWED_LOCATOR_ONLY",
                }
            )
    return candidates


def _inventory_boundary() -> dict[str, Any]:
    return {
        "state": "AI_LOCATOR_INVENTORY_REQUIRES_HUMAN_REVIEW",
        "rawTeachingTextIncluded": False,
        "humanReviewerMustOpenPinnedLocalSource": True,
        "allowedTeachingForms": list(TEACHING_FORM_MARKERS),
        "questionBankAuthority": False,
        "autonomousQuestionGenerationAuthority": False,
        "assessmentAuthority": False,
        "reviewSchedulingAuthority": False,
        "learningEvidenceWriteAuthority": False,
        "formalKnowledgeCoverageContribution": 0,
    }


def _decision_template(inventory_id: str, candidate_count: int) -> dict[str, Any]:
    return {
        "schemaVersion": 1,
        "decisionSetId": "open-teaching-human-review-decisions-2026-v1",
        "inventoryId": inventory_id,
        "inventoryCandidateCount": candidate_count,
        "reviewState": "NOT_STARTED",
        "reviewer": None,
        "reviewedAtEpochMillis": None,
        "sourceTextAttestation": None,
        "decisions": [],
        "automaticKnowledgePackMutationAllowed": False,
    }


def _inventory_summary(
    inventory_id: str,
    artifact_count: int,
    candidates: list[dict[str, Any]],
) -> dict[str, Any]:
    subject_counts: Counter[str] = Counter()
    form_counts: Counter[str] = Counter()
    for candidate in candidates:
        subject_counts.update(candidate["subjects"])
        form_counts.update(candidate["candidateTeachingForms"])
    return {
        "inventoryId": inventory_id,
        "sourceArtifactCount": artifact_count,
        "candidateSectionCount": len(candidates),
        "subjectCandidateCounts": dict(sorted(subject_counts.items())),
        "teachingFormCandidateCounts": dict(sorted(form_counts.items())),
        "reviewState": "NOT_STARTED",
        "formalKnowledgeCoverageContribution": 0,
    }


def build_review_inventory(
    project_root: Path,
    manifest: dict[str, Any],
    register: dict[str, Any],
) -> tuple[dict[str, Any], dict[str, Any], dict[str, Any]]:
    audit_report, _ = audit_manifest(project_root, manifest, register)
    candidates = [
        candidate
        for artifact in audit_report["artifacts"]
        for candidate in _section_candidates(project_root, artifact)
    ]
    candidate_ids = [candidate["candidateId"] for candidate in candidates]
    if len(set(candidate_ids)) != len(candidate_ids):
        raise ValueError("Teaching review candidate ids must be unique")
    inventory = {
        "schemaVersion": 1,
        "inventoryId": "open-teaching-review-inventory-2026-v1",
        "manifestId": manifest["manifestId"],
        "sourceRegisterId": register["registerId"],
        "updatedAtEpochMillis": manifest["updatedAtEpochMillis"],
        "boundary": _inventory_boundary(),
        "candidates": candidates,
    }
    decisions = _decision_template(inventory["inventoryId"], len(candidates))
    summary = _inventory_summary(
        inventory["inventoryId"],
        len(audit_report["artifacts"]),
        candidates,
    )
    return inventory, decisions, summary
