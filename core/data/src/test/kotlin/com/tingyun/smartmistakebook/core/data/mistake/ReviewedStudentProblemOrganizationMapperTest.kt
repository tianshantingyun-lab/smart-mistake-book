package com.tingyun.smartmistakebook.core.data.mistake

import com.tingyun.smartmistakebook.core.model.AtomicKnowledgeSuggestion
import com.tingyun.smartmistakebook.core.model.CaptureSourceAssetRef
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocument
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocumentFingerprint
import com.tingyun.smartmistakebook.core.model.ClassificationDimension
import com.tingyun.smartmistakebook.core.model.ContentBlock
import com.tingyun.smartmistakebook.core.model.KnowledgeBaseCatalogProvenance
import com.tingyun.smartmistakebook.core.model.KnowledgeBaseNodeContext
import com.tingyun.smartmistakebook.core.model.KnowledgeNodeGranularity
import com.tingyun.smartmistakebook.core.model.KnowledgeNodeKind
import com.tingyun.smartmistakebook.core.model.KnowledgeNodeVerificationStatus
import com.tingyun.smartmistakebook.core.model.ModelEgressAssetGrant
import com.tingyun.smartmistakebook.core.model.ModelEgressAuthorizationId
import com.tingyun.smartmistakebook.core.model.ModelEgressManifest
import com.tingyun.smartmistakebook.core.model.ModelEgressPurpose
import com.tingyun.smartmistakebook.core.model.ModelPromptPolicyVersions
import com.tingyun.smartmistakebook.core.model.ModelTaskFingerprint
import com.tingyun.smartmistakebook.core.model.ModelTaskKind
import com.tingyun.smartmistakebook.core.model.ModelTaskRequest
import com.tingyun.smartmistakebook.core.model.ModelTaskSnapshot
import com.tingyun.smartmistakebook.core.model.ModelTaskStage
import com.tingyun.smartmistakebook.core.model.ModelTaskStatus
import com.tingyun.smartmistakebook.core.model.NormalizedSourceRegion
import com.tingyun.smartmistakebook.core.model.ProblemClassificationSuggestion
import com.tingyun.smartmistakebook.core.model.ProblemErrorAttributionCandidate
import com.tingyun.smartmistakebook.core.model.ProblemErrorAttributionResolutionStatus
import com.tingyun.smartmistakebook.core.model.ProblemErrorEvidenceKind
import com.tingyun.smartmistakebook.core.model.ProblemErrorEvidenceRef
import com.tingyun.smartmistakebook.core.model.ProblemFamilySuggestion
import com.tingyun.smartmistakebook.core.model.ProblemOrganizationOutput
import com.tingyun.smartmistakebook.core.model.ProblemOrganizationPlan
import com.tingyun.smartmistakebook.core.model.ProblemOrganizationV3Input
import com.tingyun.smartmistakebook.core.model.ProblemStepKnowledgeAttribution
import com.tingyun.smartmistakebook.core.model.ProviderCapabilitySnapshot
import com.tingyun.smartmistakebook.core.model.QuestionBlockEvidence
import com.tingyun.smartmistakebook.core.model.QuestionBlockProvenance
import com.tingyun.smartmistakebook.core.model.QuestionBlockReviewStatus
import com.tingyun.smartmistakebook.core.model.QuestionDocument
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.WritingLayer
import com.tingyun.smartmistakebook.core.model.storage.KnowledgeNodeRef
import com.tingyun.smartmistakebook.core.model.storage.KnowledgeReferenceProofAuthority
import com.tingyun.smartmistakebook.core.model.storage.StudentProblemRef
import com.tingyun.smartmistakebook.core.model.storage.StudentProblemRevisionRef
import com.tingyun.smartmistakebook.core.student.mistake.database.OrganizeStudentProblemCommand
import com.tingyun.smartmistakebook.core.student.mistake.database.ReviewedStudentProblemOrganizationBinding
import com.tingyun.smartmistakebook.core.student.mistake.database.ReviewedStudentProblemOrganizationLocalContext
import com.tingyun.smartmistakebook.core.student.mistake.database.ReviewedStudentProblemOrganizationMapping
import com.tingyun.smartmistakebook.core.student.mistake.database.STUDENT_PROBLEM_ORGANIZATION_MAPPING_POLICY_VERSION
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentProblemErrorOccurrenceRef
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentProblemErrorAttribution
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentProblemOrganizationFacet
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentProblemOrganizationFacetDimension
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewedStudentProblemOrganizationMapperTest {
    @Test
    fun validMappingContainsOnlySemanticsEntailedByReviewedV3Output() {
        val fixture = fixture()

        val command =
            ReviewedStudentProblemOrganizationMapper.mapUnsigned(
                fixture.local,
                fixture.task,
                fixture.proofs,
            )
        val binding = command.requireExact(fixture)

        assertEquals(fixture.output.plan.summaryMarkdown, command.solutionAnalysis.summaryMarkdown)
        assertNull(command.solutionAnalysis.finalAnswerMarkdown)
        assertEquals(
            fixture.output.plan.stepAttributions.map { it.stepSummaryMarkdown },
            command.solutionAnalysis.steps.map { it.summaryMarkdown },
        )
        assertTrue(command.solutionAnalysis.steps.all { it.resultMarkdown == null })
        fixture.output.plan.errorAttributionCandidates.zip(command.errorAttributions).forEach {
                (candidate, attribution) ->
            assertEquals(candidate.resolutionStatus, attribution.resolutionStatus)
            assertEquals(candidate.rationaleMarkdown, attribution.rationaleMarkdown)
            assertEquals(candidate.stepOrdinal, attribution.stepOrdinal)
            assertEquals(candidate.atomicReferenceId, attribution.atomicReferenceId)
            assertEquals(candidate.evidenceRefs, attribution.evidenceRefs)
        }
        assertEquals(3, command.classifications.size)
        assertEquals(4, command.stepKnowledgeBindings.size)
        assertTrue(command.facets.isEmpty())
        assertEquals(
            STUDENT_PROBLEM_ORGANIZATION_MAPPING_POLICY_VERSION,
            binding.mappingPolicyVersion,
        )
        assertEquals(MANIFEST_FINGERPRINT, binding.knowledgeManifestFingerprint)
        assertEquals(ACTIVATION_GENERATION, binding.knowledgeActivationGeneration)
        assertEquals(command.payloadCanonicalFingerprint, binding.finalCommandCanonicalFingerprint)
        assertEquals(
            ReviewedStudentProblemOrganizationMapping.reviewedOutputFingerprint(fixture.output),
            binding.reviewedOutputCanonicalFingerprint,
        )
    }

    @Test
    fun reviewedProblemFamilyMapsToOneProblemFamilyFacet() {
        val base = fixture()
        val familyOutput =
            base.output.copy(
                plan =
                    base.output.plan.copy(
                        problemFamily =
                            ProblemFamilySuggestion(
                                familyKey = "monotonic_interval_family",
                                rationaleMarkdown = "同题不同录入与近似变式归入同一族。",
                                confidence = 0.94,
                            ),
                    ),
            )
        val familyTask = base.task.copy(output = familyOutput)
        val command =
            ReviewedStudentProblemOrganizationMapper.mapUnsigned(
                base.local,
                familyTask,
                base.proofs,
            )

        val facet = command.facets.single()
        assertEquals(
            StudentProblemOrganizationFacetDimension.PROBLEM_FAMILY,
            facet.dimension,
        )
        assertEquals("monotonic_interval_family", facet.familyId)
        assertEquals(familyOutput.modelVersion, facet.familyVersion)
        command.requireExact(
            Fixture(
                task = familyTask,
                output = familyOutput,
                provider = base.provider,
                local = base.local,
                knowledgeAuthority = base.knowledgeAuthority,
                proofs = base.proofs,
            ),
        )
    }

    @Test
    fun problemFamilyBindingIncludesExactCapturedDocumentEvidence() {
        val base = fixture()
        val familyOutput =
            base.output.copy(
                plan =
                    base.output.plan.copy(
                        problemFamily =
                            ProblemFamilySuggestion(
                                familyKey = "monotonic_interval_family",
                                rationaleMarkdown = "同题不同录入与近似变式归入同一族。",
                                confidence = 0.94,
                            ),
                    ),
            )
        val baseTask = base.task.copy(output = familyOutput)
        val baseCommand =
            ReviewedStudentProblemOrganizationMapper.mapUnsigned(
                base.local,
                baseTask,
                base.proofs,
            )
        val input = base.task.request.input as ProblemOrganizationV3Input
        val modifiedCaptured =
            input.capturedDocument.copy(
                document =
                    input.capturedDocument.document.copy(
                        blocks =
                            listOf(
                                ContentBlock.Paragraph(
                                    "question-block",
                                    "另一份同一道题的录入版本。",
                                ),
                            ),
                    ),
            )
        val modifiedInput = input.copy(capturedDocument = modifiedCaptured)
        val modifiedRevision =
            base.local.problemRevision.copy(
                documentCanonicalFingerprint =
                    CapturedQuestionDocumentFingerprint.of(modifiedCaptured),
            )
        val modifiedLocal =
            base.local.copy(
                problemRevision = modifiedRevision,
                errorOccurrences =
                    base.local.errorOccurrences.map { occurrence ->
                        occurrence.copy(problemRevision = modifiedRevision)
                    },
            )
        val modifiedRequest = base.task.request.copy(input = modifiedInput)
        val modifiedTask =
            base.task.copy(
                request = modifiedRequest,
                requestFingerprint = ModelTaskFingerprint.of(modifiedRequest),
                output = familyOutput,
            )
        val modifiedCommand =
            ReviewedStudentProblemOrganizationMapper.mapUnsigned(
                modifiedLocal,
                modifiedTask,
                base.proofs,
            )

        assertNotEquals(
            baseCommand.facets.single().bindingCanonicalFingerprint,
            modifiedCommand.facets.single().bindingCanonicalFingerprint,
        )
    }

    @Test
    fun lowConfidenceProblemFamilyIsDroppedWithoutBlockingOrganization() {
        val base = fixture()
        val familyOutput =
            base.output.copy(
                plan =
                    base.output.plan.copy(
                        problemFamily =
                            ProblemFamilySuggestion(
                                familyKey = "untrusted_family",
                                rationaleMarkdown = "同题不同录入与近似变式归入同一族。",
                                confidence = 0.89,
                            ),
                    ),
            )
        val familyTask = base.task.copy(output = familyOutput)

        val command =
            ReviewedStudentProblemOrganizationMapper.mapUnsigned(
                base.local,
                familyTask,
                base.proofs,
            )

        assertTrue(command.facets.isEmpty())
        command.requireExact(
            Fixture(
                task = familyTask,
                output = familyOutput,
                provider = base.provider,
                local = base.local,
                knowledgeAuthority = base.knowledgeAuthority,
                proofs = base.proofs,
            ),
        )
    }

    @Test
    fun semanticTamperMatrixAlwaysFailsClosed() {
        val fixture = fixture()
        val command =
            ReviewedStudentProblemOrganizationMapper.mapUnsigned(
                fixture.local,
                fixture.task,
                fixture.proofs,
            )
        val fullLegacyFacets = completeLegacyFacets()
        val mutations =
            linkedMapOf<String, (OrganizeStudentProblemCommand) -> OrganizeStudentProblemCommand>(
                "alternate solution summary" to { current ->
                    current.copy(
                        solutionAnalysis =
                            current.solutionAnalysis.copy(
                                summaryMarkdown = "替换后的讲解。",
                            ),
                    )
                },
                "alternate step reasoning" to { current ->
                    current.copy(
                        solutionAnalysis =
                            current.solutionAnalysis.copy(
                                steps =
                                    current.solutionAnalysis.steps.mapIndexed { index, step ->
                                        if (index == 0) {
                                            step.copy(reasoningMarkdown = "替换后的推理。")
                                        } else {
                                            step
                                        }
                                    },
                            ),
                    )
                },
                "alternate error rationale" to { current ->
                    current.copy(
                        errorAttributions =
                            current.errorAttributions.mapIndexed { index, attribution ->
                                if (index == 0) {
                                    StudentProblemErrorAttribution(
                                        attributionId = attribution.attributionId,
                                        problemRevision = attribution.problemRevision,
                                        solutionAnalysisId = attribution.solutionAnalysisId,
                                        candidate =
                                            ProblemErrorAttributionCandidate(
                                                resolutionStatus = attribution.resolutionStatus,
                                                rationaleMarkdown = "替换后的错误判断。",
                                                confidence = 1.0,
                                                stepOrdinal = attribution.stepOrdinal,
                                                atomicReferenceId = attribution.atomicReferenceId,
                                                evidenceRefs = attribution.evidenceRefs,
                                            ),
                                        modelProviderId = attribution.modelProviderId,
                                        modelId = attribution.modelId,
                                        analyzerVersion = attribution.analyzerVersion,
                                        resultCanonicalFingerprint =
                                            attribution.resultCanonicalFingerprint,
                                        recordedAtEpochMillis = attribution.recordedAtEpochMillis,
                                    )
                                } else {
                                    attribution
                                }
                            },
                    )
                },
                "omitted classification" to { current ->
                    current.copy(classifications = current.classifications.dropLast(1))
                },
                "extra classification" to { current ->
                    current.copy(
                        classifications =
                            current.classifications +
                                current.classifications.last().copy(
                                    classificationId = "classification:9999:${"a".repeat(32)}",
                                ),
                    )
                },
                "reordered classifications" to { current ->
                    current.copy(classifications = current.classifications.reversed())
                },
                "changed chapter display" to { current ->
                    current.copy(
                        classifications =
                            current.classifications.map { classification ->
                                if (classification.curriculumDisplayName != null) {
                                    classification.copy(curriculumDisplayName = "替换章节")
                                } else {
                                    classification
                                }
                            },
                    )
                },
                "omitted step binding" to { current ->
                    current.copy(stepKnowledgeBindings = current.stepKnowledgeBindings.dropLast(1))
                },
                "duplicate step binding" to { current ->
                    current.copy(
                        stepKnowledgeBindings =
                            current.stepKnowledgeBindings + current.stepKnowledgeBindings.last(),
                    )
                },
                "reordered step bindings" to { current ->
                    current.copy(stepKnowledgeBindings = current.stepKnowledgeBindings.reversed())
                },
                "alternate complete facets" to { current ->
                    current.copy(facets = fullLegacyFacets)
                },
                "partial facets" to { current ->
                    current.copy(facets = fullLegacyFacets.take(1))
                },
            )

        mutations.forEach { (label, mutate) ->
            assertTrue(
                "$label must fail before or during exact owner review",
                runCatching {
                    mutate(command).requireExact(fixture)
                }.isFailure,
            )
        }
    }

    @Test
    fun changedReviewedOutputProviderProblemOrKnowledgeGenerationCannotReuseMapping() {
        val fixture = fixture()
        val command =
            ReviewedStudentProblemOrganizationMapper.mapUnsigned(
                fixture.local,
                fixture.task,
                fixture.proofs,
            )
        val changedOutput =
            fixture.output.copy(
                plan =
                    fixture.output.plan.copy(
                        summaryMarkdown = "模型后来返回了另一份整理结果。",
                    ),
            )
        val changedOutputTask = fixture.task.copy(output = changedOutput)
        val changedProviderTask =
            fixture.task.copy(
                provider =
                    fixture.provider.copy(
                        providerConfigurationVersion = "provider-config-v2",
                    ),
            )
        val foreignRevision =
            fixture.local.problemRevision.copy(
                problem =
                    fixture.local.problemRevision.problem.copy(
                        learnerId = "another-learner",
                    ),
            )
        val changedProblem =
            runCatching {
                command.copy(problemRevision = foreignRevision)
            }
        val mixedGenerationProofs =
            fixture.proofs.mapIndexed { index, proof ->
                if (index == 0) {
                    fixture.knowledgeAuthority.issuer.issue(
                        proof.ref,
                        MANIFEST_FINGERPRINT,
                        ACTIVATION_GENERATION + 1,
                    )
                } else {
                    proof
                }
            }

        assertTrue(runCatching { command.requireExact(fixture, changedOutputTask) }.isFailure)
        assertTrue(runCatching { command.requireExact(fixture, changedProviderTask) }.isFailure)
        assertTrue(changedProblem.isFailure ||
            runCatching { changedProblem.getOrThrow().requireExact(fixture) }.isFailure)
        assertTrue(
            runCatching {
                ReviewedStudentProblemOrganizationMapper.mapUnsigned(
                    fixture.local,
                    fixture.task,
                    mixedGenerationProofs,
                )
            }.isFailure,
        )
    }

    @Test
    fun emptyFacetsNeedExactReviewedMappingWhileCompleteLegacyShapeStillConstructs() {
        val fixture = fixture()
        val command =
            ReviewedStudentProblemOrganizationMapper.mapUnsigned(
                fixture.local,
                fixture.task,
                fixture.proofs,
            )
        val legacyShape = command.copy(facets = completeLegacyFacets())

        command.requireExact(fixture)
        assertEquals(3, legacyShape.facets.size)
        assertTrue(runCatching { legacyShape.requireExact(fixture) }.isFailure)
        assertTrue(
            runCatching {
                command.copy(facets = completeLegacyFacets().take(2))
            }.isFailure,
        )
    }

    private fun OrganizeStudentProblemCommand.requireExact(
        fixture: Fixture,
        task: ModelTaskSnapshot = fixture.task,
    ): ReviewedStudentProblemOrganizationBinding =
        ReviewedStudentProblemOrganizationMapping.requireExact(
            this,
            task,
            fixture.knowledgeAuthority.verifier::verifies,
        )

    private fun fixture(): Fixture {
        val captured =
            CapturedQuestionDocument(
                document =
                    QuestionDocument(
                        id = "question-document",
                        blocks =
                            listOf(
                                ContentBlock.Paragraph(
                                    id = "question-block",
                                    markdown = "根据导数符号判断函数的单调区间。",
                                ),
                            ),
                    ),
                blockEvidence =
                    listOf(
                        QuestionBlockEvidence(
                            blockId = "question-block",
                            sourceAssetId = "source-page-1",
                            sourceRegion = NormalizedSourceRegion(0.1, 0.1, 0.9, 0.9),
                            writingLayer = WritingLayer.HANDWRITTEN,
                            provenance = QuestionBlockProvenance.USER_CORRECTION,
                            confidence = 0.96,
                            reviewStatus = QuestionBlockReviewStatus.USER_CONFIRMED,
                        ),
                    ),
            )
        val source =
            CaptureSourceAssetRef(
                assetId = "source-page-1",
                sha256 = "a".repeat(64),
                width = 1_200,
                height = 1_600,
                pageIndex = 0,
                selectedRegion = NormalizedSourceRegion(0.05, 0.05, 0.95, 0.95),
            )
        val chapterNode =
            KnowledgeBaseNodeContext(
                knowledgeNodeId = "math-topic-functions",
                subject = SubjectKind.MATH,
                canonicalName = "函数与导数",
                aliases = listOf("导数章节"),
                kind = KnowledgeNodeKind.TOPIC,
                granularity = KnowledgeNodeGranularity.TOPIC,
                parentCanonicalName = null,
                taxonomyVersion = TAXONOMY_VERSION,
                catalogProvenance = knowledgeCatalogProvenance(),
                verificationStatus = KnowledgeNodeVerificationStatus.CURATED,
            )
        val signNode = atomNode(
            id = "math-atom-derivative-sign",
            name = "根据导数符号判断单调性",
        )
        val intervalNode = atomNode(
            id = "math-atom-monotonic-interval",
            name = "写出函数的单调区间",
        )
        val input =
            ProblemOrganizationV3Input(
                problemId = "problem-1",
                problemRevisionId = "revision-1",
                practiceUnitId = "practice-unit-1",
                subject = SubjectKind.MATH,
                capturedDocument = captured,
                sourceAssets = listOf(source),
                relationCandidates = emptyList(),
                knowledgeBaseNodes = listOf(chapterNode, signNode, intervalNode),
            )
        val output =
            ProblemOrganizationOutput(
                problemId = input.problemId,
                problemRevisionId = input.problemRevisionId,
                practiceUnitId = input.practiceUnitId,
                plan =
                    ProblemOrganizationPlan(
                        summaryMarkdown = "先判断导数符号，再写出对应的单调区间。",
                        reviewPriorityMarkdown = "需要再巩固导数符号与单调区间的对应关系。",
                        targetedEvidenceLabels = emptyList(),
                        classifications =
                            listOf(
                                ProblemClassificationSuggestion(
                                    dimension = ClassificationDimension.CHAPTER,
                                    displayName = chapterNode.canonicalName,
                                    rationaleMarkdown = "题目属于函数与导数部分。",
                                    confidence = 0.98,
                                ),
                                ProblemClassificationSuggestion(
                                    dimension = ClassificationDimension.KNOWLEDGE,
                                    displayName = "导数应用",
                                    rationaleMarkdown = "解题依赖导数符号和单调区间。",
                                    confidence = 0.96,
                                ),
                            ),
                        relations = emptyList(),
                        schemaVersion = ProblemOrganizationPlan.SCHEMA_VERSION,
                        atomicKnowledge =
                            listOf(
                                atom(
                                    referenceId = "atom-sign",
                                    node = signNode,
                                ),
                                atom(
                                    referenceId = "atom-interval",
                                    node = intervalNode,
                                ),
                            ),
                        stepAttributions =
                            listOf(
                                ProblemStepKnowledgeAttribution(
                                    stepOrdinal = 1,
                                    stepSummaryMarkdown = "判断每个区间内的导数符号。",
                                    atomicReferenceIds =
                                        listOf("atom-sign", "atom-interval"),
                                ),
                                ProblemStepKnowledgeAttribution(
                                    stepOrdinal = 2,
                                    stepSummaryMarkdown = "根据符号写出增区间和减区间。",
                                    atomicReferenceIds =
                                        listOf("atom-sign", "atom-interval"),
                                ),
                            ),
                        errorAttributionCandidates =
                            listOf(
                                ProblemErrorAttributionCandidate(
                                    resolutionStatus =
                                        ProblemErrorAttributionResolutionStatus.RESOLVED,
                                    rationaleMarkdown = "手写过程把导数为负的区间写成了增区间。",
                                    confidence = 0.93,
                                    stepOrdinal = 1,
                                    atomicReferenceId = "atom-sign",
                                    evidenceRefs =
                                        listOf(
                                            ProblemErrorEvidenceRef(
                                                blockId = "question-block",
                                                sourceAssetId = "source-page-1",
                                                evidenceKind =
                                                    ProblemErrorEvidenceKind.STUDENT_WORK,
                                            ),
                                        ),
                                ),
                                ProblemErrorAttributionCandidate(
                                    resolutionStatus =
                                        ProblemErrorAttributionResolutionStatus.UNRESOLVED,
                                    rationaleMarkdown = "另一处批改痕迹不足以确定具体卡点。",
                                    confidence = 0.35,
                                ),
                            ),
                    ),
                modelVersion = "model-version-1",
            )
        val provider =
            ProviderCapabilitySnapshot(
                providerId = "provider-1",
                providerDisplayName = "Provider",
                modelId = "model-1",
                supportedTasks = setOf(ModelTaskKind.PROBLEM_CLASSIFY),
                supportsImageInput = true,
                supportsStructuredOutput = true,
                supportsStreaming = false,
                providerConfigurationVersion = "provider-config-v1",
            )
        val requestId = "organization-request-1"
        val manifest =
            ModelEgressManifest(
                authorizationId = ModelEgressAuthorizationId.forInput(requestId, input),
                subjectId = input.subjectId,
                purpose = ModelEgressPurpose.CLASSIFICATION,
                authorizedTaskKinds = setOf(ModelTaskKind.PROBLEM_CLASSIFY),
                providerId = provider.providerId,
                modelId = provider.modelId,
                providerConfigurationVersion = provider.providerConfigurationVersion,
                promptPolicyVersion = ModelPromptPolicyVersions.PROBLEM_ORGANIZATION,
                approvedAtEpochMillis = 90,
                assets =
                    listOf(
                        ModelEgressAssetGrant(
                            assetId = source.assetId,
                            sha256 = source.sha256,
                            byteSize = 2_048,
                            width = source.width,
                            height = source.height,
                            selectedRegion = source.selectedRegion,
                        ),
                    ),
                disclosedData =
                    ModelEgressManifest.problemOrganizationV3Disclosure(
                        includesSelectedRegion = true,
                    ),
                prohibitedData =
                    ModelEgressManifest.problemOrganizationV3ProhibitedData(
                        includesSelectedRegion = true,
                    ),
            )
        val request =
            ModelTaskRequest(
                requestId = requestId,
                input = input,
                occurredAtEpochMillis = 100,
                egressManifest = manifest,
            )
        val task =
            ModelTaskSnapshot(
                taskId = "organization-task-1",
                request = request,
                requestFingerprint = ModelTaskFingerprint.of(request),
                status = ModelTaskStatus.SUCCEEDED,
                stateVersion = 2,
                stage = ModelTaskStage.COMPLETE,
                userMessage = "整理完成",
                attemptCount = 1,
                provider = provider,
                output = output,
                createdAtEpochMillis = 100,
                updatedAtEpochMillis = 150,
            )
        val revision =
            StudentProblemRevisionRef(
                problem =
                    StudentProblemRef(
                        learnerId = "learner-1",
                        subject = SubjectKind.MATH,
                        problemId = input.problemId,
                        practiceUnitId = input.practiceUnitId,
                    ),
                revisionId = input.problemRevisionId,
                revisionNumber = 1,
                documentCanonicalFingerprint =
                    CapturedQuestionDocumentFingerprint.of(captured),
            )
        val local =
            ReviewedStudentProblemOrganizationLocalContext(
                receiptId = "organization-receipt-1",
                organizationRevision = 1,
                supersedesReceiptId = null,
                previousPayloadCanonicalFingerprint = null,
                problemRevision = revision,
                errorOccurrences =
                    listOf(
                        StudentProblemErrorOccurrenceRef(
                            occurrenceId = "error-occurrence-1",
                            problemRevision = revision,
                            occurrenceCanonicalFingerprint = "b".repeat(64),
                        ),
                    ),
                reviewIssuedAtEpochMillis = 160,
                reviewExpiresAtEpochMillis = 260,
                completedAtEpochMillis = 170,
            )
        val authority = KnowledgeReferenceProofAuthority.create()
        val proofs =
            listOf(chapterNode, signNode, intervalNode).map { node ->
                authority.issuer.issue(
                    KnowledgeNodeRef(
                        subject = SubjectKind.MATH,
                        knowledgeNodeId = node.knowledgeNodeId,
                        taxonomyVersion = node.taxonomyVersion,
                        knowledgePackVersion = KNOWLEDGE_PACK_VERSION,
                    ),
                    MANIFEST_FINGERPRINT,
                    ACTIVATION_GENERATION,
                )
            }
        return Fixture(
            task = task,
            output = output,
            provider = provider,
            local = local,
            knowledgeAuthority = authority,
            proofs = proofs,
        )
    }

    private fun atomNode(
        id: String,
        name: String,
    ): KnowledgeBaseNodeContext =
        KnowledgeBaseNodeContext(
            knowledgeNodeId = id,
            subject = SubjectKind.MATH,
            canonicalName = name,
            aliases = emptyList(),
            kind = KnowledgeNodeKind.REASONING,
            granularity = KnowledgeNodeGranularity.ATOMIC,
            parentCanonicalName = "导数应用",
            taxonomyVersion = TAXONOMY_VERSION,
            catalogProvenance = knowledgeCatalogProvenance(),
            verificationStatus = KnowledgeNodeVerificationStatus.CURATED,
        )

    private fun knowledgeCatalogProvenance() =
        KnowledgeBaseCatalogProvenance(
            packId = "pack",
            knowledgePackVersion = "pack-v1",
            taxonomyVersion = TAXONOMY_VERSION,
            manifestFingerprint = "a".repeat(64),
            activationGeneration = 7L,
        )

    private fun atom(
        referenceId: String,
        node: KnowledgeBaseNodeContext,
    ): AtomicKnowledgeSuggestion =
        AtomicKnowledgeSuggestion(
            referenceId = referenceId,
            canonicalName = node.canonicalName,
            aliases = emptyList(),
            kind = node.kind,
            parentKnowledgeDisplayName = checkNotNull(node.parentCanonicalName),
            matchedKnowledgeNodeId = node.knowledgeNodeId,
            prerequisiteReferenceIds = emptyList(),
            observableOutcomeMarkdown = "能在本题步骤中正确使用这个知识点。",
            boundaryMarkdown = "只用于当前步骤所需的判断。",
            confidence = 0.96,
        )

    private fun completeLegacyFacets(): List<StudentProblemOrganizationFacet> =
        StudentProblemOrganizationFacetDimension.entries
            .sortedBy { it.name }
            .mapIndexed { index, dimension ->
                StudentProblemOrganizationFacet(
                    dimension = dimension,
                    familyId = "legacy-family-$index",
                    familyVersion = "legacy-v1",
                    bindingCanonicalFingerprint = "${index + 1}".repeat(64),
                )
            }

    private data class Fixture(
        val task: ModelTaskSnapshot,
        val output: ProblemOrganizationOutput,
        val provider: ProviderCapabilitySnapshot,
        val local: ReviewedStudentProblemOrganizationLocalContext,
        val knowledgeAuthority: KnowledgeReferenceProofAuthority,
        val proofs: List<KnowledgeReferenceProofAuthority.Proof>,
    )

    private companion object {
        const val TAXONOMY_VERSION = "math-v1"
        const val KNOWLEDGE_PACK_VERSION = "math-pack-v1"
        const val MANIFEST_FINGERPRINT =
            "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"
        const val ACTIVATION_GENERATION = 7L
    }
}
