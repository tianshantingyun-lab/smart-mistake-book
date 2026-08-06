from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from audit_generalization import audit


class GeneralizationAuditTest(unittest.TestCase):
    def write_source(self, root: Path, source: str, *, source_set: str = "main") -> None:
        target = root / "module" / "src" / source_set / "kotlin" / "Policy.kt"
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(source, encoding="utf-8")

    def test_accepts_structural_identity_checks_and_interpolated_contract_fixtures(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.write_source(
                root,
                """
                fun valid(problemId: String, expectedProblemId: String, suffix: String) {
                    require(problemId == expectedProblemId)
                    require(problemId == "problem-$suffix")
                }
                """,
            )
            self.assertEqual([], audit(root))

    def test_rejects_literal_question_identity_comparisons_in_either_direction(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.write_source(
                root,
                """
                fun invalid(problemId: String, imageHash: String) {
                    if (problemId == "q1") return
                    if ("known-image-digest" == imageHash) return
                }
                """,
            )
            self.assertEqual(
                [
                    "literal-question-identity-comparison",
                    "literal-question-identity-comparison",
                ],
                [violation.rule for violation in audit(root)],
            )

    def test_rejects_literal_when_and_string_method_branches(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.write_source(
                root,
                """
                fun invalid(questionId: String, imageFingerprint: String) {
                    when (questionId) {
                        "seed-question" -> return
                    }
                    if (imageFingerprint.startsWith("deadbeefcafebabe")) return
                }
                """,
            )
            self.assertEqual(
                {
                    "literal-question-identity-method-branch",
                    "literal-question-identity-when",
                },
                {violation.rule for violation in audit(root)},
            )

    def test_ignores_comments_and_test_source_sets(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.write_source(
                root,
                '// if (problemId == "comment-only-question") return\n',
            )
            self.write_source(
                root,
                'fun fixture(problemId: String) = problemId == "test-question"\n',
                source_set="test",
            )
            self.assertEqual([], audit(root))

    def test_repository_production_sources_have_no_literal_identity_branch(self) -> None:
        repository_root = Path(__file__).resolve().parents[2]
        self.assertEqual([], audit(repository_root))


if __name__ == "__main__":
    unittest.main()
