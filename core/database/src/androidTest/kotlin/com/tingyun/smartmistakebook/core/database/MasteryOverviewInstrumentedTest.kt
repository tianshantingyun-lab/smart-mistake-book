package com.tingyun.smartmistakebook.core.database

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tingyun.smartmistakebook.core.model.AssessmentEvidenceSnapshot
import com.tingyun.smartmistakebook.core.model.AssessmentSnapshotVerification
import com.tingyun.smartmistakebook.core.model.AttemptSubmittedResponse
import com.tingyun.smartmistakebook.core.model.CalibrationSnapshot
import com.tingyun.smartmistakebook.core.model.CalibrationSupport
import com.tingyun.smartmistakebook.core.model.EvidenceAttributionCertainty
import com.tingyun.smartmistakebook.core.model.EvidenceAttributionRole
import com.tingyun.smartmistakebook.core.model.IndependentCorrectObservation
import com.tingyun.smartmistakebook.core.model.KnowledgeEvidenceAttribution
import com.tingyun.smartmistakebook.core.model.KnowledgeMasteryState
import com.tingyun.smartmistakebook.core.model.LearnerSnapshot
import com.tingyun.smartmistakebook.core.model.LearnerSnapshotFreshness
import com.tingyun.smartmistakebook.core.model.LearningEvidence
import com.tingyun.smartmistakebook.core.model.LearningEvidenceDirection
import com.tingyun.smartmistakebook.core.model.LearningEvidenceReason
import com.tingyun.smartmistakebook.core.model.MasteryStatus
import com.tingyun.smartmistakebook.core.model.ProblemMemoryOutcome
import com.tingyun.smartmistakebook.core.model.ProjectionCheckpoint
import com.tingyun.smartmistakebook.core.model.ProjectionStatus
import com.tingyun.smartmistakebook.core.model.StudyDayContext
import com.tingyun.smartmistakebook.core.database.entity.LearnerChatEvidenceEntity
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The subject mastery read behind the tutor's `MASTERY_READ` tool, against real
 * Room.
 *
 * Three properties this file exists to pin:
 * 1. **It reads the learner projection, not the fixture-seeded legacy table.**
 *    `seedFixture` writes `knowledge_mastery_state`, which is the pre-projection
 *    denormalized table nothing in production writes. A read that joined it
 *    looked healthy on device while returning NULL for every real user (KD-7),
 *    so the negative case here is the important one.
 * 2. **Subject scoping is a disclosure boundary.** The tool may only return the
 *    subject the session is already in, so a leaked row is a privacy defect, not
 *    a cosmetic one.
 * 3. **Node-grain, not binding-grain.** The lattice view returns one row per
 *    binding, so the same knowledge node repeats per bound question; the tool
 *    needs one row per node.
 */
@RunWith(AndroidJUnit4::class)
class MasteryOverviewInstrumentedTest {
    private lateinit var store: RoomStudyDatabase

    @Before
    fun setUp() {
        runBlocking {
            store = StudyDatabaseFactory.openInMemory(ApplicationProvider.getApplicationContext())
            store.seedFixture(baseSeed())
            store.commitProjection(projectionCommit())
        }
    }

    @After
    fun tearDown() {
        store.close()
    }

    @Test
    fun subjectMasteryIsNodeGraindAndScopedToTheSubject() = runBlocking {
        val rows = store.readSubjectMastery(LEARNER, "MATH")

        assertEquals(listOf("kc-weak", "kc-strong"), rows.map { it.knowledgeNodeId })
        assertEquals("函数单调性", rows.first().displayName)
        assertEquals(0.21, rows.first().lowerBoundIndependentCorrect, 1e-9)
        // 物理的知识点绝不能出现在数学会话里。
        assertTrue(
            "another subject leaked: ${rows.map { it.knowledgeNodeId }}",
            rows.none { it.knowledgeNodeId == "kc-physics" },
        )
    }

    @Test
    fun fixtureSeededLegacyMasteryIsNotServedAsMastery() = runBlocking {
        // baseSeed 通过 seedFixture 往遗留表 knowledge_mastery_state 写了 kc-legacy。
        // 它没有投影行，所以新的读取必须看不到它——这一条就是 KD-7 那类陷阱的回归。
        val rows = store.readSubjectMastery(LEARNER, "MATH")

        assertTrue(
            "the legacy fixture table must not be read as mastery: ${rows.map { it.knowledgeNodeId }}",
            rows.none { it.knowledgeNodeId == "kc-legacy" },
        )
    }

    @Test
    fun oneNodeCountsItsActiveBoundQuestionsOnce() = runBlocking {
        val rows = store.readSubjectMastery(LEARNER, "MATH")
        val weak = rows.single { it.knowledgeNodeId == "kc-weak" }

        assertEquals(1, weak.boundQuestionCount)
        // 节点粒度：两条 binding 指向同一个知识点时仍只有一行（lattice 会给两行）。
        assertEquals(1, rows.count { it.knowledgeNodeId == "kc-weak" })
    }

    @Test
    fun theReviewableNodeCountExcludesUnreviewedCandidates() = runBlocking {
        // MATH 有两个 SOURCE_GROUNDED 节点计入；kc-physics 属于别的科目，
        // kc-legacy 与 kc-unreviewed 是 MODEL_CANDIDATE（模型提议、未经审校）。
        // 这个数决定"另有 N 个尚无学习证据"那一行是否诚实，所以口径要与
        // countReviewedKnowledgeNodesBySubject 保持一致。
        assertEquals(2, store.countReviewableKnowledgeNodes("MATH"))
        assertEquals(1, store.countReviewableKnowledgeNodes("PHYSICS"))
    }

    @Test
    fun independentCorrectObservationsFeedTheAggregate() = runBlocking {
        val aggregates = store.readMasteryAggregates(LEARNER, setOf("kc-strong"))

        val strong = aggregates.single()
        assertEquals("kc-strong", strong.knowledgeNodeId)
        assertEquals(2, strong.independentCorrectCount)
        assertEquals(2, strong.independentCorrectItemFamilyCount)
        assertEquals(2, strong.independentCorrectStudyDayCount)
    }

    @Test
    fun modelEvidenceAggregatesSplitAcceptedFromRejected() = runBlocking {
        store.recordChatEvidence(
            listOf(
                chatEvidence(evidenceId = "ev-accepted", rejectedReason = null),
                chatEvidence(evidenceId = "ev-rejected", rejectedReason = "SAME_KC_IN_COOLDOWN"),
            ),
        )

        val aggregate = store.readMasteryAggregates(LEARNER, setOf("kc-weak")).single()

        assertEquals(1, aggregate.acceptedModelEvidenceCount)
        assertEquals(1, aggregate.rejectedModelEvidenceCount)
    }

    @Test
    fun aNegativeAttemptCountsOnceThroughItsAttribution() = runBlocking {
        store.saveAssessmentEvidenceSnapshot(evidenceSnapshot())
        store.recordAttempt(
            AttemptWriteCommand(
                learnerId = LEARNER,
                submissionId = "submission-1",
                attemptId = "attempt-1",
                presentationId = "presentation-1",
                assessmentSnapshotId = SNAPSHOT_ID,
                submittedResponse = AttemptSubmittedResponse.Choice(
                    choiceId = "choice-a",
                    choiceMarkdown = "选项 A",
                    submittedAtEpochMillis = OCCURRED_AT,
                ),
                evidence = LearningEvidence(
                    direction = LearningEvidenceDirection.NEGATIVE,
                    weight = 1.0,
                    reason = LearningEvidenceReason.INDEPENDENT_INCORRECT,
                ),
                problemMemoryOutcome = ProblemMemoryOutcome.RETRIEVAL_FAILURE,
                occurredAtEpochMillis = OCCURRED_AT,
                durationSeconds = 30,
                studyDay = studyDay(OCCURRED_AT),
            ),
        )

        val aggregate = store.readMasteryAggregates(LEARNER, setOf("kc-weak")).single()

        assertEquals(1, aggregate.independentErrorCount)
        assertEquals(OCCURRED_AT, aggregate.lastIndependentErrorAtEpochMillis)
        // 正向作答不应计入错误。
        assertEquals(0, store.readMasteryAggregates(LEARNER, setOf("kc-strong"))
            .single().independentErrorCount)
    }

    private fun chatEvidence(evidenceId: String, rejectedReason: String?) = LearnerChatEvidenceEntity(
        evidence_id = evidenceId,
        learner_id = LEARNER,
        conversation_id = "tutor-conv:captured:session-1",
        knowledge_node_id = "kc-weak",
        direction = "POSITIVE",
        weight = 0.15,
        reason_markdown = "学生独立做对了这一步",
        confidence = 0.8,
        source_kind = "MODEL_CHAT",
        created_at_epoch_millis = OCCURRED_AT,
        rejected_reason = rejectedReason,
        rejected_at_epoch_millis = rejectedReason?.let { OCCURRED_AT },
    )

    private fun evidenceSnapshot() = AssessmentEvidenceSnapshot(
        snapshotId = SNAPSHOT_ID,
        assessmentItemId = "assessment-item-1",
        practiceUnitId = UNIT_ID,
        problemRevisionId = REVISION_ID,
        answerSpecId = "answer-spec-1",
        itemFamilyId = "family-1",
        sourceBundleId = null,
        taxonomyVersion = "taxonomy-v1",
        verification = AssessmentSnapshotVerification.VERIFIED,
        calibration = CalibrationSnapshot.unknown(),
        attributions = listOf(
            KnowledgeEvidenceAttribution(
                bindingId = BINDING_ID,
                knowledgeNodeId = "kc-weak",
                weight = 1.0,
                basisRevisionId = REVISION_ID,
                taxonomyVersion = "taxonomy-v1",
                role = EvidenceAttributionRole.PRIMARY,
                certainty = EvidenceAttributionCertainty.DIRECT,
            ),
        ),
        capturedAtEpochMillis = OCCURRED_AT,
    )

    private fun studyDay(atEpochMillis: Long): StudyDayContext {
        val zoned = Instant.ofEpochMilli(atEpochMillis).atZone(ZoneId.of(ZONE))
        return StudyDayContext(
            epochDay = zoned.toLocalDate().toEpochDay(),
            timeZoneId = ZONE,
            utcOffsetMinutes = zoned.offset.totalSeconds / 60,
        )
    }

    private fun projectionCommit() = ProjectionCommit(
        projectionName = PROJECTION,
        learnerId = LEARNER,
        expectedPreviousCheckpoint = 0,
        expectedPreviousStateVersion = 0,
        mode = ProjectionCommitMode.FULL_REPLAY,
        knownLedgerHeadSequence = 0,
        consumedLedgerEvents = emptyList(),
        presentationProjectionStates = emptyMap(),
        snapshot = LearnerSnapshot(
            learnerId = LEARNER,
            knowledgeMasteryStates = mapOf(
                "kc-weak" to masteryState("kc-weak", score = 0.21, status = MasteryStatus.LEARNING),
                "kc-strong" to masteryState("kc-strong", score = 0.82, status = MasteryStatus.MASTERED),
                "kc-physics" to masteryState("kc-physics", score = 0.40, status = MasteryStatus.LEARNING),
            ),
            checkpoint = ProjectionCheckpoint(
                lastSequence = 0,
                projectorVersion = PROJECTOR_VERSION,
                projectedAtEpochMillis = PROJECTED_AT,
            ),
            generatedAtEpochMillis = PROJECTED_AT,
            freshness = LearnerSnapshotFreshness.CURRENT,
            projectionStatus = ProjectionStatus.CURRENT,
        ),
    )

    private fun masteryState(
        nodeId: String,
        score: Double,
        status: MasteryStatus,
    ) = KnowledgeMasteryState(
        knowledgeNodeId = nodeId,
        masteryScore = score,
        conservativeMasteryScore = score,
        evidenceMass = 1.5,
        independentCorrectObservations = if (status == MasteryStatus.MASTERED) {
            listOf(
                IndependentCorrectObservation(
                    itemFamilyId = "family-1",
                    studyDayEpochDay = 20_100,
                    occurredAtEpochMillis = OCCURRED_AT - 2 * DAY_MILLIS,
                ),
                IndependentCorrectObservation(
                    itemFamilyId = "family-2",
                    studyDayEpochDay = 20_101,
                    occurredAtEpochMillis = OCCURRED_AT - DAY_MILLIS,
                ),
            )
        } else {
            emptyList()
        },
        lastIndependentErrorAtEpochMillis = null,
        status = status,
        calibrationSupport = CalibrationSupport.UNKNOWN,
        projectorVersion = PROJECTOR_VERSION,
        checkpointSequence = 0,
    )

    private fun baseSeed() = StudySeedBundle(
        problems = listOf(
            ProblemSeedRecord(PROBLEM_ID, "problem-fingerprint", "MATH", 1_000),
        ),
        revisions = listOf(
            ProblemRevisionSeedRecord(
                revisionId = REVISION_ID,
                problemId = PROBLEM_ID,
                revisionNumber = 1,
                title = "函数单调性",
                problemMarkdown = "求函数的单调区间。",
                answerSpecId = "answer-spec-1",
                answerSpecSnapshot = "增区间与减区间",
                answerVerificationStatus = StudyDbValue.VerificationStatus.VERIFIED,
                sourceType = "IMPORT",
                sourceReference = null,
                contentFingerprint = "revision-fingerprint",
                createdAtEpochMillis = 2_000,
            ),
        ),
        practiceUnits = listOf(
            PracticeUnitSeedRecord(
                practiceUnitId = UNIT_ID,
                problemId = PROBLEM_ID,
                problemRevisionId = REVISION_ID,
                unitKey = "whole",
                unitKind = "WHOLE",
                title = "函数单调性",
                promptMarkdown = "求单调区间。",
                estimatedSeconds = 120,
                createdAtEpochMillis = 3_000,
            ),
        ),
        errorBookEntries = listOf(
            ErrorBookEntrySeedRecord(
                entryId = ENTRY_ID,
                practiceUnitId = UNIT_ID,
                problemId = PROBLEM_ID,
                currentRevisionId = REVISION_ID,
                sourceKey = "source-1",
                acceptedAtEpochMillis = 4_000,
                updatedAtEpochMillis = 4_000,
            ),
        ),
        knowledgeNodes = listOf(
            knowledgeNode("kc-weak", "函数单调性", "MATH"),
            knowledgeNode("kc-strong", "函数奇偶性", "MATH"),
            knowledgeNode("kc-physics", "动量守恒", "PHYSICS"),
            knowledgeNode("kc-legacy", "遗留节点", "MATH", verificationStatus = "MODEL_CANDIDATE"),
            knowledgeNode("kc-unreviewed", "未审校节点", "MATH", verificationStatus = "MODEL_CANDIDATE"),
        ),
        knowledgeBindings = listOf(
            KnowledgeBindingSeedRecord(
                bindingId = BINDING_ID,
                practiceUnitId = UNIT_ID,
                knowledgeNodeId = "kc-weak",
                basisRevisionId = REVISION_ID,
                strength = 1.0,
                sourceType = "VERIFIED",
                taxonomyVersion = "taxonomy-v1",
                acceptedAtEpochMillis = 5_000,
            ),
        ),
        // 遗留表（fixture 专用）也写一份：它写了不算数，读取必须无视它。
        knowledgeMasteryStates = listOf(
            KnowledgeMasteryStateRecord(
                knowledgeNodeId = "kc-legacy",
                masteryProbability = 0.99,
                independentCorrectCount = 9,
                assistedCorrectCount = 0,
                incorrectCount = 0,
                evidenceWeightTotal = 9.0,
                lastEvidenceAtEpochMillis = OCCURRED_AT,
                projectionCheckpoint = 0,
                projectorVersion = PROJECTOR_VERSION,
                updatedAtEpochMillis = OCCURRED_AT,
            ),
        ),
    )

    private fun knowledgeNode(
        nodeId: String,
        name: String,
        subject: String,
        verificationStatus: String = "SOURCE_GROUNDED",
    ) = KnowledgeNodeSeedRecord(
        knowledgeNodeId = nodeId,
        stableCode = nodeId,
        subject = subject,
        displayName = name,
        parentKnowledgeNodeId = null,
        taxonomyVersion = "taxonomy-v1",
        createdAtEpochMillis = 1_000,
        canonicalName = name,
        granularity = "ATOMIC",
        verificationStatus = verificationStatus,
    )

    private companion object {
        const val LEARNER = "learner:test"
        const val PROJECTION = "study-experience-v1"
        const val PROJECTOR_VERSION = "learning-core-v6"
        const val PROBLEM_ID = "problem-1"
        const val REVISION_ID = "revision-1"
        const val UNIT_ID = "unit-1"
        const val ENTRY_ID = "entry-1"
        const val BINDING_ID = "binding-1"
        const val SNAPSHOT_ID = "snapshot-1"
        const val ZONE = "Asia/Shanghai"
        const val DAY_MILLIS = 86_400_000L
        const val OCCURRED_AT = 1_700_000_000_000L
        const val PROJECTED_AT = 1_700_000_100_000L
    }
}
