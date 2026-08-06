package com.tingyun.smartmistakebook.core.model

/**
 * Versioned, deterministic projection of optional tutor context.
 *
 * The confirmed question and the student's current message stay on their typed inputs and are
 * never trimmed here. Optional context is admitted in this fixed order: the minimum direct
 * knowledge boundary, reviewed summaries and applicability, already-visible relationship and
 * conversation context, then reviewed material bodies. Markdown fields are admitted whole so a
 * formula or applicability boundary can never become a misleading prefix.
 */
class BudgetedTutorContext private constructor(
    val policyVersion: String,
    val teachingConstraints: List<TutorKnowledgeGuidance>,
    val teachingReferences: List<BudgetedTutorTeachingReference>,
    val priorConversationMemory: TutorConversationMemory?,
    val priorCycleStudentMessages: List<String>,
    val priorTurns: List<TutorTurnHistoryEntry>,
    val visibleTutorContextMarkdown: String?,
    val priorMessages: List<TutorChatHistoryEntry>,
) {
    companion object {
        const val POLICY_VERSION = "budgeted-tutor-context-v1"
        const val MAX_OPTIONAL_CONTEXT_CHARS = 16_000
        const val MAX_OPTIONAL_CONTEXT_UTF8_BYTES = 32L * 1_024L
        /** Tokenizer-independent upper bound: UTF-8 bytes are never presented as exact tokens. */
        const val MAX_OPTIONAL_CONTEXT_CONSERVATIVE_TOKEN_UPPER_BOUND =
            MAX_OPTIONAL_CONTEXT_UTF8_BYTES

        fun from(
            input: TutorPlanInput,
            maxChars: Int = MAX_OPTIONAL_CONTEXT_CHARS,
            maxUtf8Bytes: Long = MAX_OPTIONAL_CONTEXT_UTF8_BYTES,
            includeTeachingConstraints: Boolean = true,
        ): BudgetedTutorContext = Builder(
            teachingConstraints = input.teachingConstraints.takeIf { includeTeachingConstraints }
                .orEmpty(),
            sourceReferences = input.reviewedTeachingReferences,
            maxChars = maxChars,
            maxUtf8Bytes = maxUtf8Bytes,
        ).build(
            priorConversationMemory = input.priorConversationMemory,
            priorCycleStudentMessages = input.priorCycleStudentMessages,
            priorTurns = input.priorTurns,
            visibleTutorContextMarkdown = null,
            priorMessages = emptyList(),
        )

        fun from(
            input: TutorRespondInput,
            maxChars: Int = MAX_OPTIONAL_CONTEXT_CHARS,
            maxUtf8Bytes: Long = MAX_OPTIONAL_CONTEXT_UTF8_BYTES,
            includeTeachingConstraints: Boolean = true,
        ): BudgetedTutorContext = Builder(
            teachingConstraints = input.teachingConstraints.takeIf { includeTeachingConstraints }
                .orEmpty(),
            sourceReferences = input.reviewedTeachingReferences,
            maxChars = maxChars,
            maxUtf8Bytes = maxUtf8Bytes,
        ).build(
            priorConversationMemory = null,
            priorCycleStudentMessages = emptyList(),
            priorTurns = emptyList(),
            visibleTutorContextMarkdown = input.visibleTutorContextMarkdown,
            priorMessages = input.priorMessages,
        )
    }

    private class Builder(
        private val teachingConstraints: List<TutorKnowledgeGuidance>,
        sourceReferences: List<TutorTeachingReference>,
        maxChars: Int,
        maxUtf8Bytes: Long,
    ) {
        private val remaining = ContextBudget(maxChars, maxUtf8Bytes)
        private val mutableReferences = sourceReferences.map { reference ->
            MutableReference(
                materialType = reference.materialType,
                title = reference.title,
                summaryMarkdown = reference.summaryMarkdown,
                applicabilityMarkdown = reference.applicabilityMarkdown,
                boundaryMarkdown = reference.boundaryMarkdown,
                contentMarkdown = reference.contentMarkdown,
            )
        }

        init {
            require(maxChars >= 0) { "Tutor context character budget must not be negative" }
            require(maxUtf8Bytes >= 0L) { "Tutor context UTF-8 budget must not be negative" }
            teachingConstraints.forEach { guidance ->
                remaining.protect(guidance.ref)
                remaining.protect(guidance.label)
                remaining.protect(guidance.constraint.name)
            }
            mutableReferences.forEach { reference ->
                val minimumReference = listOf(
                    reference.materialType.name,
                    reference.title,
                    reference.boundaryMarkdown,
                )
                if (remaining.tryTake(minimumReference.contextCost())) {
                    reference.included = true
                    reference.boundary = reference.boundaryMarkdown
                }
            }
            mutableReferences.forEach { reference ->
                if (!reference.included) return@forEach
                reference.summary = remaining.takeWhole(reference.summaryMarkdown)
                reference.applicability = remaining.takeWhole(reference.applicabilityMarkdown)
            }
        }

        fun build(
            priorConversationMemory: TutorConversationMemory?,
            priorCycleStudentMessages: List<String>,
            priorTurns: List<TutorTurnHistoryEntry>,
            visibleTutorContextMarkdown: String?,
            priorMessages: List<TutorChatHistoryEntry>,
        ): BudgetedTutorContext {
            val acceptedVisibleContext = visibleTutorContextMarkdown?.let(remaining::takeWhole)
            val acceptedPriorMessages = remaining.takeNewestWhole(
                priorMessages,
                ::chatHistoryCost,
            )
            val acceptedPriorTurns = remaining.takeNewestWhole(priorTurns, ::turnHistoryCost)
            val acceptedCycleMessages = remaining.takeNewestWhole(
                priorCycleStudentMessages,
                { it.contextCost() },
            )
            val acceptedMemory = priorConversationMemory?.takeIf { memory ->
                remaining.tryTake(conversationMemoryCost(memory))
            }
            mutableReferences.forEach { reference ->
                if (!reference.included) return@forEach
                reference.content = remaining.takeWhole(reference.contentMarkdown)
            }
            return BudgetedTutorContext(
                policyVersion = POLICY_VERSION,
                teachingConstraints = teachingConstraints.toList(),
                teachingReferences = mutableReferences.mapNotNull(MutableReference::freeze),
                priorConversationMemory = acceptedMemory,
                priorCycleStudentMessages = acceptedCycleMessages,
                priorTurns = acceptedPriorTurns,
                visibleTutorContextMarkdown = acceptedVisibleContext,
                priorMessages = acceptedPriorMessages,
            )
        }
    }
}

data class BudgetedTutorTeachingReference(
    val materialType: KnowledgeTeachingMaterialType,
    val title: String,
    val summaryMarkdown: String?,
    val applicabilityMarkdown: String?,
    val boundaryMarkdown: String?,
    val contentMarkdown: String?,
)

private class MutableReference(
    val materialType: KnowledgeTeachingMaterialType,
    val title: String,
    val summaryMarkdown: String,
    val applicabilityMarkdown: String,
    val boundaryMarkdown: String,
    val contentMarkdown: String,
) {
    var included: Boolean = false
    var summary: String? = null
    var applicability: String? = null
    var boundary: String? = null
    var content: String? = null

    fun freeze(): BudgetedTutorTeachingReference? = if (included) BudgetedTutorTeachingReference(
        materialType = materialType,
        title = title,
        summaryMarkdown = summary,
        applicabilityMarkdown = applicability,
        boundaryMarkdown = boundary,
        contentMarkdown = content,
    ) else null
}

private class ContextBudget(maxChars: Int, maxUtf8Bytes: Long) {
    private var remainingChars = maxChars
    private var remainingUtf8Bytes = maxUtf8Bytes
    private var lowerPrioritiesAllowed = true

    fun protect(value: String) {
        remainingChars -= value.length
        remainingUtf8Bytes -= value.utf8Bytes
    }

    fun takeWhole(value: String): String? {
        if (!tryTake(value.contextCost())) return null
        return value
    }

    fun tryTake(cost: ContextCost): Boolean {
        if (
            !lowerPrioritiesAllowed ||
            cost.chars < 0 ||
            cost.utf8Bytes < 0L ||
            cost.chars > remainingChars ||
            cost.utf8Bytes > remainingUtf8Bytes
        ) {
            lowerPrioritiesAllowed = false
            return false
        }
        remainingChars -= cost.chars
        remainingUtf8Bytes -= cost.utf8Bytes
        return true
    }

    fun <T> takeNewestWhole(values: List<T>, cost: (T) -> ContextCost): List<T> {
        if (values.isEmpty() || !lowerPrioritiesAllowed) return emptyList()
        val accepted = ArrayDeque<T>()
        for (index in values.indices.reversed()) {
            val value = values[index]
            if (!tryTake(cost(value))) break
            accepted.addFirst(value)
        }
        return accepted.toList()
    }
}

private data class ContextCost(val chars: Int, val utf8Bytes: Long)

private val String.utf8Bytes: Long
    get() = encodeToByteArray().size.toLong()

private fun String.contextCost() = ContextCost(length, utf8Bytes)

private fun chatHistoryCost(entry: TutorChatHistoryEntry): ContextCost =
    listOf(entry.studentMessage, entry.assistantMarkdown).contextCost()

private fun turnHistoryCost(entry: TutorTurnHistoryEntry): ContextCost = listOf(
    entry.diagnosticStemMarkdown,
    entry.selectedChoiceMarkdown,
    entry.feedbackMarkdown,
    entry.requestedMove.name,
).contextCost()

private fun conversationMemoryCost(memory: TutorConversationMemory): ContextCost = listOf(
    memory.lastFeedbackMarkdown.orEmpty(),
    memory.lastRequestedMove?.name.orEmpty(),
    "0".repeat(32),
).contextCost()

private fun List<String>.contextCost() = ContextCost(
    chars = sumOf { it.length },
    utf8Bytes = sumOf { it.utf8Bytes },
)
