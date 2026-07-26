package com.tingyun.smartmistakebook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RootNavigationPolicyTest {
    @Test
    fun `root destinations put tutor first and preserve module order`() {
        assertEquals(
            listOf(Routes.Tutor, Routes.Review, Routes.Library, Routes.Profile),
            rootDestinationRoutes,
        )
    }

    @Test
    fun `cold start opens tutor`() {
        assertEquals(Routes.Tutor, ROOT_START_DESTINATION)
    }

    @Test
    fun `root destinations select themselves`() {
        rootDestinationRoutes.forEach { route ->
            assertEquals(route, bottomBarRouteFor(route))
        }
    }

    @Test
    fun `both exact tutor sessions keep the tutor destination selected`() {
        assertEquals(Routes.Tutor, bottomBarRouteFor(Routes.CapturedTutorSession))
        assertEquals(Routes.Tutor, bottomBarRouteFor(Routes.MistakeTutor))
    }

    @Test
    fun `secondary workflows do not acquire the bottom bar`() {
        listOf(
            Routes.ReviewSession,
            Routes.CaptureTutor,
            Routes.CaptureLibrary,
            Routes.CaptureInbox,
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
