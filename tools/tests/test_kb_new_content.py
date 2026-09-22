# -*- coding: utf-8 -*-
"""新增内容通道（new_points.csv / materials.jsonl）的契约测试。

用合成夹具：真实包 2573 个节点无法逐条断言，这里要证明的是**通道本身**的正确性
——新增点会落到它声明的章节、新增材料会带上来源、越界会拒绝而不是静默丢弃。
真实数据的正确性由 gate 与 round-trip 保证。
"""

from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from kb_build import new_content
from kb_build.build import Builder, InvariantError

PACK_ID = "test-pack-v1"


def _point(slug: str, name: str, locator: str = "某来源") -> dict:
    return {
        "slug": slug,
        "name": name,
        "aliases": [name],
        "kind": "CONCEPT",
        "boundary": "定位：某册 第一章 某章。",
        "sourceLocator": locator,
        "prerequisiteSlugs": [],
    }


def _pack() -> dict:
    return {
        "schemaVersion": 2,
        "packId": PACK_ID,
        "taxonomyVersion": PACK_ID,
        "sourceNamespace": "test",
        "reviewedAtEpochMillis": 1,
        "sourceUri": "https://example.edu/x",
        "coverage": {"baselineId": "b", "catalogLevel": "PARTIAL",
                     "teachingSupportLevel": "PARTIAL"},
        "subjects": [{
            "subject": "CHEMISTRY",
            "sourceFingerprint": "A" * 64,
            "topics": [{
                "slug": "t1",
                "name": "某章",
                "sourceLocator": "某来源",
                "knowledgePoints": [_point("已有节点", "已有节点")],
            }],
        }],
    }


def _material(slug: str, target: str, subject: str = "CHEMISTRY") -> dict:
    return {
        "slug": slug,
        "subject": subject,
        "type": "CONCEPT_EXPLANATION",
        "title": "某材料",
        "summaryMarkdown": "某结论。",
        "applicabilityMarkdown": "某类题。",
        "contentMarkdown": "1. 定义：某定义。",
        "boundaryMarkdown": "等号成立条件必须验证，不成立则最小值达不到。",
        "derivationKind": "REVIEWED_SYNTHESIS",
        "sourceId": new_content.source_id(subject),
        "sourceLocator": "《2027 5·3 A版 高考总复习 化学 精讲册》P1",
        "reviewedAtEpochMillis": 1,
        "bindings": [{"knowledgeNodeId": f"kb:{PACK_ID}:{subject.lower()}:atomic:{target}",
                      "role": "PRIMARY"}],
    }


NEW_BOUNDARY = "等号成立条件必须验证，不成立则最小值达不到。"


def _new_point() -> dict:
    point = _point("新节点", "新节点")
    point["boundary"] = NEW_BOUNDARY
    return point


def _builder(**overrides) -> Builder:
    kwargs = dict(
        pack=_pack(),
        sidecars=[(None, {"schemaVersion": 2, "packId": PACK_ID, "sources": [],
                          "materials": []})],
        chapters={"某来源": {"book": "某册", "chapter": "第一章 某章", "decision": "fix"}},
        aliases={},
        # 边界表的键与新增点一致：生产路径下它由 new_points.csv 的 boundary 列预置，
        # 这里显式传入以保持"边界只有一张权威表"的约定。
        boundaries={("CHEMISTRY", "新节点"): NEW_BOUNDARY},
        prereqs={},
        material_bindings={},
        new_points={("CHEMISTRY", "新节点"): _new_point()},
        new_point_placements={("CHEMISTRY", "新节点"): ("化学必修第一册", "第一章 物质及其变化", "物质的分类")},
        new_materials=[],
    )
    kwargs.update(overrides)
    return Builder(**kwargs)


def _points(builder: Builder) -> dict[str, dict]:
    return {p["slug"]: p for s in builder.pack["subjects"] for t in s["topics"]
            for p in t["knowledgePoints"]}


def _topic_of(builder: Builder, slug: str) -> str:
    for s in builder.pack["subjects"]:
        for t in s["topics"]:
            for p in t["knowledgePoints"]:
                if p["slug"] == slug:
                    return t["name"]
    raise AssertionError(f"未找到节点 {slug}")


class MaterialValidationTest(unittest.TestCase):
    def test_extra_key_is_rejected(self):
        material = _material("m1", "已有节点")
        material["pageImage"] = "x"
        with self.assertRaises(new_content.NewContentError):
            new_content._validate_material(material, 0, {("CHEMISTRY", "已有节点")}, set())

    def test_missing_primary_is_rejected(self):
        material = _material("m1", "已有节点")
        material["bindings"] = []
        with self.assertRaises(new_content.NewContentError):
            new_content._validate_material(material, 0, {("CHEMISTRY", "已有节点")}, set())

    def test_cross_subject_binding_is_rejected(self):
        material = _material("m1", "已有节点", subject="MATH")
        material["bindings"][0]["knowledgeNodeId"] = f"kb:{PACK_ID}:chemistry:atomic:已有节点"
        with self.assertRaises(new_content.NewContentError):
            new_content._validate_material(material, 0, {("CHEMISTRY", "已有节点")}, set())

    def test_worked_example_is_rejected(self):
        """来源政策只允许 REVIEWED_SYNTHESIS，例题类材料不得入库。"""
        material = _material("m1", "已有节点")
        material["type"] = "WORKED_EXAMPLE"
        with self.assertRaises(new_content.NewContentError):
            new_content._validate_material(material, 0, {("CHEMISTRY", "已有节点")}, set())

    def test_too_many_content_lines_is_rejected(self):
        material = _material("m1", "已有节点")
        material["contentMarkdown"] = "\n".join(f"{i}. 第{i}行" for i in range(1, 6))
        with self.assertRaises(new_content.NewContentError):
            new_content._validate_material(material, 0, {("CHEMISTRY", "已有节点")}, set())

    def test_ascii_quote_is_rejected(self):
        material = _material("m1", "已有节点")
        material["boundaryMarkdown"] = '写成 "空集" 优先讨论。'
        with self.assertRaises(new_content.NewContentError):
            new_content._validate_material(material, 0, {("CHEMISTRY", "已有节点")}, set())


class NewPointTest(unittest.TestCase):
    def test_new_point_lands_in_declared_chapter(self):
        builder = _builder()
        builder.build()
        self.assertIn("新节点", _points(builder))
        # 归属来自 new_points 行内的 (册,章,主题)，不是来源单元表
        self.assertEqual("物质的分类", _topic_of(builder, "新节点"))

    def test_new_chapter_gets_book_and_chapter_shells(self):
        """内容落在知识库还没有的章上时，树里必须补出「册」「章」两层空壳。

        五三按人教版编排，而库里不少章根本还没有对应的分组节点（规范的「33 个
        canonical 章缺 L2 章节点」）。没有空壳，新节点就会挂到错误的父下——
        所以这条不是"顺手建的"，是挂树正确性的前提。
        """
        builder = _builder(new_point_placements={
            ("CHEMISTRY", "新节点"): ("化学必修第一册", "第九章 全新的一章", "全新的主题")})
        builder.build()
        topics = builder.pack["subjects"][0]["topics"]
        books = [t for t in topics if t["name"] == "化学必修第一册"]
        self.assertEqual(1, len(books))
        chapters = [t for t in topics if t["name"] == "第九章 全新的一章"]
        self.assertEqual(1, len(chapters))
        self.assertEqual(books[0]["slug"], chapters[0]["parentSlug"])
        self.assertEqual([], chapters[0]["knowledgePoints"])   # 章只作空壳分组

    def test_injected_boundary_is_not_overwritten(self):
        """显式注入的边界不被 Builder 覆盖（加载器才是合成定位前缀的那一层）。"""
        builder = _builder()
        builder.build()
        self.assertEqual(NEW_BOUNDARY, _points(builder)["新节点"]["boundary"])

    def test_new_point_keeps_name_alias(self):
        builder = _builder()
        builder.build()
        self.assertEqual(["新节点"], _points(builder)["新节点"]["aliases"])

    def test_invariants_pass(self):
        builder = _builder()
        builder.build()
        self.assertEqual([], builder.check_invariants())


class NewMaterialTest(unittest.TestCase):
    def test_material_appended_and_source_registered(self):
        builder = _builder(new_materials=[_material("wusan-chem-1", "新节点")])
        builder.build()
        doc = builder.sidecars[0][1]
        self.assertEqual(1, len(doc["materials"]))
        self.assertEqual([new_content.source_id("CHEMISTRY")],
                         [s["sourceId"] for s in doc["sources"]])

    def test_sidecar_overflow_is_refused_not_silently_dropped(self):
        """侧车清单在 Kotlin 侧硬编码；新开文件不会被加载，必须拒绝而不是丢材料。"""
        full = {"schemaVersion": 2, "packId": PACK_ID, "sources": [],
                "materials": [_material(f"m{i}", "已有节点") for i in range(2048)]}
        builder = _builder(sidecars=[(None, full)],
                           new_materials=[_material("wusan-chem-1", "新节点")])
        with self.assertRaises(InvariantError):
            builder.build()


class NewPointsTableTest(unittest.TestCase):
    """直接跑 new_points.csv 的解析路径。

    此前这条路径只被 build 端到端覆盖，规则一改就没有能失败的用例；而它是
    「作者写什么 → 包里长什么样」唯一的接缝。
    """

    HEADER = "subject,slug,name,kind,boundary,volume,chapter,theme,source_locator\n"

    def _load(self, body: str):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "new_points.csv"
            path.write_text(self.HEADER + body, encoding="utf-8")
            return new_content.load_new_points(existing={"CHEMISTRY": set()}, path=path)

    def _row(self, **overrides) -> str:
        cells = {"subject": "CHEMISTRY", "slug": "新节点", "name": "新节点",
                 "kind": "CONCEPT", "boundary": NEW_BOUNDARY,
                 "volume": "化学必修第一册", "chapter": "第一章 物质及其变化",
                 "theme": "物质的分类", "source_locator": "人教版高中教材（2019）"}
        cells.update(overrides)
        return ",".join(cells.values()) + "\n"

    def test_authored_boundary_is_prefixed_with_its_placard(self):
        """册/章在成品包里就靠 boundary 的 `定位：` 前缀承载（gate 从它取归属），
        作者只写真边界，前缀由加载器合成——否则节点会变成「归属缺失」。"""
        points, placements, boundaries = self._load(self._row())
        expected = f"定位：化学必修第一册 第一章 物质及其变化。{NEW_BOUNDARY}"
        self.assertEqual(expected, points[("CHEMISTRY", "新节点")]["boundary"])
        self.assertEqual(expected, boundaries[("CHEMISTRY", "新节点")])
        self.assertEqual(("化学必修第一册", "第一章 物质及其变化", "物质的分类"),
                         placements[("CHEMISTRY", "新节点")])

    def test_authored_boundary_may_not_itself_be_a_locator(self):
        """作者把定位串当边界填进来时必须拒绝——那等于没写边界。"""
        with self.assertRaises(new_content.NewContentError):
            self._load(self._row(boundary="定位：化学必修第一册 第一章 物质及其变化。"))


class SourceRegisterTest(unittest.TestCase):
    """来源元数据的权威是候选资料登记表，生成期固化的值必须与它一致。

    侧车里其余 27 个来源 id 的规律是「登记表 id 把 `:` 换成 `-`，再加 `:<科目小写>`」；
    上一轮自造的 `wusan-2027-a-version-jingjiang-chemistry:chemistry` 不合这条规律，
    没有登记条目能对上。
    """

    REGISTER = Path("knowledge-research/sources/source-register-candidates.json")

    @classmethod
    def setUpClass(cls):
        from kb_build import pack_io
        cls.entries = {}
        path = pack_io.REPO / cls.REGISTER
        if path.exists():
            for entry in json.loads(path.read_text(encoding="utf-8"))["sources"]:
                cls.entries[entry["sourceId"]] = entry

    def test_source_id_derives_from_the_register_entry(self):
        for subject in new_content.SUBJECT_CN:
            with self.subTest(subject=subject):
                registered = f"registry:wusan:2027:a-version-jingjiang:{subject.lower()}"
                self.assertEqual(registered.replace(":", "-") + f":{subject.lower()}",
                                 new_content.source_id(subject))

    def test_title_and_publisher_match_the_register(self):
        if not self.entries:
            self.skipTest("候选资料登记表不在本机")
        for subject, cn in new_content.SUBJECT_CN.items():
            entry = self.entries.get(f"registry:wusan:2027:a-version-jingjiang:{subject.lower()}")
            if entry is None:
                self.skipTest(f"登记表里没有 {subject} 的五三条目")
            source = new_content.make_source(subject)
            with self.subTest(subject=subject):
                self.assertEqual(entry["title"], source["title"])
                self.assertEqual(entry["publisher"], source["publisher"])

    def test_fingerprint_tracks_the_contributed_material_text(self):
        """`contentFingerprint` 得真的随内容变，否则这个字段只是装饰。"""
        base = [{"slug": "a", "contentMarkdown": "1. 定义：甲。"}]
        changed = [{"slug": "a", "contentMarkdown": "1. 定义：乙。"}]
        self.assertNotEqual(new_content.make_source("CHEMISTRY", base)["contentFingerprint"],
                            new_content.make_source("CHEMISTRY", changed)["contentFingerprint"])
        self.assertEqual(64, len(new_content.make_source("CHEMISTRY", base)["contentFingerprint"]))


if __name__ == "__main__":
    unittest.main()
