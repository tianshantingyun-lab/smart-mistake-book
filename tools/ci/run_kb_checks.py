#!/usr/bin/env python3
"""知识库构建侧统一检查入口：22 门 + 五表一致性 + round-trip + 台账。

CI（android-check workflow）与本地档 1 都调这一个脚本，避免"门在 CI 上跑的是
另一套参数"的漂移。检查对象是**晋升候选**：本地默认 `build/kb-staging/`
（不存在时回退成品目录——干净检出上等价于查成品）；`--root` 可指定任意包目录。

    python3 tools/ci/run_kb_checks.py            # 摘要 + exit 0/1
    python3 tools/ci/run_kb_checks.py --json     # JSON 摘要

退出码：全绿 0，任一节失败 1。
"""

from __future__ import annotations

import argparse
import copy
import json
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / "tools"))

from kb_build import check_pack_contract, gate, pack_io, roundtrip, update_manifest  # noqa: E402
from dense_build import check_asset  # noqa: E402


def pick_default_root() -> Path:
    """本地有 staging 查 staging（晋升候选）；CI 干净检出无 staging，查成品。"""
    if (pack_io.STAGING_DIR / pack_io.PACK_NAME).exists():
        return pack_io.STAGING_DIR
    return pack_io.release_dir()


def run(root: Path) -> dict:
    pack_io.use_directory(root)
    try:
        sections: dict = {"root": str(root)}

        metrics = gate.evaluate()
        sections["gates"] = {
            "ok": all(m.ok for m in metrics),
            "count": len(metrics),
            "failed": [{"key": m.key, "title": m.title, "value": m.value,
                        "detail": m.detail[:10]} for m in metrics if not m.ok],
        }

        consistency, stats = check_pack_contract.evaluate(root)
        bad = {name: problems for name, problems in consistency.items() if problems}
        sections["consistency"] = {
            "ok": not bad,
            "topics": stats["topics"],
            "points": stats["points"],
            "failed": bad,
        }

        total, failures = roundtrip.run(root)
        sections["roundtrip"] = {"ok": failures == 0, "files": total, "failed": failures}

        manifest = update_manifest.load(root / update_manifest.MANIFEST_NAME)
        problems: list[str] = []
        if manifest is None:
            problems.append(f"缺台账 {update_manifest.MANIFEST_NAME}")
        else:
            # schema 2 必须**当下**就一跳到底（App 侧 codec 会拒）；
            # schema 1 是历史形态，按晋升变换（canonicalize）后必须可压平干净。
            try:
                update_manifest._require_shape(manifest)
                if manifest.get("schemaVersion") == update_manifest.SCHEMA_VERSION:
                    update_manifest.assert_one_hop(manifest)
                else:
                    update_manifest.canonicalize(copy.deepcopy(manifest))
            except ValueError as exc:
                problems.append(str(exc))
        sections["manifest"] = {"ok": not problems, "problems": problems}

        # dense 向量资产门（Stage-3）：`core/data/src/main/resources/knowledge/dense/` 下的
        # `.vec` 是**随包分发的资产**，它的向量是按某一版包与词表生成的。包或词表内容变了而
        # 向量没跟着重生成，端侧就会拿"旧内容的向量"比"新词条的文本"——而现有 22 门只查包
        # 自身的契约，看不到这个文件。这一节把三者钉在一起（旁车哈希 == 当前包/词表/.vec）。
        dense_result = check_asset.evaluate(REPO)
        sections["dense"] = {
            "ok": dense_result["ok"],
            "checks": len(dense_result["checks"]),
            "failed": [{"name": c["name"], "detail": c["detail"]} for c in dense_result["failed"]],
        }

        sections["ok"] = all(s["ok"] for s in sections.values() if isinstance(s, dict))
        return sections
    finally:
        pack_io.reset_directory()


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="知识库构建侧统一检查入口")
    parser.add_argument("--root", type=Path, default=None,
                        help="包目录（默认：staging 存在则 staging，否则成品）")
    parser.add_argument("--json", action="store_true", help="JSON 摘要输出")
    args = parser.parse_args(argv)

    root = (args.root or pick_default_root()).resolve()
    result = run(root)

    if args.json:
        print(json.dumps(result, ensure_ascii=False, indent=1))
    else:
        print(f"kb checks（{root}）：")
        for name in ("gates", "consistency", "roundtrip", "manifest", "dense"):
            section = result[name]
            mark = "OK  " if section["ok"] else "FAIL"
            extra = ""
            if name == "gates" and section["failed"]:
                extra = "：" + "、".join(f"{f['key']}={f['value']}" for f in section["failed"])
            if name == "consistency" and section["failed"]:
                extra = "：" + "、".join(f"{k}×{len(v)}" for k, v in section["failed"].items())
            if name == "roundtrip" and not section["ok"]:
                extra = f"：{section['failed']} 个文件不一致"
            if name == "manifest" and section["problems"]:
                extra = "：" + "; ".join(section["problems"][:3])
            if name == "dense" and section["failed"]:
                extra = "：" + "; ".join(f"{f['name']}（{f['detail']}）" for f in section["failed"])
            print(f"  {mark} {name}{extra}")
        if not result["ok"]:
            for item in result["gates"]["failed"][:10]:
                print(f"      [门] {item['title']}：{item['value']} 条")
            for table, problems in result["consistency"]["failed"].items():
                for line in problems[:8]:
                    print(f"      [{table}]", line)
            for line in result["manifest"]["problems"][:5]:
                print(f"      [manifest]", line)
    return 0 if result["ok"] else 1


if __name__ == "__main__":
    sys.exit(main())
