# -*- coding: utf-8 -*-
"""单一晋升路径：staging → 成品目录。

所有手术工具默认读写 `build/kb-staging/`（成品目录的镜像）；**唯一**能把内容
写进 `core/data/src/main/resources/knowledge/` 的是本模块。晋升 = 一次原子决策：

    读 staging（包家族 + 台账）
    → 22 道内容门（gate.evaluate）
    → 契约镜像 + 五张权威表↔包一致性（check_pack_contract）
    → round-trip（包家族全部文件可规范重放）
    → 台账 schema 2 压平 + 一跳到底 + 单调 version
    → 全绿才逐文件落盘成品（先写临时文件再原子替换，台账**最后**写）
    → 落盘后复算内容戳与落账值核对（不一致即 PromoteError）

任何一环不绿：不写盘、exit 1。内容戳（contentVersion）与版本号（version）
只在落盘这一刻刷新（原子戳）：手术工具写 staging 不刷戳，
`update_manifest` 也不提供刷戳入口。

version 单调性：新 version = max(staging 台账, 成品台账) + 1——
即便 staging 是从旧版成品复制的基线，成品线上的 version 也不会回退
（TUF 教训：全量哈希防不了回滚/冻结，单调版本才防）。

用法：
    PYTHONPATH=tools python -m kb_build.promote            # 跑门并晋升
    PYTHONPATH=tools python -m kb_build.promote --dry-run  # 只跑门，不写盘
    PYTHONPATH=tools python -m kb_build.promote --json     # 检查结果按 JSON 输出
"""

from __future__ import annotations

import argparse
import json
import os
import shutil
from pathlib import Path

from kb_build import check_pack_contract, gate, pack_io, roundtrip, update_manifest


class PromoteError(Exception):
    pass


def run_checks(root: Path, release: Path) -> dict:
    """对 staging（root）跑晋升前的全部检查。返回分节结果，`ok` 汇总。"""
    pack_io.use_directory(root)
    try:
        pack = pack_io.load_json(root / pack_io.PACK_NAME)
        result: dict = {"root": str(root), "sections": {}}

        # 1) 22 道内容门（读 root）
        metrics = gate.evaluate()
        result["sections"]["gates"] = {
            "ok": all(m.ok for m in metrics),
            "failed": [{"key": m.key, "value": m.value, "detail": m.detail[:10]}
                       for m in metrics if not m.ok],
        }

        # 2) 契约镜像 + 五表一致性（读 root 的包 + 台账）
        consistency, _stats = check_pack_contract.evaluate(root)
        bad_tables = {name: problems for name, problems in consistency.items() if problems}
        result["sections"]["consistency"] = {"ok": not bad_tables, "failed": bad_tables}

        # 3) round-trip：包家族可规范重放
        _total, failures = roundtrip.run(root)
        result["sections"]["roundtrip"] = {"ok": failures == 0, "failed_files": failures}

        # 4) 台账：规范化（schema 2 + 压平 + 一跳到底）+ version 单调
        staging_manifest = update_manifest.load(root / update_manifest.MANIFEST_NAME)
        release_manifest = update_manifest.load(release / update_manifest.MANIFEST_NAME)
        promoted = update_manifest.load_or_empty(pack["packId"],
                                                 root / update_manifest.MANIFEST_NAME)
        before = [dict(e) for e in promoted["retired"]]
        try:
            update_manifest.canonicalize(promoted)
        except ValueError as exc:
            result["sections"]["manifest"] = {"ok": False, "error": str(exc)}
            result["ok"] = False
            return result
        rewrites = sum(1 for a, b in zip(before, promoted["retired"]) if a != b)
        new_version = max(int(promoted["version"]),
                          int((release_manifest or {}).get("version", 0))) + 1
        promoted["version"] = new_version
        update_manifest._require_shape(promoted)
        result["sections"]["manifest"] = {
            "ok": True,
            "new_version": new_version,
            "flattened": rewrites,
            "upgraded": staging_manifest is not None
            and staging_manifest.get("schemaVersion") != update_manifest.SCHEMA_VERSION,
        }
        result["ok"] = all(section["ok"] for section in result["sections"].values())
        return result
    finally:
        pack_io.reset_directory()


def _build_promoted_manifest(root: Path, release: Path, pack: dict,
                             sidecars: list[dict]) -> tuple[dict, int, int]:
    """构造要落盘的台账：schema 2 + 压平 + 新 version + 新内容戳。"""
    promoted = update_manifest.load_or_empty(pack["packId"],
                                             root / update_manifest.MANIFEST_NAME)
    release_manifest = update_manifest.load(release / update_manifest.MANIFEST_NAME)
    before = [dict(e) for e in promoted["retired"]]
    update_manifest.canonicalize(promoted)
    rewrites = sum(1 for a, b in zip(before, promoted["retired"]) if a != b)
    new_version = max(int(promoted["version"]),
                      int((release_manifest or {}).get("version", 0))) + 1
    promoted["version"] = new_version
    promoted["contentVersion"] = update_manifest.content_version(pack, sidecars)
    update_manifest._require_shape(promoted)
    return promoted, new_version, rewrites


def _dump_release(obj, path: Path) -> None:
    """成品目录逐文件原子写：先写 .promotetmp 再 os.replace。

    崩溃安全取向：任一时刻成品目录里的文件要么是旧的完整版本、要么是新版本，
    不会有半截文件。
    """
    tmp = path.with_name(path.name + ".promotetmp")
    with tmp.open("w", encoding="utf-8", newline="\n") as fh:
        fh.write(pack_io.serialize(obj))
    os.replace(tmp, path)


def promote(root: Path | None = None, release: Path | None = None,
            dry_run: bool = False) -> tuple[int, dict]:
    """执行晋升。root/staging 与 release/成品 可注入（测试用夹具）。

    返回 (退出码, 检查报告)。dry_run=True 时门全绿也**不写盘**。
    """
    root = (root or pack_io.work_dir()).resolve()
    release = (release or pack_io.release_dir()).resolve()
    if root == release:
        raise PromoteError("staging 与成品目录必须是两个目录")
    if not (root / pack_io.PACK_NAME).exists():
        raise PromoteError(f"staging 缺包文件：{root / pack_io.PACK_NAME}")

    result = run_checks(root, release)
    if not result["ok"]:
        return 1, result
    if dry_run:
        return 0, result

    # 读包家族：侧车清单以 root 的索引为准（自定义 root 时默认工作目录不是它）
    pack_io.use_directory(root)
    try:
        pack = pack_io.load_json(root / pack_io.PACK_NAME)
        sidecar_paths = pack_io.sidecar_paths()
        sidecars = [pack_io.load_json(p) for p in sidecar_paths]
        index = pack_io.load_json(root / pack_io.SIDECAR_INDEX_NAME)
    finally:
        pack_io.reset_directory()
    promoted, new_version, rewrites = _build_promoted_manifest(root, release, pack, sidecars)

    release.mkdir(parents=True, exist_ok=True)
    # 落盘顺序：pack → sidecar → 索引 → 台账（最后）。台账携带内容戳，
    # 戳与内容在同一个原子决策里生效。
    _dump_release(pack, release / pack_io.PACK_NAME)
    for sp, sc in zip(sidecar_paths, sidecars):
        _dump_release(sc, release / sp.name)
    _dump_release(index, release / pack_io.SIDECAR_INDEX_NAME)
    _dump_release(promoted, release / update_manifest.MANIFEST_NAME)

    # 落盘自检：成品复算戳必须与落账值一致，否则写入过程出了岔子
    r_pack = pack_io.load_json(release / pack_io.PACK_NAME)
    r_sidecars = [pack_io.load_json(release / p.name) for p in sidecar_paths]
    recomputed = update_manifest.content_version(r_pack, r_sidecars)
    if recomputed != promoted["contentVersion"]:
        raise PromoteError(
            f"落盘自检失败：成品复算戳 {recomputed} ≠ 落账 {promoted['contentVersion']}")

    # 晋升后同步镜像：staging 回到与成品一致的状态，下一轮手术从新基线出发
    # （否则 staging 里还躺着晋升前的旧台账，链式形态会一直滞留在工作区）。
    for name in [pack_io.PACK_NAME] + [p.name for p in sidecar_paths] \
            + [pack_io.SIDECAR_INDEX_NAME, update_manifest.MANIFEST_NAME]:
        src = release / name
        tmp = root / (name + ".promotetmp")
        shutil.copy2(src, tmp)
        os.replace(tmp, root / name)

    result["written"] = len(sidecar_paths) + 3  # pack + 卷 + 索引 + 台账
    result["contentVersion"] = promoted["contentVersion"]
    result["version"] = new_version
    result["flattened"] = rewrites
    return 0, result


def _print_report(result: dict) -> None:
    print(f"晋升检查（{result['root']}）：")
    for name, section in result["sections"].items():
        mark = "OK  " if section["ok"] else "FAIL"
        extra = ""
        if name == "gates" and section["failed"]:
            extra = "：" + "、".join(f"{f['key']}={f['value']}" for f in section["failed"])
        if name == "consistency" and section["failed"]:
            extra = "：" + "、".join(f"{k}×{len(v)}" for k, v in section["failed"].items())
        if name == "roundtrip" and not section["ok"]:
            extra = f"：{section['failed_files']} 个文件不一致"
        if name == "manifest":
            extra = (f"：version→{section['new_version']}，压平 {section['flattened']} 条"
                     + ("，schema 1→2 升级" if section.get("upgraded") else ""))
        print(f"  {mark} {name}{extra}")
    consistency = result["sections"]["consistency"]
    if not consistency["ok"]:
        for k, v in consistency["failed"].items():
            for line in v[:8]:
                print(f"      [{k}]", line)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="单一晋升路径：staging → 成品")
    parser.add_argument("--dry-run", action="store_true", help="只跑门，不写盘")
    parser.add_argument("--json", action="store_true", help="把检查结果按 JSON 输出")
    args = parser.parse_args(argv)
    try:
        code, result = promote(dry_run=args.dry_run)
        if args.json:
            print(json.dumps(result, ensure_ascii=False, indent=1))
        else:
            _print_report(result)
            if code == 0 and not args.dry_run:
                print(f"\n已晋升 {result['written']} 个文件 → {pack_io.release_dir()}")
                print(f"  内容戳 → {result['contentVersion']}")
                print(f"  version → {result['version']}（压平 {result['flattened']} 条链）")
            elif code == 0:
                print(f"\n（dry-run：门全绿，未写盘；将写入 version "
                      f"{result['sections']['manifest']['new_version']}）")
            else:
                print(f"\n晋升被拒绝：未全绿，成品目录未写")
        return code
    except PromoteError as exc:
        print(f"晋升失败：{exc}")
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
