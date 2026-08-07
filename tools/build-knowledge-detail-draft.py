"""Semi-automated knowledge-detail draft builder.

Reads crawled article markdown files plus the target module's section list
(from knowledge-point-directory), and produces a draft knowledge-detail JSON
where each section is pre-populated with:
  - section / aliases / evidenceSources (from manifest)
  - conceptSummary (first substantive paragraph of the matched article)
  - methodModels / commonConfusions candidates (keyword-split excerpts)

The output is a DRAFT that still needs an LLM to curate; the tool only does
mechanical extraction so curation is faster and grounded in the sources.

Usage:
  python tools/build-knowledge-detail-draft.py <module> <out_path>
"""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path

PROJECT_ROOT = Path(r"D:\智能错题本\.worktrees\ui-rebuild")
DIRECTORY = PROJECT_ROOT / "knowledge-production" / "knowledge-point-directory-2026-v1.json"
ARTIFACTS = Path(r"D:\智能错题本\.artifacts\research\knowledge-crawl")

# Patterns that start a useful excerpt for a detail field.
CONCEPT_START = re.compile(r"^.{0,40}?(概念|定义|含义|称为|记作)")
METHOD_HINT = re.compile(r"(方法|步骤|技巧|解法|思路|配凑|换元|代换|图象|数轴|分类|验证)")
CONFUSION_HINT = re.compile(r"(注意|易错|易混|陷阱|谨记|小心|切忌|误区|提醒)")
EXAMPLE_HINT = re.compile(r"(例[0-9一二三四五六七八九十]*[.、）]|例题|经典|真题|典例|练习)")
PROPERTY_HINT = re.compile(r"(性质|定理|法则|公式|推论)")


def clean(text: str) -> str:
    text = re.sub(r"!\[[^\]]*\]\([^)]*\)", "", text)  # images
    text = re.sub(r"\n{3,}", "\n\n", text)
    return text.strip()


def split_sentences(para: str) -> list[str]:
    parts = re.split(r"(?<=[。；！？])", para)
    return [p.strip() for p in parts if p.strip()]


def extract_excerpts(text: str, patterns: list[re.Pattern], limit: int = 6) -> list[str]:
    paras = [p.strip() for p in re.split(r"\n\s*\n", text) if p.strip()]
    hits = []
    for para in paras:
        for pat in patterns:
            if pat.search(para) and len(para) > 15 and len(para) < 400:
                hits.append(clean(para))
                break
    # dedupe preserving order
    seen, out = set(), []
    for h in hits:
        if h[:40] not in seen:
            seen.add(h[:40])
            out.append(h)
        if len(out) >= limit:
            break
    return out


def main() -> int:
    if len(sys.argv) < 3:
        print("usage: python build-knowledge-detail-draft.py <module> <out_path>")
        return 2
    module = sys.argv[1]
    out_path = Path(sys.argv[2])

    directory = json.load(open(DIRECTORY, encoding="utf-8"))
    subject_entry = next(s for s in directory["subjects"] if s["subject"] == "MATH")
    module_entry = next(m for m in subject_entry["modules"] if m["moduleSlug"] == module)
    sections = [s["section"] for s in module_entry["matchedSections"]]

    # Load all crawled articles, keyed by filename stem.
    articles: dict[str, str] = {}
    for f in ARTIFACTS.glob("*.md"):
        articles[f.stem] = clean(f.read_text(encoding="utf-8"))

    # Map each section to the most relevant article by keyword overlap.
    section_keywords = {
        s: set(re.findall(r"[\u4e00-\u9fa5]{2,4}", s)) for s in sections
    }
    used_articles = set()
    sections_out = []
    for sec in sections:
        kw = section_keywords[sec]
        best_art, best_score = None, 0
        for stem, text in articles.items():
            if stem in used_articles:
                continue
            score = sum(1 for k in kw if k in text[:6000])
            if score > best_score:
                best_score, best_art = score, stem
        if best_art and best_score >= 1:
            used_articles.add(best_art)
            art_text = articles[best_art]
            concept = extract_excerpts(art_text, [CONCEPT_START])[:1]
            methods = extract_excerpts(art_text, [METHOD_HINT, EXAMPLE_HINT])
            confusions = extract_excerpts(art_text, [CONFUSION_HINT])
            properties = extract_excerpts(art_text, [PROPERTY_HINT])
            sections_out.append(
                {
                    "section": sec,
                    "candidateArticle": best_art,
                    "conceptSummary": concept[0] if concept else "",
                    "propertyCandidates": properties[:4],
                    "methodCandidates": methods[:6],
                    "confusionCandidates": confusions[:5],
                }
            )
        else:
            sections_out.append(
                {
                    "section": sec,
                    "candidateArticle": None,
                    "conceptSummary": "",
                    "propertyCandidates": [],
                    "methodCandidates": [],
                    "confusionCandidates": [],
                    "note": "no matching crawled article",
                }
            )

    draft = {
        "schemaVersion": 1,
        "artifactId": f"knowledge-detail-draft-{module}",
        "subject": "MATH",
        "moduleSlug": module,
        "reviewState": "DRAFT_UNREVIEWED",
        "sections": sections_out,
        "availableArticles": sorted(articles.keys()),
    }
    out_path.write_text(json.dumps(draft, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"wrote {out_path}")
    matched = sum(1 for s in sections_out if s["candidateArticle"])
    print(f"sections: {len(sections_out)}, matched-to-article: {matched}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
