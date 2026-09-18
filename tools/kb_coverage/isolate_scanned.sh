#!/usr/bin/env bash
# 隔离"已扫描"的原始扫描件：镜像搬到 <源根>/zz-已扫描隔离/ 下，可逆。
#
# 为什么需要：同一本书被反复扫描/转写（用户明确要求"扫过的不要重复扫了"）。
# 决定表由 tools/kb_coverage/scan_registry.py 产出，本脚本只执行移动。
#
# 安全约束（每条都在下面显式校验）：
#   1. 清单行必须是相对路径，含 ".." 或绝对路径一律拒绝并中止；
#   2. 源文件必须真实存在于源根之下（用 -f 判定），否则记录 missing 不移动；
#   3. 目标目录由源根 + 隔离目录名拼接，父目录先 mkdir -p；
#   4. 移动前后比对字节数，不一致即报错退出（不静默）；
#   5. --undo 按日志原样搬回。
#
# 用法：
#   tools/kb_coverage/isolate_scanned.sh --dry-run
#   tools/kb_coverage/isolate_scanned.sh
#   tools/kb_coverage/isolate_scanned.sh --undo
set -euo pipefail

SOURCE_ROOT="/c/Users/听云/Desktop/知识库原始数据资料"
HOLD_NAME="zz-已扫描隔离"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
LIST="$REPO_ROOT/tools/kb_coverage/tables/scan_movable.txt"
LOG="$REPO_ROOT/knowledge-production/scan-isolation-log-2026-09.tsv"

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
total=0; moved=0; already=0; missing=0; dry=0

verify_lines() {
  while IFS= read -r rel; do
    rel="${rel%$'\r'}"          # 清单可能是 CRLF：去掉行尾 \r，否则路径永远匹配不上
    [ -n "$rel" ] || continue
    case "$rel" in
      /*|*..*|*':') echo "拒绝可疑路径：$rel" >&2; exit 3 ;;
    esac
  done < "$LIST"
}
verify_lines

while IFS= read -r rel; do
  rel="${rel%$'\r'}"
  [ -n "$rel" ] || continue
  total=$((total+1))
  src="$SOURCE_ROOT/$rel"
  dst="$SOURCE_ROOT/$HOLD_NAME/$rel"
  if [ ! -f "$src" ]; then
    if [ -f "$dst" ]; then already=$((already+1)); echo -e "$rel\t-\t-\talready_isolated" >> "$LOG.tmp"; else missing=$((missing+1)); echo -e "$rel\t-\t-\tmissing" >> "$LOG.tmp"; fi
    continue
  fi
  bytes=$(stat -c %s "$src")
  sha=$(sha256sum "$src" | cut -c1-16)
  if [ "$mode" = "dry" ]; then
    dry=$((dry+1)); continue
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
  exit 0
fi

# 合并（幂等：重跑时保留既有 moved 记录，去掉重复行）
if [ -f "$LOG" ]; then cat "$LOG" "$LOG.tmp" | awk -F'\t' '!seen[$1]++' > "$LOG.merged"; mv "$LOG.merged" "$LOG"; else mv "$LOG.tmp" "$LOG"; fi
rm -f "$LOG.tmp"
echo "隔离完成：总计 $total，移动 $moved，已在隔离区 $already，缺失 $missing"
echo "日志：$LOG"
