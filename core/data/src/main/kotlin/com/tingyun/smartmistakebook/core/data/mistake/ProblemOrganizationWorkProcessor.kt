package com.tingyun.smartmistakebook.core.data.mistake

import com.tingyun.smartmistakebook.core.data.session.AuthorizeProblemOrganizationWorkSessionCommand
import com.tingyun.smartmistakebook.core.data.session.ClaimProblemOrganizationWorkSessionCommand
import com.tingyun.smartmistakebook.core.data.session.ProblemOrganizationWorkSessionMutationResult
import com.tingyun.smartmistakebook.core.data.session.ProblemOrganizationWorkSessionPort
import com.tingyun.smartmistakebook.core.data.session.ProblemOrganizationWorkSessionReadQuery
import com.tingyun.smartmistakebook.core.data.session.ProblemOrganizationWorkSessionSnapshot
import com.tingyun.smartmistakebook.core.data.session.ProblemOrganizationWorkSessionStatus
import com.tingyun.smartmistakebook.core.data.session.ProblemOrganizationWorkSessionTransition
import com.tingyun.smartmistakebook.core.data.session.SessionMutationDisposition
import com.tingyun.smartmistakebook.core.data.session.SessionOpaquePayload
import com.tingyun.smartmistakebook.core.data.session.SessionOperationIdentity
import com.tingyun.smartmistakebook.core.data.session.SessionScope
import com.tingyun.smartmistakebook.core.domain.MistakeOrganizationRepository
import com.tingyun.smartmistakebook.core.domain.ModelTaskRepository
import com.tingyun.smartmistakebook.core.domain.ProblemOrganizationImmutableConflictException
import com.tingyun.smartmistakebook.core.domain.ProblemOrganizationWorkCompletionAuthority
import com.tingyun.smartmistakebook.core.domain.ProblemOrganizationWorkCompletionOutcome
import com.tingyun.smartmistakebook.core.data.production.ProductionProblemOrganizationExecutionRevokedException
import com.tingyun.smartmistakebook.core.model.CanonicalSha256
import com.tingyun.smartmistakebook.core.model.ModelFailureCode
import com.tingyun.smartmistakebook.core.model.ModelEgressAuthorizationException
import com.tingyun.smartmistakebook.core.model.ModelTaskCodec
import com.tingyun.smartmistakebook.core.model.ModelTaskRequest
import com.tingyun.smartmistakebook.core.model.ModelTaskSnapshot
import com.tingyun.smartmistakebook.core.model.ModelTaskStatus
import com.tingyun.smartmistakebook.core.model.ProblemOrganizationV3Input
import com.tingyun.smartmistakebook.core.model.ProblemOrganizationAuthorizationGrantCodec
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentProblemOrganizationCommitFence
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect

/** Processes one durable organization work item after claiming its exact [workId]. */
class ProblemOrganizationWorkProcessor(
    private val scope: SessionScope,
    private val sessions: ProblemOrganizationWorkSessionPort,
    private val modelTasks: ModelTaskRepository,
    private val organizations: MistakeOrganizationRepository,
    private val clock: () -> Long = System::currentTimeMillis,
    private val trustedAnswerRules: ProblemOrganizationTrustedAnswerRuleCompletionPort =
        ProblemOrganizationTrustedAnswerRuleCompletionPort { _, _ -> },
    private val executionIsCurrent: () -> Boolean = { true },
    private val organizationCommitFence: StudentProblemOrganizationCommitFence =
        currentExecutionCheckCommitFence {
            if (!executionIsCurrent()) {
                throw ProductionProblemOrganizationExecutionRevokedException()
            }
        },
) {
    suspend fun matchesScheduledStateVersion(
        workId: String,
        expectedStateVersion: Long,
    ): Boolean {
        require(workId.isNotBlank()) { "workId must not be blank" }
        require(expectedStateVersion >= 0L) { "expectedStateVersion must not be negative" }
        val work = guardedSuspend {
            sessions.read(ProblemOrganizationWorkSessionReadQuery.ByWorkId(scope, workId))
        }
        return work?.version?.sequence == expectedStateVersion
    }

    /** Restores and consumes only the exact authorization persisted with this work occurrence. */
    suspend fun authorizeStoredGrant(
        workId: String,
        nowEpochMillis: Long,
    ): ProblemOrganizationWorkAuthorizationResult {
        require(workId.isNotBlank()) { "workId must not be blank" }
        require(nowEpochMillis >= 0) { "nowEpochMillis must not be negative" }

        requireCurrentExecution()
        val work = guardedSuspend {
            sessions.read(ProblemOrganizationWorkSessionReadQuery.ByWorkId(scope, workId))
        }
            ?: return ProblemOrganizationWorkAuthorizationResult.NotWaiting
        if (work.status != ProblemOrganizationWorkSessionStatus.WAITING_AUTHORIZATION) {
            return ProblemOrganizationWorkAuthorizationResult.NotWaiting
        }
        val authorization = ProblemOrganizationAuthorizationGrantCodec.decodeOrNull(
            work.authorizationPayload?.content,
        ) ?: return ProblemOrganizationWorkAuthorizationResult.WaitingAuthorization
        val provider = guardedSuspend { modelTasks.capabilities() }
        if (!authorization.matchesCurrent(provider, nowEpochMillis)) {
            return ProblemOrganizationWorkAuthorizationResult.WaitingAuthorization
        }
        val preparation = try {
            guardedSuspend {
                organizations.prepareCommittedWork(
                    workId = work.workId,
                    provider = provider,
                    authorization = authorization,
                    requestVersion = work.version.sequence,
                    occurredAtEpochMillis = nowEpochMillis,
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (revoked: ProductionProblemOrganizationExecutionRevokedException) {
            throw revoked
        } catch (_: IllegalArgumentException) {
            return ProblemOrganizationWorkAuthorizationResult.WaitingAuthorization
        } catch (_: IllegalStateException) {
            return ProblemOrganizationWorkAuthorizationResult.WaitingAuthorization
        }
        val request = preparation.request
        val requestPayload = SessionOpaquePayload(
            schema = ORGANIZATION_REQUEST_SCHEMA,
            content = ModelTaskCodec.encodeRequest(request),
        )
        val result = guardedSuspend {
            sessions.authorize(
                AuthorizeProblemOrganizationWorkSessionCommand(
                    scope = scope,
                    operation = operationIdentity(
                        phase = OPERATION_AUTHORIZE,
                        requestId = request.requestId,
                        work = work,
                        payloadFingerprint = requestPayload.contentSha256,
                        occurredAtEpochMillis = nowEpochMillis,
                    ),
                    workId = work.workId,
                    expectedVersion = work.version,
                    requestPayload = requestPayload,
                    notBeforeEpochMillis = nowEpochMillis,
                    occurredAtEpochMillis = nowEpochMillis,
                ),
            )
        }
        val authorized = result.snapshot
        if (
            result.receipt.disposition in SUCCESSFUL_MUTATION_DISPOSITIONS &&
            authorized?.requestId == request.requestId &&
            authorized?.requestPayload?.contentSha256 == requestPayload.contentSha256
        ) {
            return ProblemOrganizationWorkAuthorizationResult.Authorized(
                requestId = request.requestId,
                notBeforeEpochMillis = nowEpochMillis,
            )
        }
        return ProblemOrganizationWorkAuthorizationResult.LostLease
    }

    suspend fun process(
        workId: String,
        leaseOwner: String,
        nowEpochMillis: Long,
    ): ProblemOrganizationWorkProcessResult {
        require(workId.isNotBlank()) { "workId must not be blank" }
        require(leaseOwner.isNotBlank()) { "leaseOwner must not be blank" }
        require(nowEpochMillis >= 0) { "nowEpochMillis must not be negative" }

        requireCurrentExecution()
        val beforeClaim =
            guardedSuspend {
                sessions.read(ProblemOrganizationWorkSessionReadQuery.ByWorkId(scope, workId))
            }
                ?: return ProblemOrganizationWorkProcessResult.LostLease
        val claim = guardedSuspend {
            sessions.claim(
                ClaimProblemOrganizationWorkSessionCommand(
                    scope = scope,
                    operation = operationIdentity(
                        phase = OPERATION_CLAIM,
                        requestId = beforeClaim.requestId ?: beforeClaim.workId,
                        work = beforeClaim,
                        payloadFingerprint = operationFingerprint(
                            phase = OPERATION_CLAIM,
                            work = beforeClaim,
                            leaseOwner = leaseOwner,
                            occurredAtEpochMillis = nowEpochMillis,
                        ),
                        occurredAtEpochMillis = nowEpochMillis,
                    ),
                    workId = workId,
                    expectedVersion = beforeClaim.version,
                    leaseOwner = leaseOwner,
                    leaseDurationMillis = LEASE_DURATION_MILLIS,
                    occurredAtEpochMillis = nowEpochMillis,
                ),
            )
        }
        val work = claim.snapshot
            ?.takeIf {
                claim.receipt.disposition in SUCCESSFUL_MUTATION_DISPOSITIONS &&
                    it.status == ProblemOrganizationWorkSessionStatus.RUNNING &&
                    it.leaseOwner == leaseOwner
            }
            ?: return ProblemOrganizationWorkProcessResult.LostLease

        val request = work.requestPayload?.content?.let { snapshot ->
            try {
                ModelTaskCodec.decodeRequest(snapshot)
            } catch (_: Exception) {
                return finishPermanentFailure(
                    work = work,
                    leaseOwner = leaseOwner,
                    nowEpochMillis = nowEpochMillis,
                    code = FAILURE_INVALID_REQUEST_SNAPSHOT,
                    message = "组织任务请求快照无法解码",
                )
            }
        } ?: return finishWaitingAuthorization(
            work = work,
            leaseOwner = leaseOwner,
            nowEpochMillis = nowEpochMillis,
            code = FAILURE_AUTHORIZATION_REQUIRED,
            message = "需要重新确认本次题目整理的发送范围",
        )

        if (request.requestId != work.requestId) {
            return finishPermanentFailure(
                work = work,
                leaseOwner = leaseOwner,
                nowEpochMillis = nowEpochMillis,
                code = FAILURE_REQUEST_ID_MISMATCH,
                message = "组织任务请求标识与工作记录不一致",
            )
        }
        if (request.input !is ProblemOrganizationV3Input) {
            return finishPermanentFailure(
                work = work,
                leaseOwner = leaseOwner,
                nowEpochMillis = nowEpochMillis,
                code = FAILURE_INVALID_REQUEST_INPUT,
                message = "组织任务必须使用当前图片归档请求",
            )
        }
        val receipt = guardedSuspend {
            sessions.readSourceReceipt(scope, work.sourceCommitReceiptId)
        }
            ?: return finishPermanentFailure(
                work = work,
                leaseOwner = leaseOwner,
                nowEpochMillis = nowEpochMillis,
                code = FAILURE_COMMIT_RECEIPT_MISSING,
                message = "组织任务缺少提交回执",
            )
        if (receipt.receiptId != work.sourceCommitReceiptId) {
            return finishPermanentFailure(
                work = work,
                leaseOwner = leaseOwner,
                nowEpochMillis = nowEpochMillis,
                code = FAILURE_COMMIT_RECEIPT_MISMATCH,
                message = "组织任务提交回执标识不一致",
            )
        }

        val terminal = try {
            collectTerminalSnapshot(request)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (revoked: ProductionProblemOrganizationExecutionRevokedException) {
            throw revoked
        } catch (denied: ModelEgressAuthorizationException) {
            return finishWaitingAuthorization(
                work = work,
                leaseOwner = leaseOwner,
                nowEpochMillis = nowEpochMillis,
                code = denied.failureCode.name,
                message = "需要重新确认本次题目整理的发送范围",
            )
        } catch (_: IllegalArgumentException) {
            return finishPermanentFailure(
                work = work,
                leaseOwner = leaseOwner,
                nowEpochMillis = nowEpochMillis,
                code = FAILURE_MODEL_PROTOCOL,
                message = "组织任务请求或协议无效",
            )
        } catch (_: Exception) {
            return finishRetry(
                work = work,
                leaseOwner = leaseOwner,
                nowEpochMillis = nowEpochMillis,
                code = FAILURE_LOCAL_EXECUTION,
                message = "组织任务暂时无法执行，将稍后重试",
            )
        }

        return when (terminal.status) {
            ModelTaskStatus.SUCCEEDED -> {
                val completion = try {
                    val authority =
                        ProblemOrganizationWorkCompletionAuthority(
                            workId = work.workId,
                            expectedStateVersion = work.version.sequence,
                            leaseOwner = leaseOwner,
                            requestId = request.requestId,
                        )
                    val guarded = organizations as? GuardedProblemOrganizationWorkCompletionPort
                    if (guarded != null) {
                        guarded.completeSuccessfulOrganizationWork(
                            authority = authority,
                            requireCurrentExecution = ::requireCurrentExecution,
                            commitFence = organizationCommitFence,
                        )
                    } else {
                        guardedSuspend {
                            organizations.completeSuccessfulOrganizationWork(authority)
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (revoked: ProductionProblemOrganizationExecutionRevokedException) {
                    throw revoked
                } catch (_: IllegalArgumentException) {
                    return finishPermanentFailure(
                        work = work,
                        leaseOwner = leaseOwner,
                        nowEpochMillis = nowEpochMillis,
                        code = FAILURE_MODEL_PROTOCOL,
                        message = "组织结果不符合本地协议",
                    )
                } catch (_: ProblemOrganizationImmutableConflictException) {
                    return finishPermanentFailure(
                        work = work,
                        leaseOwner = leaseOwner,
                        nowEpochMillis = nowEpochMillis,
                        code = FAILURE_ORGANIZATION_IMMUTABLE_CONFLICT,
                        message = "题目整理结果与当前记录冲突，本次结果已停止",
                    )
                } catch (_: Exception) {
                    return finishRetry(
                        work = work,
                        leaseOwner = leaseOwner,
                        nowEpochMillis = nowEpochMillis,
                        code = FAILURE_LOCAL_APPLICATION,
                        message = "组织结果暂时无法保存，将稍后重试",
                    )
                }
                when (completion) {
                    ProblemOrganizationWorkCompletionOutcome.COMPLETED -> {
                        try {
                            guardedSuspend {
                                trustedAnswerRules.afterSuccessfulOrganization(
                                    learnerId = scope.learnerId,
                                    problemRevisionId =
                                        (request.input as ProblemOrganizationV3Input)
                                            .problemRevisionId,
                                )
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (revoked: ProductionProblemOrganizationExecutionRevokedException) {
                            throw revoked
                        } catch (_: Exception) {
                            // Organization remains saved. Missing/unavailable rule admission keeps
                            // structured answer verification fail-closed.
                        }
                        ProblemOrganizationWorkProcessResult.Succeeded
                    }

                    ProblemOrganizationWorkCompletionOutcome.LOST_AUTHORITY ->
                        ProblemOrganizationWorkProcessResult.LostLease
                }
            }

            ModelTaskStatus.RETRYABLE_FAILURE -> if (terminal.requiresEgressAuthorization()) {
                finishWaitingAuthorization(
                    work = work,
                    leaseOwner = leaseOwner,
                    nowEpochMillis = nowEpochMillis,
                    code = terminal.failureCodeOr(FAILURE_AUTHORIZATION_REQUIRED),
                    message = "需要重新确认本次题目整理的发送范围",
                )
            } else {
                finishRetry(
                    work = work,
                    leaseOwner = leaseOwner,
                    nowEpochMillis = nowEpochMillis,
                    code = terminal.failureCodeOr(FAILURE_MODEL_RETRY),
                    message = terminal.failure?.message ?: "组织任务暂时未完成，将稍后重试",
                )
            }

            ModelTaskStatus.CANCELLED -> finishPermanentFailure(
                work = work,
                leaseOwner = leaseOwner,
                nowEpochMillis = nowEpochMillis,
                code = FAILURE_MODEL_CANCELLED,
                message = "组织任务已取消",
            )

            ModelTaskStatus.PERMANENT_FAILURE -> if (terminal.requiresEgressAuthorization()) {
                finishWaitingAuthorization(
                    work = work,
                    leaseOwner = leaseOwner,
                    nowEpochMillis = nowEpochMillis,
                    code = terminal.failureCodeOr(FAILURE_AUTHORIZATION_REQUIRED),
                    message = "需要重新确认本次题目整理的发送范围",
                )
            } else {
                finishPermanentFailure(
                    work = work,
                    leaseOwner = leaseOwner,
                    nowEpochMillis = nowEpochMillis,
                    code = terminal.failureCodeOr(FAILURE_MODEL_PERMANENT),
                    message = terminal.failure?.message ?: "组织任务返回了不可用结果",
                )
            }

            ModelTaskStatus.WAITING_FOR_MODEL,
            ModelTaskStatus.QUEUED,
            ModelTaskStatus.RUNNING,
            ModelTaskStatus.STREAMING,
            -> finishPermanentFailure(
                work = work,
                leaseOwner = leaseOwner,
                nowEpochMillis = nowEpochMillis,
                code = FAILURE_MODEL_PROTOCOL,
                message = "组织任务未返回终态",
            )
        }
    }

    suspend fun recoveryNotBeforeEpochMillis(workId: String): Long? {
        require(workId.isNotBlank()) { "workId must not be blank" }
        requireCurrentExecution()
        val work =
            guardedSuspend {
                sessions.read(ProblemOrganizationWorkSessionReadQuery.ByWorkId(scope, workId))
            }
                ?: return null
        return work.leaseExpiresAtEpochMillis
            ?.takeIf { work.status == ProblemOrganizationWorkSessionStatus.RUNNING }
    }

    private suspend fun collectTerminalSnapshot(request: ModelTaskRequest): ModelTaskSnapshot {
        requireCurrentExecution()
        var latest: ModelTaskSnapshot? = null
        modelTasks.execute(request).collect { snapshot ->
            requireCurrentExecution()
            latest = snapshot
            requireCurrentExecution()
        }
        requireCurrentExecution()
        return checkNotNull(latest) { "Organization model task emitted no durable snapshot" }
    }

    private suspend fun finishWaitingAuthorization(
        work: ProblemOrganizationWorkSessionSnapshot,
        leaseOwner: String,
        nowEpochMillis: Long,
        code: String,
        message: String,
    ): ProblemOrganizationWorkProcessResult {
        val occurredAtEpochMillis = transitionTime(nowEpochMillis)
        val command =
            ProblemOrganizationWorkSessionTransition.WaitForAuthorization(
                scope = scope,
                operation = transitionOperationIdentity(
                    phase = OPERATION_WAIT_FOR_AUTHORIZATION,
                    work = work,
                    leaseOwner = leaseOwner,
                    occurredAtEpochMillis = occurredAtEpochMillis,
                    failureCode = code,
                    failureMessage = message,
                ),
                workId = work.workId,
                expectedVersion = work.version,
                leaseOwner = leaseOwner,
                occurredAtEpochMillis = occurredAtEpochMillis,
                failureCode = code,
                failureMessage = message,
            )
        return transition(
            result = guardedSuspend { sessions.transition(command) },
            command = command,
            success = ProblemOrganizationWorkProcessResult.WaitingAuthorization,
        )
    }

    private suspend fun finishRetry(
        work: ProblemOrganizationWorkSessionSnapshot,
        leaseOwner: String,
        nowEpochMillis: Long,
        code: String,
        message: String,
    ): ProblemOrganizationWorkProcessResult {
        val transitionEpochMillis = transitionTime(nowEpochMillis)
        val delayMillis = retryDelayMillis(work.attemptCount)
        val notBeforeEpochMillis = if (transitionEpochMillis > Long.MAX_VALUE - delayMillis) {
            Long.MAX_VALUE
        } else {
            transitionEpochMillis + delayMillis
        }
        val command =
            ProblemOrganizationWorkSessionTransition.Retry(
                scope = scope,
                operation = transitionOperationIdentity(
                    phase = OPERATION_RETRY,
                    work = work,
                    leaseOwner = leaseOwner,
                    occurredAtEpochMillis = transitionEpochMillis,
                    failureCode = code,
                    failureMessage = message,
                    notBeforeEpochMillis = notBeforeEpochMillis,
                ),
                workId = work.workId,
                expectedVersion = work.version,
                leaseOwner = leaseOwner,
                occurredAtEpochMillis = transitionEpochMillis,
                failureCode = code,
                failureMessage = message,
                notBeforeEpochMillis = notBeforeEpochMillis,
            )
        return transition(
            result = guardedSuspend { sessions.transition(command) },
            command = command,
            success = ProblemOrganizationWorkProcessResult.RetryScheduled(notBeforeEpochMillis),
        )
    }

    private suspend fun finishPermanentFailure(
        work: ProblemOrganizationWorkSessionSnapshot,
        leaseOwner: String,
        nowEpochMillis: Long,
        code: String,
        message: String,
    ): ProblemOrganizationWorkProcessResult {
        val occurredAtEpochMillis = transitionTime(nowEpochMillis)
        val command =
            ProblemOrganizationWorkSessionTransition.FailPermanently(
                scope = scope,
                operation = transitionOperationIdentity(
                    phase = OPERATION_FAIL_PERMANENTLY,
                    work = work,
                    leaseOwner = leaseOwner,
                    occurredAtEpochMillis = occurredAtEpochMillis,
                    failureCode = code,
                    failureMessage = message,
                ),
                workId = work.workId,
                expectedVersion = work.version,
                leaseOwner = leaseOwner,
                occurredAtEpochMillis = occurredAtEpochMillis,
                failureCode = code,
                failureMessage = message,
            )
        return transition(
            result = guardedSuspend { sessions.transition(command) },
            command = command,
            success = ProblemOrganizationWorkProcessResult.PermanentFailure,
        )
    }

    private fun transition(
        result: ProblemOrganizationWorkSessionMutationResult,
        command: ProblemOrganizationWorkSessionTransition,
        success: ProblemOrganizationWorkProcessResult,
    ): ProblemOrganizationWorkProcessResult =
        if (
            result.receipt.disposition == SessionMutationDisposition.APPLIED ||
            result.receipt.disposition == SessionMutationDisposition.DUPLICATE &&
            command.isExactlySatisfiedBy(result.snapshot)
        ) {
            success
        } else {
            ProblemOrganizationWorkProcessResult.LostLease
        }

    private suspend inline fun <Result> guardedSuspend(
        crossinline operation: suspend () -> Result,
    ): Result {
        requireCurrentExecution()
        val result = operation()
        requireCurrentExecution()
        return result
    }

    private fun requireCurrentExecution() {
        if (!executionIsCurrent()) {
            throw ProductionProblemOrganizationExecutionRevokedException()
        }
    }

    private fun operationIdentity(
        phase: String,
        requestId: String,
        work: ProblemOrganizationWorkSessionSnapshot,
        payloadFingerprint: String,
        occurredAtEpochMillis: Long,
    ): SessionOperationIdentity {
        val identityFingerprint =
            CanonicalSha256("problem-organization-session-operation-identity-v1")
                .field("phase", phase)
                .field("workId", work.workId)
                .field("stateVersion", work.version.sequence)
                .field("stateFingerprint", work.version.fingerprint)
                .field("requestId", requestId)
                .field("payloadFingerprint", payloadFingerprint)
                .field("occurredAtEpochMillis", occurredAtEpochMillis)
                .finish()
        return SessionOperationIdentity(
            requestId = requestId,
            idempotencyKey = "organization-$identityFingerprint",
            requestVersion = work.version.sequence,
            payloadFingerprint = payloadFingerprint,
        )
    }

    private fun operationFingerprint(
        phase: String,
        work: ProblemOrganizationWorkSessionSnapshot,
        leaseOwner: String,
        occurredAtEpochMillis: Long,
        failureCode: String? = null,
        failureMessage: String? = null,
        notBeforeEpochMillis: Long? = null,
    ): String =
        CanonicalSha256("problem-organization-session-operation-payload-v1")
            .field("phase", phase)
            .field("workId", work.workId)
            .field("stateVersion", work.version.sequence)
            .field("stateFingerprint", work.version.fingerprint)
            .field("leaseOwner", leaseOwner)
            .field("occurredAtEpochMillis", occurredAtEpochMillis)
            .nullableField("failureCode", failureCode)
            .nullableField("failureMessage", failureMessage)
            .nullableField("notBeforeEpochMillis", notBeforeEpochMillis?.toString())
            .finish()

    private fun transitionOperationIdentity(
        phase: String,
        work: ProblemOrganizationWorkSessionSnapshot,
        leaseOwner: String,
        occurredAtEpochMillis: Long,
        failureCode: String,
        failureMessage: String,
        notBeforeEpochMillis: Long? = null,
    ): SessionOperationIdentity {
        val fingerprint =
            operationFingerprint(
                phase = phase,
                work = work,
                leaseOwner = leaseOwner,
                occurredAtEpochMillis = occurredAtEpochMillis,
                failureCode = failureCode,
                failureMessage = failureMessage,
                notBeforeEpochMillis = notBeforeEpochMillis,
            )
        return operationIdentity(
            phase = phase,
            requestId = work.requestId ?: work.workId,
            work = work,
            payloadFingerprint = fingerprint,
            occurredAtEpochMillis = occurredAtEpochMillis,
        )
    }

    private fun ProblemOrganizationWorkSessionTransition.isExactlySatisfiedBy(
        snapshot: ProblemOrganizationWorkSessionSnapshot?,
    ): Boolean {
        if (snapshot == null || snapshot.scope != scope || snapshot.workId != workId) return false
        return when (this) {
            is ProblemOrganizationWorkSessionTransition.WaitForAuthorization ->
                snapshot.status == ProblemOrganizationWorkSessionStatus.WAITING_AUTHORIZATION &&
                    snapshot.failureCode == failureCode &&
                    snapshot.failureMessage == failureMessage

            is ProblemOrganizationWorkSessionTransition.Retry ->
                snapshot.status == ProblemOrganizationWorkSessionStatus.RETRY &&
                    snapshot.notBeforeEpochMillis == notBeforeEpochMillis &&
                    snapshot.failureCode == failureCode &&
                    snapshot.failureMessage == failureMessage

            is ProblemOrganizationWorkSessionTransition.FailPermanently ->
                snapshot.status == ProblemOrganizationWorkSessionStatus.PERMANENT_FAILURE &&
                    snapshot.failureCode == failureCode &&
                    snapshot.failureMessage == failureMessage

            is ProblemOrganizationWorkSessionTransition.Complete ->
                snapshot.status == ProblemOrganizationWorkSessionStatus.SUCCEEDED &&
                    snapshot.requestId == completedRequestId
        }
    }

    private fun ModelTaskSnapshot.requiresEgressAuthorization(): Boolean =
        failure?.code == ModelFailureCode.EGRESS_AUTHORIZATION_REQUIRED ||
            failure?.code == ModelFailureCode.EGRESS_AUTHORIZATION_INVALID

    private fun ModelTaskSnapshot.failureCodeOr(fallback: String): String = failure?.code?.name ?: fallback

    private fun retryDelayMillis(attemptCount: Int): Long {
        val exponent = (attemptCount - 1).coerceIn(0, MAX_BACKOFF_EXPONENT)
        return RETRY_BASE_DELAY_MILLIS * (1L shl exponent)
    }

    private fun transitionTime(claimedAtEpochMillis: Long): Long =
        maxOf(claimedAtEpochMillis, clock().also { require(it >= 0) { "clock must not be negative" } })

    private companion object {
        const val LEASE_DURATION_MILLIS = 5L * 60L * 1_000L
        const val RETRY_BASE_DELAY_MILLIS = 60L * 1_000L
        const val MAX_BACKOFF_EXPONENT = 6

        const val FAILURE_AUTHORIZATION_REQUIRED = "EGRESS_AUTHORIZATION_REQUIRED"
        const val FAILURE_INVALID_REQUEST_SNAPSHOT = "INVALID_REQUEST_SNAPSHOT"
        const val FAILURE_REQUEST_ID_MISMATCH = "REQUEST_ID_MISMATCH"
        const val FAILURE_INVALID_REQUEST_INPUT = "INVALID_REQUEST_INPUT"
        const val FAILURE_COMMIT_RECEIPT_MISSING = "COMMIT_RECEIPT_MISSING"
        const val FAILURE_COMMIT_RECEIPT_MISMATCH = "COMMIT_RECEIPT_MISMATCH"
        const val FAILURE_LOCAL_EXECUTION = "LOCAL_EXECUTION_FAILURE"
        const val FAILURE_LOCAL_APPLICATION = "LOCAL_APPLICATION_FAILURE"
        const val FAILURE_MODEL_RETRY = "MODEL_RETRY"
        const val FAILURE_MODEL_PERMANENT = "MODEL_PERMANENT_FAILURE"
        const val FAILURE_MODEL_CANCELLED = "MODEL_CANCELLED"
        const val FAILURE_MODEL_PROTOCOL = "MODEL_PROTOCOL_FAILURE"
        const val FAILURE_ORGANIZATION_IMMUTABLE_CONFLICT =
            "ORGANIZATION_IMMUTABLE_CONFLICT"

        const val ORGANIZATION_REQUEST_SCHEMA = "organization-request-v1"
        const val OPERATION_AUTHORIZE = "authorize"
        const val OPERATION_CLAIM = "claim"
        const val OPERATION_WAIT_FOR_AUTHORIZATION = "wait-authorization"
        const val OPERATION_RETRY = "retry"
        const val OPERATION_FAIL_PERMANENTLY = "fail-permanently"

        val SUCCESSFUL_MUTATION_DISPOSITIONS =
            setOf(SessionMutationDisposition.APPLIED, SessionMutationDisposition.DUPLICATE)
    }
}

/**
 * Post-organization hook keyed only by learner and exact revision. It deliberately receives no
 * model task or organization output, so the primary model cannot become an answer credential.
 */
fun interface ProblemOrganizationTrustedAnswerRuleCompletionPort {
    suspend fun afterSuccessfulOrganization(
        learnerId: String,
        problemRevisionId: String,
    )
}

sealed interface ProblemOrganizationWorkProcessResult {
    data object Succeeded : ProblemOrganizationWorkProcessResult
    data object WaitingAuthorization : ProblemOrganizationWorkProcessResult
    data class RetryScheduled(val notBeforeEpochMillis: Long) : ProblemOrganizationWorkProcessResult
    data object PermanentFailure : ProblemOrganizationWorkProcessResult
    data object LostLease : ProblemOrganizationWorkProcessResult
}

sealed interface ProblemOrganizationWorkAuthorizationResult {
    data class Authorized(
        val requestId: String,
        val notBeforeEpochMillis: Long,
    ) : ProblemOrganizationWorkAuthorizationResult

    data object NotWaiting : ProblemOrganizationWorkAuthorizationResult
    data object WaitingAuthorization : ProblemOrganizationWorkAuthorizationResult
    data object LostLease : ProblemOrganizationWorkAuthorizationResult
}
