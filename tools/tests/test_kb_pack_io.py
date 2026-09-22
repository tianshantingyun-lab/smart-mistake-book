# -*- coding: utf-8 -*-
"""pack_io：sidecar 索引驱动的文件清单 + staging 工作目录拓扑。

拓扑契约（2026-09-22 定案）：
- 默认工作目录是 staging（build/kb-staging/），不存在时从成品复制基线；
- 成品目录（release_dir）对工具只读，唯一写者是 promote.py（path-guard 测试钉住）；
- use_directory 切走后全部派生路径以覆盖目录为根，reset 恢复默认。
"""

from __future__ import annotations

import re
import shutil
import tempfile
import unittest
from pathlib import Path

from kb_build import pack_io


class SidecarIndexTest(unittest.TestCase):
    def test_index_matches_disk(self):
        paths = pack_io.sidecar_paths()
        on_disk = sorted(
            p for p in pack_io.work_dir().glob("moe-2025-teaching-support-v2-*.json")
            if re.search(r"moe-2025-teaching-support-v2-\d{2}\.json$", p.name)
        )
        self.assertEqual(sorted(paths), on_disk, "索引清单与磁盘文件不一致")
        self.assertGreaterEqual(len(paths), 6)

    def test_next_sidecar_is_max_plus_one(self):
        nums = [int(p.name.rsplit("-", 1)[-1].split(".")[0]) for p in pack_io.sidecar_paths()]
        self.assertEqual(f"moe-2025-teaching-support-v2-{max(nums) + 1:02d}.json",
                         pack_io.next_sidecar_path().name)

    def test_index_points_at_existing_pack_id(self):
        doc = pack_io.load_json(pack_io.sidecar_index_path())
        pack = pack_io.load_json(pack_io.pack_path())
        self.assertEqual(pack["packId"], doc["packId"])


class StagingTopologyTest(unittest.TestCase):
    def setUp(self):
        # 不碰共享 staging：本类只测纯函数与 tmp 种子
        self._tmps: list[Path] = []

    def tearDown(self):
        for tmp in self._tmps:
            shutil.rmtree(tmp, ignore_errors=True)

    def _repo_tmpdir(self, prefix: str) -> Path:
        tmp = Path(tempfile.mkdtemp(prefix=prefix, dir=pack_io.REPO / "build"))
        self._tmps.append(tmp)
        return tmp

    def test_seed_copies_release_family_verbatim(self):
        dst = self._repo_tmpdir("kb-seed-test-")
        pack_io.seed_staging(dst)
        for src in sorted(pack_io.release_dir().glob("*.json")):
            out = dst / src.name
            self.assertTrue(out.exists(), f"种子缺文件 {src.name}")
            self.assertEqual(src.read_bytes(), out.read_bytes(), f"种子内容不一致 {src.name}")

    def test_seed_overwrites_existing_family_files(self):
        """staging 是镜像：已存在的同名 JSON 必须被成品种子覆盖（基线永远来自成品）。"""
        dst = self._repo_tmpdir("kb-seed-test-")
        pack_io.seed_staging(dst)
        marker = dst / pack_io.PACK_NAME
        self.assertTrue(marker.exists())
        # 模拟 staging 被改脏，再种子必须回到成品内容
        marker.write_text("{}", encoding="utf-8")
        pack_io.seed_staging(dst)
        self.assertEqual(
            (pack_io.release_dir() / pack_io.PACK_NAME).read_bytes(),
            marker.read_bytes(),
        )

    def test_work_dir_default_is_staging(self):
        # 共享工作树里 staging 可能不存在（干净检出）——work_dir 应懒种子并指向它
        pack_io.reset_directory()
        self.assertEqual(pack_io.work_dir(), pack_io.STAGING_DIR)

    def test_use_directory_overrides_all_derived_paths(self):
        dst = self._repo_tmpdir("kb-override-test-")
        pack_io.seed_staging(dst)
        pack_io.use_directory(dst)
        try:
            self.assertEqual(pack_io.work_dir(), dst)
            self.assertEqual(pack_io.pack_path(), dst / pack_io.PACK_NAME)
            self.assertEqual(pack_io.sidecar_index_path(), dst / pack_io.SIDECAR_INDEX_NAME)
            self.assertTrue(all(p.parent == dst for p in pack_io.sidecar_paths()))
        finally:
            pack_io.reset_directory()
        self.assertEqual(pack_io.work_dir(), pack_io.STAGING_DIR)

    def test_dump_json_refuses_paths_outside_repo(self):
        outside = Path(tempfile.gettempdir()) / "kb-escape-test.json"
        with self.assertRaises(ValueError):
            pack_io.dump_json({"a": 1}, outside)
        self.assertFalse(outside.exists(), "拒写必须发生在落盘之前")


if __name__ == "__main__":
    unittest.main()
