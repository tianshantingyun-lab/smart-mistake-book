package com.tingyun.smartmistakebook.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StudentFacingLanguagePolicyTest {
    @Test
    fun allowsOrdinaryKnowledgePointAndKnowledgeStoreSettingsLanguage() {
        val output = TutorRespondOutput(
            sessionId = "session-1",
            draftRevisionNumber = 1,
            questionDocumentId = "question-1",
            responseOrdinal = 1,
            messageMarkdown = "这个知识点可以从函数图像的增减趋势理解。",
            modelVersion = "model-v1",
        )

        assertEquals("student-facing-language-v8", StudentFacingLanguagePolicy.POLICY_VERSION)
        assertTrue(output.messageMarkdown.contains("知识点"))
        listOf(
            "你可以在设置里关闭知识库功能。",
            "你可以在設定中選擇是否啟用知識庫。",
            "You can turn off the knowledge base in Settings.",
            "可以在错题本里检索这道题。",
        ).forEach { value ->
            StudentFacingLanguagePolicy.requirePlainLanguage(value, "Tutor response")
        }
    }

    @Test
    fun planAndResponseRejectInternalMaterialVocabulary() {
        val planFailure = runCatching {
            TutorTurnPlan(
                openingMarkdown = "我从内部资料中找到一个方法。",
                solutionMarkdown = "先观察函数的变化趋势。",
                alternateMethodMarkdown = "也可以画图判断。",
                difficultyReasonMarkdown = "关键是区分定义域。",
                targetedEvidenceLabels = emptyList(),
                inferredKnowledgeLabels = listOf("函数单调性"),
            )
        }.exceptionOrNull()
        val responseFailure = runCatching {
            TutorRespondOutput(
                sessionId = "session-1",
                draftRevisionNumber = 1,
                questionDocumentId = "question-1",
                responseOrdinal = 1,
                messageMarkdown = "知识库的来源状态显示这个结论可用。",
                modelVersion = "model-v1",
            )
        }.exceptionOrNull()

        assertTrue(planFailure is IllegalArgumentException)
        assertTrue(responseFailure is IllegalArgumentException)
    }

    @Test
    fun rejectsSeparatorAndCaseVariantsOfInternalVocabulary() {
        val variants = listOf(
            "MANIFEST_fingerprint",
            "The provider configuration activation-generation is current.",
            "reviewed.teaching/reference",
            "内 部—资 料",
            "清单_指纹",
        )

        variants.forEach { variant ->
            assertTrue(
                variant,
                runCatching {
                    StudentFacingLanguagePolicy.requirePlainLanguage(variant, "Tutor response")
                }.exceptionOrNull() is IllegalArgumentException,
            )
        }
        StudentFacingLanguagePolicy.requirePlainLanguage(
            "这个知识点可以结合函数图像来理解。",
            "Tutor response",
        )
    }

    @Test
    fun rejectsSemanticFamiliesAcrossScriptsSynonymsAndInterstitialWords() {
        val variants = listOf(
            "我参考了内部用于讲解的参考材料。",
            "內容來自系統內部維護的教學參考資料。",
            "This used an INTERNAL carefully reviewed instructional source.",
            "结论来自知识数据库。",
            "結論來自知識資料庫。",
            "This came from a knowledge database.",
            "知识库里的来源目前处于可用状态。",
            "The knowledge base provenance is current.",
            "清单当前使用的指纹仍然有效。",
        )

        variants.forEach { variant ->
            assertTrue(
                variant,
                runCatching {
                    StudentFacingLanguagePolicy.requirePlainLanguage(
                        variant,
                        "Tutor response",
                    )
                }.exceptionOrNull() is IllegalArgumentException,
            )
        }
    }

    @Test
    fun ordinaryWordsInDifferentMeaningsDoNotBecomeASplitProtocolTerm() {
        listOf(
            "这道题来源于教材，你目前的学习状态很稳定。",
            "资料里有三种题型，可以按类型逐一练习。",
            "先学习这一节；投影图放在下一页。",
            "氯原子得电子的能力更强。",
            "学习空间几何时先看投影图。",
            "氧原子的核外电子是本题的知识点。",
            "The source is your textbook, and your current study status looks steady.",
            "Use retrieval practice today. Recall the formula again tomorrow.",
        ).forEach { value ->
            StudentFacingLanguagePolicy.requirePlainLanguage(value, "Tutor response")
        }
    }

    @Test
    fun localProtocolCoOccurrenceStillRejectsDisguisedInternalTerms() {
        listOf(
            "知识库中的来源目前显示为可用状态。",
            "清单当前使用的指纹仍然有效。",
            "The manifest currently uses this fingerprint.",
            "检索阶段返回的召回结果如下。",
        ).forEach { value ->
            assertTrue(
                value,
                runCatching {
                    StudentFacingLanguagePolicy.requirePlainLanguage(value, "Tutor response")
                }.exceptionOrNull() is IllegalArgumentException,
            )
        }
    }

    @Test
    fun separatorFoldingCannotHideInternalPhrasesAcrossLineBreaks() {
        listOf(
            "内\n部资料显示这个结论可用。",
            "source_\ngrounded",
            "knowledge-\nnode",
            "证据\n权重为 0.6。",
            "ｓｏｕｒｃｅ＿ｇｒｏｕｎｄｅｄ",
            "sou\u200Brce grounded",
            "知\u200B识\u200C节点",
            "内部\u200D资料",
            "sou\u200Brce\u200Bgrounded",
        ).forEach { value ->
            assertTrue(
                value,
                runCatching {
                    StudentFacingLanguagePolicy.requirePlainLanguage(value, "Tutor response")
                }.exceptionOrNull() is IllegalArgumentException,
            )
        }
    }

    @Test
    fun latinWordBoundariesDoNotRejectOrdinaryEnglish() {
        listOf(
            "The resource is grounded in the passage.",
            "This knowledgeable student explained the node clearly.",
            "Retrieval practice helps students recall new vocabulary.",
            "Positive evidence and negative evidence can test a biological hypothesis.",
            "Compare each candidate node in the phylogenetic tree.",
            "We reviewed the teaching materials before class.",
            "Membrane activation leads to the generation of an action potential.",
            "Gene activation controls the generation of messenger RNA.",
        ).forEach { value ->
            StudentFacingLanguagePolicy.requirePlainLanguage(value, "Tutor response")
        }
    }

    @Test
    fun semanticFamiliesCannotHideAcrossLineBreaksOrLatinLookalikes() {
        listOf(
            "This system-maintained\ncarefully reviewed instructional source is current.",
            "The knоwledge nοde was selected.",
            "The knоwledge base prοvenance is current.",
            "This sοurce grоunded result came from the model.",
            "Negative evidence changed the learner mastery state.",
        ).forEach { value ->
            assertTrue(
                value,
                runCatching {
                    StudentFacingLanguagePolicy.requirePlainLanguage(value, "Tutor response")
                }.exceptionOrNull() is IllegalArgumentException,
            )
        }
    }

    @Test
    fun masteryImplementationVocabularyNeverReachesStudentText() {
        listOf(
            "当前知识节点的置信度为 0.82。",
            "这条负向证据的证据权重是 0.6。",
            "错误归因已经写入。",
            "The candidate node has a confidence score of 0.82.",
            "The error attribution produced a negative evidence weight.",
        ).forEach { value ->
            assertTrue(
                value,
                runCatching {
                    StudentFacingLanguagePolicy.requirePlainLanguage(value, "Tutor response")
                }.exceptionOrNull() is IllegalArgumentException,
            )
        }
    }
}
