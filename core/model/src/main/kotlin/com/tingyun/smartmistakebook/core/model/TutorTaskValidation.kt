package com.tingyun.smartmistakebook.core.model

internal fun String.requireTutorMarkdown(label: String, maxChars: Int) {
    requireSafeModelText(label, maxChars, true)
    StudentFacingLanguagePolicy.requirePlainLanguage(this, label)
    val normalized = lowercase()
    require("<script" !in normalized && "javascript:" !in normalized) {
        "$label contains active content"
    }
}

internal fun String.requireTutorRespondText(label: String, maxChars: Int) {
    requireTutorSceneText(label, maxChars, true)
}
