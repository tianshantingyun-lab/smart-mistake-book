# -*- coding: utf-8 -*-
"""dense 档位 · int8 ONNX → 静态 [1, seq] → onnxsim → onnx2tf → `.tflite`（README §7 的转换链）。

## 为什么把这个驱动收进仓库

它原来只存在于 `build/tflite-work/run_convert.py`（`build/` 是 gitignore，历史上被清过场），
README §7 只能"用散文描述"这条链 —— 换件时没有可执行的复算路径，只能照文字重写一遍，
三条取舍随时可能漏一条。这里把链与三条取舍一起固化成断言：

1. **必须先冻成静态 `[1, seq]`**：`flatbuffer_direct` 在动态图上直接报
   `reshape.cc: num_input_elements != num_output_elements`（实测）；冻完正常。
2. **不用 `-tb tf_converter`**：那条路把 `Erf`（GELU）降到 Flex 算子（`FlexErf`），
   端侧 LiteRT 不带 Flex delegate，`Invoke` 直接失败（实测）。
3. **转换会就地改写输入 ONNX**（onnx2tf 的 onnxsim 步骤）：2026-09-24 真实事故——拿
   `build/dense-model/` 下的参考模型当 `-i`，哈希 `4d3b3135…`→`6a795693…`，与冻结 npy 的
   逐行 cosine 从 1.0 掉到 ~0.99985。所以只在 `build/` 的**副本**上转，转完**断言源件哈希未变**。

## 用法（TF venv，仓库根下）

```
build/tflite-venv/Scripts/python.exe tools/dense_build/convert_onnx_to_tflite.py            # 默认档
build/tflite-venv/Scripts/python.exe tools/dense_build/convert_onnx_to_tflite.py --install  # 并落到 assets
build/tflite-venv/Scripts/python.exe tools/dense_build/convert_onnx_to_tflite.py --model bge-small-zh-v1.5
```

产物：`build/tflite-work/<档>/…_float32.tflite`（onnx2tf 同时出一个 float16 变体，**不采用**：
端侧要的精度是 float32，且真机数是在 float32 件上测的）。`--install` 时按档位拷到
`core/data/src/main/assets/dense/` 的**固定文件名**（消费侧常量 `MODEL_ASSET_PATH` 不动）。

## 三条路线（`--route`，默认取档位的 `tfliteRoute`）

0. `flatbuffer_direct_keepint8`（**Stage-5 大档采用**）：与第 1 条**同一条 onnx2tf 命令**，
   只把入口换成 `onnx2tf_keep_weight_int8.py`（进程内把 `constant_fold_a5` 的
   `_FOLDABLE_OPS` 摘掉 `DequantizeLinear`，不改 site-packages）。
   - **它消灭的具体失败**：`flatbuffer_direct` 的常量折叠把"int8 权重 + DequantizeLinear"
     折成 fp32 常量 ⇒ bge-base 件 341.6 MiB（实测，README §8.4），远超"约 100 MB 级"目标。
   - 结果：int8 初始化器原样进 flatbuffer，`DEQUANTIZE` 算子由 `build_dequantize_linear_op`
     正常生成（实测 tiny 图：13,004 B / 纯 fp32 → 4,536 B / `DEQUANTIZE`+`BATCH_MATMUL`、
     dtype 直方图出现 int8）⇒ 体积回到 int8 量级。
   - **数值与 int8 ONNX 同一条算式**（`dequant(int8, scale)`），不引入第二次量化 —— 对比
     第 2 条路线（折成 fp32 后由 TF 转换器**重新量化一次**）。
   - 本路线转换后当场断言"产物里确实有大块 int8 张量"（`inspect_weight_dtype`，
     阈值 1e7 个元素），防止折叠又把它展开而没人发现。

1. `flatbuffer_direct`（Stage-3 小档的出货路线）：`-tb flatbuffer_direct -nuo`。
   **它的代价**：flatbuffer_direct 把 int8 权重**展开成 fp32 常量**（只有 `Gather` 的嵌入表
   因为输出是动态的而留 int8）⇒ 件体积回到 fp32 量级（实测 bge-base 341.6 MiB）。
2. `tf_converter_drqt`（Stage-5 换件路线，走 TF 转换器的 **dynamic-range 量化**）：
   `-tb tf_converter -odrqt -rtpo erf -nuo`。权重仍以 **int8 存储**（FULLY_CONNECTED 的 hybrid
   权重点）、激活 fp32 ⇒ 件体积回到 int8 量级（实测 bge-base ≈ 102 MB）。
   - 为什么能吃 int8 权重：动态范围量化是 **TF 转换器**的能力，flatbuffer_direct 那条路
     （`-odrqt` 在它上面是空操作，实测 94.85 MB → 90.46 MB，等于没量化）拿不到。
   - `-rtpo erf`：TF 的 TFLite 内置算子集里没有 ERF，不替换就会降到 `FlexErf`（**端侧 LiteRT
     不带 Flex delegate，Invoke 直接失败** —— Stage-3 实测过的那条）。`-rtpo erf` 把它换成
     一组内置算子的伪实现。**这条是硬约束，不是可调项**：转换后本脚本会断言产物里
     **没有任何 Flex/CUSTOM 算子**（`--assert-no-flex`，默认开）。
   - 输入必须是 **fp32 图**：本路线先把 int8 图的"常量权重 DequantizeLinear"折成 fp32 常量
     （`fold_constant_dequant`）——**折出来的权重仍在 int8 格点上**（它是 dequant(quant(W))），
     所以 TF 转换器再量化一次几乎无损；而 `Gather→DequantizeLinear`（嵌入表）折不了、也不该折。

投产后必须过 `tools/dense_build/check_tflite_parity.py`（宿主对拍 ≥0.999）与真机
`DenseEncoderParityInstrumentedTest`（README §7.1 的两层门），本脚本不做数值判定。
"""
from __future__ import annotations

import argparse
import json
import shutil
import subprocess
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
import dense_asset as D  # noqa: E402
import freeze_onnx_static as F  # noqa: E402


def fold_constant_dequant(path_in: str, path_out: str) -> dict:
    """把"常量 -> DequantizeLinear"折成 fp32 常量（权重的 int8 格点不变）。

    只折**数据输入是初始化器**的 DequantizeLinear：嵌入表那条链是 `Gather → DequantizeLinear`
    （数据输入是动态的），折不了也不该折 —— 它正是 flatbuffer_direct 唯一能留 int8 的地方。
    """
    import onnx
    from onnx import numpy_helper

    model = onnx.load(path_in)
    initializers = {init.name: init for init in model.graph.initializer}
    folded = 0
    replaced_outputs: dict = {}
    remaining = []
    dropped = set()
    folded_initializers = []
    for node in model.graph.node:
        if node.op_type != "DequantizeLinear" or not node.input:
            remaining.append(node)
            continue
        data_name = node.input[0]
        init = initializers.get(data_name)
        if init is None:
            remaining.append(node)
            continue
        array = numpy_helper.to_array(init)
        scale = numpy_helper.to_array(initializers[node.input[1]])
        if array.ndim != 2:
            raise SystemExit("常量 DequantizeLinear 的输入不是二维（%r）——不猜，先人工看" % (array.shape,))
        axis = next((attr.i for attr in node.attribute if attr.name == "axis"), 1)
        if axis < 0:
            axis += array.ndim
        if axis != 1 or scale.ndim != 1 or scale.shape[0] != array.shape[1]:
            raise SystemExit("常量 DequantizeLinear 的 scale 与轴不符（axis=%s %r vs %r）"
                             % (axis, array.shape, scale.shape))
        restored = array.astype(np.float32) * scale.reshape(1, -1).astype(np.float32)
        new_name = data_name + "_folded_fp32"
        folded_initializers.append(numpy_helper.from_array(restored, new_name))
        replaced_outputs[node.output[0]] = new_name
        dropped.add(data_name)
        dropped.add(node.input[1])
        folded += 1
    for node in remaining:
        for index, name in enumerate(node.input):
            if name in replaced_outputs:
                node.input[index] = replaced_outputs[name]
    del model.graph.node[:]
    model.graph.node.extend(remaining)
    keep = [init for name, init in initializers.items() if name not in dropped]
    del model.graph.initializer[:]
    model.graph.initializer.extend(keep + folded_initializers)
    for output in model.graph.output:
        if output.name in replaced_outputs:
            raise SystemExit("被折掉的张量是图输出（%s）——不猜" % output.name)
    onnx.checker.check_model(model)
    onnx.save(model, path_out)
    return dict(foldedDequant=folded,
                note="常量权重的 DequantizeLinear 折成 fp32（值仍在 int8 格点上）；"
                     "Gather→DequantizeLinear（嵌入表）保留")


def assert_no_flex(path) -> dict:
    """断言产物里没有 Flex/CUSTOM 算子（端侧 LiteRT 不带 Flex delegate）。"""
    import collections

    from ai_edge_litert.interpreter import Interpreter

    interpreter = Interpreter(model_path=str(path))
    interpreter.allocate_tensors()
    if not hasattr(interpreter, "_get_ops_details"):
        raise SystemExit("无法枚举产物算子（Interpreter 私有 API 变了）——**不当作已通过**")
    ops = collections.Counter(op["op_name"] for op in interpreter._get_ops_details())
    offenders = sorted(name for name in ops if "Flex" in name or "ustom" in name)
    if offenders:
        raise SystemExit("产物含 Flex/CUSTOM 算子 %r —— 端侧跑不了，按纪律不推" % offenders)
    inputs = [(detail["name"], detail["shape"].tolist(), np.dtype(detail["dtype"]).name)
              for detail in interpreter.get_input_details()]
    output = interpreter.get_output_details()[0]
    return dict(ops=dict(ops), opCount=sum(ops.values()),
                inputs=inputs, output=[output["name"], output["shape"].tolist(),
                                       np.dtype(output["dtype"]).name],
                int8TensorCount=sum(1 for detail in interpreter.get_tensor_details()
                                    if "int8" in np.dtype(detail["dtype"]).name))


def inspect_weight_dtype(path) -> dict:
    """数一数产物里"大块 int8 张量"到底有多少元素（= 权重是不是真的以 int8 存储）。

    只看张量 dtype 与元素个数，不看文件大小——`flatbuffer_direct_keepint8` 的卖点是
    "int8 权重不再被展开成 fp32"，展开与否必须从产物本身读出来，不能从体积反推。
    """
    from ai_edge_litert.interpreter import Interpreter

    interpreter = Interpreter(model_path=str(path))
    interpreter.allocate_tensors()
    int8_count = 0
    int8_elements = 0
    int8_tensors = []
    for detail in interpreter.get_tensor_details():
        if "int8" not in np.dtype(detail["dtype"]).name:
            continue
        shape = [int(v) for v in detail["shape"]]
        elements = 1
        for value in shape:
            elements *= value
        if elements < 4096:
            continue
        int8_count += 1
        int8_elements += elements
        int8_tensors.append([detail["name"], shape, elements])
    return dict(int8TensorCount=int8_count, int8Elements=int8_elements,
                int8Tensors=sorted(int8_tensors, key=lambda row: -row[2])[:8])


def guarded(root: Path, path: Path) -> Path:
    """只允许写 `build/` 下的路径（转换链的中间物一律留在仓库的 scratch 里）。"""
    resolved = path.resolve()
    if (root / "build") not in resolved.parents and resolved != (root / "build"):
        raise SystemExit("拒绝写 build/ 之外的路径：%s" % resolved)
    return resolved


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo-root", default=None)
    parser.add_argument("--model", default=None,
                        help="档位键（默认 %s；回退档 bge-small-zh-v1.5）" % D.DEFAULT_MODEL_KEY)
    parser.add_argument("--seq-len", type=int, default=None,
                        help="冻结后的输入长度（默认取档位 maxLen=512）")
    parser.add_argument("--route", choices=("flatbuffer_direct", "flatbuffer_direct_keepint8",
                                            "tf_converter_drqt"), default=None,
                        help="转换路线（默认取档位的 tfliteRoute；flatbuffer_direct = Stage-3 小档路线；"
                             "flatbuffer_direct_keepint8 = 同一条命令但保留 int8 权重存储）")
    parser.add_argument("--install", action="store_true",
                        help="转换后拷到档位的 assets 路径（core/data/src/main/assets/dense/…）")
    args = parser.parse_args()
    root = D.repo_root(args.repo_root)
    profile = D.model_profile(args.model)
    seq_len = args.seq_len or profile["maxLen"]
    route = args.route or profile.get("tfliteRoute") or "flatbuffer_direct"
    source = root.joinpath(*D.model_paths(profile)["int8"].split("/"))
    if not source.is_file():
        raise SystemExit("缺 int8 ONNX %s（先跑 export_bge_int8.py --model %s）" % (source, profile["key"]))
    print("档位=%s（dim=%d seq=%d 路线=%s）" % (profile["key"], profile["dim"], seq_len, route))

    work = guarded(root, root / "build" / "tflite-work" / profile["modelStem"])
    out_dir = guarded(root, work / "out")
    work.mkdir(parents=True, exist_ok=True)
    out_dir.mkdir(parents=True, exist_ok=True)

    before = D.sha256_file(source)
    copy = work / "source-copy.onnx"
    shutil.copyfile(source, copy)
    print("源件 %s sha256=%s" % (D.model_paths(profile)["int8"], before[:16]))

    if route == "tf_converter_drqt":
        # ---- 路线 2：TF 转换器的 dynamic-range 量化（权重 int8 存储 + fp32 激活） ----
        # 输入必须是 fp32 图 ⇒ 先把常量权重的 DequantizeLinear 折成 fp32（见模块 docstring）。
        folded = work / ("quant-fp32-%s.onnx" % seq_len)
        fold_stats = fold_constant_dequant(str(copy), str(folded))
        print("折常量 dequant：%d 个（%s）" % (fold_stats["foldedDequant"], folded.name))
        static = work / ("static-drqt-%s.onnx" % seq_len)
        F.freeze(str(folded), str(static), seq_len, profile["dim"])
        log_path = work / "onnx2tf-drqt.log"
        with log_path.open("w", encoding="utf-8") as handle:
            # -odrqt：输出 dynamic range quantized（权重存 int8）；-rtpo erf：ERF 无内置算子，
            # 不替换会落到 FlexErf（端侧跑不了）；-nuo：不叠 onnx2tf 自己的 onnxsim。
            process = subprocess.run([sys.executable, "-m", "onnx2tf", "-i", str(static),
                                      "-o", str(out_dir), "-b", "1", "-nuo",
                                      "-tb", "tf_converter", "-odrqt", "-rtpo", "erf"],
                                     stdout=handle, stderr=subprocess.STDOUT)
        print("onnx2tf exit=%d（log %s）" % (process.returncode, log_path))
        if process.returncode != 0:
            raise SystemExit("onnx2tf（tf_converter_drqt）失败：%s" % log_path)
        produced = sorted(out_dir.glob("*_dynamic_range_quant.tflite"))
        if len(produced) != 1:
            raise SystemExit("应恰好产出一个 *_dynamic_range_quant.tflite，实测 %r"
                             % [p.name for p in produced])
        tflite = produced[0]
        inspection = assert_no_flex(tflite)
        print("产物算子自检：无 Flex/CUSTOM；算子总数 %d；int8 张量 %d 个；输入 %s；输出 %s"
              % (inspection["opCount"], inspection["int8TensorCount"],
                 inspection["inputs"], inspection["output"]))
    else:
        static = work / ("static-%s.onnx" % seq_len)
        F.freeze(str(copy), str(static), seq_len, profile["dim"])

        import onnx
        import onnxsim

        simplified, ok = onnxsim.simplify(onnx.load(str(static)))
        if not ok:
            raise SystemExit("onnxsim 自检未通过（简化后形状/数值不一致）——不推")
        sim_path = work / ("static-%s-sim.onnx" % seq_len)
        onnx.save(simplified, str(sim_path))
        print("onnxsim ok=True；%s → %s（%d → %d B）"
              % (static.name, sim_path.name, static.stat().st_size, sim_path.stat().st_size))
        # onnxsim **不折** DequantizeLinear（实测：tiny 图上 `w_int8` 仍是 int8 初始化器，
        # DequantizeLinear 节点原样保留）⇒ 下面这条路线的 int8 权重能原样进 onnx2tf。

        log_path = work / "onnx2tf.log"
        if route == "flatbuffer_direct_keepint8":
            # 路线 3：与 flatbuffer_direct **同一条命令**，只把入口换成"不折常量 DequantizeLinear"
            # 的包装（`onnx2tf_keep_weight_int8.py`）。**它消灭的具体失败**：flatbuffer_direct 的
            # `constant_fold_a5` 规则把"int8 权重 + DequantizeLinear"折成 fp32 常量 ⇒ bge-base
            # 件 341.6 MiB（实测，README §8.4），远超目标量级。
            runner = [sys.executable,
                      str(Path(__file__).resolve().parent / "onnx2tf_keep_weight_int8.py")]
        else:
            runner = [sys.executable, "-m", "onnx2tf"]
        with log_path.open("w", encoding="utf-8") as handle:
            # -b 1：batch 固定 1；-nuo：不叠 optimize（与 Stage-3 出货件同一条命令口径）
            process = subprocess.run(runner + ["-i", str(sim_path),
                                               "-o", str(out_dir), "-b", "1", "-nuo"],
                                     stdout=handle, stderr=subprocess.STDOUT)
        print("onnx2tf exit=%d（log %s，入口 %s）" % (process.returncode, log_path, runner[1:]))
        if process.returncode != 0:
            raise SystemExit("onnx2tf 失败：%s" % log_path)
        produced = sorted(out_dir.glob("*_float32.tflite"))
        if len(produced) != 1:
            raise SystemExit("应恰好产出一个 *_float32.tflite，实测 %r" % [p.name for p in produced])
        tflite = produced[0]
        if route == "flatbuffer_direct_keepint8":
            # 这条路线的卖点就是"权重以 int8 存储"——必须从产物本身读出大块 int8 张量，
            # 否则说明折叠又发生了（体积会回到 fp32 量级）。当场断言，不靠肉眼。
            int8_info = inspect_weight_dtype(tflite)
            print("int8 存储自检：int8 张量 %d 个 / %d 个元素（%.1f MiB）"
                  % (int8_info["int8TensorCount"], int8_info["int8Elements"],
                     int8_info["int8Elements"] / 2**20))
            if int8_info["int8Elements"] < 10_000_000:
                raise SystemExit("产物里大块 int8 元素只有 %d 个（<1e7）——权重像是又被展开成 fp32，"
                                 "按纪律不推" % int8_info["int8Elements"])

    after = D.sha256_file(source)
    if after != before:
        raise SystemExit("源件被转换就地改写了！（%s → %s）按纪律不推，先恢复源件"
                         % (before[:16], after[:16]))
    print("源件哈希未变：%s UNCHANGED" % after[:16])

    sha = D.sha256_file(tflite)
    print("tflite 产物：%s（%d B，%.1f MiB，sha256=%s）" % (tflite, tflite.stat().st_size,
                                                       tflite.stat().st_size / 2**20, sha))
    print(json.dumps(dict(route=route, sizeBytes=tflite.stat().st_size, sha256=sha),
                     ensure_ascii=False))
    if args.install:
        target = root.joinpath(*profile["tfliteAsset"].split("/"))
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(tflite, target)
        installed_sha = D.sha256_file(target)
        if installed_sha != sha:
            raise SystemExit("安装后哈希不符：%s vs %s" % (installed_sha[:16], sha[:16]))
        print("已安装：%s（%d B，sha256=%s）" % (profile["tfliteAsset"], target.stat().st_size, installed_sha))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
