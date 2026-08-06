package com.tingyun.smartmistakebook.feature.tutor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TutorSaveActionPolicyTest {
    @Test
    fun genericTutorQuestionShowsSaveActionByDefaultPolicy() {
        assertTrue(shouldShowTutorSaveAction(showSaveAction = true))
    }

    @Test
    fun nonSaveableFixtureHidesSaveAction() {
        assertFalse(shouldShowTutorSaveAction(showSaveAction = false))
    }
}
