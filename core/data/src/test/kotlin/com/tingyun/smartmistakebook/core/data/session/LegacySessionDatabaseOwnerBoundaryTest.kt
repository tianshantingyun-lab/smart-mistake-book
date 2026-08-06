package com.tingyun.smartmistakebook.core.data.session

import com.tingyun.smartmistakebook.core.data.mistake.MistakeDetailRepositoryFactory
import com.tingyun.smartmistakebook.core.data.mistake.MistakeOrganizationRepositoryFactory
import com.tingyun.smartmistakebook.core.data.model.ModelTaskRepositoryFactory
import com.tingyun.smartmistakebook.core.data.model.RestrictedModelAssetSourceFactory
import com.tingyun.smartmistakebook.core.data.tutor.TutorInteractionRepositoryFactory
import com.tingyun.smartmistakebook.core.database.LegacyPreCutoverMistakeDetailReadPort
import com.tingyun.smartmistakebook.core.database.LegacyPreCutoverMistakeOrganizationBusinessPort
import com.tingyun.smartmistakebook.core.database.LegacyPreCutoverOrganizationReauthorizationPort
import com.tingyun.smartmistakebook.core.database.LegacyPreCutoverTutorInteractionSessionDatabasePort
import com.tingyun.smartmistakebook.core.database.LegacySessionDatabaseOwner
import com.tingyun.smartmistakebook.core.database.LegacySessionDatabaseOwnerFactory
import com.tingyun.smartmistakebook.core.database.StudyDatabasePort
import com.tingyun.smartmistakebook.core.database.StudyDatabaseFactory
import com.tingyun.smartmistakebook.core.database.TrustedBatchSessionDatabaseCapability
import com.tingyun.smartmistakebook.core.database.TrustedCaptureSessionDatabaseCapability
import com.tingyun.smartmistakebook.core.database.TrustedLegacyCutoverMigrationDatabaseCapability
import com.tingyun.smartmistakebook.core.database.TrustedModelTaskDatabaseCapability
import com.tingyun.smartmistakebook.core.database.TrustedOrganizationWorkDatabaseCapability
import com.tingyun.smartmistakebook.core.database.TrustedTutorSessionDatabaseCapability
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacySessionDatabaseOwnerBoundaryTest {
    @Test
    fun ownerAndIssuedCapabilitiesNeverExposeTheRawDatabaseOrCloseableHandles() {
        val violations =
            (listOf(LegacySessionDatabaseOwner::class.java) + ISSUED_CAPABILITIES)
                .flatMap { capability ->
                    capability.methods.flatMap { method ->
                        val signatures =
                            method.genericParameterTypes.map { it.typeName } +
                                method.genericReturnType.typeName
                        signatures.filter { signature ->
                            FORBIDDEN_TYPE_FRAGMENTS.any(signature::contains)
                        }.map { signature ->
                            "${capability.simpleName}.${method.name}: $signature"
                        }
                    }
                }

        assertTrue("Legacy owner ABI leaked a broad handle: $violations", violations.isEmpty())
        ISSUED_CAPABILITIES.forEach { capability ->
            assertFalse(
                "${capability.simpleName} owns the database lifecycle",
                capability.methods.any { method -> method.name == "close" },
            )
        }
    }

    @Test
    fun issuedCapabilitiesContainNoBusinessAuthorityWriter() {
        val violations =
            ISSUED_CAPABILITIES.flatMap { capability ->
                capability.methods
                    .filter { method -> method.name in FORBIDDEN_BUSINESS_METHODS }
                    .map { method -> "${capability.simpleName}.${method.name}" }
            }

        assertTrue("Legacy owner issued a business authority writer: $violations", violations.isEmpty())
    }

    @Test
    fun publicOwnerCannotCombineCutoverJournalAppendWithBarrierActivation() {
        val ownerMethodNames =
            LegacySessionDatabaseOwner::class.java.methods.mapTo(mutableSetOf()) { it.name }
        val cutoverMethodNames =
            TrustedLegacyCutoverMigrationDatabaseCapability::class.java.methods
                .mapTo(mutableSetOf()) { it.name }

        assertFalse("Public owner exposes the raw v45 barrier", "businessWriteBarrierOwner" in ownerMethodNames)
        assertFalse("Cutover proof capability appends terminal receipts", "appendStageReceipt" in cutoverMethodNames)
        assertFalse(
            "Cutover proof capability activates the terminal barrier",
            "activateTerminalCutover" in cutoverMethodNames,
        )
    }

    @Test
    fun releaseFactoryAbiContainsNoBroadDatabaseOpener() {
        val forbidden =
            StudyDatabaseFactory::class.java.declaredMethods
                .filter { method ->
                    method.name.contains("open", ignoreCase = true) ||
                        FORBIDDEN_TYPE_FRAGMENTS.any { fragment ->
                            method.genericReturnType.typeName.contains(fragment)
                        }
                }.map { method -> method.toGenericString() }

        assertTrue("Release StudyDatabaseFactory exposes a test opener: $forbidden", forbidden.isEmpty())
    }

    @Test
    fun productionOwnerAbiAcceptsOnlyAndroidContext() {
        val openMethods =
            LegacySessionDatabaseOwnerFactory::class.java.methods
                .filter { method -> method.name == "open" }

        assertEquals(1, openMethods.size)
        assertEquals(
            listOf("android.content.Context"),
            openMethods.single().parameterTypes.map { parameterType -> parameterType.name },
        )
    }

    @Test
    fun productionSourceHasOneFixedNameOwnerOpenerAndNoBroadFactoryCallers() {
        val root = projectRoot()
        val databaseFactory =
            File(
                root,
                "core/database/src/main/kotlin/com/tingyun/smartmistakebook/core/database/" +
                    "StudyDatabase.kt",
            ).readText()
        val owner =
            File(
                root,
                "core/database/src/main/kotlin/com/tingyun/smartmistakebook/core/database/" +
                    "LegacySessionDatabasePort.kt",
            ).readText()

        assertFalse(databaseFactory.contains("fun open("))
        assertFalse(databaseFactory.contains("fun openInMemory("))
        assertFalse(databaseFactory.contains("fun openPreCutoverForTest("))
        assertTrue(owner.contains("object LegacySessionDatabaseOwnerFactory"))
        assertTrue(owner.contains("fun open(context: Context): LegacySessionDatabaseOwner"))
        assertFalse(owner.contains("databaseName: String"))
        assertTrue(owner.contains(".canonicalPath"))
        assertFalse(owner.contains(".absolutePath"))
        assertTrue(owner.contains("private fun openProductionLegacyDatabase("))
        assertTrue(owner.contains("database.useConnection(isReadOnly = false)"))

        val violations =
            productionKotlinSources(root)
                .filterNot { source ->
                    source.invariantSeparatorsPath.endsWith(
                        "core/database/src/main/kotlin/com/tingyun/smartmistakebook/" +
                            "core/database/LegacySessionDatabasePort.kt",
                    )
                }.filter { source ->
                    source.readText().contains("StudyDatabaseFactory.open(")
                }.map { source -> source.relativeTo(root).invariantSeparatorsPath }
                .toList()

        assertTrue("Production source bypasses the legacy owner: $violations", violations.isEmpty())
    }

    @Test
    fun barrierActivationBridgeIsInternalAndRequiresVerifiedFence() {
        val source =
            File(
                projectRoot(),
                "core/data/src/main/kotlin/com/tingyun/smartmistakebook/core/data/authority/" +
                    "ThreeAuthorityLegacyBusinessWriteBarrierOwner.kt",
            ).readText()

        assertTrue(source.contains("internal fun interface ThreeAuthorityLegacyBusinessWriteBarrierOwner"))
        assertTrue(source.contains("proof: VerifiedThreeAuthorityFence"))
        assertTrue(source.contains("internal object ThreeAuthorityLegacyBusinessWriteBarrierOwnerFactory"))
    }

    @Test
    fun wpCConsumerSourcesContainNoDatabaseGodPort() {
        val root = projectRoot()
        val actual =
            productionKotlinSources(root)
                .filter { source ->
                    source.invariantSeparatorsPath.contains("/core/data/src/main/") &&
                        source.readText().contains("StudyDatabasePort")
                }.map { source -> source.relativeTo(root).invariantSeparatorsPath }
                .toSet()

        assertTrue("WP-C left broad core:data consumers: $actual", actual.isEmpty())
    }

    @Test
    fun wpCFactoryAbiAcceptsOnlyOwnerIssuedOrExplicitPreCutoverCapabilities() {
        val signatures =
            mapOf(
                factoryMethod(ModelTaskRepositoryFactory::class.java, "create") to
                    listOf(
                        "TrustedModelTaskDatabaseCapability",
                        "ModelGateway",
                    ),
                factoryMethod(RestrictedModelAssetSourceFactory::class.java, "create") to
                    listOf(
                        "Context",
                        "TrustedModelTaskDatabaseCapability",
                    ),
                factoryMethod(
                    MistakeDetailRepositoryFactory::class.java,
                    "createLegacyDuringAuthorityMigration",
                ) to
                    listOf(
                        "Context",
                        "LegacyPreCutoverMistakeDetailReadPort",
                    ),
                factoryMethod(
                    MistakeOrganizationRepositoryFactory::class.java,
                    "createLegacyDuringAuthorityMigration",
                ) to
                    listOf(
                        "LegacyPreCutoverMistakeOrganizationBusinessPort",
                        "TrustedOrganizationWorkDatabaseCapability",
                        "LegacyPreCutoverOrganizationReauthorizationPort",
                        "TrustedModelTaskDatabaseCapability",
                        "ReviewedProblemKnowledgeContextRepository",
                    ),
                factoryMethod(
                    TutorInteractionRepositoryFactory::class.java,
                    "createLegacyPreCutover",
                ) to
                    listOf("LegacyPreCutoverTutorInteractionSessionDatabasePort"),
            )

        signatures.forEach { (method, expectedParameters) ->
            assertEquals(
                "${method.declaringClass.simpleName}.${method.name}",
                expectedParameters,
                method.parameterTypes.map { parameter -> parameter.simpleName },
            )
            assertFalse(
                "${method.declaringClass.simpleName}.${method.name} accepts StudyDatabasePort",
                method.parameterTypes.any { parameter -> parameter == StudyDatabasePort::class.java },
            )
        }
        assertFalse(
            "Tutor factory must not imply owner-issued authority for frozen interaction rows",
            TutorInteractionRepositoryFactory::class.java.declaredMethods.any { method ->
                method.name == "create"
            },
        )
    }

    @Test
    fun ownerIssuedCapabilitiesDoNotGainPreCutoverBusinessAuthority() {
        assertFalse(
            LegacyPreCutoverTutorInteractionSessionDatabasePort::class.java.isAssignableFrom(
                TrustedTutorSessionDatabaseCapability::class.java,
            ),
        )
        assertFalse(
            LegacyPreCutoverMistakeOrganizationBusinessPort::class.java.isAssignableFrom(
                TrustedOrganizationWorkDatabaseCapability::class.java,
            ),
        )
        assertFalse(
            LegacyPreCutoverOrganizationReauthorizationPort::class.java.isAssignableFrom(
                TrustedOrganizationWorkDatabaseCapability::class.java,
            ),
        )
        assertFalse(
            LegacyPreCutoverMistakeDetailReadPort::class.java.isAssignableFrom(
                TrustedLegacyCutoverMigrationDatabaseCapability::class.java,
            ),
        )
    }

    @Test
    fun productionFactoryCallSitesUseOwnerCapabilitiesAndNeverUseMigrationCompatibility() {
        val root = projectRoot()
        val consumers =
            productionKotlinSources(root)
                .filterNot { source ->
                    source.relativeTo(root).invariantSeparatorsPath in WP_C_CONSUMER_SOURCES
                }.toList()

        val ownerCallViolations =
            listOf(
                "ModelTaskRepositoryFactory.create(" to "modelTasksAndAssetDocuments()",
                "RestrictedModelAssetSourceFactory.create(" to "modelTasksAndAssetDocuments()",
            ).flatMap { (call, requiredOwnerIssue) ->
                consumers
                    .filter { source -> call in source.readText() }
                    .filterNot { source -> requiredOwnerIssue in source.readText() }
                    .map { source ->
                        "${source.relativeTo(root).invariantSeparatorsPath} -> $call"
                    }
            }
        assertTrue(
            "Production factory call bypasses the owner-issued capability: $ownerCallViolations",
            ownerCallViolations.isEmpty(),
        )

        val migrationCallViolations =
            consumers
                .filter { source ->
                    val text = source.readText()
                    MIGRATION_ONLY_FACTORY_CALLS.any(text::contains)
                }.map { source -> source.relativeTo(root).invariantSeparatorsPath }
        assertTrue(
            "Production assembled a migration-only compatibility repository: " +
                migrationCallViolations,
            migrationCallViolations.isEmpty(),
        )
    }

    @Test
    fun ownerSourcesContainNoCrossDatabaseSql() {
        val root = projectRoot()
        val sources =
            listOf(
                "core/database/src/main/kotlin/com/tingyun/smartmistakebook/core/database/" +
                    "StudyDatabase.kt",
                "core/database/src/main/kotlin/com/tingyun/smartmistakebook/core/database/" +
                    "LegacySessionDatabasePort.kt",
            )

        val violations =
            sources.flatMap { relativePath ->
                val text = File(root, relativePath).readText()
                FORBIDDEN_SQL_TOKENS
                    .filter { token -> text.contains(token, ignoreCase = true) }
                    .map { token -> "$relativePath -> $token" }
            }

        assertTrue("Legacy owner introduced cross-database SQL: $violations", violations.isEmpty())
    }

    private fun projectRoot(): File =
        generateSequence(
            File(requireNotNull(System.getProperty("user.dir"))).canonicalFile,
        ) { directory ->
            directory.parentFile
        }.firstOrNull { directory ->
            File(directory, "settings.gradle.kts").isFile
        } ?: error("Could not locate the project root")

    private fun productionKotlinSources(root: File): Sequence<File> =
        root.walkTopDown()
            .onEnter { directory ->
                directory.name !in setOf("build", ".gradle", ".git", ".worktrees")
            }.filter { source ->
                source.isFile &&
                    source.extension == "kt" &&
                    source.invariantSeparatorsPath.contains("/src/main/")
            }

    private fun factoryMethod(
        factory: Class<*>,
        name: String,
    ) = factory.declaredMethods.single { method -> method.name == name }

    companion object {
        private val ISSUED_CAPABILITIES =
            listOf(
                TrustedTutorSessionDatabaseCapability::class.java,
                TrustedModelTaskDatabaseCapability::class.java,
                TrustedCaptureSessionDatabaseCapability::class.java,
                TrustedBatchSessionDatabaseCapability::class.java,
                TrustedOrganizationWorkDatabaseCapability::class.java,
                TrustedLegacyCutoverMigrationDatabaseCapability::class.java,
            )

        private val FORBIDDEN_TYPE_FRAGMENTS =
            setOf(
                "StudyDatabasePort",
                "RoomStudyDatabase",
                "androidx.room",
                "SQLite",
            )

        private val FORBIDDEN_BUSINESS_METHODS =
            setOf(
                "confirmAndCommitProblemDraftFromWorkspace",
                "commitTutorSession",
                "confirmAndCompleteProblemOrganizationWork",
                "confirmProblemOrganization",
                "observeMistakes",
                "observeReviewPlan",
                "saveAssessmentAttempt",
                "saveLearningEvent",
                "saveKnowledge",
                "saveMastery",
                "seedFixture",
            )

        private val FORBIDDEN_SQL_TOKENS =
            setOf(
                "ATTACH DATABASE",
                "DETACH DATABASE",
            )

        private val WP_C_CONSUMER_SOURCES =
            setOf(
                "core/data/src/main/kotlin/com/tingyun/smartmistakebook/core/data/mistake/" +
                    "RoomMistakeDetailRepository.kt",
                "core/data/src/main/kotlin/com/tingyun/smartmistakebook/core/data/mistake/" +
                    "RoomMistakeOrganizationRepository.kt",
                "core/data/src/main/kotlin/com/tingyun/smartmistakebook/core/data/mistake/" +
                    "RoomMistakeOrganizationRepositoryMappings.kt",
                "core/data/src/main/kotlin/com/tingyun/smartmistakebook/core/data/model/" +
                    "AndroidRestrictedModelAssetSource.kt",
                "core/data/src/main/kotlin/com/tingyun/smartmistakebook/core/data/model/" +
                    "RoomModelTaskRepository.kt",
                "core/data/src/main/kotlin/com/tingyun/smartmistakebook/core/data/tutor/" +
                    "RoomTutorInteractionRepository.kt",
            )

        private val MIGRATION_ONLY_FACTORY_CALLS =
            setOf(
                "MistakeDetailRepositoryFactory.createLegacyDuringAuthorityMigration(",
                "MistakeOrganizationRepositoryFactory.createLegacyDuringAuthorityMigration(",
                "TutorInteractionRepositoryFactory.createLegacyPreCutover(",
            )
    }
}
