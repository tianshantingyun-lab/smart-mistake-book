# -*- coding: utf-8 -*-
"""Stage-3 端侧 · 稠密扫描与融合的**数值参考 fixture** 生成脚本。

## 回答什么问题

端侧 JVM 测试不跑模型（LiteRT 是 Android-only），所以"扫描 / 量化还原 / 节点取 max /
融合"这几件纯逻辑必须有一份**来自 Python 参考侧**的期望值，否则端侧只能自证
（自证 = 自己和自己一致，不能证明与离线链同口径）。

## 三块期望值

1. **资产头**：`core/data/src/main/resources/knowledge/dense/bge-small-zh-int8.vec` 的头字段
   与 sha256（端侧解析器要与它逐字段对上，且按 sha 校验身份）；
2. **量化**：`dense_asset.quantize_int8`（权威实现，打包侧同一个函数）对若干 fp32 向量的
   (int8, scale) 期望值，含 `max=0` 的退化解（scale=1.0、q 全 0）；
3. **扫描**：对 4 条金标查询（每科 1 条），把该科**全部原子节点**的节点分冻结下来——
   节点分 = 该节点全部向量 cosine 的 max，cosine 按端侧口径算：
   `scale[r] * Σ_i (int8[r][i] * query[i])`（query 为 int8 模型输出的 L2 归一化查询向量，
   取 `build/dense-model/int8-queries.npy`），同时给出 numpy fp32（BLAS）值供对照。
   端侧实现只要与前者在 1e-5 内一致即可（差值是累加顺序，不是口径）。
4. **融合**：**直接调用** `stage3_expectation.minmax` / `best_first`（离线判分器里的权威实现）
   对合成的候选域算期望次序，覆盖 min-max 边界（全等分、单元素、负分）、缺腿给 0、
   并列按 node_id 升序、空域。

产物（入库，JVM 测试读）：
- `core/data/src/test/resources/dense/dense-scan-reference.txt`
- `core/data/src/test/resources/dense/dense-fusion-reference.txt`

用法（仓库根下）：
```
python tools/dense_build/gen_dense_device_fixture.py
```
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
import dense_asset as D  # noqa: E402
import stage3_expectation as S  # noqa: E402（融合口径的权威实现：minmax / best_first）

SCAN_RELATIVE = "core/data/src/test/resources/dense/dense-scan-reference.txt"
FUSION_RELATIVE = "core/data/src/test/resources/dense/dense-fusion-reference.txt"
ALPHA = 0.5
VECTOR_FILE = Path("core/data/src/main/resources/knowledge/dense") / D.VECTOR_FILE_NAME

QUANTIZE_CASES = [
    ("simple", [0.5, -0.25, 0.125, -1.0], "极值 -1.0 ⇒ scale=1/127"),
    ("zero_vector", [0.0, 0.0, 0.0], "退化：max=0 ⇒ scale=1.0、q 全 0（不产生 NaN）"),
    ("rounding", [0.9, 0.1, -0.7], "四舍五入到最近整数格"),
    ("all_positive", [0.02, 0.01, 0.005], "小幅值向量"),
]

DOT_CASES = [
    ("unit_axis", [127, 0, 0, 0], [1.0, 0.0, 0.0, 0.0], 1.0 / 127.0),
    ("negated", [-127, 0, 0, 0], [1.0, 0.0, 0.0, 0.0], 1.0 / 127.0),
    ("mixed", [1, -2, 3, -4], [0.5, 0.25, -0.125, 1.0], 1.0 / 127.0),
]

# 融合用例：domain / dense(缺省空) / lex(缺省空) / 说明；期望次序由 S.minmax + α 加权 + S.best_first 现算。
FUSION_CASES = [
    ("two_legs_normal", ["a", "b", "c"], {"a": 0.9, "b": 0.5, "c": 0.1}, {"a": 1.0, "b": 3.0, "c": 2.0},
     "两腿都有分：min-max 后 α=0.5 加权"),
    ("dense_all_equal", ["a", "b", "c"], {"a": 0.4, "b": 0.4, "c": 0.4}, {"a": 1.0, "b": 2.0, "c": 3.0},
     "min-max 上界=下界 ⇒ 该腿全体 0（hi<=lo 分支）"),
    ("single_node_domain", ["only"], {"only": 0.7}, {"only": 2.0}, "单元素域：两腿都退化为 0"),
    ("dense_missing", ["a", "b"], {}, {"a": 5.0, "b": 1.0}, "稠密腿整条缺失 ⇒ 全 0，不参与 min-max"),
    ("lex_missing", ["a", "b"], {"a": 1.0, "b": 0.0}, {}, "词面腿整条缺失 ⇒ 全 0"),
    ("partial_missing", ["a", "b", "c"], {"a": 0.8, "c": 0.2}, {"b": 4.0},
     "两腿各自缺一个节点：缺的那个拿 0，且不进对方的 min-max"),
    ("tie_break", ["b", "a", "c"], {"a": 0.5, "b": 0.5, "c": 0.5}, {"a": 2.0, "b": 2.0, "c": 2.0},
     "全并列 ⇒ 按 node_id 升序（best_first 的定序约定）"),
    ("negative_dense", ["a", "b", "c"], {"a": -0.2, "b": -0.6, "c": -0.9}, {"a": 1.0, "b": 1.0, "c": 1.0},
     "负分（余弦可为负）：min-max 在负区间上做"),
    ("empty_domain", [], {}, {}, "空候选域 ⇒ 空序列"),
    ("domain_not_in_legs", ["x", "a"], {"a": 0.3}, {"a": 1.0},
     "域里有节点两腿都无分 ⇒ 该节点 0 分（仍留在域里）"),
]


def fmt(values) -> str:
    return " ".join(repr(float(v)) if isinstance(v, (int, float)) else str(v) for v in values)


def read_raw_asset(path):
    """直读 .vec 的**原始 int8 行与 per-vector scale**（不经过还原）。

    布局是 `dense_asset.py` 的权威定义（magic/version/dim/count/dtype/idsLen + ids 块 +
    int8 矩阵 + f32 scales）；这里直读是为了让 fixture 钉住**真实存储字节**，
    而不是"把还原值再量化一遍"（后者可能差 1 个量化格）。
    """
    import struct
    with Path(path).open("rb") as handle:
        head = handle.read(24)
        magic = head[:4]
        if magic != D.MAGIC:
            raise SystemExit("magic 不符：%r" % magic)
        version, dim, count, dtype, ids_bytes = struct.unpack("<IIIII", head[4:])
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
    if offset != len(block):
        raise SystemExit("ids 块长度与 count 不符")
    return dict(version=version, dim=dim, count=count, dtype=dtype, idsBytesLength=ids_bytes,
                magic=magic.decode("ascii")), ids, matrix, scales


def scan_cases(root: Path):
    cases = S.D.goldens(root)
    subjects = sorted({case["subject"] for case in cases})
    picked = []
    for subject in subjects:
        for index, case in enumerate(cases):
            if case["subject"] == subject:
                picked.append(index)
                break
    vector_path = root.joinpath(*VECTOR_FILE.parts)
    asset_header, ids, matrix, scales = read_raw_asset(vector_path)
    query_int8 = np.load(root / "build" / "dense-model" / "int8-queries.npy")
    query_norm = query_int8 / np.maximum(np.linalg.norm(query_int8, axis=1, keepdims=True), 1e-12)
    _rows, nodes, groups = D.atomic_layout(root)
    if ids != [row["node_id"] for row in _rows]:
        raise SystemExit("资产 ids 与包布局不一致（资产过期？跑 pack_dense_asset.py）")
    group_of = {node_id: (start, end) for node_id, start, end in groups}
    out = []
    for index in picked:
        subject = cases[index]["subject"]
        query = query_norm[index].astype(np.float64)
        node_scores = []
        for node in nodes:
            if node["subject"] != subject:
                continue
            start, end = group_of[node["node_id"]]
            best_km = None
            best_blas = None
            for row in range(start, end):
                scale = float(scales[row])
                value = scale * float(np.dot(matrix[row].astype(np.float64), query))
                blas = float(np.dot(matrix[row].astype(np.float32).astype(np.float64) * scale, query))
                best_km = value if best_km is None else max(best_km, value)
                best_blas = blas if best_blas is None else max(best_blas, blas)
            node_scores.append((node["node_id"], best_km, best_blas))
        out.append((index, subject, query.tolist(), node_scores))
    return asset_header, out


def fusion_case_rows():
    rows = []
    for level, domain, dense, lex, note in FUSION_CASES:
        dense_norm = S.minmax(dict(dense))
        lex_norm = S.minmax(dict(lex))
        pairs = [(node_id, ALPHA * dense_norm.get(node_id, 0.0) + (1.0 - ALPHA) * lex_norm.get(node_id, 0.0))
                 for node_id in domain]
        order = S.best_first(pairs)
        rows.append((level, domain, dict(dense), dict(lex), dense_norm, lex_norm, order, dict(pairs), note))
    return rows


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo-root", default=None)
    parser.add_argument("--model", default=None,
                        help="档位键（默认 %s；回退档 bge-small-zh-v1.5）——资产维度按档校验"
                             % D.DEFAULT_MODEL_KEY)
    args = parser.parse_args()
    root = D.repo_root(args.repo_root)
    profile = D.model_profile(args.model)
    print("档位=%s（dim=%d）" % (profile["key"], profile["dim"]))

    vector_path = root.joinpath(*VECTOR_FILE.parts)
    asset_sha = D.sha256_file(vector_path)
    # 旁车里的 sha 必须与实测一致（否则参考 fixture 对的是另一个包）
    sidecar = root.joinpath("core/data/src/main/resources/knowledge/dense") / (D.VECTOR_FILE_NAME + ".json")
    import json
    sidecar_sha = json.loads(sidecar.read_text(encoding="utf-8"))["sha256"]
    if sidecar_sha != asset_sha:
        raise SystemExit("旁车 sha 与实测不符：%s != %s" % (sidecar_sha, asset_sha))

    asset_header, scans = scan_cases(root)
    if asset_header["count"] != 28931 or asset_header["dim"] != profile["dim"]:
        raise SystemExit("资产形状不符（档位 %s 应 28931×%d，实测 %d×%d）"
                         % (profile["key"], profile["dim"], asset_header["count"], asset_header["dim"]))
    expected_bytes = (24 + asset_header["idsBytesLength"] + asset_header["count"] * asset_header["dim"]
                      + asset_header["count"] * 4)
    actual_bytes = vector_path.stat().st_size
    if expected_bytes != actual_bytes:
        raise SystemExit("资产字节数与布局不符：%d != %d" % (expected_bytes, actual_bytes))

    lines = [
        "# Stage-3 端侧 · 稠密扫描数值参考（生成脚本 tools/dense_build/gen_dense_device_fixture.py）",
        "# 资产：%s" % VECTOR_FILE.as_posix(),
        "# 节点分口径：该节点全部向量 cosine 的 max；cosine = scale[r] * Σ_i int8[r][i] * query[i]（float64）",
        "# 查询向量：build/dense-model/int8-queries.npy（同一 int8 ONNX 输出）的 L2 归一化值",
        "# 端侧只要求与 scoreKm 在 1e-5 内一致（差值是累加顺序）；scoreBlas 是同数据的 fp32 对照",
        "[asset]\tsha256=%s\tmagic=%s\tversion=%d\tdim=%d\tcount=%d\tdtypeCode=%d\tidsBytesLength=%d\texpectedBytes=%d"
        % (asset_sha, asset_header["magic"], asset_header["version"], asset_header["dim"],
           asset_header["count"], asset_header["dtype"], asset_header["idsBytesLength"],
           expected_bytes),
    ]
    for label, vector, note in QUANTIZE_CASES:
        matrix = np.asarray([vector], dtype=np.float32)
        quantized, scales = D.quantize_int8(matrix)
        lines.append("[quantize]\t%s\t%s\t%s\t%s\t%s"
                     % (label, fmt(vector), fmt(quantized[0].tolist()), repr(float(scales[0])), note))
    for label, int8_row, query, scale in DOT_CASES:
        expected = float(scale) * float(np.dot(np.asarray(int8_row, dtype=np.float64),
                                               np.asarray(query, dtype=np.float64)))
        lines.append("[dot]\t%s\t%s\t%s\t%s\t%s"
                     % (label, fmt(int8_row), fmt(query), repr(scale), repr(expected)))
    for index, subject, query, node_scores in scans:
        lines.append("[scan]\t%d\t%s\t%s" % (index, subject, fmt(query)))
        for node_id, score_km, score_blas in node_scores:
            lines.append("[node]\t%s\t%s\t%s" % (node_id, repr(score_km), repr(score_blas)))
    scan_path = root.joinpath(*SCAN_RELATIVE.split("/"))
    scan_path.parent.mkdir(parents=True, exist_ok=True)
    scan_path.write_text("\n".join(lines) + "\n", encoding="utf-8", newline="\n")

    fusion_lines = [
        "# Stage-3 端侧 · 融合口径参考（生成脚本 tools/dense_build/gen_dense_device_fixture.py）",
        "# 期望次序由 tools/dense_build/stage3_expectation.py 的 minmax/best_first + α=0.5 加权现算",
        "# 列：[fusion] \\t caseId \\t domain(,) \\t dense_raw(node=score;…) \\t lex_raw(node=score;…) \\t"
        " dense_norm \\t lex_norm \\t 期望次序(,) \\t 逐节点融合分(node=score;…) \\t 说明",
    ]
    for (level, domain, dense, lex, dense_norm, lex_norm, order, pairs, note) in fusion_case_rows():
        fusion_lines.append("[fusion]\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s" % (
            level, ",".join(domain),
            ";".join("%s=%r" % (k, v) for k, v in dense.items()),
            ";".join("%s=%r" % (k, v) for k, v in lex.items()),
            ";".join("%s=%r" % (k, v) for k, v in dense_norm.items()),
            ";".join("%s=%r" % (k, v) for k, v in lex_norm.items()),
            ",".join(order),
            ";".join("%s=%r" % (k, v) for k, v in pairs.items()),
            note))
    fusion_path = root.joinpath(*FUSION_RELATIVE.split("/"))
    fusion_path.write_text("\n".join(fusion_lines) + "\n", encoding="utf-8", newline="\n")

    print("scan  %s（%d 条查询 / %d 个节点行）"
          % (SCAN_RELATIVE, len(scans), sum(len(s[3]) for s in scans)))
    print("fusion %s（%d 用例）" % (FUSION_RELATIVE, len(FUSION_CASES)))
    print("asset sha256=%s" % asset_sha)
    print("量化/点积用例：quantize=%d dot=%d" % (len(QUANTIZE_CASES), len(DOT_CASES)))


if __name__ == "__main__":
    main()
