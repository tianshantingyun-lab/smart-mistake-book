package com.tingyun.smartmistakebook.core.database

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tingyun.smartmistakebook.core.model.AssessmentEvidenceSnapshot
import com.tingyun.smartmistakebook.core.model.AssessmentSnapshotVerification
import com.tingyun.smartmistakebook.core.model.CalibrationSnapshot
import com.tingyun.smartmistakebook.core.model.CalibrationSupport
import com.tingyun.smartmistakebook.core.model.EvidenceAttributionCertainty
import com.tingyun.smartmistakebook.core.model.EvidenceAttributionRole
import com.tingyun.smartmistakebook.core.model.KnowledgeEvidenceAttribution
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProblemOrganizationDatabaseInstrumentedTest {
    private lateinit var store: RoomStudyDatabase

    @Before
    fun setUp() = runBlocking {
        store = StudyDatabaseFactory.openInMemory(ApplicationProvider.getApplicationContext())
        store.seedFixture(baseSeed())
        Unit
    }

    @After
    fun tearDown() {
        store.close()
    }

    @Test
    fun confirmationIsAtomicObservableAndIdempotent() = runBlocking {
        val command = command(commandId = "confirm-organization", fingerprint = "a".repeat(64))
        val catalogUpdate = async {
            store.observeMistakes().first { rows ->
                rows.first { it.problemId == PROBLEM }.knowledgeLabels.isNotEmpty()
            }
        }
        yield()

        val first = store.confirmProblemOrganization(command)
        val replay = store.confirmProblemOrganization(command)
        val confirmed = store.observeConfirmedProblemOrganization(PROBLEM, REVISION).first()
        val catalog = catalogUpdate.await().single { it.problemId == PROBLEM }

        assertTrue(first.created)
        assertFalse(replay.created)
        assertEquals(
            listOf("二次函数", "二次函数最值"),
            confirmed.classifications.map { it.displayName }.sorted(),
        )
        assertEquals(listOf(RELATED_PROBLEM), confirmed.relations.map { it.targetProblemId })
        assertEquals(setOf(KNOWLEDGE), confirmed.knowledgeNodeIds)
        assertEquals(listOf("二次函数最值"), catalog.knowledgeLabels)
    }

    @Test
    fun sameCommandIdWithDifferentPayloadFailsClosed() = runBlocking {
        store.confirmProblemOrganization(command("same-command", "b".repeat(64)))

        val failure = runCatching {
            store.confirmProblemOrganization(command("same-command", "c".repeat(64)))
        }.exceptionOrNull()

        assertTrue(failure is ImmutablePayloadConflictException)
        assertEquals(2, store.observeConfirmedProblemOrganization(PROBLEM, REVISION).first().classifications.size)
    }

    @Test
    fun newerConfirmationReplacesOldOrganizationForTheSameRevision() = runBlocking {
        store.confirmProblemOrganization(command("first-confirmation", "1".repeat(64)))
        val replacement = replacementCommand()

        val firstReplacement = store.confirmProblemOrganization(replacement)
        val replay = store.confirmProblemOrganization(replacement)
        val confirmed = store.observeConfirmedProblemOrganization(PROBLEM, REVISION).first()
        val catalog = store.observeMistakes().first().single { it.problemId == PROBLEM }

        assertTrue(firstReplacement.created)
        assertFalse(replay.created)
        assertEquals(
            listOf("函数", "函数零点"),
            confirmed.classifications.map { it.displayName }.sorted(),
        )
        assertEquals(listOf(RELATED_PROBLEM), confirmed.relations.map { it.targetProblemId })
        assertEquals(setOf("knowledge-replacement"), confirmed.knowledgeNodeIds)
        assertEquals(listOf("函数"), catalog.chapterLabels)
        assertEquals(listOf("函数零点"), catalog.knowledgeLabels)
    }

    @Test
    fun relationReplacementMustBeRequestedExplicitly() = runBlocking {
        store.confirmProblemOrganization(command("first-confirmation", "4".repeat(64)))
        val replacement = replacementCommand().copy(
            commandId = "replacement-with-relations",
            payloadFingerprint = "5".repeat(64),
            replaceRelations = true,
        )

        store.confirmProblemOrganization(replacement)

        assertTrue(
            store.observeConfirmedProblemOrganization(PROBLEM, REVISION).first().relations.isEmpty(),
        )
    }

    @Test
    fun currentAcceptanceSourcesAreWhitelistedButLegacyAndUnknownWritesFailClosed() = runBlocking {
        val automatic = command("automatic", "6".repeat(64)).copy(
            knowledgeBindings = command("unused-a", "7".repeat(64)).knowledgeBindings.map {
                it.copy(sourceType = "LOCAL_POLICY_ACCEPTED")
            },
            classifications = command("unused-b", "8".repeat(64)).classifications.map {
                it.copy(acceptanceSource = "LOCAL_POLICY_ACCEPTED")
            },
        )
        assertTrue(store.confirmProblemOrganization(automatic).created)

        val legacy = replacementCommand().copy(
            commandId = "legacy-source",
            payloadFingerprint = "f".repeat(64),
            knowledgeBindings = replacementCommand().knowledgeBindings.map {
                it.copy(sourceType = "USER_CONFIRMED")
            },
            classifications = replacementCommand().classifications.map {
                it.copy(acceptanceSource = "USER_CONFIRMED")
            },
        )
        assertTrue(runCatching { store.confirmProblemOrganization(legacy) }.isFailure)

        val unknown = replacementCommand().copy(
            commandId = "unknown-source",
            payloadFingerprint = "0".repeat(64),
            classifications = replacementCommand().classifications.map {
                it.copy(acceptanceSource = "MODEL_DIRECT")
            },
        )
        assertTrue(runCatching { store.confirmProblemOrganization(unknown) }.isFailure)
    }

    @Test
    fun deprecatedRelationKindIsRejectedForNewOrganizationWrites() = runBlocking {
        val deprecated = command("deprecated-relation", "7".repeat(64)).copy(
            relations = command("unused-deprecated", "8".repeat(64)).relations.map {
                it.copy(relationType = StudyDbValue.RelationType.SAME_ERROR_PATTERN)
            },
        )

        assertTrue(runCatching { store.confirmProblemOrganization(deprecated) }.isFailure)
    }

    @Test
    fun relationMergeAddsNewFactsWithoutDeletingExistingOnes() = runBlocking {
        val existing = command("existing-relation", "8".repeat(64))
        store.confirmProblemOrganization(existing)
        val merged = replacementCommand().copy(
            commandId = "merge-relation",
            payloadFingerprint = "9".repeat(64),
            relations = listOf(
                existing.relations.single().copy(
                    relationId = "relation-same-knowledge",
                    relationType = StudyDbValue.RelationType.SAME_KNOWLEDGE,
                    createdAtEpochMillis = 3_000,
                    updatedAtEpochMillis = 3_000,
                ),
            ),
            replaceRelations = false,
        )

        store.confirmProblemOrganization(merged)

        assertEquals(
            setOf(StudyDbValue.RelationType.VARIANT_OF, StudyDbValue.RelationType.SAME_KNOWLEDGE),
            store.observeConfirmedProblemOrganization(PROBLEM, REVISION).first()
                .relations.mapTo(linkedSetOf()) { it.relationType },
        )

        val removeOnlyExisting = replacementCommand().copy(
            commandId = "remove-one-relation",
            payloadFingerprint = "a".repeat(64),
            acceptedAtEpochMillis = 4_000,
            knowledgeNodes = replacementCommand().knowledgeNodes.map {
                it.copy(createdAtEpochMillis = 4_000)
            },
            knowledgeBindings = replacementCommand().knowledgeBindings.map {
                it.copy(acceptedAtEpochMillis = 4_000)
            },
            classifications = replacementCommand().classifications.map {
                it.copy(acceptedAtEpochMillis = 4_000)
            },
            relations = emptyList(),
            relationIdsToRemove = setOf(existing.relations.single().relationId),
        )
        store.confirmProblemOrganization(removeOnlyExisting)

        assertEquals(
            listOf(StudyDbValue.RelationType.SAME_KNOWLEDGE),
            store.observeConfirmedProblemOrganization(PROBLEM, REVISION).first()
                .relations.map { it.relationType },
        )
    }

    @Test
    fun automaticAuthorityCannotReplaceUserCorrectionEvenWithANewerTimestamp() = runBlocking {
        store.confirmProblemOrganization(command("user-authority", "b".repeat(64)))
        val automatic = replacementCommand().copy(
            commandId = "automatic-after-user",
            payloadFingerprint = "c".repeat(64),
            acceptedAtEpochMillis = 4_000,
            knowledgeNodes = replacementCommand().knowledgeNodes.map {
                it.copy(createdAtEpochMillis = 4_000)
            },
            knowledgeBindings = replacementCommand().knowledgeBindings.map {
                it.copy(
                    sourceType = "LOCAL_POLICY_ACCEPTED",
                    acceptedAtEpochMillis = 4_000,
                )
            },
            classifications = replacementCommand().classifications.map {
                it.copy(
                    acceptanceSource = "LOCAL_POLICY_ACCEPTED",
                    acceptedAtEpochMillis = 4_000,
                )
            },
        )

        val failure = runCatching { store.confirmProblemOrganization(automatic) }.exceptionOrNull()

        assertTrue(failure is ProblemOrganizationAuthorityConflictException)
        assertEquals(
            listOf("二次函数", "二次函数最值"),
            store.observeConfirmedProblemOrganization(PROBLEM, REVISION).first()
                .classifications.map { it.displayName }.sorted(),
        )
    }

    @Test
    fun userCorrectionReusesLegacyAutomaticKnowledgeNodeIdentity() = runBlocking {
        val legacyAutomatic = command("legacy-automatic-node", "d".repeat(64)).copy(
            knowledgeNodes = command("unused-node", "e".repeat(64)).knowledgeNodes.map {
                it.copy(taxonomyVersion = "local-policy-v1")
            },
            knowledgeBindings = command("unused-binding", "f".repeat(64)).knowledgeBindings.map {
                it.copy(sourceType = "LOCAL_POLICY_ACCEPTED", taxonomyVersion = "local-policy-v1")
            },
            classifications = command("unused-classification", "0".repeat(64)).classifications.map {
                it.copy(acceptanceSource = "LOCAL_POLICY_ACCEPTED", taxonomyVersion = "local-policy-v1")
            },
        )
        store.confirmProblemOrganization(legacyAutomatic)
        val corrected = command("corrected-same-node", "1".repeat(64)).copy(
            acceptedAtEpochMillis = 3_000,
            knowledgeNodes = legacyAutomatic.knowledgeNodes.map {
                it.copy(taxonomyVersion = "organization-v1", createdAtEpochMillis = 3_000)
            },
            knowledgeBindings = legacyAutomatic.knowledgeBindings.map {
                it.copy(
                    sourceType = "USER_CORRECTED",
                    taxonomyVersion = "user-corrected-v1",
                    acceptedAtEpochMillis = 3_000,
                )
            },
            classifications = legacyAutomatic.classifications.map {
                it.copy(
                    acceptanceSource = "USER_CORRECTED",
                    taxonomyVersion = "user-corrected-v1",
                    acceptedAtEpochMillis = 3_000,
                )
            },
            relations = emptyList(),
        )

        store.confirmProblemOrganization(corrected)

        assertTrue(
            store.observeConfirmedProblemOrganization(PROBLEM, REVISION).first()
                .classifications.all { it.acceptanceSource == "USER_CORRECTED" },
        )
    }

    @Test
    fun userConfirmationPromotesAModelCandidateNodeWithoutConflictingOnItsPayload() = runBlocking {
        // A policy/model write parks the node as an unverified candidate.
        store.confirmProblemOrganization(command("candidate-node", "2".repeat(64)))
        assertEquals(
            "MODEL_CANDIDATE",
            store.readKnowledgeNodesByIds(setOf(KNOWLEDGE)).single().verificationStatus,
        )

        // The student confirming the same label writes the identical node fact
        // with the stronger status. That must upgrade the existing row rather
        // than fail the immutable-payload check (audit 2026-09-09).
        val base = command("user-confirmed-node", "3".repeat(64))
        val confirmed = base.copy(
            acceptedAtEpochMillis = 3_000,
            knowledgeNodes = base.knowledgeNodes.map { node ->
                node.copy(verificationStatus = "USER_CONFIRMED", createdAtEpochMillis = 3_000)
            },
            knowledgeBindings = base.knowledgeBindings.map { binding ->
                binding.copy(acceptedAtEpochMillis = 3_000)
            },
            classifications = base.classifications.map { classification ->
                classification.copy(acceptedAtEpochMillis = 3_000)
            },
            relations = emptyList(),
        )
        store.confirmProblemOrganization(confirmed)

        assertEquals(
            "USER_CONFIRMED",
            store.readKnowledgeNodesByIds(setOf(KNOWLEDGE)).single().verificationStatus,
        )
    }

    @Test
    fun reclassificationRetainsBindingsReferencedByLearningEvidence() = runBlocking {
        val initial = command("evidence-initial", "a".repeat(64))
        store.confirmProblemOrganization(initial)
        store.saveAssessmentEvidenceSnapshot(evidenceSnapshot(initial))

        store.confirmProblemOrganization(replacementCommand())

        assertEquals(
            listOf("函数", "函数零点"),
            store.observeConfirmedProblemOrganization(PROBLEM, REVISION).first()
                .classifications.map { it.displayName }.sorted(),
        )
    }

    @Test
    fun delayedOlderConfirmationCannotRollBackANewerCorrection() = runBlocking {
        store.confirmProblemOrganization(
            command("initial-confirmation-with-relation", "a".repeat(64)),
        )
        store.confirmProblemOrganization(replacementCommand())

        val failure = runCatching {
            store.confirmProblemOrganization(command("delayed-old-confirmation", "3".repeat(64)))
        }.exceptionOrNull()
        val confirmed = store.observeConfirmedProblemOrganization(PROBLEM, REVISION).first()

        assertTrue(failure is ImmutablePayloadConflictException)
        assertEquals(
            listOf("函数", "函数零点"),
            confirmed.classifications.map { it.displayName }.sorted(),
        )
        assertEquals(listOf(RELATED_PROBLEM), confirmed.relations.map { it.targetProblemId })
    }

    @Test
    fun invalidTargetRollsBackEveryFormalFact() = runBlocking {
        val invalid = command("invalid-target", "d".repeat(64)).copy(
            relations = command("unused", "e".repeat(64)).relations.map {
                it.copy(
                    targetProblemId = "missing-problem",
                    targetBasisRevisionId = "missing-revision",
                )
            },
        )

        assertTrue(runCatching { store.confirmProblemOrganization(invalid) }.isFailure)
        val confirmed = store.observeConfirmedProblemOrganization(PROBLEM, REVISION).first()
        assertTrue(confirmed.classifications.isEmpty())
        assertTrue(confirmed.relations.isEmpty())
    }

    @Test
    fun versionSevenMigratesAndAcceptsOrganizationFacts() = runBlocking {
        store.close()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "problem-organization-migration-${System.nanoTime()}.db"
        context.deleteDatabase(databaseName)
        try {
            createDatabaseFromExportedSchema(context, databaseName, version = 7)
            val migrated = StudyDatabaseFactory.open(context, databaseName)
            migrated.seedFixture(baseSeed())
            assertTrue(
                migrated.confirmProblemOrganization(
                    command("migration-confirm", "9".repeat(64)),
                ).created,
            )
            assertEquals(
                2,
                migrated.observeConfirmedProblemOrganization(PROBLEM, REVISION)
                    .first().classifications.size,
            )
            migrated.close()
        } finally {
            context.deleteDatabase(databaseName)
            store = StudyDatabaseFactory.openInMemory(context)
        }
    }

    private fun command(commandId: String, fingerprint: String) =
        ConfirmProblemOrganizationCommand(
            commandId = commandId,
            payloadFingerprint = fingerprint,
            problemId = PROBLEM,
            problemRevisionId = REVISION,
            practiceUnitId = PRACTICE,
            knowledgeNodes = listOf(
                KnowledgeNodeSeedRecord(
                    knowledgeNodeId = KNOWLEDGE,
                    stableCode = "math:knowledge:test",
                    subject = "MATH",
                    displayName = "二次函数最值",
                    parentKnowledgeNodeId = null,
                    taxonomyVersion = "user-corrected-v1",
                    createdAtEpochMillis = 2_000,
                ),
            ),
            knowledgeBindings = listOf(
                KnowledgeBindingSeedRecord(
                    bindingId = "binding-test",
                    practiceUnitId = PRACTICE,
                    knowledgeNodeId = KNOWLEDGE,
                    basisRevisionId = REVISION,
                    strength = 1.0,
                    sourceType = "USER_CORRECTED",
                    taxonomyVersion = "user-corrected-v1",
                    acceptedAtEpochMillis = 2_000,
                ),
            ),
            classifications = listOf(
                ProblemClassificationBindingRecord(
                    bindingId = "classification-chapter-test",
                    problemId = PROBLEM,
                    basisRevisionId = REVISION,
                    dimension = "CHAPTER",
                    labelId = "math:chapter:quadratic",
                    displayName = "二次函数",
                    taxonomyVersion = "user-corrected-v1",
                    acceptanceSource = "USER_CORRECTED",
                    acceptedAtEpochMillis = 2_000,
                ),
                ProblemClassificationBindingRecord(
                    bindingId = "classification-test",
                    problemId = PROBLEM,
                    basisRevisionId = REVISION,
                    dimension = "KNOWLEDGE",
                    labelId = "math:knowledge:test",
                    displayName = "二次函数最值",
                    taxonomyVersion = "user-corrected-v1",
                    acceptanceSource = "USER_CORRECTED",
                    acceptedAtEpochMillis = 2_000,
                ),
            ),
            relations = listOf(
                ProblemRelationSeedRecord(
                    relationId = "relation-test",
                    sourceProblemId = PROBLEM,
                    targetProblemId = RELATED_PROBLEM,
                    relationType = StudyDbValue.RelationType.VARIANT_OF,
                    status = StudyDbValue.RelationStatus.ACTIVE,
                    sourceBasisRevisionId = REVISION,
                    targetBasisRevisionId = RELATED_REVISION,
                    confidence = 0.8,
                    createdAtEpochMillis = 2_000,
                    updatedAtEpochMillis = 2_000,
                ),
            ),
            acceptedAtEpochMillis = 2_000,
        )

    private fun replacementCommand() = ConfirmProblemOrganizationCommand(
        commandId = "replacement-confirmation",
        payloadFingerprint = "2".repeat(64),
        problemId = PROBLEM,
        problemRevisionId = REVISION,
        practiceUnitId = PRACTICE,
        knowledgeNodes = listOf(
            KnowledgeNodeSeedRecord(
                knowledgeNodeId = "knowledge-replacement",
                stableCode = "math:knowledge:root",
                subject = "MATH",
                displayName = "函数零点",
                parentKnowledgeNodeId = null,
                taxonomyVersion = "user-corrected-v1",
                createdAtEpochMillis = 3_000,
            ),
        ),
        knowledgeBindings = listOf(
            KnowledgeBindingSeedRecord(
                bindingId = "binding-replacement",
                practiceUnitId = PRACTICE,
                knowledgeNodeId = "knowledge-replacement",
                basisRevisionId = REVISION,
                strength = 1.0,
                sourceType = "USER_CORRECTED",
                taxonomyVersion = "user-corrected-v1",
                acceptedAtEpochMillis = 3_000,
            ),
        ),
        classifications = listOf(
            ProblemClassificationBindingRecord(
                bindingId = "classification-replacement-knowledge",
                problemId = PROBLEM,
                basisRevisionId = REVISION,
                dimension = "KNOWLEDGE",
                labelId = "math:knowledge:root",
                displayName = "函数零点",
                taxonomyVersion = "user-corrected-v1",
                acceptanceSource = "USER_CORRECTED",
                acceptedAtEpochMillis = 3_000,
            ),
            ProblemClassificationBindingRecord(
                bindingId = "classification-replacement-chapter",
                problemId = PROBLEM,
                basisRevisionId = REVISION,
                dimension = "CHAPTER",
                labelId = "math:chapter:function",
                displayName = "函数",
                taxonomyVersion = "user-corrected-v1",
                acceptanceSource = "USER_CORRECTED",
                acceptedAtEpochMillis = 3_000,
            ),
        ),
        relations = emptyList(),
        acceptedAtEpochMillis = 3_000,
    )

    private fun evidenceSnapshot(
        command: ConfirmProblemOrganizationCommand,
    ): AssessmentEvidenceSnapshot {
        val binding = command.knowledgeBindings.single()
        return AssessmentEvidenceSnapshot(
            snapshotId = "organization-evidence",
            assessmentItemId = "organization-item",
            practiceUnitId = PRACTICE,
            problemRevisionId = REVISION,
            answerSpecId = "organization-answer",
            itemFamilyId = "organization-family",
            sourceBundleId = null,
            taxonomyVersion = binding.taxonomyVersion,
            verification = AssessmentSnapshotVerification.VERIFIED,
            calibration = CalibrationSnapshot(
                support = CalibrationSupport.SUPPORTED,
                sourceId = "organization-calibration",
                version = "v1",
                validFromEpochMillis = 1,
                validUntilEpochMillis = 10_000,
            ),
            attributions = listOf(
                KnowledgeEvidenceAttribution(
                    bindingId = binding.bindingId,
                    knowledgeNodeId = binding.knowledgeNodeId,
                    weight = 1.0,
                    basisRevisionId = REVISION,
                    taxonomyVersion = binding.taxonomyVersion,
                    role = EvidenceAttributionRole.PRIMARY,
                    certainty = EvidenceAttributionCertainty.DIRECT,
                ),
            ),
            capturedAtEpochMillis = 2_500,
        )
    }

    private fun baseSeed() = StudySeedBundle(
        problems = listOf(
            problem(PROBLEM, "f".repeat(64)),
            problem(RELATED_PROBLEM, "e".repeat(64)),
        ),
        revisions = listOf(
            revision(REVISION, PROBLEM, "d".repeat(64)),
            revision(RELATED_REVISION, RELATED_PROBLEM, "c".repeat(64)),
        ),
        practiceUnits = listOf(
            practice(PRACTICE, PROBLEM, REVISION),
            practice(RELATED_PRACTICE, RELATED_PROBLEM, RELATED_REVISION),
        ),
        errorBookEntries = listOf(
            entry("entry-main", PRACTICE, PROBLEM, REVISION),
            entry("entry-related", RELATED_PRACTICE, RELATED_PROBLEM, RELATED_REVISION),
        ),
    )

    private fun problem(id: String, fingerprint: String) = ProblemSeedRecord(
        problemId = id,
        canonicalFingerprint = fingerprint,
        subject = "MATH",
        createdAtEpochMillis = 1_000,
    )

    private fun revision(id: String, problemId: String, fingerprint: String) =
        ProblemRevisionSeedRecord(
            revisionId = id,
            problemId = problemId,
            revisionNumber = 1,
            title = "测试题",
            problemMarkdown = "求函数最值。",
            questionDocumentSnapshot = null,
            answerSpecId = null,
            answerSpecSnapshot = null,
            answerVerificationStatus = "UNKNOWN",
            sourceType = "TEST",
            sourceReference = null,
            contentFingerprint = fingerprint,
            createdAtEpochMillis = 1_000,
        )

    private fun practice(id: String, problemId: String, revisionId: String) =
        PracticeUnitSeedRecord(
            practiceUnitId = id,
            problemId = problemId,
            problemRevisionId = revisionId,
            unitKey = "unit:$id",
            unitKind = "PROBLEM",
            title = "测试题",
            promptMarkdown = "求函数最值。",
            estimatedSeconds = 180,
            createdAtEpochMillis = 1_000,
        )

    private fun entry(id: String, practiceId: String, problemId: String, revisionId: String) =
        ErrorBookEntrySeedRecord(
            entryId = id,
            practiceUnitId = practiceId,
            problemId = problemId,
            currentRevisionId = revisionId,
            sourceKey = null,
            status = StudyDbValue.ErrorBookStatus.ACTIVE,
            acceptedAtEpochMillis = 1_000,
            updatedAtEpochMillis = 1_000,
        )

    private companion object {
        const val PROBLEM = "problem-main"
        const val REVISION = "revision-main"
        const val PRACTICE = "practice-main"
        const val RELATED_PROBLEM = "problem-related"
        const val RELATED_REVISION = "revision-related"
        const val RELATED_PRACTICE = "practice-related"
        const val KNOWLEDGE = "knowledge-test"
    }
}
