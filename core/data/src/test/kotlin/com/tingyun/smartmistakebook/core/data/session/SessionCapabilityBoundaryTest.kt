package com.tingyun.smartmistakebook.core.data.session

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionCapabilityBoundaryTest {
    @Test
    fun publicPortSurfacesDoNotExposeLegacyDatabaseOrRoomTypes() {
        val violations =
            SESSION_PORTS.flatMap { port ->
                port.declaredMethods.flatMap { method ->
                    (
                        method.genericParameterTypes.map { it.typeName } +
                            method.genericReturnType.typeName
                        ).filter { signature ->
                            FORBIDDEN_SIGNATURE_FRAGMENTS.any(signature::contains)
                        }.map { signature -> "${port.simpleName}.${method.name}: $signature" }
                }
            }

        assertTrue(
            "Session capability signatures expose a database implementation type: $violations",
            violations.isEmpty(),
        )
    }

    @Test
    fun sessionPortsRemainNarrow() {
        val unexpected =
            SESSION_PORTS.mapNotNull { port ->
                val declaredMethodCount = port.declaredMethods.size
                val maximum = MAX_DECLARED_METHODS.getValue(port)
                if (declaredMethodCount <= maximum) {
                    null
                } else {
                    "${port.simpleName} has $declaredMethodCount methods (max $maximum)"
                }
            }

        assertTrue("Session capabilities grew into god ports: $unexpected", unexpected.isEmpty())
    }

    @Test
    fun contractSourcesContainNoDatabaseDependency() {
        val root = projectRoot()
        val violations =
            CONTRACT_FILES.flatMap { relativePath ->
                val source = File(root, relativePath)
                assertTrue("Missing session contract source: $relativePath", source.isFile)
                val text = source.readText()
                FORBIDDEN_CONTRACT_SOURCE_TOKENS
                    .filter(text::contains)
                    .map { token -> "$relativePath -> $token" }
            }

        assertTrue(
            "Session contract sources depend on persistence implementation details: $violations",
            violations.isEmpty(),
        )
    }

    @Test
    fun legacyAdaptersKeepTheirNarrowDependencyPrivateAndLearnerBound() {
        val root = projectRoot()
        val violations =
            LEGACY_ADAPTER_FILES.flatMap { relativePath ->
                val source = File(root, relativePath)
                assertTrue("Missing legacy session adapter: $relativePath", source.isFile)
                val text = source.readText()
                buildList {
                    if ("private val boundScope: SessionScope" !in text) {
                        add("$relativePath does not bind a learner scope")
                    }
                    if ("private val legacy:" !in text) {
                        add("$relativePath does not keep its legacy dependency private")
                    }
                    if (".requireBoundTo(boundScope)" !in text) {
                        add("$relativePath does not reject cross-scope access")
                    }
                }
            }

        assertTrue("Legacy session adapters lost their boundary: $violations", violations.isEmpty())
    }

    @Test
    fun legacyAdapterAndFactorySourcesNeverAcceptTheDatabaseGodPort() {
        val root = projectRoot()
        val violations =
            LEGACY_DATABASE_ADAPTER_FILES.filter { relativePath ->
                val source = File(root, relativePath)
                assertTrue("Missing legacy database adapter: $relativePath", source.isFile)
                source.readText().contains("StudyDatabasePort")
            }

        assertTrue(
            "Legacy adapters or factories still accept StudyDatabasePort: $violations",
            violations.isEmpty(),
        )
    }

    @Test
    fun batchBoundaryBridgeCannotInvokeCaptureMerge() {
        val root = projectRoot()
        val adapter =
            File(
                root,
                "$SESSION_MAIN/LegacyBatchImportSessionAdapter.kt",
            ).readText()
        val store =
            File(
                root,
                "core/database/src/main/kotlin/com/tingyun/smartmistakebook/core/database/" +
                    "RoomBatchImportStore.kt",
            ).readText()

        assertFalse(adapter.contains("resolveBatchImportBoundary("))
        assertFalse(adapter.contains("mergeSourceBundle("))
        assertFalse(store.contains("mergeSourceBundle("))
        assertFalse(store.contains("problemDraftTransactionDao()"))
        assertTrue(adapter.contains("recordBatchImportBoundarySessionResolution("))
        assertTrue(store.contains("captureMergeReceiptRef"))
    }

    @Test
    fun organizationProcessorDependsOnSessionCapabilityNotTheDatabaseGodPort() {
        val source =
            File(
                projectRoot(),
                "core/data/src/main/kotlin/com/tingyun/smartmistakebook/core/data/mistake/" +
                    "ProblemOrganizationWorkProcessor.kt",
            ).readText()

        assertTrue(source.contains("ProblemOrganizationWorkSessionPort"))
        assertFalse(source.contains("com.tingyun.smartmistakebook.core.database"))
        assertFalse(source.contains("StudyDatabasePort"))
        assertFalse(source.contains("ProblemOrganizationWorkRecord"))
        assertFalse(source.contains("StudyDbValue"))
    }

    private fun projectRoot(): File =
        generateSequence(
            File(requireNotNull(System.getProperty("user.dir"))).canonicalFile,
        ) { directory ->
            directory.parentFile
        }.firstOrNull { directory ->
            File(directory, "settings.gradle.kts").isFile
        } ?: error("Could not locate the project root")

    companion object {
        private const val SESSION_MAIN =
            "core/data/src/main/kotlin/com/tingyun/smartmistakebook/core/data/session"

        private val SESSION_PORTS =
            listOf(
                BatchImportSessionPort::class.java,
                ModelTaskSessionPort::class.java,
                CaptureModelDependencyReadPort::class.java,
                ProblemOrganizationWorkSessionPort::class.java,
                TutorConversationSessionPort::class.java,
                TutorInteractionSessionPort::class.java,
            )

        private val MAX_DECLARED_METHODS =
            mapOf(
                BatchImportSessionPort::class.java to 5,
                ModelTaskSessionPort::class.java to 5,
                CaptureModelDependencyReadPort::class.java to 1,
                ProblemOrganizationWorkSessionPort::class.java to 7,
                TutorConversationSessionPort::class.java to 2,
                TutorInteractionSessionPort::class.java to 9,
            )

        private val FORBIDDEN_SIGNATURE_FRAGMENTS =
            listOf(
                "com.tingyun.smartmistakebook.core.database",
                "androidx.room",
                ".dao.",
                ".entity.",
                "StudyDatabasePort",
            )

        private val FORBIDDEN_CONTRACT_SOURCE_TOKENS =
            listOf(
                "com.tingyun.smartmistakebook.core.database",
                "androidx.room",
                "StudyDatabasePort",
                "@Dao",
                "@Query",
                "@Entity",
            )

        private val CONTRACT_FILES =
            listOf(
                "$SESSION_MAIN/SessionCapabilityContracts.kt",
                "$SESSION_MAIN/BatchImportSessionPort.kt",
                "$SESSION_MAIN/ModelTaskSessionPort.kt",
                "$SESSION_MAIN/CaptureModelDependencyReadPort.kt",
                "$SESSION_MAIN/ProblemOrganizationWorkSessionPort.kt",
                "$SESSION_MAIN/TutorConversationSessionPort.kt",
                "$SESSION_MAIN/TutorInteractionSessionPort.kt",
            )

        private val LEGACY_ADAPTER_FILES =
            listOf(
                "$SESSION_MAIN/LegacyBatchImportSessionAdapter.kt",
                "$SESSION_MAIN/LegacyModelTaskSessionAdapter.kt",
                "$SESSION_MAIN/LegacyCaptureModelDependencyReadAdapter.kt",
                "$SESSION_MAIN/LegacyProblemOrganizationWorkSessionAdapter.kt",
                "$SESSION_MAIN/LegacyTutorConversationSessionAdapter.kt",
                "$SESSION_MAIN/LegacyTutorInteractionSessionAdapter.kt",
            )

        private val LEGACY_DATABASE_ADAPTER_FILES =
            LEGACY_ADAPTER_FILES +
                listOf(
                    "$SESSION_MAIN/LegacyRoomCaptureSessionAdapter.kt",
                    "$SESSION_MAIN/capture/LegacyRoomCaptureSessionStateStore.kt",
                    "$SESSION_MAIN/migration/LegacyRoomBatchImportRepository.kt",
                )
    }
}
