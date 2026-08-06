import json
import re
import subprocess
import unittest
from pathlib import Path

TOOLS_ROOT = Path(__file__).resolve().parents[1]
REGISTRY = TOOLS_ROOT / "scenario_to_test_registry.json"


class ScenarioMappingTest(unittest.TestCase):
    def test_scenario_registry_is_complete_and_machine_checkable(self):
        entries = json.loads(REGISTRY.read_text(encoding="utf-8"))
        self.assertGreaterEqual(len(entries), 10)

        ids = [entry["id"] for entry in entries]
        self.assertEqual(len(ids), len(set(ids)), "scenario ids must be unique")
        self.assertTrue(all(re.fullmatch(r"[a-z0-9-]+", value) for value in ids))

        required_fields = (
            "scenario",
            "entry",
            "userVisibleBehavior",
            "dataChange",
            "retryStrategy",
            "testRef",
        )
        for entry in entries:
            for field in required_fields:
                self.assertTrue(entry.get(field), f"{entry['id']} is missing {field}")
            self.assertIn("#", entry["testRef"], f"{entry['id']} testRef must be Class#method")
            text = " ".join(str(entry[field]) for field in required_fields)
            self.assertNotIn("以后再说", text)
            self.assertNotIn("TODO", text)
            self.assertNotIn("TBD", text)

    def test_registry_test_refs_resolve_to_real_test_sources(self):
        entries = json.loads(REGISTRY.read_text(encoding="utf-8"))
        for entry in entries:
            test_class, test_method = entry["testRef"].split("#", 1)
            matches = self._resolve_test_sources(test_class)
            self.assertTrue(
                matches,
                f"{entry['id']} test class {test_class} is not referenced by a test source",
            )
            method_matches = []
            for path in matches:
                content = Path(path).read_text(encoding="utf-8", errors="replace")
                if test_method in content:
                    method_matches.append(path)
            self.assertTrue(
                method_matches,
                f"{entry['id']} test method {test_method} is not present in {test_class}",
            )

    def test_registry_test_refs_are_annotated_gradle_tests(self):
        entries = json.loads(REGISTRY.read_text(encoding="utf-8"))
        for entry in entries:
            test_class, test_method = entry["testRef"].split("#", 1)
            matches = self._resolve_test_sources(test_class)
            self.assertTrue(
                any(self._is_annotated_test(path, test_method) for path in matches),
                f"{entry['id']} test method {test_method} is not an annotated Gradle test",
            )

    def _resolve_test_sources(self, test_class):
        result = subprocess.run(
            [
                "rg",
                "-l",
                "-g",
                "*.kt",
                "-g",
                "*.java",
                test_class,
                str(TOOLS_ROOT.parent),
            ],
            capture_output=True,
        )
        stdout = result.stdout.decode("utf-8", errors="replace")
        return [
            line.strip()
            for line in stdout.splitlines()
            if "/src/test/" in line.replace("\\", "/")
            or "/src/androidTest/" in line.replace("\\", "/")
        ]

    def _is_annotated_test(self, path, test_method):
        content = Path(path).read_text(encoding="utf-8", errors="replace")
        for pattern in (
            rf"fun `{re.escape(test_method)}`",
            rf"fun {re.escape(test_method)}\(",
        ):
            for match in re.finditer(pattern, content):
                prefix = content[max(0, match.start() - 400) : match.start()]
                annotations = re.findall(r"@([A-Za-z_][A-Za-z0-9_]*)", prefix)
                if any(
                    annotation in ("Test", "ParameterizedTest", "TestFactory")
                    for annotation in annotations
                ):
                    return True
        return False
