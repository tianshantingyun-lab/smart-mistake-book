#!/usr/bin/env python3
"""Render docs/status.md from real build outputs.

Values that have no measurement source are rendered as NOT_MEASURED,
never as fabricated numbers (acceptance-audit 4.2 requirement).
"""

from __future__ import annotations

import glob
import hashlib
import os
import re
import sys
import xml.etree.ElementTree as ET
from datetime import datetime, timezone
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
TEMPLATE = REPO / ".github" / "workflows" / "status-template.md"
OUTPUT = REPO / "docs" / "status.md"

NOT_MEASURED = "NOT_MEASURED"

MODULES = [
    ":core:database",
    ":core:data",
    ":core:domain",
    ":feature:library",
    ":feature:capture",
    ":feature:tutor",
]


def gradle_dir(module: str) -> Path:
    return REPO / module.lstrip(":").replace(":", "/")


def test_results(module: str) -> list[Path]:
    base = gradle_dir(module) / "build" / "test-results"
    if not base.exists():
        return []
    return sorted(base.rglob("TEST-*.xml"))


def summarize_tests(module: str) -> tuple[int, int, int]:
    total = passed = failures = 0
    for xml in test_results(module):
        try:
            root = ET.parse(xml).getroot()
        except ET.ParseError:
            continue
        total += int(root.get("tests", 0))
        failures += int(root.get("failures", 0)) + int(root.get("errors", 0))
    passed = total - failures
    return total, max(passed, 0), failures


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def apk_outputs() -> dict[str, Path]:
    results: dict[str, Path] = {}
    pattern = gradle_dir(":app") / "build" / "outputs" / "apk"
    for path in sorted(pattern.rglob("*.apk")):
        name = path.name
        flavor = "localFirst" if "localfirst" in str(path).lower() or "local-first" in name else (
            "strictOffline" if "offline" in str(path).lower() or "strict-offline" in name else "unknown"
        )
        kind = "debug" if "debug" in name else ("release" if "release" in name else "other")
        results[f"{flavor}-{kind}"] = path
    return results


def lint_report(variant: str) -> tuple[int, int]:
    report = gradle_dir(":app") / "build" / "reports" / f"lint-results-{variant}.xml"
    if not report.exists():
        return -1, -1
    try:
        root = ET.parse(report).getroot()
    except ET.ParseError:
        return -1, -1
    errors = warnings = 0
    for issue in root.iter("issue"):
        severity = issue.get("severity", "")
        if severity == "Error":
            errors += 1
        elif severity == "Warning":
            warnings += 1
    return errors, warnings


def compile_status(module: str) -> str:
    classes = gradle_dir(module) / "build" / "classes"
    return "OK" if classes.exists() and any(classes.rglob("*.class")) else NOT_MEASURED


def main() -> int:
    if not TEMPLATE.exists():
        print(f"template missing: {TEMPLATE}", file=sys.stderr)
        return 1
    text = TEMPLATE.read_text(encoding="utf-8")

    values: dict[str, str] = {}

    values["COMMIT_SHA"] = os.environ.get("COMMIT_SHA", "local")
    values["BUILD_DATE"] = datetime.now(timezone.utc).strftime("%Y-%m-%d")
    values["BRANCH"] = os.environ.get("BRANCH", "unknown")
    values["TRIGGERED_BY"] = os.environ.get("TRIGGERED_BY", "manual")
    values["GENERATION_TIMESTAMP"] = datetime.now(timezone.utc).isoformat()

    for module in MODULES:
        key = module.lstrip(":").replace(":", "_").upper()
        total, passed, failed = summarize_tests(module)
        if total > 0:
            values[f"{key}_TEST_TOTAL"] = str(total)
            values[f"{key}_TEST_PASSED"] = str(passed)
            values[f"{key}_TEST_FAILED"] = str(failed)
            values[f"{key}_TEST_DURATION"] = "-"
        else:
            values[f"{key}_TEST_TOTAL"] = NOT_MEASURED
            values[f"{key}_TEST_PASSED"] = NOT_MEASURED
            values[f"{key}_TEST_FAILED"] = NOT_MEASURED
            values[f"{key}_TEST_DURATION"] = NOT_MEASURED
        values[f"{key}_STATUS"] = compile_status(module)
        values[f"{key}_DURATION"] = "-"

    values["APP_LOCAL_FIRST_STATUS"] = compile_status(":app")
    values["APP_LOCAL_FIRST_DURATION"] = "-"
    values["APP_STRICT_OFFLINE_STATUS"] = NOT_MEASURED
    values["APP_STRICT_OFFLINE_DURATION"] = "-"

    for variant, key in (("localFirstDebug", "LINT_LOCAL_FIRST"), ("strictOfflineDebug", "LINT_STRICT_OFFLINE")):
        errors, warnings = lint_report(variant)
        if errors < 0:
            values[f"{key}_STATUS"] = NOT_MEASURED
            values[f"{key}_ERRORS"] = NOT_MEASURED
            values[f"{key}_WARNINGS"] = NOT_MEASURED
        else:
            values[f"{key}_STATUS"] = "OK"
            values[f"{key}_ERRORS"] = str(errors)
            values[f"{key}_WARNINGS"] = str(warnings)

    apks = apk_outputs()
    release_rows = {
        "RELEASE_LOCAL_FIRST_STATUS": NOT_MEASURED,
        "RELEASE_LOCAL_FIRST_APK_SIZE": NOT_MEASURED,
        "RELEASE_LOCAL_FIRST_AAB_SIZE": NOT_MEASURED,
        "RELEASE_STRICT_OFFLINE_STATUS": NOT_MEASURED,
        "RELEASE_STRICT_OFFLINE_APK_SIZE": NOT_MEASURED,
        "RELEASE_STRICT_OFFLINE_AAB_SIZE": NOT_MEASURED,
        "LOCAL_FIRST_DEBUG_APK_SHA": NOT_MEASURED,
        "LOCAL_FIRST_RELEASE_APK_SHA": NOT_MEASURED,
        "STRICT_OFFLINE_DEBUG_APK_SHA": NOT_MEASURED,
        "STRICT_OFFLINE_RELEASE_APK_SHA": NOT_MEASURED,
    }
    aab_pattern = gradle_dir(":app") / "build" / "outputs" / "bundle"
    aabs = {p.name: p.stat().st_size for p in aab_pattern.rglob("*.aab")}
    for tag, path in apks.items():
        key = tag.upper().replace("-", "_")
        release_rows[f"{key}_APK_SHA"] = sha256(path)
        size_mb = path.stat().st_size / (1024 * 1024)
        if "RELEASE" in key:
            flavor = "LOCAL_FIRST" if "LOCAL_FIRST" in key else "STRICT_OFFLINE"
            release_rows[f"RELEASE_{flavor}_STATUS"] = "OK"
            release_rows[f"RELEASE_{flavor}_APK_SIZE"] = f"{size_mb:.1f} MB"
    if aabs:
        first_aab = next(iter(aabs.values()))
        release_rows.setdefault("AAB_SIZE_PLACEHOLDER", f"{first_aab / (1024 * 1024):.1f} MB")
    values.update(release_rows)

    # Anything still unresolved in the template is genuinely unmeasured.
    rendered = re.sub(r"\{\{[A-Z0-9_]+\}\}", NOT_MEASURED, text)
    for placeholder, value in values.items():
        rendered = rendered.replace("{{" + placeholder + "}}", value)
    rendered = re.sub(r"\{\{[A-Z0-9_]+\}\}", NOT_MEASURED, rendered)

    overall = "PASS" if os.environ.get("JOB_STATUS", "success") == "success" else "FAIL"
    rendered = rendered.replace("{{OVERALL_STATUS}}", overall)

    OUTPUT.write_text(rendered, encoding="utf-8")
    print(f"wrote {OUTPUT}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
