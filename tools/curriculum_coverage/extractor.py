"""PDF loading, module slicing, and curriculum statement extraction."""

from __future__ import annotations

import hashlib
import logging
import re
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Callable

try:
    from pypdf import PdfReader
except ImportError as error:  # pragma: no cover - environment preflight
    raise SystemExit(
        "pypdf is required. Run this tool with the bundled Codex document Python "
        "runtime or install pypdf in an isolated tool environment."
    ) from error

from .model import (
    ParsedItemStart,
    PendingItem,
    SourceLine,
    is_noise,
    marker_key,
    normalize_display,
    strip_marker_brackets,
)


logging.getLogger("pypdf").setLevel(logging.ERROR)

CONTENT_MARKERS = {"内容要求", "学习目标与内容"}
STOP_MARKERS = {
    "教学提示",
    "学业要求",
    "活动建议",
    "教学建议",
    "学习要求",
}
DECIMAL_PATTERN = re.compile(r"^(\d+(?:\.\d+)+)\s*(.*)$")
ARABIC_DOT_PATTERN = re.compile(r"^(\d+)\.\s*(.*)$")
PAREN_NUMBER_PATTERN = re.compile(r"^\((\d+)\)\s*(.*)$")
PLAIN_UNIT_PATTERN = re.compile(r"^(\d+)\s+(.+)$")
THEME_PATTERN = re.compile(r"^主题\s*(\d+)\s*[:：]?\s*(.+)$")
EXAMPLE_PATTERN = re.compile(r"^(?:例|示例)\s*\d+")
CIRCLED_NUMBERS = {
    symbol: index
    for index, symbol in enumerate(
        "①②③④⑤⑥⑦⑧⑨⑩⑪⑫⑬⑭⑮⑯⑰⑱⑲⑳",
        start=1,
    )
}
ItemStartParser = Callable[
    [SourceLine, str, int | None, int | None],
    ParsedItemStart | None,
]


def load_pdf_lines(
    pdf_path: Path,
    source: dict[str, Any],
    course_pages: list[int],
) -> tuple[list[SourceLine], int]:
    payload = pdf_path.read_bytes()
    expected_length = int(source["contentLengthBytes"])
    actual_fingerprint = hashlib.sha256(payload).hexdigest().upper()
    if len(payload) != expected_length:
        raise ValueError(
            f"{pdf_path.name} byte count {len(payload)} != {expected_length}"
        )
    if actual_fingerprint != str(source["contentFingerprint"]).upper():
        raise ValueError(f"{pdf_path.name} SHA-256 does not match the source register")

    reader = PdfReader(str(pdf_path))
    page_count = len(reader.pages)
    if course_pages[1] > page_count:
        raise ValueError(f"{pdf_path.name} has only {page_count} pages")
    lines: list[SourceLine] = []
    for page_number in range(course_pages[0], course_pages[1] + 1):
        text = reader.pages[page_number - 1].extract_text() or ""
        for raw_line in text.splitlines():
            display = normalize_display(raw_line)
            line = SourceLine(
                page=page_number,
                raw=raw_line.strip(),
                text=display,
                key=marker_key(display),
            )
            if not is_noise(line):
                lines.append(line)
    return lines, page_count


def find_marker_index(
    lines: list[SourceLine],
    marker: str,
    start_page: int,
    end_page: int,
    after_index: int = 0,
) -> int:
    target = marker_key(marker)
    for index in range(after_index, len(lines)):
        line = lines[index]
        if line.page < start_page or line.page > end_page:
            continue
        if line.key == target or line.key.startswith(target):
            return index
    raise ValueError(
        f"Cannot locate marker '{marker}' on PDF pages {start_page}-{end_page}"
    )


def slice_module(lines: list[SourceLine], module: dict[str, Any]) -> list[SourceLine]:
    start_page, end_page = module["pages"]
    start_index = find_marker_index(
        lines,
        str(module["startMarker"]),
        start_page,
        end_page,
    )
    end_marker = module.get("endMarker")
    if end_marker:
        end_index = find_marker_index(
            lines,
            str(end_marker),
            start_page,
            end_page,
            after_index=start_index + 1,
        )
    else:
        end_index = next(
            (
                index
                for index in range(start_index + 1, len(lines))
                if lines[index].page > end_page
            ),
            len(lines),
        )
    chunk = [
        line
        for line in lines[start_index:end_index]
        if start_page <= line.page <= end_page
    ]
    if not chunk:
        raise ValueError(f"Module {module['slug']} has an empty source slice")
    return chunk


def _circled_start(
    line: SourceLine,
    mode: str,
    current_unit: int | None,
    current_parent: int | None,
) -> ParsedItemStart | None:
    raw_start = line.raw.strip()[:1]
    number = CIRCLED_NUMBERS.get(raw_start)
    if number is None:
        return None
    hierarchy = None
    if mode == "NUMBERED" and current_unit is not None:
        hierarchy = (
            (current_unit, current_parent, number)
            if current_parent is not None
            else (current_unit, number)
        )
    return ParsedItemStart(
        marker=raw_start,
        text=normalize_display(line.raw.strip()[1:]),
        hierarchy=hierarchy,
        kind="CIRCLED",
        current_unit=current_unit,
        current_parent=current_parent,
    )


def _decimal_start(
    line: SourceLine,
    _mode: str,
    _current_unit: int | None,
    _current_parent: int | None,
) -> ParsedItemStart | None:
    match = DECIMAL_PATTERN.match(line.text)
    if not match:
        return None
    code = match.group(1)
    hierarchy = tuple(int(part) for part in code.split("."))
    return ParsedItemStart(
        marker=code,
        text=match.group(2),
        hierarchy=hierarchy,
        kind="DECIMAL",
        current_unit=hierarchy[0],
        current_parent=hierarchy[-1],
    )


def _parenthesized_start(
    line: SourceLine,
    mode: str,
    current_unit: int | None,
    _current_parent: int | None,
) -> ParsedItemStart | None:
    match = PAREN_NUMBER_PATTERN.match(line.text)
    if not match:
        return None
    number = int(match.group(1))
    hierarchy = (
        (current_unit, number)
        if mode == "NUMBERED" and current_unit is not None
        else None
    )
    return ParsedItemStart(
        marker=f"({number})",
        text=match.group(2),
        hierarchy=hierarchy,
        kind="PARENTHESIZED",
        current_unit=current_unit,
        current_parent=number,
    )


def _arabic_dot_start(
    line: SourceLine,
    mode: str,
    current_unit: int | None,
    _current_parent: int | None,
) -> ParsedItemStart | None:
    match = ARABIC_DOT_PATTERN.match(line.text)
    if not match:
        return None
    number = int(match.group(1))
    numbered = mode == "NUMBERED"
    return ParsedItemStart(
        marker=f"{number}.",
        text=match.group(2),
        hierarchy=(number,) if numbered else None,
        kind="UNIT" if numbered else "LIST",
        current_unit=number if numbered else current_unit,
        current_parent=None,
    )


def _numbered_heading_start(
    line: SourceLine,
    mode: str,
    _current_unit: int | None,
    _current_parent: int | None,
) -> ParsedItemStart | None:
    if mode != "NUMBERED":
        return None
    theme = THEME_PATTERN.match(line.text)
    if theme:
        number = int(theme.group(1))
        return ParsedItemStart(
            marker=f"主题 {number}",
            text=theme.group(2),
            hierarchy=(number,),
            kind="THEME",
            current_unit=number,
            current_parent=None,
        )
    plain_unit = PLAIN_UNIT_PATTERN.match(line.text)
    if not plain_unit:
        return None
    number = int(plain_unit.group(1))
    return ParsedItemStart(
        marker=str(number),
        text=plain_unit.group(2),
        hierarchy=(number,),
        kind="UNIT",
        current_unit=number,
        current_parent=None,
    )


ITEM_START_PARSERS: tuple[ItemStartParser, ...] = (
    _circled_start,
    _decimal_start,
    _parenthesized_start,
    _arabic_dot_start,
    _numbered_heading_start,
)


def parse_item_start(
    line: SourceLine,
    mode: str,
    current_unit: int | None,
    current_parent: int | None,
) -> ParsedItemStart | None:
    for parser in ITEM_START_PARSERS:
        parsed = parser(line, mode, current_unit, current_parent)
        if parsed is not None:
            return parsed
    return None


def is_content_marker(line: SourceLine) -> bool:
    stripped = strip_marker_brackets(line.key)
    return any(
        stripped == marker or stripped.startswith(marker)
        for marker in CONTENT_MARKERS
    )


def is_stop_marker(line: SourceLine) -> bool:
    stripped = strip_marker_brackets(line.key)
    return any(
        stripped == marker or stripped.startswith(marker) for marker in STOP_MARKERS
    )


def is_auxiliary_start(line: SourceLine) -> bool:
    return (
        bool(EXAMPLE_PATTERN.match(line.text))
        or line.text.startswith("◆")
        or line.key.startswith("活动建议")
        or line.key.startswith("教学提示")
        or line.key.startswith("学业要求")
    )


def finalize_pending(
    pending: PendingItem | None,
    items: list[PendingItem],
) -> None:
    if pending is None:
        return
    text = normalize_display(" ".join(part for part in pending.parts if part))
    if text:
        pending.parts = [text]
        items.append(pending)


def _starts_content(line: SourceLine, mode: str) -> bool:
    return is_content_marker(line) or (
        mode == "GOALS" and line.key.startswith("1.学习目标与内容")
    )


def _stops_content(line: SourceLine, mode: str) -> bool:
    return is_stop_marker(line) or (
        mode == "GOALS" and line.key.startswith("2.教学提示")
    )


@dataclass(slots=True)
class ExtractionState:
    active: bool
    skip_auxiliary: bool = False
    current_unit: int | None = None
    current_parent: int | None = None
    pending: PendingItem | None = None
    items: list[PendingItem] = field(default_factory=list)

    def close_pending(self) -> None:
        finalize_pending(self.pending, self.items)
        self.pending = None

    def enter_scope(self, active: bool) -> None:
        self.close_pending()
        self.active = active
        self.skip_auxiliary = False

    def start_item(self, line: SourceLine, parsed: ParsedItemStart) -> None:
        self.close_pending()
        self.current_unit = parsed.current_unit
        self.current_parent = parsed.current_parent
        self.pending = PendingItem(
            page=line.page,
            raw_marker=parsed.marker,
            hierarchy=parsed.hierarchy,
            hierarchy_kind=parsed.kind,
            parts=[parsed.text],
        )
        self.skip_auxiliary = False


def _handle_scope_boundary(
    state: ExtractionState,
    line: SourceLine,
    mode: str,
) -> bool:
    if _starts_content(line, mode):
        state.enter_scope(active=True)
        return True
    if _stops_content(line, mode):
        state.enter_scope(active=False)
        return True
    return False


def _consume_active_line(
    state: ExtractionState,
    line: SourceLine,
    mode: str,
) -> None:
    parsed = parse_item_start(
        line,
        mode,
        state.current_unit,
        state.current_parent,
    )
    if parsed is not None:
        state.start_item(line, parsed)
        return
    if is_auxiliary_start(line):
        state.skip_auxiliary = True
        return
    if state.skip_auxiliary or state.pending is None:
        return
    if not line.key.startswith(("表", "续表", "课程类别", "内容要求")):
        state.pending.parts.append(line.text)


def extract_numbered_items(
    chunk: list[SourceLine],
    mode: str,
) -> list[PendingItem]:
    state = ExtractionState(active=mode == "NUMBERED")
    for line in chunk:
        if _handle_scope_boundary(state, line, mode):
            continue
        if state.active:
            _consume_active_line(state, line, mode)
    state.close_pending()
    return remove_parent_items(state.items)


def remove_parent_items(items: list[PendingItem]) -> list[PendingItem]:
    hierarchies = {
        item.hierarchy for item in items if item.hierarchy is not None
    }
    parent_hierarchies = {
        hierarchy[:length]
        for hierarchy in hierarchies
        for length in range(1, len(hierarchy))
    }
    return [
        item
        for item in items
        if item.hierarchy is None or item.hierarchy not in parent_hierarchies
    ]


def descriptive_summary(chunk: list[SourceLine]) -> PendingItem:
    parts: list[str] = []
    start_page = chunk[0].page
    for line in chunk[1:]:
        if is_content_marker(line) or is_stop_marker(line) or is_auxiliary_start(line):
            continue
        if parse_item_start(line, "DESCRIPTIVE", None, None) is not None:
            continue
        parts.append(line.text)
        if len("".join(parts)) >= 360:
            break
    summary = normalize_display(" ".join(parts))
    if not summary:
        summary = "课程标准声明了该模块范围，但正文未形成可机械分割的编号要求。"
    return PendingItem(
        page=start_page,
        raw_marker="范围",
        hierarchy=None,
        hierarchy_kind="SCOPE_SUMMARY",
        parts=[summary[:1200]],
    )
