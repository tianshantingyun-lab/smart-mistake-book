package com.tingyun.smartmistakebook.core.data.authority

import android.content.Context
import com.tingyun.smartmistakebook.core.domain.ConfiguredModelExecutionLease
import kotlin.jvm.JvmSynthetic

/**
 * Package-local entry into the audited authority owner.
 *
 * The concrete holder is a Java shell with a genuinely private constructor. Storage ownership is
 * assembled only inside the existing audited authority runtime source and reaches the holder as
 * one erased, close-only resource.
 */
internal object CurrentGenerationProductionOwnerPortFactory {
    @JvmSynthetic
    internal fun open(
        context: Context,
        generationBinding: GenerationBoundLearningAuthorityRuntime,
        sessionOwner: Any,
        modelExecutionLease: ConfiguredModelExecutionLease,
    ): CurrentGenerationProductionOwnerPorts =
        assembleCurrentGenerationProductionOwnerPorts(
            context = context,
            generationBinding = generationBinding,
            sessionOwnerResource = sessionOwner,
            modelExecutionLease = modelExecutionLease,
        )
}

@JvmSynthetic
internal fun claimCurrentGenerationProductionPublicationBinding(
    ports: CurrentGenerationProductionOwnerPorts,
): Any = CurrentGenerationProductionOwnerPorts.claimPublicationBinding(ports)
