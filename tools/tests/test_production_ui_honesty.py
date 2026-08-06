from __future__ import annotations

import re
import unittest
from pathlib import Path


PROJECT_ROOT = Path(__file__).resolve().parents[2]
PRODUCTION_ROOTS = (
    PROJECT_ROOT / "app" / "src" / "main",
    PROJECT_ROOT / "feature",
    PROJECT_ROOT / "core",
)
FORBIDDEN_PATTERNS = (
    re.compile(r"onClick\s*=\s*\{\s*\}"),
    re.compile(r"onClick\s*=\s*\{\s*Unit\s*\}"),
    re.compile(r"敬请期待|即将支持|后续版本|以后再说|永久禁用|暂时禁用|功能预留|占位"),
)
FORBIDDEN_STUDENT_LANGUAGE_PHRASES = (
    "原子知识",
    "知识本体",
    "检索召回",
    "学习投影",
    "证据权重",
    "模型候选",
    "分类依据",
    "资料完整度",
    "知识点与资料",
    "待补齐",
    "知识节点",
    "历史轴",
    "冲突投影",
    "grounding",
    "taxonomy",
    "embedding",
    "schema",
    "learner",
    "evidence",
)
UI_TEXT_ASSIGNMENT = re.compile(
    r"(?:\bText\(|"
    r"\b(?:text|contentDescription|label|title|message|error|hint|"
    r"buttonText|actionText|subtitle|description|header|body)\s*=\s*)"
)


def _production_sources() -> list[Path]:
    sources: list[Path] = []
    for root in PRODUCTION_ROOTS:
        if not root.exists():
            continue
        if root.name == "main" and root.parent.name == "src":
            sources.extend(root.rglob("*.kt"))
            sources.extend(root.rglob("*.java"))
            continue
        for module in sorted(root.iterdir()):
            main_dir = root / module / "src" / "main"
            if main_dir.exists():
                sources.extend(main_dir.rglob("*.kt"))
                sources.extend(main_dir.rglob("*.java"))
    return sorted(sources)


def _production_ui_sources() -> list[Path]:
    roots = [
        PROJECT_ROOT / "app" / "src" / "main",
        PROJECT_ROOT / "feature",
        PROJECT_ROOT / "core" / "ui" / "src" / "main",
    ]
    sources: list[Path] = []
    for root in roots:
        if not root.exists():
            continue
        if root.name == "main" and root.parent.name == "src":
            sources.extend(root.rglob("*.kt"))
            sources.extend(root.rglob("*.java"))
            continue
        for module in sorted(root.iterdir()):
            main_dir = root / module / "src" / "main"
            if main_dir.exists():
                sources.extend(main_dir.rglob("*.kt"))
                sources.extend(main_dir.rglob("*.java"))
    return sorted(sources)


def _string_literals(text: str) -> list[str]:
    literals: list[str] = []
    index = 0
    while index < len(text):
        if text.startswith('"""', index):
            end = text.find('"""', index + 3)
            if end < 0:
                break
            literals.append(text[index + 3 : end])
            index = end + 3
            continue
        if text[index] != '"':
            index += 1
            continue
        cursor = index + 1
        value: list[str] = []
        while cursor < len(text):
            char = text[cursor]
            if char == "\\" and cursor + 1 < len(text):
                value.append(text[cursor : cursor + 2])
                cursor += 2
                continue
            if char == '"':
                break
            value.append(char)
            cursor += 1
        if cursor < len(text):
            literals.append("".join(value))
            index = cursor + 1
        else:
            break
    return literals


def _user_facing_literals(line: str) -> list[str]:
    if not UI_TEXT_ASSIGNMENT.search(line):
        return []
    return _string_literals(line)


class ProductionUiHonestyGovernanceTest(unittest.TestCase):
    def test_production_ui_has_no_empty_actions_or_fake_future_capabilities(self) -> None:
        violations: list[str] = []
        for path in _production_sources():
            for line_number, line in enumerate(
                path.read_text(encoding="utf-8", errors="ignore").splitlines(),
                start=1,
            ):
                if any(pattern.search(line) for pattern in FORBIDDEN_PATTERNS):
                    violations.append(f"{path}:{line_number}:{line.strip()}")
        self.assertEqual([], violations)

    def test_production_student_facing_sources_have_no_internal_language(self) -> None:
        violations: list[str] = []
        for path in _production_ui_sources():
            text = path.read_text(encoding="utf-8", errors="ignore")
            for line_number, line in enumerate(text.splitlines(), start=1):
                literals = _user_facing_literals(line)
                hits = [
                    phrase
                    for phrase in FORBIDDEN_STUDENT_LANGUAGE_PHRASES
                    if any(phrase.lower() in literal.lower() for literal in literals)
                ]
                if hits:
                    violations.append(
                        f"{path}:{line_number}:{','.join(hits)}:{line.strip()}"
                    )
        self.assertEqual([], violations)


if __name__ == "__main__":
    unittest.main()
