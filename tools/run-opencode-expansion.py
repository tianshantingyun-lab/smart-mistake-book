"""Expand knowledge-detail modules with paid DeepSeek V4 Flash.

For every knowledge point the model appends concrete properties, method
models, worked examples, and confusions without rewriting or deleting existing
content. The independent depth/content audits stay the source of truth.
"""

from __future__ import annotations

import argparse
import concurrent.futures
import glob
import hashlib
import json
import subprocess
import sys
from pathlib import Path

PROJECT_ROOT = Path(r"D:\智能错题本\.worktrees\ui-rebuild")
KNOWLEDGE_DIR = PROJECT_ROOT / "knowledge-production"
LOG_DIR = PROJECT_ROOT / ".opencode-expansion-logs"
REFERENCE_NOTES = (
    KNOWLEDGE_DIR / "reference-notes" / "curriculum-framework-2026-v1.md"
)

EXCLUDE_PARTS = (
    "content-audit",
    "enrichment",
    "alignment",
    "directory",
    "ledger",
    "size-audit",
    "index",
    "node-catalog",
)
SKIP_MODULES = {"required-organic-compounds"}

SUBJECT_REFERENCE_HEADINGS = {
    "CHINESE": "语文",
    "MATH": "数学",
    "ENGLISH": "英语",
    "PHYSICS": "物理",
    "CHEMISTRY": "化学",
    "BIOLOGY": "生物",
    "POLITICS": "政治",
    "HISTORY": "历史",
    "GEOGRAPHY": "地理",
}


def reference_section(subject: str) -> str:
    if not REFERENCE_NOTES.is_file():
        return ""
    text = REFERENCE_NOTES.read_text(encoding="utf-8")
    heading = SUBJECT_REFERENCE_HEADINGS.get(subject)
    if not heading:
        return ""
    marker = f"## {heading}"
    start = text.find(marker)
    if start < 0:
        return ""
    rest = text[start + len(marker):]
    end = rest.find("\n## ")
    section = rest[:end if end >= 0 else None].strip()
    return f"## {heading}{section}"


def prompt_for(path: Path, subject: str) -> str:
    reference = reference_section(subject)
    reference_block = ""
    if reference:
        reference_block = (
            "以下是与该学科一致的课程/教材框架参考资料，只能用于校准表述与查漏，"
            "不得新增或删除 knowledgePoints：\n"
            f"{reference}\n\n"
        )
    return f"""请扩充这一个 JSON 文件的教学支持内容：{path}。不要改动其他文件，不要问问题。

{reference_block}对文件里每个 knowledgePoint，在保留现有内容不变的前提下，按下面规则追加新内容：
1. properties 若少于 10 条，追加到至少 10 条；新增条目必须是该小节新的具体事实、公式、反应、制度、事件或判断规则，每条至少 8 个汉字，不得重复已有条目。
2. methodModels 若少于 4 个，追加到至少 4 个；新方法模型 name 至少 4 个汉字，steps 至少 4 步且每步至少 6 个汉字、具体可操作，example 至少 12 个汉字。
3. workedExampleStructure 若少于 4 个，追加到至少 4 个；新条目 questionShape 与 method 至少 4 个汉字，example 至少 20 个汉字且是完整具体示例。
4. commonConfusions 若少于 6 条，追加到至少 6 条；新条目至少 10 个汉字，写出易混概念或典型错误及纠正要点。
5. 禁止使用“见上/同上/见方法模型/按步骤/代入”等占位，禁止删除、合并、重排或重写已有条目，禁止新增或删除 knowledgePoints，禁止修改顶层字段。
6. 只使用编辑工具（Edit）直接修改并保存文件；禁止运行任何 shell 命令、Python、PowerShell、git、测试或验证命令。
7. 完成后只回复一行 DONE <文件名>。"""


def detail_files(include_skipped: bool = False) -> list[Path]:
    out = []
    for path in sorted(Path(p) for p in glob.glob(str(KNOWLEDGE_DIR / "knowledge-detail-*.json"))):
        if any(part in path.name for part in EXCLUDE_PARTS):
            continue
        try:
            with open(path, encoding="utf-8") as fh:
                module_slug = json.load(fh).get("moduleSlug")
        except json.JSONDecodeError as exc:
            print(
                f"WARN skip {path.name}: not valid JSON yet "
                f"(line {exc.lineno} col {exc.colno}); likely being written",
                flush=True,
            )
            continue
        if module_slug in SKIP_MODULES and not include_skipped:
            continue
        out.append(path)
    return out


def file_digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def expand_one(path: Path, model: str, round_no: int) -> tuple[Path, int, bool]:
    log = LOG_DIR / f"{path.stem}-r{round_no}.log"
    log.parent.mkdir(parents=True, exist_ok=True)
    subject = json.loads(path.read_text(encoding="utf-8")).get("subject", "")
    prompt = prompt_for(path, subject)
    before = file_digest(path)
    with log.open("w", encoding="utf-8") as fh:
        result = subprocess.run(
            [
                r"C:\Users\听云\AppData\Roaming\npm\opencode.cmd",
                "run",
                "--auto",
                "--pure",
                "-m",
                model,
                "--format",
                "json",
            ],
            cwd=PROJECT_ROOT,
            input=prompt,
            stdout=fh,
            stderr=subprocess.STDOUT,
            text=True,
            encoding="utf-8",
            errors="replace",
        )
    return path, result.returncode, before != file_digest(path)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--model", default="opencode-go/deepseek-v4-flash")
    parser.add_argument("--workers", type=int, default=2)
    parser.add_argument("--rounds", type=int, default=1)
    parser.add_argument("--only", nargs="*", default=None)
    parser.add_argument(
        "--subject",
        nargs="*",
        default=None,
        help="restrict to modules whose subject is in this list (e.g. PHYSICS)",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="print selected modules and prompt sizes without calling the model",
    )
    parser.add_argument(
        "--include-skipped",
        action="store_true",
        help="also expand modules listed in SKIP_MODULES",
    )
    args = parser.parse_args()

    files = detail_files(include_skipped=args.include_skipped)
    if args.only:
        wanted = set(args.only)
        files = [p for p in files if p.stem in wanted or p.name in wanted]
    if args.subject:
        wanted_subjects = {s.upper() for s in args.subject}
        files = [
            p
            for p in files
            if json.loads(p.read_text(encoding="utf-8")).get("subject", "").upper()
            in wanted_subjects
        ]

    if args.dry_run:
        print(f"dry-run: {len(files)} modules")
        for path in files:
            subject = json.loads(path.read_text(encoding="utf-8")).get("subject", "")
            prompt = prompt_for(path, subject)
            print(f"  {path.name} subject={subject} prompt_chars={len(prompt)}")
        return 0

    for round_no in range(1, args.rounds + 1):
        print(f"round {round_no}: expanding {len(files)} modules", flush=True)
        with concurrent.futures.ThreadPoolExecutor(max_workers=args.workers) as pool:
            futures = [
                pool.submit(expand_one, path, args.model, round_no) for path in files
            ]
            for future in concurrent.futures.as_completed(futures):
                path, code, changed = future.result()
                status = "OK" if code == 0 else f"FAIL({code})"
                print(
                    f"{status} {path.name} {'CHANGED' if changed else 'NOCHANGE'}",
                    flush=True,
                )
    return 0


if __name__ == "__main__":
    sys.exit(main())
