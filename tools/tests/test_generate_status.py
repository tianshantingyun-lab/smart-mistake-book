"""Tests for the machine-verifiable status report (audit R-13).

The report's contract is the one its own docstring states: a value with no
measurement source renders as NOT_MEASURED rather than a fabrication. The overall
verdict used to sit outside that rule — it fell back to "success" whenever the
workflow that sets JOB_STATUS was not the caller, so a run that measured nothing
still produced a PASS.
"""

from __future__ import annotations

import os
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

# tools/ci is a script directory rather than a package, so the subject does not
# sit on the path the way tools/ itself does for the sibling test modules.
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "ci"))

import generate_status


class OverallStatusTest(unittest.TestCase):
    def test_a_caller_that_measured_nothing_gets_no_pass(self):
        self.assertEqual(
            generate_status.NOT_MEASURED,
            generate_status.overall_status(None),
        )

    def test_the_ci_job_conclusion_is_still_the_verdict(self):
        self.assertEqual("PASS", generate_status.overall_status("success"))
        self.assertEqual("FAIL", generate_status.overall_status("failure"))
        self.assertEqual("FAIL", generate_status.overall_status("cancelled"))


class RenderedReportTest(unittest.TestCase):
    """main() renders the real template against a throwaway build-output tree."""

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.repo = Path(self._tmp.name)
        self._saved = (generate_status.REPO, generate_status.OUTPUT)
        generate_status.REPO = self.repo
        generate_status.OUTPUT = self.repo / "docs" / "status.md"
        generate_status.OUTPUT.parent.mkdir(parents=True)

    def tearDown(self):
        generate_status.REPO, generate_status.OUTPUT = self._saved
        self._tmp.cleanup()

    def render(self, job_status: str | None) -> str:
        with mock.patch.dict(os.environ):
            if job_status is None:
                os.environ.pop("JOB_STATUS", None)
            else:
                os.environ["JOB_STATUS"] = job_status
            self.assertEqual(0, generate_status.main())
        return generate_status.OUTPUT.read_text(encoding="utf-8")

    def verdict_of(self, rendered: str) -> str:
        # Anchor on the heading *prefix* and pick the first bolded token after it,
        # rather than assuming the heading line ends at "Status". The heading
        # carries a scope qualifier (N-31) and the verdict stays findable if that
        # qualifier is ever reworded.
        after = rendered.split("## Overall Status", 1)[1]
        for line in after.splitlines():
            stripped = line.strip()
            if stripped.startswith("**") and stripped.endswith("**"):
                return stripped
        self.fail("the rendered report has no verdict line")

    def test_a_run_with_no_job_status_does_not_claim_a_pass(self):
        self.assertEqual("**NOT_MEASURED**", self.verdict_of(self.render(None)))

    def test_a_failing_ci_job_still_renders_fail(self):
        self.assertEqual("**FAIL**", self.verdict_of(self.render("failure")))

    def test_a_green_ci_job_still_renders_pass(self):
        self.assertEqual("**PASS**", self.verdict_of(self.render("success")))

    def test_a_measured_aab_fills_the_column_the_template_names(self):
        # The size was read and then written to a key no template names
        # ("AAB_SIZE_PLACEHOLDER"), so this row reported NOT_MEASURED for an
        # artifact that had in fact been measured.
        aab = (
            self.repo
            / "app"
            / "build"
            / "outputs"
            / "bundle"
            / "localFirstRelease"
            / "app-localFirst-release.aab"
        )
        aab.parent.mkdir(parents=True)
        aab.write_bytes(b"\0" * (3 * 1024 * 1024))

        rendered = self.render("success")

        self.assertIn(
            "| `localFirst` | NOT_MEASURED | NOT_MEASURED | 3.0 MB |",
            rendered,
        )


if __name__ == "__main__":
    unittest.main()
