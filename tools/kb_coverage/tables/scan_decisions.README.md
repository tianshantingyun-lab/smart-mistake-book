# 扫描件处置决定表（scan_decisions.csv）

407 个扫描 PDF（`extraction_state.csv` 里 state=PENDING_SCANNED）的**逐文件处置决定**，
每行都带规则名与理由。用途：避免"同一本书被反复扫描/转写"（用户 2026-09-19 明确要求
"扫过的不要重复扫了"）。

## 列

`rel_path, subject, decision, rule, reason`

## 决定取值与含义

| decision | 含义 | 是否隔离 |
|---|---|---|
| `TRANSCRIBED` | 已视觉转写并入库（证据：页图 manifest + 已注册材料） | 是 |
| `DUPLICATE_OF_BOOK` | 与已转写整本同页（证据：页图网格逐页比对） | 是 |
| `TRANSCRIBE_THIS_ROUND` | 本轮渲染并派代理转写 | 否 |
| `TRANSCRIBE_LATER` | 知识型，排后续轮次 | 否 |
| `SKIP_EXERCISES` / `SKIP_ANSWERS` | 题集/答案，按"只要知识点不要题目"跳过 | 否（原地保留） |
| `PENDING_UNCLASSIFIED` | 规则未覆盖，待人工判定 | 否 |

## 关键判定证据（可复核）

- **五三精讲册 4 本 = TRANSCRIBED**：`build/wusan-render/manifest-*.json` 记录 743 页全渲染，
  材料已入库。
- **五三 A版【01】分节精讲册（5_精讲册PDF，56 个文件）= DUPLICATE_OF_BOOK**：
  用页图网格把 `1_1.1 集合.pdf` 的 p1–p5 与整本精讲册 p5–p9 逐页并排核对，为同一本书的
  按节拆分（重复收录每章"考情清单"开篇页，故分节页数之和 239 > 整本 175）；
  判据脚本 `tools/kb_coverage/dedupe_scans.py`。**不是**凭文件名推测。
- **物理"1_讲册PDF"按章包 = TRANSCRIBE_LATER（未判定重复）**：实测与整本精讲册
  **非同一版式/页码**（静电场印在印刷页 133，而整本同页是别的章），保留待转写。

## 隔离怎么执行

`tools/kb_coverage/isolate_scanned.sh`（只搬 `TRANSCRIBED` + `DUPLICATE_OF_BOOK`）：

```bash
bash tools/kb_coverage/isolate_scanned.sh --dry-run   # 只做路径校验
bash tools/kb_coverage/isolate_scanned.sh             # 搬到 <源根>/zz-已扫描隔离/<原相对路径>
bash tools/kb_coverage/isolate_scanned.sh --undo      # 按日志原样搬回
```

- 清单由本目录 `scan_movable.txt` 给出；脚本对每行做校验（拒绝绝对路径、`..`、含 `:` 的行）。
- 可逆日志：`knowledge-production/scan-isolation-log-2026-09.tsv`
  （`rel_path <TAB> sha256前16 <TAB> 字节数 <TAB> 状态`）。
- **副作用须知**：搬走后，`build/wusan-render/manifest-*.json` 里记录的 `source_pdf`
  绝对路径会失效；映射关系以隔离日志为准（页图仍在 `build/` 下，不受影响）。
