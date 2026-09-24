# -*- coding: utf-8 -*-
"""Stage-3 小档 · bge-small-zh-v1.5 → ONNX fp32 → 动态 int8（自转，可复跑）。

## 路线取舍（工具链现实性优先）

`ai-edge-torch` / `tensorflow` / `tflite` **本机均未安装**（实测 ModuleNotFoundError，见
`tools/dense_build/README.md` 的「工具链探针」一节），而 `onnxruntime 1.28.0` 已就位 ⇒
按用户 2026-09-24 的授权走 **ONNX Runtime 路线**：`torch.onnx.export` → fp32 ONNX →
`onnxruntime.quantization.quantize_dynamic` → int8 ONNX。不引入新的大依赖（TF 一条链
在 Windows/CPU 上的安装与体积代价远高于收益，且 LiteRT 只是 ONNX 的下游候选）。

## 图契约（与离线臂逐字同语义，写死不临场试）

- 输入：`input_ids` / `attention_mask` / `token_type_ids`，int64，动态 `[batch, seq]`；
- 输出：`sentence_embedding` = **取 last_hidden_state 的第 0 位（CLS）→ L2 归一化**，
  float32 `[batch, 512]`。池化与归一化**烘进图里**：端侧不用再实现一遍（少一处漂移源），
  而且"对拍"比较的就是最终句向量本身，与下游余弦口径同一个量。
- 文档侧不加任何指令；查询侧前缀 = `为这个句子生成表示以用于检索相关文章：`
  （spec §2.1，与 `stage2_encode.py` 同源）。
- Tokenizer：`AutoTokenizer` + `sentence_bert_config.json` 的 `do_lower_case` +
  `strip_accents=False`——**这三个默认值不能省**（bge 声明 do_lower_case=true，大写拉丁串
  `Na2CO3`/`CO2` 靠它才切得对；`strip_accents=False` 是 Stage-2 与 sentence-transformers
  逐条对拍得出的），本脚本从包内配置读，不凭印象定。

## 断言（对拍门：cosine ≥ 0.999）

1. **fp32 ONNX vs Python 参考（torch fp32）**：全量 28,932 文档 + 90 查询，逐行 cosine 最小
   值必须 ≥ 0.999999（导出保真，不是量化质量）；
2. **int8 ONNX vs 同一份参考**：逐行 cosine 最小值必须 ≥ 0.999（**本阶段的对拍门**）；
3. 若离线臂的 `bge-docs.npy` / `bge-queries.npy` 在位，再对一次（证明向量集口径与行序
   与 Stage-2 离线臂逐条同一，不只是"自己跟自己一致"）。

用法（仓库根下）：
```
python tools/dense_build/export_bge_int8.py
```
产物：`build/dense-model/bge-small-zh-v1.5-{fp32,int8}.onnx` + 词表冻结副本 +
`tools/dense_build/model-manifest.json`（哈希与坐标）。
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


def quantize_weights_only(fp32_path, int8_path) -> dict:
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
    for node in model.graph.node:
        if node.op_type != "MatMul" or len(node.input) != 2:
            continue
        weight_name = node.input[1]
        weight = by_name.get(weight_name)
        if weight is None:
            left_float += 1
            left_reasons.append("%s: B 不是常量初始化器（激活×激活）" % node.name)
            continue
        array = numpy_helper.to_array(weight)
        if array.ndim != 2:
            left_float += 1
            left_reasons.append("%s: B 不是二维（%r）" % (node.name, array.shape))
            continue
        # MatMul 的 B 形状是 (K, N)：K = 输入特征、N = 输出特征 ⇒ 每**输出通道**一个 scale
        peak = np.abs(array).max(axis=0)
        scale = np.where(peak > 0.0, peak / 127.0, 1.0).astype(np.float32)
        quantized = np.rint(array / scale[None, :]).clip(-127.0, 127.0).astype(np.int8)
        q_name, s_name, dq_name = weight_name + "_int8", weight_name + "_scale", weight_name + "_deq"
        new_initializers.append(numpy_helper.from_array(quantized, q_name))
        new_initializers.append(numpy_helper.from_array(scale, s_name))
        dq_before[node.name] = helper.make_node("DequantizeLinear", [q_name, s_name], [dq_name],
                                                name=weight_name + "_dequant")
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
        dq_after[node.name] = helper.make_node(
            "DequantizeLinear", [gathered_tmp, s_name], [original_output],
            name=table_name + "_dequant", axis=-1)
        replaced_weights.add(table_name)
        embed_converted += 1
    converted += embed_converted
    # 节点顺序：DequantizeLinear 必须排在用到它的 MatMul 之前（ONNX 要求拓扑序。
    # 初版这里在 `model.graph.node` 里找插入的节点——那些节点还没进图，于是"一条都没插进去"，
    # 由下面的计数断言当场抓住）。
    ordered = []
    for node in list(model.graph.node):
        dq = dq_before.get(node.name)
        if dq is not None:
            ordered.append(dq)
        ordered.append(node)
        after = dq_after.get(node.name)
        if after is not None:
            ordered.append(after)
    expected_nodes = len(model.graph.node) + converted
    if len(ordered) != expected_nodes:
        raise SystemExit("DequantizeLinear 插入数不符：期望 %d，实际 %d" % (expected_nodes, len(ordered)))
    del model.graph.node[:]
    model.graph.node.extend(ordered)
    # 初始化器：**被替换掉的 fp32 权重不再写回**（体积就是这么省下来的），其余原样 + 新增 int8/scale。
    del model.graph.initializer[:]
    model.graph.initializer.extend(
        [init for init in original_initializers if init.name not in replaced_weights] + new_initializers)
    onnx.checker.check_model(model)
    onnx.save_model(model, str(int8_path))
    return dict(converted=converted, leftFloat=left_float, leftFloatReasons=left_reasons[:8],
                insertedDequant=len(dq_before) + len(dq_after),
                convertedEmbeddings=embed_converted, embeddingSkipped=embed_skipped[:8])


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo-root", default=None)
    parser.add_argument("--threads", type=int, default=16)
    parser.add_argument("--skip-export", action="store_true", help="只做对拍（复用已有 ONNX）")
    args = parser.parse_args()
    root = D.repo_root(args.repo_root)

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
    if len(surfaces) != 28932:
        raise SystemExit("向量集应为 28,932（spec §2.2），实测 %d" % len(surfaces))

    # ---- 模型与 tokenizer（本地快照优先；缺了才去取） ----
    from huggingface_hub import snapshot_download
    local_dir = snapshot_download(D.BGE_REPO, revision=D.BGE_REVISION)
    sbert = Path(local_dir) / "sentence_bert_config.json"
    tokenizer_kwargs = {}
    if sbert.is_file():
        do_lower_case = bool(json.loads(sbert.read_text(encoding="utf-8")).get("do_lower_case", False))
        tokenizer_kwargs = dict(do_lower_case=do_lower_case, strip_accents=False)
    tokenizer = AutoTokenizer.from_pretrained(local_dir, **tokenizer_kwargs)
    print("tokenizer=%s kwargs=%s（取自包内 sentence_bert_config.json）"
          % (type(tokenizer).__name__, tokenizer_kwargs))

    doc_texts = list(surfaces)
    query_texts = [D.BGE_QUERY_PREFIX + case["query"] for case in cases]

    def tokenize(texts):
        """与 Stage-2 逐字同源：padding=True + truncation + max_length=512，只取三个 int64 输入。"""
        out = []
        for start in range(0, len(texts), BATCH_SIZE):
            batch = texts[start:start + BATCH_SIZE]
            encoded = tokenizer(batch, padding=True, truncation=True, max_length=D.BGE_MAX_LEN,
                                return_tensors="pt")
            out.append((encoded["input_ids"].to(torch.int64),
                        encoded["attention_mask"].to(torch.int64),
                        encoded["token_type_ids"].to(torch.int64)))
        return out

    doc_batches = tokenize(doc_texts)
    query_batches = tokenize(query_texts)

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

    fp32_path = root.joinpath(*D.MODEL_FP32_RELATIVE.split("/"))
    int8_path = root.joinpath(*D.MODEL_RELATIVE.split("/"))
    fp32_path.parent.mkdir(parents=True, exist_ok=True)

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

        stats = quantize_weights_only(fp32_path, int8_path)
        comparison["weightOnlyInt8PerChannel"] = dict(
            path=D.MODEL_RELATIVE, bytes=int8_path.stat().st_size,
            convertedMatMuls=stats["converted"], leftFloatMatMuls=stats["leftFloat"],
            leftFloatReasons=stats["leftFloatReasons"],
        )
        print("int8 ONNX 落盘（路线 B，产物）：%s（%.1f MB，%.1f%% of fp32；量化了 %d 个带权 MatMul，"
              "剩下 %d 个是激活×激活，保持 fp32）" % (
                  int8_path, int8_path.stat().st_size / 1e6,
                  100.0 * int8_path.stat().st_size / fp32_path.stat().st_size,
                  stats["converted"], stats["leftFloat"]))

    # ---- 3) 对拍 ----
    def cosine_min(left, right):
        left = left / np.linalg.norm(left, axis=1, keepdims=True)
        right = right / np.linalg.norm(right, axis=1, keepdims=True)
        return float((left * right).sum(axis=1).min())

    sessions = {
        "fp32": ort.InferenceSession(str(fp32_path), providers=["CPUExecutionProvider"]),
        "int8": ort.InferenceSession(str(int8_path), providers=["CPUExecutionProvider"]),
    }
    # 路线 A 的两个变体也各测一遍（它们是"没有过门"的证据，写进清单；不作为产物）
    for label, entry in sorted(comparison.items()):
        variant = root / entry["path"]
        if variant.is_file():
            sessions["routeA:%s" % label] = ort.InferenceSession(str(variant), providers=["CPUExecutionProvider"])
    report = {}
    for key, session in sessions.items():
        docs = onnx_encode(session, doc_batches, "%s-docs" % key)
        queries = onnx_encode(session, query_batches, "%s-queries" % key)
        report[key] = dict(
            docsCosineMin=cosine_min(docs, reference_docs),
            queriesCosineMin=cosine_min(queries, reference_queries),
            docsCosineMean=float(np.mean((docs / np.linalg.norm(docs, axis=1, keepdims=True)
                                          * reference_docs
                                          / np.linalg.norm(reference_docs, axis=1, keepdims=True)).sum(axis=1))),
        )
        if key == "int8":
            np.save(fp32_path.parent / "int8-docs.npy", docs)
            np.save(fp32_path.parent / "int8-queries.npy", queries)
        print("[%s] vs 参考实现：docs cosine min=%.9f mean=%.6f ; queries cosine min=%.9f"
              % (key, report[key]["docsCosineMin"], report[key]["docsCosineMean"],
                 report[key]["queriesCosineMin"]))

    # ---- 4) 与离线臂 npy 的交叉对拍（存在则做） ----
    # 比的是**产物本身**（int8 句向量）与 Stage-2 离线臂的 fp32 向量：这一条同时证明
    # "行序/文本构造与离线臂逐条同一"（布局或分词一旦不同，逐行 cosine 会立刻塌）与
    # "量化后仍与离线臂同向量集"。不是自己跟自己比。
    offline = root / "build" / "stage2-dense-work" / "vectors"
    shipped_docs = np.load(fp32_path.parent / "int8-docs.npy")
    shipped_queries = np.load(fp32_path.parent / "int8-queries.npy")
    cross = {}
    for name, mine in (("bge-docs.npy", shipped_docs), ("bge-queries.npy", shipped_queries)):
        path = offline / name
        if not path.is_file():
            cross[name] = None
            print("[交叉] 离线臂 %s 不在位——跳过（**不当作已通过**）" % name)
            continue
        other = np.load(path)
        if other.shape != mine.shape:
            raise SystemExit("离线臂 %s 形状不符：%s vs %s（向量集口径已漂移）" % (name, other.shape, mine.shape))
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
    if not all(thresholds.values()):
        raise SystemExit("对拍不过（见上）——按纪律不推")
    if not cross_ok:
        raise SystemExit("与离线臂的交叉对拍不过——向量集口径可能漂移，按纪律不推")

    # ---- 5) 词表冻结 + 清单 ----
    vocab_dir = root.joinpath(*D.VOCAB_RELATIVE.split("/")).parent
    vocab_dir.mkdir(parents=True, exist_ok=True)
    for source, target_rel in ((Path(local_dir) / "vocab.txt", D.VOCAB_RELATIVE),
                               (Path(local_dir) / "tokenizer.json", D.TOKENIZER_RELATIVE)):
        target = root.joinpath(*target_rel.split("/"))
        target.write_bytes(source.read_bytes())
        print("词表冻结：%s ← %s（sha256=%s）" % (target_rel, source.name, D.sha256_file(target)[:16]))

    # 把路线 A 的实测结果并进 comparison（它是"没采用那条路"的证据）
    for label in list(comparison):
        measured = report.get("routeA:%s" % label)
        if measured:
            comparison[label].update(docsCosineMin=measured["docsCosineMin"],
                                     docsCosineMean=measured["docsCosineMean"],
                                     queriesCosineMin=measured["queriesCosineMin"],
                                     passedGate=bool(measured["docsCosineMin"] >= REFERENCE_COSINE_MIN
                                                     and measured["queriesCosineMin"] >= REFERENCE_COSINE_MIN))
    manifest = dict(
        modelId="bge-small-zh-v1.5-int8-onnx",
        repo=D.BGE_REPO, revision=D.BGE_REVISION, license=BGE_LICENSE_NOTE,
        dim=D.BGE_DIM, maxLen=D.BGE_MAX_LEN,
        pooling="cls", normalize="l2", queryPrefix=D.BGE_QUERY_PREFIX, docPrefix=None,
        tokenizer=dict(className=type(tokenizer).__name__, kwargs=tokenizer_kwargs,
                       vocabSha256=D.sha256_file(root.joinpath(*D.VOCAB_RELATIVE.split("/"))),
                       tokenizerJsonSha256=D.sha256_file(root.joinpath(*D.TOKENIZER_RELATIVE.split("/")))),
        onnx=dict(
            route="onnx-weight-only-int8-per-channel（自转：int8 权重含嵌入表 + fp32 激活/计算）",
            fp32Path=D.MODEL_FP32_RELATIVE, fp32Sha256=D.sha256_file(fp32_path),
            fp32Bytes=fp32_path.stat().st_size,
            int8Path=D.MODEL_RELATIVE, int8Sha256=D.sha256_file(int8_path),
            int8Bytes=int8_path.stat().st_size,
            sizeRatioVsFp32=round(int8_path.stat().st_size / fp32_path.stat().st_size, 4),
            weightsOnly=dict(convertedMatMuls=comparison["weightOnlyInt8PerChannel"]["convertedMatMuls"],
                             convertedEmbeddingTables=stats["convertedEmbeddings"],
                             embeddingSkipped=stats["embeddingSkipped"],
                             leftFloatMatMuls=comparison["weightOnlyInt8PerChannel"]["leftFloatMatMuls"],
                             leftFloatReasons=comparison["weightOnlyInt8PerChannel"]["leftFloatReasons"]),
        ),
        inputs=["input_ids", "attention_mask", "token_type_ids"],
        output="sentence_embedding",
        opset=17, dynamicAxes=["batch", "seq"],
        toolchainProbe=probes,
        onnxruntime=str(ort.__version__),
        torch=str(torch.__version__),
        cosineAgainstTorchFp32=dict(
            fp32Docs=report["fp32"]["docsCosineMin"], fp32Queries=report["fp32"]["queriesCosineMin"],
            int8Docs=report["int8"]["docsCosineMin"], int8Queries=report["int8"]["queriesCosineMin"],
            int8DocsMean=report["int8"]["docsCosineMean"],
        ),
        cosineAgainstStage2OfflineArm=cross,
        quantizationRouteComparison=comparison,
        gate=dict(threshold=REFERENCE_COSINE_MIN, checks=thresholds, passed=bool(all(thresholds.values())),
                  note="产物（权重-only int8）过门；路线 A（激活动态量化）的实测值在同级 comparison 里，未采用"),
        generatedBy="python tools/dense_build/export_bge_int8.py",
    )
    manifest_path = root.joinpath(*D.MODEL_MANIFEST_RELATIVE.split("/"))
    manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
                             encoding="utf-8")
    print("清单落盘：%s" % D.MODEL_MANIFEST_RELATIVE)


BGE_LICENSE_NOTE = "mit（基座许可证，spec §2.1；可再分发）"

if __name__ == "__main__":
    main()
