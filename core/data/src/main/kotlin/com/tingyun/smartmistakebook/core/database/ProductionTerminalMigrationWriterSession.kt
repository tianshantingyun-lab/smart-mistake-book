package com.tingyun.smartmistakebook.core.database

import android.content.Context
import com.tingyun.smartmistakebook.core.data.authority.LegacyMigrationWriterSession
import com.tingyun.smartmistakebook.core.data.authority.ProductionTerminalAuthorityCutoverWriter
import com.tingyun.smartmistakebook.core.data.authority.ProductionTerminalAuthorityCutoverWriterCreation
import com.tingyun.smartmistakebook.core.data.authority.ProductionTerminalAuthorityCutoverWriterFactory
import com.tingyun.smartmistakebook.core.data.authority.TerminalAuthorityStageOperation
import com.tingyun.smartmistakebook.core.data.authority.TerminalAuthorityPostImportCapabilityResolution
import com.tingyun.smartmistakebook.core.data.authority.TerminalAuthorityPostImportCapabilityResolver
import com.tingyun.smartmistakebook.core.data.authority.TerminalLearnerMasteryAuthorityMigrator
import com.tingyun.smartmistakebook.core.data.authority.TerminalStudentAuthorityMigrator
import com.tingyun.smartmistakebook.core.data.authority.ThreeAuthorityCutoverStage
import com.tingyun.smartmistakebook.core.data.authority.ThreeAuthorityDatabaseLayout
import com.tingyun.smartmistakebook.core.knowledge.database.HighSchoolKnowledgeCatalog
import com.tingyun.smartmistakebook.core.knowledge.database.KnowledgeCatalogActivationReceipt
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentMistakeOwnerAccess.openStudentTerminalAuthorityAttestationOwner

internal sealed interface ProductionTerminalMigrationWriterSessionCreation {
    data class Ready(
        val session: LegacyMigrationWriterSession,
    ) : ProductionTerminalMigrationWriterSessionCreation

    data class Blocked(
        val missingStages: Set<ThreeAuthorityCutoverStage>,
        val duplicateStages: Set<ThreeAuthorityCutoverStage>,
        val unexpectedStages: Set<ThreeAuthorityCutoverStage>,
    ) : ProductionTerminalMigrationWriterSessionCreation
}

/**
 * Opens the terminal writer only through core:database's hidden exclusive bridge.
 *
 * The returned session is the capability accepted by [LegacyMigrationWriterSession]. It owns the
 * hidden bridge for its whole migration and closes it before the startup gate can recover proofs,
 * create authority fences, or open a separate v45 barrier finalizer.
 */
internal object ProductionTerminalMigrationWriterSessionFactory {
    fun open(
        context: Context,
        layout: ThreeAuthorityDatabaseLayout,
        legacySource: LegacyAuthorityMigrationSourcePort,
        knowledgeCatalog: HighSchoolKnowledgeCatalog,
        knowledgeActivation: KnowledgeCatalogActivationReceipt,
        cutoverGeneration: Long,
        studentMigrator: TerminalStudentAuthorityMigrator,
        masteryMigrator: TerminalLearnerMasteryAuthorityMigrator,
        postImportOperations: Collection<TerminalAuthorityStageOperation>,
        ownedResources: Collection<AutoCloseable> = emptyList(),
        clock: () -> Long = { System.currentTimeMillis().coerceAtLeast(0L) },
    ): ProductionTerminalMigrationWriterSessionCreation {
        val resources = ownedResources.toList()
        val verifiedPostImportOperations =
            when (
                val resolution =
                    TerminalAuthorityPostImportCapabilityResolver.resolve(
                        postImportOperations,
                    )
            ) {
                is TerminalAuthorityPostImportCapabilityResolution.Blocked -> {
                    closeResourcesReverse(resources)
                    return ProductionTerminalMigrationWriterSessionCreation.Blocked(
                        missingStages = resolution.missingStages,
                        duplicateStages = resolution.duplicateStages,
                        unexpectedStages = resolution.unexpectedStages,
                    )
                }

                is TerminalAuthorityPostImportCapabilityResolution.Ready ->
                    resolution.orderedOperations
            }
        val lease =
            try {
                LegacyTerminalCutoverJournalLease.open(context)
            } catch (failure: Throwable) {
                closeResourcesAfterFailure(resources, failure)
                throw failure
            }
        val studentAttestationOwner =
            try {
                openStudentTerminalAuthorityAttestationOwner(
                    context,
                    layout,
                    clock,
                )
            } catch (failure: Throwable) {
                closeAfterOpenFailure(lease, failure)
                closeResourcesAfterFailure(resources, failure)
                throw failure
            }
        val creation =
            try {
                ProductionTerminalAuthorityCutoverWriterFactory.create(
                    layout = layout,
                    legacySource = legacySource,
                    knowledgeCatalog = knowledgeCatalog,
                    knowledgeActivation = knowledgeActivation,
                    journal = lease.journal(),
                    cutoverGeneration = cutoverGeneration,
                    studentMigrator = studentMigrator,
                    studentAttestationOwner = studentAttestationOwner,
                    masteryMigrator = masteryMigrator,
                    postImportOperations = verifiedPostImportOperations,
                    clock = clock,
                )
            } catch (failure: Throwable) {
                closeAfterOpenFailure(studentAttestationOwner, lease, resources, failure)
                throw failure
            }
        return when (creation) {
            is ProductionTerminalAuthorityCutoverWriterCreation.Ready ->
                ProductionTerminalMigrationWriterSessionCreation.Ready(
                    session =
                        BridgeOwnedTerminalMigrationWriterSession(
                            lease = lease,
                            writer = creation.writer,
                            ownedResources = resources,
                        ),
                )

            is ProductionTerminalAuthorityCutoverWriterCreation.Blocked -> {
                closeBlockedCreation(studentAttestationOwner, lease, resources)
                ProductionTerminalMigrationWriterSessionCreation.Blocked(
                    missingStages = creation.missingStages,
                    duplicateStages = creation.duplicateStages,
                    unexpectedStages = creation.unexpectedStages,
                )
            }
        }
    }
}

private class BridgeOwnedTerminalMigrationWriterSession(
    private val lease: LegacyTerminalCutoverJournalLease,
    private val writer: ProductionTerminalAuthorityCutoverWriter,
    private val ownedResources: List<AutoCloseable>,
) : LegacyMigrationWriterSession {
    private val lifecycleLock = Any()
    private var state = MigrationSessionState.OPEN

    override suspend fun migrate() {
        synchronized(lifecycleLock) {
            check(state == MigrationSessionState.OPEN) {
                "Terminal migration session is not open"
            }
            state = MigrationSessionState.MIGRATING
        }
        try {
            writer.migrateAndVerifyTerminalJournal()
            synchronized(lifecycleLock) {
                check(state == MigrationSessionState.MIGRATING)
                state = MigrationSessionState.MIGRATED
            }
        } catch (failure: Throwable) {
            synchronized(lifecycleLock) {
                if (state == MigrationSessionState.MIGRATING) {
                    state = MigrationSessionState.FAILED
                }
            }
            throw failure
        }
    }

    override fun close() {
        synchronized(lifecycleLock) {
            if (state == MigrationSessionState.CLOSED) return
            check(state != MigrationSessionState.MIGRATING) {
                "Terminal migration session cannot close during an active migration"
            }
            state = MigrationSessionState.CLOSED
        }
        closeSessionResources(writer, lease, ownedResources)
    }
}

private enum class MigrationSessionState {
    OPEN,
    MIGRATING,
    MIGRATED,
    FAILED,
    CLOSED,
}

private fun closeAfterOpenFailure(
    lease: LegacyTerminalCutoverJournalLease,
    failure: Throwable,
) {
    try {
        lease.close()
    } catch (closeFailure: Throwable) {
        failure.addSuppressed(closeFailure)
    }
}

private fun closeAfterOpenFailure(
    writer: AutoCloseable,
    lease: LegacyTerminalCutoverJournalLease,
    ownedResources: List<AutoCloseable>,
    failure: Throwable,
) {
    try {
        writer.close()
    } catch (closeFailure: Throwable) {
        failure.addSuppressed(closeFailure)
    }
    closeAfterOpenFailure(lease, failure)
    closeResourcesAfterFailure(ownedResources, failure)
}

private fun closeBlockedCreation(
    writer: AutoCloseable,
    lease: LegacyTerminalCutoverJournalLease,
    ownedResources: List<AutoCloseable>,
) {
    var failure: Throwable? = null
    try {
        writer.close()
    } catch (closeFailure: Throwable) {
        failure = closeFailure
    }
    try {
        lease.close()
    } catch (closeFailure: Throwable) {
        val current = failure
        if (current == null) {
            failure = closeFailure
        } else {
            current.addSuppressed(closeFailure)
        }
    }
    ownedResources.asReversed().forEach { resource ->
        try {
            resource.close()
        } catch (closeFailure: Throwable) {
            val current = failure
            if (current == null) {
                failure = closeFailure
            } else {
                current.addSuppressed(closeFailure)
            }
        }
    }
    failure?.let { throw it }
}

private fun closeSessionResources(
    writer: AutoCloseable,
    lease: LegacyTerminalCutoverJournalLease,
    ownedResources: List<AutoCloseable>,
) {
    closeBlockedCreation(writer, lease, ownedResources)
}

private fun closeResourcesReverse(resources: List<AutoCloseable>) {
    var failure: Throwable? = null
    resources.asReversed().forEach { resource ->
        try {
            resource.close()
        } catch (closeFailure: Throwable) {
            val first = failure
            if (first == null) {
                failure = closeFailure
            } else {
                first.addSuppressed(closeFailure)
            }
        }
    }
    failure?.let { throw it }
}

private fun closeResourcesAfterFailure(
    resources: List<AutoCloseable>,
    ownerFailure: Throwable,
) {
    try {
        closeResourcesReverse(resources)
    } catch (closeFailure: Throwable) {
        ownerFailure.addSuppressed(closeFailure)
    }
}
