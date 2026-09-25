# -*- coding: utf-8 -*-
"""dense 档位 · **权重 int8 的误差补偿**（GPTQ 口径）：不改位宽、不改体积，只改"怎么舍入"。

## 它消灭的具体失败

bge-base 的"权重-only int8 + 输入通道重标定 α=0.5"在本档实测 **docs 逐行 cosine 最小
0.998638**（门 ≥0.999，差 0.00136），而 docs 的最小值由 2–4 个字的短文本行决定。
本轮的**错误预算实测**（`build/wp2r3_ablate.py`，在真 int8 ONNX 上逐层消融，见 README §8.3.3）：

| 实验（150 条最差行 + 90 查询） | docs 逐行 cosine 最小 |
|---|---|
| 现行口径（α=0.5，单级） | 0.998638 |
| + 嵌入表两级残差（+16.6 MB） | 0.998689（只 +5.1e-5 ⇒ 缺口**不在**嵌入表） |
| 把任意**一层**的 6 个 MatMul 留 fp32（+21 MB/层） | 0.998567 … 0.998890（每层 ~1e-4，**没有单独敏感层**） |
| 把**全部** MatMul 换两级残差（+85 MB） | **0.999962** |

⇒ 缺口均匀摊在 12 层 × 6 个矩阵上，"留 fp32 / 加一级残差"要花 85 MB 才能补齐（件会到 188 MB，
超出"约 100 MB 级"的档位目标）。而误差是**累积**的 —— 逐矩阵的舍入误差一路传到 CLS。
误差补偿正好治这个：**同一张 int8 网格**上，用还没量化的权重反向抵消已舍入部分的误差，
体积一个字节都不多。

## 口径（写死，可复核）

对每个"常量权重 MatMul"（`MatMul(A, B)`，B 是初始化器且为二维）：

1. **保持 Stage-5 已定的网格不变**：输入通道重标定 `B' = B·diag(f)`（`f_k = (max_n|B[k,n]|)^(-α)`
   按几何均值归一，α 取档位值）、**每输出通道**对称 int8（`s_n = max_k|B'[k,n]|/127`）。
   GPTQ 只决定"每个权重取网格上的哪一个点"，网格本身（`f`、`s`）一字不改。
2. 用**标定集**（默认：语料里随机抽的 surface，**不含任何金标查询**）跑一遍 fp32 图，按注意力
   掩码只累加真实 token，得到每层输入的二阶矩 `G = Σ x xᵀ`；重标定后的输入是 `x' = x/f`，
   所以 `G' = diag(1/f)·G·diag(1/f)`。
3. 逐列（沿输入维）量化，并把 `err = (w - ŵ)/[H⁻¹]_jj` 沿 `[H⁻¹][j, j:]` 传播给**还没量化的列**
   （`W[:, j+1:] -= err ⊗ [H⁻¹][j, j+1:]`，`H = G' + λI`）—— 这就是 GPTQ 的误差补偿。
4. 产物是**仍落在原网格上的 fp32 权重** `Ŵ = q·s`。写回 fp32 ONNX 时写的是 `q·s/f`
   （`export_bge_int8.quantize_weights_only` 会再乘一次 `f`），于是它重新量化出来的就是同一个 q
   ⇒ **下游链路一行不用改**。

统计里给出逐矩阵的 "G 加权权重重构误差（补偿前/后）" —— 它是能补多少的**先验指标**；
最终判定只看真图对拍（`export_bge_int8.py` 的全量 28,931 行），不看这里的数。

用法（仓库根下，主环境即可：onnx + onnxruntime + numpy + transformers）：

    python tools/dense_build/quant_error_compensation.py --model bge-base-zh-v1.5
"""
from __future__ import annotations

import argparse
import json
import sys
import time
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
import dense_asset as D  # noqa: E402
import export_bge_int8 as E  # noqa: E402

CALIB_ROWS = 1024
CALIB_BATCH = 32
DAMPING = 0.01          # λ = DAMPING × mean(diag(G))（GPTQ 的标准做法；G 近奇异时保稳定）
GROUP_SIZE = 12         # 每组抓多少张中间张量（控制峰值内存与单次 run 的返回体积）


def rescale_factors(array: np.ndarray, alpha: float) -> np.ndarray:
    """与 `export_bge_int8.quantize_weights_only` **同一条公式**（口径不许两处各解释一遍）。

    `array` 是 (K, N)：输入通道 = 第 0 轴。
    """
    peak = np.abs(array).max(axis=1)
    factors = np.where(peak > 0.0, peak, 1.0) ** (-alpha)
    return (factors / np.exp(np.mean(np.log(factors)))).astype(np.float32)


def gram_weighted_error(delta: np.ndarray, gram: np.ndarray) -> float:
    """`tr(Δᵀ G Δ)`：把权重重构误差投到"真正影响输出的方向"上量。"""
    return float(np.einsum("kn,kl,ln->", delta, gram, delta))


def quantize_matrix(array: np.ndarray, factors: np.ndarray, gram: np.ndarray) -> tuple:
    """对一个 (K, N) 权重做 GPTQ，返回 (int8 矩阵, 每输出通道 scale, 统计)。"""
    scaled = (array * factors[:, None]).astype(np.float64)                 # (K, N) 重标定域
    peak = np.abs(scaled).max(axis=0)
    scale = np.where(peak > 0.0, peak / 127.0, 1.0)                        # (N,)
    baseline = np.rint(scaled / scale[None, :]).clip(-127.0, 127.0) * scale[None, :]
    before = gram_weighted_error(scaled - baseline, gram)
    work = scaled.T.copy()                                                 # (N, K)：每行一个输出通道
    quantized = np.zeros(work.shape, dtype=np.int8)
    inverse = np.linalg.inv(gram)
    for j in range(work.shape[1]):
        column = work[:, j]
        rounded = np.rint(column / scale).clip(-127.0, 127.0)
        quantized[:, j] = rounded.astype(np.int8)
        diagonal = inverse[j, j]
        if diagonal <= 0.0 or not np.isfinite(diagonal):
            continue
        error = (column - rounded * scale) / diagonal
        if j + 1 < work.shape[1]:
            work[:, j + 1:] -= np.outer(error, inverse[j, j + 1:])
    # `quantized` 是 (N, K)：**每行一个输出通道**，所以 scale 要按行广播（写成 `scale[None, :]`
    # 会沿列广播——方阵形状相同、错误会被广播悄悄吃掉，非方阵直接报形状不符）。
    restored = quantized.astype(np.float64) * scale[:, None]
    after = gram_weighted_error(scaled - restored.T, gram)
    return quantized, scale.astype(np.float32), dict(
        weightedErrorBefore=before, weightedErrorAfter=after,
        rowsWithFullScale=int(np.sum(np.abs(quantized).max(axis=1) == 127)),
        rows=int(quantized.shape[0]))


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo-root", default=None)
    parser.add_argument("--model", default=None)
    parser.add_argument("--calib-rows", type=int, default=CALIB_ROWS)
    parser.add_argument("--seed", type=int, default=20260926)
    parser.add_argument("--out", default=None, help="on-grid fp32 ONNX 的输出路径")
    parser.add_argument("--damping", type=float, default=None,
                        help="λ 与 mean(diag(G)) 的比值（默认 %s）" % DAMPING)
    parser.add_argument("--alpha", type=float, default=None,
                        help="输入通道重标定指数（默认取档位 quantCaliber；用来扫口径）")
    parser.add_argument("--report", default=None, help="统计 JSON 的输出路径")
    parser.add_argument("--threads", type=int, default=12)
    args = parser.parse_args()
    root = D.repo_root(args.repo_root)
    profile = D.model_profile(args.model)
    caliber = profile.get("quantCaliber") or {}
    alpha = float(caliber.get("rescaleAlpha", 0.0)) if args.alpha is None else float(args.alpha)
    damping = DAMPING if args.damping is None else float(args.damping)
    paths = D.model_paths(profile)
    fp32_path = root.joinpath(*paths["fp32"].split("/"))
    out_path = Path(args.out) if args.out else root / "build" / "dense-model" / (
        "%s-fp32-ongrid.onnx" % profile["modelStem"])
    print("档位=%s α=%s；fp32=%s" % (profile["key"], alpha, paths["fp32"]))

    import onnx
    import onnxruntime as ort
    from onnx import helper, numpy_helper
    from transformers import AutoTokenizer

    ort.set_default_logger_severity(3)
    model = onnx.load(str(fp32_path))
    initializers = {init.name: init for init in model.graph.initializer}
    targets = []
    for node in model.graph.node:
        if node.op_type != "MatMul" or len(node.input) != 2:
            continue
        init = initializers.get(node.input[1])
        if init is None:
            continue
        array = numpy_helper.to_array(init)
        if array.ndim != 2:
            continue
        targets.append((node.name, node.input[1], node.input[0], array))
    print("待补偿的常量权重 MatMul：%d 个" % len(targets))

    # ---- 标定集：语料里随机抽 surface（**不用金标查询**） ----
    rows, _nodes, _groups = D.atomic_layout(root)
    surfaces = [row["surface"] for row in rows]
    rng = np.random.default_rng(args.seed)
    picked = rng.choice(len(surfaces), size=min(args.calib_rows, len(surfaces)), replace=False)
    texts = [surfaces[int(index)] for index in picked]
    local_dir = E.resolve_snapshot(profile["repo"], profile["revision"])
    sbert = Path(local_dir) / "sentence_bert_config.json"
    tokenizer_kwargs = {}
    if sbert.is_file():
        tokenizer_kwargs = dict(
            do_lower_case=bool(json.loads(sbert.read_text(encoding="utf-8"))["do_lower_case"]),
            strip_accents=False)
    tokenizer = AutoTokenizer.from_pretrained(local_dir, **tokenizer_kwargs)
    batches = []
    for start in range(0, len(texts), CALIB_BATCH):
        encoded = tokenizer(texts[start:start + CALIB_BATCH], padding=True, truncation=True,
                            max_length=profile["maxLen"], return_tensors="np")
        batches.append((encoded["input_ids"].astype(np.int64),
                        encoded["attention_mask"].astype(np.int64),
                        encoded["token_type_ids"].astype(np.int64)))
    print("标定集：%d 条 surface，%d 批（不含金标查询；token 上限 %d）"
          % (len(texts), len(batches), max(int(m.sum(axis=1).max()) for _, m, _ in batches)))

    # ---- 一次性把需要的中间张量加成图输出，一个 session 反复跑；每次只取一组 ----
    patched = onnx.ModelProto()
    patched.CopyFrom(model)
    activation_names = []
    for _node_name, _weight_name, activation_name, _array in targets:
        if activation_name not in activation_names:
            activation_names.append(activation_name)
    for name in activation_names:
        patched.graph.output.append(helper.make_empty_tensor_value_info(name))
    session = ort.InferenceSession(patched.SerializeToString(), providers=["CPUExecutionProvider"])
    del patched
    groups = [activation_names[start:start + GROUP_SIZE]
              for start in range(0, len(activation_names), GROUP_SIZE)]
    by_activation = {}
    for node_name, weight_name, activation_name, array in targets:
        by_activation.setdefault(activation_name, []).append((node_name, weight_name, array))

    results = {}
    for group_index, group in enumerate(groups):
        # G 的维度 = 该 MatMul 的 **输入特征数 K**，也就是权重 (K, N) 的第 0 轴。
        grams = {name: np.zeros((by_activation[name][0][2].shape[0],) * 2, dtype=np.float64)
                 for name in group}
        started = time.time()
        for input_ids, attention_mask, token_type_ids in batches:
            outputs = session.run(group, {"input_ids": input_ids, "attention_mask": attention_mask,
                                          "token_type_ids": token_type_ids})
            mask = attention_mask.reshape(-1).astype(np.float64)
            for name, value in zip(group, outputs):
                flat = np.asarray(value, dtype=np.float64).reshape(-1, value.shape[-1])
                grams[name] += (flat * mask[:, None]).T @ flat
        print("  组 %d/%d：%d 张激活，%.1fs" % (group_index + 1, len(groups), len(group),
                                              time.time() - started))
        for name in group:
            gram = grams.pop(name)
            for node_name, weight_name, array in by_activation[name]:
                factors = rescale_factors(array, alpha)
                inverse_f = (1.0 / factors).astype(np.float64)
                weighted = (gram * inverse_f[:, None]) * inverse_f[None, :]
                weighted = weighted + np.eye(weighted.shape[0]) * (
                    damping * float(np.mean(np.diag(weighted))) + 1e-12)
                quantized, scale, stats = quantize_matrix(array, factors, weighted)
                # `quantized` 是 (N, K)，scale 按**行**（输出通道）广播 —— 与 quantize_matrix 一致。
                restored = quantized.astype(np.float32) * scale[:, None]
                results[weight_name] = dict(
                    nodeName=node_name, shape=list(array.shape),
                    weightedErrorBefore=stats["weightedErrorBefore"],
                    weightedErrorAfter=stats["weightedErrorAfter"],
                    rowsWithFullScale=stats["rowsWithFullScale"], rows=stats["rows"])
                # 写回的初始化器必须是 (K, N)（与原图同形）：`restored` 是 (N, K) 要转置再除 f。
                # `restored.T / factors[:, None]` 正是"`quantize_weights_only` 再乘一次 f"的逆
                # ⇒ 它重新量化出来的就是刚才那组 q。
                initializers[weight_name].CopyFrom(numpy_helper.from_array(
                    (restored.T / factors[:, None]).astype(np.float32), weight_name))
                print("    %-58s G-MSE %.4e → %.4e（%.2f×）"
                      % (node_name.split("/", 2)[-1], stats["weightedErrorBefore"],
                         stats["weightedErrorAfter"],
                         stats["weightedErrorBefore"] / max(stats["weightedErrorAfter"], 1e-30)))
    del session
    del model.graph.initializer[:]
    model.graph.initializer.extend(initializers.values())
    onnx.checker.check_model(model)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    onnx.save(model, str(out_path))
    ratios = [entry["weightedErrorBefore"] / max(entry["weightedErrorAfter"], 1e-30)
              for entry in results.values()]
    summary = dict(model=profile["key"], alpha=alpha, calibRows=int(len(texts)), seed=args.seed,
                   damping=damping, matrices=len(results),
                   weightedErrorRatioMin=float(min(ratios)),
                   weightedErrorRatioMedian=float(np.median(ratios)),
                   weightedErrorRatioMax=float(max(ratios)),
                   ongridPath=str(out_path.resolve().relative_to(root)).replace("\\", "/")
                   if str(out_path.resolve()).startswith(str(root)) else str(out_path.resolve()),
                   ongridBytes=out_path.stat().st_size, entries=results)
    report_path = (Path(args.report) if args.report
                   else root / "build" / "wp2r3-quant" / "error-compensation.json")
    report_path.parent.mkdir(parents=True, exist_ok=True)
    report_path.write_text(json.dumps(summary, ensure_ascii=False, indent=1), encoding="utf-8")
    print("G 加权权重重构误差改善：min %.2f× / 中位 %.2f× / max %.2f×"
          % (summary["weightedErrorRatioMin"], summary["weightedErrorRatioMedian"],
             summary["weightedErrorRatioMax"]))
    print("on-grid fp32 ONNX：%s（%.1f MB）" % (summary["ongridPath"], summary["ongridBytes"] / 1e6))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
