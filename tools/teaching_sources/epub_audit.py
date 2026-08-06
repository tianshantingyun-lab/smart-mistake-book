"""Read-only structural and rights audit for acquired open-teaching EPUBs."""

from __future__ import annotations

import hashlib
import re
import unicodedata
import zipfile
from html.parser import HTMLParser
from pathlib import Path, PurePosixPath
from typing import Any
from urllib.parse import unquote, urlsplit, urlunsplit
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
    "expectedProviderIdentity",
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
    "publisher",
    "independenceGroup",
    "discoveryUri",
    "documentUri",
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
NEGATIVE_RIGHTS_PATTERN = re.compile(
    r"\b(?:all\s+rights\s+(?:are\s+)?reserved|not\s+(?:licensed|available)\s+under|"
    r"no\s+(?:license|rights?)\s+(?:is|are|were|has\s+been\s+)?granted|"
    r"license\s+(?:is\s+)?(?:revoked|withdrawn|invalid))\b",
    re.IGNORECASE,
)
LICENSE_URI_PATTERN = re.compile(r"https?://[^\s<>\"']+", re.IGNORECASE)
ALLOWED_LICENSE_URIS = frozenset(
    {
        "https://creativecommons.org/licenses/by/4.0/",
        "https://creativecommons.org/licenses/by-sa/4.0/",
    }
)
DISABLED_PROVIDER_IDENTITIES = {
    "openstax": {
        "identityTokens": {"openstax", "riceuniversity"},
        "hosts": {"openstax.org"},
    }
}
TRUSTED_PROVIDER_IDENTITY_ALIASES = {
    "siyavula": {"siyavula"},
    "uhpressbooks": {
        "hawaii",
        "pressbooksoerhawaiiedu",
        "uhpressbooks",
        "universityofhawaii",
    },
    "openoregon": {"openoregon", "openoregonpressbookspub"},
    "bccampus": {"bccampus"},
}
TRUSTED_PROVIDER_SOURCE_POLICY = {
    "siyavula": {
        "documentHosts": {"siyavula.com", "www.siyavula.com"},
        "publisherTokens": {"siyavulaeducation"},
    },
    "uhpressbooks": {
        "documentHosts": {"pressbooks.oer.hawaii.edu"},
        "publisherTokens": {
            "universityofhawaiiatmanoa",
            "universityofhawaiihonolulucommunitycollege",
        },
    },
    "openoregon": {
        "documentHosts": {"openoregon.pressbooks.pub"},
        "publisherTokens": {"openoregoneducationalresources"},
    },
    "bccampus": {
        "documentHosts": {"ohiostate.pressbooks.pub"},
        "publisherTokens": {"bccampus"},
    },
}
PROVIDER_SOURCE_FIELDS = (
    "sourceId",
    "publisher",
    "independenceGroup",
    "discoveryUri",
    "documentUri",
    "attributionText",
)
PROVIDER_MANIFEST_FIELDS = (
    "sourceId",
    "localPath",
    "expectedProviderIdentity",
    "provider",
    "publisher",
    "provenance",
)
OPF_PROVENANCE_NAMES = {
    "contributor",
    "creator",
    "description",
    "identifier",
    "publisher",
    "rights",
    "source",
    "title",
}
MAX_ENTRIES = 20_000
MAX_ENTRY_UNCOMPRESSED_BYTES = 128 * 1024 * 1024
MAX_TOTAL_UNCOMPRESSED_BYTES = 2 * 1024 * 1024 * 1024
MAX_COMPRESSION_RATIO = 250
MAX_ARCHIVE_ENTRY_NAME_CHARACTERS = 2_048
MAX_AUDIT_DECOMPRESSED_BYTES = 256 * 1024 * 1024


class ArchiveReadBudgetExceeded(ValueError):
    """Raised before an EPUB read would exceed its cumulative byte budget."""


class _RightsEvidenceParser(HTMLParser):
    """Collect visible rights text and explicit link targets, never comments."""

    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.visible_text: list[str] = []
        self.link_targets: list[str] = []
        self._hidden_stack: list[tuple[str, bool]] = []
        self._hidden_depth = 0
        self._anchor_stack: list[dict[str, Any]] = []

    @staticmethod
    def _hidden(tag: str, attrs: dict[str, str]) -> bool:
        style = re.sub(r"\s+", "", attrs.get("style", "")).casefold()
        return (
            tag in {"script", "style", "template"}
            or "hidden" in attrs
            or attrs.get("aria-hidden", "").strip().casefold() == "true"
            or "display:none" in style
            or "visibility:hidden" in style
        )

    def handle_starttag(
        self,
        tag: str,
        attrs: list[tuple[str, str | None]],
    ) -> None:
        normalized_tag = tag.casefold()
        normalized_attrs = {
            name.casefold(): value or "" for name, value in attrs
        }
        hidden = bool(self._hidden_depth) or self._hidden(
            normalized_tag,
            normalized_attrs,
        )
        if normalized_tag == "a":
            self._anchor_stack.append(
                {
                    "href": normalized_attrs.get("href", "").strip()
                    if not hidden
                    else "",
                    "hasVisibleText": False,
                }
            )
        if normalized_tag not in {
            "area",
            "base",
            "br",
            "col",
            "embed",
            "hr",
            "img",
            "input",
            "link",
            "meta",
            "param",
            "source",
            "track",
            "wbr",
        }:
            self._hidden_stack.append((normalized_tag, hidden))
            if hidden:
                self._hidden_depth += 1

    def handle_startendtag(
        self,
        tag: str,
        attrs: list[tuple[str, str | None]],
    ) -> None:
        self.handle_starttag(tag, attrs)
        if self._hidden_stack and self._hidden_stack[-1][0] == tag.casefold():
            self.handle_endtag(tag)

    def handle_endtag(self, tag: str) -> None:
        normalized_tag = tag.casefold()
        if normalized_tag == "a" and self._anchor_stack:
            anchor = self._anchor_stack.pop()
            if anchor["href"] and anchor["hasVisibleText"]:
                self.link_targets.append(str(anchor["href"]))
        while self._hidden_stack:
            stack_tag, hidden = self._hidden_stack.pop()
            if hidden:
                self._hidden_depth -= 1
            if stack_tag == normalized_tag:
                break

    def handle_data(self, data: str) -> None:
        if not self._hidden_depth and data.strip():
            self.visible_text.append(data)
            for anchor in self._anchor_stack:
                anchor["hasVisibleText"] = True


def _rights_evidence(raw: bytes) -> tuple[str, list[str]]:
    parser = _RightsEvidenceParser()
    parser.feed(raw.decode("utf-8", errors="strict"))
    parser.close()
    visible = unicodedata.normalize("NFKC", " ".join(parser.visible_text))
    return " ".join(visible.split()), parser.link_targets


def _rights_token_text(value: str) -> str:
    normalized = unicodedata.normalize("NFKC", value).casefold()
    return " ".join(
        "".join(character if character.isalnum() else " " for character in normalized).split()
    )


class BoundedArchiveReader:
    """Validate first, cache each entry once, and meter actual decompression."""

    def __init__(
        self,
        archive: zipfile.ZipFile,
        *,
        max_decompressed_bytes: int,
    ) -> None:
        self.archive = archive
        self.entries = validated_archive_entries(archive)
        self.entries_by_name = {entry.filename: entry for entry in self.entries}
        self.max_decompressed_bytes = max_decompressed_bytes
        self.decompressed_bytes = 0
        self._cache: dict[str, bytes] = {}

    @property
    def cached_entry_count(self) -> int:
        return len(self._cache)

    def read(self, entry: str | zipfile.ZipInfo) -> bytes:
        name = entry.filename if isinstance(entry, zipfile.ZipInfo) else entry
        cached = self._cache.get(name)
        if cached is not None:
            return cached
        info = self.entries_by_name.get(name)
        if info is None:
            raise KeyError(name)
        projected = self.decompressed_bytes + info.file_size
        if projected > self.max_decompressed_bytes:
            raise ArchiveReadBudgetExceeded(
                "EPUB cumulative decompression budget exceeded"
            )
        raw = self.archive.read(info)
        if len(raw) != info.file_size:
            raise ValueError(f"EPUB entry size changed while reading: {name}")
        self.decompressed_bytes = projected
        self._cache[name] = raw
        return raw


def normalize_license_uri(value: Any) -> str:
    """Return the canonical URI only for the deliberately narrow allowlist."""
    if not isinstance(value, str):
        raise ValueError("Open teaching EPUB license URI must be a non-empty string")
    normalized = unicodedata.normalize("NFKC", value).strip()
    if not normalized or any(ord(character) < 32 for character in normalized):
        raise ValueError("Open teaching EPUB license URI must be non-empty")
    split = urlsplit(normalized)
    try:
        port = split.port
    except ValueError as error:
        raise ValueError("Open teaching EPUB license URI has an invalid port") from error
    host = (split.hostname or "").casefold().rstrip(".")
    if (
        split.scheme.casefold() != "https"
        or split.username is not None
        or split.password is not None
        or port not in {None, 443}
        or host != "creativecommons.org"
        or split.query
        or split.fragment
    ):
        raise ValueError("Open teaching EPUB license URI is not allowlisted")
    decoded_path = unquote(split.path)
    if "%" in split.path or "//" in decoded_path or not decoded_path.startswith("/"):
        raise ValueError("Open teaching EPUB license URI is not canonicalizable")
    canonical = f"https://creativecommons.org{decoded_path.rstrip('/')}/"
    if canonical not in ALLOWED_LICENSE_URIS:
        raise ValueError("Open teaching EPUB license URI is not allowlisted")
    return canonical


def _normalize_visible_license_evidence_uri(value: str) -> str:
    """Canonicalize an exact legacy CC HTTP link without weakening trusted metadata."""
    normalized = unicodedata.normalize("NFKC", value).strip()
    split = urlsplit(normalized)
    if split.scheme.casefold() == "http":
        normalized = urlunsplit(
            ("https", split.netloc, split.path, split.query, split.fragment)
        )
    return normalize_license_uri(normalized)


def _identity_token(value: str) -> str:
    normalized = unicodedata.normalize("NFKD", value).casefold()
    return "".join(character for character in normalized if character.isalnum())


def _disabled_provider(value: str) -> str | None:
    token = _identity_token(value)
    split = urlsplit(unicodedata.normalize("NFKC", value).strip())
    host = (split.hostname or "").casefold().rstrip(".")
    for identity, rules in DISABLED_PROVIDER_IDENTITIES.items():
        if any(candidate in token for candidate in rules["identityTokens"]):
            return identity
        if any(host == candidate or host.endswith(f".{candidate}") for candidate in rules["hosts"]):
            return identity
    return None


def _assert_provider_allowed(
    artifact: dict[str, Any],
    source: dict[str, Any],
    opf_provenance: list[tuple[str, str]],
) -> str:
    if not opf_provenance:
        raise ValueError(
            f"EPUB OPF has no provider provenance: {artifact['sourceId']}"
        )
    expected_provider = _identity_token(str(artifact["expectedProviderIdentity"]))
    if not expected_provider:
        raise ValueError(
            f"Open teaching EPUB expected provider identity is empty: "
            f"{artifact['sourceId']}"
        )
    source_id_parts = str(artifact["sourceId"]).split(":")
    manifest_provider = (
        _identity_token(source_id_parts[1]) if len(source_id_parts) >= 3 else ""
    )
    register_provider = _identity_token(str(source["independenceGroup"]))
    if (
        not manifest_provider
        or expected_provider != manifest_provider
        or expected_provider != register_provider
    ):
        raise ValueError(
            f"Open teaching EPUB manifest/register provider mismatch: "
            f"{artifact['sourceId']}"
        )
    if (
        expected_provider not in TRUSTED_PROVIDER_IDENTITY_ALIASES
        or expected_provider not in TRUSTED_PROVIDER_SOURCE_POLICY
    ):
        raise ValueError(
            f"Open teaching EPUB provider identity is not in the trusted registry: "
            f"{artifact['sourceId']}"
        )
    aliases = TRUSTED_PROVIDER_IDENTITY_ALIASES[expected_provider]
    source_values = [
        str(source[field])
        for field in ("publisher", "discoveryUri", "documentUri", "attributionText")
        if isinstance(source.get(field), str) and str(source[field]).strip()
    ]
    opf_values = [value for _, value in opf_provenance]
    source_policy = TRUSTED_PROVIDER_SOURCE_POLICY[expected_provider]
    source_identity_matches = any(
        alias in _identity_token(value)
        for alias in aliases
        for value in source_values
    )
    document_host = (
        urlsplit(str(source["documentUri"])).hostname or ""
    ).casefold().rstrip(".")
    publisher_token = _identity_token(str(source["publisher"]))
    source_identity_matches = (
        document_host in source_policy["documentHosts"]
        or publisher_token in source_policy["publisherTokens"]
    )
    if not source_identity_matches:
        raise ValueError(
            f"Open teaching EPUB canonical provider is absent from source register: "
            f"{artifact['sourceId']}"
        )
    if not any(
        alias in _identity_token(value)
        for alias in aliases
        for value in opf_values
    ):
        raise ValueError(
            f"Open teaching EPUB canonical provider is absent from OPF provenance: "
            f"{artifact['sourceId']}"
        )
    evidence: list[tuple[str, str]] = []
    for field in PROVIDER_SOURCE_FIELDS:
        value = source.get(field)
        if isinstance(value, str) and value.strip():
            evidence.append((f"sourceRegister.{field}", value))
    for field in PROVIDER_MANIFEST_FIELDS:
        value = artifact.get(field)
        if isinstance(value, str) and value.strip():
            evidence.append((f"manifest.{field}", value))
    evidence.extend((f"opf.{field}", value) for field, value in opf_provenance)
    for locator, value in evidence:
        if locator == "opf.rights" and NEGATIVE_RIGHTS_PATTERN.search(
            _rights_token_text(value)
        ):
            raise ValueError(
                f"EPUB OPF contains negative license language: "
                f"{artifact['sourceId']}"
            )
        disabled = _disabled_provider(value)
        if disabled is not None:
            raise ValueError(
                f"Open teaching EPUB uses disabled provider identity {disabled}: "
                f"{artifact['sourceId']}/{locator}"
            )
    digest_input = "\n".join(
        f"{locator}:{_identity_token(value)}" for locator, value in sorted(evidence)
    )
    return hashlib.sha256(digest_input.encode("utf-8")).hexdigest().upper()


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest().upper()


def _safe_archive_name(name: str) -> None:
    if not name or len(name) > MAX_ARCHIVE_ENTRY_NAME_CHARACTERS:
        raise ValueError("EPUB archive entry name is outside the safe range")
    path = PurePosixPath(name)
    if path.is_absolute() or ".." in path.parts or "\\" in name:
        raise ValueError(f"Unsafe EPUB archive entry: {name}")


def validated_archive_entries(archive: zipfile.ZipFile) -> list[zipfile.ZipInfo]:
    """Return entries only after applying the shared bounded-ZIP contract."""
    entries = archive.infolist()
    if not entries or len(entries) > MAX_ENTRIES:
        raise ValueError("EPUB entry count is outside the safe range")
    total_uncompressed = 0
    seen_names: set[str] = set()
    for entry in entries:
        _safe_archive_name(entry.filename)
        if entry.filename in seen_names:
            raise ValueError(f"EPUB archive entry is duplicated: {entry.filename}")
        seen_names.add(entry.filename)
        if entry.file_size > MAX_ENTRY_UNCOMPRESSED_BYTES:
            raise ValueError(f"EPUB entry is too large: {entry.filename}")
        total_uncompressed += entry.file_size
        if total_uncompressed > MAX_TOTAL_UNCOMPRESSED_BYTES:
            raise ValueError("EPUB uncompressed content is too large")
        if entry.compress_size > 0:
            ratio = entry.file_size / entry.compress_size
            if ratio > MAX_COMPRESSION_RATIO:
                raise ValueError(
                    f"EPUB entry compression ratio is unsafe: {entry.filename}"
                )
    return entries


def _rootfile_path(reader: BoundedArchiveReader) -> str:
    try:
        container = reader.read("META-INF/container.xml")
    except KeyError as error:
        raise ValueError("EPUB is missing META-INF/container.xml") from error
    try:
        root = ElementTree.fromstring(container)
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
    reader: BoundedArchiveReader,
    rootfile_path: str,
) -> tuple[str, str, list[tuple[str, str]]]:
    try:
        package = ElementTree.fromstring(reader.read(rootfile_path))
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
    provenance = []
    for element in package.iter():
        name = _local_xml_name(element.tag)
        text = " ".join("".join(element.itertext()).split()).strip()
        if name in OPF_PROVENANCE_NAMES and text:
            provenance.append((name, text))
        elif name == "meta":
            value = str(element.attrib.get("content", "")).strip() or text
            property_name = str(
                element.attrib.get("property", element.attrib.get("name", "metadata"))
            ).strip()
            if value:
                provenance.append((f"meta:{property_name}", value))
        elif name == "link":
            href = str(element.attrib.get("href", "")).strip()
            if href:
                provenance.append(("link", href))
    return title, language, provenance


def _archive_metrics(
    reader: BoundedArchiveReader,
    expected_license_uri: str,
) -> tuple[int, int, int, list[str], dict[str, int]]:
    entries = reader.entries
    total_uncompressed = 0
    xhtml_count = 0
    license_evidence_paths: set[str] = set()
    marker_counts = {name: 0 for name in MARKERS}
    for entry in entries:
        total_uncompressed += entry.file_size
        suffix = PurePosixPath(entry.filename).suffix.lower()
        if suffix not in {".xhtml", ".html", ".htm", ".opf", ".xml"}:
            continue
        if suffix in {".xhtml", ".html", ".htm"}:
            xhtml_count += 1
        raw = reader.read(entry)
        text = raw.decode("utf-8", errors="ignore")
        if RIGHTS_DOCUMENT_PATTERN.search(entry.filename):
            try:
                visible_rights_text, link_targets = _rights_evidence(raw)
            except (UnicodeError, ValueError) as error:
                raise ValueError(
                    f"EPUB rights document is not safely readable: {entry.filename}"
                ) from error
            if NEGATIVE_RIGHTS_PATTERN.search(
                _rights_token_text(visible_rights_text)
            ):
                raise ValueError(
                    f"EPUB rights document contains negative license language: "
                    f"{entry.filename}"
                )
            visible_uris = LICENSE_URI_PATTERN.findall(visible_rights_text)
            for raw_uri in visible_uris:
                candidate = raw_uri.rstrip(".,;:)]}")
                try:
                    normalized_uri = _normalize_visible_license_evidence_uri(candidate)
                except ValueError:
                    continue
                if normalized_uri == expected_license_uri:
                    license_evidence_paths.add(entry.filename)
            for raw_uri in link_targets:
                candidate = raw_uri.rstrip(".,;:)]}")
                if urlsplit(candidate).scheme.casefold() != "https":
                    continue
                try:
                    normalized_uri = normalize_license_uri(candidate)
                except ValueError:
                    continue
                if normalized_uri == expected_license_uri:
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


def _local_xml_name(value: str) -> str:
    return value.rsplit("}", 1)[-1].lower()


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
) -> tuple[dict[str, Any], str]:
    source_id = str(artifact["sourceId"])
    source = sources_by_id.get(source_id)
    if source is None:
        raise ValueError(f"EPUB manifest source is absent from register: {source_id}")
    require_keys(source, SOURCE_KEYS, f"knowledge source {source_id}")
    for field in ("publisher", "independenceGroup", "discoveryUri", "documentUri"):
        value = source[field]
        if not isinstance(value, str) or not value.strip():
            raise ValueError(
                f"Open teaching EPUB provider provenance is incomplete: "
                f"{source_id}/{field}"
            )
    for field in ("discoveryUri", "documentUri"):
        split = urlsplit(str(source[field]).strip())
        if split.scheme.casefold() != "https" or not split.hostname:
            raise ValueError(
                f"Open teaching EPUB provider URI is invalid: {source_id}/{field}"
            )
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
    expected_license_uri = normalize_license_uri(artifact["expectedLicenseUri"])
    source_license_uri = normalize_license_uri(source["licenseUri"])
    license_expression = str(source.get("licenseExpression", "")).strip()
    if license_expression and NEGATIVE_RIGHTS_PATTERN.search(
        _rights_token_text(license_expression)
    ):
        raise ValueError(f"Open teaching EPUB has negative license metadata: {source_id}")
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
            source_license_uri,
            expected_license_uri,
        ),
    }
    for label, (actual, expected) in comparisons.items():
        if actual != expected:
            raise ValueError(f"Open teaching EPUB {label} mismatch: {source_id}")
    return source, expected_license_uri


def _pinned_epub_path(
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
    reader: BoundedArchiveReader,
    artifact: dict[str, Any],
) -> str:
    entries = reader.entries
    if (
        not entries
        or entries[0].filename != "mimetype"
        or entries[0].compress_type != zipfile.ZIP_STORED
    ):
        raise ValueError(f"EPUB mimetype entry is not canonical: {artifact['sourceId']}")
    try:
        media_type = reader.read("mimetype").decode("ascii").strip()
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


def _inspect_epub(
    path: Path,
    artifact: dict[str, Any],
    source: dict[str, Any],
    normalized_license_uri: str,
) -> dict[str, Any]:
    required_markers = _required_marker_thresholds(artifact)
    with zipfile.ZipFile(path) as archive:
        reader = BoundedArchiveReader(
            archive,
            max_decompressed_bytes=MAX_AUDIT_DECOMPRESSED_BYTES,
        )
        media_type = _canonical_media_type(reader, artifact)
        rootfile_path = _rootfile_path(reader)
        title, language, opf_provenance = _metadata(reader, rootfile_path)
        provider_evidence_sha256 = _assert_provider_allowed(
            artifact,
            source,
            opf_provenance,
        )
        metrics = _archive_metrics(reader, normalized_license_uri)
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
        "normalizedLicenseUri": normalized_license_uri,
        "licenseEvidencePaths": license_evidence_paths,
        "licenseEvidenceState": "URI_EVIDENCE_LOCATED_REVIEW_REQUIRED",
        "rightsReviewState": "REVIEW_REQUIRED",
        "providerIdentityEvidenceSha256": provider_evidence_sha256,
        "canonicalProviderIdentity": artifact["expectedProviderIdentity"],
        "providerIdentityReviewState": "REVIEW_REQUIRED",
        "requiredTeachingMarkers": required_markers,
        "teachingStructureMarkers": marker_counts,
    }


def audit_epub(
    project_root: Path,
    artifact: dict[str, Any],
    sources_by_id: dict[str, dict[str, Any]],
) -> dict[str, Any]:
    require_keys(artifact, ARTIFACT_KEYS, "open teaching EPUB artifact")
    source, normalized_license_uri = _validate_source(artifact, sources_by_id)
    path, actual_bytes, actual_sha256 = _pinned_epub_path(project_root, artifact)
    inspection = _inspect_epub(
        path,
        artifact,
        source,
        normalized_license_uri,
    )
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
    if not all(isinstance(source, dict) for source in sources):
        raise ValueError("Knowledge source register entries must be objects")
    register_source_ids = [str(source.get("sourceId", "")) for source in sources]
    if (
        any(not source_id for source_id in register_source_ids)
        or len(set(register_source_ids)) != len(register_source_ids)
    ):
        raise ValueError("Knowledge source register sourceIds must be non-empty and unique")
    if not all(isinstance(artifact, dict) for artifact in artifacts):
        raise ValueError("Open teaching EPUB manifest artifacts must be objects")
    artifact_source_ids = [str(artifact.get("sourceId", "")) for artifact in artifacts]
    if (
        any(not source_id for source_id in artifact_source_ids)
        or len(set(artifact_source_ids)) != len(artifact_source_ids)
    ):
        raise ValueError(
            "Open teaching EPUB manifest sourceIds must be non-empty and unique"
        )
    sources_by_id = {str(source["sourceId"]): source for source in sources}
    audits = [
        audit_epub(project_root, artifact, sources_by_id) for artifact in artifacts
    ]
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
