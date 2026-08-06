package com.tingyun.smartmistakebook.core.mastery.database

import com.tingyun.smartmistakebook.core.model.CanonicalSha256
import com.tingyun.smartmistakebook.core.model.LOCAL_LEARNER_ID
import com.tingyun.smartmistakebook.core.model.LearningObservationFactKind
import com.tingyun.smartmistakebook.core.model.LearningObservationSource
import com.tingyun.smartmistakebook.core.model.SubjectKind
import java.io.Closeable
import java.util.Collections

data class LearnerMasteryLegacyMigrationCursor(
    val occurredAtEpochMillis: Long,
    val sourceFactId: String,
) : Comparable<LearnerMasteryLegacyMigrationCursor> {
    init {
        require(occurredAtEpochMillis >= 0L)
        requireMasteryIdentity(sourceFactId, "Legacy source-fact id")
    }

    override fun compareTo(other: LearnerMasteryLegacyMigrationCursor): Int =
        compareValuesBy(
            this,
            other,
            LearnerMasteryLegacyMigrationCursor::occurredAtEpochMillis,
            LearnerMasteryLegacyMigrationCursor::sourceFactId,
        )
}

/**
 * Lossless copy of one physical legacy observation record.
 *
 * This is an audit snapshot, not mastery evidence. In particular it has no invented hint, retry,
 * answer-exposure, correctness, attribution, or projection fields.
 */
data class LearnerMasteryLegacyObservationSnapshot(
    val sourceFactId: String,
    val learnerId: String,
    val source: String,
    val factKind: String,
    val anchorId: String,
    val subject: String,
    val conversationGeneration: Long?,
    val conversationId: String?,
    val turnReceiptId: String?,
    val evidenceRequestId: String?,
    val responseFingerprint: String,
    val responseSummary: String,
    val occurredAtEpochMillis: Long,
    val sourceVersion: String,
    val sourcePayloadCanonicalFingerprint: String,
    val proofPresent: Boolean,
    val sourceProofCanonicalFingerprint: String?,
    val sourceReferenceId: String?,
    val targetKind: String?,
    val targetDatabase: String?,
    val targetId: String?,
    val targetVersion: String?,
    val targetCanonicalFingerprint: String?,
    val attestedAtEpochMillis: Long?,
    val sourceRecordCanonicalFingerprint: String,
) {
    val cursor =
        LearnerMasteryLegacyMigrationCursor(
            occurredAtEpochMillis = occurredAtEpochMillis,
            sourceFactId = sourceFactId,
        )

    init {
        require(learnerId == LOCAL_LEARNER_ID) {
            "Legacy mastery snapshots belong only to the fixed local learner"
        }
        requireMasteryIdentity(sourceFactId, "Legacy source-fact id")
        requireMasteryIdentity(anchorId, "Legacy anchor id")
        require(enumValues<LearningObservationSource>().any { it.name == source }) {
            "Legacy observation source is unsupported"
        }
        require(enumValues<LearningObservationFactKind>().any { it.name == factKind }) {
            "Legacy observation fact kind is unsupported"
        }
        require(
            enumValues<SubjectKind>().any {
                it != SubjectKind.GENERAL && it.name == subject
            },
        ) {
            "Legacy observation requires one high-school subject"
        }
        require(conversationGeneration == null || conversationGeneration > 0L)
        listOf(conversationId, turnReceiptId, evidenceRequestId).forEach { value ->
            value?.let { requireMasteryIdentity(it, "Legacy conversation reference") }
        }
        requireMasteryFingerprint(responseFingerprint, "Legacy response fingerprint")
        require(responseSummary.isNotBlank() && responseSummary == responseSummary.trim()) {
            "Legacy response summary must be retained exactly"
        }
        require(responseSummary.length <= 512) {
            "Legacy response summary exceeds its historical bound"
        }
        require(occurredAtEpochMillis >= 0L)
        requireMasteryVersion(sourceVersion, "Legacy source version")
        requireMasteryFingerprint(
            sourcePayloadCanonicalFingerprint,
            "Legacy source payload fingerprint",
        )
        val proofValues =
            listOf(
                sourceProofCanonicalFingerprint,
                sourceReferenceId,
                targetKind,
                targetDatabase,
                targetId,
                targetVersion,
                targetCanonicalFingerprint,
                attestedAtEpochMillis?.toString(),
            )
        require(
            if (proofPresent) {
                proofValues.all { it != null }
            } else {
                proofValues.all { it == null }
            },
        ) {
            "Legacy mastery proof must be preserved as either complete or absent"
        }
        sourceProofCanonicalFingerprint?.let {
            requireMasteryFingerprint(it, "Legacy source proof fingerprint")
        }
        targetCanonicalFingerprint?.let {
            requireMasteryFingerprint(it, "Legacy target fingerprint")
        }
        attestedAtEpochMillis?.let {
            require(it >= occurredAtEpochMillis) {
                "Legacy proof attestation precedes its source fact"
            }
        }
        requireMasteryFingerprint(
            sourceRecordCanonicalFingerprint,
            "Legacy source-record fingerprint",
        )
        require(sourceRecordCanonicalFingerprint == recomputeSourceRecordFingerprint()) {
            "Legacy source-record fingerprint does not cover the complete snapshot"
        }
    }

    internal fun recomputeSourceRecordFingerprint(): String =
        CanonicalSha256(LEGACY_SOURCE_RECORD_FINGERPRINT_DOMAIN)
            .field("sourceFactId", sourceFactId)
            .field("learnerScopeId", learnerId)
            .field("source", source)
            .field("factKind", factKind)
            .field("anchorId", anchorId)
            .field("subject", subject)
            .nullableField(
                "conversationGeneration",
                conversationGeneration?.toString(),
            )
            .nullableField("conversationId", conversationId)
            .nullableField("turnReceiptId", turnReceiptId)
            .nullableField("evidenceRequestId", evidenceRequestId)
            .field("responseFingerprint", responseFingerprint)
            .field("responseSummary", responseSummary)
            .field("occurredAtEpochMillis", occurredAtEpochMillis)
            .field("sourceVersion", sourceVersion)
            .field(
                "sourcePayloadCanonicalFingerprint",
                sourcePayloadCanonicalFingerprint,
            )
            .nullableField(
                "sourceProofCanonicalFingerprint",
                sourceProofCanonicalFingerprint,
            )
            .nullableField("sourceReferenceId", sourceReferenceId)
            .nullableField("targetKind", targetKind)
            .nullableField("targetDatabase", targetDatabase)
            .nullableField("targetId", targetId)
            .nullableField("targetVersion", targetVersion)
            .nullableField(
                "targetCanonicalFingerprint",
                targetCanonicalFingerprint,
            )
            .nullableField("attestedAtEpochMillis", attestedAtEpochMillis?.toString())
            .finish()
}

class LearnerMasteryLegacySnapshotPage(
    val learnerId: String,
    val sourceGeneration: String,
    val batchSequence: Long,
    val sourcePageCanonicalFingerprint: String,
    val afterExclusive: LearnerMasteryLegacyMigrationCursor?,
    snapshots: List<LearnerMasteryLegacyObservationSnapshot>,
    val finalBatch: Boolean,
) {
    val snapshots: List<LearnerMasteryLegacyObservationSnapshot> =
        Collections.unmodifiableList(snapshots.toList())

    val terminalCursor: LearnerMasteryLegacyMigrationCursor? =
        this.snapshots.lastOrNull()?.cursor ?: afterExclusive

    init {
        require(learnerId == LOCAL_LEARNER_ID) {
            "Legacy mastery migration is defined only for the fixed local learner"
        }
        requireMasteryFingerprint(sourceGeneration, "Legacy source generation")
        require(batchSequence > 0L)
        requireMasteryFingerprint(
            sourcePageCanonicalFingerprint,
            "Legacy source-page fingerprint",
        )
        require(this.snapshots.size <= MAX_SNAPSHOTS)
        require(this.snapshots.all { it.learnerId == learnerId })
        require(
            this.snapshots
                .map(LearnerMasteryLegacyObservationSnapshot::sourceFactId)
                .distinct()
                .size == this.snapshots.size,
        ) {
            "Legacy mastery page contains the same physical source fact more than once"
        }
        require(
            this.snapshots
                .map(LearnerMasteryLegacyObservationSnapshot::cursor)
                .zipWithNext()
                .all { (left, right) -> left < right },
        )
        require(
            this.snapshots.firstOrNull()?.let { first ->
                afterExclusive == null || first.cursor > afterExclusive
            } != false,
        )
        require(this.snapshots.isNotEmpty() || (batchSequence == 1L && finalBatch)) {
            "Only an empty source may write an empty terminal page"
        }
        require(
            sourcePageCanonicalFingerprint ==
                recomputeSourcePageFingerprint(
                    learnerId = learnerId,
                    afterExclusive = afterExclusive,
                    snapshots = this.snapshots,
                ),
        ) {
            "Legacy source-page fingerprint does not cover the supplied snapshots"
        }
    }

    val canonicalFingerprint: String
        get() {
            val digest =
                CanonicalSha256(LEGACY_SNAPSHOT_PAGE_RECEIPT_DOMAIN)
                    .field("learnerId", learnerId)
                    .field("sourceGeneration", sourceGeneration)
                    .field("batchSequence", batchSequence)
                    .field("sourcePageCanonicalFingerprint", sourcePageCanonicalFingerprint)
                    .nullableField(
                        "afterOccurredAtEpochMillis",
                        afterExclusive?.occurredAtEpochMillis?.toString(),
                    )
                    .nullableField("afterSourceFactId", afterExclusive?.sourceFactId)
                    .field("recordCount", snapshots.size)
                    .field("finalBatch", finalBatch)
            snapshots.forEachIndexed { index, snapshot ->
                digest.field(
                    "sourceRecord[$index]",
                    snapshot.sourceRecordCanonicalFingerprint,
                )
            }
            return digest.finish()
        }

    private companion object {
        const val MAX_SNAPSHOTS = 256
    }
}

enum class LearnerMasteryLegacySnapshotPageDisposition {
    IMPORTED,
    DUPLICATE,
    CONFLICT,
    OUT_OF_ORDER,
}

data class LearnerMasteryLegacySnapshotPageResult(
    val learnerId: String,
    val sourceGeneration: String,
    val batchSequence: Long,
    val sourcePageCanonicalFingerprint: String,
    val disposition: LearnerMasteryLegacySnapshotPageDisposition,
    val snapshotCount: Int,
    val finalBatch: Boolean,
    val pageReceiptCanonicalFingerprint: String,
)

interface LearnerMasteryLegacyMigrationPort : Closeable {
    val learnerId: String

    suspend fun applyPage(
        page: LearnerMasteryLegacySnapshotPage,
    ): LearnerMasteryLegacySnapshotPageResult
}

internal class RoomLearnerMasteryLegacyMigrationPort(
    private val database: LearnerMasteryRoomDatabase,
    override val learnerId: String,
) : LearnerMasteryLegacyMigrationPort {
    private val dao = database.cutoverDao()

    init {
        require(learnerId == LOCAL_LEARNER_ID)
    }

    override suspend fun applyPage(
        page: LearnerMasteryLegacySnapshotPage,
    ): LearnerMasteryLegacySnapshotPageResult {
        check(page.learnerId == learnerId) {
            "Legacy mastery page is outside the owner learner scope"
        }
        return dao.appendLegacySnapshotPage(page)
    }

    override fun close() {
        database.close()
    }
}

internal fun recomputeSourcePageFingerprint(
    learnerId: String,
    afterExclusive: LearnerMasteryLegacyMigrationCursor?,
    snapshots: List<LearnerMasteryLegacyObservationSnapshot>,
): String {
    val digest =
        CanonicalSha256(LEGACY_SOURCE_PAGE_FINGERPRINT_DOMAIN)
            .field("manifestVersion", LEGACY_EXACT_MANIFEST_VERSION)
            .field("learnerId", learnerId)
            .nullableField(
                "afterOccurredAtEpochMillis",
                afterExclusive?.occurredAtEpochMillis?.toString(),
            )
            .nullableField("afterSourceFactId", afterExclusive?.sourceFactId)
            .field("recordCount", snapshots.size)
    snapshots.forEachIndexed { index, snapshot ->
        digest.field("record[$index]", snapshot.sourceRecordCanonicalFingerprint)
    }
    return digest.finish()
}

internal const val LEGACY_EXACT_MANIFEST_VERSION = "exact-legacy-authority-manifest-v1"
internal const val LEGACY_SOURCE_RECORD_FINGERPRINT_DOMAIN =
    "exact-legacy-mastery-fact-record-v1"
internal const val LEGACY_SOURCE_PAGE_FINGERPRINT_DOMAIN =
    "exact-legacy-mastery-fact-page-v1"
internal const val LEGACY_SNAPSHOT_PAGE_RECEIPT_DOMAIN =
    "learner-mastery-legacy-snapshot-page-v1"
