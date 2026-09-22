# -*- coding: utf-8 -*-
"""Round-trip 校验：证明包家族对规范序列化可忠实重放。

做法：读入文件 → 按规范形态（.gitattributes 规定 JSON 为 LF）重新序列化
→ 与磁盘内容的归一化形态比对。不一致就说明生成器对格式有假设，
任何后续修正都不可信。

覆盖**整个包家族**（pack + 全部 sidecar + sidecar 索引 + 取代台账）：
索引与台账同样是 dump_json 的产物，手工编辑过的 JSON 缩进/键序会在这里
现出原形——晋升前必须干净。

比对基准说明：工作树里的 JSON 可能是 Windows 检出的 CRLF，而索引里是 LF；
因此比对前先把两侧都归一化为 LF、去掉末尾换行，再逐字符比。

用法：python tools/build-knowledge-pack.py --verify-roundtrip
"""

from __future__ import annotations

from pathlib import Path

from kb_build import pack_io, update_manifest


def normalize(text: str) -> str:
    return text.replace("\r\n", "\n").rstrip("\n")


def verify_file(path: Path) -> tuple[bool, str]:
    disk = normalize(path.read_text(encoding="utf-8"))
    rebuilt = normalize(pack_io.serialize(pack_io.load_json(path)))
    if disk == rebuilt:
        return True, f"OK   {path.name}"
    a = disk.split("\n")
    b = rebuilt.split("\n")
    for i, (x, y) in enumerate(zip(a, b), 1):
        if x != y:
            return False, (
                f"DIFF {path.name} line {i}\n"
                f"  disk: {x[:160]}\n"
                f"  emit: {y[:160]}"
            )
    if len(a) != len(b):
        return False, f"DIFF {path.name} line count {len(a)} -> {len(b)}"
    return False, f"DIFF {path.name} 内容长度不同：{len(disk)} -> {len(rebuilt)}"


def run(root: Path | None = None) -> tuple[int, int]:
    """对包家族逐文件 round-trip。返回 (文件数, 失败数)。"""
    if root is not None:
        pack_io.use_directory(root)
    files = [pack_io.pack_path(), *pack_io.sidecar_paths()]
    failures = 0
    # 索引是硬要求（Kotlin loader 按它加载卷）；台账存在才查。
    idx = pack_io.sidecar_index_path()
    if idx.exists():
        files.append(idx)
    else:
        print("! DIFF 缺少 sidecar 索引文件")
        failures += 1
    mp = pack_io.work_dir() / update_manifest.MANIFEST_NAME
    if mp.exists():
        files.append(mp)
    for path in files:
        ok, message = verify_file(path)
        print(("  " if ok else "! ") + message)
        if not ok:
            failures += 1
    return len(files) + (1 if not idx.exists() else 0), failures


def main(root: Path | None = None) -> int:
    total, failures = run(root)
    print()
    if failures:
        print(f"round-trip FAILED on {failures} file(s)")
        return 1
    print(f"round-trip OK on {total} file(s)：生成器可忠实重放包家族")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
