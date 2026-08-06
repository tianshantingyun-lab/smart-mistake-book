package com.tingyun.smartmistakebook.core.model.provider

import com.tingyun.smartmistakebook.core.domain.ConfiguredModelExecutionLease
import com.tingyun.smartmistakebook.core.domain.ConfiguredModelExecutionPort
import com.tingyun.smartmistakebook.core.domain.ModelConfigurationReadCapability
import com.tingyun.smartmistakebook.core.domain.RestrictedModelAsset
import com.tingyun.smartmistakebook.core.domain.RestrictedModelAssetSource
import com.tingyun.smartmistakebook.core.model.ModelExecutionPermit
import com.tingyun.smartmistakebook.core.model.ModelGatewayEvent
import com.tingyun.smartmistakebook.core.model.ModelGatewayExecution
import com.tingyun.smartmistakebook.core.model.ProviderCapabilitySnapshot
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow

/** Issues one configured-provider handoff without receiving any storage capability. */
object ConfiguredModelExecutionLeaseFactory {
    fun create(
        configuration: ModelConfigurationReadCapability,
    ): ConfiguredModelExecutionLease =
        ProviderConfiguredModelExecutionLease(configuration)
}

private class ProviderConfiguredModelExecutionLease(
    configuration: ModelConfigurationReadCapability,
) : ConfiguredModelExecutionLease {
    private val state = AtomicReference(LeaseState.AVAILABLE)
    private val port = RevocableConfiguredModelExecutionPort(configuration, state)

    override fun claimForModelTaskOwner(): ConfiguredModelExecutionPort {
        check(state.compareAndSet(LeaseState.AVAILABLE, LeaseState.CLAIMED)) {
            "Configured model execution lease is closed or already claimed"
        }
        return port
    }

    override fun close() {
        if (state.getAndSet(LeaseState.CLOSED) != LeaseState.CLOSED) {
            port.revoke()
        }
    }
}

private class RevocableConfiguredModelExecutionPort(
    private val configuration: ModelConfigurationReadCapability,
    private val state: AtomicReference<LeaseState>,
) : ConfiguredModelExecutionPort {
    private val activeCollections = ConcurrentHashMap.newKeySet<Job>()

    override suspend fun capabilities(): ProviderCapabilitySnapshot {
        requireClaimed()
        val snapshot =
            OpenAiCompatibleModelGateway(
                configurationStore = configuration,
                assetSource = DeniedCapabilityProbeAssetSource,
            ).capabilities()
        requireClaimed()
        return snapshot
    }

    override fun execute(
        execution: ModelGatewayExecution,
        assets: RestrictedModelAssetSource,
    ): Flow<ModelGatewayEvent> {
        requireClaimed()
        execution.requireExternalPermit()
        val invocation =
            AtomicReference(
                ExecutionInvocation(
                    execution = execution,
                    assets = assets,
                ),
            )
        return flow {
            val current = checkNotNull(invocation.getAndSet(null)) {
                "Configured model execution flow may be collected only once"
            }
            collectCurrent(current, this)
        }
    }

    fun revoke() {
        activeCollections.forEach { collection ->
            collection.cancel(
                CancellationException("Configured model execution lease was closed"),
            )
        }
        activeCollections.clear()
    }

    private fun requireClaimed() {
        check(state.get() == LeaseState.CLAIMED) {
            "Configured model execution port is not current"
        }
    }

    private suspend fun collectCurrent(
        invocation: ExecutionInvocation,
        destination: FlowCollector<ModelGatewayEvent>,
    ) = coroutineScope {
        val collection = currentCoroutineContext()[Job]
            ?: error("Configured model execution requires a cancellable collection")
        check(activeCollections.add(collection)) {
            "Configured model execution collection was registered twice"
        }
        try {
            requireClaimed()
            OpenAiCompatibleModelGateway(
                configurationStore = configuration,
                assetSource = invocation.assets,
            ).execute(invocation.execution).collect { event ->
                requireClaimed()
                destination.emit(event)
            }
        } finally {
            activeCollections.remove(collection)
        }
    }
}

private data class ExecutionInvocation(
    val execution: ModelGatewayExecution,
    val assets: RestrictedModelAssetSource,
)

private object DeniedCapabilityProbeAssetSource : RestrictedModelAssetSource {
    override suspend fun open(
        execution: ModelGatewayExecution,
        assetId: String,
    ): RestrictedModelAsset =
        throw SecurityException("Capability probes cannot read model assets")
}

private fun ModelGatewayExecution.requireExternalPermit() {
    val permittedManifest = (permit as? ModelExecutionPermit.External)?.manifest
        ?: throw SecurityException("Configured provider requires an external execution permit")
    check(request.egressManifest == permittedManifest) {
        "Configured provider execution does not match its egress permit"
    }
}

private enum class LeaseState {
    AVAILABLE,
    CLAIMED,
    CLOSED,
}
