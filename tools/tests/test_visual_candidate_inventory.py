from __future__ import annotations

import hashlib
import io
import json
import tempfile
import unittest
import zipfile
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path
from unittest.mock import patch

from PIL import Image, ImageDraw

from teaching_sources.visual_candidate_cli import main as visual_candidate_cli
from teaching_sources.visual_candidate_inventory import (
    _svg_metrics,
    build_visual_candidate_inventory,
)


LICENSE_URI = "https://creativecommons.org/licenses/by/4.0/"
SOURCE_ID = "candidate:siyavula:test-visual-reference"


def _png_bytes(variant: int = 0) -> bytes:
    image = Image.new("RGB", (320, 220), "white")
    draw = ImageDraw.Draw(image)
    for offset in range(12):
        x = 12 + offset * 22
        draw.line((x, 12, 300 - offset * 3, 200), fill=(20, 80 + offset * 8, 120))
    draw.rectangle((45, 45, 155, 150), outline=(180, variant * 20, 30), width=4)
    stream = io.BytesIO()
    image.save(stream, format="PNG")
    return stream.getvalue()


def _noise_png_bytes() -> bytes:
    raw = bytes((index * 73 + index // 11 * 29) % 256 for index in range(320 * 220 * 3))
    image = Image.frombytes("RGB", (320, 220), raw)
    stream = io.BytesIO()
    image.save(stream, format="PNG")
    return stream.getvalue()


def _svg_bytes(*, hidden: bool = False, zero_area: bool = False) -> bytes:
    style = ' style="display:none"' if hidden else ""
    width = "0" if zero_area else "120"
    lines = "" if zero_area else "".join(
        f'<line x1="10" y1="{10 + offset * 7}" x2="190" '
        f'y2="{20 + offset * 7}" stroke="black" stroke-width="2"/>'
        for offset in range(12)
    )
    return (
        f'<svg xmlns="http://www.w3.org/2000/svg" width="200" height="120"{style}>'
        f'<rect x="20" y="20" width="{width}" height="70" fill="none" '
        f'stroke="black"/>{lines}</svg>'
    ).encode("utf-8")


def _epub_payload(
    *,
    traversal: bool = False,
    corrupted: bool = False,
    chapter_text: str | None = None,
    image_entries: dict[str, bytes] | None = None,
    additional_documents: dict[str, str] | None = None,
    opf_publisher: str = "Siyavula Education",
    opf_identifier: str = "www.siyavula.com.test-visual-fixture",
) -> bytes:
    container = """<?xml version="1.0"?>
<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles><rootfile full-path="book.opf" media-type="application/oebps-package+xml"/></rootfiles>
</container>"""
    package = f"""<?xml version="1.0"?>
<package xmlns="http://www.idpf.org/2007/opf">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:title>Visual Inventory Test Source</dc:title><dc:language>en</dc:language>
    <dc:publisher>{opf_publisher}</dc:publisher>
    <dc:identifier>{opf_identifier}</dc:identifier>
  </metadata>
</package>"""
    chapter = chapter_text or """<html xmlns="http://www.w3.org/1999/xhtml"><body>
<p>This worked example includes a solution.</p>
<figure><img src="media/a.png"/><figcaption>System relation diagram.</figcaption>
<p class="credit">Illustration credit: Example Author.</p></figure>
<figure><img src="media/b.png"/><figcaption>Repeated rendering.</figcaption></figure>
<figure><img src="media/bad.png"/></figure>
</body></html>"""
    stream = io.BytesIO()
    with zipfile.ZipFile(stream, "w") as archive:
        archive.writestr(
            "mimetype",
            "application/epub+zip",
            compress_type=zipfile.ZIP_STORED,
        )
        archive.writestr("META-INF/container.xml", container)
        archive.writestr("book.opf", package)
        archive.writestr(
            "OEBPS/copyright.xhtml",
            f"<html><body>Licensed under {LICENSE_URI}</body></html>",
        )
        archive.writestr("OEBPS/chapter.xhtml", chapter)
        default_images = {
            "OEBPS/media/a.png": _png_bytes(),
            "OEBPS/media/b.png": _png_bytes(),
            "OEBPS/media/bad.png": b"not an image" if corrupted else _png_bytes(1),
        }
        for name, image in (image_entries or default_images).items():
            archive.writestr(name, image)
        for name, document in (additional_documents or {}).items():
            archive.writestr(name, document)
        if traversal:
            archive.writestr("../escape.png", _png_bytes())
    return stream.getvalue()


def _fixture(
    root: Path,
    *,
    traversal: bool = False,
    corrupted: bool = False,
    chapter_text: str | None = None,
    image_entries: dict[str, bytes] | None = None,
    additional_documents: dict[str, str] | None = None,
    opf_publisher: str = "Siyavula Education",
    opf_identifier: str = "www.siyavula.com.test-visual-fixture",
    source_publisher: str = "Siyavula Education",
):
    artifact = root / ".artifacts" / "source.epub"
    artifact.parent.mkdir(parents=True)
    payload = _epub_payload(
        traversal=traversal,
        corrupted=corrupted,
        chapter_text=chapter_text,
        image_entries=image_entries,
        additional_documents=additional_documents,
        opf_publisher=opf_publisher,
        opf_identifier=opf_identifier,
    )
    artifact.write_bytes(payload)
    fingerprint = hashlib.sha256(payload).hexdigest().upper()
    manifest = {
        "schemaVersion": 1,
        "manifestId": "test-visual-manifest",
        "sourceRegisterId": "test-source-register",
        "updatedAtEpochMillis": 42,
        "artifacts": [
            {
                "sourceId": SOURCE_ID,
                "localPath": ".artifacts/source.epub",
                "expectedBytes": len(payload),
                "expectedSha256": fingerprint,
                "expectedMediaType": "application/epub+zip",
                "expectedLicenseUri": LICENSE_URI,
                "expectedLanguage": "en",
                "expectedSubjects": ["PHYSICS"],
                "requiredTeachingMarkers": {"workedExample": 1, "solution": 1},
                "expectedProviderIdentity": "siyavula",
            }
        ],
    }
    register = {
        "registerId": "test-source-register",
        "sources": [
            {
                "sourceId": SOURCE_ID,
                "subjects": ["PHYSICS"],
                "purposes": [
                    "TEACHING_REFERENCE",
                    "METHOD_REFERENCE",
                    "WORKED_EXAMPLE_REFERENCE",
                ],
                "acquisitionState": "ACQUIRED_UNREVIEWED",
                "contentLengthBytes": len(payload),
                "contentFingerprint": fingerprint,
                "licenseStatus": "LICENSED",
                "licenseUri": LICENSE_URI,
                "contentUsePolicy": "ADAPTATION_ALLOWED",
                "modelUsePolicy": "FULL_CONTENT_ALLOWED",
                "publisher": source_publisher,
                "independenceGroup": "siyavula",
                "discoveryUri": "https://www.siyavula.com/test-discovery",
                "documentUri": "https://www.siyavula.com/test-source.epub",
            }
        ],
    }
    return manifest, register


class VisualCandidateInventoryTest(unittest.TestCase):
    def test_inventory_is_deterministic_deduplicated_and_locator_only(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest, register = _fixture(root, corrupted=True)
            first, _ = build_visual_candidate_inventory(
                root, manifest, register, candidate_limit=10, per_source_limit=10
            )
            second, _ = build_visual_candidate_inventory(
                root, manifest, register, candidate_limit=10, per_source_limit=10
            )

        self.assertEqual(first, second)
        self.assertEqual(1, first["scanIsolation"]["duplicateImageCount"])
        self.assertEqual(1, first["scanIsolation"]["isolatedImageCount"])
        candidate = first["candidates"][0]
        self.assertEqual("REVIEW_REQUIRED", candidate["rightsReviewState"])
        self.assertEqual("siyavula", candidate["canonicalProviderIdentity"])
        self.assertEqual(LICENSE_URI, candidate["bookLicense"]["licenseUri"])
        self.assertEqual("PNG", candidate["image"]["format"])
        self.assertIn("domPathSha256", candidate["xhtmlLocator"])
        serialized = json.dumps(first)
        self.assertNotIn("System relation diagram", serialized)
        self.assertNotIn("Example Author", serialized)
        self.assertNotIn("imageBytes", candidate)
        self.assertTrue(candidate["captionEvidence"])
        self.assertTrue(candidate["creditEvidence"])

    def test_disabled_provider_cannot_hide_behind_an_alias_source_id(self) -> None:
        for boundary in ("source-register-origin", "opf-identifier"):
            with self.subTest(boundary=boundary):
                with tempfile.TemporaryDirectory() as directory:
                    root = Path(directory)
                    manifest, register = _fixture(
                        root,
                        opf_publisher="Siyavula Education",
                        opf_identifier=(
                            "https://assets.openstax.org/aliased-book"
                            if boundary == "opf-identifier"
                            else "www.siyavula.com.aliased-book"
                        ),
                        source_publisher="Siyavula Education",
                    )
                    if boundary == "source-register-origin":
                        register["sources"][0]["documentUri"] = (
                            "https://openstax.org/books/aliased-book"
                        )
                    with self.assertRaisesRegex(
                        ValueError,
                        "disabled provider identity",
                    ):
                        build_visual_candidate_inventory(
                            root,
                            manifest,
                            register,
                            candidate_limit=10,
                            per_source_limit=10,
                        )

    def test_provider_cannot_jointly_rename_manifest_register_and_opf(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest, register = _fixture(
                root,
                opf_publisher="Rice University",
                opf_identifier="978-1-23456-789-0",
                source_publisher="Rice University",
            )
            alias_source_id = "candidate:alias:renamed-provider"
            manifest["artifacts"][0]["sourceId"] = alias_source_id
            manifest["artifacts"][0]["expectedProviderIdentity"] = "alias"
            register["sources"][0]["sourceId"] = alias_source_id
            register["sources"][0]["independenceGroup"] = "alias"

            with self.assertRaises(ValueError):
                build_visual_candidate_inventory(
                    root,
                    manifest,
                    register,
                    candidate_limit=10,
                    per_source_limit=10,
                )

    def test_caption_and_credit_evidence_stays_in_the_current_figure(self) -> None:
        chapter = """<html xmlns="http://www.w3.org/1999/xhtml"><body>
<p>This worked example includes a solution.</p><section>
<figure><img src="media/a.png"/><figcaption>Caption alpha.</figcaption>
<p class="credit">Credit alpha.</p></figure>
<figure><img src="media/b.png"/><figcaption>Caption beta.</figcaption>
<p class="credit">Credit beta.</p></figure>
</section></body></html>"""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest, register = _fixture(
                root,
                chapter_text=chapter,
                image_entries={
                    "OEBPS/media/a.png": _png_bytes(0),
                    "OEBPS/media/b.png": _png_bytes(1),
                },
            )
            inventory, _ = build_visual_candidate_inventory(
                root, manifest, register, candidate_limit=10, per_source_limit=10
            )

        by_path = {candidate["archiveEntryPath"]: candidate for candidate in inventory["candidates"]}
        alpha_hash = hashlib.sha256(b"Caption alpha.").hexdigest().upper()
        beta_hash = hashlib.sha256(b"Caption beta.").hexdigest().upper()
        self.assertEqual(
            {alpha_hash},
            {item["normalizedTextSha256"] for item in by_path["OEBPS/media/a.png"]["captionEvidence"]},
        )
        self.assertEqual(
            {beta_hash},
            {item["normalizedTextSha256"] for item in by_path["OEBPS/media/b.png"]["captionEvidence"]},
        )
        for candidate in by_path.values():
            for evidence in candidate["captionEvidence"] + candidate["creditEvidence"]:
                self.assertEqual("OEBPS/chapter.xhtml", evidence["xhtmlLocator"]["entryPath"])
                self.assertTrue(evidence["xhtmlLocator"]["domPath"])

    def test_cross_document_duplicate_keeps_dereferenceable_occurrences(self) -> None:
        chapter = """<html xmlns="http://www.w3.org/1999/xhtml"><body>
<p>Worked example and solution.</p><figure><img src="media/shared.png"/>
<figcaption>First locator.</figcaption></figure></body></html>"""
        second = """<html xmlns="http://www.w3.org/1999/xhtml"><body><figure>
<img src="media/shared.png"/><figcaption>Second locator.</figcaption>
</figure></body></html>"""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest, register = _fixture(
                root,
                chapter_text=chapter,
                image_entries={"OEBPS/media/shared.png": _png_bytes()},
                additional_documents={"OEBPS/second.xhtml": second},
            )
            inventory, _ = build_visual_candidate_inventory(
                root, manifest, register, candidate_limit=10, per_source_limit=10
            )

        candidate = inventory["candidates"][0]
        self.assertEqual(
            {"OEBPS/chapter.xhtml", "OEBPS/second.xhtml"},
            {item["xhtmlLocator"]["entryPath"] for item in candidate["occurrences"]},
        )
        self.assertEqual(
            {"OEBPS/chapter.xhtml", "OEBPS/second.xhtml"},
            {item["xhtmlLocator"]["entryPath"] for item in candidate["captionEvidence"]},
        )

    def test_multi_image_figure_requires_explicit_caption_relationships(self) -> None:
        chapter = """<html xmlns="http://www.w3.org/1999/xhtml"><body>
<p>Worked example and solution.</p><figure>
<img src="media/a.png"/><img src="media/b.png"/>
<figcaption>Ambiguous first.</figcaption><figcaption>Ambiguous second.</figcaption>
</figure></body></html>"""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest, register = _fixture(
                root,
                chapter_text=chapter,
                image_entries={
                    "OEBPS/media/a.png": _png_bytes(0),
                    "OEBPS/media/b.png": _png_bytes(1),
                },
            )
            inventory, _ = build_visual_candidate_inventory(
                root, manifest, register, candidate_limit=10, per_source_limit=10
            )

        self.assertTrue(
            all(not candidate["captionEvidence"] for candidate in inventory["candidates"])
        )

    def test_hidden_and_zero_area_svg_are_isolated_and_noise_does_not_outrank_structure(self) -> None:
        chapter = """<html xmlns="http://www.w3.org/1999/xhtml"><body>
<p>Worked example and solution.</p>
<img src="media/noise.png"/><img src="media/structure.svg"/>
<img src="media/zero.svg"/><img src="media/hidden.svg"/>
<img src="media/missing-paint.svg"/><img src="media/external-image.svg"/>
<img src="media/empty-path.svg"/><img src="media/two-point.svg"/>
<img src="media/transparent-rgba.svg"/><img src="media/transparent-hex.svg"/>
</body></html>"""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest, register = _fixture(
                root,
                chapter_text=chapter,
                image_entries={
                    "OEBPS/media/noise.png": _noise_png_bytes(),
                    "OEBPS/media/structure.svg": _svg_bytes(),
                    "OEBPS/media/zero.svg": _svg_bytes(zero_area=True),
                    "OEBPS/media/hidden.svg": _svg_bytes(hidden=True),
                    "OEBPS/media/missing-paint.svg": (
                        b'<svg xmlns="http://www.w3.org/2000/svg" width="100" height="100">'
                        b'<rect width="90" height="90" fill="url(#missing)"/></svg>'
                    ),
                    "OEBPS/media/external-image.svg": (
                        b'<svg xmlns="http://www.w3.org/2000/svg" width="100" height="100">'
                        b'<image href="https://example.test/pixel.png" width="90" height="90"/>'
                        b'</svg>'
                    ),
                    "OEBPS/media/empty-path.svg": (
                        b'<svg xmlns="http://www.w3.org/2000/svg" width="100" height="100">'
                        b'<path d="M0 0 M100 100"/></svg>'
                    ),
                    "OEBPS/media/two-point.svg": (
                        b'<svg xmlns="http://www.w3.org/2000/svg" width="100" height="100">'
                        b'<polygon points="0,0 90,90"/></svg>'
                    ),
                    "OEBPS/media/transparent-rgba.svg": (
                        b'<svg xmlns="http://www.w3.org/2000/svg" width="100" height="100">'
                        b'<rect width="90" height="90" fill="rgba(0,0,0,0)"/></svg>'
                    ),
                    "OEBPS/media/transparent-hex.svg": (
                        b'<svg xmlns="http://www.w3.org/2000/svg" width="100" height="100">'
                        b'<rect width="90" height="90" fill="#00000000"/></svg>'
                    ),
                },
            )
            inventory, summary = build_visual_candidate_inventory(
                root, manifest, register, candidate_limit=1, per_source_limit=10
            )

        self.assertEqual("OEBPS/media/structure.svg", inventory["candidates"][0]["archiveEntryPath"])
        self.assertEqual(8, inventory["scanIsolation"]["isolatedImageCount"])
        self.assertEqual(1, summary["reviewRequiredCandidateCount"])
        self.assertEqual(64, len(summary["inventorySha256"]))

    def test_twenty_thousand_references_use_one_image_analysis_cache_entry(self) -> None:
        references = "".join(
            f'<img src="media/shared.png" aria-describedby="caption-{index}"/>'
            f'<span id="caption-{index}">Caption {index}</span>'
            for index in range(20_000)
        )
        chapter = (
            '<html xmlns="http://www.w3.org/1999/xhtml"><body>'
            '<p>Worked example and solution.</p><figure>'
            + references
            + '</figure></body></html>'
        )
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest, register = _fixture(
                root,
                chapter_text=chapter,
                image_entries={"OEBPS/media/shared.png": _png_bytes()},
            )
            inventory, _ = build_visual_candidate_inventory(
                root, manifest, register, candidate_limit=1, per_source_limit=1
            )

        isolation = inventory["scanIsolation"]
        self.assertEqual(20_000, isolation["referencedImageCount"])
        self.assertEqual(1, isolation["decodedImageEntryCount"])
        self.assertEqual(19_999, isolation["imageEntryCacheHitCount"])

    def test_deep_dom_is_isolated_without_aborting_other_documents(self) -> None:
        shallow = """<html xmlns="http://www.w3.org/1999/xhtml"><body>
<p>Worked example and solution.</p><img src="media/a.png"/></body></html>"""
        deep = (
            '<html xmlns="http://www.w3.org/1999/xhtml"><body>'
            + "<div>" * 1_200
            + '<img src="media/a.png"/>'
            + "</div>" * 1_200
            + "</body></html>"
        )
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest, register = _fixture(
                root,
                chapter_text=shallow,
                image_entries={"OEBPS/media/a.png": _png_bytes()},
                additional_documents={"OEBPS/deep.xhtml": deep},
            )
            inventory, _ = build_visual_candidate_inventory(
                root, manifest, register, candidate_limit=10, per_source_limit=10
            )

        self.assertEqual(1, inventory["scanIsolation"]["isolatedDocumentCount"])

    def test_xml_names_and_locator_bytes_are_bounded_before_path_expansion(self) -> None:
        valid = """<html xmlns="http://www.w3.org/1999/xhtml"><body>
<p>Worked example and solution.</p><img src="media/a.png"/></body></html>"""
        oversized_name = "n" * 513
        long_bounded_name = "n" * 450
        oversized_locator = (
            '<html xmlns="http://www.w3.org/1999/xhtml"><body>'
            + f"<{long_bounded_name}>" * 20
            + '<img src="media/a.png"/>'
            + f"</{long_bounded_name}>" * 20
            + "</body></html>"
        )
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest, register = _fixture(
                root,
                chapter_text=valid,
                additional_documents={
                    "OEBPS/oversized-name.xhtml": (
                        f"<{oversized_name}><img src='media/a.png'/></{oversized_name}>"
                    ),
                    "OEBPS/oversized-locator.xhtml": oversized_locator,
                },
            )
            inventory, _ = build_visual_candidate_inventory(
                root, manifest, register, candidate_limit=10, per_source_limit=10
            )

        self.assertEqual(2, inventory["scanIsolation"]["isolatedDocumentCount"])

    def test_svg_scan_does_not_materialize_unused_dom_locators(self) -> None:
        with patch(
            "teaching_sources.visual_candidate_inventory.MAX_DOM_LOCATOR_BYTES",
            1,
        ), patch(
            "teaching_sources.visual_candidate_inventory.MAX_TOTAL_DOM_LOCATOR_BYTES",
            1,
        ):
            dimensions, metrics = _svg_metrics(_svg_bytes())

        self.assertEqual("SVG", dimensions["format"])
        self.assertGreater(metrics["visibleArea"], 0)

    def test_explicit_relationship_output_hashes_ids_without_raw_text(self) -> None:
        raw_relationship_id = "raw-answer-is-42"
        chapter = f"""<html xmlns="http://www.w3.org/1999/xhtml"><body>
<p>Worked example and solution.</p><img src="media/a.png"
aria-describedby="{raw_relationship_id}"/>
<span id="{raw_relationship_id}">A deliberately sensitive caption.</span>
</body></html>"""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest, register = _fixture(root, chapter_text=chapter)
            inventory, _ = build_visual_candidate_inventory(
                root, manifest, register, candidate_limit=10, per_source_limit=10
            )

        serialized = json.dumps(inventory, ensure_ascii=False)
        self.assertNotIn(raw_relationship_id, serialized)
        evidence = inventory["candidates"][0]["captionEvidence"][0]
        self.assertEqual("EXPLICIT_IDREF", evidence["relationship"])
        self.assertEqual(64, len(evidence["relationshipReferenceSha256"]))
        self.assertGreater(evidence["relationshipReferenceOrdinal"], 0)
        self.assertFalse(inventory["boundary"]["rawCaptionOrCreditTextIncluded"])

    def test_cumulative_decompression_and_pixel_budgets_fail_closed(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest, register = _fixture(root)
            with patch(
                "teaching_sources.visual_candidate_inventory.MAX_DECOMPRESSED_BYTES_PER_SOURCE",
                1,
            ):
                with self.assertRaisesRegex(ValueError, "decompression budget"):
                    build_visual_candidate_inventory(
                        root, manifest, register, candidate_limit=10, per_source_limit=10
                    )
            with patch(
                "teaching_sources.visual_candidate_inventory.MAX_TOTAL_RASTER_PIXELS_PER_SOURCE",
                1,
            ):
                with self.assertRaisesRegex(ValueError, "pixel budget"):
                    build_visual_candidate_inventory(
                        root, manifest, register, candidate_limit=10, per_source_limit=10
                    )
            with patch(
                "teaching_sources.visual_candidate_inventory.MAX_TOTAL_RASTER_FRAMES_PER_SOURCE",
                0,
            ):
                with self.assertRaisesRegex(ValueError, "frame budget"):
                    build_visual_candidate_inventory(
                        root, manifest, register, candidate_limit=10, per_source_limit=10
                    )

    def test_archive_traversal_fails_closed(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest, register = _fixture(root, traversal=True)
            with self.assertRaisesRegex(ValueError, "Unsafe EPUB archive entry"):
                build_visual_candidate_inventory(
                    root, manifest, register, candidate_limit=10, per_source_limit=10
                )

    def test_cli_separates_artifacts_and_only_writes_to_artifact_output(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            project_root = root / "worktree"
            artifact_root = root / "private"
            project_root.mkdir()
            artifact_root.mkdir()
            manifest, register = _fixture(artifact_root)
            (project_root / "manifest.json").write_text(json.dumps(manifest), "utf-8")
            (project_root / "register.json").write_text(json.dumps(register), "utf-8")
            arguments = [
                "--project-root",
                str(project_root),
                "--artifact-root",
                str(artifact_root),
                "--manifest",
                "manifest.json",
                "--source-register",
                "register.json",
                "--output",
                ".artifacts/visual-candidates.json",
                "--summary-output",
                ".artifacts/visual-candidates-summary.json",
                "--max-candidates",
                "2",
                "--max-per-source",
                "2",
                "--write",
            ]
            with redirect_stdout(io.StringIO()):
                result = visual_candidate_cli(arguments)

            self.assertEqual(0, result)
            self.assertTrue(
                (project_root / ".artifacts" / "visual-candidates.json").is_file()
            )
            self.assertTrue(
                (
                    project_root
                    / ".artifacts"
                    / "visual-candidates-summary.json"
                ).is_file()
            )
            tracked_check = project_root / "tracked-check.json"
            tracked_check.write_text(
                (project_root / ".artifacts" / "visual-candidates.json").read_text(
                    "utf-8"
                ),
                "utf-8",
            )
            check_arguments = arguments.copy()
            check_arguments[check_arguments.index(".artifacts/visual-candidates.json")] = (
                "tracked-check.json"
            )
            check_arguments[check_arguments.index("--write")] = "--check"
            tracked_summary = project_root / "tracked-summary.json"
            tracked_summary.write_text(
                (
                    project_root
                    / ".artifacts"
                    / "visual-candidates-summary.json"
                ).read_text("utf-8"),
                "utf-8",
            )
            check_arguments[
                check_arguments.index(".artifacts/visual-candidates-summary.json")
            ] = "tracked-summary.json"
            with redirect_stdout(io.StringIO()):
                checked = visual_candidate_cli(check_arguments)
            self.assertEqual(0, checked)
            arguments[arguments.index(".artifacts/visual-candidates.json")] = "tracked.json"
            with redirect_stdout(io.StringIO()), redirect_stderr(io.StringIO()):
                rejected = visual_candidate_cli(arguments)
            self.assertEqual(1, rejected)
            self.assertFalse((project_root / "tracked.json").exists())

    def test_production_source_has_no_fixture_image_exception(self) -> None:
        source = (
            Path(__file__).parents[1]
            / "teaching_sources"
            / "visual_candidate_inventory.py"
        ).read_text("utf-8")
        self.assertNotIn(SOURCE_ID, source)
        self.assertNotIn("OEBPS/media/a.png", source)
        self.assertNotIn(hashlib.sha256(_png_bytes()).hexdigest().upper(), source)


if __name__ == "__main__":
    unittest.main()
