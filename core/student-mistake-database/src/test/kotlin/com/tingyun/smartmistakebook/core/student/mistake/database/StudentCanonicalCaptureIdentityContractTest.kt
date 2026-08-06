package com.tingyun.smartmistakebook.core.student.mistake.database

import com.tingyun.smartmistakebook.core.model.NormalizedSourceRegion
import java.io.File
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StudentCanonicalCaptureIdentityContractTest {
    @Test
    fun captureCommandCannotSubmitAStableIdentityAndResolutionIsOutputOnly() {
        val commandClass = SaveStudentCaptureOccurrenceCommand::class.java
        val identityClass = StudentProblemCanonicalIdentityKey::class.java

        assertTrue(
            commandClass.declaredFields.none {
                it.name in setOf("canonicalIdentity", "resolvedIdentity", "stableKey")
            },
        )
        assertTrue(
            commandClass.declaredConstructors.all { constructor ->
                constructor.parameterTypes.none(identityClass::equals)
            },
        )
        assertEquals(
            identityClass,
            StudentCaptureOccurrenceReceipt::class.java
                .getDeclaredMethod("getResolvedIdentity")
                .returnType,
        )

        val source = captureContractSource()
        assertTrue(
            Regex(
                """class\s+StudentProblemCanonicalIdentityKey\s+internal\s+constructor\s*\(""",
            ).containsMatchIn(source),
        )
        val command =
            source.segment(
                "data class SaveStudentCaptureOccurrenceCommand(",
                "data class StudentCaptureOccurrenceReceipt(",
            )
        assertFalse("StudentProblemCanonicalIdentityKey" in command)
        assertFalse("stableKey" in command)
        assertTrue(
            Regex(
                """identityEvidence:\s*StudentProblemIdentityEvidence\s*=\s*""" +
                    """StudentProblemIdentityEvidence\.Unresolved""",
            ).containsMatchIn(command),
        )
    }

    @Test
    fun validExactOrAliasDocumentFingerprintCannotCarryTamperedTitleOrStem() {
        val command =
            captureContractSource().segment(
                "data class SaveStudentCaptureOccurrenceCommand(",
                "data class StudentCaptureOccurrenceReceipt(",
            )
        val capturedDocument =
            command.indexOf(
                "checkNotNull(capture.target.problem.capturedQuestionDocument)",
            )
        val exactTitle =
            command.indexOf(
                "capture.target.problem.title == capturedDocument.document.title",
            )
        val exactStem =
            command.indexOf(
                "capture.target.problem.stemMarkdown ==",
            )
        val structuredProjection =
            command.indexOf(
                "QuestionDocumentMarkdownProjection.project(capturedDocument.document)",
            )
        val failure =
            command.indexOf(
                "Atomic capture title and stem must be exact projections of its captured document",
            )
        val identityResolution =
            command.indexOf(
                "identityEvidence == StudentProblemIdentityEvidence.ExactAssetSelection",
            )

        assertTrue(capturedDocument >= 0)
        assertTrue(exactTitle > capturedDocument)
        assertTrue(exactStem > exactTitle)
        assertTrue(structuredProjection > exactStem)
        assertTrue(failure > structuredProjection)
        assertTrue(identityResolution > failure)
    }

    @Test
    fun exactAssetAndReviewedAliasRequireAtLeastOneAsset() {
        val command =
            captureContractSource().segment(
                "data class SaveStudentCaptureOccurrenceCommand(",
                "data class StudentCaptureOccurrenceReceipt(",
            )
        val exactSelectionBranch =
            captureAdapterSource().segment(
                "StudentProblemIdentityEvidence.ExactAssetSelection ->",
                "is StudentProblemIdentityEvidenceAuthority.TrustedSourceEvidence ->",
            )
        val guardSurface = command + exactSelectionBranch

        assertTrue(
            "identityEvidence == StudentProblemIdentityEvidence.ExactAssetSelection" in
                command,
        )
        assertTrue(
            "identityEvidence is\n            StudentProblemIdentityEvidenceAuthority.ReviewedAliasEvidence" in
                command,
        )
        assertTrue(
            "Exact-asset and reviewed-alias identity must reject an empty manifest",
            Regex(
                """(?s)require\s*\([^)]*(?:originalImages|images|assetManifest)""" +
                    """[^)]*isNotEmpty\s*\(\s*\)""",
            ).containsMatchIn(guardSurface),
        )
    }

    @Test
    fun assetManifestExcludesLocalUriAndProvisionalImageId() {
        val first =
            image(
                imageReferenceId = "provisional-image-a",
                localContentUri = "content://capture/session-a/image-a",
            )
        val relocated =
            image(
                imageReferenceId = "provisional-image-b",
                localContentUri = "file:///private/cache/relocated-image.png",
            )

        assertEquals(
            studentCaptureAssetManifestCanonicalFingerprint(listOf(first)),
            studentCaptureAssetManifestCanonicalFingerprint(listOf(relocated)),
        )
    }

    @Test
    fun emptySelectedRegionsUseAnExplicitFullFrameIdentity() {
        val implicitFullFrame = image()
        val explicitFullFrame =
            image(
                selectedRegions =
                    listOf(
                        NormalizedSourceRegion(
                            left = 0.0,
                            top = 0.0,
                            right = 1.0,
                            bottom = 1.0,
                        ),
                    ),
            )

        assertTrue(
            """STUDENT_CAPTURE_FULL_FRAME_REGION_MARKER = "FULL_FRAME_V1"""" in
                captureContractSource(),
        )
        assertNotEquals(
            studentCaptureSelectedRegionCanonicalFingerprint(
                listOf(implicitFullFrame),
            ),
            studentCaptureSelectedRegionCanonicalFingerprint(
                listOf(explicitFullFrame),
            ),
        )
    }

    @Test
    fun selectedRegionOrderChangesTheCanonicalFingerprint() {
        val upperLeft =
            NormalizedSourceRegion(
                left = 0.05,
                top = 0.10,
                right = 0.45,
                bottom = 0.40,
            )
        val lowerRight =
            NormalizedSourceRegion(
                left = 0.55,
                top = 0.60,
                right = 0.95,
                bottom = 0.90,
            )

        val forward =
            studentCaptureSelectedRegionCanonicalFingerprint(
                listOf(image(selectedRegions = listOf(upperLeft, lowerRight))),
            )
        val reversed =
            studentCaptureSelectedRegionCanonicalFingerprint(
                listOf(image(selectedRegions = listOf(lowerRight, upperLeft))),
            )

        assertNotEquals(forward, reversed)
    }

    @Test
    fun identityReceiptsAndProofsAreOpaqueOwnerIssuedCapabilities() {
        val capabilityClasses =
            listOf(
                StudentProblemIdentityEvidenceAuthority.ReceiptReference::class.java,
                StudentProblemIdentityEvidenceAuthority.TrustedSourceProof::class.java,
                StudentProblemIdentityEvidenceAuthority.ReviewedAliasProof::class.java,
            )
        assertTrue(
            capabilityClasses.all { capabilityClass ->
                capabilityClass.declaredConstructors.all {
                    Modifier.isPrivate(it.modifiers)
                }
            },
        )

        val issuerClass =
            StudentProblemIdentityEvidenceAuthority.Issuer::class.java
        val ownerClass = StudentProblemIdentityEvidenceOwner::class.java
        val verifierClass =
            StudentProblemIdentityEvidenceAuthority.Verifier::class.java
        val roomOccurrenceClass =
            Class.forName(
                "com.tingyun.smartmistakebook.core.student.mistake.database." +
                    "RoomLearnerBoundStudentCaptureOccurrencePort",
            )
        assertFalse(Modifier.isPublic(ownerClass.modifiers))
        assertFalse(Modifier.isPublic(roomOccurrenceClass.modifiers))
        assertFalse(Modifier.isPublic(StudentProblemIdentityEvidenceAuthority::class.java.modifiers))
        assertFalse(Modifier.isPublic(verifierClass.modifiers))
        assertFalse(Modifier.isPublic(issuerClass.modifiers))
        assertTrue(
            StudentMistakeRuntimeCapabilities::class.java.methods.none { method ->
                method.returnType == issuerClass ||
                    method.parameterTypes.any(issuerClass::equals)
            },
        )
        assertTrue(
            StudentMistakeRuntimeCapabilities::class.java.declaredMethods
                .filter { method ->
                    method.returnType == issuerClass ||
                        method.parameterTypes.any(issuerClass::equals)
                }
                .all { method -> !Modifier.isPublic(method.modifiers) },
        )
        assertFalse(
            Modifier.isPublic(
                StudentProblemIdentityEvidenceAuthority::class.java
                    .declaredMethods
                    .single { it.name == "getIssuer" }
                    .modifiers,
            ),
        )
        assertTrue(
            issuerClass.declaredMethods
                .filter { it.name.startsWith("issue") }
                .all { !Modifier.isPublic(it.modifiers) },
        )
        assertTrue(
            StudentProblemIdentityEvidenceAuthority.ReviewedAliasProof::class.java
                .declaredMethods
                .any { it.name == "getReviewDecisionCanonicalFingerprint" },
        )
        assertTrue(ownerClass.declaredMethods.none { Modifier.isPublic(it.modifiers) })
        assertTrue(
            StudentMistakeRuntimeCapabilities::class.java.methods.none { method ->
                method.name in
                    setOf(
                        "getBusinessStore",
                        "getCaptureSaves",
                        "getErrorOccurrences",
                        "getVerifier",
                    )
            },
        )
        assertTrue(
            StudentMistakeRoomDatabase::class.java.declaredMethods
                .filter { it.name == "identityReceiptDao" }
                .all { !Modifier.isPublic(it.modifiers) },
        )
    }

    @Test
    fun reviewedAliasUsesItsOwnAppendOnlySemanticSourceBindingKey() {
        val contract = captureContractSource()
        val evidenceKinds =
            contract.segment(
                "internal enum class StudentProblemIdentityEvidenceKind {",
                "internal data class StudentProblemIdentityEvidenceDraft(",
            )
        assertTrue("REVIEWED_ALIAS" in evidenceKinds)

        val aliasAdapter =
            captureAdapterSource().segment(
                "is StudentProblemIdentityEvidenceAuthority.ReviewedAliasEvidence ->",
                "private fun StudentCaptureSaveSource.toAtomicCaptureHandoffEntity(",
            )
        assertTrue(
            "StudentProblemIdentityEvidenceKind.REVIEWED_ALIAS" in aliasAdapter,
        )
        assertFalse(
            "StudentProblemIdentityEvidenceKind.EXACT_ASSET_SELECTION" in
                aliasAdapter,
        )

        val semanticFingerprint =
            contract.segment(
                "internal fun canonicalStudentProblemIdentityEvidenceFingerprint(",
                "fun studentCaptureAssetManifestCanonicalFingerprint(",
            )
        listOf(
            "assetManifestCanonicalFingerprint",
            "selectedRegionCanonicalFingerprint",
            "candidateDocumentCanonicalFingerprint",
            "aliasIdentityCanonicalFingerprint",
            "reviewCaseId",
            "reviewRevision",
            "reviewDecisionCanonicalFingerprint",
        ).forEach { field ->
            assertTrue(
                "Reviewed-alias semantic key omitted $field",
                "\"$field\"" in semanticFingerprint,
            )
        }
        assertTrue(
            semanticFingerprint
                .windowed("StudentProblemIdentityEvidenceKind.REVIEWED_ALIAS".length)
                .count {
                    it == "StudentProblemIdentityEvidenceKind.REVIEWED_ALIAS"
                } >= 7,
        )
        assertFalse("reviewedAliasProofFingerprint" in semanticFingerprint)
    }

    @Test
    fun transactionReplayPrecedesFreshOpaqueUuidIssuance() {
        val source = studentMistakeDaoSource()
        val transaction =
            source.segment(
                "open suspend fun saveStudentCaptureOccurrence(",
                "private suspend fun resolveAtomicCaptureIdentity(",
            )
        val collisionRead =
            transaction.indexOf("readCaptureOccurrenceTransactionCollisions(")
        val replay =
            transaction.indexOf("return requireExactAtomicCaptureReplay(")
        val resolution =
            transaction.indexOf("resolveAtomicCaptureIdentity(bundle)")

        assertTrue(collisionRead >= 0)
        assertTrue(replay > collisionRead)
        assertTrue(resolution > replay)
        assertFalse("UUID.randomUUID()" in transaction)
        assertTrue(
            "UUID.randomUUID()" in
                source.segment(
                    "private suspend fun resolveAtomicCaptureIdentity(",
                    "private suspend fun readCanonicalIdentityForBinding(",
                ),
        )
    }

    @Test
    fun sourceAndExactAssetMismatchRequiresAReviewedCorrectionBeforeReuse() {
        val source = studentMistakeDaoSource()
        val resolution =
            source.segment(
                "private suspend fun resolveAtomicCaptureIdentity(",
                "private suspend fun readCanonicalIdentityForBinding(",
            )
        val contentMismatch =
            "Canonical identity evidence changed content; " +
                "internal alias/revision review is required"
        val assetMismatch =
            "Exact asset identity evidence changed bytes or selected-region order"

        assertTrue(contentMismatch in resolution)
        assertTrue(assetMismatch in resolution)
        assertTrue(
            resolution.indexOf(
                "existingBinding.documentCanonicalFingerprint ==",
            ) > resolution.indexOf("readReviewedAliasCorrectionBindings("),
        )
        assertTrue(
            resolution.indexOf("readReviewedAliasCorrectionBindings(") <
                resolution.indexOf(contentMismatch),
        )
        assertTrue(
            resolution.indexOf(
                "existingBinding.assetManifestCanonicalFingerprint ==",
            ) < resolution.indexOf(assetMismatch),
        )
        assertTrue(
            resolution.indexOf(
                "existingBinding.selectedRegionCanonicalFingerprint ==",
            ) < resolution.indexOf(assetMismatch),
        )
        assertTrue(
            resolution.indexOf(contentMismatch) <
                resolution.indexOf(
                    "targetMode = ResolvedAtomicCaptureTargetMode.REUSE_REVISION",
                ),
        )

        val correctionQuery =
            source.segment(
                "protected abstract suspend fun readCanonicalProblemSourceBinding(",
                "protected abstract suspend fun insertCanonicalProblemIdentity(",
            )
        assertTrue("readReviewedAliasCorrectionBindings(" in correctionQuery)
        assertFalse("identity_namespace = :identityNamespace" in correctionQuery)
        assertFalse("identity_version = :identityVersion" in correctionQuery)
        assertFalse("identity_stable_key = :identityStableKey" in correctionQuery)
        assertTrue("evidence_kind = :reviewedAliasEvidenceKind" in correctionQuery)
        assertTrue(
            "asset_manifest_canonical_fingerprint =" in correctionQuery,
        )
        assertTrue(
            "selected_region_canonical_fingerprint =" in correctionQuery,
        )
        assertTrue(
            "document_canonical_fingerprint =" in correctionQuery,
        )
        assertTrue("review_case_id IS NOT NULL" in correctionQuery)
        assertTrue("review_revision IS NOT NULL" in correctionQuery)
        assertTrue(
            "review_decision_canonical_fingerprint IS NOT NULL" in
                correctionQuery,
        )

        val correctionLookup =
            resolution.segment(
                "val reviewedCorrectionBindings =",
                "val aliasIdentity =",
            )
        assertTrue(
            "evidence.resolutionKind !=" in correctionLookup,
        )
        assertTrue(
            "StudentProblemIdentityResolutionKind.FRESH_OPAQUE" in
                correctionLookup,
        )

        val correctionFallback =
            resolution.segment(
                "if (existingBinding == null && reviewedCorrectionBindings.isNotEmpty())",
                "check(existingBinding == null)",
            )
        assertTrue("it.identityNamespace" in correctionFallback)
        assertTrue("it.identityVersion" in correctionFallback)
        assertTrue("it.identityStableKey" in correctionFallback)
        assertTrue("it.boundRevisionId" in correctionFallback)
        assertTrue(
            "Reviewed alias evidence conflicts across canonical identities" in
                correctionFallback,
        )
        assertTrue("insertSourceBinding = true" in correctionFallback)
    }

    @Test
    fun canonicalAssetsResolveAcrossExactAndTrustedModesBeforeUuidMint() {
        val source = studentMistakeDaoSource()
        val queries =
            source.segment(
                "protected abstract suspend fun readCanonicalProblemSourceBinding(",
                "protected abstract suspend fun insertCanonicalProblemIdentity(",
            )
        assertTrue("readCanonicalProblemAssetBindings(" in queries)
        assertTrue(
            "evidence_kind IN (:exactAssetEvidenceKind, :trustedSourceEvidenceKind)" in
                queries,
        )
        assertTrue(
            "asset_manifest_canonical_fingerprint =" in queries,
        )
        assertTrue(
            "selected_region_canonical_fingerprint =" in queries,
        )

        val resolution =
            source.segment(
                "private suspend fun resolveAtomicCaptureIdentity(",
                "private suspend fun readCanonicalIdentityForBinding(",
            )
        val assetLookup = resolution.indexOf("val canonicalAssetBindings =")
        val crossModeResolution =
            resolution.indexOf(
                "if (existingBinding == null && canonicalAssetBindings.isNotEmpty())",
            )
        val uuidMint = resolution.indexOf("UUID.randomUUID()")
        assertTrue(assetLookup >= 0)
        assertTrue(crossModeResolution > assetLookup)
        assertTrue(uuidMint > crossModeResolution)

        val crossModeBranch =
            resolution.segment(
                "if (existingBinding == null && canonicalAssetBindings.isNotEmpty())",
                "if (existingBinding == null && reviewedCorrectionBindings.isNotEmpty())",
            )
        assertTrue("it.identityNamespace" in crossModeBranch)
        assertTrue("it.identityVersion" in crossModeBranch)
        assertTrue("it.identityStableKey" in crossModeBranch)
        assertTrue(
            "Canonical asset evidence conflicts across problem identities" in
                crossModeBranch,
        )
        assertTrue("insertIdentity = false" in crossModeBranch)
        assertTrue("insertSourceBinding = true" in crossModeBranch)
    }

    @Test
    fun zeroImageTrustedSourceSkipsCrossAssetLookupsButKeepsDirectLookup() {
        val resolution =
            studentMistakeDaoSource().segment(
                "private suspend fun resolveAtomicCaptureIdentity(",
                "private suspend fun readCanonicalIdentityForBinding(",
            )
        val normalized = resolution.replace(Regex("""\s+"""), " ")
        assertTrue(
            Regex(
                """val hasExactAssetEvidence = bundle\.capture\.confirmedMistake""" +
                    """\.problem\.images\.isNotEmpty\(\)""",
            ).containsMatchIn(normalized),
        )

        val directLookup =
            resolution.segment(
                "val existingBinding =",
                "val canonicalAssetBindings =",
            ).replace(Regex("""\s+"""), " ")
        assertTrue(
            Regex(
                """val existingBinding = readCanonicalProblemSourceBinding\(""",
            ).containsMatchIn(directLookup),
        )
        assertFalse("hasExactAssetEvidence" in directLookup)
        assertFalse("emptyList()" in directLookup)

        val crossAssetLookup =
            resolution.segment(
                "val canonicalAssetBindings =",
                "val reviewedCorrectionBindings =",
            ).replace(Regex("""\s+"""), " ")
        assertTrue("if (" in crossAssetLookup)
        assertTrue(
            crossAssetLookup.indexOf("hasExactAssetEvidence") <
                crossAssetLookup.indexOf("readCanonicalProblemAssetBindings("),
        )
        assertTrue("else { emptyList() }" in crossAssetLookup)

        val correctionLookup =
            resolution.segment(
                "val reviewedCorrectionBindings =",
                "val aliasIdentity =",
            ).replace(Regex("""\s+"""), " ")
        assertTrue("if (" in correctionLookup)
        assertTrue(
            correctionLookup.indexOf("hasExactAssetEvidence") <
                correctionLookup.indexOf("readReviewedAliasCorrectionBindings("),
        )
        assertTrue("else { emptyList() }" in correctionLookup)
    }

    @Test
    fun contradictoryRawAndReviewedMappingsFailClosed() {
        val resolution =
            studentMistakeDaoSource().segment(
                "private suspend fun resolveAtomicCaptureIdentity(",
                "private suspend fun readCanonicalIdentityForBinding(",
            )

        listOf(
            "Reviewed alias evidence conflicts with an existing canonical identity",
            "Canonical asset evidence conflicts across problem identities",
            "Reviewed alias evidence conflicts across problem revisions",
            "Reviewed alias evidence conflicts with canonical asset evidence",
            "Canonical asset evidence conflicts across problem revisions",
            "Reviewed alias evidence conflicts across canonical identities",
        ).forEach { failure ->
            assertTrue("Missing fail-closed conflict: $failure", failure in resolution)
        }
        assertTrue(
            "canonicalAssetBindings.all { it.targetsIdentity(aliasIdentity) }" in
                resolution,
        )
        assertTrue(
            "reviewedCorrectionBindings.all {" in resolution,
        )
        assertTrue(
            "it.targetsIdentity(aliasIdentity)" in resolution,
        )
        assertTrue(
            "it.targetsIdentity(canonicalAssetIdentity)" in resolution,
        )
        assertTrue(
            ".map { it.boundRevisionId }" in resolution,
        )
        assertTrue(".distinct()" in resolution)
    }

    @Test
    fun trustedSourceExactAssetsAreMaterializedInsideTheAtomicTransaction() {
        val source = studentMistakeDaoSource()
        val transaction =
            source.segment(
                "open suspend fun saveStudentCaptureOccurrence(",
                "private suspend fun resolveAtomicCaptureIdentity(",
            )
        val sourceBindingWrite =
            transaction.indexOf("insertCanonicalProblemSourceBinding(resolved.sourceBinding)")
        val exactMaterialization =
            transaction.indexOf("materializeTrustedSourceExactAssetBinding(resolved)")
        val receiptWrite =
            transaction.indexOf("insertCaptureOccurrenceTransaction(transaction)")
        assertTrue(sourceBindingWrite >= 0)
        assertTrue(exactMaterialization > sourceBindingWrite)
        assertTrue(receiptWrite > exactMaterialization)

        val materialization =
            source.segment(
                "private suspend fun materializeTrustedSourceExactAssetBinding(",
                "private suspend fun buildResolvedAtomicCapture(",
            )
        assertTrue(
            "StudentProblemIdentityEvidenceKind.TRUSTED_SOURCE.name" in
                materialization,
        )
        assertTrue(
            "bundle.capture.confirmedMistake.problem.images.isEmpty()" in
                materialization,
        )
        assertTrue(
            "StudentProblemIdentityEvidenceKind.EXACT_ASSET_SELECTION" in
                materialization,
        )
        assertTrue("readCanonicalProblemSourceBinding(" in materialization)
        assertTrue(
            "Trusted source exact assets are bound to another canonical identity" in
                materialization,
        )
        assertTrue("insertCanonicalProblemSourceBinding(" in materialization)
        assertTrue("locatorNamespace = null" in materialization)
        assertTrue("trustedSourceProofFingerprint = null" in materialization)
        assertTrue("reviewDecisionCanonicalFingerprint = null" in materialization)
    }

    @Test
    fun reviewedAliasRetargetPreservesPersistedProblemAndPracticeMetadata() {
        val source = studentMistakeDaoSource()
        val aliasResolution =
            source.segment(
                "val matchingRevision =",
                "if (existingBinding == null && canonicalAssetBindings.isNotEmpty())",
            )
        assertTrue("buildResolvedAtomicCapture(" in aliasResolution)
        assertTrue("identity = aliasIdentity" in aliasResolution)

        val build =
            source.segment(
                "private suspend fun buildResolvedAtomicCapture(",
                "private fun retargetAtomicCapture(",
            )
        assertTrue("readPracticeUnit(practiceUnitId)" in build)
        assertTrue("readProblem(identity.problemId)" in build)
        assertTrue("persistedPracticeUnit = persistedPracticeUnit" in build)
        assertTrue("persistedProblem = persistedProblem" in build)

        val retarget =
            source.segment(
                "private fun retargetAtomicCapture(",
                "private fun finishResolvedAtomicCapture(",
            )
        val persistedProblemCopy = retarget.indexOf("persistedProblem?.copy(")
        val provisionalProblemCopy = retarget.indexOf("?: original.problem.copy(")
        val persistedPracticeCopy =
            retarget.indexOf("persistedPracticeUnit?.copy(")
        val provisionalPracticeCopy =
            retarget.indexOf("?: original.practiceUnit.copy(")
        assertTrue(persistedProblemCopy >= 0)
        assertTrue(provisionalProblemCopy > persistedProblemCopy)
        assertTrue(persistedPracticeCopy > provisionalProblemCopy)
        assertTrue(provisionalPracticeCopy > persistedPracticeCopy)
        assertTrue("currentRevisionId = revisionId" in retarget)
        assertTrue("basisRevisionId = revisionId" in retarget)
        assertTrue(
            "persistedProblem.updatedAtEpochMillis" in retarget,
        )
        assertTrue(
            "persistedPracticeUnit.updatedAtEpochMillis" in retarget,
        )
    }

    @Test
    fun reviewedAliasCanCreateOneNewRevisionWithoutReviewOrMasteryWrites() {
        val source = studentMistakeDaoSource()
        val aliasResolution =
            source.segment(
                "if (aliasIdentity != null) {",
                "check(existingBinding == null)",
            )
        assertTrue("readCanonicalProblemRevisionByDocument(" in aliasResolution)
        assertTrue(
            "latestRevision.revisionNumber + 1" in aliasResolution,
        )
        assertTrue(
            "ResolvedAtomicCaptureTargetMode.CREATE_REVIEWED_ALIAS_REVISION" in
                aliasResolution,
        )

        val persistence =
            source.segment(
                "private suspend fun persistResolvedAtomicCaptureTarget(",
                "private suspend fun requireCanonicalIdentityTarget(",
            )
        assertTrue(
            "ResolvedAtomicCaptureTargetMode.CREATE_REVIEWED_ALIAS_REVISION" in
                persistence,
        )
        assertTrue("commitProblem(" in persistence)
        assertTrue("publishRevisionToMastery = false" in persistence)
        assertTrue("Reviewed alias revision was not committed exactly" in persistence)
        assertFalse("publishRevisionToMastery = true" in persistence)
        assertFalse("applyReviewCandidate(" in persistence)
        assertFalse("insertSolutionAnalysis(" in persistence)
        assertFalse("insertErrorAttribution" in persistence)

        val requestValidation =
            source.segment(
                "private fun requireAtomicCaptureOccurrenceRequest(",
                "private fun requireAtomicCaptureOccurrenceBundle(",
            )
        assertTrue("target.problem.solutionAnalysis == null" in requestValidation)
        assertTrue("target.problem.solutionSteps.isEmpty()" in requestValidation)
        assertTrue("target.problem.errorAttributions.isEmpty()" in requestValidation)
        assertTrue("target.problem.errorEvidence.isEmpty()" in requestValidation)
    }

    @Test
    fun captureTargetAndOccurrenceCommitUnderOneRoomTransaction() {
        val source = studentMistakeDaoSource()
        val signature =
            source.indexOf("open suspend fun saveStudentCaptureOccurrence(")
        assertTrue(signature >= 0)
        val annotationWindow =
            source.substring(
                (signature - 96).coerceAtLeast(0),
                signature,
            )
        assertTrue("@Transaction" in annotationWindow)

        val transaction =
            source.segment(
                "open suspend fun saveStudentCaptureOccurrence(",
                "private suspend fun resolveAtomicCaptureIdentity(",
            )
        assertTrue("persistResolvedAtomicCaptureTarget(resolved)" in transaction)
        assertTrue("insertCaptureSaveHandoff(handoff)" in transaction)
        assertTrue("appendAtomicErrorOccurrence(resolved.occurrence)" in transaction)
        assertTrue("insertCaptureOccurrenceTransaction(transaction)" in transaction)
    }

    private fun image(
        imageReferenceId: String = "provisional-image",
        localContentUri: String = "content://capture/image",
        selectedRegions: List<NormalizedSourceRegion> = emptyList(),
    ): StudentProblemImageReference =
        StudentProblemImageReference(
            imageReferenceId = imageReferenceId,
            localContentUri = localContentUri,
            contentCanonicalFingerprint = fingerprint('a'),
            mediaType = "image/png",
            ordinal = 0,
            widthPixels = 1200,
            heightPixels = 800,
            byteSize = 42_000,
            selectedRegions = selectedRegions,
        )

    private fun captureContractSource(): String =
        projectFile(
            "core/student-mistake-database/src/main/kotlin/com/tingyun/" +
                "smartmistakebook/core/student/mistake/database/" +
                "StudentCaptureOccurrencePort.kt",
        ).readText()

    private fun captureAdapterSource(): String =
        projectFile(
            "core/student-mistake-database/src/main/kotlin/com/tingyun/" +
                "smartmistakebook/core/student/mistake/database/" +
                "RoomLearnerBoundStudentCaptureOccurrencePort.kt",
        ).readText()

    private fun studentMistakeDaoSource(): String =
        listOf(
            "StudentProblemDocumentDao.kt",
            "StudentCaptureIdentityDao.kt",
            "StudentReviewDao.kt",
            "StudentMistakeDao.kt",
            "StudentAtomicCaptureWriteDao.kt",
        ).joinToString("\n") { fileName ->
            projectFile(
                "core/student-mistake-database/src/main/kotlin/com/tingyun/" +
                    "smartmistakebook/core/student/mistake/database/" +
                    fileName,
            ).readText()
        }

    private fun projectFile(relativePath: String): File =
        File(projectRoot(), relativePath)

    private fun projectRoot(): File =
        generateSequence(
            File(requireNotNull(System.getProperty("user.dir"))).canonicalFile,
        ) { directory ->
            directory.parentFile
        }.firstOrNull { directory ->
            File(directory, "settings.gradle.kts").isFile
        } ?: error("Could not locate the project root")

    private fun String.segment(
        startMarker: String,
        endMarker: String,
    ): String {
        val start = indexOf(startMarker)
        check(start >= 0) { "Missing source marker: $startMarker" }
        val end = indexOf(endMarker, start + startMarker.length)
        check(end > start) { "Missing source marker: $endMarker" }
        return substring(start, end)
    }

    private fun fingerprint(character: Char): String =
        character.toString().repeat(64)
}
