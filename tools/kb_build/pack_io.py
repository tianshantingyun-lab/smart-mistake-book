# -*- coding: utf-8 -*-
"""知识包 JSON 的读写。

契约来自 ReviewedKnowledgePackJsonCodec（BundledKnowledgePackResources.kt）：
- 根键（schema 2）恰好：schemaVersion, packId, taxonomyVersion, sourceNamespace,
  reviewedAtEpochMillis, sourceUri, coverage, subjects
- packId 必须等于 taxonomyVersion
- subject 键恰好：subject, sourceFingerprint, topics
- topic 键：slug, name, sourceLocator, parentSlug, knowledgePoints
- point 键恰好 7 个：slug, name, aliases, kind, boundary, sourceLocator, prerequisiteSlugs
- 同科内 topic slug 唯一、point slug 唯一；prerequisite 必须引用同科 point
本模块只负责忠实读写；语义校验在 gate.py。
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

# 现行成品的排版约定：indent=1，不转义非 ASCII，UTF-8。
_JSON_KW = dict(ensure_ascii=False, indent=1)

REPO: Path = Path(__file__).resolve().parents[2]
KNOWLEDGE_DIR: Path = REPO / "core" / "data" / "src" / "main" / "resources" / "knowledge"

PACK_NAME = "moe-2025-four-subjects-v1.json"
SIDECAR_TMPL = "moe-2025-teaching-support-v2-0%d.json"
SIDECAR_COUNT = 6


def use_directory(path: Path) -> None:
    """把读取根切到别处（例如 build/kb-staging）。

    仅供"验证生成结果"这类只读比较使用；写回一律走 dump_json 的显式路径参数，
    不会因为改了这里而误覆盖成品。
    """
    global KNOWLEDGE_DIR
    KNOWLEDGE_DIR = path


def _inside_repo(path: Path) -> Path:
    """拒绝解析到仓库之外的路径，避免读写越界。"""
    resolved = path.resolve()
    if not resolved.is_relative_to(REPO):
        raise ValueError(f"path escapes repository root: {resolved}")
    return resolved


def pack_path() -> Path:
    return KNOWLEDGE_DIR / PACK_NAME


def sidecar_paths() -> list[Path]:
    return [KNOWLEDGE_DIR / (SIDECAR_TMPL % i) for i in range(1, SIDECAR_COUNT + 1)]


def load_json(path: Path) -> dict[str, Any]:
    with _inside_repo(path).open(encoding="utf-8") as fh:
        return json.load(fh)


def dump_json(obj: dict[str, Any], path: Path) -> Path:
    """按成品既有排版写出，保证 diff 最小。

    规范形态由 .gitattributes（`*.json text eol=lf`）与索引实况决定：
    LF 换行、缩进 1、不转义非 ASCII、文件末尾不带多余换行。
    工作树里出现的 CRLF 只是 Windows 检出产物，不属于规范形态。
    """
    target = _inside_repo(path)
    target.parent.mkdir(parents=True, exist_ok=True)
    text = serialize(obj)
    with target.open("w", encoding="utf-8", newline="\n") as fh:
        fh.write(text)
    return target


def serialize(obj: dict[str, Any]) -> str:
    """生成规范形态的 JSON 文本（LF、末尾无换行）。"""
    return json.dumps(obj, **_JSON_KW)


def iter_points(pack: dict[str, Any]):
    """遍历 (subject, topic, point) 三元组。"""
    for subject in pack["subjects"]:
        for topic in subject["topics"]:
            for point in topic.get("knowledgePoints") or []:
                yield subject["subject"], topic, point


def iter_topics(pack: dict[str, Any]):
    for subject in pack["subjects"]:
        for topic in subject["topics"]:
            yield subject["subject"], topic


def point_index(pack: dict[str, Any]) -> dict[tuple[str, str], dict]:
    """(subject, point slug) -> point。"""
    return {(s, p["slug"]): p for s, _t, p in iter_points(pack)}


def load_materials() -> list[tuple[Path, dict]]:
    """返回 [(sidecar 路径, 材料 dict)]。"""
    out: list[tuple[Path, dict]] = []
    for path in sidecar_paths():
        doc = load_json(path)
        out.extend((path, m) for m in doc["materials"])
    return out


def material_index() -> dict[str, dict]:
    """材料 slug -> 材料 dict（跨 6 个 sidecar 全局）。"""
    return {m["slug"]: m for _p, m in load_materials()}
