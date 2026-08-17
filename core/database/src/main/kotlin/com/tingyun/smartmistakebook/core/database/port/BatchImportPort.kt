package com.tingyun.smartmistakebook.core.database.port

import com.tingyun.smartmistakebook.core.database.BatchImportJobRecord
import kotlinx.coroutines.flow.Flow

/**
 * Read-only port for batch import operations.
 */
interface BatchImportReadPort {
    fun observeBatchImportJobs(): Flow<List<BatchImportJobRecord>>
}
