# -*- coding: utf-8 -*-
"""Stage-3 dense 小档 · 向量资产格式与共用件（**入库跟踪**的权威定义）。

## 为什么单开一个模块

`.vec` 的读写有三处要用：打包（`pack_dense_asset.py`）、参考数计算（`stage3_expectation.py`）、
CI 头哈希门（`tools/ci/run_kb_checks.py` 的 dense 节）。三处各写一份布局 = 会漂移；
这里一份，谁都不许另写。

## 文件格式（v1，全小端）

```
偏移   长度              内容
0      4                 magic = b"SMBV"（Smart Mistake Book Vector）
4      4                 uint32 version        = 1
8      4                 uint32 dim            = 512
12     4                 uint32 count          = 28932
16     4                 uint32 dtype          = 1（INT8_PER_VECTOR_F32_SCALE）
20     4                 uint32 idsBytesLength
24     idsBytesLength    ids 块：count × (uint32 utf8Len + utf8 bytes)，**按向量行序**
...    count*dim         int8 矩阵，行主序（第 r 行 = 第 r 条向量的 dim 个分量）
...    count*4           float32 scale，每向量一个（行 r 的还原值 = int8 * scale[r]）
```

量化口径（写死）：**每向量对称 int8**——`scale[r] = max(|v_r|) / 127`，`q = round(v_r / scale[r])`
截到 [-127, 127]。`max = 0` 的退化解按 `scale = 1.0`、`q = 0` 处理（不产生 NaN）。

**为什么是"每向量一个 scale"而不是全局一个**：向量已 L2 归一化，全局 scale 会让
短尾向量的分量化步长过粗；每向量 scale 把误差压在各向量自身的幅度上，代价只是
count×4 字节（28,932×4 = 115,728 B）。还原后仍是 float32 点积（暴力余弦），
不引入 int8 累加器溢出问题。

## 与离线臂（Stage-2）的关系

向量集 = spec §2.2 的 **3,572 条 ATOMIC canonicalName + 25,360 条 alias = 28,932**，
**不含 TOPIC**（与离线臂一致；`include_topics` 只用在诊断臂）。节点分 = 该节点全部
向量 cosine 的 **max**。行序与 `build/stage2-dense-work/stage2_common.py` 的
`build_vector_layout` 逐条相同（打包脚本会用离线臂的 `bge-docs.npy` 逐行对拍来证明）。
"""
from __future__ import annotations

import hashlib
import json
import struct
from pathlib import Path

MAGIC = b"SMBV"
VERSION = 1
DTYPE_INT8_PER_VECTOR_SCALE = 1
HEADER_BYTES = 24

# ---- 仓库内固定路径（不接受外部输入参与路径构造） ----
PACK_RELATIVE = "core/data/src/main/resources/knowledge/moe-2025-four-subjects-v1.json"
PACK_ID = "moe-2025-four-subjects-v1"
DENSE_DIR_RELATIVE = "core/data/src/main/resources/knowledge/dense"
VECTOR_FILE_NAME = "bge-small-zh-int8.vec"
VOCAB_RELATIVE = "tools/dense_build/vocab/bge-small-zh-v1.5-vocab.txt"
TOKENIZER_RELATIVE = "tools/dense_build/vocab/bge-small-zh-v1.5-tokenizer.json"
MODEL_RELATIVE = "build/dense-model/bge-small-zh-v1.5-int8.onnx"
MODEL_FP32_RELATIVE = "build/dense-model/bge-small-zh-v1.5-fp32.onnx"
MODEL_MANIFEST_RELATIVE = "tools/dense_build/model-manifest.json"
LEXICAL_LEG_RELATIVE = "build/production-lexical-leg.tsv"
GOLDEN_RELATIVE = "tools/kb_coverage/tables/golden_queries_v1.json"
GOLDEN_SHA256 = "7c004b763bdd49556e11ff1c9500c9461b09a7383b77f230fa8fd35754e6ae39"

BGE_REPO = "BAAI/bge-small-zh-v1.5"
BGE_REVISION = "7999e1d3359715c523056ef9478215996d62a620"
BGE_LICENSE = "mit"
BGE_QUERY_PREFIX = "为这个句子生成表示以用于检索相关文章："
BGE_MAX_LEN = 512
BGE_DIM = 512


def repo_root(explicit=None) -> Path:
    """仓库根：显式传入或当前工作目录；必须能看见 settings.gradle.kts（不做上溯拼接）。"""
    root = Path(explicit).resolve() if explicit else Path.cwd().resolve()
    if not (root / "settings.gradle.kts").is_file():
        raise SystemExit("不是仓库根（缺 settings.gradle.kts）：%s" % root)
    return root


def sha256_file(path) -> str:
    digest = hashlib.sha256()
    with Path(path).open("rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def atomic_layout(root, pack_path=None):
    """向量集布局：**节点顺序出 canonicalName，紧跟该节点的全部 alias**（只取 ATOMIC）。

    与 spec §2.2 的 28,932 口径、与离线臂 `stage2_common.build_vector_layout` 同源。
    `pack_path` 只为**测试**留（把布局指向一份被改过的包副本，验证门能抓住布局漂移）；
    生产调用一律只传 `root`。

    返回 (rows, nodes, groups)：
      - nodes[i] = dict(node_id, parent_id, subject, name, aliases)，
        `node_id` 是**完整的 knowledgeNodeId**（`kb:<taxonomy>:<subject小写>:atomic:<slug>`，
        与包/DB/生产 TSV 同一命名），不是裸 slug——端侧要拿它回查 Room。
      - rows[i]  = dict(surface, node_id, kind ∈ {canonical, alias}, index)
      - groups   = [(node_id, start, end)]，与 rows 同序（end 不含）
    alias 去重**保序**（`dict.fromkeys`），与离线臂逐字相同。
    """
    source = Path(pack_path) if pack_path else root.joinpath(*PACK_RELATIVE.split("/"))
    pack = json.loads(source.read_text(encoding="utf-8"))
    taxonomy = pack["taxonomyVersion"]
    rows = []
    nodes = []
    groups = []
    for subject in pack["subjects"]:
        key = subject["subject"].lower()
        for topic in subject["topics"]:
            topic_id = "kb:%s:%s:topic:%s" % (taxonomy, key, topic["slug"])
            for point in topic["knowledgePoints"]:
                node_id = "kb:%s:%s:atomic:%s" % (taxonomy, key, point["slug"])
                aliases = list(dict.fromkeys(point.get("aliases") or []))
                start = len(rows)
                rows.append(dict(surface=point["name"], node_id=node_id, kind="canonical", index=start))
                for alias in aliases:
                    rows.append(dict(surface=alias, node_id=node_id, kind="alias", index=len(rows)))
                groups.append((node_id, start, len(rows)))
                nodes.append(dict(node_id=node_id, parent_id=topic_id, subject=subject["subject"],
                                  name=point["name"], aliases=aliases))
    return rows, nodes, groups


def goldens(root):
    path = root.joinpath(*GOLDEN_RELATIVE.split("/"))
    actual = sha256_file(path)
    if actual != GOLDEN_SHA256:
        raise SystemExit("金标集 sha256 不符：%s != %s（判官冻结纪律：不改金标）" % (actual, GOLDEN_SHA256))
    cases = json.loads(path.read_text(encoding="utf-8"))
    if len(cases) != 90:
        raise SystemExit("金标条数应为 90，实测 %d" % len(cases))
    return cases


def quantize_int8(matrix):
    """每向量对称 int8 量化 → (int8 矩阵, float32 scale 向量)。"""
    import numpy as np

    if matrix.ndim != 2:
        raise SystemExit("量化输入应为二维矩阵，实测 %r" % (matrix.shape,))
    peak = np.abs(matrix).max(axis=1)
    scale = np.where(peak > 0.0, peak / 127.0, 1.0).astype(np.float32)
    quantized = np.rint(matrix / scale[:, None]).clip(-127.0, 127.0).astype(np.int8)
    return quantized, scale


def write_vector_file(path, ids, matrix_int8, scales) -> str:
    """按上面的布局落盘；返回该文件的 sha256。ids 与矩阵行数必须一致。"""
    import numpy as np

    count = len(ids)
    dim = matrix_int8.shape[1]
    if matrix_int8.shape[0] != count or scales.shape[0] != count:
        raise SystemExit("ids/矩阵/scale 行数不一致：%d/%d/%d" % (count, matrix_int8.shape[0], scales.shape[0]))
    block = bytearray()
    for node_id in ids:
        raw = node_id.encode("utf-8")
        block += struct.pack("<I", len(raw)) + raw
    out = Path(path)
    out.parent.mkdir(parents=True, exist_ok=True)
    with out.open("wb") as handle:
        handle.write(MAGIC)
        handle.write(struct.pack("<IIIII", VERSION, dim, count, DTYPE_INT8_PER_VECTOR_SCALE, len(block)))
        handle.write(bytes(block))
        handle.write(matrix_int8.astype(np.int8).tobytes(order="C"))
        handle.write(scales.astype("<f4").tobytes(order="C"))
    return sha256_file(out)


def read_header(path) -> dict:
    """只读头 + ids 块（CI 门用；不载矩阵，省内存）。"""
    with Path(path).open("rb") as handle:
        head = handle.read(HEADER_BYTES)
        if len(head) != HEADER_BYTES:
            raise SystemExit("向量文件过短（头都不全）：%s" % path)
        magic = head[:4]
        version, dim, count, dtype, ids_bytes = struct.unpack("<IIIII", head[4:])
        if magic != MAGIC:
            raise SystemExit("向量文件 magic 不符：%r" % magic)
        block = handle.read(ids_bytes)
        if len(block) != ids_bytes:
            raise SystemExit("ids 块被截断：%s" % path)
    ids = []
    offset = 0
    for _ in range(count):
        (length,) = struct.unpack_from("<I", block, offset)
        offset += 4
        ids.append(block[offset:offset + length].decode("utf-8"))
        offset += length
    expected = HEADER_BYTES + ids_bytes + count * dim + count * 4
    return dict(version=version, dim=dim, count=count, dtype=dtype,
                ids=ids, expectedBytes=expected)


def load_vector_file(path):
    """载入并还原成 float32 矩阵（count×dim）+ ids。"""
    import numpy as np

    header = read_header(path)
    count = header["count"]
    dim = header["dim"]
    with Path(path).open("rb") as handle:
        head = handle.read(HEADER_BYTES)
        _, _, _, _, ids_len = struct.unpack("<IIIII", head[4:])
        handle.seek(HEADER_BYTES + ids_len)
        matrix = np.frombuffer(handle.read(count * dim), dtype=np.int8).reshape(count, dim)
        scales = np.frombuffer(handle.read(count * 4), dtype="<f4").reshape(count)
    restored = matrix.astype(np.float32) * scales[:, None]
    return header, restored
