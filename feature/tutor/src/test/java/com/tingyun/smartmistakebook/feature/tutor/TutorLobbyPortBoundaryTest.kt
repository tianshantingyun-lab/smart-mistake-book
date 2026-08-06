package com.tingyun.smartmistakebook.feature.tutor

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TutorLobbyPortBoundaryTest {
    @Test
    fun productionLobbyUsesOnlyTheNarrowConversationPort() {
        val sourceRoot = File(
            projectRoot(),
            "feature/tutor/src/main/java/com/tingyun/smartmistakebook/feature/tutor",
        )
        val controller = File(sourceRoot, "TutorLobbyConversationController.kt").readText()
        val lobbyRoute = File(sourceRoot, "TutorLobbyRoute.kt").readText()
        val entryRoute = File(sourceRoot, "TutorRoute.kt").readText()
        val lobbySource = "$controller\n$lobbyRoute\n$entryRoute"

        listOf(
            "TutorLearningMemoryRepository",
            "learnerScopeId",
            "LOCAL_LEARNER_ID",
            "AllocateTutorTurnCommand",
            "ArchiveTutorConversationCommand",
            "CreateTutorConversationCommand",
            "OpenTutorConversationCommand",
            "TutorTurnReceipt",
            "clientIdempotencyKey",
            "payloadFingerprint",
            "studentMessageFingerprint",
            "directiveFingerprint",
            "prepareEvidenceRequest",
            "finalizeEvidence",
            "RoomDatabase",
            "androidx.room",
        ).forEach { forbidden ->
            assertFalse(
                "Tutor lobby bypassed its narrow lifecycle port with $forbidden",
                forbidden in lobbySource,
            )
        }

        assertTrue("TutorConversationLobbyPort" in controller)
        assertTrue("lobby.openCurrent()" in controller)
        assertTrue("lobby.startNew(current.conversationToken)" in controller)
        assertTrue("lobby.allocateTurn(" in controller)
        assertTrue("conversationLobby: TutorConversationLobbyPort" in lobbyRoute)
        assertTrue("conversationLobby: TutorConversationLobbyPort" in entryRoute)
        assertFalse("conversationToken" in lobbyRoute)
    }

    private fun projectRoot(): File =
        generateSequence(
            File(requireNotNull(System.getProperty("user.dir"))).canonicalFile,
        ) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("Could not locate project root")
}
