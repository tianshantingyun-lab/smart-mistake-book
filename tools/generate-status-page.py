#!/usr/bin/env python3
"""
Generate CI status page from build results.
This script reads build artifacts and test results to generate a status page.
"""

import os
import json
import hashlib
from datetime import datetime
from pathlib import Path

def calculate_sha256(file_path: str) -> str:
    """Calculate SHA-256 hash of a file."""
    sha256_hash = hashlib.sha256()
    with open(file_path, "rb") as f:
        for byte_block in iter(lambda: f.read(4096), b""):
            sha256_hash.update(byte_block)
    return sha256_hash.hexdigest()

def get_file_size_mb(file_path: str) -> str:
    """Get file size in MB."""
    if os.path.exists(file_path):
        size_bytes = os.path.getsize(file_path)
        return f"{size_bytes / (1024 * 1024):.1f}MB"
    return "N/A"

def parse_test_results(xml_path: str) -> dict:
    """Parse JUnit XML test results."""
    if not os.path.exists(xml_path):
        return {"total": 0, "passed": 0, "failed": 0, "duration": "N/A"}
    
    try:
        import xml.etree.ElementTree as ET
        tree = ET.parse(xml_path)
        root = tree.getroot()
        
        tests = int(root.get("tests", 0))
        failures = int(root.get("failures", 0))
        time_seconds = float(root.get("time", 0))
        
        return {
            "total": tests,
            "passed": tests - failures,
            "failed": failures,
            "duration": f"{time_seconds:.1f}s"
        }
    except Exception:
        return {"total": 0, "passed": 0, "failed": 0, "duration": "N/A"}

def generate_status_page():
    """Generate the status page from build artifacts."""
    
    # Read template
    template_path = Path(".github/workflows/status-template.md")
    if not template_path.exists():
        print(f"Template not found: {template_path}")
        return
    
    template = template_path.read_text(encoding="utf-8")
    
    # Get build information
    commit_sha = os.environ.get("GITHUB_SHA", "unknown")
    build_date = datetime.now().isoformat()
    branch = os.environ.get("GITHUB_REF_NAME", "unknown")
    triggered_by = os.environ.get("GITHUB_ACTOR", "unknown")
    
    # Replace template variables
    replacements = {
        "{{COMMIT_SHA}}": commit_sha,
        "{{BUILD_DATE}}": build_date,
        "{{BRANCH}}": branch,
        "{{TRIGGERED_BY}}": triggered_by,
        "{{GENERATION_TIMESTAMP}}": datetime.now().isoformat(),
    }
    
    # Parse test results
    test_modules = [
        ("core:database", "core/database/build/test-results/testDebugUnitTest"),
        ("core:data", "core/data/build/test-results/testDebugUnitTest"),
        ("feature:library", "feature/library/build/test-results/testDebugUnitTest"),
        ("feature:capture", "feature/capture/build/test-results/testDebugUnitTest"),
        ("feature:tutor", "feature/tutor/build/test-results/testDebugUnitTest"),
    ]
    
    for module_name, test_dir in test_modules:
        xml_files = list(Path(test_dir).glob("*.xml")) if Path(test_dir).exists() else []
        if xml_files:
            # Combine results from all XML files
            total = 0
            passed = 0
            failed = 0
            duration = "0.0s"
            
            for xml_file in xml_files:
                results = parse_test_results(str(xml_file))
                total += results["total"]
                passed += results["passed"]
                failed += results["failed"]
            
            prefix = module_name.replace(":", "_").upper()
            replacements[f"{{{prefix}_TEST_TOTAL}}"] = str(total)
            replacements[f"{{{prefix}_TEST_PASSED}}"] = str(passed)
            replacements[f"{{{prefix}_TEST_FAILED}}"] = str(failed)
            replacements[f"{{{prefix}_TEST_DURATION}}"] = duration
    
    # Calculate APK hashes
    apk_files = {
        "LOCAL_FIRST_DEBUG_APK_SHA": "app/build/outputs/apk/localFirst/debug/app-localFirst-debug.apk",
        "LOCAL_FIRST_RELEASE_APK_SHA": "app/build/outputs/apk/localFirst/release/app-localFirst-release.apk",
        "STRICT_OFFLINE_DEBUG_APK_SHA": "app/build/outputs/apk/strictOffline/debug/app-strictOffline-debug.apk",
        "STRICT_OFFLINE_RELEASE_APK_SHA": "app/build/outputs/apk/strictOffline/release/app-strictOffline-release.apk",
    }
    
    for key, path in apk_files.items():
        if os.path.exists(path):
            replacements[f"{{{key}}}"] = calculate_sha256(path)
        else:
            replacements[f"{{{key}}}"] = "N/A"
    
    # Replace all placeholders
    for key, value in replacements.items():
        template = template.replace(key, value)
    
    # Write output
    output_path = Path("docs/current/build-status.md")
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(template, encoding="utf-8")
    
    print(f"Status page generated: {output_path}")

if __name__ == "__main__":
    generate_status_page()
