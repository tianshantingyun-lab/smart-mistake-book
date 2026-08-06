import re
import unittest
from pathlib import Path

TOOLS_ROOT = Path(__file__).resolve().parents[1]
REGISTER = TOOLS_ROOT.parent / "systemic_root_cause_register.md"


class RootCauseRegisterTest(unittest.TestCase):
    def test_register_is_complete_and_machine_checkable(self):
        lines = REGISTER.read_text(encoding="utf-8").splitlines()
        sections = []
        current = None
        for line in lines:
            match = re.match(r"^## (R\d+) (.+)$", line)
            if match:
                current = {"id": match.group(1), "title": match.group(2), "lines": []}
                sections.append(current)
            elif current is not None:
                current["lines"].append(line)

        self.assertGreaterEqual(len(sections), 20)
        self.assertEqual([entry["id"] for entry in sections], sorted(entry["id"] for entry in sections))
        self.assertEqual(len({entry["id"] for entry in sections}), len(sections))

        for entry in sections:
            body = "\n".join(entry["lines"])
            for marker in ("- **根因：**", "- **统一修复：**", "- **归属：**", "- **状态：**"):
                self.assertIn(marker, body, f"{entry['id']} is missing {marker}")
            required_markers = (
                "- **根因：**",
                "- **统一修复：**",
                "- **归属：**",
                "- **状态：**",
            )
            evidence_fields = [
                line
                for line in entry["lines"]
                if line.startswith("- **")
                and not any(line.startswith(marker) for marker in required_markers)
            ]
            self.assertTrue(
                evidence_fields,
                f"{entry['id']} is missing an evidence marker",
            )
            owner_line = next(
                line for line in entry["lines"] if line.startswith("- **归属：**")
            )
            owners = owner_line.removeprefix("- **归属：**").strip()
            self.assertTrue(
                re.search(r"[A-Z0-9]{2}", owners),
                f"{entry['id']} owner packages are missing",
            )
            self.assertNotIn("以后再说", body)
            self.assertNotIn("TODO", body)
            self.assertNotIn("TBD", body)
