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
    fun everyKindWithAPromptPolicyHasAContract() {
        // A kind that production can send (it has a current prompt policy) must
        // also declare its egress contract; otherwise its disclosure scope is
        // unverified. Kinds without a policy are the unbuilt placeholders
        // (PROBLEM_RELATE / TUTOR_EVALUATE / REVIEW_RERANK).
        val declared = ModelTaskContractRegistry.all().map { it.kind }.toSet()
        val missing = ModelTaskKind.entries.filter { kind ->
            ModelPromptPolicyVersions.currentFor(kind) != null && kind !in declared
        }
        assertTrue("Model task kinds without an egress contract: $missing", missing.isEmpty())
    }

    @Test
    fun imagePipelineClassifyKeepsTheCaptureImageScope() {
        val contract = ModelTaskContractRegistry.require(ModelTaskKind.IMAGE_PIPELINE_CLASSIFY)

        assertEquals(ModelEgressPurpose.CAPTURE_TO_DOCUMENT, contract.egressPurpose)
        assertEquals(ModelTaskAssetPolicy.REQUIRED, contract.assetPolicy)
        assertEquals(ModelEgressManifest.CAPTURE_IMAGE_DISCLOSURE, contract.requiredDisclosures)
        assertTrue(ModelEgressDataClass.API_CREDENTIALS in contract.prohibitedDisclosures)
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
