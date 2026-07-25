package com.tingyun.smartmistakebook.core.data

import com.tingyun.smartmistakebook.core.database.AssessmentItemSnapshotSeedRecord
import com.tingyun.smartmistakebook.core.database.ErrorBookEntrySeedRecord
import com.tingyun.smartmistakebook.core.database.KnowledgeBindingSeedRecord
import com.tingyun.smartmistakebook.core.database.KnowledgeNodeSeedRecord
import com.tingyun.smartmistakebook.core.database.PracticeUnitSeedRecord
import com.tingyun.smartmistakebook.core.database.ProblemRelationSeedRecord
import com.tingyun.smartmistakebook.core.database.ProblemRevisionSeedRecord
import com.tingyun.smartmistakebook.core.database.ProblemSeedRecord
import com.tingyun.smartmistakebook.core.database.StudyDbValue
import com.tingyun.smartmistakebook.core.database.StudySeedBundle
import com.tingyun.smartmistakebook.core.model.AssessmentEvidenceSnapshot
import com.tingyun.smartmistakebook.core.model.AssessmentSnapshotVerification
import com.tingyun.smartmistakebook.core.model.CalibrationSnapshot
import com.tingyun.smartmistakebook.core.model.CalibrationSupport
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocument
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocumentCodec
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocumentFingerprint
import com.tingyun.smartmistakebook.core.model.ContentBlock
import com.tingyun.smartmistakebook.core.model.EvidenceAttributionCertainty
import com.tingyun.smartmistakebook.core.model.EvidenceAttributionRole
import com.tingyun.smartmistakebook.core.model.KnowledgeEvidenceAttribution
import com.tingyun.smartmistakebook.core.model.NormalizedSourceRegion
import com.tingyun.smartmistakebook.core.model.QuestionBlockEvidence
import com.tingyun.smartmistakebook.core.model.QuestionBlockProvenance
import com.tingyun.smartmistakebook.core.model.QuestionBlockReviewStatus
import com.tingyun.smartmistakebook.core.model.QuestionDocument
import com.tingyun.smartmistakebook.core.model.StructuredChoice
import com.tingyun.smartmistakebook.core.model.TeachingArtifactVerification
import com.tingyun.smartmistakebook.core.model.TutorAssessmentItem
import com.tingyun.smartmistakebook.core.model.TutorChoice
import com.tingyun.smartmistakebook.core.model.VerifiedTeachingArtifact
import com.tingyun.smartmistakebook.core.model.VerifiedTeachingFollowUp
import com.tingyun.smartmistakebook.core.model.WritingLayer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Small, auditable M1 catalog used to prove the real Room-backed learning loop.
 *
 * The catalog contains five original, verified questions. Four are visibly labelled local
 * examples in the initial mistake book; the derivative-sign question is the Tutor example and is
 * added only after the learner explicitly saves it. No attempt, mastery, streak, or review history
 * is seeded, so the UI cannot present invented learning statistics.
 */
object M1CuratedStudySeed {
    const val FIXTURE_VERSION = "m1-curated-v1"
    const val TAXONOMY_VERSION = "cn-highschool-m1-v1"
    const val TUTOR_PROBLEM_ID = "problem:m1:math:derivative-sign-change"
    const val TUTOR_PRACTICE_UNIT_ID = "practice:m1:derivative-sign-change:whole"

    fun teachingArtifactForPracticeUnit(practiceUnitId: String): VerifiedTeachingArtifact? =
        curatedQuestions().firstOrNull { it.practiceUnitId == practiceUnitId }?.teachingArtifact()

    fun evidenceSnapshotForAssessment(assessmentItemId: String): AssessmentEvidenceSnapshot? =
        curatedQuestions().firstOrNull { it.assessmentId == assessmentItemId }?.evidenceSnapshot()

    fun bundle(includeTutorMistake: Boolean = false): StudySeedBundle {
        val questions = curatedQuestions()
        return StudySeedBundle(
            problems = questions.map(QuestionSeed::problemRecord),
            revisions = questions.map(QuestionSeed::revisionRecord),
            practiceUnits = questions.map(QuestionSeed::practiceUnitRecord),
            errorBookEntries = questions.filter { question ->
                question.initiallyInMistakeBook ||
                    (includeTutorMistake && question.slug == TUTOR_QUESTION_SLUG)
            }
                .map(QuestionSeed::errorBookEntryRecord),
            knowledgeNodes = questions.map(QuestionSeed::knowledgeNodeRecord),
            knowledgeBindings = questions.map(QuestionSeed::knowledgeBindingRecord),
            relations = listOf(
                ProblemRelationSeedRecord(
                    relationId = "relation:m1:derivative-sign-prerequisite-extrema",
                    sourceProblemId = TUTOR_PROBLEM_ID,
                    targetProblemId = "problem:m1:math:closed-interval-extrema",
                    relationType = StudyDbValue.RelationType.PREREQUISITE_OF,
                    status = StudyDbValue.RelationStatus.ACTIVE,
                    sourceBasisRevisionId = "revision:m1:derivative-sign-change:r1",
                    targetBasisRevisionId = "revision:m1:closed-interval-extrema:r1",
                    confidence = 1.0,
                    createdAtEpochMillis = SEED_CREATED_AT_EPOCH_MILLIS,
                    updatedAtEpochMillis = SEED_CREATED_AT_EPOCH_MILLIS,
                ),
            ),
            assessmentItems = questions.map(QuestionSeed::assessmentItemRecord),
        )
    }

    private fun curatedQuestions() = listOf(
        QuestionSeed(
            slug = "closed-interval-extrema",
            subject = "MATH",
            title = "闭区间上的函数最值",
            problemMarkdown = """
                已知函数 ${'$'}f(x)=x^3-3x+1${'$'}，求它在闭区间 ${'$'}[-2,2]${'$'} 上的最大值与最小值。
            """.trimIndent(),
            choices = listOf(
                "A" to "最大值为 2，最小值为 -2",
                "B" to "最大值为 3，最小值为 -1",
                "C" to "最大值为 1，最小值为 -3",
                "D" to "最大值为 4，最小值为 -4",
            ),
            correctChoiceId = "B",
            explanationMarkdown = """
                先求导：${'$'}f'(x)=3x^2-3=3(x-1)(x+1)${'$'}，区间内的驻点为 ${'$'}x=-1,1${'$'}。
                闭区间最值必须同时比较驻点和两个端点：
                ${'$'}f(-2)=-1, f(-1)=3, f(1)=-1, f(2)=3${'$'}。
                因此最大值为 3，最小值为 -1，选择 B。
            """.trimIndent(),
            knowledgeCode = "math.derivative.closed_interval_extrema",
            knowledgeName = "利用导数求闭区间最值",
            estimatedSeconds = 240,
            initiallyInMistakeBook = true,
        ),
        QuestionSeed(
            slug = "derivative-sign-change",
            subject = "MATH",
            title = "由导数符号判断单调区间",
            problemMarkdown = """
                设函数 ${'$'}f(x)${'$'} 在实数集上可导，且
                ${'$'}f'(x)=(x-1)(x+2)${'$'}。下列关于 ${'$'}f(x)${'$'} 单调性的判断正确的是哪一项？
            """.trimIndent(),
            choices = listOf(
                "A" to "在 (-∞,-2) 与 (1,+∞) 上递增，在 (-2,1) 上递减",
                "B" to "在 (-∞,-2) 与 (1,+∞) 上递减，在 (-2,1) 上递增",
                "C" to "在 (-∞,1) 上递增，在 (1,+∞) 上递减",
                "D" to "在实数集上始终递增",
            ),
            correctChoiceId = "A",
            explanationMarkdown = """
                导数在 ${'$'}x=-2,1${'$'} 处为零。分别在三个区间取测试点，
                ${'$'}f'(x)${'$'} 的符号依次为正、负、正，所以原函数依次递增、递减、递增。
                因而递增区间是 ${'$'}(-\infty,-2)${'$'} 与 ${'$'}(1,+\infty)${'$'}，选择 A。
            """.trimIndent(),
            knowledgeCode = "math.derivative.monotonicity",
            knowledgeName = "导数符号与函数单调性",
            estimatedSeconds = 150,
            initiallyInMistakeBook = false,
        ),
        QuestionSeed(
            slug = "electromagnetic-direction",
            subject = "PHYSICS",
            title = "运动导体中的电荷偏转方向",
            problemMarkdown = """
                一根竖直金属棒在水平面内向右匀速运动，空间中存在垂直纸面向里的匀强磁场。
                忽略其他作用，达到稳定后金属棒哪一端电势较高？
            """.trimIndent(),
            choices = listOf(
                "A" to "下端电势较高",
                "B" to "上端电势较高",
                "C" to "两端电势始终相等",
                "D" to "两端电势高低周期性交替",
            ),
            correctChoiceId = "B",
            explanationMarkdown = """
                对正电荷使用 ${'$'}\vec F=q\vec v\times\vec B${'$'}：速度向右、磁场向里，
                叉乘方向向上。正电荷向上端聚集，直到电场力与磁场力平衡，因此上端电势更高，选择 B。
            """.trimIndent(),
            knowledgeCode = "physics.electromagnetism.lorentz_force_direction",
            knowledgeName = "洛伦兹力方向与动生电动势",
            estimatedSeconds = 150,
            initiallyInMistakeBook = true,
        ),
        QuestionSeed(
            slug = "conic-eccentricity",
            subject = "MATH",
            title = "椭圆离心率",
            problemMarkdown = """
                椭圆 ${'$'}\frac{x^2}{25}+\frac{y^2}{9}=1${'$'} 的离心率是多少？
            """.trimIndent(),
            choices = listOf(
                "A" to "3/5",
                "B" to "2/3",
                "C" to "4/5",
                "D" to "5/4",
            ),
            correctChoiceId = "C",
            explanationMarkdown = """
                标准方程给出 ${'$'}a=5,b=3${'$'}，所以
                ${'$'}c=\sqrt{a^2-b^2}=\sqrt{25-9}=4${'$'}。
                离心率 ${'$'}e=c/a=4/5${'$'}，选择 C。
            """.trimIndent(),
            knowledgeCode = "math.conic.ellipse_eccentricity",
            knowledgeName = "椭圆标准方程与离心率",
            estimatedSeconds = 120,
            initiallyInMistakeBook = true,
        ),
        QuestionSeed(
            slug = "chemical-equilibrium",
            subject = "CHEMISTRY",
            title = "用浓度商判断平衡移动",
            problemMarkdown = """
                恒温下，密闭容器中的反应 ${'$'}N_2O_4(g) \rightleftharpoons 2NO_2(g)${'$'} 已达到平衡。
                瞬间将容器体积压缩为原来的一半。只考虑体积突变后的浓度商与平衡常数，平衡将怎样移动？
            """.trimIndent(),
            choices = listOf(
                "A" to "不移动，因为温度没有改变",
                "B" to "向右移动，因为各物质浓度都增大",
                "C" to "先向右移动，再向左移动",
                "D" to "向左移动，因为突变后 Qc 大于 Kc",
            ),
            correctChoiceId = "D",
            explanationMarkdown = """
                体积减半的瞬间，各气体浓度都变为原来的 2 倍。
                对 ${'$'}N_2O_4 \rightleftharpoons 2NO_2${'$'}，
                ${'$'}Q_c=[NO_2]^2/[N_2O_4]${'$'}，因此突变后 ${'$'}Q'_c=2K_c>K_c${'$'}。
                系统会向左移动以降低浓度商，选择 D。
            """.trimIndent(),
            knowledgeCode = "chemistry.equilibrium.reaction_quotient",
            knowledgeName = "浓度商与化学平衡移动",
            estimatedSeconds = 180,
            initiallyInMistakeBook = true,
        ),
    )

    private data class QuestionSeed(
        val slug: String,
        val subject: String,
        val title: String,
        val problemMarkdown: String,
        val choices: List<Pair<String, String>>,
        val correctChoiceId: String,
        val explanationMarkdown: String,
        val knowledgeCode: String,
        val knowledgeName: String,
        val estimatedSeconds: Int,
        val initiallyInMistakeBook: Boolean,
    ) {
        private val problemId = "problem:m1:${subject.lowercase()}:$slug"
        private val revisionId = "revision:m1:$slug:r1"
        val practiceUnitId = "practice:m1:$slug:whole"
        private val answerSpecId = "answer:m1:$slug:r1"
        val assessmentId = "assessment:m1:$slug:r1"
        private val knowledgeNodeId = "knowledge:m1:$knowledgeCode"
        private val optionsSnapshot = choices.toOptionsSnapshot()
        private val answerSpecSnapshot = "{\"schema\":\"single-choice.v1\",\"correctChoiceId\":\"$correctChoiceId\"}"
        private val capturedQuestionDocument by lazy {
            val blocks = listOf(
                ContentBlock.Paragraph("$slug-stem", problemMarkdown),
                ContentBlock.ChoiceGroup(
                    id = "$slug-choices",
                    promptMarkdown = "请选择一个答案",
                    choices = choices.map { (choiceId, markdown) ->
                        StructuredChoice(id = choiceId, markdown = markdown)
                    },
                ),
            )
            CapturedQuestionDocument(
                document = QuestionDocument(
                    id = "document:m1:$slug:r1",
                    title = title,
                    blocks = blocks,
                ),
                blockEvidence = blocks.map { block ->
                    QuestionBlockEvidence(
                        blockId = block.id,
                        sourceAssetId = "curated-reference:$slug",
                        sourceRegion = NormalizedSourceRegion(0.0, 0.0, 1.0, 1.0),
                        writingLayer = WritingLayer.PRINTED,
                        provenance = QuestionBlockProvenance.USER_CORRECTION,
                        reviewStatus = QuestionBlockReviewStatus.USER_CONFIRMED,
                    )
                },
            )
        }

        fun problemRecord() = ProblemSeedRecord(
            problemId = problemId,
            canonicalFingerprint = sha256("$subject\n$problemMarkdown"),
            subject = subject,
            createdAtEpochMillis = SEED_CREATED_AT_EPOCH_MILLIS,
        )

        fun revisionRecord() = ProblemRevisionSeedRecord(
            revisionId = revisionId,
            problemId = problemId,
            revisionNumber = 1,
            title = title,
            problemMarkdown = problemMarkdown,
            questionDocumentSnapshot = CapturedQuestionDocumentCodec.encode(capturedQuestionDocument),
            answerSpecId = answerSpecId,
            answerSpecSnapshot = answerSpecSnapshot,
            answerVerificationStatus = StudyDbValue.VerificationStatus.VERIFIED,
            sourceType = "CURATED_LOCAL_EXAMPLE",
            sourceReference = FIXTURE_VERSION,
            contentFingerprint = CapturedQuestionDocumentFingerprint.of(capturedQuestionDocument),
            createdAtEpochMillis = SEED_CREATED_AT_EPOCH_MILLIS,
        )

        fun practiceUnitRecord() = PracticeUnitSeedRecord(
            practiceUnitId = practiceUnitId,
            problemId = problemId,
            problemRevisionId = revisionId,
            unitKey = "whole",
            unitKind = "WHOLE_PROBLEM",
            title = title,
            promptMarkdown = problemMarkdown,
            estimatedSeconds = estimatedSeconds,
            createdAtEpochMillis = SEED_CREATED_AT_EPOCH_MILLIS,
        )

        fun errorBookEntryRecord() = ErrorBookEntrySeedRecord(
            entryId = "entry:m1:$slug",
            practiceUnitId = practiceUnitId,
            problemId = problemId,
            currentRevisionId = revisionId,
            sourceKey = "seed:$FIXTURE_VERSION:$slug",
            acceptedAtEpochMillis = SEED_CREATED_AT_EPOCH_MILLIS,
            updatedAtEpochMillis = SEED_CREATED_AT_EPOCH_MILLIS,
        )

        fun knowledgeNodeRecord() = KnowledgeNodeSeedRecord(
            knowledgeNodeId = knowledgeNodeId,
            stableCode = knowledgeCode,
            subject = subject,
            displayName = knowledgeName,
            parentKnowledgeNodeId = null,
            taxonomyVersion = TAXONOMY_VERSION,
            createdAtEpochMillis = SEED_CREATED_AT_EPOCH_MILLIS,
        )

        fun knowledgeBindingRecord() = KnowledgeBindingSeedRecord(
            bindingId = "binding:m1:$slug:$knowledgeCode",
            practiceUnitId = practiceUnitId,
            knowledgeNodeId = knowledgeNodeId,
            basisRevisionId = revisionId,
            strength = 1.0,
            sourceType = "CURATED_VERIFIED",
            taxonomyVersion = TAXONOMY_VERSION,
            acceptedAtEpochMillis = SEED_CREATED_AT_EPOCH_MILLIS,
        )

        fun assessmentItemRecord() = AssessmentItemSnapshotSeedRecord(
            assessmentItemSnapshotId = assessmentId,
            itemRevision = 1,
            practiceUnitId = practiceUnitId,
            problemRevisionId = revisionId,
            tutorContentSnapshotId = "teaching:m1:$slug:r1",
            promptMarkdown = problemMarkdown,
            optionsSnapshot = optionsSnapshot,
            answerSpecSnapshot = answerSpecSnapshot,
            verificationStatus = StudyDbValue.VerificationStatus.VERIFIED,
            assessmentEligibility = StudyDbValue.AssessmentEligibility.ATTEMPT_ELIGIBLE,
            scoringMode = StudyDbValue.ScoringMode.AUTO_VERIFIED,
            learnerSnapshotVersion = "seed-no-learning-history",
            projectionCheckpoint = 0L,
            hintLevelAtPresentation = 0,
            answerRevealState = "HIDDEN",
            createdAtEpochMillis = SEED_CREATED_AT_EPOCH_MILLIS,
        )

        fun teachingArtifact(): VerifiedTeachingArtifact {
            val followUps = listOf(
                VerifiedTeachingFollowUp(
                    id = "follow-up:m1:$slug:method",
                    label = "换种方法",
                    contentMarkdown = explanationMarkdown,
                ),
                VerifiedTeachingFollowUp(
                    id = "follow-up:m1:$slug:key",
                    label = "关键判断是什么",
                    contentMarkdown = "先独立判断关键量，再核对选项；不要从选项表面措辞反推答案。",
                ),
            )
            return VerifiedTeachingArtifact(
                id = "teaching:m1:$slug:r1",
                subject = subject,
                title = title,
                problemMarkdown = problemMarkdown,
                explanationMarkdown = explanationMarkdown,
                verification = TeachingArtifactVerification.CURATED_REFERENCE,
                assessmentItems = listOf(
                    TutorAssessmentItem(
                        id = assessmentId,
                        stemMarkdown = problemMarkdown,
                        choices = choices.map { (id, text) ->
                            TutorChoice(
                                id = id,
                                markdown = text,
                                feedbackMarkdown = if (id == correctChoiceId) {
                                    "判断正确。"
                                } else {
                                    "先回到关键量和因果方向，再比较选项。"
                                },
                                followUpIds = followUps.map(VerifiedTeachingFollowUp::id),
                            )
                        },
                        correctChoiceId = correctChoiceId,
                        promptMarkdown = "先独立判断，再选择最符合条件的一项。",
                        initialFollowUpIds = followUps.map(VerifiedTeachingFollowUp::id),
                        knowledgeNodeIds = setOf(knowledgeNodeId),
                    ),
                ),
                followUps = followUps,
                knowledgeNodeIds = setOf(knowledgeNodeId),
            )
        }

        fun evidenceSnapshot() = AssessmentEvidenceSnapshot(
            snapshotId = "evidence:m1:$slug:r1",
            assessmentItemId = assessmentId,
            practiceUnitId = practiceUnitId,
            problemRevisionId = revisionId,
            answerSpecId = answerSpecId,
            itemFamilyId = "family:m1:$slug",
            sourceBundleId = "source:m1:$slug",
            taxonomyVersion = TAXONOMY_VERSION,
            verification = AssessmentSnapshotVerification.VERIFIED,
            calibration = CalibrationSnapshot(
                support = CalibrationSupport.SUPPORTED,
                sourceId = "calibration:$FIXTURE_VERSION",
                version = "curated-answer-key-v1",
                validFromEpochMillis = SEED_CREATED_AT_EPOCH_MILLIS,
                validUntilEpochMillis = CALIBRATION_VALID_UNTIL_EPOCH_MILLIS,
            ),
            attributions = listOf(
                KnowledgeEvidenceAttribution(
                    bindingId = "binding:m1:$slug:$knowledgeCode",
                    knowledgeNodeId = knowledgeNodeId,
                    weight = 1.0,
                    basisRevisionId = revisionId,
                    taxonomyVersion = TAXONOMY_VERSION,
                    role = EvidenceAttributionRole.PRIMARY,
                    certainty = EvidenceAttributionCertainty.DIRECT,
                ),
            ),
            capturedAtEpochMillis = SEED_CREATED_AT_EPOCH_MILLIS,
        )
    }

    private fun List<Pair<String, String>>.toOptionsSnapshot(): String = joinToString(
        prefix = "{\"schema\":\"choice-options.v1\",\"options\":[",
        postfix = "]}",
    ) { (id, text) ->
        "{\"id\":\"${id.jsonEscaped()}\",\"text\":\"${text.jsonEscaped()}\"}"
    }

    private fun String.jsonEscaped(): String = buildString(length) {
        this@jsonEscaped.forEach { character ->
            when (character) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character.isISOControl()) {
                    append("\\u")
                    append(character.code.toString(16).padStart(4, '0'))
                } else {
                    append(character)
                }
            }
        }
    }

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString(separator = "") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
    }

    private const val SEED_CREATED_AT_EPOCH_MILLIS = 1_767_225_600_000L
    private const val CALIBRATION_VALID_UNTIL_EPOCH_MILLIS = 4_102_444_800_000L
    private const val TUTOR_QUESTION_SLUG = "derivative-sign-change"
}
