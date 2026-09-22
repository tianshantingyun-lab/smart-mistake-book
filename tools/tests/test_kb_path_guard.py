# -*- coding: utf-8 -*-
"""写盘拓扑的路径守卫（D2：无双模式旁路）。

它消灭的失败：任何手术工具"顺手"写成品目录——绕过门、绕过原子戳，
成品就重新变回"化石"（2026-09-21 审计 P1：JSON 是 100+ 次手工手术的累积）。
守卫是**源码级**的：除 pack_io（定义）与 promote.py（唯一写者）外，
tools/kb_build 里任何人引用成品写路径（`KNOWLEDGE_DIR` / `release_dir`）
并把它喂给 `dump_json`（或写文件调用）都会让本用例变红。
"""

from __future__ import annotations

import re
import unittest
from pathlib import Path

KB_BUILD = Path(__file__).resolve().parents[1] / "kb_build"

# 引用成品目录是允许的（定义/只读），引用名单之外一律违规
RELEASE_REFS_ALLOWED = {"pack_io.py", "promote.py", "wusan_route.py"}
# 把成品路径喂给写调用——只允许 promote.py
RELEASE_WRITES_ALLOWED = {"promote.py"}

_RELEASE_TOKENS = re.compile(r"KNOWLEDGE_DIR|release_dir\b|resources[\"']?\s*\+\s*[\"']?knowledge")
_WRITE_CALLS = re.compile(r"dump_json\s*\(|open\s*\([^)]*['\"]w|write_text|shutil\.copy")


class PathGuardTest(unittest.TestCase):
    def _sources(self) -> dict[str, str]:
        return {p.name: p.read_text(encoding="utf-8")
                for p in sorted(KB_BUILD.glob("*.py"))}

    def test_only_known_files_reference_release_path(self):
        offenders = {
            name for name, src in self._sources().items()
            if _RELEASE_TOKENS.search(src) and name not in RELEASE_REFS_ALLOWED
        }
        self.assertEqual(set(), offenders,
                         f"引用成品目录的文件超出白名单：{sorted(offenders)}")

    def test_only_promote_writes_to_release_path(self):
        """引用成品路径的文件里，只有 promote.py 允许出现写调用。"""
        offenders = {}
        for name, src in self._sources().items():
            if name not in RELEASE_REFS_ALLOWED or name not in RELEASE_WRITES_ALLOWED:
                continue
            if _RELEASE_TOKENS.search(src):
                write_lines = [
                    (i, line.strip()) for i, line in enumerate(src.splitlines(), 1)
                    if _WRITE_CALLS.search(line) and _RELEASE_TOKENS.search(line)
                ]
                if write_lines:
                    offenders[name] = write_lines
        self.assertEqual({}, offenders, f"成品写路径出现在非 promote 文件：{offenders}")

    def test_release_reference_is_read_only_in_wusan_route(self):
        """wusan_route 对成品的引用只允许是**读**（staging 缺失时的回退）。"""
        src = (KB_BUILD / "wusan_route.py").read_text(encoding="utf-8")
        for i, line in enumerate(src.splitlines(), 1):
            if "release_dir" in line or "KNOWLEDGE_DIR" in line:
                self.assertFalse(_WRITE_CALLS.search(line),
                                 f"wusan_route.py:{i} 对成品目录有写调用：{line.strip()}")

    def test_promote_actually_writes_release(self):
        """负向对照：守卫不能空转——promote.py 必须真的引用成品写路径，
        否则上面两条'只有 promote'的断言对任何仓库都恒真。"""
        src = (KB_BUILD / "promote.py").read_text(encoding="utf-8")
        self.assertTrue(_RELEASE_TOKENS.search(src))
        self.assertTrue(re.search(r"_dump_release\(", src))

    def test_surgery_tools_have_no_release_write(self):
        """30+ 手术工具逐个点名：它们写的一律是 staging 派生路径。"""
        # wusan_route 的成品引用是**读**回退（staging 缺失时），由专门的只读用例钉住
        surgery = {
            p.name for p in KB_BUILD.glob("*.py")
        } - {"pack_io.py", "promote.py", "wusan_route.py", "build.py", "__init__.py"}
        for name in sorted(surgery):
            src = (KB_BUILD / name).read_text(encoding="utf-8")
            self.assertNotIn("KNOWLEDGE_DIR", src, f"{name} 直接引用成品目录常量")
            self.assertNotIn("release_dir", src, f"{name} 调用 release_dir()")


if __name__ == "__main__":
    unittest.main()
