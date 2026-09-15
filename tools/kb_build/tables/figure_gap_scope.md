# 图依赖缺口清单（存量补做的靶子）

- 生成：`PYTHONPATH=tools python -m kb_build.audit_figure_gap --root build/kb-assess --write`
- 口径：知识点名称/材料标题含图依赖词（图象·曲线·装置·电路·系谱·示意图·流程图…），
  且其绑定材料里**找不到任何"图已转文"的证据词**（斜率/交点/面积/由图/纵轴/横轴/串联/世代…）
- 数：图依赖知识点 224 个；已转文 172 个；**缺口 52 个（零绑定 24 个）**

## 缺口为什么重要
这些知识点的要害在图形关系里（图象的斜率/面积/交点、电路的连接与动态、装置的流程），
若材料里没有把这些读出来，等于该知识点在库里是"半空的"。

## 覆盖策略（不另跑老素材全书）
这 52 个的主题几乎都在五三覆盖范围内（图象类、电路类、装置类都是五三的考点/微专题），
因此**五三转录 + 绑定即可填掉大部分**。做法：
1. 五三材料绑定时，把这 52 个节点设为**优先目标**（同章内优先绑到它们）
2. 五三未覆盖的少数，再回源页补抽（狂K PDF / 知识清单 docx）
3. 填完后对本清单重跑审计，确认缺口降到 0

## 52 个缺口节点（按绑定材料数升序 → 零绑定优先）

### 零绑定（24 个，最严重）
| 科目 | 节点名 |
|---|---|
| MATH | 幂函数的图象与性质 |
| MATH | 图象法 |
| PHYSICS | 图象法 |
| PHYSICS | E-x图像 |
| PHYSICS | Ep-x图像 |
| PHYSICS | 电路中的基本概念 |
| PHYSICS | 电路中的基本定律 |
| PHYSICS | 曲线运动 |
| PHYSICS | 图象 |
| PHYSICS | 图象信息 |
| PHYSICS | 热力学图像问题 |
| PHYSICS | 电能的远距离输送电路图 |
| PHYSICS | 常见的图象 |
| PHYSICS | 图象的应用 |
| CHEMISTRY | FeCl3溶液腐蚀铜箔（印刷电路板原理） |
| CHEMISTRY | 常见的装置 |
| CHEMISTRY | 分析图像书写热化学方程式 |
| CHEMISTRY | 滴定曲线 |
| CHEMISTRY | 速率-时间图像 |
| CHEMISTRY | 常见图像形式 |
| CHEMISTRY | 析图关键——关注曲线变化趋势，采取"定一议二"法 |
| CHEMISTRY | 含量/转化率—投料比图像 |
| CHEMISTRY | 常规图像解题分析 |
| CHEMISTRY | 化工生产中的复杂图像分析 |

### 有 1~2 条绑定但无读图证据（28 个）
| 科目 | 节点名 | 绑定数 |
|---|---|---|
| MATH | 对数函数的图象与性质应用 | 1 |
| （其余 27 个见 `tools/kb_build/tables/figure_gap_audit.csv` 中 figure_verbalized=no 的行） | | |

## 复跑命令
```bash
PYTHONPATH=tools python -m kb_build.audit_figure_gap --root build/kb-assess
```
