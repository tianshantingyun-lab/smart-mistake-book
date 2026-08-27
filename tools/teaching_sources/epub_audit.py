"""Read-only structural and rights audit for acquired open-teaching EPUBs."""

from __future__ import annotations

import hashlib
import re
import zipfile
from pathlib import Path, PurePosixPath
from typing import Any
from xml.etree import ElementTree

from curriculum_coverage.model import ensure_under_project, require_keys


MANIFEST_KEYS = {
    "schemaVersion",
    "manifestId",
    "sourceRegisterId",
    "updatedAtEpochMillis",
    "artifacts",
}
ARTIFACT_KEYS = {
    "sourceId",
    "localPath",
    "expectedBytes",
    "expectedSha256",
    "expectedMediaType",
    "expectedLicenseUri",
    "expectedLanguage",
    "expectedSubjects",
    "requiredTeachingMarkers",
}
SOURCE_KEYS = {
    "sourceId",
    "subjects",
    "purposes",
    "acquisitionState",
    "contentLengthBytes",
    "contentFingerprint",
    "licenseStatus",
    "licenseUri",
    "contentUsePolicy",
    "modelUsePolicy",
}
REQUIRED_PURPOSES = {
    "TEACHING_REFERENCE",
    "METHOD_REFERENCE",
    "WORKED_EXAMPLE_REFERENCE",
}
MARKERS = {
    "example": re.compile(r"\bexamples?\b", re.IGNORECASE),
    "workedExample": re.compile(r"\bworked examples?\b", re.IGNORECASE),
    "solution": re.compile(r"\bsolutions?\b", re.IGNORECASE),
    "method": re.compile(r"\bmethods?\b", re.IGNORECASE),
    "strategy": re.compile(r"\bstrateg(?:y|ies)\b", re.IGNORECASE),
    "exercise": re.compile(r"\bexercises?\b", re.IGNORECASE),
    "activity": re.compile(r"\bactivit(?:y|ies)\b", re.IGNORECASE),
    "summary": re.compile(r"\bsummar(?:y|ies)\b", re.IGNORECASE),
    "chapterSummary": re.compile(r"\bchapter summary\b", re.IGNORECASE),
    "writingProcess": re.compile(r"\bwriting process\b", re.IGNORECASE),
    "primarySource": re.compile(r"\bprimary sources?\b", re.IGNORECASE),
    "historicalSource": re.compile(r"\bhistorical sources?\b", re.IGNORECASE),
    "scientificMethod": re.compile(r"\bscientific method\b", re.IGNORECASE),
    "practiceQuestion": re.compile(r"\bpractice questions?\b", re.IGNORECASE),
    "reviewQuestion": re.compile(r"\breview questions?\b", re.IGNORECASE),
    "answer": re.compile(r"\banswers?\b", re.IGNORECASE),
    "derivation": re.compile(r"\bderivations?\b", re.IGNORECASE),
}
RIGHTS_DOCUMENT_PATTERN = re.compile(
    r"(?:copyright|licen[cs]e|rights|attribution|acknowledg)",
    re.IGNORECASE,
)
MAX_ENTRIES = 20_000
MAX_ENTRY_UNCOMPRESSED_BYTES = 128 * 1024 * 1024
MAX_TOTAL_UNCOMPRESSED_BYTES = 2 * 1024 * 1024 * 1024
MAX_COMPRESSION_RATIO = 250


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest().upper()


def _safe_archive_name(name: str) -> None:
    path = PurePosixPath(name)
    if path.is_absolute() or ".." in path.parts or "\\" in name:
        raise ValueError(f"Unsafe EPUB archive entry: {name}")


def _rootfile_path(archive: zipfile.ZipFile) -> str:
    try:
        container = archive.read("META-INF/container.xml")
    except KeyError as error:
        raise ValueError("EPUB is missing META-INF/container.xml") from error
    try:
        root = ElementTree.fromstring(
            container,
            parser=ElementTree.XMLParser(resolve_entities=False),
        )
    except ElementTree.ParseError as error:
        raise ValueError("EPUB container.xml is invalid") from error
    rootfile = root.find(
        ".//{urn:oasis:names:tc:opendocument:xmlns:container}rootfile"
    )
    if rootfile is None:
        raise ValueError("EPUB container.xml has no rootfile")
    path = str(rootfile.attrib.get("full-path", "")).strip()
    _safe_archive_name(path)
    if not path:
        raise ValueError("EPUB rootfile path is empty")
    return path


def _metadata(
    archive: zipfile.ZipFile,
    rootfile_path: str,
) -> tuple[str, str]:
    try:
        package = ElementTree.fromstring(
            archive.read(rootfile_path),
            parser=ElementTree.XMLParser(resolve_entities=False),
        )
    except KeyError as error:
        raise ValueError(f"EPUB rootfile does not exist: {rootfile_path}") from error
    except ElementTree.ParseError as error:
        raise ValueError("EPUB package metadata is invalid") from error
    title = package.findtext(".//{http://purl.org/dc/elements/1.1/}title", "").strip()
    language = package.findtext(
        ".//{http://purl.org/dc/elements/1.1/}language",
        "",
    ).strip()
    if not title or not language:
        raise ValueError("EPUB package metadata requires title and language")
    return title, language


def _archive_metrics(
    archive: zipfile.ZipFile,
    expected_license_uri: str,
) -> tuple[int, int, int, list[str], dict[str, int]]:
    entries = archive.infolist()
    if not entries or len(entries) > MAX_ENTRIES:
        raise ValueError("EPUB entry count is outside the safe range")
    total_uncompressed = 0
    xhtml_count = 0
    license_evidence_paths: set[str] = set()
    license_token = re.sub(r"^https?://", "", expected_license_uri).rstrip("/").lower()
    marker_counts = {name: 0 for name in MARKERS}
    for entry in entries:
        _safe_archive_name(entry.filename)
        if entry.file_size > MAX_ENTRY_UNCOMPRESSED_BYTES:
            raise ValueError(f"EPUB entry is too large: {entry.filename}")
        total_uncompressed += entry.file_size
        if total_uncompressed > MAX_TOTAL_UNCOMPRESSED_BYTES:
            raise ValueError("EPUB uncompressed content is too large")
        if entry.compress_size > 0:
            ratio = entry.file_size / entry.compress_size
            if ratio > MAX_COMPRESSION_RATIO:
                raise ValueError(f"EPUB entry compression ratio is unsafe: {entry.filename}")
        suffix = PurePosixPath(entry.filename).suffix.lower()
        if suffix not in {".xhtml", ".html", ".htm", ".opf", ".xml"}:
            continue
        if suffix in {".xhtml", ".html", ".htm"}:
            xhtml_count += 1
        text = archive.read(entry).decode("utf-8", errors="ignore")
        normalized_text = re.sub(r"https?://", "", text, flags=re.IGNORECASE).lower()
        if (
            RIGHTS_DOCUMENT_PATTERN.search(entry.filename)
            and license_token in normalized_text
        ):
            license_evidence_paths.add(entry.filename)
        for name, pattern in MARKERS.items():
            marker_counts[name] += len(pattern.findall(text))
    if xhtml_count == 0:
        raise ValueError("EPUB contains no readable XHTML documents")
    return (
        len(entries),
        total_uncompressed,
        xhtml_count,
        sorted(license_evidence_paths),
        marker_counts,
    )


def _required_marker_thresholds(artifact: dict[str, Any]) -> dict[str, int]:
    source_id = str(artifact["sourceId"])
    raw = artifact["requiredTeachingMarkers"]
    if not isinstance(raw, dict) or not raw:
        raise ValueError(
            f"Open teaching EPUB requires source-specific teaching markers: {source_id}"
        )
    thresholds: dict[str, int] = {}
    for marker, minimum in raw.items():
        if marker not in MARKERS:
            raise ValueError(f"Unknown teaching marker {marker}: {source_id}")
        if (
            isinstance(minimum, bool)
            or not isinstance(minimum, int)
            or minimum < 1
        ):
            raise ValueError(
                f"Teaching marker threshold must be a positive integer: "
                f"{source_id}/{marker}"
            )
        thresholds[str(marker)] = minimum
    return thresholds


def _validate_source(
    artifact: dict[str, Any],
    sources_by_id: dict[str, dict[str, Any]],
) -> dict[str, Any]:
    source_id = str(artifact["sourceId"])
    source = sources_by_id.get(source_id)
    if source is None:
        raise ValueError(f"EPUB manifest source is absent from register: {source_id}")
    require_keys(source, SOURCE_KEYS, f"knowledge source {source_id}")
    if source["acquisitionState"] != "ACQUIRED_UNREVIEWED":
        raise ValueError(f"Open teaching EPUB must remain acquired-unreviewed: {source_id}")
    if set(source["purposes"]) != REQUIRED_PURPOSES:
        raise ValueError(f"Open teaching EPUB purposes are incomplete: {source_id}")
    if source["licenseStatus"] != "LICENSED":
        raise ValueError(f"Open teaching EPUB requires explicit license: {source_id}")
    if source["contentUsePolicy"] != "ADAPTATION_ALLOWED":
        raise ValueError(f"Open teaching EPUB must permit adaptation: {source_id}")
    if source["modelUsePolicy"] != "FULL_CONTENT_ALLOWED":
        raise ValueError(f"Open teaching EPUB model-use policy is inconsistent: {source_id}")
    comparisons = {
        "subjects": (list(source["subjects"]), list(artifact["expectedSubjects"])),
        "contentLengthBytes": (
            int(source["contentLengthBytes"]),
            int(artifact["expectedBytes"]),
        ),
        "contentFingerprint": (
            str(source["contentFingerprint"]),
            str(artifact["expectedSha256"]),
        ),
        "licenseUri": (
            str(source["licenseUri"]),
            str(artifact["expectedLicenseUri"]),
        ),
    }
    for label, (actual, expected) in comparisons.items():
        if actual != expected:
            raise ValueError(f"Open teaching EPUB {label} mismatch: {source_id}")
    return source


def _verified_epub_path(
    project_root: Path,
    artifact: dict[str, Any],
) -> tuple[Path, int, str]:
    path = ensure_under_project(
        project_root / str(artifact["localPath"]),
        project_root,
        f"{artifact['sourceId']} local EPUB",
    )
    if not path.is_file():
        raise ValueError(f"Missing open teaching EPUB: {path}")
    actual_bytes = path.stat().st_size
    actual_sha256 = _sha256(path)
    if actual_bytes != int(artifact["expectedBytes"]):
        raise ValueError(f"Open teaching EPUB size is stale: {artifact['sourceId']}")
    if actual_sha256 != artifact["expectedSha256"]:
        raise ValueError(f"Open teaching EPUB hash is stale: {artifact['sourceId']}")
    if not zipfile.is_zipfile(path):
        raise ValueError(f"Open teaching artifact is not an EPUB ZIP: {artifact['sourceId']}")
    return path, actual_bytes, actual_sha256


def _canonical_media_type(
    archive: zipfile.ZipFile,
    artifact: dict[str, Any],
) -> str:
    entries = archive.infolist()
    if (
        not entries
        or entries[0].filename != "mimetype"
        or entries[0].compress_type != zipfile.ZIP_STORED
    ):
        raise ValueError(f"EPUB mimetype entry is not canonical: {artifact['sourceId']}")
    try:
        media_type = archive.read("mimetype").decode("ascii").strip()
    except KeyError as error:
        raise ValueError("EPUB is missing the mimetype entry") from error
    if media_type != artifact["expectedMediaType"]:
        raise ValueError(f"Unexpected EPUB media type: {artifact['sourceId']}")
    return media_type


def _missing_required_markers(
    required_markers: dict[str, int],
    marker_counts: dict[str, int],
) -> dict[str, dict[str, int]]:
    return {
        marker: {
            "minimum": minimum,
            "actual": marker_counts[marker],
        }
        for marker, minimum in required_markers.items()
        if marker_counts[marker] < minimum
    }


def _inspect_epub(path: Path, artifact: dict[str, Any]) -> dict[str, Any]:
    required_markers = _required_marker_thresholds(artifact)
    with zipfile.ZipFile(path) as archive:
        media_type = _canonical_media_type(archive, artifact)
        rootfile_path = _rootfile_path(archive)
        title, language = _metadata(archive, rootfile_path)
        metrics = _archive_metrics(archive, str(artifact["expectedLicenseUri"]))
    (
        entry_count,
        total_bytes,
        xhtml_count,
        license_evidence_paths,
        marker_counts,
    ) = metrics
    if language.lower() != str(artifact["expectedLanguage"]).lower():
        raise ValueError(f"Unexpected EPUB language: {artifact['sourceId']}")
    if not license_evidence_paths:
        raise ValueError(
            f"Expected license URI is absent from an EPUB rights document: "
            f"{artifact['sourceId']}"
        )
    missing_markers = _missing_required_markers(required_markers, marker_counts)
    if missing_markers:
        raise ValueError(
            f"EPUB lacks required teaching-structure evidence: "
            f"{artifact['sourceId']} {missing_markers}"
        )
    return {
        "title": title,
        "mediaType": media_type,
        "language": language,
        "rootfilePath": rootfile_path,
        "archiveEntryCount": entry_count,
        "xhtmlDocumentCount": xhtml_count,
        "totalUncompressedBytes": total_bytes,
        "licenseUriVerified": artifact["expectedLicenseUri"],
        "licenseEvidencePaths": license_evidence_paths,
        "requiredTeachingMarkers": required_markers,
        "teachingStructureMarkers": marker_counts,
    }


def audit_epub(
    project_root: Path,
    artifact: dict[str, Any],
    sources_by_id: dict[str, dict[str, Any]],
) -> dict[str, Any]:
    require_keys(artifact, ARTIFACT_KEYS, "open teaching EPUB artifact")
    source = _validate_source(artifact, sources_by_id)
    path, actual_bytes, actual_sha256 = _verified_epub_path(project_root, artifact)
    inspection = _inspect_epub(path, artifact)
    return {
        "sourceId": artifact["sourceId"],
        "subjects": source["subjects"],
        "localPath": artifact["localPath"],
        "contentLengthBytes": actual_bytes,
        "contentFingerprint": actual_sha256,
        **inspection,
        "reviewState": "CONTENT_ACQUIRED_UNREVIEWED",
        "chinaCurriculumMappingComplete": False,
    }


def audit_manifest(
    project_root: Path,
    manifest: dict[str, Any],
    register: dict[str, Any],
) -> tuple[dict[str, Any], dict[str, Any]]:
    require_keys(manifest, MANIFEST_KEYS, "open teaching EPUB manifest")
    if manifest["schemaVersion"] != 1:
        raise ValueError("Only open teaching EPUB manifest schemaVersion 1 is supported")
    if manifest["sourceRegisterId"] != register.get("registerId"):
        raise ValueError("Open teaching EPUB manifest references another source register")
    artifacts = manifest["artifacts"]
    if not isinstance(artifacts, list) or not artifacts:
        raise ValueError("Open teaching EPUB manifest requires artifacts")
    sources = register.get("sources")
    if not isinstance(sources, list):
        raise ValueError("Knowledge source register sources must be a list")
    sources_by_id = {str(source["sourceId"]): source for source in sources}
    audits = [
        audit_epub(project_root, artifact, sources_by_id) for artifact in artifacts
    ]
    source_ids = [audit["sourceId"] for audit in audits]
    if len(set(source_ids)) != len(source_ids):
        raise ValueError("Open teaching EPUB manifest sourceIds must be unique")
    report = {
        "schemaVersion": 1,
        "artifactId": "open-teaching-epub-audit-2026-v1",
        "manifestId": manifest["manifestId"],
        "sourceRegisterId": register["registerId"],
        "updatedAtEpochMillis": manifest["updatedAtEpochMillis"],
        "reviewBoundary": {
            "state": "CONTENT_ACQUIRED_UNREVIEWED",
            "chinaCurriculumMappingComplete": False,
            "teachingSynthesisReviewed": False,
            "formalKnowledgeCoverageContribution": 0,
        },
        "artifacts": audits,
    }
    subject_counts: dict[str, int] = {}
    for audit in audits:
        for subject in audit["subjects"]:
            subject_counts[subject] = subject_counts.get(subject, 0) + 1
    summary = {
        "artifactId": report["artifactId"],
        "acquiredArtifactCount": len(audits),
        "subjectArtifactCounts": subject_counts,
        "reviewState": report["reviewBoundary"]["state"],
        "formalKnowledgeCoverageContribution": 0,
    }
    return report, summary
