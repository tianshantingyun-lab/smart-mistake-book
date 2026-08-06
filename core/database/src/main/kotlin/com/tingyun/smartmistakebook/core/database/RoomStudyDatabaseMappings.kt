package com.tingyun.smartmistakebook.core.database

import com.tingyun.smartmistakebook.core.database.dao.ExactLegacyStudentDocumentAssetRow
import com.tingyun.smartmistakebook.core.database.dao.MistakeRow
import com.tingyun.smartmistakebook.core.database.dao.KnowledgeGroundingSummaryRow
import com.tingyun.smartmistakebook.core.database.dao.LegacyStudentDocumentAssetRow
import com.tingyun.smartmistakebook.core.database.dao.LegacyStudentDocumentMigrationRow
import com.tingyun.smartmistakebook.core.database.dao.PendingCaptureHeadRow
import com.tingyun.smartmistakebook.core.database.dao.PendingCaptureSourceAssetRow
import com.tingyun.smartmistakebook.core.database.dao.ReviewPlanAggregate
import com.tingyun.smartmistakebook.core.database.dao.ReviewedKnowledgeCoverageRow
import com.tingyun.smartmistakebook.core.database.dao.activeSessionHead
import com.tingyun.smartmistakebook.core.database.dao.latestSessionHead
import com.tingyun.smartmistakebook.core.database.dao.toRecord
import com.tingyun.smartmistakebook.core.database.entity.AssessmentEventEntity
import com.tingyun.smartmistakebook.core.database.entity.AssessmentItemSnapshotEntity
import com.tingyun.smartmistakebook.core.database.entity.CaptureDraftMergeSessionReceiptEntity
import com.tingyun.smartmistakebook.core.database.entity.ErrorBookEntryEntity
import com.tingyun.smartmistakebook.core.database.entity.KnowledgeMasteryStateEntity
import com.tingyun.smartmistakebook.core.database.entity.KnowledgeGroundingRequestEntity
import com.tingyun.smartmistakebook.core.database.entity.KnowledgeGroundingResolutionEntity
import com.tingyun.smartmistakebook.core.database.entity.KnowledgeNodeEntity
import com.tingyun.smartmistakebook.core.database.entity.KnowledgeNodeRelationEntity
import com.tingyun.smartmistakebook.core.database.entity.KnowledgeNodeSourceBindingEntity
import com.tingyun.smartmistakebook.core.database.entity.KnowledgeSearchFeatureEntity
import com.tingyun.smartmistakebook.core.database.entity.KnowledgeSourceEntity
import com.tingyun.smartmistakebook.core.database.entity.KnowledgeTeachingMaterialEntity
import com.tingyun.smartmistakebook.core.database.entity.KnowledgeTeachingMaterialNodeBindingEntity
import com.tingyun.smartmistakebook.core.database.entity.PracticeUnitEntity
import com.tingyun.smartmistakebook.core.database.entity.PracticeUnitKnowledgeBindingEntity
import com.tingyun.smartmistakebook.core.database.entity.ProblemEntity
import com.tingyun.smartmistakebook.core.database.entity.ProblemDraftCommitReceiptEntity
import com.tingyun.smartmistakebook.core.database.entity.ProblemMemoryStateEntity
import com.tingyun.smartmistakebook.core.database.entity.ProblemOrganizationWorkEntity
import com.tingyun.smartmistakebook.core.database.entity.ProblemRelationEntity
import com.tingyun.smartmistakebook.core.database.entity.ProblemRevisionEntity
import com.tingyun.smartmistakebook.core.database.entity.ReviewPlanEntity
import com.tingyun.smartmistakebook.core.database.entity.ReviewQueueItemEntity
import com.tingyun.smartmistakebook.core.database.entity.ReviewQueueKnowledgeNodeEntity
import com.tingyun.smartmistakebook.core.database.entity.ReviewQueueReasonEntity
import com.tingyun.smartmistakebook.core.database.entity.ReviewSessionAdvanceReceiptEntity
import com.tingyun.smartmistakebook.core.database.entity.ReviewSessionEntity
import com.tingyun.smartmistakebook.core.database.entity.ReviewSessionRevisionEntity
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocumentCodec
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocumentFingerprint
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocumentValidator
import com.tingyun.smartmistakebook.core.model.CanonicalSha256
import com.tingyun.smartmistakebook.core.model.ModelEgressManifest
import com.tingyun.smartmistakebook.core.model.ModelEgressPurpose
import com.tingyun.smartmistakebook.core.model.ModelPromptPolicyVersions
import com.tingyun.smartmistakebook.core.model.ModelTaskKind
import com.tingyun.smartmistakebook.core.model.ProblemOrganizationAuthorizationGrant
import com.tingyun.smartmistakebook.core.model.ProviderCapabilitySnapshot
import com.tingyun.smartmistakebook.core.model.TutorEvidenceRequest
import com.tingyun.smartmistakebook.core.model.TutorEvidenceRequestStatus
import kotlinx.coroutines.flow.map

internal const val KNOWLEDGE_NODE_QUERY_CHUNK_SIZE = 400
internal const val MAX_EXACT_LEGACY_CAPTURE_SOURCE_ASSETS =
    MAX_LEGACY_STUDENT_SOURCE_ASSETS_PER_RECORD
internal const val EXACT_CAPTURE_QUESTION_SOURCE_ROLE = "QUESTION_SOURCE"
internal val EXACT_LEGACY_CAPTURE_SHA256 = Regex("[0-9a-f]{64}")

internal fun ExactLegacyStudentDocumentAssetRow.toLegacyStudentDocumentAssetRowOrNull(
    expectedRevisionId: String,
): LegacyStudentDocumentAssetRow? {
    val durableSourceAssetId = canonicalSourceAssetId ?: return null
    val durableContentSha256 = contentSha256 ?: return null
    val durableRelativePath = relativePath ?: return null
    val durableMimeType = mimeType ?: return null
    val durableByteSize = byteSize ?: return null
    val durableWidth = width ?: return null
    val durableHeight = height ?: return null
    val durableSourceType = sourceType ?: return null
    val durableCreatedAtEpochMillis = createdAtEpochMillis ?: return null
    if (
        problemRevisionId != expectedRevisionId ||
        (pageIndex != null && pageIndex < 0) ||
        durableSourceAssetId != linkedSourceAssetId ||
        durableSourceAssetId.isBlank() ||
        role.isBlank() ||
        !durableContentSha256.matches(EXACT_LEGACY_CAPTURE_SHA256) ||
        durableRelativePath.isBlank() ||
        durableMimeType.isBlank() ||
        durableByteSize <= 0L ||
        durableWidth <= 0 ||
        durableHeight <= 0 ||
        durableSourceType.isBlank() ||
        durableCreatedAtEpochMillis < 0L
    ) {
        return null
    }
    return LegacyStudentDocumentAssetRow(
        problemRevisionId = problemRevisionId,
        pageIndex = pageIndex,
        role = role,
        linkedSourceAssetId = linkedSourceAssetId,
        canonicalSourceAssetId = durableSourceAssetId,
        contentSha256 = durableContentSha256,
        relativePath = durableRelativePath,
        mimeType = durableMimeType,
        byteSize = durableByteSize,
        width = durableWidth,
        height = durableHeight,
        sourceType = durableSourceType,
        createdAtEpochMillis = durableCreatedAtEpochMillis,
    )
}

internal fun LegacyStudentDocumentAssetRow.requireDurableLegacyStudentDocumentAsset(): LegacyStudentDocumentAssetRow {
    val durableSourceAssetId =
        checkNotNull(canonicalSourceAssetId) {
            "Legacy student source contains a broken source-asset link"
        }
    check(durableSourceAssetId == linkedSourceAssetId) {
        "Legacy student source-asset identity changed while it was read"
    }
    check(!contentSha256.isNullOrBlank())
    check(!relativePath.isNullOrBlank())
    check(!mimeType.isNullOrBlank())
    check(byteSize != null && byteSize > 0L)
    check(width != null && width > 0)
    check(height != null && height > 0)
    check(!sourceType.isNullOrBlank())
    check(createdAtEpochMillis != null && createdAtEpochMillis >= 0L)
    check(role.isNotBlank())
    check(pageIndex == null || pageIndex >= 0)
    return this
}

internal fun LegacyStudentDocumentMigrationRow.toLegacyStudentDocumentMigrationRecord(
    sourceAssets: List<LegacyStudentDocumentAssetRow>,
) = LegacyStudentDocumentMigrationRecord(
    entryId = entryId,
    problemId = problemId,
    problemCanonicalFingerprint = problemCanonicalFingerprint,
    revisionId = revisionId,
    revisionNumber = revisionNumber,
    subject = subject,
    problemCreatedAtEpochMillis = problemCreatedAtEpochMillis,
    problemArchivedAtEpochMillis = problemArchivedAtEpochMillis,
    title = title,
    problemMarkdown = problemMarkdown,
    questionDocumentSnapshot = questionDocumentSnapshot,
    answerSpecId = answerSpecId,
    answerSpecSnapshot = answerSpecSnapshot,
    answerVerificationStatus = answerVerificationStatus,
    revisionSourceType = revisionSourceType,
    revisionSourceReference = revisionSourceReference,
    contentFingerprint = contentFingerprint,
    practiceUnitId = practiceUnitId,
    practiceUnitKey = practiceUnitKey,
    practiceUnitKind = practiceUnitKind,
    practiceUnitTitle = practiceUnitTitle,
    practiceUnitPromptMarkdown = practiceUnitPromptMarkdown,
    estimatedSeconds = estimatedSeconds,
    practiceUnitRevisionId = practiceUnitRevisionId,
    practiceUnitCreatedAtEpochMillis = practiceUnitCreatedAtEpochMillis,
    entryCurrentRevisionId = entryCurrentRevisionId,
    sourceKey = sourceKey,
    status = status,
    acceptedAtEpochMillis = acceptedAtEpochMillis,
    updatedAtEpochMillis = updatedAtEpochMillis,
    revisionCreatedAtEpochMillis = revisionCreatedAtEpochMillis,
    sourceAssets =
        sourceAssets.map { asset ->
            MistakeDetailSourceAssetRecord(
                role = asset.role,
                pageIndex = asset.pageIndex,
                sourceAsset =
                    CanonicalSourceAssetRecord(
                        sourceAssetId = checkNotNull(asset.canonicalSourceAssetId),
                        contentSha256 = checkNotNull(asset.contentSha256),
                        relativePath = checkNotNull(asset.relativePath),
                        mimeType = checkNotNull(asset.mimeType),
                        byteSize = checkNotNull(asset.byteSize),
                        width = checkNotNull(asset.width),
                        height = checkNotNull(asset.height),
                        sourceType = checkNotNull(asset.sourceType),
                        createdAtEpochMillis =
                            checkNotNull(asset.createdAtEpochMillis),
                    ),
            )
        },
)

internal data class OrganizationSourceAssetIdentity(
    val assetId: String,
    val sha256: String,
    val width: Int,
    val height: Int,
    val pageIndex: Int,
)

internal fun reauthorizationNotApplied() = ReauthorizeProblemOrganizationWorkResult(
    outcome = ReauthorizeProblemOrganizationWorkOutcome.NOT_APPLIED,
    work = null,
)

internal fun validateProblemOrganizationReauthorization(
    authorization: ProblemOrganizationAuthorizationGrant,
    provider: ProviderCapabilitySnapshot,
    receipt: ProblemDraftCommitReceipt,
    draft: ProblemDraftRecord,
    nowEpochMillis: Long,
) {
    require(authorization.schemaVersion == ProblemOrganizationAuthorizationGrant.CURRENT_SCHEMA_VERSION) {
        "Problem organization authorization schema is not current"
    }
    require(
        authorization.authorizationPolicyVersion ==
            ProblemOrganizationAuthorizationGrant.CURRENT_AUTHORIZATION_POLICY_VERSION,
    ) { "Problem organization authorization policy is not current" }
    require(authorization.purpose == ModelEgressPurpose.CLASSIFICATION) {
        "Problem organization authorization purpose does not match"
    }
    require(authorization.authorizedTaskKind == ModelTaskKind.PROBLEM_CLASSIFY) {
        "Problem organization authorization task does not match"
    }
    require(authorization.requestSchemaVersion == ModelEgressManifest.CURRENT_SCHEMA_VERSION) {
        "Problem organization authorization request schema is not current"
    }
    require(
        authorization.promptPolicyVersion == ModelPromptPolicyVersions.PROBLEM_ORGANIZATION,
    ) { "Problem organization authorization prompt policy is not current" }
    require(authorization.matchesCurrent(provider, nowEpochMillis)) {
        "Problem organization authorization does not match the current provider"
    }
    require(authorization.sourceDraftId == receipt.draftId) {
        "Problem organization authorization belongs to another source draft"
    }
    require(
        draft.draftId == receipt.draftId &&
            draft.status == StudyDbValue.ProblemDraftStatus.COMMITTED &&
            draft.currentRevision.revisionNumber == receipt.draftRevisionNumber,
    ) { "Organization work no longer points to its committed source revision" }

    val authorizedAssetsById = authorization.assets.associateBy { it.assetId }
    require(
        authorizedAssetsById.size == draft.sourceAssets.size &&
            draft.sourceAssets.all { source ->
                val canonical = source.sourceAsset
                val authorized = authorizedAssetsById[canonical.sourceAssetId]
                authorized != null &&
                    authorized.sha256 == canonical.contentSha256 &&
                    authorized.byteSize == canonical.byteSize &&
                    authorized.width == canonical.width &&
                    authorized.height == canonical.height &&
                    authorized.selectedRegion == null
            },
    ) { "Problem organization authorization assets do not match its exact import occurrence" }
}

internal fun validateOrganizationWorkTransition(
    command: ProblemOrganizationWorkTransitionCommand,
    requireFailure: Boolean,
) {
    require(command.workId.isNotBlank()) { "workId must not be blank" }
    require(command.expectedStateVersion >= 0) { "expectedStateVersion must not be negative" }
    require(command.leaseOwner.isNotBlank()) { "leaseOwner must not be blank" }
    require(command.occurredAtEpochMillis >= 0) { "occurredAtEpochMillis must not be negative" }
    if (requireFailure) {
        require(!command.failureCode.isNullOrBlank()) { "failureCode must not be blank" }
        require(!command.failureMessage.isNullOrBlank()) { "failureMessage must not be blank" }
    } else {
        require(command.failureCode == null && command.failureMessage == null) {
            "Successful organization work cannot contain a failure"
        }
    }
}

internal fun validateAtomicOrganizationCompletion(
    command: CompleteProblemOrganizationWorkAtomicallyCommand,
) {
    require(command.workId.isNotBlank()) { "workId must not be blank" }
    require(command.expectedStateVersion >= 0) { "expectedStateVersion must not be negative" }
    require(command.leaseOwner.isNotBlank()) { "leaseOwner must not be blank" }
    require(command.requestId.isNotBlank()) { "requestId must not be blank" }
}

internal fun ProblemOrganizationWorkEntity.matchesCompletionAuthority(
    command: CompleteProblemOrganizationWorkAtomicallyCommand,
    authorizationNowEpochMillis: Long,
): Boolean =
    stateVersion == command.expectedStateVersion &&
        status == StudyDbValue.ProblemOrganizationWorkStatus.RUNNING &&
        leaseOwner == command.leaseOwner &&
        requestId == command.requestId &&
        leaseExpiresAtEpochMillis != null &&
        leaseExpiresAtEpochMillis > authorizationNowEpochMillis

internal fun ProblemOrganizationWorkEntity.toRecord() = ProblemOrganizationWorkRecord(
    workId = workId,
    commitReceiptCommandId = commitReceiptCommandId,
    status = status,
    stateVersion = stateVersion,
    attemptCount = attemptCount,
    notBeforeEpochMillis = notBeforeEpochMillis,
    requestId = requestId,
    requestSnapshot = requestSnapshot,
    authorizationGrantSnapshot = authorizationGrantSnapshot,
    leaseOwner = leaseOwner,
    leaseExpiresAtEpochMillis = leaseExpiresAtEpochMillis,
    failureCode = failureCode,
    failureMessage = failureMessage,
    createdAtEpochMillis = createdAtEpochMillis,
    updatedAtEpochMillis = updatedAtEpochMillis,
)

internal fun ProblemDraftCommitReceiptEntity.toProblemDraftCommitReceipt() =
    ProblemDraftCommitReceipt(
        commandId = commandId,
        payloadFingerprint = payloadFingerprint,
        draftId = draftId,
        draftRevisionNumber = draftRevisionNumber,
        problemId = problemId,
        problemRevisionId = problemRevisionId,
        practiceUnitId = practiceUnitId,
        errorBookEntryId = errorBookEntryId,
        committedAtEpochMillis = committedAtEpochMillis,
    )

internal fun PendingCaptureHeadRow.toProblemDraftRecord(
    sourceRows: List<PendingCaptureSourceAssetRow>,
): ProblemDraftRecord {
    val persistedRevisionNumber = revisionNumber
        ?: throw LearningLedgerIntegrityException("Pending draft $draftId has no current revision")
    if (persistedRevisionNumber != draftCurrentRevisionNumber) {
        throw LearningLedgerIntegrityException("Pending draft $draftId has a mismatched current revision")
    }
    val sourceAssets = sourceRows.map { row ->
        ProblemDraftSourceAssetRecord(
            pageIndex = row.pageIndex,
            sourceAsset = CanonicalSourceAssetRecord(
                sourceAssetId = row.sourceAssetId,
                contentSha256 = row.contentSha256,
                relativePath = row.relativePath,
                mimeType = row.mimeType,
                byteSize = row.byteSize,
                width = row.width,
                height = row.height,
                sourceType = row.sourceType,
                createdAtEpochMillis = row.createdAtEpochMillis,
            ),
        )
    }
    val primarySource = sourceAssets.firstOrNull()?.sourceAsset
        ?: throw LearningLedgerIntegrityException("Pending draft $draftId has no source asset")
    if (primarySource.sourceAssetId != primarySourceAssetId) {
        throw LearningLedgerIntegrityException("Pending draft $draftId has a mismatched primary source")
    }
    return ProblemDraftRecord(
        draftId = draftId,
        sourceAsset = primarySource,
        sourceAssets = sourceAssets,
        origin = draftOrigin,
        status = draftStatus,
        currentRevision = ProblemDraftRevisionRecord(
            draftId = draftId,
            revisionNumber = persistedRevisionNumber,
            basisRevisionNumber = revisionBasisRevisionNumber,
            subject = revisionSubject,
            title = revisionTitle
                ?: throw LearningLedgerIntegrityException("Pending draft $draftId has no revision title"),
            questionDocument = CapturedQuestionDocumentCodec.decode(
                revisionQuestionDocumentSnapshot ?: throw LearningLedgerIntegrityException(
                    "Pending draft $draftId has no question document",
                ),
            ),
            documentFingerprint = revisionDocumentFingerprint
                ?: throw LearningLedgerIntegrityException(
                    "Pending draft $draftId has no document fingerprint",
                ),
            author = revisionAuthor
                ?: throw LearningLedgerIntegrityException("Pending draft $draftId has no revision author"),
            createdAtEpochMillis = revisionCreatedAtEpochMillis
                ?: throw LearningLedgerIntegrityException(
                    "Pending draft $draftId has no revision creation time",
                ),
        ),
        createdAtEpochMillis = draftCreatedAtEpochMillis,
        updatedAtEpochMillis = draftUpdatedAtEpochMillis,
        requestFingerprint = draftRequestFingerprint,
    )
}

internal val PENDING_CAPTURE_TABLES = arrayOf(
    "problem_draft",
    "problem_draft_revision",
    "problem_draft_source_asset",
    "canonical_source_asset",
    "problem_draft_edit_snapshot",
    "tutor_session",
    "model_task",
)

internal fun ProblemSeedRecord.toEntity() = ProblemEntity(
    problemId = problemId,
    canonicalFingerprint = canonicalFingerprint,
    subject = subject,
    createdAtEpochMillis = createdAtEpochMillis,
)

internal fun ProblemRevisionSeedRecord.toEntity() = ProblemRevisionEntity(
    revisionId = revisionId,
    problemId = problemId,
    revisionNumber = revisionNumber,
    title = title,
    problemMarkdown = problemMarkdown,
    questionDocumentSnapshot = questionDocumentSnapshot,
    answerSpecId = answerSpecId,
    answerSpecSnapshot = answerSpecSnapshot,
    answerVerificationStatus = answerVerificationStatus,
    sourceType = sourceType,
    sourceReference = sourceReference,
    contentFingerprint = contentFingerprint,
    createdAtEpochMillis = createdAtEpochMillis,
)

internal fun PracticeUnitSeedRecord.toEntity() = PracticeUnitEntity(
    practiceUnitId = practiceUnitId,
    problemId = problemId,
    problemRevisionId = problemRevisionId,
    unitKey = unitKey,
    unitKind = unitKind,
    title = title,
    promptMarkdown = promptMarkdown,
    estimatedSeconds = estimatedSeconds,
    createdAtEpochMillis = createdAtEpochMillis,
)

internal fun ErrorBookEntrySeedRecord.toEntity() = ErrorBookEntryEntity(
    entryId = entryId,
    practiceUnitId = practiceUnitId,
    problemId = problemId,
    currentRevisionId = currentRevisionId,
    sourceKey = sourceKey,
    status = status,
    acceptedAtEpochMillis = acceptedAtEpochMillis,
    updatedAtEpochMillis = updatedAtEpochMillis,
)

internal fun KnowledgeNodeSeedRecord.toEntity() = KnowledgeNodeEntity(
    knowledgeNodeId = knowledgeNodeId,
    stableCode = stableCode,
    subject = subject,
    displayName = displayName,
    canonicalName = canonicalName,
    nodeKind = nodeKind,
    granularity = granularity,
    aliasesText = aliases.sorted().joinToString("\u001F"),
    boundaryMarkdown = boundaryMarkdown,
    verificationStatus = verificationStatus,
    parentKnowledgeNodeId = parentKnowledgeNodeId,
    taxonomyVersion = taxonomyVersion,
    createdAtEpochMillis = createdAtEpochMillis,
)

internal fun KnowledgeNodeSeedRecord.toSearchFeatures(): List<KnowledgeSearchFeatureEntity> =
    KnowledgeSearchFeatureExtractor.fromNode(this).map { feature ->
        KnowledgeSearchFeatureEntity(
            subject = subject,
            searchFeature = feature,
            knowledgeNodeId = knowledgeNodeId,
        )
    }

internal fun KnowledgeNodeEntity.toSearchFeatures(): List<KnowledgeSearchFeatureEntity> =
    toSeedRecord().toSearchFeatures()

internal fun KnowledgeNodeEntity.toSeedRecord() = KnowledgeNodeSeedRecord(
    knowledgeNodeId = knowledgeNodeId,
    stableCode = stableCode,
    subject = subject,
    displayName = displayName,
    parentKnowledgeNodeId = parentKnowledgeNodeId,
    taxonomyVersion = taxonomyVersion,
    createdAtEpochMillis = createdAtEpochMillis,
    canonicalName = canonicalName,
    nodeKind = nodeKind,
    granularity = granularity,
    aliases = aliasesText.split("\u001F").filter(String::isNotBlank).toSet(),
    boundaryMarkdown = boundaryMarkdown,
    verificationStatus = verificationStatus,
)

internal fun KnowledgeSourceSeedRecord.toEntity() = KnowledgeSourceEntity(
    sourceId = sourceId,
    subject = subject,
    sourceType = sourceType,
    title = title,
    publisher = publisher,
    edition = edition,
    sourceUri = sourceUri,
    licenseStatus = licenseStatus,
    contentFingerprint = contentFingerprint,
    importedAtEpochMillis = importedAtEpochMillis,
    contentUsePolicy = contentUsePolicy,
    licenseExpression = licenseExpression,
    licenseUri = licenseUri,
    attributionText = attributionText,
)

internal fun KnowledgeSourceEntity.toSeedRecord() = KnowledgeSourceSeedRecord(
    sourceId = sourceId,
    subject = subject,
    sourceType = sourceType,
    title = title,
    publisher = publisher,
    edition = edition,
    sourceUri = sourceUri,
    licenseStatus = licenseStatus,
    contentFingerprint = contentFingerprint,
    importedAtEpochMillis = importedAtEpochMillis,
    contentUsePolicy = contentUsePolicy,
    licenseExpression = licenseExpression,
    licenseUri = licenseUri,
    attributionText = attributionText,
)

internal fun KnowledgeNodeRelationRecord.toEntity() = KnowledgeNodeRelationEntity(
    relationId = relationId,
    subject = subject,
    prerequisiteKnowledgeNodeId = prerequisiteKnowledgeNodeId,
    dependentKnowledgeNodeId = dependentKnowledgeNodeId,
    relationType = relationType,
    sourceId = sourceId,
    sourceLocator = sourceLocator,
    reviewedAtEpochMillis = reviewedAtEpochMillis,
)

internal fun KnowledgeNodeRelationEntity.toRecord() = KnowledgeNodeRelationRecord(
    relationId = relationId,
    subject = subject,
    prerequisiteKnowledgeNodeId = prerequisiteKnowledgeNodeId,
    dependentKnowledgeNodeId = dependentKnowledgeNodeId,
    relationType = relationType,
    sourceId = sourceId,
    sourceLocator = sourceLocator,
    reviewedAtEpochMillis = reviewedAtEpochMillis,
)

internal fun KnowledgeTeachingMaterialRecord.toEntity() = KnowledgeTeachingMaterialEntity(
    materialId = materialId,
    stableCode = stableCode,
    subject = subject,
    materialType = materialType,
    title = title,
    summaryMarkdown = summaryMarkdown,
    applicabilityMarkdown = applicabilityMarkdown,
    contentMarkdown = contentMarkdown,
    boundaryMarkdown = boundaryMarkdown,
    derivationKind = derivationKind,
    sourceId = sourceId,
    sourceLocator = sourceLocator,
    contentFingerprint = contentFingerprint,
    reviewedAtEpochMillis = reviewedAtEpochMillis,
)

internal fun KnowledgeTeachingMaterialEntity.toRecord() = KnowledgeTeachingMaterialRecord(
    materialId = materialId,
    stableCode = stableCode,
    subject = subject,
    materialType = materialType,
    title = title,
    summaryMarkdown = summaryMarkdown,
    applicabilityMarkdown = applicabilityMarkdown,
    contentMarkdown = contentMarkdown,
    boundaryMarkdown = boundaryMarkdown,
    derivationKind = derivationKind,
    sourceId = sourceId,
    sourceLocator = sourceLocator,
    contentFingerprint = contentFingerprint,
    reviewedAtEpochMillis = reviewedAtEpochMillis,
)

internal fun KnowledgeTeachingMaterialNodeBindingRecord.toEntity() =
    KnowledgeTeachingMaterialNodeBindingEntity(
        materialId = materialId,
        knowledgeNodeId = knowledgeNodeId,
        role = role,
    )

internal fun KnowledgeTeachingMaterialNodeBindingEntity.toRecord() =
    KnowledgeTeachingMaterialNodeBindingRecord(
        materialId = materialId,
        knowledgeNodeId = knowledgeNodeId,
        role = role,
    )

internal fun KnowledgeGroundingRequestRecord.toEntity() = KnowledgeGroundingRequestEntity(
    groundingRequestId = groundingRequestId,
    groundingKey = groundingKey,
    organizationRequestId = organizationRequestId,
    organizationRequestFingerprint = organizationRequestFingerprint,
    requestOrdinal = requestOrdinal,
    problemId = problemId,
    problemRevisionId = problemRevisionId,
    practiceUnitId = practiceUnitId,
    subject = subject,
    query = query,
    expectedParentKnowledgeDisplayName = expectedParentKnowledgeDisplayName,
    reasonMarkdown = reasonMarkdown,
    status = status,
    createdAtEpochMillis = createdAtEpochMillis,
    updatedAtEpochMillis = updatedAtEpochMillis,
)

internal fun KnowledgeGroundingRequestEntity.toRecord() = KnowledgeGroundingRequestRecord(
    groundingRequestId = groundingRequestId,
    groundingKey = groundingKey,
    organizationRequestId = organizationRequestId,
    organizationRequestFingerprint = organizationRequestFingerprint,
    requestOrdinal = requestOrdinal,
    problemId = problemId,
    problemRevisionId = problemRevisionId,
    practiceUnitId = practiceUnitId,
    subject = subject,
    query = query,
    expectedParentKnowledgeDisplayName = expectedParentKnowledgeDisplayName,
    reasonMarkdown = reasonMarkdown,
    status = status,
    createdAtEpochMillis = createdAtEpochMillis,
    updatedAtEpochMillis = updatedAtEpochMillis,
)

internal fun KnowledgeGroundingSummaryRow.toRecord() = KnowledgeGroundingSummaryRecord(
    groundingKey = groundingKey,
    subject = subject,
    expectedParentKnowledgeDisplayName = expectedParentKnowledgeDisplayName,
    query = query,
    relatedQuestionCount = relatedQuestionCount,
    firstObservedAtEpochMillis = firstObservedAtEpochMillis,
    lastObservedAtEpochMillis = lastObservedAtEpochMillis,
)

internal data class KnowledgeBaseDependencies(
    val sources: List<KnowledgeSourceSeedRecord>,
    val parentNodes: List<KnowledgeNodeSeedRecord>,
)

internal fun KnowledgeGroundingResolutionEntity.toRecord() = KnowledgeGroundingResolutionRecord(
    resolutionId = resolutionId,
    groundingKey = groundingKey,
    subject = subject,
    knowledgeNodeId = knowledgeNodeId,
    taxonomyVersion = taxonomyVersion,
    resolvedOccurrenceCount = resolvedOccurrenceCount,
    linkedPracticeUnitCount = linkedPracticeUnitCount,
    resolvedAtEpochMillis = resolvedAtEpochMillis,
)

internal fun ReviewedKnowledgeCoverageRow.toRecord() = ReviewedKnowledgeCoverageRecord(
    subject = subject,
    topicCount = topicCount,
    atomicKnowledgeCount = atomicKnowledgeCount,
    reviewedSourceCount = reviewedSourceCount,
    latestReviewedAtEpochMillis = latestReviewedAtEpochMillis,
)

internal fun KnowledgeNodeSourceBindingSeedRecord.toEntity() = KnowledgeNodeSourceBindingEntity(
    knowledgeNodeId = knowledgeNodeId,
    sourceId = sourceId,
    sourceLocator = sourceLocator,
    derivationNote = derivationNote,
    reviewedAtEpochMillis = reviewedAtEpochMillis,
)

internal fun KnowledgeNodeSourceBindingEntity.toSeedRecord() =
    KnowledgeNodeSourceBindingSeedRecord(
        knowledgeNodeId = knowledgeNodeId,
        sourceId = sourceId,
        sourceLocator = sourceLocator,
        derivationNote = derivationNote,
        reviewedAtEpochMillis = reviewedAtEpochMillis,
    )

internal fun KnowledgeBindingSeedRecord.toEntity() =
    PracticeUnitKnowledgeBindingEntity(
        bindingId = bindingId,
        practiceUnitId = practiceUnitId,
        knowledgeNodeId = knowledgeNodeId,
        knowledgeSubject = null,
        knowledgeTaxonomyVersion = null,
        knowledgePackVersion = null,
        knowledgeManifestFingerprint = null,
        knowledgeActivationGeneration = null,
        knowledgeReferenceStatus =
            StudyDbValue.KnowledgeReferenceStatus.PENDING_REATTRIBUTION,
        basisRevisionId = basisRevisionId,
        strength = strength,
        sourceType = sourceType,
        taxonomyVersion = taxonomyVersion,
        acceptedAtEpochMillis = acceptedAtEpochMillis,
    )

internal fun ProblemRelationSeedRecord.toEntity() = ProblemRelationEntity(
    relationId = relationId,
    sourceProblemId = sourceProblemId,
    targetProblemId = targetProblemId,
    relationType = relationType,
    status = status,
    sourceBasisRevisionId = sourceBasisRevisionId,
    targetBasisRevisionId = targetBasisRevisionId,
    confidence = confidence,
    createdAtEpochMillis = createdAtEpochMillis,
    updatedAtEpochMillis = updatedAtEpochMillis,
)

internal fun AssessmentItemSnapshotSeedRecord.toEntity() = AssessmentItemSnapshotEntity(
    assessmentItemSnapshotId = assessmentItemSnapshotId,
    itemRevision = itemRevision,
    practiceUnitId = practiceUnitId,
    problemRevisionId = problemRevisionId,
    tutorContentSnapshotId = tutorContentSnapshotId,
    promptMarkdown = promptMarkdown,
    optionsSnapshot = optionsSnapshot,
    answerSpecSnapshot = answerSpecSnapshot,
    verificationStatus = verificationStatus,
    assessmentEligibility = assessmentEligibility,
    scoringMode = scoringMode,
    learnerSnapshotVersion = learnerSnapshotVersion,
    projectionCheckpoint = projectionCheckpoint,
    hintLevelAtPresentation = hintLevelAtPresentation,
    answerRevealState = answerRevealState,
    createdAtEpochMillis = createdAtEpochMillis,
)

internal fun AssessmentItemSnapshotEntity.toRecord() = AssessmentItemSnapshotSeedRecord(
    assessmentItemSnapshotId = assessmentItemSnapshotId,
    itemRevision = itemRevision,
    practiceUnitId = practiceUnitId,
    problemRevisionId = problemRevisionId,
    tutorContentSnapshotId = tutorContentSnapshotId,
    promptMarkdown = promptMarkdown,
    optionsSnapshot = optionsSnapshot,
    answerSpecSnapshot = answerSpecSnapshot,
    verificationStatus = verificationStatus,
    assessmentEligibility = assessmentEligibility,
    scoringMode = scoringMode,
    learnerSnapshotVersion = learnerSnapshotVersion,
    projectionCheckpoint = projectionCheckpoint,
    hintLevelAtPresentation = hintLevelAtPresentation,
    answerRevealState = answerRevealState,
    createdAtEpochMillis = createdAtEpochMillis,
)

internal fun AssessmentEventSeedRecord.toEntity() = AssessmentEventEntity(
    assessmentEventId = assessmentEventId,
    assessmentItemSnapshotId = assessmentItemSnapshotId,
    eventSequence = eventSequence,
    eventType = eventType,
    hintLevel = hintLevel,
    submittedResponse = submittedResponse,
    occurredAtEpochMillis = occurredAtEpochMillis,
)

internal fun ProblemMemoryStateRecord.toEntity() = ProblemMemoryStateEntity(
    practiceUnitId = practiceUnitId,
    stabilityDays = stabilityDays,
    difficulty = difficulty,
    lastReviewedAtEpochMillis = lastReviewedAtEpochMillis,
    nextReviewAtEpochMillis = nextReviewAtEpochMillis,
    reviewCount = reviewCount,
    lapseCount = lapseCount,
    retrievability = retrievability,
    projectionCheckpoint = projectionCheckpoint,
    projectorVersion = projectorVersion,
    updatedAtEpochMillis = updatedAtEpochMillis,
)

internal fun KnowledgeMasteryStateRecord.toEntity() = KnowledgeMasteryStateEntity(
    knowledgeNodeId = knowledgeNodeId,
    masteryProbability = masteryProbability,
    independentCorrectCount = independentCorrectCount,
    assistedCorrectCount = assistedCorrectCount,
    incorrectCount = incorrectCount,
    evidenceWeightTotal = evidenceWeightTotal,
    lastEvidenceAtEpochMillis = lastEvidenceAtEpochMillis,
    projectionCheckpoint = projectionCheckpoint,
    projectorVersion = projectorVersion,
    updatedAtEpochMillis = updatedAtEpochMillis,
)

internal fun ReviewPlanRecord.toEntity() = ReviewPlanEntity(
    reviewPlanId = reviewPlanId,
    learnerId = learnerId,
    localDate = localDate,
    localDayEpochDay = localDayEpochDay,
    timeZoneId = timeZoneId,
    timeBudgetSeconds = timeBudgetSeconds,
    planningAtEpochMillis = planningAtEpochMillis,
    status = status,
    plannerVersion = plannerVersion,
    projectionCheckpoint = projectionCheckpoint,
    inputFingerprint = inputFingerprint,
    planFingerprint = planFingerprint,
    planRevision = planRevision,
    createdAtEpochMillis = createdAtEpochMillis,
)

internal fun ReviewQueueItemRecord.toEntity() = ReviewQueueItemEntity(
    reviewQueueItemId = reviewQueueItemId,
    reviewPlanId = reviewPlanId,
    practiceUnitId = practiceUnitId,
    itemFamilyId = itemFamilyId,
    sourceBundleId = sourceBundleId,
    ordinal = ordinal,
    priorityScore = priorityScore,
    difficultyBand = difficultyBand,
    dueAtEpochMillis = dueAtEpochMillis,
    estimatedSeconds = estimatedSeconds,
    reasonSnapshot = reasonSnapshot,
    status = status,
)

internal fun ReviewQueueItemRecord.toKnowledgeNodeEntities() = knowledgeNodeIds
    .sorted()
    .map { ReviewQueueKnowledgeNodeEntity(reviewQueueItemId, it) }

internal fun ReviewQueueItemRecord.toReasonEntities() = reasons
    .sorted()
    .map { ReviewQueueReasonEntity(reviewQueueItemId, it) }

internal fun ReviewSessionRecord.toEntity() = ReviewSessionEntity(
    reviewSessionId = reviewSessionId,
    reviewPlanId = reviewPlanId,
    status = status,
    activeSessionKey = reviewPlanId.takeIf { status == StudyDbValue.ReviewStatus.IN_PROGRESS },
    startedAtEpochMillis = startedAtEpochMillis,
    lastActiveAtEpochMillis = lastActiveAtEpochMillis,
    completedAtEpochMillis = completedAtEpochMillis,
    currentOrdinal = currentOrdinal,
    timeBudgetSeconds = timeBudgetSeconds,
    projectionCheckpoint = projectionCheckpoint,
    stateVersion = stateVersion,
)

internal fun ReviewSessionRecord.toRevisionEntity() = ReviewSessionRevisionEntity(
    reviewSessionId = reviewSessionId,
    stateVersion = stateVersion,
    reviewPlanId = reviewPlanId,
    status = status,
    startedAtEpochMillis = startedAtEpochMillis,
    lastActiveAtEpochMillis = lastActiveAtEpochMillis,
    completedAtEpochMillis = completedAtEpochMillis,
    currentOrdinal = currentOrdinal,
    timeBudgetSeconds = timeBudgetSeconds,
    projectionCheckpoint = projectionCheckpoint,
)

internal fun ReviewPlanAggregate.toRecord(): ReviewPlanBundle {
    val sortedQueue = queue.sortedBy { it.item.ordinal }
    return ReviewPlanBundle(
        plan = ReviewPlanRecord(
            reviewPlanId = plan.reviewPlanId,
            learnerId = plan.learnerId,
            localDate = plan.localDate,
            localDayEpochDay = plan.localDayEpochDay,
            timeZoneId = plan.timeZoneId,
            timeBudgetSeconds = plan.timeBudgetSeconds,
            planningAtEpochMillis = plan.planningAtEpochMillis,
            status = plan.status,
            plannerVersion = plan.plannerVersion,
            projectionCheckpoint = plan.projectionCheckpoint,
            inputFingerprint = plan.inputFingerprint,
            planFingerprint = plan.planFingerprint,
            planRevision = plan.planRevision,
            createdAtEpochMillis = plan.createdAtEpochMillis,
        ),
        queue = sortedQueue.map { aggregate ->
            val item = aggregate.item
            ReviewQueueItemRecord(
                reviewQueueItemId = item.reviewQueueItemId,
                reviewPlanId = item.reviewPlanId,
                practiceUnitId = item.practiceUnitId,
                knowledgeNodeIds = aggregate.knowledgeNodes.mapTo(linkedSetOf()) { it.knowledgeNodeId },
                itemFamilyId = item.itemFamilyId,
                sourceBundleId = item.sourceBundleId,
                reasons = aggregate.reasons.mapTo(linkedSetOf()) { it.reason },
                ordinal = item.ordinal,
                priorityScore = item.priorityScore,
                difficultyBand = item.difficultyBand,
                dueAtEpochMillis = item.dueAtEpochMillis,
                estimatedSeconds = item.estimatedSeconds,
                reasonSnapshot = item.reasonSnapshot,
                status = item.status,
            )
        },
        activeSession = activeSessionHead()?.toRecord(),
        isCurrent = currentSlots.isNotEmpty(),
        latestSession = latestSessionHead()?.toRecord(),
    )
}

internal fun ReviewSessionEntity.toRecord() = ReviewSessionRecord(
    reviewSessionId = reviewSessionId,
    reviewPlanId = reviewPlanId,
    status = status,
    startedAtEpochMillis = startedAtEpochMillis,
    lastActiveAtEpochMillis = lastActiveAtEpochMillis,
    completedAtEpochMillis = completedAtEpochMillis,
    currentOrdinal = currentOrdinal,
    timeBudgetSeconds = timeBudgetSeconds,
    projectionCheckpoint = projectionCheckpoint,
    stateVersion = stateVersion,
)

internal fun ProblemDraftEditWorkspaceRecord.toConfirmedRevision(
    expected: ExpectedProblemDraftEditWorkspace,
): ProblemDraftRevisionRecord {
    val workspace = try {
        DatabaseContractValidator.decodeProblemDraftEditWorkspace(
            snapshotSchemaVersion = snapshotSchemaVersion,
            workspaceSnapshot = workspaceSnapshot,
            workspaceFingerprint = workspaceFingerprint,
        )
    } catch (failure: Exception) {
        throw ProblemDraftEditWorkspaceIntegrityException(
            "Problem-draft workspace $draftId is corrupted",
            failure,
        )
    }
    val finalRequest = workspace.finalConfirmationRequest
        ?: throw ProblemDraftEditWorkspaceConflictException(
            "Problem-draft workspace $draftId has no final confirmation identity",
        )
    if (
        draftId != expected.draftId ||
        basisRevisionNumber != expected.basisRevisionNumber ||
        workspaceVersion != expected.workspaceVersion ||
        workspaceFingerprint != expected.workspaceFingerprint ||
        finalRequest.requestId != expected.finalRequestId ||
        finalRequest.occurredAtEpochMillis != expected.finalOccurredAtEpochMillis ||
        expected.finalOccurredAtEpochMillis < updatedAtEpochMillis
    ) {
        throw ProblemDraftEditWorkspaceConflictException(
            "Problem-draft workspace $draftId does not match the final request",
        )
    }
    val subject = workspace.subject
        ?: throw ProblemDraftEditWorkspaceConflictException(
            "Problem-draft workspace $draftId has no confirmed subject",
        )
    val title = workspace.workingDocument.document.title
        ?.takeIf(String::isNotBlank)
        ?: throw ProblemDraftEditWorkspaceConflictException(
            "Problem-draft workspace $draftId has no confirmed title",
        )
    if (CapturedQuestionDocumentValidator.validateForCommit(workspace.workingDocument).isNotEmpty()) {
        throw ProblemDraftEditWorkspaceConflictException(
            "Problem-draft workspace $draftId is not ready to confirm",
        )
    }
    return ProblemDraftRevisionRecord(
        draftId = draftId,
        revisionNumber = basisRevisionNumber + 1,
        basisRevisionNumber = basisRevisionNumber,
        subject = subject,
        title = title,
        questionDocument = workspace.workingDocument,
        documentFingerprint = CapturedQuestionDocumentFingerprint.of(workspace.workingDocument),
        author = StudyDbValue.ProblemDraftAuthor.USER,
        createdAtEpochMillis = expected.finalOccurredAtEpochMillis,
    )
}

internal fun ExpectedProblemDraftEditWorkspace.toConsumeCommand() =
    ConsumeProblemDraftEditWorkspaceCommand(
        draftId = draftId,
        basisRevisionNumber = basisRevisionNumber,
        expectedWorkspaceVersion = workspaceVersion,
        expectedWorkspaceFingerprint = workspaceFingerprint,
    )

internal fun ProblemDraftEditWorkspaceRecord.toConsumeCommand() =
    ConsumeProblemDraftEditWorkspaceCommand(
        draftId = draftId,
        basisRevisionNumber = basisRevisionNumber,
        expectedWorkspaceVersion = workspaceVersion,
        expectedWorkspaceFingerprint = workspaceFingerprint,
    )

internal fun ReviewSessionAdvanceReceiptEntity.toRecord() = ReviewSessionAdvanceReceipt(
    sessionId = reviewSessionId,
    fromVersion = fromVersion,
    toVersion = toVersion,
    reviewQueueItemId = reviewQueueItemId,
    practiceUnitId = practiceUnitId,
    attemptId = attemptId,
    submissionId = submissionId,
    presentationId = presentationId,
    occurredAtEpochMillis = occurredAtEpochMillis,
)

internal fun CaptureDraftMergeSessionReceiptEntity.toRecord() =
    CaptureDraftMergeSessionReceiptRecord(
        receiptReference = receiptReference,
        batchJobId = batchJobId,
        batchPageIndex = batchPageIndex,
        primaryDraftId = primaryDraftId,
        followingDraftId = followingDraftId,
        mergedDraftId = mergedDraftId,
        assetOrderFingerprint = assetOrderFingerprint,
        sessionVersion = sessionVersion,
        sourceAssetCount = sourceAssetCount,
        requestCanonicalFingerprint = requestCanonicalFingerprint,
        mergedAtEpochMillis = mergedAtEpochMillis,
    )

internal fun TutorEvidenceRequest.withLearningEvidenceSession(
    session: TutorLearningEvidenceSessionRecord,
): TutorEvidenceRequest {
    if (
        evidenceRequestId != session.evidenceRequestId ||
        conversationId != session.conversationId ||
        conversationGeneration != session.conversationGeneration ||
        conversationStateVersion != session.conversationStateVersion ||
        turnReceiptId != session.turnReceiptId ||
        turnOrdinal != session.turnOrdinal ||
        subject != session.subject ||
        problemAnchorId != session.sessionAnchorId ||
        kind != session.evidenceKind ||
        requestVersion != session.requestVersion ||
        modeVersion != session.modeVersion
    ) {
        throw TutorLearningEvidenceSessionConflictException(
            "Tutor evidence request does not match its durable mastery session",
        )
    }
    return when (session.state) {
        TutorLearningEvidenceSessionRecordState.PENDING_MASTERY ->
            copy(
                status = TutorEvidenceRequestStatus.PENDING,
                stateVersion = session.stateVersion,
                resolvedAtEpochMillis = null,
                terminalReceiptId = null,
            )

        TutorLearningEvidenceSessionRecordState.MASTERY_ACKNOWLEDGED ->
            copy(
                status = TutorEvidenceRequestStatus.SUBMITTED,
                stateVersion = session.stateVersion,
                resolvedAtEpochMillis = checkNotNull(session.acknowledgedAtEpochMillis),
                terminalReceiptId = checkNotNull(session.masteryReceiptId),
            )
    }
}

internal fun ProblemDraftRecord.captureAssetOrderFingerprint(): String {
    val canonical =
        CanonicalSha256("capture-draft-asset-order-v1")
            .field("draftSessionId", draftId)
            .field("assetCount", sourceAssets.size)
    sourceAssets.forEach { page ->
        canonical
            .field("pageIndex", page.pageIndex)
            .field("assetId", page.sourceAsset.sourceAssetId)
            .field("contentSha256", page.sourceAsset.contentSha256)
    }
    return canonical.finish()
}

internal fun captureDraftSessionVersion(
    revisionNumber: Int,
    sourceAssetCount: Int,
): Long {
    require(revisionNumber > 0)
    require(sourceAssetCount in 1..8)
    return (revisionNumber.toLong() shl 8) or sourceAssetCount.toLong()
}

internal fun MistakeRow.toRecord() = MistakeRecord(
    entryId = entryId,
    problemId = problemId,
    problemRevisionId = problemRevisionId,
    practiceUnitId = practiceUnitId,
    sourceKey = sourceKey,
    subject = subject,
    title = title,
    problemMarkdown = problemMarkdown,
    status = status,
    createdAtEpochMillis = createdAtEpochMillis,
    nextReviewAtEpochMillis = nextReviewAtEpochMillis,
    retrievability = retrievability,
    estimatedSeconds = estimatedSeconds,
    knowledgeNodeIds = knowledgeNodeIds.toCatalogLabels().toCollection(linkedSetOf()),
    chapterLabels = chapterLabels.toCatalogLabels(),
    knowledgeLabels = knowledgeLabels.toCatalogLabels(),
    captureOccurrenceCount = maxOf(1, captureOccurrenceCount),
)

internal fun String?.toCatalogLabels(): List<String> = this
    ?.split("\u001F")
    ?.map(String::trim)
    ?.filter(String::isNotEmpty)
    ?.distinct()
    ?.sorted()
    .orEmpty()
