package com.tingyun.smartmistakebook.feature.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.CreationExtras
import com.tingyun.smartmistakebook.core.domain.KnowledgeReviewSessionPlan

/**
 * 知识点复习会话 ViewModel 工厂（spec dual-review-entry §3.2/§3.3）：注入今日知识点计划
 * 与 SavedStateHandle。队列由 app 层在进入会话时经 currentKnowledgeReviewPlan 取一次，
 * 作为不可变快照交与会话——进程重建后索引仍在 SavedStateHandle，题目本身经 ModelTask
 * 重观察恢复。
 */
internal class KnowledgeReviewSessionViewModelFactory(
    private val plan: KnowledgeReviewSessionPlan,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(
        modelClass: Class<T>,
        extras: CreationExtras,
    ): T {
        require(modelClass.isAssignableFrom(KnowledgeReviewSessionViewModel::class.java)) {
            "Unknown KnowledgeReviewSessionViewModel class: ${modelClass.name}"
        }
        return KnowledgeReviewSessionViewModel(
            savedStateHandle = extras.createSavedStateHandle(),
            plan = plan,
        ) as T
    }
}
