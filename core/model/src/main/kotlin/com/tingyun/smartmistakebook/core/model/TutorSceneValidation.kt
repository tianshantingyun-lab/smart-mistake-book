package com.tingyun.smartmistakebook.core.model

internal fun requireTutorSceneHeader(
    sceneId: String,
    title: String,
    schemaVersion: Int,
    expectedSchemaVersion: Int = TutorVisualScene.SCHEMA_VERSION,
) {
    sceneId.requireTutorSceneId("Tutor visual scene id")
    title.requireTutorSceneText("Tutor visual scene title", TutorVisualScene.MAX_TITLE_CHARS, false)
    require(schemaVersion == expectedSchemaVersion) {
        "Unsupported tutor visual scene schema version"
    }
}

internal fun String.requireTutorSceneId(label: String) {
    requireSafeModelText(label, ModelTaskRequest.MAX_ID_CHARS, false)
}

internal fun String.requireTutorSceneFormula(label: String) {
    requireTutorSceneText(label, TutorVisualScene.MAX_FORMULA_CHARS, false)
    require(!RestrictedFormulaText.hasUnsupportedCommand(this)) {
        "$label contains an unsupported formula command"
    }
}

internal fun String.requireTutorSceneText(label: String, maxChars: Int, allowLineBreaks: Boolean) {
    requireSafeModelText(label, maxChars, allowLineBreaks)
    StudentFacingLanguagePolicy.requirePlainLanguage(this, label)
    require(!TUTOR_SCENE_HTML.containsMatchIn(this)) { "$label contains HTML" }
    require(!TUTOR_SCENE_CODE_MARKUP.containsMatchIn(this)) { "$label contains code markup" }
    require(!TUTOR_SCENE_MARKDOWN_LINK.containsMatchIn(this)) { "$label contains a Markdown link" }
    require(!TUTOR_SCENE_REFERENCE_LINK.containsMatchIn(this)) { "$label contains a reference link" }
    require(!TUTOR_SCENE_IMAGE_MARKER.containsMatchIn(this)) { "$label contains image markup" }
    require(!TUTOR_SCENE_URL.containsMatchIn(this)) { "$label contains a URL" }
}

internal fun requireUniqueTutorSceneIds(sceneId: String, itemIds: List<String>) {
    val allIds = listOf(sceneId) + itemIds
    require(allIds.distinct().size == allIds.size) { "Tutor visual scene ids must be unique" }
}

internal fun requireTutorSceneTextBudget(parts: List<String>) {
    require(parts.sumOf(String::length) <= TutorVisualScene.MAX_TOTAL_TEXT_CHARS) {
        "Tutor visual scene exceeds its total text budget"
    }
}

private val TUTOR_SCENE_HTML = Regex("(?is)<!--|<\\s*/?\\s*[a-z][^>]*>")
private val TUTOR_SCENE_CODE_MARKUP = Regex("`|~~~")
private val TUTOR_SCENE_MARKDOWN_LINK = Regex(
    """!?\[[^\r\n]{0,256}]\s*\([^\r\n)]{0,2048}\)""",
)
private val TUTOR_SCENE_REFERENCE_LINK = Regex(
    """\[[^\r\n]{1,256}]\s*\[[^\r\n]{0,256}]""",
)
private val TUTOR_SCENE_IMAGE_MARKER = Regex("!\\s*\\[")
private val TUTOR_SCENE_URL = Regex(
    "(?i)(?:\\b(?:https?|ftp|file|mailto|data|javascript):\\S*|\\bwww\\.[^\\s]+)",
)
