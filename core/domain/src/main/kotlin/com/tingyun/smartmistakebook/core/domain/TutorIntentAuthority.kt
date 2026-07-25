package com.tingyun.smartmistakebook.core.domain

import com.tingyun.smartmistakebook.core.model.TutorIntentDecision
import com.tingyun.smartmistakebook.core.model.TutorMemoryPreference
import com.tingyun.smartmistakebook.core.model.TutorMessageIntent
import com.tingyun.smartmistakebook.core.model.TutorRequestedLocalCapability

enum class TutorAuthorizedCapability {
    READ_MISTAKE_NOTEBOOK,
    READ_LEARNING_PROGRESS,
    REQUEST_SAVE_CONFIRMATION,
    REQUEST_END_WITHOUT_SAVE_CONFIRMATION,
    BLOCK_LONG_TERM_WRITES_FOR_SESSION,
}

data class TutorIntentAuthorization(
    val capabilities: Set<TutorAuthorizedCapability>,
    val mistakeReadLimit: Int? = null,
    val learningProgressReadLimit: Int? = null,
    val requiresClarification: Boolean,
) {
    init {
        require(
            (TutorAuthorizedCapability.READ_MISTAKE_NOTEBOOK in capabilities) ==
                (mistakeReadLimit != null),
        )
        require(
            (TutorAuthorizedCapability.READ_LEARNING_PROGRESS in capabilities) ==
                (learningProgressReadLimit != null),
        )
        require(mistakeReadLimit == null || mistakeReadLimit in 1..MAX_READ_ITEMS)
        require(learningProgressReadLimit == null || learningProgressReadLimit in 1..MAX_READ_ITEMS)
    }

    companion object {
        const val MAX_READ_ITEMS = 20
    }
}

/**
 * The model classifies semantics; this policy owns authority. Reads are bounded, writes become
 * confirmation requests, and an uncertain message executes no local action.
 */
object TutorIntentAuthorityPolicy {
    private const val MIN_READ_CONFIDENCE = 0.65
    private const val MIN_RESTRICT_WRITES_CONFIDENCE = 0.70
    private const val MIN_CONFIRMATION_CONFIDENCE = 0.90

    fun authorize(
        decision: TutorIntentDecision,
        studentMessage: String,
    ): TutorIntentAuthorization {
        val actionBoundToMessage = decision.actionIsBoundTo(studentMessage)
        val requestsLocalAuthority =
            decision.requestedLocalCapability != TutorRequestedLocalCapability.NONE ||
                decision.memoryPreference == TutorMemoryPreference.BLOCK_LONG_TERM_WRITES_FOR_SESSION
        val needsClarification =
            decision.intent == TutorMessageIntent.AMBIGUOUS ||
                decision.confidence < MIN_READ_CONFIDENCE ||
                (requestsLocalAuthority && !actionBoundToMessage)
        if (needsClarification) {
            return TutorIntentAuthorization(
                capabilities = emptySet(),
                requiresClarification = true,
            )
        }

        val capabilities = buildSet {
            if (
                actionBoundToMessage &&
                decision.memoryPreference ==
                TutorMemoryPreference.BLOCK_LONG_TERM_WRITES_FOR_SESSION &&
                decision.confidence >= MIN_RESTRICT_WRITES_CONFIDENCE
            ) {
                add(TutorAuthorizedCapability.BLOCK_LONG_TERM_WRITES_FOR_SESSION)
            }
            if (!actionBoundToMessage) return@buildSet
            when (decision.requestedLocalCapability) {
                TutorRequestedLocalCapability.NONE -> Unit
                TutorRequestedLocalCapability.READ_MISTAKE_NOTEBOOK -> {
                    if (
                        decision.intent == TutorMessageIntent.MISTAKE_NOTEBOOK_LOOKUP &&
                        decision.confidence >= MIN_READ_CONFIDENCE
                    ) {
                        add(TutorAuthorizedCapability.READ_MISTAKE_NOTEBOOK)
                    }
                }
                TutorRequestedLocalCapability.READ_LEARNING_PROGRESS -> {
                    if (
                        decision.intent == TutorMessageIntent.LEARNING_PROGRESS_LOOKUP &&
                        decision.confidence >= MIN_READ_CONFIDENCE
                    ) {
                        add(TutorAuthorizedCapability.READ_LEARNING_PROGRESS)
                    }
                }
                TutorRequestedLocalCapability.OFFER_SAVE_CURRENT_QUESTION -> {
                    if (decision.confidence >= MIN_CONFIRMATION_CONFIDENCE) {
                        add(TutorAuthorizedCapability.REQUEST_SAVE_CONFIRMATION)
                    }
                }
                TutorRequestedLocalCapability.OFFER_END_WITHOUT_SAVE -> {
                    if (
                        decision.intent == TutorMessageIntent.END_OR_PAUSE &&
                        decision.confidence >= MIN_CONFIRMATION_CONFIDENCE
                    ) {
                        add(TutorAuthorizedCapability.REQUEST_END_WITHOUT_SAVE_CONFIRMATION)
                    }
                }
            }
        }
        return TutorIntentAuthorization(
            capabilities = capabilities,
            mistakeReadLimit = TutorIntentAuthorization.MAX_READ_ITEMS.takeIf {
                TutorAuthorizedCapability.READ_MISTAKE_NOTEBOOK in capabilities
            },
            learningProgressReadLimit = TutorIntentAuthorization.MAX_READ_ITEMS.takeIf {
                TutorAuthorizedCapability.READ_LEARNING_PROGRESS in capabilities
            },
            requiresClarification = false,
        )
    }
}

private fun TutorIntentDecision.actionIsBoundTo(studentMessage: String): Boolean {
    if (!explicitActionRequest) {
        return requestedLocalCapability == TutorRequestedLocalCapability.NONE &&
            memoryPreference == TutorMemoryPreference.UNCHANGED
    }
    val normalized = studentMessage.lowercase()
    if (lookupTerms.any { it.lowercase() !in normalized }) return false
    if (memoryPreference == TutorMemoryPreference.BLOCK_LONG_TERM_WRITES_FOR_SESSION) {
        return normalized.containsAny(BLOCK_WRITE_MARKERS)
    }
    return when (requestedLocalCapability) {
        TutorRequestedLocalCapability.NONE -> true
        TutorRequestedLocalCapability.READ_MISTAKE_NOTEBOOK ->
            normalized.containsAny(READ_ACTION_MARKERS) &&
                normalized.containsAny(MISTAKE_NOTEBOOK_MARKERS)
        TutorRequestedLocalCapability.READ_LEARNING_PROGRESS ->
            normalized.containsAny(READ_ACTION_MARKERS) &&
                normalized.containsAny(LEARNING_PROGRESS_MARKERS)
        TutorRequestedLocalCapability.OFFER_SAVE_CURRENT_QUESTION ->
            !normalized.containsAny(BLOCK_WRITE_MARKERS) &&
                normalized.containsAny(SAVE_CURRENT_QUESTION_MARKERS)
        TutorRequestedLocalCapability.OFFER_END_WITHOUT_SAVE ->
            normalized.containsAny(END_SESSION_MARKERS)
    }
}

private fun String.containsAny(markers: Set<String>): Boolean = markers.any(::contains)

private val READ_ACTION_MARKERS = setOf("看", "查", "找", "列", "打开", "调出", "显示")
private val MISTAKE_NOTEBOOK_MARKERS = setOf("错题", "错题本", "题库")
private val LEARNING_PROGRESS_MARKERS = setOf("学习情况", "掌握", "进度", "薄弱", "学习数据")
private val SAVE_CURRENT_QUESTION_MARKERS = setOf("保存", "记下", "加入错题", "收录", "记进错题")
private val END_SESSION_MARKERS = setOf("结束", "暂停", "先到这里", "停一下", "不学了")
private val BLOCK_WRITE_MARKERS = setOf(
    "这题不记",
    "这次不记",
    "不要记入",
    "别记入",
    "不记入错题",
    "别记入错题",
    "不要记入错题",
    "不要存入",
    "别存入",
    "不要记录",
    "别记录",
    "不要保存",
    "别保存",
    "不要写入",
)
