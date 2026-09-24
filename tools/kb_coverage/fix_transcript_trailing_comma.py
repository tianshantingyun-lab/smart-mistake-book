"""修暂存转写片里的"行尾多余逗号"（代理把 JSONL 每行当数组元素写）。

无损：只删每行最后那个逗号（及其后空白），对象内容一字不动；改了几行有日志。
用法：python tools/kb_coverage/fix_transcript_trailing_comma.py [--dry-run]
"""
import argparse
import json
import pathlib
import sys
import time

ROOT = pathlib.Path("build/2027-53-transcripts")
SETTLE_SECONDS = 300  # 最近 5 分钟内被写过的片跳过（避免与仍在写的转写代理抢文件）

ap = argparse.ArgumentParser(description=__doc__)
ap.add_argument("--dry-run", action="store_true")
args = ap.parse_args()

now = time.time()
files = [f for f in sorted(ROOT.glob("**/range_*.jsonl"))
         if now - f.stat().st_mtime >= SETTLE_SECONDS]
fresh = len(list(ROOT.glob("**/range_*.jsonl"))) - len(files)
print(f"静止片 {len(files)}｜跳过（最近 {SETTLE_SECONDS}s 内被写）{fresh}", file=sys.stderr)
fixed_files = fixed_lines = still_bad = 0
for f in files:
    lines = f.read_text(encoding="utf-8").splitlines()
    out, changed = [], 0
    for ln, raw in enumerate(lines, 1):
        s = raw.strip()
        if s and s.endswith(","):
            s = s[:-1].rstrip()
            changed += 1
        if s:
            try:
                json.loads(s)
            except json.JSONDecodeError as e:
                still_bad += 1
                print(f"  ! 仍坏：{f.parent.name} {f.name}:L{ln} {e.msg}", file=sys.stderr)
        out.append(s)
    if changed:
        fixed_files += 1
        fixed_lines += changed
        if not args.dry_run:
            f.write_text("\n".join(out) + "\n", encoding="utf-8")

print(json.dumps({"files": len(files), "fixed_files": fixed_files,
                  "fixed_lines": fixed_lines, "still_bad": still_bad,
                  "dry_run": args.dry_run}, ensure_ascii=False))
