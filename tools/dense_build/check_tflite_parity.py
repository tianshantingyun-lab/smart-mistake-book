# -*- coding: utf-8 -*-
"""dense 档位 · `.tflite` 与 int8 ONNX 的**宿主对拍**（README §7.1 的第一层，硬门 ≥0.999）。

## 这一层管什么

端侧只跑 `.tflite`（LiteRT），离线出数跑的是 int8 ONNX。两者**必须同结果**，否则：
- 离线主集数在端侧根本复现不了（不是"略差"）。
- 端侧模型缺失/损坏时是**静默回退纯词面**（`DenseRecallAssembly` 的设计），所以"tflite 不对"
  在设备上表现为"分数变差"而不是报错 —— 没有宿主对拍，就得靠 4 分钟的真机 instrumentation
  去发现，代价高且容易漏跑。

所以这一层把判定拉回宿主：90 条查询 + 200 条 surface（冻结 tokenizer fixture 的文本，
ids 也来自它 —— 与端侧 `DenseEncoderParityInstrumentedTest` 同一批文本、同一份 ids）。

## 判定与自证

1. **对齐自证**：fixture 的 ids → int8 ONNX 的输出，与冻结 `build/dense-model/int8-queries.npy`
   逐行 cosine ≈ 1（证明 ids 与参考向量行对齐，不是"文本陪跑"）；
2. **对拍门**：tflite 输出 vs 同一批 ids 的 int8 ONNX 输出，逐条 cosine **min ≥ 0.999**
   （Stage-3 实测 0.99965；这条线是 README §7.1 写死的那条）。

用法（TF venv：需要 `ai_edge_litert` + `onnxruntime`；仓库根下）：
```
build/tflite-venv/Scripts/python.exe tools/dense_build/check_tflite_parity.py
build/tflite-venv/Scripts/python.exe tools/dense_build/check_tflite_parity.py --tflite build/tflite-work/x/out/y_float32.tflite
```
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
import dense_asset as D  # noqa: E402

TOKENIZER_FIXTURE = "core/data/src/test/resources/dense/tokenizer-parity-cases.txt"
FLOOR = 0.999
# 对齐自证的下限：与整条链一致的 **0.999**（任务书对"同一支模型"的那条线；设备侧
# `DenseEncoderParityInstrumentedTest.MIN_COSINE` 也是这个值）。
# 为什么不设 0.9999 那种更漂亮的数：**跨进程**重算 int8 图会带进 ~1e-4 的数值抖动
# （实测 bge-base int8：同进程两次会话逐行 1.0；另起进程重算 vs 冻结 npy 最差行 0.99983 /
# 中位 0.99989 —— ORT CPU 内核的规约顺序随进程/线程划分变）。而这条自检要抓的是"id/行错位"：
# 90 条查询参考向量的最大**非对角** cosine 只有 0.80，错位会直接掉到 0.8 量级。
# 0.999 把这两件事分得干干净净，同时不为数值抖动假红。
ALIGNMENT_FLOOR = 0.999
BACKSLASH = chr(92)
ESCAPES = {BACKSLASH: BACKSLASH, "t": "\t", "n": "\n", "r": "\r"}


def unescape(text: str) -> str:
    out = []
    index = 0
    while index < len(text):
        if text[index] == BACKSLASH:
            out.append(ESCAPES[text[index + 1]])
            index += 2
        else:
            out.append(text[index])
            index += 1
    return "".join(out)


def load_cases(root, kinds=("query", "surface")):
    rows = []
    for line in root.joinpath(*TOKENIZER_FIXTURE.split("/")).read_text(encoding="utf-8").splitlines():
        if not line or line.startswith("#"):
            continue
        fields = line.split("\t")
        if fields[0] not in kinds:
            continue
        rows.append((fields[1], fields[0], unescape(fields[3]), [int(token) for token in fields[4].split(" ")]))
    return rows


def cosine(left, right) -> float:
    left = np.asarray(left, dtype=np.float64)
    right = np.asarray(right, dtype=np.float64)
    return float(left @ right / (np.linalg.norm(left) * np.linalg.norm(right) + 1e-12))


def onnx_vectors(onnx_path, cases, max_len, batch=64, single_row=False):
    """ORT 参考输出。

    `single_row=True`：逐条推理、右 PAD 到 `max_len`、掩码——**与 tflite 侧同一执行形态**，
    用来把"转换保真"单独量出来。为什么必须同形态：实测（bge-base int8）同一份模型
    **batch=1 与 batch=64** 的输出 cosine 是 0.99983 而不是 1.0（batch 变了 GEMM 的规约
    形状，float32 累加顺序随之变），跨形态比会把这点数值差算到"转换"头上。
    `batch>1`（默认）与导出链的批式口径一致，用来做 id/行对齐自证与"设备样"参考。
    """
    import onnxruntime as ort

    session = ort.InferenceSession(str(onnx_path), providers=["CPUExecutionProvider"])
    out = []
    step = 1 if single_row else batch
    for start in range(0, len(cases), step):
        chunk = [case[3][:max_len] for case in cases[start:start + step]]
        width = max_len if single_row else max(len(ids) for ids in chunk)
        input_ids = np.zeros((len(chunk), width), dtype=np.int64)
        mask = np.zeros((len(chunk), width), dtype=np.int64)
        for row, ids in enumerate(chunk):
            input_ids[row, :len(ids)] = ids
            mask[row, :len(ids)] = 1
        result = session.run(None, {
            "input_ids": input_ids, "attention_mask": mask,
            "token_type_ids": np.zeros_like(input_ids),
        })[0]
        out.append(np.asarray(result, dtype=np.float32))
    return np.concatenate(out, axis=0)


def tflite_vectors(tflite_path, cases, max_len):
    from ai_edge_litert.interpreter import Interpreter

    interpreter = Interpreter(model_path=str(tflite_path))
    inputs = interpreter.get_input_details()
    output = interpreter.get_output_details()[0]
    print("tflite 输入：%s" % [(d["name"], d["shape"].tolist(),
                              np.dtype(d["dtype"]).name) for d in inputs])
    print("tflite 输出：%s %s %s" % (output["name"], output["shape"].tolist(),
                                   np.dtype(output["dtype"]).name))
    signature = inputs[0].get("shape_signature")
    fixed = int(signature[1]) if signature is not None and len(signature) == 2 and signature[1] > 0 else None
    # 固定形状模型**只 allocate 一次**（与端侧 `LiteRtDenseQueryEncoder` 同路径：它只对动态
    # 形状模型 resize+allocate）。初版这里每行都 resize+allocate —— 对 341MiB 的模型就是每行
    # 重建张量区，跑 290 行 >40 分钟（被 timeout 杀掉、判定数为空），而端侧根本不是这么跑的。
    if fixed is not None:
        interpreter.allocate_tensors()
    width_seen = fixed
    results = []
    for case in cases:
        ids = case[3][:max_len]
        width = fixed or len(ids)
        input_ids = np.zeros((1, width), dtype=np.int64)
        mask = np.zeros((1, width), dtype=np.int64)
        input_ids[0, :len(ids)] = ids
        mask[0, :len(ids)] = 1
        if fixed is None and width != width_seen:
            for detail in inputs:
                interpreter.resize_tensor_input(detail["index"], input_ids.shape, strict=False)
            interpreter.allocate_tensors()
            width_seen = width
        interpreter.set_tensor(inputs[0]["index"], input_ids)
        interpreter.set_tensor(inputs[1]["index"], mask)
        interpreter.set_tensor(inputs[2]["index"], np.zeros_like(input_ids))
        interpreter.invoke()
        results.append(interpreter.get_tensor(output["index"]).reshape(-1).astype(np.float32))
    return np.asarray(results, dtype=np.float32)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo-root", default=None)
    parser.add_argument("--model", default=None,
                        help="档位键（默认 %s；回退档 bge-small-zh-v1.5）" % D.DEFAULT_MODEL_KEY)
    parser.add_argument("--tflite", default=None, help="默认取档位的 assets 件")
    args = parser.parse_args()
    root = D.repo_root(args.repo_root)
    profile = D.model_profile(args.model)
    onnx_path = root.joinpath(*D.model_paths(profile)["int8"].split("/"))
    tflite_path = Path(args.tflite) if args.tflite else root.joinpath(*profile["tfliteAsset"].split("/"))
    print("档位=%s dim=%d" % (profile["key"], profile["dim"]))
    print("tflite=%s（%d B，sha256=%s）" % (tflite_path, tflite_path.stat().st_size,
                                           D.sha256_file(tflite_path)[:16]))
    print("onnx  =%s（sha256=%s）" % (onnx_path, D.sha256_file(onnx_path)[:16]))

    cases = load_cases(root)
    if len(cases) != 290:
        raise SystemExit("fixture 文本应为 290 条（query 90 + surface 200），实测 %d" % len(cases))
    query_count = sum(1 for case in cases if case[1] == "query")

    # 参考（批式，与导出链**同一批划分**）：只用来做 id/行对齐自证。
    # 必须只喂查询行：导出链的查询批是"64 + 26"两批，若把 surface 混进同一批，PAD 宽度按
    # 该批最长文本走，batch 形态一变输出就差 ~1e-4（实测），自证会假红。
    query_cases = [case for case in cases if case[1] == "query"]
    reference_batched = onnx_vectors(onnx_path, query_cases, profile["maxLen"])
    if reference_batched.shape[1] != profile["dim"]:
        raise SystemExit("ONNX 输出维度 %d 与档位 %d 不符" % (reference_batched.shape[1], profile["dim"]))
    frozen = np.load(root / "build" / "dense-model" / "int8-queries.npy").astype(np.float32)
    if frozen.shape[0] != query_count:
        raise SystemExit("int8-queries.npy 行数 %d 与 fixture 查询数 %d 不符" % (frozen.shape[0], query_count))
    aligned = min(cosine(reference_batched[index], frozen[index]) for index in range(query_count))
    print("对齐自证（批式 vs 冻结 npy）：逐行 cosine min=%.9f（下限 %.4f）" % (aligned, ALIGNMENT_FLOOR))
    if aligned < ALIGNMENT_FLOOR:
        raise SystemExit("对齐自证不过：ids 与冻结查询向量的行没对齐，后面的对拍没有意义")

    # 同一执行形态（逐条、PAD 到 maxLen）下的 ONNX 参考 = 与 tflite 侧同口径
    reference = onnx_vectors(onnx_path, cases, profile["maxLen"], single_row=True)
    measured = tflite_vectors(tflite_path, cases, profile["maxLen"])
    diff = np.array([cosine(measured[index], reference[index]) for index in range(len(cases))])
    by_kind = {}
    cursor = 0
    for kind in ("query", "surface"):
        count = sum(1 for case in cases if case[1] == kind)
        chunk = diff[cursor:cursor + count]
        cursor += count
        by_kind[kind] = dict(n=count, min=float(chunk.min()), median=float(np.median(chunk)))
        print("%-8s n=%d min=%.6f median=%.6f" % (kind, count, chunk.min(), np.median(chunk)))
    print("ALL      n=%d min=%.6f median=%.6f p95=%.6f（门 %.3f）"
          % (len(diff), diff.min(), np.median(diff), float(np.percentile(diff, 95)), FLOOR))
    # 顺带量一条"设备样"的数（逐条 tflite vs 冻结批式 npy，只对查询）：真机上端侧对的就是这份
    # npy（`DenseEncoderParityInstrumentedTest`），batch 形态不同会带进 ~1e-4 的数值差。
    device_like = min(cosine(measured[index], frozen[index]) for index in range(query_count))
    print("设备样（逐条 tflite vs 冻结 npy 查询）：min=%.6f" % device_like)
    if diff.min() < FLOOR:
        raise SystemExit("宿主 tflite 对拍不过：min %.6f < %.3f（按纪律不推）" % (diff.min(), FLOOR))
    print("宿主对拍过：tflite 与 int8 ONNX 同结果（这份 tflite 才允许上设备）")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
