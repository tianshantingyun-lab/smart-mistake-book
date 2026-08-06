package com.tingyun.smartmistakebook.core.student.mistake.database

import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocumentCodec
import com.tingyun.smartmistakebook.core.model.storage.KnowledgeReferenceProofAuthority
import com.tingyun.smartmistakebook.core.model.storage.KnowledgeReferenceProofVerifier
import com.tingyun.smartmistakebook.core.model.storage.ProblemKnowledgeBindingRef
import com.tingyun.smartmistakebook.core.model.storage.ProblemLifecycleState
import com.tingyun.smartmistakebook.core.model.storage.StudentProblemRef
import com.tingyun.smartmistakebook.core.model.storage.StudentProblemRevisionRef
import java.util.UUID

internal class RoomStudentMistakeStore(
    private val database: StudentMistakeRoomDatabase,
    private val knowledgeReferenceVerifier: KnowledgeReferenceProofVerifier =
        KnowledgeReferenceProofAuthority.create().verifier,
) : StudentMistakeStore {
    private val dao = database.mistakeDao()
    private val reviewDao = database.reviewDao()
    private val libraryDao = database.libraryDao()

    @JvmSynthetic
    internal fun identityReceiptLedger(): StudentProblemIdentityReceiptLedger =
        database.identityReceiptLedger()

    internal fun relayForLearner(
        learnerId: String,
    ): StudentMistakeRelayCapability =
        relayForLearner(learnerId, outboxAuthenticatorForLearner(learnerId))

    internal fun outboxAuthenticatorForLearner(
        learnerId: String,
    ): StudentOutboxAuthenticatorSession =
        StudentOutboxAuthenticatorSession(
            learnerId,
            AndroidKeystoreStudentOutboxHmacKeyStore.INSTANCE,
        )

    internal fun relayForLearner(
        learnerId: String,
        authenticator: StudentOutboxAuthenticatorSession,
    ): StudentMistakeRelayCapability =
        StudentMistakeMessageJournal(
            dao = dao,
            learnerId = learnerId,
            authenticityVerifier = authenticator.verifier,
            activeStoreGeneration = { ensureStoreGeneration(System.currentTimeMillis()) },
            activeAuthenticityKey = {
                checkNotNull(dao.readActiveOutboxAuthenticityKeyState()) {
                    "Student outbox authenticity key-state is unavailable"
                }.toActiveKey()
            },
            authenticatorSession = authenticator,
        )

    internal fun libraryForLearner(
        learnerId: String,
    ): LearnerBoundStudentMistakeLibraryPort =
        RoomLearnerBoundStudentMistakeLibraryPort(
            dao = dao,
            libraryDao = libraryDao,
            learnerId = learnerId,
        )

    internal fun captureSavesForLearner(
        learnerId: String,
    ): LearnerBoundStudentCaptureSavePort =
        RoomLearnerBoundStudentCaptureSavePort(
            store = this,
            dao = dao,
            learnerId = learnerId,
        )

    internal fun captureOccurrencesForLearner(
        learnerId: String,
        identityEvidenceVerifier:
            StudentProblemIdentityEvidenceAuthority.Verifier,
    ): LearnerBoundStudentCaptureOccurrencePort =
        createRoomLearnerBoundStudentCaptureOccurrencePort(
            store = this,
            dao = dao,
            learnerId = learnerId,
            identityEvidenceVerifier = identityEvidenceVerifier,
        )

    internal fun reviewSessionsForLearner(
        learnerId: String,
    ): LearnerBoundStudentReviewSessionPort =
        RoomLearnerBoundStudentReviewSessionPort(
            dao = dao,
            reviewDao = reviewDao,
            learnerId = learnerId,
        )

    internal fun trustedReviewAnswersForLearner(
        learnerId: String,
    ): LearnerBoundStudentTrustedReviewAnswerPort =
        trustedReviewAnswersForLearner(learnerId, System::currentTimeMillis)

    internal fun trustedReviewAnswersForLearner(
        learnerId: String,
        nowEpochMillis: () -> Long,
    ): LearnerBoundStudentTrustedReviewAnswerPort =
        StudentTrustedReviewAnswerOwner(
            learnerId = learnerId,
            persistence =
                RoomStudentTrustedReviewAnswerPersistence(
                    answers = database.trustedReviewAnswerDao(),
                    reviews = dao,
                    storeGenerationProvider = ::ensureStoreGeneration,
                    outboxIssuerProvider = ::outboxIssuerForLearner,
                ),
            nowEpochMillis = nowEpochMillis,
            newReceiptId = { "trusted-review-lease:${UUID.randomUUID()}" },
        )

    internal fun trustedSavedAnswerRulesForLearner(
        learnerId: String,
    ): LearnerBoundStudentTrustedSavedAnswerRulePort =
        StudentTrustedSavedAnswerRuleOwner(
            learnerId = learnerId,
            persistence = database.trustedReviewAnswerDao(),
            responseBindingIssuerProvider = {
                val sourceStoreGeneration = ensureStoreGeneration(System.currentTimeMillis())
                StudentOutboxBoundReviewResponseBindingIssuer(
                    outboxIssuerForLearner(learnerId, sourceStoreGeneration),
                )
            },
            nowEpochMillis = System::currentTimeMillis,
            newReceiptId = { "trusted-saved-answer-lease:${UUID.randomUUID()}" },
        )

    internal fun trustedReviewAnswerRuleAdmissions():
        StudentTrustedReviewAnswerRuleAdmissionPort =
        database.trustedReviewAnswerDao()

    internal fun tutorInteractionAnswerCertificatesForLearner(
        learnerId: String,
    ): LearnerBoundTutorInteractionAnswerCertificatePort =
        tutorInteractionAnswerCertificatesForLearner(learnerId, System::currentTimeMillis)

    internal fun tutorInteractionAnswerCertificatesForLearner(
        learnerId: String,
        nowEpochMillis: () -> Long,
    ): LearnerBoundTutorInteractionAnswerCertificatePort =
        TutorInteractionAnswerCertificateOwner(
            learnerId = learnerId,
            persistence = database.tutorInteractionAnswerCertificateDao(),
            nowEpochMillis = nowEpochMillis,
            newLeaseReceiptId = { "tutor-answer-certificate-lease:${UUID.randomUUID()}" },
        )

    internal fun tutorInteractionAnswerCertificateAdmissions():
        TutorInteractionAnswerCertificateAdmissionPort =
        database.tutorInteractionAnswerCertificateDao()

    internal fun errorOccurrencesForLearner(
        learnerId: String,
    ): StudentProblemErrorOccurrencePort =
        RoomStudentProblemErrorOccurrencePort(
            dao = database.errorOccurrenceDao(),
            learnerId = learnerId,
        )

    internal fun organizationSourcesForLearner(
        learnerId: String,
        captureHandoffs: LearnerBoundStudentCaptureHandoffPort,
        errorOccurrences: StudentProblemErrorOccurrencePort,
    ): LearnerBoundStudentProblemOrganizationSourcePort =
        StudentProblemOrganizationSourceOwner(
            learnerId = learnerId,
            store = this,
            captureHandoffs = captureHandoffs,
            errorOccurrences = errorOccurrences,
        )

    internal fun organizationForLearner(
        learnerId: String,
        reviewProofVerifier: StudentProblemOrganizationReviewAuthority.Verifier,
    ): StudentProblemOrganizationPort =
        RoomStudentProblemOrganizationPort(
            dao = database.organizationDao(),
            learnerId = learnerId,
            knowledgeReferenceVerifier = knowledgeReferenceVerifier,
            reviewProofVerifier = reviewProofVerifier,
        )

    override suspend fun commitProblem(
        command: CommitStudentProblemCommand,
    ): StudentProblemDocument {
        command.revision.problem.subject.requireHighSchoolSubject()
        val existingProblem = dao.readProblem(command.revision.problem.problemId)
        dao.commitProblem(prepareCommitBundle(command, existingProblem))
        return command.toDocument(
            collection = readCollectionState(command.revision.problem),
            lifecycleState =
                existingProblem?.lifecycleState?.let {
                    enumValueOrCorrupt(it, "problem lifecycle")
                } ?: StudentProblemLifecycleState.ACTIVE,
        )
    }

    internal suspend fun prepareCommitBundle(
        command: CommitStudentProblemCommand,
        existingProblem: StudentProblemDocumentEntity?,
    ): CommitStudentProblemBundle {
        command.revision.problem.subject.requireHighSchoolSubject()
        val storeGeneration = ensureStoreGeneration(command.committedAtEpochMillis)
        val createdAt = existingProblem?.createdAtEpochMillis ?: command.committedAtEpochMillis
        val problem =
            StudentProblemDocumentEntity(
                problemId = command.revision.problem.problemId,
                learnerId = command.revision.problem.learnerId,
                subject = command.revision.problem.subject.name,
                primaryPracticeUnitId = command.revision.problem.practiceUnitId,
                currentRevisionId = command.revision.revisionId,
                errorBookEntryId =
                    existingProblem?.errorBookEntryId ?: command.errorBookEntryId,
                lifecycleState =
                    existingProblem?.lifecycleState ?: StudentProblemLifecycleState.ACTIVE.name,
                archivedAtEpochMillis = existingProblem?.archivedAtEpochMillis,
                tombstonedAtEpochMillis = existingProblem?.tombstonedAtEpochMillis,
                createdAtEpochMillis = createdAt,
                updatedAtEpochMillis = command.committedAtEpochMillis,
            )
        val revision =
            StudentProblemRevisionEntity(
                revisionId = command.revision.revisionId,
                problemId = command.revision.problem.problemId,
                revisionNumber = command.revision.revisionNumber,
                title = command.title,
                stemMarkdown = command.stemMarkdown,
                capturedQuestionDocumentWire =
                    command.capturedQuestionDocument?.let(CapturedQuestionDocumentCodec::encode),
                documentCanonicalFingerprint = command.revision.documentCanonicalFingerprint,
                createdAtEpochMillis = command.committedAtEpochMillis,
                updatedAtEpochMillis = command.committedAtEpochMillis,
            )
        val practiceUnit =
            StudentPracticeUnitEntity(
                practiceUnitId = command.revision.problem.practiceUnitId,
                problemId = command.revision.problem.problemId,
                basisRevisionId = command.revision.revisionId,
                unitKind = command.practiceUnitKind.name,
                title = command.practiceUnitTitle,
                itemFamilyId = command.itemFamilyId,
                estimatedDurationSeconds = command.estimatedDurationSeconds,
                sourceBundleId = command.sourceBundleId,
                partIdsWire = encodeOrderedStrings(command.partIds),
                createdAtEpochMillis = createdAt,
                updatedAtEpochMillis = command.committedAtEpochMillis,
            )
        val images =
            command.originalImages.map { image ->
                StudentProblemImageReferenceEntity(
                    imageReferenceId = image.imageReferenceId,
                    revisionId = command.revision.revisionId,
                    localContentUri = image.localContentUri,
                    contentCanonicalFingerprint = image.contentCanonicalFingerprint,
                    mediaType = image.mediaType,
                    ordinal = image.ordinal,
                    widthPixels = image.widthPixels,
                    heightPixels = image.heightPixels,
                    byteSize = image.byteSize,
                    selectedRegionsWire = encodeSelectedRegions(image.selectedRegions),
                    createdAtEpochMillis = command.committedAtEpochMillis,
                )
            }
        val solutionAnalysis = command.solutionAnalysis?.toEntity()
        val solutionSteps =
            command.solutionAnalysis?.steps.orEmpty().map { step ->
                step.toEntity(checkNotNull(solutionAnalysis))
            }
        val errorAttributions =
            command.errorAttributions.map(StudentProblemErrorAttribution::toEntity)
        val errorEvidence =
            command.errorAttributions.flatMap { attribution ->
                attribution.evidenceRefs.mapIndexed { index, evidence ->
                    evidence.toEntity(
                        attribution = attribution,
                        ordinal = index,
                    )
                }
            }
        val outbox =
            StudentOutboxEntityFactory.signedEntity(
                StudentOutboxEntityFactory.problemRevisionCommitted(
                    command.revision,
                    command.committedAtEpochMillis,
                    storeGeneration,
                ),
                outboxIssuerForLearner(
                    command.revision.problem.learnerId,
                    storeGeneration,
                ),
            )
        val normalizedSearch =
            StudentMistakeSearchNormalizer.document(
                revisionId = command.revision.revisionId,
                title = command.title,
                stemMarkdown = command.stemMarkdown,
                practiceUnitTitle = command.practiceUnitTitle,
            )
        return CommitStudentProblemBundle(
            problem = problem,
            revision = revision,
            practiceUnit = practiceUnit,
            images = images,
            solutionAnalysis = solutionAnalysis,
            solutionSteps = solutionSteps,
            errorAttributions = errorAttributions,
            errorEvidence = errorEvidence,
            searchDocument =
                StudentProblemSearchDocumentEntity(
                    revisionId = command.revision.revisionId,
                    sourceCanonicalFingerprint = normalizedSearch.sourceCanonicalFingerprint,
                    normalizedText = normalizedSearch.normalizedText,
                    tokenizedText = normalizedSearch.tokenizedText,
                    indexedAtEpochMillis = command.committedAtEpochMillis,
                ),
            outbox = outbox,
            supersededRevisionOutbox =
                prepareRevisionSupersededOutbox(
                    command = command,
                    existingProblem = existingProblem,
                ),
        )
    }

    internal suspend fun prepareRevisionSupersededOutbox(
        command: CommitStudentProblemCommand,
        existingProblem: StudentProblemDocumentEntity?,
    ): StudentStoreOutboxEntity? {
        if (
            existingProblem == null ||
            existingProblem.currentRevisionId == command.revision.revisionId
        ) {
            return null
        }
        val previousRevision =
            checkNotNull(dao.readRevision(existingProblem.currentRevisionId)) {
                "Corrupt student mistake store: previous revision is missing"
            }
        val storeGeneration = ensureStoreGeneration(command.committedAtEpochMillis)
        return StudentOutboxEntityFactory.signedEntity(
            StudentOutboxEntityFactory.problemRevisionSuperseded(
                previousRevision.toRef(existingProblem),
                command.revision,
                command.committedAtEpochMillis,
                storeGeneration,
            ),
            outboxIssuerForLearner(
                command.revision.problem.learnerId,
                storeGeneration,
            ),
        )
    }

    override suspend fun saveConfirmedMistake(
        command: SaveIntentConfirmedStudentMistakeCommand,
    ): StudentProblemDocument {
        val problemCommand = command.problem
        val ref = problemCommand.revision.problem
        val collectionCommand =
            SetStudentProblemCollectionCommand(
                problem = ref,
                mistakeState = StudentMistakeEntryState.ACTIVE,
                favorite = command.favorite,
                changedAtEpochMillis = problemCommand.committedAtEpochMillis,
            )
        val collection =
            prepareCollectionEntity(
                command = collectionCommand,
                existing = dao.readCollection(ref.practiceUnitId),
            )
        dao.saveConfirmedMistake(
            SaveConfirmedStudentMistakeBundle(
                problem =
                    prepareCommitBundle(
                        command = problemCommand,
                        existingProblem = dao.readProblem(ref.problemId),
                    ),
                collection = collection,
                initialReviewCandidate =
                    prepareInitialReviewCandidate(
                        command = problemCommand,
                        collection = collection,
                    ),
                receipt =
                    StudentMistakeSaveReceiptEntity(
                        intentConfirmationId = command.intentConfirmationId,
                        intentCanonicalFingerprint = command.intentCanonicalFingerprint,
                        learnerId = ref.learnerId,
                        problemId = ref.problemId,
                        basisRevisionId = problemCommand.revision.revisionId,
                        errorBookEntryId = checkNotNull(problemCommand.errorBookEntryId),
                        confirmedAtEpochMillis = command.confirmedAtEpochMillis,
                        savedAtEpochMillis = problemCommand.committedAtEpochMillis,
                    ),
            ),
        )
        return checkNotNull(findProblem(ref)) {
            "Intent-confirmed mistake save did not persist its exact problem"
        }
    }

    override suspend fun saveConfirmedMistake(
        command: SaveTargetConfirmedStudentMistakeCommand,
    ): TargetConfirmedStudentMistakeSaveReceipt {
        val targetFingerprint = command.targetCanonicalFingerprint
        val outcome =
            dao.saveTargetConfirmedMistake(
                prepareTargetConfirmedMistakeBundle(command),
            )
        return TargetConfirmedStudentMistakeSaveReceipt(
            outcome = outcome,
            problemRevision = command.problem.revision,
            errorBookEntryId = checkNotNull(command.problem.errorBookEntryId),
            targetCanonicalFingerprint = targetFingerprint,
        )
    }

    internal suspend fun prepareTargetConfirmedMistakeBundle(
        command: SaveTargetConfirmedStudentMistakeCommand,
    ): SaveConfirmedStudentMistakeBundle {
        val problemCommand = command.problem
        val ref = problemCommand.revision.problem
        val collection =
            prepareCollectionEntity(
                command =
                    SetStudentProblemCollectionCommand(
                        problem = ref,
                        mistakeState = StudentMistakeEntryState.ACTIVE,
                        favorite = command.favorite,
                        changedAtEpochMillis = problemCommand.committedAtEpochMillis,
                    ),
                existing = null,
            )
        val targetFingerprint = command.targetCanonicalFingerprint
        return SaveConfirmedStudentMistakeBundle(
            problem =
                prepareCommitBundle(
                    command = problemCommand,
                    existingProblem = null,
                ),
            collection = collection,
            initialReviewCandidate =
                prepareInitialReviewCandidate(
                    command = problemCommand,
                    collection = collection,
                ),
            receipt =
                StudentMistakeSaveReceiptEntity(
                    intentConfirmationId = "target-$targetFingerprint",
                    intentCanonicalFingerprint = targetFingerprint,
                    learnerId = ref.learnerId,
                    problemId = ref.problemId,
                    basisRevisionId = problemCommand.revision.revisionId,
                    errorBookEntryId = checkNotNull(problemCommand.errorBookEntryId),
                    confirmedAtEpochMillis = command.confirmedAtEpochMillis,
                    savedAtEpochMillis = problemCommand.committedAtEpochMillis,
                ),
        )
    }

    internal fun prepareInitialReviewCandidate(
        command: CommitStudentProblemCommand,
        collection: StudentProblemCollectionEntity,
    ): StudentReviewCandidateEntity {
        check(collection.mistakeState == StudentMistakeEntryState.ACTIVE.name) {
            "Only an active confirmed mistake receives an initial review candidate"
        }
        check(
            collection.learnerId == command.revision.problem.learnerId &&
                collection.practiceUnitId == command.revision.problem.practiceUnitId,
        ) {
            "Initial review candidate does not match the confirmed mistake"
        }
        return StudentReviewCandidateEntity(
            candidateId = command.revision.problem.practiceUnitId,
            learnerId = command.revision.problem.learnerId,
            practiceUnitId = command.revision.problem.practiceUnitId,
            basisRevisionId = command.revision.revisionId,
            reasonCodesWire = encodeCanonicalSet(setOf(INITIAL_REVIEW_REASON)),
            itemFamilyId = command.itemFamilyId,
            estimatedDurationSeconds = command.estimatedDurationSeconds,
            availableAtEpochMillis = collection.changedAtEpochMillis,
            dueAtEpochMillis = null,
            sourceEvidenceEventKind = null,
            sourceEvidenceEventId = null,
            sourceEvidenceSequence = null,
            sourceEvidenceCanonicalFingerprint = null,
            candidateVersion = command.revision.revisionNumber.toLong(),
            createdAtEpochMillis = collection.changedAtEpochMillis,
            updatedAtEpochMillis = collection.changedAtEpochMillis,
        )
    }

    override suspend fun findProblem(ref: StudentProblemRef): StudentProblemDocument? {
        ref.subject.requireHighSchoolSubject()
        val problem = dao.readProblem(ref.problemId) ?: return null
        if (!problem.matches(ref)) return null
        val revision =
            checkNotNull(dao.readRevision(problem.currentRevisionId)) {
                "Corrupt student mistake store: current revision is missing"
            }
        val practiceUnit =
            checkNotNull(dao.readPracticeUnit(ref.practiceUnitId)) {
                "Corrupt student mistake store: primary practice unit is missing"
            }
        check(practiceUnit.basisRevisionId == revision.revisionId) {
            "Corrupt student mistake store: practice unit does not target the current revision"
        }
        val collection = dao.readCollection(ref.practiceUnitId)
        val revisionRef = revision.toRef(problem)
        val images = dao.readImages(revision.revisionId, MAX_STORED_IMAGES + 1)
        check(images.size <= MAX_STORED_IMAGES) {
            "Corrupt student mistake store: image-reference budget exceeded"
        }
        val authority = readRevisionAuthority(revisionRef)
        return StudentProblemDocument(
            revision = revisionRef,
            title = revision.title,
            stemMarkdown = revision.stemMarkdown,
            practiceUnitKind = enumValueOrCorrupt(practiceUnit.unitKind, "practice-unit kind"),
            practiceUnitTitle = practiceUnit.title,
            itemFamilyId = practiceUnit.itemFamilyId,
            estimatedDurationSeconds = practiceUnit.estimatedDurationSeconds,
            sourceBundleId = practiceUnit.sourceBundleId,
            partIds = decodeOrderedStrings(practiceUnit.partIdsWire),
            originalImages = images.map { it.toDomain() },
            lifecycleState = enumValueOrCorrupt(problem.lifecycleState, "problem lifecycle"),
            mistakeState =
                collection?.mistakeState?.let {
                    enumValueOrCorrupt(it, "mistake state")
                } ?: StudentMistakeEntryState.NONE,
            favorite = collection?.favorite ?: false,
            errorBookEntryId = problem.errorBookEntryId,
            capturedQuestionDocument =
                revision.capturedQuestionDocumentWire?.let(CapturedQuestionDocumentCodec::decode),
            solutionAnalysis = authority.solutionAnalysis,
            errorAttributions = authority.errorAttributions,
        )
    }

    override suspend fun readMistakeDetail(
        query: StudentMistakeDetailQuery,
    ): StudentProblemDocument? {
        val detail = findProblem(query.problem) ?: return null
        if (detail.revision.problem.learnerId != query.learnerId ||
            detail.errorBookEntryId != query.errorBookEntryId
        ) {
            return null
        }
        return detail
    }

    override suspend fun readRevisionHistory(
        query: StudentProblemRevisionHistoryQuery,
    ): StudentProblemRevisionHistoryPage {
        val current = findProblem(query.problem)
        if (current == null ||
            current.revision.problem.learnerId != query.learnerId ||
            current.errorBookEntryId != query.errorBookEntryId
        ) {
            return StudentProblemRevisionHistoryPage(emptyList(), null)
        }
        val rows =
            dao.readRevisionHistoryRows(
                errorBookEntryId = query.errorBookEntryId,
                cursorRevisionNumber = query.cursor?.revisionNumber,
                cursorRevisionId = query.cursor?.revisionId,
                limit = query.limit + 1,
            )
        val pageRows = rows.take(query.limit)
        val pageItems = pageRows.map(StudentProblemRevisionHistoryRow::toDomain)
        if (pageItems.any { item -> item.revision.problem != query.problem }) {
            return StudentProblemRevisionHistoryPage(emptyList(), null)
        }
        return StudentProblemRevisionHistoryPage(
            items = pageItems,
            nextCursor =
                if (rows.size > query.limit) {
                    pageRows.lastOrNull()?.let { row ->
                        StudentProblemRevisionHistoryCursor(
                            revisionNumber = row.revisionNumber,
                            revisionId = row.revisionId,
                        )
                    }
                } else {
                    null
                },
        )
    }

    override suspend fun searchMistakes(
        query: StudentMistakeSearchQuery,
    ): StudentMistakeSearchPage {
        val rows =
            dao.searchMistakes(
                learnerId = query.learnerId,
                subject = query.subject?.name,
                favoriteOnly = query.favoriteOnly,
                searchPattern = query.text?.toEscapedSearchPattern(),
                curriculumSectionLabelId = query.curriculumSectionLabelId,
                knowledgeSubject = query.knowledgeNode?.subject?.name,
                knowledgeNodeId = query.knowledgeNode?.knowledgeNodeId,
                knowledgeTaxonomyVersion = query.knowledgeNode?.taxonomyVersion,
                knowledgePackVersion = query.knowledgeNode?.knowledgePackVersion,
                cursorChangedAtEpochMillis = query.cursor?.changedAtEpochMillis,
                cursorProblemId = query.cursor?.problemId,
                limit = query.limit + 1,
            )
        val pageRows = rows.take(query.limit)
        return StudentMistakeSearchPage(
            items = pageRows.map(StudentMistakeSearchRow::toDomain),
            nextCursor =
                if (rows.size > query.limit) {
                    pageRows.lastOrNull()?.let { row ->
                        StudentMistakeSearchCursor(
                            changedAtEpochMillis = row.changedAtEpochMillis,
                            problemId = row.problemId,
                        )
                    }
                } else {
                    null
                },
        )
    }

    override fun observeChangeVersion(learnerId: String) =
        learnerId
            .also { it.requireStoreText("Learner id", MAX_ID_CHARS) }
            .let(dao::observeChangeVersion)

    override suspend fun setCollectionState(command: SetStudentProblemCollectionCommand) {
        command.problem.subject.requireHighSchoolSubject()
        dao.setCollectionState(
            prepareCollectionEntity(
                command = command,
                existing = dao.readCollection(command.problem.practiceUnitId),
            ),
        )
    }

    internal suspend fun prepareCollectionEntity(
        command: SetStudentProblemCollectionCommand,
        existing: StudentProblemCollectionEntity?,
    ): StudentProblemCollectionEntity {
        command.problem.subject.requireHighSchoolSubject()
        val addedAt =
            when {
                existing?.addedAtEpochMillis != null -> existing.addedAtEpochMillis
                command.mistakeState == StudentMistakeEntryState.ACTIVE || command.favorite ->
                    command.changedAtEpochMillis

                else -> null
            }
        return StudentProblemCollectionEntity(
            practiceUnitId = command.problem.practiceUnitId,
            problemId = command.problem.problemId,
            learnerId = command.problem.learnerId,
            mistakeState = command.mistakeState.name,
            favorite = command.favorite,
            addedAtEpochMillis = addedAt,
            archivedAtEpochMillis =
                if (command.mistakeState == StudentMistakeEntryState.ARCHIVED) {
                    command.changedAtEpochMillis
                } else {
                    existing?.archivedAtEpochMillis
                },
            trashedAtEpochMillis =
                if (command.mistakeState == StudentMistakeEntryState.TRASHED) {
                    command.changedAtEpochMillis
                } else {
                    existing?.trashedAtEpochMillis
                },
            changedAtEpochMillis = command.changedAtEpochMillis,
        )
    }

    override suspend fun setProblemLifecycle(command: SetStudentProblemLifecycleCommand) {
        command.problem.subject.requireHighSchoolSubject()
        val lifecycleOutbox = prepareLifecycleChangedOutbox(command)
        dao.setProblemLifecycle(
            ref = command.problem,
            nextState = command.state,
            changedAtEpochMillis = command.changedAtEpochMillis,
            outbox = prepareLifecycleBindingSnapshot(command),
            lifecycleOutbox = lifecycleOutbox,
        )
    }

    internal suspend fun prepareLifecycleChangedOutbox(
        command: SetStudentProblemLifecycleCommand,
    ): StudentStoreOutboxEntity? {
        val problem = checkNotNull(dao.readProblem(command.problem.problemId)) {
            "Cannot change lifecycle for a missing problem"
        }
        if (problem.lifecycleState == command.state.name) return null
        val revision = checkNotNull(dao.readRevision(problem.currentRevisionId)) {
            "Corrupt student mistake store: current revision is missing"
        }
        val revisionRef = revision.toRef(problem)
        val storeGeneration = ensureStoreGeneration(command.changedAtEpochMillis)
        return StudentOutboxEntityFactory.signedEntity(
            StudentOutboxEntityFactory.problemLifecycleChanged(
                revisionRef,
                ProblemLifecycleState.valueOf(problem.lifecycleState),
                ProblemLifecycleState.valueOf(command.state.name),
                command.changedAtEpochMillis,
                storeGeneration,
            ),
            outboxIssuerForLearner(
                command.problem.learnerId,
                storeGeneration,
            ),
        )
    }

    internal suspend fun prepareLifecycleBindingSnapshot(
        command: SetStudentProblemLifecycleCommand,
    ): StudentStoreOutboxEntity? {
        val problem = checkNotNull(dao.readProblem(command.problem.problemId)) {
            "Cannot change lifecycle for a missing problem"
        }
        if (problem.lifecycleState == command.state.name) return null
        val revision = checkNotNull(dao.readRevision(problem.currentRevisionId)) {
            "Corrupt student mistake store: current revision is missing"
        }
        val revisionRef = revision.toRef(problem)
        val bindings =
            if (command.state == StudentProblemLifecycleState.ACTIVE) {
                currentAcceptedKnowledgeBindings(revisionRef)
            } else {
                emptyList()
            }
        val previousBindingSetVersion =
            dao.readLatestBindingSetVersion(revision.revisionId)
        check(previousBindingSetVersion < Long.MAX_VALUE) {
            "Knowledge binding snapshot version is exhausted"
        }
        val storeGeneration =
            ensureStoreGeneration(command.changedAtEpochMillis)
        return StudentOutboxEntityFactory.signedEntity(
            StudentOutboxEntityFactory.knowledgeBindingsSnapshot(
                revisionRef,
                bindings,
                previousBindingSetVersion + 1,
                command.changedAtEpochMillis,
                storeGeneration,
            ),
            outboxIssuerForLearner(
                command.problem.learnerId,
                storeGeneration,
            ),
        )
    }

    override suspend fun recordClassifications(
        command: RecordStudentProblemClassificationsCommand,
    ) {
        command.problemRevision.problem.subject.requireHighSchoolSubject()
        requirePersistedRevision(command.problemRevision)
        val verifiedKnowledgeReferences = command.verifiedKnowledgeReferences.toMap()
        val requiredVerificationIds =
            command.results
                .filter(StudentProblemClassificationResult::requiresKnowledgeVerification)
                .mapTo(sortedSetOf(), StudentProblemClassificationResult::classificationId)
        check(verifiedKnowledgeReferences.keys == requiredVerificationIds) {
            "Verified knowledge references changed after command validation"
        }
        check(verifiedKnowledgeReferences.values.all(knowledgeReferenceVerifier::verifies)) {
            "Knowledge reference proof was not issued by this runtime's knowledge authority"
        }
        val entities =
            command.results.map { result ->
                result.toEntity(verifiedKnowledgeReferences[result.classificationId])
            }
        val referencedIds =
            buildSet {
                entities.forEach { result ->
                    add(result.classificationId)
                    result.supersedesClassificationId?.let(::add)
                }
            }
        val persistedById =
            referencedIds
                .chunked(MAX_CLASSIFICATION_ID_BATCH)
                .flatMap { ids ->
                    dao.readClassificationsByIds(
                        classificationIds = ids,
                        limit = ids.size,
                    )
                }
                .associateBy(StudentProblemClassificationResultEntity::classificationId)
        val currentAccepted =
            dao.readCurrentClassifications(
                revisionId = command.problemRevision.revisionId,
                limit = MAX_STORED_CLASSIFICATIONS + 1,
            )
        check(currentAccepted.size <= MAX_STORED_CLASSIFICATIONS) {
            "Current classification set exceeds the supported budget"
        }
        val effectiveById =
            currentAccepted.associateByTo(linkedMapOf()) {
                it.classificationId
            }
        val supersededIds = linkedSetOf<String>()
        val newEntities =
            entities.filter { submitted ->
                persistedById[submitted.classificationId]?.let { persisted ->
                    check(persisted.sameSubmittedResult(submitted)) {
                        "Classification id was replayed with different immutable content"
                    }
                    return@filter false
                }
                submitted.supersedesClassificationId?.let { supersededId ->
                    val superseded = checkNotNull(effectiveById[supersededId]) {
                        "Replacement or revocation must target a current accepted classification"
                    }
                    check(
                        superseded.problemId == submitted.problemId &&
                            superseded.basisRevisionId == submitted.basisRevisionId &&
                            superseded.dimension == submitted.dimension,
                    ) {
                        "Replacement or revocation must target the same revision and dimension"
                    }
                    if (submitted.status == StudentProblemClassificationStatus.REVOKED.name) {
                        check(superseded.sameClassificationReference(submitted)) {
                            "Revocation must retain the verified reference being revoked"
                        }
                    }
                    effectiveById.remove(supersededId)
                    supersededIds += supersededId
                }
                if (submitted.status == StudentProblemClassificationStatus.ACCEPTED.name) {
                    check(
                        effectiveById.values.none { existing ->
                            existing.conflictsWithAccepted(submitted)
                        },
                    ) {
                        "A current classification must be explicitly replaced before accepting a conflicting result"
                    }
                    effectiveById[submitted.classificationId] = submitted
                }
                true
            }
        check(effectiveById.size <= MAX_STORED_CLASSIFICATIONS) {
            "Classification update exceeds the supported effective-set budget"
        }
        val activeKnowledgeVersions =
            effectiveById.values
                .filter {
                    it.dimension == StudentProblemClassificationDimension.KNOWLEDGE.name
                }
                .map {
                    Pair(
                        checkNotNull(it.knowledgeTaxonomyVersion),
                        checkNotNull(it.knowledgePackVersion),
                    )
                }
                .toSet()
        check(activeKnowledgeVersions.size <= 1) {
            "Current knowledge bindings must use one catalog and taxonomy version"
        }
        val knowledgeMutations =
            newEntities.filter {
                it.dimension == StudentProblemClassificationDimension.KNOWLEDGE.name &&
                    (
                        it.status == StudentProblemClassificationStatus.ACCEPTED.name ||
                            it.status == StudentProblemClassificationStatus.REVOKED.name
                    )
            }
        val acceptedBindings =
            effectiveById.values
                .filter {
                    it.dimension == StudentProblemClassificationDimension.KNOWLEDGE.name &&
                        it.status == StudentProblemClassificationStatus.ACCEPTED.name
                }
                .map { result ->
                    val knowledgeNode = result.requireKnowledgeNodeRef()
                    val verification = result.requireKnowledgeVerification()
                    ProblemKnowledgeBindingRef(
                        bindingId = result.classificationId,
                        problemRevision = command.problemRevision,
                        knowledgeNode = knowledgeNode,
                        bindingCanonicalFingerprint =
                            locallyVerifiedBindingFingerprint(
                                classificationId = result.classificationId,
                                revision = command.problemRevision,
                                knowledgeNode = knowledgeNode,
                                manifestFingerprint = verification.first,
                                activationGeneration = verification.second,
                            ),
                    )
                }
                .sortedBy(ProblemKnowledgeBindingRef::bindingId)
        val previousBindingSetVersion =
            if (knowledgeMutations.isEmpty()) {
                null
            } else {
                dao.readLatestBindingSetVersion(command.problemRevision.revisionId)
            }
        check(previousBindingSetVersion == null || previousBindingSetVersion < Long.MAX_VALUE) {
            "Knowledge binding snapshot version is exhausted"
        }
        val outbox =
            if (knowledgeMutations.isEmpty()) {
                null
            } else {
                val storeGeneration =
                    ensureStoreGeneration(
                        knowledgeMutations.minOf(
                            StudentProblemClassificationResultEntity::recordedAtEpochMillis,
                        ),
                    )
                StudentOutboxEntityFactory.signedEntity(
                    StudentOutboxEntityFactory.knowledgeBindingsSnapshot(
                        command.problemRevision,
                        acceptedBindings,
                        checkNotNull(previousBindingSetVersion) + 1,
                        knowledgeMutations.maxOf(
                            StudentProblemClassificationResultEntity::recordedAtEpochMillis,
                        ),
                        storeGeneration,
                    ),
                    outboxIssuerForLearner(
                        command.problemRevision.problem.learnerId,
                        storeGeneration,
                    ),
                )
            }

        dao.recordClassifications(
            RecordStudentClassificationsBundle(
                problemId = command.problemRevision.problem.problemId,
                revisionId = command.problemRevision.revisionId,
                results = entities,
                expectedCurrentAcceptedIds = currentAccepted.mapTo(sortedSetOf()) {
                    it.classificationId
                },
                supersededAcceptedIds = supersededIds,
                expectedFinalAcceptedIds = effectiveById.keys,
                expectedPreviousBindingSetVersion = previousBindingSetVersion,
                outbox = outbox,
            ),
        )
    }

    private suspend fun currentAcceptedKnowledgeBindings(
        revision: StudentProblemRevisionRef,
    ): List<ProblemKnowledgeBindingRef> {
        val results =
            dao.readCurrentClassifications(
                revisionId = revision.revisionId,
                limit = MAX_STORED_CLASSIFICATIONS + 1,
            )
        check(results.size <= MAX_STORED_CLASSIFICATIONS) {
            "Current classification set exceeds the supported budget"
        }
        return results
            .filter {
                it.dimension == StudentProblemClassificationDimension.KNOWLEDGE.name &&
                    it.status == StudentProblemClassificationStatus.ACCEPTED.name
            }
            .map { result ->
                val knowledgeNode = result.requireKnowledgeNodeRef()
                val verification = result.requireKnowledgeVerification()
                ProblemKnowledgeBindingRef(
                    bindingId = result.classificationId,
                    problemRevision = revision,
                    knowledgeNode = knowledgeNode,
                    bindingCanonicalFingerprint =
                        locallyVerifiedBindingFingerprint(
                            classificationId = result.classificationId,
                            revision = revision,
                            knowledgeNode = knowledgeNode,
                            manifestFingerprint = verification.first,
                            activationGeneration = verification.second,
                        ),
                )
            }
            .sortedBy(ProblemKnowledgeBindingRef::bindingId)
    }

    override suspend fun readClassifications(
        revision: StudentProblemRevisionRef,
    ): List<StudentProblemClassificationResult> {
        if (!matchesPersistedRevision(revision)) return emptyList()
        return dao.readClassifications(revision.revisionId, MAX_STORED_CLASSIFICATIONS)
            .map { it.toDomain(revision) }
    }

    override suspend fun readCurrentClassifications(
        revision: StudentProblemRevisionRef,
    ): List<StudentProblemClassificationResult> {
        if (!matchesPersistedRevision(revision)) return emptyList()
        return dao.readCurrentClassifications(
            revisionId = revision.revisionId,
            limit = MAX_STORED_CLASSIFICATIONS,
        ).map { it.toDomain(revision) }
    }

    override suspend fun upsertReviewCandidate(candidate: StudentReviewCandidate) {
        candidate.problemRevision.problem.subject.requireHighSchoolSubject()
        requireReviewEligibleRevisions(listOf(candidate.problemRevision))
        dao.upsertReviewCandidate(candidate.toEntity())
    }

    override suspend fun readReviewCandidates(
        query: StudentReviewCandidateQuery,
    ): StudentReviewCandidatePage {
        val rows =
            reviewDao.readReviewCandidateRows(
                learnerId = query.learnerId,
                nowEpochMillis = query.nowEpochMillis,
                cursorAvailableAtEpochMillis = query.cursor?.availableAtEpochMillis,
                cursorCandidateId = query.cursor?.candidateId,
                limit = query.limit + 1,
            )
        val pageRows = rows.take(query.limit)
        return StudentReviewCandidatePage(
            items = pageRows.map(StudentReviewCandidateReadRow::toDomain),
            nextCursor =
                if (rows.size > query.limit) {
                    pageRows.lastOrNull()?.let { row ->
                        StudentReviewCandidateCursor(
                            availableAtEpochMillis = row.availableAtEpochMillis,
                            candidateId = row.candidateId,
                        )
                    }
                } else {
                    null
                },
        )
    }

    override suspend fun readReviewCandidatesWithKnowledge(
        query: StudentReviewCandidateQuery,
    ): StudentReviewCandidateWithKnowledgePage {
        val page = readReviewCandidates(query)
        if (page.items.isEmpty()) {
            return StudentReviewCandidateWithKnowledgePage(
                items = emptyList(),
                nextCursor = page.nextCursor,
            )
        }
        val revisionIds =
            page.items
                .map { it.problemRevision.revisionId }
                .distinct()
        val rows =
            reviewDao.readAcceptedReviewKnowledgeRows(
                basisRevisionIds = revisionIds,
                limit = revisionIds.size * MAX_STORED_CLASSIFICATIONS,
            )
        val acceptedByRevision =
            rows
                .groupBy(StudentReviewAcceptedKnowledgeRow::basisRevisionId)
                .mapValues { (_, values) ->
                    values.mapTo(linkedSetOf(), StudentReviewAcceptedKnowledgeRow::toRef)
                }
        val reviewedFamilyByRevision =
            reviewDao.readReviewedProblemFamilies(revisionIds)
                .groupBy(StudentReviewOrganizationFamilyRow::basisRevisionId)
                .mapValues { (_, values) ->
                    values.singleOrNull()?.let { row ->
                        row.familyId to row.basisDocumentCanonicalFingerprint
                    }
                }
        return StudentReviewCandidateWithKnowledgePage(
            items =
                page.items.map { candidate ->
                    StudentReviewCandidateWithKnowledge(
                        candidate = candidate,
                        acceptedKnowledgeNodes =
                            acceptedByRevision[candidate.problemRevision.revisionId].orEmpty(),
                        reviewedProblemFamilyId =
                            reviewedFamilyByRevision[candidate.problemRevision.revisionId]
                                ?.first,
                        reviewedProblemFamilyBindingDocumentFingerprint =
                            reviewedFamilyByRevision[candidate.problemRevision.revisionId]
                                ?.second,
                    )
                },
            nextCursor = page.nextCursor,
        )
    }

    override suspend fun storeReviewQueue(command: StoreStudentReviewQueueCommand) {
        requireReviewEligibleRevisions(
            command.items.map(StudentReviewQueueItem::problemRevision),
        )
        dao.storeReviewQueue(
            StoreStudentReviewQueueBundle(
                plan =
                    StudentReviewPlanEntity(
                        planId = command.planId,
                        planCanonicalFingerprint = command.planCanonicalFingerprint,
                        learnerId = command.learnerId,
                        localDayEpochDay = command.localDayEpochDay,
                        timeZoneId = command.timeZoneId,
                        timeBudgetSeconds = command.timeBudgetSeconds,
                        generatedAtEpochMillis = command.generatedAtEpochMillis,
                        plannerVersion = command.plannerVersion,
                    ),
                items =
                    command.items.map {
                        it.toEntity(
                            planId = command.planId,
                            learnerId = command.learnerId,
                            createdAtEpochMillis = command.generatedAtEpochMillis,
                        )
                    },
            ),
        )
    }

    override suspend fun readReviewQueue(
        learnerId: String,
        localDayEpochDay: Long,
    ): List<StudentReviewQueueItem> {
        learnerId.requireStoreText("Learner id", MAX_ID_CHARS)
        val plan = reviewDao.readReviewPlan(learnerId, localDayEpochDay) ?: return emptyList()
        return reviewDao.readReviewQueueRows(plan.planId, MAX_STORED_REVIEW_ITEMS)
            .map(StudentReviewQueueReadRow::toDomain)
    }

    override suspend fun readReviewPlanSnapshot(
        learnerId: String,
        localDayEpochDay: Long,
    ): StudentReviewPlanSnapshot? {
        learnerId.requireStoreText("Learner id", MAX_ID_CHARS)
        val plan = reviewDao.readReviewPlan(learnerId, localDayEpochDay) ?: return null
        val rows =
            reviewDao.readReviewPlanSnapshotRows(
                planId = plan.planId,
                limit = MAX_STORED_REVIEW_ITEMS + 1,
            )
        check(rows.size <= MAX_STORED_REVIEW_ITEMS) {
            "Corrupt student mistake store: review-plan item budget exceeded"
        }
        return StudentReviewPlanSnapshot(
            planId = plan.planId,
            planCanonicalFingerprint = plan.planCanonicalFingerprint,
            learnerId = plan.learnerId,
            localDayEpochDay = plan.localDayEpochDay,
            timeZoneId = plan.timeZoneId,
            timeBudgetSeconds = plan.timeBudgetSeconds,
            generatedAtEpochMillis = plan.generatedAtEpochMillis,
            plannerVersion = plan.plannerVersion,
            items = rows.map(StudentReviewQueueReadRow::toDomain),
        )
    }

    private suspend fun readRevisionAuthority(
        revision: StudentProblemRevisionRef,
    ): StudentRevisionAuthority {
        val analysisEntity = dao.readSolutionAnalysis(revision.revisionId)
        val stepEntities =
            analysisEntity?.let { analysis ->
                dao.readSolutionSteps(
                    solutionAnalysisId = analysis.solutionAnalysisId,
                    limit = MAX_STORED_SOLUTION_STEPS + 1,
                )
            }.orEmpty()
        check(stepEntities.size <= MAX_STORED_SOLUTION_STEPS) {
            "Corrupt student mistake store: solution-step budget exceeded"
        }
        val attributionEntities =
            dao.readErrorAttributions(
                revisionId = revision.revisionId,
                limit = MAX_STORED_ERROR_ATTRIBUTIONS + 1,
            )
        check(attributionEntities.size <= MAX_STORED_ERROR_ATTRIBUTIONS) {
            "Corrupt student mistake store: error-attribution budget exceeded"
        }
        val evidenceEntities =
            dao.readErrorEvidence(
                revisionId = revision.revisionId,
                limit = MAX_STORED_ERROR_EVIDENCE + 1,
            )
        check(evidenceEntities.size <= MAX_STORED_ERROR_EVIDENCE) {
            "Corrupt student mistake store: error-evidence budget exceeded"
        }
        val attributionIds =
            attributionEntities.mapTo(hashSetOf()) {
                it.attributionId
            }
        check(evidenceEntities.all { it.attributionId in attributionIds }) {
            "Corrupt student mistake store: orphan error evidence"
        }
        val evidenceByAttribution = evidenceEntities.groupBy { it.attributionId }
        return StudentRevisionAuthority(
            solutionAnalysis =
                analysisEntity?.toDomain(
                    revision = revision,
                    steps = stepEntities,
                ),
            errorAttributions =
                attributionEntities.map { attribution ->
                    attribution.toDomain(
                        revision = revision,
                        evidence = evidenceByAttribution[attribution.attributionId].orEmpty(),
                    )
                },
        )
    }

    override fun close() {
        database.close()
    }

    internal suspend fun ensureStoreGeneration(nowEpochMillis: Long): String =
        dao.ensureStoreGeneration(
            StudentStoreMetadataEntity(
                metadataKey = STORE_GENERATION_METADATA_KEY,
                metadataValue = UUID.randomUUID().toString(),
                createdAtEpochMillis = nowEpochMillis,
                updatedAtEpochMillis = nowEpochMillis,
            ),
        )

    private suspend fun outboxIssuerForLearner(
        learnerId: String,
        sourceStoreGeneration: String,
    ): StudentOutboxAuthenticator {
        val state = checkNotNull(dao.readActiveOutboxAuthenticityKeyState()) {
            "Student outbox authenticity key-state is unavailable"
        }
        check(state.sourceStoreGeneration == sourceStoreGeneration) {
            "Student outbox authenticity key-state generation mismatch"
        }
        return StudentOutboxAuthenticator(
            learnerId,
            state.toActiveKey(),
            AndroidKeystoreStudentOutboxHmacKeyStore.INSTANCE,
        )
    }

    private suspend fun readCollectionState(
        ref: StudentProblemRef,
    ): Pair<StudentMistakeEntryState, Boolean> {
        val collection = dao.readCollection(ref.practiceUnitId)
        return Pair(
            collection?.mistakeState?.let { enumValueOrCorrupt(it, "mistake state") }
                ?: StudentMistakeEntryState.NONE,
            collection?.favorite ?: false,
        )
    }

    private suspend fun requirePersistedRevision(expected: StudentProblemRevisionRef) {
        requirePersistedRevisions(listOf(expected))
    }

    private suspend fun matchesPersistedRevision(
        expected: StudentProblemRevisionRef,
    ): Boolean {
        expected.problem.subject.requireHighSchoolSubject()
        val persisted = dao.readRevision(expected.revisionId) ?: return false
        check(
            persisted.problemId == expected.problem.problemId &&
                persisted.revisionNumber == expected.revisionNumber &&
                persisted.documentCanonicalFingerprint ==
                expected.documentCanonicalFingerprint,
        ) {
            "Problem revision reference does not match persisted immutable content"
        }
        return true
    }

    private suspend fun requireReviewEligibleRevisions(
        expectedRevisions: List<StudentProblemRevisionRef>,
    ) {
        requirePersistedRevisions(
            expectedRevisions = expectedRevisions,
            requireActiveMistakeEntry = true,
        )
    }

    private suspend fun requirePersistedRevisions(
        expectedRevisions: List<StudentProblemRevisionRef>,
        requireActiveMistakeEntry: Boolean = false,
    ) {
        if (expectedRevisions.isEmpty()) return
        val revisionIds =
            expectedRevisions
                .map(StudentProblemRevisionRef::revisionId)
                .distinct()
        val rows =
            dao.readRevisionReferenceRows(
                revisionIds = revisionIds,
                limit = revisionIds.size,
            ).associateBy(PersistedStudentProblemRevisionRefRow::revisionId)
        expectedRevisions.forEach { expected ->
            val row = checkNotNull(rows[expected.revisionId]) {
                "Problem revision does not exist"
            }
            check(
                row.learnerId == expected.problem.learnerId &&
                    row.subject == expected.problem.subject.name &&
                    row.problemId == expected.problem.problemId &&
                    row.practiceUnitId == expected.problem.practiceUnitId &&
                    row.revisionNumber == expected.revisionNumber &&
                    row.documentCanonicalFingerprint ==
                    expected.documentCanonicalFingerprint &&
                    row.lifecycleState != StudentProblemLifecycleState.TOMBSTONED.name,
            ) {
                "Problem revision reference does not match persisted immutable content"
            }
            if (requireActiveMistakeEntry) {
                check(
                    row.lifecycleState == StudentProblemLifecycleState.ACTIVE.name &&
                        row.mistakeState == StudentMistakeEntryState.ACTIVE.name,
                ) {
                    "Review is available only for an active mistake entry"
                }
            }
        }
    }
}
