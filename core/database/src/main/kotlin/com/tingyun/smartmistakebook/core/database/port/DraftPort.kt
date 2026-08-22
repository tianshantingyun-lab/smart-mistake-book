package com.tingyun.smartmistakebook.core.database.port

import com.tingyun.smartmistakebook.core.database.ConfirmedProblemOrganizationRecord
import kotlinx.coroutines.flow.Flow

/**
 * Read-only port for problem draft operations.
 */
interface DraftReadPort {
    fun observePendingProblemDraftCount(): Flow<Int>
}

/**
 * Port for confirmed problem organization.
 */
interface OrganizationReadPort {
    fun observeConfirmedProblemOrganization(
        problemId: String,
        problemRevisionId: String,
    ): Flow<ConfirmedProblemOrganizationRecord>
}
