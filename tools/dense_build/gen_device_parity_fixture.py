# -*- coding: utf-8 -*-
"""Stage-3 端侧 · **编码对拍 fixture**（仪表化测试的数据源）。

## 这个 fixture 回答什么

任务书的硬门：*同一批文本（90 条金标题面 + 抽样节点文本）在设备上编码，与 Python
参考向量比 cosine ≥ 0.999*。所以设备侧需要三样东西：文本、参考向量、以及"这两样
确实成对"的封存。

| 产物 | 内容 |
|---|---|
| `core/data/src/androidTest/assets/dense/encoder-parity-cases.tsv` | 逐行 `id \t kind \t text(转义) \t referenceRow` |
| `core/data/src/androidTest/assets/dense/encoder-parity-vectors.f32` | 小端 float32，N×dim（dim 按档：512/768），行序 = 上面 TSV 的行序 |
| `core/data/src/androidTest/assets/dense/encoder-parity.json` | 溯源（两侧哈希）、逐行对齐自检数、判据（≥0.999） |

## 三方来源（都是冻结件，不新造数）

1. **文本** = `core/data/src/test/resources/dense/tokenizer-parity-cases.txt` 的
   `query`（90 条，含 BGE 查询前缀）与 `surface`（200 条节点名/别名，无前缀）两段——
   这份 fixture 已由 `gen_tokenizer_fixture.py` 从冻结金标与向量集文本按种子抽出；
2. **参考向量** = `build/dense-model/int8-queries.npy`（行序 = 金标序）与
   `build/dense-model/int8-docs.npy`（行序 = 向量集 28,931 条序，surface 行的 `row=`
   即索引）——即**离线参考链的产物本身**（ORT 1.28.0 跑 int8 ONNX）；
3. **对齐自检** = 用 fixture 的 ids 现场重跑同一份 int8 ONNX，与上面那份 npy 逐行比
   cosine，确认"行对齐"这件事不是假设（对齐失败直接退出，不产出 fixture）。

**换件纪律**：档位由 `--model` 选（默认 = 仓库当前随包那一档）。换件（模型/资产/词表任一
变更）后必须重跑本脚本，否则设备侧对拍的是**上一支模型**的参考向量——`DenseEncoderParityInstrumentedTest`
会红，或者更糟：如果有人把阈值解释成"设备自我一致"，就没人看得出它比错了对象。

用法（仓库根下）：
```
python tools/dense_build/gen_device_parity_fixture.py                     # 默认档
python tools/dense_build/gen_device_parity_fixture.py --model bge-small-zh-v1.5
```
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import struct
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
import dense_asset as D  # noqa: E402

TOKENIZER_FIXTURE = Path("core/data/src/test/resources/dense/tokenizer-parity-cases.txt")
OUT_DIR = Path("core/data/src/androidTest/assets/dense")
OUT_CASES = OUT_DIR / "encoder-parity-cases.tsv"
OUT_VECTORS = OUT_DIR / "encoder-parity-vectors.f32"
OUT_META = OUT_DIR / "encoder-parity.json"
QUERY_REF = Path("build/dense-model/int8-queries.npy")
DOC_REF = Path("build/dense-model/int8-docs.npy")
GOLDEN = Path("tools/kb_coverage/tables/golden_queries_v1.json")

# 档位坐标（`--model` 选；默认 = 仓库当前随包那一档）。`DIM`/`ONNX_MODEL`/`MAX_LEN` 都是
# 运行时从档位解析出来的——换件只换这一处来源，fixture 的形状（N×dim）随档走。
DIM = 0
MAX_LEN = 512
ONNX_MODEL = Path("build/dense-model/bge-small-zh-v1.5-int8.onnx")
THRESHOLD = 0.999
ALIGNMENT_FLOOR = 0.9999  # 行对齐自检的下限（低于它说明 npy 与模型/文本脱节）

BACKSLASH = chr(92)
ESCAPES = {BACKSLASH: BACKSLASH, "t": "\t", "n": "\n", "r": "\r"}
UNESCAPES = {v: k for k, v in ESCAPES.items()}


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


def escape(text: str) -> str:
    return (
        text.replace(BACKSLASH, BACKSLASH + BACKSLASH)
        .replace("\t", BACKSLASH + "t")
        .replace("\n", BACKSLASH + "n")
        .replace("\r", BACKSLASH + "r")
    )


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def cosine(left: np.ndarray, right: np.ndarray) -> float:
    return float(
        left @ right / (np.linalg.norm(left) * np.linalg.norm(right) + 1e-12)
    )


def load_tokenizer_fixture():
    rows = []
    for line in TOKENIZER_FIXTURE.read_text(encoding="utf-8").split("\n"):
        if not line or line.startswith("#"):
            continue
        fields = line.split("\t")
        if len(fields) != 6:
            raise SystemExit("tokenizer fixture 行格式不符：%r" % line[:120])
        kind, case_id, meta, text, ids, _tokens = fields
        if kind not in ("query", "surface"):
            continue
        row = 0
        match = re.search(r"row=(\d+)", meta)
        if match:
            row = int(match.group(1))
        rows.append(dict(
            kind=kind,
            caseId=case_id,
            meta=meta,
            text=unescape(text),
            ids=[int(x) for x in ids.split(" ")],
            referenceRow=row,
        ))
    return rows


def main() -> int:
    global DIM, MAX_LEN, ONNX_MODEL
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo-root", default=None)
    parser.add_argument("--model", default=None,
                        help="档位键（默认 %s；回退档 bge-small-zh-v1.5）" % D.DEFAULT_MODEL_KEY)
    args = parser.parse_args()
    root = D.repo_root(args.repo_root)
    profile = D.model_profile(args.model)
    DIM = profile["dim"]
    MAX_LEN = profile["maxLen"]
    ONNX_MODEL = Path(D.model_paths(profile)["int8"])
    print("档位=%s（dim=%d）" % (profile["key"], DIM))

    rows = load_tokenizer_fixture()
    queries = [r for r in rows if r["kind"] == "query"]
    surfaces = [r for r in rows if r["kind"] == "surface"]
    print("文本来源：query=%d surface=%d" % (len(queries), len(surfaces)))
    if len(queries) != 90 or len(surfaces) != 200:
        raise SystemExit("文本条数不符（应为 90 + 200）")

    query_ref = np.load(QUERY_REF).astype(np.float32)
    doc_ref = np.load(DOC_REF, mmap_mode="r")
    if query_ref.shape != (90, DIM):
        raise SystemExit("int8-queries.npy 形状不符：%s" % (query_ref.shape,))
    if doc_ref.shape[1] != DIM:
        raise SystemExit("int8-docs.npy 形状不符：%s" % (doc_ref.shape,))

    # ---- 行对齐自检：现场重跑 int8 ONNX（同一份模型文件），与 npy 逐行比 cosine ----
    import onnxruntime as ort

    session = ort.InferenceSession(str(ONNX_MODEL), providers=["CPUExecutionProvider"])
    print("ORT %s，模型 %s" % (ort.__version__, ONNX_MODEL))

    def encode(ids):
        # 截断到模型的 maxLen（不是 dim！dim 是输出维度，与输入长度无关）——端侧按
        # `encodePadded(text, 512)` 右 PAD，这里按同一长度上限截断，两者逐值等价。
        array = np.array(ids[:MAX_LEN], dtype=np.int64).reshape(1, -1)
        outputs = session.run(None, {
            "input_ids": array,
            "attention_mask": np.ones_like(array),
            "token_type_ids": np.zeros_like(array),
        })
        vector = np.asarray(outputs[0], dtype=np.float32).reshape(-1)
        return vector / (np.linalg.norm(vector) + 1e-12)

    alignment = []
    vectors = np.zeros((len(rows), DIM), dtype=np.float32)
    for index, row in enumerate(rows):
        if row["kind"] == "query":
            stored = query_ref[index]
        else:
            stored = np.asarray(doc_ref[row["referenceRow"]], dtype=np.float32)
        fresh = encode(row["ids"])
        value = cosine(fresh, stored)
        alignment.append(value)
        vectors[index] = stored
    norm_min = float(np.min(np.linalg.norm(vectors, axis=1)))
    norm_max = float(np.max(np.linalg.norm(vectors, axis=1)))
    print("行对齐自检（现场 ORT vs 冻结 npy）：min=%.9f median=%.9f n=%d" % (
        min(alignment), float(np.median(alignment)), len(alignment)))
    print("参考向量 L2 范数范围：[%.6f, %.6f]" % (norm_min, norm_max))
    if min(alignment) < ALIGNMENT_FLOOR:
        raise SystemExit("行对齐自检未过 %.6f（fixture 不产出）" % ALIGNMENT_FLOOR)

    OUT_DIR.mkdir(parents=True, exist_ok=True)
    lines = []
    for index, row in enumerate(rows):
        lines.append("\t".join((
            row["caseId"],
            row["kind"],
            escape(row["text"]),
            str(row["referenceRow"]),
        )))
    OUT_CASES.write_text("\n".join(lines) + "\n", encoding="utf-8")
    OUT_VECTORS.write_bytes(vectors.astype("<f4").tobytes(order="C"))
    meta = {
        "generatedBy": "python tools/dense_build/gen_device_parity_fixture.py",
        "threshold": THRESHOLD,
        "thresholdMeaning": "端侧编码向量与参考向量的逐条 cosine 下限（任务书硬门）",
        "dim": DIM,
        "count": len(rows),
        "byKind": {"query": len(queries), "surface": len(surfaces)},
        "textSource": {
            "path": str(TOKENIZER_FIXTURE),
            "sha256": sha256_file(TOKENIZER_FIXTURE),
            "note": "query 行含 BGE 查询前缀；surface 行是向量集原文（节点名/别名）",
        },
        "referenceVectors": {
            "queries": {"path": str(QUERY_REF), "sha256": sha256_file(QUERY_REF)},
            "docs": {"path": str(DOC_REF), "sha256": sha256_file(DOC_REF)},
            "producer": "onnxruntime " + ort.__version__ + " on " + str(ONNX_MODEL),
            "modelSha256": sha256_file(ONNX_MODEL),
        },
        "golden": {"path": str(GOLDEN), "sha256": sha256_file(GOLDEN)},
        "alignmentSelfCheck": {
            "note": "用同一份 int8 ONNX 现场重跑 fixture 的 ids，与冻结 npy 逐行比 cosine",
            "min": min(alignment),
            "median": float(np.median(alignment)),
            "floor": ALIGNMENT_FLOOR,
        },
        "casesFile": {"path": str(OUT_CASES), "sha256": sha256_file(OUT_CASES)},
        "vectorsFile": {
            "path": str(OUT_VECTORS),
            "sha256": sha256_file(OUT_VECTORS),
            "layout": "little-endian float32, row-major, N×%d" % DIM,
            "l2NormRange": [norm_min, norm_max],
        },
    }
    OUT_META.write_text(json.dumps(meta, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print("写出：%s / %s / %s" % (OUT_CASES, OUT_VECTORS, OUT_META))
    print("vectors sha256=%s" % meta["vectorsFile"]["sha256"])
    return 0


if __name__ == "__main__":
    sys.exit(main())
