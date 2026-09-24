# `tools/dense_build/` —— Stage-3 小档（bge-small-zh-v1.5 int8）资产与参考数的生成链

本目录是**可复算的生成链**：模型怎么转、向量怎么打包、参考数怎么算，全在这里；产物分两类，
一类**入库跟踪**（随包分发的资产），一类落 `build/`（可重建的中间物与出数）。

## 1. 流水线（按顺序跑，全部在仓库根下）

```bash
# ① 词面腿：生产 v1 的分数导出（Kotlin 侧，唯一实现；出 build/production-lexical-leg.tsv）
./gradlew.bat :core:data:testDebugUnitTest --tests "*ProductionLexicalLegExportTest*" --rerun

# ② 模型：bge-small-zh-v1.5 → ONNX fp32 → int8（自转），并做对拍
python tools/dense_build/export_bge_int8.py

# ③ 向量资产：28,932 条句向量 → int8 每向量 scale → .vec + 旁车
python tools/dense_build/pack_dense_asset.py

# ④ 参考数：生产词面腿 + int8 稠密腿 → D1 形态的设备期望值
python tools/dense_build/stage3_expectation.py

# ⑤ 陈旧性门（CI 同款）
python tools/ci/run_kb_checks.py            # 新含 dense 一节
python -m unittest discover -s tools/tests -t tools -p "test_dense_asset_gate.py"
```

| 文件 | 角色 | 是否入库 |
|---|---|---|
| `dense_asset.py` | `.vec` 格式与包的权威定义（读写、布局、量化口径），三处共用 | ✅ |
| `export_bge_int8.py` | 模型转换 + 两条量化路线的实测 + 对拍门 | ✅ |
| `pack_dense_asset.py` | 打包 `.vec` + 旁车（含包/词表/词面腿哈希） | ✅ |
| `stage3_expectation.py` | D1 形态的参考数与设备期望值 + 复现 Stage-2 的自证 | ✅ |
| `check_asset.py` | **陈旧性门**（旁车哈希 == 当前包 + 词表 + `.vec`；ids == 当前包布局） | ✅ |
| `model-manifest.json` | 模型的坐标/哈希/两条路线的实测 cosine/对拍门结论 | ✅ |
| `vocab/bge-small-zh-v1.5-{vocab.txt,tokenizer.json}` | 词表冻结副本（端侧 tokenizer 必须与它逐条同结果） | ✅ |
| `core/data/src/main/resources/knowledge/dense/bge-small-zh-int8.vec` | 随包分发的向量资产（28,932×512 int8） | ✅ |
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
实测（全量 28,932 文档 + 90 查询，逐行 cosine 取最小；数字来自 `model-manifest.json`）：

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
12     4                 uint32 count          = 28932
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
    build/dense-model/bge-small-zh-v1.5-int8.onnx build/tflite-work/static/bge-int8-512.onnx 512
# 之后：进程内 onnxsim → onnx2tf -tb flatbuffer_direct -nuo（driver 见 build/tflite-work/run_convert.py）
```

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

