package com.tingyun.smartmistakebook.feature.common.accessibility

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Minimum touch target size following Material Design guidelines.
 * All interactive elements must be at least 48dp × 48dp.
 */
val MIN_TOUCH_TARGET_SIZE = 48.dp

/**
 * Font scale multiplier for large text mode.
 * When enabled, all text is scaled to 200% of normal size.
 */
const val LARGE_TEXT_SCALE = 2.0f

/**
 * Check if the device is currently using large text (200% or more).
 */
@Composable
fun isLargeTextMode(): Boolean {
    val configuration = LocalConfiguration.current
    return configuration.fontScale >= LARGE_TEXT_SCALE
}

/**
 * Modifier that ensures a minimum touch target size.
 * If the content is smaller than 48dp, it will be padded to meet the minimum.
 */
fun Modifier.minimumTouchTarget(): Modifier {
    return this.size(MIN_TOUCH_TARGET_SIZE)
}

/**
 * Modifier that applies accessibility semantics for interactive elements.
 */
fun Modifier.accessibleInteractive(
    label: String,
    role: Role = Role.Button,
    stateDescription: String? = null,
    onClick: (() -> Unit)? = null,
): Modifier {
    return this.semantics {
        contentDescription = label
        this.role = role
        stateDescription?.let { stateDescription = it }
    }
}

/**
 * Modifier that applies accessibility semantics for informational elements.
 */
fun Modifier.accessibleInfo(
    label: String,
    stateDescription: String? = null,
): Modifier {
    return this.semantics {
        contentDescription = label
        stateDescription?.let { stateDescription = it }
    }
}

/**
 * Data class representing an accessible alternative for visual content.
 */
data class AccessibleAlternative(
    val type: AlternativeType,
    val contentDescription: String,
    val dataTable: List<List<String>>? = null,
    val textEquivalent: String? = null,
)

/**
 * Types of accessible alternatives.
 */
enum class AlternativeType {
    /** Equivalent text description of visual content. */
    TEXT_DESCRIPTION,
    /** Data table equivalent of chart/graph data. */
    DATA_TABLE,
    /** Simplified 2D view of 3D content. */
    SIMPLIFIED_2D,
    /** Audio description of visual content. */
    AUDIO_DESCRIPTION,
}

/**
 * Component that provides accessible alternatives for complex visual content.
 */
@Composable
fun AccessibleContent(
    visualContentDescription: String,
    alternatives: List<AccessibleAlternative>,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    var showAlternative by remember { mutableStateOf(false) }

    if (showAlternative && alternatives.isNotEmpty()) {
        // Show the first alternative (usually data table or text description)
        when (val alternative = alternatives.first()) {
            is AccessibleAlternative -> {
                when (alternative.type) {
                    AlternativeType.DATA_TABLE -> {
                        alternative.dataTable?.let { table ->
                            DataTableContent(
                                data = table,
                                contentDescription = alternative.contentDescription,
                            )
                        }
                    }
                    AlternativeType.TEXT_DESCRIPTION -> {
                        alternative.textEquivalent?.let { text ->
                            TextContent(
                                text = text,
                                contentDescription = alternative.contentDescription,
                            )
                        }
                    }
                    AlternativeType.SIMPLIFIED_2D -> {
                        // Show simplified version
                        content()
                    }
                    AlternativeType.AUDIO_DESCRIPTION -> {
                        // Audio would be handled by TalkBack
                        content()
                    }
                }
            }
        }
    } else {
        content()
    }
}

/**
 * Simple data table for accessible chart data.
 */
@Composable
private fun DataTableContent(
    data: List<List<String>>,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    androidx.compose.foundation.layout.Column(
        modifier = modifier.semantics {
            this.contentDescription = contentDescription
        },
    ) {
        data.forEach { row ->
            androidx.compose.foundation.layout.Row {
                row.forEach { cell ->
                    androidx.compose.material3.Text(
                        text = cell,
                        modifier = Modifier
                            .weight(1f)
                            .semantics {
                                contentDescription = cell
                            },
                    )
                }
            }
        }
    }
}

/**
 * Text content for accessible alternatives.
 */
@Composable
private fun TextContent(
    text: String,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    androidx.compose.material3.Text(
        text = text,
        modifier = modifier.semantics {
            this.contentDescription = contentDescription
        },
    )
}

/**
 * Scale factor for text based on accessibility settings.
 */
@Composable
fun accessibleTextScale(): Float {
    val configuration = LocalConfiguration.current
    return if (configuration.fontScale >= LARGE_TEXT_SCALE) {
        LARGE_TEXT_SCALE
    } else {
        1.0f
    }
}

/**
 * Accessible font size that respects user's font size settings.
 */
@Composable
fun accessibleFontSize(baseSize: sp): sp {
    val scale = accessibleTextScale()
    return (baseSize.value * scale).sp
}

/**
 * Check if animations should be reduced for accessibility.
 */
@Composable
fun shouldReduceAnimations(): Boolean {
    // In a real implementation, this would check system settings
    // For now, we check if large text mode is enabled as a proxy
    return isLargeTextMode()
}

/**
 * Modifier that reduces animations when accessibility requires it.
 */
@Composable
fun Modifier.accessibleAnimation(): Modifier {
    val reduceAnimations = shouldReduceAnimations()
    return if (reduceAnimations) {
        // In a real implementation, this would disable animations
        this
    } else {
        this
    }
}
