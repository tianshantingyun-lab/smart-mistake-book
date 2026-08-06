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
    re.compile(r"\bimport\s+android\.util\.Log\b"),
    re.compile(r"\bSystem\.(?:out|err)\b"),
    re.compile(r"\bprintln\s*\("),
    re.compile(r"\bprintStackTrace\s*\("),
)


def _production_sources() -> list[Path]:
    sources: list[Path] = []
    for root in PRODUCTION_ROOTS:
        if not root.exists():
            continue
        if root.name == "src" and root.parent.name == "main":
            sources.extend(root.rglob("*.kt"))
            sources.extend(root.rglob("*.java"))
            continue
        main_dirs = [
            root / module / "src" / "main"
            for module in sorted(root.iterdir())
            if (root / module / "src" / "main").exists()
        ]
        for main_dir in main_dirs:
            sources.extend(main_dir.rglob("*.kt"))
            sources.extend(main_dir.rglob("*.java"))
    return sorted(sources)


class DiagnosticRedactionGovernanceTest(unittest.TestCase):
    def test_production_sources_do_not_emit_raw_diagnostic_output(self) -> None:
        violations: list[str] = []
        for path in _production_sources():
            for line_number, line in enumerate(
                path.read_text(encoding="utf-8", errors="ignore").splitlines(),
                start=1,
            ):
                if any(pattern.search(line) for pattern in FORBIDDEN_PATTERNS):
                    violations.append(f"{path}:{line_number}:{line.strip()}")
        self.assertEqual([], violations)

    def test_diagnostic_redactor_never_prints_the_original_id(self) -> None:
        source = (
            PROJECT_ROOT
            / "core"
            / "domain"
            / "src"
            / "main"
            / "kotlin"
            / "com"
            / "tingyun"
            / "smartmistakebook"
            / "core"
            / "domain"
            / "DiagnosticRedactor.kt"
        )
        text = source.read_text(encoding="utf-8")
        self.assertIn("[REDACTED]", text)
        self.assertNotIn("learnerId", text)
        self.assertNotIn("sourceFactId", text)


if __name__ == "__main__":
    unittest.main()
