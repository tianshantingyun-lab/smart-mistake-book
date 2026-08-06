#!/usr/bin/env python3
"""Keep wrong-question, learner-mastery, and curriculum knowledge stores independent."""

from __future__ import annotations

import argparse
import io
import re
import sys
import zipfile
from dataclasses import dataclass
from collections.abc import Iterable, Iterator
from pathlib import Path


@dataclass(frozen=True)
class StoreBoundary:
    module_path: str
    implementation_package: str
    database_name_constant: str
    database_filename: str
    authorized_split_sources: tuple[str, ...] = ()


SPLIT_OWNER_FORBIDDEN_CRYPTO_PATTERNS = (
    re.compile(r"\bSecretKey\b"),
    re.compile(r"\bMac\b"),
    re.compile(r"\bKeyStore\b"),
    re.compile(r"AndroidKeyStore"),
    re.compile(r"\b\w*Authenticator\b"),
    re.compile(r"\bloadExisting\b"),
    re.compile(r"\battest(?:\b|(?=[A-Z_$]))\w*"),
    re.compile(r"\bsign(?:\b|(?=[A-Z_$]))\w*"),
)

EXCLUDED_TEST_SOURCE_SET_PREFIXES = ("test", "androidTest", "testFixtures")
COMPILED_ARTIFACT_SUFFIXES = {".class", ".jar", ".aar"}
RAW_RELAY_DESCRIPTOR_MARKERS = (
    "CrossStoreEventEnvelope",
    "CrossStoreRelayMessage",
    "StudentMistakeRelayMessage",
    "LearnerMasteryRelayMessage",
)
RAW_SYNTHETIC_ACCESSOR_NAME_MARKERS = ("envelope", "relaymessage")
CRYPTO_MATERIAL_DESCRIPTOR_MARKERS = (
    "Ljavax/crypto/SecretKey;",
    "Ljavax/crypto/Mac;",
    "Ljava/security/KeyStore",
)
SENSITIVE_CRYPTO_CLASS_SUFFIXES = ("Authenticator",)
OWNER_ISSUED_VERIFIER_METHOD_DESCRIPTORS = (
    "(Lcom/tingyun/smartmistakebook/core/model/storage/StudentMistakeRelayMessage;)V",
    "(Lcom/tingyun/smartmistakebook/core/model/storage/LearnerMasteryRelayMessage;)V",
)


STORE_BOUNDARIES = (
    StoreBoundary(
        "core/student-mistake-database",
        "com.tingyun.smartmistakebook.core.student.mistake.database",
        "STUDENT_MISTAKE_DATABASE_NAME",
        "student-mistakes.db",
        (
            "core/data/src/main/java/com/tingyun/smartmistakebook/core/data/authority/"
            "StudentMistakeOwnerAccess.java",
        ),
    ),
    StoreBoundary(
        "core/learner-mastery-database",
        "com.tingyun.smartmistakebook.core.mastery.database",
        "LEARNER_MASTERY_DATABASE_NAME",
        "learner-mastery.db",
        (
            "core/data/src/main/java/com/tingyun/smartmistakebook/core/data/authority/"
            "LearnerMasteryOwnerAccess.java",
        ),
    ),
    StoreBoundary(
        "core/knowledge-database",
        "com.tingyun.smartmistakebook.core.knowledge.database",
        "HIGH_SCHOOL_KNOWLEDGE_DATABASE_NAME",
        "high-school-knowledge.db",
    ),
)


@dataclass(frozen=True)
class Violation:
    path: Path
    line: int
    rule: str
    excerpt: str


@dataclass(frozen=True)
class CompiledMethod:
    name: str
    descriptor: str
    access_flags: int


@dataclass(frozen=True)
class CompiledClass:
    internal_name: str
    access_flags: int
    methods: tuple[CompiledMethod, ...]


@dataclass(frozen=True)
class CompiledClassEntry:
    origin: Path
    display_path: Path
    content: bytes


def _line_number(source: str, offset: int) -> int:
    return source.count("\n", 0, offset) + 1


def _main_sources(module_root: Path) -> list[Path]:
    source_root = module_root / "src" / "main"
    if not source_root.exists():
        return []
    return sorted(
        path
        for path in source_root.rglob("*")
        if path.is_file() and path.suffix in {".kt", ".java"}
    )


def _is_installable_source_set(name: str) -> bool:
    return not any(
        name == prefix
        or (name.startswith(prefix) and name[len(prefix) : len(prefix) + 1].isupper())
        for prefix in EXCLUDED_TEST_SOURCE_SET_PREFIXES
    )


def _installable_sources(module_root: Path) -> list[Path]:
    source_root = module_root / "src"
    if not source_root.is_dir():
        return []
    return sorted(
        path
        for source_set in source_root.iterdir()
        if source_set.is_dir() and _is_installable_source_set(source_set.name)
        for path in source_set.rglob("*")
        if path.is_file() and path.suffix in {".kt", ".java"}
    )


def _repository_module_roots(root: Path) -> list[Path]:
    module_roots: list[Path] = []
    ignored_directories = {"src", "build", "node_modules", ".gradle", ".toolchains"}

    def visit(directory: Path) -> None:
        if (directory / "src").is_dir():
            module_roots.append(directory)
        for child in directory.iterdir():
            if (
                not child.is_dir()
                or child.name in ignored_directories
                or child.name.startswith(".")
            ):
                continue
            visit(child)

    visit(root)
    return sorted(module_roots)


def _repository_installable_sources(root: Path) -> list[Path]:
    return sorted(
        path
        for module_root in _repository_module_roots(root)
        for path in _installable_sources(module_root)
    )


def audit(
    root: Path,
    compiled_artifacts: Iterable[Path] = (),
) -> list[Violation]:
    root = root.resolve()
    violations: list[Violation] = []
    repository_sources = {
        path: path.read_text(encoding="utf-8")
        for path in _repository_installable_sources(root)
    }
    for owner in STORE_BOUNDARIES:
        module_root = root / owner.module_path
        main_sources = _main_sources(module_root)
        installable_sources = _installable_sources(module_root)
        if main_sources:
            main_source_by_path = {
                path: path.read_text(encoding="utf-8") for path in main_sources
            }
            _audit_physical_store_identity(
                root, owner, main_source_by_path, violations
            )
        else:
            violations.append(
                Violation(
                    path=Path(owner.module_path),
                    line=1,
                    rule="physical-store-module-missing",
                    excerpt="no production Kotlin or Java source",
                )
            )
        installable_source_by_path = {
            path: repository_sources[path]
            for path in installable_sources
        }
        for foreign in STORE_BOUNDARIES:
            if foreign == owner:
                continue
            _audit_foreign_implementation_references(
                root, foreign, installable_source_by_path, violations
            )
            _audit_foreign_gradle_dependency(
                root, module_root, foreign, violations
            )
        _audit_split_package_ownership(root, owner, repository_sources, violations)
    violations.extend(audit_compiled_artifacts(root, compiled_artifacts))
    return sorted(violations, key=lambda item: (str(item.path), item.line, item.rule))


def _audit_foreign_implementation_references(
    root: Path,
    foreign: StoreBoundary,
    source_by_path: dict[Path, str],
    violations: list[Violation],
) -> None:
    for path, source in source_by_path.items():
        for match in re.finditer(re.escape(foreign.implementation_package), source):
            violations.append(
                Violation(
                    path=path.relative_to(root),
                    line=_line_number(source, match.start()),
                    rule="cross-store-implementation-reference",
                    excerpt=foreign.implementation_package,
                )
            )


def _audit_foreign_gradle_dependency(
    root: Path,
    module_root: Path,
    foreign: StoreBoundary,
    violations: list[Violation],
) -> None:
    build_file = module_root / "build.gradle.kts"
    if not build_file.exists():
        return
    source = build_file.read_text(encoding="utf-8")
    dependency = re.compile(
        r"project\s*\(\s*[\"']:\s*"
        + re.escape(foreign.module_path.replace("/", ":"))
        + r"[\"']\s*\)"
    )
    for match in dependency.finditer(source):
        violations.append(
            Violation(
                path=build_file.relative_to(root),
                line=_line_number(source, match.start()),
                rule="cross-store-gradle-dependency",
                excerpt=" ".join(match.group(0).split()),
            )
        )


def _audit_split_package_ownership(
    root: Path,
    boundary: StoreBoundary,
    repository_sources: dict[Path, str],
    violations: list[Violation],
) -> None:
    package_declaration = re.compile(
        rf"^\s*package\s+{re.escape(boundary.implementation_package)}\s*;?\s*$",
        re.MULTILINE,
    )
    owner_root = (root / boundary.module_path).resolve()
    allowlist = {Path(path) for path in boundary.authorized_split_sources}
    for path, source in repository_sources.items():
        match = package_declaration.search(source)
        if match is None:
            continue
        resolved = path.resolve()
        if resolved == owner_root or owner_root in resolved.parents:
            continue
        relative = path.relative_to(root)
        if relative in allowlist:
            _audit_split_owner_crypto_surface(relative, source, violations)
            continue
        violations.append(
            Violation(
                path=relative,
                line=_line_number(source, match.start()),
                rule="unauthorized-store-split-package",
                excerpt=boundary.implementation_package,
            )
        )


def _audit_split_owner_crypto_surface(
    relative: Path,
    source: str,
    violations: list[Violation],
) -> None:
    """Owner adapters may invoke an atomic owner operation, never handle key material."""
    for pattern in SPLIT_OWNER_FORBIDDEN_CRYPTO_PATTERNS:
        for match in pattern.finditer(source):
            violations.append(
                Violation(
                    path=relative,
                    line=_line_number(source, match.start()),
                    rule="split-owner-crypto-surface",
                    excerpt=match.group(0),
                )
            )


def _audit_physical_store_identity(
    root: Path,
    boundary: StoreBoundary,
    source_by_path: dict[Path, str],
    violations: list[Violation],
) -> None:
    _audit_database_name_identity(root, boundary, source_by_path, violations)
    _audit_room_container_identity(boundary, source_by_path, violations)


def _audit_database_name_identity(
    root: Path,
    boundary: StoreBoundary,
    source_by_path: dict[Path, str],
    violations: list[Violation],
) -> None:
    constant_pattern = re.compile(
        rf"\bconst\s+val\s+{re.escape(boundary.database_name_constant)}\s*=\s*"
        r'["\']([^"\']+)["\']'
    )
    declarations = [
        (path, match)
        for path, source in source_by_path.items()
        for match in constant_pattern.finditer(source)
    ]
    if len(declarations) != 1:
        violations.append(
            Violation(
                path=Path(boundary.module_path),
                line=1,
                rule="physical-store-name-declaration",
                excerpt=(
                    f"expected exactly one {boundary.database_name_constant}, "
                    f"found {len(declarations)}"
                ),
            )
        )
    else:
        path, match = declarations[0]
        if match.group(1) != boundary.database_filename:
            violations.append(
                Violation(
                    path=path.relative_to(root),
                    line=_line_number(source_by_path[path], match.start()),
                    rule="physical-store-name-mismatch",
                    excerpt=f"{match.group(1)} != {boundary.database_filename}",
                )
            )


def _audit_room_container_identity(
    boundary: StoreBoundary,
    source_by_path: dict[Path, str],
    violations: list[Violation],
) -> None:
    room_declarations = [
        (path, match)
        for path, source in source_by_path.items()
        for match in re.finditer(
            r"\bclass\s+\w+[^\n{]*(?::|extends)\s*RoomDatabase(?:\(\))?",
            source,
        )
    ]
    database_annotations = sum(source.count("@Database(") for source in source_by_path.values())
    if len(room_declarations) != 1 or database_annotations != 1:
        violations.append(
            Violation(
                path=Path(boundary.module_path),
                line=1,
                rule="physical-room-store-declaration",
                excerpt=(
                    "expected one @Database RoomDatabase container, "
                    f"found annotations={database_annotations}, containers={len(room_declarations)}"
                ),
            )
        )


class _ClassFileReader:
    def __init__(self, content: bytes) -> None:
        self._content = memoryview(content)
        self._offset = 0

    def read_u1(self) -> int:
        return self._read_integer(1)

    def read_u2(self) -> int:
        return self._read_integer(2)

    def read_u4(self) -> int:
        return self._read_integer(4)

    def read_bytes(self, length: int) -> bytes:
        end = self._offset + length
        if end > len(self._content):
            raise ValueError("truncated class file")
        value = self._content[self._offset:end].tobytes()
        self._offset = end
        return value

    def _read_integer(self, length: int) -> int:
        return int.from_bytes(self.read_bytes(length), byteorder="big")


def _skip_class_attributes(reader: _ClassFileReader) -> None:
    for _ in range(reader.read_u2()):
        reader.read_u2()
        reader.read_bytes(reader.read_u4())


def _read_constant_pool_entry(
    reader: _ClassFileReader,
    tag: int,
) -> tuple[object | None, bool]:
    if tag == 1:
        return (
            reader.read_bytes(reader.read_u2()).decode("utf-8", errors="replace"),
            False,
        )
    if tag in {3, 4}:
        reader.read_bytes(4)
        return None, False
    if tag in {5, 6}:
        reader.read_bytes(8)
        return None, True
    if tag in {7, 8, 16, 19, 20}:
        return (tag, reader.read_u2()), False
    if tag in {9, 10, 11, 12, 17, 18}:
        reader.read_bytes(4)
        return None, False
    if tag == 15:
        reader.read_bytes(3)
        return None, False
    raise ValueError(f"unsupported constant-pool tag {tag}")


def _read_constant_pool(reader: _ClassFileReader) -> list[object | None]:
    constant_pool: list[object | None] = [None] * reader.read_u2()
    index = 1
    while index < len(constant_pool):
        value, occupies_two_slots = _read_constant_pool_entry(reader, reader.read_u1())
        constant_pool[index] = value
        index += 2 if occupies_two_slots else 1
    return constant_pool


def _read_compiled_methods(
    reader: _ClassFileReader,
    constant_pool: list[object | None],
) -> tuple[CompiledMethod, ...]:
    methods: list[CompiledMethod] = []
    for _ in range(reader.read_u2()):
        access_flags = reader.read_u2()
        name = constant_pool[reader.read_u2()]
        descriptor = constant_pool[reader.read_u2()]
        if not isinstance(name, str) or not isinstance(descriptor, str):
            raise ValueError("invalid method name or descriptor")
        methods.append(CompiledMethod(name, descriptor, access_flags))
        _skip_class_attributes(reader)
    return tuple(methods)


def _parse_compiled_class(content: bytes) -> CompiledClass:
    reader = _ClassFileReader(content)
    if reader.read_u4() != 0xCAFEBABE:
        raise ValueError("invalid class-file magic")
    reader.read_u2()
    reader.read_u2()
    constant_pool = _read_constant_pool(reader)

    class_access_flags = reader.read_u2()
    this_class_index = reader.read_u2()
    reader.read_u2()
    this_class = constant_pool[this_class_index]
    if not isinstance(this_class, tuple) or this_class[0] != 7:
        raise ValueError("invalid this_class entry")
    internal_name = constant_pool[this_class[1]]
    if not isinstance(internal_name, str):
        raise ValueError("invalid class name")

    reader.read_bytes(reader.read_u2() * 2)
    for _ in range(reader.read_u2()):
        reader.read_u2()
        reader.read_u2()
        reader.read_u2()
        _skip_class_attributes(reader)

    methods = _read_compiled_methods(reader, constant_pool)
    _skip_class_attributes(reader)
    return CompiledClass(internal_name, class_access_flags, methods)


def _compiled_entry_display(root: Path, origin: Path, entry: str | None = None) -> Path:
    try:
        displayed_origin = origin.resolve().relative_to(root)
    except ValueError:
        displayed_origin = origin.resolve()
    if entry is None:
        return displayed_origin
    return Path(f"{displayed_origin}!/{entry}")


def _zip_compiled_entries(
    root: Path,
    origin: Path,
    content: bytes | None = None,
    prefix: str = "",
) -> Iterator[CompiledClassEntry]:
    archive_source: Path | io.BytesIO
    archive_source = origin if content is None else io.BytesIO(content)
    with zipfile.ZipFile(archive_source) as archive:
        for info in archive.infolist():
            if info.is_dir():
                continue
            entry_name = f"{prefix}{info.filename}"
            if info.filename.endswith(".class"):
                yield CompiledClassEntry(
                    origin,
                    _compiled_entry_display(root, origin, entry_name),
                    archive.read(info),
                )
            elif info.filename.endswith(".jar"):
                yield from _zip_compiled_entries(
                    root,
                    origin,
                    archive.read(info),
                    prefix=f"{entry_name}!/",
                )


def _compiled_entries(root: Path, artifact: Path) -> Iterator[CompiledClassEntry]:
    resolved = artifact if artifact.is_absolute() else root / artifact
    resolved = resolved.resolve()
    artifact_files = (
        sorted(
            path
            for path in resolved.rglob("*")
            if path.is_file() and path.suffix.casefold() in COMPILED_ARTIFACT_SUFFIXES
        )
        if resolved.is_dir()
        else [resolved]
    )
    for artifact_file in artifact_files:
        suffix = artifact_file.suffix.casefold()
        if suffix == ".class":
            yield CompiledClassEntry(
                artifact_file,
                _compiled_entry_display(root, artifact_file),
                artifact_file.read_bytes(),
            )
        elif suffix in {".jar", ".aar"}:
            yield from _zip_compiled_entries(root, artifact_file)
        else:
            raise ValueError(f"unsupported compiled artifact: {artifact_file}")


def _path_is_within(path: Path, parent: Path) -> bool:
    resolved = path.resolve()
    resolved_parent = parent.resolve()
    return resolved == resolved_parent or resolved_parent in resolved.parents


def _authorized_compiled_owner_names(boundary: StoreBoundary) -> dict[str, Path]:
    package_path = boundary.implementation_package.replace(".", "/")
    authorized: dict[str, Path] = {}
    for source in boundary.authorized_split_sources:
        source_path = Path(source)
        parts = source_path.parts
        source_index = parts.index("src")
        module_root = Path(*parts[:source_index])
        authorized[f"{package_path}/{source_path.stem}"] = module_root
    return authorized


def _compiled_class_has_authorized_origin(
    root: Path,
    boundary: StoreBoundary,
    entry: CompiledClassEntry,
    compiled_class: CompiledClass,
) -> bool:
    if _path_is_within(entry.origin, root / boundary.module_path):
        return True
    for owner_name, owner_module in _authorized_compiled_owner_names(boundary).items():
        if (
            compiled_class.internal_name == owner_name
            or compiled_class.internal_name.startswith(f"{owner_name}$")
        ) and _path_is_within(entry.origin, root / owner_module):
            return True
    return False


def _compiled_store_boundary(compiled_class: CompiledClass) -> StoreBoundary | None:
    class_package = compiled_class.internal_name.rpartition("/")[0].replace("/", ".")
    return next(
        (
            candidate
            for candidate in STORE_BOUNDARIES
            if candidate.implementation_package == class_package
        ),
        None,
    )


def _compiled_method_identity(
    compiled_class: CompiledClass,
    method: CompiledMethod,
) -> str:
    return (
        f"{compiled_class.internal_name.replace('/', '.')}#"
        f"{method.name}{method.descriptor}"
    )


def _has_sensitive_crypto_operation_name(method_name: str) -> bool:
    for prefix in ("sign", "attest", "loadExisting"):
        if method_name == prefix:
            return True
        suffix = method_name[len(prefix) :]
        if method_name.startswith(prefix) and suffix[:1] in {"_", "$"}:
            return True
        if method_name.startswith(prefix) and suffix[:1].isupper():
            return True
    return False


def _is_sensitive_crypto_owner_method(
    compiled_class: CompiledClass,
    method: CompiledMethod,
) -> bool:
    outer_class_name = compiled_class.internal_name.rsplit("/", 1)[-1].split("$", 1)[0]
    if outer_class_name.endswith(SENSITIVE_CRYPTO_CLASS_SUFFIXES):
        return True
    owner_or_store = "Owner" in outer_class_name or outer_class_name.endswith(
        ("Store", "StoreKt")
    )
    return owner_or_store and _has_sensitive_crypto_operation_name(method.name)


def _owner_issued_final_verifier_method(
    compiled_class: CompiledClass,
) -> CompiledMethod | None:
    outer_class_name = compiled_class.internal_name.rsplit("/", 1)[-1].split("$", 1)[0]
    public_and_final = compiled_class.access_flags & 0x0011 == 0x0011
    if not public_and_final or not outer_class_name.endswith("AuthenticityVerifier"):
        return None
    externally_visible = 0x0001 | 0x0004
    constructors = [
        method for method in compiled_class.methods if method.name == "<init>"
    ]
    if not constructors or any(
        constructor.access_flags & externally_visible for constructor in constructors
    ):
        return None
    if any(
        marker in method.descriptor
        for method in compiled_class.methods
        for marker in CRYPTO_MATERIAL_DESCRIPTOR_MARKERS
    ) or any(
        _has_sensitive_crypto_operation_name(method.name)
        for method in compiled_class.methods
    ):
        return None
    visible_methods = [
        method
        for method in compiled_class.methods
        if method.name != "<init>" and method.access_flags & externally_visible
    ]
    if len(visible_methods) != 1:
        return None
    method = visible_methods[0]
    if method.access_flags & 0x0001 == 0:
        return None
    if method.name != "requireAuthentic":
        return None
    if method.descriptor not in OWNER_ISSUED_VERIFIER_METHOD_DESCRIPTORS:
        return None
    return method


def _compiled_method_violation_rules(
    compiled_class: CompiledClass,
    method: CompiledMethod,
    allowed_verifier_method: CompiledMethod | None,
) -> tuple[str, ...]:
    externally_visible = 0x0001 | 0x0004
    class_is_public = compiled_class.access_flags & 0x0001 != 0
    if not class_is_public or method.access_flags & externally_visible == 0:
        return ()
    rules: list[str] = []
    if any(
        marker in method.descriptor for marker in CRYPTO_MATERIAL_DESCRIPTOR_MARKERS
    ):
        rules.append("compiled-public-crypto-material-api")
    if _is_sensitive_crypto_owner_method(compiled_class, method):
        rules.append("compiled-public-crypto-owner-api")
    has_raw_descriptor = any(
        marker in method.descriptor for marker in RAW_RELAY_DESCRIPTOR_MARKERS
    )
    exposes_raw_synthetic_accessor = method.name.startswith("access$") and (
        has_raw_descriptor
        or any(
            marker in method.name.casefold()
            for marker in RAW_SYNTHETIC_ACCESSOR_NAME_MARKERS
        )
    )
    if exposes_raw_synthetic_accessor:
        rules.append("compiled-public-synthetic-accessor")
    if has_raw_descriptor and method != allowed_verifier_method:
        rules.append("compiled-public-raw-relay-api")
    return tuple(rules)


def _audit_compiled_methods(
    entry: CompiledClassEntry,
    compiled_class: CompiledClass,
    violations: list[Violation],
) -> None:
    allowed_verifier_method = _owner_issued_final_verifier_method(compiled_class)
    for method in compiled_class.methods:
        method_identity = _compiled_method_identity(compiled_class, method)
        for rule in _compiled_method_violation_rules(
            compiled_class, method, allowed_verifier_method
        ):
            violations.append(
                Violation(
                    path=entry.display_path,
                    line=1,
                    rule=rule,
                    excerpt=method_identity,
                )
            )


def _audit_compiled_class(
    root: Path,
    entry: CompiledClassEntry,
    compiled_class: CompiledClass,
    violations: list[Violation],
) -> None:
    boundary = _compiled_store_boundary(compiled_class)
    if boundary is None:
        return
    if not _compiled_class_has_authorized_origin(root, boundary, entry, compiled_class):
        violations.append(
            Violation(
                path=entry.display_path,
                line=1,
                rule="compiled-unauthorized-store-split-package",
                excerpt=compiled_class.internal_name.replace("/", "."),
            )
        )
    _audit_compiled_methods(entry, compiled_class, violations)


def _audit_compiled_entry(
    root: Path,
    entry: CompiledClassEntry,
    violations: list[Violation],
) -> None:
    try:
        compiled_class = _parse_compiled_class(entry.content)
    except (IndexError, UnicodeDecodeError, ValueError) as error:
        violations.append(
            Violation(
                path=entry.display_path,
                line=1,
                rule="compiled-artifact-unreadable",
                excerpt=str(error),
            )
        )
        return
    _audit_compiled_class(root, entry, compiled_class, violations)


def _audit_compiled_artifact(
    root: Path,
    artifact: Path,
    violations: list[Violation],
) -> None:
    resolved = artifact if artifact.is_absolute() else root / artifact
    if not resolved.exists():
        violations.append(
            Violation(
                path=artifact,
                line=1,
                rule="compiled-artifact-missing",
                excerpt=str(artifact),
            )
        )
        return
    try:
        entries_seen = False
        for entry in _compiled_entries(root, artifact):
            entries_seen = True
            _audit_compiled_entry(root, entry, violations)
        if not entries_seen:
            violations.append(
                Violation(
                    path=_compiled_entry_display(root, resolved),
                    line=1,
                    rule="compiled-artifact-empty",
                    excerpt="no .class entries found",
                )
            )
    except (OSError, ValueError, zipfile.BadZipFile) as error:
        violations.append(
            Violation(
                path=_compiled_entry_display(root, resolved),
                line=1,
                rule="compiled-artifact-unreadable",
                excerpt=str(error),
            )
        )


def audit_compiled_artifacts(
    root: Path,
    artifacts: Iterable[Path],
) -> list[Violation]:
    root = root.resolve()
    violations: list[Violation] = []
    for artifact in artifacts:
        _audit_compiled_artifact(root, artifact, violations)
    return sorted(violations, key=lambda item: (str(item.path), item.line, item.rule))


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--root",
        type=Path,
        default=Path(__file__).resolve().parents[1],
        help="repository root (defaults to the parent of tools)",
    )
    parser.add_argument(
        "--compiled-artifact",
        "--compiled",
        dest="compiled_artifacts",
        action="append",
        type=Path,
        default=[],
        help=(
            "optional .class, JAR, AAR, or directory to scan for compiled "
            "store-boundary API leaks; may be repeated"
        ),
    )
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(sys.argv[1:] if argv is None else argv)
    violations = audit(args.root, args.compiled_artifacts)
    if not violations:
        print("Database-boundary audit passed: 3 physical stores remain independent")
        return 0
    print("Cross-store implementation coupling detected:", file=sys.stderr)
    for violation in violations:
        print(
            f"  {violation.path}:{violation.line}: {violation.rule}: {violation.excerpt}",
            file=sys.stderr,
        )
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
