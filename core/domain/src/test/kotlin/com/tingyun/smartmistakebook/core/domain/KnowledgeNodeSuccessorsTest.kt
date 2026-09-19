package com.tingyun.smartmistakebook.core.domain

import com.tingyun.smartmistakebook.core.model.AssessmentEvidenceSnapshot
import com.tingyun.smartmistakebook.core.model.AssessmentSnapshotVerification
import com.tingyun.smartmistakebook.core.model.Attempt
import com.tingyun.smartmistakebook.core.model.CalibrationSnapshot
import com.tingyun.smartmistakebook.core.model.CalibrationSupport
import com.tingyun.smartmistakebook.core.model.EvidenceAttributionCertainty
import com.tingyun.smartmistakebook.core.model.EvidenceAttributionRole
import com.tingyun.smartmistakebook.core.model.KnowledgeEvidenceAttribution
import com.tingyun.smartmistakebook.core.model.LearnerSnapshot
import com.tingyun.smartmistakebook.core.model.LearningEvidence
import com.tingyun.smartmistakebook.core.model.LearningEvidenceDirection
import com.tingyun.smartmistakebook.core.model.LearningEvidenceReason
import com.tingyun.smartmistakebook.core.model.ProblemMemoryOutcome
import com.tingyun.smartmistakebook.core.model.PresentationProjectionState
import com.tingyun.smartmistakebook.core.model.StudyDayContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 合并重定向：把**已退役**知识点上的历史证据算到取代它的节点名下。
 *
 * **它消灭的失败**：内容侧合并两个重复知识点后，学生答错过的那个 `X` 上的证据无处可去——
 * `Y` 的掌握度凭空少一块，排程把学生早就练过的东西当没学过。而历史行**不可改写**
 * （改写会让重放不再可复现），所以只能在投影时解析。
 *
 * 三类行为各钉一个失败：
 * 1. **链式解析**：`X→Y` 之后 `Y→Z` 要跟到底，停在中途会把证据算到一个也已退役的节点上。
 * 2. **环与自指不致命**：映射来自内容包，坏内容不该让投影死循环。
 * 3. **空映射逐位不变**：今天全库 `superseded_by` 都是 NULL，所以引入这次解析**对既有数据
 *    必须是空操作**——否则等于悄悄改了所有人的掌握度。
 */
class KnowledgeNodeSuccessorsTest {

    // ---- 纯解析 ----

    @Test
    fun `no mapping leaves the id untouched`() {
        assertEquals("kc-a", KnowledgeNodeSuccessors.EMPTY.resolve("kc-a"))
    }

    @Test
    fun `follows a chain to the live node`() {
        // 真实数据里就有这种链：无氧呼吸-x2 → -x1 → 无氧呼吸
        val successors = KnowledgeNodeSuccessors(
            mapOf("x2" to "x1", "x1" to "root"),
        )
        assertEquals("root", successors.resolve("x2"))
        assertEquals("root", successors.resolve("x1"))
        assertEquals("root", successors.resolve("root"))
    }

    @Test
    fun `a cycle terminates instead of looping forever`() {
        val successors = KnowledgeNodeSuccessors(mapOf("a" to "b", "b" to "a"))
        // 不关心停在哪一个，只要求**返回**——坏内容不该让投影挂死
        val resolved = successors.resolve("a")
        assertTrue(resolved == "a" || resolved == "b")
    }

    @Test
    fun `a self reference is not a redirect`() {
        assertEquals("a", KnowledgeNodeSuccessors(mapOf("a" to "a")).resolve("a"))
    }

    // ---- 投影：合并并入 ----

    @Test
    fun `evidence on a retired node is credited to its successor`() {
        val result = LearningProjector().project(
            previous = LearnerSnapshot.empty("learner"),
            events = listOf(attempt("a-1", 1, "kc-old")),
            knownLedgerHeadSequence = 1,
            authoritativePresentationStates = mapOf(
                "presentation-a-1" to presentationState("presentation-a-1"),
            ),
            knowledgeNodeSuccessors = KnowledgeNodeSuccessors(mapOf("kc-old" to "kc-new")),
        )

        val states = result.snapshot.knowledgeMasteryStates
        assertTrue("证据必须落在存活节点上，实际：${states.keys}", "kc-new" in states.keys)
        assertTrue("已退役的节点下不该再出现新的掌握度", "kc-old" !in states.keys)
        assertEquals(
            "状态自带的 id 必须与它被记账的键一致，否则落库与读取会对不上",
            "kc-new",
            states.getValue("kc-new").knowledgeNodeId,
        )
    }

    @Test
    fun `without a mapping the evidence stays on the original node`() {
        val result = LearningProjector().project(
            previous = LearnerSnapshot.empty("learner"),
            events = listOf(attempt("a-1", 1, "kc-old")),
            knownLedgerHeadSequence = 1,
            authoritativePresentationStates = mapOf(
                "presentation-a-1" to presentationState("presentation-a-1"),
            ),
        )

        val states = result.snapshot.knowledgeMasteryStates
        assertTrue("默认空映射＝既有行为逐位不变", "kc-old" in states.keys)
        assertTrue("kc-new" !in states.keys)
    }

    @Test
    fun `a batch spanning both nodes accumulates onto the successor`() {
        // 一条挂在旧节点、一条挂在存活节点——两条证据都要累加到存活节点上，
        // 而不是各算各的再挑一个（那会丢掉先发生那条的作用）
        val result = LearningProjector().project(
            previous = LearnerSnapshot.empty("learner"),
            events = listOf(attempt("a-1", 1, "kc-old"), attempt("a-2", 2, "kc-new")),
            knownLedgerHeadSequence = 2,
            authoritativePresentationStates = mapOf(
                "presentation-a-1" to presentationState("presentation-a-1"),
                "presentation-a-2" to presentationState("presentation-a-2"),
            ),
            knowledgeNodeSuccessors = KnowledgeNodeSuccessors(mapOf("kc-old" to "kc-new")),
        )

        val merged = result.snapshot.knowledgeMasteryStates.getValue("kc-new")
        // 基线：同样的证据量，但只有一条、且从头就是存活节点
        val onlyOne = LearningProjector().project(
            previous = LearnerSnapshot.empty("learner"),
            events = listOf(attempt("a-1", 1, "kc-new")),
            knownLedgerHeadSequence = 1,
            authoritativePresentationStates = mapOf(
                "presentation-a-1" to presentationState("presentation-a-1"),
            ),
        ).snapshot.knowledgeMasteryStates.getValue("kc-new")

        assertEquals("两条证据都要算进去", 2.0, merged.evidenceMass, 1e-9)
        assertEquals("单条只积一份", 1.0, onlyOne.evidenceMass, 1e-9)
        assertTrue(
            "两条证据的掌握度必须高于只有一条",
            merged.masteryScore > onlyOne.masteryScore,
        )
    }

    // ---- 夹具（照 BlockingLearningCoreReviewTest 的写法，只保留本用例需要的字段） ----

    private fun presentationState(presentationId: String) = PresentationProjectionState(
        presentationId,
        asOfLedgerSequence = 0,
        memoryProjectionApplied = false,
    )

    private fun attempt(id: String, sequence: Long, knowledgeNodeId: String) = Attempt(
        attemptId = id,
        presentationId = "presentation-$id",
        responseOrdinal = 1,
        assessmentSnapshot = AssessmentEvidenceSnapshot(
            snapshotId = "snapshot-$id",
            assessmentItemId = "assessment-1",
            practiceUnitId = "unit-1",
            problemRevisionId = "revision-1",
            answerSpecId = "answer-1",
            itemFamilyId = "family-$id",
            sourceBundleId = "source-1",
            taxonomyVersion = "taxonomy-v1",
            verification = AssessmentSnapshotVerification.VERIFIED,
            calibration = CalibrationSnapshot(
                support = CalibrationSupport.SUPPORTED,
                sourceId = "calibration-source",
                version = "calibration-v1",
                validFromEpochMillis = 0,
                validUntilEpochMillis = 30L * 24 * 60 * 60 * 1000,
            ),
            attributions = listOf(
                KnowledgeEvidenceAttribution(
                    bindingId = "binding-$id",
                    knowledgeNodeId = knowledgeNodeId,
                    weight = 1.0,
                    basisRevisionId = "revision-1",
                    taxonomyVersion = "taxonomy-v1",
                    role = EvidenceAttributionRole.PRIMARY,
                    certainty = EvidenceAttributionCertainty.DIRECT,
                ),
            ),
            capturedAtEpochMillis = 0,
        ),
        evidence = LearningEvidence(
            direction = LearningEvidenceDirection.POSITIVE,
            weight = 1.0,
            reason = LearningEvidenceReason.INDEPENDENT_CORRECT,
        ),
        problemMemoryOutcome = ProblemMemoryOutcome.INDEPENDENT_RECALL,
        occurredAtEpochMillis = sequence * 1_000,
        durationSeconds = 60,
        studyDay = StudyDayContext(sequence, "Asia/Shanghai", 480),
        eventSequence = sequence,
    )
}
