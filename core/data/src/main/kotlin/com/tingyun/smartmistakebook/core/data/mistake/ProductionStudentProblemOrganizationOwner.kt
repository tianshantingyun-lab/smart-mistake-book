package com.tingyun.smartmistakebook.core.data.mistake

import com.tingyun.smartmistakebook.core.data.knowledge.ReviewedProblemKnowledgeContextRepository
import com.tingyun.smartmistakebook.core.data.knowledge.ReviewedProblemKnowledgeReferenceBatchRequest
import com.tingyun.smartmistakebook.core.data.production.ProductionProblemOrganizationExecutionRevokedException
import com.tingyun.smartmistakebook.core.data.session.LegacyProblemOrganizationWorkSessionAdapter
import com.tingyun.smartmistakebook.core.data.session.OrganizationSourceCommitSessionReceipt
import com.tingyun.smartmistakebook.core.data.session.ProblemOrganizationWorkSessionReadQuery
import com.tingyun.smartmistakebook.core.data.session.ProblemOrganizationWorkSessionStatus
import com.tingyun.smartmistakebook.core.data.session.SessionScope
import com.tingyun.smartmistakebook.core.database.LegacyModelAssetDocumentReadPort
import com.tingyun.smartmistakebook.core.database.LegacyOrganizationWorkCoordinationPort
import com.tingyun.smartmistakebook.core.database.ModelTaskDatabasePort
import com.tingyun.smartmistakebook.core.database.StudyDbValue
import com.tingyun.smartmistakebook.core.database.TrustedModelTaskDatabaseCapability
import com.tingyun.smartmistakebook.core.database.TrustedOrganizationWorkDatabaseCapability
import com.tingyun.smartmistakebook.core.domain.MistakeOrganizationPreparation
import com.tingyun.smartmistakebook.core.model.AtomicKnowledgeSuggestion
import com.tingyun.smartmistakebook.core.model.CanonicalSha256
import com.tingyun.smartmistakebook.core.model.CaptureSourceAssetRef
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocument
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocumentFingerprint
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocumentValidator
import com.tingyun.smartmistakebook.core.model.ClassificationDimension
import com.tingyun.smartmistakebook.core.model.KnowledgeBaseNodeContext
import com.tingyun.smartmistakebook.core.model.KnowledgeNodeGranularity
import com.tingyun.smartmistakebook.core.model.KnowledgeNodeVerificationStatus
import com.tingyun.smartmistakebook.core.model.ModelEgressAssetGrant
import com.tingyun.smartmistakebook.core.model.ModelEgressAuthorizationId
import com.tingyun.smartmistakebook.core.model.ModelEgressManifest
import com.tingyun.smartmistakebook.core.model.ModelEgressPurpose
import com.tingyun.smartmistakebook.core.model.ModelExecutionLocation
import com.tingyun.smartmistakebook.core.model.ModelPromptPolicyVersions
import com.tingyun.smartmistakebook.core.model.ModelTaskCompletionValidator
import com.tingyun.smartmistakebook.core.model.ModelTaskFingerprint
import com.tingyun.smartmistakebook.core.model.ModelTaskKind
import com.tingyun.smartmistakebook.core.model.ModelTaskLogicalOperationFingerprint
import com.tingyun.smartmistakebook.core.model.ModelTaskRequest
import com.tingyun.smartmistakebook.core.model.ModelTaskSnapshot
import com.tingyun.smartmistakebook.core.model.ModelTaskStatus
import com.tingyun.smartmistakebook.core.model.ProblemOrganizationAuthorizationGrant
import com.tingyun.smartmistakebook.core.model.ProblemOrganizationOutput
import com.tingyun.smartmistakebook.core.model.ProblemOrganizationPlan
import com.tingyun.smartmistakebook.core.model.ProblemOrganizationV3Input
import com.tingyun.smartmistakebook.core.model.ProblemErrorAttributionResolutionStatus
import com.tingyun.smartmistakebook.core.model.ProviderCapabilitySnapshot
import com.tingyun.smartmistakebook.core.model.QuestionDocumentMarkdownProjection
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.storage.StudentProblemRevisionRef
import com.tingyun.smartmistakebook.core.model.storage.VerifiedKnowledgeReferenceProof
import com.tingyun.smartmistakebook.core.student.mistake.database.LearnerBoundStudentProblemOrganizationSourcePort
import com.tingyun.smartmistakebook.core.student.mistake.database.OrganizeStudentProblemCommand
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentProblemErrorOccurrenceRef
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentProblemOrganizationConflictException
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentProblemOrganizationCommitFence
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentProblemOrganizationKnowledgeSnapshot
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentProblemOrganizationPort
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentProblemOrganizationReceipt
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentProblemOrganizationSourceSnapshot
import java.util.Locale
import kotlin.math.min
import kotlinx.coroutines.CancellationException

/** Immutable, URI-free source resolved from both the legacy commit receipt and student authority. */
internal data class CommittedStudentProblemOrganizationSource(
    val sourceReceiptId: String,
    val sourceReceiptPayloadFingerprint: String,
    val draftId: String,
    val problemRevision: StudentProblemRevisionRef,
    val capturedDocument: CapturedQuestionDocument,
    val sourceAssets: List<CaptureSourceAssetRef>,
    val egressAssets: List<ModelEgressAssetGrant>,
    val errorOccurrences: List<StudentProblemErrorOccurrenceRef>,
    val committedAtEpochMillis: Long,
) {
    init {
        require(sourceReceiptId.isOwnerIdentifier()) { "Organization source receipt is invalid" }
        require(sourceReceiptPayloadFingerprint.isSha256()) {
            "Organization source receipt fingerprint is invalid"
        }
        require(draftId.isOwnerIdentifier()) { "Organization source draft is invalid" }
        require(problemRevision.problem.subject != SubjectKind.GENERAL) {
            "Organization source must use one high-school subject"
        }
        require(
            CapturedQuestionDocumentFingerprint.of(capturedDocument) ==
                problemRevision.documentCanonicalFingerprint,
        ) { "Organization source document does not match its student revision" }
        require(CapturedQuestionDocumentValidator.validateForCommit(capturedDocument).isEmpty()) {
            "Organization source is not a fully committed captured document"
        }
        require(sourceAssets.isNotEmpty() && sourceAssets.size == egressAssets.size) {
            "Organization source assets are incomplete"
        }
        require(
            sourceAssets.zip(egressAssets).all { (source, grant) ->
                source.assetId == grant.assetId &&
                    source.sha256 == grant.sha256 &&
                    source.width == grant.width &&
                    source.height == grant.height &&
                    source.selectedRegion == grant.selectedRegion
            },
        ) { "Organization source egress grants do not match its exact assets" }
        require(
            errorOccurrences.isNotEmpty() &&
                errorOccurrences.map(StudentProblemErrorOccurrenceRef::occurrenceId) ==
                    errorOccurrences.map(StudentProblemErrorOccurrenceRef::occurrenceId).sorted() &&
                errorOccurrences.map(StudentProblemErrorOccurrenceRef::occurrenceId).distinct().size ==
                    errorOccurrences.size &&
                errorOccurrences.all { occurrence ->
                    occurrence.problemRevision == problemRevision
                },
        ) { "Organization source errors do not match its student revision" }
        require(committedAtEpochMillis >= 0) { "Organization source time is invalid" }
    }

    val subject: SubjectKind
        get() = problemRevision.problem.subject

    val questionText: String
        get() = QuestionDocumentMarkdownProjection.project(capturedDocument.document)

    val canonicalFingerprint: String =
        CanonicalSha256(SOURCE_FINGERPRINT_DOMAIN)
            .field("sourceReceiptId", sourceReceiptId)
            .field("sourceReceiptPayloadFingerprint", sourceReceiptPayloadFingerprint)
            .field("draftId", draftId)
            .field("problemRevision", problemRevision.canonicalFingerprint)
            .field(
                "capturedDocumentFingerprint",
                CapturedQuestionDocumentFingerprint.of(capturedDocument),
            )
            .field("sourceAssetCount", sourceAssets.size)
            .apply {
                sourceAssets.forEachIndexed { index, asset ->
                    field("sourceAsset[$index].id", asset.assetId)
                    field("sourceAsset[$index].sha256", asset.sha256)
                    field("sourceAsset[$index].width", asset.width)
                    field("sourceAsset[$index].height", asset.height)
                    field("sourceAsset[$index].pageIndex", asset.pageIndex)
                    nullableField(
                        "sourceAsset[$index].region.left",
                        asset.selectedRegion?.left?.toString(),
                    )
                    nullableField(
                        "sourceAsset[$index].region.top",
                        asset.selectedRegion?.top?.toString(),
                    )
                    nullableField(
                        "sourceAsset[$index].region.right",
                        asset.selectedRegion?.right?.toString(),
                    )
                    nullableField(
                        "sourceAsset[$index].region.bottom",
                        asset.selectedRegion?.bottom?.toString(),
                    )
                }
            }
            .field("egressAssetCount", egressAssets.size)
            .apply {
                egressAssets.forEachIndexed { index, asset ->
                    field("egressAsset[$index].id", asset.assetId)
                    field("egressAsset[$index].sha256", asset.sha256)
                    field("egressAsset[$index].byteSize", asset.byteSize)
                    field("egressAsset[$index].width", asset.width)
                    field("egressAsset[$index].height", asset.height)
                }
            }
            .field("errorOccurrenceCount", errorOccurrences.size)
            .apply {
                errorOccurrences.forEachIndexed { index, occurrence ->
                    field("errorOccurrence[$index]", occurrence.canonicalFingerprint)
                }
            }
            .field("committedAtEpochMillis", committedAtEpochMillis)
            .finish()
}

internal fun interface CommittedStudentProblemOrganizationSourcePort {
    suspend fun readExact(
        sourceReceipt: OrganizationSourceCommitSessionReceipt,
    ): CommittedStudentProblemOrganizationSource?
}

/** Read-only terminal-task lookup. It has no execute, cancel, provider, asset, or write method. */
internal fun interface CompletedStudentProblemOrganizationTaskReadPort {
    suspend fun read(requestId: String): ModelTaskSnapshot?
}

/** Revalidates the exact running lease immediately before the student-authority write. */
internal fun interface StudentProblemOrganizationLeaseGuard {
    suspend fun isStillHeld(
        command: ReviewAndWriteStudentProblemOrganizationCommand,
    ): Boolean
}

internal object ProductionStudentProblemOrganizationOwnerFactory {
    fun create(
        learnerId: String,
        studentSources: LearnerBoundStudentProblemOrganizationSourcePort,
        studentOrganizations: StudentProblemOrganizationPort,
        reviewPort: ReviewedStudentProblemOrganizationReviewPort,
        organizationWork: TrustedOrganizationWorkDatabaseCapability,
        modelTasksAndAssetDocuments: TrustedModelTaskDatabaseCapability,
        knowledgeContext: ReviewedProblemKnowledgeContextRepository,
        clock: () -> Long = System::currentTimeMillis,
    ): StudentProblemOrganizationOwnerPort {
        require(
            learnerId == studentSources.learnerId &&
                learnerId == studentOrganizations.learnerId,
        ) {
            "Production organization capabilities must share one learner"
        }
        val scope = SessionScope(learnerId)
        return createFromNarrowPorts(
            scope = scope,
            sources =
                LegacyReceiptStudentProblemOrganizationSourcePort(
                    organizationWork = organizationWork,
                    assetDocuments = modelTasksAndAssetDocuments,
                    studentSources = studentSources,
                ),
            completedTasks =
                ControlledCompletedStudentProblemOrganizationTaskReadPort(
                    modelTasksAndAssetDocuments,
                ),
            leaseGuard =
                LegacyStudentProblemOrganizationLeaseGuard(
                    scope = scope,
                    organizationWork = organizationWork,
                ),
            knowledgeContext = knowledgeContext,
            reviewPort = reviewPort,
            studentOrganizations = studentOrganizations,
            clock = clock,
        )
    }

    internal fun createFromNarrowPorts(
        scope: SessionScope,
        sources: CommittedStudentProblemOrganizationSourcePort,
        completedTasks: CompletedStudentProblemOrganizationTaskReadPort,
        leaseGuard: StudentProblemOrganizationLeaseGuard,
        knowledgeContext: ReviewedProblemKnowledgeContextRepository,
        reviewPort: ReviewedStudentProblemOrganizationReviewPort,
        studentOrganizations: StudentProblemOrganizationPort,
        clock: () -> Long,
    ): StudentProblemOrganizationOwnerPort {
        require(scope.learnerId == studentOrganizations.learnerId) {
            "Production organization owner crosses the student write capability"
        }
        return ProductionStudentProblemOrganizationOwner(
            scope = scope,
            sources = sources,
            completedTasks = completedTasks,
            leaseGuard = leaseGuard,
            knowledgeContext = knowledgeContext,
            reviewPort = reviewPort,
            studentOrganizations = studentOrganizations,
            clock = clock,
        )
    }
}

private class LegacyStudentProblemOrganizationLeaseGuard(
    scope: SessionScope,
    organizationWork: LegacyOrganizationWorkCoordinationPort,
) : StudentProblemOrganizationLeaseGuard {
    private val workSessions =
        LegacyProblemOrganizationWorkSessionAdapter(scope, organizationWork)

    override suspend fun isStillHeld(
        command: ReviewAndWriteStudentProblemOrganizationCommand,
    ): Boolean {
        val work =
            workSessions.read(
                ProblemOrganizationWorkSessionReadQuery.ByWorkId(
                    command.scope,
                    command.workId,
                ),
            ) ?: return false
        return work.scope == command.scope &&
            work.workId == command.workId &&
            work.sourceCommitReceiptId == command.sourceReceipt.receiptId &&
            work.status == ProblemOrganizationWorkSessionStatus.RUNNING &&
            work.version.sequence == command.expectedWorkVersionSequence &&
            work.version.fingerprint == command.expectedWorkVersionFingerprint &&
            work.requestId == command.requestId &&
            work.leaseOwner == command.leaseOwner &&
            work.leaseExpiresAtEpochMillis == command.leaseExpiresAtEpochMillis
    }
}

private class LegacyReceiptStudentProblemOrganizationSourcePort(
    private val organizationWork: LegacyOrganizationWorkCoordinationPort,
    private val assetDocuments: LegacyModelAssetDocumentReadPort,
    private val studentSources: LearnerBoundStudentProblemOrganizationSourcePort,
) : CommittedStudentProblemOrganizationSourcePort {
    override suspend fun readExact(
        sourceReceipt: OrganizationSourceCommitSessionReceipt,
    ): CommittedStudentProblemOrganizationSource? {
        if (sourceReceipt.scope.learnerId != studentSources.learnerId) return null
        val receipt =
            organizationWork.readProblemOrganizationWorkCommitReceipt(sourceReceipt.receiptId)
                ?: return null
        if (
            receipt.commandId != sourceReceipt.receiptId ||
            receipt.payloadFingerprint != sourceReceipt.payloadFingerprint ||
            receipt.committedAtEpochMillis != sourceReceipt.recordedAtEpochMillis
        ) {
            return null
        }
        val draft = assetDocuments.readProblemDraft(receipt.draftId) ?: return null
        if (
            draft.draftId != receipt.draftId ||
            draft.status != StudyDbValue.ProblemDraftStatus.COMMITTED ||
            draft.currentRevision.draftId != receipt.draftId ||
            draft.currentRevision.revisionNumber != receipt.draftRevisionNumber ||
            CapturedQuestionDocumentFingerprint.of(draft.currentRevision.questionDocument) !=
                draft.currentRevision.documentFingerprint
        ) {
            return null
        }
        val subject =
            draft.currentRevision.subject
                ?.let { value -> runCatching { SubjectKind.valueOf(value.uppercase(Locale.ROOT)) }.getOrNull() }
                ?.takeUnless { it == SubjectKind.GENERAL }
                ?: return null
        val student = studentSources.readByDraftId(receipt.draftId) ?: return null
        if (!student.matches(receipt.draftRevisionNumber, receipt.committedAtEpochMillis)) {
            return null
        }
        val problem = student.problem
        if (
            problem.revision.problem.problemId != receipt.problemId ||
            problem.revision.revisionId != receipt.problemRevisionId ||
            problem.revision.problem.practiceUnitId != receipt.practiceUnitId ||
            problem.errorBookEntryId != receipt.errorBookEntryId ||
            problem.revision.problem.subject != subject ||
            problem.revision.documentCanonicalFingerprint != draft.currentRevision.documentFingerprint ||
            problem.capturedQuestionDocument != draft.currentRevision.questionDocument ||
            problem.originalImages.size != draft.sourceAssets.size
        ) {
            return null
        }
        val assets =
            draft.sourceAssets.mapIndexed { index, source ->
                val legacy = source.sourceAsset
                val studentImage = problem.originalImages.getOrNull(index) ?: return null
                if (
                    source.pageIndex != index ||
                    studentImage.ordinal != index ||
                    studentImage.contentCanonicalFingerprint != legacy.contentSha256 ||
                    studentImage.widthPixels != legacy.width ||
                    studentImage.heightPixels != legacy.height ||
                    studentImage.byteSize != legacy.byteSize
                ) {
                    return null
                }
                CaptureSourceAssetRef(
                    assetId = legacy.sourceAssetId,
                    sha256 = legacy.contentSha256,
                    width = legacy.width,
                    height = legacy.height,
                    pageIndex = index,
                )
            }
        val grants =
            draft.sourceAssets.map { source ->
                val asset = source.sourceAsset
                ModelEgressAssetGrant(
                    assetId = asset.sourceAssetId,
                    sha256 = asset.contentSha256,
                    byteSize = asset.byteSize,
                    width = asset.width,
                    height = asset.height,
                )
            }
        return CommittedStudentProblemOrganizationSource(
            sourceReceiptId = receipt.commandId,
            sourceReceiptPayloadFingerprint = receipt.payloadFingerprint,
            draftId = receipt.draftId,
            problemRevision = problem.revision,
            capturedDocument = checkNotNull(problem.capturedQuestionDocument),
            sourceAssets = assets,
            egressAssets = grants,
            errorOccurrences = student.errorOccurrences,
            committedAtEpochMillis = receipt.committedAtEpochMillis,
        )
    }
}

private class ControlledCompletedStudentProblemOrganizationTaskReadPort(
    private val modelTasks: ModelTaskDatabasePort,
) : CompletedStudentProblemOrganizationTaskReadPort {
    override suspend fun read(requestId: String): ModelTaskSnapshot? {
        require(requestId.isOwnerIdentifier()) { "Organization task request id is invalid" }
        return modelTasks.readModelTask(requestId)
    }
}

private class ProductionStudentProblemOrganizationOwner(
    override val scope: SessionScope,
    private val sources: CommittedStudentProblemOrganizationSourcePort,
    private val completedTasks: CompletedStudentProblemOrganizationTaskReadPort,
    private val leaseGuard: StudentProblemOrganizationLeaseGuard,
    private val knowledgeContext: ReviewedProblemKnowledgeContextRepository,
    private val reviewPort: ReviewedStudentProblemOrganizationReviewPort,
    private val studentOrganizations: StudentProblemOrganizationPort,
    private val clock: () -> Long,
) : StudentProblemOrganizationOwnerPort {
    override suspend fun prepareCommitted(
        command: PrepareStudentProblemOrganizationCommand,
    ): PreparedStudentProblemOrganization {
        command.scope.requireOwnerScope(scope)
        require(command.provider.supports(ModelTaskKind.PROBLEM_CLASSIFY)) {
            "Current provider does not support student problem organization"
        }
        require(command.provider.supportsImageInput) {
            "Student problem organization requires image input"
        }
        require(command.provider.supportsStructuredOutput) {
            "Student problem organization requires structured output"
        }
        require(command.authorization.matchesCurrent(command.provider, command.occurredAtEpochMillis)) {
            "Student problem organization authorization is not current"
        }
        val source = requireNotNull(sources.readExact(command.sourceReceipt)) {
            "Committed student organization source was not found"
        }
        require(command.occurredAtEpochMillis >= source.committedAtEpochMillis) {
            "Student organization request predates its committed source"
        }
        source.requireExactAuthorization(command.authorization)
        val contexts = knowledgeContext.read(source.subject, source.questionText)
        require(contexts.isNotEmpty()) {
            "Committed student organization has no reviewed knowledge context"
        }
        val preparationProofs =
            knowledgeContext.verifyExactReferences(
                ReviewedProblemKnowledgeReferenceBatchRequest(
                    subject = source.subject,
                    knowledgeBaseNodes = contexts,
                    exactKnowledgeNodeIds = contexts.map(KnowledgeBaseNodeContext::knowledgeNodeId),
                ),
            )
        val preparationKnowledgeIds =
            contexts.map(KnowledgeBaseNodeContext::knowledgeNodeId)
        val knowledgeSnapshot =
            preparationProofs.requireExactSnapshot(
                subject = source.subject,
                contexts = contexts,
                expectedKnowledgeNodeIds = preparationKnowledgeIds,
            )
        val input =
            ProblemOrganizationV3Input(
                problemId = source.problemRevision.problem.problemId,
                problemRevisionId = source.problemRevision.revisionId,
                practiceUnitId = source.problemRevision.problem.practiceUnitId,
                subject = source.subject,
                capturedDocument = source.capturedDocument,
                sourceAssets = source.sourceAssets,
                relationCandidates = emptyList(),
                knowledgeBaseNodes = contexts,
            )
        val requestId =
            organizationRequestId(
                workId = command.workId,
                source = source,
                input = input,
                provider = command.provider,
                requestedAtEpochMillis = command.occurredAtEpochMillis,
                knowledgeSnapshot = knowledgeSnapshot,
            )
        val request =
            ModelTaskRequest(
                schemaVersion = ModelTaskRequest.PROBLEM_ORGANIZATION_V3_SCHEMA_VERSION,
                requestId = requestId,
                input = input,
                occurredAtEpochMillis = command.occurredAtEpochMillis,
                egressManifest = command.authorization.toEgressManifest(requestId, input),
            )
        return PreparedStudentProblemOrganization(
            sourceReceiptId = source.sourceReceiptId,
            sourceReceiptPayloadFingerprint = source.sourceReceiptPayloadFingerprint,
            preparation =
                MistakeOrganizationPreparation(
                    request = request,
                    relatedCandidateTitles = emptyList(),
                    knowledgeContextCount = contexts.size,
                ),
        )
    }

    override suspend fun reviewAndWrite(
        command: ReviewAndWriteStudentProblemOrganizationCommand,
        requireCurrentExecution: () -> Unit,
        commitFence: StudentProblemOrganizationCommitFence,
    ): StudentProblemOrganizationReviewWriteOutcome {
        command.scope.requireOwnerScope(scope)
        return try {
            reviewAndWriteExact(command, requireCurrentExecution, commitFence)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (revoked: ProductionProblemOrganizationExecutionRevokedException) {
            throw revoked
        } catch (rejected: OrganizationReviewRejected) {
            terminal(rejected.reasonCode)
        } catch (unavailable: OrganizationReviewUnavailable) {
            retryable(unavailable.reasonCode)
        } catch (_: StudentProblemOrganizationConflictException) {
            terminal("student-organization-conflict")
        } catch (_: IllegalArgumentException) {
            terminal("student-organization-review-rejected")
        } catch (_: IllegalStateException) {
            terminal("student-organization-review-rejected")
        } catch (_: Exception) {
            retryable("student-organization-owner-unavailable")
        }
    }

    private suspend fun reviewAndWriteExact(
        command: ReviewAndWriteStudentProblemOrganizationCommand,
        requireCurrentExecution: () -> Unit,
        commitFence: StudentProblemOrganizationCommitFence,
    ): StudentProblemOrganizationReviewWriteOutcome.Applied {
        requireCurrentExecution()
        val initialNow = clock()
        if (initialNow >= command.leaseExpiresAtEpochMillis) reject("organization-lease-expired")
        if (
            !guardedStudentOrganizationExecutionBoundary(requireCurrentExecution) {
                leaseGuard.isStillHeld(command)
            }
        ) {
            reject("organization-lease-not-held")
        }
        val source =
            guardedStudentOrganizationExecutionBoundary(requireCurrentExecution) {
                sources.readExact(command.sourceReceipt)
            } ?: reject("organization-source-missing")
        if (
            source.problemRevision.problem.learnerId != scope.learnerId ||
            source.sourceReceiptId != command.sourceReceipt.receiptId ||
            source.sourceReceiptPayloadFingerprint != command.sourceReceipt.payloadFingerprint
        ) {
            reject("organization-source-scope-mismatch")
        }
        val task =
            guardedStudentOrganizationExecutionBoundary(requireCurrentExecution) {
                completedTasks.read(command.requestId)
            } ?: unavailable("organization-task-not-visible")
        if (ModelTaskFingerprint.of(task.request) != command.requestCanonicalFingerprint) {
            reject("organization-task-request-mismatch")
        }
        if (task.status != ModelTaskStatus.SUCCEEDED) {
            if (!task.status.isTerminal) unavailable("organization-task-not-terminal")
            reject("organization-task-unsuccessful")
        }
        if (
            task.requestFingerprint != command.requestCanonicalFingerprint ||
            task.request.requestId != command.requestId ||
            task.request.schemaVersion != ModelTaskRequest.PROBLEM_ORGANIZATION_V3_SCHEMA_VERSION ||
            task.request.occurredAtEpochMillis < source.committedAtEpochMillis ||
            task.createdAtEpochMillis < task.request.occurredAtEpochMillis ||
            task.createdAtEpochMillis > initialNow ||
            task.updatedAtEpochMillis > initialNow ||
            task.updatedAtEpochMillis >= command.leaseExpiresAtEpochMillis
        ) {
            reject("organization-task-late-or-stale")
        }
        val input = task.request.input as? ProblemOrganizationV3Input
            ?: reject("organization-task-not-v3")
        val output = task.output as? ProblemOrganizationOutput
            ?: reject("organization-task-output-missing")
        try {
            ModelTaskCompletionValidator.requireValid(task.request, output)
        } catch (revoked: ProductionProblemOrganizationExecutionRevokedException) {
            throw revoked
        } catch (_: Exception) {
            reject("organization-task-completion-invalid")
        }
        val provider = task.provider ?: reject("organization-provider-missing")
        if (
            !provider.supports(ModelTaskKind.PROBLEM_CLASSIFY) ||
            !provider.supportsImageInput ||
            !provider.supportsStructuredOutput ||
            provider.executionLocation != ModelExecutionLocation.EXTERNAL_PROVIDER
        ) {
            reject("organization-provider-capability-mismatch")
        }
        source.requireExactTaskInput(input)
        task.requireExactEgress(source, input, provider)
        val selectedKnowledgeIds = input.requireLocallyAcceptable(output)

        val existing =
            guardedStudentOrganizationExecutionBoundary(requireCurrentExecution) {
                studentOrganizations.readReceipt(
                    command.requestId,
                    command.requestCanonicalFingerprint,
                )
            }
        if (existing != null) {
            val persistedKnowledge =
                guardedStudentOrganizationExecutionBoundary(requireCurrentExecution) {
                    studentOrganizations.readKnowledgeSnapshot(existing.receiptId)
                } ?: reject("organization-replay-knowledge-missing")
            existing.requireExactReplay(source, task, persistedKnowledge)
            command.requireDeterministicRequestId(
                source = source,
                input = input,
                provider = provider,
                requestedAtEpochMillis = task.request.occurredAtEpochMillis,
                knowledge = persistedKnowledge,
            )
            return applied(
                receipt = existing,
                source = source,
                knowledge = persistedKnowledge,
                verifiedReferenceCount = existing.classificationCount,
            )
        }

        val proofs =
            guardedStudentOrganizationExecutionBoundary(requireCurrentExecution) {
                try {
                    knowledgeContext.verifyExactReferences(
                        ReviewedProblemKnowledgeReferenceBatchRequest(
                            subject = input.subject,
                            knowledgeBaseNodes = input.knowledgeBaseNodes,
                            exactKnowledgeNodeIds = selectedKnowledgeIds,
                        ),
                    )
                } catch (failure: IllegalArgumentException) {
                    reject("organization-knowledge-reference-invalid")
                } catch (revoked: ProductionProblemOrganizationExecutionRevokedException) {
                    throw revoked
                } catch (failure: IllegalStateException) {
                    reject("organization-knowledge-reference-stale")
                } catch (failure: Exception) {
                    unavailable("organization-knowledge-unavailable")
                }
            }
        val knowledgeSnapshot =
            proofs.requireExactSnapshot(
                subject = input.subject,
                contexts = input.knowledgeBaseNodes,
                expectedKnowledgeNodeIds = selectedKnowledgeIds,
            )
        command.requireDeterministicRequestId(
            source = source,
            input = input,
            provider = provider,
            requestedAtEpochMillis = task.request.occurredAtEpochMillis,
            knowledge = knowledgeSnapshot,
        )

        val current =
            guardedStudentOrganizationExecutionBoundary(requireCurrentExecution) {
                studentOrganizations.readCurrent(source.problemRevision)
            }
        val reviewIssuedAt = clock()
        if (
            reviewIssuedAt < task.updatedAtEpochMillis ||
            reviewIssuedAt >= command.leaseExpiresAtEpochMillis
        ) {
            reject("organization-review-after-lease")
        }
        val reviewExpiresAt =
            min(
                command.leaseExpiresAtEpochMillis,
                Math.addExact(reviewIssuedAt, REVIEW_PROOF_TTL_MILLIS),
            )
        if (reviewExpiresAt <= reviewIssuedAt) reject("organization-review-window-closed")
        val local =
            com.tingyun.smartmistakebook.core.student.mistake.database
                .ReviewedStudentProblemOrganizationLocalContext(
                    receiptId = organizationReceiptId(command.requestId, source.problemRevision),
                    organizationRevision = (current?.organizationRevision ?: 0) + 1,
                    supersedesReceiptId = current?.receiptId,
                    previousPayloadCanonicalFingerprint = current?.payloadCanonicalFingerprint,
                    problemRevision = source.problemRevision,
                    errorOccurrences = source.errorOccurrences,
                    reviewIssuedAtEpochMillis = reviewIssuedAt,
                    reviewExpiresAtEpochMillis = reviewExpiresAt,
                    completedAtEpochMillis = reviewIssuedAt,
                )
        val organizedCommand =
            guardedStudentOrganizationExecutionBoundary(requireCurrentExecution) {
                reviewPort.mapAndReview(
                    local = local,
                    reviewedTask = task,
                    verifiedKnowledgeReferences = proofs,
                )
            }
        organizedCommand.requireOwnerPrepared(source, command, knowledgeSnapshot)

        // Re-read both the immutable source and the organization head immediately before write.
        // A changed capture handoff/revision or a concurrent organization revision is a zero-write
        // rejection; only the student database performs the final idempotent insert.
        val sourceBeforeWrite =
            guardedStudentOrganizationExecutionBoundary(requireCurrentExecution) {
                sources.readExact(command.sourceReceipt)
            } ?: reject("organization-source-disappeared")
        if (sourceBeforeWrite.canonicalFingerprint != source.canonicalFingerprint) {
            reject("organization-source-changed")
        }
        if (
            guardedStudentOrganizationExecutionBoundary(requireCurrentExecution) {
                studentOrganizations.readCurrent(source.problemRevision)
            } != current
        ) {
            reject("organization-head-changed")
        }
        if (
            !guardedStudentOrganizationExecutionBoundary(requireCurrentExecution) {
                leaseGuard.isStillHeld(command)
            }
        ) {
            reject("organization-lease-lost-before-write")
        }
        if (clock() >= command.leaseExpiresAtEpochMillis) {
            reject("organization-lease-expired-before-write")
        }
        val result =
            guardedStudentOrganizationExecutionBoundary(requireCurrentExecution) {
                studentOrganizations.organize(
                    command = organizedCommand,
                    commitFence = commitFence,
                )
            }
        result.receipt.requireExactWrite(organizedCommand)
        return applied(
            receipt = result.receipt,
            source = source,
            knowledge = knowledgeSnapshot,
            verifiedReferenceCount = proofs.size,
        )
    }
}

private suspend inline fun <Result> guardedStudentOrganizationExecutionBoundary(
    requireCurrentExecution: () -> Unit,
    crossinline operation: suspend () -> Result,
): Result {
    requireCurrentExecution()
    val result = operation()
    requireCurrentExecution()
    return result
}

private fun StudentProblemOrganizationSourceSnapshot.matches(
    expectedDraftRevision: Int,
    expectedCommittedAtEpochMillis: Long,
): Boolean =
    source.draftRevisionNumber == expectedDraftRevision &&
        source.occurredAtEpochMillis == expectedCommittedAtEpochMillis &&
        errorOccurrences.all { occurrence -> occurrence.problemRevision == problem.revision }

private fun CommittedStudentProblemOrganizationSource.requireExactAuthorization(
    authorization: ProblemOrganizationAuthorizationGrant,
) {
    require(
        authorization.sourceDraftId == draftId &&
            authorization.assets == egressAssets,
    ) {
        "Problem organization authorization does not match the committed student source"
    }
}

private fun CommittedStudentProblemOrganizationSource.requireExactTaskInput(
    input: ProblemOrganizationV3Input,
) {
    if (
        input.problemId != problemRevision.problem.problemId ||
        input.problemRevisionId != problemRevision.revisionId ||
        input.practiceUnitId != problemRevision.problem.practiceUnitId ||
        input.subject != subject ||
        input.capturedDocument != capturedDocument ||
        CapturedQuestionDocumentFingerprint.of(input.capturedDocument) !=
            problemRevision.documentCanonicalFingerprint ||
        input.sourceAssets != sourceAssets ||
        input.relationCandidates.isNotEmpty()
    ) {
        reject("organization-input-source-mismatch")
    }
}

private fun ModelTaskSnapshot.requireExactEgress(
    source: CommittedStudentProblemOrganizationSource,
    input: ProblemOrganizationV3Input,
    provider: ProviderCapabilitySnapshot,
) {
    val egress = request.egressManifest ?: reject("organization-egress-missing")
    if (
        provider.providerId != egress.providerId ||
        provider.modelId != egress.modelId ||
        provider.providerConfigurationVersion != egress.providerConfigurationVersion ||
        egress.schemaVersion != ModelEgressManifest.CURRENT_SCHEMA_VERSION ||
        egress.approvedAtEpochMillis > request.occurredAtEpochMillis ||
        egress.authorizationId != ModelEgressAuthorizationId.forInput(request.requestId, input) ||
        egress.subjectId != input.subjectId ||
        egress.purpose != ModelEgressPurpose.CLASSIFICATION ||
        egress.authorizedTaskKinds != setOf(ModelTaskKind.PROBLEM_CLASSIFY) ||
        egress.promptPolicyVersion != ModelPromptPolicyVersions.PROBLEM_ORGANIZATION ||
        egress.assets != source.egressAssets
    ) {
        reject("organization-egress-mismatch")
    }
}

private fun ProblemOrganizationV3Input.requireLocallyAcceptable(
    output: ProblemOrganizationOutput,
): List<String> {
    val plan = output.plan
    if (
        output.problemId != problemId ||
        output.problemRevisionId != problemRevisionId ||
        output.practiceUnitId != practiceUnitId ||
        plan.schemaVersion != ProblemOrganizationPlan.SCHEMA_VERSION ||
        plan.groundingRequests.isNotEmpty() ||
        plan.atomicKnowledge.isEmpty() ||
        plan.errorAttributionCandidates.any { candidate ->
            candidate.resolutionStatus == ProblemErrorAttributionResolutionStatus.RESOLVED &&
                candidate.confidence < CLASSIFICATION_ACCEPTANCE_CONFIDENCE
        }
    ) {
        reject("organization-output-incomplete")
    }
    val normalizedClassifications =
        plan.classifications.map { suggestion ->
            suggestion.dimension to suggestion.displayName.normalizedOrganizationName()
        }
    if (normalizedClassifications.distinct().size != normalizedClassifications.size) {
        reject("organization-classification-duplicate")
    }
    val chapter =
        plan.classifications.singleOrNull { it.dimension == ClassificationDimension.CHAPTER }
            ?: reject("organization-chapter-ambiguous")
    if (chapter.confidence < CLASSIFICATION_ACCEPTANCE_CONFIDENCE) {
        reject("organization-chapter-low-confidence")
    }
    if (
        plan.classifications.none { suggestion ->
            suggestion.dimension == ClassificationDimension.KNOWLEDGE &&
                suggestion.confidence >= CLASSIFICATION_ACCEPTANCE_CONFIDENCE
        }
    ) {
        reject("organization-knowledge-low-confidence")
    }
    val chapterNodes =
        knowledgeBaseNodes.filter { node ->
            node.granularity == KnowledgeNodeGranularity.TOPIC &&
                node.knownNames().contains(chapter.displayName.normalizedOrganizationName())
        }
    val chapterNode = chapterNodes.singleOrNull()
        ?: reject("organization-chapter-not-grounded")
    val atomicNodeIds = linkedSetOf<String>()
    val atomicReferenceIds = linkedSetOf<String>()
    plan.atomicKnowledge.forEach { atom ->
        if (!atomicReferenceIds.add(atom.referenceId)) {
            reject("organization-atomic-reference-duplicate")
        }
        val matchedId = atom.matchedKnowledgeNodeId
            ?: reject("organization-atomic-not-grounded")
        if (!atomicNodeIds.add(matchedId)) {
            reject("organization-knowledge-node-duplicate")
        }
        val node = knowledgeBaseNodes.singleOrNull { it.knowledgeNodeId == matchedId }
            ?: reject("organization-atomic-outside-context")
        if (!atom.matchesReviewedNode(node)) {
            reject("organization-atomic-low-confidence-or-mismatch")
        }
    }
    if (chapterNode.knowledgeNodeId in atomicNodeIds) {
        reject("organization-chapter-atom-collision")
    }
    return listOf(chapterNode.knowledgeNodeId) + atomicNodeIds
}

private fun AtomicKnowledgeSuggestion.matchesReviewedNode(
    node: KnowledgeBaseNodeContext,
): Boolean =
    confidence >= ATOMIC_KNOWLEDGE_ACCEPTANCE_CONFIDENCE &&
        node.subject != SubjectKind.GENERAL &&
        node.granularity == KnowledgeNodeGranularity.ATOMIC &&
        node.verificationStatus != KnowledgeNodeVerificationStatus.MODEL_CANDIDATE &&
        kind == node.kind &&
        canonicalName.normalizedOrganizationName() in node.knownNames() &&
        parentKnowledgeDisplayName.normalizedOrganizationName() ==
        node.parentCanonicalName?.normalizedOrganizationName()

private fun KnowledgeBaseNodeContext.knownNames(): Set<String> =
    (aliases + canonicalName).mapTo(linkedSetOf(), String::normalizedOrganizationName)

private fun String.normalizedOrganizationName(): String =
    trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")

private fun ReviewAndWriteStudentProblemOrganizationCommand.requireDeterministicRequestId(
    source: CommittedStudentProblemOrganizationSource,
    input: ProblemOrganizationV3Input,
    provider: ProviderCapabilitySnapshot,
    requestedAtEpochMillis: Long,
    knowledge: StudentProblemOrganizationKnowledgeSnapshot,
) {
    if (
        requestId !=
        organizationRequestId(
            workId = workId,
            source = source,
            input = input,
            provider = provider,
            requestedAtEpochMillis = requestedAtEpochMillis,
            knowledgeSnapshot = knowledge,
        )
    ) {
        reject("organization-request-generation-mismatch")
    }
}

private fun OrganizeStudentProblemCommand.requireOwnerPrepared(
    source: CommittedStudentProblemOrganizationSource,
    review: ReviewAndWriteStudentProblemOrganizationCommand,
    knowledge: StudentProblemOrganizationKnowledgeSnapshot,
) {
    val proof = verifiedReviewProof ?: reject("organization-owner-proof-missing")
    val snapshots =
        (
            classifications.map { it.verifiedKnowledgeReference } +
                stepKnowledgeBindings.map { it.verifiedKnowledgeReference }
        ).map { it.manifestFingerprint to it.activationGeneration }.distinct()
    if (
        requestId != review.requestId ||
        requestCanonicalFingerprint != review.requestCanonicalFingerprint ||
        problemRevision != source.problemRevision ||
        errorOccurrences != source.errorOccurrences ||
        snapshots != listOf(knowledge.manifestFingerprint to knowledge.activationGeneration) ||
        proof.finalCommandCanonicalFingerprint != payloadCanonicalFingerprint
    ) {
        reject("organization-owner-mapping-mismatch")
    }
}

private fun StudentProblemOrganizationReceipt.requireExactReplay(
    source: CommittedStudentProblemOrganizationSource,
    task: ModelTaskSnapshot,
    knowledge: StudentProblemOrganizationKnowledgeSnapshot,
) {
    val output = task.output as ProblemOrganizationOutput
    val provider = checkNotNull(task.provider)
    if (
        requestId != task.request.requestId ||
        requestCanonicalFingerprint != ModelTaskFingerprint.of(task.request) ||
        problemRevision != source.problemRevision ||
        modelProviderId != provider.providerId ||
        modelId != provider.modelId ||
        modelVersion != output.modelVersion ||
        providerConfigurationVersion != provider.providerConfigurationVersion ||
        modelTaskSchemaVersion != task.request.schemaVersion ||
        organizationPlanSchemaVersion != output.plan.schemaVersion ||
        task.updatedAtEpochMillis > reviewIssuedAtEpochMillis ||
        completedAtEpochMillis !in
        reviewIssuedAtEpochMillis..reviewExpiresAtEpochMillis ||
        knowledge.activationGeneration <= 0
    ) {
        reject("organization-replay-receipt-mismatch")
    }
}

private fun StudentProblemOrganizationReceipt.requireExactWrite(
    command: OrganizeStudentProblemCommand,
) {
    if (
        receiptId != command.receiptId ||
        requestId != command.requestId ||
        requestCanonicalFingerprint != command.requestCanonicalFingerprint ||
        problemRevision != command.problemRevision ||
        payloadCanonicalFingerprint != command.payloadCanonicalFingerprint ||
        organizationRevision != command.organizationRevision ||
        completedAtEpochMillis != command.completedAtEpochMillis
    ) {
        reject("organization-write-receipt-mismatch")
    }
}

private fun ProductionStudentProblemOrganizationOwner.applied(
    receipt: StudentProblemOrganizationReceipt,
    source: CommittedStudentProblemOrganizationSource,
    knowledge: StudentProblemOrganizationKnowledgeSnapshot,
    verifiedReferenceCount: Int,
): StudentProblemOrganizationReviewWriteOutcome.Applied =
    StudentProblemOrganizationReviewWriteOutcome.Applied(
        organizationReceiptId = receipt.receiptId,
        requestId = receipt.requestId,
        requestCanonicalFingerprint = receipt.requestCanonicalFingerprint,
        sourceReceiptId = source.sourceReceiptId,
        sourceReceiptPayloadFingerprint = source.sourceReceiptPayloadFingerprint,
        problemRevisionCanonicalFingerprint = receipt.problemRevision.canonicalFingerprint,
        modelProviderId = receipt.modelProviderId,
        modelId = receipt.modelId,
        modelVersion = receipt.modelVersion,
        providerConfigurationVersion = receipt.providerConfigurationVersion,
        reviewedModelTaskSchemaVersion = receipt.modelTaskSchemaVersion,
        reviewedOrganizationPlanSchemaVersion = receipt.organizationPlanSchemaVersion,
        verifiedKnowledgeReferenceCount = verifiedReferenceCount,
        knowledgeManifestFingerprint = knowledge.manifestFingerprint,
        knowledgeActivationGeneration = knowledge.activationGeneration,
        organizationPayloadCanonicalFingerprint = receipt.payloadCanonicalFingerprint,
        ownerReviewProofFingerprint =
            ownerReceiptFingerprint(receipt, source, knowledge),
        recordedAtEpochMillis = receipt.completedAtEpochMillis,
    )

private fun ownerReceiptFingerprint(
    receipt: StudentProblemOrganizationReceipt,
    source: CommittedStudentProblemOrganizationSource,
    knowledge: StudentProblemOrganizationKnowledgeSnapshot,
): String =
    CanonicalSha256(OWNER_RECEIPT_FINGERPRINT_DOMAIN)
        .field("sourceReceiptId", source.sourceReceiptId)
        .field("sourceReceiptPayloadFingerprint", source.sourceReceiptPayloadFingerprint)
        .field("sourceCanonicalFingerprint", source.canonicalFingerprint)
        .field("organizationReceiptId", receipt.receiptId)
        .field("organizationPayloadFingerprint", receipt.payloadCanonicalFingerprint)
        .field("requestId", receipt.requestId)
        .field("requestFingerprint", receipt.requestCanonicalFingerprint)
        .field("problemRevision", receipt.problemRevision.canonicalFingerprint)
        .field("modelProviderId", receipt.modelProviderId)
        .field("modelId", receipt.modelId)
        .field("modelVersion", receipt.modelVersion)
        .field("providerConfigurationVersion", receipt.providerConfigurationVersion)
        .field("modelTaskSchemaVersion", receipt.modelTaskSchemaVersion)
        .field("organizationPlanSchemaVersion", receipt.organizationPlanSchemaVersion)
        .field("knowledgeManifestFingerprint", knowledge.manifestFingerprint)
        .field("knowledgeActivationGeneration", knowledge.activationGeneration)
        .finish()

private fun organizationRequestId(
    workId: String,
    source: CommittedStudentProblemOrganizationSource,
    input: ProblemOrganizationV3Input,
    provider: ProviderCapabilitySnapshot,
    requestedAtEpochMillis: Long,
    knowledgeSnapshot: StudentProblemOrganizationKnowledgeSnapshot,
): String =
    "student-problem-organization:" +
        CanonicalSha256(REQUEST_ID_DOMAIN)
            .field("workId", workId)
            .field("sourceReceiptId", source.sourceReceiptId)
            .field("sourceReceiptPayloadFingerprint", source.sourceReceiptPayloadFingerprint)
            .field("sourceCanonicalFingerprint", source.canonicalFingerprint)
            .field("inputFingerprint", ModelTaskLogicalOperationFingerprint.of(input))
            .field("providerId", provider.providerId)
            .field("modelId", provider.modelId)
            .field("providerConfigurationVersion", provider.providerConfigurationVersion)
            .field("requestedAtEpochMillis", requestedAtEpochMillis)
            .field("knowledgeManifestFingerprint", knowledgeSnapshot.manifestFingerprint)
            .field("knowledgeActivationGeneration", knowledgeSnapshot.activationGeneration)
            .field("promptPolicyVersion", ModelPromptPolicyVersions.PROBLEM_ORGANIZATION)
            .finish()
            .take(40)

private fun organizationReceiptId(
    requestId: String,
    revision: StudentProblemRevisionRef,
): String =
    "organization:" +
        CanonicalSha256(RECEIPT_ID_DOMAIN)
            .field("requestId", requestId)
            .field("problemRevision", revision.canonicalFingerprint)
            .finish()
            .take(40)

private fun List<VerifiedKnowledgeReferenceProof>.requireExactSnapshot(
    subject: SubjectKind,
    contexts: List<KnowledgeBaseNodeContext>,
    expectedKnowledgeNodeIds: List<String>,
): StudentProblemOrganizationKnowledgeSnapshot {
    val expectedContexts = contexts.associateBy(KnowledgeBaseNodeContext::knowledgeNodeId)
    require(
        expectedKnowledgeNodeIds.isNotEmpty() &&
            expectedKnowledgeNodeIds.distinct().size == expectedKnowledgeNodeIds.size &&
            size == expectedKnowledgeNodeIds.size &&
            map { proof -> proof.ref.knowledgeNodeId }.toSet() ==
            expectedKnowledgeNodeIds.toSet() &&
            all { proof ->
                val context = expectedContexts[proof.ref.knowledgeNodeId]
                proof.ref.subject == subject &&
                    context?.subject == subject &&
                    proof.ref.taxonomyVersion == context.taxonomyVersion
            },
    ) { "Organization knowledge proofs do not match the exact disclosed references" }
    val snapshots = map { it.manifestFingerprint to it.activationGeneration }.distinct()
    require(snapshots.size == 1) {
        "Organization references must use exactly one knowledge snapshot"
    }
    val snapshot = snapshots.single()
    return StudentProblemOrganizationKnowledgeSnapshot(snapshot.first, snapshot.second)
}

private fun SessionScope.requireOwnerScope(expected: SessionScope) {
    require(this == expected) { "Organization request crosses the learner-bound owner" }
}

private fun String.isOwnerIdentifier(): Boolean =
    isNotBlank() && this == trim() && length <= 256 && none(Char::isISOControl)

private fun String.isSha256(): Boolean =
    length == 64 && all { character -> character in '0'..'9' || character in 'a'..'f' }

private fun terminal(reasonCode: String) =
    StudentProblemOrganizationReviewWriteOutcome.NotApplied(
        StudentProblemOrganizationNonApplication.TERMINAL,
        reasonCode,
    )

private fun retryable(reasonCode: String) =
    StudentProblemOrganizationReviewWriteOutcome.NotApplied(
        StudentProblemOrganizationNonApplication.RETRYABLE,
        reasonCode,
    )

private fun reject(reasonCode: String): Nothing = throw OrganizationReviewRejected(reasonCode)

private fun unavailable(reasonCode: String): Nothing =
    throw OrganizationReviewUnavailable(reasonCode)

private class OrganizationReviewRejected(
    val reasonCode: String,
) : IllegalArgumentException(reasonCode)

private class OrganizationReviewUnavailable(
    val reasonCode: String,
) : IllegalStateException(reasonCode)

private const val REVIEW_PROOF_TTL_MILLIS = 60_000L
private const val SOURCE_FINGERPRINT_DOMAIN =
    "committed-student-problem-organization-source-v1"
private const val REQUEST_ID_DOMAIN = "student-problem-organization-request-id-v1"
private const val RECEIPT_ID_DOMAIN = "student-problem-organization-receipt-id-v1"
private const val OWNER_RECEIPT_FINGERPRINT_DOMAIN =
    "student-problem-organization-owner-receipt-v1"
