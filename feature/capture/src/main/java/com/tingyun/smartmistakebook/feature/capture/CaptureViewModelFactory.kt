package com.tingyun.smartmistakebook.feature.capture

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.CreationExtras
import com.tingyun.smartmistakebook.core.domain.CaptureWorkflowRepository
import com.tingyun.smartmistakebook.core.domain.ModelTaskRepository

class CaptureViewModelFactory(
    private val repository: CaptureWorkflowRepository,
    private val modelTasks: ModelTaskRepository,
    private val resumeDraftId: String?,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(
        modelClass: Class<T>,
        extras: CreationExtras,
    ): T {
        require(modelClass.isAssignableFrom(CaptureViewModel::class.java)) {
            "Unknown CaptureViewModel class: ${modelClass.name}"
        }
        val savedStateHandle = extras.createSavedStateHandle()
        if (resumeDraftId != null) {
            savedStateHandle[KEY_RESUME_DRAFT_ID] = resumeDraftId
        }
        return CaptureViewModel(
            savedStateHandle = savedStateHandle,
            repository = repository,
            modelTasks = modelTasks,
        ) as T
    }

    private companion object {
        const val KEY_RESUME_DRAFT_ID = "resumeDraftId"
    }
}
