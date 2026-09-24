# -*- coding: utf-8 -*-
"""Freeze the dynamic [batch, seq] axes of the Stage-3 int8 ONNX to a static [1, 512].

Why: onnx2tf's `flatbuffer_direct` backend aborts on this graph's dynamic symbolic
dimensions (`RuntimeError: reshape.cc num_input_elements != num_output_elements`), and the
`tf_converter` backend needs the Flex delegate (`FlexErf`) which plain LiteRT cannot run.
A static-shape copy removes both problems; the device then pads every query to 512 and
masks the padding, which is numerically identical to the offline dynamic-length run
(padding is attention-masked -- verified on host).

Usage (repo root):
    build/tflite-venv/Scripts/python.exe tools/dense_build/freeze_onnx_static.py \
        build/dense-model/bge-small-zh-v1.5-int8.onnx build/tflite-work/static/bge-int8-512.onnx 512
"""
from __future__ import annotations

import sys
from pathlib import Path

import onnx
from onnx import shape_inference


def freeze(path_in: str, path_out: str, seq_len: int) -> None:
    model = onnx.load(path_in)
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
                dim.dim_value = 1 if index == 0 else 512
    model = shape_inference.infer_shapes(model, strict_mode=False)
    Path(path_out).parent.mkdir(parents=True, exist_ok=True)
    onnx.save(model, path_out)
    print("wrote", path_out, "inputs:", [
        (v.name, [d.dim_value if d.HasField("dim_value") else d.dim_param
                  for d in v.type.tensor_type.shape.dim]) for v in model.graph.input])


if __name__ == "__main__":
    freeze(sys.argv[1], sys.argv[2], int(sys.argv[3]) if len(sys.argv) > 3 else 512)
