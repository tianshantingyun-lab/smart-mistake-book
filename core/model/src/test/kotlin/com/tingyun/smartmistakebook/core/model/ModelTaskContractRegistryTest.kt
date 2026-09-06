package com.tingyun.smartmistakebook.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelTaskContractRegistryTest {
    @Test
    fun everyImplementedContractUsesTheCurrentPromptPolicy() {
        ModelTaskContractRegistry.all().forEach { contract ->
            assertEquals(
                ModelPromptPolicyVersions.currentFor(contract.kind),
                contract.promptPolicyVersion,
            )
        }
    }

    @Test
    fun tutorLobbyIsTextOnlyAndNeverDisclosesImages() {
        val contract = ModelTaskContractRegistry.require(ModelTaskKind.TUTOR_LOBBY)

        assertEquals(ModelTaskAssetPolicy.FORBIDDEN, contract.assetPolicy)
        assertEquals(ModelEgressPurpose.TUTORING, contract.egressPurpose)
        assertFalse(
            ModelEgressDataClass.SANITIZED_IMAGE_BYTES in contract.requiredDisclosures,
        )
    }

    @Test
    fun captureContractsRequireExactImageScope() {
        listOf(ModelTaskKind.CAPTURE_ASSESS, ModelTaskKind.CAPTURE_PARSE).forEach { kind ->
            val contract = ModelTaskContractRegistry.require(kind)

            assertEquals(ModelTaskAssetPolicy.REQUIRED, contract.assetPolicy)
            assertEquals(
                ModelEgressManifest.CAPTURE_IMAGE_DISCLOSURE,
                contract.requiredDisclosures,
            )
            assertTrue(ModelEgressDataClass.API_CREDENTIALS in contract.prohibitedDisclosures)
        }
    }

    @Test
    fun dispatchBudgetIsSharedAndNeverResetByRequestId() {
        ModelTaskContractRegistry.all().forEach { contract ->
            assertEquals(ModelTaskRemoteDispatchPolicy.MAX_DISPATCHES, contract.maxProviderDispatches)
            assertEquals(
                ModelTaskRemoteDispatchPolicy.MAX_DISPATCHES,
                contract.maxProviderDispatches,
            )
        }
    }
}
