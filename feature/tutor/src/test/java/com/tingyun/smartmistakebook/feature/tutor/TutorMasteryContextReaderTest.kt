package com.tingyun.smartmistakebook.feature.tutor

import com.tingyun.smartmistakebook.core.domain.TutorMasteryContext
import com.tingyun.smartmistakebook.core.domain.TutorMasteryContextRepository
import com.tingyun.smartmistakebook.core.domain.TutorMasteryContextRequest
import com.tingyun.smartmistakebook.core.domain.TutorMasteryStatus
import com.tingyun.smartmistakebook.core.domain.TutorMasterySummary
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.storage.KnowledgeNodeRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class TutorMasteryContextReaderTest {
    @Test
    fun newerRequestRejectsLateResultFromCancelledRead() = runTest {
        val firstResult = CompletableDeferred<TutorMasteryContext>()
        val firstRequest = request("first")
        val secondRequest = request("second")
        var snapshot = TutorMasteryContextReadSnapshot()
        val repository = TutorMasteryContextRepository { request ->
            if (request == firstRequest) {
                try {
                    firstResult.await()
                } catch (_: CancellationException) {
                    withContext(NonCancellable) { firstResult.await() }
                }
            } else {
                context("second")
            }
        }
        val coordinator = TutorMasteryContextReadCoordinator(this) { snapshot = it }

        coordinator.refresh(repository, firstRequest)
        runCurrent()
        coordinator.refresh(repository, secondRequest)
        runCurrent()
        firstResult.complete(context("first"))
        runCurrent()

        assertEquals(secondRequest, snapshot.request)
        assertFalse(snapshot.loading)
        assertEquals(
            listOf("second"),
            snapshot.context.summaries.map { summary -> summary.knowledgeNode.knowledgeNodeId },
        )
    }

    @Test
    fun repositoryFailurePublishesSafeEmptyContext() = runTest {
        val request = request("current")
        var snapshot = TutorMasteryContextReadSnapshot()
        val coordinator = TutorMasteryContextReadCoordinator(this) { snapshot = it }

        coordinator.refresh(
            repository = TutorMasteryContextRepository { error("unavailable") },
            request = request,
        )
        runCurrent()

        assertEquals(request, snapshot.request)
        assertFalse(snapshot.loading)
        assertEquals(TutorMasteryContext.EMPTY, snapshot.context)
    }

    private fun request(id: String) = TutorMasteryContextRequest(
        subject = SubjectKind.MATH,
        questionKnowledgeNodes = listOf(node(id)),
    )

    private fun context(id: String) = TutorMasteryContext(
        summaries = listOf(
            TutorMasterySummary(
                knowledgeNode = node(id),
                displayName = id,
                status = TutorMasteryStatus.LEARNING,
            ),
        ),
    )

    private fun node(id: String) = KnowledgeNodeRef(
        subject = SubjectKind.MATH,
        knowledgeNodeId = id,
        taxonomyVersion = "taxonomy-v1",
        knowledgePackVersion = "pack-v1",
    )
}
