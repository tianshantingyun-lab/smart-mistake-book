package com.tingyun.smartmistakebook.core.data.study

import com.tingyun.smartmistakebook.core.database.KnowledgeNodeSeedRecord
import com.tingyun.smartmistakebook.core.database.KnowledgeTeachingMaterialNodeBindingRecord
import com.tingyun.smartmistakebook.core.database.LibraryCatalogRow
import com.tingyun.smartmistakebook.core.database.TutorMessageRecord
import com.tingyun.smartmistakebook.core.database.TutorTurnResponseRecord
import com.tingyun.smartmistakebook.core.database.port.MasteryAggregateRecord
import com.tingyun.smartmistakebook.core.database.port.SubjectMasteryRecord
import com.tingyun.smartmistakebook.core.domain.MasteryWriteGate
import com.tingyun.smartmistakebook.core.model.TutorEvidenceDirection
import com.tingyun.smartmistakebook.core.model.TutorKnowledgeCode
import com.tingyun.smartmistakebook.core.model.TutorKnowledgeCodeRole
import com.tingyun.smartmistakebook.core.model.TutorToolCall
import com.tingyun.smartmistakebook.core.model.TutorToolName
import com.tingyun.smartmistakebook.core.model.TutorToolOutcome
import com.tingyun.smartmistakebook.core.model.TutorUnderstandingTier
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 讲题工具环 T6 `MASTERY_UPDATE` 的写侧接线（档2，spec
 * `2026-09-06-mastery-judgment-gate-evolution.md` §1）。
 *
 * 消灭的失败：runner 曾把 `hasBehavioralSupport` 硬编码为 false，而本地在讲题通道
 * 拿不到"学生懂了"的客观佐证——于是模型的 MASTERED 判断**永远被拒**，权重表里的
 * 0.18 档在生产里是死常数。本测试锁定新契约：MASTERED 的可核查性来自模型
 * rationale 里逐字引用的证据锚条数，由 [MasteryWriteGate.evidenceAnchorCount]
 * 从 rationale 数出后传门；不足门槛即拒并落 rejected 观察行。
 */
class RoomTutorToolRunnerTest {

    private val learnerId = "learner:local"

    private fun anchoredPort(nodeId: String = "kc-monotonicity") = FakeStudyDatabasePort().apply {
        knowledgeNodes += KnowledgeNodeSeedRecord(
            knowledgeNodeId = nodeId,
            stableCode = "math.function.monotonicity",
            subject = "MATH",
            displayName = "函数单调性",
            parentKnowledgeNodeId = null,
            taxonomyVersion = "cn-highschool-m1-v1",
            createdAtEpochMillis = 1_000,
            canonicalName = "函数单调性",
        )
    }

    /** 会话注册表：K1 = 当前题确认绑定的"函数单调性"（CONFIRMED 锚定等级）。 */
    private fun confirmedRegistry() = TutorKnowledgeCodeRegistry().apply {
        adopt(
            listOf(
                TutorKnowledgeCode(
                    knowledgeNodeId = "kc-monotonicity",
                    displayName = "函数单调性",
                    role = TutorKnowledgeCodeRole.CONFIRMED_BINDING,
                ),
            ),
        )
    }

    private fun context(
        subject: String = "MATH",
        registry: TutorKnowledgeCodeRegistry? = confirmedRegistry(),
    ) = RoomTutorToolRunner.Context(
        subject = subject,
        learnerId = learnerId,
        conversationId = "tutor-conv-1",
        knowledgeCodeRegistry = registry,
    )

    /**
     * 单一代号通道（D5）：terms[0] 是**代号**（默认 K1 = 确认绑定的 kc-monotonicity），
     * 原始 id 不进工具参数。
     */
    private fun masteryCall(
        rationale: String,
        understanding: TutorUnderstandingTier = TutorUnderstandingTier.MASTERED,
        direction: TutorEvidenceDirection = TutorEvidenceDirection.POSITIVE,
        terms: String = "K1",
    ) = TutorToolCall(
        tool = TutorToolName.MASTERY_UPDATE,
        rationale = rationale,
        terms = listOf(terms),
        direction = direction,
        understanding = understanding,
        confidence = 0.9,
    )

    @Test
    fun masteredWithTwoQuotedAnchorsIsAcceptedAtTheMasterTier() = runBlocking {
        // 两条锚都必须真出现在会话语料里——校核函数的引入没有取消这条路径，
        // 只是把"引号对上"升级为"引文确有其事"。
        val port = anchoredPort()
        port.tutorMessages += studentMessage("我把两边都乘以了2")
        port.tutorMessages += studentMessage("因为斜率相等所以平行")

        val outcome = RoomTutorToolRunner(port).run(
            masteryCall(
                rationale = "学生说\"我把两边都乘以了2\"，随后独立写出\"因为斜率相等所以平行\"。",
            ),
            sessionContext(),
        )

        assertTrue("expected accepted outcome but was $outcome", outcome.ok)
        val evidence = port.recordedChatEvidence.single()
        assertEquals(TutorEvidenceDirection.POSITIVE.name, evidence.direction)
        assertEquals(MasteryWriteGate.WEIGHT_MASTERED_POSITIVE, evidence.weight, 1e-9)
        assertNull(evidence.rejected_reason)
    }

    // ---- 单一代号通道验收（ADR 0001 / D5-D10）----

    @Test
    fun `a legal code write succeeds with the confirmed anchor class`() = runBlocking {
        // 验收①：合法代号（K1 = 当前题确认绑定）写入成功，anchor_class 正确（CONFIRMED，
        // 全权重）。
        val port = anchoredPort()
        port.tutorMessages += studentMessage("我把两边都乘以了2")
        port.tutorMessages += studentMessage("因为斜率相等所以平行")

        val outcome = RoomTutorToolRunner(port).run(
            masteryCall(
                rationale = "学生说\"我把两边都乘以了2\"，随后独立写出\"因为斜率相等所以平行\"。",
            ),
            sessionContext(),
        )

        assertTrue("expected accepted outcome but was $outcome", outcome.ok)
        val evidence = port.recordedChatEvidence.single()
        assertEquals("CONFIRMED", evidence.anchor_class)
        assertEquals(MasteryWriteGate.WEIGHT_MASTERED_POSITIVE, evidence.weight, 1e-9)
        assertEquals("kc-monotonicity", evidence.knowledge_node_id)
    }

    @Test
    fun `a fabricated code is structurally refused without an evidence row`() = runBlocking {
        // 验收②：编造代号（不在本会话已披露集合）结构性拒——协议错误路径，不进统一门、
        // 不落任何证据行（没有可挂靠审计的知识节点）。
        val port = anchoredPort()
        port.tutorMessages += studentMessage("我把两边都乘以了2")

        val outcome = RoomTutorToolRunner(port).run(
            masteryCall(
                rationale = "学生说\"我把两边都乘以了2\"。",
                understanding = TutorUnderstandingTier.CONFIDENT,
                terms = "K9",
            ),
            sessionContext(),
        )

        assertEquals(false, outcome.ok)
        assertEquals("invalid_knowledge_code", outcome.errorKind)
        assertTrue("结构性拒不得落任何证据行", port.recordedChatEvidence.isEmpty())
    }

    @Test
    fun `a cross-subject node behind a legal code is rejected as unanchored`() = runBlocking {
        // 验收③：代号合法但目标节点跨科目（数学会话里写 PHYSICS 节点）——KC 锚定的科目
        // 边界仍拒（gate 拒因 KNOWLEDGE_NODE_NOT_ANCHORED，落 rejected 观察行）。
        val port = FakeStudyDatabasePort().apply {
            knowledgeNodes += KnowledgeNodeSeedRecord(
                knowledgeNodeId = "kc-physics-momentum",
                stableCode = "physics.momentum",
                subject = "PHYSICS",
                displayName = "动量守恒",
                parentKnowledgeNodeId = null,
                taxonomyVersion = "cn-highschool-m1-v1",
                createdAtEpochMillis = 1_000,
                canonicalName = "动量守恒",
            )
        }
        val registry = TutorKnowledgeCodeRegistry().apply {
            adopt(
                listOf(
                    TutorKnowledgeCode(
                        knowledgeNodeId = "kc-physics-momentum",
                        displayName = "动量守恒",
                        role = TutorKnowledgeCodeRole.RETRIEVAL_CANDIDATE,
                    ),
                ),
            )
        }
        port.tutorMessages += studentMessage("动量守恒我推导过了")

        val outcome = RoomTutorToolRunner(port).run(
            masteryCall(
                rationale = "学生说\"动量守恒我推导过了\"。",
                understanding = TutorUnderstandingTier.CONFIDENT,
            ),
            context(registry = registry, subject = "MATH"),
        )

        assertEquals("rejected:KNOWLEDGE_NODE_NOT_ANCHORED", outcome.errorKind)
        assertEquals(
            "KNOWLEDGE_NODE_NOT_ANCHORED",
            port.recordedChatEvidence.single().rejected_reason,
        )
    }

    @Test
    fun `an unconfirmed candidate anchor write persists the halved weight`() = runBlocking {
        // 验收④：无确认锚（CANDIDATE）写入 → D9 降权安全垫在**写入时**施加：
        // 存库 weight = 档位权重 × 0.5，anchor_class 落库（投影按存库值逐位积分）。
        val port = anchoredPort()
        port.tutorMessages += studentMessage("我把两边都乘以了2")
        val registry = TutorKnowledgeCodeRegistry().apply {
            adopt(
                listOf(
                    TutorKnowledgeCode(
                        knowledgeNodeId = "kc-monotonicity",
                        displayName = "函数单调性",
                        role = TutorKnowledgeCodeRole.CONFIRMED_BINDING,
                    ),
                    TutorKnowledgeCode(
                        knowledgeNodeId = "kc-peifang",
                        displayName = "配方法",
                        role = TutorKnowledgeCodeRole.RETRIEVAL_CANDIDATE,
                    ),
                ),
            )
        }
        port.knowledgeNodes += knowledgeNode("kc-peifang", "配方法", "MATH")

        val outcome = RoomTutorToolRunner(port).run(
            masteryCall(
                rationale = "学生说\"我把两边都乘以了2\"。",
                understanding = TutorUnderstandingTier.CONFIDENT,
                terms = "K2",
            ),
            sessionContext(registry = registry),
        )

        assertTrue("expected accepted outcome but was $outcome", outcome.ok)
        val evidence = port.recordedChatEvidence.single()
        assertEquals("CANDIDATE", evidence.anchor_class)
        assertEquals(MasteryWriteGate.WEIGHT_CONFIDENT_POSITIVE * 0.5, evidence.weight, 1e-9)
    }

    @Test
    fun `a disclosed tool-found anchor write also persists the halved weight`() = runBlocking {
        // 验收④的 DISCLOSED 半边（工具发现节点）：与 CANDIDATE 同吃安全垫，
        // 区分只用于审计。
        val port = anchoredPort()
        port.tutorMessages += studentMessage("我把两边都乘以了2")
        val discovered = knowledgeNode("kc-discovered", "二次函数图像", "MATH")
        port.knowledgeNodes += discovered
        val registry = TutorKnowledgeCodeRegistry().apply {
            assign(TutorKnowledgeCode("kc-discovered", "二次函数图像", TutorKnowledgeCodeRole.TOOL_DISCOVERED))
        }

        val outcome = RoomTutorToolRunner(port).run(
            masteryCall(
                rationale = "学生说\"我把两边都乘以了2\"。",
                understanding = TutorUnderstandingTier.CONFIDENT,
                terms = "K1",
            ),
            sessionContext(registry = registry),
        )

        assertTrue("expected accepted outcome but was $outcome", outcome.ok)
        val evidence = port.recordedChatEvidence.single()
        assertEquals("DISCLOSED", evidence.anchor_class)
        assertEquals(MasteryWriteGate.WEIGHT_CONFIDENT_POSITIVE * 0.5, evidence.weight, 1e-9)
    }

    @Test
    fun `knowledgeReadReturnsCodesBoundaryAndMaterialDigestAndAppendsDisclosure`() = runBlocking {
        // D5 读侧：KNOWLEDGE_READ 返回代号+名称+边界前 80 字+绑定材料摘要；新发现节点
        // 追加披露（K2）；原始 id 不进结果文本（模型没有产生 id 的渠道）。
        val port = FakeStudyDatabasePort()
        val nodeA = knowledgeNode("kc-a", "函数单调性", "MATH").copy(
            boundaryMarkdown = "讨论函数在区间上的增减性；不含导数应用题。",
        )
        val nodeB = knowledgeNode("kc-b", "导数与切线", "MATH").copy(
            boundaryMarkdown = "导数的几何意义与切线方程。",
        )
        port.recallCandidates += nodeA
        port.recallCandidates += nodeB
        port.teachingMaterials += teachingMaterial("mat-a", "kc-a")
        port.materialNodeBindings += KnowledgeTeachingMaterialNodeBindingRecord(
            materialId = "mat-a",
            knowledgeNodeId = "kc-a",
            role = "PRIMARY",
        )
        val registry = TutorKnowledgeCodeRegistry().apply {
            adopt(listOf(TutorKnowledgeCode("kc-a", "函数单调性", TutorKnowledgeCodeRole.CONFIRMED_BINDING)))
        }

        // 两个检索词各锚一个召回节点：路由 = v1 生产形状（裸 B 路 limit=5，B512→select
        // 统一路由已回滚，见 KD-24）——fake port 按插入序返回候选，两个节点都进 top-5。
        val outcome = RoomTutorToolRunner(port)
            .run(knowledgeReadCall(terms = listOf("单调性", "切线")), context(registry = registry))

        assertTrue("expected ok outcome but was $outcome", outcome.ok)
        val text = outcome.summaryMarkdown
        assertTrue("已披露节点保持原码: $text", text.contains("K1. 函数单调性"))
        assertTrue("新发现节点追加披露: $text", text.contains("K2. 导数与切线"))
        assertTrue("边界前 80 字进结果: $text", text.contains("讨论函数在区间上的增减性"))
        assertTrue("绑定材料摘要进结果: $text", text.contains("材料："))
        assertFalse("原始 id 不进结果文本: $text", text.contains("kc-a") || text.contains("kc-b"))
        assertEquals(setOf("K1", "K2"), registry.disclosedCodes())
        assertEquals("K2", registry.codeFor("kc-b"))
        assertEquals(TutorKnowledgeCodeRole.TOOL_DISCOVERED, registry.resolve("K2")?.role)
        // 结果仍受单工具 2k 预算约束。
        assertTrue(text.length <= TutorToolOutcome.MAX_TOOL_RESULT_CHARS)
    }

    @Test
    fun masteredWithoutAnchorsIsRejectedAndStillAudited() = runBlocking {
        val port = anchoredPort()

        val outcome = RoomTutorToolRunner(port).run(
            masteryCall(rationale = "看起来学生已经掌握了这个知识点。"),
            context(),
        )

        assertEquals(false, outcome.ok)
        assertEquals("rejected:MASTERED_WITHOUT_EVIDENCE_ANCHOR", outcome.errorKind)
        // 被拒 ≠ 删除：观察行落库（weight=0、带拒因），不静默丢弃。
        val rejected = port.recordedChatEvidence.single()
        assertEquals(0.0, rejected.weight, 1e-9)
        assertEquals("MASTERED_WITHOUT_EVIDENCE_ANCHOR", rejected.rejected_reason)
        assertNotNull(rejected.rejected_at_epoch_millis)
    }

    @Test
    fun aBareClaimOfUnderstandingDoesNotCountAsAnAnchor() = runBlocking {
        val port = anchoredPort()

        val outcome = RoomTutorToolRunner(port).run(
            masteryCall(rationale = "学生说\"懂了\"，也说了\"会了\"。"),
            context(),
        )

        assertEquals(false, outcome.ok)
        assertEquals("rejected:MASTERED_WITHOUT_EVIDENCE_ANCHOR", outcome.errorKind)
    }

    @Test
    fun confidentNeedsOneVerifiedAnchor() = runBlocking {
        // 2026-09-13 起 CONFIDENT 不再豁免锚底线（开放式作答只能靠模型语义判断，
        // 至少得引用到一处学生真说过的话）。有锚 → 照旧按 0.15 入库；
        // 无锚 → 拒写（下方另一个用例覆盖）。
        val port = anchoredPort()
        port.tutorMessages += studentMessage("我把负号漏掉了")

        val outcome = RoomTutorToolRunner(port).run(
            masteryCall(
                rationale = "学生说\"我把负号漏掉了\"，这次自己纠正了。",
                understanding = TutorUnderstandingTier.CONFIDENT,
            ),
            context(),
        )

        assertTrue("expected accepted outcome but was $outcome", outcome.ok)
        assertEquals(
            MasteryWriteGate.WEIGHT_CONFIDENT_POSITIVE,
            port.recordedChatEvidence.single().weight,
            1e-9,
        )
    }

    @Test
    fun negativeLapseNeedsNoAnchor() = runBlocking {
        val port = anchoredPort()

        val outcome = RoomTutorToolRunner(port).run(
            masteryCall(
                rationale = "学生把符号搞反了。",
                understanding = TutorUnderstandingTier.STRUGGLING,
                direction = TutorEvidenceDirection.NEGATIVE,
            ),
            context(),
        )

        assertTrue("expected accepted outcome but was $outcome", outcome.ok)
        assertNull(port.recordedChatEvidence.single().rejected_reason)
    }

    // ---- 客观作答交叉核对（研究 tutor-evidence-gate §3.2）----

    @Test
    fun aPositiveClaimIsRejectedWhenTheStudentJustMissedTheCheckQuestion() = runBlocking {
        // 消灭的失败：模型对着学生刚答错的检查题判 POSITIVE，口头声明压过
        // 本地行为证据写进掌握度——runner 此前恒传 false，从不做这个核对。
        val port = anchoredPort()
        port.tutorTurnResponses += turnResponse(cycleOrdinal = 1, selectionWasCorrect = false)

        val outcome = RoomTutorToolRunner(port).run(
            masteryCall(rationale = "学生独立完成了这一步。", understanding = TutorUnderstandingTier.CONFIDENT),
            sessionContext(),
        )

        assertEquals(false, outcome.ok)
        assertEquals("rejected:OBJECTIVE_ANSWER_CONTRADICTS_POSITIVE", outcome.errorKind)
        // 被拒 ≠ 删除：降级为观察记录，保留拒因与时刻。
        val rejected = port.recordedChatEvidence.single()
        assertEquals(0.0, rejected.weight, 1e-9)
        assertEquals("OBJECTIVE_ANSWER_CONTRADICTS_POSITIVE", rejected.rejected_reason)
    }

    @Test
    fun theCrossCheckOutranksTheEvidenceAnchorRoute() = runBlocking {
        // 锁死优先级：模型不能靠多引用两个片段绕开学生的错误作答。
        val port = anchoredPort()
        port.tutorTurnResponses += turnResponse(cycleOrdinal = 1, selectionWasCorrect = false)

        val outcome = RoomTutorToolRunner(port).run(
            masteryCall(
                rationale = "学生说\"我把两边都乘以了2\"，随后独立写出\"因为斜率相等所以平行\"。",
                understanding = TutorUnderstandingTier.MASTERED,
            ),
            sessionContext(),
        )

        assertEquals("rejected:OBJECTIVE_ANSWER_CONTRADICTS_POSITIVE", outcome.errorKind)
    }

    @Test
    fun aCorrectCheckAnswerDoesNotBlockAPositiveClaim() = runBlocking {
        // 答对了检查题不构成阻碍；但正向仍要有 ≥1 条已核实引文锚（2026-09-13 底线），
        // 模型引用学生所选选项文本即满足——这是本会话真实存在过的文本。
        val port = anchoredPort()
        port.tutorTurnResponses += turnResponse(cycleOrdinal = 1, selectionWasCorrect = true)

        val outcome = RoomTutorToolRunner(port).run(
            masteryCall(
                rationale = "学生选了\"选项 A\"，这一步独立完成。",
                understanding = TutorUnderstandingTier.CONFIDENT,
            ),
            sessionContext(),
        )

        assertTrue("expected accepted outcome but was $outcome", outcome.ok)
    }

    @Test
    fun lastCyclesMistakeDoesNotBlockThisCyclesPositiveClaim() = runBlocking {
        // 上一轮的答错正是重教的理由。永久计入会让重教后答对也洗不掉，
        // 门成为不可达的死门（与档2 修的 0.18 死常数同类）。
        val port = anchoredPort()
        port.tutorTurnResponses += turnResponse(cycleOrdinal = 1, selectionWasCorrect = false)
        port.tutorTurnResponses += turnResponse(cycleOrdinal = 2, selectionWasCorrect = true)

        val outcome = RoomTutorToolRunner(port).run(
            masteryCall(
                rationale = "学生选了\"选项 A\"，这一步独立完成。",
                understanding = TutorUnderstandingTier.CONFIDENT,
            ),
            sessionContext(cycleOrdinal = 2),
        )

        assertTrue("expected accepted outcome but was $outcome", outcome.ok)
    }

    @Test
    fun aNegativeClaimIsNotBlockedByTheStudentsWrongAnswer() = runBlocking {
        // 方向一致不构成冲突：此时拒写会把真实的下滑信号一起丢掉。
        val port = anchoredPort()
        port.tutorTurnResponses += turnResponse(cycleOrdinal = 1, selectionWasCorrect = false)

        val outcome = RoomTutorToolRunner(port).run(
            masteryCall(
                rationale = "学生把符号搞反了。",
                understanding = TutorUnderstandingTier.STRUGGLING,
                direction = TutorEvidenceDirection.NEGATIVE,
            ),
            sessionContext(),
        )

        assertTrue("expected accepted outcome but was $outcome", outcome.ok)
    }

    @Test
    fun withoutASessionThereIsNothingToCrossCheck() = runBlocking {
        // 无会话上下文（Lobby 派遣/测试直调）时没有会话语料，因此：
        // ①不引入"客观作答冲突"这个拒因（那需要真的存在客观作答）；
        // ②但空语料下任何正向都拿不到引文锚，按 2026-09-13 的正向底线拒写——
        //   拒的是"无法核查"，不是"与行为证据冲突"。
        val port = anchoredPort()
        port.tutorTurnResponses += turnResponse(cycleOrdinal = 1, selectionWasCorrect = false)

        val outcome = RoomTutorToolRunner(port).run(
            masteryCall(rationale = "学生独立完成了这一步。", understanding = TutorUnderstandingTier.CONFIDENT),
            context(),
        )

        assertEquals(false, outcome.ok)
        assertEquals("rejected:POSITIVE_WITHOUT_EVIDENCE_ANCHOR", outcome.errorKind)
    }

    // ---- 开放式（纯文字）作答：本地可核对性 ----

    @Test
    fun proseOnlySessionAcceptsAPositiveThatQuotesTheStudentVerbatim() = runBlocking {
        // 开放式作答没有机判选项，对错只能靠模型语义判断；本地唯一能机械执行的要求是
        // "引文必须真出现在学生说过的话里"。学生文字落库后这条要求才成立。
        val port = anchoredPort()
        port.tutorMessages += studentMessage("我觉得是先配方再开方")

        val outcome = RoomTutorToolRunner(port).run(
            masteryCall(
                rationale = "学生说\"我觉得是先配方再开方\"，这一步他自己想到了。",
                understanding = TutorUnderstandingTier.CONFIDENT,
            ),
            context(),
        )

        assertTrue("expected accepted outcome but was $outcome", outcome.ok)
    }

    @Test
    fun proseOnlySessionRejectsAPositiveThatQuotesNothingReal() = runBlocking {
        // 反向：词句不在学生的原话里（改写/编造），本地核对抓得到 → 拒写。
        val port = anchoredPort()
        port.tutorMessages += studentMessage("我觉得是先配方再开方")

        val outcome = RoomTutorToolRunner(port).run(
            masteryCall(
                rationale = "学生说\"我很清楚这是余弦定理\"，思路完整。",
                understanding = TutorUnderstandingTier.CONFIDENT,
            ),
            context(),
        )

        assertEquals(false, outcome.ok)
        assertEquals("rejected:POSITIVE_WITHOUT_EVIDENCE_ANCHOR", outcome.errorKind)
    }

    // ---- 证据锚真实性核对（方向A）----

    @Test
    fun masteredIsRejectedWhenTheQuotedAnchorsAreFabricated() = runBlocking {
        // 消灭的失败：档2 只数引号，模型写 `"因为""所以"` 就凑够 2 条锚并以
        // MASTERED 写入。核对后，引文必须是本会话里学生真产出过的文本。
        val port = anchoredPort()
        port.tutorMessages += studentMessage("我把负号漏掉了")
        port.tutorMessages += studentMessage("因为斜率相等所以平行")

        val outcome = RoomTutorToolRunner(port).run(
            masteryCall(
                rationale = "学生说\"我把正号写错了\"，随后\"因为截距相等所以平行\"。",
                understanding = TutorUnderstandingTier.MASTERED,
            ),
            sessionContext(),
        )

        assertEquals(false, outcome.ok)
        assertEquals("rejected:MASTERED_WITHOUT_EVIDENCE_ANCHOR", outcome.errorKind)
        // 被拒 ≠ 删除：落观察行供审计。
        assertEquals(
            "MASTERED_WITHOUT_EVIDENCE_ANCHOR",
            port.recordedChatEvidence.single().rejected_reason,
        )
    }

    @Test
    fun masteredIsAcceptedWhenTheQuotedAnchorsAreVerbatim() = runBlocking {
        val port = anchoredPort()
        port.tutorMessages += studentMessage("我把负号漏掉了")
        port.tutorMessages += studentMessage("因为斜率相等所以平行")

        val outcome = RoomTutorToolRunner(port).run(
            masteryCall(
                rationale = "学生说\"我把负号漏掉了\"，随后\"因为斜率相等所以平行\"。",
                understanding = TutorUnderstandingTier.MASTERED,
            ),
            sessionContext(),
        )

        assertTrue("expected accepted outcome but was $outcome", outcome.ok)
        assertEquals(
            MasteryWriteGate.WEIGHT_MASTERED_POSITIVE,
            port.recordedChatEvidence.single().weight,
            1e-9,
        )
    }

    @Test
    fun aVerbatimStudentChoiceCountsAsAnAnchorWithoutAnyMessage() = runBlocking {
        // 学生的客观作答属于档1 规范里的"可观察行为"，与消息原文同为可核查语料。
        val port = anchoredPort()
        port.tutorTurnResponses += turnResponse(
            cycleOrdinal = 1,
            selectionWasCorrect = true,
            selectedChoiceMarkdown = "因为斜率相等所以平行",
        )

        val outcome = RoomTutorToolRunner(port).run(
            masteryCall(
                rationale = "学生选了\"因为斜率相等所以平行\"，随后\"我把负号漏掉了\"。",
                understanding = TutorUnderstandingTier.MASTERED,
            ),
            sessionContext(),
        )

        // 两条锚里只有一条能在作答语料中找到，仍不足门槛。
        assertEquals("rejected:MASTERED_WITHOUT_EVIDENCE_ANCHOR", outcome.errorKind)
    }

    @Test
    fun assistantMessagesCannotCorroborateTheModelsOwnClaim() = runBlocking {
        // 模型不能拿自己说过的话当证据：ASSISTANT 行不进入可核查语料。
        val port = anchoredPort()
        port.tutorMessages += studentMessage("我把负号漏掉了", role = "ASSISTANT")

        val outcome = RoomTutorToolRunner(port).run(
            masteryCall(
                rationale = "学生说\"我把负号漏掉了\"，随后\"因为斜率相等所以平行\"。",
                understanding = TutorUnderstandingTier.MASTERED,
            ),
            sessionContext(),
        )

        assertEquals("rejected:MASTERED_WITHOUT_EVIDENCE_ANCHOR", outcome.errorKind)
    }

    @Test
    fun lowerTiersNeedOneFaithfulQuoteNotAPerfectParaphrase() = runBlocking {
        // 2026-09-13 起核对推广到所有正向档（底线 ≥1 条已核实锚）。标准是"逐字"，
        // 不是"意思相近"：改写过的引文不算，忠实引用一处即可通过——日常讲题只要
        // 模型真的引了学生的话就写得进去。
        val port = anchoredPort()
        port.tutorMessages += studentMessage("我把负号漏掉了")

        val paraphrased = RoomTutorToolRunner(port).run(
            masteryCall(
                rationale = "学生说\"我把符号问题处理好了\"。",
                understanding = TutorUnderstandingTier.CONFIDENT,
            ),
            sessionContext(),
        )
        assertEquals(false, paraphrased.ok)
        assertEquals("rejected:POSITIVE_WITHOUT_EVIDENCE_ANCHOR", paraphrased.errorKind)

        val verbatim = RoomTutorToolRunner(port).run(
            masteryCall(
                rationale = "学生说\"我把负号漏掉了\"，现在自己找到了。",
                understanding = TutorUnderstandingTier.CONFIDENT,
            ),
            sessionContext(),
        )
        assertTrue("expected accepted outcome but was $verbatim", verbatim.ok)
    }

    // ---- 掌握情况深挖：清单 / 聚焦 / 截断 / 扩展预算 ----

    @Test
    fun masteryReadListsKnowledgeNodesWeakestFirstWithNames() = runBlocking {
        // 消灭的失败：旧实现按 practice_unit 字典序取前 24 个 binding 行，
        // 输出的还是 practiceUnitId——模型既看不到知识点名，也看不到真正的薄弱项。
        val port = anchoredPort()
        port.publishSubjectMastery(
            "MATH",
            listOf(
                masteryRow(nodeId = "kc-strong", name = "函数奇偶性", lowerBound = 0.82, status = "MASTERED"),
                masteryRow(nodeId = "kc-weak", name = "函数单调性", lowerBound = 0.21, status = "LEARNING"),
            ),
        )

        val outcome = RoomTutorToolRunner(port).run(masteryReadCall(), context())

        assertTrue("expected ok outcome but was $outcome", outcome.ok)
        val text = outcome.summaryMarkdown
        assertTrue("expected knowledge-node names, got: $text", text.contains("函数单调性"))
        assertTrue("expected the other node too, got: $text", text.contains("函数奇偶性"))
        assertTrue(
            "weakest node must come first, got: $text",
            text.indexOf("函数单调性") < text.indexOf("函数奇偶性"),
        )
    }

    @Test
    fun masteryReadReportsNodesWithoutEvidenceAsARemainder() = runBlocking {
        // 只有有证据的节点会成行，所以必须给出"还有多少没证据"，否则模型会把
        // 短清单当成整个科目的全貌。
        val port = anchoredPort()
        port.publishSubjectMastery("MATH", listOf(masteryRow(nodeId = "kc-1", name = "函数单调性")))
        port.reviewableKnowledgeNodeCount = 97

        val outcome = RoomTutorToolRunner(port).run(masteryReadCall(), context())

        assertTrue(outcome.summaryMarkdown, outcome.summaryMarkdown.contains("另有 96 个"))
    }

    @Test
    fun masteryReadFocusesOnResolvedNodesAndAddsHistoryAggregates() = runBlocking {
        val port = anchoredPort()
        port.publishSubjectMastery(
            "MATH",
            listOf(
                masteryRow(nodeId = "kc-weak", name = "函数单调性", lowerBound = 0.21),
                masteryRow(nodeId = "kc-other", name = "函数奇偶性", lowerBound = 0.33),
            ),
        )
        port.recallCandidates += knowledgeNode("kc-weak", "函数单调性", "MATH")
        port.masteryAggregates += MasteryAggregateRecord(
            knowledgeNodeId = "kc-weak",
            independentCorrectCount = 5,
            independentCorrectItemFamilyCount = 3,
            independentCorrectStudyDayCount = 4,
            lastIndependentCorrectAtEpochMillis = null,
            independentErrorCount = 2,
            lastIndependentErrorAtEpochMillis = null,
            acceptedModelEvidenceCount = 3,
            rejectedModelEvidenceCount = 1,
            lastAcceptedModelEvidenceAtEpochMillis = null,
        )

        val outcome = RoomTutorToolRunner(port).run(
            masteryReadCall(terms = listOf("函数单调性")),
            context(),
        )

        val text = outcome.summaryMarkdown
        assertTrue("focused node missing: $text", text.contains("函数单调性"))
        assertTrue("non-focused node leaked in: $text", !text.contains("函数奇偶性"))
        assertTrue("independent-correct aggregate missing: $text", text.contains("独立答对 5 次"))
        assertTrue("item-family breadth missing: $text", text.contains("3 个题目族"))
        assertTrue("independent-error aggregate missing: $text", text.contains("独立错误 2 次"))
        assertTrue("model-evidence split missing: $text", text.contains("接受 3 条、被拒 1 条"))
    }

    @Test
    fun masteryReadFocusReportsResolvedNodesThatHaveNoEvidenceYet() = runBlocking {
        // 学生问的知识点确实存在、但本地还没有任何证据——这必须说出来，
        // 而不是返回一个空清单让模型以为"没有这个东西"。
        val port = anchoredPort()
        port.publishSubjectMastery("MATH", listOf(masteryRow(nodeId = "kc-other", name = "函数奇偶性")))
        port.recallCandidates += knowledgeNode("kc-unmeasured", "导数与切线", "MATH")

        val outcome = RoomTutorToolRunner(port).run(
            masteryReadCall(terms = listOf("导数与切线")),
            context(),
        )

        assertTrue(outcome.summaryMarkdown, outcome.summaryMarkdown.contains("导数与切线"))
        assertTrue(outcome.summaryMarkdown, outcome.summaryMarkdown.contains("尚无学习证据"))
    }

    @Test
    fun masteryReadNeverLeaksAnotherSubject() = runBlocking {
        // 科目是披露边界：物理的掌握情况绝不能出现在数学会话里。
        val port = anchoredPort()
        port.publishSubjectMastery("MATH", listOf(masteryRow(nodeId = "kc-math", name = "函数单调性")))
        port.publishSubjectMastery("PHYSICS", listOf(masteryRow(nodeId = "kc-physics", name = "动量守恒")))

        val outcome = RoomTutorToolRunner(port).run(masteryReadCall(), context())

        assertTrue(outcome.summaryMarkdown, outcome.summaryMarkdown.contains("函数单调性"))
        assertTrue(
            "another subject's mastery leaked: ${outcome.summaryMarkdown}",
            !outcome.summaryMarkdown.contains("动量守恒"),
        )
    }

    @Test
    fun masteryReadWithoutASubjectFailsClosed() = runBlocking {
        val port = anchoredPort()
        port.publishSubjectMastery("MATH", listOf(masteryRow(nodeId = "kc-math", name = "函数单调性")))

        val outcome = RoomTutorToolRunner(port).run(
            masteryReadCall(),
            context().copy(subject = null),
        )

        assertEquals(false, outcome.ok)
        assertEquals("no_subject", outcome.errorKind)
    }

    @Test
    fun anOversizedListIsTruncatedWithANote() = runBlocking {
        // 不限条数，所以字符预算就是真边界；但截断必须说出来，否则模型会把
        // 被砍掉的清单当成整个科目。
        val port = anchoredPort()
        port.publishSubjectMastery(
            "MATH",
            (1..400).map { index ->
                masteryRow(nodeId = "kc-$index", name = "知识点${"%03d".format(index)}")
            },
        )

        val outcome = RoomTutorToolRunner(port).run(masteryReadCall(), context())

        assertTrue("expected ok outcome but was $outcome", outcome.ok)
        assertTrue(
            "truncation must be announced: ${outcome.summaryMarkdown.takeLast(200)}",
            outcome.summaryMarkdown.contains("已截断"),
        )
        assertTrue(
            "truncated result must stay inside the default budget",
            outcome.summaryMarkdown.length <= TutorToolOutcome.MAX_TOOL_RESULT_CHARS,
        )
    }

    @Test
    fun theExtendedBudgetCarriesMoreRowsThanTheDefaultOne() = runBlocking {
        val port = anchoredPort()
        port.publishSubjectMastery(
            "MATH",
            (1..400).map { index ->
                masteryRow(nodeId = "kc-$index", name = "知识点${"%03d".format(index)}")
            },
        )

        val plain = RoomTutorToolRunner(port).run(masteryReadCall(), context())
        val extended = RoomTutorToolRunner(port).run(
            masteryReadCall(extendedResult = true),
            context(),
        )

        assertTrue(
            "extended result should carry more: ${plain.summaryMarkdown.length} vs " +
                "${extended.summaryMarkdown.length}",
            extended.summaryMarkdown.length > plain.summaryMarkdown.length,
        )
        assertTrue(
            "extended result must still respect the ceiling",
            extended.summaryMarkdown.length <= TutorToolOutcome.MAX_TOOL_RESULT_CHARS_EXTENDED,
        )
    }

    @Test
    fun aRoundThatAlreadyUsedTheExtendedBudgetFallsBackToTheDefaultOne() = runBlocking {
        // 轮内只放一次：三个并发扩展请求会在下一轮 prompt 里堆到 18k。
        val port = anchoredPort()
        port.publishSubjectMastery(
            "MATH",
            (1..400).map { index ->
                masteryRow(nodeId = "kc-$index", name = "知识点${"%03d".format(index)}")
            },
        )

        val outcome = RoomTutorToolRunner(port).run(
            masteryReadCall(extendedResult = true),
            context().copy(allowsExtendedResult = false),
        )

        assertTrue(
            "denied extended budget must fall back to the default ceiling",
            outcome.summaryMarkdown.length <= TutorToolOutcome.MAX_TOOL_RESULT_CHARS,
        )
    }

    // ---- 错题本读取：产出形态随本轮披露范围（F5）----

    @Test
    fun aRoundThatDoesNotDiscloseOtherQuestionsGetsNoNotebookTitles() = runBlocking {
        // 消灭的失败：无题轮的 NOTEBOOK_READ 把错题本里**别的题**的标题与科目逐条列进下一轮
        // 请求，而本轮的披露集合把 RELATED_QUESTION_CANDIDATES / CONFIRMED_QUESTION_DOCUMENT
        // 列为禁止——清单少报了一个真实出网的类目。不扩披露集合（那是用户的裁定），改结果形态：
        // 只给条数与检索词。
        val port = anchoredPort()
        port.libraryRows += libraryRow(title = "二次函数最值综合题", subject = "MATH")
        port.libraryRows += libraryRow(title = "向量数量积的应用", subject = "MATH")

        val outcome = RoomTutorToolRunner(port).run(
            notebookRead(terms = listOf("二次函数")),
            context(),
        )

        assertTrue("expected ok outcome but was $outcome", outcome.ok)
        val text = outcome.summaryMarkdown
        assertTrue("条数必须留着，否则模型不知道有没有命中: $text", text.contains("1"))
        assertFalse("别的题的标题不得随请求下发: $text", text.contains("二次函数最值综合题"))
        assertFalse("没命中的那条更不该出现: $text", text.contains("向量数量积的应用"))
        assertFalse("条目的可识别字段（科目）同样属于该披露类目: $text", text.contains("MATH"))
    }

    @Test
    fun aRoundThatDisclosesTheCandidateMenuMayStillNameTheEntries() = runBlocking {
        // 反向：本轮披露集合覆盖了候选菜单（学生显式带题 / 本地检索到候选）时，别的题的标题是
        // **已披露**的那一类内容，逐条点名仍是被许可的能力——闸门不能修成一律不列。
        val port = anchoredPort()
        port.libraryRows += libraryRow(title = "二次函数最值综合题", subject = "MATH")

        val outcome = RoomTutorToolRunner(port).run(
            notebookRead(terms = listOf("二次函数")),
            context().copy(roundDisclosesQuestionCandidates = true),
        )

        assertTrue("expected ok outcome but was $outcome", outcome.ok)
        assertTrue(
            "覆盖了候选菜单时条目标题应当照旧可读: ${outcome.summaryMarkdown}",
            outcome.summaryMarkdown.contains("二次函数最值综合题"),
        )
    }

    private fun notebookRead(
        terms: List<String> = emptyList(),
    ) = TutorToolCall(
        tool = TutorToolName.NOTEBOOK_READ,
        rationale = "学生想找错题本里的题",
        terms = terms,
    )

    private fun libraryRow(
        title: String,
        subject: String = "MATH",
        problemMarkdown: String = "求函数的最值。",
    ) = LibraryCatalogRow(
        entryId = "entry-${title.hashCode()}",
        title = title,
        problemMarkdown = problemMarkdown,
        subject = subject,
        createdAtEpochMillis = 1_000,
        updatedAtEpochMillis = 1_000,
        nextReviewAtEpochMillis = null,
        retrievability = null,
    )

    private fun masteryReadCall(
        terms: List<String> = emptyList(),
        extendedResult: Boolean = false,
    ) = TutorToolCall(
        tool = TutorToolName.MASTERY_READ,
        rationale = "需要看这个知识点的掌握情况",
        terms = terms,
        extendedResult = extendedResult,
    )

    private fun masteryRow(
        nodeId: String,
        name: String,
        lowerBound: Double = 0.30,
        status: String = "LEARNING",
    ) = SubjectMasteryRecord(
        knowledgeNodeId = nodeId,
        displayName = name,
        granularity = "ATOMIC",
        nodeKind = "CONCEPT",
        probabilityIndependentCorrect = lowerBound,
        lowerBoundIndependentCorrect = lowerBound,
        evidenceMass = 1.0,
        status = status,
        lastEvidenceAtEpochMillis = null,
        lastEvidenceDirection = null,
        lastIndependentErrorAtEpochMillis = null,
        boundQuestionCount = 1,
    )

    /**
     * 模拟生产 B 路召回的输出形态：召回 SQL 只返回 trusted 状态（CURATED /
     * SOURCE_GROUNDED / USER_CONFIRMED）的非退役节点。KNOWLEDGE_READ / MASTERY_READ
     * 聚焦解析 = v1 生产形状（裸 B 路，B512→select 统一路由已回滚，见 KD-24）——
     * 工具执行器直接消费这份候选，不再过 A 路精排。
     */
    private fun knowledgeNode(nodeId: String, name: String, subject: String) = KnowledgeNodeSeedRecord(
        knowledgeNodeId = nodeId,
        stableCode = nodeId,
        subject = subject,
        displayName = name,
        parentKnowledgeNodeId = null,
        taxonomyVersion = "taxonomy-v1",
        createdAtEpochMillis = 1_000,
        canonicalName = name,
        nodeKind = "CONCEPT",
        granularity = "ATOMIC",
        verificationStatus = "CURATED",
    )

    private fun studentMessage(body: String, role: String = "STUDENT") = TutorMessageRecord(
        messageId = "message-${body.hashCode()}-$role",
        conversationId = CONVERSATION_ID,
        ordinal = 1,
        role = role,
        bodyMarkdown = body,
        status = "COMPLETED",
        logicalOperationId = null,
        replyToMessageId = null,
        createdAtEpochMillis = 2_000,
        completedAtEpochMillis = 2_000,
        errorCode = null,
    )

    private fun sessionContext(
        cycleOrdinal: Int = 1,
        registry: TutorKnowledgeCodeRegistry? = confirmedRegistry(),
    ) = context(registry = registry).copy(
        tutorSessionId = TUTOR_SESSION_ID,
        conversationId = CONVERSATION_ID,
        cycleOrdinal = cycleOrdinal,
    )

    private fun knowledgeReadCall(
        terms: List<String> = listOf("单调性"),
    ) = TutorToolCall(
        tool = TutorToolName.KNOWLEDGE_READ,
        rationale = "看这道题相关的知识点",
        terms = terms,
    )

    private fun teachingMaterial(materialId: String, nodeId: String) =
        com.tingyun.smartmistakebook.core.database.KnowledgeTeachingMaterialRecord(
            materialId = materialId,
            stableCode = "mat-$nodeId",
            subject = "MATH",
            materialType = "METHOD_MODEL",
            title = "方法模型：${nodeId}",
            summaryMarkdown = "该材料讲解对应知识点的核心方法与典型误区。",
            applicabilityMarkdown = "适用于该知识点的讲题。",
            contentMarkdown = "正文。",
            boundaryMarkdown = "边界。",
            derivationKind = "EXTRACT",
            sourceId = "source-1",
            sourceLocator = "locator-1",
            contentFingerprint = "fp-$materialId",
            reviewedAtEpochMillis = 1_000,
        )

    private fun turnResponse(
        cycleOrdinal: Int,
        selectionWasCorrect: Boolean,
        selectedChoiceMarkdown: String = "选项 A",
    ) = TutorTurnResponseRecord(
        sessionId = TUTOR_SESSION_ID,
        questionDocumentId = "question-1",
        revisionNumber = 1,
        cycleOrdinal = cycleOrdinal,
        turnOrdinal = 1,
        diagnosticStemMarkdown = "下列哪个选项正确？",
        selectedChoiceId = "choice-a",
        selectedChoiceMarkdown = selectedChoiceMarkdown,
        selectionWasCorrect = selectionWasCorrect,
        feedbackMarkdown = "解析。",
        requestedMove = null,
        solutionRevealed = false,
        choiceSubmittedAtEpochMillis = 2_000,
        submittedAtEpochMillis = 2_000,
        updatedAtEpochMillis = 2_000,
    )

    private companion object {
        const val TUTOR_SESSION_ID = "tutor-session-1"
        const val CONVERSATION_ID = "tutor-conv-1"
    }
}
