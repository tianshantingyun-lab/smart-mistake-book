# -*- coding: utf-8 -*-
"""pack_io：sidecar 索引驱动的文件清单与硬编码 glob 必须一致。"""

from __future__ import annotations

import re
import unittest

from kb_build import pack_io


class SidecarIndexTest(unittest.TestCase):
    def test_index_matches_disk(self):
        paths = pack_io.sidecar_paths()
        on_disk = sorted(
            p for p in pack_io.KNOWLEDGE_DIR.glob("moe-2025-teaching-support-v2-*.json")
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


if __name__ == "__main__":
    unittest.main()
