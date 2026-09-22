# -*- coding: utf-8 -*-
"""按权威表生成新知识包。默认只写 staging，不覆盖成品。

稳字优先的设计：
- 输出到 build/kb-staging/；写成品目录 core/data/src/main/resources/knowledge/
  的唯一通道是 kb_build.promote（22 门 + 表↔包一致性 + roundtrip 全绿才落盘）。
  成品包被 App 启动时按"逐字段相等"校验，写坏会让老安装直接启动失败，所以不允许误覆盖。
- 生成前先验不变量（见 check_invariants），任何一条不过就拒绝生成。
- 生成后再验一遍，并打印与现行成品的差异摘要。

节点增删改不由本生成器执行：原权威输入 `node_actions.csv` 已作废（C-09：与成品包
不同坐标系），节点的改名/删除/合并走五张权威动作表 + 幂等手术工具直接改成品
（见 `tables/node_actions.README.md`）。本生成器只施加章节/别名/边界/前置表与
新增内容，且整体**停用**——它不是成品的生成器，只是历史拓扑。
"""

from __future__ import annotations

import argparse
import re
from collections import Counter
from pathlib import Path

from kb_build import new_content, pack_io, tables

STAGING = pack_io.STAGING_DIR

# 生成物必须满足的键集合（来自 ReviewedKnowledgePackJsonCodec）
POINT_KEYS = ("slug", "name", "aliases", "kind", "boundary", "sourceLocator", "prerequisiteSlugs")

# 每个侧车文件的材料上限（DB 导入契约的单批上限，codec 在解码侧强制）。
MAX_MATERIALS_PER_SIDECAR = 2048


class InvariantError(Exception):
    pass


def _node_id(pack_id: str, subject: str, slug: str) -> str:
    return f"kb:{pack_id}:{subject.lower()}:atomic:{slug}"


class Builder:
    """把权威表施加到知识包上。

    pack/sidecars 可注入，便于用合成夹具做单测（真实数据 2573 个节点，
    不适合逐条构造断言）。
    """

    def __init__(self, pack=None, sidecars=None, *, chapters=None,
                 aliases=None, boundaries=None, prereqs=None,
                 new_points=None, new_point_placements=None, new_materials=None,
                 material_bindings=None):
        self.pack = pack if pack is not None else pack_io.load_json(pack_io.pack_path())
        self.sidecars = (sidecars if sidecars is not None
                         else [(p, pack_io.load_json(p)) for p in pack_io.sidecar_paths()])
        self.chapters = chapters if chapters is not None else tables.load_chapter_by_source()
        self.chapters_by_node = tables.load_chapter_map()
        self.aliases = aliases if aliases is not None else tables.load_alias_map()
        self.boundaries = boundaries if boundaries is not None else tables.load_boundary_map()
        self.prereqs = prereqs if prereqs is not None else tables.load_prereq_map()
        self.material_bindings = (material_bindings if material_bindings is not None
                                  else tables.load_material_bindings())
        # 新增内容：None = 从授权表现读；传 [] 表示"本次不加"（测试用）。
        if new_points is None or new_point_placements is None:
            loaded_points, placements, new_boundaries = new_content.load_new_points()
            self.new_points = new_points if new_points is not None else loaded_points
            self.new_point_placements = (new_point_placements if new_point_placements is not None
                                         else placements)
            # 新增点的边界随行给出，先并入边界表；boundary_map.csv 若另有行则以后者为准
            for key, value in new_boundaries.items():
                self.boundaries.setdefault(key, value)
        else:
            self.new_points = new_points
            self.new_point_placements = new_point_placements
        self.new_materials = (new_content.load_new_materials(known_slugs=self._post_build_slugs())
                              if new_materials is None else new_materials)
        self.notes: list[str] = []

    # ---------- 节点层 ----------

    def _post_build_slugs(self) -> set[tuple[str, str]]:
        """本次生成**之后**仍存在的节点集，用作材料绑定的校验目标。

        不能拿成品包现读就完事：本次新增的点还不存在于成品里，
        校验集必须加上它们，否则新点绑材料会被误判成悬空。
        """
        surviving = {(subject, point["slug"])
                     for subject, _topic, point in pack_io.iter_points(self.pack)}
        surviving |= set(self.new_points)
        return surviving

    def _apply_alias_and_boundary(self) -> None:
        """按表覆盖别名与边界；未定稿的部分走安全默认值。

        别名：表里没有就清空。现有 847 条别名来自早期绑定快照，会污染检索；
        空别名只是少一些召回，污染会召回错节点。
        边界：表里没有就只留定位、剥掉**定位串之外的全部内容**（`has_unvetted_content`）。
        这里刻意不换成 `has_verbatim_excerpt` 的精确判据：剥离是**整段替换**，而被判
        "疑似原文"的条目里有相当一部分是"知识结论 + 典例"的拼接体，改用精确判据会在
        同一条里留下题号选项（权利问题），或反过来继续删掉真知识。放行第三方原文比
        删掉真知识严重得多，所以在**按段过滤**实现之前，这侧保持保守。
        真正的边界待依正确的材料绑定后撰写。
        """
        from kb_build import textfix
        missing_boundary = stripped = 0
        for subject in self.pack["subjects"]:
            for topic in subject["topics"]:
                for point in topic.get("knowledgePoints") or []:
                    key = (subject["subject"], point["slug"])
                    point["aliases"] = self.aliases.get(key, [])
                    if key in self.boundaries:
                        point["boundary"] = self.boundaries[key]
                        continue
                    original = point.get("boundary") or ""
                    if textfix.has_unvetted_content(original):
                        point["boundary"] = textfix.strip_boundary_excerpt(original)
                        stripped += 1
                    missing_boundary += 1
        if stripped:
            self.notes.append(f"边界剥离未核验正文（只留定位）{stripped} 条")
        if missing_boundary:
            self.notes.append(f"边界待定稿 {missing_boundary} 条（当前只留教材定位）")

    def _apply_prereq(self) -> None:
        for subject in self.pack["subjects"]:
            slugs = {p["slug"] for t in subject["topics"]
                     for p in t.get("knowledgePoints") or []}
            for topic in subject["topics"]:
                for point in topic.get("knowledgePoints") or []:
                    declared = self.prereqs.get(point["slug"], {}).get("prerequisites", [])
                    for prereq in declared:
                        if prereq not in slugs:
                            raise InvariantError(
                                f"{point['slug']} 的前置 {prereq} 不在同科节点集里")
                    point["prerequisiteSlugs"] = list(declared)

    # ---------- 新增内容（五三炼化） ----------

    def _apply_new_points(self) -> None:
        """把 new_points.csv 的原子点放进包里，并把归属并入节点级章节表。

        新增点必须自带显式 (册,章,主题)——放进去之后 `_rebuild_topics` 会像对待
        既有节点那样按章节表重建层级，因此新增内容不需要第二套挂树逻辑；
        边界同理，先并入边界表再由 `_apply_alias_and_boundary` 统一落盘。
        """
        if not self.new_points:
            return
        for key, placement in self.new_point_placements.items():
            self.chapters_by_node.setdefault(key, placement)

        added = 0
        for (subject_name, slug), point in self.new_points.items():
            subject = next((s for s in self.pack["subjects"] if s["subject"] == subject_name), None)
            if subject is None:
                raise InvariantError(f"新增知识点 {subject_name} 不是包里的科目")
            # 名称本身就是最有效的召回别名：预置进别名表，免得新增点建好后
            # 只能靠 name 精确命中（检索对别名有权重）。
            self.aliases.setdefault((subject_name, slug), [point["name"]])
            # 容器只为了让点进入 _rebuild_topics 的遍历；topic 层级随后整体重建，
            # 这个壳不会被保留（既有主题的挂载位置在重建时一律重算）。
            if not subject["topics"]:
                subject["topics"].append({
                    "slug": f"{subject_name.lower()}-incoming",
                    "name": "（新增待归类）",
                    "sourceLocator": "定位：新增待归类。",
                    "knowledgePoints": [],
                })
            subject["topics"][0]["knowledgePoints"].append(point)
            added += 1
        self.notes.append(f"新增知识点 {added} 个（归属与边界均来自 new_points.csv）")

    def _apply_new_materials(self) -> None:
        """把 materials.jsonl 的材料追加进侧车，并登记其来源。

        四科包的侧车清单在 Kotlin 侧由索引文件给出（BundledKnowledgePackResources
        读 moe-2025-teaching-support-v2-index.json；只有 2020 样例包仍走硬编码的
        teachingSidecarsByPack），pack_io.sidecar_paths 读的是同一份索引。
        因此这里宁可拒绝也不新开侧车文件——开新卷必须"写新文件 + 更新索引"一起做，
        漏了索引更新，App 不会加载新卷，材料会静默消失，而静默消失比生成失败难发现得多。
        """
        if not self.new_materials:
            return
        from kb_build import new_content as nc

        cap = MAX_MATERIALS_PER_SIDECAR
        attached = 0
        subjects_seen: set[str] = set()
        for material in self.new_materials:
            subjects_seen.add(material["subject"])
            for _path, doc in self.sidecars:
                if len(doc["materials"]) < cap:
                    doc["materials"].append(material)
                    attached += 1
                    break
            else:
                raise InvariantError(
                    f"侧车已满（{len(self.sidecars)} × {cap}）；新开侧车文件必须同时更新侧车索引"
                    f"（moe-2025-teaching-support-v2-index.json），本生成器不代劳。"
                )
        # 来源按 codec 要求与材料同侧车登记，且跨侧车共享同一 id；
        # 指纹按**该来源实际贡献的材料正文**算，内容一改指纹就变。
        registered: set[str] = set()
        for subject in sorted(subjects_seen):
            contributed = [m for m in self.new_materials if m["subject"] == subject]
            source = nc.make_source(subject, contributed)
            registered.add(source["sourceId"])
            for _path, doc in self.sidecars:
                if any(m["sourceId"] == source["sourceId"] for m in doc["materials"]):
                    if not any(s["sourceId"] == source["sourceId"] for s in doc["sources"]):
                        doc["sources"].append(source)
        self.notes.append(f"新增材料 {attached} 条；登记来源 {len(registered)} 个")

    def _apply_material_bindings(self) -> None:
        """按 `material_bindings.csv` 给已有材料补绑定。

        为什么需要：抽取阶段产出的材料里有一批从未绑上（或绑到被判为抽取事故的
        节点上、随节点删除而被解除）。它们进不了库不是因为内容差，而是因为缺一条
        绑定——`BundledKnowledgePackResources` 会把无绑定材料整条剔除，讲题时
        检索不到。这张表是它们唯一的修正通道。

        校验目标是**生成后**仍存在的节点集：绑到待删/待并的节点上会随节点一起
        消失，等于白写。
        """
        if not self.material_bindings:
            return
        known = self._post_build_slugs()
        pack_id = self.pack["packId"]
        by_slug: dict[str, list[dict]] = {}
        for _path, doc in self.sidecars:
            for material in doc["materials"]:
                by_slug.setdefault(material["slug"], []).append(material)
        applied = detached = 0
        for material_slug, point_slug in self.material_bindings.items():
            materials = by_slug.get(material_slug)
            if not materials:
                raise InvariantError(f"material_bindings.csv: 找不到材料 {material_slug!r}")
            for material in materials:
                subject = material["subject"]
                if not point_slug:
                    # 空目标 = 解绑。逐条判过、现绑节点与材料讲的不是一回事、又找不到
                    # 正确归属的，退回"不导入"：讲题时拿出一条不相关的材料当依据，
                    # 比检索不到更糟，而且用户无从发现。
                    material["bindings"] = []
                    detached += 1
                    continue
                if (subject, point_slug) not in known:
                    raise InvariantError(
                        f"material_bindings.csv: {material_slug!r} 的目标节点 "
                        f"{subject}/{point_slug!r} 在生成后不存在")
                material["bindings"] = [{
                    "knowledgeNodeId": _node_id(pack_id, subject, point_slug),
                    "role": "PRIMARY",
                }]
                applied += 1
        self.notes.append(f"按绑定表修正 {applied} 条材料、解绑 {detached} 条")

    # ---------- 章节层 ----------

    def _canonical_chapters(self) -> dict[str, dict[str, str]]:
        """册 -> {章号或章名: 规范章名}，用于把教材来源节点的旧简写章名归到规范名。

        旧定位串里的章名常是简写（`第三章·相互作用`），而教材规范名是
        `第三章 相互作用——力`；不归一化会让同一章在树里出现两种写法。
        优先按章号匹配（同册内章号唯一），无章号时按名称包含匹配。
        """
        by_number: dict[str, dict[str, str]] = {}
        by_name: dict[str, dict[str, str]] = {}
        pairs: set[tuple[str, str]] = set()
        for entry in self.chapters.values():          # 单元级表：{book, chapter, decision}
            if entry.get("book") and entry.get("chapter"):
                pairs.add((entry["book"].strip(), entry["chapter"].strip()))
        for volume, chapter, _theme in self.chapters_by_node.values():   # 节点级表：(册, 章, 主题)
            if volume and chapter:
                pairs.add((volume.strip(), chapter.strip()))
        for (book, chapter) in pairs:
            match = re.match(r"^(第[一二三四五六七八九十百\d]+章)\s*(.*)$", chapter)
            if match:
                by_number.setdefault(book, {})[match.group(1)] = chapter
                by_name.setdefault(book, {})[match.group(2)] = chapter
            else:
                by_name.setdefault(book, {})[chapter] = chapter
        return {"number": by_number, "name": by_name}

    @staticmethod
    def _normalize_chapter(book: str, chapter: str, canon: dict) -> str:
        number_map = canon["number"].get(book, {})
        name_map = canon["name"].get(book, {})
        match = re.match(r"^(第[一二三四五六七八九十百\d]+章)\s*(.*)$", chapter)
        if match and match.group(1) in number_map:
            return number_map[match.group(1)]
        for old, canonical in name_map.items():
            if old and (old == chapter or old in chapter or chapter in old):
                return canonical
        return chapter

    def _rebuild_topics(self) -> None:
        """按章节表重建 册 → 章 → 主题 层级，并把 boundary 的定位改成一致的写法。

        为什么必须重建而不是原地打补丁：现有归属来自关键词硬映射（kb_tools/
        kw_chapter_map），已证实把圆周运动/抛体/磁场挂到"运动的描述"下。定位串与
        章节归属必须是同一个事实，所以定位由章节表推导，不再是它的输入。

        主题（第三层）沿用节点原 boundary 里的第三段——它记的是教辅专题/教材主题，
        内容是对的，错的只是前面的册与章。
        """
        from kb_build import gen_chapter_table
        chapter_table = self.chapters
        if not chapter_table:
            self.notes.append("章节表为空：保持原章节归属")
            return

        uncovered: set[str] = set()
        moved = kept_per_node = 0
        canon = self._canonical_chapters()
        # (册, 章, 主题) -> [point]
        grouped: dict[tuple[str, str, str], list] = {}
        subject_of_book: dict[tuple[str, str], str] = {}
        for subject in self.pack["subjects"]:
            subject_name = subject["subject"]
            new_topics: list[dict] = []
            for topic in subject["topics"]:
                for point in topic.get("knowledgePoints") or []:
                    unit = gen_chapter_table.source_unit(point.get("sourceLocator", ""))
                    entry = chapter_table.get(unit)
                    theme = gen_chapter_table.theme_of_unit(unit)
                    # 节点级覆盖优先：一个教辅专题可能跨教材的两章
                    # （如《专题01 匀变速直线运动及其规律》跨"运动的描述"与"匀变速"两章）
                    node_override = self.chapters_by_node.get((subject_name, point["slug"]))
                    if node_override:
                        book, chapter = node_override[0], node_override[1]
                        # 授权主题优先于来源单元派生：chapter_map 的 theme 是逐节点
                        # 读上下文定的（`质点与参考系`/`速度与平均速度`），而派生值
                        # 只到教辅专题那一层（`匀变速直线运动及其规律`）。实测 68 个
                        # 覆盖节点的两值全不一致，一律取授权值——否则这些节点会被
                        # 并进一个粗分组，等于覆盖白写。
                        if node_override[2]:
                            theme = node_override[2]
                        moved += 1
                    elif entry is None:
                        uncovered.add(unit)
                        book, chapter = self._current_place(point)
                        chapter = self._normalize_chapter(book, chapter, canon)
                    elif entry["decision"] == "keep_per_node":
                        book, chapter = self._current_place(point)
                        # 旧定位串的章名常是简写（`第三章·相互作用`），归到章表的规范名
                        chapter = self._normalize_chapter(book, chapter, canon)
                        # 教材来源的节点自带节名；但老节名也有错（`牛顿第二定律` 挂在
                        # "3 牛顿第三定律"下），所以只认"像节名"的：含编号或长度适中，
                        # 且不是来源文档名。不可信就留空——节点直接挂到章下，
                        # 少一层分组好过挂一层错分组。
                        section = self._current_section(point)
                        theme = section if self._section_matches(point, section) else ""
                        kept_per_node += 1
                    else:
                        book, chapter = entry["book"], entry["chapter"]
                        moved += 1
                    # 定位串由章节表推导，不再是它的输入。但**已定稿的边界**
                    # （boundary_map / new_points 授权）不能被定位串覆盖——
                    # 否则 real boundary 会被这一步静默吃掉。
                    if (subject_name, point["slug"]) not in self.boundaries:
                        point["boundary"] = f"定位：{book} {chapter}。"
                    if theme and theme == chapter:
                        theme = ""      # 主题与章同名时不再单设一层，避免"第五章/第五章"
                    grouped.setdefault((book, chapter, theme), []).append(
                        (subject_name, topic, point))
            subject["topics"] = []

        # 组装新的 topic 层级：册 -> 章 -> 主题
        for subject in self.pack["subjects"]:
            subject_name = subject["subject"]
            buckets = [(key, pts) for key, pts in grouped.items()
                       if all(s == subject_name for s, _t, _p in pts)]
            if not buckets:
                # 该科没有节点会触发 codec 的"每科至少一个知识点"校验，保留一个空主题占位
                subject["topics"] = [{
                    "slug": f"{subject_name.lower()}-empty",
                    "name": "（暂未归类）",
                    "sourceLocator": "定位：未归类。",
                    "knowledgePoints": [],
                }]
                continue
            # 先建册与章的中间节点，再挂主题
            book_slugs: dict[str, str] = {}
            chapter_slugs: dict[tuple[str, str], str] = {}
            topics_out: list[dict] = []
            for (book, chapter, _theme), _pts in sorted(buckets):
                if book not in book_slugs:
                    slug = self._unique_slug(topics_out, book, subject_name)
                    book_slugs[book] = slug
                    topics_out.append({
                        "slug": slug, "name": book,
                        "sourceLocator": f"定位：{book}。",
                        "knowledgePoints": [],
                    })
                if (book, chapter) not in chapter_slugs:
                    slug = self._unique_slug(topics_out, f"{book}·{chapter}", subject_name)
                    chapter_slugs[(book, chapter)] = slug
                    topics_out.append({
                        "slug": slug, "name": chapter,
                        "sourceLocator": f"定位：{book} {chapter}。",
                        "parentSlug": book_slugs[book],
                        "knowledgePoints": [],
                    })
            for (book, chapter, theme), pts in sorted(buckets):
                parent = chapter_slugs[(book, chapter)]
                if not theme:
                    # 没有可信的主题就不要再包一层：否则会造出名字等于章名的冗余层
                    for subject_name, _t, point in pts:
                        for topic in topics_out:
                            if topic["slug"] == parent:
                                topic["knowledgePoints"].append(point)
                    continue
                slug = self._unique_slug(topics_out, f"{book}·{chapter}·{theme}", subject_name)
                topics_out.append({
                    "slug": slug,
                    "name": theme,
                    "sourceLocator": f"定位：{book} {chapter}。",
                    "parentSlug": parent,
                    "knowledgePoints": [p for _s, _t, p in pts],
                })
            subject["topics"] = topics_out

        self.notes.append(
            f"章节重建：按章表归位 {moved} 个节点，教材来源沿用逐节点归属 {kept_per_node} 个"
        )
        if uncovered:
            self.notes.append(f"章节表未覆盖的来源单元 {len(uncovered)} 个（保持原归属）")

    @staticmethod
    def _current_place(point: dict) -> tuple[str, str]:
        """从原 boundary 的定位串取（册, 章），章名要带上章号。

        老定位串有两种写法：
          `数学必修第一册 第三章·函数的概念与性质。`      （册 章号·章名）
          `化学选择性必修2·物质结构与性质·分子晶体与共价晶体。`（册·章名·节名）
        统一按 `·` 切段后：段0 含章号则与段1 合并成 `第三章 函数的概念与性质`，
        否则段1 就是章名。**章号必须保留**——丢掉它会让同一章在树里出现两种名字
        （`函数的概念与性质` 与 `第三章 函数的概念与性质`），UI 分组与父名回填都会错。
        """
        match = re.match(r"^定位：(?P<body>[^。]*)", point.get("boundary") or "")
        if not match:
            return "未归类", "未归类"
        body = match.group("body").replace("（见知识清单/教材）", "").strip()
        segments = [seg.strip() for seg in body.split("·") if seg.strip()]
        if not segments:
            return "未归类", "未归类"
        book, _, number = segments[0].partition(" ")
        if not number:
            book, number = segments[0], ""
        rest = segments[1] if len(segments) > 1 else ""
        if number and rest:
            chapter = f"{number} {rest}"
        else:
            chapter = rest or number
        return (book or "未归类"), (chapter or "未归类")

    @staticmethod
    def _current_section(point: dict) -> str:
        """从原 boundary 取节名。老格式 `册 章号·章名·节名`：第三节及其后都是节。"""
        match = re.match(r"^定位：[^。]*", point.get("boundary") or "")
        if not match:
            return ""
        segments = match.group(0).replace("定位：", "").split("·")
        return "·".join(segments[2:]).strip()[:60]

    @staticmethod
    def _looks_like_section(text: str) -> bool:
        """节名应当像教材小节名：带编号或长度适中，且不是来源文档名。"""
        if not text or len(text) < 2 or len(text) > 24:
            return False
        if re.match(r"^\d+(\s|　|$)", text):        # `4 自由落体运动`
            return True
        return bool(re.search(r"实验|模型|图像|规律|应用", text))

    @classmethod
    def _section_matches(cls, point: dict, section: str) -> bool:
        """节名要能当这个节点的父分组。

        判据是"互为子串"或"实验/综合类"：字符重合度在这里不适用——
        `牛顿第二定律` 与 `3 牛顿第三定律` 只差一个字，重合度高达 5/6，
        但那正是老树里要修的错挂。
        """
        if not cls._looks_like_section(section):
            return False
        core = re.sub(r"^\d+(\s|　)*", "", section).strip()
        if re.search(r"实验|综合|复习|专题", core):
            return True
        name = point.get("name", "")
        return bool(core) and (core in name or name in core)

    @staticmethod
    def _unique_slug(topics_out: list[dict], base: str, subject_name: str) -> str:
        """topic slug 只需科内唯一；用册章名派生并去重。"""
        taken = {t["slug"] for t in topics_out}
        slug = re.sub(r"\s+", "", base)[:80] or f"{subject_name.lower()}-topic"
        candidate, index = slug, 2
        while candidate in taken:
            candidate = f"{slug}-{index}"
            index += 1
        return candidate

    def _repair_material_text(self) -> None:
        """修材料的 LaTeX 命令损坏与控制字符残迹。

        这两项都是上游生成时把转义字符当不可打印字符处理造成的（见 textfix 说明），
        修复是确定性的，因此放在生成器里而不是留给人工。
        """
        from kb_build import gate, textfix
        commands = gate._REAL_LATEX_COMMANDS
        fields = ("title", "summaryMarkdown", "applicabilityMarkdown",
                  "contentMarkdown", "boundaryMarkdown")
        repaired = cleaned = 0
        for _path, doc in self.sidecars:
            for material in doc["materials"]:
                touched = False
                for field in fields:
                    original = material.get(field) or ""
                    fixed = textfix.strip_control_junk(
                        textfix.repair_latex_commands(original, commands)
                    )
                    if fixed != original:
                        material[field] = fixed
                        touched = True
                if touched:
                    repaired += 1
                if any(any(ch in (material.get(f) or "") for ch in textfix._CONTROL_JUNK)
                       for f in fields):
                    cleaned += 1
        self.notes.append(f"材料文本修复 {repaired} 条；仍含控制字符 {cleaned} 条")

    # ---------- 校验 ----------

    def check_invariants(self) -> list[str]:
        errors: list[str] = []
        seen: dict[tuple[str, str], str] = {}
        for subject in self.pack["subjects"]:
            subject_name = subject["subject"]
            topic_slugs = [t["slug"] for t in subject["topics"]]
            if len(topic_slugs) != len(set(topic_slugs)):
                errors.append(f"{subject_name}: topic slug 重复")
            slugs: list[str] = []
            for topic in subject["topics"]:
                if set(topic) - {"slug", "name", "sourceLocator", "parentSlug", "knowledgePoints"}:
                    errors.append(f"{subject_name}/{topic['slug']}: topic 含未知键")
                for point in topic.get("knowledgePoints") or []:
                    if tuple(point) != POINT_KEYS:
                        errors.append(f"{subject_name}/{point.get('slug')}: 键集合或顺序不符 {tuple(point)}")
                    slugs.append(point["slug"])
                    seen[(subject_name, point["slug"])] = point["name"]
            if len(slugs) != len(set(slugs)):
                dupes = [s for s, c in Counter(slugs).items() if c > 1]
                errors.append(f"{subject_name}: 知识点 slug 重复 {dupes[:3]}")
            slug_set = set(slugs)
            # 同科内不得有同名知识点：改名与合并都可能新造重名，必须在这里拦住，
            # 否则会带着"同一概念两个节点"进包（掌握度会显示成两行）。
            by_canon: dict[str, list[str]] = {}
            for topic in subject["topics"]:
                for point in topic.get("knowledgePoints") or []:
                    canon = re.sub(r"[★☆]+", "", point["name"]).rstrip("．。，、 ")
                    by_canon.setdefault(canon, []).append(point["slug"])
            for canon, dup_slugs in by_canon.items():
                if len(dup_slugs) > 1:
                    errors.append(
                        f"{subject_name}: 同名知识点「{canon}」{dup_slugs[:3]}"
                    )
            for topic in subject["topics"]:
                for point in topic.get("knowledgePoints") or []:
                    for prereq in point["prerequisiteSlugs"]:
                        if prereq not in slug_set:
                            errors.append(f"{subject_name}/{point['slug']}: 前置 {prereq} 悬空")
                    if not point["name"].strip():
                        errors.append(f"{subject_name}/{point['slug']}: 名称为空")
                    if not point["boundary"].strip():
                        errors.append(f"{subject_name}/{point['slug']}: 边界为空（codec 会拒绝）")
        # 材料绑定必须指向存在的节点
        pack_id = self.pack["packId"]
        for path, doc in self.sidecars:
            for material in doc["materials"]:
                bindings = material.get("bindings") or []
                if len(bindings) > 1:
                    errors.append(f"{path.name}/{material['slug']}: 多于 1 条绑定")
                for binding in bindings:
                    node = binding["knowledgeNodeId"]
                    parts = node.split(":")
                    if len(parts) < 5 or (parts[-3].upper(), parts[-1]) not in seen:
                        errors.append(f"{path.name}/{material['slug']}: 绑定悬空 {node[:60]}")
        return errors

    # ---------- 执行 ----------

    def build(self) -> dict:
        self._apply_new_points()
        self._apply_new_materials()
        self._apply_material_bindings()
        self._apply_alias_and_boundary()
        self._apply_prereq()
        self._repair_material_text()
        self._rebuild_topics()
        # 树建完后再把 name 收敛成层内名：`_rebuild_topics` 产出的名字带完整路径，而路径
        # 应由 parentSlug 表达。位置必须在最后——它依赖最终的父子关系，改名也不许影响
        # 别的任何字段（slug 一个字节都不动）。
        from kb_build import shorten_topic_names
        shorten_topic_names.shorten(self.pack)
        return {"new_points": len(self.new_points),
                "new_materials": len(self.new_materials)}

    def write(self) -> list[Path]:
        """只写 staging。成品目录唯一写者是 promote.py（门全绿才落盘）。"""
        STAGING.mkdir(parents=True, exist_ok=True)
        written = [pack_io.dump_json(self.pack, STAGING / pack_io.PACK_NAME)]
        for path, doc in self.sidecars:
            written.append(pack_io.dump_json(doc, STAGING / path.name))
        return written


def summarize(builder: Builder, stats: dict) -> None:
    before = Counter()
    for subject in pack_io.load_json(pack_io.pack_path())["subjects"]:
        before["nodes"] += len(subject["topics"])
        for topic in subject["topics"]:
            before["points"] += len(topic.get("knowledgePoints") or [])
    after = Counter()
    for subject in builder.pack["subjects"]:
        after["nodes"] += len(subject["topics"])
        for topic in subject["topics"]:
            after["points"] += len(topic.get("knowledgePoints") or [])
    print(f"  知识点 {before['points']} -> {after['points']}（新增 {stats['new_points']}）")
    print(f"  topic  {before['nodes']} -> {after['nodes']}")
    for note in builder.notes:
        print(f"  说明：{note}")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--write", action="store_true", help="真的落盘（只写 staging）")
    args = parser.parse_args(argv)

    builder = Builder()
    try:
        stats = builder.build()
    except InvariantError as exc:
        print(f"拒绝生成：{exc}")
        return 2

    errors = builder.check_invariants()
    print(f"生成前不变量校验：{'通过' if not errors else f'{len(errors)} 项失败'}")
    for err in errors[:10]:
        print(f"   ! {err}")
    if errors:
        return 1

    print("差异摘要：")
    summarize(builder, stats)

    if not args.write:
        print()
        print("（dry-run，未落盘。加 --write 写 staging；晋升走 kb_build.promote）")
        return 0

    written = builder.write()
    print()
    print(f"已写出 {len(written)} 个文件到 {written[0].parent}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
