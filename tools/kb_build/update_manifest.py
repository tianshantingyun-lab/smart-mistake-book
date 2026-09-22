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

## schema 2：写入时压平 + 单调版本（2026-09-22 定案）

schema 1 的累积语义是"链式合并保留全部中间边，解析留到读取侧做传递闭包"。
那正是 MediaWiki 红线"A double redirect does not work"的形态：运行侧一旦漏做闭包，
学生数据就解析到一个**已退役**的节点。schema 2 把压平移到**写入时**：

- `record()` 时把 MERGE 目标沿现有链解析到底再落账——目标本身已退役就跟链到终局
  节点直接记终局；链底是一条 DELETE（没有后继）则该条目降级为 DELETE。
- `write()` 落账前对全账再压平一次（一批条目内部也可能成链）并断言
  **任意 nodeId 一跳到底**：任何条目的 `supersededBy` 指向的节点自身不在 `retired` 里。
- 根键新增**单调整数 `version`**：只在晋升（promote）时 +1。全量内容哈希
  （contentVersion）防不了回滚/冻结（同一内容回退再前进，哈希原样），
  单调版本号让"这比那旧"永远可判。Kotlin 侧
  `ReviewedKnowledgeUpdateManifestJsonCodec` 同步（schema 1 仍可解码，schema 2
  额外要求 version 为非负整数且一跳到底）。

`retired` 仍是**只增的账本**：同一 nodeId 重复记录时保留最早那条（先发生的事实不改写）；
压平改写的是"目标指向哪个终局"，不是"谁退役了"。

## 内容戳

`contentVersion` 是 pack + 全部 sidecar 内容的 sha256。安装器用它做快速路径：
**版本相同就跳过全量比对**。取值算错只会让安装器多跑一次全量比对（慢），
不会让它少更新（错）——这是刻意选的失败方向。

**戳只在晋升时刷新**（原子戳裁定）：手术工具写台账不刷戳，`promote.py` 在门全绿
落盘成品的那一刻重算并写入。本模块不提供刷戳的写入口。

## 用法

    PYTHONPATH=tools python -m kb_build.update_manifest            # 报告现状
    PYTHONPATH=tools python -m kb_build.update_manifest --backfill # 从动作表回填并压平
"""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path

from kb_build import pack_io

MANIFEST_NAME = "moe-2025-update-manifest.json"
LEGACY_SCHEMA_VERSION = 1
SCHEMA_VERSION = 2
KIND_MERGE = "MERGE"
KIND_DELETE = "DELETE"

# 根键集合与 Kotlin codec 的 requireOnlyKeys 一一对应（两边都写死，改一边必须改另一边）。
_SCHEMA1_KEYS = ("schemaVersion", "packId", "contentVersion", "retired")
_SCHEMA2_KEYS = ("schemaVersion", "packId", "version", "contentVersion", "retired")
_ENTRY_KEYS = ("nodeId", "supersededBy", "kind", "reason")


def manifest_path() -> Path:
    return pack_io.work_dir() / MANIFEST_NAME


def node_id(pack_id: str, subject: str, slug: str) -> str:
    """与 Kotlin `BundledKnowledgePackResources` 的 pointId 派生逐字一致。"""
    return f"kb:{pack_id}:{subject.lower()}:atomic:{slug}"


def empty(pack_id: str) -> dict:
    return {
        "schemaVersion": SCHEMA_VERSION,
        "packId": pack_id,
        "version": 0,
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
    sv = doc.get("schemaVersion")
    if sv not in (LEGACY_SCHEMA_VERSION, SCHEMA_VERSION):
        raise ValueError(f"update manifest schemaVersion 必须是 {LEGACY_SCHEMA_VERSION} 或 {SCHEMA_VERSION}")
    allowed = _SCHEMA1_KEYS if sv == LEGACY_SCHEMA_VERSION else _SCHEMA2_KEYS
    if set(doc) != set(allowed):
        raise ValueError(f"update manifest 根键必须恰好是 {list(allowed)}，实际 {sorted(doc)}")
    if sv == SCHEMA_VERSION and (
        not isinstance(doc["version"], int) or isinstance(doc["version"], bool)
        or doc["version"] < 0
    ):
        raise ValueError(f"schema 2 的 version 必须是非负整数：{doc['version']!r}")
    if not isinstance(doc.get("retired"), list):
        raise ValueError("update manifest 的 retired 必须是列表")
    for entry in doc["retired"]:
        if set(entry) != set(_ENTRY_KEYS):
            raise ValueError(f"retired 条目键必须恰好是 {list(_ENTRY_KEYS)}：{entry}")
        if entry["kind"] not in (KIND_MERGE, KIND_DELETE):
            raise ValueError(f"未知的退役类型 {entry['kind']}")
        if entry["kind"] == KIND_MERGE and not entry["supersededBy"]:
            raise ValueError(f"MERGE 条目必须有取代目标：{entry}")
        if entry["kind"] == KIND_DELETE and entry["supersededBy"]:
            raise ValueError(f"DELETE 条目不应有取代目标：{entry}")


def upgrade_to_schema_2(doc: dict) -> bool:
    """schema 1 台账就地升成 schema 2（根键补 version=0 并重排成规范键序）。

    返回是否发生了升级。升版只动根键，不动 retired 内容——
    链的压平由 flatten()/write() 统一负责。
    """
    if doc.get("schemaVersion") == SCHEMA_VERSION:
        return False
    _require_shape(doc)
    upgraded = {
        "schemaVersion": SCHEMA_VERSION,
        "packId": doc["packId"],
        "version": 0,
        "contentVersion": doc["contentVersion"],
        "retired": doc["retired"],
    }
    doc.clear()
    doc.update(upgraded)
    return True


def _resolve_terminal(target: str | None, by_id: dict[str, dict], origin: str) -> str | None:
    """沿现有 MERGE 链把 `target` 解析到终局。

    返回终局节点 id；链底是一条 DELETE（没有后继）时返回 None；成环则报错。
    """
    seen = {origin}
    cur = target
    while cur is not None and cur in by_id:
        entry = by_id[cur]
        if entry["kind"] == KIND_DELETE:
            return None
        if cur in seen:
            raise ValueError(f"退役台账 MERGE 链成环：{origin} → … → {cur}")
        seen.add(cur)
        cur = entry["supersededBy"]
    return cur


def flatten(doc: dict) -> int:
    """把全部 MERGE 条目沿链压平到终局，就地改，返回被改写的条数。

    压平后台账满足**任意 nodeId 一跳到底**：任何条目的 supersededBy
    指向的节点自身不在 retired 里（assert_one_hop 可验）。
    链底是 DELETE 的条目降级为 DELETE——它同样没有唯一后继，
    运行时的处理与直接删除一致（墓碑、不重定向）。
    """
    by_id = {e["nodeId"]: e for e in doc["retired"]}
    changed = 0
    for entry in doc["retired"]:
        if entry["kind"] != KIND_MERGE:
            continue
        terminal = _resolve_terminal(entry["supersededBy"], by_id, entry["nodeId"])
        if terminal is None:
            entry["kind"] = KIND_DELETE
            entry["supersededBy"] = None
            changed += 1
        elif entry["supersededBy"] != terminal:
            entry["supersededBy"] = terminal
            changed += 1
    return changed


def assert_one_hop(doc: dict) -> None:
    """断言"任意 nodeId 一跳到底"：MERGE 目标自身不得在 retired 里。"""
    retired_ids = {e["nodeId"] for e in doc["retired"]}
    for entry in doc["retired"]:
        if entry["kind"] == KIND_MERGE and entry["supersededBy"] in retired_ids:
            raise ValueError(
                f"退役台账违反一跳到底：{entry['nodeId']} → {entry['supersededBy']}"
                f"（目标本身已退役，须先压平）"
            )


def canonicalize(doc: dict) -> dict:
    """就地规范化到晋升形态：schema 2 + 全账压平 + 一跳到底。返回 doc 本身。

    成环或形状违例抛 ValueError。这是 **promote 与一致性检查共用的唯一变换**——
    两处必须同口径，否则会出现"晋升时绿、检查时红"（或反过来）。
    """
    _require_shape(doc)
    upgrade_to_schema_2(doc)
    flatten(doc)
    assert_one_hop(doc)
    return doc


def record(doc: dict, entries: list[dict]) -> int:
    """把新的退役条目并入台账（就地改），返回**新增**条数。幂等。

    nodeId 已存在时不覆盖——先发生的事实不改写。
    MERGE 条目落账前把目标沿现有链解析到底（写入时压平）；
    整账的终局一致性由 write() 的 flatten() 兜底。
    """
    _require_shape(doc)
    if doc["schemaVersion"] == LEGACY_SCHEMA_VERSION:
        upgrade_to_schema_2(doc)
    by_id = {e["nodeId"]: e for e in doc["retired"]}
    added = 0
    for entry in entries:
        if entry["nodeId"] in by_id:
            continue
        e = dict(entry)
        if e["kind"] == KIND_MERGE and e["supersededBy"]:
            terminal = _resolve_terminal(e["supersededBy"], by_id, e["nodeId"])
            if terminal is None:
                e["kind"] = KIND_DELETE
                e["supersededBy"] = None
            else:
                e["supersededBy"] = terminal
        doc["retired"].append(e)
        by_id[e["nodeId"]] = e
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


def write(doc: dict, path: Path | None = None) -> Path:
    """把台账写到工作目录（staging）。落账前：形状校验 → 全账压平 → 一跳到底断言。

    **不重算内容戳**——戳只在晋升路径（promote.py）刷新。
    """
    _require_shape(doc)
    flatten(doc)
    assert_one_hop(doc)
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
    parser.add_argument("--backfill", action="store_true",
                        help="从 point_merge/point_delete 表回填**可证明已执行**的退役条目（不刷戳）")
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
        write(doc)
        print(f"回填 +{added} 条（落账前已压平到终局；内容戳只在晋升时刷新）")

    kinds: dict[str, int] = {}
    for entry in doc["retired"]:
        kinds[entry["kind"]] = kinds.get(entry["kind"], 0) + 1
    print(f"台账 {manifest_path().name}：退役 {len(doc['retired'])} 条（{kinds or '无'}）"
          f"；schema {doc['schemaVersion']}，version {doc.get('version', '—')}")

    computed = content_version(pack, sidecars)
    recorded = doc.get("contentVersion") or "（未记录）"
    print(f"内容戳：记录 {recorded} / 实算 {computed}"
          + ("　一致" if recorded == computed else "　**不一致**（由晋升路径刷新）"))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
