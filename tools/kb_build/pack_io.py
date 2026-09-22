# -*- coding: utf-8 -*-
"""知识包 JSON 的读写（staging → promote → 成品 拓扑）。

契约来自 ReviewedKnowledgePackJsonCodec（BundledKnowledgePackResources.kt）：
- 根键（schema 2）恰好：schemaVersion, packId, taxonomyVersion, sourceNamespace,
  reviewedAtEpochMillis, sourceUri, coverage, subjects
- packId 必须等于 taxonomyVersion
- subject 键恰好：subject, sourceFingerprint, topics
- topic 键：slug, name, sourceLocator, parentSlug, knowledgePoints
- point 键恰好 7 个：slug, name, aliases, kind, boundary, sourceLocator, prerequisiteSlugs
- 同科内 topic slug 唯一、point slug 唯一；prerequisite 必须引用同科 point
本模块只负责忠实读写；语义校验在 gate.py。

写盘拓扑（2026-09-22 单一晋升路径定案）：
- 所有工具默认读写 **staging**（`build/kb-staging/`，成品目录的镜像）；
  staging 不存在时从成品目录复制基线（`seed_staging`）。
- **成品目录对工具只读**：唯一能写成品目录的是 `promote.py`（跑全部门后才落盘）。
  因此本模块只把 `KNOWLEDGE_DIR`（成品）暴露给 `release_dir()` / 种子复制，
  工具一律走 `work_dir()` 派生的路径。`path-guard` 测试（tools/tests）
  扫描本包源码，断言除 promote.py 外无人引用成品写路径。
"""

from __future__ import annotations

import json
import os
import shutil
from pathlib import Path
from typing import Any

# 现行成品的排版约定：indent=1，不转义非 ASCII，UTF-8。
_JSON_KW = dict(ensure_ascii=False, indent=1)

REPO: Path = Path(__file__).resolve().parents[2]
KNOWLEDGE_DIR: Path = REPO / "core" / "data" / "src" / "main" / "resources" / "knowledge"
STAGING_DIR: Path = REPO / "build" / "kb-staging"

PACK_NAME = "moe-2025-four-subjects-v1.json"
SIDECAR_TMPL = "moe-2025-teaching-support-v2-{i:02d}.json"
SIDECAR_INDEX_NAME = "moe-2025-teaching-support-v2-index.json"

# staging 是"整个成品目录的镜像"：种子只复制 JSON（包家族 + 样例包），
# staging 里工具产出的 CSV 报告/判定单不在此列、也不被种子覆盖。
_SEED_SUFFIX = ".json"

_dir_override: Path | None = None


def use_directory(path: Path) -> None:
    """把工作目录切到别处（测试夹具 / 验证某个生成结果）。

    切走后 `pack_path()` 等全部派生路径以 `path` 为根；
    `reset_directory()` 恢复默认（staging）。
    """
    global _dir_override
    _dir_override = _inside_repo(path)


def reset_directory() -> None:
    """撤销 use_directory 的覆盖，恢复默认工作目录。"""
    global _dir_override
    _dir_override = None


def release_dir() -> Path:
    """成品目录（core/data 资源）。对工具**只读**；唯一写者是 promote.py。"""
    return KNOWLEDGE_DIR


def seed_staging(dst: Path | None = None, src: Path | None = None) -> Path:
    """把成品目录的 JSON 复制到 staging，建立工作基线。

    逐文件"先写临时文件再原子替换"：staging 是共享工作区，双会话并发
    种子时不能留下半截文件。已存在的同名文件会被覆盖（staging 是镜像，
    基线永远来自成品）。返回 dst。
    """
    dst = dst or STAGING_DIR
    src = src or KNOWLEDGE_DIR
    dst = _inside_repo(dst)
    src = _inside_repo(src)
    dst.mkdir(parents=True, exist_ok=True)
    for p in sorted(src.iterdir()):
        if not p.is_file() or p.suffix != _SEED_SUFFIX:
            continue
        tmp = dst / (p.name + ".seedtmp")
        shutil.copy2(p, tmp)
        os.replace(tmp, dst / p.name)
    return dst


def work_dir() -> Path:
    """默认工作目录：staging；首次使用且缺包文件时从成品复制基线。"""
    if _dir_override is not None:
        return _dir_override
    if not (STAGING_DIR / PACK_NAME).exists():
        seed_staging()
    return STAGING_DIR


def _inside_repo(path: Path) -> Path:
    """拒绝解析到仓库之外的路径，避免读写越界。"""
    resolved = path.resolve()
    if not resolved.is_relative_to(REPO):
        raise ValueError(f"path escapes repository root: {resolved}")
    return resolved


def pack_path() -> Path:
    return work_dir() / PACK_NAME


def sidecar_index_path() -> Path:
    return work_dir() / SIDECAR_INDEX_NAME


def sidecar_paths() -> list[Path]:
    """sidecar 清单以索引文件为准（Kotlin loader 读同一份索引）。

    索引缺失时回退到按文件名排序的 glob（仅用于早期验证，正常成品必须带索引）。
    """
    idx = sidecar_index_path()
    if idx.exists():
        doc = load_json(idx)
        return [work_dir() / name.rsplit("/", 1)[-1] for name in doc["sidecars"]]
    import re
    pat = re.compile(r"moe-2025-teaching-support-v2-\d{2}\.json$")
    return sorted(p for p in work_dir().glob("moe-2025-teaching-support-v2-*.json")
                  if pat.search(p.name))


def next_sidecar_path() -> Path:
    """下一个未占用的 sidecar 文件号（材料膨胀后开新卷用）。"""
    used = {p.name for p in sidecar_paths()}
    i = 1
    while SIDECAR_TMPL.format(i=i) in used:
        i += 1
    return work_dir() / SIDECAR_TMPL.format(i=i)


def write_sidecar_index(paths: list[Path]) -> None:
    """把 sidecar 清单落盘成索引（Kotlin 端按同一清单加载）。"""
    doc = {"packId": "moe-2025-four-subjects-v1",
           "sidecars": ["knowledge/" + p.name for p in paths]}
    dump_json(doc, sidecar_index_path())


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
    """材料 slug -> 材料 dict（跨全部 sidecar 全局，清单见索引文件）。"""
    return {m["slug"]: m for _p, m in load_materials()}
