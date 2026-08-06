package com.tingyun.smartmistakebook.core.data.mistake

import com.tingyun.smartmistakebook.core.data.knowledge.ReviewedProblemKnowledgeContextRepository
import com.tingyun.smartmistakebook.core.database.MistakeDetailRecord
import com.tingyun.smartmistakebook.core.database.MistakeRecord
import com.tingyun.smartmistakebook.core.database.StudyDatabasePort
import com.tingyun.smartmistakebook.core.domain.MistakeRevisionKey
import com.tingyun.smartmistakebook.core.domain.StudyProfileOverview
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocument
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocumentCodec
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocumentFingerprint
import com.tingyun.smartmistakebook.core.model.ContentBlock
import com.tingyun.smartmistakebook.core.model.KnowledgeBaseCatalogProvenance
import com.tingyun.smartmistakebook.core.model.KnowledgeBaseNodeContext
import com.tingyun.smartmistakebook.core.model.KnowledgeNodeGranularity
import com.tingyun.smartmistakebook.core.model.KnowledgeNodeKind
import com.tingyun.smartmistakebook.core.model.KnowledgeNodeVerificationStatus
import com.tingyun.smartmistakebook.core.model.ModelExecutionLocation
import com.tingyun.smartmistakebook.core.model.ModelTaskKind
import com.tingyun.smartmistakebook.core.model.NormalizedSourceRegion
import com.tingyun.smartmistakebook.core.model.ProblemOrganizationInput
import com.tingyun.smartmistakebook.core.model.ProviderCapabilitySnapshot
import com.tingyun.smartmistakebook.core.model.QuestionBlockEvidence
import com.tingyun.smartmistakebook.core.model.QuestionBlockProvenance
import com.tingyun.smartmistakebook.core.model.QuestionBlockReviewStatus
import com.tingyun.smartmistakebook.core.model.QuestionDocument
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.WritingLayer
import java.lang.reflect.Proxy
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomMistakeOrganizationKnowledgeBoundaryTest {
    @Test
    fun prepareReadsIndependentKnowledgeContextAndNeverCallsLegacyKnowledgeQueries() = runBlocking {
        val document = committedDocument()
        val mistake =
            MistakeRecord(
                entryId = ENTRY_ID,
                problemId = PROBLEM_ID,
                problemRevisionId = REVISION_ID,
                practiceUnitId = PRACTICE_ID,
                sourceKey = null,
                subject = SubjectKind.MATH.name,
                title = "函数最值",
                problemMarkdown = "利用驻点确定函数最值。",
                status = "ACTIVE",
                createdAtEpochMillis = 1,
                nextReviewAtEpochMillis = null,
                retrievability = null,
            )
        val detail =
            MistakeDetailRecord(
                entryId = ENTRY_ID,
                problemId = PROBLEM_ID,
                problemRevisionId = REVISION_ID,
                revisionNumber = 1,
                subject = SubjectKind.MATH.name,
                title = mistake.title,
                problemMarkdown = mistake.problemMarkdown,
                questionDocumentSnapshot = CapturedQuestionDocumentCodec.encode(document),
                contentFingerprint = CapturedQuestionDocumentFingerprint.of(document),
                sourceAssets = emptyList(),
                practiceUnitId = PRACTICE_ID,
            )
        val legacyKnowledgeCalls = mutableListOf<String>()
        val database =
            Proxy.newProxyInstance(
                StudyDatabasePort::class.java.classLoader,
                arrayOf(StudyDatabasePort::class.java),
            ) { _, method, _ ->
                when (method.name) {
                    "observeMistakes" -> flowOf(listOf(mistake))
                    "readExactMistakeDetail" -> detail
                    "readSubjectKnowledgeRecallCandidates",
                    "readKnowledgeNodeRelationsForDependents",
                    "readKnowledgeNodesByIds",
                    "replaceKnowledgeBase",
                    -> {
                        legacyKnowledgeCalls += method.name
                        error("Legacy knowledge authority must not be used")
                    }
                    "close" -> Unit
                    "toString" -> "KnowledgeBoundaryStudyDatabase"
                    "hashCode" -> System.identityHashCode(this)
                    "equals" -> false
                    else -> error("Unexpected operational database call: ${method.name}")
                }
            } as StudyDatabasePort
        var independentReads = 0
        val expectedContext = knowledgeContext()
        val independentKnowledge =
            ReviewedProblemKnowledgeContextRepository { subject, questionText ->
                independentReads += 1
                assertEquals(SubjectKind.MATH, subject)
                assertTrue("驻点" in questionText)
                listOf(expectedContext)
            }
        val repository =
            RoomMistakeOrganizationRepository(
                legacyBusiness = database,
                organizationWork = database,
                legacyReauthorization = database,
                modelTasks = database,
                assetDocuments = database,
                knowledgeContext = independentKnowledge,
            )

        val preparation =
            repository.prepare(
                key = MistakeRevisionKey(ENTRY_ID, PROBLEM_ID, REVISION_ID),
                profile = StudyProfileOverview(),
                provider = provider(),
                attempt = 0,
                occurredAtEpochMillis = 2_000,
                approvedAtEpochMillis = 1_000,
            )

        val input = preparation.request.input as ProblemOrganizationInput
        assertEquals(listOf(expectedContext), input.knowledgeBaseNodes)
        assertEquals(1, independentReads)
        assertTrue(legacyKnowledgeCalls.isEmpty())
    }

    private fun committedDocument() =
        CapturedQuestionDocument(
            document =
                QuestionDocument(
                    id = "document-1",
                    blocks =
                        listOf(
                            ContentBlock.Paragraph(
                                id = "stem",
                                markdown = "利用驻点确定函数最值。",
                            ),
                        ),
                ),
            blockEvidence =
                listOf(
                    QuestionBlockEvidence(
                        blockId = "stem",
                        sourceAssetId = "asset-1",
                        sourceRegion = NormalizedSourceRegion(0.0, 0.0, 1.0, 1.0),
                        writingLayer = WritingLayer.PRINTED,
                        provenance = QuestionBlockProvenance.USER_CORRECTION,
                        reviewStatus = QuestionBlockReviewStatus.USER_CONFIRMED,
                    ),
                ),
        )

    private fun knowledgeContext() =
        KnowledgeBaseNodeContext(
            knowledgeNodeId = "math-atomic-extrema-candidates",
            subject = SubjectKind.MATH,
            canonicalName = "确定函数最值的候选位置",
            aliases = listOf("极值候选点"),
            kind = KnowledgeNodeKind.PROCEDURE,
            granularity = KnowledgeNodeGranularity.ATOMIC,
            parentCanonicalName = "函数最值",
            taxonomyVersion = "taxonomy-v1",
            catalogProvenance =
                KnowledgeBaseCatalogProvenance(
                    packId = "pack",
                    knowledgePackVersion = "pack-v1",
                    taxonomyVersion = "taxonomy-v1",
                    manifestFingerprint = "a".repeat(64),
                    activationGeneration = 1L,
                ),
            verificationStatus = KnowledgeNodeVerificationStatus.SOURCE_GROUNDED,
        )

    private fun provider() =
        ProviderCapabilitySnapshot(
            providerId = "local-test-provider",
            providerDisplayName = "本地测试模型",
            modelId = "test-model",
            supportedTasks = setOf(ModelTaskKind.PROBLEM_CLASSIFY),
            supportsImageInput = false,
            supportsStructuredOutput = true,
            supportsStreaming = false,
            executionLocation = ModelExecutionLocation.LOCAL_NO_EGRESS,
            providerConfigurationVersion = "test-config-v1",
        )

    private companion object {
        const val ENTRY_ID = "entry-1"
        const val PROBLEM_ID = "problem-1"
        const val REVISION_ID = "revision-1"
        const val PRACTICE_ID = "practice-1"
    }
}
