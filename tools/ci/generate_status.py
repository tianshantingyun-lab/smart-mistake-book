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
import subprocess
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
    base = gradle_dir(module) / "build"
    classes = base / "classes"
    if classes.exists() and any(classes.rglob("*.class")):
        return "OK"
    # AGP modules package compiled classes into intermediates JARs instead
    # of loose .class files under build/classes.
    for name in ("compile_library_classes_jar", "compile_app_classes_jar"):
        candidate = base / "intermediates" / name
        if candidate.exists() and any(candidate.rglob("*.jar")):
            return "OK"
    return NOT_MEASURED


def app_compile_status(variant: str) -> str:
    jar_dir = (
        gradle_dir(":app") / "build" / "intermediates" / "compile_app_classes_jar" / variant
    )
    if jar_dir.exists() and any(jar_dir.rglob("*.jar")):
        return "OK"
    return NOT_MEASURED


def coverage_reports(module: str) -> list[Path]:
    """Candidate coverage XMLs: Kover for JVM modules, the AGP+Jacoco report for
    Android libraries (Kover 0.9.1 cannot see AGP 9's built-in-Kotlin variants
    — see KD-5). The first one with real counters wins."""
    gradle = gradle_dir(module)
    return [
        gradle / "build" / "reports" / "kover" / "report.xml",
        gradle / "build" / "reports" / "coverage" / "unit-test.xml",
    ]


def kover_coverage(module: str) -> tuple[str, str]:
    """Line/branch coverage percentages from the module's coverage XML."""
    counters: dict[str, tuple[int, int]] = {}
    for report in coverage_reports(module):
        if not report.exists():
            continue
        try:
            root = ET.parse(report).getroot()
        except ET.ParseError:
            continue
        counters = {}
        for counter in root.iter("counter"):
            kind = counter.get("type", "")
            if kind in ("LINE", "BRANCH"):
                missed = int(counter.get("missed", 0))
                covered = int(counter.get("covered", 0))
                counters[kind] = (missed, covered)
        # A report with zero counters means nothing was instrumented; try the
        # next candidate instead of rendering a misleading "0.0%".
        line = counters.get("LINE", (0, 0))
        if counters and line[0] + line[1] > 0:
            break
    if not counters:
        return NOT_MEASURED, NOT_MEASURED
    line = counters.get("LINE", (0, 0))
    if line[0] + line[1] == 0:
        return NOT_MEASURED, NOT_MEASURED

    def percentage(kind: str) -> str:
        missed, covered = counters[kind]
        total = missed + covered
        return f"{covered / total * 100:.1f}%" if total else NOT_MEASURED

    return percentage("LINE"), percentage("BRANCH")


def kb_gate_rows() -> tuple[str, bool, int]:
    """Run the 22 knowledge-base content gates (kb_build.gate) on the bundled pack.

    Returns (template table rows, all-green, ok count). A crash while evaluating
    is reported as red, never as unmeasured: F-05's lesson is that a status
    report which cannot say what happened must not say "PASS".
    """
    try:
        sys.path.insert(0, str(REPO / "tools"))
        from kb_build import gate
        metrics = gate.evaluate()
    except Exception as exc:  # noqa: BLE001 - crash = red, with the reason visible
        return f"| gate evaluation crashed | FAIL | {exc} |", False, 0
    rows = []
    ok_count = 0
    for metric in metrics:
        if metric.ok:
            ok_count += 1
        rows.append(
            f"| `{metric.key}` | {metric.title} | {'OK' if metric.ok else 'FAIL'} | {metric.value} |"
        )
    return "\n".join(rows), ok_count == len(metrics), ok_count


def tools_test_summary() -> tuple[str, str, str, bool]:
    """Run the Python tools test suite (tools/tests) and summarize total/passed/failed.

    Runs in a subprocess so test code cannot mutate this process' globals
    (several tests rebind tables.TABLES_DIR). A crash or a hung run is red,
    never "not measured".
    """
    env = dict(os.environ)
    env["PYTHONPATH"] = "tools" + os.pathsep + env.get("PYTHONPATH", "")
    try:
        proc = subprocess.run(
            [sys.executable, "-m", "unittest", "discover", "-s", "tools/tests"],
            cwd=REPO, env=env, capture_output=True, text=True, timeout=900,
        )
    except Exception as exc:  # noqa: BLE001
        return f"RUN_FAILED ({type(exc).__name__})", NOT_MEASURED, NOT_MEASURED, False
    stderr = proc.stderr
    total_match = re.search(r"Ran (\d+) tests?", stderr)
    if total_match is None:
        tail = stderr.strip().splitlines()
        detail = tail[-1][:160] if tail else f"exit={proc.returncode}"
        return f"RUN_FAILED ({detail})", NOT_MEASURED, NOT_MEASURED, False
    total = int(total_match.group(1))
    failed = 0
    for marker in ("failures=", "errors="):
        match = re.search(rf"{marker}(\d+)", stderr)
        if match:
            failed += int(match.group(1))
    if proc.returncode != 0:
        failed = max(failed, 1)
    return str(total), str(total - failed), str(failed), failed == 0


def benchmark_metrics_text() -> str:
    """Verbatim content of the retrieval benchmark written by
    :core:data's FourSubjectRetrievalBenchmarkTest (module-relative
    build/benchmark-metrics.txt). Staleness detection is R3's job; here the
    report only refuses to pretend the measurement does not exist."""
    path = gradle_dir(":core:data") / "build" / "benchmark-metrics.txt"
    if not path.exists():
        return NOT_MEASURED
    try:
        return path.read_text(encoding="utf-8").strip() or NOT_MEASURED
    except OSError:
        return NOT_MEASURED


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

    values["APP_LOCAL_FIRST_STATUS"] = app_compile_status("localFirstDebug")
    values["APP_LOCAL_FIRST_DURATION"] = "-"
    values["APP_STRICT_OFFLINE_STATUS"] = app_compile_status("strictOfflineDebug")
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

    # Coverage: only modules with a coverage XML render values; the rest stay
    # NOT_MEASURED. JVM modules use Kover, Android libraries the AGP+Jacoco
    # report (see coverage_report).
    for module, prefix in (
        (":core:domain", "CORE_DOMAIN"),
        (":core:data", "CORE_DATA"),
        (":core:database", "CORE_DATABASE"),
    ):
        line_pct, branch_pct = kover_coverage(module)
        values[f"{prefix}_LINE_COVERAGE"] = line_pct
        values[f"{prefix}_BRANCH_COVERAGE"] = branch_pct

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
    # tag is "<flavor>-<kind>"; upper() does not split camelCase, so the
    # display key must be mapped explicitly to match the template
    # (LOCAL_FIRST_DEBUG_..., not LOCALFIRST_DEBUG_...).
    display_flavor = {
        "localFirst": "LOCAL_FIRST",
        "strictOffline": "STRICT_OFFLINE",
        "unknown": "UNKNOWN",
    }
    for tag, path in apks.items():
        flavor, _, kind = tag.partition("-")
        flavor_key = display_flavor.get(flavor, flavor.upper())
        release_rows[f"{flavor_key}_{kind.upper()}_APK_SHA"] = sha256(path)
        size_mb = path.stat().st_size / (1024 * 1024)
        if kind == "release":
            release_rows[f"RELEASE_{flavor_key}_STATUS"] = "OK"
            release_rows[f"RELEASE_{flavor_key}_APK_SIZE"] = f"{size_mb:.1f} MB"
    if aabs:
        first_aab = next(iter(aabs.values()))
        release_rows.setdefault("AAB_SIZE_PLACEHOLDER", f"{first_aab / (1024 * 1024):.1f} MB")
    values.update(release_rows)

    # Knowledge base: the 22 content gates run here (kb_build.gate), the Python
    # tools suite is summarized here, and the retrieval benchmark file written
    # by :core:data's JVM test is embedded verbatim. Overall must not be PASS
    # unless all of these are measured AND green — that is the F-05 root fix:
    # a PASS that only tracked the build job's status could never reflect the
    # state of the knowledge base at all.
    gates_rows, gates_ok, gates_ok_count = kb_gate_rows()
    tools_total, tools_passed, tools_failed, tools_ok = tools_test_summary()
    values["KB_GATE_ROWS"] = gates_rows
    values["GATES_OK_COUNT"] = f"{gates_ok_count}/22"
    values["TOOLS_TESTS_TOTAL"] = tools_total
    values["TOOLS_TESTS_PASSED"] = tools_passed
    values["TOOLS_TESTS_FAILED"] = tools_failed
    values["BENCHMARK_METRICS"] = benchmark_metrics_text()

    job_ok = os.environ.get("JOB_STATUS", "success") == "success"
    overall = "PASS" if (job_ok and gates_ok and tools_ok) else "FAIL"
    values["OVERALL_STATUS"] = overall
    values["OVERALL_BREAKDOWN"] = " · ".join((
        f"build job: {'OK' if job_ok else 'FAIL'}",
        f"KB 22 gates: {'all OK' if gates_ok else f'{gates_ok_count}/22 OK or crashed'}",
        f"tools tests: {tools_passed}/{tools_total} passed"
        if tools_ok else f"tools tests: {tools_failed}/{tools_total} failed or run failed",
    ))

    # Known measurements first, then anything still unresolved in the
    # template is genuinely unmeasured. The sweep must run AFTER value
    # substitution: sweeping first erased every real value (acceptance
    # audit: status.md rendered all-NOT_MEASURED even on green runs).
    rendered = text
    for placeholder, value in values.items():
        rendered = rendered.replace("{{" + placeholder + "}}", value)
    rendered = re.sub(r"\{\{[A-Z0-9_]+\}\}", NOT_MEASURED, rendered)

    OUTPUT.write_text(rendered, encoding="utf-8")
    print(f"wrote {OUTPUT}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
