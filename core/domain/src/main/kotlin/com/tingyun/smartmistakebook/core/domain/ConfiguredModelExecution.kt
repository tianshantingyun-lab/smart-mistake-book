package com.tingyun.smartmistakebook.core.domain

import com.tingyun.smartmistakebook.core.model.ModelGatewayEvent
import com.tingyun.smartmistakebook.core.model.ModelGatewayExecution
import com.tingyun.smartmistakebook.core.model.ProviderCapabilitySnapshot
import kotlinx.coroutines.flow.Flow

/**
 * Provider execution without storage ownership.
 *
 * The provider receives the restricted asset reader only for one authorized execution. It cannot
 * retain or query the model-task database, learner memory, or the mutable configuration store.
 */
interface ConfiguredModelExecutionPort {
    suspend fun capabilities(): ProviderCapabilitySnapshot

    fun execute(
        execution: ModelGatewayExecution,
        assets: RestrictedModelAssetSource,
    ): Flow<ModelGatewayEvent>
}

/**
 * One process-local handoff from the configured provider to one production model-task owner.
 *
 * Claim is single-use. Closing before claim rejects a late handoff; closing after claim revokes the
 * issued port. Implementations must make claim and close safe under concurrent calls.
 */
interface ConfiguredModelExecutionLease : AutoCloseable {
    fun claimForModelTaskOwner(): ConfiguredModelExecutionPort
}
