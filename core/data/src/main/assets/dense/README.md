# `assets/dense/` —— 端侧稠密腿的模型件（**已就位**）

端侧检索在这里找 `bge-small-zh-v1.5-int8.tflite`（常量：`DenseRecallAssembly.MODEL_ASSET_PATH`）。
LiteRT（`org.tensorflow.lite.Interpreter`）**只吃 `.tflite`**，不吃 ONNX。

| 项 | 值 |
|---|---|
| 文件 | `bge-small-zh-v1.5-int8.tflite`（**随包分发**，不入 `build/`） |
| 体积 | 62,396,488 B（≈59.5 MiB；APK 内 stored，不压缩） |
| sha256 | `015b231580dd850e5107c6991ed36fffabf2b1f6f326134a8b146a5610c672ee` |
| 来源 | `build/dense-model/bge-small-zh-v1.5-int8.onnx`（路线 B：weight-only int8-per-channel，sha256 `4d3b3135…`）经 `onnx2tf` 转出 |
| 转换链与三条取舍 | `tools/dense_build/README.md` §7（独立 venv `build/tflite-venv`；冻结静态 `[1,512]`；不能用 `-tb tf_converter`；转换会就地改写输入 ONNX） |

**模型件交付时按此验收**（判据出数前写死，不许事后放宽）：

| 项 | 要求 | 依据 |
|---|---|---|
| 输入名 | `input_ids` / `attention_mask` / `token_type_ids`（int64） | `export_bge_int8.py` 的导出签名 |
| 输入长度 | 定长 ≥ 512，或动态 `[1, seq]` | 定长 < 512 会被 `LiteRtDenseQueryEncoder.open` **拒绝**（长输入静默截短 = 与离线口径不一致） |
| 输出 | 单个 float32 输出，`numElements = 512`（**随包那一档的维度**；换档时按档取：base 档 = 768。图内已含 CLS 池化 + L2 归一） | 实测 ONNX 计算图：`Gather(0) → ReduceL2 → Clip → Expand → Div`；端侧测试的维度取自 `encoder-parity.json` 的 `dim`，不写死 |
| 数值 | 真机逐条对拍冻结参考向量（`DenseEncoderParityInstrumentedTest`，n=290）**min cosine ≥ 0.999** | 实测 min **0.99963** / median 0.99978 / p95 0.99984（全过） |

**换件纪律**：任何一次换模型件都必须**重跑** `DenseEncoderParityInstrumentedTest`（真机硬门
≥0.999）与 `GoldenRetrievalInstrumentedTest`（对拍 `build/stage3-device-expectation.json`），
并把新 sha256 更新到上表。模型缺失/哈希不符时端侧**静默回退纯词面**（设计内行为，
见 `DenseRecallReranker` 契约与 `DenseRecallAssembly`），所以"换了件但没对拍"会静默降级而
不是报错——这条纪律是那次降级的唯一防线。

Stage-3 的全部真机数、参考数、回退路径与"换大档判据"见 `docs/kb-stage3-report-2026-09-24.md`；
质量参考数（离线）在 `build/stage3-device-expectation.json`（`build/` 不入库）。

换件的可执行链条（档位参数化、词表为什么不用换、转换与宿主对拍两个工具）见
`tools/dense_build/README.md` §8；**换件怎么换、怎么复算**：

```bash
# 换档（--install 才落到本目录；不跑 --install 只出中间物）
build/tflite-venv/Scripts/python.exe tools/dense_build/convert_onnx_to_tflite.py --model <档> --install
# 落件后必须重跑的两层对拍（宿主 ≥0.999 → 真机 ≥0.999）
build/tflite-venv/Scripts/python.exe tools/dense_build/check_tflite_parity.py --model <档>
./gradlew.bat :core:data:connectedDebugAndroidTest --tests "*DenseEncoderParityInstrumentedTest*"
```

**2026-09-25 Stage-5 换件（bge-base-zh-v1.5，768 维）按写死判据
（编码器对拍 ≥0.999 且 量化模型金标融合主集 ≥0.7444）判定**：对拍 docs **0.998638** /
queries **0.999173**（口径已从第一轮的 0.9478 抬到 0.9986，仍差 0.00136，缺口来源见
`tools/dense_build/README.md` §8.3.2）、金标主集 **0.7889（71/90）** ⇒ 判据①不过 ⇒
本目录**保持 Stage-3 小档原样**（§8.3–§8.6 有全部实测数与缺口归因）。
**全阶段证据、换件清单（含字节与 sha）、延迟硬线状态（未测）、回退复核（0 个文件）与
UNVERIFIED 见 `docs/kb-stage5-report-2026-09-25.md`**。
端侧对拍 fixture 的维度**取自 `encoder-parity.json` 的 `dim`**（不写死 512/768）——
换件后重生成 fixture 即可（`python tools/dense_build/gen_device_parity_fixture.py --model <档>`）。
