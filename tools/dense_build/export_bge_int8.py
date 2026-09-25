# -*- coding: utf-8 -*-
"""dense 档位 · HF 模型 → ONNX fp32 → int8（自转，可复跑）。

档位由 `--model` 选（`dense_asset.MODEL_PROFILES`）：**默认 = 仓库当前随包的那一档**
（`DEFAULT_MODEL_KEY` = `bge-small-zh-v1.5`，Stage-3 现役小档，2026-09-24 真机闭环）；
Stage-5 换件目标档 `bge-base-zh-v1.5` **未落地**（过不了 ≥0.999 对拍门），要跑它必须显式
`--model bge-base-zh-v1.5`。
两档同族同接口（BertModel + WordPiece + CLS 池化 + L2 归一 + 三输入签名）⇒ 本文件除坐标与
维度外**没有按档分叉的逻辑**；ONNX 按档分名、`.npy` 输出通用名（身份由清单里的 sha 钉住）。

## 路线取舍（工具链现实性优先）

`ai-edge-torch` / `tensorflow` / `tflite` **本机均未安装**（实测 ModuleNotFoundError，见
`tools/dense_build/README.md` 的「工具链探针」一节），而 `onnxruntime 1.28.0` 已就位 ⇒
按用户 2026-09-24 的授权走 **ONNX Runtime 路线**：`torch.onnx.export` → fp32 ONNX →
`onnxruntime.quantization.quantize_dynamic` → int8 ONNX。不引入新的大依赖（TF 一条链
在 Windows/CPU 上的安装与体积代价远高于收益，且 LiteRT 只是 ONNX 的下游候选）。

## 图契约（与离线臂逐字同语义，写死不临场试）

- 输入：`input_ids` / `attention_mask` / `token_type_ids`，int64，动态 `[batch, seq]`；
- 输出：`sentence_embedding` = **取 last_hidden_state 的第 0 位（CLS）→ L2 归一化**，
  float32 `[batch, dim]`（dim 按档：small=512 / base=768）。池化与归一化**烘进图里**：
  端侧不用再实现一遍（少一处漂移源），而且"对拍"比较的就是最终句向量本身，与下游余弦同一量。
- 文档侧不加任何指令；查询侧前缀 = `为这个句子生成表示以用于检索相关文章：`
  （spec §2.1，与 `stage2_encode.py` 同源）。
- Tokenizer：`AutoTokenizer` + `sentence_bert_config.json` 的 `do_lower_case` +
  `strip_accents=False`——**这三个默认值不能省**（bge 声明 do_lower_case=true，大写拉丁串
  `Na2CO3`/`CO2` 靠它才切得对；`strip_accents=False` 是 Stage-2 与 sentence-transformers
  逐条对拍得出的），本脚本从包内配置读，不凭印象定。
  **若本档的 `tokenizer.json` 不是仓库里的冻结副本**（bge-base 档即如此：两档 vocab.txt
  逐字节相同、tokenizer.json 仅 `normalizer.lowercase` 一处不同，而这里显式传
  `do_lower_case=True` 把它覆盖掉），脚本会拿**冻结 fixture 的逐条 token id** 当场对拍
  （`core/data/src/test/resources/dense/tokenizer-parity-cases.txt`，即端侧 Kotlin 分词器
  被钉住的那 333 条）——不一致即停（spec §3.2「不一致即停」）。

## 断言（对拍门：cosine ≥ 0.999）

1. **fp32 ONNX vs Python 参考（torch fp32）**：全量 28,931 文档 + 90 查询，逐行 cosine 最小
   值必须 ≥ 0.999999（导出保真，不是量化质量）；
2. **int8 ONNX vs 同一份参考**：逐行 cosine 最小值必须 ≥ 0.999（**本阶段的对拍门**）；
3. 若离线臂的 `bge-docs.npy` / `bge-queries.npy` 在位**且与本档同维**，再对一次（证明向量集
   口径与行序与 Stage-2 离线臂逐条同一，不只是"自己跟自己一致"）；换档后维度不同 ⇒ 这一条
   **不适用**（记 None + 原因，不当作通过），行序口径由 `pack_dense_asset.py` 的
   ids==包布局 + `gen_device_parity_fixture.py` 的行对齐自检另行覆盖。

用法（仓库根下）：
```
python tools/dense_build/export_bge_int8.py                    # 默认档 = 仓库当前随包那一档（bge-small-zh-v1.5）
python tools/dense_build/export_bge_int8.py --model bge-small-zh-v1.5   # 回退档（Stage-3）
```
产物：`build/dense-model/<档>-{fp32,int8}.onnx` + `int8-{docs,queries}.npy` +
`fp32-{docs,queries}.npy`（同档 torch fp32 参考，打包侧算端到端误差用）+ 词表冻结副本 +
`tools/dense_build/model-manifest.json`（按档分条：`modelEntries[<档>]`，顶层镜像当前档）。
"""
from __future__ import annotations

import argparse
import importlib.util
import json
import sys
import tempfile
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
import dense_asset as D  # noqa: E402

REPORT_EVERY = 20
BATCH_SIZE = 64
REFERENCE_COSINE_MIN = 0.999


def probe_toolchain() -> dict:
    """工具链探针：如实记录"哪条路可选"，不猜。"""
    out = {}
    for name in ("torch", "transformers", "onnx", "onnxruntime", "tensorflow", "ai_edge_torch", "tflite"):
        out[name] = importlib.util.find_spec(name) is not None
    return out


def resolve_snapshot(repo, revision) -> str:
    """取模型快照目录（钉 revision）。HF 直连不通时退回**本地已按同一 revision 下好的缓存**。

    这条回退消灭的具体失败（实测，2026-09-25）：`huggingface.co` 直连报
    `ConnectError: [SSL: UNEXPECTED_EOF_WHILE_READING]`，导出链在"网络被掐但快照已在本地"
    时整条跑不起来——而"可由钉住的 revision 重生成"正依赖这份快照。回退只认
    `~/.cache/huggingface/hub/models--<repo>/snapshots/<revision>`（目录名 = commit sha，
    不额外信任任何 `main` 之类可变引用），命中即打印路径与原因；未命中仍按原样报错。
    """
    from huggingface_hub import snapshot_download

    try:
        return snapshot_download(repo, revision=revision)
    except Exception as failure:  # noqa: BLE001 —— 失败原因多种（网络/证书/限流），一律试缓存
        cache = (Path.home() / ".cache" / "huggingface" / "hub"
                 / ("models--" + repo.replace("/", "--")) / "snapshots" / revision)
        if not (cache / "config.json").is_file():
            raise
        print("snapshot_download 失败（%s: %s）⇒ 退回本地缓存快照 %s"
              % (type(failure).__name__, failure, cache))
        return str(cache)


def fixture_token_ids(root) -> list:
    """冻结 fixture 的 (caseId, text, ids)——`gen_tokenizer_fixture.py` 的产物，端侧被钉住的那些。

    列：`kind \\t caseId \\t meta \\t text(转义) \\t ids(空格分隔) \\t tokens`。
    """
    path = root / "core" / "data" / "src" / "test" / "resources" / "dense" / "tokenizer-parity-cases.txt"
    if not path.is_file():
        raise SystemExit("缺 tokenizer 冻结 fixture %s（端侧分词器钉的就是它）——不当作已通过" % path)
    cases = []
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line or line.startswith("#"):
            continue
        parts = line.split("\t")
        if len(parts) < 5:
            raise SystemExit("fixture 列数异常：%r" % line)
        text = (parts[3].replace("\\r", "\r").replace("\\n", "\n")
                .replace("\\t", "\t").replace("\\\\", "\\"))
        cases.append((parts[1], text, [int(token) for token in parts[4].split()]))
    if not cases:
        raise SystemExit("fixture 里没有数据行：%s" % path)
    return cases


def assert_tokenizer_matches_frozen(tokenizer, root, profile) -> dict:
    """本档 tokenizer 必须与**端侧冻结 fixture** 逐条同 id（不一致即停，spec §3.2）。

    为什么这条要单独证：端侧 Kotlin 分词器是按 `tools/dense_build/vocab/` 的冻结副本行为
    钉死的（`DenseTokenizerParityTest`），而冻结副本是 Stage-3 小档那一份。换到
    `bge-base-zh-v1.5` 后，如果"两份词表同字但分词结果不同"，端侧查询与离线出数的向量就
    不在同一口径上——离线主集数在端侧根本复现不了，而且**没有任何门会红**（端侧按自己的
    分词器跑，分数只是悄悄变差）。所以换档时当场对拍，而不是事后靠人眼。
    """
    mismatched = []
    for case_id, text, expected in fixture_token_ids(root):
        # 与 fixture 生成侧同一调用口径：`truncation=True, max_length=maxLen`（含特殊符）
        actual = list(tokenizer(text, truncation=True, max_length=profile["maxLen"])["input_ids"])
        if actual != expected:
            first = next((i for i, (a, b) in enumerate(zip(expected, actual)) if a != b), None)
            mismatched.append((case_id, len(expected), len(actual), first,
                               expected[first] if first is not None else None,
                               actual[first] if first is not None else None))
    if mismatched:
        raise SystemExit("本档 tokenizer 与冻结 fixture 不一致 %d 条（caseId, 期望长度, 实际长度, "
                         "首个差异下标, 期望 id, 实际 id）：%r；端侧口径会漂移，按 spec §3.2"
                         "「不一致即停」，不推。" % (len(mismatched), mismatched[:3]))
    total = len(fixture_token_ids(root))
    print("tokenizer 对拍：本档（%s）× 冻结 fixture %d 条逐条同 id ⇒ 端侧分词口径不漂移"
          % (profile["key"], total))
    return dict(frozenFixturePath="core/data/src/test/resources/dense/tokenizer-parity-cases.txt",
                frozenFixtureCases=total, tokenIdsIdentical=True)


class SentenceEncoder:
    """torch.onnx.export 的包装：显式输入签名 + CLS 池化 + L2 归一化（图内）。"""

    @staticmethod
    def build(torch, backbone):
        class _Encoder(torch.nn.Module):
            def __init__(self):
                super().__init__()
                self.backbone_module = backbone

            def forward(self, input_ids, attention_mask, token_type_ids):
                outputs = self.backbone_module(
                    input_ids=input_ids,
                    attention_mask=attention_mask,
                    token_type_ids=token_type_ids,
                )
                cls = outputs.last_hidden_state[:, 0]
                return torch.nn.functional.normalize(cls, p=2, dim=1)

        return _Encoder()


def quantize_weights_only(fp32_path, int8_path, rescale_alpha=0.0, embed_levels=1,
                          skip_matmul_containing=(), residual_matmul_containing=(),
                          residual_matmul_all=False) -> dict:
    """**权重-only int8（per-channel 对称）+ fp32 计算**——本阶段的产物路线。

    逐节点、可复核地做一件事：`MatMul(A, B)` 里 B 若是常量权重，就把 B 换成
    「int8 权重 + 每输出通道一个 scale」并在 MatMul 前插一条 `DequantizeLinear` 还原成 fp32。
    - 权重存储：`scale[n] = max_k |W[k, n]| / 127`（对称、无 zero point），`W_q = round(W / scale)`；
    - 计算：MatMul 保持 fp32 —— **激活不量化**，也就不会引入 uint8 激活量化那一层偏差；
    - 收益：权重体积 4× 变小（94.9MB → ~24MB），这是"小档"要的那一项；
    - 代价：端侧每层多一次 dequant，算力仍在 fp32（真实收益以档 2 真机实测为准，本文件不预测）。

    **为什么不直接用 `quantize_dynamic`**：那条路会把激活也动态量化成 uint8，实测句向量被
    系统性扭转、过不了对拍门（见 `main()` 里路线 A 的实测段与 `tools/dense_build/README.md`）。
    对拍门是硬要求（cosine ≥ 0.999），所以就低不就高，选保真的那条。

    返回转换统计（转换了几个 MatMul、几个保持 fp32 及原因），供清单落盘与复核。

    `rescale_alpha`（Stage-5 WP2 新增）：**输入通道重标定**的指数，0 = 不做（= Stage-3 口径）。
    做法是逐 MatMul 取 `d_k = (max_n |B[k,n]|)^(-α)`（按几何均值归一），把权重换成 `B·diag(d)`、
    并在激活侧插一条 `Mul(A, d⁻¹)` —— 数学上恒等（`A·B = (A·D⁻¹)·(D·B)`），但因为量化步长按
    每输出通道的最大值定，**先压掉离群列的动态范围**再量化，误差会小一个量级（实测见 README §8.6：
    bge-base 逐行 cosine 最小 0.9478 → 0.999112）。激活侧那条 `Mul` 是逐通道 fp32、常量在
    TFLite 里不被量化 ⇒ 存储仍是"每输出通道 1 字节 + 一个 scale"，**这条改进不花体积**。

    `embed_levels`（Stage-5 WP2 第三轮新增）：嵌入表的**残差两级编码**，2 = 开。
    **它消灭的具体失败**：重标定 α=0.5 之后 docs 的逐行 cosine 最小仍只有 0.998638（差 0.00136），
    缺口全部来自嵌入表 —— docs 的最小值由 2–4 个字的短文本行决定（没有"多 token 平均"，
    嵌入表的 int8 误差直接落到 CLS 上；同批行上"嵌入表未量化"的同口径模拟 min 0.999292）。
    做法：一级仍是**逐列**对称 int8（口径一字不改，`s1 = max|列|/127`），残差
    `R = T - q1·s1` 再用同一口径编码成 `s2/q2`，还原式变成
    `T' = q1·s1 + q2·s2`（图上是 `Gather→DequantizeLinear` 两条 + 一条 `Add`，
    全是内置算子，ONNX 与 TFLite 都承载得住）。逐列误差上界从 `s1/2` 降到 `s2/2 ≈ s1/508`。
    代价：多一张 int8 表 —— bge-base 的嵌入表 21,128×768 实测 +16.2 MB。
    """
    import onnx
    from onnx import helper, numpy_helper

    model = onnx.load(str(fp32_path))
    original_initializers = list(model.graph.initializer)
    by_name = {init.name: init for init in original_initializers}
    replaced_weights = set()
    new_initializers = []
    converted = 0
    left_float = 0
    left_reasons = []
    dq_before: dict = {}
    dq_after: dict = {}
    rescaled = 0
    rescale_rows = []
    residual_matmul_names = []
    residual_matmul_bytes = 0
    residual_matmul_extra_nodes = 0
    def wants_residual(name: str) -> bool:
        """这个权重要不要做"两级残差"编码。

        `residual_matmul_all` 全开；`residual_matmul_containing` 按名字片段选（用来只给
        **经过实测确认贡献最大**的那几层加精度——见 README §8.3.3 的错误预算表）。
        """
        if residual_matmul_all:
            return True
        return any(token in name for token in residual_matmul_containing)

    for node in model.graph.node:
        if node.op_type != "MatMul" or len(node.input) != 2:
            continue
        weight_name = node.input[1]
        weight = by_name.get(weight_name)
        if weight is None:
            left_float += 1
            left_reasons.append("%s: B 不是常量初始化器（激活×激活）" % node.name)
            continue
        if any(token in node.name for token in skip_matmul_containing):
            # 消融用：这一层的权重完全不量化（留 fp32）。用来量"某一层对误差的贡献"。
            left_float += 1
            left_reasons.append("%s: 被 skip_matmul_containing 排除（消融）" % node.name)
            continue
        array = numpy_helper.to_array(weight)
        if array.ndim != 2:
            left_float += 1
            left_reasons.append("%s: B 不是二维（%r）" % (node.name, array.shape))
            continue
        # MatMul 的 B 形状是 (K, N)：K = 输入特征、N = 输出特征 ⇒ 每**输出通道**一个 scale
        mul_node = None
        if rescale_alpha > 0.0:
            # B 是 (K, N)：**输入通道** = 第 0 轴（K），所以"该输入通道的列峰"要对输出轴取 max。
            column_peak = np.abs(array).max(axis=1)                     # (K,) 每个输入通道
            factors = np.where(column_peak > 0.0, column_peak, 1.0) ** (-rescale_alpha)
            factors = (factors / np.exp(np.mean(np.log(factors)))).astype(np.float32)
            before = float(column_peak.max() / np.median(column_peak))
            array = (array * factors[:, None]).astype(np.float32)
            after_peak = np.abs(array).max(axis=1)
            after = float(after_peak.max() / np.median(after_peak))
            inverse_name = weight_name + "_rescale_inverse"
            rescaled_act = weight_name + "_rescaled_activation"
            new_initializers.append(numpy_helper.from_array((1.0 / factors).astype(np.float32),
                                                            inverse_name))
            mul_node = helper.make_node("Mul", [node.input[0], inverse_name], [rescaled_act],
                                        name=weight_name + "/input_channel_rescale")
            node.input[0] = rescaled_act
            rescale_rows.append(dict(name=node.name, in_features=int(array.shape[0]),
                                     columnPeakSpreadBefore=round(before, 4),
                                     columnPeakSpreadAfter=round(after, 4),
                                     factorMin=round(float(factors.min()), 6),
                                     factorMax=round(float(factors.max()), 6)))
            rescaled += 1
        peak = np.abs(array).max(axis=0)
        scale = np.where(peak > 0.0, peak / 127.0, 1.0).astype(np.float32)
        quantized = np.rint(array / scale[None, :]).clip(-127.0, 127.0).astype(np.int8)
        q_name, s_name, dq_name = weight_name + "_int8", weight_name + "_scale", weight_name + "_deq"
        new_initializers.append(numpy_helper.from_array(quantized, q_name))
        new_initializers.append(numpy_helper.from_array(scale, s_name))
        pending = [] if mul_node is None else [mul_node]
        if wants_residual(node.name):
            # 两级残差（与嵌入表同一套办法）：一级口径一字不改，残差同口径再编码一次。
            residual = array - quantized.astype(np.float32) * scale[None, :]
            peak2 = np.abs(residual).max(axis=0)
            scale2 = np.where(peak2 > 0.0, peak2 / 127.0, 1.0).astype(np.float32)
            quantized2 = np.rint(residual / scale2[None, :]).clip(-127.0, 127.0).astype(np.int8)
            q2_name, s2_name = weight_name + "_int8_r2", weight_name + "_scale_r2"
            dq1_name, dq2_name = weight_name + "_deq_r1", weight_name + "_deq_r2"
            new_initializers.append(numpy_helper.from_array(quantized2, q2_name))
            new_initializers.append(numpy_helper.from_array(scale2, s2_name))
            pending.append(helper.make_node("DequantizeLinear", [q_name, s_name], [dq1_name],
                                            name=weight_name + "_dequant_r1"))
            pending.append(helper.make_node("DequantizeLinear", [q2_name, s2_name], [dq2_name],
                                            name=weight_name + "_dequant_r2"))
            pending.append(helper.make_node("Add", [dq1_name, dq2_name], [dq_name],
                                            name=weight_name + "_residual_add"))
            residual_matmul_names.append(node.name)
            residual_matmul_bytes += int(quantized2.nbytes + scale2.nbytes)
            residual_matmul_extra_nodes += 2
        else:
            pending.append(helper.make_node("DequantizeLinear", [q_name, s_name], [dq_name],
                                            name=weight_name + "_dequant"))
        dq_before[node.name] = pending
        node.input[1] = dq_name
        replaced_weights.add(weight_name)
        converted += 1
    if converted == 0:
        raise SystemExit("没有任何 MatMul 的权重被量化——权重-only 路线不适用，需人工决策")
    # ---- 词/位置嵌入表：Gather 的 data 是 rank-2 常量，同样按"列"（= 嵌入维）对称量化 ----
    # 不量化它的话 fp32 体积停在 57MB（21,128×512 的表就占 43MB，比全部 MatMul 加起来还大），
    # 而"小档"要的正是包体：量化后 24.7MB，与审计记录锚点（bge-small-zh int8 ONNX 23.9MB）同量级。
    # `Gather(table_int8, ids)` 之后插一条 `DequantizeLinear`（scale 与最后一维对齐，axis=-1）：
    # Gather(data(V,C), indices(S..), axis=0) 的输出形状是 indices.shape + (C,) ⇒ 最后一维就是 C。
    embed_converted = 0
    embed_skipped = []
    embed_residual_tables = []
    embed_residual_bytes = 0
    for node in model.graph.node:
        if node.op_type != "Gather" or not node.input:
            continue
        table_name = node.input[0]
        table = by_name.get(table_name)
        if table is None or table_name in replaced_weights:
            continue
        array = numpy_helper.to_array(table)
        if array.ndim != 2:
            embed_skipped.append("%s: 表不是二维（%r）" % (table_name, array.shape))
            continue
        axis = next((attr.i for attr in node.attribute if attr.name == "axis"), 0)
        if axis != 0:
            embed_skipped.append("%s: Gather axis=%d（非 0，末维不是嵌入维）" % (table_name, axis))
            continue
        # 与 MatMul 权重同口径：每**列**（= 嵌入维）一个 scale
        peak = np.abs(array).max(axis=0)
        scale = np.where(peak > 0.0, peak / 127.0, 1.0).astype(np.float32)
        quantized = np.rint(array / scale[None, :]).clip(-127.0, 127.0).astype(np.int8)
        q_name, s_name = table_name + "_int8", table_name + "_scale"
        gathered_tmp = node.output[0] + "_int8out"
        original_output = node.output[0]
        new_initializers.append(numpy_helper.from_array(quantized, q_name))
        new_initializers.append(numpy_helper.from_array(scale, s_name))
        node.input[0] = q_name
        node.output[0] = gathered_tmp
        # 注意方向：这条 DequantizeLinear 要排在 Gather **之后**（它吃 Gather 的输出）
        dequant_nodes = [helper.make_node(
            "DequantizeLinear", [gathered_tmp, s_name], [original_output],
            name=table_name + "_dequant", axis=-1)]
        if embed_levels >= 2:
            # 二级残差（见 docstring）：一级的还原值先在 numpy 侧算出来，残差同口径再编码一次。
            # 一级口径**一字不改**（仍是 per-column max/127），所以开与不开只差"多出来的一级"，
            # 不是换了一套口径。
            residual = array - quantized.astype(np.float32) * scale[None, :]
            peak2 = np.abs(residual).max(axis=0)
            scale2 = np.where(peak2 > 0.0, peak2 / 127.0, 1.0).astype(np.float32)
            quantized2 = np.rint(residual / scale2[None, :]).clip(-127.0, 127.0).astype(np.int8)
            q2_name, s2_name = table_name + "_int8_r2", table_name + "_scale_r2"
            gathered2 = node.output[0] + "_int8out_r2"
            deq1 = node.output[0] + "_deq_r1"
            deq2 = node.output[0] + "_deq_r2"
            new_initializers.append(numpy_helper.from_array(quantized2, q2_name))
            new_initializers.append(numpy_helper.from_array(scale2, s2_name))
            # 一级的 DequantizeLinear 改成写到 deq1（原来的输出名留给最后的 Add）
            dequant_nodes = [
                helper.make_node("DequantizeLinear", [gathered_tmp, s_name], [deq1],
                                 name=table_name + "_dequant_r1", axis=-1),
                helper.make_node("Gather", [q2_name, node.input[1]], [gathered2],
                                 name=table_name + "_gather_r2", axis=0),
                helper.make_node("DequantizeLinear", [gathered2, s2_name], [deq2],
                                 name=table_name + "_dequant_r2", axis=-1),
                helper.make_node("Add", [deq1, deq2], [original_output],
                                 name=table_name + "_residual_add"),
            ]
            embed_residual_tables.append(table_name)
            embed_residual_bytes += int(quantized2.nbytes + scale2.nbytes)
        # Gather 之后要按序插的节点（一级时 1 条，两级时 4 条）——顺序必须是拓扑序。
        dq_after[node.name] = dequant_nodes
        replaced_weights.add(table_name)
        embed_converted += 1
    converted += embed_converted
    # 节点顺序：DequantizeLinear 必须排在用到它的 MatMul 之前（ONNX 要求拓扑序。
    # 初版这里在 `model.graph.node` 里找插入的节点——那些节点还没进图，于是"一条都没插进去"，
    # 由下面的计数断言当场抓住）。
    ordered = []
    for node in list(model.graph.node):
        for pending in dq_before.get(node.name, ()):
            ordered.append(pending)
        ordered.append(node)
        for after in dq_after.get(node.name, ()):
            ordered.append(after)
    # 计数断言要覆盖两级残差多出来的节点：每张开残差的嵌入表多 2 个 Gather/DequantizeLinear
    # + 1 个 Add（`converted` 里只算了它 1 个，剩下的在这里补）；每个开残差的 MatMul 多
    # 1 个 DequantizeLinear + 1 个 Add（+2，见 residual_matmul_extra_nodes）。
    residual_extra = 3 * len(embed_residual_tables) + residual_matmul_extra_nodes
    expected_nodes = len(model.graph.node) + converted + rescaled + residual_extra
    if len(ordered) != expected_nodes:
        raise SystemExit("DequantizeLinear/Mul 插入数不符：期望 %d，实际 %d" % (expected_nodes, len(ordered)))
    del model.graph.node[:]
    model.graph.node.extend(ordered)
    # 初始化器：**被替换掉的 fp32 权重不再写回**（体积就是这么省下来的），其余原样 + 新增 int8/scale。
    del model.graph.initializer[:]
    model.graph.initializer.extend(
        [init for init in original_initializers if init.name not in replaced_weights] + new_initializers)
    onnx.checker.check_model(model)
    onnx.save_model(model, str(int8_path))
    return dict(converted=converted, leftFloat=left_float, leftFloatReasons=left_reasons[:8],
                insertedDequant=sum(len(v) for v in dq_before.values()) + len(dq_after),
                convertedEmbeddings=embed_converted, embeddingSkipped=embed_skipped[:8],
                embedLevels=embed_levels, embedResidualTables=embed_residual_tables,
                embedResidualExtraBytes=embed_residual_bytes,
                residualMatMulCount=len(residual_matmul_names),
                residualMatMulExtraBytes=residual_matmul_bytes,
                residualMatMulNames=residual_matmul_names[:8],
                skippedMatMulCount=len(skip_matmul_containing),
                rescaleAlpha=rescale_alpha, rescaledMatMuls=rescaled,
                rescaleRule=("d_k = (max_n |B[k,n]|)^(-alpha)，按几何均值归一；"
                             "激活侧插 Mul(A, 1/d)"
                             if rescale_alpha > 0.0 else "关闭（Stage-3 原口径）"),
                rescaleRows=rescale_rows[:4],
                rescaleColumnPeakSpreadBeforeMax=(max((row["columnPeakSpreadBefore"]
                                                       for row in rescale_rows), default=None)),
                rescaleColumnPeakSpreadAfterMax=(max((row["columnPeakSpreadAfter"]
                                                      for row in rescale_rows), default=None)))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo-root", default=None)
    parser.add_argument("--threads", type=int, default=16)
    parser.add_argument("--model", default=None,
                        help="档位键（默认 %s；回退档 bge-small-zh-v1.5）" % D.DEFAULT_MODEL_KEY)
    parser.add_argument("--quant-rescale-alpha", type=float, default=None,
                        help="权重 int8 前的**输入通道重标定**指数（0=关；默认取档位的 quantCaliber）")
    parser.add_argument("--quant-embed-levels", type=int, choices=(1, 2), default=None,
                        help="嵌入表编码级数（1=只一级 per-column int8；2=再加一级残差；"
                             "默认取档位的 quantCaliber.embedLevels，缺省 1）")
    parser.add_argument("--weights-ongrid", default=None,
                        help="int8 量化的**权重来源**改用这份 fp32 ONNX（= 误差补偿产出的 "
                             "on-grid 件，见 quant_error_compensation.py）；不给则用本脚本刚导出的 "
                             "fp32。torch fp32 参考与 fp32 变体的对拍口径都不变")
    parser.add_argument("--publish", action="store_true",
                        help="过门后**把清单顶层镜像指向本档**。默认不写：顶层镜像 = 仓库当前随包"
                             "那一档的声明，只有换件动作（覆盖 .vec/旁车/.tflite/装配常量）才该动它")
    parser.add_argument("--skip-export", action="store_true", help="只做对拍（复用已有 ONNX）")
    args = parser.parse_args()
    root = D.repo_root(args.repo_root)
    profile = D.model_profile(args.model)
    paths = D.model_paths(profile)
    caliber = profile.get("quantCaliber") or dict(kind="perChannelMax", rescaleAlpha=0.0)
    rescale_alpha = (caliber.get("rescaleAlpha", 0.0) if args.quant_rescale_alpha is None
                     else args.quant_rescale_alpha)
    embed_levels = (int(caliber.get("embedLevels", 1)) if args.quant_embed_levels is None
                    else args.quant_embed_levels)
    residual_matmuls = tuple(caliber.get("residualMatmuls", ()))
    print("档位=%s（repo=%s revision=%s dim=%d；量化口径=%s α=%s；嵌入表级数=%d；"
          "两级残差的 MatMul %r）"
          % (profile["key"], profile["repo"], profile["revision"], profile["dim"],
             caliber.get("kind"), rescale_alpha, embed_levels, residual_matmuls))

    probes = probe_toolchain()
    print("工具链探针：%s" % json.dumps(probes, ensure_ascii=False))
    if not probes["tensorflow"] and not probes["ai_edge_torch"]:
        print("⇒ LiteRT/TF 链不就绪（tensorflow=%s ai_edge_torch=%s）；按预注册取舍走 ONNX Runtime 路线。"
              % (probes["tensorflow"], probes["ai_edge_torch"]))
    if not probes["onnx"] or not probes["onnxruntime"]:
        raise SystemExit("ONNX 路线也不可用（onnx=%s onnxruntime=%s）——两条路都不通，需人工决策。"
                         % (probes["onnx"], probes["onnxruntime"]))

    import onnxruntime as ort
    import torch
    from transformers import AutoModel, AutoTokenizer

    torch.set_num_threads(args.threads)
    ort.set_default_logger_severity(3)

    rows, _nodes, _groups = D.atomic_layout(root)
    cases = D.goldens(root)
    surfaces = [row["surface"] for row in rows]
    print("向量集：%d 条（canonical %d + alias %d），查询 %d 条"
          % (len(surfaces), sum(1 for r in rows if r["kind"] == "canonical"),
             sum(1 for r in rows if r["kind"] == "alias"), len(cases)))
    if len(surfaces) != 28931:
        raise SystemExit("向量集应为 28,931（3,572 节点 + 25,359 别名，2026-09-25 WP3 后），实测 %d" % len(surfaces))

    # ---- 模型与 tokenizer（本地快照优先；缺了才去取） ----
    local_dir = resolve_snapshot(profile["repo"], profile["revision"])
    sbert = Path(local_dir) / "sentence_bert_config.json"
    tokenizer_kwargs = {}
    if sbert.is_file():
        do_lower_case = bool(json.loads(sbert.read_text(encoding="utf-8")).get("do_lower_case", False))
        tokenizer_kwargs = dict(do_lower_case=do_lower_case, strip_accents=False)
    tokenizer = AutoTokenizer.from_pretrained(local_dir, **tokenizer_kwargs)
    print("tokenizer=%s kwargs=%s（取自包内 sentence_bert_config.json）"
          % (type(tokenizer).__name__, tokenizer_kwargs))
    # 本档的 tokenizer.json 若不是仓库里的冻结副本（bge-base 档），当场与端侧冻结 fixture 对拍。
    frozen_tokenizer = root.joinpath(*D.TOKENIZER_RELATIVE.split("/"))
    snapshot_tokenizer = Path(local_dir) / "tokenizer.json"
    if (snapshot_tokenizer.is_file() and frozen_tokenizer.is_file()
            and D.sha256_file(snapshot_tokenizer) != D.sha256_file(frozen_tokenizer)):
        print("本档 tokenizer.json 与冻结副本不同（%s vs %s）⇒ 逐条对拍冻结 fixture"
              % (D.sha256_file(snapshot_tokenizer)[:16], D.sha256_file(frozen_tokenizer)[:16]))
        frozen_parity = assert_tokenizer_matches_frozen(tokenizer, root, profile)
    else:
        frozen_parity = dict(frozenFixturePath=None, frozenFixtureCases=0,
                             tokenIdsIdentical=None,
                             note="本档 tokenizer.json 就是冻结副本 ⇒ 无需另证（端侧 fixture 由它生成）")

    doc_texts = list(surfaces)
    query_texts = [profile["queryPrefix"] + case["query"] for case in cases]

    def tokenize(texts):
        """与 Stage-2 逐字同源：padding=True + truncation + max_length，只取三个 int64 输入。"""
        out = []
        for start in range(0, len(texts), BATCH_SIZE):
            batch = texts[start:start + BATCH_SIZE]
            encoded = tokenizer(batch, padding=True, truncation=True, max_length=profile["maxLen"],
                                return_tensors="pt")
            out.append((encoded["input_ids"].to(torch.int64),
                        encoded["attention_mask"].to(torch.int64),
                        encoded["token_type_ids"].to(torch.int64)))
        return out

    doc_batches = tokenize(doc_texts)
    query_batches = tokenize(query_texts)
    # 逐行取真实 token 数（padding 位在 attention_mask 里是 0）——必须 ≤ maxLen，
    # 否则就是静默截断：离线出的向量对应的是被截断的文本，与端侧/契约口径不一致。
    token_max = max(int(mask.sum(dim=1).max()) for _, mask, _ in doc_batches)
    query_token_max = max(int(mask.sum(dim=1).max()) for _, mask, _ in query_batches)
    print("token 长度：doc max=%d ; query max=%d（上限 %d）" % (token_max, query_token_max, profile["maxLen"]))
    if token_max > profile["maxLen"] or query_token_max > profile["maxLen"]:
        raise SystemExit("分词长度超出 maxLen：doc=%d query=%d（会静默截断，与离线口径不一致）"
                         % (token_max, query_token_max))

    # ---- 参考实现：torch fp32（= spec §2.1 的 bge 口径；同时核对离线臂 npy） ----
    def torch_encode(batches):
        chunks = []
        with torch.inference_mode():
            for input_ids, attention_mask, token_type_ids in batches:
                hidden = model(input_ids=input_ids, attention_mask=attention_mask,
                               token_type_ids=token_type_ids).last_hidden_state
                pooled = torch.nn.functional.normalize(hidden[:, 0], p=2, dim=1)
                chunks.append(pooled.to(torch.float32).cpu().numpy())
        return np.concatenate(chunks, axis=0)

    model = AutoModel.from_pretrained(local_dir, dtype=torch.float32)
    model.eval()
    reference_docs = torch_encode(doc_batches)
    reference_queries = torch_encode(query_batches)
    print("参考实现（torch fp32）：docs=%s queries=%s" % (reference_docs.shape, reference_queries.shape))
    if reference_docs.shape != (len(doc_texts), profile["dim"]):
        raise SystemExit("参考向量形状与档位不符：%s（应 %s）"
                         % (reference_docs.shape, (len(doc_texts), profile["dim"])))

    fp32_path = root.joinpath(*paths["fp32"].split("/"))
    int8_path = root.joinpath(*paths["int8"].split("/"))
    fp32_path.parent.mkdir(parents=True, exist_ok=True)
    # torch fp32 参考向量落盘：打包侧要算"资产 vs fp32 参考实现"的**端到端**误差（换了档以后
    # Stage-2 的 512 维离线臂对不上形状，不落这份就只剩"自己跟自己比"）。身份由清单的 sha 钉住。
    fp32_docs_path = root.joinpath(*paths["fp32Docs"].split("/"))
    fp32_queries_path = root.joinpath(*paths["fp32Queries"].split("/"))
    np.save(fp32_docs_path, reference_docs)
    np.save(fp32_queries_path, reference_queries)
    print("torch fp32 参考落盘：%s / %s" % (paths["fp32Docs"], paths["fp32Queries"]))

    def onnx_encode(session, batches, tag):
        chunks = []
        for index, (input_ids, attention_mask, token_type_ids) in enumerate(batches):
            outputs = session.run(None, {
                "input_ids": input_ids.numpy().astype(np.int64),
                "attention_mask": attention_mask.numpy().astype(np.int64),
                "token_type_ids": token_type_ids.numpy().astype(np.int64),
            })
            chunks.append(np.asarray(outputs[0], dtype=np.float32))
            if index % REPORT_EVERY == 0:
                print("  [%s] %d/%d 批" % (tag, index, len(batches)))
                sys.stdout.flush()
        return np.concatenate(chunks, axis=0)

    # ---- 1) fp32 ONNX 导出 ----
    if not args.skip_export:
        wrapper = SentenceEncoder.build(torch, model)
        wrapper.eval()
        sample_ids, sample_mask, sample_types = doc_batches[0]
        dynamic = {name: {0: "batch", 1: "seq"} for name in
                   ("input_ids", "attention_mask", "token_type_ids")}
        with torch.inference_mode():
            torch.onnx.export(
                wrapper,
                (sample_ids, sample_mask, sample_types),
                str(fp32_path),
                input_names=["input_ids", "attention_mask", "token_type_ids"],
                output_names=["sentence_embedding"],
                dynamic_axes=dynamic,
                opset_version=17,
                do_constant_folding=True,
                dynamo=False,
            )
        print("fp32 ONNX 落盘：%s（%.1f MB）" % (fp32_path, fp32_path.stat().st_size / 1e6))

    # ---- 2) int8 量化：两条路线都实测，**只把过门的那条当产物** ----
    #
    # 路线 A（不采用，仅作证据）：`onnxruntime.quantization.quantize_dynamic` —— 权重 int8 +
    #   **激活动态量化 uint8**（图里是 DynamicQuantizeLinear + MatMulInteger）。实测句向量被系统性
    #   扭转：per-tensor 逐行 cosine 最小 0.9499 / 中位 0.9673，per-channel 最小 0.9872 / 中位 0.9939，
    #   28,932 行**无一**达到 0.999。对拍门（≥0.999）过不了 ⇒ 不作为产物。
    # 路线 B（采用）：**权重-only int8（per-channel 对称）+ fp32 计算**。权重按输出通道取 scale、
    #   存 int8，MatMul 前用一条 DequantizeLinear 还原成 fp32 —— 只吃"权重 4× 变小"这一项
    #   （小档的包体收益正在这里），激活与计算保持 fp32 ⇒ 句向量几乎不变。
    #   **不引入新依赖、不改 ORT 语义**：图上做的是"存 int8、算 fp32"这一件事，可逐节点复核。
    comparison = {}
    if not args.skip_export:
        quant_tmp = root / "build" / "tmp-dense-quant"
        quant_tmp.mkdir(parents=True, exist_ok=True)
        # ORT 的 quantize_dynamic 会在 `tempfile.gettempdir()` 下建临时目录、调
        # `onnx.shape_inference.infer_shapes_path` 做形状推断。本机 `TEMP` 是
        # `C:\Users\听云\AppData\Local\Temp`（含非 ASCII），而 onnx 1.23 的 C++ 文件版推断
        # 在该路径下**静默不产出文件**（4×4 小模型即可复现：ASCII 目录 True、非 ASCII 目录 False），
        # 随后 `onnx.load` 报 FileNotFoundError（`model-inferred.onnx`）。
        # 把临时目录指到仓库内的 ASCII 路径即可（只改临时目录位置，不改 ORT 语义）。
        tempfile.tempdir = str(quant_tmp)
        from onnxruntime.quantization import QuantType, quantize_dynamic

        for label, per_channel, path in (("dynamic-perTensor", False, quant_tmp / "routeA-perTensor.onnx"),
                                         ("dynamic-perChannel", True, quant_tmp / "routeA-perChannel.onnx")):
            quantize_dynamic(str(fp32_path), str(path), weight_type=QuantType.QInt8, per_channel=per_channel)
            comparison[label] = dict(path=str(path.relative_to(root)).replace("\\", "/"),
                                     bytes=path.stat().st_size, perChannel=per_channel)
            print("[路线 A · %s] 落盘 %s（%.1f MB）" % (label, path.name, path.stat().st_size / 1e6))

        # 量化**权重来源**：默认是本脚本刚导出的 fp32；给了 `--weights-ongrid` 就用那份
        # （= `quant_error_compensation.py` 产出的"落在 int8 网格上的 fp32"）。
        # 参考（torch fp32）、`fp32` 变体的对拍、以及清单里 fp32 件的身份**都不变** ——
        # 换的只是"int8 从哪个权重矩阵量化出来"。
        weights_source = fp32_path
        if args.weights_ongrid:
            candidate = Path(args.weights_ongrid)
            if not candidate.is_absolute():
                candidate = root / candidate
            if not candidate.is_file():
                raise SystemExit("--weights-ongrid 指向的文件不存在：%s（先跑 "
                                 "quant_error_compensation.py）" % candidate)
            weights_source = candidate
            print("权重来源 = %s（%.1f MB）" % (weights_source, weights_source.stat().st_size / 1e6))
        stats = quantize_weights_only(weights_source, int8_path, rescale_alpha=rescale_alpha,
                                      embed_levels=embed_levels,
                                      residual_matmul_containing=residual_matmuls)
        comparison["weightOnlyInt8PerChannel"] = dict(
            path=paths["int8"], bytes=int8_path.stat().st_size,
            convertedMatMuls=stats["converted"], leftFloatMatMuls=stats["leftFloat"],
            leftFloatReasons=stats["leftFloatReasons"],
        )
        # 本档口径的完整留档（换件后要能回答"这份 int8 是怎么量出来的"）：
        # 重标定指数、规则、逐矩阵的列峰展布 before/after（越小 = 动态范围压得越平）。
        comparison["caliber"] = dict(
            kind=caliber.get("kind"), rescaleAlpha=stats["rescaleAlpha"],
            rescaleRule=stats["rescaleRule"], rescaledMatMuls=stats["rescaledMatMuls"],
            columnPeakSpreadBeforeMax=stats["rescaleColumnPeakSpreadBeforeMax"],
            columnPeakSpreadAfterMax=stats["rescaleColumnPeakSpreadAfterMax"],
            rescaleRows=stats["rescaleRows"],
            embedLevels=stats["embedLevels"], embedResidualTables=stats["embedResidualTables"],
            embedResidualExtraBytes=stats["embedResidualExtraBytes"],
            residualMatMulCount=stats["residualMatMulCount"],
            residualMatMulExtraBytes=stats["residualMatMulExtraBytes"],
            residualMatMulNames=stats["residualMatMulNames"],
            residualMatMulRule=("选中的 MatMul 权重加一级**同口径残差**（T' = q1·s1 + q2·s2，"
                                "图上多 1 条 DequantizeLinear + 1 条 Add，仍是内置算子）"
                                if stats["residualMatMulCount"] else "关闭（单级）"),
            weightsSource=dict(path=(str(weights_source.resolve().relative_to(root)).replace("\\", "/")
                                     if str(weights_source.resolve()).startswith(str(root))
                                     else str(weights_source.resolve())),
                               sha256=D.sha256_file(weights_source),
                               note="int8 从这份 fp32 权重量化出来；= 原导出件时说明没做误差补偿，"
                                    "= <stem>-fp32-ongrid.onnx 时说明过了 quant_error_compensation.py"),
            embedRule=("一级 per-column 对称 int8 + 二级同口径残差（T' = q1·s1 + q2·s2，"
                       "Gather/DequantizeLinear/Mul/Add 全是内置算子）"
                       if stats["embedLevels"] >= 2 else "只一级 per-column 对称 int8（Stage-3 原口径）"),
            note="存储仍是 int8 权重（每输出通道一 scale）+ fp32 激活；重标定只改误差分布、不改体积",
        )
        print("int8 ONNX 落盘（路线 B，产物）：%s（%.1f MB，%.1f%% of fp32；量化了 %d 个带权 MatMul，"
              "剩下 %d 个是激活×激活，保持 fp32；嵌入表 %d 张、级数 %d、残差 +%d B）" % (
                  int8_path, int8_path.stat().st_size / 1e6,
                  100.0 * int8_path.stat().st_size / fp32_path.stat().st_size,
                  stats["converted"], stats["leftFloat"], stats["convertedEmbeddings"],
                  stats["embedLevels"], stats["embedResidualExtraBytes"]))

    # ---- 3) 对拍 ----
    def cosine_min(left, right):
        left = left / np.linalg.norm(left, axis=1, keepdims=True)
        right = right / np.linalg.norm(right, axis=1, keepdims=True)
        return float((left * right).sum(axis=1).min())

    # 逐条 session 测完即释放：换大档（base，fp32 ONNX ~400MB）后若把 fp32 + int8 + 两个
    # 路线 A 变体同时驻留，峰值内存会顶到本机双会话的边上（实测过 EOF/被杀的那类基础设施
    # 故障）。度量值与"全部同时驻留"逐位相同（同一条 ONNX、同一批输入、同一个 ORT），
    # 只是峰值降下来。
    variants = [("fp32", fp32_path), ("int8", int8_path)]
    # 路线 A 的两个变体也各测一遍（它们是"没有过门"的证据，写进清单；不作为产物）
    for label, entry in sorted(comparison.items()):
        if not entry.get("path"):
            continue  # 口径留档段（caliber）不是模型件，没有 path
        candidate = root / entry["path"]
        if candidate.is_file():
            variants.append(("routeA:%s" % label, candidate))
    report = {}
    for key, model_path in variants:
        session = ort.InferenceSession(str(model_path), providers=["CPUExecutionProvider"])
        docs = onnx_encode(session, doc_batches, "%s-docs" % key)
        queries = onnx_encode(session, query_batches, "%s-queries" % key)
        del session
        report[key] = dict(
            docsCosineMin=cosine_min(docs, reference_docs),
            queriesCosineMin=cosine_min(queries, reference_queries),
            docsCosineMean=float(np.mean((docs / np.linalg.norm(docs, axis=1, keepdims=True)
                                          * reference_docs
                                          / np.linalg.norm(reference_docs, axis=1, keepdims=True)).sum(axis=1))),
        )
        if key == "int8":
            np.save(root.joinpath(*paths["int8Docs"].split("/")), docs)
            np.save(root.joinpath(*paths["int8Queries"].split("/")), queries)
        print("[%s] vs 参考实现：docs cosine min=%.9f mean=%.6f ; queries cosine min=%.9f"
              % (key, report[key]["docsCosineMin"], report[key]["docsCosineMean"],
                 report[key]["queriesCosineMin"]))

    # ---- 4) 与离线臂 npy 的交叉对拍（同维才适用） ----
    # 比的是**产物本身**（int8 句向量）与 Stage-2 离线臂的 fp32 向量：这一条同时证明
    # "行序/文本构造与离线臂逐条同一"（布局或分词一旦不同，逐行 cosine 会立刻塌）与
    # "量化后仍与离线臂同向量集"。不是自己跟自己比。
    # **换档后维度不同**（base 768 vs 离线臂 512）⇒ 这一条按构造不可能成立：如实记 None +
    # 原因（不当作通过），行序口径改由打包侧的 ids==包布局 与设备对拍 fixture 的行对齐自检
    # 覆盖。同维但行数不同仍是硬错（那是真的口径漂移，必须红）。
    offline = root / "build" / "stage2-dense-work" / "vectors"
    shipped_docs = np.load(root.joinpath(*paths["int8Docs"].split("/")))
    shipped_queries = np.load(root.joinpath(*paths["int8Queries"].split("/")))
    cross = {}
    cross_notes = []
    arm = profile["stage2Arm"]
    for arm_key, mine in (("docs", shipped_docs), ("queries", shipped_queries)):
        name = arm[arm_key]
        path = offline / name
        if not path.is_file():
            cross[name] = None
            cross_notes.append("%s 不在位——跳过（**不当作已通过**）" % name)
            print("[交叉] 离线臂 %s 不在位——跳过（**不当作已通过**）" % name)
            continue
        other = np.load(path)
        if other.shape[1] != mine.shape[1]:
            cross[name] = None
            cross_notes.append("%s 与本档不同维（本档 %d vs 离线臂 %d）——该臂按构造不适用，"
                               "不作为通过" % (name, mine.shape[1], other.shape[1]))
            print("[交叉] 离线臂 %s 与本档不同维（%s vs %s）——**不适用**（不作为通过）"
                  % (name, other.shape, mine.shape))
            continue
        if other.shape != mine.shape:
            raise SystemExit("离线臂 %s 形状不符：%s vs %s（同维但行数不同 = 向量集口径已漂移）"
                             % (name, other.shape, mine.shape))
        cross[name] = cosine_min(mine, other)
        print("[交叉] 产物 vs 离线臂 %s 逐行 cosine min=%.9f" % (name, cross[name]))

    thresholds = {
        "fp32_docs": report["fp32"]["docsCosineMin"] >= 0.999999,
        "fp32_queries": report["fp32"]["queriesCosineMin"] >= 0.999999,
        "int8_docs": report["int8"]["docsCosineMin"] >= REFERENCE_COSINE_MIN,
        "int8_queries": report["int8"]["queriesCosineMin"] >= REFERENCE_COSINE_MIN,
    }
    cross_ok = all(value is None or value >= REFERENCE_COSINE_MIN for value in cross.values())
    print("门（cosine ≥ %.3f）：%s" % (REFERENCE_COSINE_MIN,
                                   json.dumps(thresholds, ensure_ascii=False)))
    print("交叉对拍：%s" % json.dumps(cross, ensure_ascii=False))
    if not cross_notes:
        pass
    else:
        for note in cross_notes:
            print("  · 交叉对拍注记：%s" % note)
    passed = bool(all(thresholds.values()))
    cross_ok = all(value is None or value >= REFERENCE_COSINE_MIN for value in cross.values())
    print("门（cosine ≥ %.3f）：%s" % (REFERENCE_COSINE_MIN,
                                   json.dumps(thresholds, ensure_ascii=False)))
    print("交叉对拍：%s" % json.dumps(cross, ensure_ascii=False))
    for note in cross_notes:
        print("  · 交叉对拍注记：%s" % note)

    # ---- 5) 词表冻结（**只在过门时做**：换件失败不在随包侧留任何副作用） ----
    # 冻哪些由档位定（`snapshotFiles`）：小档两份（现状不变）；base 档只冻 vocab.txt
    # （与小档逐字节相同，写下去是幂等的），tokenizer.json 保持冻结副本不动——它的差异只在
    # normalizer.lowercase，被上面显式 kwargs 覆盖，逐条 token id 已当场对拍（见 §冻结 fixture）。
    frozen_targets = {"vocab.txt": D.VOCAB_RELATIVE, "tokenizer.json": D.TOKENIZER_RELATIVE}
    if passed and cross_ok:
        vocab_dir = root.joinpath(*D.VOCAB_RELATIVE.split("/")).parent
        vocab_dir.mkdir(parents=True, exist_ok=True)
        for name in profile["snapshotFiles"]:
            target_rel = frozen_targets[name]
            target = root.joinpath(*target_rel.split("/"))
            source = Path(local_dir) / name
            target.write_bytes(source.read_bytes())
            print("词表冻结：%s ← %s（sha256=%s）" % (target_rel, source.name, D.sha256_file(target)[:16]))
        for name, target_rel in frozen_targets.items():
            if name in profile["snapshotFiles"]:
                continue
            print("词表冻结（保持不动）：%s（本档 %s 与冻结副本不同：%s vs %s；差异仅在 "
                  "normalizer.lowercase，已由 do_lower_case=%s 覆盖并逐条对拍）"
                  % (target_rel, name, D.sha256_file(Path(local_dir) / name)[:16],
                     D.sha256_file(root.joinpath(*target_rel.split("/")))[:16],
                     tokenizer_kwargs.get("do_lower_case")))
    else:
        print("词表冻结：跳过（本轮没过门）")

    # 把路线 A 的实测结果并进 comparison（它是"没采用那条路"的证据）
    for label in list(comparison):
        measured = report.get("routeA:%s" % label)
        if measured:
            comparison[label].update(docsCosineMin=measured["docsCosineMin"],
                                     docsCosineMean=measured["docsCosineMean"],
                                     queriesCosineMin=measured["queriesCosineMin"],
                                     passedGate=bool(measured["docsCosineMin"] >= REFERENCE_COSINE_MIN
                                                     and measured["queriesCosineMin"] >= REFERENCE_COSINE_MIN))
    def npy_fingerprint(path):
        """`.npy` 的身份（sha256 + 行/列）——打包侧据此确认"拿到的就是这一次导出的输出"。"""
        array = np.load(path, mmap_mode="r")
        return dict(path=str(Path(path).resolve().relative_to(root)).replace("\\", "/"),
                    sha256=D.sha256_file(path), rows=int(array.shape[0]), dim=int(array.shape[1]))

    outputs = dict(
        int8Docs=npy_fingerprint(root.joinpath(*paths["int8Docs"].split("/"))),
        int8Queries=npy_fingerprint(root.joinpath(*paths["int8Queries"].split("/"))),
        fp32Docs=npy_fingerprint(fp32_docs_path), fp32Queries=npy_fingerprint(fp32_queries_path),
    )
    entry = dict(
        modelId="%s-int8-onnx" % profile["modelStem"],
        modelKey=profile["key"],
        repo=profile["repo"], revision=profile["revision"], license=profile["license"],
        dim=profile["dim"], maxLen=profile["maxLen"],
        pooling=profile["pooling"], normalize=profile["normalize"],
        queryPrefix=profile["queryPrefix"], docPrefix=profile["docPrefix"],
        tokenizer=dict(className=type(tokenizer).__name__, kwargs=tokenizer_kwargs,
                       vocabSha256=D.sha256_file(root.joinpath(*D.VOCAB_RELATIVE.split("/"))),
                       tokenizerJsonSha256=D.sha256_file(root.joinpath(*D.TOKENIZER_RELATIVE.split("/"))),
                       snapshotVocabSha256=D.sha256_file(Path(local_dir) / "vocab.txt"),
                       snapshotTokenizerJsonSha256=D.sha256_file(Path(local_dir) / "tokenizer.json"),
                       frozenFixtureParity=frozen_parity),
        onnx=dict(
            route="onnx-weight-only-int8-per-channel（自转：int8 权重含嵌入表 + fp32 激活/计算）",
            fp32Path=paths["fp32"], fp32Sha256=D.sha256_file(fp32_path),
            fp32Bytes=fp32_path.stat().st_size,
            int8Path=paths["int8"], int8Sha256=D.sha256_file(int8_path),
            int8Bytes=int8_path.stat().st_size,
            sizeRatioVsFp32=round(int8_path.stat().st_size / fp32_path.stat().st_size, 4),
            weightsOnly=dict(convertedMatMuls=comparison["weightOnlyInt8PerChannel"]["convertedMatMuls"],
                             convertedEmbeddingTables=stats["convertedEmbeddings"],
                             embeddingSkipped=stats["embeddingSkipped"],
                             leftFloatMatMuls=comparison["weightOnlyInt8PerChannel"]["leftFloatMatMuls"],
                             leftFloatReasons=comparison["weightOnlyInt8PerChannel"]["leftFloatReasons"]),
        ),
        inputs=profile["inputs"], output=profile["output"],
        opset=17, dynamicAxes=["batch", "seq"],
        toolchainProbe=probes, onnxruntime=str(ort.__version__), torch=str(torch.__version__),
        outputs=outputs,
        tokenLengths=dict(docMax=token_max, queryMax=query_token_max, maxLen=profile["maxLen"],
                          truncatedDocs=0, truncatedQueries=0),
        cosineAgainstTorchFp32=dict(
            fp32Docs=report["fp32"]["docsCosineMin"], fp32Queries=report["fp32"]["queriesCosineMin"],
            int8Docs=report["int8"]["docsCosineMin"], int8Queries=report["int8"]["queriesCosineMin"],
            int8DocsMean=report["int8"]["docsCosineMean"],
        ),
        cosineAgainstStage2OfflineArm=cross,
        cosineAgainstStage2OfflineArmNotes=cross_notes,
        quantizationRouteComparison=comparison,
        gate=dict(threshold=REFERENCE_COSINE_MIN, checks=thresholds, passed=passed,
                  crossArmChecks=cross, crossArmNotes=cross_notes,
                  note="产物（权重-only int8）过门；路线 A（激活动态量化）的实测值在同级 comparison 里，未采用"),
        generatedBy="python tools/dense_build/export_bge_int8.py --model %s" % profile["key"],
    )
    # ---- 6) 清单 ----
    # 条目**一律落盘**（没过门也是结论：不落盘就得重跑整条导出才能复现"为什么没推"），但
    # **顶层镜像只在过门时更新**：check_asset/打包侧读的就是顶层那一层，它必须始终指向
    # **随包的那一档**（否则镜像与随包资产/旁车不一致，dense 门当场红）。
    manifest_path = root.joinpath(*D.MODEL_MANIFEST_RELATIVE.split("/"))
    entries = {}
    published = dict(entry)
    published_key = profile["key"]
    if manifest_path.is_file():
        previous = json.loads(manifest_path.read_text(encoding="utf-8"))
        entries.update(previous.get("modelEntries") or {})
        # 旧版清单是单档扁平结构：按 repo+revision 反查档位键并原样并入（**不删旧值**）。
        previous_key = next((key for key, spec in D.MODEL_PROFILES.items()
                             if spec["repo"] == previous.get("repo")
                             and spec["revision"] == previous.get("revision")), None)
        if previous_key and previous_key != profile["key"]:
            entries.setdefault(previous_key, previous)
        if not (passed and cross_ok) or not args.publish:
            # 顶层镜像保持原样（= 随包那一档），只把本档条目记进 entries；
            # `activeModel` 跟顶层镜像一致（顶层描述的是哪一档就写哪一档的键）。
            #
            # `--publish` 是**落地动作**的显式开关（Stage-5 WP2 第三轮加）：对拍过 ≠ 已随包。
            # 顶层镜像是"仓库当前随包那一档"的声明，改它必须与随包资产（`.vec`/旁车/`.tflite`/
            # `DenseRecallAssembly` 常量）**同一次动作**里改；只重跑导出而不换件，会把镜像
            # 提前指向还没进包的那一档（dense 门随即红）。所以默认不写顶层，换件时显式 `--publish`。
            published = {key: value for key, value in previous.items()
                         if key not in ("modelEntries", "activeModel")}
            published_key = previous_key or previous.get("activeModel")
            print("清单顶层镜像保持 %s（随包那一档；本档 passed=%s crossOk=%s publish=%s），"
                  "本档条目只进 modelEntries"
                  % (published.get("modelId"), passed, cross_ok, bool(args.publish)))
    entries[profile["key"]] = entry
    manifest = dict(published)
    manifest["activeModel"] = published_key
    manifest["modelEntries"] = entries
    manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
                             encoding="utf-8")
    print("清单落盘：%s（档位条目 %s；顶层镜像=%s）"
          % (D.MODEL_MANIFEST_RELATIVE, sorted(entries), manifest.get("activeModel")))

    if not passed:
        raise SystemExit("对拍不过（见上）——按纪律不推；本档条目已记入 %s 的 modelEntries"
                         "（gate.passed=false），随包侧未做任何改动。" % D.MODEL_MANIFEST_RELATIVE)
    if not cross_ok:
        raise SystemExit("与离线臂的交叉对拍不过——向量集口径可能漂移，按纪律不推")


if __name__ == "__main__":
    main()
