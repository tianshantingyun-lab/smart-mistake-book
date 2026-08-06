package com.tingyun.smartmistakebook.core.database

import com.tingyun.smartmistakebook.core.model.ModelTaskCodec
import com.tingyun.smartmistakebook.core.model.ModelTaskKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Structured detector for obsolete tutor snapshots that persisted raw mastery numbers.
 *
 * Detection is deliberately limited to the direct legacy fields of a tutor input. Field-looking
 * text in the question and nested attacker-controlled objects are never searched recursively.
 */
internal object ModelTaskCacheHygiene {
    private val json = Json {
        isLenient = false
        ignoreUnknownKeys = false
    }

    fun containsDeprecatedTutorMasteryNumbers(
        taskKind: String,
        requestSnapshot: String,
    ): Boolean {
        val expectedInputType = when (taskKind) {
            ModelTaskKind.TUTOR_PLAN.name -> "tutor_plan"
            ModelTaskKind.TUTOR_RESPOND.name -> "tutor_respond"
            else -> return false
        }
        if (requestSnapshot.length > ModelTaskCodec.MAX_ENCODED_CHARS) return false
        if (!requestSnapshot.hasBoundedJsonNesting()) return false
        val root = runCatching {
            json.parseToJsonElement(requestSnapshot).jsonObject
        }.getOrNull() ?: return false
        val input = root["input"] as? JsonObject ?: return false
        if ((input["type"] as? JsonPrimitive)?.contentOrNull != expectedInputType) return false

        return (input["relevantLearningEvidence"] as? JsonArray)
            ?.any(::containsKnowledgeMasteryNumber) == true ||
            (input["questionLearningEvidence"] as? JsonObject)
                ?.containsNumericValueFor(QUESTION_MASTERY_NUMBER_FIELDS) == true
    }

    private fun containsKnowledgeMasteryNumber(element: kotlinx.serialization.json.JsonElement) =
        (element as? JsonObject)
            ?.containsNumericValueFor(KNOWLEDGE_MASTERY_NUMBER_FIELDS) == true

    private fun JsonObject.containsNumericValueFor(fieldNames: Set<String>): Boolean =
        fieldNames.any { fieldName ->
            val value = this[fieldName] as? JsonPrimitive
            value != null && !value.isString && value.doubleOrNull != null
        }

    private fun String.hasBoundedJsonNesting(): Boolean {
        var depth = 0
        var inString = false
        var escaped = false
        forEach { character ->
            if (inString) {
                when {
                    escaped -> escaped = false
                    character == '\\' -> escaped = true
                    character == '"' -> inString = false
                }
            } else {
                when (character) {
                    '"' -> inString = true
                    '{', '[' -> {
                        depth++
                        if (depth > MAX_JSON_DEPTH) return false
                    }
                    '}', ']' -> {
                        depth--
                        if (depth < 0) return false
                    }
                }
            }
        }
        return depth == 0 && !inString && !escaped
    }

    private val KNOWLEDGE_MASTERY_NUMBER_FIELDS = setOf(
        "independentCorrectLowerBound",
        "evidenceMass",
        "independentCorrectObservationCount",
    )
    private val QUESTION_MASTERY_NUMBER_FIELDS = setOf(
        "independentRecallCount",
        "assistedRecallCount",
        "retrievalFailureCount",
        "answerRevealCount",
        "retentionEstimate",
    )
    private const val MAX_JSON_DEPTH = 64
}
