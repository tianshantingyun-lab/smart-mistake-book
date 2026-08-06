package com.tingyun.smartmistakebook.core.data.authority

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tingyun.smartmistakebook.core.data.mastery.LearningMasteryKnowledgeActivation
import com.tingyun.smartmistakebook.core.data.mastery.LocalLearningMasteryDisplayRepository
import com.tingyun.smartmistakebook.core.data.tutor.LocalTutorMasteryContextRepository
import com.tingyun.smartmistakebook.core.domain.LearningMasteryDisplayRepository
import com.tingyun.smartmistakebook.core.domain.LearningMasteryLoadState
import com.tingyun.smartmistakebook.core.domain.LearningMasteryPageRequest
import com.tingyun.smartmistakebook.core.domain.LearningMasteryStatus
import com.tingyun.smartmistakebook.core.domain.LearningMasterySubject
import com.tingyun.smartmistakebook.core.domain.TutorMasteryContextRepository
import com.tingyun.smartmistakebook.core.domain.TutorMasteryContextRequest
import com.tingyun.smartmistakebook.core.domain.TutorMasteryStatus
import com.tingyun.smartmistakebook.core.knowledge.database.DebugBoundaryKnowledgePackFixture
import com.tingyun.smartmistakebook.core.knowledge.database.HighSchoolKnowledgeCatalogFactory
import com.tingyun.smartmistakebook.core.knowledge.database.HighSchoolKnowledgePackProvisioner
import com.tingyun.smartmistakebook.core.knowledge.database.HIGH_SCHOOL_KNOWLEDGE_DATABASE_NAME
import com.tingyun.smartmistakebook.core.knowledge.database.KnowledgePackProvisionOutcome
import com.tingyun.smartmistakebook.core.mastery.database.LEARNER_MASTERY_DATABASE_NAME
import com.tingyun.smartmistakebook.core.mastery.database.KnowledgeMasteryState
import com.tingyun.smartmistakebook.core.mastery.database.LearnerMasteryInboundDisposition
import com.tingyun.smartmistakebook.core.mastery.database.LearnerMasteryOwnerAccess
import com.tingyun.smartmistakebook.core.mastery.database.LearnerMasteryReader
import com.tingyun.smartmistakebook.core.mastery.database.LearnerMasteryRelayCapability
import com.tingyun.smartmistakebook.core.mastery.database.VerifiedStudentMistakeDelivery
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.storage.CrossStoreEventEnvelope
import com.tingyun.smartmistakebook.core.model.storage.KnowledgeNodeRef
import com.tingyun.smartmistakebook.core.model.storage.KnowledgeReferenceProofAuthority
import com.tingyun.smartmistakebook.core.model.storage.StudentProblemRevisionRef
import com.tingyun.smartmistakebook.core.model.storage.ReviewVerificationOutcome
import com.tingyun.smartmistakebook.core.student.mistake.database.ReviewClosureRelayTestFactory
import com.tingyun.smartmistakebook.core.student.mistake.database.STUDENT_MISTAKE_DATABASE_NAME
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

private const val LEARNER_ID = "learner-closure-test"

private fun CrossStoreEventEnvelope.verifiedDelivery(): VerifiedStudentMistakeDelivery =
    LearnerMasteryOwnerAccess.bindVerifiedStudentOutboxDelivery(
        ReviewClosureRelayTestFactory.verifiedDelivery(
            envelope = this,
            learnerId = LEARNER_ID,
        ),
    )

@RunWith(AndroidJUnit4::class)
class LearningMemoryClosureInstrumentedTest {
    @Test
    fun reviewObservationProjectsIntoDisplayAndTutorContextThroughRealAuthority() =
        runBlocking {
            withClosureAuthority { authority ->
                val binding =
                    learnerMasteryClosureBinding(authority.revision, authority.node)
                val bindingsEnvelope =
                    learnerMasteryClosureBindingsEnvelope(
                        revision = authority.revision,
                        bindings = listOf(binding),
                        changedAtEpochMillis = authority.now - 2_000L,
                    )
                assertEquals(
                    LearnerMasteryInboundDisposition.APPLIED,
                    authority.relay.accept(
                        bindingsEnvelope.verifiedDelivery(),
                        authority.now,
                    ),
                )

                val reviewEnvelope =
                    learnerMasteryClosureReviewObservation(
                        revision = authority.revision,
                        capturedAtEpochMillis = authority.now - 1_000L,
                    )
                assertEquals(
                    LearnerMasteryInboundDisposition.APPLIED,
                    authority.relay.accept(
                        reviewEnvelope.verifiedDelivery(),
                        authority.now,
                    ),
                )

                val overview =
                    authority.display.observeSubjectOverview().first { state ->
                        state is LearningMasteryLoadState.Content
                    }
                val overviewValue =
                    (overview as LearningMasteryLoadState.Content).value
                val math =
                    overviewValue.subjects.single {
                        it.subject == LearningMasterySubject.MATHEMATICS
                    }
                assertEquals(
                    LearningMasteryStatus.NEEDS_REINFORCEMENT,
                    math.status,
                )
                assertTrue(
                    overviewValue.subjects
                        .filterNot {
                            it.subject == LearningMasterySubject.MATHEMATICS
                        }
                        .all { it.status == LearningMasteryStatus.NOT_YET_LEARNED },
                )

                val page =
                    authority.display
                        .observeKnowledgePage(
                            LearningMasteryPageRequest(
                                subject = LearningMasterySubject.MATHEMATICS,
                                revision = overviewValue.revision,
                            ),
                        )
                        .first { state ->
                            state is LearningMasteryLoadState.Content
                        }
                val pageValue =
                    (page as LearningMasteryLoadState.Content).value
                assertTrue(
                    pageValue.items.any {
                        it.status == LearningMasteryStatus.NEEDS_REINFORCEMENT
                    },
                )

                val context =
                    authority.tutor.read(
                        TutorMasteryContextRequest(
                            subject = SubjectKind.MATH,
                            questionKnowledgeNodes = listOf(authority.node),
                        ),
                    )
                val summary = context.summaries.single()
                assertEquals(TutorMasteryStatus.NEEDS_PRACTICE, summary.status)
                assertTrue(context.projectionIsCurrent)
            }
        }

    @Test
    fun sameSubjectMasteryIsAvailableToAnotherProblemRequest() =
        runBlocking {
            withClosureAuthority { authority ->
                val secondRevision =
                    learnerMasteryClosureRevision(LEARNER_ID, suffix = "second-problem")
                val firstBinding =
                    learnerMasteryClosureBinding(authority.revision, authority.node)
                val secondBinding =
                    learnerMasteryClosureBinding(secondRevision, authority.node)

                assertEquals(
                    LearnerMasteryInboundDisposition.APPLIED,
                    authority.relay.accept(
                        learnerMasteryClosureBindingsEnvelope(
                            revision = authority.revision,
                            bindings = listOf(firstBinding),
                            changedAtEpochMillis = authority.now - 2_000L,
                        ).verifiedDelivery(),
                        authority.now,
                    ),
                )
                assertEquals(
                    LearnerMasteryInboundDisposition.APPLIED,
                    authority.relay.accept(
                        learnerMasteryClosureReviewObservation(
                            revision = authority.revision,
                            capturedAtEpochMillis = authority.now - 1_000L,
                        ).verifiedDelivery(),
                        authority.now,
                    ),
                )
                assertEquals(
                    LearnerMasteryInboundDisposition.APPLIED,
                    authority.relay.accept(
                        learnerMasteryClosureBindingsEnvelope(
                            revision = secondRevision,
                            bindings = listOf(secondBinding),
                            changedAtEpochMillis = authority.now - 500L,
                        ).verifiedDelivery(),
                        authority.now,
                    ),
                )

                val context =
                    authority.tutor.read(
                        TutorMasteryContextRequest(
                            subject = SubjectKind.MATH,
                            questionKnowledgeNodes = listOf(authority.node),
                        ),
                    )
                val summary = context.summaries.single()
                assertEquals(TutorMasteryStatus.NEEDS_PRACTICE, summary.status)
                assertTrue(context.projectionIsCurrent)
            }
        }

    @Test
    fun relatedKnowledgeNeighborhoodProjectsThroughRealAuthorityAndCatalog() =
        runBlocking {
            withClosureAuthority { authority ->
                val related = learnerMasteryClosureRelatedNode()
                val relatedRevision =
                    learnerMasteryClosureRevision(LEARNER_ID, suffix = "related-problem")
                val topicBinding =
                    learnerMasteryClosureBinding(authority.revision, authority.node)
                val relatedBinding =
                    learnerMasteryClosureBinding(
                        revision = relatedRevision,
                        node = related,
                        bindingId = "binding:related",
                    )

                assertEquals(
                    LearnerMasteryInboundDisposition.APPLIED,
                    authority.relay.accept(
                        learnerMasteryClosureBindingsEnvelope(
                            revision = authority.revision,
                            bindings = listOf(topicBinding),
                            changedAtEpochMillis = authority.now - 2_000L,
                        ).verifiedDelivery(),
                        authority.now,
                    ),
                )
                assertEquals(
                    LearnerMasteryInboundDisposition.APPLIED,
                    authority.relay.accept(
                        learnerMasteryClosureReviewObservation(
                            revision = authority.revision,
                            capturedAtEpochMillis = authority.now - 1_500L,
                        ).verifiedDelivery(),
                        authority.now,
                    ),
                )
                assertEquals(
                    LearnerMasteryInboundDisposition.APPLIED,
                    authority.relay.accept(
                        learnerMasteryClosureBindingsEnvelope(
                            revision = relatedRevision,
                            bindings = listOf(relatedBinding),
                            changedAtEpochMillis = authority.now - 1_000L,
                        ).verifiedDelivery(),
                        authority.now,
                    ),
                )
                assertEquals(
                    LearnerMasteryInboundDisposition.APPLIED,
                    authority.relay.accept(
                        learnerMasteryClosureReviewObservation(
                            revision = relatedRevision,
                            capturedAtEpochMillis = authority.now - 500L,
                            eventSuffix = "related",
                        ).verifiedDelivery(),
                        authority.now,
                    ),
                )

                val context =
                    authority.tutor.read(
                        TutorMasteryContextRequest(
                            subject = SubjectKind.MATH,
                            questionKnowledgeNodes = listOf(authority.node),
                        ),
                    )
                assertEquals(listOf(authority.node), context.summaries.map { it.knowledgeNode })
                assertEquals(listOf(related), context.relatedSummaries.map { it.knowledgeNode })
                assertEquals(TutorMasteryStatus.NEEDS_PRACTICE, context.summaries.single().status)
                assertEquals(
                    TutorMasteryStatus.NEEDS_PRACTICE,
                    context.relatedSummaries.single().status,
                )
                assertTrue(context.projectionIsCurrent)
            }
        }

    @Test
    fun correctRetriesAfterErrorWritePositiveEvidenceAndPresentations() =
        runBlocking {
            withClosureAuthority { authority ->
                val binding =
                    learnerMasteryClosureBinding(authority.revision, authority.node)
                assertEquals(
                    LearnerMasteryInboundDisposition.APPLIED,
                    authority.relay.accept(
                        learnerMasteryClosureBindingsEnvelope(
                            revision = authority.revision,
                            bindings = listOf(binding),
                            changedAtEpochMillis = authority.now - 2_000L,
                        ).verifiedDelivery(),
                        authority.now,
                    ),
                )
                assertEquals(
                    LearnerMasteryInboundDisposition.APPLIED,
                    authority.relay.accept(
                        learnerMasteryClosureReviewObservation(
                            revision = authority.revision,
                            capturedAtEpochMillis = authority.now - 1_500L,
                            eventSuffix = "error",
                            outcome = ReviewVerificationOutcome.INCORRECT,
                        ).verifiedDelivery(),
                        authority.now,
                    ),
                )
                repeat(5) { index ->
                    assertEquals(
                        LearnerMasteryInboundDisposition.APPLIED,
                        authority.relay.accept(
                            learnerMasteryClosureReviewObservation(
                                revision = authority.revision,
                                capturedAtEpochMillis =
                                    authority.now - 1_000L + index * 10L,
                                eventSuffix = "recover-$index",
                                outcome = ReviewVerificationOutcome.CORRECT,
                            ).verifiedDelivery(),
                            authority.now,
                        ),
                    )
                }

                val digest =
                    authority.reader.queryDigest(
                        subject = SubjectKind.MATH,
                        focusLimit = 16,
                    )
                val item =
                    digest.focus.single {
                        it.knowledgeNode.canonicalFingerprint ==
                            authority.node.canonicalFingerprint
                    }
                assertTrue(item.lastPositiveAtEpochMillis != null)
                assertTrue(item.lastNegativeAtEpochMillis != null)
                assertTrue(item.distinctPresentationCount >= 5L)
            }
        }

    @Test
    fun revealedCorrectAnswerDoesNotWriteIndependentPositiveEvidence() =
        runBlocking {
            withClosureAuthority { authority ->
                val binding =
                    learnerMasteryClosureBinding(authority.revision, authority.node)
                assertEquals(
                    LearnerMasteryInboundDisposition.APPLIED,
                    authority.relay.accept(
                        learnerMasteryClosureBindingsEnvelope(
                            revision = authority.revision,
                            bindings = listOf(binding),
                            changedAtEpochMillis = authority.now - 2_000L,
                        ).verifiedDelivery(),
                        authority.now,
                    ),
                )
                assertEquals(
                    LearnerMasteryInboundDisposition.APPLIED,
                    authority.relay.accept(
                        learnerMasteryClosureReviewObservation(
                            revision = authority.revision,
                            capturedAtEpochMillis = authority.now - 1_000L,
                            eventSuffix = "revealed-correct",
                            outcome = ReviewVerificationOutcome.CORRECT,
                            answerWasRevealed = true,
                        ).verifiedDelivery(),
                        authority.now,
                    ),
                )

                val digest =
                    authority.reader.queryDigest(
                        subject = SubjectKind.MATH,
                        focusLimit = 16,
                    )
                assertTrue(
                    digest.focus.none {
                        it.knowledgeNode.canonicalFingerprint ==
                            authority.node.canonicalFingerprint
                    },
                )
            }
        }

    @Test
    fun weakNodeGetsAttentionThenSolidMasterySurvivesAnotherProblemRequest() =
        runBlocking {
            withClosureAuthority { authority ->
                val binding =
                    learnerMasteryClosureBinding(authority.revision, authority.node)
                assertEquals(
                    LearnerMasteryInboundDisposition.APPLIED,
                    authority.relay.accept(
                        learnerMasteryClosureBindingsEnvelope(
                            revision = authority.revision,
                            bindings = listOf(binding),
                            changedAtEpochMillis = authority.now - 2_000L,
                        ).verifiedDelivery(),
                        authority.now,
                    ),
                )
                assertEquals(
                    LearnerMasteryInboundDisposition.APPLIED,
                    authority.relay.accept(
                        learnerMasteryClosureReviewObservation(
                            revision = authority.revision,
                            capturedAtEpochMillis = authority.now - 1_500L,
                            eventSuffix = "weak",
                            outcome = ReviewVerificationOutcome.INCORRECT,
                        ).verifiedDelivery(),
                        authority.now,
                    ),
                )

                val weakContext =
                    authority.tutor.read(
                        TutorMasteryContextRequest(
                            subject = SubjectKind.MATH,
                            questionKnowledgeNodes = listOf(authority.node),
                        ),
                    )
                assertEquals(
                    TutorMasteryStatus.NEEDS_PRACTICE,
                    weakContext.summaries.single().status,
                )

                var projectionReachedSteady = false
                for (index in 0 until 30) {
                    if (index > 0) {
                        assertEquals(
                            LearnerMasteryInboundDisposition.APPLIED,
                            authority.relay.accept(
                                learnerMasteryClosureReviewObservation(
                                    revision = authority.revision,
                                    capturedAtEpochMillis =
                                        authority.now - 1_000L + index * 10L,
                                    eventSuffix = "solid-$index",
                                    outcome = ReviewVerificationOutcome.CORRECT,
                                ).verifiedDelivery(),
                                authority.now,
                            ),
                        )
                    }
                    val digest =
                        authority.reader.queryDigest(
                            subject = SubjectKind.MATH,
                            focusLimit = 16,
                        )
                    val item =
                        digest.focus.singleOrNull {
                            it.knowledgeNode.canonicalFingerprint ==
                                authority.node.canonicalFingerprint
                        } ?: continue
                    if (item.currentRecallState == KnowledgeMasteryState.STEADY) {
                        projectionReachedSteady = true
                        break
                    }
                }
                assertTrue(
                    "Projection did not reach STEADY after repeated independent correct evidence",
                    projectionReachedSteady,
                )

                val solidContext =
                    authority.tutor.read(
                        TutorMasteryContextRequest(
                            subject = SubjectKind.MATH,
                            questionKnowledgeNodes = listOf(authority.node),
                        ),
                    )
                assertEquals(TutorMasteryStatus.SOLID, solidContext.summaries.single().status)
                assertTrue(solidContext.projectionIsCurrent)

                val overview =
                    authority.display.observeSubjectOverview().first { state ->
                        state is LearningMasteryLoadState.Content
                    }
                val overviewValue =
                    (overview as LearningMasteryLoadState.Content).value
                val math =
                    overviewValue.subjects.single {
                        it.subject == LearningMasterySubject.MATHEMATICS
                    }
                assertEquals(LearningMasteryStatus.FAIRLY_STEADY, math.status)

                val secondRevision =
                    learnerMasteryClosureRevision(LEARNER_ID, suffix = "second-problem")
                val secondBinding =
                    learnerMasteryClosureBinding(secondRevision, authority.node)
                assertEquals(
                    LearnerMasteryInboundDisposition.APPLIED,
                    authority.relay.accept(
                        learnerMasteryClosureBindingsEnvelope(
                            revision = secondRevision,
                            bindings = listOf(secondBinding),
                            changedAtEpochMillis = authority.now - 500L,
                        ).verifiedDelivery(),
                        authority.now,
                    ),
                )

                val secondProblemContext =
                    authority.tutor.read(
                        TutorMasteryContextRequest(
                            subject = SubjectKind.MATH,
                            questionKnowledgeNodes = listOf(authority.node),
                        ),
                    )
                val summary = secondProblemContext.summaries.single()
                assertEquals(TutorMasteryStatus.SOLID, summary.status)
                assertTrue(secondProblemContext.projectionIsCurrent)
            }
        }

    @Test
    fun solidMasteryDropsAfterRecentErrorAndRecoversAfterIndependentCorrect() =
        runBlocking {
            withClosureAuthority { authority ->
                val binding =
                    learnerMasteryClosureBinding(authority.revision, authority.node)
                assertEquals(
                    LearnerMasteryInboundDisposition.APPLIED,
                    authority.relay.accept(
                        learnerMasteryClosureBindingsEnvelope(
                            revision = authority.revision,
                            bindings = listOf(binding),
                            changedAtEpochMillis = authority.now - 2_000L,
                        ).verifiedDelivery(),
                        authority.now,
                    ),
                )

                var reachedSteady = false
                for (index in 0 until 40) {
                    if (index > 0) {
                        assertEquals(
                            LearnerMasteryInboundDisposition.APPLIED,
                            authority.relay.accept(
                                learnerMasteryClosureReviewObservation(
                                    revision = authority.revision,
                                    capturedAtEpochMillis =
                                        authority.now - 1_000L + index * 10L,
                                    eventSuffix = "steady-$index",
                                    outcome = ReviewVerificationOutcome.CORRECT,
                                ).verifiedDelivery(),
                                authority.now,
                            ),
                        )
                    }
                    val digest =
                        authority.reader.queryDigest(
                            subject = SubjectKind.MATH,
                            focusLimit = 16,
                        )
                    val item =
                        digest.focus.singleOrNull {
                            it.knowledgeNode.canonicalFingerprint ==
                                authority.node.canonicalFingerprint
                        } ?: continue
                    if (item.currentRecallState == KnowledgeMasteryState.STEADY) {
                        reachedSteady = true
                        break
                    }
                }
                assertTrue(
                    "Projection did not reach STEADY before the error recovery case",
                    reachedSteady,
                )

                var nextEventTime = authority.now - 1_000L + 40 * 10L
                var droppedToReinforcement = false
                for (index in 0 until 40) {
                    assertEquals(
                        LearnerMasteryInboundDisposition.APPLIED,
                        authority.relay.accept(
                            learnerMasteryClosureReviewObservation(
                                revision = authority.revision,
                                capturedAtEpochMillis = nextEventTime,
                                eventSuffix = "drop-$index",
                                outcome = ReviewVerificationOutcome.INCORRECT,
                            ).verifiedDelivery(),
                            authority.now,
                        ),
                    )
                    nextEventTime += 10L
                    val digest =
                        authority.reader.queryDigest(
                            subject = SubjectKind.MATH,
                            focusLimit = 16,
                        )
                    val item =
                        digest.focus.singleOrNull {
                            it.knowledgeNode.canonicalFingerprint ==
                                authority.node.canonicalFingerprint
                        } ?: continue
                    if (item.currentRecallState == KnowledgeMasteryState.NEEDS_REINFORCEMENT) {
                        droppedToReinforcement = true
                        break
                    }
                }
                assertTrue(
                    "Projection did not drop to NEEDS_REINFORCEMENT after recent errors",
                    droppedToReinforcement,
                )

                val droppedContext =
                    authority.tutor.read(
                        TutorMasteryContextRequest(
                            subject = SubjectKind.MATH,
                            questionKnowledgeNodes = listOf(authority.node),
                        ),
                    )
                assertEquals(
                    TutorMasteryStatus.NEEDS_PRACTICE,
                    droppedContext.summaries.single().status,
                )

                var recoveredToSteady = false
                for (index in 0 until 40) {
                    if (index > 0) {
                        assertEquals(
                            LearnerMasteryInboundDisposition.APPLIED,
                            authority.relay.accept(
                                learnerMasteryClosureReviewObservation(
                                    revision = authority.revision,
                                    capturedAtEpochMillis = nextEventTime,
                                    eventSuffix = "recover-$index",
                                    outcome = ReviewVerificationOutcome.CORRECT,
                                ).verifiedDelivery(),
                                authority.now,
                            ),
                        )
                        nextEventTime += 10L
                    }
                    val digest =
                        authority.reader.queryDigest(
                            subject = SubjectKind.MATH,
                            focusLimit = 16,
                        )
                    val item =
                        digest.focus.singleOrNull {
                            it.knowledgeNode.canonicalFingerprint ==
                                authority.node.canonicalFingerprint
                        } ?: continue
                    if (item.currentRecallState == KnowledgeMasteryState.STEADY) {
                        recoveredToSteady = true
                        break
                    }
                }
                assertTrue(
                    "Projection did not recover to STEADY after independent correct evidence",
                    recoveredToSteady,
                )

                val recoveredContext =
                    authority.tutor.read(
                        TutorMasteryContextRequest(
                            subject = SubjectKind.MATH,
                            questionKnowledgeNodes = listOf(authority.node),
                        ),
                    )
                assertEquals(TutorMasteryStatus.SOLID, recoveredContext.summaries.single().status)
                assertTrue(recoveredContext.projectionIsCurrent)
            }
        }

    private suspend fun <T> withClosureAuthority(
        block: suspend (ClosureAuthority) -> T,
    ): T {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        clearLearningMemoryAuthorities(context)
        val proofAuthority = KnowledgeReferenceProofAuthority.create()
        try {
            val provision =
                HighSchoolKnowledgePackProvisioner.provisionApkBundledReviewedPack(
                    context = context,
                    pack = DebugBoundaryKnowledgePackFixture.reviewedPack(),
                )
            assertEquals(KnowledgePackProvisionOutcome.ACTIVATED, provision.outcome)

            return HighSchoolKnowledgeCatalogFactory.open(context, proofAuthority.issuer)
                .use { knowledge ->
                    val manifest = knowledge.readManifest()
                    LearnerMasteryOwnerAccess.openLearnerMasteryOwnerCapabilities(
                        context,
                        LEARNER_ID,
                        proofAuthority.verifier,
                    ).use { mastery ->
                        val display =
                            LocalLearningMasteryDisplayRepository(
                                mastery = mastery.getDisplayReader(),
                                knowledge = knowledge,
                                activation =
                                    LearningMasteryKnowledgeActivation(
                                        knowledgePackVersion =
                                            manifest.knowledgePackVersion,
                                        taxonomyVersion = manifest.taxonomyVersion,
                                        manifestFingerprint = manifest.contentFingerprint,
                                        generation = provision.activation.generation,
                                    ),
                            )
                        val tutor =
                            LocalTutorMasteryContextRepository(
                                mastery = mastery.getLocalContextReader(),
                                knowledge = knowledge,
                            )
                        block(
                            ClosureAuthority(
                                relay =
                                    LearnerMasteryOwnerAccess
                                        .openLearnerMasteryRelayCapability(mastery),
                                reader = mastery.getReader(),
                                display = display,
                                tutor = tutor,
                                node = learnerMasteryClosureNode(),
                                revision = learnerMasteryClosureRevision(LEARNER_ID),
                                now = System.currentTimeMillis(),
                            ),
                        )
                    }
                }
        } finally {
            clearLearningMemoryAuthorities(context)
        }
    }

    private fun clearLearningMemoryAuthorities(context: android.content.Context) {
        context.deleteDatabase(STUDENT_MISTAKE_DATABASE_NAME)
        context.deleteDatabase(LEARNER_MASTERY_DATABASE_NAME)
        context.deleteDatabase(HIGH_SCHOOL_KNOWLEDGE_DATABASE_NAME)
        val knowledgeDirectory =
            requireNotNull(
                context.getDatabasePath(HIGH_SCHOOL_KNOWLEDGE_DATABASE_NAME).parentFile,
            )
        listOf(
            "$HIGH_SCHOOL_KNOWLEDGE_DATABASE_NAME.activation",
            "$HIGH_SCHOOL_KNOWLEDGE_DATABASE_NAME.activation.pending",
        ).forEach { name ->
            File(knowledgeDirectory, name).delete()
        }
    }

    private data class ClosureAuthority(
        val relay: LearnerMasteryRelayCapability,
        val reader: LearnerMasteryReader,
        val display: LearningMasteryDisplayRepository,
        val tutor: TutorMasteryContextRepository,
        val node: KnowledgeNodeRef,
        val revision: StudentProblemRevisionRef,
        val now: Long,
    )
}
