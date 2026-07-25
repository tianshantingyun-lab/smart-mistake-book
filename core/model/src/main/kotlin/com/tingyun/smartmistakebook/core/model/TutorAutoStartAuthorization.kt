package com.tingyun.smartmistakebook.core.model

/**
 * One-process evidence that a freshly captured question may start its first tutor plan without a
 * second confirmation. The app must never persist this value.
 */
class TutorAutoStartAuthorization private constructor(
    val authorizationId: String,
    val sessionId: String,
    val questionDocumentId: String,
    val revisionNumber: Int,
    val providerId: String,
    val modelId: String,
    val providerConfigurationVersion: String,
    val promptPolicyVersion: String,
    val approvedAtEpochMillis: Long,
) {
    fun matches(
        sessionId: String,
        questionDocumentId: String,
        revisionNumber: Int,
        provider: ProviderCapabilitySnapshot,
        promptPolicyVersion: String,
        nowEpochMillis: Long,
    ): Boolean = this.sessionId == sessionId &&
        this.questionDocumentId == questionDocumentId &&
        this.revisionNumber == revisionNumber &&
        provider.executionLocation == ModelExecutionLocation.EXTERNAL_PROVIDER &&
        provider.supports(ModelTaskKind.TUTOR_PLAN) &&
        provider.providerId == providerId &&
        provider.modelId == modelId &&
        provider.providerConfigurationVersion == providerConfigurationVersion &&
        this.promptPolicyVersion == promptPolicyVersion &&
        isModelEgressApprovalFresh(approvedAtEpochMillis, nowEpochMillis)

    companion object {
        fun grant(
            authorizationId: String,
            sessionId: String,
            questionDocumentId: String,
            revisionNumber: Int,
            provider: ProviderCapabilitySnapshot,
            promptPolicyVersion: String,
            approvedAtEpochMillis: Long,
        ): TutorAutoStartAuthorization {
            require(authorizationId.isNotBlank())
            require(sessionId.isNotBlank())
            require(questionDocumentId.isNotBlank())
            require(revisionNumber > 0)
            require(provider.executionLocation == ModelExecutionLocation.EXTERNAL_PROVIDER)
            require(provider.supports(ModelTaskKind.TUTOR_PLAN))
            require(promptPolicyVersion.isNotBlank())
            require(approvedAtEpochMillis >= 0)
            return TutorAutoStartAuthorization(
                authorizationId = authorizationId,
                sessionId = sessionId,
                questionDocumentId = questionDocumentId,
                revisionNumber = revisionNumber,
                providerId = provider.providerId,
                modelId = provider.modelId,
                providerConfigurationVersion = provider.providerConfigurationVersion,
                promptPolicyVersion = promptPolicyVersion,
                approvedAtEpochMillis = approvedAtEpochMillis,
            )
        }
    }
}
