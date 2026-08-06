package com.tingyun.smartmistakebook.core.database

import androidx.room3.withReadTransaction
import androidx.room3.withWriteTransaction
import com.tingyun.smartmistakebook.core.database.entity.TutorCurrentInteractionEventEntity
import com.tingyun.smartmistakebook.core.database.entity.TutorCurrentInteractionHeadEntity
import com.tingyun.smartmistakebook.core.database.entity.TutorCurrentInteractionScopeEntity
import com.tingyun.smartmistakebook.core.database.entity.TutorFreeResponseOutboxEntity
import com.tingyun.smartmistakebook.core.model.CanonicalSha256
import com.tingyun.smartmistakebook.core.model.TutorConversationStatus
import com.tingyun.smartmistakebook.core.model.TutorEvidenceRequestKind
import com.tingyun.smartmistakebook.core.model.TutorEvidenceRequestStatus
import com.tingyun.smartmistakebook.core.model.TutorExplanationMode
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

internal class RoomCurrentTutorInteractionSessionStore(
    private val database: StudyDatabase,
    private val clock: () -> Long,
    private val freeResponseOutboxCipher: TutorFreeResponseOutboxCipher,
    expirySchedulerFactory: TutorFreeResponseOutboxExpirySchedulerFactory =
        CoroutineTutorFreeResponseOutboxExpirySchedulerFactory,
) : CurrentTutorInteractionSessionDatabasePort,
    CurrentTutorSessionHostWorkDatabasePort {
    private val dao
        get() = database.currentTutorInteractionSessionDao()

    private val freeResponseOutboxExpiryScheduler = expirySchedulerFactory.create(
        clock = clock,
        task = object : TutorFreeResponseOutboxExpiryTask {
            override suspend fun nextPendingCutoffEpochMillis(): Long? =
                database.withReadTransaction {
                    dao.readNextPendingFreeResponseOutboxCutoff()
                }

            override suspend fun wipePendingOutboxesThrough(cutoffEpochMillis: Long) {
                database.withWriteTransaction {
                    dao.failClosedPendingFreeResponseOutboxesThrough(cutoffEpochMillis)
                }
            }
        },
    )

    init {
        freeResponseOutboxExpiryScheduler.requestRefresh()
    }

    internal fun close() {
        freeResponseOutboxExpiryScheduler.closeAndJoin()
    }

    override suspend fun persistCurrentTutorSessionPolicy(
        command: PersistCurrentTutorSessionPolicyCommand,
    ): CurrentTutorSessionPolicyWriteResult = database.withWriteTransaction {
        val now = trustedNow(command.occurredAtEpochMillis)
        val existing = dao.readPolicy(command.learnerId, command.sessionId)
        if (existing != null && existing.matches(command)) {
            return@withWriteTransaction policyWriteResult(
                CurrentTutorSessionHostWorkWriteDisposition.DUPLICATE,
                existing,
                now,
            )
        }
        if (existing != null && !command.isStrictlyNewerThan(existing)) {
            return@withWriteTransaction policyWriteResult(
                CurrentTutorSessionHostWorkWriteDisposition.STALE,
                existing,
                now,
            )
        }
        val candidate = command.toEntity(now)
        val applied = if (existing == null) {
            dao.insertPolicy(candidate) != INSERT_CONFLICT
        } else {
            dao.updatePolicy(candidate) == 1
        }
        if (applied && existing != null && existing.invalidatesFreeResponse(candidate)) {
            dao.failClosedFreeResponseOutboxesForConversation(
                learnerId = command.learnerId,
                sessionId = command.sessionId,
                updatedAtEpochMillis = now,
            )
            freeResponseOutboxExpiryScheduler.requestRefresh()
        }
        policyWriteResult(
            if (applied) CurrentTutorSessionHostWorkWriteDisposition.APPLIED
            else CurrentTutorSessionHostWorkWriteDisposition.STALE,
            if (applied) candidate else dao.readPolicy(command.learnerId, command.sessionId),
            now,
        )
    }

    override suspend fun readCurrentTutorSessionPolicy(
        learnerId: String,
        sessionId: String,
    ): CurrentTutorSessionPolicyRecord? {
        requireCurrentIdentifier(learnerId)
        requireCurrentIdentifier(sessionId)
        return database.withReadTransaction { dao.readPolicy(learnerId, sessionId)?.toRecord() }
    }

    override suspend fun stageCurrentTutorSessionHostWork(
        command: StageCurrentTutorSessionHostWorkCommand,
    ): CurrentTutorSessionHostWorkWriteResult = database.withWriteTransaction {
        val now = trustedNow(command.occurredAtEpochMillis)
        val durablePolicy = command.expectedPolicyStateFingerprint?.let {
            dao.readPolicy(command.learnerId, command.sessionId)
        }
        if (
            !command.hasValidContentBinding() ||
            !authorityMatches(command) ||
            (
                command.expectedPolicyStateFingerprint != null &&
                    durablePolicy?.matchesExpected(command) != true
                )
        ) {
            return@withWriteTransaction hostWorkResult(
                CurrentTutorSessionHostWorkWriteDisposition.REJECTED,
                dao.readHostWork(command.learnerId, command.sessionId),
                now,
            )
        }
        val existing = dao.readHostWork(command.learnerId, command.sessionId)
        if (
            existing != null &&
            existing.status != CurrentTutorSessionHostWorkStatus.REVOKED.name &&
            existing.matches(command)
        ) {
            return@withWriteTransaction hostWorkResult(
                CurrentTutorSessionHostWorkWriteDisposition.DUPLICATE,
                existing,
                now,
            )
        }
        if (
            (existing == null && command.expectedStateVersion != null) ||
            (existing != null && !existing.matchesExpected(command)) ||
            (existing != null && !command.isStrictlyNewerThan(existing)) ||
            existing?.stateVersion == Long.MAX_VALUE
        ) {
            return@withWriteTransaction hostWorkResult(
                CurrentTutorSessionHostWorkWriteDisposition.STALE,
                existing,
                now,
            )
        }
        val candidate = command.toEntity(
            createdAtEpochMillis = existing?.createdAtEpochMillis ?: now,
            stateVersion = (existing?.stateVersion ?: -1L) + 1L,
            updatedAtEpochMillis = now,
        )
        val applied = if (existing == null) {
            dao.insertHostWork(candidate) != INSERT_CONFLICT
        } else {
            dao.updateHostWork(candidate) == 1
        }
        if (applied) {
            dao.failClosedFreeResponseOutboxesForConversation(
                learnerId = command.learnerId,
                sessionId = command.sessionId,
                updatedAtEpochMillis = now,
            )
            freeResponseOutboxExpiryScheduler.requestRefresh()
        }
        hostWorkResult(
            if (applied) {
                CurrentTutorSessionHostWorkWriteDisposition.APPLIED
            } else {
                CurrentTutorSessionHostWorkWriteDisposition.STALE
            },
            if (applied) candidate else dao.readHostWork(command.learnerId, command.sessionId),
            now,
        )
    }

    override suspend fun readCurrentTutorSessionHostWork(
        learnerId: String,
        sessionId: String,
    ): CurrentTutorSessionHostWorkRecord? {
        requireCurrentIdentifier(learnerId)
        requireCurrentIdentifier(sessionId)
        return database.withReadTransaction { dao.readHostWork(learnerId, sessionId)?.toRecord() }
    }

    override fun observeCurrentTutorSessionHostWork(
        learnerId: String,
        sessionId: String,
    ): Flow<CurrentTutorSessionHostWorkRecord?> {
        requireCurrentIdentifier(learnerId)
        requireCurrentIdentifier(sessionId)
        return dao.observeHostWork(learnerId, sessionId)
            .map { entity -> entity?.toRecord() }
            .distinctUntilChanged()
    }

    override suspend fun markCurrentTutorSessionHostWorkActive(
        command: MarkCurrentTutorSessionHostWorkActiveCommand,
    ): CurrentTutorSessionHostWorkWriteResult = database.withWriteTransaction {
        val now = trustedNow(command.occurredAtEpochMillis)
        val existing = dao.readHostWork(command.learnerId, command.sessionId)
            ?: return@withWriteTransaction hostWorkResult(
                CurrentTutorSessionHostWorkWriteDisposition.NOT_FOUND,
                null,
                now,
            )
        val durablePolicy = command.expectedPolicyStateFingerprint?.let {
            dao.readPolicy(command.learnerId, command.sessionId)
        }
        val durablePolicyMatches = command.expectedPolicyStateFingerprint == null ||
            durablePolicy?.matchesExpected(command, existing) == true
        if (
            existing.status == CurrentTutorSessionHostWorkStatus.ACTIVE.name &&
            existing.workId == command.expectedWorkId &&
            existing.activeScopeId == command.expectedTargetScopeId &&
            existing.targetActivationFingerprint == command.expectedTargetActivationFingerprint &&
            durablePolicyMatches
        ) {
            return@withWriteTransaction hostWorkResult(
                CurrentTutorSessionHostWorkWriteDisposition.DUPLICATE,
                existing,
                now,
            )
        }
        val targetScope = dao.readScope(command.expectedTargetScopeId)
        if (
            existing.status != CurrentTutorSessionHostWorkStatus.STAGED.name ||
            !existing.matchesExpected(command) ||
            existing.targetActivationFingerprint != command.expectedTargetActivationFingerprint ||
            targetScope == null ||
            targetScope.activationFingerprint != command.expectedTargetActivationFingerprint ||
            targetScope.presentationFingerprint != existing.constrainedTutorContentFingerprint ||
            !durablePolicyMatches ||
            existing.stateVersion == Long.MAX_VALUE
        ) {
            return@withWriteTransaction hostWorkResult(
                CurrentTutorSessionHostWorkWriteDisposition.STALE,
                existing,
                now,
            )
        }
        val next = existing.transitioned(
            status = CurrentTutorSessionHostWorkStatus.ACTIVE,
            activeScopeId = command.expectedTargetScopeId,
            revocationReason = null,
            updatedAtEpochMillis = now,
        )
        val applied = dao.updateHostWork(next) == 1
        if (applied) {
            dao.failClosedFreeResponseOutboxesForConversation(
                learnerId = command.learnerId,
                sessionId = command.sessionId,
                updatedAtEpochMillis = now,
            )
        }
        hostWorkResult(
            if (applied) CurrentTutorSessionHostWorkWriteDisposition.APPLIED
            else CurrentTutorSessionHostWorkWriteDisposition.STALE,
            if (applied) next else dao.readHostWork(command.learnerId, command.sessionId),
            now,
        )
    }

    override suspend fun revokeCurrentTutorSessionHostWork(
        command: RevokeCurrentTutorSessionHostWorkCommand,
    ): CurrentTutorSessionHostWorkWriteResult = database.withWriteTransaction {
        val now = trustedNow(command.occurredAtEpochMillis)
        val existing = dao.readHostWork(command.learnerId, command.sessionId)
            ?: return@withWriteTransaction hostWorkResult(
                CurrentTutorSessionHostWorkWriteDisposition.NOT_FOUND,
                null,
                now,
            )
        if (
            existing.status == CurrentTutorSessionHostWorkStatus.REVOKED.name &&
            existing.workId == command.expectedWorkId &&
            existing.revocationReason == command.reason.name
        ) {
            dao.failClosedFreeResponseOutboxesForConversation(
                learnerId = command.learnerId,
                sessionId = command.sessionId,
                updatedAtEpochMillis = now,
            )
            return@withWriteTransaction hostWorkResult(
                CurrentTutorSessionHostWorkWriteDisposition.DUPLICATE,
                existing,
                now,
            )
        }
        if (existing.status == CurrentTutorSessionHostWorkStatus.REVOKED.name) {
            return@withWriteTransaction hostWorkResult(
                CurrentTutorSessionHostWorkWriteDisposition.REJECTED,
                existing,
                now,
            )
        }
        if (!existing.matchesExpected(command)) {
            return@withWriteTransaction hostWorkResult(
                CurrentTutorSessionHostWorkWriteDisposition.STALE,
                existing,
                now,
            )
        }
        if (
            existing.stateVersion == Long.MAX_VALUE ||
            (command.reason ==
                CurrentTutorSessionHostWorkRevocationReason.LEARNING_WRITES_DISABLED &&
                existing.learningWritePermissionVersion == Long.MAX_VALUE)
        ) {
            return@withWriteTransaction hostWorkResult(
                CurrentTutorSessionHostWorkWriteDisposition.REJECTED,
                existing,
                now,
            )
        }
        val next = existing.transitioned(
            status = CurrentTutorSessionHostWorkStatus.REVOKED,
            activeScopeId = existing.activeScopeId,
            revocationReason = command.reason,
            updatedAtEpochMillis = now,
            learningWritesAllowed = false,
            learningWritePermissionVersion =
                if (
                    command.reason ==
                    CurrentTutorSessionHostWorkRevocationReason.LEARNING_WRITES_DISABLED
                ) {
                    existing.learningWritePermissionVersion + 1L
                } else {
                    existing.learningWritePermissionVersion
                },
        )
        val applied = dao.updateHostWork(next) == 1
        if (applied) {
            dao.failClosedFreeResponseOutboxesForConversation(
                learnerId = command.learnerId,
                sessionId = command.sessionId,
                updatedAtEpochMillis = now,
            )
        }
        hostWorkResult(
            if (applied) CurrentTutorSessionHostWorkWriteDisposition.APPLIED
            else CurrentTutorSessionHostWorkWriteDisposition.STALE,
            if (applied) next else dao.readHostWork(command.learnerId, command.sessionId),
            now,
        )
    }

    override suspend fun claimCurrentTutorFreeResponseAction(
        command: ClaimCurrentTutorFreeResponseActionCommand,
    ): CurrentTutorFreeResponseActionClaimResult {
        val result = try {
            database.withWriteTransaction {
                val now = trustedNow(command.occurredAtEpochMillis)
                val existing = dao.readFreeResponseOutbox(
                    command.learnerId,
                    command.sessionId,
                    command.actionToken,
                )
                if (existing?.hasFreeResponseClockRollback(now) == true) {
                    failClosedOutbox(existing, now)
                    return@withWriteTransaction freeResponseClaimResult(
                        CurrentTutorFreeResponseActionClaimDisposition.REJECTED,
                        null,
                        now,
                    )
                }
                if (existing == null && now >= command.actionExpiresAtEpochMillis) {
                    return@withWriteTransaction freeResponseClaimResult(
                        CurrentTutorFreeResponseActionClaimDisposition.REJECTED,
                        null,
                        now,
                    )
                }
                val work = currentFreeResponseWorkLocked(
                    learnerId = command.learnerId,
                    sessionId = command.sessionId,
                )?.takeIf { current -> current.matches(command) }
                    ?: run {
                        existing?.let { failClosedOutbox(it, now) }
                        return@withWriteTransaction freeResponseClaimResult(
                            CurrentTutorFreeResponseActionClaimDisposition.NOT_CURRENT,
                            null,
                            now,
                        )
                    }
                val answerBinding = freeResponseOutboxCipher.answerBinding(
                    freeResponseAnswerBindingContext(command, work),
                    command.answer,
                )
                val bundle = currentFreeResponseBundleLocked(work)
                    ?: run {
                        existing?.let { failClosedOutbox(it, now) }
                        return@withWriteTransaction freeResponseClaimResult(
                            CurrentTutorFreeResponseActionClaimDisposition.NOT_CURRENT,
                            null,
                            now,
                        )
                    }
                val scope = dao.readScope(bundle.scope.scopeId)
                if (scope == null || !freeResponseAuthorityStillCurrentLocked(work, scope)) {
                    existing?.let { failClosedOutbox(it, now) }
                    return@withWriteTransaction freeResponseClaimResult(
                        CurrentTutorFreeResponseActionClaimDisposition.NOT_CURRENT,
                        null,
                        now,
                    )
                }
                if (existing != null) {
                    if (!existing.matches(command, work, answerBinding)) {
                        return@withWriteTransaction freeResponseClaimResult(
                            CurrentTutorFreeResponseActionClaimDisposition.REJECTED,
                            null,
                            now,
                        )
                    }
                    val replay = appendCurrentTutorInteractionLocked(
                        command.toAppendCommand(
                            bundle = bundle,
                            canonicalOccurredAtEpochMillis =
                                existing.canonicalOccurredAtEpochMillis,
                            answerBinding = answerBinding,
                        ),
                        now,
                    )
                    return@withWriteTransaction freeResponseClaimResult(
                        disposition = if (
                            replay.disposition == CurrentTutorInteractionAppendDisposition.DUPLICATE
                        ) {
                            CurrentTutorFreeResponseActionClaimDisposition.DUPLICATE
                        } else {
                            CurrentTutorFreeResponseActionClaimDisposition.REJECTED
                        },
                        canonicalOccurredAtEpochMillis =
                            existing.canonicalOccurredAtEpochMillis.takeIf {
                                replay.disposition ==
                                    CurrentTutorInteractionAppendDisposition.DUPLICATE
                            },
                        recordedAtEpochMillis = now,
                    )
                }
                val discardAfterEpochMillis = checkedFreeResponseDeadline(
                    now,
                    TUTOR_FREE_RESPONSE_OUTBOX_RETENTION_MILLIS,
                ) ?: return@withWriteTransaction freeResponseClaimResult(
                    CurrentTutorFreeResponseActionClaimDisposition.REJECTED,
                    null,
                    now,
                )
                val aad = freeResponseOutboxAad(command, work, answerBinding)
                val encrypted = freeResponseOutboxCipher.encrypt(aad, command.answer)
                val payloadFingerprint = command.outboxPayloadFingerprint(work, answerBinding)
                val appended = appendCurrentTutorInteractionLocked(
                    command.toAppendCommand(
                        bundle = bundle,
                        canonicalOccurredAtEpochMillis = command.occurredAtEpochMillis,
                        answerBinding = answerBinding,
                    ),
                    now,
                )
                if (appended.disposition != CurrentTutorInteractionAppendDisposition.APPLIED) {
                    throw AtomicFreeResponseClaimException(appended.disposition)
                }
                val outbox = command.toOutboxEntity(
                    work = work,
                    encrypted = encrypted,
                    answerBinding = answerBinding,
                    payloadFingerprint = payloadFingerprint,
                    discardAfterEpochMillis = discardAfterEpochMillis,
                    now = now,
                )
                if (dao.insertFreeResponseOutbox(outbox) == INSERT_CONFLICT) {
                    throw ConcurrentAppendException()
                }
                freeResponseClaimResult(
                    CurrentTutorFreeResponseActionClaimDisposition.CLAIMED,
                    command.occurredAtEpochMillis,
                    now,
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: AtomicFreeResponseClaimException) {
            freeResponseClaimResult(
                disposition = when (failure.disposition) {
                    CurrentTutorInteractionAppendDisposition.NOT_FOUND,
                    CurrentTutorInteractionAppendDisposition.RELOAD_REQUIRED,
                    -> CurrentTutorFreeResponseActionClaimDisposition.NOT_CURRENT
                    else -> CurrentTutorFreeResponseActionClaimDisposition.REJECTED
                },
                canonicalOccurredAtEpochMillis = null,
                recordedAtEpochMillis = trustedNow(command.occurredAtEpochMillis),
            )
        } catch (_: Exception) {
            freeResponseClaimResult(
                CurrentTutorFreeResponseActionClaimDisposition.REJECTED,
                null,
                trustedNow(command.occurredAtEpochMillis),
            )
        }
        if (result.disposition == CurrentTutorFreeResponseActionClaimDisposition.CLAIMED) {
            freeResponseOutboxExpiryScheduler.requestRefresh()
        }
        return result
    }

    override suspend fun readCurrentTutorFreeResponseDispatchState(
        query: CurrentTutorFreeResponseActionClaimQuery,
    ): CurrentTutorFreeResponseDispatchState = database.withWriteTransaction {
        val now = trustedNow(0L)
        val outbox = dao.readFreeResponseOutbox(
            query.learnerId,
            query.sessionId,
            query.actionToken,
        ) ?: return@withWriteTransaction CurrentTutorFreeResponseDispatchState.NOT_CLAIMED
        val work = currentFreeResponseWorkLocked(query.learnerId, query.sessionId)
            ?.takeIf { current -> current.matches(query) }
        if (work == null || !outbox.matches(query, work)) {
            failClosedOutbox(outbox, now)
            return@withWriteTransaction CurrentTutorFreeResponseDispatchState.FAILED_CLOSED
        }
        if (outbox.status == FREE_RESPONSE_OUTBOX_COMPLETED) {
            return@withWriteTransaction CurrentTutorFreeResponseDispatchState.COMPLETED
        }
        if (outbox.status == FREE_RESPONSE_OUTBOX_FAILED_CLOSED) {
            return@withWriteTransaction CurrentTutorFreeResponseDispatchState.FAILED_CLOSED
        }
        if (outbox.mustFailClosedOnObservation(now)) {
            failClosedOutbox(outbox, now)
            return@withWriteTransaction CurrentTutorFreeResponseDispatchState.FAILED_CLOSED
        }
        val bundle = currentFreeResponseBundleLocked(work)
        val scope = bundle?.let { current -> dao.readScope(current.scope.scopeId) }
        if (scope == null || !freeResponseAuthorityStillCurrentLocked(work, scope)) {
            failClosedOutbox(outbox, now)
            return@withWriteTransaction CurrentTutorFreeResponseDispatchState.FAILED_CLOSED
        }
        outbox.dispatchState(now)
    }

    override suspend fun acquireCurrentTutorFreeResponseDispatch(
        command: AcquireCurrentTutorFreeResponseDispatchCommand,
    ): CurrentTutorFreeResponseDispatchAcquireResult = database.withWriteTransaction {
        val now = trustedNow(command.occurredAtEpochMillis)
        val outbox = if (command.actionToken != null) {
            dao.readFreeResponseOutbox(command.learnerId, command.sessionId, command.actionToken)
        } else {
            dao.readRecoverableFreeResponseOutbox(
                command.learnerId,
                command.sessionId,
                now,
                command.leaseGenerationId,
                TUTOR_FREE_RESPONSE_MAX_DISPATCH_ATTEMPTS,
            )
        } ?: return@withWriteTransaction CurrentTutorFreeResponseDispatchAcquireResult.NotAvailable
        val work = currentFreeResponseWorkLocked(command.learnerId, command.sessionId)
        if (work == null || !outbox.matchesCurrentWork(work)) {
            failClosedOutbox(outbox, now)
            return@withWriteTransaction CurrentTutorFreeResponseDispatchAcquireResult.FailedClosed
        }
        if (outbox.status == FREE_RESPONSE_OUTBOX_COMPLETED) {
            return@withWriteTransaction CurrentTutorFreeResponseDispatchAcquireResult.Completed
        }
        if (outbox.status == FREE_RESPONSE_OUTBOX_FAILED_CLOSED) {
            return@withWriteTransaction CurrentTutorFreeResponseDispatchAcquireResult.FailedClosed
        }
        if (outbox.mustFailClosedOnObservation(now)) {
            failClosedOutbox(outbox, now)
            return@withWriteTransaction CurrentTutorFreeResponseDispatchAcquireResult.FailedClosed
        }
        val bundle = currentFreeResponseBundleLocked(work)
        val scope = bundle?.let { current -> dao.readScope(current.scope.scopeId) }
        if (scope == null || !freeResponseAuthorityStillCurrentLocked(work, scope)) {
            failClosedOutbox(outbox, now)
            return@withWriteTransaction CurrentTutorFreeResponseDispatchAcquireResult.FailedClosed
        }
        when (outbox.status) {
            FREE_RESPONSE_OUTBOX_IN_FLIGHT -> if (
                outbox.leaseGenerationId == command.leaseGenerationId &&
                (outbox.leaseExpiresAtEpochMillis == null ||
                    outbox.leaseExpiresAtEpochMillis > now)
            ) {
                return@withWriteTransaction CurrentTutorFreeResponseDispatchAcquireResult.Busy
            }
            FREE_RESPONSE_OUTBOX_NEEDS_DISPATCH -> if (
                now < outbox.nextDispatchAtEpochMillis
            ) {
                return@withWriteTransaction CurrentTutorFreeResponseDispatchAcquireResult.Busy
            }
            else -> {
                failClosedOutbox(outbox, now)
                return@withWriteTransaction CurrentTutorFreeResponseDispatchAcquireResult.FailedClosed
            }
        }
        if (outbox.dispatchAttemptCount >= TUTOR_FREE_RESPONSE_MAX_DISPATCH_ATTEMPTS) {
            failClosedOutbox(outbox, now)
            return@withWriteTransaction CurrentTutorFreeResponseDispatchAcquireResult.FailedClosed
        }
        val encrypted = outbox.encryptedAnswer?.let { ciphertext ->
            val nonce = outbox.nonce ?: return@let null
            TutorFreeResponseEncryptedAnswer(outbox.keyVersion, nonce, ciphertext)
        }
        val answer = encrypted?.let { value ->
            freeResponseOutboxCipher.decrypt(freeResponseOutboxAad(outbox), value)
        }
        if (
            answer == null ||
            freeResponseOutboxCipher.answerBinding(
                freeResponseAnswerBindingContext(outbox),
                answer,
            ) != outbox.answerBinding
        ) {
            failClosedOutbox(outbox, now)
            return@withWriteTransaction CurrentTutorFreeResponseDispatchAcquireResult.FailedClosed
        }
        val leaseToken = CanonicalSha256("tutor-free-response-dispatch-lease-v1")
            .field("actionToken", outbox.actionToken)
            .field("owner", command.leaseOwnerId)
            .field("generation", command.leaseGenerationId)
            .field("nonce", UUID.randomUUID().toString())
            .field("issuedAt", now)
            .finish()
        if (now > Long.MAX_VALUE - command.leaseDurationMillis) {
            failClosedOutbox(outbox, now)
            return@withWriteTransaction CurrentTutorFreeResponseDispatchAcquireResult.FailedClosed
        }
        val leaseExpiresAt = now + command.leaseDurationMillis
        if (
            dao.acquireFreeResponseOutbox(
                learnerId = outbox.learnerId,
                sessionId = outbox.sessionId,
                actionToken = outbox.actionToken,
                expectedPayloadFingerprint = outbox.payloadFingerprint,
                leaseOwnerId = command.leaseOwnerId,
                leaseGenerationId = command.leaseGenerationId,
                leaseToken = leaseToken,
                leaseExpiresAtEpochMillis = leaseExpiresAt,
                updatedAtEpochMillis = now,
                maxDispatchAttempts = TUTOR_FREE_RESPONSE_MAX_DISPATCH_ATTEMPTS,
            ) != 1
        ) return@withWriteTransaction CurrentTutorFreeResponseDispatchAcquireResult.Busy
        CurrentTutorFreeResponseDispatchAcquireResult.Acquired(
            RoomCurrentTutorFreeResponseDispatchLease(
                learnerId = outbox.learnerId,
                sessionId = outbox.sessionId,
                actionToken = outbox.actionToken,
                workId = outbox.workId,
                workStateVersion = outbox.workStateVersion,
                workStateFingerprint = outbox.workStateFingerprint,
                presentationToken = outbox.presentationToken,
                evidenceRequestId = outbox.evidenceRequestId,
                answerBinding = outbox.answerBinding,
                answer = answer,
                canonicalOccurredAtEpochMillis = outbox.canonicalOccurredAtEpochMillis,
                leaseToken = leaseToken,
                leaseExpiresAtEpochMillis = leaseExpiresAt,
            ),
        )
    }.also { result ->
        if (result is CurrentTutorFreeResponseDispatchAcquireResult.Acquired) {
            freeResponseOutboxExpiryScheduler.requestRefresh()
        }
    }

    override suspend fun completeCurrentTutorFreeResponseDispatch(
        command: CompleteCurrentTutorFreeResponseDispatchCommand,
    ): CurrentTutorFreeResponseDispatchMutationResult = database.withWriteTransaction {
        val now = trustedNow(command.occurredAtEpochMillis)
        val outbox = dao.readFreeResponseOutbox(
            command.learnerId,
            command.sessionId,
            command.actionToken,
        ) ?: return@withWriteTransaction CurrentTutorFreeResponseDispatchMutationResult.NOT_CURRENT
        val work = currentFreeResponseWorkLocked(command.learnerId, command.sessionId)
        if (work == null || !outbox.matchesCurrentWork(work)) {
            failClosedOutbox(outbox, now)
            return@withWriteTransaction CurrentTutorFreeResponseDispatchMutationResult.NOT_CURRENT
        }
        if (outbox.status == FREE_RESPONSE_OUTBOX_COMPLETED) {
            return@withWriteTransaction if (
                outbox.candidateIdempotencyKey == command.candidateIdempotencyKey &&
                outbox.candidateReceiptFingerprint == command.candidateReceiptFingerprint
            ) {
                CurrentTutorFreeResponseDispatchMutationResult.DUPLICATE
            } else {
                CurrentTutorFreeResponseDispatchMutationResult.REJECTED
            }
        }
        if (
            outbox.status == FREE_RESPONSE_OUTBOX_FAILED_CLOSED ||
            now >= outbox.discardAfterEpochMillis ||
            outbox.hasFreeResponseClockRollback(now) ||
            outbox.dispatchAttemptCount !in 1..TUTOR_FREE_RESPONSE_MAX_DISPATCH_ATTEMPTS
        ) {
            if (outbox.status != FREE_RESPONSE_OUTBOX_FAILED_CLOSED) failClosedOutbox(outbox, now)
            return@withWriteTransaction CurrentTutorFreeResponseDispatchMutationResult.REJECTED
        }
        val bundle = currentFreeResponseBundleLocked(work)
        val scope = bundle?.let { current -> dao.readScope(current.scope.scopeId) }
        if (scope == null || !freeResponseAuthorityStillCurrentLocked(work, scope)) {
            failClosedOutbox(outbox, now)
            return@withWriteTransaction CurrentTutorFreeResponseDispatchMutationResult.NOT_CURRENT
        }
        if (
            dao.completeFreeResponseOutbox(
                learnerId = command.learnerId,
                sessionId = command.sessionId,
                actionToken = command.actionToken,
                leaseToken = command.leaseToken,
                candidateIdempotencyKey = command.candidateIdempotencyKey,
                candidateReceiptFingerprint = command.candidateReceiptFingerprint,
                completedAtEpochMillis = now,
            ) == 1
        ) CurrentTutorFreeResponseDispatchMutationResult.APPLIED
        else CurrentTutorFreeResponseDispatchMutationResult.REJECTED
    }.also { result ->
        if (
            result == CurrentTutorFreeResponseDispatchMutationResult.APPLIED ||
            result == CurrentTutorFreeResponseDispatchMutationResult.DUPLICATE
        ) {
            freeResponseOutboxExpiryScheduler.requestRefresh()
        }
    }

    override suspend fun releaseCurrentTutorFreeResponseDispatch(
        command: ReleaseCurrentTutorFreeResponseDispatchCommand,
    ): CurrentTutorFreeResponseDispatchMutationResult = database.withWriteTransaction {
        val now = trustedNow(command.occurredAtEpochMillis)
        val outbox = dao.readFreeResponseOutbox(
            command.learnerId,
            command.sessionId,
            command.actionToken,
        ) ?: return@withWriteTransaction CurrentTutorFreeResponseDispatchMutationResult.NOT_CURRENT
        if (
            outbox.status != FREE_RESPONSE_OUTBOX_IN_FLIGHT ||
            outbox.leaseToken != command.leaseToken
        ) return@withWriteTransaction CurrentTutorFreeResponseDispatchMutationResult.NOT_CURRENT
        val nextDispatchAtEpochMillis = checkedFreeResponseDeadline(
            now,
            freeResponseRetryDelayMillis(outbox.dispatchAttemptCount),
        )
        if (
            outbox.dispatchAttemptCount >= TUTOR_FREE_RESPONSE_MAX_DISPATCH_ATTEMPTS ||
            outbox.dispatchAttemptCount < 1 ||
            now >= outbox.discardAfterEpochMillis ||
            outbox.hasFreeResponseClockRollback(now) ||
            nextDispatchAtEpochMillis == null ||
            nextDispatchAtEpochMillis >= outbox.discardAfterEpochMillis
        ) {
            return@withWriteTransaction if (
                dao.failClosedLeasedFreeResponseOutbox(
                    learnerId = command.learnerId,
                    sessionId = command.sessionId,
                    actionToken = command.actionToken,
                    leaseToken = command.leaseToken,
                    updatedAtEpochMillis = now,
                ) == 1
            ) CurrentTutorFreeResponseDispatchMutationResult.APPLIED
            else CurrentTutorFreeResponseDispatchMutationResult.NOT_CURRENT
        }
        if (
            dao.releaseFreeResponseOutbox(
                learnerId = command.learnerId,
                sessionId = command.sessionId,
                actionToken = command.actionToken,
                leaseToken = command.leaseToken,
                nextDispatchAtEpochMillis = nextDispatchAtEpochMillis,
                updatedAtEpochMillis = now,
            ) == 1
        ) CurrentTutorFreeResponseDispatchMutationResult.APPLIED
        else CurrentTutorFreeResponseDispatchMutationResult.NOT_CURRENT
    }.also { result ->
        if (result == CurrentTutorFreeResponseDispatchMutationResult.APPLIED) {
            freeResponseOutboxExpiryScheduler.requestRefresh()
        }
    }

    override suspend fun failCurrentTutorFreeResponseDispatchClosed(
        command: FailCurrentTutorFreeResponseDispatchClosedCommand,
    ): CurrentTutorFreeResponseDispatchMutationResult = database.withWriteTransaction {
        val now = trustedNow(command.occurredAtEpochMillis)
        val existing = dao.readFreeResponseOutbox(
            command.learnerId,
            command.sessionId,
            command.actionToken,
        ) ?: return@withWriteTransaction CurrentTutorFreeResponseDispatchMutationResult.NOT_CURRENT
        if (existing.status == FREE_RESPONSE_OUTBOX_FAILED_CLOSED) {
            return@withWriteTransaction CurrentTutorFreeResponseDispatchMutationResult.DUPLICATE
        }
        if (
            dao.failClosedLeasedFreeResponseOutbox(
                learnerId = command.learnerId,
                sessionId = command.sessionId,
                actionToken = command.actionToken,
                leaseToken = command.leaseToken,
                updatedAtEpochMillis = now,
            ) == 1
        ) CurrentTutorFreeResponseDispatchMutationResult.APPLIED
        else CurrentTutorFreeResponseDispatchMutationResult.REJECTED
    }.also { result ->
        if (
            result == CurrentTutorFreeResponseDispatchMutationResult.APPLIED ||
            result == CurrentTutorFreeResponseDispatchMutationResult.DUPLICATE
        ) {
            freeResponseOutboxExpiryScheduler.requestRefresh()
        }
    }

    override suspend fun activateCurrentTutorInteraction(
        command: ActivateCurrentTutorInteractionCommand,
    ): CurrentTutorInteractionActivationResult = database.withWriteTransaction {
        if (!authorityMatches(command)) {
            return@withWriteTransaction CurrentTutorInteractionActivationResult(
                CurrentTutorInteractionActivationDisposition.AUTHORITY_MISMATCH,
                null,
            )
        }
        val existingScope = dao.readScope(command.scopeId)
        val candidate = command.toEntity(
            createdAtEpochMillis = existingScope?.createdAtEpochMillis ?: command.occurredAtEpochMillis,
        )
        if (existingScope != null && !existingScope.matchesActivationCandidate(candidate)) {
            return@withWriteTransaction CurrentTutorInteractionActivationResult(
                CurrentTutorInteractionActivationDisposition.AUTHORITY_MISMATCH,
                null,
            )
        }

        val currentHead = dao.readHead(command.learnerId, command.conversationId)
        if (currentHead == null) {
            if (existingScope == null && dao.insertScope(candidate) == INSERT_CONFLICT) {
                return@withWriteTransaction CurrentTutorInteractionActivationResult(
                    CurrentTutorInteractionActivationDisposition.AUTHORITY_MISMATCH,
                    null,
                )
            }
            val head = command.initialHead(trustedNow(command.occurredAtEpochMillis))
            if (dao.insertHead(head) == INSERT_CONFLICT) {
                return@withWriteTransaction CurrentTutorInteractionActivationResult(
                    CurrentTutorInteractionActivationDisposition.STALE,
                    null,
                )
            }
            return@withWriteTransaction CurrentTutorInteractionActivationResult(
                CurrentTutorInteractionActivationDisposition.ACTIVATED,
                readBundleLocked(head, candidate),
            )
        }
        val currentScope = dao.readScope(currentHead.currentScopeId)
            ?: return@withWriteTransaction CurrentTutorInteractionActivationResult(
                CurrentTutorInteractionActivationDisposition.AUTHORITY_MISMATCH,
                null,
            )
        if (currentScope.activationFingerprint == command.activationFingerprint) {
            return@withWriteTransaction CurrentTutorInteractionActivationResult(
                CurrentTutorInteractionActivationDisposition.DUPLICATE,
                readBundleLocked(currentHead, currentScope),
            )
        }
        if (!command.isStrictlyNewerThan(currentScope)) {
            return@withWriteTransaction CurrentTutorInteractionActivationResult(
                CurrentTutorInteractionActivationDisposition.STALE,
                readBundleLocked(currentHead, currentScope),
            )
        }
        if (existingScope == null && dao.insertScope(candidate) == INSERT_CONFLICT) {
            return@withWriteTransaction CurrentTutorInteractionActivationResult(
                CurrentTutorInteractionActivationDisposition.AUTHORITY_MISMATCH,
                null,
            )
        }
        if (
            dao.replaceHead(
                learnerId = command.learnerId,
                conversationId = command.conversationId,
                expectedScopeId = currentHead.currentScopeId,
                expectedStateVersion = currentHead.stateVersion,
                expectedStateFingerprint = currentHead.stateFingerprint,
                newScopeId = command.scopeId,
                newStateFingerprint = command.activationFingerprint,
                updatedAtEpochMillis = trustedNow(command.occurredAtEpochMillis),
            ) != 1
        ) {
            return@withWriteTransaction CurrentTutorInteractionActivationResult(
                CurrentTutorInteractionActivationDisposition.STALE,
                null,
            )
        }
        val replaced = checkNotNull(dao.readHead(command.learnerId, command.conversationId))
        CurrentTutorInteractionActivationResult(
            CurrentTutorInteractionActivationDisposition.ACTIVATED,
            readBundleLocked(replaced, candidate),
        )
    }

    override suspend fun readCurrentTutorInteraction(
        learnerId: String,
        conversationId: String,
    ): CurrentTutorInteractionBundle? {
        requireCurrentIdentifier(learnerId)
        requireCurrentIdentifier(conversationId)
        return database.withReadTransaction {
            val head = dao.readHead(learnerId, conversationId) ?: return@withReadTransaction null
            val scope = dao.readScope(head.currentScopeId) ?: return@withReadTransaction null
            if (!authorityStillCurrent(scope)) return@withReadTransaction null
            readBundleLocked(head, scope)
        }
    }

    override fun observeCurrentTutorInteraction(
        learnerId: String,
        conversationId: String,
    ): Flow<CurrentTutorInteractionBundle?> {
        requireCurrentIdentifier(learnerId)
        requireCurrentIdentifier(conversationId)
        return combine(
            dao.observeCurrentScope(learnerId, conversationId),
            dao.observeCurrentEvents(learnerId, conversationId),
        ) { scope, events -> scope?.scopeId to events.size }
            .map { readCurrentTutorInteraction(learnerId, conversationId) }
            .distinctUntilChanged()
    }

    override fun observeTutorInteractionHistory(
        learnerId: String,
        conversationId: String,
    ): Flow<List<CurrentTutorInteractionEventRecord>> {
        requireCurrentIdentifier(learnerId)
        requireCurrentIdentifier(conversationId)
        return dao.observeHistoryEvents(learnerId, conversationId)
            .map { events ->
                database.withReadTransaction { readEventRecordsLocked(events) }
            }
            .distinctUntilChanged()
    }

    override suspend fun readTutorAnswerExposureEvents(
        learnerId: String,
        modelTaskRequestIds: Set<String>,
    ): List<CurrentTutorInteractionEventRecord> {
        requireCurrentIdentifier(learnerId)
        require(modelTaskRequestIds.size <= 128)
        modelTaskRequestIds.forEach(::requireCurrentIdentifier)
        if (modelTaskRequestIds.isEmpty()) return emptyList()
        return database.withReadTransaction {
            readEventRecordsLocked(dao.readAnswerExposureEvents(learnerId, modelTaskRequestIds))
        }
    }

    override suspend fun appendCurrentTutorInteraction(
        command: AppendCurrentTutorInteractionCommand,
    ): CurrentTutorInteractionAppendResult = try {
        database.withWriteTransaction {
            appendCurrentTutorInteractionLocked(
                command = command,
                now = trustedNow(command.occurredAtEpochMillis),
            )
        }
    } catch (_: ConcurrentAppendException) {
        CurrentTutorInteractionAppendResult(
            disposition = CurrentTutorInteractionAppendDisposition.RELOAD_REQUIRED,
            head = null,
            event = null,
            recordedAtEpochMillis = trustedNow(command.occurredAtEpochMillis),
        )
    } catch (_: TutorEvidenceConflictException) {
        rejected(trustedNow(command.occurredAtEpochMillis))
    } catch (_: TutorMemoryScopeConflictException) {
        rejected(trustedNow(command.occurredAtEpochMillis))
    } catch (_: ImmutablePayloadConflictException) {
        rejected(trustedNow(command.occurredAtEpochMillis))
    }

    private suspend fun appendCurrentTutorInteractionLocked(
        command: AppendCurrentTutorInteractionCommand,
        now: Long,
    ): CurrentTutorInteractionAppendResult {
        val replay = dao.readEventByIdempotency(command.learnerId, command.idempotencyKey)
        if (replay != null) {
            val head = dao.readHead(command.learnerId, command.conversationId)
            val replayScope = dao.readScope(replay.scopeId)
            return if (replay.matches(command, replayScope)) {
                CurrentTutorInteractionAppendResult(
                    disposition = CurrentTutorInteractionAppendDisposition.DUPLICATE,
                    head = head?.toRecord(),
                    event = replay.toRecordAtCommit(checkNotNull(replayScope)),
                    recordedAtEpochMillis = now,
                )
            } else {
                rejected(now)
            }
        }
        if (command.eventKind == CurrentTutorInteractionEventKind.OPEN_RESPONSE_CANDIDATE_CLAIM) {
            val priorClaim = dao.readOpenResponseClaimByScopeFingerprint(
                command.learnerId,
                command.payloadFingerprint,
            )
            if (priorClaim != null) {
                val priorScope = dao.readScope(priorClaim.scopeId)
                return if (priorClaim.matches(command, priorScope)) {
                    CurrentTutorInteractionAppendResult(
                        disposition = CurrentTutorInteractionAppendDisposition.DUPLICATE,
                        head = dao.readHead(command.learnerId, command.conversationId)?.toRecord(),
                        event = priorClaim.toRecordAtCommit(checkNotNull(priorScope)),
                        recordedAtEpochMillis = now,
                    )
                } else {
                    rejected(now)
                }
            }
        }
        if (!command.isWellFormed()) return rejected(now)
        val head = dao.readHead(command.learnerId, command.conversationId)
            ?: return CurrentTutorInteractionAppendResult(
                disposition = CurrentTutorInteractionAppendDisposition.NOT_FOUND,
                head = null,
                event = null,
                recordedAtEpochMillis = now,
            )
        val scope = dao.readScope(head.currentScopeId) ?: return rejected(now)
        if (
            head.currentScopeId != command.scopeId ||
            head.stateVersion != command.expectedStateVersion ||
            head.stateFingerprint != command.expectedStateFingerprint
        ) {
            return CurrentTutorInteractionAppendResult(
                disposition = CurrentTutorInteractionAppendDisposition.RELOAD_REQUIRED,
                head = head.toRecord(),
                event = null,
                recordedAtEpochMillis = now,
            )
        }
        val currentInteractionState = dao.readEvents(scope.scopeId).interactionState(scope)
        if (
            !scope.matchesBinding(command, currentInteractionState) ||
            !authorityStillCurrent(scope) ||
            !requestAuthorityMatches(scope, command)
        ) {
            return rejected(now)
        }
        val nextInteractionState = currentInteractionState.after(command) ?: return rejected(now)
        cancelAuthorityRequestIfNeeded(scope, command, now)
        val trustedSelectionWasCorrect = resolveSelectionWasCorrect(scope, command)
        val nextVersion = head.stateVersion + 1L
        val nextFingerprint = currentTutorSha256(
            "tutor-current-interaction-state-v1",
            head.stateFingerprint,
            command.payloadFingerprint,
            command.eventKind.name,
            command.eventId,
            nextVersion.toString(),
        )
        val event = command.toEntity(
            scope,
            nextVersion,
            nextFingerprint,
            now,
            trustedSelectionWasCorrect,
        )
        if (dao.insertEvent(event) == INSERT_CONFLICT) throw ConcurrentAppendException()
        if (
            dao.advanceStudentInteractionState(
                scopeId = scope.scopeId,
                expectedAttemptOrdinal = scope.attemptOrdinal,
                expectedHintCount = scope.hintCount,
                newAttemptOrdinal = nextInteractionState.attemptOrdinal,
                newHintCount = nextInteractionState.hintCount,
            ) != 1
        ) {
            throw ConcurrentAppendException()
        }
        if (
            dao.advanceHead(
                learnerId = command.learnerId,
                conversationId = command.conversationId,
                scopeId = command.scopeId,
                expectedStateVersion = head.stateVersion,
                expectedStateFingerprint = head.stateFingerprint,
                newStateVersion = nextVersion,
                newStateFingerprint = nextFingerprint,
                updatedAtEpochMillis = now,
            ) != 1
        ) {
            throw ConcurrentAppendException()
        }
        return CurrentTutorInteractionAppendResult(
            disposition = CurrentTutorInteractionAppendDisposition.APPLIED,
            head = checkNotNull(dao.readHead(command.learnerId, command.conversationId)).toRecord(),
            event = event.toRecord(scope.withInteractionState(nextInteractionState)),
            recordedAtEpochMillis = now,
        )
    }

    override suspend fun consumeCurrentTutorOpenResponseAuthorization(
        command: ConsumeCurrentTutorOpenResponseAuthorizationCommand,
    ): CurrentTutorOpenResponseAuthorizationConsumeResult {
        val result = appendCurrentTutorInteraction(command.toAppendCommand())
        val disposition = when (result.disposition) {
            CurrentTutorInteractionAppendDisposition.APPLIED ->
                CurrentTutorOpenResponseAuthorizationConsumeDisposition.CONSUMED
            CurrentTutorInteractionAppendDisposition.DUPLICATE ->
                CurrentTutorOpenResponseAuthorizationConsumeDisposition.DUPLICATE
            CurrentTutorInteractionAppendDisposition.RELOAD_REQUIRED,
            CurrentTutorInteractionAppendDisposition.NOT_FOUND,
            -> CurrentTutorOpenResponseAuthorizationConsumeDisposition.NOT_CURRENT
            CurrentTutorInteractionAppendDisposition.REJECTED ->
                CurrentTutorOpenResponseAuthorizationConsumeDisposition.REJECTED
        }
        return CurrentTutorOpenResponseAuthorizationConsumeResult(
            disposition = disposition,
            recordedAtEpochMillis = result.recordedAtEpochMillis,
        )
    }

    private suspend fun authorityMatches(command: ActivateCurrentTutorInteractionCommand): Boolean {
        val conversation = dao.readAuthorityConversation(
            command.learnerId,
            command.authorityConversationId,
            command.authorityConversationGeneration,
        ) ?: return false
        val turn = dao.readAuthorityTurn(command.learnerId, command.authorityTurnReceiptId)
            ?: return false
        return conversation.status == TutorConversationStatus.ACTIVE.name &&
            conversation.stateVersion == command.authorityConversationStateVersion &&
            turn.conversationId == command.authorityConversationId &&
            turn.conversationGeneration == command.authorityConversationGeneration &&
            turn.conversationStateVersion == command.authorityConversationStateVersion &&
            turn.turnOrdinal == command.authorityTurnOrdinal &&
            turn.subject == command.subject.name &&
            turn.problemAnchorId == command.problemAnchorId &&
            turn.requestVersion == command.authorityRequestVersion &&
            turn.explanationMode == command.explanationMode.name &&
            turn.modeVersion == command.modeVersion
    }

    private suspend fun authorityMatches(
        command: StageCurrentTutorSessionHostWorkCommand,
    ): Boolean {
        val conversation = dao.readAuthorityConversation(
            command.learnerId,
            command.authorityConversationId,
            command.authorityConversationGeneration,
        ) ?: return false
        val turn = dao.readAuthorityTurn(command.learnerId, command.authorityTurnReceiptId)
            ?: return false
        val turnMatches =
            conversation.status == TutorConversationStatus.ACTIVE.name &&
                conversation.stateVersion == command.authorityConversationStateVersion &&
                turn.conversationId == command.authorityConversationId &&
                turn.conversationGeneration == command.authorityConversationGeneration &&
                turn.conversationStateVersion == command.authorityConversationStateVersion &&
                turn.turnOrdinal == command.authorityTurnOrdinal &&
                turn.subject == command.subject.name &&
                turn.problemAnchorId == command.problemAnchorId &&
                turn.requestVersion == command.authorityRequestVersion &&
                turn.explanationMode == command.explanationMode.name &&
                turn.modeVersion == command.modeVersion &&
                turn.directiveFingerprint == command.authorityDirectiveFingerprint
        if (!turnMatches) return false
        val evidenceRequestId = command.evidenceRequestId ?: return true
        val evidence = dao.readPendingAuthorityEvidenceRequest(command.learnerId, evidenceRequestId)
            ?: return false
        return evidence.conversationId == command.authorityConversationId &&
            evidence.conversationGeneration == command.authorityConversationGeneration &&
            evidence.conversationStateVersion == command.authorityConversationStateVersion &&
            evidence.turnReceiptId == command.authorityTurnReceiptId &&
            evidence.turnOrdinal == command.authorityTurnOrdinal &&
            evidence.subject == command.subject.name &&
            evidence.problemAnchorId == command.problemAnchorId &&
            evidence.requestVersion == command.authorityRequestVersion &&
            evidence.explanationMode == command.explanationMode.name &&
            evidence.modeVersion == command.modeVersion &&
            evidence.directiveFingerprint == turn.directiveFingerprint &&
            evidence.kind == command.pendingInteractionKind?.name
    }

    private suspend fun authorityStillCurrent(scope: TutorCurrentInteractionScopeEntity): Boolean {
        val conversation = dao.readAuthorityConversation(
            scope.learnerId,
            scope.authorityConversationId,
            scope.authorityConversationGeneration,
        ) ?: return false
        val turn = dao.readAuthorityTurn(scope.learnerId, scope.authorityTurnReceiptId)
            ?: return false
        val hostWork = dao.readHostWork(scope.learnerId, scope.conversationId)
        val hostAllowsScope = hostWork == null || (
            hostWork.status == CurrentTutorSessionHostWorkStatus.ACTIVE.name &&
                hostWork.activeScopeId == scope.scopeId &&
                hostWork.targetActivationFingerprint == scope.activationFingerprint &&
                hostWork.authorityDirectiveFingerprint == turn.directiveFingerprint &&
                hostWork.constrainedTutorContentFingerprint == scope.presentationFingerprint
            )
        return hostAllowsScope &&
            conversation.status == TutorConversationStatus.ACTIVE.name &&
            conversation.stateVersion == scope.authorityConversationStateVersion &&
            turn.conversationId == scope.authorityConversationId &&
            turn.conversationGeneration == scope.authorityConversationGeneration &&
            turn.conversationStateVersion == scope.authorityConversationStateVersion &&
            turn.turnOrdinal == scope.authorityTurnOrdinal &&
            turn.subject == scope.subject &&
            turn.problemAnchorId == scope.problemAnchorId &&
            turn.requestVersion == scope.authorityRequestVersion &&
            turn.explanationMode == scope.explanationMode &&
            turn.modeVersion == scope.modeVersion
    }

    private suspend fun requestAuthorityMatches(
        scope: TutorCurrentInteractionScopeEntity,
        command: AppendCurrentTutorInteractionCommand,
    ): Boolean {
        return when (command.eventKind) {
        CurrentTutorInteractionEventKind.ANSWER_EXPOSURE -> {
            val requestId = command.authorizationRequestId ?: return false
            if (command.modelTaskRequestId != scope.turnReferenceId) return false
            when (TutorExplanationMode.valueOf(scope.explanationMode)) {
                TutorExplanationMode.DIRECT -> requestId == scope.turnReferenceId
                TutorExplanationMode.GUIDED -> {
                    val request = dao.readPendingAuthorityEvidenceRequest(scope.learnerId, requestId)
                        ?: return false
                    request.matchesAuthority(scope)
                }
            }
        }
        CurrentTutorInteractionEventKind.OPEN_RESPONSE_CANDIDATE_CLAIM -> {
            val requestId = command.authorizationRequestId ?: return false
            when (TutorExplanationMode.valueOf(scope.explanationMode)) {
                TutorExplanationMode.DIRECT -> requestId == scope.turnReferenceId
                TutorExplanationMode.GUIDED -> {
                    val request = dao.readPendingAuthorityEvidenceRequest(scope.learnerId, requestId)
                        ?: return false
                    request.matchesAuthority(scope) &&
                        request.kind == TutorEvidenceRequestKind.FREE_RESPONSE.name
                }
            }
        }
        CurrentTutorInteractionEventKind.FREE_RESPONSE_SUBMISSION_CLAIM -> {
            val requestId = command.authorizationRequestId ?: return false
            if (TutorExplanationMode.valueOf(scope.explanationMode) != TutorExplanationMode.GUIDED) {
                return false
            }
            val request = dao.readPendingAuthorityEvidenceRequest(scope.learnerId, requestId)
                ?: return false
            request.matchesAuthority(scope) &&
                request.kind == TutorEvidenceRequestKind.FREE_RESPONSE.name
        }
        CurrentTutorInteractionEventKind.CHOICE,
        CurrentTutorInteractionEventKind.VISUAL_SELECTION,
        CurrentTutorInteractionEventKind.EVIDENCE_CANCELLATION,
        -> {
            val requestId = command.authorizationRequestId ?: return false
            val request = dao.readPendingAuthorityEvidenceRequest(scope.learnerId, requestId)
                ?: return false
            if (!request.matchesAuthority(scope)) return false
            when (command.eventKind) {
                CurrentTutorInteractionEventKind.CHOICE ->
                    request.kind == TutorEvidenceRequestKind.CHOICE.name
                CurrentTutorInteractionEventKind.VISUAL_SELECTION ->
                    request.kind == TutorEvidenceRequestKind.VISUAL_TARGET.name
                CurrentTutorInteractionEventKind.EVIDENCE_CANCELLATION -> true
                else -> false
            }
        }
        CurrentTutorInteractionEventKind.MOVE,
        CurrentTutorInteractionEventKind.HINT_SHOWN,
        CurrentTutorInteractionEventKind.SOLUTION_REVEAL,
        CurrentTutorInteractionEventKind.SESSION_ANCHOR,
        -> command.authorizationRequestId == null
        }
    }

    private suspend fun cancelAuthorityRequestIfNeeded(
        scope: TutorCurrentInteractionScopeEntity,
        command: AppendCurrentTutorInteractionCommand,
        now: Long,
    ) {
        if (command.eventKind != CurrentTutorInteractionEventKind.EVIDENCE_CANCELLATION) return
        val requestId = checkNotNull(command.authorizationRequestId)
        val request = checkNotNull(
            dao.readPendingAuthorityEvidenceRequest(scope.learnerId, requestId),
        )
        database.tutorLearningMemoryDao().finalizeEvidence(
            command = FinalizeTutorEvidenceRequestCommand(
                learnerId = scope.learnerId,
                conversationId = scope.authorityConversationId,
                conversationGeneration = scope.authorityConversationGeneration,
                conversationStateVersion = scope.authorityConversationStateVersion,
                turnReceiptId = scope.authorityTurnReceiptId,
                turnOrdinal = scope.authorityTurnOrdinal,
                subject = com.tingyun.smartmistakebook.core.model.SubjectKind.valueOf(scope.subject),
                problemAnchorId = scope.problemAnchorId,
                evidenceRequestId = requestId,
                expectedEvidenceStateVersion = request.stateVersion,
                kind = com.tingyun.smartmistakebook.core.model.TutorEvidenceRequestKind
                    .valueOf(request.kind),
                requestVersion = scope.authorityRequestVersion,
                explanationMode = com.tingyun.smartmistakebook.core.model.TutorExplanationMode
                    .valueOf(scope.explanationMode),
                modeVersion = scope.modeVersion,
                directiveFingerprint = request.directiveFingerprint,
                terminalStatus = TutorEvidenceRequestStatus.CANCELLED,
                idempotencyKey = command.idempotencyKey,
                payloadFingerprint = command.payloadFingerprint,
                submission = null,
            ),
            nowEpochMillis = now,
        )
        dao.failClosedFreeResponseOutboxesForConversation(
            learnerId = scope.learnerId,
            sessionId = scope.conversationId,
            updatedAtEpochMillis = now,
        )
    }

    private suspend fun resolveSelectionWasCorrect(
        scope: TutorCurrentInteractionScopeEntity,
        command: AppendCurrentTutorInteractionCommand,
    ): Boolean? = when (command.eventKind) {
        CurrentTutorInteractionEventKind.VISUAL_SELECTION ->
            if (command.authorizationPurpose == TRUSTED_SAVED_VISUAL_SELECTION_PURPOSE) {
                command.selectionWasCorrect
            } else {
                database.tutorInteractionDao().verifyCurrentVisualTargetEvidence(
                    PersistTutorVisualTargetEvidenceCommand(
                        sessionId = scope.conversationId,
                        questionDocumentId = scope.questionDocumentId,
                        revisionNumber = scope.questionRevisionNumber,
                        cycleOrdinal = scope.cycleOrdinal,
                        turnOrdinal = scope.turnOrdinal,
                        surfaceKind = checkNotNull(command.surfaceKind),
                        modelTaskRequestId = checkNotNull(command.modelTaskRequestId),
                        responseOrdinal = command.responseOrdinal,
                        sceneSourceKind = checkNotNull(command.sceneSourceKind),
                        sceneTaskRequestId = checkNotNull(command.sceneTaskRequestId),
                        sceneId = checkNotNull(command.sceneId),
                        sceneFingerprint = checkNotNull(command.sceneFingerprint),
                        hitProofId = checkNotNull(command.hitProofId),
                        panelId = checkNotNull(command.panelId),
                        frameFingerprint = checkNotNull(command.frameFingerprint),
                        stepIndex = checkNotNull(command.stepIndex),
                        selectedTargetId = checkNotNull(command.selectedTargetId),
                        submittedAtEpochMillis = command.occurredAtEpochMillis,
                    ),
                )
            }
        else -> command.selectionWasCorrect
    }

    private suspend fun readBundleLocked(
        head: TutorCurrentInteractionHeadEntity,
        scope: TutorCurrentInteractionScopeEntity,
    ): CurrentTutorInteractionBundle {
        val events = dao.readEvents(scope.scopeId)
        return CurrentTutorInteractionBundle(
            scope = scope.toRecord(),
            head = head.toRecord(),
            events = events.toRecordsAtCommit(scope),
        )
    }

    private suspend fun TutorCurrentInteractionEventEntity.toRecordAtCommit(
        scope: TutorCurrentInteractionScopeEntity,
    ): CurrentTutorInteractionEventRecord =
        checkNotNull(
            dao.readEvents(scope.scopeId)
                .toRecordsAtCommit(scope)
                .singleOrNull { record -> record.eventId == eventId },
        ) { "Current Tutor event is missing from its durable scope" }

    private suspend fun readEventRecordsLocked(
        events: List<TutorCurrentInteractionEventEntity>,
    ): List<CurrentTutorInteractionEventRecord> {
        if (events.isEmpty()) return emptyList()
        val scopeIds = events.map(TutorCurrentInteractionEventEntity::scopeId).distinct()
        val scopes = mutableListOf<TutorCurrentInteractionScopeEntity>()
        for (batch in scopeIds.chunked(MAX_SCOPE_READ_BATCH_SIZE)) {
            scopes += dao.readScopes(batch)
        }
        check(scopes.size == scopeIds.size) {
            "Current Tutor history references a missing or duplicate scope"
        }
        val scopesById = scopes.associateBy(TutorCurrentInteractionScopeEntity::scopeId)
        check(scopesById.size == scopes.size && scopesById.keys == scopeIds.toSet()) {
            "Current Tutor history scope association is not one-to-one"
        }
        val recordsByKey = scopesById.values.flatMap { scope ->
            dao.readEvents(scope.scopeId).toRecordsAtCommit(scope)
        }.associateBy { record -> record.scopeId to record.eventId }
        return events.map { event ->
            checkNotNull(recordsByKey[event.scopeId to event.eventId]) {
                "Current Tutor history event is missing from its durable scope"
            }
        }
    }

    private suspend fun currentFreeResponseWorkLocked(
        learnerId: String,
        sessionId: String,
    ): CurrentTutorSessionHostWorkRecord? {
        val policy = dao.readPolicy(learnerId, sessionId) ?: return null
        return dao.readHostWork(learnerId, sessionId)
            ?.toRecord()
            ?.takeIf { current ->
                current.status == CurrentTutorSessionHostWorkStatus.ACTIVE &&
                    current.pendingInteractionKind == TutorEvidenceRequestKind.FREE_RESPONSE &&
                    current.explanationMode == TutorExplanationMode.GUIDED &&
                    current.learningWritesAllowed &&
                    current.evidenceRequestId != null &&
                    policy.explanationMode == current.explanationMode.name &&
                    policy.modeVersion == current.modeVersion &&
                    policy.learningWritesAllowed == current.learningWritesAllowed &&
                    policy.learningWritePermissionVersion ==
                    current.learningWritePermissionVersion &&
                    policy.visualIntent == current.visualIntent.name &&
                    policy.visualIntentVersion == current.visualIntentVersion
            }
    }

    private suspend fun currentFreeResponseBundleLocked(
        work: CurrentTutorSessionHostWorkRecord,
    ): CurrentTutorInteractionBundle? {
        val head = dao.readHead(work.learnerId, work.sessionId) ?: return null
        val scope = dao.readScope(head.currentScopeId) ?: return null
        return readBundleLocked(head, scope).takeIf { current ->
            scope.matchesFreeResponseWork(work) &&
                current.scope.presentationFingerprint == work.constrainedTutorContentFingerprint &&
                current.scope.explanationMode == TutorExplanationMode.GUIDED &&
                current.scope.learningWritePermissionVersion ==
                    work.learningWritePermissionVersion
        }
    }

    private suspend fun freeResponseAuthorityStillCurrentLocked(
        work: CurrentTutorSessionHostWorkRecord,
        scope: TutorCurrentInteractionScopeEntity,
    ): Boolean {
        val evidenceRequestId = work.evidenceRequestId
            ?: return false
        val request = dao.readPendingAuthorityEvidenceRequest(work.learnerId, evidenceRequestId)
            ?: return false
        return authorityStillCurrent(scope) &&
            request.matchesAuthority(scope) &&
            request.evidenceRequestId == evidenceRequestId &&
            request.directiveFingerprint == work.authorityDirectiveFingerprint &&
            request.kind == TutorEvidenceRequestKind.FREE_RESPONSE.name
    }

    private suspend fun failClosedOutbox(
        outbox: TutorFreeResponseOutboxEntity,
        now: Long,
    ) {
        val changed = dao.failClosedFreeResponseOutbox(
            learnerId = outbox.learnerId,
            sessionId = outbox.sessionId,
            actionToken = outbox.actionToken,
            updatedAtEpochMillis = maxOf(now, outbox.updatedAtEpochMillis),
        )
        if (changed == 1) freeResponseOutboxExpiryScheduler.requestRefresh()
    }

    private fun trustedNow(occurredAtEpochMillis: Long): Long = maxOf(clock(), occurredAtEpochMillis)

    private class ConcurrentAppendException : RuntimeException()

    private class AtomicFreeResponseClaimException(
        val disposition: CurrentTutorInteractionAppendDisposition,
    ) : RuntimeException()

    private companion object {
        const val INSERT_CONFLICT = -1L
        const val MAX_SCOPE_READ_BATCH_SIZE = 500
    }
}

