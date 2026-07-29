package com.tingyun.smartmistakebook.core.data.mistake

import com.tingyun.smartmistakebook.core.database.MistakeRecord
import com.tingyun.smartmistakebook.core.domain.ProblemOrganizationRelationKey
import com.tingyun.smartmistakebook.core.model.BindingAcceptanceSource
import com.tingyun.smartmistakebook.core.model.AtomicKnowledgeSuggestion
import com.tingyun.smartmistakebook.core.model.ClassificationDimension
import com.tingyun.smartmistakebook.core.model.ContentBlock
import com.tingyun.smartmistakebook.core.model.KnowledgeBaseNodeContext
import com.tingyun.smartmistakebook.core.model.KnowledgeNodeGranularity
import com.tingyun.smartmistakebook.core.model.KnowledgeNodeKind
import com.tingyun.smartmistakebook.core.model.KnowledgeNodeVerificationStatus
import com.tingyun.smartmistakebook.core.model.KnowledgeGroundingRequest
import com.tingyun.smartmistakebook.core.model.ProblemClassificationSuggestion
import com.tingyun.smartmistakebook.core.model.ProblemErrorAttributionCandidate
import com.tingyun.smartmistakebook.core.model.ProblemErrorAttributionResolutionStatus
import com.tingyun.smartmistakebook.core.model.ProblemErrorEvidenceKind
import com.tingyun.smartmistakebook.core.model.ProblemErrorEvidenceRef
import com.tingyun.smartmistakebook.core.model.ProblemOrganizationInput
import com.tingyun.smartmistakebook.core.model.ProblemOrganizationOutput
import com.tingyun.smartmistakebook.core.model.ProblemOrganizationPlan
import com.tingyun.smartmistakebook.core.model.ProblemRelationKind
import com.tingyun.smartmistakebook.core.model.ProblemRelationSuggestion
import com.tingyun.smartmistakebook.core.model.ProblemStepKnowledgeAttribution
import com.tingyun.smartmistakebook.core.model.QuestionDocument
import com.tingyun.smartmistakebook.core.model.RelatedProblemCandidate
import com.tingyun.smartmistakebook.core.model.SubjectKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomMistakeOrganizationRepositoryTest {
    @Test
    fun repeatedOntologyGapKeepsOccurrencesButSharesOneGroundingKey() {
        val first = buildKnowledgeGroundingRecords(
            organizationRequestId = "organization-request-1",
            organizationRequestFingerprint = "a".repeat(64),
            input = input(),
            requests = listOf(
                KnowledgeGroundingRequest(
                    query = "高中数学  导数符号  单调性",
                    expectedParentKnowledgeDisplayName = "函数性质",
                    reasonMarkdown = "现有本体没有足够细的原子节点。",
                ),
            ),
            occurredAtEpochMillis = 2_000,
        ).single()
        val second = buildKnowledgeGroundingRecords(
            organizationRequestId = "organization-request-2",
            organizationRequestFingerprint = "b".repeat(64),
            input = input(),
            requests = listOf(
                KnowledgeGroundingRequest(
                    query = "高中数学 导数符号 单调性",
                    expectedParentKnowledgeDisplayName = "函数性质",
                    reasonMarkdown = "另一道题再次命中同一个本体缺口。",
                ),
            ),
            occurredAtEpochMillis = 3_000,
        ).single()

        assertEquals(first.groundingKey, second.groundingKey)
        assertTrue(first.groundingRequestId != second.groundingRequestId)
        assertEquals("MATH", first.subject)
        assertEquals(0, first.requestOrdinal)
    }

    @Test
    fun explicitCorrectionBecomesDeterministicUserCorrectedFacts() {
        val first = buildConfirmationCommand(
            requestId = "organization-request",
            input = input(),
            classifications = classifications(),
            relations = relations(),
            acceptedAtEpochMillis = 2_000,
        )
        val replay = buildConfirmationCommand(
            requestId = "organization-request",
            input = input(),
            classifications = classifications(),
            relations = relations(),
            acceptedAtEpochMillis = 2_000,
        )

        assertEquals(first, replay)
        assertEquals(2, first.classifications.size)
        assertEquals(setOf("CHAPTER", "KNOWLEDGE"), first.classifications.map { it.dimension }.toSet())
        assertEquals(1, first.knowledgeNodes.size)
        assertEquals(1, first.knowledgeBindings.size)
        assertEquals("USER_CORRECTED", first.classifications.first().acceptanceSource)
        assertEquals("USER_CORRECTED", first.knowledgeBindings.single().sourceType)
        assertEquals("user-corrected-v1", first.classifications.first().taxonomyVersion)
        assertEquals("ACTIVE", first.relations.single().status)
        assertFalse(first.replaceRelations)
        assertTrue(first.payloadFingerprint.matches(Regex("[a-f0-9]{64}")))
    }

    @Test
    fun schemaThreeCommandPreservesStepsAttributionsAndErrorEvidence() {
        val candidate = ProblemErrorAttributionCandidate(
            resolutionStatus = ProblemErrorAttributionResolutionStatus.RESOLVED,
            rationaleMarkdown = "把导数为负的区间误判为递增。",
            confidence = 0.91,
            stepOrdinal = 1,
            atomicReferenceId = "atom-1",
            evidenceRefs = listOf(
                ProblemErrorEvidenceRef(
                    blockId = "question-1-block",
                    sourceAssetId = "asset-2",
                    evidenceKind = ProblemErrorEvidenceKind.STUDENT_WORK,
                ),
                ProblemErrorEvidenceRef(
                    blockId = "question-1-block",
                    sourceAssetId = "asset-1",
                    evidenceKind = ProblemErrorEvidenceKind.QUESTION_CONTENT,
                ),
            ),
        )
        fun commandFor(errorCandidate: ProblemErrorAttributionCandidate) =
            buildV3ConfirmationCommand(
                requestId = "organization-v3-request",
                input = input(),
                classifications = classifications(),
                relations = emptyList(),
                atomicKnowledge = output().plan.atomicKnowledge,
                stepAttributions = output().plan.stepAttributions,
                errorAttributionCandidates = listOf(errorCandidate),
                modelVersion = "test-model-v3",
                sourceCommitReceiptCommandId = "draft-commit-receipt-1",
                acceptedAtEpochMillis = 2_000,
                acceptanceSource = BindingAcceptanceSource.LOCAL_POLICY_ACCEPTED,
                relationRemovals = emptySet(),
                replaceRelations = false,
            )
        val command = commandFor(candidate)

        assertEquals(3, command.planSchemaVersion)
        assertEquals("draft-commit-receipt-1", command.sourceCommitReceiptCommandId)
        assertEquals(1, command.solutionSteps.size)
        assertEquals("atom-1", command.solutionSteps.single().knowledgeReferences.single().knowledgeReferenceId)
        assertEquals("math-atomic-core-operation", command.solutionSteps.single().knowledgeReferences.single().knowledgeNodeId)
        val persistedCandidate = command.errorAttributionCandidates.single()
        assertEquals("RESOLVED", persistedCandidate.resolutionStatus)
        assertEquals("atom-1", persistedCandidate.knowledgeReferenceId)
        assertEquals("math-atomic-core-operation", persistedCandidate.knowledgeNodeId)
        assertEquals(listOf("asset-1", "asset-2"), persistedCandidate.evidence.map { it.sourceAssetId })
        assertTrue(command.payloadFingerprint.matches(Regex("[a-f0-9]{64}")))
        assertEquals(
            command.payloadFingerprint,
            commandFor(candidate.copy(evidenceRefs = candidate.evidenceRefs.reversed())).payloadFingerprint,
        )
        assertFalse(
            command.payloadFingerprint ==
                commandFor(candidate.copy(rationaleMarkdown = "不同的错误归因说明。")).payloadFingerprint,
        )
    }

    @Test
    fun knowledgeNodeIdentityDoesNotChangeWithAcceptanceAuthority() {
        val automatic = buildConfirmationCommand(
            requestId = "automatic-request",
            input = input(),
            classifications = classifications(),
            relations = emptyList(),
            acceptedAtEpochMillis = 2_000,
            acceptanceSource = BindingAcceptanceSource.LOCAL_POLICY_ACCEPTED,
        )
        val corrected = buildConfirmationCommand(
            requestId = "corrected-request",
            input = input(),
            classifications = classifications(),
            relations = emptyList(),
            acceptedAtEpochMillis = 3_000,
            acceptanceSource = BindingAcceptanceSource.USER_CORRECTED,
        )

        assertEquals(
            automatic.knowledgeNodes.single().knowledgeNodeId,
            corrected.knowledgeNodes.single().knowledgeNodeId,
        )
        assertEquals("LOCAL_POLICY_ACCEPTED", automatic.knowledgeBindings.single().sourceType)
        assertEquals("USER_CORRECTED", corrected.knowledgeBindings.single().sourceType)
        assertEquals("organization-v1", automatic.knowledgeNodes.single().taxonomyVersion)
        assertEquals("organization-v1", corrected.knowledgeNodes.single().taxonomyVersion)
    }

    @Test
    fun automaticRerunPreservesAcceptedLabelsThatTheModelOmits() {
        val existing = buildConfirmationCommand(
            requestId = "existing-organization",
            input = input(),
            classifications = classifications() +
                classification(ClassificationDimension.KNOWLEDGE, "链式法则", 0.99),
            relations = emptyList(),
            acceptedAtEpochMillis = 2_000,
            acceptanceSource = BindingAcceptanceSource.LOCAL_POLICY_ACCEPTED,
        ).classifications
        val incoming = listOf(
            classification(ClassificationDimension.CHAPTER, "函数", 0.95),
            classification(ClassificationDimension.KNOWLEDGE, "导数符号", 0.95),
        )

        val merged = mergeAutomaticClassifications(existing, incoming)

        assertEquals(
            setOf("函数", "二次函数最值", "链式法则", "导数符号"),
            merged.mapTo(linkedSetOf(), ProblemClassificationSuggestion::displayName),
        )
    }

    @Test
    fun exactRelationRemovalUsesDeterministicScopedIdentityWithoutFullReplacement() {
        val command = buildConfirmationCommand(
            requestId = "remove-relation-request",
            input = input(),
            classifications = classifications(),
            relations = emptyList(),
            acceptedAtEpochMillis = 4_000,
            relationRemovals = setOf(
                ProblemOrganizationRelationKey(
                    targetProblemId = "problem-2",
                    targetProblemRevisionId = "revision-2",
                    kind = ProblemRelationKind.VARIANT_OF,
                ),
            ),
        )

        assertEquals(1, command.relationIdsToRemove.size)
        assertTrue(command.relationIdsToRemove.single().startsWith("relation:"))
        assertFalse(command.replaceRelations)
    }

    @Test
    fun incompleteHierarchyCannotBecomeAConfirmationCommand() {
        val selectedKnowledge = classifications().filter {
            it.dimension == ClassificationDimension.KNOWLEDGE
        }

        assertTrue(
            runCatching {
                buildConfirmationCommand(
                    requestId = "organization-request",
                    input = input(),
                    classifications = selectedKnowledge,
                    relations = emptyList(),
                    acceptedAtEpochMillis = 2_000,
                )
            }.isFailure,
        )
    }

    @Test
    fun completeHighConfidenceHierarchyIsAcceptedAndNormalized() {
        val output = output(
            classifications = listOf(
                classification(ClassificationDimension.CHAPTER, " 函数 ", 0.78),
                classification(ClassificationDimension.CHAPTER, "函数", 0.95),
                classification(ClassificationDimension.KNOWLEDGE, "二次函数最值", 0.90),
            ),
            relations = relations().map { it.copy(confidence = 0.90) },
        )

        val accepted = requireNotNull(acceptOrganizationLocally(input(), output))

        assertEquals(listOf("函数", "二次函数最值"), accepted.classifications.map { it.displayName })
        assertEquals(listOf("problem-2"), accepted.relations.map { it.targetProblemId })
        val command = buildConfirmationCommand(
            requestId = "organization-request",
            input = input(),
            classifications = accepted.classifications,
            relations = accepted.relations,
            acceptedAtEpochMillis = 2_000,
            atomicKnowledge = accepted.atomicKnowledge,
            stepAttributions = accepted.stepAttributions,
            acceptanceSource = BindingAcceptanceSource.LOCAL_POLICY_ACCEPTED,
        )
        assertEquals("LOCAL_POLICY_ACCEPTED", command.classifications.single {
            it.dimension == "CHAPTER"
        }.acceptanceSource)
        assertEquals("LOCAL_POLICY_ACCEPTED", command.knowledgeBindings.single().sourceType)
        assertEquals("local-policy-v1", command.classifications.first().taxonomyVersion)
    }

    @Test
    fun lowConfidenceOrIncompleteHierarchyIsNotAccepted() {
        val lowChapter = output(
            classifications = listOf(
                classification(ClassificationDimension.CHAPTER, "函数", 0.77),
                classification(ClassificationDimension.KNOWLEDGE, "导数", 0.99),
            ),
        )
        val missingKnowledge = output(
            classifications = listOf(
                classification(ClassificationDimension.CHAPTER, "函数", 0.99),
                classification(ClassificationDimension.KNOWLEDGE, "导数", 0.77),
            ),
        )

        assertEquals(null, acceptOrganizationLocally(input(), lowChapter))
        assertEquals(null, acceptOrganizationLocally(input(), missingKnowledge))
    }

    @Test
    fun ungroundedAtomicKnowledgeCannotEnterSubjectMasteryMemory() {
        val grounded = output()
        val ungrounded = grounded.copy(
            plan = grounded.plan.copy(
                atomicKnowledge = grounded.plan.atomicKnowledge.map { atom ->
                    atom.copy(matchedKnowledgeNodeId = null)
                },
            ),
        )

        assertEquals(null, acceptOrganizationLocally(input(), ungrounded))
    }

    @Test
    fun invalidOrLowConfidenceRelationDoesNotBlockClassification() {
        val invalidTarget = relations().single().copy(
            targetProblemId = "not-in-candidates",
            targetProblemRevisionId = "unknown-revision",
            confidence = 0.99,
        )
        val lowConfidence = relations().single().copy(confidence = 0.89)
        val accepted = requireNotNull(
            acceptOrganizationLocally(
                input(),
                output(relations = listOf(invalidTarget, lowConfidence)),
            ),
        )

        assertEquals(2, accepted.classifications.size)
        assertTrue(accepted.relations.isEmpty())
        val command = buildConfirmationCommand(
            requestId = "organization-request",
            input = input(),
            classifications = accepted.classifications,
            relations = accepted.relations,
            acceptedAtEpochMillis = 2_000,
            acceptanceSource = BindingAcceptanceSource.LOCAL_POLICY_ACCEPTED,
        )
        assertFalse(command.replaceRelations)
    }

    @Test
    fun outputForAnotherRevisionCannotBeLocallyAccepted() {
        val mismatched = output().copy(problemRevisionId = "revision-2")

        assertEquals(null, acceptOrganizationLocally(input(), mismatched))
    }

    @Test
    fun relationShortlistPrioritizesSharedKnowledgeOverAlphabeticalOrder() {
        val current = mistake(
            problemId = "current",
            title = "函数单调性",
            knowledge = listOf("导数符号"),
        )
        val alphabeticallyFirstButUnrelated = mistake(
            problemId = "a-unrelated",
            title = "A集合运算",
            knowledge = listOf("集合"),
        )
        val related = mistake(
            problemId = "z-related",
            title = "利用导数研究单调区间",
            knowledge = listOf("导数符号"),
        )

        assertTrue(
            relationCandidateScore(current, related) >
                relationCandidateScore(current, alphabeticallyFirstButUnrelated),
        )
    }

    private fun input() = ProblemOrganizationInput(
        problemId = "problem-1",
        problemRevisionId = "revision-1",
        practiceUnitId = "practice-1",
        subject = SubjectKind.MATH,
        questionDocument = document("question-1", "求函数最值"),
        relevantLearningEvidence = emptyList(),
        relationCandidates = listOf(
            RelatedProblemCandidate(
                problemId = "problem-2",
                problemRevisionId = "revision-2",
                subject = SubjectKind.MATH,
                title = "相关变式",
                questionDocument = document("question-2", "讨论参数范围"),
            ),
        ),
        knowledgeBaseNodes = listOf(
            KnowledgeBaseNodeContext(
                knowledgeNodeId = "math-atomic-core-operation",
                subject = SubjectKind.MATH,
                canonicalName = "识别并执行核心运算步骤",
                aliases = emptyList(),
                kind = KnowledgeNodeKind.PROCEDURE,
                granularity = KnowledgeNodeGranularity.ATOMIC,
                parentCanonicalName = "二次函数最值",
                taxonomyVersion = "math-v1",
                verificationStatus = KnowledgeNodeVerificationStatus.SOURCE_GROUNDED,
                boundaryMarkdown = "只记录本题实际使用的运算能力。",
            ),
        ),
    )

    private fun classifications() = listOf(
        ProblemClassificationSuggestion(
            dimension = ClassificationDimension.KNOWLEDGE,
            displayName = "二次函数最值",
            rationaleMarkdown = "核心知识点。",
            confidence = 0.9,
        ),
        ProblemClassificationSuggestion(
            dimension = ClassificationDimension.CHAPTER,
            displayName = "函数",
            rationaleMarkdown = "属于函数板块。",
            confidence = 0.8,
        ),
    )

    private fun classification(
        dimension: ClassificationDimension,
        displayName: String,
        confidence: Double,
    ) = ProblemClassificationSuggestion(
        dimension = dimension,
        displayName = displayName,
        rationaleMarkdown = "本地策略测试。",
        confidence = confidence,
    )

    private fun output(
        classifications: List<ProblemClassificationSuggestion> = classifications(),
        relations: List<ProblemRelationSuggestion> = emptyList(),
    ) = ProblemOrganizationOutput(
        problemId = "problem-1",
        problemRevisionId = "revision-1",
        practiceUnitId = "practice-1",
        plan = ProblemOrganizationPlan(
            summaryMarkdown = "整理完成。",
            reviewPriorityMarkdown = "复习当前题目。",
            targetedEvidenceLabels = emptyList(),
            classifications = classifications,
            relations = relations,
            schemaVersion = 2,
            atomicKnowledge = listOf(
                AtomicKnowledgeSuggestion(
                    referenceId = "atom-1",
                    canonicalName = "识别并执行核心运算步骤",
                    aliases = emptyList(),
                    kind = KnowledgeNodeKind.PROCEDURE,
                    parentKnowledgeDisplayName = classifications.first {
                        it.dimension == ClassificationDimension.KNOWLEDGE
                    }.displayName.trim().replace(Regex("\\s+"), " "),
                    matchedKnowledgeNodeId = "math-atomic-core-operation",
                    prerequisiteReferenceIds = emptyList(),
                    observableOutcomeMarkdown = "能独立完成题目中的核心运算步骤。",
                    boundaryMarkdown = "只记录本题实际使用的运算能力。",
                    confidence = 0.9,
                ),
            ),
            stepAttributions = listOf(
                ProblemStepKnowledgeAttribution(
                    stepOrdinal = 1,
                    stepSummaryMarkdown = "完成核心运算。",
                    atomicReferenceIds = listOf("atom-1"),
                ),
            ),
        ),
        modelVersion = "test-model",
    )

    private fun relations() = listOf(
        ProblemRelationSuggestion(
            targetProblemId = "problem-2",
            targetProblemRevisionId = "revision-2",
            kind = ProblemRelationKind.VARIANT_OF,
            rationaleMarkdown = "知识点相同，参数条件不同。",
            confidence = 0.8,
        ),
    )

    private fun document(id: String, markdown: String) = QuestionDocument(
        id = id,
        blocks = listOf(ContentBlock.Paragraph("$id-block", markdown)),
    )

    private fun mistake(
        problemId: String,
        title: String,
        knowledge: List<String>,
    ) = MistakeRecord(
        entryId = "entry-$problemId",
        problemId = problemId,
        problemRevisionId = "revision-$problemId",
        practiceUnitId = "practice-$problemId",
        sourceKey = null,
        subject = "MATH",
        title = title,
        problemMarkdown = title,
        status = "ACTIVE",
        createdAtEpochMillis = 1,
        nextReviewAtEpochMillis = null,
        retrievability = null,
        knowledgeLabels = knowledge,
    )
}
