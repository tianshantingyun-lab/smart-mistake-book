package com.tingyun.smartmistakebook.core.domain

import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfiguredModelExecutionContractTest {
    @Test
    fun portExposesOnlyExecutionAndPerCallRestrictedAssets() {
        val methods =
            ConfiguredModelExecutionPort::class.java.methods
                .filter { method -> Modifier.isPublic(method.modifiers) }
        assertEquals(
            setOf("capabilities", "execute"),
            methods.mapTo(linkedSetOf()) { method -> method.name },
        )

        val surface = methods.joinToString("\n") { method -> method.toGenericString() }
        assertTrue("RestrictedModelAssetSource" in surface)
        listOf(
            ".database.",
            "RoomDatabase",
            "ModelTaskRepository",
            "ModelConfigurationStore",
            "TutorLearningMemory",
            "LearnerMastery",
            "StudentMistake",
        ).forEach { forbidden ->
            assertFalse("Configured execution port exposes $forbidden", forbidden in surface)
        }
    }

    @Test
    fun leaseHasNoAuthorityGetterOrReplayParameter() {
        val methods = ConfiguredModelExecutionLease::class.java.methods
        assertEquals(
            setOf("claimForModelTaskOwner", "close"),
            methods.mapTo(linkedSetOf()) { method -> method.name },
        )
        assertTrue(
            methods.single { method -> method.name == "claimForModelTaskOwner" }
                .parameterTypes
                .isEmpty(),
        )
    }
}
