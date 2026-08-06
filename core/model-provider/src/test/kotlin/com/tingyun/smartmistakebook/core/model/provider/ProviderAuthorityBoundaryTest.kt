package com.tingyun.smartmistakebook.core.model.provider

import com.tingyun.smartmistakebook.core.domain.ModelConfigurationStore
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderAuthorityBoundaryTest {
    @Test
    fun providerApiCannotReceiveTrustedStorageCapabilities() {
        val exposedTypes =
            listOf(
                ConfiguredModelGatewayFactory::class.java,
                ConfiguredModelExecutionLeaseFactory::class.java,
                ConfiguredModelCapabilityTesterFactory::class.java,
                OpenAiCompatibleModelGateway::class.java,
            ).flatMap { type ->
                type.declaredConstructors.flatMap { it.parameterTypes.asList() } +
                    type.declaredMethods.flatMap { method ->
                        method.parameterTypes.asList() + method.returnType
                    }
            }.map(Class<*>::getName)

        val forbiddenFragments =
            listOf(
                "android.content.Context",
                ".database.",
                "RoomDatabase",
                "StudentMistake",
                "LearnerMastery",
                "KnowledgeCatalog",
                ModelConfigurationStore::class.java.name,
            )
        forbiddenFragments.forEach { forbidden ->
            assertTrue(
                "External provider API exposes trusted capability '$forbidden': $exposedTypes",
                exposedTypes.none { typeName -> forbidden in typeName },
            )
        }
    }
}
