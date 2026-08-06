"""Build a rights-aware, locator-only inventory of complex visual candidates."""

from __future__ import annotations

import hashlib
import io
import json
import math
import platform
import re
import sys
import unicodedata
import warnings
import zipfile
from collections import Counter, defaultdict
from collections.abc import Callable, Hashable, Iterable
from pathlib import Path, PurePosixPath
from typing import Any
from urllib.parse import unquote, urlsplit
from xml.etree import ElementTree

from PIL import Image, __version__ as PILLOW_VERSION

from .epub_audit import (
    ArchiveReadBudgetExceeded,
    BoundedArchiveReader,
    audit_manifest,
)


TEXT_SUFFIXES = {".xhtml", ".html", ".htm"}
MAX_XHTML_ENTRY_BYTES = 8 * 1024 * 1024
MAX_XHTML_TOTAL_BYTES_PER_SOURCE = 128 * 1024 * 1024
MAX_IMAGE_ENTRY_BYTES = 32 * 1024 * 1024
MAX_IMAGE_REFERENCES_PER_SOURCE = 20_000
MAX_DECOMPRESSED_BYTES_PER_SOURCE = 512 * 1024 * 1024
MAX_RASTER_PIXELS = 40_000_000
MAX_RASTER_DIMENSION = 20_000
MAX_RASTER_FRAMES_PER_IMAGE = 512
MAX_TOTAL_RASTER_PIXELS_PER_SOURCE = 500_000_000
MAX_TOTAL_RASTER_FRAMES_PER_SOURCE = MAX_IMAGE_REFERENCES_PER_SOURCE
MAX_SVG_ELEMENTS = 100_000
MAX_DOM_ELEMENTS = 100_000
MAX_DOM_DEPTH = 256
MAX_XML_NAME_BYTES = 512
MAX_DOM_LOCATOR_BYTES = 8 * 1024
MAX_TOTAL_DOM_LOCATOR_BYTES = 8 * 1024 * 1024
MAX_NEARBY_EVIDENCE_ITEMS = 32
MAX_EXPLICIT_EVIDENCE_ELEMENTS = 32
MAX_BOOK_RIGHTS_LOCATORS = 16
MAX_OUTPUT_CANDIDATES = 5_000
MAX_OUTPUT_CANDIDATES_PER_SOURCE = 1_000
ANALYSIS_EDGE_THRESHOLD = 24
ANALYSIS_SIZE = 160
RIGHTS_REVIEW_REQUIRED = "REVIEW_REQUIRED"
LOCKED_PYTHON_IMPLEMENTATION = "CPython"
LOCKED_PYTHON_VERSION = (3, 12, 13)
LOCKED_PILLOW_VERSION = "12.2.0"
_XML_DECLARATION_PATTERN = re.compile(br"<!\s*(?:DOCTYPE|ENTITY)\b", re.IGNORECASE)
_RIGHTS_ATTRIBUTE_PATTERN = re.compile(
    r"(?:credit|attribution|copyright|licen[cs]e|rights|source)", re.IGNORECASE
)
_CAPTION_ATTRIBUTE_PATTERN = re.compile(r"caption", re.IGNORECASE)
_SVG_NUMBER_PATTERN = re.compile(
    r"^\s*([+-]?(?:[0-9]+(?:\.[0-9]*)?|\.[0-9]+)(?:[eE][+-]?[0-9]+)?)"
)
_SVG_NUMBER_SEARCH_PATTERN = re.compile(
    r"[+-]?(?:[0-9]+(?:\.[0-9]*)?|\.[0-9]+)(?:[eE][+-]?[0-9]+)?"
)
_SVG_DRAWABLE_TAGS = {
    "circle",
    "ellipse",
    "image",
    "line",
    "path",
    "polygon",
    "polyline",
    "rect",
    "text",
    "use",
}
_SVG_UNRESOLVED_VISIBILITY_ATTRIBUTES = {
    "clip-path",
    "filter",
    "mask",
    "transform",
}
_SVG_NON_RENDERING_CONTAINERS = {
    "clippath",
    "defs",
    "desc",
    "marker",
    "mask",
    "metadata",
    "pattern",
    "symbol",
    "title",
}


class ScanBudgetExceeded(ValueError):
    """Raised when continuing a source scan would exceed a cumulative budget."""


def require_locked_visual_runtime() -> None:
    actual_python = sys.version_info[:3]
    if (
        platform.python_implementation() != LOCKED_PYTHON_IMPLEMENTATION
        or actual_python != LOCKED_PYTHON_VERSION
        or PILLOW_VERSION != LOCKED_PILLOW_VERSION
    ):
        expected_python = ".".join(str(part) for part in LOCKED_PYTHON_VERSION)
        raise ValueError(
            "Visual-candidate runtime mismatch: requires "
            f"{LOCKED_PYTHON_IMPLEMENTATION} {expected_python} and "
            f"Pillow {LOCKED_PILLOW_VERSION}"
        )


def _sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest().upper()


def _canonical_payload_sha256(value: Any) -> str:
    payload = json.dumps(
        value,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")
    return _sha256_bytes(payload)


def _local_name(value: str) -> str:
    return value.rsplit("}", 1)[-1].lower()


def _validated_xml_name(value: Any) -> str:
    if not isinstance(value, str):
        raise ValueError("XML element name is invalid")
    if len(value.encode("utf-8")) > MAX_XML_NAME_BYTES:
        raise ValueError("XML element name is outside the safe range")
    return _local_name(value)


def _normalized_text(element: ElementTree.Element) -> str:
    value = unicodedata.normalize("NFKC", " ".join(element.itertext()))
    return " ".join(value.split()).strip()


def _attribute_words(element: ElementTree.Element) -> str:
    values = (
        element.attrib.get("class", ""),
        element.attrib.get("id", ""),
        element.attrib.get("role", ""),
        element.attrib.get("rel", ""),
        *element.attrib.keys(),
    )
    return " ".join(values)


def _safe_reference_path(document_path: str, reference: str) -> str | None:
    if len(reference) > 2_048 or "\\" in reference:
        return None
    split = urlsplit(reference)
    if split.scheme or split.netloc or not split.path:
        return None
    decoded = unquote(split.path)
    if decoded.startswith("/") or "\\" in decoded or "\x00" in decoded:
        return None
    stack = list(PurePosixPath(document_path).parent.parts)
    for part in PurePosixPath(decoded).parts:
        if part in {"", "."}:
            continue
        if part == "..":
            if not stack:
                return None
            stack.pop()
            continue
        stack.append(part)
    resolved = PurePosixPath(*stack).as_posix()
    return resolved if resolved and len(resolved) <= 2_048 else None


def _walk_elements(
    root: ElementTree.Element,
    *,
    include_locators: bool = True,
) -> tuple[
    list[ElementTree.Element],
    dict[int, ElementTree.Element],
    dict[int, int],
    dict[int, str],
]:
    elements: list[ElementTree.Element] = []
    parents: dict[int, ElementTree.Element] = {}
    ordinals: dict[int, int] = {}
    paths: dict[int, str] = {}
    root_name = _validated_xml_name(root.tag)
    root_path = f"/{root_name}[1]" if include_locators else ""
    total_locator_bytes = 0
    if include_locators:
        total_locator_bytes = len(root_path.encode("utf-8"))
    stack: list[tuple[ElementTree.Element, str, int]] = [
        (root, root_path, 1)
    ]
    while stack:
        element, path, depth = stack.pop()
        if depth > MAX_DOM_DEPTH:
            raise ValueError("XHTML DOM depth is outside the safe range")
        elements.append(element)
        if len(elements) > MAX_DOM_ELEMENTS:
            raise ValueError("XHTML DOM element count is outside the safe range")
        ordinals[id(element)] = len(elements)
        if include_locators:
            paths[id(element)] = path
        child_counts: Counter[str] = Counter()
        children_with_paths: list[tuple[ElementTree.Element, str, int]] = []
        for child in list(element):
            parents[id(child)] = element
            name = _validated_xml_name(child.tag)
            child_counts[name] += 1
            child_path = (
                f"{path}/{name}[{child_counts[name]}]" if include_locators else ""
            )
            if include_locators:
                child_path_bytes = len(child_path.encode("utf-8"))
                if child_path_bytes > MAX_DOM_LOCATOR_BYTES:
                    raise ValueError("XHTML DOM locator is outside the safe range")
                total_locator_bytes += child_path_bytes
                if total_locator_bytes > MAX_TOTAL_DOM_LOCATOR_BYTES:
                    raise ValueError(
                        "XHTML cumulative DOM locator budget exceeded"
                    )
            children_with_paths.append(
                (child, child_path, depth + 1)
            )
        stack.extend(reversed(children_with_paths))
    return elements, parents, ordinals, paths


def _image_reference(element: ElementTree.Element) -> str | None:
    tag = _local_name(element.tag)
    if tag == "img":
        return element.attrib.get("src")
    if tag == "image":
        return element.attrib.get("href") or element.attrib.get(
            "{http://www.w3.org/1999/xlink}href"
        )
    if tag == "object":
        return element.attrib.get("data")
    return None


def _nearest_figure(
    element: ElementTree.Element,
    parents: dict[int, ElementTree.Element],
) -> ElementTree.Element | None:
    current = element
    while True:
        parent = parents.get(id(current))
        if parent is None:
            return None
        if _local_name(parent.tag) == "figure":
            return parent
        current = parent


def _explicit_relationship_ids(element: ElementTree.Element) -> set[str]:
    references: set[str] = set()
    for attribute in ("aria-describedby", "aria-labelledby"):
        references.update(element.attrib.get(attribute, "").split())
    return {reference for reference in references if reference}


def _nearby_evidence(
    image_element: ElementTree.Element,
    *,
    entry_path: str,
    parents: dict[int, ElementTree.Element],
    ordinals: dict[int, int],
    paths: dict[int, str],
    elements_by_id: dict[str, ElementTree.Element],
    figure_image_counts: Counter[int],
    evidence_cache: dict[tuple[int, str, str], list[dict[str, Any]]],
    kind: str,
) -> list[dict[str, Any]]:
    def matches(element: ElementTree.Element) -> bool:
        tag = _local_name(element.tag)
        attributes = _attribute_words(element)
        if kind == "caption":
            return tag == "figcaption" or bool(
                _CAPTION_ATTRIBUTE_PATTERN.search(attributes)
            )
        return bool(_RIGHTS_ATTRIBUTE_PATTERN.search(attributes))

    def item(
        element: ElementTree.Element,
        relationship: str,
        relationship_reference: str | None = None,
    ) -> dict[str, Any] | None:
        text = _normalized_text(element)
        if not text:
            return None
        dom_path = paths[id(element)]
        result = {
            "normalizedTextSha256": _sha256_bytes(text.encode("utf-8")),
            "normalizedTextCharacterCount": len(text),
            "relationship": relationship,
            "xhtmlLocator": {
                "entryPath": entry_path,
                "elementTag": _local_name(element.tag),
                "elementOrdinal": ordinals[id(element)],
                "domPath": dom_path,
                "domPathSha256": _sha256_bytes(dom_path.encode("utf-8")),
            },
        }
        if relationship_reference is not None:
            result["relationshipReferenceSha256"] = _sha256_bytes(
                relationship_reference.encode("utf-8")
            )
            result["relationshipReferenceOrdinal"] = ordinals[id(element)]
        return result

    evidence: list[dict[str, Any]] = []
    figure = _nearest_figure(image_element, parents)
    if figure is not None and figure_image_counts[id(figure)] == 1:
        figure_key = (id(figure), kind, "CURRENT_FIGURE")
        figure_evidence = evidence_cache.get(figure_key)
        if figure_evidence is None:
            figure_evidence = []
            for element in figure.iter():
                if (
                    element is not figure
                    and _nearest_figure(element, parents) is not figure
                ):
                    continue
                if not matches(element):
                    continue
                candidate = item(element, "CURRENT_FIGURE")
                if candidate is not None:
                    figure_evidence.append(candidate)
            evidence_cache[figure_key] = figure_evidence
        evidence.extend(figure_evidence)

    def collect_explicit(relationship_ids: set[str]) -> list[dict[str, Any]]:
        explicit_evidence: list[dict[str, Any]] = []
        for reference in sorted(relationship_ids):
            target = elements_by_id.get(reference)
            if target is None:
                continue
            target_key = (id(target), kind, "EXPLICIT_IDREF")
            target_evidence = evidence_cache.get(target_key)
            if target_evidence is None:
                target_evidence = []
                target_size = 0
                for _ in target.iter():
                    target_size += 1
                    if target_size > MAX_EXPLICIT_EVIDENCE_ELEMENTS:
                        break
                explicitly_described = kind == "caption"
                if target_size <= MAX_EXPLICIT_EVIDENCE_ELEMENTS and (
                    matches(target) or explicitly_described
                ):
                    candidate = item(
                        target,
                        "EXPLICIT_IDREF",
                        relationship_reference=reference,
                    )
                    if candidate is not None:
                        target_evidence.append(candidate)
                evidence_cache[target_key] = target_evidence
            explicit_evidence.extend(target_evidence)
        return explicit_evidence

    evidence.extend(collect_explicit(_explicit_relationship_ids(image_element)))
    if figure is not None:
        figure_relationship_key = (id(figure), kind, "FIGURE_IDREFS")
        figure_relationship_evidence = evidence_cache.get(figure_relationship_key)
        if figure_relationship_evidence is None:
            figure_relationship_evidence = collect_explicit(
                _explicit_relationship_ids(figure)
            )
            evidence_cache[figure_relationship_key] = figure_relationship_evidence
        evidence.extend(figure_relationship_evidence)

    unique = {
        (
            candidate["normalizedTextSha256"],
            candidate["relationship"],
            candidate["xhtmlLocator"]["domPath"],
        ): candidate
        for candidate in evidence
    }
    result = sorted(
        unique.values(),
        key=lambda item: (
            item["xhtmlLocator"]["entryPath"],
            item["xhtmlLocator"]["elementOrdinal"],
            item["relationship"],
            item["normalizedTextSha256"],
        ),
    )[:MAX_NEARBY_EVIDENCE_ITEMS]
    return result


def _raster_metrics(raw: bytes) -> tuple[dict[str, Any], dict[str, Any]]:
    with warnings.catch_warnings():
        warnings.simplefilter("error", Image.DecompressionBombWarning)
        with Image.open(io.BytesIO(raw)) as image:
            image.verify()
        with Image.open(io.BytesIO(raw)) as image:
            width, height = image.size
            if (
                width < 1
                or height < 1
                or width > MAX_RASTER_DIMENSION
                or height > MAX_RASTER_DIMENSION
                or width * height > MAX_RASTER_PIXELS
            ):
                raise ValueError("Raster image dimensions are outside the safe range")
            media_format = str(image.format or "").upper()
            if not media_format:
                raise ValueError("Raster image format is unknown")
            frame_count = max(1, int(getattr(image, "n_frames", 1)))
            if frame_count > MAX_RASTER_FRAMES_PER_IMAGE:
                raise ValueError("Raster frame count is outside the safe range")
            image.seek(0)
            sample = image.convert("RGBA")
            sample.thumbnail(
                (ANALYSIS_SIZE, ANALYSIS_SIZE),
                Image.Resampling.BOX,
            )
            background = Image.new("RGBA", sample.size, (255, 255, 255, 255))
            background.alpha_composite(sample)
            rgb = background.convert("RGB")
            pixels = list(rgb.get_flattened_data())
            sample_width, sample_height = rgb.size

    quantized = [(red // 32, green // 32, blue // 32) for red, green, blue in pixels]
    color_richness = len(set(quantized)) / 512.0
    border: list[tuple[int, int, int]] = []
    for y in range(sample_height):
        for x in range(sample_width):
            if x in {0, sample_width - 1} or y in {0, sample_height - 1}:
                border.append(quantized[y * sample_width + x])
    background_bin = Counter(border or quantized).most_common(1)[0][0]
    occupancy = sum(pixel != background_bin for pixel in quantized) / len(quantized)
    grayscale = [
        (299 * red + 587 * green + 114 * blue) // 1000
        for red, green, blue in pixels
    ]
    edge_hits = 0
    edge_comparisons = 0
    for y in range(sample_height):
        for x in range(sample_width):
            index = y * sample_width + x
            if x + 1 < sample_width:
                edge_comparisons += 1
                edge_hits += (
                    abs(grayscale[index] - grayscale[index + 1])
                    >= ANALYSIS_EDGE_THRESHOLD
                )
            if y + 1 < sample_height:
                edge_comparisons += 1
                edge_hits += (
                    abs(grayscale[index] - grayscale[index + sample_width])
                    >= ANALYSIS_EDGE_THRESHOLD
                )
    edge_density = edge_hits / max(1, edge_comparisons)
    area_component = min(1.0, math.log2(max(2, width * height)) / 24.0)
    edge_signal = min(1.0, edge_density / 0.08)
    noise_suppression = max(0.0, 1.0 - max(0.0, edge_density - 0.18) / 0.45)
    color_simplicity = max(0.0, 1.0 - color_richness / 0.30)
    diagram_occupancy = max(0.0, 1.0 - abs(occupancy - 0.25) / 0.55)
    structure_likelihood = (
        0.50 * edge_signal * noise_suppression
        + 0.30 * color_simplicity
        + 0.20 * diagram_occupancy
    )
    score = 0.15 * area_component + 0.85 * structure_likelihood
    dimensions = {
        "width": width,
        "height": height,
        "format": media_format,
        "frameCount": frame_count,
    }
    metrics = {
        "pixelArea": width * height,
        "aspectRatio": round(width / height, 6),
        "edgeDensity": round(edge_density, 6),
        "colorRichness": round(color_richness, 6),
        "foregroundOccupancy": round(occupancy, 6),
        "structuralElementDensity": None,
        "structureLikelihood": round(structure_likelihood, 6),
        "rankingScore": round(score, 6),
    }
    return dimensions, metrics


def _svg_dimension(value: str | None) -> float | None:
    if not value:
        return None
    match = _SVG_NUMBER_PATTERN.match(value)
    if match is None or value[match.end() :].strip().casefold() not in {"", "px"}:
        return None
    result = float(match.group(1))
    return result if math.isfinite(result) else None


def _svg_style(element: ElementTree.Element) -> dict[str, str]:
    result: dict[str, str] = {}
    for declaration in element.attrib.get("style", "").split(";"):
        if not declaration.strip():
            continue
        name, separator, value = declaration.partition(":")
        if not separator or not name.strip() or not value.strip():
            raise ValueError("SVG inline style is invalid")
        normalized_name = name.strip().casefold()
        if normalized_name in result:
            raise ValueError("SVG inline style repeats a property")
        result[normalized_name] = value.strip()
    return result


def _svg_float(value: str | None, *, default: float | None = None) -> float:
    if value is None:
        if default is None:
            raise ValueError("SVG geometry is incomplete")
        return default
    match = _SVG_NUMBER_PATTERN.match(value)
    if match is None or value[match.end() :].strip().casefold() not in {"", "px"}:
        raise ValueError("SVG geometry is not a finite absolute number")
    result = float(match.group(1))
    if not math.isfinite(result):
        raise ValueError("SVG geometry is not finite")
    return result


def _svg_opacity(value: str | None, *, default: float) -> float:
    result = _svg_float(value, default=default)
    if not 0.0 <= result <= 1.0:
        raise ValueError("SVG opacity is outside the safe range")
    return result


def _svg_box_area(
    box: tuple[float, float, float, float],
    viewport: tuple[float, float, float, float],
) -> float:
    left, top, right, bottom = box
    viewport_left, viewport_top, viewport_right, viewport_bottom = viewport
    width = max(0.0, min(right, viewport_right) - max(left, viewport_left))
    height = max(0.0, min(bottom, viewport_bottom) - max(top, viewport_top))
    return width * height


def _svg_paint_visible(value: str, properties: dict[str, str]) -> bool:
    normalized = value.strip().casefold()
    if normalized == "currentcolor":
        normalized = properties.get("color", "black").strip().casefold()
    if normalized.startswith("url("):
        raise ValueError("SVG paint-server visibility cannot be proven")
    if normalized in {"none", "transparent"}:
        return False
    if normalized.startswith("#"):
        digits = normalized[1:]
        if len(digits) == 4:
            return int(digits[-1], 16) > 0
        if len(digits) == 8:
            return int(digits[-2:], 16) > 0
        return len(digits) in {3, 6}
    functional = re.fullmatch(r"(?:rgba|hsla)\((.*)\)", normalized)
    if functional:
        components = [part.strip() for part in functional.group(1).split(",")]
        if len(components) != 4:
            raise ValueError("SVG alpha paint cannot be proven")
        alpha = components[-1]
        if alpha.endswith("%"):
            return _svg_float(alpha[:-1], default=0.0) > 0
        return _svg_opacity(alpha, default=1.0) > 0
    if "(" in normalized or ")" in normalized:
        raise ValueError("SVG functional paint cannot be proven")
    return bool(normalized)


def _svg_visible_geometry_area(
    element: ElementTree.Element,
    *,
    properties: dict[str, str],
    viewport: tuple[float, float, float, float],
) -> float:
    tag = _local_name(element.tag)
    fill = properties["fill"].strip().casefold()
    stroke = properties["stroke"].strip().casefold()
    if tag == "image":
        raise ValueError("SVG embedded-image visibility cannot be proven")
    fill_visible = (
        _svg_paint_visible(fill, properties)
        and _svg_opacity(properties.get("fill-opacity"), default=1.0) > 0
    )
    stroke_visible = (
        _svg_paint_visible(stroke, properties)
        and _svg_opacity(properties.get("stroke-opacity"), default=1.0) > 0
    )
    stroke_width = _svg_float(properties.get("stroke-width"), default=1.0)
    if stroke_width < 0:
        raise ValueError("SVG stroke width is negative")

    if tag == "rect":
        width = _svg_float(element.attrib.get("width"), default=0.0)
        height = _svg_float(element.attrib.get("height"), default=0.0)
        x = _svg_float(element.attrib.get("x"), default=0.0)
        y = _svg_float(element.attrib.get("y"), default=0.0)
        if width <= 0 or height <= 0 or (not fill_visible and not stroke_visible):
            return 0.0
        return _svg_box_area((x, y, x + width, y + height), viewport)
    if tag == "circle":
        radius = _svg_float(element.attrib.get("r"), default=0.0)
        center_x = _svg_float(element.attrib.get("cx"), default=0.0)
        center_y = _svg_float(element.attrib.get("cy"), default=0.0)
        if radius <= 0 or (not fill_visible and not stroke_visible):
            return 0.0
        return _svg_box_area(
            (center_x - radius, center_y - radius, center_x + radius, center_y + radius),
            viewport,
        )
    if tag == "ellipse":
        radius_x = _svg_float(element.attrib.get("rx"), default=0.0)
        radius_y = _svg_float(element.attrib.get("ry"), default=0.0)
        center_x = _svg_float(element.attrib.get("cx"), default=0.0)
        center_y = _svg_float(element.attrib.get("cy"), default=0.0)
        if radius_x <= 0 or radius_y <= 0 or (not fill_visible and not stroke_visible):
            return 0.0
        return _svg_box_area(
            (
                center_x - radius_x,
                center_y - radius_y,
                center_x + radius_x,
                center_y + radius_y,
            ),
            viewport,
        )
    if tag == "line":
        x1 = _svg_float(element.attrib.get("x1"), default=0.0)
        y1 = _svg_float(element.attrib.get("y1"), default=0.0)
        x2 = _svg_float(element.attrib.get("x2"), default=0.0)
        y2 = _svg_float(element.attrib.get("y2"), default=0.0)
        if not stroke_visible or stroke_width <= 0:
            return 0.0
        length = math.hypot(x2 - x1, y2 - y1)
        if length <= 0:
            return 0.0
        box_area = _svg_box_area(
            (
                min(x1, x2) - stroke_width / 2,
                min(y1, y2) - stroke_width / 2,
                max(x1, x2) + stroke_width / 2,
                max(y1, y2) + stroke_width / 2,
            ),
            viewport,
        )
        return min(box_area, length * stroke_width)
    if tag == "path":
        raise ValueError("SVG path visibility cannot be proven without rendering")
    if tag in {"polygon", "polyline"}:
        geometry = element.attrib.get("points", "")
        numbers = [float(value) for value in _SVG_NUMBER_SEARCH_PATTERN.findall(geometry)]
        remainder = _SVG_NUMBER_SEARCH_PATTERN.sub("", geometry)
        if remainder.strip(" ,\t\r\n") or len(numbers) % 2:
            raise ValueError("SVG point geometry is invalid")
        points = list(zip(numbers[0::2], numbers[1::2], strict=True))
        required_points = 3 if tag == "polygon" else 2
        if len(points) < required_points or len(set(points)) < required_points:
            return 0.0
        x_values = [point[0] for point in points]
        y_values = [point[1] for point in points]
        box = (min(x_values), min(y_values), max(x_values), max(y_values))
        if tag == "polygon" and fill_visible:
            polygon_area = abs(
                sum(
                    x_values[index] * y_values[(index + 1) % len(points)]
                    - x_values[(index + 1) % len(points)] * y_values[index]
                    for index in range(len(points))
                )
            ) / 2.0
            if polygon_area > 0:
                return min(_svg_box_area(box, viewport), polygon_area)
        if stroke_visible and stroke_width > 0:
            return _svg_box_area(
                (
                    box[0] - stroke_width / 2,
                    box[1] - stroke_width / 2,
                    box[2] + stroke_width / 2,
                    box[3] + stroke_width / 2,
                ),
                viewport,
            )
        return 0.0
    if tag == "text":
        text = _normalized_text(element)
        font_size = _svg_float(properties.get("font-size"), default=16.0)
        if not text or font_size <= 0 or (not fill_visible and not stroke_visible):
            return 0.0
        x = _svg_float(element.attrib.get("x"), default=0.0)
        y = _svg_float(element.attrib.get("y"), default=0.0)
        width = max(font_size * 0.5, len(text) * font_size * 0.5)
        return _svg_box_area((x, y - font_size, x + width, y), viewport)
    if tag == "use":
        raise ValueError("SVG use visibility cannot be proven without rendering")
    return 0.0


def _svg_metrics(raw: bytes) -> tuple[dict[str, Any], dict[str, Any]]:
    if _XML_DECLARATION_PATTERN.search(raw):
        raise ValueError("SVG declarations are not accepted")
    root = ElementTree.fromstring(raw)
    if _local_name(root.tag) != "svg":
        raise ValueError("Image entry is not an SVG document")
    elements, _, _, _ = _walk_elements(root, include_locators=False)
    if len(elements) > MAX_SVG_ELEMENTS:
        raise ValueError("SVG element count is outside the safe range")
    if any(_local_name(element.tag) == "style" for element in elements):
        raise ValueError("SVG stylesheet visibility cannot be proven")
    view_box = [
        float(part)
        for part in re.split(r"[\s,]+", root.attrib.get("viewBox", "").strip())
        if part
    ]
    if view_box and (
        len(view_box) != 4
        or any(not math.isfinite(part) for part in view_box)
        or view_box[2] <= 0
        or view_box[3] <= 0
    ):
        raise ValueError("SVG viewBox is missing or invalid")
    width = _svg_dimension(root.attrib.get("width"))
    height = _svg_dimension(root.attrib.get("height"))
    if (width is None or height is None) and len(view_box) == 4:
        width = width or view_box[2]
        height = height or view_box[3]
    if width is None or height is None or width <= 0 or height <= 0:
        raise ValueError("SVG dimensions are missing or invalid")
    if width > MAX_RASTER_DIMENSION or height > MAX_RASTER_DIMENSION:
        raise ValueError("SVG dimensions are outside the safe range")
    if view_box:
        viewport = (
            view_box[0],
            view_box[1],
            view_box[0] + view_box[2],
            view_box[1] + view_box[3],
        )
    else:
        viewport = (0.0, 0.0, width, height)
    inherited_defaults = {
        "fill": "black",
        "stroke": "none",
        "fill-opacity": "1",
        "stroke-opacity": "1",
        "stroke-width": "1",
        "font-size": "16",
        "visibility": "visible",
    }
    visible_area = 0.0
    visible_drawable_count = 0
    colors: set[str] = set()
    stack: list[tuple[ElementTree.Element, dict[str, str], float, bool]] = [
        (root, inherited_defaults, 1.0, False)
    ]
    while stack:
        element, inherited, parent_opacity, parent_hidden = stack.pop()
        if element.attrib.get("class"):
            raise ValueError("SVG class visibility cannot be proven")
        style = _svg_style(element)
        if any(
            attribute in element.attrib or attribute in style
            for attribute in _SVG_UNRESOLVED_VISIBILITY_ATTRIBUTES
        ):
            raise ValueError("SVG visibility uses unsupported rendering effects")
        properties = dict(inherited)
        for property_name in properties:
            if property_name in element.attrib:
                properties[property_name] = element.attrib[property_name]
            if property_name in style:
                properties[property_name] = style[property_name]
        display = style.get("display", element.attrib.get("display", "inline"))
        display_value = display.strip().casefold()
        if display_value not in {"block", "contents", "inline", "inline-block", "none"}:
            raise ValueError("SVG display value cannot be proven")
        visibility = properties["visibility"].strip().casefold()
        if visibility not in {"visible", "hidden", "collapse"}:
            raise ValueError("SVG visibility value cannot be proven")
        opacity = parent_opacity * _svg_opacity(
            style.get("opacity", element.attrib.get("opacity")),
            default=1.0,
        )
        hidden = (
            parent_hidden
            or display_value == "none"
            or visibility in {"hidden", "collapse"}
            or opacity <= 0
            or _local_name(element.tag) in _SVG_NON_RENDERING_CONTAINERS
        )
        if not hidden and _local_name(element.tag) in _SVG_DRAWABLE_TAGS:
            drawable_area = _svg_visible_geometry_area(
                element,
                properties=properties,
                viewport=viewport,
            )
            if drawable_area > 0:
                visible_drawable_count += 1
                visible_area += drawable_area * opacity
        for attribute in ("fill", "stroke", "color"):
            value = style.get(attribute, element.attrib.get(attribute))
            if value and value.lower() not in {"none", "transparent"}:
                colors.add(value.strip().casefold())
        for child in reversed(list(element)):
            stack.append((child, properties, opacity, hidden))
    viewport_area = (viewport[2] - viewport[0]) * (viewport[3] - viewport[1])
    visible_area = min(viewport_area, visible_area)
    if visible_drawable_count == 0 or visible_area <= 0:
        raise ValueError("SVG has no provably visible non-zero-area drawing")
    structural_density = min(1.0, math.log2(visible_drawable_count + 1) / 6.0)
    color_richness = min(1.0, len(colors) / 32.0)
    visible_area_ratio = visible_area / viewport_area
    area = int(round(width * height))
    area_component = min(1.0, math.log2(max(2, area)) / 24.0)
    structure_likelihood = min(
        1.0,
        0.80 * structural_density + 0.20 * min(1.0, visible_area_ratio * 4.0),
    )
    score = 0.15 * area_component + 0.85 * structure_likelihood
    dimensions = {
        "width": round(width, 3),
        "height": round(height, 3),
        "format": "SVG",
        "frameCount": 1,
    }
    metrics = {
        "pixelArea": area,
        "aspectRatio": round(width / height, 6),
        "edgeDensity": None,
        "colorRichness": round(color_richness, 6),
        "foregroundOccupancy": None,
        "structuralElementDensity": round(structural_density, 6),
        "visibleArea": round(visible_area, 6),
        "visibleAreaRatio": round(visible_area_ratio, 6),
        "structureLikelihood": round(structure_likelihood, 6),
        "rankingScore": round(score, 6),
    }
    return dimensions, metrics


def _image_metrics(
    raw: bytes,
    archive_path: str,
) -> tuple[dict[str, Any], dict[str, Any]]:
    if PurePosixPath(archive_path).suffix.lower() == ".svg":
        return _svg_metrics(raw)
    return _raster_metrics(raw)


def _candidate_sort_key(candidate: dict[str, Any]) -> tuple[Any, ...]:
    return (
        -candidate["complexity"]["rankingScore"],
        candidate["sourceId"],
        candidate["imageSha256"],
        candidate["archiveEntryPath"],
        candidate["xhtmlLocator"]["entryPath"],
        candidate["xhtmlLocator"]["elementOrdinal"],
    )


def _merged_evidence(
    left: list[dict[str, Any]],
    right: list[dict[str, Any]],
) -> list[dict[str, Any]]:
    unique = {
        (
            item["normalizedTextSha256"],
            item["relationship"],
            item.get("relationshipReferenceSha256"),
            item["xhtmlLocator"]["entryPath"],
            item["xhtmlLocator"]["domPath"],
        ): item
        for item in left + right
    }
    return sorted(
        unique.values(),
        key=lambda item: (
            item["xhtmlLocator"]["entryPath"],
            item["xhtmlLocator"]["elementOrdinal"],
            item["relationship"],
            item["normalizedTextSha256"],
        ),
    )[:MAX_NEARBY_EVIDENCE_ITEMS]


def _validate_selection_limits(candidate_limit: int, per_source_limit: int) -> None:
    if (
        isinstance(candidate_limit, bool)
        or not isinstance(candidate_limit, int)
        or not 1 <= candidate_limit <= MAX_OUTPUT_CANDIDATES
    ):
        raise ValueError("Candidate limit is outside the safe range")
    if (
        isinstance(per_source_limit, bool)
        or not isinstance(per_source_limit, int)
        or not 1 <= per_source_limit <= MAX_OUTPUT_CANDIDATES_PER_SOURCE
    ):
        raise ValueError("Per-source candidate limit is outside the safe range")


def select_visual_candidates(
    candidates: Iterable[dict[str, Any]],
    *,
    candidate_limit: int,
    per_source_limit: int,
    diversity_key: Callable[[dict[str, Any]], Hashable] | None = None,
) -> list[dict[str, Any]]:
    """Select deterministically; callers may add a reviewed diversity bucket seam."""
    _validate_selection_limits(candidate_limit, per_source_limit)
    ranked = sorted(candidates, key=_candidate_sort_key)
    if diversity_key is None:
        ordered = ranked
    else:
        buckets: dict[Hashable, list[dict[str, Any]]] = defaultdict(list)
        for candidate in ranked:
            buckets[diversity_key(candidate)].append(candidate)
        ordered = []
        bucket_keys = sorted(buckets, key=lambda key: repr(key))
        offset = 0
        while True:
            added = False
            for key in bucket_keys:
                if offset < len(buckets[key]):
                    ordered.append(buckets[key][offset])
                    added = True
            if not added:
                break
            offset += 1
    result: list[dict[str, Any]] = []
    source_counts: Counter[str] = Counter()
    for candidate in ordered:
        source_id = candidate["sourceId"]
        if source_counts[source_id] >= per_source_limit:
            continue
        result.append(candidate)
        source_counts[source_id] += 1
        if len(result) >= candidate_limit:
            break
    return result


def _document_visuals(
    raw: bytes,
    document_info: zipfile.ZipInfo,
    entries_by_name: dict[str, zipfile.ZipInfo],
    *,
    remaining_reference_budget: int,
) -> list[dict[str, Any]]:
    if document_info.file_size > MAX_XHTML_ENTRY_BYTES:
        raise ValueError("XHTML entry is outside the safe range")
    if _XML_DECLARATION_PATTERN.search(raw):
        raise ValueError("XHTML declarations are not accepted")
    root = ElementTree.fromstring(raw)
    elements, parents, ordinals, paths = _walk_elements(root)
    elements_by_id: dict[str, ElementTree.Element] = {}
    figure_image_counts: Counter[int] = Counter()
    evidence_cache: dict[
        tuple[int, str, str],
        list[dict[str, Any]],
    ] = {}
    for element in elements:
        element_id = element.attrib.get("id", "").strip()
        if not element_id:
            continue
        if element_id in elements_by_id:
            raise ValueError("XHTML relationship IDs must be unique")
        elements_by_id[element_id] = element
    for element in elements:
        if _image_reference(element):
            figure = _nearest_figure(element, parents)
            if figure is not None:
                figure_image_counts[id(figure)] += 1
    visuals: list[dict[str, Any]] = []
    for element in elements:
        reference = _image_reference(element)
        if not reference:
            continue
        image_path = _safe_reference_path(document_info.filename, reference)
        image_info = entries_by_name.get(image_path or "")
        if (
            image_info is None
            or image_info.is_dir()
            or image_info.file_size > MAX_IMAGE_ENTRY_BYTES
        ):
            continue
        if len(visuals) >= remaining_reference_budget:
            raise ScanBudgetExceeded("Visual-candidate reference budget exceeded")
        dom_path = paths[id(element)]
        visuals.append(
            {
                "imageInfo": image_info,
                "xhtmlLocator": {
                    "entryPath": document_info.filename,
                    "elementTag": _local_name(element.tag),
                    "elementOrdinal": ordinals[id(element)],
                    "domPath": dom_path,
                    "domPathSha256": _sha256_bytes(dom_path.encode("utf-8")),
                },
                "captionEvidence": _nearby_evidence(
                    element,
                    entry_path=document_info.filename,
                    parents=parents,
                    ordinals=ordinals,
                    paths=paths,
                    elements_by_id=elements_by_id,
                    figure_image_counts=figure_image_counts,
                    evidence_cache=evidence_cache,
                    kind="caption",
                ),
                "creditEvidence": _nearby_evidence(
                    element,
                    entry_path=document_info.filename,
                    parents=parents,
                    ordinals=ordinals,
                    paths=paths,
                    elements_by_id=elements_by_id,
                    figure_image_counts=figure_image_counts,
                    evidence_cache=evidence_cache,
                    kind="credit",
                ),
            }
        )
    return visuals


def _scan_artifact(
    artifact_root: Path,
    artifact_audit: dict[str, Any],
) -> tuple[list[dict[str, Any]], dict[str, int]]:
    source_id = str(artifact_audit["sourceId"])
    epub_path = (artifact_root / str(artifact_audit["localPath"])).resolve()
    candidates_by_hash: dict[str, dict[str, Any]] = {}
    image_cache: dict[
        str,
        tuple[str, dict[str, Any], dict[str, Any]] | None,
    ] = {}
    isolated_documents = 0
    isolated_images = 0
    isolated_image_references = 0
    duplicate_images = 0
    image_entry_cache_hits = 0
    decoded_image_entries = 0
    reference_count = 0
    xhtml_bytes = 0
    total_raster_pixels = 0
    total_raster_frames = 0
    with zipfile.ZipFile(epub_path) as archive:
        reader = BoundedArchiveReader(
            archive,
            max_decompressed_bytes=MAX_DECOMPRESSED_BYTES_PER_SOURCE,
        )
        entries_by_name = reader.entries_by_name
        documents = sorted(
            (
                entry
                for entry in reader.entries
                if PurePosixPath(entry.filename).suffix.lower() in TEXT_SUFFIXES
            ),
            key=lambda entry: entry.filename,
        )
        for document in documents:
            if document.file_size > MAX_XHTML_ENTRY_BYTES:
                isolated_documents += 1
                continue
            xhtml_bytes += document.file_size
            if xhtml_bytes > MAX_XHTML_TOTAL_BYTES_PER_SOURCE:
                raise ScanBudgetExceeded("Visual-candidate XHTML budget exceeded")
            try:
                raw_document = reader.read(document)
                visuals = _document_visuals(
                    raw_document,
                    document,
                    entries_by_name,
                    remaining_reference_budget=(
                        MAX_IMAGE_REFERENCES_PER_SOURCE - reference_count
                    ),
                )
            except (ArchiveReadBudgetExceeded, ScanBudgetExceeded) as error:
                raise ScanBudgetExceeded(str(error)) from error
            except Exception:
                isolated_documents += 1
                continue
            reference_count += len(visuals)
            if reference_count > MAX_IMAGE_REFERENCES_PER_SOURCE:
                raise ScanBudgetExceeded(
                    "Visual-candidate reference budget exceeded"
                )
            for visual in visuals:
                image_info = visual["imageInfo"]
                if image_info.filename in image_cache:
                    image_entry_cache_hits += 1
                    cached_analysis = image_cache[image_info.filename]
                    if cached_analysis is None:
                        isolated_image_references += 1
                        continue
                    image_sha256, dimensions, complexity = cached_analysis
                else:
                    try:
                        raw_image = reader.read(image_info)
                        image_sha256 = _sha256_bytes(raw_image)
                        dimensions, complexity = _image_metrics(
                            raw_image,
                            image_info.filename,
                        )
                        if dimensions["format"] != "SVG":
                            projected_pixels = (
                                total_raster_pixels + complexity["pixelArea"]
                            )
                            projected_frames = (
                                total_raster_frames + dimensions["frameCount"]
                            )
                            if projected_pixels > MAX_TOTAL_RASTER_PIXELS_PER_SOURCE:
                                raise ScanBudgetExceeded(
                                    "Visual-candidate raster pixel budget exceeded"
                                )
                            if projected_frames > MAX_TOTAL_RASTER_FRAMES_PER_SOURCE:
                                raise ScanBudgetExceeded(
                                    "Visual-candidate raster frame budget exceeded"
                                )
                            total_raster_pixels = projected_pixels
                            total_raster_frames = projected_frames
                    except (ArchiveReadBudgetExceeded, ScanBudgetExceeded) as error:
                        raise ScanBudgetExceeded(str(error)) from error
                    except Exception:
                        image_cache[image_info.filename] = None
                        isolated_images += 1
                        isolated_image_references += 1
                        continue
                    decoded_image_entries += 1
                    image_cache[image_info.filename] = (
                        image_sha256,
                        dimensions,
                        complexity,
                    )
                occurrence = {
                    "archiveEntryPath": image_info.filename,
                    "xhtmlLocator": visual["xhtmlLocator"],
                }
                existing = candidates_by_hash.get(image_sha256)
                if existing is not None:
                    duplicate_images += 1
                    existing["occurrences"].append(occurrence)
                    existing["captionEvidence"] = _merged_evidence(
                        existing["captionEvidence"],
                        visual["captionEvidence"],
                    )
                    existing["creditEvidence"] = _merged_evidence(
                        existing["creditEvidence"],
                        visual["creditEvidence"],
                    )
                    continue
                license_evidence_paths = list(artifact_audit["licenseEvidencePaths"])
                license_path_digest = _sha256_bytes(
                    "\n".join(license_evidence_paths).encode("utf-8")
                )
                candidates_by_hash[image_sha256] = {
                    "sourceId": source_id,
                    "canonicalProviderIdentity": artifact_audit[
                        "canonicalProviderIdentity"
                    ],
                    "archiveEntryPath": image_info.filename,
                    "imageSha256": image_sha256,
                    "image": dimensions,
                    "xhtmlLocator": visual["xhtmlLocator"],
                    "occurrences": [occurrence],
                    "captionEvidence": visual["captionEvidence"],
                    "creditEvidence": visual["creditEvidence"],
                    "bookLicense": {
                        "licenseUri": artifact_audit["normalizedLicenseUri"],
                        "evidenceEntryPaths": license_evidence_paths[
                            :MAX_BOOK_RIGHTS_LOCATORS
                        ],
                        "evidenceEntryPathCount": len(license_evidence_paths),
                        "evidenceEntryPathsSha256": license_path_digest,
                    },
                    "rightsReviewState": RIGHTS_REVIEW_REQUIRED,
                    "complexity": complexity,
                }
    candidates = list(candidates_by_hash.values())
    for candidate in candidates:
        candidate["occurrences"].sort(
            key=lambda occurrence: (
                occurrence["archiveEntryPath"],
                occurrence["xhtmlLocator"]["entryPath"],
                occurrence["xhtmlLocator"]["elementOrdinal"],
            )
        )
    return candidates, {
        "referencedImageCount": reference_count,
        "isolatedDocumentCount": isolated_documents,
        "isolatedImageCount": isolated_images,
        "isolatedImageReferenceCount": isolated_image_references,
        "duplicateImageCount": duplicate_images,
        "decodedImageEntryCount": decoded_image_entries,
        "imageEntryCacheHitCount": image_entry_cache_hits,
        "decompressedEntryCount": reader.cached_entry_count,
        "decompressedByteCount": reader.decompressed_bytes,
        "rasterPixelCount": total_raster_pixels,
        "rasterFrameCount": total_raster_frames,
    }


def build_visual_candidate_inventory(
    artifact_root: Path,
    manifest: dict[str, Any],
    register: dict[str, Any],
    *,
    candidate_limit: int,
    per_source_limit: int,
) -> tuple[dict[str, Any], dict[str, Any]]:
    require_locked_visual_runtime()
    _validate_selection_limits(candidate_limit, per_source_limit)
    audit_report, _ = audit_manifest(artifact_root, manifest, register)
    all_candidates: list[dict[str, Any]] = []
    isolation_totals: Counter[str] = Counter()
    for artifact_audit in sorted(
        audit_report["artifacts"], key=lambda artifact: artifact["sourceId"]
    ):
        candidates, isolation = _scan_artifact(artifact_root, artifact_audit)
        all_candidates.extend(candidates)
        isolation_totals.update(isolation)
    if not all_candidates:
        raise ValueError("Visual-candidate scan produced no eligible images")
    selected = select_visual_candidates(
        all_candidates,
        candidate_limit=candidate_limit,
        per_source_limit=per_source_limit,
    )
    inventory = {
        "schemaVersion": 1,
        "inventoryId": "open-visual-candidate-inventory-2026-v1",
        "manifestId": manifest["manifestId"],
        "sourceRegisterId": register["registerId"],
        "updatedAtEpochMillis": manifest["updatedAtEpochMillis"],
        "runtimeContract": {
            "pythonImplementation": LOCKED_PYTHON_IMPLEMENTATION,
            "pythonVersion": ".".join(
                str(part) for part in LOCKED_PYTHON_VERSION
            ),
            "pillowVersion": LOCKED_PILLOW_VERSION,
        },
        "boundary": {
            "locatorOnly": True,
            "imageBytesIncluded": False,
            "rawCaptionOrCreditTextIncluded": False,
            "semanticCorrectnessClaimed": False,
            "figureFamilyAutomaticallyClassified": False,
            "humanRightsReviewRequired": True,
            "formalKnowledgeCoverageContribution": 0,
        },
        "selection": {
            "candidateLimit": candidate_limit,
            "perSourceLimit": per_source_limit,
            "rankedByGenericVisualComplexityOnly": True,
        },
        "scanIsolation": dict(sorted(isolation_totals.items())),
        "candidates": selected,
    }
    if any(
        candidate.get("rightsReviewState") != RIGHTS_REVIEW_REQUIRED
        for candidate in selected
    ):
        raise ValueError("Visual-candidate rights review state cannot be weakened")
    selected_source_counts = Counter(
        str(candidate["sourceId"]) for candidate in selected
    )
    summary = {
        "inventoryId": inventory["inventoryId"],
        "inventorySha256": _canonical_payload_sha256(inventory),
        "manifestSha256": _canonical_payload_sha256(manifest),
        "sourceRegisterSha256": _canonical_payload_sha256(register),
        "auditedSourceCount": len(audit_report["artifacts"]),
        "eligibleUniqueImageCount": len(all_candidates),
        "selectedCandidateCount": len(selected),
        "reviewRequiredCandidateCount": len(selected),
        "selectedCandidateCountsBySource": dict(sorted(selected_source_counts.items())),
        "scanIsolation": dict(sorted(isolation_totals.items())),
        "rightsReviewState": RIGHTS_REVIEW_REQUIRED,
        "semanticCorrectnessClaimed": False,
        "runtimeContract": inventory["runtimeContract"],
    }
    return inventory, summary
