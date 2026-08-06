from __future__ import annotations

import hashlib
import io
import json
import tempfile
import unittest
import zipfile
from contextlib import redirect_stdout
from pathlib import Path

from teaching_sources.epub_audit import audit_manifest, normalize_license_uri
from teaching_sources.epub_cli import main as audit_epub_cli
from teaching_sources.review_inventory import build_review_inventory


LICENSE_URI = "https://creativecommons.org/licenses/by/4.0/"
SOURCE_ID = "candidate:siyavula:test-teaching-epub"


def _write_epub(
    path: Path,
    *,
    rights_text: str,
    chapter_text: str,
) -> None:
    container = """<?xml version="1.0"?>
<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles>
    <rootfile full-path="book.opf" media-type="application/oebps-package+xml"/>
  </rootfiles>
</container>"""
    package = """<?xml version="1.0"?>
<package xmlns="http://www.idpf.org/2007/opf">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:title>Teaching Evidence Fixture</dc:title>
    <dc:language>en</dc:language>
    <dc:publisher>Siyavula Education</dc:publisher>
    <dc:identifier>www.siyavula.com.test-teaching-fixture</dc:identifier>
  </metadata>
</package>"""
    with zipfile.ZipFile(path, "w") as archive:
        archive.writestr(
            "mimetype",
            "application/epub+zip",
            compress_type=zipfile.ZIP_STORED,
        )
        archive.writestr("META-INF/container.xml", container)
        archive.writestr("book.opf", package)
        archive.writestr("OEBPS/copyright.xhtml", rights_text)
        archive.writestr("OEBPS/chapter.xhtml", chapter_text)


def _source(length: int, fingerprint: str) -> dict[str, object]:
    return {
        "sourceId": SOURCE_ID,
        "subjects": ["ENGLISH"],
        "purposes": [
            "TEACHING_REFERENCE",
            "METHOD_REFERENCE",
            "WORKED_EXAMPLE_REFERENCE",
        ],
        "acquisitionState": "ACQUIRED_UNREVIEWED",
        "contentLengthBytes": length,
        "contentFingerprint": fingerprint,
        "licenseStatus": "LICENSED",
        "licenseUri": LICENSE_URI,
        "contentUsePolicy": "ADAPTATION_ALLOWED",
        "modelUsePolicy": "FULL_CONTENT_ALLOWED",
        "publisher": "Siyavula Education",
        "independenceGroup": "siyavula",
        "discoveryUri": "https://www.siyavula.com/test-discovery",
        "documentUri": "https://www.siyavula.com/test-fixture.epub",
    }


def _manifest(length: int, fingerprint: str) -> dict[str, object]:
    return {
        "schemaVersion": 1,
        "manifestId": "test-open-teaching-epub-manifest",
        "sourceRegisterId": "test-source-register",
        "updatedAtEpochMillis": 1,
        "artifacts": [
            {
                "sourceId": SOURCE_ID,
                "localPath": "fixture.epub",
                "expectedBytes": length,
                "expectedSha256": fingerprint,
                "expectedMediaType": "application/epub+zip",
                "expectedLicenseUri": LICENSE_URI,
                "expectedLanguage": "en",
                "expectedSubjects": ["ENGLISH"],
                "requiredTeachingMarkers": {
                    "example": 1,
                    "writingProcess": 1,
                    "activity": 1,
                },
                "expectedProviderIdentity": "siyavula",
            }
        ],
    }


class TeachingSourceAuditTest(unittest.TestCase):
    def _fixture(
        self,
        root: Path,
        *,
        rights_text: str,
        chapter_text: str,
    ) -> tuple[dict[str, object], dict[str, object]]:
        path = root / "fixture.epub"
        _write_epub(path, rights_text=rights_text, chapter_text=chapter_text)
        payload = path.read_bytes()
        fingerprint = hashlib.sha256(payload).hexdigest().upper()
        register = {
            "registerId": "test-source-register",
            "sources": [_source(len(payload), fingerprint)],
        }
        return _manifest(len(payload), fingerprint), register

    def test_accepts_source_specific_teaching_evidence(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest, register = self._fixture(
                root,
                rights_text=f"This work uses {LICENSE_URI}",
                chapter_text=(
                    "The writing process is explained through an example "
                    "and a guided activity."
                ),
            )

            report, summary = audit_manifest(root, manifest, register)

        artifact = report["artifacts"][0]
        self.assertEqual(1, summary["acquiredArtifactCount"])
        self.assertEqual(
            ["OEBPS/copyright.xhtml"],
            artifact["licenseEvidencePaths"],
        )
        self.assertEqual(LICENSE_URI, artifact["normalizedLicenseUri"])
        self.assertEqual("siyavula", artifact["canonicalProviderIdentity"])
        self.assertEqual("REVIEW_REQUIRED", artifact["rightsReviewState"])
        self.assertNotIn("verified", json.dumps(report).casefold())
        self.assertEqual(
            {"example": 1, "writingProcess": 1, "activity": 1},
            artifact["requiredTeachingMarkers"],
        )
        self.assertEqual(0, summary["formalKnowledgeCoverageContribution"])

    def test_rejects_license_uri_outside_rights_document(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest, register = self._fixture(
                root,
                rights_text="Copyright information without a license URI.",
                chapter_text=(
                    f"{LICENSE_URI} The writing process uses an example and activity."
                ),
            )

            with self.assertRaisesRegex(
                ValueError,
                "absent from an EPUB rights document",
            ):
                audit_manifest(root, manifest, register)

    def test_rejects_empty_unallowlisted_and_negative_license_evidence(self) -> None:
        cases = (
            ("", f"Licensed under {LICENSE_URI}"),
            (
                "http://creativecommons.org/licenses/by/4.0/",
                "Licensed under http://creativecommons.org/licenses/by/4.0/",
            ),
            ("https://creativecommons.org/licenses/by-nc/4.0/", "Licensed"),
            (LICENSE_URI, f"All rights reserved. Licensed under {LICENSE_URI}"),
            (LICENSE_URI, f"All <b>rights</b>&nbsp;reserved. {LICENSE_URI}"),
            (LICENSE_URI, f'All-rights-reserved. <a href="{LICENSE_URI}">terms</a>'),
            (LICENSE_URI, f"This work is not licensed under {LICENSE_URI}"),
            (LICENSE_URI, f"Copyright notice. <!-- Licensed under {LICENSE_URI} -->"),
            (LICENSE_URI, f'Copyright notice. <img src="{LICENSE_URI}"/>'),
            (LICENSE_URI, f'Copyright notice. <a hidden href="{LICENSE_URI}">terms</a>'),
            (
                LICENSE_URI,
                f'Copyright notice. <a style="display:none" href="{LICENSE_URI}">terms</a>',
            ),
            (
                LICENSE_URI,
                f'<link rel="license" href="{LICENSE_URI}"/>',
            ),
            (
                LICENSE_URI,
                f'<a href="{LICENSE_URI}"></a>',
            ),
            (
                LICENSE_URI,
                '<a href="http://creativecommons.org/licenses/by/4.0/">terms</a>',
            ),
        )
        for configured_uri, rights_text in cases:
            with self.subTest(configured_uri=configured_uri, rights_text=rights_text):
                with tempfile.TemporaryDirectory() as directory:
                    root = Path(directory)
                    manifest, register = self._fixture(
                        root,
                        rights_text=rights_text,
                        chapter_text=(
                            "The writing process uses an example and an activity."
                        ),
                    )
                    manifest["artifacts"][0]["expectedLicenseUri"] = configured_uri
                    register["sources"][0]["licenseUri"] = configured_uri
                    with self.assertRaises(ValueError):
                        audit_manifest(root, manifest, register)

    def test_normalizes_allowlisted_license_uri_before_comparison(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest, register = self._fixture(
                root,
                rights_text=f"Licensed under {LICENSE_URI}",
                chapter_text=(
                    "The writing process uses an example and an activity."
                ),
            )
            manifest["artifacts"][0]["expectedLicenseUri"] = (
                "HTTPS://CreativeCommons.org/licenses/by/4.0"
            )
            register["sources"][0]["licenseUri"] = LICENSE_URI

            report, _ = audit_manifest(root, manifest, register)

        self.assertEqual(
            LICENSE_URI,
            report["artifacts"][0]["normalizedLicenseUri"],
        )

    def test_accepts_legacy_http_only_as_visible_license_evidence(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest, register = self._fixture(
                root,
                rights_text=(
                    "Licensed under "
                    "http://creativecommons.org/licenses/by/4.0/"
                ),
                chapter_text=(
                    "The writing process uses an example and an activity."
                ),
            )

            report, _ = audit_manifest(root, manifest, register)

        self.assertEqual(
            LICENSE_URI,
            report["artifacts"][0]["normalizedLicenseUri"],
        )

    def test_accepts_visible_https_anchor_but_not_invisible_link_metadata(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest, register = self._fixture(
                root,
                rights_text=f'<a href="{LICENSE_URI}">license terms</a>',
                chapter_text=(
                    "The writing process uses an example and an activity."
                ),
            )

            report, _ = audit_manifest(root, manifest, register)

        self.assertEqual(
            ["OEBPS/copyright.xhtml"],
            report["artifacts"][0]["licenseEvidencePaths"],
        )

    def test_checked_in_manifest_metadata_summary_gate(self) -> None:
        project_root = Path(__file__).parents[2]
        manifest = json.loads(
            (project_root / "knowledge-production/open-teaching-epub-manifest-2026-v1.json").read_text(
                "utf-8"
            )
        )
        register = json.loads(
            (
                project_root
                / "core/data/src/main/resources/knowledge/source-register-2025-v1.json"
            ).read_text("utf-8")
        )
        sources = {source["sourceId"]: source for source in register["sources"]}

        self.assertEqual(10, len(manifest["artifacts"]))
        self.assertEqual(
            len(manifest["artifacts"]),
            len({artifact["sourceId"] for artifact in manifest["artifacts"]}),
        )
        for artifact in manifest["artifacts"]:
            source = sources[artifact["sourceId"]]
            self.assertEqual(
                artifact["expectedProviderIdentity"],
                artifact["sourceId"].split(":")[1],
            )
            self.assertEqual(
                artifact["expectedProviderIdentity"],
                source["independenceGroup"],
            )
            self.assertEqual(
                normalize_license_uri(artifact["expectedLicenseUri"]),
                normalize_license_uri(source["licenseUri"]),
            )
            self.assertTrue(source["publisher"].strip())
            self.assertTrue(source["independenceGroup"].strip())

    def test_provider_identity_is_required_and_must_match_register(self) -> None:
        for mutation in ("missing", "manifest-alias", "register-alias"):
            with self.subTest(mutation=mutation):
                with tempfile.TemporaryDirectory() as directory:
                    root = Path(directory)
                    manifest, register = self._fixture(
                        root,
                        rights_text=f"Licensed under {LICENSE_URI}",
                        chapter_text=(
                            "The writing process uses an example and an activity."
                        ),
                    )
                    if mutation == "missing":
                        del manifest["artifacts"][0]["expectedProviderIdentity"]
                    elif mutation == "manifest-alias":
                        manifest["artifacts"][0]["expectedProviderIdentity"] = "alias"
                    else:
                        register["sources"][0]["independenceGroup"] = "alias"
                    with self.assertRaises(ValueError):
                        audit_manifest(root, manifest, register)

    def test_rejects_missing_source_specific_marker(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest, register = self._fixture(
                root,
                rights_text=f"This work uses {LICENSE_URI}",
                chapter_text="This example omits the other required structures.",
            )

            with self.assertRaisesRegex(
                ValueError,
                "lacks required teaching-structure evidence",
            ):
                audit_manifest(root, manifest, register)

    def test_review_inventory_is_locator_only_and_has_no_question_authority(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest, register = self._fixture(
                root,
                rights_text=f"This work uses {LICENSE_URI}",
                chapter_text=(
                    "The writing process uses a method, an example, an activity, "
                    "and a complete solution."
                ),
            )

            inventory, decisions, summary = build_review_inventory(
                root,
                manifest,
                register,
            )

        boundary = inventory["boundary"]
        candidate = inventory["candidates"][0]
        self.assertGreater(summary["candidateSectionCount"], 0)
        self.assertFalse(boundary["rawTeachingTextIncluded"])
        self.assertFalse(boundary["questionBankAuthority"])
        self.assertFalse(boundary["learningEvidenceWriteAuthority"])
        self.assertNotIn("contentMarkdown", candidate)
        self.assertIn("METHOD_MODEL", candidate["candidateTeachingForms"])
        self.assertIn("WORKED_EXAMPLE", candidate["candidateTeachingForms"])
        self.assertIn("COMPLETE_SOLUTION", candidate["candidateTeachingForms"])
        self.assertEqual("NOT_STARTED", decisions["reviewState"])
        self.assertEqual([], decisions["decisions"])
        self.assertFalse(decisions["automaticKnowledgePackMutationAllowed"])

    def test_cli_can_keep_private_artifacts_outside_the_checked_out_project(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            project_root = root / "worktree"
            artifact_root = root / "private"
            project_root.mkdir()
            artifact_root.mkdir()
            manifest, register = self._fixture(
                artifact_root,
                rights_text=f"This work uses {LICENSE_URI}",
                chapter_text=(
                    "The writing process is explained through an example "
                    "and a guided activity."
                ),
            )
            (project_root / "manifest.json").write_text(
                json.dumps(manifest),
                encoding="utf-8",
            )
            (project_root / "register.json").write_text(
                json.dumps(register),
                encoding="utf-8",
            )

            with redirect_stdout(io.StringIO()):
                exit_code = audit_epub_cli(
                    [
                        "--project-root",
                        str(project_root),
                        "--artifact-root",
                        str(artifact_root),
                        "--manifest",
                        "manifest.json",
                        "--source-register",
                        "register.json",
                        "--output",
                        "report.json",
                        "--write",
                    ]
                )

            self.assertEqual(0, exit_code)
            self.assertTrue((project_root / "report.json").is_file())
            self.assertFalse((artifact_root / "report.json").exists())


if __name__ == "__main__":
    unittest.main()
