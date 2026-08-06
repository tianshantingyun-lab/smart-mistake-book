package com.tingyun.smartmistakebook.core.model.provider

import com.tingyun.smartmistakebook.core.model.ModelRequestBudgetExceededException
import com.tingyun.smartmistakebook.core.model.ModelTaskInput
import com.tingyun.smartmistakebook.core.model.TutorPlanInput
import com.tingyun.smartmistakebook.core.model.TutorRespondInput
import java.nio.charset.StandardCharsets

internal data class OpenAiTextRequestMeasurement(
    val serializedChars: Int,
    val exactUtf8Bytes: Long,
    /** Tokenizer-independent upper bound; no claim of exact token usage is made. */
    val conservativeTokenUpperBound: Long,
)

internal object OpenAiTextRequestBudget {
    const val POLICY_VERSION = "openai-text-request-budget-v1"
    const val MAX_SERIALIZED_CHARS = 180_000
    const val MAX_EXACT_UTF8_BYTES = 512L * 1_024L
    const val MAX_CONSERVATIVE_TOKEN_UPPER_BOUND = 512L * 1_024L
    const val MAX_TUTOR_SERIALIZED_CHARS = 96_000
    const val MAX_TUTOR_EXACT_UTF8_BYTES = 192L * 1_024L
    const val MAX_TUTOR_CONSERVATIVE_TOKEN_UPPER_BOUND = 192L * 1_024L

    fun measure(serializedJson: String): OpenAiTextRequestMeasurement {
        val exactUtf8Bytes = serializedJson.toByteArray(StandardCharsets.UTF_8).size.toLong()
        return OpenAiTextRequestMeasurement(
            serializedChars = serializedJson.length,
            exactUtf8Bytes = exactUtf8Bytes,
            conservativeTokenUpperBound = exactUtf8Bytes,
        )
    }

    fun requireFits(
        serializedJson: String,
        input: ModelTaskInput? = null,
    ): OpenAiTextRequestMeasurement =
        measure(serializedJson).also { measurement ->
            if (!fits(measurement, input)) {
                throw ModelRequestBudgetExceededException()
            }
        }

    fun fits(
        serializedJson: String,
        input: ModelTaskInput? = null,
    ): Boolean = fits(measure(serializedJson), input)

    private fun fits(
        measurement: OpenAiTextRequestMeasurement,
        input: ModelTaskInput?,
    ): Boolean {
        val isTutorTextTask = input is TutorPlanInput || input is TutorRespondInput
        return measurement.serializedChars <= MAX_SERIALIZED_CHARS &&
            measurement.exactUtf8Bytes <= MAX_EXACT_UTF8_BYTES &&
            measurement.conservativeTokenUpperBound <=
            MAX_CONSERVATIVE_TOKEN_UPPER_BOUND &&
            (!isTutorTextTask || (
                measurement.serializedChars <= MAX_TUTOR_SERIALIZED_CHARS &&
                    measurement.exactUtf8Bytes <= MAX_TUTOR_EXACT_UTF8_BYTES &&
                    measurement.conservativeTokenUpperBound <=
                    MAX_TUTOR_CONSERVATIVE_TOKEN_UPPER_BOUND
            ))
    }
}
