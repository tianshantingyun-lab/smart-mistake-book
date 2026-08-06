package com.tingyun.smartmistakebook.core.mastery.database

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class RoomOpenResponseWeakCandidateOwner(
    private val database: LearnerMasteryRoomDatabase,
    override val learnerId: String,
    private val nowEpochMillis: () -> Long,
    private val ownerSeal: Any,
) : LearnerMasteryOpenResponseWeakCandidateOwner {
    private val mutationMutex = Mutex()
    private val closed = AtomicBoolean(false)

    init {
        CoreDataLearnerMasteryOwnerBridge.requireOpenResponseOwnerSeal(ownerSeal)
        requireMasteryIdentity(learnerId, "Open-response owner learner id")
    }

    override suspend fun findCommitted(
        query: LearnerMasteryOpenResponseWeakCandidateReceiptQuery,
    ): LearnerMasteryOpenResponseWeakCandidateCommitReceipt? =
        mutationMutex.withLock {
            check(!closed.get()) {
                "Open-response weak-candidate owner is closed"
            }
            if (query.learnerId != learnerId) return@withLock null
            database.masteryDao()
                .findOpenResponseWeakCandidateReceiptByIdempotency(
                    query.candidateIdempotencyKey,
                )
                ?.takeIf { receipt ->
                    receipt.learnerId == learnerId &&
                        receipt.sourceFactId == query.sourceFactId &&
                        receipt.reviewCaseId == query.reviewCaseId &&
                        receipt.scopeFingerprint == query.scopeFingerprint &&
                        receipt.candidateIdempotencyKey == query.candidateIdempotencyKey
                }
                ?.let { receipt ->
                    LearnerMasteryOpenResponseWeakCandidateCommitReceipt(
                        query = query,
                        receiptFingerprint = receipt.receiptFingerprint,
                    )
                }
        }

    override suspend fun submit(
        command: LearnerMasteryOpenResponseWeakCandidateCommand,
        authorization: LearnerMasteryOpenResponseWeakCandidateAuthorization,
    ): LearnerMasteryOpenResponseWeakCandidateResult =
        try {
            mutationMutex.withLock {
                check(!closed.get()) {
                    "Open-response weak-candidate owner is closed"
                }
                if (
                    command.learnerId != learnerId ||
                    authorization.learnerId != learnerId ||
                    authorization.scopeFingerprint != command.scopeFingerprint ||
                    !authorization.belongsTo(this)
                ) {
                    return@withLock LearnerMasteryOpenResponseWeakCandidateResult(
                        disposition =
                            LearnerMasteryOpenResponseWeakCandidateDisposition.REJECTED,
                        receiptFingerprint = null,
                    )
                }
                val trustedNow = nowEpochMillis()
                require(trustedNow >= 0L) {
                    "Local mastery clock must not be negative"
                }
                database.masteryDao().recordOpenResponseWeakCandidate(
                    owner = this,
                    command = command,
                    authorization = authorization,
                    receivedAtEpochMillis = trustedNow,
                )
            }
        } finally {
            authorization.revoke()
        }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            CoreDataLearnerMasteryOwnerBridge.closeOpenResponseOwner(this, ownerSeal)
            database.close()
        }
    }

    fun hasOpenResponseOwnerSeal(candidate: Any): Boolean = candidate === ownerSeal

    fun isClosedForIssuer(candidate: Any): Boolean {
        CoreDataLearnerMasteryOwnerBridge.requireOpenResponseOwnerSeal(candidate)
        return closed.get()
    }

    fun revokeFromRuntime(candidate: Any) {
        CoreDataLearnerMasteryOwnerBridge.requireOpenResponseOwnerSeal(candidate)
        if (closed.compareAndSet(false, true)) {
            database.close()
        }
    }
}
