package com.tingyun.smartmistakebook.core.data.production

import com.tingyun.smartmistakebook.core.data.authority.CurrentGenerationProductionOwnerPorts
import com.tingyun.smartmistakebook.core.data.authority.claimCurrentGenerationProductionPublicationBinding

/**
 * Consumes the one owner-issued current-generation port aggregate and publishes every inventory
 * slot under one shared reverse-close owner. No generation value or terminal proof is accepted.
 */
internal fun assembleCompleteProductionCapabilities(
    ports: CurrentGenerationProductionOwnerPorts,
): CompleteProductionCapabilityAssembly {
    val authorityBinding =
        try {
            claimCurrentGenerationProductionPublicationBinding(ports)
        } catch (failure: Throwable) {
            ports.closeAfterAssemblyFailure(failure)
            throw failure
        }
    val sharedOwner =
        SharedProductionCapabilityOwner(PRODUCTION_CAPABILITY_SLOT_COUNT) {
            ports.close()
        }

    try {
        val mastery =
            TutorMasteryAndProfileProductionCapability.issue(
                ports.tutorMasteryContext,
                ports.learningMasteryDisplay,
                ports.learningMasteryPrivacy,
            )
        val tutorSession =
            TutorSessionProductionCapability.issue(
                ports.tutorCurrentSessionHost,
            )
        val review =
            ports.dailyReview.let { daily ->
                ReviewPlanningProductionCapability.issue(
                    daily.planning,
                    daily.sessionActions,
                    daily.pacingActions,
                    daily.answerSubmissionPorts,
                    daily.assistanceActions,
                    daily.pacingCommandFactory,
                )
            }
        val leases =
            IssuedProductionCapabilityLeases(
                captureWorkflow = sharedOwner.lease(ports.captureWorkflow),
                batchImport = sharedOwner.lease(ports.batchImport),
                mistakeDetail = sharedOwner.lease(ports.mistakeDetail),
                mistakeOrganization = sharedOwner.lease(ports.mistakeOrganization),
                studentMistakeCatalog = sharedOwner.lease(ports.studentMistakeCatalog),
                tutorSession = sharedOwner.lease(tutorSession),
                tutorConversationLobby = sharedOwner.lease(ports.tutorConversationLobby),
                tutorMasteryAndProfile = sharedOwner.lease(mastery),
                tutorTeachingReference = sharedOwner.lease(ports.tutorTeachingReference),
                modelTaskQueue = sharedOwner.lease(ports.modelTaskQueue),
                modelAssetDocuments = sharedOwner.lease(ports.modelAssetDocuments),
                reviewPlanning = sharedOwner.lease(review),
                workManagerCoordination = sharedOwner.lease(ports.workManagerCoordination),
                terminalCutoverGate =
                    sharedOwner.lease(ProductionTerminalCutoverCapability.issue()),
            )
        CurrentGenerationProductionPublicationHandoff(
            leases,
            authorityBinding,
        ).use { handoff ->
            ProductionCapabilityPublicationRegistry
                .issueCurrentGeneration(handoff)
                .use { ownerClaim ->
                    return CompleteProductionCapabilityAssembly
                        .fromCurrentGenerationClaim(ownerClaim)
                }
        }
    } catch (failure: Throwable) {
        sharedOwner.closeUnissuedAfterFailure(failure)
        throw failure
    }
}

private fun AutoCloseable.closeAfterAssemblyFailure(owner: Throwable) {
    try {
        close()
    } catch (closeFailure: Throwable) {
        owner.addSuppressed(closeFailure)
    }
}
