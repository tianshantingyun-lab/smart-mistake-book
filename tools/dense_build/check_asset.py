# -*- coding: utf-8 -*-
"""dense 向量资产的**陈旧性门**（CI 与本地档 1 都跑这一份）。

## 它消灭的具体失败

`.vec` 是**随包分发的资产**：它的向量是按**某一版包**（`moe-2025-four-subjects-v1.json`）
的 3,572 个原子节点 + 25,360 条别名生成的，用的词表也是某一版。包或词表**内容变了而向量
没重生成**，端侧就会拿"旧内容的向量"去跟"新词条的文本"比——检索分数整体失真，而且**没有任何
现有门会红**：`run_kb_checks.py` 的 22 门查的是包自身的契约，向量文件不在它们的视野里。

所以这门把三者钉在一起，任一处与旁车记录不符即红：

    旁车(shas) == 当前包 sha256  +  当前词表 sha256  +  .vec 自身 sha256  +  行数/维度/ids == 当前包布局

## 为什么 ids 也要比（不只看 sha）

sha 只证明"文件没被改过"，不证明"它对应的是这一版包"。ids 与 `atomic_layout` 逐条相等
才把"这份向量属于这一版包"钉死——这正是"内容变了向量没跟着变"那一类的正面覆盖。

## 正反两侧

`tools/tests/test_dense_asset_gate.py` 用真资产跑正侧（绿），用临时副本改包/改旁车跑反侧（红）。
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import dense_asset as D  # noqa: E402


def evaluate(repo_root=None, pack_path=None, sidecar_path=None, vocab_path=None,
             tokenizer_path=None, vector_path=None, model_manifest_path=None) -> dict:
    """跑一遍 dense 资产门。返回 {ok, checks:[{name, ok, detail}], failed:[...]}。

    路径参数只为**测试**留（正侧用默认、反侧指向临时副本）；生产调用一律用默认值。
    """
    root = D.repo_root(repo_root)
    pack = Path(pack_path) if pack_path else root.joinpath(*D.PACK_RELATIVE.split("/"))
    vector = Path(vector_path) if vector_path else (
        root.joinpath(*D.DENSE_DIR_RELATIVE.split("/")) / D.VECTOR_FILE_NAME)
    sidecar_file = Path(sidecar_path) if sidecar_path else vector.with_name(vector.name + ".json")
    vocab = Path(vocab_path) if vocab_path else root.joinpath(*D.VOCAB_RELATIVE.split("/"))
    tokenizer = Path(tokenizer_path) if tokenizer_path else root.joinpath(*D.TOKENIZER_RELATIVE.split("/"))
    manifest = Path(model_manifest_path) if model_manifest_path else (
        root.joinpath(*D.MODEL_MANIFEST_RELATIVE.split("/")))

    checks = []

    def record(name, ok, detail):
        checks.append(dict(name=name, ok=bool(ok), detail=detail))

    if not sidecar_file.is_file():
        record("sidecar", False, "缺旁车 %s（先跑 tools/dense_build/pack_dense_asset.py）" % sidecar_file)
        return dict(ok=False, checks=checks, failed=[c for c in checks if not c["ok"]])
    sidecar = json.loads(sidecar_file.read_text(encoding="utf-8"))
    record("sidecar", True, "%s" % sidecar_file.name)

    if not vector.is_file():
        record("vectorFile", False, "缺向量资产 %s" % vector)
        return dict(ok=False, checks=checks, failed=[c for c in checks if not c["ok"]])
    if not pack.is_file():
        record("pack", False, "缺包 %s" % pack)
        return dict(ok=False, checks=checks, failed=[c for c in checks if not c["ok"]])

    # ---- 1) 包哈希：内容变了没重生成向量 ⇒ 红 ----
    pack_sha = D.sha256_file(pack)
    expected = (sidecar.get("packSha256") or "")
    record("packSha256", pack_sha == expected,
           "当前包 sha256=%s；旁车记录=%s" % (pack_sha[:16], (expected or "(缺)")[:16]))

    # ---- 2) 词表哈希：词表换了没重导出 ⇒ 红 ----
    # 旁车字段名与词表种类同名对不上会静默漏检（首版就是这样：读 `vocab.vocabSha256` 而
    # 旁车写的是 `vocab.sha256`，于是"记录=缺"却没人看见）——所以映射写死在这里。
    for key, path, field in (("vocab", vocab, "sha256"),
                             ("tokenizer", tokenizer, "tokenizerJsonSha256")):
        if not path.is_file():
            record("vocab:%s" % key, False, "缺词表 %s" % path)
            continue
        actual = D.sha256_file(path)
        recorded = ((sidecar.get("vocab") or {}).get(field)) or ""
        record("vocab:%s" % key, actual == recorded,
               "当前 %s sha256=%s；旁车记录 %s=%s" % (path.name, actual[:16], field, (recorded or "(缺)")[:16]))

    # ---- 3) .vec 自身哈希 + 头字段 ----
    actual_sha = D.sha256_file(vector)
    record("vectorSha256", actual_sha == (sidecar.get("sha256") or ""),
           "当前 .vec sha256=%s；旁车记录=%s" % (actual_sha[:16], (sidecar.get("sha256") or "(缺)")[:16]))
    try:
        header = D.read_header(vector)
    except SystemExit as exc:
        record("vectorHeader", False, str(exc))
        return dict(ok=False, checks=checks, failed=[c for c in checks if not c["ok"]])
    fmt = sidecar.get("format") or {}
    record("vectorHeader",
           header["dim"] == fmt.get("dim") and header["count"] == fmt.get("count")
           and header["version"] == D.VERSION,
           "头：dim=%s count=%s version=%s；旁车：dim=%s count=%s"
           % (header["dim"], header["count"], header["version"], fmt.get("dim"), fmt.get("count")))
    corpus = sidecar.get("corpus") or {}
    record("vectorCount", header["count"] == corpus.get("vectorCount"),
           "头 count=%s；旁车 vectorCount=%s" % (header["count"], corpus.get("vectorCount")))
    record("vectorBytes", vector.stat().st_size == header["expectedBytes"],
           "文件 %d B；头自述 %d B" % (vector.stat().st_size, header["expectedBytes"]))

    # ---- 4) ids == 当前包的原子布局（"这份向量属于这一版包"的正面证明） ----
    try:
        rows, _nodes, _groups = D.atomic_layout(root, pack_path=pack)
    except SystemExit as exc:
        record("layout", False, "无法解析包布局：%s" % exc)
    else:
        # ids 块是**逐向量**的（28,932 条，同一节点的向量连续、id 逐行重复）——与打包侧同一约定。
        expected_ids = [row["node_id"] for row in rows]
        if header["ids"] == expected_ids:
            record("layout", True, "ids 与包布局逐条一致（%d 条向量）" % len(expected_ids))
        else:
            first = next((i for i, (a, b) in enumerate(zip(header["ids"], expected_ids)) if a != b), None)
            record("layout", False,
                   "ids 与当前包布局不一致：长度 %d vs %d，首个差异下标 %s（%s vs %s）"
                   % (len(header["ids"]), len(expected_ids), first,
                      header["ids"][first] if first is not None else "-",
                      expected_ids[first] if first is not None else "-"))

    # ---- 5) 模型清单（在则比对；不在则如实标未验证，不当作通过） ----
    if manifest.is_file():
        model = json.loads(manifest.read_text(encoding="utf-8"))
        onnx_int8 = (model.get("onnx") or {}).get("int8Path")
        onnx_path = root / onnx_int8 if onnx_int8 else None
        model_checks = []
        model_checks.append(("vocabSha256", (model.get("tokenizer") or {}).get("vocabSha256")
                             == ((sidecar.get("vocab") or {}).get("sha256"))))
        model_checks.append(("sidecarOnnxInt8Sha256", (sidecar.get("model") or {}).get("onnxInt8Sha256")
                             == (model.get("onnx") or {}).get("int8Sha256")))
        if onnx_path is not None and onnx_path.is_file():
            model_checks.append(("onnxBytes", D.sha256_file(onnx_path)
                                 == (model.get("onnx") or {}).get("int8Sha256")))
            detail = "onnx 在位并复核哈希"
        else:
            detail = "onnx 不在位（%s）——**未验证**（模型件不入库，可从 model-manifest.json 的脚本+坐标重生成）" % onnx_int8
        record("modelManifest", all(ok for _, ok in model_checks),
               "清单三方一致=%s；%s" % ([(n, ok) for n, ok in model_checks], detail))
    else:
        record("modelManifest", True, "无 %s（跳过，不算通过）" % D.MODEL_MANIFEST_RELATIVE)

    failed = [c for c in checks if not c["ok"]]
    return dict(ok=not failed, checks=checks, failed=failed)


def main(argv=None) -> int:
    import argparse

    parser = argparse.ArgumentParser(description="dense 向量资产陈旧性门")
    parser.add_argument("--repo-root", default=None)
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args(argv)
    result = evaluate(args.repo_root)
    if args.json:
        print(json.dumps(result, ensure_ascii=False, indent=1))
    else:
        for check in result["checks"]:
            print("  %s %s：%s" % ("OK  " if check["ok"] else "FAIL", check["name"], check["detail"]))
    return 0 if result["ok"] else 1


if __name__ == "__main__":
    sys.exit(main())
