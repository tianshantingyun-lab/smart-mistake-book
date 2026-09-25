# `tools/dense_build/` —— 稠密腿的**档位参数化**生成链（当前随包档 = bge-small-zh-v1.5 int8）

本目录是**可复算的生成链**：模型怎么转、向量怎么打包、参考数怎么算，全在这里；产物分两类，
一类**入库跟踪**（随包分发的资产），一类落 `build/`（可重建的中间物与出数）。

档位（模型）由 `dense_asset.MODEL_PROFILES` 收成坐标集合、脚本用 `--model <键>` 选，
**默认档 = 仓库当前随包那一档**（`DEFAULT_MODEL_KEY`）；路径名**不随档变**。§8 是各档的实测与取舍，
§8.6 是写死的落地判据与当前状态。**Stage-5（换 `bge-base-zh-v1.5`）的全阶段证据、换件清单、
延迟硬线状态、回退复核与 UNVERIFIED 在 `docs/kb-stage5-report-2026-09-25.md`。**

## 1. 流水线（按顺序跑，全部在仓库根下）

```bash
# ① 词面腿：生产 v1 的分数导出（Kotlin 侧，唯一实现；出 build/production-lexical-leg.tsv）
./gradlew.bat :core:data:testDebugUnitTest --tests "*ProductionLexicalLegExportTest*" --rerun

# ② 模型：bge-small-zh-v1.5 → ONNX fp32 → int8（自转），并做对拍
python tools/dense_build/export_bge_int8.py

# ③ 向量资产：28,931 条句向量 → int8 每向量 scale → .vec + 旁车
python tools/dense_build/pack_dense_asset.py

# ④ 参考数：生产词面腿 + int8 稠密腿 → D1 形态的设备期望值
python tools/dense_build/stage3_expectation.py

# ⑤ 陈旧性门（CI 同款）
python tools/ci/run_kb_checks.py            # 新含 dense 一节
python -m unittest discover -s tools/tests -t tools -p "test_dense_asset_gate.py"
```

②③④⑤ 的每一步都接受 `--model <档位键>`（`bge-small-zh-v1.5` / `bge-base-zh-v1.5`），
**不带 = 仓库当前随包那一档**（`dense_asset.DEFAULT_MODEL_KEY`）⇒ 上面这套命令就是"复算随包件"的命令，
换档时才需要显式给 `--model`（§8.1）。

| 文件 | 角色 | 是否入库 |
|---|---|---|
| `dense_asset.py` | `.vec` 格式与包的权威定义（读写、布局、量化口径），三处共用 | ✅ |
| `export_bge_int8.py` | 模型转换 + 两条量化路线的实测 + 对拍门 | ✅ |
| `pack_dense_asset.py` | 打包 `.vec` + 旁车（含包/词表/词面腿哈希） | ✅ |
| `stage3_expectation.py` | D1 形态的参考数与设备期望值 + 复现 Stage-2 的自证 | ✅ |
| `check_asset.py` | **陈旧性门**（旁车哈希 == 当前包 + 词表 + `.vec`；ids == 当前包布局） | ✅ |
| `model-manifest.json` | 模型的坐标/哈希/两条路线的实测 cosine/对拍门结论 | ✅ |
| `vocab/bge-small-zh-v1.5-{vocab.txt,tokenizer.json}` | 词表冻结副本（端侧 tokenizer 必须与它逐条同结果） | ✅ |
| `core/data/src/main/resources/knowledge/dense/bge-small-zh-int8.vec` | 随包分发的向量资产（28,931×512 int8） | ✅ |
| `core/data/src/main/resources/knowledge/dense/bge-small-zh-int8.vec.json` | 旁车（溯源 + 哈希 + 量化实测） | ✅ |
| `build/dense-model/*.onnx` | fp32 / int8 模型件（可由 ② 从钉住的 revision 重生成） | ❌ `build/` |
| `build/production-lexical-leg.tsv` | 生产词面腿（含 sha 记进旁车） | ❌ `build/` |
| `build/stage3-device-expectation.json` | **设备期望值**（档 2 的对拍目标） | ❌ `build/` |

## 2. 工具链取舍（走 ONNX，不走 LiteRT）

本机探针（`export_bge_int8.py` 每次运行都打印，`model-manifest.json` 里也留档）：

```
{"torch": true, "transformers": true, "onnx": true, "onnxruntime": true,
 "tensorflow": false, "ai_edge_torch": false, "tflite": false}
```

`tensorflow` / `ai_edge_torch` / `tflite` **均未安装**（ModuleNotFoundError，非"没试"），
而 `onnxruntime 1.28.0` 已就位 ⇒ 按用户 2026-09-24 的授权走 **ONNX Runtime 路线**，
不新增 TF 那一条链（在 Windows/CPU 上的安装与体积代价远高于收益）。LiteRT 只是这条路的
下游候选：将来要转 `.tflite` 时，源仍是本目录产出的 ONNX 与词表。

## 3. int8 的两条路线（都实测，只把过门的那条当产物）

门是任务书写死的：**与 Python 参考实现（torch fp32）的对拍 cosine ≥ 0.999**。
实测（全量 28,931 文档 + 90 查询，逐行 cosine 取最小；数字来自 `model-manifest.json`）：

| 路线 | 权重 | 激活 | 句向量逐行 cosine 最小 | 过门 | 件大小 |
|---|---|---|---|---|---|
| A · `quantize_dynamic(per_channel=False)` | int8 | **uint8 动态量化** | 0.949946 | ✗ | 23.9 MB |
| A · `quantize_dynamic(per_channel=True)` | int8 | **uint8 动态量化** | 0.987204 | ✗ | 24.0 MB |
| **B · 权重-only int8（per-channel）+ fp32 计算（本产物）** | int8 | fp32 | **0.999010** | ✅ | **24.0 MB** |
| 参照：fp32 ONNX 图本身 | fp32 | fp32 | 0.999999881 | ✅ | 94.9 MB |

- **路线 A 不过门的原因**：它把**激活**也动态量化成 uint8（图里是 `DynamicQuantizeLinear` +
  `MatMulInteger`）。把激活压到 256 级会把句向量整体扭转（全体 28,932 行**无一**达到 0.999），
  不是少数离群行的问题。这是实测结论，不是理论推断。
- **路线 B 的做法**（`export_bge_int8.py::quantize_weights_only`）：逐节点把 `MatMul` 的常量权重
  换成「int8 + 每输出通道 scale」并在前面插一条 `DequantizeLinear`；`Gather` 的嵌入表同理
  （按列量化，`Gather` 之后插 `DequantizeLinear`）。**激活与计算保持 fp32**，所以吃到的只是
  "权重 4× 变小"，句向量几乎不动。
- 嵌入表也必须量化：不量化它，fp32 体积停在 57.2 MB（21,128×512 的嵌入表占 43 MB，比全部
  MatMul 加起来还大）；量化后 24.0 MB，与审计记录锚点（bge-small-zh int8 ONNX 23.9 MB）同量级。
- 代价说清楚：路线 B 的**端侧算力仍在 fp32**（每层多一次 dequant），它省的是包体。真机延迟
  必须由档 2 实测，本目录不预测。

### 3.1 一个环境性坑（已绕开，记下来免得再踩）

`onnxruntime.quantization.quantize_dynamic` 会在 `tempfile.gettempdir()` 下建临时目录、调
`onnx.shape_inference.infer_shapes_path` 做形状推断。本机 `TEMP` 是
`C:\Users\听云\AppData\Local\Temp`（**含非 ASCII**），而 `onnx 1.23.0` 的 C++ 文件版推断
在那条路径下**静默不产出文件**（4×4 小模型即可复现：ASCII 目录 `True`、非 ASCII 目录 `False`），
随后 `onnx.load` 报 `FileNotFoundError: model-inferred.onnx`。

处置：把 `tempfile.tempdir` 指到仓库内的 ASCII 目录 `build/tmp-dense-quant/`（只改临时目录位置，
**不改 ORT 语义**）。这条只影响路线 A 的复算；路线 B 不经过 ORT 的量化器。

## 4. 向量资产格式（`.vec` v1）

```
偏移   长度              内容
0      4                 magic = b"SMBV"
4      4                 uint32 version        = 1
8      4                 uint32 dim            = 512
12     4                 uint32 count          = 28931
16     4                 uint32 dtype          = 1（INT8_PER_VECTOR_F32_SCALE）
20     4                 uint32 idsBytesLength
24     idsBytesLength    ids 块：count × (uint32 utf8Len + utf8 bytes)，逐向量
...    count*dim         int8 矩阵，行主序
...    count*4           float32 scale，每向量一个（value = int8 × scale[r]）
```

- ids 是**逐向量**的 `knowledgeNodeId`：同一节点的向量在矩阵里**连续**（布局 = 节点顺序出
  canonicalName，紧跟其全部 alias），端侧扫连续段即可算"节点分 = 段内 cosine 的 max"。
- 量化口径：`scale[r] = max|v_r| / 127`、`q = round(v_r / scale[r])` 截到 ±127。
  每向量一个 scale 而不是全局一个——向量已 L2 归一化，全局 scale 会让短尾向量塌成 0。
- 实测还原精度：**逐行 cosine 最小 0.999747 / 中位 0.999891**（vs 量化前的 int8 模型输出）。
  端到端（资产 vs **fp32 参考实现**，即叠上模型自身 int8 误差）：**最小 0.998890 / 中位 0.999282**
  —— 逐行最小值略低于 0.999，因为两层 int8 的误差叠加；中位数与端到端指标不受影响，这个数
  写在旁车与设备期望值里，端侧可自行核对。

## 5. 参考数（D1 形态 = 当前生产形态：matched 前置）

判读对象 = 冻结金标 90 条（sha256 `7c004b76…`，两侧运行时复核）。判分口径与 Stage-1/2 逐字相同
（top-5、按科隔离、可信过滤、命中 = 前 5 名里存在 `:atomic:<expectedSlug>` 结尾的节点）。
融合 = spec §2.4 的写死参数：**每查询候选域内 min-max、α=0.5、缺腿给 0（不参与 min-max）**。

| 臂（D1 形态） | 主集 Recall@5 | 逐章最小 | MRR |
|---|---|---|---|
| `refFusedD1`（生产词面腿 + int8 稠密腿，α=0.5） | **0.7333（66/90）** | 0.3333 | 0.6035 |
| `refDenseD1`（纯 int8 稠密腿） | **0.6667（60/90）** | 0.3333 | 0.5057 |
| 参照：生产词面腿单独（v1，D1 形） | 0.6444（58/90） | 0.2222 | 0.5637 |

- **词面腿换了源**：Stage-2 的词面腿是实验台 FTS5 `-bm25`；生产跑的是 v1 的
  `COUNT(DISTINCT feature)`（`ProblemOrganizationDao.searchSubjectKnowledgeRecallCandidates`）。
  本阶段的参考数用**生产那条腿**，否则"设备期望值"对的是另一个检索器。
- **口径自证**：本目录的判分器用 Stage-2 的 FTS5 腿 + fp32 向量跑同一套判分，**逐位复现**
  Stage-2 的 `D-only-bge` 0.6444（58/90）/MRR 0.505 与 `D-fuse-a0.5-bge` 0.7444（67/90）/MRR 0.62
  ⇒ 它不是"另一套判分"。
- **int8 对端到端指标的影响**：同一条生产词面腿、把稠密腿换回 fp32，融合主集同为 0.7333（66/90）
  ⇒ 这套 int8 量化**不改变主集命中数**（MRR 0.6044 → 0.6035 的差来自并列处的名次微动）。
- 弱章（物理·相互作用 3/9、化学·铁与金属材料 3/9）两档都没有改善，与立项时"本阶段只承诺
  主集改善"的口径一致；主集从词面腿单独的 0.6444 抬到 0.7333（+8.9pp）。
- 逐题 top-5 命中与名次在 `build/stage3-device-expectation.json` 的 `perCase` 段（档 2 的对拍目标）。

## 6. 复算与门

- **陈旧性门**（`tools/ci/run_kb_checks.py` 的 `dense` 一节 + `check_asset.py`）：旁车记录的
  包哈希 / 词表哈希 / `.vec` 哈希 / 行数维度 与**当前**仓库逐个比对，另比 `ids` 与当前包的
  原子布局。正反两侧在 `tools/tests/test_dense_asset_gate.py`（正侧真资产全绿；反侧改包、改词表、
  改旁车行数、绕哈希改包、缺旁车，各自必须红）。
- 模型件不入库（`build/`）：它可由 `export_bge_int8.py` 从钉住的 revision
  `7999e1d3359715c523056ef9478215996d62a620` 重生成，哈希在 `model-manifest.json`。
  重生成后**必须重跑 ③④**（向量与参考数都跟着变）。

## 7. 端侧模型件：`.tflite` 转换链与真机实测（2026-09-24 补）

`core/data/src/main/assets/dense/bge-small-zh-v1.5-int8.tflite`（62,396,488 B，
sha256 `015b231580dd850e5107c6991ed36fffabf2b1f6f326134a8b146a5610c672ee`）由 ② 产出的
int8 ONNX（路线 B）转出，**随包分发**（不入库就没有稠密腿——模型缺失时上层静默回退纯词面）。

工具链装在**独立 venv**（`build/tflite-venv`），不动主环境的 `onnxruntime 1.28.0`——
参考数与对拍口径都依赖它：

```bash
python -m venv build/tflite-venv
build/tflite-venv/Scripts/python.exe -m pip install "onnx2tf[tensorflow]"   # tensorflow-cpu 2.21.0
build/tflite-venv/Scripts/python.exe tools/dense_build/freeze_onnx_static.py \
    build/dense-model/bge-small-zh-v1.5-int8.onnx build/tflite-work/static/bge-int8-512.onnx 512 --out-dim 512
# 之后：进程内 onnxsim → onnx2tf -tb flatbuffer_direct -nuo（driver = tools/dense_build/convert_onnx_to_tflite.py）
```

> **`--out-dim` 是必需的**（2026-09-25 实测）：`freeze_onnx_static.py` 不再拿 512 当兜底——
> 这份 int8 ONNX 的 `sentence_embedding` 最后一维**不是静态可推断**的，省略 `--out-dim` 会直接
> 非零退出（`图输出的最后一维不是静态、也没给 --out-dim —— 拒绝用 512 兜底`，实测 exit=1）。
> 谁忘了带这个参数，都会当场停下而不是悄悄把 768 维的图冻成 512 维。
> `convert_onnx_to_tflite.py` 内部按档传 `profile["dim"]`，走脚本不会被这条绊到。

三条取舍（实测，不是推断）：

1. **必须先把动态轴冻成 `[1,512]`**：`flatbuffer_direct` 在这份动态图上报
   `reshape.cc: num_input_elements != num_output_elements`；定长后正常。端侧按
   `encodePadded(text, 512)` 右 PAD + 掩码，与离线 dynamic 口径逐值等价（宿主已核）。
2. **不能用 `-tb tf_converter`**：那条路把 `Erf`（GELU）降到 Flex 算子（`FlexErf`），
   端侧 LiteRT 不带 Flex delegate，`Invoke` 直接失败。
3. **转换会就地改写输入 ONNX**（onnx2tf 的 onnxsim 步骤）：拿 `build/dense-model/` 下的
   参考模型当 `-i` 会被重写（本轮踩过：哈希 `4d3b3135…` → `6a795693…`，与冻结 npy 的
   逐行 cosine 从 1.0 掉到 ~0.99985）。所以只在 `build/tflite-work/` 的副本上转，并在转换后
   断言源件哈希未变；源件可用 `export_bge_int8.py` 的 `quantize_weights_only(fp32, out)`
   逐字节重生成（已实测：哈希回到 `4d3b3135…`、与 `int8-queries.npy` 逐行 cosine = 1.0）。

### 7.1 对拍（硬门 ≥0.999）

| 层 | 命令 / 测试 | 结果 |
|---|---|---|
| 宿主 tflite vs int8 ONNX（290 条 fixture 文本） | `build/tflite-work/check_tflite_parity.py` | min 0.99965 / median 0.99979 |
| 真机（API 34 x86_64）逐条编码 vs 冻结参考向量 | `DenseEncoderParityInstrumentedTest`（androidTest assets 里带 fixture 与 sha256 封存） | n=290 **min 0.99963 / median 0.99978 / p95 0.99984**；query 90 条 min 0.99973、surface 200 条 min 0.99963 —— 全过 |
| 单条编码耗时（XNNPACK 开，模拟器） | 同上 | p50 71ms / p95 76ms / max 91ms |
| 首次用到才付的一次性开销 | `DenseFirstUseCostInstrumentedTest` | openEncoder 34ms；firstOrder 391ms；secondOrder（稳态）93ms |

**代价**：onnx2tf 把路线 B 的 int8 权重**展开成 fp32 常量**（只有嵌入表留 int8），所以模型件
是 62.4 MB，APK 里三块合计 ≈ +98.9 MB（模型 62.4 + LiteRT 运行时 25.9（三 ABI，arm64 单片
8.75）+ 向量资产 17.3 原始 / 10.6 压缩）。要压回 ~24 MB 的下一步是**动态范围量化**
（权重 int8 + fp32 激活，TFLite converter `Optimize.DEFAULT`），但它引入**第二次**量化误差，
必须重跑 7.1 的 ≥0.999 门再决定（本轮未做，见报告"换大档/缩小档判据"）。


## 8. Stage-5 换件（2026-09-25）：档位参数化 + bge-base 的实测结论

### 8.1 怎么换：一条 `--model`，路径名一律不动

`dense_asset.MODEL_PROFILES` 把"档位"收成一个坐标集合，脚本一律用 `--model <键>` 选档；
**默认档 = 仓库当前随包那一档**（`DEFAULT_MODEL_KEY`）：

| 档位 | repo / revision（钉死） | dim | 状态 |
|---|---|---|---|
| `bge-small-zh-v1.5`（默认，随包） | `BAAI/bge-small-zh-v1.5` @ `7999e1d3359715c523056ef9478215996d62a620` | 512 | Stage-3 小档，真机闭环已过 |
| `bge-base-zh-v1.5` | `BAAI/bge-base-zh-v1.5` @ `f03589ceff5aac7111bd60cfc7d497ca17ecac65` | 768 | Stage-5 目标档：整条链可复算，int8 对拍已过（§8.3.2）；**是否随包不由本文件判**（§8.6 记数，判据见阶段任务书） |

- **路径名不随档变**：`.vec` / `.tflite` / 词表 / 消费侧常量引用的名字都不动，换件只换内容。
  只有 `build/dense-model/<档>-{fp32,int8}.onnx` 按档分名（文件名自带档位，避免"叫 small
  的文件里装着 base"）；`.npy` 输出用通用名，身份由 `model-manifest.json` 里的
  sha256/维度/行数钉住，`pack_dense_asset.py` 逐个复核后才允许打包。
- 接受 `--model` 的脚本：`export_bge_int8.py`、`pack_dense_asset.py`、`stage3_expectation.py`、
  `stage3_device_sim.py`、`gen_dense_device_fixture.py`、`gen_device_parity_fixture.py`。
  CI（`run_kb_checks.py`）与 `check_asset.py` **不认档位**：它们只比"旁车 ↔ 当前文件"，
  所以换件后旁车与哈希必须一起更新（打包含这一步）。
- 新增两个**入库**工具（此前的转换驱动只存在于 `build/tflite-work/`，而 `build/` 是
  gitignore、历史上被清过场 ⇒ 转换链无法从仓库复现）：
  - `convert_onnx_to_tflite.py`：冻静态 → onnxsim → onnx2tf；README §7 的三条取舍写成断言，
    含"源件哈希未变"（第 3 条事故的防线）；
  - `check_tflite_parity.py`：宿主对拍 tflite vs int8 ONNX（290 条文本，门 ≥0.999），
    含 id/行对齐自证。
- 清单结构：顶层仍是"当前随包那一档"的扁平镜像（`check_asset` / 打包侧读它），
  所有档位的条目留在 `modelEntries[<档>]`；**没过门的档位只进 `modelEntries`**
  （`gate.passed=false`），顶层镜像不动 —— 换件失败不会污染随包侧的读数。

### 8.2 词表：两档共享，换件不用动

bge-base-zh-v1.5 与 bge-small-zh-v1.5 的 `vocab.txt` **逐字节相同**
（sha256 `45bbac6b341c319adc98a532532882e91a9cefc0329aa57bac9ae761c27b291c`，2026-09-25 实测）
⇒ 端侧词表资产 `knowledge/dense/bge-small-zh-v1.5-vocab.txt` 与 `tools/dense_build/vocab/`
的冻结副本**两档通用**，不换文件、不重生成 tokenizer fixture。
`tokenizer.json` 两档不同，但差异只在 `normalizer.lowercase`（base=true / small=false），
而导出侧显式传 `do_lower_case=True` 把它覆盖掉：**333 条冻结 fixture（query 90 / surface 200 /
edge 18 / stage 25）逐条 token id 相同** —— `export_bge_int8.py` 在换档导出时当场断言，
不一致即停（spec §3.2）。`gen_tokenizer_fixture.py` 的词表来源因此写死为**词表那一档**的
snapshot，不再跟 `D.BGE_REVISION` 漂移。

### 8.3 bge-base 的量化保真度：第一轮归因 + 第二轮的根因、口径横扫与改进（全部实测）

> **落不落地由 Stage-5 的写死判据决定**（编码器对拍 ≥0.999 **且** 量化模型金标融合主集
> ≥0.7444），不由本文件决定。本节只记坐标、口径与实测数；判定见 §8.6。

第一轮（per-output-channel `max/127`，即小档口径）过不了对拍门：

| 对拍（门 ≥0.999） | bge-small（现役） | bge-base |
|---|---|---|
| fp32 ONNX vs torch 参考（docs） | 0.999999881 | **0.999999821** |
| fp32 ONNX vs torch 参考（queries） | 0.999999940 | **0.999999881** |
| int8 ONNX vs torch 参考（docs） | 0.999009609 | **0.947813928 ✗** |
| int8 ONNX vs torch 参考（queries） | 0.999194980 | **0.967578547 ✗** |
| 与同档离线臂（`bgebase-*.npy`）交叉对拍 | —（该档无臂） | 0.947813928（两处独立测量同值） |

根因（实测消融，90 条查询）：**错在 MatMul 权重量化**——只量化 3 张嵌入表时
min=0.999990；只量化 72 个 MatMul 权重时 min=0.967966、mean=0.981278。而两档的
**逐权重**相对误差几乎相同（matmul 中位 0.00794 vs 0.00828、最大 0.01803 vs 0.02738；
嵌入 0.00654 vs 0.00632；int8/fp32 体积比 0.253 vs 0.252）⇒ 差别在 **12 层 × 768 维的
误差累积**，不在实现：小档当年是**贴着门线过**（0.999010，余量 1e-5），这套量化路线不推广。

同一轮的其他实测（都不是推断）：

- **tflite 转换链跑通，但体积 358,236,080 B（341.6 MiB）**、sha256 `d290652d…` ——
  onnx2tf 把路线 B 的 int8 权重展开成 fp32（小档 62.4 MB 的 5.7×）；
  "体积约 100MB 级"在这条路线上不成立。宿主 tflite 对拍见 §8.4。
- **质量（离线融合，生产词面腿 + 该档向量，`stage3_expectation` 口径）**：
  bge-base **fp32** 主集 **0.7889（71/90）**、逐章最小 0.4444、MRR 0.6343；
  把向量换成**按 int8 模型输出量化的资产**（假设照发）：主集同为 0.7889（71/90）、
  MRR 0.6219 ⇒ 量化偏差没有改变主集命中，但 MRR 掉 0.012。
- **打包（为验证新代码路径跑了一遍，随后按原字节还原）**：该档 `.vec` 会是
  28,931 × 768 int8、**24,574,209 B**、sha256 `d50bca8323193bf4…`；
  还原精度（资产 vs 该档 int8 模型输出）逐行 cosine min **0.999474** / 中位 0.999779（门 0.999 ✓）；
  端到端（资产 vs 同档 torch fp32 参考）min **0.947695** / 中位 0.987518 —— 与模型自身的
  int8 偏差同量级（0.9478）⇒ "两层 int8"里**模型那一层**是主导。跑完即把随包 `.vec` / 旁车
  按备份原字节还原（sha 复核 `cdf93650…` / `01e631c0…`，`git status` 亦无改动）。
- **随包侧一律未动**（截至第三轮结束仍如此，且第三轮的 base 档产物全部落在 `build/` scratch）：
  `.vec` / 旁车 / `.tflite` / `DenseRecallAssembly.VECTOR_ASSET_SHA256` / 默认档都保持 Stage-3 小档；
  bge-base 的坐标、口径与工具链留在 `MODEL_PROFILES` 里，随时可复算
  （`export_bge_int8.py --model bge-base-zh-v1.5 --weights-ongrid <EC 产物>`）。
  **落不落地由 Stage-5 的写死判据判**（§8.6 只记数）。

### 8.3.1 第二轮的根因收敛（实测，不是推断）

`build/wp2_diag_quant.py`（scratch）在 **torch 侧**复现了路线 B 的语义（`Dequantize(W)→MatMul`
= 把权重换成 `dequant(quant(W))` 的 fp32 矩阵乘），于是可以便宜地扫口径、做消融：

1. **逐矩阵敏感度**（只量化一个矩阵、其余 fp32，90 查询）：每个矩阵单独致偏都极小
   （逐行 cosine min ≥ 0.99998，相对偏差 ≤ 0.037 在 `layer5.output.dense`）⇒ 误差**不是**某个
   敏感矩阵，而是 72 个矩阵各自的量化噪声沿 12 层累积 + 残余再放大。
2. **逐权重误差**：相对 Frobenius 误差中位 ≈ 0.008（与小档 0.0083 同量级）⇒ 不是"实现错了"，
   是**逐输出通道一个 scale 这个口径**在 12 层深度上不够。
3. **口径横扫**（同一 90 条查询，torch 模拟，括号内为逐行 cosine 最小值）：

| 口径 | 逐行 cosine 最小 | 端侧可承载？ |
|---|---|---|
| per-tensor（整矩阵一个 scale） | 0.359536 | 可，但不合格 |
| per-output-channel `max/127`（小档口径） | 0.968615 | 可 |
| per-output-channel + 分位数裁剪（0.9999 / 0.999 / 0.99） | 0.969111 / 0.828609 / 0.236366 | 可，无增益或更差 |
| per-output-channel + 逐行 MSE 最优裁剪（11 个候选点） | 0.968983 | 可，无增益 |
| **per-block（沿输入轴 128 / 64 一块一 scale）** | **0.999616 / 0.999727** | **不可**：TFLite 的 `DEQUANTIZE` 只支持 per-axis，块内 scale 存不下 |
| **输入通道重标定 α=0.25 / 0.5 / 0.75 / 1.0（本档采用 α=0.5）** | 0.994461 / **0.999443** / 0.998559 / 0.997093 | **可**：`A·W=(A·D⁻¹)·(D·W)`，存储仍是 per-output-channel int8，只多一条逐通道 fp32 `Mul` |

⇒ 选"输入通道重标定"：`d_k = (max_n |B[k,n]|)^(-0.5)`（按几何均值归一；**纯权重统计、不用标定集**），
把离群列先压平再量化，激活侧补一条 `Mul(A, 1/d)` —— 数学恒等、**不花体积**（实测 .onnx 102,989,680 B
与改口径前同量级）。机制的直接证据：72 个矩阵的"列峰展布 max/median"最大者 **146.2 → 12.1**。

### 8.3.2 本档口径的全量对拍（实测，逐轮）

`python tools/dense_build/export_bge_int8.py --model bge-base-zh-v1.5`（ORT int8 图 vs torch fp32）：

| 对拍（门 ≥0.999） | 第一轮（per-channel） | 第二轮（重标定 α=0.5） | **第三轮（+GPTQ 误差补偿 +FFN 两级残差）** |
|---|---|---|---|
| docs（28,931 行） | 0.947814 ✗ | 0.998638 ✗ | **0.999128 ✓** |
| docs 均值 | 0.987600 | 0.999411 | **0.999699** |
| queries（90 条） | 0.967579 ✗ | 0.999173 ✓ | **0.999171 ✓** |
| 与同档离线臂交叉对拍 | 0.9478 / 0.9676 | 0.998638 / 0.999173 | **0.999128 / 0.999171** |

### 8.3.3 第三轮的**错误预算**（在真 int8 ONNX 上实测，不是 torch 模拟）

> 上一轮把残留缺口归因到"嵌入表"（依据是"ONNX 图 vs 嵌入表未量化的同口径 torch 模拟"的
> 0.999292）。**这条归因被本轮实测推翻**：给嵌入表加两级残差（把它的误差几乎清零）
> 只把 docs 最小值从 0.998638 抬到 0.998689（**+5.1e-5**）。缺口在 **MatMul 权重侧**。

`build/wp2r3_ablate.py`（子集 = 上一轮最差 150 行 + 90 条查询；判定仍以全量 28,931 行为准）：

| 实验 | 体积代价 | docs 逐行 cosine 最小 |
|---|---|---|
| 重标定 α=0.5（第二轮口径） | — | 0.998638 |
| + 嵌入表两级残差 | +16.6 MB | 0.998689（+5.1e-5） |
| 把任意**一层**的 6 个 MatMul 留 fp32 | +21 MB/层 | 0.998567 … 0.998890（**没有单独敏感层**，且 L4 反而更差） |
| 把**全部** MatMul 换成两级残差 | +85 MB | 0.999962（上限确实很高，但件会到 188 MB） |
| **GPTQ 误差补偿**（`quant_error_compensation.py`，不改体积） | 0 | 0.998993 |
| **GPTQ + 12 个 `intermediate/dense` 两级残差** | +28.3 MB | **0.999409**（全量实测 0.999128） |

同一批体积里，"给 FFN 第一层加残差"比"给全部 attention 加残差"划算（同价：0.999409 vs 0.999192）。
误差是**逐层累积**的（12 层 × 6 个矩阵各自只值 ~1e-4，合起来 1.36e-3），所以"补精度"要按**层**铺开，
而**误差补偿**（同一张 int8 网格上换一种舍入，把已舍入部分的误差反向推给还没量化的权重）是**不花体积**的那一档。

### 8.3.4 采用的口径与"中途放弃过的候选"

采用（写进 `MODEL_PROFILES["bge-base-zh-v1.5"]["quantCaliber"]`）：

1. 输入通道重标定 α=0.5（第二轮定的，**不动**）；
2. **GPTQ 误差补偿**（`tools/dense_build/quant_error_compensation.py`）：标定集 = 语料随机抽的
   1,024 条 surface（**不含金标查询**），`H = G' + λI`、λ = 0.01·mean(diag G')；
   72 个矩阵的 G 加权权重重构误差 min 0.68× / **中位 1.35×** / max 20.28×
   （GPTQ 只换"取网格上哪个点"，`f`/`s` 与编码链一字不改）；
3. 12 个 `intermediate/dense` 权重加**二级同口径残差**（`T' = q1·s1 + q2·s2`，+28.3 MB）。

放弃过的候选（都是实测，记录在此以免下一轮重复走）：

| 候选 | 实测 | 为什么不用 |
|---|---|---|
| λ=0.002 / 0.0005 | G-MSE 中位 0.85× / 0.40×（**比不补偿更差**）、docs 0.998729 / 0.995147 | G 的近奇异方向被放大，补偿量漂到网格外 |
| λ=0.03 | G-MSE 中位 **1.61×**（比 λ=0.01 的 1.35× 好）、但子集 docs 0.998955 | **两个指标打架**：G-MSE 偏好 0.03、真正的门（docs 逐行余弦最小）偏好 0.01（0.998993）。两者差值 ~4e-5 已在子集噪声量级，**按门指标选了 λ=0.01**（全量复算见 §8.3.2：0.999128 过门） |
| 嵌入表两级残差 | +5.1e-5，与 GPTQ 叠加时在噪声内 | 不值 16.6 MB |
| 全部 attention 加残差 | 0.999192（同价下不如 FFN 第一层） | 同价劣于 FFN 方案 |

### 8.4 三条 `.tflite` 路线：体积/算子/端侧可跑性（实测）

`convert_onnx_to_tflite.py --route` 有三条路，差别**只在"int8 权重怎么落进 flatbuffer"**（数值口径同一条）：

| 路线 | 命令要点 | 小档实测 | base 档实测 |
|---|---|---|---|
| `flatbuffer_direct`（Stage-3 出货路线） | `-tb flatbuffer_direct -nuo` | 62.4 MB（随包那件，真机闭环过） | **341.6 MiB**：flatbuffer_direct 把 int8 权重**展开成 fp32 常量**（只有嵌入表留 int8） |
| `tf_converter_drqt`（本轮新增） | `-tb tf_converter -odrqt -rtpo erf -nuo` | **23.7 MiB**（`*_dynamic_range_quant.tflite`，权重 int8 存储、无 Flex/CUSTOM 算子，算子自检写进脚本） | **本轮转换被阻塞**：onnx2tf 的 tf_converter 路把 3-D 中间张量按 NCHW 误判后转置，`wa/backbone_module/embeddings/Add_1` 报 `Dimensions must be equal, but are 512 and 768`（`-kat input_ids attention_mask token_type_ids` 无效；原始错误在 `build/odrqt-test/*.log`）。按 int8 ONNX 的权重 payload 估算，该路线落地件应 ≈ **103 MB**（**估算，非实测**） |

### 8.4.1 第三轮新增：`flatbuffer_direct_keepint8`（base 档真正跑通的那条）

第三条路 `flatbuffer_direct_keepint8`：与 `flatbuffer_direct` **同一条 onnx2tf 命令**，只把入口换成
`onnx2tf_keep_weight_int8.py` —— 它在**进程内**把 `constant_fold_a5` 规则的 `_FOLDABLE_OPS` 摘掉
`DequantizeLinear`（不改 site-packages、不改其余任何预处理），于是"int8 权重 + DequantizeLinear"
不再被折成 fp32 常量，权重以 **int8 存储**进 flatbuffer，`DEQUANTIZE` 由
`tflite_builder/op_builders/quantize_linear.py` 正常生成。

**它消灭的具体失败**：`flatbuffer_direct` 出的 base 件是 **341.6 MiB**（int8 权重被展开成 fp32），
远超"约 100 MB 级"的档位目标。极小等价图上的探针（`build/wp2r3_int8_probe.py`）：
不带包装 13,004 B / 只有 `BATCH_MATMUL` / dtype 直方图无 int8 ⇒ 带包装 4,536 B /
`DEQUANTIZE`+`BATCH_MATMUL` / 出现 int8 ⇒ 折叠点被准确定位并关掉。
两级残差的图（`build/wp2r3_int8_probe2.py`）同样保住：`DEQUANTIZE`×2 + `ADD` + `BATCH_MATMUL`，
6,144 个 int8 元素（两级各 3,072）。

**base 档实测（本轮，最终口径 = §8.3.4）**：

| 项 | 实测 |
|---|---|
| 产物 | `build/tflite-work/bge-base-zh-v1.5/out/static-512-sim_float32.tflite` |
| 体积 | **132,375,600 B（126.2 MiB）** —— 在 ~100–130 MB 目标内 |
| sha256 | `45fe2cb7936b1f498f391f03a767e25affc6346b208fc92c29f4539f8bc7c518` |
| 权重存储 | 88 个大块 int8 张量 / 130,652,160 个元素（124.6 MiB）—— 脚本当场断言 ≥1e7，防折叠回归 |
| 算子 | 871 个，**无 Flex / 无 CUSTOM**（`DEQUANTIZE` 87 / `ADD` 187 / `GELU` 12 内置） |
| 输入 / 输出 | `[1,512] int64` ×3（ids/mask/token_type_ids）→ `sentence_embedding [1,768] float32` |
| 源件未被就地改写 | `bge-base-zh-v1.5-int8.onnx` sha `1994768d776b8684` UNCHANGED |

> **⚠ 同机同形态宿主耗时：p50 16,843 ms / p95 17,051 ms**（n=12，逐条 + PAD 512 + 掩码）。
> 小档同形态的宿主数是 **2,545 ms**（权重展开成 fp32 的随包件）/ 856 ms（`tf_converter_drqt` 的
> hybrid 内核件）。比值 6.7× 与该档/小档的 FLOPs 比（~6.75×）一致 ⇒ **这条路线的"int8 存储"是靠
> `DEQUANTIZE` 算子把权重每次推理都还原成 fp32 做到的，没有 hybrid int8 内核**，所以体积回到 int8 量级、
> 速度**没有**回到 int8 量级。按小档的宿主→真机比例（2,545 ms → 71 ms，~36×）外推，
> base 档真机单条约 **470 ms**，高于判据 ② 的 213 ms 硬线（**外推，不是真机实测**）。
> 也因此，判据 ② 里"宿主端单条编码 ≤1.5 s 量级"这条目标对 base 档**按算术不可能达成**：
> 即便按小档 `tf_converter_drqt` 的 856 ms 与 6.75× FLOPs 比，下限也在 5.8 s 量级。

⇒ "tflite 回到 int8 量级"在**小档上已实测成立**（23.7 MiB vs 展开后的 62.4 MB），
base 档在**体积**上本轮已成立（126.2 MiB），在**宿主耗时**上不成立（见上面的 ⚠）。

**同机同形态的宿主耗时**（同一批 6 条查询文本、逐条 + PAD 512 + 掩码，Python LiteRT）：

| 小档 `.tflite` | 体积 | 宿主 p50 |
|---|---|---|
| `flatbuffer_direct`（随包那件，权重已展开成 fp32） | 62.4 MB | 2,570 ms |
| `tf_converter_drqt`（权重 int8 存储） | 23.7 MiB | **856 ms（3.0× 快）** |

第一轮那份 base 档 tflite（341.6 MiB，`flatbuffer_direct`）的**全量 290 条宿主对拍没跑完**：
初版探针每行都 `resize + allocate`，对 341 MiB 的件就是每行重建张量区（>40 分钟，被 timeout 杀掉、
判定数为空；`check_tflite_parity.py` 的注释里记着这次事故），而端侧根本不是那么跑的（定长件只 allocate 一次）。
`build/tflite-parity-bgebase.log` 是那条链上唯一落盘的日志，它只打到 tflite 输出行就以 `EXIT=1` 结束。

因此本轮给的是**子集探针**（同一份工具函数、n=24/290：query 12 + surface 12）：

| 探针 | 结果 |
|---|---|
| tflite vs int8 ONNX（逐条、同执行形态） | **min 0.999710 / 中位 0.999761**（门 0.999 ✓） |
| id/行对齐自证（批式 ONNX vs 冻结 `int8-queries.npy`） | min 0.999833832（下限 0.999 ✓） |
| 输入/输出张量 | `[1,512] int64` ×3 → `sentence_embedding [1,768] float32` |

> **出处注（2026-09-25 收尾补，记录本身保留）**：这两行 n=24 的对拍数与对齐自证数，
> 在 `build/` 下**找不到对应的落盘日志**（那份日志可能只到了控制台）。可核对的是**对齐自证**那一行：
> `build/tflite-parity-bgebase.log` 里 `逐行 cosine min=0.999833832` 与之逐位相同，但它引用的
> int8 ONNX 是第一轮口径的旧件（sha `f4f39eb39baa9ca8`，现档为 `8baeae174677e5b1`）且该次运行 `EXIT=1`。
> 这条只影响"一个未随包的档的宿主对拍"，不影响任何判决（判决看 §8.6）。

结论：**转换链是保真的**（tflite 与 int8 ONNX 同结果），bge-base 的 0.9478 偏差来自
**量化**（§8.3 的消融已归因到 MatMul 权重），不是转换工具链。

> 待办（换件真要落地时）：把 `check_tflite_parity.py` 的全量 290 条跑完（给足 ~2 小时），
> 或把宿主逐条推理换成批量/降线程以缩短；端侧 `DenseEncoderParityInstrumentedTest` 仍是
> 唯一权威门（它的数据源 `encoder-parity.json` 也要用 `gen_device_parity_fixture.py` 重生成）。

### 8.5 顺带的耗时旁证（宿主代理，**不是真机数**）

同一台机器、同一套 LiteRT Python 运行时、同样"逐条 + PAD 512 + 掩码"的形态各测 12 条
（`build/latency_probe.py`，两档同一时刻同一负载下量）：

| 模型件 | 宿主 p50 |
|---|---|
| bge-small tflite（现役，62.4 MB） | 2,533 ms |
| bge-base tflite（341.6 MiB） | 19,389 ms |

比值 **7.65×**（与该档 / 小档的 FLOPs 比 ~6.75× 同量级）⇒ 若真机也按这个比例走，现役
真机 71ms 会变成 **≈350–550ms**，与判据 ②（≤213ms，现役 3 倍）差得很远。**这只是旁证**：
真机数必须在真机上量，本轮没有真机。

### 8.6 当前状态的**实测读数**（判据写死，本文件只记录数，不做判定）

这一节只放"本档今天量到多少"。**落不落地由 Stage-5 的写死判据判，不由本文件判**；
本文件的职责是把每次跑出来的坐标/哈希/数留在原地，让判定可复核。

| 项 | 第三轮实测（口径 = §8.3.4，全量 28,931 行） |
|---|---|
| 编码器对拍（int8 ONNX vs torch fp32，门 ≥0.999） | docs **0.999127567** ✓ / queries **0.999171495** ✓ |
| 金标融合主集（`build/wp2_golden_candidate.py`，判分器 import 自 `stage3_expectation.py`） | **0.7889（71/90）** / 逐章最小 0.4444 / MRR 0.6269 |
| 同档离线臂交叉对拍 | `bgebase-docs.npy` 0.999128 / `bgebase-queries.npy` 0.999171 |
| `.tflite` 体积 / 算子 | 132,375,600 B（126.2 MiB）/ 无 Flex·CUSTOM |
| `.tflite` 宿主单条编码（PAD 512，n=12） | p50 **16,843 ms**（收尾复测 **17,437 ms**，n=12，min 17,144 / max 17,930） |
| 转换保真（tflite vs int8 ONNX，290 条） | 见 `build/wp2r3-parity-final.log`（跑完即写；§7.1 那条门） |
| 该档 `.vec`（scratch，**未覆盖随包件**） | 28,931 × 768 int8、**24,574,209 B**、sha256 `4484a739e351bbd5…`；资产还原 vs int8 模型输出 min 0.999404 / 中位 0.999752 |

**随包侧（截至本轮结束）逐字节仍是 Stage-3 小档**：`bge-small-zh-v1.5-int8.tflite` 62,396,488 B /
`015b2315…`、`bge-small-zh-int8.vec` 17,167,873 B / `cdf93650…`（== `DenseRecallAssembly.kt:90`）、
旁车 `01e631c0…`。本轮的 base 档产物全部落在 `build/`（scratch），
`modelEntries["bge-base-zh-v1.5"]` 记了它的坐标/哈希/口径；清单**顶层镜像**仍是随包那一档
（`export_bge_int8.py` 的顶层镜像只在显式 `--publish` 时改写 —— 对拍过 ≠ 已随包，
镜像必须与随包资产在同一次动作里改，否则 dense 门当场红）。

全阶段证据、换件清单（字节 + sha）、未验证项见 **`docs/kb-stage5-report-2026-09-25.md`**（本文件的
base 档 scratch 读数与它的 §3/§4 同源）。**Stage-5 结局（第三轮后定论）**：判据①编码器对拍与金标
融合主集**都过**，判据②真机单条编码 p50 ≤213ms **不达**——宿主同形态同机复测 base 档 **p50 17,437 ms**
vs 小档 **2,582 ms**（比值 **6.75×** = 两档 FLOPs 比），按小档"宿主 2,545 ms → 真机 71 ms（≈36×）"
外推，base 档真机 **≈470–485 ms**（**外推，真机数不存在**）⇒ **按判据不落地**，随包侧逐字节保持小档
（回退清单 0 个文件），判据③（门重定标）未触发、旧门一字未改。落地待共享工作树恢复：
本轮 Kotlin 侧编译/测试被另一条会话在 `core/database` 的飞行改动阻断（KSP `MissingType`），
"换件后必跑的真机硬门"因此无法执行（UNVERIFIED，外部阻塞）。
