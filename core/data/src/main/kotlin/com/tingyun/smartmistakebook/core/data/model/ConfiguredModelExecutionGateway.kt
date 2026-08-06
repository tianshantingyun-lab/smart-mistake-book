package com.tingyun.smartmistakebook.core.data.model

import com.tingyun.smartmistakebook.core.domain.ConfiguredModelExecutionPort
import com.tingyun.smartmistakebook.core.domain.ModelGateway
import com.tingyun.smartmistakebook.core.domain.RestrictedModelAssetSource
import com.tingyun.smartmistakebook.core.model.ModelGatewayEvent
import com.tingyun.smartmistakebook.core.model.ModelGatewayExecution
import com.tingyun.smartmistakebook.core.model.ProviderCapabilitySnapshot
import kotlinx.coroutines.flow.Flow

/** Binds provider execution to the current session owner's restricted asset reader. */
internal class ConfiguredModelExecutionGateway(
    private val execution: ConfiguredModelExecutionPort,
    private val assets: RestrictedModelAssetSource,
) : ModelGateway {
    override suspend fun capabilities(): ProviderCapabilitySnapshot = execution.capabilities()

    override fun execute(execution: ModelGatewayExecution): Flow<ModelGatewayEvent> =
        this.execution.execute(execution, assets)
}
