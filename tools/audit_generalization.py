#!/usr/bin/env python3
"""Reject production branches that recognize individual questions or test fixtures."""

from __future__ import annotations

import argparse
import os
import re
import sys
from dataclasses import dataclass
from pathlib import Path


IDENTITY_NAMES = (
    "problemId",
    "problemRevisionId",
    "practiceUnitId",
    "questionId",
    "imageHash",
    "imageSha256",
    "imageFingerprint",
    "assetHash",
    "documentHash",
    "problemFingerprint",
    "questionFingerprint",
    "visualCaseId",
    "testName",
    "testCase",
    "fixtureId",
    "benchmarkId",
)
IDENTITY = r"(?:" + "|".join(map(re.escape, IDENTITY_NAMES)) + r")"
STATIC_LITERAL = r'"(?:\\.|[^"\\$]){1,}"'
EXCLUDED_PARTS = frozenset(
    {
        ".android",
        ".git",
        ".gradle",
        ".idea",
        ".toolchains",
        ".worktrees",
        "build",
        "generated",
        "out",
    },
)


@dataclass(frozen=True)
class Violation:
    path: Path
    line: int
    rule: str
    excerpt: str


LINE_PATTERNS = (
    (
        "literal-question-identity-comparison",
        re.compile(
            rf"\b{IDENTITY}\b\s*(?:==|!=)\s*{STATIC_LITERAL}"
            rf"|{STATIC_LITERAL}\s*(?:==|!=)\s*\b{IDENTITY}\b",
            re.IGNORECASE,
        ),
    ),
    (
        "literal-question-identity-method-branch",
        re.compile(
            rf"\b{IDENTITY}\b\s*\.\s*(?:equals|contains|startsWith|endsWith)"
            rf"\s*\(\s*{STATIC_LITERAL}",
            re.IGNORECASE,
        ),
    ),
)

BLOCK_PATTERNS = (
    (
        "literal-question-identity-when",
        re.compile(
            rf"\bwhen\s*\(\s*{IDENTITY}\s*\)\s*\{{"
            rf"(?:(?!\n\s*\}}).){{0,2000}}?{STATIC_LITERAL}\s*->",
            re.IGNORECASE | re.DOTALL,
        ),
    ),
    (
        "literal-question-identity-switch",
        re.compile(
            rf"\bswitch\s*\(\s*{IDENTITY}\s*\)\s*\{{"
            rf"(?:(?!\n\s*\}}).){{0,2000}}?\bcase\s+{STATIC_LITERAL}\s*:",
            re.IGNORECASE | re.DOTALL,
        ),
    ),
)


def production_sources(root: Path) -> list[Path]:
    result: list[Path] = []
    for directory, child_directories, file_names in os.walk(root):
        child_directories[:] = sorted(
            name for name in child_directories if name not in EXCLUDED_PARTS
        )
        directory_path = Path(directory)
        relative_parts = directory_path.relative_to(root).parts
        source_set_pairs = tuple(zip(relative_parts, relative_parts[1:]))
        if any(first == "src" and second != "main" for first, second in source_set_pairs):
            child_directories.clear()
            continue
        if not any(first == "src" and second == "main" for first, second in source_set_pairs):
            continue
        for file_name in sorted(file_names):
            path = directory_path / file_name
            if path.suffix in {".kt", ".java"}:
                result.append(path)
    return sorted(result)


def mask_comments(source: str) -> str:
    """Replace comments with spaces while preserving strings and line numbers."""
    chars = list(source)
    index = 0
    state = "code"
    while index < len(chars):
        current = chars[index]
        following = chars[index + 1] if index + 1 < len(chars) else ""
        triple = source[index : index + 3] == '"""'
        if state == "code":
            if current == "/" and following == "/":
                chars[index] = chars[index + 1] = " "
                index += 2
                state = "line-comment"
                continue
            if current == "/" and following == "*":
                chars[index] = chars[index + 1] = " "
                index += 2
                state = "block-comment"
                continue
            if triple:
                index += 3
                state = "triple-string"
                continue
            if current == '"':
                index += 1
                state = "string"
                continue
            if current == "'":
                index += 1
                state = "char"
                continue
        elif state == "line-comment":
            if current == "\n":
                state = "code"
            else:
                chars[index] = " "
        elif state == "block-comment":
            if current == "*" and following == "/":
                chars[index] = chars[index + 1] = " "
                index += 2
                state = "code"
                continue
            if current != "\n":
                chars[index] = " "
        elif state == "string":
            if current == "\\":
                index += 2
                continue
            if current == '"':
                state = "code"
        elif state == "triple-string":
            if triple:
                index += 3
                state = "code"
                continue
        elif state == "char":
            if current == "\\":
                index += 2
                continue
            if current == "'":
                state = "code"
        index += 1
    return "".join(chars)


def scan_source(path: Path, root: Path) -> list[Violation]:
    source = mask_comments(path.read_text(encoding="utf-8"))
    violations: list[Violation] = []
    for rule, pattern in (*LINE_PATTERNS, *BLOCK_PATTERNS):
        for match in pattern.finditer(source):
            line = source.count("\n", 0, match.start()) + 1
            excerpt = " ".join(match.group(0).split())[:180]
            violations.append(Violation(path.relative_to(root), line, rule, excerpt))
    return violations


def audit(root: Path) -> list[Violation]:
    violations: list[Violation] = []
    for path in production_sources(root):
        violations.extend(scan_source(path, root))
    return sorted(violations, key=lambda item: (str(item.path), item.line, item.rule))


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--root",
        type=Path,
        default=Path(__file__).resolve().parents[1],
        help="repository root (defaults to the parent of tools)",
    )
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(sys.argv[1:] if argv is None else argv)
    root = args.root.resolve()
    violations = audit(root)
    if not violations:
        print(f"Generalization audit passed: {len(production_sources(root))} production sources checked")
        return 0
    print("Question- or test-specific production branches detected:", file=sys.stderr)
    for violation in violations:
        print(
            f"  {violation.path}:{violation.line}: {violation.rule}: {violation.excerpt}",
            file=sys.stderr,
        )
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
