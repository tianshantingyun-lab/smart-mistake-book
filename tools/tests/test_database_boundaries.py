from __future__ import annotations

import io
import shutil
import struct
import tempfile
import unittest
import zipfile
from collections import Counter
from pathlib import Path

from audit_database_boundaries import audit, audit_compiled_artifacts

WORKSPACE_TEMP_ROOT = Path(__file__).resolve().parents[2] / ".tmp" / "boundary-audit"
WORKSPACE_TEMP_ROOT.mkdir(parents=True, exist_ok=True)


def compiled_class_fixture(
    internal_name: str,
    methods: tuple[tuple[str, str, int], ...],
    *,
    class_access_flags: int = 0x0021,
) -> bytes:
    constant_pool: list[tuple[int, bytes | int]] = [
        (1, internal_name.encode("utf-8")),
        (7, 1),
        (1, b"java/lang/Object"),
        (7, 3),
    ]
    method_indexes: list[tuple[int, int, int]] = []
    for name, descriptor, access_flags in methods:
        name_index = len(constant_pool) + 1
        constant_pool.append((1, name.encode("utf-8")))
        descriptor_index = len(constant_pool) + 1
        constant_pool.append((1, descriptor.encode("utf-8")))
        method_indexes.append((access_flags, name_index, descriptor_index))

    content = bytearray(struct.pack(">IHHH", 0xCAFEBABE, 0, 52, len(constant_pool) + 1))
    for tag, value in constant_pool:
        content.append(tag)
        if tag == 1:
            assert isinstance(value, bytes)
            content.extend(struct.pack(">H", len(value)))
            content.extend(value)
        else:
            assert isinstance(value, int)
            content.extend(struct.pack(">H", value))
    content.extend(struct.pack(">HHHH", class_access_flags, 2, 4, 0))
    content.extend(struct.pack(">H", 0))
    content.extend(struct.pack(">H", len(method_indexes)))
    for access_flags, name_index, descriptor_index in method_indexes:
        content.extend(
            struct.pack(">HHHH", access_flags, name_index, descriptor_index, 0)
        )
    content.extend(struct.pack(">H", 0))
    return bytes(content)


class DatabaseBoundaryAuditTest(unittest.TestCase):
    def setUp(self) -> None:
        self._previous_tempdir = tempfile.tempdir
        tempfile.tempdir = str(WORKSPACE_TEMP_ROOT)

    def tearDown(self) -> None:
        tempfile.tempdir = self._previous_tempdir

    PHYSICAL_STORES = {
        "core/student-mistake-database": (
            "STUDENT_MISTAKE_DATABASE_NAME",
            "student-mistakes.db",
            "StudentMistakeRoomDatabase",
        ),
        "core/learner-mastery-database": (
            "LEARNER_MASTERY_DATABASE_NAME",
            "learner-mastery.db",
            "LearnerMasteryRoomDatabase",
        ),
        "core/knowledge-database": (
            "HIGH_SCHOOL_KNOWLEDGE_DATABASE_NAME",
            "high-school-knowledge.db",
            "HighSchoolKnowledgeRoomDatabase",
        ),
    }

    def seed_physical_stores(self, root: Path) -> None:
        for module, (constant, filename, room_class) in self.PHYSICAL_STORES.items():
            target = root / module / "src" / "main" / "kotlin" / "PhysicalStore.kt"
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(
                f'const val {constant} = "{filename}"\n'
                f'@Database(entities = [], version = 1)\n'
                f'abstract class {room_class} : RoomDatabase()\n',
                encoding="utf-8",
            )

    def write_source(self, root: Path, module: str, source: str) -> None:
        target = root / module / "src" / "main" / "kotlin" / "Store.kt"
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(source, encoding="utf-8")

    def write_production_path(self, root: Path, relative: str, source: str) -> None:
        target = root / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(source, encoding="utf-8")

    def write_build(self, root: Path, module: str, source: str) -> None:
        target = root / module / "build.gradle.kts"
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(source, encoding="utf-8")

    def test_accepts_shared_contract_dependencies(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.seed_physical_stores(root)
            self.write_source(
                root,
                "core/student-mistake-database",
                "import com.tingyun.smartmistakebook.core.model.storage.CrossStoreEventEnvelope",
            )
            self.write_build(
                root,
                "core/student-mistake-database",
                'dependencies { implementation(project(":core:model")) }',
            )
            self.assertEqual([], audit(root))

    def test_rejects_foreign_store_implementation_import(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.seed_physical_stores(root)
            self.write_source(
                root,
                "core/learner-mastery-database",
                "import com.tingyun.smartmistakebook.core.student.mistake.database.StudentDao",
            )
            self.assertEqual(
                ["cross-store-implementation-reference"],
                [violation.rule for violation in audit(root)],
            )

    def test_rejects_direct_gradle_dependency_between_stores(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.seed_physical_stores(root)
            self.write_build(
                root,
                "core/knowledge-database",
                'dependencies { implementation(project(":core:learner-mastery-database")) }',
            )
            self.assertEqual(
                ["cross-store-gradle-dependency"],
                [violation.rule for violation in audit(root)],
            )

    def test_accepts_only_the_audited_java_owner_bridge_split_package(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.seed_physical_stores(root)
            self.write_production_path(
                root,
                "core/data/src/main/java/com/tingyun/smartmistakebook/core/data/"
                "authority/LearnerMasteryOwnerAccess.java",
                "package com.tingyun.smartmistakebook.core.mastery.database;\n"
                "final class LearnerMasteryOwnerAccess { "
                "LearnerMasteryAuthenticityVerifier verifier; "
                "String signedSummary() { return \"ok\"; } }\n",
            )
            self.assertEqual([], audit(root))

    def test_rejects_kotlin_owner_bridge_regression(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.seed_physical_stores(root)
            self.write_production_path(
                root,
                "core/data/src/main/kotlin/com/tingyun/smartmistakebook/core/data/"
                "authority/LearnerMasteryOwnerAccess.kt",
                "package com.tingyun.smartmistakebook.core.mastery.database\n",
            )
            self.assertEqual(
                ["unauthorized-store-split-package"],
                [violation.rule for violation in audit(root)],
            )

    def test_rejects_crypto_key_access_from_the_owner_adapter(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.seed_physical_stores(root)
            self.write_production_path(
                root,
                "core/data/src/main/java/com/tingyun/smartmistakebook/core/data/"
                "authority/StudentMistakeOwnerAccess.java",
                "package com.tingyun.smartmistakebook.core.student.mistake.database;\n"
                "import javax.crypto.SecretKey;\n"
                "final class StudentMistakeOwnerAccess { SecretKey loadExisting() { return null; } }\n",
            )
            violations = audit(root)
            self.assertEqual(
                ["SecretKey", "SecretKey", "loadExisting"],
                [
                    violation.excerpt
                    for violation in violations
                    if violation.rule == "split-owner-crypto-surface"
                ],
            )

    def test_rejects_an_unauthorized_store_split_package_consumer(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.seed_physical_stores(root)
            self.write_production_path(
                root,
                "feature/tutor/src/main/kotlin/UnauthorizedMasteryAccess.kt",
                "package com.tingyun.smartmistakebook.core.mastery.database\n",
            )
            self.assertEqual(
                ["unauthorized-store-split-package"],
                [violation.rule for violation in audit(root)],
            )

    def test_rejects_a_second_core_data_split_package_file(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.seed_physical_stores(root)
            self.write_production_path(
                root,
                "core/data/src/main/kotlin/com/tingyun/smartmistakebook/core/data/"
                "authority/Backdoor.kt",
                "package com.tingyun.smartmistakebook.core.student.mistake.database\n",
            )
            self.assertEqual(
                ["unauthorized-store-split-package"],
                [violation.rule for violation in audit(root)],
            )

    def test_rejects_a_second_java_owner_bridge(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.seed_physical_stores(root)
            package = "package com.tingyun.smartmistakebook.core.mastery.database;\n"
            self.write_production_path(
                root,
                "core/data/src/main/java/com/tingyun/smartmistakebook/core/data/"
                "authority/LearnerMasteryOwnerAccess.java",
                package,
            )
            self.write_production_path(
                root,
                "core/data/src/main/java/com/tingyun/smartmistakebook/core/data/"
                "authority/LearnerMasteryOwnerAccessCopy.java",
                package,
            )
            self.assertEqual(
                ["unauthorized-store-split-package"],
                [violation.rule for violation in audit(root)],
            )

    def test_rejects_split_package_in_every_installable_source_set(self) -> None:
        for source_set in (
            "debug",
            "release",
            "schoolFlavor",
            "benchmark",
            "testingFlavor",
        ):
            with self.subTest(source_set=source_set), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                self.seed_physical_stores(root)
                self.write_production_path(
                    root,
                    f"feature/tutor/src/{source_set}/kotlin/InjectedStoreAccess.kt",
                    "package com.tingyun.smartmistakebook.core.mastery.database\n",
                )
                self.assertEqual(
                    ["unauthorized-store-split-package"],
                    [violation.rule for violation in audit(root)],
                )

    def test_ignores_non_installable_test_source_sets(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.seed_physical_stores(root)
            for source_set in ("test", "testDebug", "androidTest", "androidTestRelease", "testFixtures"):
                self.write_production_path(
                    root,
                    f"feature/tutor/src/{source_set}/kotlin/TestOnlyAccess.kt",
                    "package com.tingyun.smartmistakebook.core.mastery.database\n",
                )
            self.assertEqual([], audit(root))

    def test_rejects_public_synthetic_raw_apply_in_compiled_class(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            class_path = (
                root
                / "core/student-mistake-database/build/classes/com/tingyun/"
                "smartmistakebook/core/student/mistake/database/UnsafeOwner.class"
            )
            class_path.parent.mkdir(parents=True, exist_ok=True)
            class_path.write_bytes(
                compiled_class_fixture(
                    "com/tingyun/smartmistakebook/core/student/mistake/database/UnsafeOwner",
                    (
                        (
                            "access$applyEnvelope",
                            "(Lcom/tingyun/smartmistakebook/core/model/storage/"
                            "CrossStoreEventEnvelope;)V",
                            0x1109,
                        ),
                    ),
                )
            )
            self.assertEqual(
                [
                    "compiled-public-raw-relay-api",
                    "compiled-public-synthetic-accessor",
                ],
                [
                    violation.rule
                    for violation in audit_compiled_artifacts(root, [class_path])
                ],
            )

    def test_allows_only_structural_owner_issued_final_verifier(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            class_root = (
                root
                / "core/student-mistake-database/build/classes/com/tingyun/"
                "smartmistakebook/core/student/mistake/database"
            )
            class_root.mkdir(parents=True, exist_ok=True)
            package = "com/tingyun/smartmistakebook/core/student/mistake/database"
            relay_descriptor = (
                "(Lcom/tingyun/smartmistakebook/core/model/storage/"
                "StudentMistakeRelayMessage;)V"
            )
            private_constructor = (("<init>", "()V", 0x0002),)
            public_verifier = (("requireAuthentic", relay_descriptor, 0x0101),)
            fixtures = (
                (
                    "SafeAuthenticityVerifier",
                    private_constructor + public_verifier,
                    0x0031,
                ),
                (
                    "NonFinalAuthenticityVerifier",
                    private_constructor + public_verifier,
                    0x0021,
                ),
                (
                    "PublicConstructorAuthenticityVerifier",
                    (("<init>", "()V", 0x0001),) + public_verifier,
                    0x0031,
                ),
                (
                    "ExtraApiAuthenticityVerifier",
                    private_constructor
                    + public_verifier
                    + (("status", "()Z", 0x0101),),
                    0x0031,
                ),
                (
                    "WrongReturnAuthenticityVerifier",
                    private_constructor
                    + (
                        (
                            "requireAuthentic",
                            relay_descriptor.removesuffix("V") + "Z",
                            0x0101,
                        ),
                    ),
                    0x0031,
                ),
            )
            for class_name, methods, class_flags in fixtures:
                (class_root / f"{class_name}.class").write_bytes(
                    compiled_class_fixture(
                        f"{package}/{class_name}",
                        methods,
                        class_access_flags=class_flags,
                    )
                )

            violations = audit_compiled_artifacts(root, [class_root])
            self.assertEqual(
                Counter({"compiled-public-raw-relay-api": 4}),
                Counter(violation.rule for violation in violations),
            )
            self.assertTrue(
                all("SafeAuthenticityVerifier" not in violation.excerpt for violation in violations)
            )

    def test_rejects_compiled_crypto_surface_with_narrow_name_matching(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            classes = (
                (
                    "CryptoLeak",
                    (
                        ("key", "()Ljavax/crypto/SecretKey;", 0x0101),
                        ("mac", "(Ljavax/crypto/Mac;)V", 0x0104),
                        ("entry", "()Ljava/security/KeyStore$Entry;", 0x0101),
                    ),
                ),
                (
                    "UnsafeOwner",
                    (
                        ("signEnvelope", "([B)[B", 0x0101),
                        ("attestProof", "([B)Z", 0x0104),
                        ("loadExistingKey", "()[B", 0x0101),
                    ),
                ),
                (
                    "StudentOutboxAuthenticator",
                    (("verify", "([B)Z", 0x0101),),
                ),
                (
                    "StudentOutboxAuthenticityVerifier",
                    (("verifiedStatus", "([B)Z", 0x0101),),
                ),
                (
                    "SignedStatusStore",
                    (("signedSummary", "()Ljava/lang/String;", 0x0101),),
                ),
            )
            class_root = (
                root
                / "core/student-mistake-database/build/classes/com/tingyun/"
                "smartmistakebook/core/student/mistake/database"
            )
            class_root.mkdir(parents=True, exist_ok=True)
            package = "com/tingyun/smartmistakebook/core/student/mistake/database"
            for class_name, methods in classes:
                (class_root / f"{class_name}.class").write_bytes(
                    compiled_class_fixture(f"{package}/{class_name}", methods)
                )

            violations = audit_compiled_artifacts(root, [class_root])
            self.assertEqual(
                Counter(
                    {
                        "compiled-public-crypto-material-api": 3,
                        "compiled-public-crypto-owner-api": 4,
                    }
                ),
                Counter(violation.rule for violation in violations),
            )

    def test_compiled_api_requires_class_and_method_external_visibility(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            class_root = (
                root
                / "core/student-mistake-database/build/classes/com/tingyun/"
                "smartmistakebook/core/student/mistake/database"
            )
            class_root.mkdir(parents=True, exist_ok=True)
            package = "com/tingyun/smartmistakebook/core/student/mistake/database"
            raw_method = (
                (
                    "requireAuthentic",
                    "(Lcom/tingyun/smartmistakebook/core/model/storage/"
                    "StudentMistakeRelayMessage;)V",
                    0x0101,
                ),
            )
            (class_root / "PackagePrivateAuthenticator.class").write_bytes(
                compiled_class_fixture(
                    f"{package}/PackagePrivateAuthenticator",
                    raw_method,
                    class_access_flags=0x0020,
                )
            )
            (class_root / "PublicAuthenticator.class").write_bytes(
                compiled_class_fixture(
                    f"{package}/PublicAuthenticator",
                    raw_method,
                    class_access_flags=0x0021,
                )
            )

            violations = audit_compiled_artifacts(root, [class_root])
            self.assertEqual(
                Counter(
                    {
                        "compiled-public-crypto-owner-api": 1,
                        "compiled-public-raw-relay-api": 1,
                    }
                ),
                Counter(violation.rule for violation in violations),
            )
            self.assertTrue(
                all(
                    "PackagePrivateAuthenticator" not in violation.excerpt
                    for violation in violations
                )
            )

    def test_rejects_compiled_split_package_injection(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            class_path = (
                root
                / "feature/tutor/build/classes/com/tingyun/smartmistakebook/core/"
                "mastery/database/Injected.class"
            )
            class_path.parent.mkdir(parents=True, exist_ok=True)
            class_path.write_bytes(
                compiled_class_fixture(
                    "com/tingyun/smartmistakebook/core/mastery/database/Injected",
                    (("status", "()Ljava/lang/String;", 0x0101),),
                )
            )
            self.assertEqual(
                ["compiled-unauthorized-store-split-package"],
                [
                    violation.rule
                    for violation in audit_compiled_artifacts(root, [class_path])
                ],
            )

    def test_accepts_safe_compiled_owner_class_from_nested_aar(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            aar_path = root / "core/learner-mastery-database/build/outputs/mastery.aar"
            aar_path.parent.mkdir(parents=True, exist_ok=True)
            safe_class = compiled_class_fixture(
                "com/tingyun/smartmistakebook/core/mastery/database/SafeStore",
                (("signedSummary", "()Ljava/lang/String;", 0x0101),),
            )
            classes_jar = io.BytesIO()
            with zipfile.ZipFile(classes_jar, "w") as archive:
                archive.writestr(
                    "com/tingyun/smartmistakebook/core/mastery/database/SafeStore.class",
                    safe_class,
                )
            with zipfile.ZipFile(aar_path, "w") as archive:
                archive.writestr("classes.jar", classes_jar.getvalue())
            self.assertEqual([], audit_compiled_artifacts(root, [aar_path]))

    def test_rejects_wrong_physical_database_filename(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.seed_physical_stores(root)
            target = (
                root
                / "core/student-mistake-database/src/main/kotlin/PhysicalStore.kt"
            )
            target.write_text(
                'const val STUDENT_MISTAKE_DATABASE_NAME = "learner-mastery.db"\n'
                '@Database(entities = [], version = 1)\n'
                'abstract class StudentMistakeRoomDatabase : RoomDatabase()\n',
                encoding="utf-8",
            )
            self.assertEqual(
                ["physical-store-name-mismatch"],
                [violation.rule for violation in audit(root)],
            )

    def test_rejects_missing_room_database_container(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.seed_physical_stores(root)
            target = root / "core/knowledge-database/src/main/kotlin/PhysicalStore.kt"
            target.write_text(
                'const val HIGH_SCHOOL_KNOWLEDGE_DATABASE_NAME = "high-school-knowledge.db"\n',
                encoding="utf-8",
            )
            self.assertEqual(
                ["physical-room-store-declaration"],
                [violation.rule for violation in audit(root)],
            )

    def test_rejects_missing_store_module(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.seed_physical_stores(root)
            shutil.rmtree(root / "core/learner-mastery-database")
            self.assertEqual(
                ["physical-store-module-missing"],
                [violation.rule for violation in audit(root)],
            )

    def test_repository_keeps_all_three_stores_independent(self) -> None:
        repository_root = Path(__file__).resolve().parents[2]
        self.assertEqual([], audit(repository_root))


if __name__ == "__main__":
    unittest.main()
