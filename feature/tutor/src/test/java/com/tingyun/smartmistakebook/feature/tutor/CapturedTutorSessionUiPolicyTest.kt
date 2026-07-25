package com.tingyun.smartmistakebook.feature.tutor

import com.tingyun.smartmistakebook.core.domain.TutorSessionDisposition
import org.junit.Assert.assertEquals
import org.junit.Test

class CapturedTutorSessionUiPolicyTest {
    @Test
    fun temporarySessionNeverClaimsThatTeachingIsReady() {
        assertEquals(
            "临时题目 · 讲完后再决定是否存入",
            tutorSessionStatusLine(isSaved = false),
        )
        assertEquals(
            "已存入错题本",
            tutorSessionStatusLine(isSaved = true),
        )
        assertEquals(
            "本次讲题已结束 · 未存入错题本",
            tutorSessionStatusLine(TutorSessionDisposition.ENDED_WITHOUT_SAVE),
        )
    }

    @Test
    fun saveActionStaysOptionalAndReportsDurableStates() {
        assertEquals(
            "存入错题本",
            tutorSessionSaveLabel(isSaved = false, saveInProgress = false, saveFailed = false),
        )
        assertEquals(
            "保存中",
            tutorSessionSaveLabel(isSaved = false, saveInProgress = true, saveFailed = false),
        )
        assertEquals(
            "重试保存",
            tutorSessionSaveLabel(isSaved = false, saveInProgress = false, saveFailed = true),
        )
        assertEquals(
            "已存入",
            tutorSessionSaveLabel(isSaved = true, saveInProgress = false, saveFailed = false),
        )
    }
}
