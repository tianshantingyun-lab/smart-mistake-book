package com.tingyun.smartmistakebook.feature.tutor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TutorLearningWriteAuthorityTest {
    @Test
    fun writeRequiresCurrentPermissionAndModeEpochs() {
        val authority = TutorLearningWriteAuthority(
            allowed = true,
            permissionVersion = 7,
        )

        assertTrue(
            authority.allows(
                requestPermissionVersion = 7,
                requestModeVersion = 3,
                currentModeVersion = 3,
            ),
        )
        assertFalse(
            authority.allows(
                requestPermissionVersion = 6,
                requestModeVersion = 3,
                currentModeVersion = 3,
            ),
        )
        assertFalse(
            authority.allows(
                requestPermissionVersion = 7,
                requestModeVersion = 2,
                currentModeVersion = 3,
            ),
        )
    }

    @Test
    fun blockedAuthorityRejectsEvenCurrentRequests() {
        val authority = TutorLearningWriteAuthority(
            allowed = false,
            permissionVersion = 8,
        )

        assertFalse(
            authority.allows(
                requestPermissionVersion = 8,
                requestModeVersion = 4,
                currentModeVersion = 4,
            ),
        )
    }
}
