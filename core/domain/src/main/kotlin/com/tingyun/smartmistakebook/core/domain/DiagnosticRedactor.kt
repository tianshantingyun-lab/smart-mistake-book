package com.tingyun.smartmistakebook.core.domain

object DiagnosticRedactor {
    const val REDACTED = "[REDACTED]"

    fun redactId(value: String?): String = REDACTED

    fun redactMessage(
        message: String,
        vararg sensitiveValues: String?,
    ): String {
        var result = message
        sensitiveValues
            .filterNotNull()
            .distinct()
            .sortedByDescending(String::length)
            .forEach { value ->
                if (value.isNotBlank()) {
                    result = result.replace(value, REDACTED)
                }
            }
        return result
    }
}
