from __future__ import annotations

import hashlib
import tempfile
import unittest
import zipfile
from pathlib import Path

from teaching_sources.epub_audit import audit_manifest
from teaching_sources.review_inventory import build_review_inventory


LICENSE_URI = "https://creativecommons.org/licenses/by/4.0/"
SOURCE_ID = "candidate:test:teaching-epub"


def _write_epub(
    path: Path,
    *,
    rights_text: str,
    chapter_text: str,
    container_xml: str | None = None,
) -> None:
    container = container_xml or """<?xml version="1.0"?>
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
        self.assertEqual(
            {"example": 1, "writingProcess": 1, "activity": 1},
            artifact["requiredTeachingMarkers"],
        )
        self.assertEqual(0, summary["formalKnowledgeCoverageContribution"])

    def test_rejects_a_container_that_declares_an_entity_instead_of_expanding_it(self) -> None:
        # 这两处原先靠 `XMLParser(resolve_entities=False)` 防实体展开，而那个关键字在
        # Python 3.13 的 stdlib 里**不存在**——防线从未生效（一执行就 TypeError），
        # 于是四个用例也从来没跑过。改成"拒绝带 DTD 的文档"之后，这条钉住**它真的会拒绝**：
        # 若哪天有人把守卫换成默认解析，`&b;` 会被展开成 64 个字符而本条仍然"通过"，
        # 所以夹具特意声明了嵌套实体（billion-laughs 的形状），断言的是**拒绝**而不是解析结果。
        entity_container = """<?xml version="1.0"?>
<!DOCTYPE container [
  <!ENTITY a "AAAA">
  <!ENTITY b "&a;&a;&a;&a;&a;&a;&a;&a;">
]>
<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles>
    <rootfile full-path="book.opf" media-type="application/oebps-package+xml"/>
  </rootfiles>
</container>"""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            path = root / "fixture.epub"
            _write_epub(
                path,
                rights_text=f"This work uses {LICENSE_URI}",
                chapter_text="The writing process is explained through an example.",
                container_xml=entity_container,
            )
            payload = path.read_bytes()
            fingerprint = hashlib.sha256(payload).hexdigest().upper()
            register = {
                "registerId": "test-source-register",
                "sources": [_source(len(payload), fingerprint)],
            }

            with self.assertRaisesRegex(ValueError, "declares a DTD or entity"):
                audit_manifest(root, _manifest(len(payload), fingerprint), register)

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


if __name__ == "__main__":
    unittest.main()
