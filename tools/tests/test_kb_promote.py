# -*- coding: utf-8 -*-
"""单一晋升路径（kb_build.promote）的用例。

它消灭的失败：手术工具直接写成品、门与写盘之间无代码依赖、内容戳被
改内容的工具随手刷成陈旧值（2026-09-21 审计实测记录 742d7487… vs 实算
89415663…）。晋升拓扑 = staging → 门全绿 → 原子落盘成品 + 刷戳/升版本。

夹具是**真实包家族**（52MB，build/ 下临时目录，测试完即删）：合成小 pack
过不了 22 门（章节表/前置表都对着真数据写的），而晋升必须跑真门。
"""

from __future__ import annotations

import json
import shutil
import tempfile
import unittest
from pathlib import Path

from kb_build import pack_io, promote, tables, update_manifest as um


def _family_names() -> list[str]:
    index = pack_io.load_json(pack_io.STAGING_DIR / pack_io.SIDECAR_INDEX_NAME)
    return ([pack_io.PACK_NAME]
            + [n.rsplit("/", 1)[-1] for n in index["sidecars"]]
            + [pack_io.SIDECAR_INDEX_NAME, um.MANIFEST_NAME])


class PromoteTestBase(unittest.TestCase):
    _tmps: list[Path] = []

    @classmethod
    def setUpClass(cls):
        cls.base = Path(tempfile.mkdtemp(prefix="kb-promote-base-",
                                         dir=pack_io.REPO / "build"))
        for name in _family_names():
            shutil.copy2(pack_io.STAGING_DIR / name, cls.base / name)

    @classmethod
    def tearDownClass(cls):
        shutil.rmtree(cls.base, ignore_errors=True)
        for tmp in list(cls._tmps):
            shutil.rmtree(tmp, ignore_errors=True)
        cls._tmps.clear()  # 三个用例类共享同一清单，逐个类清空（顺序执行）

    def _fresh(self, tag: str) -> tuple[Path, Path]:
        """从 base 拷出 (root, release) 两个目录；release 默认与 root 内容相同。"""
        root = self._mkdir(f"kb-promote-{tag}-root-")
        release = self._mkdir(f"kb-promote-{tag}-rel-")
        for name in _family_names():
            shutil.copy2(self.base / name, root / name)
            shutil.copy2(self.base / name, release / name)
        return root, release

    def _mkdir(self, prefix: str) -> Path:
        tmp = Path(tempfile.mkdtemp(prefix=prefix, dir=pack_io.REPO / "build"))
        type(self)._tmps.append(tmp)
        return tmp

    def _bytes(self, directory: Path) -> dict[str, bytes]:
        return {p.name: p.read_bytes() for p in directory.iterdir()}


class PromoteGreenTest(PromoteTestBase):
    def test_green_promote_writes_family_and_refreshes_stamp(self):
        root, release = self._fresh("green")
        before_release = self._bytes(release)
        code, result = promote.promote(root, release)
        self.assertEqual(0, code)
        self.assertTrue(result["ok"])

        # 包家族逐字节等同 staging 候选
        for name in _family_names()[:-1]:  # 台账除外（要升 schema/version/戳）
            self.assertEqual((root / name).read_bytes(), (release / name).read_bytes())

        # 台账：schema 2、version = 旧成品 version + 1、戳 = 实算值
        manifest = json.loads((release / um.MANIFEST_NAME).read_text(encoding="utf-8"))
        self.assertEqual(um.SCHEMA_VERSION, manifest["schemaVersion"])
        self.assertEqual(
            json.loads(before_release[um.MANIFEST_NAME].decode())["version"] + 1,
            manifest["version"],
        )
        r_pack = json.loads((release / pack_io.PACK_NAME).read_text(encoding="utf-8"))
        r_sidecars = [json.loads((release / n).read_text(encoding="utf-8"))
                      for n in _family_names()[1:-2]]
        self.assertEqual(um.content_version(r_pack, r_sidecars),
                         manifest["contentVersion"])

        # 晋升后镜像同步：staging 台账 == 成品台账
        self.assertEqual((root / um.MANIFEST_NAME).read_bytes(),
                         (release / um.MANIFEST_NAME).read_bytes())

    def test_dry_run_writes_nothing(self):
        root, release = self._fresh("dry")
        before = self._bytes(release)
        code, result = promote.promote(root, release, dry_run=True)
        self.assertEqual(0, code)
        self.assertEqual(before, self._bytes(release), "dry-run 不得写盘")

    def test_version_never_decreases_across_promotes(self):
        root, release = self._fresh("mono")
        # 成品 version 被人为抬高到 5：晋升不得回退
        mpath = release / um.MANIFEST_NAME
        doc = json.loads(mpath.read_text(encoding="utf-8"))
        doc["version"] = 5
        mpath.write_text(json.dumps(doc, ensure_ascii=False, indent=1),
                         encoding="utf-8")
        code, _result = promote.promote(root, release)
        self.assertEqual(0, code)
        after = json.loads(mpath.read_text(encoding="utf-8"))
        self.assertEqual(6, after["version"])

    def test_second_promote_increments_version_again(self):
        root, release = self._fresh("twice")
        self.assertEqual(0, promote.promote(root, release)[0])
        first = json.loads((release / um.MANIFEST_NAME).read_text(encoding="utf-8"))["version"]
        # 第二次晋升（内容零变化）也必须 +1——version 数的是晋升次数，不是内容差异
        self.assertEqual(0, promote.promote(root, release)[0])
        second = json.loads((release / um.MANIFEST_NAME).read_text(encoding="utf-8"))["version"]
        self.assertEqual(first + 1, second)


class PromoteRejectTest(PromoteTestBase):
    def _assert_release_untouched(self, release: Path, before: dict[str, bytes]) -> None:
        self.assertEqual(before, self._bytes(release), "被拒晋升不得改成品目录")

    def test_bad_name_rejects_promote(self):
        """故意在 staging 放一个坏名 → 门红 → 拒绝落盘（验收用例）。"""
        root, release = self._fresh("badname")
        before = self._bytes(release)
        ppath = root / pack_io.PACK_NAME
        pack = json.loads(ppath.read_text(encoding="utf-8"))
        point = next(p for s in pack["subjects"] if s["subject"] == "MATH"
                     for t in s["topics"] for p in t.get("knowledgePoints") or [])
        point["name"] = "这是一个故意超过二十四字的整句知识点名称触发坏名门"
        ppath.write_text(json.dumps(pack, ensure_ascii=False, indent=1),
                         encoding="utf-8", newline="\n")
        code, result = promote.promote(root, release)
        self.assertEqual(1, code)
        self.assertFalse(result["ok"])
        self.assertFalse(result["sections"]["gates"]["ok"])
        self.assertTrue(any(f["key"] == "bad_names" for f in result["sections"]["gates"]["failed"]))
        self._assert_release_untouched(release, before)

    def test_roundtrip_failure_rejects_promote(self):
        root, release = self._fresh("roundtrip")
        before = self._bytes(release)
        ppath = root / pack_io.PACK_NAME
        text = ppath.read_text(encoding="utf-8")
        # 插入一行非规范缩进的空白行：JSON 仍合法，但 dump 形态对不上
        text = text.replace('"subjects": [', '"subjects": [\n   ', 1)
        ppath.write_text(text, encoding="utf-8", newline="\n")
        code, result = promote.promote(root, release)
        self.assertEqual(1, code)
        self.assertFalse(result["sections"]["roundtrip"]["ok"])
        self._assert_release_untouched(release, before)

    def test_chain_cycle_rejects_promote(self):
        root, release = self._fresh("cycle")
        before = self._bytes(release)
        mpath = root / um.MANIFEST_NAME
        doc = json.loads(mpath.read_text(encoding="utf-8"))
        doc["retired"].append(
            {"nodeId": "kb:zz:math:atomic:cycle-a",
             "supersededBy": "kb:zz:math:atomic:cycle-b",
             "kind": "MERGE", "reason": "环"})
        doc["retired"].append(
            {"nodeId": "kb:zz:math:atomic:cycle-b",
             "supersededBy": "kb:zz:math:atomic:cycle-a",
             "kind": "MERGE", "reason": "环"})
        doc["retired"].sort(key=lambda e: e["nodeId"])
        mpath.write_text(json.dumps(doc, ensure_ascii=False, indent=1),
                         encoding="utf-8", newline="\n")
        code, result = promote.promote(root, release)
        self.assertEqual(1, code)
        self.assertFalse(result["sections"]["manifest"]["ok"])
        self._assert_release_untouched(release, before)

    def test_promote_refuses_same_directory(self):
        root, _release = self._fresh("same")
        with self.assertRaises(promote.PromoteError):
            promote.promote(root, root)


class PromoteFlattenTest(PromoteTestBase):
    """链式台账在晋升时压平到终局（写入时压平裁定）。"""

    def test_chain_entries_flatten_in_promoted_manifest(self):
        root, release = self._fresh("flatten")
        pack = json.loads((root / pack_io.PACK_NAME).read_text(encoding="utf-8"))
        live = next(p["slug"] for s in pack["subjects"] if s["subject"] == "MATH"
                    for t in s["topics"] for p in t.get("knowledgePoints") or [])
        pack_id = pack["packId"]

        # 表里补两行（幸存者都是 live：动作"已执行"，x8/x9 不在包内）——
        # 与 2026-09-19 无氧呼吸-x2 的双行历史同构（-x1 行 + 根节点行）
        tables_dir = self._mkdir("kb-promote-flatten-tables-")
        shutil.copytree(tables.TABLES_DIR, tables_dir / "tables")
        merged_csv = tables_dir / "tables" / "point_merge.csv"
        with merged_csv.open("a", encoding="utf-8", newline="") as fh:
            fh.write(f"MATH,{live},{live}-x8,晋升压平测试,\n")
            fh.write(f"MATH,{live},{live}-x9,晋升压平测试,\n")

        # 台账里是**未压平**的链：x8→x9、x9→live
        mpath = root / um.MANIFEST_NAME
        doc = json.loads(mpath.read_text(encoding="utf-8"))
        doc["retired"].append(
            {"nodeId": f"kb:{pack_id}:math:atomic:{live}-x8",
             "supersededBy": f"kb:{pack_id}:math:atomic:{live}-x9",
             "kind": "MERGE", "reason": "链"})
        doc["retired"].append(
            {"nodeId": f"kb:{pack_id}:math:atomic:{live}-x9",
             "supersededBy": f"kb:{pack_id}:math:atomic:{live}",
             "kind": "MERGE", "reason": "链"})
        doc["retired"].sort(key=lambda e: e["nodeId"])
        mpath.write_text(json.dumps(doc, ensure_ascii=False, indent=1),
                         encoding="utf-8", newline="\n")

        original_tables_dir = tables.TABLES_DIR
        tables.TABLES_DIR = tables_dir / "tables"
        try:
            code, result = promote.promote(root, release)
        finally:
            tables.TABLES_DIR = original_tables_dir
        self.assertEqual(0, code, result)

        out = json.loads((release / um.MANIFEST_NAME).read_text(encoding="utf-8"))
        targets = {e["nodeId"].split(":")[-1]: e["supersededBy"]
                   for e in out["retired"]}
        # x9 一跳到底 live；x8 沿链（x8→x9→live）压平后直接一跳到底 live
        self.assertEqual(f"kb:{pack_id}:math:atomic:{live}", targets[f"{live}-x9"])
        self.assertEqual(f"kb:{pack_id}:math:atomic:{live}", targets[f"{live}-x8"])
        retired_ids = {e["nodeId"] for e in out["retired"]}
        for e in out["retired"]:
            if e["kind"] == "MERGE":
                self.assertNotIn(e["supersededBy"], retired_ids, "成品台账不得含链跳")


if __name__ == "__main__":
    unittest.main()
