package com.tingyun.smartmistakebook.core.data.mistake

import com.tingyun.smartmistakebook.core.database.AuthorizeProblemOrganizationWorkCommand
import com.tingyun.smartmistakebook.core.database.ImmutablePayloadConflictException
import com.tingyun.smartmistakebook.core.database.ProblemOrganizationWorkRecord
import com.tingyun.smartmistakebook.core.database.ProblemOrganizationWorkTransitionCommand
import com.tingyun.smartmistakebook.core.database.StudyDatabasePort
import com.tingyun.smartmistakebook.core.database.StudyDbValue
import com.tingyun.smartmistakebook.core.domain.MistakeOrganizationRepository
import com.tingyun.smartmistakebook.core.domain.ModelTaskRepository
import com.tingyun.smartmistakebook.core.model.ModelFailureCode
import com.tingyun.smartmistakebook.core.model.ModelEgressAuthorizationException
import com.tingyun.smartmistakebook.core.model.ModelTaskCodec
import com.tingyun.smartmistakebook.core.model.ModelTaskRequest
import com.tingyun.smartmistakebook.core.model.ModelTaskSnapshot
import com.tingyun.smartmistakebook.core.model.ModelTaskStatus
import com.tingyun.smartmistakebook.core.model.ProblemOrganizationV3Input
import com.tingyun.smartmistakebook.core.model.ProblemOrganizationAuthorizationGrantCodec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect

/** Processes one durable organization work item after claiming its exact [workId]. */
class ProblemOrganizationWorkProcessor(
    private val database: StudyDatabasePort,
    private val modelTasks: ModelTaskRepository,
    private val organizations: MistakeOrganizationRepository,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Restores and consumes only the exact authorization persisted with this work occurrence. */
    suspend fun authorizeStoredGrant(
        workId: String,
        nowEpochMillis: Long,
    ): ProblemOrganizationWorkAuthorizationResult {
        require(workId.isNotBlank()) { "workId must not be blank" }
        require(nowEpochMillis >= 0) { "nowEpochMillis must not be negative" }

        val work = database.readProblemOrganizationWork(workId)
            ?: return ProblemOrganizationWorkAuthorizationResult.NotWaiting
        if (work.status != StudyDbValue.ProblemOrganizationWorkStatus.WAITING_AUTHORIZATION) {
            return ProblemOrganizationWorkAuthorizationResult.NotWaiting
        }
        val authorization = ProblemOrganizationAuthorizationGrantCodec.decodeOrNull(
            work.authorizationGrantSnapshot,
        ) ?: return ProblemOrganizationWorkAuthorizationResult.WaitingAuthorization
        val provider = modelTasks.capabilities()
        if (!authorization.matchesCurrent(provider, nowEpochMillis)) {
            return ProblemOrganizationWorkAuthorizationResult.WaitingAuthorization
        }
        val preparation = try {
            organizations.prepareCommittedWork(
                workId = work.workId,
                provider = provider,
                authorization = authorization,
                requestVersion = work.stateVersion,
                occurredAtEpochMillis = nowEpochMillis,
            )
        } catch (_: IllegalArgumentException) {
            return ProblemOrganizationWorkAuthorizationResult.WaitingAuthorization
        } catch (_: IllegalStateException) {
            return ProblemOrganizationWorkAuthorizationResult.WaitingAuthorization
        }
        val request = preparation.request
        val snapshot = ModelTaskCodec.encodeRequest(request)
        if (
            database.authorizeProblemOrganizationWork(
                AuthorizeProblemOrganizationWorkCommand(
                    workId = work.workId,
                    expectedStateVersion = work.stateVersion,
                    requestId = request.requestId,
                    requestSnapshot = snapshot,
                    notBeforeEpochMillis = nowEpochMillis,
                    authorizedAtEpochMillis = nowEpochMillis,
                ),
            )
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

        val work = database.claimProblemOrganizationWork(
            workId = workId,
            leaseOwner = leaseOwner,
            nowEpochMillis = nowEpochMillis,
            leaseDurationMillis = LEASE_DURATION_MILLIS,
        ) ?: return ProblemOrganizationWorkProcessResult.LostLease

        val request = work.requestSnapshot?.let { snapshot ->
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
        val input = request.input as? ProblemOrganizationV3Input
            ?: return finishPermanentFailure(
                work = work,
                leaseOwner = leaseOwner,
                nowEpochMillis = nowEpochMillis,
                code = FAILURE_INVALID_REQUEST_INPUT,
                message = "组织任务必须使用当前图片归档请求",
            )
        val receipt = database.readProblemOrganizationWorkCommitReceipt(work.commitReceiptCommandId)
            ?: return finishPermanentFailure(
                work = work,
                leaseOwner = leaseOwner,
                nowEpochMillis = nowEpochMillis,
                code = FAILURE_COMMIT_RECEIPT_MISSING,
                message = "组织任务缺少提交回执",
            )
        if (
            input.problemId != receipt.problemId ||
            input.problemRevisionId != receipt.problemRevisionId ||
            input.practiceUnitId != receipt.practiceUnitId
        ) {
            return finishPermanentFailure(
                work = work,
                leaseOwner = leaseOwner,
                nowEpochMillis = nowEpochMillis,
                code = FAILURE_COMMIT_RECEIPT_MISMATCH,
                message = "组织任务请求与精确提交回执不一致",
            )
        }

        val terminal = try {
            collectTerminalSnapshot(request)
        } catch (cancelled: CancellationException) {
            throw cancelled
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
                try {
                    organizations.applySuccessfulOrganization(request.requestId)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: IllegalArgumentException) {
                    return finishPermanentFailure(
                        work = work,
                        leaseOwner = leaseOwner,
                        nowEpochMillis = nowEpochMillis,
                        code = FAILURE_MODEL_PROTOCOL,
                        message = "组织结果不符合本地协议",
                    )
                } catch (_: ImmutablePayloadConflictException) {
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
                if (
                    database.completeProblemOrganizationWork(
                        ProblemOrganizationWorkTransitionCommand(
                            workId = work.workId,
                            expectedStateVersion = work.stateVersion,
                            leaseOwner = leaseOwner,
                            occurredAtEpochMillis = transitionTime(nowEpochMillis),
                            requestId = request.requestId,
                        ),
                    )
                ) {
                    ProblemOrganizationWorkProcessResult.Succeeded
                } else {
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
        val work = database.readProblemOrganizationWork(workId) ?: return null
        return work.leaseExpiresAtEpochMillis
            ?.takeIf { work.status == StudyDbValue.ProblemOrganizationWorkStatus.RUNNING }
    }

    private suspend fun collectTerminalSnapshot(request: ModelTaskRequest): ModelTaskSnapshot {
        var latest: ModelTaskSnapshot? = null
        modelTasks.execute(request).collect { snapshot -> latest = snapshot }
        return checkNotNull(latest) { "Organization model task emitted no durable snapshot" }
    }

    private suspend fun finishWaitingAuthorization(
        work: ProblemOrganizationWorkRecord,
        leaseOwner: String,
        nowEpochMillis: Long,
        code: String,
        message: String,
    ): ProblemOrganizationWorkProcessResult = transition(
        applied = database.markProblemOrganizationWorkWaitingAuthorization(
            ProblemOrganizationWorkTransitionCommand(
                workId = work.workId,
                expectedStateVersion = work.stateVersion,
                leaseOwner = leaseOwner,
                occurredAtEpochMillis = transitionTime(nowEpochMillis),
                failureCode = code,
                failureMessage = message,
            ),
        ),
        success = ProblemOrganizationWorkProcessResult.WaitingAuthorization,
    )

    private suspend fun finishRetry(
        work: ProblemOrganizationWorkRecord,
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
        return transition(
            applied = database.retryProblemOrganizationWork(
                ProblemOrganizationWorkTransitionCommand(
                    workId = work.workId,
                    expectedStateVersion = work.stateVersion,
                    leaseOwner = leaseOwner,
                    occurredAtEpochMillis = transitionEpochMillis,
                    failureCode = code,
                    failureMessage = message,
                    notBeforeEpochMillis = notBeforeEpochMillis,
                ),
            ),
            success = ProblemOrganizationWorkProcessResult.RetryScheduled(notBeforeEpochMillis),
        )
    }

    private suspend fun finishPermanentFailure(
        work: ProblemOrganizationWorkRecord,
        leaseOwner: String,
        nowEpochMillis: Long,
        code: String,
        message: String,
    ): ProblemOrganizationWorkProcessResult = transition(
        applied = database.failProblemOrganizationWorkPermanently(
            ProblemOrganizationWorkTransitionCommand(
                workId = work.workId,
                expectedStateVersion = work.stateVersion,
                leaseOwner = leaseOwner,
                occurredAtEpochMillis = transitionTime(nowEpochMillis),
                failureCode = code,
                failureMessage = message,
            ),
        ),
        success = ProblemOrganizationWorkProcessResult.PermanentFailure,
    )

    private fun transition(
        applied: Boolean,
        success: ProblemOrganizationWorkProcessResult,
    ): ProblemOrganizationWorkProcessResult = if (applied) success else ProblemOrganizationWorkProcessResult.LostLease

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
    }
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
