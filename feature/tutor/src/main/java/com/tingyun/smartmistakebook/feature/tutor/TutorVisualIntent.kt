package com.tingyun.smartmistakebook.feature.tutor

import com.tingyun.smartmistakebook.core.model.TutorVisualGenerationRequest

internal enum class VisualIntentKind {
    DIAGRAM,
    ANIMATION,
    THREE_DIMENSIONAL,
    VISUALIZATION,
}

/**
 * Local authority for an explicitly requested visual.
 *
 * It is reconstructed from the persisted student message, so visual work does not depend on the
 * text model remembering to return an optional visual request.
 */
internal data class VisualIntent(
    val kind: VisualIntentKind,
    val focusMarkdown: String,
) {
    fun toGenerationRequest() = TutorVisualGenerationRequest(focusMarkdown)

    companion object {
        fun detect(studentMessage: String): VisualIntent? {
            val focus = studentMessage.trim()
            if (focus.isEmpty()) return null
            val normalized = focus.lowercase()
            var requestedKind: VisualIntentKind? = null
            normalized.split(CLAUSE_BOUNDARY).forEach { clause ->
                if (clause.isBlank()) return@forEach
                val negatedKinds = negatedVisualKinds(clause)
                if (
                    NEGATED_GENERIC_VISUAL_REQUEST.containsMatchIn(clause) ||
                    requestedKind in negatedKinds
                ) {
                    requestedKind = null
                }
                val clauseRequest = visualKind(clause)?.takeIf {
                    REQUEST_SIGNAL.containsMatchIn(clause) &&
                        it !in negatedKinds &&
                        !NEGATED_GENERIC_VISUAL_REQUEST.containsMatchIn(clause)
                }
                if (clauseRequest != null) requestedKind = clauseRequest
            }
            return requestedKind?.let { kind ->
                VisualIntent(kind = kind, focusMarkdown = focus)
            }
        }

        private fun negatedVisualKinds(clause: String): Set<VisualIntentKind> {
            if (!VISUAL_DENIAL_MARKER.containsMatchIn(clause)) return emptySet()
            return VisualIntentKind.entries.filterTo(mutableSetOf()) { kind ->
                visualSignal(kind).containsMatchIn(clause)
            }
        }

        private fun visualKind(value: String): VisualIntentKind? = when {
            THREE_DIMENSIONAL_SIGNAL.containsMatchIn(value) ->
                VisualIntentKind.THREE_DIMENSIONAL
            ANIMATION_SIGNAL.containsMatchIn(value) -> VisualIntentKind.ANIMATION
            DIAGRAM_SIGNAL.containsMatchIn(value) -> VisualIntentKind.DIAGRAM
            VISUALIZATION_SIGNAL.containsMatchIn(value) -> VisualIntentKind.VISUALIZATION
            else -> null
        }

        private fun visualSignal(kind: VisualIntentKind): Regex = when (kind) {
            VisualIntentKind.DIAGRAM -> DIAGRAM_SIGNAL
            VisualIntentKind.ANIMATION -> ANIMATION_SIGNAL
            VisualIntentKind.THREE_DIMENSIONAL -> THREE_DIMENSIONAL_SIGNAL
            VisualIntentKind.VISUALIZATION -> VISUALIZATION_SIGNAL
        }

        private val CLAUSE_BOUNDARY = Regex(
            """(?:[，,。.!！?？;；\n]+|\b(?:but|however|instead)\b|但是|但|不过|而是)""",
        )
        private val VISUAL_DENIAL_MARKER = Regex(
            """(?:不是|不需要|不用|不要|无需|不想|别|停止|避免|""" +
                """\b(?:don't|do\s+not|dont|no|without|stop)\b)""",
        )
        private val NEGATED_GENERIC_VISUAL_REQUEST = Regex(
            """(?:""" +
                """(?:不需要|不用|不要|无需|不想|别)\s*(?:任何|所有)?\s*""" +
                """(?:视觉(?:内容|解释)?|图像|图片|图示)""" +
                """|(?:don't|do\s+not|dont|no|without|stop)\s+""" +
                """(?:(?:use|using)\s+)?(?:any\s+|all\s+)?(?:visuals?|images?)""" +
                """)""",
        )
        private val REQUEST_SIGNAL = Regex(
            """(?:请|帮我|给我|能否|可以|画|绘制|生成|展示|演示|做成|看一下|看看|用.+(?:解释|说明)|show|draw|make|generate|use|visuali[sz]e|animate)""",
        )
        private val THREE_DIMENSIONAL_SIGNAL = Regex(
            """(?:3\s*d|三维|立体(?:图|模型|视图)|空间模型)""",
        )
        private val ANIMATION_SIGNAL = Regex("""(?:动画|动图|动态演示|animation|animate)""")
        private val DIAGRAM_SIGNAL = Regex(
            """(?:图解|图示|示意图|流程图|受力图|关系图|结构图|画.+图|diagram|flowchart|illustration|graphic)""",
        )
        private val VISUALIZATION_SIGNAL = Regex("""(?:可视化|visuali[sz](?:e|ation))""")
    }
}
