# -*- coding: utf-8 -*-
"""Stage-3 端侧 · **端侧算法**的离线复算（预测设备形态的指标 + 与参考数的差）。

## 为什么要有这个脚本

`core/data/.../knowledge/dense/` 的端侧实现与离线判分器有一处**定义上的差异**：
任务书写死"稠密腿只影响排序，不改变返回集合的长度语义"，所以端侧的排序域 =
**SQL 词面召回集**（`searchSubjectKnowledgeRecallCandidates` 的 `LIMIT 512`），
而离线参考（`stage3_expectation.py`）的排序域 = 该科全部节点。
差异到底影响多少，不能靠嘴说——本脚本把端侧算法在真数据上跑一遍：

1. 候选域 = 生产词面腿 TSV 的**前 512 行**（行序 = SQL 排序，与 DAO 的 `LIMIT` 同序）；
2. 稠密腿原始分 = 该科全部原子节点（`nodeScores` 同口径，int8 资产 + per-vector scale）；
3. 两条腿各自 min-max（稠密在整科原子节点上、词面在候选集上）→ `α=0.5` 加权；
4. 排序键 `(-score, node_id)`（与 `stage3_expectation.best_first` 同约定）；
5. 判分形态 D1（matched 前置、父节点随后，与生产一致），判分口径复用
   `stage3_expectation.Judgement` / `shape_rankings`（**不另写一套判分**）。

输出三组数（同一份金标、同一套判分）：
- `device`：端侧算法（词面召回域内排序）；
- `reference`：离线参考数（整科域排序，取自 `build/stage3-device-expectation.json`）；
- `deviceFullDomain`：端侧算法但排序域放宽到整科（= 隔离出"域"这一项的影响）。

并落一份端侧 JVM 测试用的期望次序 fixture：
`core/data/src/test/resources/dense/dense-device-order-reference.txt`
（4 条查询：学科 / 查询向量 / 候选集与词面分 / 端侧算法期望次序）。

用法（仓库根下）：
```
python tools/dense_build/stage3_device_sim.py
```
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
import dense_asset as D  # noqa: E402
import stage3_expectation as S  # noqa: E402

DEVICE_ORDER_FIXTURE = "core/data/src/test/resources/dense/dense-device-order-reference.txt"
CANDIDATE_LIMIT = 512  # MAX_KNOWLEDGE_RECALL_CANDIDATES
EXPECTATION_RELATIVE = "build/stage3-device-expectation.json"
VECTOR_FILE = Path("core/data/src/main/resources/knowledge/dense") / D.VECTOR_FILE_NAME


def read_raw_asset(path):
    import struct
    with Path(path).open("rb") as handle:
        head = handle.read(24)
        if head[:4] != D.MAGIC:
            raise SystemExit("magic 不符")
        _version, dim, count, _dtype, ids_bytes = struct.unpack("<IIIII", head[4:])
        block = handle.read(ids_bytes)
        matrix = np.frombuffer(handle.read(count * dim), dtype=np.int8).reshape(count, dim)
        scales = np.frombuffer(handle.read(count * 4), dtype="<f4").reshape(count)
    ids = []
    offset = 0
    for _ in range(count):
        (length,) = struct.unpack_from("<I", block, offset)
        offset += 4
        ids.append(block[offset:offset + length].decode("utf-8"))
        offset += length
    return ids, matrix, scales


def lexical_rows(path):
    """生产词面腿 TSV → {query_id: [(node_id, count)]}，保留文件行序（= SQL 排序）。"""
    out = {}
    for line in Path(path).read_text(encoding="utf-8").splitlines():
        if not line or line.startswith("#"):
            continue
        query_id, node_id, score = line.split("\t")
        out.setdefault(int(query_id), []).append((node_id, int(round(float(score)))))
    return out


def fmt(values) -> str:
    return " ".join(repr(float(v)) for v in values)


def device_order(domain, dense_scores, lexical_scores):
    """端侧 `DenseFusion.order` 的等价实现（直接调参考的 minmax/best_first）。"""
    dense_norm = S.minmax(dict(dense_scores))
    lex_norm = S.minmax(dict(lexical_scores))
    pairs = [(node_id, S.ALPHA * dense_norm.get(node_id, 0.0) + (1.0 - S.ALPHA) * lex_norm.get(node_id, 0.0))
             for node_id in domain]
    return S.best_first(pairs)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo-root", default=None)
    args = parser.parse_args()
    root = D.repo_root(args.repo_root)

    rows, nodes, groups = D.atomic_layout(root)
    cases = D.goldens(root)
    ids, matrix, scales = read_raw_asset(root.joinpath(*VECTOR_FILE.parts))
    if ids != [row["node_id"] for row in rows]:
        raise SystemExit("资产 ids 与包布局不一致")
    subjects = sorted({node["subject"] for node in nodes})
    group_of = {node_id: (start, end) for node_id, start, end in groups}
    subject_of = {node["node_id"]: node["subject"] for node in nodes}
    parent_by_id = {node["node_id"]: node["parent_id"] for node in nodes}
    pack_order = {node["node_id"]: i for i, node in enumerate(nodes)}
    atomic_by_subject = {
        subject: {node["node_id"] for node in nodes if node["subject"] == subject}
        for subject in subjects
    }
    topic_ids_by_subject = {}
    for node in nodes:
        topic_ids_by_subject.setdefault(node["subject"], set()).add(node["parent_id"])
    domain_by_subject = {
        subject: atomic_by_subject[subject] | topic_ids_by_subject.get(subject, set())
        for subject in subjects
    }

    leg = lexical_rows(root.joinpath(*D.LEXICAL_LEG_RELATIVE.split("/")))
    if len(leg) != 90:
        raise SystemExit("生产词面腿应覆盖 90 题，实测 %d（先跑 ProductionLexicalLegExportTest）" % len(leg))
    query_int8 = np.load(root / "build" / "dense-model" / "int8-queries.npy")
    query_norm = query_int8 / np.maximum(np.linalg.norm(query_int8, axis=1, keepdims=True), 1e-12)

    def dense_subject_scores(query, subject):
        out = {}
        for node in nodes:
            if node["subject"] != subject:
                continue
            start, end = group_of[node["node_id"]]
            best = -1e30
            for row in range(start, end):
                value = float(scales[row]) * float(np.dot(matrix[row].astype(np.float64), query))
                best = max(best, value)
            out[node["node_id"]] = best
        return out

    device_rankings, full_rankings, candidate_rows = [], [], []
    for index, case in enumerate(cases):
        subject = case["subject"]
        dense = dense_subject_scores(query_norm[index].astype(np.float64), subject)
        candidates = leg.get(index, [])[:CANDIDATE_LIMIT]
        domain = [node_id for node_id, _count in candidates]
        lexical = {node_id: float(count) for node_id, count in candidates}
        device_rankings.append(device_order(domain, dense, lexical))
        # 整科域：集合迭代顺序不影响结果（并列由 node_id 决定），排序只是为了可复现。
        full_rankings.append(device_order(sorted(domain_by_subject[subject]), dense, lexical))
        candidate_rows.append((index, subject, domain, candidates))

    def judge(rankings):
        shaped = [S.shape_rankings(ranking, parent_by_id, pack_order, domain_by_subject[case["subject"]])
                  for ranking, case in zip(rankings, cases)]
        return S.Judgement([item["D1"] for item in shaped], cases)

    device_judgement = judge(device_rankings)
    full_judgement = judge(full_rankings)

    expectation = {}
    expectation_path = root.joinpath(*EXPECTATION_RELATIVE.split("/"))
    if expectation_path.is_file():
        expectation = json.loads(expectation_path.read_text(encoding="utf-8"))
    ref_fused = expectation.get("refFusedD1", {})
    ref_lexical = expectation.get("refLexicalOnlyD1", {})
    per_case = {item["index"]: item for item in expectation.get("perCase", {}).get("fused", [])}

    print("端侧算法（词面召回域内排序）：%s" % json.dumps(device_judgement.summary(), ensure_ascii=False))
    print("端侧算法（整科域排序）：      %s" % json.dumps(full_judgement.summary(), ensure_ascii=False))
    if ref_fused:
        print("离线参考 refFusedD1：         %s" % json.dumps(ref_fused, ensure_ascii=False))
    if ref_lexical:
        print("离线参考 refLexicalOnlyD1：   %s" % json.dumps(ref_lexical, ensure_ascii=False))

    composition_mismatch = 0
    rank_mismatch = 0
    hit_lost = []
    for index, item in per_case.items():
        device_top5 = device_judgement.top5[index]
        if device_top5 != item["top5"]:
            composition_mismatch += 1
        if device_judgement.ranks[index] != item["rank"]:
            rank_mismatch += 1
        if item["rank"] > 0 and device_judgement.ranks[index] == 0:
            hit_lost.append((index, cases[index]["expectedSlug"]))
    print("与离线参考逐题比对：top-5 成员不同 %d/90、名次不同 %d/90、命中丢失 %d 条 %s"
          % (composition_mismatch, rank_mismatch, len(hit_lost), hit_lost[:5]))

    # ---- 端侧 JVM 端到端期望次序 fixture（4 条查询：每科 1 条，与 scan fixture 同选法）----
    picked = []
    for subject in subjects:
        for index, case in enumerate(cases):
            if case["subject"] == subject:
                picked.append(index)
                break
    lines = [
        "# Stage-3 端侧 · 端侧算法期望次序（生成脚本 tools/dense_build/stage3_device_sim.py）",
        "# 用途：Kotlin 端到端测试（假编码器喂这里的 query 向量 + 这里的候选集 ⇒ 真实资产/融合/排序）",
        "# 候选集 = 生产词面腿前 512 行（id:count，行序 = SQL 排序）；稠密腿 = 该科全部原子节点",
        "# 期望次序 = DenseFusion.order 等价实现（stage3_expectation.minmax/best_first + α=0.5）",
        "# 形状：[case] index \t subject \t query(空格分隔 512 维) → 若干 [candidate] id \t count → [expected] 次序(,)",
    ]
    for index, subject, domain, candidates in candidate_rows:
        if index not in picked:
            continue
        lines.append("[case]\t%d\t%s\t%s" % (index, subject, fmt(query_norm[index].tolist())))
        for node_id, count in candidates:
            lines.append("[candidate]\t%s\t%d" % (node_id, count))
        lines.append("[expected]\t%s" % ",".join(device_order(
            [node_id for node_id, _ in candidates],
            dense_subject_scores(query_norm[index].astype(np.float64), subject),
            {node_id: float(count) for node_id, count in candidates},
        )))
    fixture_path = root.joinpath(*DEVICE_ORDER_FIXTURE.split("/"))
    fixture_path.parent.mkdir(parents=True, exist_ok=True)
    fixture_path.write_text("\n".join(lines) + "\n", encoding="utf-8", newline="\n")
    print("落盘：%s（%d 条查询）" % (DEVICE_ORDER_FIXTURE, len(picked)))


if __name__ == "__main__":
    main()
