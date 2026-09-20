package com.tingyun.smartmistakebook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RootNavigationPolicyTest {
    @Test
    fun `root destinations select themselves`() {
        listOf(Routes.Review, Routes.Tutor, Routes.Library, Routes.Profile).forEach { route ->
            assertEquals(route, bottomBarRouteFor(route))
        }
    }

    /**
     * 讲题只有"一个页面"：底部「智能体」、拍照讲解落下的会话、错题详情「讲解这道题」（复习
     * 预判与判题复核也走它）、历史重开都停在同一格上。三个入口各自带什么题由导航参数决定
     * （会话 id 或 entryId/problemId/problemRevisionId），底栏始终是「智能体」。
     */
    @Test
    fun `every tutor entry keeps the tutor destination selected`() {
        assertEquals(Routes.Tutor, bottomBarRouteFor(Routes.Tutor))
        assertEquals(Routes.Tutor, bottomBarRouteFor(Routes.CapturedTutorSession))
        assertEquals(Routes.Tutor, bottomBarRouteFor(Routes.MistakeTutor))
        assertEquals(Routes.Tutor, bottomBarRouteFor(Routes.TutorTextConversation))
        assertEquals(Routes.Tutor, bottomBarRouteFor(Routes.TutorHistory))
    }

    /** 拍照流程本身是模态工作流，不进底栏；拍完落到上面的讲题页面。 */
    @Test
    fun `the capture workflow stays out of the bottom bar while it runs`() {
        assertNull(bottomBarRouteFor(Routes.CaptureTutor))
    }

    @Test
    fun `secondary workflows do not acquire the bottom bar`() {
        listOf(
            Routes.ReviewSession,
            Routes.CaptureTutor,
            Routes.CaptureLibrary,
            Routes.SplitReview,
            Routes.BatchImport,
            Routes.LibraryBatchExport,
            Routes.CaptureResume,
            Routes.MistakeDetail,
            Routes.MistakeExport,
            Routes.Capability,
            Routes.LearningMastery,
            Routes.Privacy,
            Routes.Reminder,
            Routes.Storage,
            null,
        ).forEach { route -> assertNull(bottomBarRouteFor(route)) }
    }
}
