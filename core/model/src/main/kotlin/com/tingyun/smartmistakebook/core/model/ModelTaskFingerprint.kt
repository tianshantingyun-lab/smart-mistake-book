package com.tingyun.smartmistakebook.core.model

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

object ModelTaskFingerprint {
    fun of(request: ModelTaskRequest): String =
        request.decodedRequestFingerprintOverride ?: request.fingerprintPayload().sha256()
}

/**
 * Stable identity for one semantic model operation across transport envelopes.
 *
 * A request id, consent receipt, provider choice, configuration version, scheduling time, or
 * retry timestamp may legitimately change when the student explicitly resumes an operation. None
 * of those changes creates a fresh remote-dispatch budget. Only the immutable typed task input
 * participates in this fingerprint.
 */
object ModelTaskLogicalOperationFingerprint {
    fun of(request: ModelTaskRequest): String =
        request.decodedOperationFingerprintOverride ?:
            operationPayload(request.input, request.schemaVersion).sha256()

    fun of(input: ModelTaskInput): String =
        operationPayload(input, ModelTaskRequest.CURRENT_SCHEMA_VERSION).sha256()

    private fun operationPayload(input: ModelTaskInput, schemaVersion: Int): String =
        buildString {
            append(input.kind.name)
            append('\n')
            append(
                logicalOperationJson.encodeToString(ModelTaskInput.serializer(), input)
                    .withoutEmptyPageComparison(input)
                    .withoutLegacyTutorRespondChoiceId(input, schemaVersion),
            )
    }
}

internal fun String.sha256(): String = MessageDigest.getInstance("SHA-256")
    .digest(toByteArray(StandardCharsets.UTF_8))
    .joinToString(separator = "") { byte -> "%02x".format(byte) }

/**
 * Binds one external-model approval to the exact typed input and request envelope.
 *
 * This value is not a secret or a signature. It prevents an approved manifest from being reused
 * for a changed request id, typed payload, question context, or image scope.
 */
object ModelEgressAuthorizationId {
    fun forInput(
        requestId: String,
        input: ModelTaskInput,
    ): String {
        val payload =
            buildString {
                append("model-egress-authorization-v1")
                append('\n')
                append(requestId)
                append('\n')
                append(input.kind.name)
                append('\n')
                append(logicalOperationJson.encodeToString(ModelTaskInput.serializer(), input))
            }
        val fingerprint =
            MessageDigest.getInstance("SHA-256")
                .digest(payload.toByteArray(StandardCharsets.UTF_8))
                .joinToString(separator = "") { byte -> "%02x".format(byte) }
        return "authorization:$fingerprint"
    }
}

private val logicalOperationJson = Json {
    classDiscriminator = "type"
    encodeDefaults = true
    explicitNulls = true
    ignoreUnknownKeys = false
}

private val legacyFingerprintJson = Json {
    classDiscriminator = "type"
    encodeDefaults = true
    explicitNulls = true
    ignoreUnknownKeys = false
}

@Serializable
private data class LegacyModelTaskRequest(
    val schemaVersion: Int,
    val requestId: String,
    val input: ModelTaskInput,
    val occurredAtEpochMillis: Long,
)

private fun ModelTaskRequest.fingerprintPayload(): String =
    if (schemaVersion == ModelTaskRequest.MIN_SUPPORTED_SCHEMA_VERSION) {
        legacyFingerprintJson.encodeToString(
            LegacyModelTaskRequest.serializer(),
            LegacyModelTaskRequest(schemaVersion, requestId, input, occurredAtEpochMillis),
        )
            .withoutLegacyTutorStudentContext(input)
            .withoutEmptyPageComparison(input)
    } else {
        ModelTaskCodec.encodeRequest(this).let { encoded ->
            encoded
                .let {
                    if (schemaVersion < ModelTaskRequest.TUTOR_STUDENT_CONTEXT_SCHEMA_VERSION) {
                        it.withoutLegacyTutorStudentContext(input)
                    } else {
                        it
                    }
                }
                .let {
                    if (schemaVersion < ModelTaskRequest.CAPTURE_PAGE_RELATION_SCHEMA_VERSION) {
                        it.withoutEmptyPageComparison(input)
                    } else {
                        it
                    }
                }
                .let {
                    it.withoutLegacyTutorRespondChoiceId(input, schemaVersion)
                }
        }
    }

private fun String.withoutLegacyTutorStudentContext(input: ModelTaskInput): String =
    if (input is TutorPlanInput) {
        replace(",\"priorCycleStudentMessages\":[]", "")
    } else {
        this
    }

private fun String.withoutEmptyPageComparison(input: ModelTaskInput): String =
    if (input is CaptureAssessmentInput && input.followingSourceAssets.isEmpty()) {
        replace(",\"followingSourceAssets\":[]", "")
    } else {
        this
    }

private fun String.withoutLegacyTutorRespondChoiceId(
    input: ModelTaskInput,
    schemaVersion: Int,
): String =
    if (
        schemaVersion < ModelTaskRequest.TUTOR_RESPOND_CHOICE_ID_SCHEMA_VERSION &&
        input is TutorRespondInput &&
        input.selectedChoiceId == null
    ) {
        replace(",\"selectedChoiceId\":null", "")
    } else {
        this
    }
