package com.tingyun.smartmistakebook.core.data.mistake

import com.tingyun.smartmistakebook.core.data.session.OrganizationSourceCommitSessionReceipt
import com.tingyun.smartmistakebook.core.data.session.ProblemOrganizationWorkSessionPort
import com.tingyun.smartmistakebook.core.data.session.ProblemOrganizationWorkSessionReadQuery
import com.tingyun.smartmistakebook.core.data.session.ProblemOrganizationWorkSessionSnapshot
import com.tingyun.smartmistakebook.core.data.session.ProblemOrganizationWorkSessionStatus
import com.tingyun.smartmistakebook.core.data.session.ProblemOrganizationWorkSessionTransition
import com.tingyun.smartmistakebook.core.data.session.SessionMutationDisposition
import com.tingyun.smartmistakebook.core.data.session.SessionOperationIdentity
import com.tingyun.smartmistakebook.core.data.session.SessionScope
import com.tingyun.smartmistakebook.core.domain.ConfirmedMistakeOrganization
import com.tingyun.smartmistakebook.core.domain.MistakeOrganizationPreparation
import com.tingyun.smartmistakebook.core.domain.MistakeOrganizationRepository
import com.tingyun.smartmistakebook.core.domain.MistakeRevisionKey
import com.tingyun.smartmistakebook.core.domain.ProblemOrganizationConfirmation
import com.tingyun.smartmistakebook.core.domain.ProblemOrganizationSelection
import com.tingyun.smartmistakebook.core.domain.ProblemOrganizationWorkCompletionAuthority
import com.tingyun.smartmistakebook.core.domain.ProblemOrganizationWorkCompletionOutcome
import com.tingyun.smartmistakebook.core.domain.StudyProfileOverview
import com.tingyun.smartmistakebook.core.model.CanonicalSha256
import com.tingyun.smartmistakebook.core.model.ModelTaskCodec
import com.tingyun.smartmistakebook.core.model.ModelTaskFingerprint
import com.tingyun.smartmistakebook.core.model.ModelTaskKind
import com.tingyun.smartmistakebook.core.model.ModelTaskRequest
import com.tingyun.smartmistakebook.core.model.ProblemOrganizationAuthorizationGrant
import com.tingyun.smartmistakebook.core.model.ProblemOrganizationV3Input
import com.tingyun.smartmistakebook.core.model.ProviderCapabilitySnapshot
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentProblemOrganizationCommitFence
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.withContext

/**
 * One owner-issued capability for student-authoritative problem organization.
 *
 * Implementations resolve the committed student problem from [sourceReceipt], review the exact
 * persisted v3 model result, verify every knowledge reference, issue the student-owner proof, and
 * perform the idempotent student-store write. This port exposes no database, DAO, SQL, mastery, or
 * cross-learner surface.
 */
interface StudentProblemOrganizationOwnerPort {
    val scope: SessionScope

    suspend fun prepareCommitted(
        command: PrepareStudentProblemOrganizationCommand,
    ): PreparedStudentProblemOrganization

    suspend fun reviewAndWrite(
        command: ReviewAndWriteStudentProblemOrganizationCommand,
        requireCurrentExecution: () -> Unit,
        commitFence: StudentProblemOrganizationCommitFence,
    ): StudentProblemOrganizationReviewWriteOutcome
}

data class PrepareStudentProblemOrganizationCommand(
    val scope: SessionScope,
    val workId: String,
    val sourceReceipt: OrganizationSourceCommitSessionReceipt,
    val provider: ProviderCapabilitySnapshot,
    val authorization: ProblemOrganizationAuthorizationGrant,
    val requestVersion: Long,
    val occurredAtEpochMillis: Long,
) {
    init {
        require(workId.isBoundedIdentifier()) { "Organization work id is invalid" }
        require(sourceReceipt.scope == scope) { "Organization source receipt crosses learner scope" }
        require(requestVersion >= 0) { "Organization request version must not be negative" }
        require(occurredAtEpochMillis >= 0) { "Organization request time must not be negative" }
    }
}

data class PreparedStudentProblemOrganization(
    val sourceReceiptId: String,
    val sourceReceiptPayloadFingerprint: String,
    val preparation: MistakeOrganizationPreparation,
) {
    init {
        require(sourceReceiptId.isBoundedIdentifier()) {
            "Prepared organization source receipt id is invalid"
        }
        require(sourceReceiptPayloadFingerprint.isSha256()) {
            "Prepared organization source receipt fingerprint is invalid"
        }
    }
}

data class ReviewAndWriteStudentProblemOrganizationCommand(
    val scope: SessionScope,
    val workId: String,
    val sourceReceipt: OrganizationSourceCommitSessionReceipt,
    val requestId: String,
    val requestCanonicalFingerprint: String,
    val expectedWorkVersionSequence: Long,
    val expectedWorkVersionFingerprint: String,
    val leaseOwner: String,
    val leaseExpiresAtEpochMillis: Long,
) {
    init {
        require(workId.isBoundedIdentifier()) { "Organization work id is invalid" }
        require(sourceReceipt.scope == scope) { "Organization source receipt crosses learner scope" }
        require(requestId.isBoundedIdentifier()) { "Organization request id is invalid" }
        require(requestCanonicalFingerprint.isSha256()) {
            "Organization request fingerprint is invalid"
        }
        require(expectedWorkVersionSequence >= 0) {
            "Organization work version must not be negative"
        }
        require(expectedWorkVersionFingerprint.isSha256()) {
            "Organization work version fingerprint is invalid"
        }
        require(leaseOwner.isBoundedIdentifier()) { "Organization lease owner is invalid" }
        require(leaseExpiresAtEpochMillis >= 0) {
            "Organization lease expiry must not be negative"
        }
    }
}

enum class StudentProblemOrganizationNonApplication {
    RETRYABLE,
    TERMINAL,
}

/**
 * [NotApplied] is a zero-write result: no organization revision and no negative learning evidence
 * may be persisted. Low confidence and incomplete knowledge grounding are terminal non-writes;
 * temporary owner/model availability failures are retryable.
 */
sealed interface StudentProblemOrganizationReviewWriteOutcome {
    data class Applied(
        val organizationReceiptId: String,
        val requestId: String,
        val requestCanonicalFingerprint: String,
        val sourceReceiptId: String,
        val sourceReceiptPayloadFingerprint: String,
        val problemRevisionCanonicalFingerprint: String,
        val modelProviderId: String,
        val modelId: String,
        val modelVersion: String,
        val providerConfigurationVersion: String,
        val reviewedModelTaskSchemaVersion: Int,
        val reviewedOrganizationPlanSchemaVersion: Int,
        val verifiedKnowledgeReferenceCount: Int,
        val knowledgeManifestFingerprint: String,
        val knowledgeActivationGeneration: Long,
        val organizationPayloadCanonicalFingerprint: String,
        val ownerReviewProofFingerprint: String,
        val recordedAtEpochMillis: Long,
    ) : StudentProblemOrganizationReviewWriteOutcome {
        init {
            require(organizationReceiptId.isBoundedIdentifier()) {
                "Student organization receipt id is invalid"
            }
            require(requestId.isBoundedIdentifier()) {
                "Student organization request id is invalid"
            }
            require(requestCanonicalFingerprint.isSha256()) {
                "Student organization request fingerprint is invalid"
            }
            require(sourceReceiptId.isBoundedIdentifier()) {
                "Student organization source receipt id is invalid"
            }
            require(sourceReceiptPayloadFingerprint.isSha256()) {
                "Student organization source receipt fingerprint is invalid"
            }
            require(problemRevisionCanonicalFingerprint.isSha256()) {
                "Student organization problem revision fingerprint is invalid"
            }
            require(modelProviderId.isBoundedIdentifier()) {
                "Student organization model provider id is invalid"
            }
            require(modelId.isBoundedIdentifier()) {
                "Student organization model id is invalid"
            }
            require(modelVersion.isBoundedIdentifier()) {
                "Student organization model version is invalid"
            }
            require(providerConfigurationVersion.isBoundedIdentifier()) {
                "Student organization provider configuration version is invalid"
            }
            require(
                reviewedModelTaskSchemaVersion >=
                    ModelTaskRequest.PROBLEM_ORGANIZATION_V3_SCHEMA_VERSION,
            ) { "Student organization write was not reviewed as v3" }
            require(reviewedOrganizationPlanSchemaVersion == 3) {
                "Student organization plan was not reviewed as v3"
            }
            require(verifiedKnowledgeReferenceCount > 0) {
                "Student organization write lacks verified knowledge references"
            }
            require(knowledgeManifestFingerprint.isSha256()) {
                "Student organization write lacks a knowledge manifest"
            }
            require(knowledgeActivationGeneration > 0) {
                "Student organization write lacks a knowledge activation generation"
            }
            require(organizationPayloadCanonicalFingerprint.isSha256()) {
                "Student organization write lacks an exact payload fingerprint"
            }
            require(ownerReviewProofFingerprint.isSha256()) {
                "Student organization write lacks an owner review proof"
            }
            require(recordedAtEpochMillis >= 0) {
                "Student organization receipt time must not be negative"
            }
        }
    }

    data class NotApplied(
        val disposition: StudentProblemOrganizationNonApplication,
        val reasonCode: String,
    ) : StudentProblemOrganizationReviewWriteOutcome {
        init {
            require(reasonCode.isBoundedIdentifier()) {
                "Student organization non-application reason is invalid"
            }
        }
    }
}

internal class StudentProblemOrganizationReviewUnavailableException(
    reasonCode: String,
) : IllegalStateException("Student organization review is temporarily unavailable: $reasonCode")

internal class StudentProblemOrganizationReviewRejectedException(
    reasonCode: String,
) : IllegalArgumentException("Student organization review rejected the result: $reasonCode")

/** Internal completion surface that fences every cross-owner durable boundary. */
internal fun interface GuardedProblemOrganizationWorkCompletionPort {
    suspend fun completeSuccessfulOrganizationWork(
        authority: ProblemOrganizationWorkCompletionAuthority,
        requireCurrentExecution: () -> Unit,
        commitFence: StudentProblemOrganizationCommitFence,
    ): ProblemOrganizationWorkCompletionOutcome
}

/**
 * Production work adapter. The student write and legacy work transition deliberately are not one
 * cross-database transaction. The student write is owner-idempotent by request fingerprint; only
 * after that durable receipt is returned do we compare the lease again and complete the work.
 */
internal class StudentAuthoritativeMistakeOrganizationRepository(
    private val studentOwner: StudentProblemOrganizationOwnerPort,
    private val workSessions: ProblemOrganizationWorkSessionPort,
) : MistakeOrganizationRepository,
    GuardedProblemOrganizationWorkCompletionPort {
    override suspend fun prepareCommittedWork(
        workId: String,
        provider: ProviderCapabilitySnapshot,
        authorization: ProblemOrganizationAuthorizationGrant,
        requestVersion: Long,
        occurredAtEpochMillis: Long,
    ): MistakeOrganizationPreparation = withContext(Dispatchers.IO) {
        require(provider.supports(ModelTaskKind.PROBLEM_CLASSIFY)) {
            "Current provider does not support problem organization"
        }
        require(provider.supportsImageInput) {
            "Image-grounded organization requires image input support"
        }
        require(authorization.matchesCurrent(provider, occurredAtEpochMillis)) {
            "Problem organization authorization is not current"
        }
        val work = requireWork(workId)
        require(work.status == ProblemOrganizationWorkSessionStatus.WAITING_AUTHORIZATION) {
            "Only waiting organization work can be prepared"
        }
        require(work.version.sequence == requestVersion) {
            "Organization work version changed before preparation"
        }
        val sourceReceipt = requireSourceReceipt(work)
        val prepared =
            studentOwner.prepareCommitted(
                PrepareStudentProblemOrganizationCommand(
                    scope = studentOwner.scope,
                    workId = work.workId,
                    sourceReceipt = sourceReceipt,
                    provider = provider,
                    authorization = authorization,
                    requestVersion = requestVersion,
                    occurredAtEpochMillis = occurredAtEpochMillis,
                ),
            )
        require(
            prepared.sourceReceiptId == sourceReceipt.receiptId &&
                prepared.sourceReceiptPayloadFingerprint == sourceReceipt.payloadFingerprint,
        ) { "Prepared organization does not match the durable source receipt" }
        prepared.preparation.requireReviewedV3Request(authorization)
    }

    override suspend fun completeSuccessfulOrganizationWork(
        authority: ProblemOrganizationWorkCompletionAuthority,
    ): ProblemOrganizationWorkCompletionOutcome {
        val requireCurrentExecution: () -> Unit = {}
        return completeSuccessfulOrganizationWork(
            authority = authority,
            requireCurrentExecution = requireCurrentExecution,
            commitFence = currentExecutionCheckCommitFence(requireCurrentExecution),
        )
    }

    override suspend fun completeSuccessfulOrganizationWork(
        authority: ProblemOrganizationWorkCompletionAuthority,
        requireCurrentExecution: () -> Unit,
        commitFence: StudentProblemOrganizationCommitFence,
    ): ProblemOrganizationWorkCompletionOutcome = withContext(Dispatchers.IO) {
        val beforeWrite = guardedExecutionBoundary(requireCurrentExecution) {
            workSessions.read(
                ProblemOrganizationWorkSessionReadQuery.ByWorkId(
                    studentOwner.scope,
                    authority.workId,
                ),
            )
        } ?: return@withContext ProblemOrganizationWorkCompletionOutcome.LOST_AUTHORITY
        if (beforeWrite.isCompletedFor(authority.requestId)) {
            return@withContext ProblemOrganizationWorkCompletionOutcome.COMPLETED
        }
        if (!beforeWrite.isHeldBy(authority)) {
            return@withContext ProblemOrganizationWorkCompletionOutcome.LOST_AUTHORITY
        }
        val request = beforeWrite.requirePersistedV3Request(authority.requestId)
        val sourceReceipt = requireSourceReceipt(beforeWrite, requireCurrentExecution)
        val write =
            guardedExecutionBoundary(requireCurrentExecution) {
                studentOwner.reviewAndWrite(
                    ReviewAndWriteStudentProblemOrganizationCommand(
                        scope = studentOwner.scope,
                        workId = beforeWrite.workId,
                        sourceReceipt = sourceReceipt,
                        requestId = request.requestId,
                        requestCanonicalFingerprint = ModelTaskFingerprint.of(request),
                        expectedWorkVersionSequence = beforeWrite.version.sequence,
                        expectedWorkVersionFingerprint = beforeWrite.version.fingerprint,
                        leaseOwner = checkNotNull(beforeWrite.leaseOwner),
                        leaseExpiresAtEpochMillis =
                            checkNotNull(beforeWrite.leaseExpiresAtEpochMillis),
                    ),
                    requireCurrentExecution,
                    commitFence,
                )
            }
        val applied =
            when (write) {
                is StudentProblemOrganizationReviewWriteOutcome.Applied -> write
                is StudentProblemOrganizationReviewWriteOutcome.NotApplied ->
                    when (write.disposition) {
                        StudentProblemOrganizationNonApplication.RETRYABLE ->
                            throw StudentProblemOrganizationReviewUnavailableException(
                                write.reasonCode,
                            )
                        StudentProblemOrganizationNonApplication.TERMINAL ->
                            throw StudentProblemOrganizationReviewRejectedException(
                                write.reasonCode,
                            )
                    }
            }
        applied.requireExact(request, sourceReceipt)

        // A slow owner review cannot use an old result to complete a work item now held elsewhere.
        val afterWrite = guardedExecutionBoundary(requireCurrentExecution) {
            workSessions.read(
                ProblemOrganizationWorkSessionReadQuery.ByWorkId(
                    studentOwner.scope,
                    authority.workId,
                ),
            )
        } ?: return@withContext ProblemOrganizationWorkCompletionOutcome.LOST_AUTHORITY
        if (afterWrite.isCompletedFor(authority.requestId)) {
            return@withContext ProblemOrganizationWorkCompletionOutcome.COMPLETED
        }
        if (
            !afterWrite.isHeldBy(authority) ||
            afterWrite.version != beforeWrite.version ||
            applied.recordedAtEpochMillis >
                checkNotNull(afterWrite.leaseExpiresAtEpochMillis) {
                    "Running organization work lost its lease expiry"
                }
        ) {
            return@withContext ProblemOrganizationWorkCompletionOutcome.LOST_AUTHORITY
        }

        val occurredAtEpochMillis =
            maxOf(applied.recordedAtEpochMillis, afterWrite.updatedAtEpochMillis)
        val payloadFingerprint =
            completionFingerprint(
                work = afterWrite,
                sourceReceipt = sourceReceipt,
                applied = applied,
            )
        val transition =
            ProblemOrganizationWorkSessionTransition.Complete(
                scope = studentOwner.scope,
                operation =
                    SessionOperationIdentity(
                        requestId = authority.requestId,
                        idempotencyKey = "student-organization-complete-$payloadFingerprint",
                        requestVersion = afterWrite.version.sequence,
                        payloadFingerprint = payloadFingerprint,
                    ),
                workId = afterWrite.workId,
                expectedVersion = afterWrite.version,
                leaseOwner = authority.leaseOwner,
                occurredAtEpochMillis = occurredAtEpochMillis,
                completedRequestId = authority.requestId,
            )
        val result = guardedExecutionBoundary(requireCurrentExecution) {
            workSessions.transition(transition)
        }
        if (
            (
                result.receipt.disposition == SessionMutationDisposition.APPLIED ||
                    result.receipt.disposition == SessionMutationDisposition.DUPLICATE
            ) &&
            result.snapshot?.isCompletedFor(authority.requestId) == true
        ) {
            ProblemOrganizationWorkCompletionOutcome.COMPLETED
        } else {
            ProblemOrganizationWorkCompletionOutcome.LOST_AUTHORITY
        }
    }

    // Student-authoritative UI reads use the student-mistake detail/catalog projections. These
    // legacy string-keyed APIs stay closed instead of crossing the owner boundary.
    override suspend fun prepare(
        key: MistakeRevisionKey,
        profile: StudyProfileOverview,
        provider: ProviderCapabilitySnapshot,
        attempt: Int,
        occurredAtEpochMillis: Long,
        approvedAtEpochMillis: Long,
    ): MistakeOrganizationPreparation =
        throw UnsupportedOperationException("Legacy organization preparation is disabled")

    override fun observeConfirmed(
        key: MistakeRevisionKey,
    ): Flow<ConfirmedMistakeOrganization> = emptyFlow()

    override suspend fun applySuccessfulOrganization(
        requestId: String,
    ): ProblemOrganizationConfirmation =
        throw UnsupportedOperationException("Unleased organization writes are disabled")

    override suspend fun confirm(
        requestId: String,
        selection: ProblemOrganizationSelection,
        acceptedAtEpochMillis: Long,
    ): ProblemOrganizationConfirmation =
        throw UnsupportedOperationException("Legacy organization correction is disabled")

    private suspend fun requireWork(workId: String): ProblemOrganizationWorkSessionSnapshot {
        require(workId.isBoundedIdentifier()) { "Organization work id is invalid" }
        return workSessions.read(
            ProblemOrganizationWorkSessionReadQuery.ByWorkId(studentOwner.scope, workId),
        ) ?: error("Organization work was not found")
    }

    private suspend fun requireSourceReceipt(
        work: ProblemOrganizationWorkSessionSnapshot,
        requireCurrentExecution: () -> Unit = {},
    ): OrganizationSourceCommitSessionReceipt {
        require(work.scope == studentOwner.scope) {
            "Organization work crosses the student-owner learner scope"
        }
        return guardedExecutionBoundary(requireCurrentExecution) {
            workSessions.readSourceReceipt(
                studentOwner.scope,
                work.sourceCommitReceiptId,
            )
        }?.also { receipt ->
            require(
                receipt.scope == studentOwner.scope &&
                    receipt.receiptId == work.sourceCommitReceiptId,
            ) { "Organization source receipt does not match the work session" }
        } ?: error("Organization source receipt was not found")
    }
}

internal fun currentExecutionCheckCommitFence(
    requireCurrentExecution: () -> Unit,
): StudentProblemOrganizationCommitFence =
    object : StudentProblemOrganizationCommitFence {
        override fun requireCurrentOwner() {
            requireCurrentExecution()
        }
    }

private suspend inline fun <Result> guardedExecutionBoundary(
    requireCurrentExecution: () -> Unit,
    crossinline operation: suspend () -> Result,
): Result {
    requireCurrentExecution()
    val result = operation()
    requireCurrentExecution()
    return result
}

object StudentAuthoritativeMistakeOrganizationRepositoryFactory {
    fun createProduction(
        studentOrganizationOwner: StudentProblemOrganizationOwnerPort,
        organizationWorkSessions: ProblemOrganizationWorkSessionPort,
    ): MistakeOrganizationRepository =
        StudentAuthoritativeMistakeOrganizationRepository(
            studentOwner = studentOrganizationOwner,
            workSessions = organizationWorkSessions,
        )
}

private fun MistakeOrganizationPreparation.requireReviewedV3Request(
    authorization: ProblemOrganizationAuthorizationGrant,
): MistakeOrganizationPreparation {
    val input = request.input as? ProblemOrganizationV3Input
        ?: throw IllegalArgumentException("Student owner did not prepare a v3 organization input")
    require(request.schemaVersion >= ModelTaskRequest.PROBLEM_ORGANIZATION_V3_SCHEMA_VERSION) {
        "Student owner did not prepare a v3 organization request"
    }
    require(request.egressManifest == authorization.toEgressManifest(request.requestId, input)) {
        "Student owner did not bind the exact authorized organization egress"
    }
    return this
}

private fun ProblemOrganizationWorkSessionSnapshot.requirePersistedV3Request(
    expectedRequestId: String,
): ModelTaskRequest {
    val encoded = requestPayload?.content
        ?: throw IllegalArgumentException("Organization work has no persisted request")
    val decoded =
        try {
            ModelTaskCodec.decodeRequest(encoded)
        } catch (failure: Exception) {
            throw IllegalArgumentException("Organization work request cannot be decoded", failure)
        }
    require(
        decoded.requestId == expectedRequestId &&
            requestId == expectedRequestId &&
            decoded.schemaVersion >= ModelTaskRequest.PROBLEM_ORGANIZATION_V3_SCHEMA_VERSION &&
            decoded.input is ProblemOrganizationV3Input,
    ) { "Organization work does not contain the exact reviewed v3 request" }
    return decoded
}

private fun ProblemOrganizationWorkSessionSnapshot.isHeldBy(
    authority: ProblemOrganizationWorkCompletionAuthority,
): Boolean =
    scope.learnerId.isNotBlank() &&
        status == ProblemOrganizationWorkSessionStatus.RUNNING &&
        workId == authority.workId &&
        version.sequence == authority.expectedStateVersion &&
        leaseOwner == authority.leaseOwner &&
        requestId == authority.requestId

private fun ProblemOrganizationWorkSessionSnapshot.isCompletedFor(
    expectedRequestId: String,
): Boolean =
    status == ProblemOrganizationWorkSessionStatus.SUCCEEDED &&
        requestId == expectedRequestId

private fun StudentProblemOrganizationReviewWriteOutcome.Applied.requireExact(
    request: ModelTaskRequest,
    sourceReceipt: OrganizationSourceCommitSessionReceipt,
) {
    val egress = checkNotNull(request.egressManifest) {
        "Student organization request lacks its egress identity"
    }
    require(
        requestId == request.requestId &&
            requestCanonicalFingerprint == ModelTaskFingerprint.of(request) &&
            sourceReceiptId == sourceReceipt.receiptId &&
            sourceReceiptPayloadFingerprint == sourceReceipt.payloadFingerprint &&
            modelProviderId == egress.providerId &&
            modelId == egress.modelId &&
            providerConfigurationVersion == egress.providerConfigurationVersion &&
            reviewedModelTaskSchemaVersion == request.schemaVersion &&
            reviewedOrganizationPlanSchemaVersion == 3 &&
            recordedAtEpochMillis >= request.occurredAtEpochMillis,
    ) { "Student owner receipt does not match the reviewed request" }
}

private fun completionFingerprint(
    work: ProblemOrganizationWorkSessionSnapshot,
    sourceReceipt: OrganizationSourceCommitSessionReceipt,
    applied: StudentProblemOrganizationReviewWriteOutcome.Applied,
): String =
    CanonicalSha256("student-authoritative-organization-work-completion-v1")
        .field("learnerId", work.scope.learnerId)
        .field("workId", work.workId)
        .field("workVersion", work.version.sequence)
        .field("workFingerprint", work.version.fingerprint)
        .field("leaseOwner", checkNotNull(work.leaseOwner))
        .field("sourceReceiptId", sourceReceipt.receiptId)
        .field("sourceReceiptFingerprint", sourceReceipt.payloadFingerprint)
        .field("organizationReceiptId", applied.organizationReceiptId)
        .field("requestId", applied.requestId)
        .field("requestFingerprint", applied.requestCanonicalFingerprint)
        .field("problemRevisionFingerprint", applied.problemRevisionCanonicalFingerprint)
        .field("modelProviderId", applied.modelProviderId)
        .field("modelId", applied.modelId)
        .field("modelVersion", applied.modelVersion)
        .field("providerConfigurationVersion", applied.providerConfigurationVersion)
        .field("modelTaskSchemaVersion", applied.reviewedModelTaskSchemaVersion)
        .field("organizationPlanSchemaVersion", applied.reviewedOrganizationPlanSchemaVersion)
        .field("knowledgeManifestFingerprint", applied.knowledgeManifestFingerprint)
        .field("knowledgeActivationGeneration", applied.knowledgeActivationGeneration)
        .field(
            "organizationPayloadFingerprint",
            applied.organizationPayloadCanonicalFingerprint,
        )
        .field("ownerReviewProofFingerprint", applied.ownerReviewProofFingerprint)
        .finish()

private fun String.isBoundedIdentifier(): Boolean =
    isNotBlank() && this == trim() && length <= 256 && none(Char::isISOControl)

private fun String.isSha256(): Boolean =
    length == 64 && all { it in "0123456789abcdef" }
