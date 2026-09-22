# -*- coding: utf-8 -*-
"""五张权威表↔包一致性检查的用例。

它消灭的失败（审计 P1：权威分裂在 JSON + 33 张表 + git log 三处）：
表与包分叉时无人发现——chapter_map 曾经"表领先于包"、alias_map 曾经
"一个读 staging 一个读成品，差点用过期快照覆盖成品别名"。

逐表语义（实现见 `check_pack_contract.check_table_consistency`）：
- alias_map：双向集合相等；
- chapter_map：行可定位 + 定位串生效 + 偏离基线必须有账；
- chapter_by_node：行可定位 + split 单元的节点都有行；
- prereq_map：边双向等价（表行在包里、包边在表里）；
- point_*：动作已生效（rename 点名 / delete 缺席 / merge 幸存者 / relocation 落位）
  + 台账方向（delete/merge 的台账条目必须有表行——行被删 = 动作失去溯源）。

验收映射：**任一表删一行 → 检查红**。rename/relocation 是单向账
（包里不留旧名/旧位痕迹，删行不可机械检测），其"红"由"动作未生效"
方向覆盖——下面逐表各有对应用例。
"""

from __future__ import annotations

import copy
import csv
import shutil
import tempfile
import unittest
from pathlib import Path

from kb_build import gen_chapter_table, pack_io, tables, update_manifest
from kb_build import gate as _gate
from kb_build import check_pack_contract as cpc


class ConsistencyTestBase(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        release = pack_io.release_dir()
        cls.pack = pack_io.load_json(release / pack_io.PACK_NAME)
        cls.manifest = pack_io.load_json(release / update_manifest.MANIFEST_NAME)
        cls.points = {(s, p["slug"]): p for s, _t, p in pack_io.iter_points(cls.pack)}

    def _tables_copy(self, tag: str) -> Path:
        tmp = Path(tempfile.mkdtemp(prefix=f"kb-tables-{tag}-", dir=pack_io.REPO / "build"))
        self.addCleanup(shutil.rmtree, tmp, ignore_errors=True)
        shutil.copytree(tables.TABLES_DIR, tmp / "tables")
        return tmp / "tables"

    def _rows(self, directory: Path, name: str) -> list[dict]:
        with (directory / name).open(encoding="utf-8-sig", newline="") as fh:
            return list(csv.DictReader(fh))

    def _write_rows(self, directory: Path, name: str, rows: list[dict]) -> None:
        cols = list(rows[0].keys()) if rows else None
        with (directory / name).open("w", encoding="utf-8", newline="") as fh:
            w = csv.DictWriter(fh, fieldnames=cols, lineterminator="\n")
            w.writeheader()
            w.writerows(rows)

    def _drop_row(self, directory: Path, name: str, row: dict) -> int:
        rows = self._rows(directory, name)
        same = lambda r: all(r[c] == row[c] for c in row)
        kept = [r for r in rows if not same(r)]
        self._write_rows(directory, name, kept)
        return len(rows) - len(kept)


class CurrentStateGreenTest(ConsistencyTestBase):
    """当前仓库状态（2026-09-22 一次性晋升 + 陈行清理后）必须全绿——
    这条用例是"表↔包一致"的回归钉，任何一处分叉都会在这里现形。"""

    def test_current_tables_agree_with_pack(self):
        result = cpc.check_table_consistency(self.pack, self.manifest)
        problems = {k: v for k, v in result.items() if v}
        self.assertEqual({}, problems)


class RowDeletionTurnsRedTest(ConsistencyTestBase):
    """验收：任一表删一行 → 一致性检查红。"""

    def test_dropping_an_alias_row_turns_red(self):
        d = self._tables_copy("alias")
        rows = self._rows(d, "alias_map.csv")
        row = rows[0]
        self._drop_row(d, "alias_map.csv", row)
        result = cpc.check_table_consistency(self.pack, self.manifest, d)
        self.assertTrue(any(row["slug"] in p for p in result["alias_map"]),
                        f"删别名行未报红：{result['alias_map'][:3]}")

    def test_dropping_a_deviating_chapter_map_row_turns_red(self):
        d = self._tables_copy("chapter_map")
        chapter_table = tables.load_chapter_by_source()
        # 找一行"偏离单元基线"的行（删它才能触发"章节偏离无账"）：
        # 行声明的 (volume, chapter) != 该节点无覆盖时应有的基线
        deviating = None
        for r in sorted(self._rows(d, "chapter_map.csv"), key=lambda r: r["slug"]):
            key = (r["subject"].strip(), r["slug"].strip())
            point = self.points.get(key)
            if point is None:
                continue
            unit = gen_chapter_table.source_unit(point.get("sourceLocator", ""))
            if chapter_table.get(unit, {}).get("decision") == "split":
                deviating = r
                break
            baseline = cpc._unit_baseline(point, chapter_table)
            if baseline is not None and (r["volume"].strip(), r["chapter"].strip()) != baseline:
                deviating = r
                break
        self.assertIsNotNone(deviating, "找不到偏离基线的 chapter_map 行（测试前提变了）")
        self._drop_row(d, "chapter_map.csv", deviating)
        result = cpc.check_table_consistency(self.pack, self.manifest, d)
        self.assertTrue(result["chapter_map"], "删偏离行后 chapter_map 未报红")

    def test_dropping_a_split_node_chapter_by_node_row_turns_red(self):
        d = self._tables_copy("chapter_by_node")
        chapter_table = tables.load_chapter_by_source()
        target = None
        for r in sorted(self._rows(d, "chapter_by_node.csv"), key=lambda r: r["slug"]):
            point = self.points.get((r["subject"].strip(), r["slug"].strip()))
            if point is None:
                continue
            unit = gen_chapter_table.source_unit(point.get("sourceLocator", ""))
            if chapter_table.get(unit, {}).get("decision") == "split":
                target = r
                break
        self.assertIsNotNone(target, "找不到 split 单元的 chapter_by_node 行（测试前提变了）")
        self._drop_row(d, "chapter_by_node.csv", target)
        result = cpc.check_table_consistency(self.pack, self.manifest, d)
        self.assertTrue(result["chapter_by_node"], "删 split 节点行后 chapter_by_node 未报红")

    def test_dropping_a_prereq_row_turns_red(self):
        d = self._tables_copy("prereq")
        rows = self._rows(d, "prereq_map.csv")
        row = rows[0]
        self._drop_row(d, "prereq_map.csv", row)
        result = cpc.check_table_consistency(self.pack, self.manifest, d)
        self.assertTrue(any(row["slug"] in p for p in result["prereq_map"]),
                        f"删前置行未报红：{result['prereq_map'][:3]}")

    def test_dropping_a_point_delete_row_turns_red(self):
        d = self._tables_copy("point_delete")
        rows = self._rows(d, "point_delete.csv")
        # 必须有台账对应（否则该行本就"无账"，不构成有效测试）
        row = next(r for r in rows if self._manifest_has_delete(r))
        self._drop_row(d, "point_delete.csv", row)
        result = cpc.check_table_consistency(self.pack, self.manifest, d)
        self.assertTrue(any(row["slug"] in p for p in result["point_delete"]),
                        "删 point_delete 行后未报红")

    def test_dropping_a_point_merge_row_turns_red(self):
        d = self._tables_copy("point_merge")
        rows = self._rows(d, "point_merge.csv")
        row = next(r for r in rows if self._manifest_has_merge(r))
        self._drop_row(d, "point_merge.csv", row)
        result = cpc.check_table_consistency(self.pack, self.manifest, d)
        self.assertTrue(any(row["merged_slug"] in p for p in result["point_merge"]),
                        "删 point_merge 行后未报红")

    def _manifest_has_delete(self, row) -> bool:
        doc = update_manifest.canonicalize(copy.deepcopy(self.manifest))
        subj, slug = row["subject"].strip(), row["slug"].strip()
        want = update_manifest.node_id(self.pack["packId"], subj, slug)
        return any(e["nodeId"] == want and e["kind"] == "DELETE" for e in doc["retired"])

    def _manifest_has_merge(self, row) -> bool:
        doc = update_manifest.canonicalize(copy.deepcopy(self.manifest))
        subj = row["subject"].strip()
        want_node = update_manifest.node_id(self.pack["packId"], subj, row["merged_slug"].strip())
        want_target = update_manifest.node_id(self.pack["packId"], subj, row["survivor_slug"].strip())
        return any(e["nodeId"] == want_node and e["supersededBy"] == want_target
                   for e in doc["retired"])


class EffectNotAppliedTurnsRedTest(ConsistencyTestBase):
    """动作未生效（包侧与表侧对不上）→ 红。用**包侧**合成突变构造。"""

    def test_unapplied_rename_turns_red(self):
        d = self._tables_copy("rename_effect")
        rows = self._rows(d, "point_rename.csv")
        # 取一行已生效的（点名 == new_name），把包的点名改回去 → 未生效
        row = next(r for r in rows
                   if self._pack_name(r["subject"].strip(), r["slug"].strip())
                   == r["new_name"].strip())
        pack = copy.deepcopy(self.pack)
        point = self._find_point(pack, row["subject"].strip(), row["slug"].strip())
        point["name"] = "旧名残迹测试值"
        result = cpc.check_table_consistency(pack, self.manifest, d)
        self.assertTrue(any(row["slug"] in p for p in result["point_rename"]),
                        "改名未生效未被检出")

    def test_unapplied_relocation_turns_red(self):
        d = self._tables_copy("relocate_effect")
        rows = self._rows(d, "point_relocation.csv")
        row = rows[0]
        pack = copy.deepcopy(self.pack)
        point = self._find_point(pack, row["subject"].strip(), row["slug"].strip())
        topic = self._find_topic(pack, row["to_topic_slug"].strip())
        # 把点挪到同科另一个 topic：relocation 的"动作已生效"被破坏
        topic["knowledgePoints"].remove(point)
        other = next(t for t in topic["subject"]["topics"] if t is not topic)
        other.setdefault("knowledgePoints", []).append(point)
        result = cpc.check_table_consistency(pack, self.manifest, d)
        self.assertTrue(any(row["slug"] in p for p in result["point_relocation"]),
                        "归位未生效未被检出")

    def test_stale_rows_are_flagged(self):
        """行引用不存在的节点（删/并后残留）→ 红。"""
        d = self._tables_copy("stale")
        for name in ("chapter_by_node.csv", "point_rename.csv", "point_relocation.csv"):
            rows = self._rows(d, name)
            slug_col = "slug"
            rows.append({c: ("幻影" if c == slug_col else "x") for c in rows[0]})
            self._write_rows(d, name, rows)
        result = cpc.check_table_consistency(self.pack, self.manifest, d)
        self.assertTrue(result["chapter_by_node"])
        self.assertTrue(result["point_rename"])
        self.assertTrue(result["point_relocation"])

    def _pack_name(self, subj: str, slug: str) -> str:
        return self._find_point(self.pack, subj, slug)["name"]

    def _find_point(self, pack: dict, subj: str, slug: str) -> dict:
        for s in pack["subjects"]:
            if s["subject"] != subj:
                continue
            for t in s["topics"]:
                for p in t.get("knowledgePoints") or []:
                    if p["slug"] == slug:
                        return p
        raise LookupError(f"{subj}/{slug} 不在包内")

    def _find_topic(self, pack: dict, topic_slug: str):
        for s in pack["subjects"]:
            for t in s["topics"]:
                if t["slug"] == topic_slug:
                    t["subject"] = s  # 测试内临时挂个引用，便于拿同科 topic 数组
                    return t
        raise LookupError(f"topic {topic_slug} 不在包内")


if __name__ == "__main__":
    unittest.main()
