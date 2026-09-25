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
12     4                 uint32 count          = 28931
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
count×4 字节（28,931×4 = 115,724 B）。还原后仍是 float32 点积（暴力余弦），
不引入 int8 累加器溢出问题。

## 与离线臂（Stage-2）的关系

向量集 = spec §2.2 的 **3,572 条 ATOMIC canonicalName + 25,359 条 alias = 28,931**，
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
# **路径名不随档位变**：`.vec` / `.tflite` / 词表 / 模型目录的文件名都是消费侧常量
# （`DenseRecallAssembly.MODEL_ASSET_PATH` 等）与 assets 里的名字。换件只换内容不改名，
# 改名的代价是动端侧装配与随包 assets——这条是"路径名保持不变"的落点。
VECTOR_FILE_NAME = "bge-small-zh-int8.vec"
VOCAB_RELATIVE = "tools/dense_build/vocab/bge-small-zh-v1.5-vocab.txt"
TOKENIZER_RELATIVE = "tools/dense_build/vocab/bge-small-zh-v1.5-tokenizer.json"
MODEL_MANIFEST_RELATIVE = "tools/dense_build/model-manifest.json"
LEXICAL_LEG_RELATIVE = "build/production-lexical-leg.tsv"
GOLDEN_RELATIVE = "tools/kb_coverage/tables/golden_queries_v1.json"
GOLDEN_SHA256 = "7c004b763bdd49556e11ff1c9500c9461b09a7383b77f230fa8fd35754e6ae39"

# ---- 模型档位（Stage-5 参数化：换件只换"哪一档"与档位坐标，路径名一律不动） ----
#
# 为什么要有这张表：Stage-3 的导出链把 repo/revision/dim 写死在模块级常量里，换一支模型
# 就得改多处代码（导出、打包、参考数、两份 fixture 生成器），任何一处漏改都表现为
# "另一支模型在跑"而**没有门会红**。表把"档位"收成一个坐标集合，脚本用 `--model` 选档，
# 默认档 = 仓库当前随包的那一档。
#
# 两档的公共部分（模型族同族：BertModel + WordPiece + CLS 池化 + L2 归一 + 三输入签名）。
MODEL_SHARED = dict(
    license="mit（基座许可证；可再分发）",
    pooling="cls", normalize="l2", docPrefix=None,
    maxLen=512,
    queryPrefix="为这个句子生成表示以用于检索相关文章：",
    inputs=["input_ids", "attention_mask", "token_type_ids"],
    output="sentence_embedding",
    # 端侧模型件（assets 内）**两档同一个文件名**：消费侧 `DenseRecallAssembly.MODEL_ASSET_PATH`
    # 是常量，换件只换内容。`--install` 就是往这里拷。
    tfliteAsset="core/data/src/main/assets/dense/bge-small-zh-v1.5-int8.tflite",
)

MODEL_PROFILES = {
    # 回退档（Stage-3 现役小档）：2026-09-24 真机闭环跑通的那一支。
    # 换件失败/要退回时的**可回退值**：`--model bge-small-zh-v1.5` 即可整条链切回。
    "bge-small-zh-v1.5": dict(
        key="bge-small-zh-v1.5",
        repo="BAAI/bge-small-zh-v1.5",
        revision="7999e1d3359715c523056ef9478215996d62a620",
        dim=512,
        modelStem="bge-small-zh-v1.5",
        note="Stage-3 小档（24M/512 维）；Stage-5 换件的回退档",
        # 同档的离线臂（`build/stage2-dense-work/stage2_encode.py --model bge` 的产物）：
        # 交叉对拍用——证明"行序/文本构造与独立产出的臂逐条同一"，不是自己跟自己比。
        stage2Arm=dict(docs="bge-docs.npy", queries="bge-queries.npy"),
        # 量化口径 = Stage-3 的 per-output-channel 对称 int8（`scale = max/127`）。
        # 小档 4 层 × 512 维上实测过对拍门（0.999010，贴着线过）；**它是本档的历史口径，
        # 不随换件改**（改了口径 = 随包件与旧记录不可比）。
        quantCaliber=dict(kind="perChannelMax", rescaleAlpha=0.0,
                          note="每输出通道 max/127 对称 int8 + fp32 激活（Stage-3 原口径）"),
        # 词表/分词器冻结副本：两档的 vocab.txt **逐字节相同**（sha256 45bbac6b…，实测），
        # 所以冻结副本仍是这一档的词表；tokenizer.json 两档不同（仅 normalizer.lowercase
        # 一处：base=true / small=false），导出侧显式传 do_lower_case=true 覆盖了它 ⇒ 逐条
        # token id 相同（见 README §8）。回退档沿用冻结副本，不额外冻结。
        snapshotFiles=("vocab.txt", "tokenizer.json"),
    ),
    # 目标档（Stage-5 换件）：102.3M/768 维，同族同接口（C-MTEB Retrieval 69.49 同表口径）。
    "bge-base-zh-v1.5": dict(
        key="bge-base-zh-v1.5",
        repo="BAAI/bge-base-zh-v1.5",
        revision="f03589ceff5aac7111bd60cfc7d497ca17ecac65",
        dim=768,
        modelStem="bge-base-zh-v1.5",
        note="Stage-5 换件目标档（102.3M/768 维）；与 bge-small 同族（BertModel+WordPiece+CLS）",
        # 同档的离线臂：`build/stage2-dense-work/stage2_encode.py --model bgebase` 的产物
        # （Stage-5 WP1 已生成，revision 与本档一致）——交叉对拍用它，同档同维才成立。
        stage2Arm=dict(docs="bgebase-docs.npy", queries="bgebase-queries.npy"),
        # 量化口径（Stage-5 WP2 改进，实测：`build/dense_build` 的 README §8.6）：
        # 纯 per-output-channel（小档口径）在 12 层 × 768 维上过不了 ≥0.999（实测 0.9478），
        # 根因是权重里少数"离群列"把整行的量化步长撑粗；沿**输入通道**做一次对角重标定
        # `A·W = (A·D⁻¹)·(D·W)`（D 取该输入通道 max|W| 的 -0.5 次幂、按几何均值归一，纯权重
        # 统计、不需要标定集）后，per-output-channel int8 的保真度回到 0.9991（docs 逐行最小）。
        # **关键是这条口径在 TFLite 里可承载**：存储仍是"每输出通道一个 scale 的 int8 权重"，
        # 激活侧多一个逐通道 fp32 `Mul`（转换链见 convert_onnx_to_tflite.py 的 tf_converter_drqt 路线）。
        # 量化口径（Stage-5 WP2 第三轮，全部实测；错误预算与消融见 README §8.3.3、§8.3.4）：
        # ① 输入通道重标定 α=0.5（第二轮定的，不动）；
        # ② **GPTQ 误差补偿**（`quant_error_compensation.py`）：同一张 int8 网格上换一种舍入，
        #    G 加权权重重构误差中位降到 1/2.0 ⇒ docs 逐行 cosine 最小 0.998638 → 0.998993；
        # ③ 12 个 `intermediate/dense`（FFN 第一层）权重加**二级同口径残差**（+28.3 MB），
        #    实测这一份是同样体积里最划算的（同价的"全部 attention"只到 0.99919，它到 0.99941）
        #    ⇒ docs 逐行 cosine 最小 0.999246（150 条最差行 + 90 查询的子集；全量判定见清单）。
        # 嵌入表**不做**二级残差：实测只值 +5e-5（且与 GPTQ 叠加时在噪声内），不值那 16.6 MB。
        quantCaliber=dict(kind="perChannelMax+inputChannelRescale+gptq+ffnResidual2",
                          rescaleAlpha=0.5,
                          embedLevels=1,
                          residualMatmuls=("intermediate/dense",),
                          columnStatistic="max|W| over output channels（纯权重统计，无标定集）",
                          normalization="geometric mean（尺度整体保持在 1 附近）",
                          errorCompensation=dict(
                              method="gptq（逐列量化 + Hessian 误差补偿）",
                              tool="tools/dense_build/quant_error_compensation.py",
                              damping=0.01, calibrationRows=1024,
                              calibrationSource="语料随机抽样的 surface（不含金标查询）",
                              note="只改「取网格上哪个点」，网格（f、s）与编码链一字不改"),
                          note="per-output-channel int8 × 输入通道重标定（α=0.5）+ GPTQ 误差补偿"
                               "（同体积）+ FFN 第一层两级残差（+28.3 MB）；激活保持 fp32"),
        # 该档的 `.tflite` 路线（见 convert_onnx_to_tflite.py 的 `--route`）：
        # - `flatbuffer_direct`（小档的出货路线）**会把 int8 权重展开成 fp32 常量**，
        #   base 档实测 341.6 MiB ⇒ 这条对小档成立、对大档不成立。
        # - `tf_converter_drqt`（走 TF 转换器的 dynamic-range 量化）：小档实测 23.7 MiB、
        #   base 档在 onnx2tf 的 `embeddings/Add_1` 上被布局启发式挡住（`Dimensions must be
        #   equal, but are 512 and 768`）⇒ 这条对大档不成立（值保留在 `--route` 可选项里，
        #   供小档/复现用）。
        # - `flatbuffer_direct_keepint8`（**本档采用**）：与 flatbuffer_direct 同一条 onnx2tf
        #   命令，只把入口换成"不折常量 DequantizeLinear"的包装 —— 权重以 int8 存储进
        #   flatbuffer，`DEQUANTIZE` 由 op_builder 正常生成，数值与 int8 ONNX 同一条算式
        #   （不再有第二次量化）。它消灭的失败 = 上面第一条的 341.6 MiB。
        tfliteRoute="flatbuffer_direct_keepint8",
        # vocab.txt 与小档逐字节相同 ⇒ 冻结副本（含端侧 `knowledge/dense/` 里的那份）不动；
        # tokenizer.json 若覆盖成 base 的，会让 `gen_tokenizer_fixture.py` 的 pinned sha 与
        # 端侧既有 fixture 一起失效——而它的差异只在 normalizer.lowercase，被导出侧显式
        # kwargs 覆盖 ⇒ 冻结副本保持（不改内容、不改路径）。
        snapshotFiles=("vocab.txt",),
    ),
}

# 仓库当前随包的那一档（`--model` 不带时的默认值）。
#
# **默认档必须与"随包那一档"一致**：不带上 `--model` 跑任何脚本，都是在拿某一档的查询向量
# 去对另一档的资产时才会发现的形状不符——所以默认值跟着**随包件**走，而不是跟着"最强的那档"走。
#
# 当前随包件（Stage-3 小档）没换过，所以默认仍是 bge-small-zh-v1.5。
# 换件目标档 bge-base-zh-v1.5 的坐标/工具链/口径都已就绪（`--model bge-base-zh-v1.5` 可整条链复算），
# 它的实测读数（全量 28,931 行，口径见该档 quantCaliber 与 README §8.3.4）：
# - 编码器对拍（int8 ONNX vs torch fp32，门 ≥0.999）：docs **0.999128** / queries **0.999171** ✓
# - 金标融合主集：**0.7889（71/90）**（写死判据 0.7444）
# - `.tflite`：132,375,600 B / 无 Flex·CUSTOM；**宿主单条编码 p50 16,843 ms**（见 README §8.4.1）
# **落不落地由 Stage-5 的写死判据判，不由本文件判**；本文件只保证"默认档 = 随包那一档"这个不变量。
# 随包资产（`.vec` / 旁车 / `.tflite` / `DenseRecallAssembly` 常量）与它保持一致；
# 清单顶层镜像同理，只由 `export_bge_int8.py --publish` 显式改写。
DEFAULT_MODEL_KEY = "bge-small-zh-v1.5"


def model_profile(key=None) -> dict:
    """取档位坐标（公共字段 + 该档字段）。`key=None` 取默认档。"""
    resolved = key or DEFAULT_MODEL_KEY
    if resolved not in MODEL_PROFILES:
        raise SystemExit("未知模型档位 %r；可选：%s" % (resolved, sorted(MODEL_PROFILES)))
    return dict(MODEL_SHARED, **MODEL_PROFILES[resolved])


def model_paths(profile) -> dict:
    """该档的中间物路径（`build/`，可由导出链重生成）。

    ONNX 按档分名（`<stem>-{fp32,int8}.onnx`）：文件名自带档位，不会出现"叫 bge-small
    的文件里装着 base"这种不可辨认的状态；`.npy` 输出用通用名（一次导出只有一个有效档），
    它的身份由 `model-manifest.json` 里的 sha256/维度/行数钉住，打包侧逐个复核。
    """
    stem = profile["modelStem"]
    return dict(
        fp32="build/dense-model/%s-fp32.onnx" % stem,
        int8="build/dense-model/%s-int8.onnx" % stem,
        int8Docs="build/dense-model/int8-docs.npy",
        int8Queries="build/dense-model/int8-queries.npy",
        fp32Docs="build/dense-model/fp32-docs.npy",
        fp32Queries="build/dense-model/fp32-queries.npy",
    )


_ACTIVE = model_profile()

# 派生常量（保持既有名字：文档、旁车与外部脚本引用它们）。默认档 = 仓库当前随包那一档；
# 回退档 bge-small-zh-v1.5 的对应值：repo="BAAI/bge-small-zh-v1.5"、
# revision="7999e1d3359715c523056ef9478215996d62a620"、dim=512。
# **脚本内部一律用 `model_profile()` 的返回值**，不要直接读这些常量——它们只反映默认档。
BGE_REPO = _ACTIVE["repo"]
BGE_REVISION = _ACTIVE["revision"]
BGE_LICENSE = _ACTIVE["license"]
BGE_QUERY_PREFIX = _ACTIVE["queryPrefix"]
BGE_MAX_LEN = _ACTIVE["maxLen"]
BGE_DIM = _ACTIVE["dim"]
MODEL_RELATIVE = model_paths(_ACTIVE)["int8"]
MODEL_FP32_RELATIVE = model_paths(_ACTIVE)["fp32"]


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

    与 spec §2.2 的 28,931 口径（数随包变：2026-09-25 由 25,360 别名降到 25,359）、与离线臂 `stage2_common.build_vector_layout` 同源。
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
