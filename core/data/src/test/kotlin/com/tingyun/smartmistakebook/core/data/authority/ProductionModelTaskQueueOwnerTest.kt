package com.tingyun.smartmistakebook.core.data.authority

import com.tingyun.smartmistakebook.core.domain.ConfiguredModelExecutionLease
import com.tingyun.smartmistakebook.core.domain.ConfiguredModelExecutionPort
import com.tingyun.smartmistakebook.core.domain.ModelTaskRepository
import com.tingyun.smartmistakebook.core.domain.RestrictedModelAsset
import com.tingyun.smartmistakebook.core.domain.RestrictedModelAssetSource
import com.tingyun.smartmistakebook.core.model.ModelGatewayExecution
import com.tingyun.smartmistakebook.core.model.ModelTaskRequest
import com.tingyun.smartmistakebook.core.model.ModelTaskSnapshot
import com.tingyun.smartmistakebook.core.model.ProviderCapabilitySnapshot
import java.lang.reflect.Modifier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductionModelTaskQueueOwnerTest {
    @Test
    fun ownerPublishesOnlyNarrowPortsAndRevokesItsLeaseOnce() {
        val queue = InertModelTaskRepository
        val assets = DeniedModelAssetSource
        val lease = RecordingExecutionLease()
        val owner = ProductionModelTaskQueueOwner.issue(queue, assets, lease)

        assertSame(queue, owner.modelTaskQueue)
        assertSame(assets, owner.modelAssetDocuments)

        owner.close()
        owner.close()

        assertEquals(1, lease.closeCalls)
    }

    @Test
    fun reflectionSurfaceHasNoStorageOrConfigurationAuthority() {
        val type = ProductionModelTaskQueueOwner::class.java
        assertTrue(type.declaredConstructors.all { constructor ->
            Modifier.isPrivate(constructor.modifiers)
        })
        val publicSurface =
            type.declaredMethods
                .filter { method -> Modifier.isPublic(method.modifiers) }
                .joinToString("\n") { method -> method.toGenericString() }
        listOf(
            ".database.",
            "RoomDatabase",
            "Dao",
            "ModelConfiguration",
            "LearnerMastery",
            "StudentMistake",
        ).forEach { forbidden ->
            assertFalse("Model queue owner exposes $forbidden", forbidden in publicSurface)
        }
        assertTrue("ModelTaskRepository" in publicSurface)
        assertTrue("RestrictedModelAssetSource" in publicSurface)
    }
}

private class RecordingExecutionLease : ConfiguredModelExecutionLease {
    var closeCalls = 0
        private set

    override fun claimForModelTaskOwner(): ConfiguredModelExecutionPort =
        error("Already claimed by the assembly under test")

    override fun close() {
        closeCalls += 1
    }
}

private object InertModelTaskRepository : ModelTaskRepository {
    override suspend fun capabilities(): ProviderCapabilitySnapshot = error("Not used")

    override fun observe(requestId: String): Flow<ModelTaskSnapshot?> = flowOf(null)

    override fun execute(request: ModelTaskRequest): Flow<ModelTaskSnapshot> = emptyFlow()
}

private object DeniedModelAssetSource : RestrictedModelAssetSource {
    override suspend fun open(
        execution: ModelGatewayExecution,
        assetId: String,
    ): RestrictedModelAsset = error("Not used")
}
