"""Audit knowledge-detail section-name uniqueness inside each subject.

Section names are the primary lookup key for high-school knowledge. A section
name repeated in two modules of the same subject makes retrieval ambiguous, so
the audit reports every duplicate with its owning modules.
"""

from __future__ import annotations

import argparse
import json
from collections import defaultdict
from pathlib import Path

PROJECT_ROOT = Path(r"D:\智能错题本\.worktrees\ui-rebuild")
KNOWLEDGE_DIR = PROJECT_ROOT / "knowledge-production"

EXCLUDE_PARTS = (
    "content-audit",
    "enrichment",
    "alignment",
    "directory",
    "ledger",
    "size-audit",
    "index",
)


def audit(knowledge_dir: Path) -> list[dict]:
    by_subject_section: dict[tuple[str, str], list[tuple[str, str]]] = defaultdict(list)
    for path in sorted(knowledge_dir.glob("knowledge-detail-*.json")):
        if any(part in path.name for part in EXCLUDE_PARTS):
            continue
        data = json.loads(path.read_text(encoding="utf-8"))
        subject = data.get("subject")
        module_slug = data.get("moduleSlug")
        for kp in data.get("knowledgePoints", []):
            section = kp.get("section", "")
            by_subject_section[(subject, section)].append((module_slug, path.name))
    duplicates = []
    for (subject, section), owners in sorted(by_subject_section.items()):
        if len(owners) > 1:
            duplicates.append(
                {
                    "subject": subject,
                    "section": section,
                    "modules": [owner[0] for owner in owners],
                    "files": [owner[1] for owner in owners],
                }
            )
    return duplicates


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    duplicates = audit(KNOWLEDGE_DIR)
    print(f"duplicate section names inside subject: {len(duplicates)}")
    for dup in duplicates:
        print(
            f"  {dup['subject']} | {dup['section']} | "
            f"modules={','.join(dup['modules'])}"
        )
    if args.check and duplicates:
        print("UNIQUENESS AUDIT FAILED")
        return 1
    if args.check:
        print("UNIQUENESS AUDIT PASSED")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
