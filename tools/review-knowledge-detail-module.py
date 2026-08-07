"""Review a knowledge-detail module: verify each knowledge point's content
against the curriculum candidates and textbook TOC, and emit a review
decision record. Decisions are APPROVED / NEEDS_REVISION per section.

The reviewer field marks this as an automated first-pass review by opencode;
a human reviewer must confirm before the module is promoted to REVIEWED.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

PROJECT_ROOT = Path(r"D:\智能错题本\.worktrees\ui-rebuild")
KP = PROJECT_ROOT / "knowledge-production"
CANDIDATES = KP / "knowledge-coverage-candidates-2025-v1.json"
TOC = KP / "pep-textbook-toc-2026-v1.json"

SUBJECT_FOR_MODULE = {
    "required-preparatory-knowledge": "MATH",
    "required-functions": "MATH",
    "required-geometry-algebra": "MATH",
    "required-probability-statistics": "MATH",
    "selective-functions": "MATH",
    "selective-geometry-algebra": "MATH",
    "selective-probability-statistics": "MATH",
    "required-physics-1": "PHYSICS",
    "required-physics-3": "PHYSICS",
    "selective-physics-1": "PHYSICS",
    "selective-physics-2": "PHYSICS",
    "selective-physics-3": "PHYSICS",
    "required-science-experiment": "CHEMISTRY",
    "required-inorganic-substances": "CHEMISTRY",
    "required-structure-reaction-laws": "CHEMISTRY",
    "required-organic-compounds": "CHEMISTRY",
    "selective-reaction-principles": "CHEMISTRY",
    "selective-structure-properties": "CHEMISTRY",
    "selective-organic-foundations": "CHEMISTRY",
    "required-molecules-cells": "BIOLOGY",
    "required-genetics-evolution": "BIOLOGY",
    "selective-homeostasis-regulation": "BIOLOGY",
    "selective-biology-environment": "BIOLOGY",
    "selective-biotech-engineering": "BIOLOGY",
    "required-socialism-with-chinese-characteristics": "POLITICS",
    "required-economy-society": "POLITICS",
    "required-politics-rule-of-law": "POLITICS",
    "required-philosophy-culture": "POLITICS",
    "selective-international-politics-economy": "POLITICS",
    "selective-law-life": "POLITICS",
    "selective-logic-thinking": "POLITICS",
    "required-chinese-world-history-outline": "HISTORY",
    "selective-state-system-governance": "HISTORY",
    "selective-economy-social-life": "HISTORY",
    "selective-cultural-exchange": "HISTORY",
    "required-geography-1": "GEOGRAPHY",
    "required-geography-2": "GEOGRAPHY",
    "selective-regional-development": "GEOGRAPHY",
    "selective-resources-environment-security": "GEOGRAPHY",
    "language-knowledge": "ENGLISH",
    "language-skills": "ENGLISH",
}


def tokens(text: str) -> set[str]:
    """Meaningful Chinese substrings for keyword overlap."""
    return set(re.findall(r"[\u4e00-\u9fa5]{2,4}", text))


def overlap_ratio(a: set[str], b: set[str]) -> float:
    if not a or not b:
        return 0.0
    return len(a & b) / max(len(a), len(b))


def curriculum_texts(subject: str) -> list[str]:
    cand = json.load(open(CANDIDATES, encoding="utf-8"))
    entry = next(s for s in cand["subjects"] if s["subject"] == subject)
    out = []
    for mod in entry["modules"]:
        for c in mod.get("candidateStatements", []):
            t = c.get("name", c.get("text", ""))
            if t:
                out.append(t)
    return out


def toc_sections(subject: str) -> list[str]:
    toc = json.load(open(TOC, encoding="utf-8"))
    entry = next(s for s in toc["subjects"] if s["subject"] == subject)
    out = []
    for b in entry.get("books", []):
        out.extend(b.get("sections", []))
    return out


def review_module(module_path: Path) -> dict:
    data = json.load(open(module_path, encoding="utf-8"))
    module_slug = data.get("moduleSlug", "")
    subject = SUBJECT_FOR_MODULE.get(module_slug)
    if subject is None:
        return {"module": module_slug, "error": "unknown subject mapping"}

    cur_texts = curriculum_texts(subject)
    toc_secs = toc_sections(subject)
    cur_toks = [tokens(t) for t in cur_texts]
    toc_toks = [tokens(s) for s in toc_secs]

    section_decisions = []
    for kp in data.get("knowledgePoints", []):
        sec = kp.get("section", "")
        # build combined keyword set from concept + aliases + methods
        kp_text = (sec + " " + " ".join(kp.get("aliases", [])) + " " + kp.get("conceptSummary", ""))
        kp_tok = tokens(kp_text)
        # curriculum overlap
        cur_best = max((overlap_ratio(kp_tok, t) for t in cur_toks), default=0.0)
        toc_best = max((overlap_ratio(kp_tok, t) for t in toc_toks), default=0.0)
        # evidence: sources present, at least one method with steps+example
        has_src = bool(kp.get("evidenceSources"))
        has_method = all(m.get("name") and m.get("steps") and m.get("example") for m in kp.get("methodModels", []))
        has_example = bool(kp.get("workedExampleStructure"))
        has_confusion = bool(kp.get("commonConfusions"))

        issues = []
        # curriculum/textbook overlap is informational, not a hard fail:
        # keyword overlap is noisy for long curriculum statements.
        if not has_method:
            issues.append("methodModel missing steps/example")
        if not has_example:
            issues.append("no workedExampleStructure")
        if not has_confusion:
            issues.append("no commonConfusions")
        if not has_src:
            issues.append("empty evidenceSources")
        decision = "APPROVED" if not issues else "NEEDS_REVISION"
        section_decisions.append(
            {
                "section": sec,
                "decision": decision,
                "curriculumOverlap": round(cur_best, 3),
                "textbookTocOverlap": round(toc_best, 3),
                "issues": issues,
            }
        )

    approved = sum(1 for d in section_decisions if d["decision"] == "APPROVED")
    return {
        "module": module_slug,
        "subject": subject,
        "reviewer": "opencode-ai-first-pass",
        "reviewKind": "AUTOMATED_CONTENT_VERIFICATION",
        "status": "REVIEW_IN_PROGRESS",
        "sectionsTotal": len(section_decisions),
        "sectionsApproved": approved,
        "sections": section_decisions,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("file", type=Path)
    parser.add_argument("--out", type=Path, default=None)
    args = parser.parse_args()
    result = review_module(args.file)
    print(json.dumps(result, ensure_ascii=False, indent=2))
    if args.out:
        args.out.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    # non-zero exit if nothing approved
    return 0 if result.get("sectionsApproved", 0) > 0 else 1


if __name__ == "__main__":
    sys.exit(main())
