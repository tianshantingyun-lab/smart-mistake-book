package com.tingyun.smartmistakebook.core.data.production

import com.tingyun.smartmistakebook.core.data.authority.ProductionAdapter
import com.tingyun.smartmistakebook.core.data.authority.ProductionAdapterAvailabilityPort
import com.tingyun.smartmistakebook.core.data.authority.ProductionAdapterManifest

/**
 * Audited status of each slot in the atomic production publication.
 *
 * `OWNER_SOURCE_READY` means a narrow, non-legacy-business capability can already be assembled
 * after current-generation binding. It does not mean the whole snapshot may be published.
 */
internal enum class ProductionOwnerPortStatus {
    OWNER_SOURCE_READY,
    DEPENDENCY_BLOCKED,
    COMPOSITE_INCOMPLETE,
    OWNER_API_MISSING,
    TERMINAL_HANDOFF_MISSING,
}

internal data class ProductionOwnerPortMatrixEntry(
    val adapter: ProductionAdapter,
    val status: ProductionOwnerPortStatus,
    val narrowSource: String,
    val implementationFiles: Set<String>,
    val blockedBy: Set<ProductionAdapter> = emptySet(),
    val missingRequirement: String? = null,
) {
    init {
        require(narrowSource.isNotBlank()) { "Production owner-port source must be named" }
        require(implementationFiles.isNotEmpty()) {
            "Production owner-port source files must be recorded"
        }
        require(
            status == ProductionOwnerPortStatus.OWNER_SOURCE_READY ||
                !missingRequirement.isNullOrBlank(),
        ) {
            "Unavailable production owner-port entries must name the missing requirement"
        }
    }
}

/**
 * Exact inventory used to keep the production manifest honest.
 *
 * The paths are architectural evidence, not runtime reflection inputs. No entry may name a broad
 * legacy business repository as an available source.
 */
internal object ProductionCapabilityOwnerPortMatrix {
    val entries: List<ProductionOwnerPortMatrixEntry> =
        listOf(
            ready(
                ProductionAdapter.CAPTURE_WORKFLOW,
                "LocalLearningAuthorityRuntime.productionCaptureWorkflowRepository",
                "core/data/src/main/kotlin/com/tingyun/smartmistakebook/core/data/authority/" +
                    "LocalLearningAuthorityRuntime.kt",
                "core/data/src/main/kotlin/com/tingyun/smartmistakebook/core/data/capture/" +
                    "ProductionCaptureWorkflowRepository.kt",
                "core/data/src/main/kotlin/com/tingyun/smartmistakebook/core/data/session/" +
                    "LegacyRoomCaptureSessionAdapter.kt",
            ),
            ready(
                ProductionAdapter.BATCH_IMPORT,
                "LocalLearningAuthorityRuntime.productionBatchImportRepository",
                "core/data/src/main/kotlin/com/tingyun/smartmistakebook/core/data/" +
                    "capture/ProductionBatchImportRepository.kt",
                "core/data/src/main/kotlin/com/tingyun/smartmistakebook/core/data/authority/" +
                    "LocalLearningAuthorityRuntime.kt",
            ),
            ready(
                ProductionAdapter.MISTAKE_DETAIL,
                "LocalLearningAuthorityRuntime.mistakeDetailRepository",
                "core/data/src/main/kotlin/com/tingyun/smartmistakebook/core/data/authority/" +
                    "LocalLearningAuthorityRuntime.kt",
                "core/data/src/main/kotlin/com/tingyun/smartmistakebook/core/data/mistake/" +
                    "StudentMistakeStoreMistakeDetailRepository.kt",
            ),
            ready(
                ProductionAdapter.MISTAKE_ORGANIZATION,
                "CurrentGenerationMistakeCapabilityOwner.mistakeOrganization",
                "core/data/src/main/kotlin/com/tingyun/smartmistakebook/core/data/authority/" +
                    "LocalLearningAuthorityRuntime.kt",
                "core/data/src/main/kotlin/com/tingyun/smartmistakebook/core/data/mistake/" +
                    "ProductionStudentProblemOrganizationOwner.kt",
                "core/data/src/main/kotlin/com/tingyun/smartmistakebook/core/data/mistake/" +
                    "StudentAuthoritativeMistakeOrganizationRepository.kt",
            ),
            ready(
                ProductionAdapter.STUDENT_MISTAKE_CATALOG,
                "CurrentGenerationMistakeCapabilityOwner.studentMistakeCatalog",
                "core/data/src/main/kotlin/com/tingyun/smartmistakebook/core/data/mistake/" +
                    "StudentMistakeLibraryCatalogRepository.kt",
                "core/data/src/main/kotlin/com/tingyun/smartmistakebook/core/data/authority/" +
                    "LocalLearningAuthorityRuntime.kt",
            ),
            ready(
                ProductionAdapter.TUTOR_SESSION,
                "LocalLearningAuthorityRuntime current session and open-response hosts",
                "core/data/src/main/kotlin/com/tingyun/smartmistakebook/core/data/session/" +
                    "CurrentTutorSessionProductionOwner.kt",
                "core/data/src/main/kotlin/com/tingyun/smartmistakebook/core/data/session/" +
                    "CurrentTutorSessionHostCoordinator.kt",
                "core/data/src/main/kotlin/com/tingyun/smartmistakebook/core/data/session/" +
                    "ProductionCurrentTutorAuthorities.kt",
                "core/data/src/main/kotlin/com/tingyun/smartmistakebook/core/data/authority/" +
                    "LocalLearningAuthorityRuntime.kt",
                "core/data/src/main/kotlin/com/tingyun/smartmistakebook/core/data/openresponse/" +
                    "CoreDataTutorOpenResponseLearningHostAdapter.kt",
            ),
            ready(
                ProductionAdapter.TUTOR_LEARNING_MEMORY,
                "LocalLearningAuthorityRuntime.tutorConversationLobby",
                "core/data/src/main/kotlin/com/tingyun/smartmistakebook/core/data/authority/" +
                    "LocalLearningAuthorityRuntime.kt",
                "core/data/src/main/kotlin/com/tingyun/smartmistakebook/core/data/session/" +
                    "TutorConversationLobbyCoordinator.kt",
            ),
            ready(
                ProductionAdapter.TUTOR_MASTERY_CONTEXT,
                "LocalLearningAuthorityRuntime mastery and profile projections",
                "core/data/src/main/kotlin/com/tingyun/smartmistakebook/core/data/authority/" +
                    "LocalLearningAuthorityRuntime.kt",
                "core/data/src/main/kotlin/com/tingyun/smartmistakebook/core/data/mastery/" +
                    "LocalLearningMasteryDisplayRepository.kt",
                "core/data/src/main/kotlin/com/tingyun/smartmistakebook/core/data/tutor/" +
                    "LocalTutorMasteryContextRepository.kt",
            ),
            ready(
                ProductionAdapter.TUTOR_TEACHING_REFERENCE,
                "LocalLearningAuthorityRuntime.tutorTeachingReferenceRepository",
                "core/data/src/main/kotlin/com/tingyun/smartmistakebook/core/data/authority/" +
                    "LocalLearningAuthorityRuntime.kt",
            ),
            ready(
                ProductionAdapter.MODEL_TASK_QUEUE,
                "CurrentGenerationProductionOwnerPorts.modelTaskQueue",
                "core/domain/src/main/kotlin/com/tingyun/smartmistakebook/core/domain/" +
                    "ConfiguredModelExecution.kt",
                "core/model-provider/src/main/kotlin/com/tingyun/smartmistakebook/core/model/" +
                    "provider/ConfiguredModelExecutionLeaseFactory.kt",
                "core/data/src/main/kotlin/com/tingyun/smartmistakebook/core/data/model/" +
                    "ConfiguredModelExecutionGateway.kt",
                "core/data/src/main/java/com/tingyun/smartmistakebook/core/data/authority/" +
                    "ProductionModelTaskQueueOwner.java",
                "core/data/src/main/kotlin/com/tingyun/smartmistakebook/core/data/authority/" +
                    "LocalLearningAuthorityRuntime.kt",
            ),
            ready(
                ProductionAdapter.MODEL_ASSET_DOCUMENTS,
                "RestrictedModelAssetSourceFactory",
                "core/data/src/main/kotlin/com/tingyun/smartmistakebook/core/data/model/" +
                    "AndroidRestrictedModelAssetSource.kt",
                "core/database/src/main/kotlin/com/tingyun/smartmistakebook/core/database/" +
                    "LegacySessionDatabasePort.kt",
            ),
            ready(
                ProductionAdapter.REVIEW_PLANNING,
                "LocalLearningAuthorityRuntime.productionDailyReviewPorts",
                "core/data/src/main/java/com/tingyun/smartmistakebook/core/data/authority/" +
                    "StudentMistakeOwnerAccess.java",
                "core/data/src/main/kotlin/com/tingyun/smartmistakebook/core/data/review/" +
                    "ProductionDailyReviewPorts.kt",
                "core/data/src/main/kotlin/com/tingyun/smartmistakebook/core/data/review/" +
                    "StudentTrustedDailyReviewAnswerAdapter.kt",
            ),
            ready(
                ProductionAdapter.WORK_MANAGER_COORDINATION,
                "CurrentGenerationMistakeCapabilityOwner.workManagerCoordination",
                "core/data/src/main/kotlin/com/tingyun/smartmistakebook/core/data/authority/" +
                    "LocalLearningAuthorityRuntime.kt",
                "core/data/src/main/java/com/tingyun/smartmistakebook/core/data/production/" +
                    "ProductionWorkManagerCoordination.java",
                "core/data/src/main/kotlin/com/tingyun/smartmistakebook/core/data/production/" +
                    "ProductionProblemOrganizationScheduling.kt",
                "core/data/src/main/kotlin/com/tingyun/smartmistakebook/core/data/production/" +
                    "ProductionProblemOrganizationExecution.kt",
                "core/data/src/main/kotlin/com/tingyun/smartmistakebook/core/data/session/" +
                    "LegacyProblemOrganizationWorkSessionAdapter.kt",
                "core/data/src/main/kotlin/com/tingyun/smartmistakebook/core/data/mistake/" +
                    "ProblemOrganizationWorkProcessor.kt",
                "app/src/main/kotlin/com/tingyun/smartmistakebook/" +
                    "ProblemOrganizationWorker.kt",
                "app/src/main/kotlin/com/tingyun/smartmistakebook/" +
                    "ProblemOrganizationWorkSchedulingCoordinator.kt",
            ),
            ready(
                ProductionAdapter.TERMINAL_CUTOVER_GATE,
                "CanonicalProductionAuthorityPublicationPreparer",
                "core/data/src/main/kotlin/com/tingyun/smartmistakebook/core/data/authority/" +
                    "CanonicalProductionAuthorityPublicationPreparer.kt",
                "core/data/src/main/kotlin/com/tingyun/smartmistakebook/core/data/authority/" +
                    "CurrentGenerationLearningAuthorityBinding.kt",
                "core/data/src/main/kotlin/com/tingyun/smartmistakebook/core/database/" +
                    "ProductionLegacyBarrierHandoff.kt",
            ),
        ).also { matrix ->
            check(matrix.map(ProductionOwnerPortMatrixEntry::adapter).toSet() ==
                ProductionAdapter.entries.toSet()) {
                "Production owner-port matrix must cover every publication slot exactly once"
            }
            check(matrix.size == ProductionAdapter.entries.size) {
                "Production owner-port matrix contains a duplicate slot"
            }
        }

    val ownerSourceReadyAdapters: Set<ProductionAdapter> =
        entries
            .filter { entry ->
                entry.status == ProductionOwnerPortStatus.OWNER_SOURCE_READY
            }.mapTo(linkedSetOf(), ProductionOwnerPortMatrixEntry::adapter)

    fun manifest(): ProductionAdapterManifest =
        ProductionAdapterManifest.fromAvailable(ownerSourceReadyAdapters)

    fun entry(adapter: ProductionAdapter): ProductionOwnerPortMatrixEntry =
        entries.single { entry -> entry.adapter == adapter }
}

/** Application-facing view of the audited, uniquely inventoried owner-port matrix. */
object AuditedProductionAdapterAvailability : ProductionAdapterAvailabilityPort {
    override fun readManifest(): ProductionAdapterManifest =
        ProductionCapabilityOwnerPortMatrix.manifest()
}

private fun ready(
    adapter: ProductionAdapter,
    source: String,
    vararg files: String,
): ProductionOwnerPortMatrixEntry =
    ProductionOwnerPortMatrixEntry(
        adapter = adapter,
        status = ProductionOwnerPortStatus.OWNER_SOURCE_READY,
        narrowSource = source,
        implementationFiles = files.toSet(),
    )

private fun missingOwner(
    adapter: ProductionAdapter,
    source: String,
    file: String,
    requirement: String,
): ProductionOwnerPortMatrixEntry =
    ProductionOwnerPortMatrixEntry(
        adapter = adapter,
        status = ProductionOwnerPortStatus.OWNER_API_MISSING,
        narrowSource = source,
        implementationFiles = setOf(file),
        missingRequirement = requirement,
    )
