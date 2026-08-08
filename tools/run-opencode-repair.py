"""Repair knowledge-detail modules that fail the independent depth check.

Unlike the first-pass enrichment (which asks for a full rewrite), this runs
only on modules with concrete failures and tells the model exactly which
sections are short and which fields need more entries. It loops until the
independent verifier passes or the round limit is reached.
"""

from __future__ import annotations

import argparse
import concurrent.futures
import glob
import json
import importlib.util
import subprocess
import sys
from pathlib import Path

_VERIFY_PATH = Path(__file__).parent / "verify-knowledge-detail-depth.py"
_SPEC = importlib.util.spec_from_file_location("verify_depth", _VERIFY_PATH)
_VERIFY_MODULE = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(_VERIFY_MODULE)
detail_files = _VERIFY_MODULE.detail_files
verify_section = _VERIFY_MODULE.verify_section

PROJECT_ROOT = Path(r"D:\智能错题本\.worktrees\ui-rebuild")
LOG_DIR = PROJECT_ROOT / ".opencode-repair-logs"

PROMPT_TEMPLATE = """请修复这一个 JSON 文件的字段缺口：{path}。不要改动其他文件，不要问问题。

独立复核发现以下小节未达标，请只修复这些小节里列出的字段缺口，不要把已达标的小节删掉或重写：

{issues}

硬性要求：
1. 必须立即使用编辑工具（Edit 工具）直接修改并保存这个 JSON 文件。不要只输出分析，不要反问，不要中途停止。
2. 禁止运行任何 shell 命令、Python、PowerShell、git、测试或验证命令；只用读取和编辑工具。
3. 禁止新增、删除、合并、移动或重排任何 knowledgePoints 或小节；禁止改动未列出的小节；禁止删除任何已有字段。保持 JSON schema 和顶层字段不变。
4. 保留 section、aliases、evidenceSources、prerequisiteRelations；不改 reviewState、reviewedBy、reviewedAtEpochMillis、reviewKind、synthesisPolicy、artifactId、updatedAtEpochMillis。
5. 每个 methodModels 条目：name 至少 4 个汉字；steps 至少 4 步，每步 6 个汉字以上且具体可操作；example 给出具体物质、化学式、现象、数值、事件或论证，至少 12 个汉字，禁止“见上/见方法模型/按步骤/代入”等占位。
6. 每个 workedExampleStructure 条目：questionShape 至少 4 个汉字；method 至少 4 个汉字；example 必须是完整具体示例，至少 20 个汉字，禁止“见上/同上/见方法模型/按步骤/代入”等占位。
7. properties 每条至少 8 个汉字，写具体事实、公式、反应、制度、事件或判断规则。
8. commonConfusions 至少 3 条，每条至少 10 个汉字，写出易混概念或典型错误及纠正要点。
9. conceptSummary 至少 2 句话，至少 40 个汉字。
10. evidenceSources 为空时设为 ["DOMAIN_COMMON_KNOWLEDGE"]。

完成后只回复一行 DONE <文件名>。不要输出验证脚本。"""


def file_digest(path: Path) -> str:
    import hashlib

    return hashlib.sha256(path.read_bytes()).hexdigest()


def module_issues(path: Path) -> list[tuple[str, list[str]]]:
    with open(path, encoding="utf-8") as fh:
        data = json.load(fh)
    out = []
    for kp in data.get("knowledgePoints", []):
        issues = verify_section(kp)
        if issues:
            out.append((kp.get("section", ""), issues))
    return out


def repair_one(path: Path, model: str, round_no: int) -> tuple[Path, int, bool]:
    log = LOG_DIR / f"{path.stem}-r{round_no}.log"
    log.parent.mkdir(parents=True, exist_ok=True)
    issues = module_issues(path)
    issue_lines = []
    for section, items in issues:
        issue_lines.append(f"- {section}: {'; '.join(items)}")
    prompt = PROMPT_TEMPLATE.format(path=path.as_posix(), issues="\n".join(issue_lines))
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
    parser.add_argument("--workers", type=int, default=3)
    parser.add_argument("--rounds", type=int, default=3)
    parser.add_argument("--only", nargs="*", default=None, help="module slugs to repair")
    args = parser.parse_args()

    files = detail_files()
    if args.only:
        wanted = set(args.only)
        files = [p for p in files if p.stem in wanted or p.name in wanted]

    for round_no in range(1, args.rounds + 1):
        failing = [p for p in files if module_issues(p)]
        if not failing:
            print("ALL MODULES PASS DEPTH CHECK")
            return 0
        print(f"round {round_no}: {len(failing)} modules need repair", flush=True)
        with concurrent.futures.ThreadPoolExecutor(max_workers=args.workers) as pool:
            futures = [
                pool.submit(repair_one, path, args.model, round_no) for path in failing
            ]
            for future in concurrent.futures.as_completed(futures):
                path, code, changed = future.result()
                status = "OK" if code == 0 else f"FAIL({code})"
                print(f"{status} {path.name} {'CHANGED' if changed else 'NOCHANGE'}", flush=True)

    failing = [p for p in files if module_issues(p)]
    if failing:
        print(f"REPAIR UNFINISHED: {len(failing)} modules still failing")
        for p in failing:
            print(f"  {p.name}")
        return 1
    print("ALL MODULES PASS DEPTH CHECK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
