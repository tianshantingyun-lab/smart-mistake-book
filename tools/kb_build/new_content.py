# -*- coding: utf-8 -*-
"""新增内容（五三炼化）的授权层：新增知识点表 + 新增材料表。

为什么需要这一层：`build.py` 只能对**已有**节点做改名/合并/删除，没有任何"新增"
路径；材料正文也只存在于成品 sidecar 里，没有可审、可重放的作者入口。缺了它，
「五三有、知识库无」的知识点只能手工改进成品包——而手工改的包不可重放，
正是这套工具链当初要消灭的故障。

两个输入：

- `tables/new_points.csv`（新增知识点，一行一个原子点）
  `subject,slug,name,kind,boundary,volume,chapter,theme,source_locator`
  章节与边界都直接写在这一行里（而不是再要一张 chapter_map / boundary_map 行），
  这样一条新增内容自带全部归档信息，审校时不必跨多张表对着看。

- `knowledge-production/wusan/materials.jsonl`（新增材料，一行一个 13 键对象）
  与 codec 的字段一一对应；`bindings` 就写在材料里，不另设绑定表——
  codec 要求绑定随材料解析，两处维护会不一致。

本模块只做**加载与校验**，不做变换；变换在 build.Builder 里。
校验前移到加载期，是为了让"整句话当名称""5 行正文""无 ASCII 双引号"这类
违规在生成前就报出来，而不是等 gate 事后统计。
"""

from __future__ import annotations

import csv
import json
import re
from pathlib import Path

from kb_build import gate, pack_io

NEW_POINTS = "new_points.csv"
MATERIALS_REL = Path("knowledge-production") / "wusan" / "materials.jsonl"

POINT_KEYS = ("slug", "name", "aliases", "kind", "boundary", "sourceLocator",
              "prerequisiteSlugs")

VALID_KINDS = {"CONCEPT", "PROCEDURE", "REASONING", "REPRESENTATION", "EXPERIMENT"}

# 来源政策：REFERENCE_ONLY + REVIEWED_SYNTHESIS_ONLY（见 docs/kb-content-conformance-spec.md §5）
VALID_MATERIAL_TYPES = {"CONCEPT_EXPLANATION", "METHOD_MODEL", "MISCONCEPTION_GUIDE"}
REQUIRED_DERIVATION = "REVIEWED_SYNTHESIS"

# 规范 §3.4 点名的三句"通用模板"边界（已占 1528 条，属质量洼地）。新增不得再写。
BANNED_BOUNDARY_TEMPLATES = (
    "该结论为一般规律归纳",
    "一轮复习结论性要点",
    "适用于教材原型实验与操作类题型",
)

MATERIAL_KEYS = ("slug", "subject", "type", "title", "summaryMarkdown",
                 "applicabilityMarkdown", "contentMarkdown", "boundaryMarkdown",
                 "derivationKind", "sourceId", "sourceLocator",
                 "reviewedAtEpochMillis", "bindings")

_SAFE_SLUG = re.compile(r"[a-z0-9]+(?:-[a-z0-9]+)*")
_CONTROL = "\x0c\t\r\x08\x07\x0b"

# 每册一个来源。侧车里 sourceId 的既有规律是
# **登记表 id 把 `:` 换成 `-`，再加 `:<科目小写>`**（实测其余 27 个来源 id 全部符合，
# 例：登记表 `registry:kb:math:handout` → 侧车 `registry-kb-math-handout:math`）。
# 所以这里从登记表条目派生，不自造 id。
SOURCE_ID_TMPL = "registry-wusan-2027-a-version-jingjiang-{subject}:{subject}"
SUBJECT_CN = {"MATH": "数学", "PHYSICS": "物理", "CHEMISTRY": "化学", "BIOLOGY": "生物学"}
# 登记表里四科条目的正式书名与发布者。**权威是登记表**
# （`knowledge-research/sources/source-register-candidates.json` 的
# `registry:wusan:2027:a-version-jingjiang:<科>`），这里只是把它固化下来供生成期使用；
# 两者是否一致由 `tools/tests/test_kb_new_content.py` 的用例守住。
SOURCE_TITLE_TMPL = "《2027 5·3 A版 高考总复习 {cn} 精讲册》"
SOURCE_PUBLISHER = "首都师范大学出版社（曲一线）"


class NewContentError(Exception):
    pass


def source_id(subject: str) -> str:
    return SOURCE_ID_TMPL.format(subject=subject.lower())


def make_source(subject: str, materials: list[dict] | None = None) -> dict:
    """五三精讲册的来源登记。

    `contentFingerprint` 是**该来源贡献的材料正文的 SHA-256**，不是随便一个定值——
    这个字段的用途就是"内容变了能被发现"，用一个与内容无关的常量填进去等于装饰。
    不传 `materials` 时（只登记来源、还没有材料）退化为对来源 id 取哈希。
    """
    import hashlib
    sid = source_id(subject)
    blob = "\n".join(sorted(
        m["slug"] + "||" + (m.get("contentMarkdown") or "") for m in (materials or [])))
    digest = hashlib.sha256((blob or sid).encode()).hexdigest().upper()
    return {
        "sourceId": sid,
        "subject": subject,
        "sourceType": "AUTHORIZED_EDUCATION_MATERIAL",
        "title": SOURCE_TITLE_TMPL.format(cn=SUBJECT_CN[subject]),
        "publisher": SOURCE_PUBLISHER,
        "edition": "2027版",
        "sourceUri": "https://www.example.edu/material",
        "licenseStatus": "REFERENCE_ONLY",
        "contentFingerprint": digest,
        "importedAtEpochMillis": 1789200000000,
        "contentUsePolicy": "REVIEWED_SYNTHESIS_ONLY",
        "licenseExpression": None,
        "licenseUri": None,
        "attributionText": "结构化总结（REVIEWED_SYNTHESIS），非原文。",
    }


def _check_text(field: str, value: str, slug: str, *, forbid_ascii_quote: bool = False) -> None:
    if not value or not value.strip():
        raise NewContentError(f"{slug}: {field} 为空")
    if value != value.strip():
        raise NewContentError(f"{slug}: {field} 首尾有空白")
    bad = [ch for ch in value if ch in _CONTROL or (ord(ch) < 0x20 and ch != "\n")]
    if bad:
        raise NewContentError(f"{slug}: {field} 含控制字符 {[hex(ord(c)) for c in bad]}")
    if forbid_ascii_quote and '"' in value:
        raise NewContentError(f"{slug}: {field} 含 ASCII 双引号（规范要求中文引号）")


def load_new_points(existing: dict[str, set[str]] | None = None, path: Path | None = None
                    ) -> tuple[dict[tuple[str, str], dict],
                               dict[tuple[str, str], tuple[str, str, str]],
                               dict[tuple[str, str], str]]:
    """读 `tables/new_points.csv`。

    返回 ({(科目,slug): 节点}, {(科目,slug): (册,章,主题)}, {(科目,slug): 边界})。
    用 (科目,slug) 作键而不是裸 slug：slug 只需科内唯一，跨科可以重名。

    `existing` 是 {subject: {已有 slug}}，用于防撞名；不传则从成品包现读。
    `path` 只为测试留的注入点；不传就读权威表。
    """
    path = path or (pack_io.REPO / "tools" / "kb_build" / "tables" / NEW_POINTS)
    if not path.exists():
        return {}, {}, {}
    if existing is None:
        pack = pack_io.load_json(pack_io.pack_path())
        existing = {}
        for subject, _t, point in pack_io.iter_points(pack):
            existing.setdefault(subject, set()).add(point["slug"])

    points: dict[tuple[str, str], dict] = {}
    seen_slug: set[tuple[str, str]] = set()
    seen_name: set[tuple[str, str]] = set()
    placements: dict[tuple[str, str], tuple[str, str, str]] = {}
    boundaries: dict[tuple[str, str], str] = {}
    with path.open(encoding="utf-8", newline="") as fh:
        for row in csv.DictReader(fh):
            subject = (row.get("subject") or "").strip()
            slug = (row.get("slug") or "").strip()
            name = (row.get("name") or "").strip()
            kind = (row.get("kind") or "").strip()
            boundary = (row.get("boundary") or "").strip()
            volume = (row.get("volume") or "").strip()
            chapter = (row.get("chapter") or "").strip()
            theme = (row.get("theme") or "").strip()
            locator = (row.get("source_locator") or "").strip()

            if subject not in SUBJECT_CN:
                raise NewContentError(f"new_points.csv: 未知科目 {subject!r}（行 slug={slug}）")
            if not slug or not name:
                raise NewContentError(f"new_points.csv: slug/name 不能为空（行 {row}）")
            if kind not in VALID_KINDS:
                raise NewContentError(f"new_points.csv: {slug} 的 kind {kind!r} 不在 {sorted(VALID_KINDS)}")
            if not volume or not chapter:
                raise NewContentError(f"new_points.csv: {slug} 缺 volume/chapter（归属必须显式）")

            # 名称规范前移：整句话/残片/通用名词在加载期就拒绝，不留到 gate 事后统计
            verdict = gate._is_bad_name(name)
            if verdict:
                raise NewContentError(f"new_points.csv: {slug} 的名称「{name}」不合规范（{verdict}）")
            if len(name) > 24:
                raise NewContentError(f"new_points.csv: {slug} 的名称「{name}」超过 24 字")
            _check_text("name", name, slug)

            # 边界必须是真边界：定位串、占位、三句通用模板都会被 gate 判失败。
            # 这里校验的是**作者写的部分**；定位前缀由本函数按 (册, 章) 合成，
            # 因为节点的册/章在成品包里就是靠 boundary 的 `定位：` 前缀承载的
            # （gate 的 chapter_no_book / chapter_locator_mismatch 都从它取），
            # 让作者手抄前缀只会多一处抄错的机会。
            _check_text("boundary", boundary, slug, forbid_ascii_quote=True)
            if boundary.startswith("定位：") or "（见知识清单/教材）" in boundary:
                raise NewContentError(f"new_points.csv: {slug} 的边界是定位串/占位，不是边界")
            if any(boundary.startswith(t) for t in BANNED_BOUNDARY_TEMPLATES):
                raise NewContentError(f"new_points.csv: {slug} 的边界是规范点名的通用模板")
            boundary = f"定位：{volume} {chapter}。{boundary}"

            if (subject, slug) in seen_slug:
                raise NewContentError(f"new_points.csv: {subject} 内 slug 重复 {slug!r}")
            if slug in existing.get(subject, set()):
                raise NewContentError(f"new_points.csv: {subject} 已有同名 slug {slug!r}，应走 node_actions 改名/合并")
            if (subject, name) in seen_name:
                raise NewContentError(f"new_points.csv: {subject} 内名称重复「{name}」")
            seen_slug.add((subject, slug))
            seen_name.add((subject, name))

            points[(subject, slug)] = {
                "slug": slug,
                "name": name,
                "aliases": [name],
                "kind": kind,
                "boundary": boundary,
                "sourceLocator": locator or "人教版高中教材（2019）",
                "prerequisiteSlugs": [],
            }
            placements[(subject, slug)] = (volume, chapter, theme)
            boundaries[(subject, slug)] = boundary
    return points, placements, boundaries


def _validate_material(material: dict, index: int, known_slugs: set[tuple[str, str]],
                       seen_slugs: set[str]) -> None:
    tag = f"materials.jsonl[{index}]"
    if tuple(material) != MATERIAL_KEYS:
        raise NewContentError(f"{tag}: 键集合或顺序不符，期望 {MATERIAL_KEYS}，实际 {tuple(material)}")
    slug = material["slug"]
    if not _SAFE_SLUG.fullmatch(slug):
        raise NewContentError(f"{tag}: slug {slug!r} 不是 kebab 形式")
    if slug in seen_slugs:
        raise NewContentError(f"{tag}: slug 重复 {slug!r}")
    seen_slugs.add(slug)
    subject = material["subject"]
    if material["type"] not in VALID_MATERIAL_TYPES:
        raise NewContentError(f"{tag}: type {material['type']!r} 不在 {sorted(VALID_MATERIAL_TYPES)}")
    if material["derivationKind"] != REQUIRED_DERIVATION:
        raise NewContentError(f"{tag}: derivationKind 必须是 {REQUIRED_DERIVATION}")
    if material["sourceId"] != source_id(subject):
        raise NewContentError(f"{tag}: sourceId 必须是 {source_id(subject)!r}")

    for field in ("title", "summaryMarkdown", "applicabilityMarkdown",
                  "contentMarkdown", "boundaryMarkdown"):
        _check_text(field, material[field], slug, forbid_ascii_quote=True)
    lines = material["contentMarkdown"].split("\n")
    if not 1 <= len(lines) <= 4:
        raise NewContentError(f"{tag}: contentMarkdown 必须 1–4 行，实际 {len(lines)} 行")
    for cap, field in ((240, "title"), (4000, "summaryMarkdown"), (8000, "applicabilityMarkdown"),
                       (32000, "contentMarkdown"), (8000, "boundaryMarkdown"), (2000, "sourceLocator")):
        if len(material[field]) > cap:
            raise NewContentError(f"{tag}: {field} 超过 {cap} 字")
    if material["title"].startswith("例") or "典例" in material["title"]:
        raise NewContentError(f"{tag}: title 不得是例题（不以「例」开头、不含「典例」）")

    bindings = material["bindings"]
    if len(bindings) != 1 or bindings[0].get("role") != "PRIMARY":
        raise NewContentError(f"{tag}: 必须恰好 1 条 PRIMARY 绑定，实际 {bindings}")
    node = bindings[0].get("knowledgeNodeId", "")
    parts = node.split(":")
    if len(parts) < 5 or (parts[-3].upper(), parts[-1]) not in known_slugs:
        raise NewContentError(f"{tag}: 绑定悬空 {node[:70]}")
    if parts[-3].upper() != subject:
        raise NewContentError(f"{tag}: 绑定跨科（材料 {subject}，节点 {parts[-3].upper()}）")


def load_new_materials(known_slugs: set[tuple[str, str]] | None = None,
                       path: Path | None = None) -> list[dict]:
    """`known_slugs` 是绑定的校验目标集：调用方必须传**生成后仍存在**的节点集。

    留 None 只为了让独立用例能直接跑；生产路径由 Builder 传 `_post_build_slugs()`。
    `path` 同理，是给测试留的注入点。
    """
    path = path or (pack_io.REPO / MATERIALS_REL)
    if not path.exists():
        return []
    if known_slugs is None:
        pack = pack_io.load_json(pack_io.pack_path())
        known_slugs = {(s, p["slug"]) for s, _t, p in pack_io.iter_points(pack)}

    materials: list[dict] = []
    seen: set[str] = set()
    with path.open(encoding="utf-8") as fh:
        for index, line in enumerate(fh):
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            try:
                material = json.loads(line)
            except json.JSONDecodeError as exc:
                raise NewContentError(f"materials.jsonl[{index}]: JSON 解析失败 {exc}") from exc
            _validate_material(material, index, known_slugs, seen)
            materials.append(material)
    return materials


def new_node_ids(materials: list[dict]) -> list[str]:
    """材料绑定引用的节点 id（供装配期检查与新增节点对得上）。"""
    return [m["bindings"][0]["knowledgeNodeId"] for m in materials]
