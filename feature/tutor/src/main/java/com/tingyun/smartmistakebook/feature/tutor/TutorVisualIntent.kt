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
            if (NEGATED_VISUAL_REQUEST.containsMatchIn(normalized)) return null
            if (!REQUEST_SIGNAL.containsMatchIn(normalized)) return null
            val kind = when {
                THREE_DIMENSIONAL_SIGNAL.containsMatchIn(normalized) ->
                    VisualIntentKind.THREE_DIMENSIONAL
                ANIMATION_SIGNAL.containsMatchIn(normalized) -> VisualIntentKind.ANIMATION
                DIAGRAM_SIGNAL.containsMatchIn(normalized) -> VisualIntentKind.DIAGRAM
                VISUALIZATION_SIGNAL.containsMatchIn(normalized) -> VisualIntentKind.VISUALIZATION
                else -> return null
            }
            return VisualIntent(kind = kind, focusMarkdown = focus)
        }

        private val NEGATED_VISUAL_REQUEST = Regex(
            """(?:不是|不需要|不用|不要|无需|别)""" +
                """(?:\s*(?:再|直接|只|给我|为我|帮我))*\s*""" +
                """(?:画|绘制|生成|展示|演示|做成)?\s*""" +
                """(?:一?个|一?张|这?个)?\s*""" +
                """(?:受力图|关系图|结构图|流程图|示意图|图解|图示|图|动画|动图|""" +
                """3\s*d|三维|立体|可视化)""",
        )
        private val REQUEST_SIGNAL = Regex(
            """(?:请|帮我|给我|能否|可以|画|绘制|生成|展示|演示|做成|看一下|看看|用.+(?:解释|说明)|show|draw|make|generate|visuali[sz]e|animate)""",
        )
        private val THREE_DIMENSIONAL_SIGNAL = Regex(
            """(?:3\s*d|三维|立体(?:图|模型|视图)|空间模型)""",
        )
        private val ANIMATION_SIGNAL = Regex("""(?:动画|动图|动态演示|animation|animate)""")
        private val DIAGRAM_SIGNAL = Regex(
            """(?:图解|图示|示意图|流程图|受力图|关系图|结构图|画.+图|diagram|flowchart)""",
        )
        private val VISUALIZATION_SIGNAL = Regex("""(?:可视化|visuali[sz](?:e|ation))""")
    }
}
