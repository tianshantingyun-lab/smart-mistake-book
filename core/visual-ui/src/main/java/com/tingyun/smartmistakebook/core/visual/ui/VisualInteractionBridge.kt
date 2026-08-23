package com.tingyun.smartmistakebook.core.visual.ui

import androidx.compose.runtime.staticCompositionLocalOf
import com.tingyun.smartmistakebook.core.domain.visual.VisualInteractionEventSink
import com.tingyun.smartmistakebook.core.domain.visual.VisualProblemConstraints
import com.tingyun.smartmistakebook.core.model.TutorVisual2DNodeElement
import com.tingyun.smartmistakebook.core.visual.runtime.CompiledTutorVisualDocument

/**
 * Injection point for the visual-interaction persistence sink (audit
 * PR-11). Provided at the app root; null by default so previews and
 * tests keep working without a database.
 */
val LocalVisualInteractionEventSink = staticCompositionLocalOf<VisualInteractionEventSink?> { null }

/**
 * Derives a conservative default constraint spec from the document
 * itself, so 2D panel drags are locally judged and recorded even when
 * the teaching content ships no explicit spec. The scene id stands in
 * for the problem revision id; every 2D node is declared draggable.
 */
internal fun deriveDefaultVisualConstraints(
    compiled: CompiledTutorVisualDocument,
): VisualProblemConstraints = VisualProblemConstraints(
    problemRevisionId = compiled.scene.sceneId,
    draggableElementIds = compiled.scene.elements
        .filterIsInstance<TutorVisual2DNodeElement>()
        .mapTo(mutableSetOf(), TutorVisual2DNodeElement::elementId),
)
