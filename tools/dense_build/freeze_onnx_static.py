# -*- coding: utf-8 -*-
"""Freeze the dynamic [batch, seq] axes of the dense int8 ONNX to a static [1, seq_len].

Why: onnx2tf's `flatbuffer_direct` backend aborts on this graph's dynamic symbolic
dimensions (`RuntimeError: reshape.cc num_input_elements != num_output_elements`), and the
`tf_converter` backend needs the Flex delegate (`FlexErf`) which plain LiteRT cannot run.
A static-shape copy removes both problems; the device then pads every query to `seq_len` and
masks the padding, which is numerically identical to the offline dynamic-length run
(padding is attention-masked -- verified on host).

Usage (repo root):
    build/tflite-venv/Scripts/python.exe tools/dense_build/freeze_onnx_static.py \
        build/dense-model/bge-small-zh-v1.5-int8.onnx build/tflite-work/static/bge-int8-512.onnx 512
    build/tflite-venv/Scripts/python.exe tools/dense_build/freeze_onnx_static.py \
        build/dense-model/bge-base-zh-v1.5-int8.onnx build/tflite-work/static/bgebase-int8-768.onnx 512 --out-dim 768

The 4th argv slot (optional) is the **output embedding dim** of the graph
(`sentence_embedding`): 512 for bge-small-zh-v1.5, 768 for bge-base-zh-v1.5.  It used to be
hardcoded 512, which silently mis-shaped the frozen graph for any 768-dim tier --
`--out-dim` / argv[4] fixes that.  When omitted it is inferred from the graph's own output
shape; **if that dim is not static the freeze refuses to run** (no 512 fallback -- 512 is the
Stage-3 tier's value and must never be a silent default when swapping tiers).
"""
from __future__ import annotations

import sys
from pathlib import Path

import onnx
from onnx import shape_inference


def output_dim_of(model: onnx.ModelProto) -> int | None:
    """图输出最后一维（静态时）——用来在没给 --out-dim 时自证，而不是硬猜 512。"""
    dims = model.graph.output[0].type.tensor_type.shape.dim
    if len(dims) == 2 and dims[1].HasField("dim_value") and dims[1].dim_value > 0:
        return int(dims[1].dim_value)
    return None


def freeze(path_in: str, path_out: str, seq_len: int, out_dim: int | None = None) -> None:
    model = onnx.load(path_in)
    # 输出维**必须**由调用方给（`convert_onnx_to_tflite.py` 传 `profile["dim"]`）或能从图里
    # 静态推断出来。**不设 512 兜底**：512 是 Stage-3 小档的值，换件后拿它当默认会把 768 维的
    # 图悄悄冻成 512 维（`--out-dim` 之前就是这样"看着有参数、实际取默认"的）。
    resolved_dim = out_dim if out_dim else output_dim_of(model)
    if not resolved_dim:
        raise SystemExit("图输出的最后一维不是静态、也没给 --out-dim —— 拒绝用 512 兜底"
                         "（那是 bge-small 档的维度，换件后会静默冻错形状）")
    for value in list(model.graph.input):
        dims = value.type.tensor_type.shape.dim
        if len(dims) == 2:
            dims[0].dim_value = 1
            dims[0].ClearField("dim_param")
            dims[1].dim_value = seq_len
            dims[1].ClearField("dim_param")
    for value in list(model.graph.output):
        dims = value.type.tensor_type.shape.dim
        for index, dim in enumerate(dims):
            if dim.HasField("dim_param"):
                dim.ClearField("dim_param")
                dim.dim_value = 1 if index == 0 else resolved_dim
    model = shape_inference.infer_shapes(model, strict_mode=False)
    Path(path_out).parent.mkdir(parents=True, exist_ok=True)
    onnx.save(model, path_out)
    print("wrote", path_out, "out_dim", resolved_dim, "inputs:", [
        (v.name, [d.dim_value if d.HasField("dim_value") else d.dim_param
                  for d in v.type.tensor_type.shape.dim]) for v in model.graph.input],
        "outputs:", [
        (v.name, [d.dim_value if d.HasField("dim_value") else d.dim_param
                  for d in v.type.tensor_type.shape.dim]) for v in model.graph.output])


if __name__ == "__main__":
    import argparse

    parser = argparse.ArgumentParser(description="把动态 [batch, seq] 冻成 [1, seq_len]")
    parser.add_argument("path_in")
    parser.add_argument("path_out")
    parser.add_argument("seq_len", nargs="?", type=int, default=512)
    parser.add_argument("--out-dim", type=int, default=None,
                        help="图输出（sentence_embedding）的维度：512=small 档 / 768=base 档；"
                             "省略时取图内静态输出维，取不到即**拒绝运行**（不拿 512 兜底）")
    parsed = parser.parse_args()
    freeze(parsed.path_in, parsed.path_out, parsed.seq_len, parsed.out_dim)
