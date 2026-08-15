package com.tingyun.smartmistakebook.feature.tutor

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.tingyun.smartmistakebook.core.domain.ModelTaskRepository

/**
 * Release stub - DebugSimpleTutorRoute is not available in release builds.
 * This prevents accidental use of the incomplete prototype in production.
 */
@Composable
fun DebugSimpleTutorRoute(
    onOpenSessionHistory: () -> Unit,
    modelTasks: ModelTaskRepository,
    modifier: Modifier = Modifier,
) {
    error("DebugSimpleTutorRoute is only available in debug builds")
}
