package com.tingyun.smartmistakebook

import com.tingyun.smartmistakebook.core.model.TutorCurrentSessionVisualIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
        (secondaryRoutes + listOf(null)).forEach { route ->
            assertNull(bottomBarRouteFor(route))
        }
    }

    @Test
    fun `secondary workflows declare a bottom-bar recovery destination`() {
        assertEquals(secondaryRoutes.size, recoveryEdges.size)
        assertEquals(
            secondaryRoutes.toSet(),
            recoveryEdges.mapTo(linkedSetOf()) { it.workflow },
        )
        recoveryEdges.forEach { edge ->
            assertTrue("${edge.workflow} must not recover to itself", edge.workflow != edge.recovery)
            assertTrue("${edge.workflow} must map to a root destination", edge.recovery in rootDestinationRoutes)
            assertNull(bottomBarRouteFor(edge.workflow))
        }
    }

    @Test
    fun `capture visual intent is carried by the route and defaults fail closed`() {
        assertEquals(
            "capture/tutor?visualIntent=USER_EXPLICIT",
            Routes.captureTutor(TutorCurrentSessionVisualIntent.USER_EXPLICIT),
        )
        assertEquals(
            "capture/tutor?visualIntent=NONE",
            Routes.captureTutor(),
        )
        assertEquals(
            TutorCurrentSessionVisualIntent.USER_EXPLICIT,
            Routes.tutorVisualIntent("USER_EXPLICIT"),
        )
        listOf(null, "", "MODEL_CREATED", "user_explicit").forEach { invalid ->
            assertEquals(
                TutorCurrentSessionVisualIntent.NONE,
                Routes.tutorVisualIntent(invalid),
            )
        }
    }

    private data class RecoveryEdge(
        val workflow: String,
        val recovery: String,
    )

    private val recoveryEdges =
        listOf(
            RecoveryEdge(Routes.ReviewSession, Routes.Review),
            RecoveryEdge(Routes.CaptureTutor, Routes.Tutor),
            RecoveryEdge(Routes.CaptureLibrary, Routes.Library),
            RecoveryEdge(Routes.CaptureInbox, Routes.Library),
            RecoveryEdge(Routes.BatchImport, Routes.Library),
            RecoveryEdge(Routes.LibraryBatchExport, Routes.Library),
            RecoveryEdge(Routes.CaptureResume, Routes.Tutor),
            RecoveryEdge(Routes.MistakeDetail, Routes.Library),
            RecoveryEdge(Routes.MistakeExport, Routes.Library),
            RecoveryEdge(Routes.Capability, Routes.Profile),
            RecoveryEdge(Routes.LearningMastery, Routes.Profile),
            RecoveryEdge(Routes.Privacy, Routes.Profile),
            RecoveryEdge(Routes.Reminder, Routes.Profile),
            RecoveryEdge(Routes.Storage, Routes.Profile),
        )

    private val secondaryRoutes =
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
        )
}
