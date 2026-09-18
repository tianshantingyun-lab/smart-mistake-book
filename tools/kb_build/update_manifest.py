# -*- coding: utf-8 -*-
"""取代映射台账：记录"哪个知识点被退役了、被谁取代"。

## 它消灭的失败

合并与删除在内容侧做得**很完整**（`merge_points.py` 有 5 处联动闭环 + 环检测 + 无损校验），
但映射只活在人工维护的 CSV 里（`point_merge.csv` / `point_delete.csv`），**成品包里一个字都不记**。

于是运行时看见的是"这个节点凭空消失了"：
- 学生错题/掌握度/复习队列还引用着它（外键 RESTRICT 挡住真删，删了会静默带走数据）
- 无法把学生数据解析到取代它的新节点上
- 发布后任何一次内容更新都会让安装器整包拒绝

本模块把那两张表的映射**持久化成一份随包发布的台账**，运行时的调和循环据此决定
"墓碑 + 重定向"，而不是"删除"。

## 为什么单独一个文件，而不是写进 pack

pack 的 point 是**精确 7 键白名单**（`pack_io` 顶部注释），加键要同时动 Kotlin codec、
`validate()` 和全套测试。而侧车索引（`moe-2025-teaching-support-v2-index.json`）已经证明
"新文件 + 索引驱动"这条路可行、且不触碰 pack 契约。

## 累积语义（重要）

`retired` 是**只增的账本**，不是某一版的快照：
- 同一 nodeId 重复记录时保留**最早**那条（先发生的事实不改写）
- 链式合并（X→Y 之后 Y→Z）保留 `X→Y` 与 `Y→Z` 两条，**不折叠成 X→Z**——
  历史事实照原样记，解析留到读取侧做传递闭包（并须防环）

## 内容戳

`contentVersion` 是 pack + 全部 sidecar 内容的 sha256。安装器用它做快速路径：
**版本相同就跳过全量比对**。取值算错只会让安装器多跑一次全量比对（慢），
不会让它少更新（错）——这是刻意选的失败方向。

## 用法

    PYTHONPATH=tools python -m kb_build.update_manifest            # 报告现状
    PYTHONPATH=tools python -m kb_build.update_manifest --stamp    # 只更新内容戳
"""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path

from kb_build import pack_io

MANIFEST_NAME = "moe-2025-update-manifest.json"
SCHEMA_VERSION = 1
KIND_MERGE = "MERGE"
KIND_DELETE = "DELETE"


def manifest_path() -> Path:
    return pack_io.KNOWLEDGE_DIR / MANIFEST_NAME


def node_id(pack_id: str, subject: str, slug: str) -> str:
    """与 Kotlin `BundledKnowledgePackResources` 的 pointId 派生逐字一致。"""
    return f"kb:{pack_id}:{subject.lower()}:atomic:{slug}"


def empty(pack_id: str) -> dict:
    return {
        "schemaVersion": SCHEMA_VERSION,
        "packId": pack_id,
        "contentVersion": "",
        "retired": [],
    }


def load(path: Path | None = None) -> dict | None:
    """读台账；不存在返回 None（调用方决定是新建还是报错）。"""
    target = path or manifest_path()
    if not target.exists():
        return None
    with target.open(encoding="utf-8") as fh:
        return json.load(fh)


def load_or_empty(pack_id: str, path: Path | None = None) -> dict:
    return load(path) or empty(pack_id)


def _require_shape(doc: dict) -> None:
    if doc.get("schemaVersion") != SCHEMA_VERSION:
        raise ValueError(f"update manifest schemaVersion 必须是 {SCHEMA_VERSION}")
    if not isinstance(doc.get("retired"), list):
        raise ValueError("update manifest 的 retired 必须是列表")
    for entry in doc["retired"]:
        missing = {"nodeId", "supersededBy", "kind"} - set(entry)
        if missing:
            raise ValueError(f"retired 条目缺字段 {sorted(missing)}：{entry}")
        if entry["kind"] not in (KIND_MERGE, KIND_DELETE):
            raise ValueError(f"未知的退役类型 {entry['kind']}")
        if entry["kind"] == KIND_MERGE and not entry["supersededBy"]:
            raise ValueError(f"MERGE 条目必须有取代目标：{entry}")
        if entry["kind"] == KIND_DELETE and entry["supersededBy"]:
            raise ValueError(f"DELETE 条目不应有取代目标：{entry}")


def record(doc: dict, entries: list[dict]) -> int:
    """把新的退役条目并入台账（就地改），返回**新增**条数。幂等。

    nodeId 已存在时不覆盖——先发生的事实不改写。链式合并因此保留全部中间边。
    """
    _require_shape(doc)
    known = {e["nodeId"] for e in doc["retired"]}
    added = 0
    for entry in entries:
        if entry["nodeId"] in known:
            continue
        doc["retired"].append(dict(entry))
        known.add(entry["nodeId"])
        added += 1
    doc["retired"].sort(key=lambda e: e["nodeId"])
    return added


def _canonical(obj) -> bytes:
    return json.dumps(obj, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()


def content_version(pack: dict, sidecars: list[dict]) -> str:
    """pack + 全部 sidecar 内容的 sha256（取前 16 位十六进制）。

    对**内容**取哈希，不含 `contentVersion` 自身，所以它只随内容变化。
    `sidecars` 按调用方给定顺序传入；调用方须保证顺序稳定（`pack_io.sidecar_paths()` 即可）。
    """
    digest = hashlib.sha256()
    digest.update(_canonical(pack))
    for sidecar in sidecars:
        digest.update(_canonical(sidecar))
    return digest.hexdigest()[:16]


def stamp(pack: dict, sidecars: list[dict], path: Path | None = None) -> str:
    """重算内容戳并写回台账。返回新戳。"""
    doc = load_or_empty(pack["packId"], path)
    doc["contentVersion"] = content_version(pack, sidecars)
    write(doc, path)
    return doc["contentVersion"]


def write(doc: dict, path: Path | None = None) -> Path:
    _require_shape(doc)
    return pack_io.dump_json(doc, path or manifest_path())


def backfill_from_tables(pack: dict, sidecar: Path | None = None) -> list[dict]:
    """从 `point_merge.csv` / `point_delete.csv` 回填台账。

    台账是后加的，而这两张表记录的动作**在台账存在之前就已经执行过**（104 条合并、
    183 条删除）。不回填，运行时对这批节点就仍然只能看到"凭空消失"。

    回填判据是**可证明已执行**：merged/deleted 的 slug 已不在包里。还在包里的行说明尚未
    执行（或被执行器因环检测跳过），**不回填**——避免把没发生的事记成事实。

    导入在函数内做：`merge_points` / `delete_points` 都 import 本模块，模块级反向 import 会成环。
    """
    from kb_build import delete_points, merge_points

    pack_id = pack["packId"]
    present = {(s, p["slug"]) for s, _t, p in pack_io.iter_points(pack)}
    entries: list[dict] = []

    for row in merge_points.load_merges():
        subj = row["subject"].strip()
        merged, survivor = row["merged_slug"].strip(), row["survivor_slug"].strip()
        if (subj, merged) in present:
            continue  # 尚未执行
        entries.append({
            "nodeId": node_id(pack_id, subj, merged),
            "supersededBy": node_id(pack_id, subj, survivor),
            "kind": KIND_MERGE,
            "reason": (row.get("reason") or "").strip(),
        })

    for (subj, slug), (reason, _drop) in delete_points.load_deletes().items():
        if (subj, slug) in present:
            continue  # 尚未执行
        entries.append({
            "nodeId": node_id(pack_id, subj, slug),
            "supersededBy": None,
            "kind": KIND_DELETE,
            "reason": reason,
        })

    return entries


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="取代映射台账")
    parser.add_argument("--stamp", action="store_true", help="重算并写回内容戳（默认只报告）")
    parser.add_argument("--backfill", action="store_true",
                        help="从 point_merge/point_delete 表回填**可证明已执行**的退役条目")
    parser.add_argument("--root", type=Path, default=None)
    args = parser.parse_args(argv)

    if args.root:
        pack_io.use_directory(args.root)

    pack = pack_io.load_json(pack_io.pack_path())
    sidecars = [pack_io.load_json(sp) for sp in pack_io.sidecar_paths()]
    doc = load_or_empty(pack["packId"])
    _require_shape(doc)

    if args.backfill:
        added = record(doc, backfill_from_tables(pack))
        print(f"回填 +{added} 条")

    kinds: dict[str, int] = {}
    for entry in doc["retired"]:
        kinds[entry["kind"]] = kinds.get(entry["kind"], 0) + 1
    print(f"台账 {manifest_path().name}：退役 {len(doc['retired'])} 条（{kinds or '无'}）")

    computed = content_version(pack, sidecars)
    recorded = doc.get("contentVersion") or "（未记录）"
    print(f"内容戳：记录 {recorded} / 实算 {computed}"
          + ("　一致" if recorded == computed else "　**不一致**"))

    if args.stamp or args.backfill:
        doc["contentVersion"] = computed
        write(doc)
        print(f"→ 已写回 {manifest_path()}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
