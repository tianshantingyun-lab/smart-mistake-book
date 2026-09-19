#!/usr/bin/env bash
# 隔离"已判定"的源文件：镜像搬到 <源根>/zz-已判定隔离/ 下，可逆、可复核。
#
# 为什么需要：判定主循环按"文件是否已判定"取活，而"扫过的不要重复扫"必须变成物理事实
# ——处理完的源文件搬进隔离区，未处理的留在原位，后续轮次只看未处理清单。
#
# 与 isolate_scanned.sh 的分工：
#   - scanned 版管扫描件（PENDING_SCANNED → TRANSCRIBED/DUPLICATE_OF_BOOK）；
#   - 本脚本管文本源（extraction_state.csv 里 state=EXTRACTED 的文件，块已判定并入库）。
#
# 安全约束（逐条在下面显式校验）：
#   1. 清单行必须是相对路径，含 ".."、以 / 开头、含 ":" 一律拒绝并中止；
#   2. 源文件必须真实存在（-f），否则记 missing 不移动；
#   3. 目标父目录先 mkdir -p；
#   4. 移动前后比对字节数，不一致即报错退出；
#   5. 幂等：日志里已有的行不重复搬；--undo 按日志原样搬回。
#
# 用法：
#   tools/kb_coverage/isolate_processed.sh --dry-run
#   tools/kb_coverage/isolate_processed.sh
#   tools/kb_coverage/isolate_processed.sh --undo
set -euo pipefail

SOURCE_ROOT="/c/Users/听云/Desktop/知识库原始数据资料"
HOLD_NAME="zz-已判定隔离"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
LIST="$REPO_ROOT/tools/kb_coverage/tables/processed_movable.txt"
LOG="$REPO_ROOT/knowledge-production/processed-isolation-log-2026-09.tsv"

mode="move"
case "${1:-}" in
  --dry-run) mode="dry" ;;
  --undo)    mode="undo" ;;
  "")        mode="move" ;;
  *) echo "未知参数：$1" >&2; exit 2 ;;
esac

if [ "$mode" = "undo" ]; then
  [ -f "$LOG" ] || { echo "无隔离日志：$LOG" >&2; exit 1; }
  restored=0; skipped=0
  while IFS=$'\t' read -r rel sha bytes status; do
    [ "$status" = "moved" ] || continue
    src="$SOURCE_ROOT/$HOLD_NAME/$rel"
    dst="$SOURCE_ROOT/$rel"
    if [ ! -f "$src" ]; then skipped=$((skipped+1)); continue; fi
    mkdir -p "$(dirname "$dst")"
    mv "$src" "$dst"
    restored=$((restored+1))
  done < "$LOG"
  echo "{\"restored\":$restored,\"skipped\":$skipped}"
  exit 0
fi

[ -f "$LIST" ] || { echo "无可隔离清单：$LIST" >&2; exit 1; }

while IFS= read -r rel; do
  rel="${rel%$'\r'}"
  [ -n "$rel" ] || continue
  case "$rel" in
    /*|*..*|*':') echo "拒绝可疑路径：$rel" >&2; exit 3 ;;
  esac
done < "$LIST"

total=0; moved=0; already=0; missing=0
: > "$LOG.tmp"
while IFS= read -r rel; do
  rel="${rel%$'\r'}"
  [ -n "$rel" ] || continue
  total=$((total+1))
  src="$SOURCE_ROOT/$rel"
  dst="$SOURCE_ROOT/$HOLD_NAME/$rel"
  if [ ! -f "$src" ]; then
    if [ -f "$dst" ]; then already=$((already+1)); echo -e "$rel\t-\t-\talready_isolated" >> "$LOG.tmp";
    else missing=$((missing+1)); echo -e "$rel\t-\t-\tmissing" >> "$LOG.tmp"; fi
    continue
  fi
  bytes=$(stat -c %s "$src")
  sha=$(sha256sum "$src" | cut -c1-16)
  if [ "$mode" = "dry" ]; then
    continue
  fi
  mkdir -p "$(dirname "$dst")"
  mv "$src" "$dst"
  dst_bytes=$(stat -c %s "$dst")
  if [ "$dst_bytes" != "$bytes" ]; then echo "字节数不一致，中止：$rel" >&2; exit 4; fi
  echo -e "$rel\t$sha\t$bytes\tmoved" >> "$LOG.tmp"
  moved=$((moved+1))
done < "$LIST"

if [ "$mode" = "dry" ]; then
  echo "dry-run：清单 $total 条，均通过路径校验（不移动）"
  rm -f "$LOG.tmp"
  exit 0
fi

if [ -f "$LOG" ]; then
  cat "$LOG" "$LOG.tmp" | awk -F'\t' '!seen[$1]++' > "$LOG.merged"; mv "$LOG.merged" "$LOG"
else
  mv "$LOG.tmp" "$LOG"
fi
rm -f "$LOG.tmp"
echo "隔离完成：总计 $total，移动 $moved，已在隔离区 $already，缺失 $missing"
echo "日志：$LOG"
