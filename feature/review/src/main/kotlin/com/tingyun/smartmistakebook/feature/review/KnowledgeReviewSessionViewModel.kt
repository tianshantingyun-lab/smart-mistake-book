package com.tingyun.smartmistakebook.feature.review

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tingyun.smartmistakebook.core.domain.KnowledgeQuizFeedbackResult
import com.tingyun.smartmistakebook.core.domain.KnowledgeReviewQueueEntry
import com.tingyun.smartmistakebook.core.domain.KnowledgeReviewSessionPlan
import com.tingyun.smartmistakebook.core.model.TutorAssessmentItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** 知识点复习：当前知识点的考察项加载状态。 */
internal enum class KnowledgeQuizLoadStatus {
    IDLE,
    LOADING,
    LOADED,
    FAILED,
}

/** 知识点复习：当前知识点作答的回写状态。 */
internal enum class KnowledgeQuizSubmitStatus {
    IDLE,
    RECORDING,
    RECORDED,
    FAILED,
}

/**
 * 知识点复习会话的一个展示单元（spec dual-review-entry §3.3/§3.4）：负责"当前知识点"
 * 从取题到判答回写的状态机。整个队列以 [KnowledgeReviewSessionPlan] 注入，当前索引与
 * 作答判决持久化在 SavedStateHandle。模型取题与掌握度回写都经回调注入（对齐
 * ReviewSessionViewModel/CapturedReviewSessionViewModel），本类不持有任何
 * repository/gateway，纯 JVM 可测。
 *
 * 进程重建语义：知识点考察项本身由 Room 中的 ModelTask 持久化，Screen 在 loadStatus
 * 回到 IDLE 时重新走 [loadCurrentQuiz]（app 层 loader 先观察既有任务、再决定是否新派发），
 * 因此本类不序列化选项文本；只持久化"末位已回写"所需的最小判决，避免重建题目丢失。
 */
internal class KnowledgeReviewSessionViewModel(
    private val savedStateHandle: SavedStateHandle,
    private val plan: KnowledgeReviewSessionPlan,
) : ViewModel() {
    internal var currentIndex: Int by mutableStateOf(
        savedStateHandle.get<Int>(CURRENT_INDEX_KEY) ?: 0,
    )
        private set

    /** 当前展示的知识点；队列走完后为 null。 */
    val currentEntry: KnowledgeReviewQueueEntry?
        get() = plan.queue.getOrNull(currentIndex)

    val queueSize: Int
        get() = plan.queue.size

    /** 当前知识点已出题成功（仅存于内存；进程重建后由 loader 重新观察既有 ModelTask）。 */
    var currentItem: TutorAssessmentItem? by mutableStateOf(null)
        private set

    var loadStatus by mutableStateOf(restoredLoadStatus())
        private set

    var selectedChoice: String? by mutableStateOf(savedStateHandle[SELECTED_CHOICE_KEY])
        private set

    var submittedChoice: String? by mutableStateOf(savedStateHandle[SUBMITTED_CHOICE_KEY])
        private set

    var submitStatus by mutableStateOf(restoredSubmitStatus())
        private set

    /** 回写结果；null = 尚未提交或未记录。 */
    var feedbackResult: KnowledgeQuizFeedbackResult? by mutableStateOf(
        savedStateHandle.get<String>(FEEDBACK_RESULT_KEY)?.let(::restoreFeedbackResult),
    )
        private set

    private val sessionStartedAtEpochMillis: Long =
        savedStateHandle.get<Long>(SESSION_STARTED_AT_KEY)
            ?: System.currentTimeMillis().also {
                savedStateHandle[SESSION_STARTED_AT_KEY] = it
            }

    /**
     * 当前节点已回写、可以前进（下一知识点或完成）。末位节点回写后仍为 true——
     * 按钮文案由 [completed] 区分（"完成" vs "下一知识点"）。
     */
    val canContinue: Boolean
        get() = plan.queue.isNotEmpty() &&
            submitStatus == KnowledgeQuizSubmitStatus.RECORDED

    /** 会话是否已全部完成（末位知识点也已回写）。 */
    val completed: Boolean
        get() = plan.queue.isNotEmpty() &&
            submitStatus == KnowledgeQuizSubmitStatus.RECORDED &&
            isLastIndex(currentIndex)

    fun select(choiceId: String) {
        if (submittedChoice != null) return
        val item = currentItem ?: return
        if (item.choices.none { it.id == choiceId }) return
        selectedChoice = choiceId
        savedStateHandle[SELECTED_CHOICE_KEY] = choiceId
    }

    /**
     * 为当前知识点取题。回调返回 null 或抛异常 = 本次取题失败（可重试，不丢节点）。
     * 幂等：同一节点多次调用只派发一次（RECORDING 中忽略）。
     */
    fun loadCurrentQuiz(
        load: suspend (KnowledgeReviewQueueEntry) -> TutorAssessmentItem?,
    ) {
        val entry = currentEntry ?: return
        if (loadStatus == KnowledgeQuizLoadStatus.LOADING) return
        if (submittedChoice != null) return
        updateLoadStatus(KnowledgeQuizLoadStatus.LOADING)
        viewModelScope.launch {
            try {
                val item = load(entry) ?: error("知识考察生成失败")
                check(item.knowledgeNodeIds.contains(entry.knowledgeNodeId)) {
                    "Generated quiz must stay anchored to the reviewed knowledge node"
                }
                currentItem = item
                updateLoadStatus(KnowledgeQuizLoadStatus.LOADED)
            } catch (cancelled: CancellationException) {
                updateLoadStatus(KnowledgeQuizLoadStatus.IDLE)
                throw cancelled
            } catch (_: Exception) {
                updateLoadStatus(KnowledgeQuizLoadStatus.FAILED)
            }
        }
    }

    /**
     * 提交当前选择并回写掌握度。判答与回写由调用方（app 层）注入，签名对齐
     * StudyExperienceRepository.submitKnowledgeQuizFeedback。判答一致性在此校验：
     * 持久化的 isCorrect 必须等于本地 evaluateChoice 的客观对错，防止远端回写越权。
     */
    fun submitAnswer(
        requestId: String,
        occurredAtEpochMillis: Long,
        submit: suspend (
            requestId: String,
            knowledgeNodeId: String,
            correctChoiceId: String,
            selectedChoiceId: String,
            occurredAtEpochMillis: Long,
            conversationId: String,
        ) -> KnowledgeQuizFeedbackResult,
    ) {
        if (submitStatus == KnowledgeQuizSubmitStatus.RECORDING) return
        if (submittedChoice != null) return
        val item = currentItem ?: return
        val entry = currentEntry ?: return
        val choiceId = selectedChoice ?: return

        updateSubmitStatus(KnowledgeQuizSubmitStatus.RECORDING)
        viewModelScope.launch {
            try {
                val result = submit(
                    requestId,
                    entry.knowledgeNodeId,
                    item.correctChoiceId,
                    choiceId,
                    occurredAtEpochMillis,
                    knowledgeQuizConversationId(),
                )
                check(result.isCorrect == item.evaluateChoice(choiceId).isCorrect) {
                    "Persisted knowledge-quiz verdict disagrees with the locally judged choice"
                }
                submittedChoice = choiceId
                savedStateHandle[SUBMITTED_CHOICE_KEY] = choiceId
                feedbackResult = result
                savedStateHandle[FEEDBACK_RESULT_KEY] = serialize(result)
                updateSubmitStatus(KnowledgeQuizSubmitStatus.RECORDED)
            } catch (cancelled: CancellationException) {
                updateSubmitStatus(KnowledgeQuizSubmitStatus.IDLE)
                throw cancelled
            } catch (_: Exception) {
                updateSubmitStatus(KnowledgeQuizSubmitStatus.FAILED)
            }
        }
    }

    /**
     * 本次知识点复习会话的写入门控标识（审计 2026-09-09 P1）：配额必须按"每次复习会话"
     * 计数，而不是全局固定 id——后者会让每会话 50 条上限变成终身上限，写满后永久拒写。
     * 会话起点持久化在 SavedStateHandle，进程重建后仍是同一次会话。
     */
    private fun knowledgeQuizConversationId(): String =
        "knowledge-quiz-review:$sessionStartedAtEpochMillis"

    /** 进入下一知识点；若当前是末位则标记会话完成（Screen 负责返回首页）。 */
    fun continueToNext() {
        if (!canContinue && !completed) return
        advance()
    }

    /**
     * 跳过当前知识点（取题持续失败时的出口）：零权重——不写掌握度、节点保持到期，
     * 只在本会话内前进，避免一个节点卡住整场复习。
     */
    fun skipCurrentNode() {
        if (currentEntry == null) return
        advance()
    }

    private fun advance() {
        currentIndex += 1
        savedStateHandle[CURRENT_INDEX_KEY] = currentIndex
        clearNodeState()
    }

    private fun clearNodeState() {
        currentItem = null
        selectedChoice = null
        savedStateHandle.remove<String>(SELECTED_CHOICE_KEY)
        submittedChoice = null
        savedStateHandle.remove<String>(SUBMITTED_CHOICE_KEY)
        feedbackResult = null
        savedStateHandle.remove<String>(FEEDBACK_RESULT_KEY)
        updateLoadStatus(KnowledgeQuizLoadStatus.IDLE)
        updateSubmitStatus(KnowledgeQuizSubmitStatus.IDLE)
    }

    private fun isLastIndex(index: Int): Boolean = index == plan.queue.size - 1

    private fun updateLoadStatus(status: KnowledgeQuizLoadStatus) {
        loadStatus = status
        savedStateHandle[LOAD_STATUS_KEY] = status.name
    }

    private fun updateSubmitStatus(status: KnowledgeQuizSubmitStatus) {
        submitStatus = status
        savedStateHandle[SUBMIT_STATUS_KEY] = status.name
    }

    private fun restoredLoadStatus(): KnowledgeQuizLoadStatus {
        // 已提交的节点无需重建题目。
        if (savedStateHandle.get<String>(SUBMITTED_CHOICE_KEY) != null) {
            return KnowledgeQuizLoadStatus.IDLE
        }
        val restored = savedStateHandle.get<String>(LOAD_STATUS_KEY)
            ?.let { runCatching { KnowledgeQuizLoadStatus.valueOf(it) }.getOrNull() }
            ?: KnowledgeQuizLoadStatus.IDLE
        return when (restored) {
            // 重建后 currentItem 只存内存、必然丢失：LOADING/LOADED 都回到 IDLE，
            // 由 Screen 重新走 loader（loader 先观察既有任务、再决定是否新派发）。
            KnowledgeQuizLoadStatus.LOADING,
            KnowledgeQuizLoadStatus.LOADED,
            -> KnowledgeQuizLoadStatus.IDLE
            else -> restored
        }
    }

    private fun restoredSubmitStatus(): KnowledgeQuizSubmitStatus {
        if (savedStateHandle.get<String>(SUBMITTED_CHOICE_KEY) != null) {
            return KnowledgeQuizSubmitStatus.RECORDED
        }
        val restored = savedStateHandle.get<String>(SUBMIT_STATUS_KEY)
            ?.let { runCatching { KnowledgeQuizSubmitStatus.valueOf(it) }.getOrNull() }
            ?: KnowledgeQuizSubmitStatus.IDLE
        return if (restored == KnowledgeQuizSubmitStatus.RECORDING) {
            KnowledgeQuizSubmitStatus.FAILED
        } else {
            restored
        }
    }

    private fun serialize(result: KnowledgeQuizFeedbackResult): String = buildString {
        append(if (result.isCorrect) "1" else "0")
        append(':')
        append(if (result.evidenceRecorded) "1" else "0")
        append(':')
        append(result.rejectedReason.orEmpty())
    }

    private fun restoreFeedbackResult(raw: String): KnowledgeQuizFeedbackResult {
        val parts = raw.split(':', limit = 3)
        return KnowledgeQuizFeedbackResult(
            isCorrect = parts.getOrNull(0) == "1",
            evidenceRecorded = parts.getOrNull(1) == "1",
            rejectedReason = parts.getOrNull(2)?.takeIf { it.isNotEmpty() },
        )
    }

    internal companion object {
        const val CURRENT_INDEX_KEY = "kq_current_index"
        const val SESSION_STARTED_AT_KEY = "kq_session_started_at"
        const val LOAD_STATUS_KEY = "kq_load_status"
        const val SELECTED_CHOICE_KEY = "kq_selected_choice"
        const val SUBMITTED_CHOICE_KEY = "kq_submitted_choice"
        const val SUBMIT_STATUS_KEY = "kq_submit_status"
        const val FEEDBACK_RESULT_KEY = "kq_feedback_result"
    }
}
