package com.tingyun.smartmistakebook.core.student.mistake.database

import androidx.room3.ColumnInfo
import androidx.room3.Dao
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.PrimaryKey
import androidx.room3.Query
import com.tingyun.smartmistakebook.core.model.CanonicalSha256

internal enum class StudentProblemIdentityReceiptKind {
    EXACT_ASSET_SELECTION,
    TRUSTED_SOURCE,
    REVIEWED_ALIAS,
}

internal enum class StudentProblemAliasReviewAuthorityKind {
    HUMAN,
    INDEPENDENT_REVIEW,
}

/**
 * Owner-only registration request for a locally authenticated stable source.
 *
 * The student authority derives every candidate field from [capture]. Receipt identity, issuer,
 * validity and the private capability seal are owner-issued and cannot be supplied here.
 */
internal class RegisterTrustedStudentProblemSourceCommand(
    val registrationId: String,
    val capture: SaveStudentCaptureOccurrenceCommand,
    val locatorNamespace: String,
    val locatorVersion: String,
    val itemLocatorCanonicalFingerprint: String,
) {
    init {
        registrationId.requireStoreText("Trusted-source registration id", MAX_ID_CHARS)
        locatorNamespace.requireStoreText("Trusted-source locator namespace", MAX_ID_CHARS)
        locatorVersion.requireStoreText("Trusted-source locator version", MAX_VERSION_CHARS)
        requireReceiptSha256(
            itemLocatorCanonicalFingerprint,
            "Trusted-source item locator fingerprint",
        )
    }
}

/**
 * Owner-only registration request for a reviewed alias.
 *
 * Only an authenticated human or independent-review path may call the owner bridge with this
 * command. Model output, OCR text and semantic similarity are not review authorities.
 */
internal class RegisterReviewedStudentProblemAliasCommand(
    val registrationId: String,
    val capture: SaveStudentCaptureOccurrenceCommand,
    val reviewAuthorityKind: StudentProblemAliasReviewAuthorityKind,
    val existingIdentity: StudentProblemCanonicalIdentityKey,
    val reviewCaseId: String,
    val reviewRevision: Int,
    val reviewDecisionCanonicalFingerprint: String,
) {
    init {
        registrationId.requireStoreText("Reviewed-alias registration id", MAX_ID_CHARS)
        reviewCaseId.requireStoreText("Reviewed-alias review case id", MAX_ID_CHARS)
        require(reviewRevision > 0) {
            "Reviewed-alias review revision must be positive"
        }
        requireReceiptSha256(
            reviewDecisionCanonicalFingerprint,
            "Reviewed-alias decision fingerprint",
        )
    }
}

@Entity(
    tableName = "student_problem_identity_receipt",
    indices = [
        Index(value = ["receipt_canonical_fingerprint"], unique = true),
        Index(value = ["learner_id", "receipt_kind", "expires_at_epoch_millis"]),
        Index(value = ["learner_id", "source_intent_id", "idempotency_key"]),
        Index(
            name = STUDENT_PROBLEM_IDENTITY_REUSE_INDEX_NAME,
            value = [
                "learner_id",
                "subject",
                "receipt_kind",
                "asset_manifest_canonical_fingerprint",
                "selected_region_canonical_fingerprint",
                "candidate_canonical_fingerprint",
                "renewal_generation",
                "issued_at_epoch_millis",
                "receipt_id",
            ],
        ),
    ],
)
internal data class StudentProblemIdentityReceiptEntity(
    @PrimaryKey
    @ColumnInfo(name = "receipt_id")
    val receiptId: String,
    @ColumnInfo(name = "receipt_kind")
    val receiptKind: String,
    @ColumnInfo(name = "receipt_canonical_fingerprint")
    val receiptCanonicalFingerprint: String,
    @ColumnInfo(name = "fingerprint_version")
    val fingerprintVersion: Int,
    @ColumnInfo(name = "issuer_key_id")
    val issuerKeyId: String,
    @ColumnInfo(name = "issuer_version")
    val issuerVersion: String,
    @ColumnInfo(name = "learner_id")
    val learnerId: String,
    val subject: String,
    @ColumnInfo(name = "source_kind")
    val sourceKind: String,
    @ColumnInfo(name = "source_intent_id")
    val sourceIntentId: String,
    @ColumnInfo(name = "idempotency_key")
    val idempotencyKey: String,
    @ColumnInfo(name = "source_canonical_fingerprint")
    val sourceCanonicalFingerprint: String,
    @ColumnInfo(name = "locator_namespace")
    val locatorNamespace: String?,
    @ColumnInfo(name = "locator_version")
    val locatorVersion: String?,
    @ColumnInfo(name = "item_locator_canonical_fingerprint")
    val itemLocatorCanonicalFingerprint: String?,
    @ColumnInfo(name = "problem_id")
    val problemId: String,
    @ColumnInfo(name = "practice_unit_id")
    val practiceUnitId: String,
    @ColumnInfo(name = "revision_id")
    val revisionId: String,
    @ColumnInfo(name = "revision_number")
    val revisionNumber: Int,
    @ColumnInfo(name = "document_canonical_fingerprint")
    val documentCanonicalFingerprint: String,
    @ColumnInfo(name = "target_canonical_fingerprint")
    val targetCanonicalFingerprint: String,
    @ColumnInfo(name = "asset_manifest_canonical_fingerprint")
    val assetManifestCanonicalFingerprint: String,
    @ColumnInfo(name = "selected_region_canonical_fingerprint")
    val selectedRegionCanonicalFingerprint: String,
    @ColumnInfo(name = "candidate_canonical_fingerprint")
    val candidateCanonicalFingerprint: String,
    @ColumnInfo(name = "review_authority_kind")
    val reviewAuthorityKind: String?,
    @ColumnInfo(name = "existing_identity_namespace")
    val existingIdentityNamespace: String?,
    @ColumnInfo(name = "existing_identity_version")
    val existingIdentityVersion: String?,
    @ColumnInfo(name = "existing_identity_stable_key")
    val existingIdentityStableKey: String?,
    @ColumnInfo(name = "existing_identity_canonical_fingerprint")
    val existingIdentityCanonicalFingerprint: String?,
    @ColumnInfo(name = "review_case_id")
    val reviewCaseId: String?,
    @ColumnInfo(name = "review_revision")
    val reviewRevision: Int?,
    @ColumnInfo(name = "review_decision_canonical_fingerprint")
    val reviewDecisionCanonicalFingerprint: String?,
    @ColumnInfo(name = "renewal_generation")
    val renewalGeneration: Int,
    @ColumnInfo(name = "issued_at_epoch_millis")
    val issuedAtEpochMillis: Long,
    @ColumnInfo(name = "expires_at_epoch_millis")
    val expiresAtEpochMillis: Long,
)

@Dao
internal interface StudentProblemIdentityReceiptDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertReceipt(receipt: StudentProblemIdentityReceiptEntity): Long

    @Query(
        """
        SELECT *
        FROM student_problem_identity_receipt
        WHERE receipt_id = :receiptId
        LIMIT 1
        """,
    )
    suspend fun readReceipt(receiptId: String): StudentProblemIdentityReceiptEntity?

    @Query(
        """
        SELECT *
        FROM student_problem_identity_receipt
        WHERE learner_id = :learnerId
          AND subject = :subject
          AND receipt_kind = :receiptKind
          AND asset_manifest_canonical_fingerprint =
              :assetManifestCanonicalFingerprint
          AND selected_region_canonical_fingerprint =
              :selectedRegionCanonicalFingerprint
          AND candidate_canonical_fingerprint = :candidateCanonicalFingerprint
        ORDER BY renewal_generation DESC, issued_at_epoch_millis DESC, receipt_id DESC
        LIMIT 1
        """,
    )
    suspend fun readLatestCandidateReceipt(
        learnerId: String,
        subject: String,
        receiptKind: String,
        assetManifestCanonicalFingerprint: String,
        selectedRegionCanonicalFingerprint: String,
        candidateCanonicalFingerprint: String,
    ): StudentProblemIdentityReceiptEntity?
}

internal interface StudentProblemIdentityReceiptLedger {
    suspend fun record(
        receipt: StudentProblemIdentityReceiptEntity,
    ): StudentProblemIdentityReceiptEntity

    suspend fun read(receiptId: String): StudentProblemIdentityReceiptEntity?

    suspend fun readLatestCandidate(
        learnerId: String,
        subject: String,
        kind: StudentProblemIdentityReceiptKind,
        assetManifestCanonicalFingerprint: String,
        selectedRegionCanonicalFingerprint: String,
        candidateCanonicalFingerprint: String,
    ): StudentProblemIdentityReceiptEntity?
}

internal class RoomStudentProblemIdentityReceiptLedger(
    private val dao: StudentProblemIdentityReceiptDao,
) : StudentProblemIdentityReceiptLedger {
    override suspend fun record(
        receipt: StudentProblemIdentityReceiptEntity,
    ): StudentProblemIdentityReceiptEntity {
        dao.insertReceipt(receipt)
        return checkNotNull(dao.readReceipt(receipt.receiptId)) {
            "Identity receipt was not durable after registration"
        }
    }

    override suspend fun read(receiptId: String): StudentProblemIdentityReceiptEntity? =
        dao.readReceipt(receiptId)

    override suspend fun readLatestCandidate(
        learnerId: String,
        subject: String,
        kind: StudentProblemIdentityReceiptKind,
        assetManifestCanonicalFingerprint: String,
        selectedRegionCanonicalFingerprint: String,
        candidateCanonicalFingerprint: String,
    ): StudentProblemIdentityReceiptEntity? =
        dao.readLatestCandidateReceipt(
            learnerId = learnerId,
            subject = subject,
            receiptKind = kind.name,
            assetManifestCanonicalFingerprint = assetManifestCanonicalFingerprint,
            selectedRegionCanonicalFingerprint = selectedRegionCanonicalFingerprint,
            candidateCanonicalFingerprint = candidateCanonicalFingerprint,
        )
}

internal data class StudentProblemIdentityCandidateBinding(
    val learnerId: String,
    val subject: String,
    val sourceKind: String,
    val sourceIntentId: String,
    val idempotencyKey: String,
    val sourceCanonicalFingerprint: String,
    val problemId: String,
    val practiceUnitId: String,
    val revisionId: String,
    val revisionNumber: Int,
    val documentCanonicalFingerprint: String,
    val targetCanonicalFingerprint: String,
    val assetManifestCanonicalFingerprint: String,
    val selectedRegionCanonicalFingerprint: String,
) {
    val canonicalFingerprint: String =
        CanonicalSha256(STUDENT_PROBLEM_IDENTITY_CANDIDATE_DOMAIN)
            .field("learnerId", learnerId)
            .field("subject", subject)
            .field("sourceKind", sourceKind)
            .field("sourceIntentId", sourceIntentId)
            .field("idempotencyKey", idempotencyKey)
            .field("sourceCanonicalFingerprint", sourceCanonicalFingerprint)
            .field("problemId", problemId)
            .field("practiceUnitId", practiceUnitId)
            .field("revisionId", revisionId)
            .field("revisionNumber", revisionNumber)
            .field("documentCanonicalFingerprint", documentCanonicalFingerprint)
            .field("targetCanonicalFingerprint", targetCanonicalFingerprint)
            .field(
                "assetManifestCanonicalFingerprint",
                assetManifestCanonicalFingerprint,
            )
            .field(
                "selectedRegionCanonicalFingerprint",
                selectedRegionCanonicalFingerprint,
            )
            .finish()
}

internal fun SaveStudentCaptureOccurrenceCommand.toIdentityCandidateBinding():
    StudentProblemIdentityCandidateBinding {
    val target = capture.target
    val revision = target.problem.revision
    return StudentProblemIdentityCandidateBinding(
        learnerId = revision.problem.learnerId,
        subject = revision.problem.subject.name,
        sourceKind = capture.source.kind.name,
        sourceIntentId = capture.source.intentId,
        idempotencyKey = occurrence.idempotencyKey,
        sourceCanonicalFingerprint = capture.source.sourceCanonicalFingerprint,
        problemId = revision.problem.problemId,
        practiceUnitId = revision.problem.practiceUnitId,
        revisionId = revision.revisionId,
        revisionNumber = revision.revisionNumber,
        documentCanonicalFingerprint = revision.documentCanonicalFingerprint,
        targetCanonicalFingerprint = target.targetCanonicalFingerprint,
        assetManifestCanonicalFingerprint =
            studentCaptureAssetManifestCanonicalFingerprint(
                target.problem.originalImages,
            ),
        selectedRegionCanonicalFingerprint =
            studentCaptureSelectedRegionCanonicalFingerprint(
                target.problem.originalImages,
            ),
    )
}

internal fun canonicalStudentProblemIdentityReceiptFingerprint(
    fingerprintVersion: Int,
    receiptId: String,
    kind: StudentProblemIdentityReceiptKind,
    issuerKeyId: String,
    issuerVersion: String,
    candidate: StudentProblemIdentityCandidateBinding,
    locatorNamespace: String?,
    locatorVersion: String?,
    itemLocatorCanonicalFingerprint: String?,
    reviewAuthorityKind: StudentProblemAliasReviewAuthorityKind?,
    existingIdentity: StudentProblemCanonicalIdentityKey?,
    existingIdentityCanonicalFingerprint: String?,
    reviewCaseId: String?,
    reviewRevision: Int?,
    reviewDecisionCanonicalFingerprint: String?,
    renewalGeneration: Int,
    issuedAtEpochMillis: Long,
    expiresAtEpochMillis: Long,
): String {
    val domain =
        when (fingerprintVersion) {
            STUDENT_PROBLEM_IDENTITY_RECEIPT_FINGERPRINT_VERSION_V1 ->
                STUDENT_PROBLEM_IDENTITY_RECEIPT_DOMAIN_V1
            STUDENT_PROBLEM_IDENTITY_RECEIPT_FINGERPRINT_VERSION_V2 ->
                STUDENT_PROBLEM_IDENTITY_RECEIPT_DOMAIN_V2
            else -> error("Unsupported identity receipt fingerprint version")
        }
    val digest =
        CanonicalSha256(domain)
        .field("receiptId", receiptId)
        .field("receiptKind", kind.name)
        .field("issuerKeyId", issuerKeyId)
        .field("issuerVersion", issuerVersion)
        .field("candidateCanonicalFingerprint", candidate.canonicalFingerprint)
        .nullableField("locatorNamespace", locatorNamespace)
        .nullableField("locatorVersion", locatorVersion)
        .nullableField(
            "itemLocatorCanonicalFingerprint",
            itemLocatorCanonicalFingerprint,
        )
        .nullableField("reviewAuthorityKind", reviewAuthorityKind?.name)
        .nullableField("existingIdentityNamespace", existingIdentity?.namespace)
        .nullableField("existingIdentityVersion", existingIdentity?.version)
        .nullableField("existingIdentityStableKey", existingIdentity?.stableKey)
        .nullableField(
            "existingIdentityCanonicalFingerprint",
            existingIdentityCanonicalFingerprint,
        )
        .nullableField("reviewCaseId", reviewCaseId)
        .nullableLongField("reviewRevision", reviewRevision?.toLong())
        .nullableField(
            "reviewDecisionCanonicalFingerprint",
            reviewDecisionCanonicalFingerprint,
        )
    if (fingerprintVersion >= STUDENT_PROBLEM_IDENTITY_RECEIPT_FINGERPRINT_VERSION_V2) {
        digest.field("renewalGeneration", renewalGeneration)
    } else {
        require(renewalGeneration == 1) {
            "Legacy identity receipt fingerprints are valid only for generation one"
        }
    }
    return digest
        .field("issuedAtEpochMillis", issuedAtEpochMillis)
        .field("expiresAtEpochMillis", expiresAtEpochMillis)
        .finish()
}

private fun requireReceiptSha256(
    value: String,
    label: String,
) {
    require(RECEIPT_SHA_256.matches(value)) {
        "$label must be lowercase SHA-256"
    }
}

private const val STUDENT_PROBLEM_IDENTITY_CANDIDATE_DOMAIN =
    "student-problem-identity-candidate-v1"
internal const val STUDENT_PROBLEM_IDENTITY_RECEIPT_FINGERPRINT_VERSION_V1 = 1
internal const val STUDENT_PROBLEM_IDENTITY_RECEIPT_FINGERPRINT_VERSION_V2 = 2
internal const val STUDENT_PROBLEM_IDENTITY_RECEIPT_FINGERPRINT_VERSION_CURRENT =
    STUDENT_PROBLEM_IDENTITY_RECEIPT_FINGERPRINT_VERSION_V2
private const val STUDENT_PROBLEM_IDENTITY_RECEIPT_DOMAIN_V1 =
    "student-problem-identity-receipt-v1"
private const val STUDENT_PROBLEM_IDENTITY_RECEIPT_DOMAIN_V2 =
    "student-problem-identity-receipt-v2"
private val RECEIPT_SHA_256 = Regex("[a-f0-9]{64}")
