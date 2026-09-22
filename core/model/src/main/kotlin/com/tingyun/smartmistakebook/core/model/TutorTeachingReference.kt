package com.tingyun.smartmistakebook.core.model

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

/**
 * Reviewed teaching context for knowledge already linked to the learner's current question.
 *
 * It is not an assessment item: it has no answer key, score, difficulty, scheduling identity, or
 * authority to create learning evidence. A reference may contain a complete worked example because
 * examples and method models are teaching material, not a pool of questions to assign.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class TutorTeachingReference(
    val materialId: String,
    val subject: String,
    val materialType: KnowledgeTeachingMaterialType,
    val title: String,
    val summaryMarkdown: String,
    val applicabilityMarkdown: String,
    val contentMarkdown: String,
    val boundaryMarkdown: String,
    val knowledgeNodeIds: List<String>,
    /**
     * 该材料对应知识点的**本会话代号**（单一代号通道，ADR 0001 / D5）：材料绑定多个节点时
     * 取其中已披露的第一个。由 core:data 的会话注册表在派生前填上；null = 尚未赋码
     * （旧行读回 / 无代号的派发），prompt 渲染时省略该键。
     *
     * `@EncodeDefault(NEVER)`：null 不落键，旧行编码形状不变——指纹空载体抹平
     * （bf8be888 教训）在这一层直接成立，不需要额外的 strip。
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val code: String? = null,
) {
    init {
        materialId.requireSafeModelText(
            "Tutor teaching-reference id",
            ModelTaskRequest.MAX_ID_CHARS,
            false,
        )
        subject.requireSafeModelText(
            "Tutor teaching-reference subject",
            TutorPlanInput.MAX_SUBJECT_CHARS,
            false,
        )
        title.requireSafeModelText("Tutor teaching-reference title", MAX_TITLE_CHARS, false)
        summaryMarkdown.requireTutorMarkdown(
            "Tutor teaching-reference summary",
            MAX_SUMMARY_CHARS,
        )
        applicabilityMarkdown.requireTutorMarkdown(
            "Tutor teaching-reference applicability",
            MAX_APPLICABILITY_CHARS,
        )
        contentMarkdown.requireTutorMarkdown(
            "Tutor teaching-reference content",
            MAX_CONTENT_CHARS,
        )
        boundaryMarkdown.requireTutorMarkdown(
            "Tutor teaching-reference boundary",
            MAX_BOUNDARY_CHARS,
        )
        require(knowledgeNodeIds.size in 1..MAX_KNOWLEDGE_NODES) {
            "Tutor teaching reference needs a bounded knowledge-point scope"
        }
        require(knowledgeNodeIds.distinct().size == knowledgeNodeIds.size) {
            "Tutor teaching-reference knowledge ids must be unique"
        }
        knowledgeNodeIds.forEach { knowledgeNodeId ->
            knowledgeNodeId.requireSafeModelText(
                "Tutor teaching-reference knowledge id",
                ModelTaskRequest.MAX_ID_CHARS,
                false,
            )
        }
        code?.let { TutorKnowledgeCode.requireCodeShape(it) }
    }

    val markdownChars: Int
        get() = summaryMarkdown.length +
            applicabilityMarkdown.length +
            contentMarkdown.length +
            boundaryMarkdown.length

    companion object {
        const val MAX_TITLE_CHARS = 240
        const val MAX_SUMMARY_CHARS = 4_000
        const val MAX_APPLICABILITY_CHARS = 8_000
        const val MAX_CONTENT_CHARS = 32_000
        const val MAX_BOUNDARY_CHARS = 8_000
        const val MAX_KNOWLEDGE_NODES = 16
    }
}

internal fun List<TutorTeachingReference>.requireValidTutorTeachingReferences(
    subject: String,
    label: String,
) {
    require(map(TutorTeachingReference::materialId).distinct().size == size) {
        "$label teaching-reference ids must be unique"
    }
    require(all { reference -> reference.subject == subject }) {
        "$label teaching references must stay within the current subject"
    }
    require(
        sumOf(TutorTeachingReference::markdownChars) <=
            TutorPlanInput.MAX_TEACHING_REFERENCE_MARKDOWN_CHARS,
    ) {
        "$label teaching references exceed their total text budget"
    }
}
