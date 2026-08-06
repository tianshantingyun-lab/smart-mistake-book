package com.tingyun.smartmistakebook.core.student.mistake.database

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey
import com.tingyun.smartmistakebook.core.model.CanonicalSha256
import com.tingyun.smartmistakebook.core.model.NormalizedSourceRegion
import com.tingyun.smartmistakebook.core.model.QuestionDocumentMarkdownProjection

const val STUDENT_CAPTURE_OCCURRENCE_TRANSACTION_SCHEMA_VERSION = 1
const val STUDENT_PROBLEM_CANONICAL_IDENTITY_SCHEMA_VERSION = 1

/**
 * Versioned opaque student-owner identity for one question.
 *
 * This is an output/read-model value. Capture commands cannot submit it. Database uniqueness is
 * scoped by learner, subject, namespace, version, and stable key; practice-unit/classification
 * data and OCR/document hashes are deliberately excluded.
 */
class StudentProblemCanonicalIdentityKey internal constructor(
    val namespace: String,
    val version: String,
    val stableKey: String,
) {
    init {
        namespace.requireStoreText("Canonical question identity namespace", MAX_ID_CHARS)
        version.requireStoreText("Canonical question identity version", MAX_VERSION_CHARS)
        stableKey.requireStoreText("Canonical question stable key", MAX_ID_CHARS)
    }

    fun canonicalFingerprint(
        learnerId: String,
        subject: String,
    ): String =
        CanonicalSha256(STUDENT_PROBLEM_CANONICAL_IDENTITY_FINGERPRINT_DOMAIN)
            .field("schemaVersion", STUDENT_PROBLEM_CANONICAL_IDENTITY_SCHEMA_VERSION)
            .field("learnerId", learnerId)
            .field("subject", subject)
            .field("namespace", namespace)
            .field("version", version)
            .field("stableKey", stableKey)
            .finish()

    override fun equals(other: Any?): Boolean =
        other is StudentProblemCanonicalIdentityKey &&
            namespace == other.namespace &&
            version == other.version &&
            stableKey == other.stableKey

    override fun hashCode(): Int =
        31 * (31 * namespace.hashCode() + version.hashCode()) + stableKey.hashCode()

    override fun toString(): String =
        "StudentProblemCanonicalIdentityKey(namespace=$namespace, version=$version, stableKey=***)"
}

/**
 * Capture may submit evidence, never a stable problem identity.
 *
 * Exact-asset reuse is derived from the ordered target images and their ordered selected regions.
 * [Unresolved] deliberately causes a fresh opaque identity to be issued by the transaction.
 */
interface StudentProblemIdentityEvidence {
    data object Unresolved : StudentProblemIdentityEvidence

    data object ExactAssetSelection : StudentProblemIdentityEvidence
}

/**
 * One learner-owned command for the complete capture boundary.
 *
 * A caller cannot split the target save from the observed error occurrence: the shared intent,
 * exact revision, and occurrence time are checked before the single database transaction starts.
 */
data class SaveStudentCaptureOccurrenceCommand(
    val capture: SaveStudentOwnedCaptureCommand,
    val occurrence: AppendStudentProblemErrorOccurrenceCommand,
    val identityEvidence: StudentProblemIdentityEvidence =
        StudentProblemIdentityEvidence.Unresolved,
) {
    init {
        require(capture.source.intentId == occurrence.idempotencyKey) {
            "Capture and error occurrence must share one idempotency identity"
        }
        require(capture.target.problem.revision == occurrence.problemRevision) {
            "Capture and error occurrence must bind the same exact problem revision"
        }
        require(capture.source.occurredAtEpochMillis == occurrence.occurredAtEpochMillis) {
            "Capture commit and error occurrence times must agree"
        }
        val capturedDocument =
            checkNotNull(capture.target.problem.capturedQuestionDocument) {
                "Atomic capture requires an exact captured-question document"
            }
        require(
            capture.target.problem.title == capturedDocument.document.title &&
                capture.target.problem.stemMarkdown ==
                    QuestionDocumentMarkdownProjection.project(capturedDocument.document),
        ) {
            "Atomic capture title and stem must be exact projections of its captured document"
        }
        require(
            capture.target.problem.solutionAnalysis == null &&
                capture.target.problem.errorAttributions.isEmpty(),
        ) {
            "Atomic capture cannot write organization or review results"
        }
        if (
            identityEvidence == StudentProblemIdentityEvidence.ExactAssetSelection ||
            identityEvidence is
            StudentProblemIdentityEvidenceAuthority.ReviewedAliasEvidence
        ) {
            require(capture.target.problem.originalImages.isNotEmpty()) {
                "Exact asset or reviewed-alias identity evidence requires at least one canonical asset"
            }
        }
    }

    val transactionId: String
        get() =
            canonicalStudentCaptureOccurrenceTransactionId(
                learnerId = occurrence.problemRevision.problem.learnerId,
                intentId = capture.source.intentId,
            )

    /**
     * Replay identity for the unresolved request. The final transaction fingerprint is returned
     * only after the student-owner transaction resolves or issues the canonical identity.
     */
    val requestCanonicalFingerprint: String
        get() =
            CanonicalSha256(STUDENT_CAPTURE_OCCURRENCE_REQUEST_FINGERPRINT_DOMAIN)
                .field("schemaVersion", STUDENT_CAPTURE_OCCURRENCE_TRANSACTION_SCHEMA_VERSION)
                .field("transactionId", transactionId)
                .field("sourceKind", capture.source.kind.name)
                .field("sourceIntentId", capture.source.intentId)
                .field(
                    "sourceCanonicalFingerprint",
                    capture.source.sourceCanonicalFingerprint,
                )
                .field("identityEvidence", identityEvidence.requestFingerprint())
                .field(
                    "proposedTargetCanonicalFingerprint",
                    capture.target.targetCanonicalFingerprint,
                )
                .field(
                    "documentCanonicalFingerprint",
                    capture.target.problem.revision.documentCanonicalFingerprint,
                )
                .field(
                    "assetManifestCanonicalFingerprint",
                    studentCaptureAssetManifestCanonicalFingerprint(
                        capture.target.problem.originalImages,
                    ),
                )
                .field(
                    "selectedRegionCanonicalFingerprint",
                    studentCaptureSelectedRegionCanonicalFingerprint(
                        capture.target.problem.originalImages,
                    ),
                )
                .field(
                    "occurrenceCanonicalFingerprint",
                    occurrence.occurrenceCanonicalFingerprint,
                )
                .finish()
}

data class StudentCaptureOccurrenceReceipt(
    val transactionId: String,
    val transactionCanonicalFingerprint: String,
    val assetManifestCanonicalFingerprint: String,
    val selectedRegionCanonicalFingerprint: String,
    val resolvedIdentity: StudentProblemCanonicalIdentityKey,
    val save: StudentOwnedCaptureSaveReceipt,
    val occurrence: StudentProblemErrorOccurrence,
) {
    init {
        val source = save.handoff.source
        require(source.intentId == occurrence.idempotencyKey) {
            "Capture occurrence receipt crosses idempotency identities"
        }
        require(save.handoff.targetRevision == occurrence.ref.problemRevision) {
            "Capture occurrence receipt crosses exact problem revisions"
        }
        require(source.occurredAtEpochMillis == occurrence.occurredAtEpochMillis) {
            "Capture occurrence receipt crosses occurrence times"
        }
        requireSha256(
            assetManifestCanonicalFingerprint,
            "Capture occurrence receipt asset-manifest fingerprint",
        )
        requireSha256(
            selectedRegionCanonicalFingerprint,
            "Capture occurrence receipt selected-region fingerprint",
        )
        require(
            transactionId ==
                canonicalStudentCaptureOccurrenceTransactionId(
                    learnerId = save.handoff.learnerId,
                    intentId = source.intentId,
                ),
        ) {
            "Capture occurrence receipt transaction id is not canonical"
        }
        require(
            transactionCanonicalFingerprint ==
                canonicalStudentCaptureOccurrenceTransactionFingerprint(
                    sourceKind = source.kind.name,
                    sourceIntentId = source.intentId,
                    sourceCanonicalFingerprint = source.sourceCanonicalFingerprint,
                    canonicalIdentityFingerprint =
                        resolvedIdentity.canonicalFingerprint(
                            learnerId = save.handoff.learnerId,
                            subject = save.handoff.targetRevision.problem.subject.name,
                        ),
                    targetLearnerId = save.handoff.learnerId,
                    targetSubject =
                        save.handoff.targetRevision.problem.subject.name,
                    targetProblemId =
                        save.handoff.targetRevision.problem.problemId,
                    targetPracticeUnitId =
                        save.handoff.targetRevision.problem.practiceUnitId,
                    targetRevisionId = save.handoff.targetRevision.revisionId,
                    targetRevisionNumber =
                        save.handoff.targetRevision.revisionNumber,
                    targetDocumentCanonicalFingerprint =
                        save.handoff.targetRevision.documentCanonicalFingerprint,
                    targetCanonicalFingerprint = save.handoff.targetCanonicalFingerprint,
                    occurrenceId = occurrence.ref.occurrenceId,
                    occurrenceCanonicalFingerprint =
                        occurrence.ref.occurrenceCanonicalFingerprint,
                    batchCanonicalFingerprint = occurrence.batchCanonicalFingerprint,
                    importSourceCanonicalFingerprint =
                        occurrence.importSourceCanonicalFingerprint,
                    assetManifestCanonicalFingerprint =
                        assetManifestCanonicalFingerprint,
                    selectedRegionCanonicalFingerprint =
                        selectedRegionCanonicalFingerprint,
                ),
        ) {
            "Capture occurrence receipt fingerprint is not canonical"
        }
    }
}

/**
 * Learner-bound atomic capability. It deliberately exposes no standalone capture or occurrence
 * writer, and therefore cannot leave one half of the capture boundary committed.
 */
interface LearnerBoundStudentCaptureOccurrencePort {
    val learnerId: String

    suspend fun save(
        command: SaveStudentCaptureOccurrenceCommand,
    ): StudentCaptureOccurrenceReceipt
}

@Entity(
    tableName = "student_problem_canonical_identity",
    primaryKeys = [
        "learner_id",
        "subject",
        "identity_namespace",
        "identity_version",
        "stable_key",
    ],
    foreignKeys = [
        ForeignKey(
            entity = StudentProblemDocumentEntity::class,
            parentColumns = ["problem_id"],
            childColumns = ["problem_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = StudentProblemRevisionEntity::class,
            parentColumns = ["revision_id"],
            childColumns = ["revision_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = StudentMistakeSaveReceiptEntity::class,
            parentColumns = ["intent_confirmation_id"],
            childColumns = ["target_save_receipt_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["identity_canonical_fingerprint"], unique = true),
        Index(value = ["problem_id"]),
        Index(value = ["revision_id"]),
        Index(value = ["target_save_receipt_id"]),
    ],
)
internal data class StudentProblemCanonicalIdentityEntity(
    @ColumnInfo(name = "learner_id")
    val learnerId: String,
    val subject: String,
    @ColumnInfo(name = "identity_namespace")
    val identityNamespace: String,
    @ColumnInfo(name = "identity_version")
    val identityVersion: String,
    @ColumnInfo(name = "stable_key")
    val stableKey: String,
    @ColumnInfo(name = "identity_canonical_fingerprint")
    val identityCanonicalFingerprint: String,
    @ColumnInfo(name = "issuance_kind")
    val issuanceKind: String,
    @ColumnInfo(name = "problem_id")
    val problemId: String,
    @ColumnInfo(name = "revision_id")
    val revisionId: String,
    @ColumnInfo(name = "revision_number")
    val revisionNumber: Int,
    @ColumnInfo(name = "document_canonical_fingerprint")
    val documentCanonicalFingerprint: String,
    @ColumnInfo(name = "practice_unit_id")
    val practiceUnitId: String,
    @ColumnInfo(name = "error_book_entry_id")
    val errorBookEntryId: String,
    @ColumnInfo(name = "target_save_receipt_id")
    val targetSaveReceiptId: String,
    @ColumnInfo(name = "target_canonical_fingerprint")
    val targetCanonicalFingerprint: String,
    @ColumnInfo(name = "created_transaction_id")
    val createdTransactionId: String,
    @ColumnInfo(name = "created_at_epoch_millis")
    val createdAtEpochMillis: Long,
)

@Entity(
    tableName = "student_problem_canonical_source_binding",
    primaryKeys = [
        "learner_id",
        "subject",
        "evidence_kind",
        "evidence_canonical_fingerprint",
    ],
    foreignKeys = [
        ForeignKey(
            entity = StudentProblemCanonicalIdentityEntity::class,
            parentColumns = [
                "learner_id",
                "subject",
                "identity_namespace",
                "identity_version",
                "stable_key",
            ],
            childColumns = [
                "learner_id",
                "subject",
                "identity_namespace",
                "identity_version",
                "identity_stable_key",
            ],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = StudentProblemRevisionEntity::class,
            parentColumns = ["revision_id"],
            childColumns = ["bound_revision_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(
            value = [
                "learner_id",
                "subject",
                "identity_namespace",
                "identity_version",
                "identity_stable_key",
            ],
        ),
        Index(value = ["bound_revision_id"]),
        Index(value = ["trusted_source_proof_fingerprint"], unique = true),
        Index(value = ["reviewed_alias_proof_fingerprint"], unique = true),
    ],
)
internal data class StudentProblemCanonicalSourceBindingEntity(
    @ColumnInfo(name = "learner_id")
    val learnerId: String,
    val subject: String,
    @ColumnInfo(name = "evidence_kind")
    val evidenceKind: String,
    @ColumnInfo(name = "evidence_canonical_fingerprint")
    val evidenceCanonicalFingerprint: String,
    @ColumnInfo(name = "identity_namespace")
    val identityNamespace: String,
    @ColumnInfo(name = "identity_version")
    val identityVersion: String,
    @ColumnInfo(name = "identity_stable_key")
    val identityStableKey: String,
    @ColumnInfo(name = "asset_manifest_canonical_fingerprint")
    val assetManifestCanonicalFingerprint: String,
    @ColumnInfo(name = "selected_region_canonical_fingerprint")
    val selectedRegionCanonicalFingerprint: String,
    @ColumnInfo(name = "document_canonical_fingerprint")
    val documentCanonicalFingerprint: String,
    @ColumnInfo(name = "bound_revision_id")
    val boundRevisionId: String,
    @ColumnInfo(name = "locator_namespace")
    val locatorNamespace: String?,
    @ColumnInfo(name = "locator_version")
    val locatorVersion: String?,
    @ColumnInfo(name = "item_locator_canonical_fingerprint")
    val itemLocatorCanonicalFingerprint: String?,
    @ColumnInfo(name = "trusted_source_proof_fingerprint")
    val trustedSourceProofFingerprint: String?,
    @ColumnInfo(name = "reviewed_alias_proof_fingerprint")
    val reviewedAliasProofFingerprint: String?,
    @ColumnInfo(name = "review_case_id")
    val reviewCaseId: String?,
    @ColumnInfo(name = "review_revision")
    val reviewRevision: Int?,
    @ColumnInfo(name = "review_decision_canonical_fingerprint")
    val reviewDecisionCanonicalFingerprint: String?,
    @ColumnInfo(name = "created_transaction_id")
    val createdTransactionId: String,
    @ColumnInfo(name = "created_at_epoch_millis")
    val createdAtEpochMillis: Long,
)

@Entity(
    tableName = "student_capture_occurrence_transaction",
    foreignKeys = [
        ForeignKey(
            entity = StudentCaptureSaveHandoffEntity::class,
            parentColumns = ["intent_id"],
            childColumns = ["capture_intent_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = StudentMistakeSaveReceiptEntity::class,
            parentColumns = ["intent_confirmation_id"],
            childColumns = ["target_save_receipt_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = StudentProblemRevisionEntity::class,
            parentColumns = ["revision_id"],
            childColumns = ["target_revision_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = StudentProblemErrorOccurrenceEntity::class,
            parentColumns = ["occurrence_id"],
            childColumns = ["occurrence_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["transaction_canonical_fingerprint"], unique = true),
        Index(value = ["capture_intent_id"], unique = true),
        Index(value = ["target_save_receipt_id"]),
        Index(value = ["target_revision_id"]),
        Index(value = ["occurrence_id"], unique = true),
        Index(value = ["learner_id", "committed_at_epoch_millis", "transaction_id"]),
    ],
)
internal data class StudentCaptureOccurrenceTransactionEntity(
    @PrimaryKey
    @ColumnInfo(name = "transaction_id")
    val transactionId: String,
    @ColumnInfo(name = "transaction_canonical_fingerprint")
    val transactionCanonicalFingerprint: String,
    @ColumnInfo(name = "request_canonical_fingerprint")
    val requestCanonicalFingerprint: String,
    @ColumnInfo(name = "schema_version")
    val schemaVersion: Int,
    @ColumnInfo(name = "learner_id")
    val learnerId: String,
    @ColumnInfo(name = "capture_intent_id")
    val captureIntentId: String,
    @ColumnInfo(name = "source_kind")
    val sourceKind: String,
    @ColumnInfo(name = "source_canonical_fingerprint")
    val sourceCanonicalFingerprint: String,
    @ColumnInfo(name = "identity_namespace")
    val identityNamespace: String,
    @ColumnInfo(name = "identity_version")
    val identityVersion: String,
    @ColumnInfo(name = "identity_stable_key")
    val identityStableKey: String,
    @ColumnInfo(name = "identity_canonical_fingerprint")
    val identityCanonicalFingerprint: String,
    @ColumnInfo(name = "identity_resolution_kind")
    val identityResolutionKind: String,
    @ColumnInfo(name = "identity_evidence_kind")
    val identityEvidenceKind: String,
    @ColumnInfo(name = "identity_evidence_canonical_fingerprint")
    val identityEvidenceCanonicalFingerprint: String,
    @ColumnInfo(name = "trusted_source_proof_fingerprint")
    val trustedSourceProofFingerprint: String?,
    @ColumnInfo(name = "reviewed_alias_proof_fingerprint")
    val reviewedAliasProofFingerprint: String?,
    @ColumnInfo(name = "target_save_receipt_id")
    val targetSaveReceiptId: String,
    @ColumnInfo(name = "target_problem_id")
    val targetProblemId: String,
    @ColumnInfo(name = "target_revision_id")
    val targetRevisionId: String,
    @ColumnInfo(name = "target_revision_number")
    val targetRevisionNumber: Int,
    @ColumnInfo(name = "target_document_canonical_fingerprint")
    val targetDocumentCanonicalFingerprint: String,
    @ColumnInfo(name = "target_canonical_fingerprint")
    val targetCanonicalFingerprint: String,
    @ColumnInfo(name = "save_outcome")
    val saveOutcome: String,
    @ColumnInfo(name = "occurrence_id")
    val occurrenceId: String,
    @ColumnInfo(name = "occurrence_canonical_fingerprint")
    val occurrenceCanonicalFingerprint: String,
    @ColumnInfo(name = "batch_canonical_fingerprint")
    val batchCanonicalFingerprint: String,
    @ColumnInfo(name = "import_source_canonical_fingerprint")
    val importSourceCanonicalFingerprint: String,
    @ColumnInfo(name = "asset_manifest_canonical_fingerprint")
    val assetManifestCanonicalFingerprint: String,
    @ColumnInfo(name = "selected_region_canonical_fingerprint")
    val selectedRegionCanonicalFingerprint: String,
    @ColumnInfo(name = "committed_at_epoch_millis")
    val committedAtEpochMillis: Long,
)

internal data class StudentCaptureOccurrenceTransactionDraft(
    val transactionId: String,
    val transactionCanonicalFingerprint: String,
    val requestCanonicalFingerprint: String,
    val schemaVersion: Int,
    val learnerId: String,
    val captureIntentId: String,
    val sourceKind: String,
    val sourceCanonicalFingerprint: String,
    val identityNamespace: String,
    val identityVersion: String,
    val identityStableKey: String,
    val identityCanonicalFingerprint: String,
    val identityResolutionKind: String,
    val identityEvidenceKind: String,
    val identityEvidenceCanonicalFingerprint: String,
    val trustedSourceProofFingerprint: String?,
    val reviewedAliasProofFingerprint: String?,
    val targetSaveReceiptId: String,
    val targetProblemId: String,
    val targetRevisionId: String,
    val targetRevisionNumber: Int,
    val targetDocumentCanonicalFingerprint: String,
    val targetCanonicalFingerprint: String,
    val occurrenceId: String,
    val occurrenceCanonicalFingerprint: String,
    val batchCanonicalFingerprint: String,
    val importSourceCanonicalFingerprint: String,
    val assetManifestCanonicalFingerprint: String,
    val selectedRegionCanonicalFingerprint: String,
    val committedAtEpochMillis: Long,
) {
    fun withSaveOutcome(
        outcome: TargetConfirmedStudentMistakeSaveOutcome,
    ): StudentCaptureOccurrenceTransactionEntity =
        StudentCaptureOccurrenceTransactionEntity(
            transactionId = transactionId,
            transactionCanonicalFingerprint = transactionCanonicalFingerprint,
            requestCanonicalFingerprint = requestCanonicalFingerprint,
            schemaVersion = schemaVersion,
            learnerId = learnerId,
            captureIntentId = captureIntentId,
            sourceKind = sourceKind,
            sourceCanonicalFingerprint = sourceCanonicalFingerprint,
            identityNamespace = identityNamespace,
            identityVersion = identityVersion,
            identityStableKey = identityStableKey,
            identityCanonicalFingerprint = identityCanonicalFingerprint,
            identityResolutionKind = identityResolutionKind,
            identityEvidenceKind = identityEvidenceKind,
            identityEvidenceCanonicalFingerprint =
                identityEvidenceCanonicalFingerprint,
            trustedSourceProofFingerprint = trustedSourceProofFingerprint,
            reviewedAliasProofFingerprint = reviewedAliasProofFingerprint,
            targetSaveReceiptId = targetSaveReceiptId,
            targetProblemId = targetProblemId,
            targetRevisionId = targetRevisionId,
            targetRevisionNumber = targetRevisionNumber,
            targetDocumentCanonicalFingerprint = targetDocumentCanonicalFingerprint,
            targetCanonicalFingerprint = targetCanonicalFingerprint,
            saveOutcome = outcome.name,
            occurrenceId = occurrenceId,
            occurrenceCanonicalFingerprint = occurrenceCanonicalFingerprint,
            batchCanonicalFingerprint = batchCanonicalFingerprint,
            importSourceCanonicalFingerprint = importSourceCanonicalFingerprint,
            assetManifestCanonicalFingerprint = assetManifestCanonicalFingerprint,
            selectedRegionCanonicalFingerprint =
                selectedRegionCanonicalFingerprint,
            committedAtEpochMillis = committedAtEpochMillis,
        )

    fun matches(
        persisted: StudentCaptureOccurrenceTransactionEntity,
    ): Boolean = persisted.copy(saveOutcome = "") == withSaveOutcome(
        TargetConfirmedStudentMistakeSaveOutcome.CREATED,
    ).copy(saveOutcome = "")
}

internal enum class StudentProblemIdentityResolutionKind {
    FRESH_OPAQUE,
    EXACT_ASSET_SELECTION,
    TRUSTED_SOURCE,
    REVIEWED_ALIAS,
}

internal enum class StudentProblemIdentityEvidenceKind {
    OPAQUE_CAPTURE_INTENT,
    EXACT_ASSET_SELECTION,
    TRUSTED_SOURCE,
    REVIEWED_ALIAS,
}

/**
 * Store-internal evidence that has already passed process-local proof verification.
 *
 * It contains no caller-selected canonical identity except the target of a sealed reviewed-alias
 * proof. Resolution and fresh identity issuance still occur in [StudentMistakeDao]'s transaction.
 */
internal data class StudentProblemIdentityEvidenceDraft(
    val resolutionKind: StudentProblemIdentityResolutionKind,
    val evidenceKind: StudentProblemIdentityEvidenceKind,
    val evidenceCanonicalFingerprint: String,
    val candidateDocumentCanonicalFingerprint: String,
    val assetManifestCanonicalFingerprint: String,
    val selectedRegionCanonicalFingerprint: String,
    val locatorNamespace: String? = null,
    val locatorVersion: String? = null,
    val itemLocatorCanonicalFingerprint: String? = null,
    val trustedSourceProofFingerprint: String? = null,
    val aliasIdentityNamespace: String? = null,
    val aliasIdentityVersion: String? = null,
    val aliasIdentityStableKey: String? = null,
    val aliasIdentityCanonicalFingerprint: String? = null,
    val reviewedAliasProofFingerprint: String? = null,
    val reviewCaseId: String? = null,
    val reviewRevision: Int? = null,
    val reviewDecisionCanonicalFingerprint: String? = null,
)

internal data class StudentCaptureOccurrenceRequestDraft(
    val transactionId: String,
    val requestCanonicalFingerprint: String,
    val schemaVersion: Int,
    val learnerId: String,
    val captureIntentId: String,
    val sourceKind: String,
    val sourceCanonicalFingerprint: String,
    val assetManifestCanonicalFingerprint: String,
    val selectedRegionCanonicalFingerprint: String,
    val committedAtEpochMillis: Long,
)

internal data class SaveStudentCaptureOccurrenceBundle(
    val identityEvidence: StudentProblemIdentityEvidenceDraft,
    val capture: SaveStudentOwnedCaptureBundle,
    val occurrence: StudentProblemErrorOccurrenceBundle,
    val request: StudentCaptureOccurrenceRequestDraft,
)

internal data class SaveStudentCaptureOccurrenceDbResult(
    val identity: StudentProblemCanonicalIdentityEntity,
    val transaction: StudentCaptureOccurrenceTransactionEntity,
    val handoff: StudentCaptureSaveHandoffEntity,
    val occurrence: StudentProblemErrorOccurrenceBundle,
)

internal fun canonicalStudentCaptureOccurrenceTransactionId(
    learnerId: String,
    intentId: String,
): String =
    CanonicalSha256(STUDENT_CAPTURE_OCCURRENCE_TRANSACTION_ID_DOMAIN)
        .field("schemaVersion", STUDENT_CAPTURE_OCCURRENCE_TRANSACTION_SCHEMA_VERSION)
        .field("learnerId", learnerId)
        .field("intentId", intentId)
        .finish()

internal fun canonicalStudentProblemId(
    identityCanonicalFingerprint: String,
): String =
    "problem-" +
        CanonicalSha256(STUDENT_PROBLEM_ID_DOMAIN)
            .field("identityCanonicalFingerprint", identityCanonicalFingerprint)
            .finish()

internal fun canonicalStudentProblemRevisionId(
    problemId: String,
    documentCanonicalFingerprint: String,
): String =
    "revision-" +
        CanonicalSha256(STUDENT_PROBLEM_REVISION_ID_DOMAIN)
            .field("problemId", problemId)
            .field("documentCanonicalFingerprint", documentCanonicalFingerprint)
            .finish()

internal fun canonicalStudentProblemErrorBookEntryId(problemId: String): String =
    "error-book-" +
        CanonicalSha256(STUDENT_PROBLEM_ERROR_BOOK_ENTRY_ID_DOMAIN)
            .field("problemId", problemId)
            .finish()

internal fun canonicalStudentProblemTargetFingerprint(
    identityCanonicalFingerprint: String,
    problemId: String,
    revisionId: String,
    documentCanonicalFingerprint: String,
): String =
    CanonicalSha256(STUDENT_PROBLEM_TARGET_FINGERPRINT_DOMAIN)
        .field("identityCanonicalFingerprint", identityCanonicalFingerprint)
        .field("problemId", problemId)
        .field("revisionId", revisionId)
        .field("documentCanonicalFingerprint", documentCanonicalFingerprint)
        .finish()

internal fun canonicalStudentCaptureOccurrenceTransactionFingerprint(
    sourceKind: String,
    sourceIntentId: String,
    sourceCanonicalFingerprint: String,
    canonicalIdentityFingerprint: String,
    targetLearnerId: String,
    targetSubject: String,
    targetProblemId: String,
    targetPracticeUnitId: String,
    targetRevisionId: String,
    targetRevisionNumber: Int,
    targetDocumentCanonicalFingerprint: String,
    targetCanonicalFingerprint: String,
    occurrenceId: String,
    occurrenceCanonicalFingerprint: String,
    batchCanonicalFingerprint: String,
    importSourceCanonicalFingerprint: String,
    assetManifestCanonicalFingerprint: String,
    selectedRegionCanonicalFingerprint: String,
): String =
    CanonicalSha256(STUDENT_CAPTURE_OCCURRENCE_TRANSACTION_FINGERPRINT_DOMAIN)
        .field("schemaVersion", STUDENT_CAPTURE_OCCURRENCE_TRANSACTION_SCHEMA_VERSION)
        .field("sourceKind", sourceKind)
        .field("sourceIntentId", sourceIntentId)
        .field("sourceCanonicalFingerprint", sourceCanonicalFingerprint)
        .field("canonicalIdentityFingerprint", canonicalIdentityFingerprint)
        .field("targetLearnerId", targetLearnerId)
        .field("targetSubject", targetSubject)
        .field("targetProblemId", targetProblemId)
        .field("targetPracticeUnitId", targetPracticeUnitId)
        .field("targetRevisionId", targetRevisionId)
        .field("targetRevisionNumber", targetRevisionNumber)
        .field(
            "targetDocumentCanonicalFingerprint",
            targetDocumentCanonicalFingerprint,
        )
        .field("targetCanonicalFingerprint", targetCanonicalFingerprint)
        .field("occurrenceId", occurrenceId)
        .field(
            "occurrenceCanonicalFingerprint",
            occurrenceCanonicalFingerprint,
        )
        .field("batchCanonicalFingerprint", batchCanonicalFingerprint)
        .field(
            "importSourceCanonicalFingerprint",
            importSourceCanonicalFingerprint,
        )
        .field(
            "assetManifestCanonicalFingerprint",
            assetManifestCanonicalFingerprint,
        )
        .field(
            "selectedRegionCanonicalFingerprint",
            selectedRegionCanonicalFingerprint,
        )
        .finish()

internal fun canonicalStudentProblemIdentityEvidenceFingerprint(
    evidenceKind: StudentProblemIdentityEvidenceKind,
    transactionId: String,
    assetManifestCanonicalFingerprint: String,
    selectedRegionCanonicalFingerprint: String,
    candidateDocumentCanonicalFingerprint: String? = null,
    locatorNamespace: String? = null,
    locatorVersion: String? = null,
    itemLocatorCanonicalFingerprint: String? = null,
    aliasIdentityCanonicalFingerprint: String? = null,
    reviewCaseId: String? = null,
    reviewRevision: Int? = null,
    reviewDecisionCanonicalFingerprint: String? = null,
): String =
    CanonicalSha256(STUDENT_PROBLEM_IDENTITY_EVIDENCE_FINGERPRINT_DOMAIN)
        .field("evidenceKind", evidenceKind.name)
        .nullableField(
            "opaqueTransactionId",
            transactionId.takeIf {
                evidenceKind == StudentProblemIdentityEvidenceKind.OPAQUE_CAPTURE_INTENT
            },
        )
        .nullableField(
            "locatorNamespace",
            locatorNamespace.takeIf {
                evidenceKind == StudentProblemIdentityEvidenceKind.TRUSTED_SOURCE
            },
        )
        .nullableField(
            "locatorVersion",
            locatorVersion.takeIf {
                evidenceKind == StudentProblemIdentityEvidenceKind.TRUSTED_SOURCE
            },
        )
        .nullableField(
            "itemLocatorCanonicalFingerprint",
            itemLocatorCanonicalFingerprint.takeIf {
                evidenceKind == StudentProblemIdentityEvidenceKind.TRUSTED_SOURCE
            },
        )
        .nullableField(
            "assetManifestCanonicalFingerprint",
            assetManifestCanonicalFingerprint.takeIf {
                evidenceKind ==
                    StudentProblemIdentityEvidenceKind.EXACT_ASSET_SELECTION ||
                    evidenceKind == StudentProblemIdentityEvidenceKind.REVIEWED_ALIAS
            },
        )
        .nullableField(
            "selectedRegionCanonicalFingerprint",
            selectedRegionCanonicalFingerprint.takeIf {
                evidenceKind ==
                    StudentProblemIdentityEvidenceKind.EXACT_ASSET_SELECTION ||
                    evidenceKind == StudentProblemIdentityEvidenceKind.REVIEWED_ALIAS
            },
        )
        .nullableField(
            "candidateDocumentCanonicalFingerprint",
            candidateDocumentCanonicalFingerprint.takeIf {
                evidenceKind == StudentProblemIdentityEvidenceKind.REVIEWED_ALIAS
            },
        )
        .nullableField(
            "aliasIdentityCanonicalFingerprint",
            aliasIdentityCanonicalFingerprint.takeIf {
                evidenceKind == StudentProblemIdentityEvidenceKind.REVIEWED_ALIAS
            },
        )
        .nullableField(
            "reviewCaseId",
            reviewCaseId.takeIf {
                evidenceKind == StudentProblemIdentityEvidenceKind.REVIEWED_ALIAS
            },
        )
        .nullableLongField(
            "reviewRevision",
            reviewRevision?.toLong()?.takeIf {
                evidenceKind == StudentProblemIdentityEvidenceKind.REVIEWED_ALIAS
            },
        )
        .nullableField(
            "reviewDecisionCanonicalFingerprint",
            reviewDecisionCanonicalFingerprint.takeIf {
                evidenceKind == StudentProblemIdentityEvidenceKind.REVIEWED_ALIAS
            },
        )
        .finish()

fun studentCaptureAssetManifestCanonicalFingerprint(
    images: List<StudentProblemImageReference>,
): String {
    require(images.map(StudentProblemImageReference::ordinal) == images.indices.toList()) {
        "Capture asset manifest must preserve contiguous source order"
    }
    val canonical =
        CanonicalSha256(STUDENT_CAPTURE_ASSET_MANIFEST_FINGERPRINT_DOMAIN)
            .field("assetCount", images.size)
    images.forEachIndexed { index, image ->
        val prefix = "asset[$index]"
        canonical
            .field("$prefix.ordinal", image.ordinal)
            .field(
                "$prefix.contentCanonicalFingerprint",
                image.contentCanonicalFingerprint,
            )
            .field("$prefix.mediaType", image.mediaType)
            .nullableLongField("$prefix.widthPixels", image.widthPixels?.toLong())
            .nullableLongField("$prefix.heightPixels", image.heightPixels?.toLong())
            .nullableLongField("$prefix.byteSize", image.byteSize)
    }
    return canonical.finish()
}

fun studentCaptureSelectedRegionCanonicalFingerprint(
    images: List<StudentProblemImageReference>,
): String {
    require(images.map(StudentProblemImageReference::ordinal) == images.indices.toList()) {
        "Capture selected regions must preserve contiguous source order"
    }
    val canonical =
        CanonicalSha256(STUDENT_CAPTURE_SELECTED_REGION_FINGERPRINT_DOMAIN)
            .field("assetCount", images.size)
    images.forEachIndexed { assetIndex, image ->
        val prefix = "asset[$assetIndex]"
        if (image.selectedRegions.isEmpty()) {
            canonical
                .field("$prefix.scope", STUDENT_CAPTURE_FULL_FRAME_REGION_MARKER)
                .field("$prefix.regionCount", 0)
        } else {
            canonical
                .field("$prefix.scope", STUDENT_CAPTURE_ORDERED_REGIONS_MARKER)
                .field("$prefix.regionCount", image.selectedRegions.size)
            image.selectedRegions.forEachIndexed { regionIndex, region ->
                canonical.captureRegion(
                    prefix = "$prefix.region[$regionIndex]",
                    region = region,
                )
            }
        }
    }
    return canonical.finish()
}

private fun CanonicalSha256.captureRegion(
    prefix: String,
    region: NormalizedSourceRegion,
): CanonicalSha256 =
    field("$prefix.leftBits", region.left.toBits())
        .field("$prefix.topBits", region.top.toBits())
        .field("$prefix.rightBits", region.right.toBits())
        .field("$prefix.bottomBits", region.bottom.toBits())

private fun StudentProblemIdentityEvidence.requestFingerprint(): String =
    when (this) {
        StudentProblemIdentityEvidence.Unresolved ->
            CanonicalSha256(STUDENT_PROBLEM_IDENTITY_REQUEST_FINGERPRINT_DOMAIN)
                .field("kind", StudentProblemIdentityResolutionKind.FRESH_OPAQUE.name)
                .finish()
        StudentProblemIdentityEvidence.ExactAssetSelection ->
            CanonicalSha256(STUDENT_PROBLEM_IDENTITY_REQUEST_FINGERPRINT_DOMAIN)
                .field(
                    "kind",
                    StudentProblemIdentityResolutionKind.EXACT_ASSET_SELECTION.name,
                )
                .finish()
        is StudentProblemIdentityEvidenceAuthority.TrustedSourceEvidence ->
            CanonicalSha256(STUDENT_PROBLEM_IDENTITY_REQUEST_FINGERPRINT_DOMAIN)
                .field("kind", StudentProblemIdentityResolutionKind.TRUSTED_SOURCE.name)
                .field("receiptId", proof.receiptId)
                .field(
                    "receiptCanonicalFingerprint",
                    proof.receiptCanonicalFingerprint,
                )
                .field("proofCanonicalFingerprint", proof.proofCanonicalFingerprint)
                .field("locatorNamespace", proof.locatorNamespace)
                .field("locatorVersion", proof.locatorVersion)
                .field(
                    "itemLocatorCanonicalFingerprint",
                    proof.itemLocatorCanonicalFingerprint,
                )
                .finish()
        is StudentProblemIdentityEvidenceAuthority.ReviewedAliasEvidence ->
            CanonicalSha256(STUDENT_PROBLEM_IDENTITY_REQUEST_FINGERPRINT_DOMAIN)
                .field("kind", StudentProblemIdentityResolutionKind.REVIEWED_ALIAS.name)
                .field("receiptId", proof.receiptId)
                .field(
                    "receiptCanonicalFingerprint",
                    proof.receiptCanonicalFingerprint,
                )
                .field("proofCanonicalFingerprint", proof.proofCanonicalFingerprint)
                .field("reviewAuthorityKind", proof.reviewAuthorityKind)
                .field(
                    "existingIdentityNamespace",
                    proof.existingIdentityNamespace,
                )
                .field(
                    "existingIdentityVersion",
                    proof.existingIdentityVersion,
                )
                .field(
                    "existingIdentityStableKey",
                    proof.existingIdentityStableKey,
                )
                .field(
                    "existingIdentityCanonicalFingerprint",
                    proof.existingIdentityCanonicalFingerprint,
                )
                .field("reviewCaseId", proof.reviewCaseId)
                .field("reviewRevision", proof.reviewRevision)
                .field(
                    "reviewDecisionCanonicalFingerprint",
                    proof.reviewDecisionCanonicalFingerprint,
                )
                .finish()
        else -> error("Unsupported student problem identity evidence")
    }

private const val STUDENT_CAPTURE_OCCURRENCE_TRANSACTION_ID_DOMAIN =
    "student-capture-occurrence-transaction-id-v1"
private const val STUDENT_CAPTURE_OCCURRENCE_REQUEST_FINGERPRINT_DOMAIN =
    "student-capture-occurrence-request-v1"
private const val STUDENT_CAPTURE_OCCURRENCE_TRANSACTION_FINGERPRINT_DOMAIN =
    "student-capture-occurrence-transaction-v1"
private const val STUDENT_PROBLEM_CANONICAL_IDENTITY_FINGERPRINT_DOMAIN =
    "student-problem-canonical-identity-v1"
private const val STUDENT_PROBLEM_ID_DOMAIN =
    "student-problem-id-from-canonical-identity-v1"
private const val STUDENT_PROBLEM_REVISION_ID_DOMAIN =
    "student-problem-revision-id-from-canonical-identity-v1"
private const val STUDENT_PROBLEM_ERROR_BOOK_ENTRY_ID_DOMAIN =
    "student-problem-error-book-entry-id-v1"
private const val STUDENT_PROBLEM_TARGET_FINGERPRINT_DOMAIN =
    "student-problem-canonical-target-v1"
private const val STUDENT_PROBLEM_IDENTITY_EVIDENCE_FINGERPRINT_DOMAIN =
    "student-problem-identity-evidence-v1"
private const val STUDENT_PROBLEM_IDENTITY_REQUEST_FINGERPRINT_DOMAIN =
    "student-problem-identity-request-v1"
private const val STUDENT_CAPTURE_ASSET_MANIFEST_FINGERPRINT_DOMAIN =
    "student-capture-ordered-asset-manifest-v1"
private const val STUDENT_CAPTURE_SELECTED_REGION_FINGERPRINT_DOMAIN =
    "student-capture-selected-region-manifest-v1"
internal const val STUDENT_CAPTURE_FULL_FRAME_REGION_MARKER = "FULL_FRAME_V1"
private const val STUDENT_CAPTURE_ORDERED_REGIONS_MARKER = "ORDERED_REGIONS_V1"
