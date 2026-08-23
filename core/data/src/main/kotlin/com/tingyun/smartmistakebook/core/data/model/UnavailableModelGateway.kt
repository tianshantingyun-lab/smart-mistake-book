package com.tingyun.smartmistakebook.core.data.model

import com.tingyun.smartmistakebook.core.model.ModelExecutionLocation
import com.tingyun.smartmistakebook.core.model.ModelFailureCode
import com.tingyun.smartmistakebook.core.model.ModelGateway
import com.tingyun.smartmistakebook.core.model.ModelGatewayEvent
import com.tingyun.smartmistakebook.core.model.ModelGatewayExecution
import com.tingyun.smartmistakebook.core.model.ModelTaskFailure
import com.tingyun.smartmistakebook.core.model.ProviderCapabilitySnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Production fail-closed gateway used when the student has not configured any
 * model provider. Every task fails with a retryable MODEL_NOT_CONFIGURED.
 */
class UnavailableModelGateway : ModelGateway {
    override suspend fun capabilities() = CAPABILITIES

    override fun execute(execution: ModelGatewayExecution): Flow<ModelGatewayEvent> = flow {
        emit(
            ModelGatewayEvent.Failed(
                ModelTaskFailure(
                    code = ModelFailureCode.MODEL_NOT_CONFIGURED,
                    message = "配置可用的模型后会从这里继续",
                    retryable = true,
                ),
            ),
        )
    }

    private companion object {
        val CAPABILITIES = ProviderCapabilitySnapshot(
            providerId = "unconfigured",
            providerDisplayName = "尚未配置模型",
            modelId = "unconfigured",
            supportedTasks = emptySet(),
            supportsImageInput = false,
            supportsStructuredOutput = false,
            supportsStreaming = false,
            executionLocation = ModelExecutionLocation.UNAVAILABLE,
            providerConfigurationVersion = "unconfigured-v1",
        )
    }
}
