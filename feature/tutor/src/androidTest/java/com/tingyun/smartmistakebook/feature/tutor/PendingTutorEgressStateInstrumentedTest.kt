package com.tingyun.smartmistakebook.feature.tutor

import androidx.compose.runtime.saveable.SaverScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PendingTutorEgressStateInstrumentedTest {
    @Test
    fun bundleSaverRoundTripsChoiceIdentityAndAcceptsLegacyMissingKeys() {
        val pending = PendingTutorEgressState(
            PendingTutorEgressAction.NewResponse(
                message = "继续",
                requestedMove = null,
                clearDraftOnPersist = false,
                selectedChoiceId = "choice-b",
                choiceSourceRequestId = "visible-reply-request",
            ),
        )
        val saved = with(pendingTutorEgressStateSaver) {
            requireNotNull(SaverScope { true }.save(pending))
        }
        val restored = requireNotNull(pendingTutorEgressStateSaver.restore(saved)).action
            as PendingTutorEgressAction.NewResponse

        assertEquals("choice-b", restored.selectedChoiceId)
        assertEquals("visible-reply-request", restored.choiceSourceRequestId)
        assertEquals("继续", restored.message)

        saved.remove("selected_choice_id")
        saved.remove("choice_source_request_id")
        val legacyRestored = requireNotNull(pendingTutorEgressStateSaver.restore(saved)).action
            as PendingTutorEgressAction.NewResponse

        assertNull(legacyRestored.selectedChoiceId)
        assertNull(legacyRestored.choiceSourceRequestId)
        assertEquals("继续", legacyRestored.message)
    }
}
