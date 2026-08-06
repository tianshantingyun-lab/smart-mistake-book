package com.tingyun.smartmistakebook.core.student.mistake.database

import java.io.File
import java.lang.reflect.Modifier
import javax.crypto.SecretKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StudentOutboxAuthenticityBoundaryTest {
    @Test
    fun verifierIsFinalAndHasNoPublicConstructionOrImplementationSurface() {
        val type = StudentOutboxAuthenticityVerifier::class.java

        assertTrue(Modifier.isFinal(type.modifiers))
        assertFalse(type.isInterface)
        assertTrue(
            type.declaredConstructors.none { constructor ->
                Modifier.isPublic(constructor.modifiers) ||
                    Modifier.isProtected(constructor.modifiers)
            },
        )
        assertEquals(
            setOf("requireAuthentic"),
            type.declaredMethods
                .filter { method -> Modifier.isPublic(method.modifiers) }
                .mapTo(sortedSetOf(), java.lang.reflect.Method::getName),
        )
        assertTrue(
            type.declaredMethods
                .filter { method -> method.name == "ownerIssued" }
                .none { method ->
                    Modifier.isPublic(method.modifiers) ||
                        Modifier.isProtected(method.modifiers)
                },
        )
        assertTrue(
            runCatching {
                Class.forName(
                    "com.tingyun.smartmistakebook.core.model.storage." +
                        "StudentOutboxAuthenticityVerifier",
                )
            }.isFailure,
        )
    }

    @Test
    fun signingAndSecretKeyApisRemainJvmPackagePrivate() {
        val ownerTypes =
            listOf(
                StudentOutboxAuthenticator::class.java,
                StudentOutboxAuthenticatorSession::class.java,
                StudentOutboxAuthenticityIssuer::class.java,
                StudentOutboxHmacKeyStore::class.java,
                AndroidKeystoreStudentOutboxHmacKeyStore::class.java,
            )

        ownerTypes.forEach { type ->
            assertFalse(Modifier.isPublic(type.modifiers))
            assertTrue(
                type.declaredConstructors.none { constructor ->
                    Modifier.isPublic(constructor.modifiers) ||
                        Modifier.isProtected(constructor.modifiers)
                },
            )
            assertTrue(
                type.declaredMethods.none { method ->
                    val exported =
                        Modifier.isPublic(method.modifiers) ||
                            Modifier.isProtected(method.modifiers)
                    exported &&
                        (
                            method.returnType == SecretKey::class.java ||
                                method.parameterTypes.any { parameter ->
                                    parameter == SecretKey::class.java
                                }
                            )
                },
            )
        }
        assertTrue(
            runCatching {
                Class.forName(
                    "com.tingyun.smartmistakebook.core.student.mistake.database." +
                        "StudentOutboxAuthenticatorKt",
                )
            }.isFailure,
        )
    }

    @Test
    fun productionReferencesAreConfinedToStudentOwnerAndCoreDataAuthorityAssembly() {
        val root = authenticityProjectRoot()
        val references =
            root.walkTopDown()
                .filter { file ->
                    file.isFile &&
                        file.extension in setOf("kt", "java") &&
                        "/src/main/" in file.invariantSeparatorsPath &&
                        "StudentOutboxAuthenticityVerifier" in file.readText()
                }
                .map { file -> file.relativeTo(root).invariantSeparatorsPath }
                .toSet()

        assertEquals(ALLOWED_PRODUCTION_REFERENCES, references)
        assertTrue(
            references.none { path ->
                path.startsWith("feature/") ||
                    "/network/" in path ||
                    path.startsWith("core/model-provider/")
            },
        )

        val ownerSources =
            ALLOWED_OWNER_FACTORY_REFERENCES.map { path -> File(root, path).readText() }
        assertTrue(ownerSources.all { source -> "ownerIssued" in source })
    }

    @Test
    fun pendingReadNeverSignsAnArbitraryDatabaseRow() {
        val root = authenticityProjectRoot()
        val journal =
            File(
                root,
                "core/student-mistake-database/src/main/kotlin/com/tingyun/" +
                    "smartmistakebook/core/student/mistake/database/" +
                    "StudentMistakeMessageJournal.kt",
            ).readText()
        val enqueueMapping =
            File(
                root,
                "core/student-mistake-database/src/main/java/com/tingyun/" +
                    "smartmistakebook/core/student/mistake/database/" +
                    "StudentOutboxEntityFactory.java",
            ).readText()

        assertFalse("readPending must never mint a proof", ".attest(" in journal)
        assertTrue("readPending must reconstruct the persisted proof", "authenticityTagHex" in journal)
        assertTrue(
            "trusted enqueue must bind the proof before persistence",
            "authenticityIssuer.attest(envelope)" in enqueueMapping,
        )
    }

    private companion object {
        val ALLOWED_PRODUCTION_REFERENCES =
            setOf(
                "core/student-mistake-database/src/main/java/com/tingyun/" +
                    "smartmistakebook/core/student/mistake/database/" +
                    "StudentOutboxAuthenticityVerifier.java",
                "core/student-mistake-database/src/main/java/com/tingyun/" +
                    "smartmistakebook/core/student/mistake/database/" +
                    "StudentMistakeRuntimeCapabilities.java",
                "core/student-mistake-database/src/main/java/com/tingyun/" +
                    "smartmistakebook/core/student/mistake/database/" +
                    "StudentOutboxAuthenticator.java",
                "core/student-mistake-database/src/main/kotlin/com/tingyun/" +
                    "smartmistakebook/core/student/mistake/database/" +
                    "StudentMistakeMessageJournal.kt",
                "core/data/src/main/kotlin/com/tingyun/smartmistakebook/core/data/" +
                    "authority/LocalLearningAuthorityRelay.kt",
                "core/data/src/main/kotlin/com/tingyun/smartmistakebook/core/data/" +
                    "authority/LocalLearningAuthorityRuntime.kt",
                "core/data/src/main/java/com/tingyun/smartmistakebook/core/data/" +
                    "authority/StudentMistakeOwnerAccess.java",
            )
        val ALLOWED_OWNER_FACTORY_REFERENCES =
            setOf(
                "core/student-mistake-database/src/main/java/com/tingyun/" +
                    "smartmistakebook/core/student/mistake/database/" +
                    "StudentOutboxAuthenticityVerifier.java",
                "core/student-mistake-database/src/main/java/com/tingyun/" +
                    "smartmistakebook/core/student/mistake/database/" +
                    "StudentOutboxAuthenticator.java",
            )
    }
}

private fun authenticityProjectRoot(): File =
    generateSequence(
        File(requireNotNull(System.getProperty("user.dir"))).canonicalFile,
        File::getParentFile,
    ).first { directory -> File(directory, "settings.gradle.kts").isFile }
