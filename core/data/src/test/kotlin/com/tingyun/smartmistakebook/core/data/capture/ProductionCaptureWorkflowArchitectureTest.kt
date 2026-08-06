package com.tingyun.smartmistakebook.core.data.capture

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductionCaptureWorkflowArchitectureTest {
    @Test
    fun productionRepositoryAndFactoryCannotReceiveTheLegacyDatabaseOrBroadRepository() {
        val source = productionSource().readText()

        listOf(
            "StudyDatabasePort",
            "RoomCaptureWorkflowRepository",
            "LegacyRoomCaptureSessionStateStore",
            "CaptureWorkflowRepository by",
            "by legacy",
        ).forEach { forbidden ->
            assertFalse(
                "Production capture boundary contains forbidden broad dependency: $forbidden",
                forbidden in source,
            )
        }
        assertFalse("New saves must not use a mirror transition", "MirrorTransition" in source)
        assertTrue(
            "Production factory must accept the owner-composed occurrence port",
            "commitPortProvider" in source,
        )
    }

    @Test
    fun temporarySessionPortDoesNotExposeStudentStoreBusinessObjects() {
        val source = productionSource().readText()
        val port =
            source.substringAfter("internal interface ProductionCaptureSessionPort")
                .substringBefore("internal interface ProductionCaptureCommitPreparationPort")

        listOf(
            "SaveStudentOwnedCaptureCommand",
            "StudentCaptureSaveHandoffRecord",
            "StudentProblemRef",
            "StudentProblemRevisionRef",
            "StudyDatabasePort",
            "RoomCaptureWorkflowRepository",
            "LegacyRoomCaptureSessionStateStore",
        ).forEach { forbidden ->
            assertFalse(
                "Temporary capture-session port leaks a business object: $forbidden",
                forbidden in port,
            )
        }
        assertTrue("Temporary session port must remain learner-scoped", "val learnerId" in port)
        assertTrue(
            "Temporary session acknowledgement must use the narrow receipt",
            "CaptureStudentSaveSessionReceipt" in port,
        )
    }

    @Test
    fun legacyBroadRepositoryIsPrivateInsideTheSessionOwnedAdapter() {
        val source =
            File(
                projectRoot(),
                "core/data/src/main/kotlin/com/tingyun/smartmistakebook/core/data/session/" +
                    "LegacyRoomCaptureSessionAdapter.kt",
            ).readText()

        assertTrue(
            "Legacy Room adapter must not be an internal production type",
            "private class LegacyRoomCaptureSessionAdapter" in source,
        )
        assertFalse(
            "Legacy Room repository escaped through the factory return type",
            Regex(
                """fun\s+create\s*\([^)]*\)\s*:\s*""" +
                    """(?:LegacyRoomCaptureSessionStateStore|StudyDatabasePort)""",
                setOf(RegexOption.DOT_MATCHES_ALL),
            ).containsMatchIn(source),
        )
    }

    @Test
    fun canonicalAssetVaultDoesNotDependOnAnyBusinessDatabase() {
        val source =
            File(
                projectRoot(),
                "core/data/src/main/kotlin/com/tingyun/smartmistakebook/core/data/capture/" +
                    "AndroidCanonicalAssetVault.kt",
            ).readText()

        assertFalse("Canonical asset vault must not import the legacy database", "core.database" in source)
        assertFalse(
            "Canonical asset vault must not import the student mistake database",
            "student.mistake.database" in source,
        )
        assertFalse(
            "Canonical asset vault must not import learner mastery",
            "mastery.database" in source,
        )
        assertTrue(
            "Canonical asset vault must expose only the capture-owned descriptor",
            "CaptureAssetDescriptor" in source,
        )
    }

    @Test
    fun captureDraftSessionPortCannotWriteAnyOfTheThreeBusinessStores() {
        val source =
            File(
                projectRoot(),
                "core/data/src/main/kotlin/com/tingyun/smartmistakebook/core/data/capture/" +
                    "CaptureDraftSessionPort.kt",
            ).readText()

        listOf(
            "core.database",
            "student.mistake.database",
            "mastery.database",
            "knowledge.database",
            "SaveStudentOwnedCaptureCommand",
            "LearningObservation",
            "KnowledgeNode",
        ).forEach { forbidden ->
            assertFalse(
                "Capture draft session port crosses a business-store boundary: $forbidden",
                forbidden in source,
            )
        }
        assertTrue("Capture must own the temporary merge", "mergeAdjacentDrafts" in source)
        assertTrue("Temporary merge must return an immutable receipt", "CaptureDraftMergeReceipt" in source)
    }

    private fun productionSource(): File =
        File(
            projectRoot(),
            "core/data/src/main/kotlin/com/tingyun/smartmistakebook/core/data/capture/" +
                "ProductionCaptureWorkflowRepository.kt",
        )

    private fun projectRoot(): File =
        generateSequence(
            File(requireNotNull(System.getProperty("user.dir"))).canonicalFile,
        ) { directory -> directory.parentFile }
            .firstOrNull { directory -> File(directory, "settings.gradle.kts").isFile }
            ?: error("Could not locate project root")
}
