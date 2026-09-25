# -*- coding: utf-8 -*-
"""`python -m onnx2tf` 的等价包装，只改一件事：**不折常量的 `DequantizeLinear`**。

## 它消灭的具体失败

`flatbuffer_direct`（Stage-3 小档的出货路线，也是 bge-base 唯一能跑通全部算子的路线）会把
"weight-only int8" 图里的 **int8 权重展开成 fp32 常量**，于是 bge-base 的 `.tflite` 是
**341.6 MiB**（实测，README §8.4）——远超"约 100 MB 级"的目标，端侧也没法分发。

折叠发生在这里（实测定位，2026-09-26）：

- `onnx2tf/tflite_builder/preprocess/rules/constant_fold.py` 的 `_FOLDABLE_OPS` 含
  `"DequantizeLinear"`，规则 ID `constant_fold_a5`：只要 `DequantizeLinear` 的输入全是
  常量（= MatMul 的 int8 权重 + fp32 scale），它就**在预处理阶段把 dequant 算成 fp32 常量**。
  嵌入表那条链是 `Gather → DequantizeLinear`（数据输入是动态的）折不了，所以只有三张嵌入表
  留了 int8 —— 这解释了"展开后仍然比 fp32 小"的部分。
- 折叠之后 `lower_onnx_to_ir` 看到的就是一个普通的 fp32 权重，DEQUANTIZE 算子根本不生成。

## 怎么改的（以及为什么不是改 site-packages）

在**进程内**把 `_FOLDABLE_OPS` 里的 `"DequantizeLinear"` 摘掉，再调 `onnx2tf.main()`：

- `constant_fold.py` 的三处判定（`op_type not in _FOLDABLE_OPS`）都是**模块全局的运行时查表**，
  所以运行前改这个 set 就是规则本身的行为改变，不需要改文件、不需要重装；
- 结果：int8 初始器原样留在图里，`build_dequantize_linear_op` 正常生成 `DEQUANTIZE` 算子
  （`op_builders/quantize_linear.py`），权重以 **int8 存储**进 flatbuffer；
- 数值上**与 int8 ONNX 逐位同一条算式**（`dequant(int8, scale)` 之后再算），所以这条路线的
  "tflite vs int8 ONNX" 天然是恒等，不引入第二次量化误差（对比 `tf_converter_drqt` 路线：
  那条要把 int8 折成 fp32 再让 TF 转换器**重新量化一次**）。

**边界**：只摘 `DequantizeLinear` 一项——`Cast/Reshape/Transpose/Concat/...` 的常量折叠照旧，
不改变其余任何预处理行为。这条包装只用于 `flatbuffer_direct` 路线（`tf_converter` 路线走
`onnx2tf/ops/` 的经典路径，预处理规则不参与，插进来是空操作）。

用法（与 `python -m onnx2tf` 完全相同，参数原样透传）：

    build/tflite-venv/Scripts/python.exe tools/dense_build/onnx2tf_keep_weight_int8.py \
        -i build/tflite-work/<档>/static-512-sim.onnx -o <out> -b 1 -nuo
"""
from __future__ import annotations

import sys


def main() -> int:
    import onnx2tf
    from onnx2tf.tflite_builder.preprocess.rules import constant_fold

    before = set(constant_fold._FOLDABLE_OPS)
    if "DequantizeLinear" not in before:
        raise SystemExit("constant_fold 的 _FOLDABLE_OPS 里已经没有 DequantizeLinear —— "
                         "onnx2tf 版本变了，这条包装的前提不成立，先人工核对再跑")
    constant_fold._FOLDABLE_OPS = {v for v in before if v != "DequantizeLinear"}
    print("[keep_weight_int8] _FOLDABLE_OPS: %d → %d 项（摘掉 DequantizeLinear）"
          % (len(before), len(constant_fold._FOLDABLE_OPS)))
    sys.stdout.flush()
    # `onnx2tf.onnx2tf.main()` 无参数、自己从 sys.argv 取（venv 内实测：`def main():` +
    # `args = parser.parse_args()`）⇒ 把 argv[0] 换掉、其余原样透传。
    sys.argv = ["onnx2tf"] + sys.argv[1:]
    return int(onnx2tf.main() or 0)


if __name__ == "__main__":
    raise SystemExit(main())
