# -*- coding: utf-8 -*-
"""Stage-3 端侧 · tokenizer 对拍 fixture 的**生成脚本**（Python 参考侧）。

## 回答什么问题

端侧 Kotlin 分词器（`core/data/src/main/kotlin/.../knowledge/dense/DenseTokenizer.kt`）
必须与离线侧**逐条同结果**（spec §3.2："不一致即停"）——否则离线 0.7333 在端侧根本不是
同一个模型在跑。本脚本把参考实现（Python `transformers` 的 BertTokenizer）的输出冻结成
fixture，端侧 JVM 测试逐 token id 比对。

## 参考实现是哪一个（不许换）

`tools/dense_build/export_bge_int8.py`（离线出数的唯一编码链）用的是：

```python
AutoTokenizer.from_pretrained(local_dir, do_lower_case=<sentence_bert_config.json 的值>,
                              strip_accents=False)
```

本次实测（2026-09-24，transformers 5.17.0）：`type(tok).__name__ == "BertTokenizer"`，
且它**不是**旧的纯 Python 实现，而是 `TokenizersBackend` —— 内部链为
`normalizers.BertNormalizer(clean_text=True, handle_chinese_chars=True, strip_accents=False,
lowercase=True)` → `pre_tokenizers.BertPreTokenizer()` → `models.WordPiece` →
`processors.TemplateProcessing("[CLS]:0 $A:0 [SEP]:0")`（`tokenizers` 0.23.2，Rust）。
端侧 Kotlin 因此要对拍的是**这一套 Rust 语义**，本脚本用同一个对象生成期望值，不重写口径。

## 产物（入库跟踪，端侧 JVM 测试读它）

| 文件 | 形状 |
|---|---|
| `core/data/src/test/resources/dense/tokenizer-parity-cases.txt` | TSV：`kind \t caseId \t meta \t text \t ids \t tokens`（text/tokens 用 `\\ \t \n \r` 转义） |
| `core/data/src/test/resources/dense/tokenizer-parity-stages.txt` | TSV：`caseId \t text \t normalized \t splits`（逐阶段中间值，出偏差时定位用） |
| `core/data/src/test/resources/dense/tokenizer-parity-summary.json` | 溯源 + 自证（词表/tokenizer.json/金标 sha、条数、脚本内断言结果、cases 文件 sha256） |

case 组成（写死，改这里等于改 fixture）：
1. `query` × 90：金标 90 条**加查询前缀**后的整串（端侧查询侧的输入就是这个串）；
2. `surface` × 200：按种子 `20260924` 从 28,931 条向量集里抽的节点文本（canonicalName + alias），
   排序后固定；
3. `edge` × N：边界探针（空串、纯空白、超长截断 509/510/511/512/513、超 100 字单词、
   ASCII/全角/希腊/组合音标/控制字符/零宽字符/中日韩标点等），覆盖 §"最高风险件"的类判定。

## 复算

```
python tools/dense_build/gen_tokenizer_fixture.py          # 写 fixture + 打印 sha256
```

脚本内自证（不通过即 SystemExit，不落盘）：
- 词表 sha256 == `dense_asset.VOCAB_SHA256`、tokenizer.json sha256 == `TOKENIZER_JSON_SHA256`；
- 90 条查询**按 export_bge_int8.py 的批式口径**（`padding=True, truncation=True,
  max_length=512` 一次 64 条）编码后剥掉尾部 PAD，逐条等于本 fixture 的单条形式
  —— 证明 fixture 记的不是"另一套调用"。

## 换件后要不要重生成（Stage-5，2026-09-25 实测）

**不用。** 换到 bge-base-zh-v1.5 后本 fixture 一字不改仍成立，两条实测依据：
1. 两档 vocab.txt **逐字节相同**（sha256 `45bbac6b…`）⇒ WordPiece 词表同一份；
2. 用 bge-base 的 tokenizer（`do_lower_case=true` = 包内 `sentence_bert_config.json`）
   重编码本 fixture 的**全部 333 条**（query 90 / surface 200 / edge 18 / stage 25），
   与冻结 ids **逐条相同**（`export_bge_int8.py` 在换档导出时当场断言，不一致即停）。
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import random
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import dense_asset as D  # noqa: E402

SNAPSHOT_RELATIVE = (
    # 词表来源**写死为 bge-small-zh-v1.5 的 snapshot**（不是"当前档"）：两档的 vocab.txt 逐字节
    # 相同（实测 sha256 45bbac6b…，见 tools/dense_build/README.md §8），冻结副本是**共享词表**；
    # 而 bge-base 的 tokenizer.json 与冻结副本不同（仅 normalizer.lowercase 一处），拿它当来源
    # 会让下面的 pinned sha 断言当场红。所以这里按"词表那一档"取，不跟 D.BGE_REVISION 走
    # ——那是个会随换件漂移的常量，而这份 fixture 的输入其实不随换件变。
    "models--BAAI--bge-small-zh-v1.5/snapshots/7999e1d3359715c523056ef9478215996d62a620"
)
CASES_RELATIVE = "core/data/src/test/resources/dense/tokenizer-parity-cases.txt"
STAGES_RELATIVE = "core/data/src/test/resources/dense/tokenizer-parity-stages.txt"
SUMMARY_RELATIVE = "core/data/src/test/resources/dense/tokenizer-parity-summary.json"
# 词表/转换件 sha（与 core/data/src/main/resources/knowledge/dense/*.vec.json 旁车同源）
VOCAB_SHA256 = "45bbac6b341c319adc98a532532882e91a9cefc0329aa57bac9ae761c27b291c"
TOKENIZER_JSON_SHA256 = "48cea5d44424912a6fd1ea647bf4fe50b55ab8b1e5879c3275f80e339e8fae26"
SURFACE_SAMPLE = 200
SURFACE_SEED = 20260924
BATCH_SIZE = 64

# 逐阶段中间值探针（端侧 Kotlin 的 normalize/preTokenize 由此钉住；同一条也进 cases）
STAGE_PROBES = [
    ("unicode_nbsp", "a\u00a0b"),
    ("unicode_zwsp", "a\u200bb"),
    ("unicode_nul_replacement", "a\u0000b\ufffdc"),
    ("unicode_fullwidth_latin", "\uff26\uff55\uff4c\uff4c\uff37\uff49\uff44\uff54\uff48"),
    ("unicode_dotted_capital_i", "\u0130stanbul I \u0131"),
    ("unicode_capital_sharp_s", "\u1e9e \u00df \u1e84"),
    ("unicode_greek_sigma", "\u0391\u0392\u0393 \u03b1\u03b2\u03b3 \u03a3 \u039f\u03a3"),
    ("unicode_roman_numeral", "\u2460 \u2461 \u2167 \u2177"),
    ("unicode_dashes", "a\u2014b\u2013c\u2026d"),
    ("unicode_cjk_punct", "\u3002\u3001\uff0c\uff1b\uff1a\uff01\uff1f\uff08\uff09\u300a\u300b\u201c\u201d\u3010\u3011"),
    ("unicode_astral_cjk", "\U0002000b\U0002000c"),
    ("unicode_astral_math", "\U0001d54f\U0001d54f"),
    ("unicode_combining_acute", "x\u0301y"),
    ("unicode_soft_hyphen", "a\u00adb"),
    ("unicode_ideographic_space", "a\u3000b"),
    ("whitespace_mix", "tabs\tand\nnewlines\r\n"),
    ("whitespace_cjk_pad", "  \u591a  \u7a7a\u683c  "),
    ("latin_precomposed_accents", "Caf\u00e9 Na\u00efve"),
    ("ascii_chems", "MgO=40.3%CaO"),
    ("fullwidth_solidus", "\uff0f\uff0f\u5168\u89d2\u659c\u6760"),
    ("kana_hangul", "\uff71\uff72\uff73\uff74\uff75 \u3072\u3089\u304c\u306a \u30ab\u30bf\u30ab\u30ca \u30cf\u30f3\u30b0\u30eb"),
    ("ascii_punct_run", "Hey friend!     How are you?!?"),
    ("underscore_tilde", "a_b~c"),
    ("private_use", "\ue000z"),
    ("unassigned", "\u0378z"),
]

EDGE_CASES = [
    ("space_only", "   "),
    ("single_ascii", "a"),
    ("single_cjk", "\u529b"),
    ("ascii_punct_only", "!?.,;:"),
    ("digits", "1234567890 3.14 -273.15"),
    ("mixed_cn_ascii", "\u4e24\u4e2a\u529b3N\u548c8N\uff0c\u5408\u529b\u6700\u5927\u6700\u5c0f\u5404\u662f\u591a\u5c11\uff1f"),
    ("long_cjk_509", "\u529b" * 509),
    ("long_cjk_510", "\u529b" * 510),
    ("long_cjk_511", "\u529b" * 511),
    ("long_cjk_512", "\u529b" * 512),
    ("long_cjk_513", "\u529b" * 513),
    ("long_cjk_2000", "\u529b" * 2000),
    ("word_100_chars", "a" * 100),
    ("word_101_chars", "a" * 101),
    ("word_120_digits", "1" * 120),
    ("sep_like_tokens", "[CLS] hello [SEP] [UNK] [PAD] [MASK]"),
    ("url", "https://example.com/a?b=1&c=2"),
    ("emoji", "\u628a\u9519\u9898\U0001F600\u62cd\u4e0b\u6765"),
]


def esc(text: str) -> str:
    return (text.replace("\\", "\\\\").replace("\t", "\\t")
            .replace("\n", "\\n").replace("\r", "\\r"))


def load_tokenizer(snapshot_dir: Path):
    from transformers import AutoTokenizer

    sbert = snapshot_dir / "sentence_bert_config.json"
    if not sbert.is_file():
        raise SystemExit("缺 sentence_bert_config.json：%s" % snapshot_dir)
    do_lower_case = bool(json.loads(sbert.read_text(encoding="utf-8")).get("do_lower_case", False))
    tokenizer = AutoTokenizer.from_pretrained(
        str(snapshot_dir), do_lower_case=do_lower_case, strip_accents=False)
    if type(tokenizer).__name__ != "BertTokenizer":
        raise SystemExit("参考 tokenizer 应为 BertTokenizer，实测 %s" % type(tokenizer).__name__)
    return tokenizer, dict(do_lower_case=do_lower_case, strip_accents=False)


def build_cases(root: Path):
    """(kind, caseId, meta, text) 序列。顺序写死：query → surface → edge → stage。"""
    cases = []
    goldens = D.goldens(root)
    for index, case in enumerate(goldens):
        cases.append(("query", "q%03d" % index,
                      "golden#%d subject=%s chapter=%s slug=%s"
                      % (index, case["subject"], case["chapter"], case["expectedSlug"]),
                      D.BGE_QUERY_PREFIX + case["query"]))
    rows, _nodes, _groups = D.atomic_layout(root)
    rng = random.Random(SURFACE_SEED)
    picked = sorted(rng.sample(range(len(rows)), SURFACE_SAMPLE))
    for slot, row_index in enumerate(picked):
        row = rows[row_index]
        cases.append(("surface", "s%03d" % slot,
                      "row=%d kind=%s node=%s" % (row_index, row["kind"], row["node_id"]),
                      row["surface"]))
    for name, text in EDGE_CASES:
        cases.append(("edge", "e_%s" % name, "synthetic", text))
    for name, text in STAGE_PROBES:
        cases.append(("stage", "p_%s" % name, "synthetic", text))
    return cases


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo-root", default=None)
    parser.add_argument("--snapshot", default=None,
                        help="模型快照目录（默认取 HF 缓存里钉住的 revision）")
    args = parser.parse_args()
    root = D.repo_root(args.repo_root)

    vocab_path = root.joinpath(*D.VOCAB_RELATIVE.split("/"))
    tokenizer_json_path = root.joinpath(*D.TOKENIZER_RELATIVE.split("/"))
    vocab_sha = D.sha256_file(vocab_path)
    tokenizer_json_sha = D.sha256_file(tokenizer_json_path)
    if vocab_sha != VOCAB_SHA256:
        raise SystemExit("词表 sha256 不符：%s != %s" % (vocab_sha, VOCAB_SHA256))
    if tokenizer_json_sha != TOKENIZER_JSON_SHA256:
        raise SystemExit("tokenizer.json sha256 不符：%s != %s" % (tokenizer_json_sha, TOKENIZER_JSON_SHA256))

    snapshot = Path(args.snapshot) if args.snapshot else Path(
        os.path.expanduser("~/.cache/huggingface/hub")).joinpath(*SNAPSHOT_RELATIVE.split("/"))
    if not snapshot.is_dir():
        raise SystemExit("模型快照不在位：%s（先跑 export_bge_int8.py 取模型）" % snapshot)

    tokenizer, kwargs = load_tokenizer(snapshot)
    cases = build_cases(root)
    kinds = {}
    for kind, _case_id, _meta, _text in cases:
        kinds[kind] = kinds.get(kind, 0) + 1

    # 空串在参考实现里没有定义（tokenizers backend 对 "" 直接 TypeError，见 summary.honesty）——
    # 本探针把它记成**事实**，不塞进 fixture 假装有个期望值。
    empty_input_raises = False
    try:
        tokenizer("", truncation=True, max_length=D.BGE_MAX_LEN)
    except TypeError:
        empty_input_raises = True

    def ids_of(text: str):
        return tokenizer(text, truncation=True, max_length=D.BGE_MAX_LEN)["input_ids"]

    # ---- 自证：90 条查询按 export_bge_int8.py 的批式口径编码，剥 PAD 后逐条相等 ----
    query_texts = [text for kind, _i, _m, text in cases if kind == "query"]
    batch_ok = True
    for start in range(0, len(query_texts), BATCH_SIZE):
        batch = query_texts[start:start + BATCH_SIZE]
        encoded = tokenizer(batch, padding=True, truncation=True, max_length=D.BGE_MAX_LEN)
        for offset, row in enumerate(encoded["input_ids"]):
            stripped = list(row)
            while stripped and stripped[-1] == tokenizer.pad_token_id:
                stripped.pop()
            single = ids_of(batch[offset])
            if stripped != single:
                batch_ok = False
                print("[自证失败] 批式与单条不一致：%s" % batch[offset])
    if not batch_ok:
        raise SystemExit("批式（padding=True）与单条编码不一致——fixture 口径不是参考链的口径")

    # ---- cases 行 ----
    case_lines = []
    for kind, case_id, meta, text in cases:
        ids = ids_of(text)
        tokens = tokenizer.convert_ids_to_tokens(ids)
        case_lines.append("\t".join([
            kind, case_id, esc(meta), esc(text),
            " ".join(str(i) for i in ids),
            "\u0001".join(esc(t) for t in tokens),
        ]))
    # 结构自证：每行恰好 6 列（转义没过干净的话这里必然多列）、无裸换行
    bad_lines = [line for line in case_lines
                 if line.count("\t") != 5 or "\n" in line or "\r" in line]
    if bad_lines:
        raise SystemExit("转义/列数不合规（%d 行）：%r" % (len(bad_lines), bad_lines[0][:120]))

    # ---- stages 行（逐阶段中间值；探针 + 抽样 queries/surfaces）----
    backend = tokenizer.backend_tokenizer
    normalizer = backend.normalizer
    pretok = backend.pre_tokenizer
    stage_cases = [(i, t) for k, i, _m, t in cases if k in ("stage", "edge")]
    stage_cases += [(i, t) for k, i, _m, t in cases if k == "query"][:5]
    stage_cases += [(i, t) for k, i, _m, t in cases if k == "surface"][:20]
    stage_lines = []
    for case_id, text in stage_cases:
        normalized = normalizer.normalize_str(text)
        splits = pretok.pre_tokenize_str(normalized)
        stage_lines.append("\t".join([
            case_id, esc(text), esc(normalized),
            "\u0001".join(esc(piece) for piece, _offset in splits),
        ]))

    header = [
        "# Stage-3 端侧 tokenizer 对拍 fixture（生成脚本 tools/dense_build/gen_tokenizer_fixture.py）",
        "# 参考实现：transformers %s 的 BertTokenizer == TokenizersBackend"
        % __import__("transformers").__version__,
        "# normalizer=BertNormalizer(clean_text=True, handle_chinese_chars=True, strip_accents=False, lowercase=True)",
        "# pre_tokenizer=BertPreTokenizer()  model=WordPiece(prefix=##, unk=[UNK], max_input_chars_per_word=100)",
        "# post_processor=TemplateProcessing([CLS] $A [SEP])  截断=truncation max_length=512（含特殊符）",
        "# 列：kind \\t caseId \\t meta \\t text \\t ids(空格分隔) \\t tokens(\\u0001 分隔；text/tokens 转义 \\\\ \\t \\n \\r)",
        "# 词表 sha256=%s" % vocab_sha,
        "# tokenizer.json sha256=%s" % tokenizer_json_sha,
        "# 金标 sha256=%s（%d 条）" % (D.GOLDEN_SHA256, len(query_texts)),
        "# 条数：%s（合计 %d）" % (", ".join("%s=%d" % kv for kv in sorted(kinds.items())), len(cases)),
    ]

    cases_path = root.joinpath(*CASES_RELATIVE.split("/"))
    stages_path = root.joinpath(*STAGES_RELATIVE.split("/"))
    summary_path = root.joinpath(*SUMMARY_RELATIVE.split("/"))
    for path in (cases_path, stages_path, summary_path):
        path.parent.mkdir(parents=True, exist_ok=True)
    cases_path.write_text("\n".join(header + case_lines) + "\n", encoding="utf-8", newline="\n")
    stages_path.write_text("\n".join(header + stage_lines) + "\n", encoding="utf-8", newline="\n")

    summary = dict(
        generatedBy="python tools/dense_build/gen_tokenizer_fixture.py",
        reference=dict(
            library="transformers", version=__import__("transformers").__version__,
            tokenizerClass="BertTokenizer", backend="TokenizersBackend",
            kwargs=kwargs,
            normalizer="BertNormalizer(clean_text=True, handle_chinese_chars=True, "
                       "strip_accents=False, lowercase=True)",
            preTokenizer="BertPreTokenizer()",
            model="WordPiece(continuing_subword_prefix=##, unk_token=[UNK], max_input_chars_per_word=100)",
            postProcessor="TemplateProcessing(single=[CLS]:0 $A:0 [SEP]:0)",
            truncation="truncation=True, max_length=512（含 [CLS]/[SEP]）",
            padding="padding=True（批式；fixture 记录剥掉尾部 PAD 的单条形式）",
            backendVersion=__import__("tokenizers").__version__,
        ),
        inputs=dict(
            vocabPath=D.VOCAB_RELATIVE, vocabSha256=vocab_sha,
            tokenizerJsonPath=D.TOKENIZER_RELATIVE, tokenizerJsonSha256=tokenizer_json_sha,
            goldenPath=D.GOLDEN_RELATIVE, goldenSha256=D.GOLDEN_SHA256,
            surfaceSample=dict(count=SURFACE_SAMPLE, seed=SURFACE_SEED, universe=28931,
                               note="从向量集 28,931 条文本（canonicalName + alias）按种子抽取、排序"),
        ),
        counts=dict(total=len(cases), byKind=kinds, stageRows=len(stage_cases)),
        selfCheck=dict(
            batchPaddingEqualsSingle=bool(batch_ok),
            note="90 条查询按 export_bge_int8.py 的批式（padding=True）编码、剥尾部 PAD 后与单条逐条相等",
        ),
        honesty=dict(
            emptyInput="参考实现（tokenizers backend）对空串直接 TypeError，没有可对拍的期望值："
                       "fixture 不含空串；端侧对空/纯空白输入只承诺**不崩**（由 Kotlin 自己的单测钉住）。"
                       "实测 raisesTypeError=%s" % empty_input_raises,
            scope="fixture 覆盖 90 条金标查询（带前缀）+ 200 条节点文本抽样（种子 %d）+ 边界探针；"
                  "spec §3.2 的 28,931 条全量对拍**不在本 fixture**（样本量按本轮任务书写死）"
                  % SURFACE_SEED,
        ),
        files=dict(
            cases=dict(path=CASES_RELATIVE, sha256=D.sha256_file(cases_path), rows=len(case_lines)),
            stages=dict(path=STAGES_RELATIVE, sha256=D.sha256_file(stages_path), rows=len(stage_lines)),
        ),
    )
    summary_path.write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n",
                            encoding="utf-8", newline="\n")
    print("cases   %s rows=%d sha256=%s" % (CASES_RELATIVE, len(case_lines),
                                            summary["files"]["cases"]["sha256"]))
    print("stages  %s rows=%d sha256=%s" % (STAGES_RELATIVE, len(stage_lines),
                                            summary["files"]["stages"]["sha256"]))
    print("summary %s" % SUMMARY_RELATIVE)
    print("条数：%s（合计 %d）；批式自证=%s"
          % (", ".join("%s=%d" % kv for kv in sorted(kinds.items())), len(cases), batch_ok))
    print("sha256(cases)=%s" % hashlib.sha256(
        cases_path.read_bytes()).hexdigest())


if __name__ == "__main__":
    main()
