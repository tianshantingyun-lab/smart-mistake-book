package com.tingyun.smartmistakebook.feature.tutor

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.tingyun.smartmistakebook.core.domain.ModelTaskRepository

/**
 * Debug-only simplified tutor route for rapid prototyping.
 * This is NOT the production tutor entry point - use TutorRoute instead.
 *
 * Known limitations (P0 issues from audit):
 * - Authorization parameters hardcoded to "default"/"default"/"1"
 * - Image assets violate TutorLobbyInput contract (requires empty assets)
 * - No disclosure confirmation flow
 * - State/failure/concurrent handling incomplete
 *
 * Use this only for UI experimentation in debug builds.
 */
@Composable
fun DebugSimpleTutorRoute(
    onOpenSessionHistory: () -> Unit,
    modelTasks: ModelTaskRepository,
    modifier: Modifier = Modifier,
) {
    SimpleTutorRoute(
        modelTasks = modelTasks,
        modifier = modifier,
    )
}
