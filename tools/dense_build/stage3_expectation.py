# -*- coding: utf-8 -*-
"""Stage-3 小档 · **设备期望值**（D1 形态的融合参考数与 dense 单路参考数）。

## 这个数是什么

档 2（端侧闭环）跑出来的检索指标，要跟本文件算出来的数**逐个对上**才算闭环跑通。
所以这里算的必须是**端侧将来真会跑的东西**：

| 组成 | 用什么 | 为什么 |
|---|---|---|
| 词面腿 | `build/production-lexical-leg.tsv`（生产 v1：`COUNT(DISTINCT feature)`） | 生产跑的就是这条腿，**不是** Stage-2 的 FTS5 `-bm25` |
| 稠密腿 | `core/data/src/main/resources/knowledge/dense/bge-small-zh-int8.vec`（**int8 资产还原值**） | 端侧拿到的是这份量化件，参考数必须按它算 |
| 查询侧 | `build/dense-model/int8-queries.npy`（同一 int8 ONNX 的输出） | 离线侧 embedding 不接近端侧就没有意义 |
| 返回形态 | **D1**（matched 前置、父节点排其后） | 生产 2026-09-24 起已是 matched 优先 |

## 融合口径（spec §2.4，出数前写死，本脚本不改任何参数）

- 每条腿**在该查询的候选域内** min-max → [0,1]；候选域 = 按科隔离 + 可信过滤后的**并集**；
- 某腿对某节点无分 = 该腿给 0（**不参与 min-max**）；
- `score = 0.5·dense_norm + 0.5·lex_norm`；RRF k=60 只作对照（本文件不算它，参照数在 Stage-2）；
- 节点分 = 该节点全部向量 cosine 的 max；判分窗口 = 前 5 名、命中定义 = 前 5 名里存在
  `:atomic:<expectedSlug>` 结尾的节点；逐章 = 10 章 × 9 条；MRR 只在前 5 名内取名次。

## 词面腿的行序就是权威

`production-lexical-leg.tsv` 的行序 = 生产 SQL 的排序（`COUNT(DESC) → ATOMIC 优先 →
canonical_name ASC → knowledge_node_id ASC`）。**本脚本不重排词面腿**：TSV 只有 count，
重排会丢掉 `canonical_name` 这一层并列键（`ProductionLexicalLegExportTest` 首版实测差 1 位）。
融合序列本身按 `(-score, node_id)` 定序（确定性，与 Stage-2 `best_first` 同约定）。

## 口径自证（脚本内断言）

1. **复现 Stage-2**：用 Stage-2 的 FTS5 词面腿 + fp32 bge 向量跑本脚本的同一套判分，
   必须复现 `D-only-bge` 0.6444（58/90）/MRR 0.505 与 `D-fuse-a0.5-bge` 0.7444（67/90）/MRR 0.62
   ——本脚本因此被证与 `stage2_score.py` 同口径（不是"另写一套"）。
2. **D1 ≡ matched-only**：D1 把 matched 排在前、父节点排其后，判分窗口（前 5）全在 matched 侧
   ⇒ 每条臂的 D1 指标与 matched-only **逐位相同**（断言，不是假设）。

用法（仓库根下）：
```
python tools/dense_build/stage3_expectation.py
```
产物：`build/stage3-device-expectation.json`。
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
import dense_asset as D  # noqa: E402

TOP_K = 5
PROBE_LIMIT = 256
ALPHA = 0.5
STAGE2_VECTORS = "build/stage2-dense-work/vectors"
STAGE2_FTS5_LEG = "build/stage2-dense-offline-lexical.tsv"
OUT_RELATIVE = "build/stage3-device-expectation.json"

# Stage-2 封存值（复现断言用；**不改**）
STAGE2_BGE_DENSE_ONLY = dict(main=0.6444444444444445, chapterMin=0.3333333333333333, mrr=0.505)
STAGE2_BGE_FUSED = dict(main=0.7444444444444445, chapterMin=0.3333333333333333, mrr=0.62)
TOL = 1e-12


def minmax(values):
    """一条腿在本查询候选域内 min-max → [0,1]（无分节点不在 values 里，不参与）。"""
    if not values:
        return {}
    lo = min(values.values())
    hi = max(values.values())
    if hi <= lo:
        return {key: 0.0 for key in values}
    return {key: (value - lo) / (hi - lo) for key, value in values.items()}


def best_first(pairs):
    """(node_id, score) → 名次序列，score 越大越优；并列按 node_id 升序（确定性）。"""
    ordered = sorted(pairs, key=lambda item: item[0])
    ordered.sort(key=lambda item: -item[1])
    return [node_id for node_id, _ in ordered]


def lexical_rows(path):
    """读生产词面腿 TSV → {query_id: [(node_id, count)]}，**保留文件行序**（= 生产排序）。"""
    out = {}
    for line in Path(path).read_text(encoding="utf-8").splitlines():
        if not line or line.startswith("#"):
            continue
        parts = line.split("\t")
        if len(parts) != 3:
            raise SystemExit("词面 TSV 列数应为 3：%r" % line)
        out.setdefault(int(parts[0]), []).append((parts[1], float(parts[2])))
    return out


def dense_node_scores(docs, query_vec, groups):
    """节点分 = max over 该节点的向量 cosine（向量都已 L2 归一化 ⇒ 点积即余弦）。"""
    rows = []
    starts = []
    node_ids = []
    for node_id, start, end in groups:
        node_ids.append(node_id)
        starts.append(len(rows))
        rows.extend(range(start, end))
    matrix = docs[np.asarray(rows, dtype=np.int64)]
    sims = matrix @ query_vec
    scores = np.maximum.reduceat(sims, np.asarray(starts, dtype=np.int64))
    return dict(zip(node_ids, scores.tolist()))


class Judgement:
    """一条排序在一个判分窗口下的结果（主集/逐章/MRR/逐题名次）。"""

    def __init__(self, ranking_by_case, cases):
        self.ranks = []
        self.probes = []
        self.top5 = []
        for index, case in enumerate(cases):
            ranking = ranking_by_case[index]
            window = ranking[:TOP_K]
            rank = 0
            for position, node_id in enumerate(window):
                if node_id.endswith(":atomic:" + case["expectedSlug"]):
                    rank = position + 1
                    break
            probe = 0
            for position, node_id in enumerate(ranking[:PROBE_LIMIT]):
                if node_id.endswith(":atomic:" + case["expectedSlug"]):
                    probe = position + 1
                    break
            self.ranks.append(rank)
            self.probes.append(probe)
            self.top5.append(window)
        self.cases = cases

    @property
    def hits(self):
        return sum(1 for rank in self.ranks if rank > 0)

    @property
    def main(self):
        return self.hits / float(len(self.ranks))

    @property
    def mrr(self):
        return sum((1.0 / rank) if rank > 0 else 0.0 for rank in self.ranks) / float(len(self.ranks))

    def chapters(self):
        order, grouped = [], {}
        for index, case in enumerate(self.cases):
            chapter = case["chapter"]
            if chapter not in grouped:
                grouped[chapter] = []
                order.append(chapter)
            grouped[chapter].append(self.ranks[index])
        return [(chapter, grouped[chapter]) for chapter in order]

    @property
    def chapter_min(self):
        return min(sum(1 for r in ranks if r > 0) / float(len(ranks)) for _, ranks in self.chapters())

    def summary(self):
        return dict(main=self.main, hits=self.hits, chapterMin=self.chapter_min, mrr=self.mrr,
                    total=len(self.ranks))


def shape_rankings(ranking, parent_by_id, pack_order_index, subject_domain):
    """由一条排序导出**判分形态与生产形态**（与 stage2_score.shape_rankings 同语义的那一支）。

    - `matched-only`：直接取排序（排序本身有多好）；
    - `D1`：matched 前置（前 5）→ 其父节点（topic）按包内顺序排其后 = **当前生产形态**
      （2026-09-24 起 matched 优先），也就是本阶段的参考形态。

    **不再输出 `production`（父节点前置）**：那是 D1 落地前的旧形态，只在 Stage-2 作历史
    对照；本阶段的参考数是 D1，多留一个旧形态的数只会给端侧多一个可能对错的目标。
    """
    matched5 = ranking[:TOP_K]
    matched_set = set(matched5)
    parent_ids = []
    for node_id in matched5:
        parent_id = parent_by_id.get(node_id)
        if parent_id and parent_id not in matched_set and parent_id not in parent_ids:
            parent_ids.append(parent_id)
    parents = sorted(parent_ids, key=lambda pid: pack_order_index.get(pid, 1 << 30))
    parents = [pid for pid in parents if pid in subject_domain]
    return {"matched-only": ranking, "D1": matched5 + [p for p in parents if p not in matched_set]}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo-root", default=None)
    args = parser.parse_args()
    root = D.repo_root(args.repo_root)

    rows, nodes, groups = D.atomic_layout(root)
    cases = D.goldens(root)
    vector_path = root.joinpath(*D.DENSE_DIR_RELATIVE.split("/")) / D.VECTOR_FILE_NAME
    header, docs = D.load_vector_file(vector_path)
    if header["count"] != 28932:
        raise SystemExit("向量资产行数应为 28,932，实测 %d" % header["count"])
    ids = header["ids"]
    # ids 块是**逐向量**的（28,932 条；同一节点的向量在矩阵里连续，id 逐行重复出现）
    # ——与打包侧（pack_dense_asset.py）同一约定。
    if ids != [row["node_id"] for row in rows]:
        raise SystemExit("向量资产 ids 与包布局不一致（资产过期？跑 pack_dense_asset.py）")

    node_by_id = {node["node_id"]: node for node in nodes}
    subject_of = {node["node_id"]: node["subject"] for node in nodes}
    parent_by_id = {node["node_id"]: node["parent_id"] for node in nodes}
    pack_order = {node["node_id"]: i for i, node in enumerate(nodes)}
    subjects = sorted({node["subject"] for node in nodes})
    # 候选域 = 按科隔离 + 可信过滤后的并集。原子节点来自向量资产；**topic 节点也在域里**
    # （生产候选池含 TOPIC；dense 腿对 topic 无分 ⇒ 融合时给 0，与 Stage-2 同语义）。
    topic_ids_by_subject = {}
    for node in nodes:
        topic_ids_by_subject.setdefault(node["subject"], set()).add(node["parent_id"])
    groups_by_subject = {subject: [g for g in groups if node_by_id[g[0]]["subject"] == subject]
                         for subject in subjects}
    domain_by_subject = {
        subject: set(g[0] for g in groups_by_subject[subject]) | topic_ids_by_subject.get(subject, set())
        for subject in subjects
    }

    leg_path = root.joinpath(*D.LEXICAL_LEG_RELATIVE.split("/"))
    leg = lexical_rows(leg_path)
    if len(leg) != 90 or any(not v for v in leg.values()):
        raise SystemExit("生产词面腿应覆盖 90 题且每题有命中，实测 %d 题" % len(leg))

    query_int8 = np.load(root / "build" / "dense-model" / "int8-queries.npy")
    if query_int8.shape != (90, D.BGE_DIM):
        raise SystemExit("查询向量形状应为 (90, 512)，实测 %s" % (query_int8.shape,))
    query_norm = query_int8 / np.maximum(np.linalg.norm(query_int8, axis=1, keepdims=True), 1e-12)

    # ---- 两条腿的逐题分数 ----
    dense_by_case = []
    for index, case in enumerate(cases):
        dense_by_case.append(dense_node_scores(docs, query_norm[index], groups_by_subject[case["subject"]]))

    # ---- 融合 + 三种形态 ----
    def build(case_dense, case_lex, label):
        rankings, shapes = [], {"matched-only": [], "D1": []}
        for index, case in enumerate(cases):
            subject = case["subject"]
            domain = domain_by_subject[subject]
            dense_norm = minmax(case_dense[index])
            # case_lex 既可能是 [(node_id, 分数)]（生产词面腿 TSV 原样），也可能是
            # {node_id: 分数}（诊断臂自造的空腿）——`dict()` 对两种形态都成立，统一成映射再用。
            lex_norm = minmax({key: float(value) for key, value in dict(case_lex[index]).items()})
            pairs = []
            for node_id in domain:
                pairs.append((node_id, ALPHA * dense_norm.get(node_id, 0.0)
                              + (1.0 - ALPHA) * lex_norm.get(node_id, 0.0)))
            ranking = best_first(pairs)
            rankings.append(ranking)
            produced = shape_rankings(ranking, parent_by_id, pack_order, domain)
            for shape in shapes:
                shapes[shape].append(produced[shape])
        judgement = {shape: Judgement(shapes[shape], cases) for shape in shapes}
        # D1 ≡ matched-only（构造使然，断言而非假设）：D1 把 matched 排在前、父节点排其后，
        # 判分窗口（前 5）全在 matched 侧 ⇒ 主集/逐章/MRR 逐位相同。
        a, b = judgement["matched-only"], judgement["D1"]
        if (a.hits, a.mrr, a.chapter_min) != (b.hits, b.mrr, b.chapter_min):
            raise SystemExit("%s 的 D1 与 matched-only 指标不一致（形态实现有问题）" % label)
        return dict(label=label, ranking=rankings, judgement=judgement)

    node_id_list = [node["node_id"] for node in nodes]
    dense_only = build(dense_by_case, [{node_id: 0.0 for node_id in node_id_list}] * len(cases),
                       "D-only-int8")
    # dense 单路：词面腿不参与（候选域仍是同科并集；词面腿对任何节点都给 0 = 无分）
    fused = build(dense_by_case, leg, "D-fuse-a0.5-int8")

    lex_j = Judgement([[node_id for node_id, _ in leg[i]] for i in range(len(cases))], cases)
    print("词面腿（生产 v1，D1 形）单独判读：%s" % json.dumps(lex_j.summary(), ensure_ascii=False))
    print("dense 单路（int8 资产）：%s" % json.dumps(dense_only["judgement"]["D1"].summary(), ensure_ascii=False))
    print("融合 α=0.5（生产词面腿 + int8）：%s" % json.dumps(fused["judgement"]["D1"].summary(), ensure_ascii=False))

    # ---- 口径自证 1：复现 Stage-2（FTS5 腿 + fp32 向量） ----
    reproduce = {}
    stage2_dir = root.joinpath(*STAGE2_VECTORS.split("/"))
    fts5_path = root.joinpath(*STAGE2_FTS5_LEG.split("/"))
    if (stage2_dir / "bge-docs.npy").is_file() and (stage2_dir / "bge-queries.npy").is_file() and fts5_path.is_file():
        fp32_docs = np.load(stage2_dir / "bge-docs.npy")
        fp32_queries = np.load(stage2_dir / "bge-queries.npy")
        fp32_dense = [dense_node_scores(fp32_docs, fp32_queries[i] / max(float(np.linalg.norm(fp32_queries[i])), 1e-12),
                                        groups_by_subject[cases[i]["subject"]]) for i in range(len(cases))]
        # FTS5 腿：负分（越小越优），min-max 前先取反（与 stage2_score.fused_ranking 同口径）
        fts5_rows = lexical_rows(fts5_path)
        neg = [{node_id: -score for node_id, score in fts5_rows[i]} for i in range(len(cases))]
        repro_dense = build(fp32_dense, [{node_id: 0.0 for node_id in node_id_list}] * len(cases),
                            "stage2-D-only-bge")
        repro_fused = build(fp32_dense, neg, "stage2-D-fuse-a0.5-bge")
        for name, judgement, expected in (
            ("D-only-bge", repro_dense["judgement"]["D1"], STAGE2_BGE_DENSE_ONLY),
            ("D-fuse-a0.5-bge", repro_fused["judgement"]["D1"], STAGE2_BGE_FUSED),
        ):
            got = judgement.summary()
            ok = (abs(got["main"] - expected["main"]) < TOL and abs(got["mrr"] - expected["mrr"]) < TOL
                  and abs(got["chapterMin"] - expected["chapterMin"]) < TOL)
            reproduce[name] = dict(got=got, expected=expected, ok=bool(ok))
            print("[自证] 复现 Stage-2 %s：%s vs %s ⇒ %s"
                  % (name, got, expected, "一致" if ok else "**不一致**"))
            if not ok:
                raise SystemExit("本脚本未能复现 Stage-2 的 %s——口径与 stage2_score.py 不同源，按纪律不推" % name)
    else:
        print("[自证] Stage-2 产物不在位（%s / %s）——复现断言**未运行**（不算已通过）"
              % (stage2_dir, fts5_path))

    # ---- 量化/来源差异诊断（非参考数）：同一生产词面腿 × fp32 向量 ----
    diagnostic = {}
    if (stage2_dir / "bge-docs.npy").is_file():
        fp32_docs = np.load(stage2_dir / "bge-docs.npy")
        fp32_queries = np.load(stage2_dir / "bge-queries.npy")
        fp32_dense = [dense_node_scores(fp32_docs, fp32_queries[i] / max(float(np.linalg.norm(fp32_queries[i])), 1e-12),
                                        groups_by_subject[cases[i]["subject"]]) for i in range(len(cases))]
        diag_dense = build(fp32_dense, [{node_id: 0.0 for node_id in node_id_list}] * len(cases),
                           "fp32-dense")
        diag_fused = build(fp32_dense, leg, "fp32-fused")
        diagnostic["fp32SameProductionLeg"] = dict(
            denseOnly=diag_dense["judgement"]["D1"].summary(),
            fused=diag_fused["judgement"]["D1"].summary(),
            note="同一份生产词面腿、同一套判分，只把稠密腿换回 fp32 离线向量 ⇒ 差值 = int8 量化+ONNX 路径的影响",
        )
        print("[诊断] 生产词面腿 × fp32 稠密腿：dense %s / fused %s"
              % (diag_dense["judgement"]["D1"].summary(), diag_fused["judgement"]["D1"].summary()))

    # ---- 落盘：设备期望值 ----
    dense_j = dense_only["judgement"]["D1"]
    fused_j = fused["judgement"]["D1"]
    expectation = dict(
        stage="stage3-dense-small-tier-device-expectation",
        arm=dict(
            denseOnly="D-only-int8（bge-small-zh-v1.5 int8 ONNX，节点分 = 向量 cosine 的 max）",
            fused="D-fuse-a0.5-int8（生产 v1 词面腿 + int8 稠密腿，min-max 域内归一、α=0.5、缺分给 0）",
            returnShape="D1（matched 前置、父节点随后；生产 2026-09-24 起为 matched 优先）",
        ),
        refFusedD1=fused_j.summary(),
        refDenseD1=dense_j.summary(),
        refLexicalOnlyD1=lex_j.summary(),
        fusion=dict(alpha=ALPHA, normalization="min-max within the query's subject-isolated candidate domain",
                    missingLeg="0（且不参与 min-max）", rrfK=60,
                    scoredTopK=TOP_K, probeLimit=PROBE_LIMIT),
        golden=dict(path=D.GOLDEN_RELATIVE, sha256=D.GOLDEN_SHA256, cases=len(cases),
                    chapters=len({case["chapter"] for case in cases})),
        lexicalLeg=dict(path=D.LEXICAL_LEG_RELATIVE, sha256=D.sha256_file(leg_path),
                        rows=sum(len(v) for v in leg.values()),
                        score="COUNT(DISTINCT feature.search_feature)，行序 = 生产 SQL 排序（读回不重排）"),
        denseAsset=dict(path=(D.DENSE_DIR_RELATIVE + "/" + D.VECTOR_FILE_NAME),
                        sha256=D.sha256_file(vector_path), count=header["count"], dim=header["dim"],
                        dtype="int8 + per-vector f32 scale（还原后再算余弦）"),
        queryVectors="build/dense-model/int8-queries.npy（同一 int8 ONNX 对 90 条带前缀查询的输出）",
        queryPrefix=D.BGE_QUERY_PREFIX,
        nodeScore="max over the node's vectors (canonicalName + aliases) cosine",
        perCase=dict(
            note="rank = 预期节点在 top-5 内的名次（0 = 未命中）；probe = 放宽到前 256 名的名次（0 = absent）",
            fused=[dict(index=i, subject=case["subject"], chapter=case["chapter"],
                        expectedSlug=case["expectedSlug"], rank=fused_j.ranks[i], probe=fused_j.probes[i],
                        top5=fused_j.top5[i]) for i, case in enumerate(cases)],
            denseOnly=[dict(index=i, expectedSlug=case["expectedSlug"], rank=dense_j.ranks[i],
                            probe=dense_j.probes[i]) for i, case in enumerate(cases)],
        ),
        selfCheck=dict(
            stage2Reproduction=reproduce,
            d1EqualsMatchedOnly=True,
            note="D1 形态的判分窗口（前 5）全在 matched 侧 ⇒ D1 指标与 matched-only 逐位相同（脚本断言）",
        ),
        diagnostics=diagnostic,
        honesty=dict(
            notReference=[
                "diagnostics.fp32SameProductionLeg 是非参考数：参考数以 int8 资产为准",
                "诊断：topic 节点无稠密向量（spec §2.2 向量集不含 topic），融合时给 0——与 Stage-2 同语义",
            ],
            notDone=[
                "端侧真机延迟/包体未测（本文件只给质量参考数）",
                "RRF k=60 对照臂不在本文件（Stage-2 已出数，非本阶段参考）",
            ],
        ),
    )
    out_path = root.joinpath(*OUT_RELATIVE.split("/"))
    out_path.write_text(json.dumps(expectation, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print("落盘：%s" % OUT_RELATIVE)
    print("refFusedD1=%r（%d/90）逐章最小=%r MRR=%r"
          % (fused_j.main, fused_j.hits, fused_j.chapter_min, fused_j.mrr))
    print("refDenseD1=%r（%d/90）逐章最小=%r MRR=%r"
          % (dense_j.main, dense_j.hits, dense_j.chapter_min, dense_j.mrr))
    print("词面腿（生产 v1，D1 形）单独：%r（%d/90）逐章最小=%r MRR=%r"
          % (lex_j.main, lex_j.hits, lex_j.chapter_min, lex_j.mrr))


if __name__ == "__main__":
    main()
