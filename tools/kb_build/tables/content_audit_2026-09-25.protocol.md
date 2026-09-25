# `content_audit_2026-09-25.csv` 读表须知（抽样口径旁车）

- 这张表是 **Stage-4 WP2 的绑定裁定**（不是 WP1 的候选表）：409 行，八列
  `subject,slug,material_slug,current_node_slug,suggested_node_slug,verdict,evidence,slice`。
- **唯一生成器**：`tools/kb_build/merge_content_audit_verdicts.py`（只合并、不裁定；
  表 == 8 个 `build/audit-slices/slice-NN.verdicts.csv` 去重排序后的并）。
  口径与计数全文见 `docs/kb-stage4-report-2026-09-25.md`。
- 复算：`PYTHONPATH=tools python -m kb_build.merge_content_audit_verdicts --check`（退出码 0 = 一致）。
- **别重跑 WP1 的 `--write`**：`PYTHONPATH=tools python -m kb_build.audit_content_bindings --write`
  会覆盖本表（写同一路径，出的是 `verdict` 全空的候选表）。

## 列怎么读

| 列 | 含义 |
|---|---|
| `subject` / `slug` | 裁定涉及的节点所属的科与**靶区节点名**（不是材料 slug） |
| `material_slug` | 材料；`current_node_slug` = 它在当前包里的现绑节点 |
| `suggested_node_slug` | **只在 `verdict=REBIND` 时非空**：建议改绑到的节点 |
| `verdict` | `KEEP` 绑定成立／`REBIND` 该改绑／`NONE` 材料不属现绑节点且包内无可承接节点 |
| `evidence` | 逐条语义裁定的理由，引文来自材料正文（见下「引文」） |
| `slice` | 裁定来源切片；**样本种类靠它区分**（见下表） |

## 样本种类：靠 `slice` 分列，**不要混池**

| slice | 样本 | 范围 | 行 |
|---|---|---|---|
| slice-01..04 | 靶子 | 两个最弱章（物理 ch3 / 化学 ch3）的 10 例 MISS 节点及其材料全量 | 31 / 64 / 14 / 18 |
| slice-05 | 靶子 | 登记册 I-04 的 6 条已核实错绑锚点及其现节点全部材料 | 157 |
| slice-06 | **随机** | 四科各 10 条材料的分层抽样（`random.Random(20260925)`，n=40） | 40 |
| slice-07..08 | 靶子 | 数学两个次弱章（ch3 函数 / 选必二 ch4 数列）的 MISS 涉及节点 | 34 / 51 |

- 靶子 369 行 / 随机 40 行。**随机样本 n=40，只能定性定位，不能拿来估计全库或单章错绑率。**
- 行级重叠 1 条：`ext-phy-548b1275c5-003`（滑动摩擦力）在 slice-01 与 slice-06 各判一行，
  两行**都保留**（各自样本要能逐行复算）。

## 引文与口径（别把这张表当"正确率"）

- `evidence` 的引文经核验是材料正文的逐字片段（`verbatim_gap_rows()`，只查**出处**，不判归属）：
  严格逐字 55/56，1 条为转述式引文（`slice-02 / phys-hj2-li-fenjie-duojie-taolun`，见报告第 4 节）。
- 归属判定**全部**来自读材料内容的语义裁定（规范 §4.2 五条），**没有**任何由字符串/正则度量
  得出的绑定结论；**不**复现登记册 A-18 的 42%（I-05 已订正：那个数正则复现不了也不该复现）。
- 金标集冻结未改；名次只用于诊断定位，改绑理由来自材料正文与节点边界。
- `NONE` ≠ 无问题：它是「有问题、但包内没有节点能承接」，要节点侧决定（新节点/合并/退场）。
