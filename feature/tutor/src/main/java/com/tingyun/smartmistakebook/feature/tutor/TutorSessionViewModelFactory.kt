package com.tingyun.smartmistakebook.feature.tutor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.CreationExtras
import com.tingyun.smartmistakebook.core.domain.CaptureWorkflowRepository
import com.tingyun.smartmistakebook.core.domain.TutorConversationRepository

internal class TutorSessionViewModelFactory(
    private val repository: CaptureWorkflowRepository,
    private val conversations: TutorConversationRepository,
    private val sessionId: String,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(
        modelClass: Class<T>,
        extras: CreationExtras,
    ): T {
        require(modelClass.isAssignableFrom(TutorSessionViewModel::class.java)) {
            "Unknown TutorSessionViewModel class: ${modelClass.name}"
        }
        val savedStateHandle = extras.createSavedStateHandle()
        savedStateHandle["sessionId"] = sessionId
        return TutorSessionViewModel(
            savedStateHandle = savedStateHandle,
            repository = repository,
            conversations = conversations,
        ) as T
    }
}
