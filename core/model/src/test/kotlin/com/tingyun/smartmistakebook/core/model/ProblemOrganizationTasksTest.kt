package com.tingyun.smartmistakebook.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProblemOrganizationTasksTest {
    @Test
    fun codecRoundTripPreservesBoundedOrganizationTask() {
        val request = request()
        val output = output()

        assertEquals(request, ModelTaskCodec.decodeRequest(ModelTaskCodec.encodeRequest(request)))
        assertEquals(output, ModelTaskCodec.decodeOutput(ModelTaskCodec.encodeOutput(output)))
        assertTrue(ModelTaskCompletionValidator.validate(request, output).isEmpty())
    }

    @Test
    fun completionRejectsUndisclosedEvidenceButLeavesRelationsToLocalAcceptance() {
        val invalid = output().copy(
            plan = output().plan.copy(
                targetedEvidenceLabels = listOf("未披露知识"),
                relations = listOf(
                    output().plan.relations.single().copy(
                        targetProblemId = "another-problem",
                    ),
                ),
            ),
        )

        val codes = ModelTaskCompletionValidator.validate(request(), invalid).map { it.code }.toSet()

        assertTrue(ModelTaskCompletionIssueCode.MODEL_CLAIMED_UNDISCLOSED_EVIDENCE in codes)
        assertFalse(ModelTaskCompletionIssueCode.ORGANIZATION_UNKNOWN_RELATION_TARGET in codes)
    }

    @Test
    fun classificationEgressIsDocumentOnlyAndExact() {
        val input = input()
        val provider = provider()
        val manifest = ModelEgressManifest(
            authorizationId = "organization-authorization",
            subjectId = input.subjectId,
            purpose = ModelEgressPurpose.CLASSIFICATION,
            authorizedTaskKinds = setOf(ModelTaskKind.PROBLEM_CLASSIFY),
            providerId = provider.providerId,
            modelId = provider.modelId,
            providerConfigurationVersion = provider.providerConfigurationVersion,
            promptPolicyVersion = ModelPromptPolicyVersions.PROBLEM_ORGANIZATION,
            approvedAtEpochMillis = 2,
            assets = emptyList(),
            disclosedData = ModelEgressManifest.PROBLEM_ORGANIZATION_DISCLOSURE,
            prohibitedData = ModelEgressManifest.PROBLEM_ORGANIZATION_PROHIBITED_DATA,
        )
        val request = ModelTaskRequest(
            requestId = "organization-request",
            input = input,
            occurredAtEpochMillis = 1,
            egressManifest = manifest,
        )

        val execution = ModelEgressPolicy.authorize(request, provider, 2)

        assertTrue((execution.permit as ModelExecutionPermit.External).manifest.assets.isEmpty())
        assertEquals(
            setOf(
                ModelEgressDataClass.CONFIRMED_QUESTION_DOCUMENT,
                ModelEgressDataClass.RELATED_QUESTION_CANDIDATES,
                ModelEgressDataClass.SUBJECT_KNOWLEDGE_BASE,
            ),
            manifest.disclosedData,
        )
        assertFalse(ModelEgressDataClass.RELEVANT_LEARNING_EVIDENCE in manifest.disclosedData)
    }

    @Test
    fun organizationInputRejectsLearningMasteryEvidence() {
        assertTrue(
            runCatching {
                input().copy(
                    relevantLearningEvidence = listOf(
                        TutorKnowledgeEvidence(
                            "node-1",
                            "导数符号",
                            TutorEvidenceLevel.LEARNING,
                            0.3,
                        ),
                    ),
                )
            }.isFailure,
        )
    }

    @Test
    fun organizationOnlyAcceptsContentHierarchyClassifications() {
        assertEquals(
            setOf(ClassificationDimension.CHAPTER, ClassificationDimension.KNOWLEDGE),
            PROBLEM_ORGANIZATION_CONTENT_DIMENSIONS,
        )
        listOf(
            ClassificationDimension.ERROR_CAUSE,
            ClassificationDimension.QUESTION_TYPE,
            ClassificationDimension.SOURCE,
        ).forEach { dimension ->
            assertTrue(
                runCatching {
                    ProblemClassificationSuggestion(
                        dimension = dimension,
                        displayName = "旧分类",
                        rationaleMarkdown = "不再作为整理结果。",
                        confidence = 0.8,
                    )
                }.isFailure,
            )
        }
    }

    @Test
    fun organizationRelationsDoNotModelErrorPatterns() {
        assertFalse(ProblemRelationKind.SAME_ERROR_PATTERN in PROBLEM_ORGANIZATION_RELATION_KINDS)
        assertTrue(
            runCatching {
                ProblemRelationSuggestion(
                    targetProblemId = "problem-2",
                    targetProblemRevisionId = "revision-2",
                    kind = ProblemRelationKind.SAME_ERROR_PATTERN,
                    rationaleMarkdown = "旧关系。",
                    confidence = 0.8,
                )
            }.isFailure,
        )
    }

    @Test
    fun organizationKnowledgeContextCannotCrossSubjects() {
        val physicsNode = KnowledgeBaseNodeContext(
            knowledgeNodeId = "physics-atom-1",
            subject = SubjectKind.PHYSICS,
            canonicalName = "受力分析",
            aliases = emptyList(),
            kind = KnowledgeNodeKind.REPRESENTATION,
            granularity = KnowledgeNodeGranularity.ATOMIC,
            parentCanonicalName = "力学",
            taxonomyVersion = "physics-v1",
            verificationStatus = KnowledgeNodeVerificationStatus.CURATED,
        )

        assertTrue(runCatching { input().copy(knowledgeBaseNodes = listOf(physicsNode)) }.isFailure)
    }

    @Test
    fun completionRejectsAPrerequisiteRelationMissingFromTheDisclosedKnowledgeGraph() {
        val prerequisiteNode = knowledgeNode("knowledge-prerequisite", "计算函数的导数")
        val dependentNode = knowledgeNode("knowledge-dependent", "根据导数符号判断函数单调性")
        val request = request().copy(
            input = input().copy(knowledgeBaseNodes = listOf(prerequisiteNode, dependentNode)),
        )
        val baseOutput = output()
        val baseAtom = baseOutput.plan.atomicKnowledge.single()
        val prerequisiteAtom = baseAtom.copy(
            referenceId = "atom-0",
            canonicalName = prerequisiteNode.canonicalName,
            aliases = emptyList(),
            matchedKnowledgeNodeId = prerequisiteNode.knowledgeNodeId,
        )
        val dependentAtom = baseAtom.copy(
            matchedKnowledgeNodeId = dependentNode.knowledgeNodeId,
            prerequisiteReferenceIds = listOf(prerequisiteAtom.referenceId),
        )
        val invalid = baseOutput.copy(
            plan = baseOutput.plan.copy(
                atomicKnowledge = listOf(prerequisiteAtom, dependentAtom),
                stepAttributions = listOf(
                    baseOutput.plan.stepAttributions.single().copy(
                        atomicReferenceIds = listOf(
                            prerequisiteAtom.referenceId,
                            dependentAtom.referenceId,
                        ),
                    ),
                ),
            ),
        )

        assertTrue(
            ModelTaskCompletionIssueCode.ORGANIZATION_UNKNOWN_KNOWLEDGE_PREREQUISITE in
                ModelTaskCompletionValidator.validate(request, invalid).map { it.code },
        )
    }

    @Test
    fun everyAtomicAbilityMustBeAttachedToASolutionStep() {
        val extraAtom = output().plan.atomicKnowledge.single().copy(
            referenceId = "atom-2",
            canonicalName = "识别临界点",
        )

        assertTrue(
            runCatching {
                output().plan.copy(atomicKnowledge = output().plan.atomicKnowledge + extraAtom)
            }.isFailure,
        )
    }

    @Test
    fun unresolvedAtomicTaxonomyFailsClosedIntoGroundingRequest() {
        val unresolved = output().plan.copy(
            atomicKnowledge = emptyList(),
            stepAttributions = emptyList(),
            groundingRequests = listOf(
                KnowledgeGroundingRequest(
                    query = "高中数学 导数符号 单调性 原子知识点边界",
                    expectedParentKnowledgeDisplayName = "利用导数研究函数单调性",
                    reasonMarkdown = "现有目录无法可靠区分符号分析与单调性结论。",
                ),
            ),
        )

        assertTrue(unresolved.atomicKnowledge.isEmpty())
        assertEquals(1, unresolved.groundingRequests.size)
    }

    @Test
    fun userVisibleOrganizationCopyRejectsInternalImplementationVocabulary() {
        listOf(
            "原子知识",
            "原子能力",
            "知识本体",
            "检索召回",
            "学习投影",
            "atomic knowledge",
            "knowledge grounding",
            "SOURCE_GROUNDED",
        ).forEach { internalTerm ->
            assertTrue(
                "Classification label leaked: $internalTerm",
                runCatching {
                    output().plan.classifications.single().copy(displayName = internalTerm)
                }.isFailure,
            )
            assertTrue(
                "Summary leaked: $internalTerm",
                runCatching {
                    output().plan.copy(summaryMarkdown = "系统正在处理 $internalTerm")
                }.isFailure,
            )
        }
    }

    private fun request() = ModelTaskRequest(
        requestId = "organization-request",
        input = input(),
        occurredAtEpochMillis = 1,
    )

    private fun input() = ProblemOrganizationInput(
        problemId = "problem-1",
        problemRevisionId = "revision-1",
        practiceUnitId = "unit-1",
        subject = SubjectKind.MATH,
        questionDocument = document("question-1", "求函数的单调区间"),
        relevantLearningEvidence = emptyList(),
        relationCandidates = listOf(
            RelatedProblemCandidate(
                "problem-2",
                "revision-2",
                SubjectKind.MATH,
                "导数变式",
                document("question-2", "讨论参数函数的单调性"),
            ),
        ),
    )

    private fun output() = ProblemOrganizationOutput(
        problemId = "problem-1",
        problemRevisionId = "revision-1",
        practiceUnitId = "unit-1",
        plan = ProblemOrganizationPlan(
            summaryMarkdown = "这是一道用导数判断单调性的题。",
            reviewPriorityMarkdown = "导数符号证据偏弱，值得近期复习。",
            targetedEvidenceLabels = emptyList(),
            classifications = listOf(
                ProblemClassificationSuggestion(
                    ClassificationDimension.KNOWLEDGE,
                    "利用导数研究函数单调性",
                    "核心步骤是求导并判断符号。",
                    0.94,
                ),
            ),
            relations = listOf(
                ProblemRelationSuggestion(
                    "problem-2",
                    "revision-2",
                    ProblemRelationKind.VARIANT_OF,
                    "两题共享导数符号分析，但参数条件不同。",
                    0.82,
                ),
            ),
            schemaVersion = 2,
            atomicKnowledge = listOf(
                AtomicKnowledgeSuggestion(
                    referenceId = "atom-1",
                    canonicalName = "根据导数符号判断函数单调性",
                    aliases = listOf("导数符号与单调性"),
                    kind = KnowledgeNodeKind.REASONING,
                    parentKnowledgeDisplayName = "利用导数研究函数单调性",
                    matchedKnowledgeNodeId = null,
                    prerequisiteReferenceIds = emptyList(),
                    observableOutcomeMarkdown = "能由导数符号确定函数的增减区间。",
                    boundaryMarkdown = "不包含求导公式本身的机械计算。",
                    confidence = 0.94,
                ),
            ),
            stepAttributions = listOf(
                ProblemStepKnowledgeAttribution(
                    stepOrdinal = 1,
                    stepSummaryMarkdown = "求导并分析导数符号。",
                    atomicReferenceIds = listOf("atom-1"),
                ),
            ),
        ),
        modelVersion = "model-v1",
    )

    private fun knowledgeNode(
        id: String,
        name: String,
        prerequisites: List<String> = emptyList(),
    ) = KnowledgeBaseNodeContext(
        knowledgeNodeId = id,
        subject = SubjectKind.MATH,
        canonicalName = name,
        aliases = emptyList(),
        kind = KnowledgeNodeKind.REASONING,
        granularity = KnowledgeNodeGranularity.ATOMIC,
        parentCanonicalName = "利用导数研究函数单调性",
        taxonomyVersion = "math-v1",
        verificationStatus = KnowledgeNodeVerificationStatus.SOURCE_GROUNDED,
        prerequisiteKnowledgeNodeIds = prerequisites,
    )

    private fun document(id: String, markdown: String) = QuestionDocument(
        id = id,
        blocks = listOf(ContentBlock.Paragraph("$id-block", markdown)),
    )

    private fun provider() = ProviderCapabilitySnapshot(
        providerId = "provider",
        providerDisplayName = "Provider",
        modelId = "model",
        supportedTasks = setOf(ModelTaskKind.PROBLEM_CLASSIFY),
        supportsImageInput = true,
        supportsStructuredOutput = true,
        supportsStreaming = false,
        providerConfigurationVersion = "config-v1",
    )
}
