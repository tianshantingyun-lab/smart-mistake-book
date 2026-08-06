package com.tingyun.smartmistakebook.core.data.capture

import android.content.Context
import com.tingyun.smartmistakebook.core.data.authority.CurrentGenerationBatchImportClaim
import com.tingyun.smartmistakebook.core.data.authority.CurrentGenerationBatchImportConstructionClaim
import com.tingyun.smartmistakebook.core.data.session.BatchImportSessionPort
import com.tingyun.smartmistakebook.core.data.session.SessionScope
import com.tingyun.smartmistakebook.core.domain.ModelTaskRepository
import java.io.File
import java.lang.reflect.Modifier
import kotlinx.coroutines.CoroutineScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductionBatchImportArchitectureTest {
    @Test
    fun rawConstructionIsPrivateAndFactoryAcceptsOnlyAnOpaqueClaim() {
        val repositoryType =
            Class.forName(
                "com.tingyun.smartmistakebook.core.data.capture." +
                    "ProductionBatchImportRepository",
            )
        assertFalse(Modifier.isPublic(repositoryType.modifiers))
        val constructors = repositoryType.declaredConstructors
        assertTrue(constructors.all { constructor -> Modifier.isPrivate(constructor.modifiers) })

        val reflectedTypes =
            constructors
                .flatMap { constructor -> constructor.parameterTypes.toList() }
                .map { type -> type.name }

        FORBIDDEN_PRODUCTION_TYPE_FRAGMENTS.forEach { forbidden ->
            assertFalse(
                "Production batch import exposes a forbidden dependency: $forbidden",
                reflectedTypes.any { type -> forbidden in type },
            )
        }
        assertTrue(
            "Production batch import must receive BatchImportSessionPort",
            reflectedTypes.any { type -> type.endsWith(".BatchImportSessionPort") },
        )
        assertTrue(
            "Production batch import must receive CaptureDraftSessionPort",
            reflectedTypes.any { type -> type.endsWith(".CaptureDraftSessionPort") },
        )

        val publicFactoryMethods =
            ProductionBatchImportRepositoryFactory::class.java.declaredMethods
                .filter { method -> Modifier.isPublic(method.modifiers) }
        assertEquals(2, publicFactoryMethods.size)
        assertEquals(
            mapOf(
                "issue" to listOf(CurrentGenerationBatchImportConstructionClaim::class.java),
                "open" to listOf(CurrentGenerationBatchImportClaim::class.java),
            ),
            publicFactoryMethods.associate { method ->
                method.name to method.parameterTypes.toList()
            },
        )

        val forbiddenPublicInputs =
            setOf(
                Context::class.java,
                SessionScope::class.java,
                BatchImportSessionPort::class.java,
                CaptureDraftSessionPort::class.java,
                CoroutineScope::class.java,
                ModelTaskRepository::class.java,
            )
        val publicRawInputs =
            buildList {
                constructors
                    .filter { constructor -> Modifier.isPublic(constructor.modifiers) }
                    .flatMapTo(this) { constructor -> constructor.parameterTypes.toList() }
                publicFactoryMethods
                    .flatMapTo(this) { method -> method.parameterTypes.toList() }
                Class.forName(
                    "com.tingyun.smartmistakebook.core.data.capture." +
                        "ProductionBatchImportRepositoryKt",
                ).declaredMethods
                    .filter { method -> Modifier.isPublic(method.modifiers) }
                    .flatMapTo(this) { method -> method.parameterTypes.toList() }
            }
        assertTrue(
            "Release JVM ABI exposes caller-controlled batch-import construction inputs",
            publicRawInputs.none(forbiddenPublicInputs::contains),
        )
    }

    @Test
    fun productionSourceCannotReachLegacyOrAnyBusinessDatabase() {
        val source = productionSource().readText()

        FORBIDDEN_PRODUCTION_SOURCE_TERMS.forEach { forbidden ->
            assertFalse(
                "Production batch import contains forbidden source dependency: $forbidden",
                forbidden in source,
            )
        }
        listOf(
            "BatchImportSessionPort",
            "CaptureDraftSessionPort",
            "ReadCaptureDraftMergeReceiptQuery",
            "boundaryClaimedAtEpochMillis",
            "CANCELLATION_AVAILABLE: Boolean = false",
            "BatchImportSessionMutation.SetStatus",
            "BatchImportSessionMutation.RetryPage",
            "BatchImportSessionMutation.SkipPage",
            "BatchImportSessionMutation.RequeueInterruptedPages",
            "BatchImportSessionMutation.RequeueInterruptedBoundaries",
        ).forEach { required ->
            assertTrue(
                "Production batch import is missing required contract: $required",
                required in source,
            )
        }
        assertTrue(
            "A retried page must reuse one stable capture import request identity",
            "requestId = \"batch:${'$'}jobId:${'$'}{claimed.pageIndex}\"" in source,
        )
    }

    @Test
    fun durableCaptureReceiptIsRecoveredBeforeInterruptedBoundariesAreRequeued() {
        val source = productionSource().readText()
        val organize =
            source.substringAfter("override suspend fun organizeBatch")
                .substringBefore("private fun schedule")
        val recovery = organize.indexOf("recoverDurableCaptureMerges(initial)")
        val requeue = organize.indexOf("requeueInterruptedBoundaries(")

        assertTrue("Capture receipt recovery is missing", recovery >= 0)
        assertTrue("Interrupted boundary requeue is missing", requeue >= 0)
        assertTrue(
            "A durable capture receipt must be consumed before boundary requeue",
            recovery < requeue,
        )
    }

    @Test
    fun mergeAndSessionResolutionUseThePersistedBoundaryClaimTime() {
        val source = productionSource().readText()
        val merge =
            source.substringAfter("private suspend fun mergeAdjacentDrafts")
                .substringBefore("private suspend fun loadBatchPageSources")
        val resolve =
            source.substringAfter("private suspend fun resolveBoundary")
                .substringBefore("private suspend fun mergeAdjacentDrafts")

        assertTrue(
            "Capture merge must use the persisted boundary claim time",
            "occurredAtEpochMillis = boundaryClaimedAtEpochMillis" in merge,
        )
        assertFalse(
            "Capture merge must not use batch creation time",
            "createdAtEpochMillis" in merge,
        )
        assertTrue(
            "Durable merge receipt lookup must happen before mutable draft reads",
            merge.indexOf("readMergeReceipt(") <
                merge.indexOf("readCanonicalSourceAssets("),
        )
        assertTrue(
            "Session resolution must echo the persisted boundary claim time",
            "boundaryClaimedAtEpochMillis = boundaryClaimedAtEpochMillis" in resolve,
        )
        assertFalse(
            "Session resolution must not invent a replay-dependent current time",
            "nowEpochMillis()" in resolve,
        )
    }

    @Test
    fun legacyRoomImplementationIsPrivateAndMigrationOnly() {
        val root = projectRoot()
        val oldProductionPath =
            File(
                root,
                "core/data/src/main/kotlin/com/tingyun/smartmistakebook/core/data/capture/" +
                    "RoomBatchImportRepository.kt",
            )
        val migrationSource =
            File(
                root,
                "core/data/src/main/kotlin/com/tingyun/smartmistakebook/core/data/session/" +
                    "migration/LegacyRoomBatchImportRepository.kt",
            )

        assertFalse(
            "Legacy Room batch repository must not remain in the production capture package",
            oldProductionPath.exists(),
        )
        assertTrue("Legacy batch migration source is missing", migrationSource.isFile)
        val source = migrationSource.readText()
        assertTrue(
            "Legacy Room batch implementation must remain private",
            "private class LegacyRoomBatchImportRepository" in source,
        )
        assertTrue(
            "Legacy batch factory must remain migration-scoped",
            "private object LegacyBatchImportRepositoryFactory" in source,
        )
        val legacyFactory =
            Class.forName(
                "com.tingyun.smartmistakebook.core.data.session.migration." +
                    "LegacyBatchImportRepositoryFactory",
            )
        assertFalse(
            "Legacy migration factory must not be public in release bytecode",
            Modifier.isPublic(legacyFactory.modifiers),
        )
    }

    private fun productionSource(): File =
        File(
            projectRoot(),
            "core/data/src/main/kotlin/com/tingyun/smartmistakebook/core/data/capture/" +
                "ProductionBatchImportRepository.kt",
        )

    private fun projectRoot(): File =
        generateSequence(
            File(requireNotNull(System.getProperty("user.dir"))).canonicalFile,
        ) { directory -> directory.parentFile }
            .firstOrNull { directory -> File(directory, "settings.gradle.kts").isFile }
            ?: error("Could not locate project root")

    private companion object {
        val FORBIDDEN_PRODUCTION_TYPE_FRAGMENTS =
            listOf(
                "StudyDatabasePort",
                "RoomBatchImportRepository",
                "CaptureWorkflowRepository",
                "LearnerMastery",
                "KnowledgeDatabase",
                "ReviewPlan",
            )

        val FORBIDDEN_PRODUCTION_SOURCE_TERMS =
            listOf(
                "StudyDatabasePort",
                "RoomBatchImportRepository",
                "LegacyBatchImportRepositoryFactory",
                "CaptureWorkflowRepository",
                "core.database",
                "student.mistake.database",
                "mastery.database",
                "knowledge.database",
                "ReviewPlan",
                "beforePossibleMergeWith",
                "900",
            )
    }
}
