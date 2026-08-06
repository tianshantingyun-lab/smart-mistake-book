package com.tingyun.smartmistakebook.core.knowledge.database

import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tingyun.smartmistakebook.core.model.KnowledgeMaterialDerivationKind
import com.tingyun.smartmistakebook.core.model.KnowledgeMaterialNodeRole
import com.tingyun.smartmistakebook.core.model.KnowledgeNodeGranularity
import com.tingyun.smartmistakebook.core.model.KnowledgeNodeKind
import com.tingyun.smartmistakebook.core.model.KnowledgeNodeVerificationStatus
import com.tingyun.smartmistakebook.core.model.KnowledgeSourceContentUsePolicy
import com.tingyun.smartmistakebook.core.model.KnowledgeSourceLicenseStatus
import com.tingyun.smartmistakebook.core.model.KnowledgeSourceType
import com.tingyun.smartmistakebook.core.model.KnowledgeTeachingMaterialType
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.storage.KnowledgeNodeRef
import com.tingyun.smartmistakebook.core.model.storage.KnowledgeReferenceProofAuthority
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HighSchoolKnowledgeCatalogInstrumentedTest {
    @Test
    fun installedPackSupportsDeterministicReadOnlyCatalogQueries() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        deleteTrustedRoomLockCompanionIfPresent(context.getDatabasePath(TEST_DATABASE_NAME))
        context.deleteDatabase(TEST_DATABASE_NAME)
        try {
            val missingDatabaseFailure =
                runCatching {
                    HighSchoolKnowledgeCatalogFactory.openExistingForTest(
                        context,
                        TEST_DATABASE_NAME,
                    )
                }.exceptionOrNull()
            assertNotNull(missingDatabaseFailure)
            assertTrue(!context.getDatabasePath(TEST_DATABASE_NAME).exists())

            val bundle = fixtureBundle()
            HighSchoolKnowledgePackBuilder.openForTest(context, TEST_DATABASE_NAME).use { builder ->
                builder.replacePack(bundle)
            }
            HighSchoolKnowledgeCatalogFactory.openExistingForTest(
                context,
                TEST_DATABASE_NAME,
            ).use { catalog ->
                assertEquals(bundle.manifest.toCatalogManifest(), catalog.readManifest())

                val quadraticRef = nodeRef(QUADRATIC_NODE_ID)
                assertEquals(
                    "二次函数",
                    catalog.findNode(quadraticRef)?.displayName,
                )
                assertEquals(
                    "A directly built test database must not mint a verified capability",
                    null,
                    catalog.resolveNode(quadraticRef),
                )
                assertEquals(
                    "A directly built test database must not mint a cross-authority proof",
                    null,
                    catalog.verifyReference(quadraticRef),
                )
                assertNotNull(
                    "A directly built test database must not serve verified batch metadata",
                    runCatching {
                        catalog.findNodes(listOf(quadraticRef))
                    }.exceptionOrNull(),
                )

                val aliasResults = catalog.recall(SubjectKind.MATH, "抛物线")
                assertEquals(quadraticRef, aliasResults.first().node.ref)

                val fullTextResults = catalog.recall(SubjectKind.MATH, "二次函数图像性质")
                assertEquals(quadraticRef, fullTextResults.first().node.ref)
                assertEquals(
                    fullTextResults,
                    catalog.recall(SubjectKind.MATH, "二次函数图像性质"),
                )

                val outgoing =
                    catalog.expandRelations(
                        origin = quadraticRef,
                        direction = KnowledgeRelationDirection.OUTGOING,
                        relationTypes = setOf("PART_OF"),
                    )
                assertEquals(1, outgoing.size)
                assertEquals(FUNCTION_NODE_ID, outgoing.single().to.knowledgeNodeId)

                val incoming =
                    catalog.expandRelations(
                        origin = nodeRef(FUNCTION_NODE_ID),
                        direction = KnowledgeRelationDirection.INCOMING,
                    )
                assertEquals(outgoing, incoming)
                assertNotNull(catalog.findNode(incoming.single().from))
                assertTrue(catalog.recall(SubjectKind.PHYSICS, "二次函数").isEmpty())

                val wrongPackRef =
                    KnowledgeNodeRef(
                        subject = SubjectKind.MATH,
                        knowledgeNodeId = QUADRATIC_NODE_ID,
                        taxonomyVersion = TAXONOMY_VERSION,
                        knowledgePackVersion = "other-pack-v1",
                    )
                assertEquals(null, catalog.findNode(wrongPackRef))
                assertTrue(catalog.expandRelations(wrongPackRef).isEmpty())
            }

            val readOnlyWriteFailure =
                runCatching {
                    HighSchoolKnowledgeCatalogFactory
                        .attemptInstallThroughReadOnlyConnectionForTest(
                            context = context,
                            databaseName = TEST_DATABASE_NAME,
                            bundle = bundle,
                        )
                }.exceptionOrNull()
            assertNotNull("OPEN_READONLY must reject catalog writes", readOnlyWriteFailure)

            HighSchoolKnowledgeCatalogFactory.openExistingForTest(
                context,
                TEST_DATABASE_NAME,
            ).use { catalog ->
                assertEquals(
                    bundle.manifest.toCatalogManifest(),
                    catalog.readManifest(),
                )
            }
        } finally {
            deleteTrustedRoomLockCompanionIfPresent(context.getDatabasePath(TEST_DATABASE_NAME))
            context.deleteDatabase(TEST_DATABASE_NAME)
        }
    }

    @Test
    fun activatedBatchDisplayLookupIsBoundedSnapshotBoundAndInputOrdered() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        clearProductionPackForTest(context)
        try {
            val bundle = fixtureBundle()
            HighSchoolKnowledgePackBuilder.openNext(context).use { builder ->
                builder.replacePack(bundle)
            }
            KnowledgePackActivationManager.activateNext(
                context,
                activationAuthorization(bundle, generation = 1L),
            )

            HighSchoolKnowledgeCatalogFactory.open(context).use { catalog ->
                val quadratic = nodeRef(QUADRATIC_NODE_ID)
                val function = nodeRef(FUNCTION_NODE_ID)
                val missing = nodeRef("node.math.missing")
                val physics =
                    KnowledgeNodeRef(
                        subject = SubjectKind.PHYSICS,
                        knowledgeNodeId = "node.physics.overview",
                        taxonomyVersion = TAXONOMY_VERSION,
                        knowledgePackVersion = KNOWLEDGE_PACK_VERSION,
                    )
                val refs = listOf(quadratic, physics, function, quadratic, missing)
                val batch = catalog.findNodes(refs)

                assertEquals(batch.snapshot, catalog.observeSnapshotRevision().value)
                assertEquals(bundle.manifest.knowledgePackVersion, batch.snapshot.knowledgePackVersion)
                assertEquals(bundle.manifest.taxonomyVersion, batch.snapshot.taxonomyVersion)
                assertEquals(bundle.manifest.contentFingerprint, batch.snapshot.manifestFingerprint)
                assertEquals(1L, batch.snapshot.activationGeneration)
                assertEquals(refs, batch.lookups.map { lookup -> lookup.requestedRef })
                assertEquals(
                    listOf("二次函数", "PHYSICS基础", "函数", "二次函数", null),
                    batch.lookups.map { lookup -> lookup.metadata?.displayName },
                )
                assertEquals(
                    batch.lookups[0].metadata,
                    batch.lookups[3].metadata,
                )
                assertEquals(
                    function,
                    batch.lookups[0].metadata?.parentRef,
                )

                val empty = catalog.findNodes(emptyList())
                assertEquals(batch.snapshot, empty.snapshot)
                assertTrue(empty.lookups.isEmpty())

                assertNotNull(
                    "A stale knowledge-pack reference must reject the complete batch",
                    runCatching {
                        catalog.findNodes(
                            listOf(
                                quadratic.copy(knowledgePackVersion = "stale-content-v1"),
                            ),
                        )
                    }.exceptionOrNull(),
                )
                val wrongSubject =
                    quadratic.copy(subject = SubjectKind.PHYSICS)
                assertEquals(
                    null,
                    catalog.findNodes(listOf(wrongSubject)).lookups.single().metadata,
                )
                assertNotNull(
                    "A batch above the request budget must be rejected",
                    runCatching {
                        catalog.findNodes(
                            List(
                                HighSchoolKnowledgeCatalog.MAX_BATCH_NODE_REQUESTS + 1,
                            ) { quadratic },
                        )
                    }.exceptionOrNull(),
                )
            }
        } finally {
            clearProductionPackForTest(context)
        }
    }

    @Test
    fun batchResolutionAndReferenceVerificationUseConstantRoomQueryCounts() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        clearProductionPackForTest(context)
        try {
            val bundle = fixtureBundle()
            HighSchoolKnowledgePackBuilder.openNext(context).use { builder ->
                builder.replacePack(bundle)
            }
            KnowledgePackActivationManager.activateNext(
                context,
                activationAuthorization(bundle, generation = 1L),
            )
            val authority = KnowledgeReferenceProofAuthority.create()
            val observedSql = mutableListOf<String>()
            HighSchoolKnowledgeCatalogFactory.openActivatedForTest(
                context = context,
                proofIssuer = authority.issuer,
                queryObserver = observedSql::add,
            ).use { catalog ->
                catalog.readManifest()
                observedSql.clear()
                val oneHandle = catalog.resolveNodes(SubjectKind.MATH, listOf(QUADRATIC_NODE_ID))
                val oneResolutionQueryCount = observedSql.countKnowledgeAuthoritySelects()

                observedSql.clear()
                val twoHandles =
                    catalog.resolveNodes(
                        SubjectKind.MATH,
                        listOf(QUADRATIC_NODE_ID, FUNCTION_NODE_ID),
                    )
                val twoResolutionQueryCount = observedSql.countKnowledgeAuthoritySelects()

                observedSql.clear()
                val oneProof = catalog.verifyReferences(listOf(nodeRef(QUADRATIC_NODE_ID)))
                val oneReferenceQueryCount = observedSql.countKnowledgeAuthoritySelects()

                observedSql.clear()
                val twoProofs =
                    catalog.verifyReferences(
                        listOf(
                            nodeRef(QUADRATIC_NODE_ID),
                            nodeRef(FUNCTION_NODE_ID),
                        ),
                    )
                val twoReferenceQueryCount = observedSql.countKnowledgeAuthoritySelects()

                assertEquals(1, oneHandle.size)
                assertEquals(2, twoHandles.size)
                assertTrue(oneResolutionQueryCount in 1..2)
                assertEquals(oneResolutionQueryCount, twoResolutionQueryCount)
                assertEquals(listOf(nodeRef(QUADRATIC_NODE_ID)), oneProof.map { proof -> proof.ref })
                assertEquals(
                    listOf(nodeRef(QUADRATIC_NODE_ID), nodeRef(FUNCTION_NODE_ID)),
                    twoProofs.map { proof -> proof.ref },
                )
                assertTrue(oneReferenceQueryCount in 0..3)
                assertEquals(oneReferenceQueryCount, twoReferenceQueryCount)
            }
        } finally {
            clearProductionPackForTest(context)
        }
    }

    @Test
    fun validatedNextPackActivatesOnceAndRejectsReplacementOrRollback() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        clearProductionPackForTest(context)
        try {
            val first = fixtureBundle()
            HighSchoolKnowledgePackBuilder.openNext(context).use { builder ->
                builder.replacePack(first)
            }
            val result =
                KnowledgePackActivationManager.activateNext(
                    context,
                    activationAuthorization(first, generation = 1L),
                )
            assertEquals(1L, result.generation)
            assertEquals(first.manifest.packId, result.packId)
            assertEquals(first.manifest.knowledgePackVersion, result.knowledgePackVersion)
            assertEquals(first.manifest.taxonomyVersion, result.taxonomyVersion)
            assertEquals(first.manifest.contentFingerprint, result.manifestFingerprint)
            assertTrue(result.activatedAtEpochMillis > 0L)
            val activationReceiptFile =
                java.io.File(
                    requireNotNull(
                        context.getDatabasePath(HIGH_SCHOOL_KNOWLEDGE_DATABASE_NAME).parentFile,
                    ),
                    "$HIGH_SCHOOL_KNOWLEDGE_DATABASE_NAME.activation",
                )
            assertEquals(
                listOf(
                    "high-school-knowledge-activation-v3",
                    result.generation.toString(),
                    result.activatedAtEpochMillis.toString(),
                    result.packId,
                    result.knowledgePackVersion,
                    result.taxonomyVersion,
                    first.manifest.searchIndexVersion,
                    result.manifestFingerprint,
                    trustedKnowledgePackAuthorizationIdentity(
                        packId = result.packId,
                        knowledgePackVersion = result.knowledgePackVersion,
                        taxonomyVersion = result.taxonomyVersion,
                        searchIndexVersion = first.manifest.searchIndexVersion,
                        generation = result.generation,
                        contentFingerprint = result.manifestFingerprint,
                    ),
                ),
                activationReceiptFile.readLines(Charsets.UTF_8),
            )
            val reopenedReceipt =
                requireNotNull(
                    KnowledgePackActivationManager.currentActivationReceipt(context),
                )
            assertEquals(result.manifestFingerprint, reopenedReceipt.manifestFingerprint)
            HighSchoolKnowledgeCatalogFactory.open(context).use { catalog ->
                assertEquals(first.manifest.toCatalogManifest(), catalog.readManifest())
                val verified = requireNotNull(catalog.resolveNode(nodeRef(QUADRATIC_NODE_ID)))
                assertEquals(nodeRef(QUADRATIC_NODE_ID), verified.ref)
                assertEquals(first.manifest.contentFingerprint, verified.manifestFingerprint)
                assertEquals(1L, verified.activationGeneration)
                val referenceProof =
                    requireNotNull(catalog.verifyReference(nodeRef(QUADRATIC_NODE_ID)))
                assertEquals(verified.ref, referenceProof.ref)
                assertEquals(verified.manifestFingerprint, referenceProof.manifestFingerprint)
                assertEquals(verified.activationGeneration, referenceProof.activationGeneration)

                val materials = catalog.readTeachingMaterials(verified)
                assertEquals(listOf(MATERIAL_ID), materials.map { it.materialId })
                assertEquals(verified.ref, materials.single().knowledgeNodeRef)
                assertTrue(
                    materials.sumOf { material -> material.markdownCharacterCount } <=
                        HighSchoolKnowledgeCatalog
                            .MAX_RETURNED_TEACHING_MATERIAL_MARKDOWN_CHARS,
                )
                assertNotNull(
                    "Teaching-material reads must enforce their result-count budget",
                    runCatching {
                        catalog.readTeachingMaterials(
                            verified,
                            HighSchoolKnowledgeCatalog.MAX_TEACHING_MATERIAL_LIMIT + 1,
                        )
                    }.exceptionOrNull(),
                )

                val wrongPackRef =
                    verified.ref.copy(knowledgePackVersion = "wrong-pack-version")
                val wrongPackNode = verified.node.copy(ref = wrongPackRef)
                val wrongPackHandle =
                    VerifiedKnowledgeNodeHandle.create(
                        node = wrongPackNode,
                        manifestFingerprint = verified.manifestFingerprint,
                        activationGeneration = verified.activationGeneration,
                    )
                assertNotNull(
                    "A verified handle from another pack version must be rejected",
                    runCatching {
                        catalog.readTeachingMaterials(wrongPackHandle)
                    }.exceptionOrNull(),
                )

                val missingNodeRef =
                    verified.ref.copy(knowledgeNodeId = "node.math.missing")
                val missingNodeHandle =
                    VerifiedKnowledgeNodeHandle.create(
                        node = verified.node.copy(ref = missingNodeRef),
                        manifestFingerprint = verified.manifestFingerprint,
                        activationGeneration = verified.activationGeneration,
                    )
                assertNotNull(
                    "A handle for a node absent from the active pack must be rejected",
                    runCatching {
                        catalog.readTeachingMaterials(missingNodeHandle)
                    }.exceptionOrNull(),
                )
            }

            HighSchoolKnowledgePackBuilder.openNext(context).use { builder ->
                builder.replacePack(first)
            }
            val sameVersionFailure =
                runCatching {
                    KnowledgePackActivationManager.activateNext(
                        context,
                        activationAuthorization(first, generation = 2L),
                    )
                }.exceptionOrNull()
            assertNotNull("An installed version must not be replaceable", sameVersionFailure)
            HighSchoolKnowledgePackBuilder.discardNext(context)

            val second = fixtureBundle(knowledgePackVersion = "test-content-v2")
            HighSchoolKnowledgePackBuilder.openNext(context).use { builder ->
                builder.replacePack(second)
            }
            val rollbackFailure =
                runCatching {
                    KnowledgePackActivationManager.activateNext(
                        context,
                        activationAuthorization(second, generation = 1L),
                    )
                }.exceptionOrNull()
            assertNotNull("Activation generation must not roll back", rollbackFailure)
        } finally {
            clearProductionPackForTest(context)
        }
    }

    @Test
    fun recoveryRestoresPreviousPackWhenSwappedDatabaseDoesNotMatchPendingReceipt() =
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            clearProductionPackForTest(context)
            try {
                val first = fixtureBundle(knowledgePackVersion = "recovery-v1")
                HighSchoolKnowledgePackBuilder.openNext(context).use { builder ->
                    builder.replacePack(first)
                }
                KnowledgePackActivationManager.activateNext(
                    context,
                    activationAuthorization(first, generation = 1L),
                )

                val second = fixtureBundle(knowledgePackVersion = "recovery-v2")
                HighSchoolKnowledgePackBuilder.openNext(context).use { builder ->
                    builder.replacePack(second)
                }
                val activeFile =
                    context.getDatabasePath(HIGH_SCHOOL_KNOWLEDGE_DATABASE_NAME)
                val candidateFile =
                    context.getDatabasePath(HighSchoolKnowledgePackBuilder.NEXT_DATABASE_NAME)
                val databaseDirectory = requireNotNull(activeFile.parentFile)
                val rollbackFile =
                    java.io.File(
                        databaseDirectory,
                        "$HIGH_SCHOOL_KNOWLEDGE_DATABASE_NAME.activation.rollback",
                    )
                val pendingFile =
                    java.io.File(
                        databaseDirectory,
                        "$HIGH_SCHOOL_KNOWLEDGE_DATABASE_NAME.activation.pending",
                    )
                copyFileWithSyncForTest(activeFile, rollbackFile)
                writeActivationReceiptForTest(
                    file = pendingFile,
                    bundle = second,
                    generation = 2L,
                    manifestFingerprint = "f".repeat(64),
                )
                Os.rename(candidateFile.absolutePath, activeFile.absolutePath)

                val recovered =
                    requireNotNull(
                        KnowledgePackActivationManager.currentActivationReceipt(context),
                    )
                assertEquals(first.manifest.knowledgePackVersion, recovered.knowledgePackVersion)
                assertEquals(first.manifest.contentFingerprint, recovered.manifestFingerprint)
                assertTrue(!pendingFile.exists())
                assertTrue(!rollbackFile.exists())
                HighSchoolKnowledgeCatalogFactory.open(context).use { catalog ->
                    assertEquals(first.manifest.toCatalogManifest(), catalog.readManifest())
                }
            } finally {
                clearProductionPackForTest(context)
            }
        }

    @Test
    fun activationRejectsCandidateSymlinkWithoutInstallingIt() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        clearProductionPackForTest(context)
        val candidateFile =
            context.getDatabasePath(HighSchoolKnowledgePackBuilder.NEXT_DATABASE_NAME)
        val symlinkTarget =
            java.io.File(
                requireNotNull(candidateFile.parentFile),
                "${candidateFile.name}.symlink-target",
            )
        try {
            val bundle = fixtureBundle(knowledgePackVersion = "symlink-v1")
            HighSchoolKnowledgePackBuilder.openNext(context).use { builder ->
                builder.replacePack(bundle)
            }
            assertTrue(candidateFile.renameTo(symlinkTarget))
            Os.symlink(symlinkTarget.absolutePath, candidateFile.absolutePath)

            assertNotNull(
                "A candidate symlink must never be opened or activated",
                runCatching {
                    KnowledgePackActivationManager.activateNext(
                        context,
                        activationAuthorization(bundle, generation = 1L),
                    )
                }.exceptionOrNull(),
            )
            assertTrue(!context.getDatabasePath(HIGH_SCHOOL_KNOWLEDGE_DATABASE_NAME).exists())
        } finally {
            if (candidateFile.exists()) assertTrue(candidateFile.delete())
            if (symlinkTarget.exists()) assertTrue(symlinkTarget.delete())
            clearProductionPackForTest(context)
        }
    }

    @Test
    fun arbitrarySelfFingerprintedPackIsRejectedByFixedTrustRegistry() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        clearProductionPackForTest(context)
        try {
            val fixture = fixtureBundle()
            assertTrue(
                fixture.manifest.contentFingerprint.matches(Regex("[0-9a-f]{64}")),
            )

            val failure =
                runCatching {
                    HighSchoolKnowledgePackProvisioner.provisionApkBundledReviewedPack(
                        context = context,
                        pack = fixture.toReviewedPack(),
                    )
                }.exceptionOrNull()

            assertTrue(failure is SecurityException)
            assertTrue(!context.getDatabasePath(HIGH_SCHOOL_KNOWLEDGE_DATABASE_NAME).exists())
            assertTrue(
                !context.getDatabasePath(HighSchoolKnowledgePackBuilder.NEXT_DATABASE_NAME).exists(),
            )
        } finally {
            clearProductionPackForTest(context)
        }
    }

    @Test
    fun registeredIdentityCannotAuthorizeDifferentSelfComputedContent() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        clearProductionPackForTest(context)
        try {
            val fixture = fixtureBundle()
            val forgedBundle =
                KnowledgePackInstallBundle(
                    manifest =
                        fixture.manifest.copy(
                            packId = HistoricalSampleKnowledgePackAuthorization.PACK_ID,
                            knowledgePackVersion =
                                HistoricalSampleKnowledgePackAuthorization.KNOWLEDGE_PACK_VERSION,
                            taxonomyVersion =
                                HistoricalSampleKnowledgePackAuthorization.TAXONOMY_VERSION,
                            searchIndexVersion =
                                HistoricalSampleKnowledgePackAuthorization.SEARCH_INDEX_VERSION,
                            contentFingerprint = "0".repeat(64),
                        ),
                    nodes =
                        fixture.nodes.map { node ->
                            node.copy(
                                taxonomyVersion =
                                    HistoricalSampleKnowledgePackAuthorization.TAXONOMY_VERSION,
                            )
                        },
                    sources = fixture.sources,
                    nodeSourceBindings = fixture.nodeSourceBindings,
                    relations =
                        fixture.relations.map { relation ->
                            relation.copy(
                                taxonomyVersion =
                                    HistoricalSampleKnowledgePackAuthorization.TAXONOMY_VERSION,
                            )
                        },
                    searchFeatures = fixture.searchFeatures,
                    materials = fixture.materials,
                    materialBindings = fixture.materialBindings,
                ).withRecomputedContentFingerprint()
            assertTrue(
                forgedBundle.manifest.contentFingerprint !=
                    HistoricalSampleKnowledgePackAuthorization.EXPECTED_CONTENT_FINGERPRINT,
            )
            val forgedPack = forgedBundle.toReviewedPack()

            val failure =
                runCatching {
                    HighSchoolKnowledgePackProvisioner.provisionApkBundledReviewedPack(
                        context = context,
                        pack = forgedPack,
                    )
                }.exceptionOrNull()

            assertTrue(failure is SecurityException)
            assertTrue(failure?.message.orEmpty().contains("fingerprint", ignoreCase = true))
            assertTrue(!context.getDatabasePath(HIGH_SCHOOL_KNOWLEDGE_DATABASE_NAME).exists())
            assertTrue(
                !context.getDatabasePath(HighSchoolKnowledgePackBuilder.NEXT_DATABASE_NAME).exists(),
            )
        } finally {
            clearProductionPackForTest(context)
        }
    }

    @Test
    fun registeredManifestMetadataMustMatchFixedTrustEntry() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        clearProductionPackForTest(context)
        try {
            val fixture = fixtureBundle()
            val wrongSearchIndexPack =
                fixture.toReviewedPack(
                    metadata =
                        ReviewedKnowledgePackMetadata(
                            packId = HistoricalSampleKnowledgePackAuthorization.PACK_ID,
                            knowledgePackVersion =
                                HistoricalSampleKnowledgePackAuthorization.KNOWLEDGE_PACK_VERSION,
                            taxonomyVersion =
                                HistoricalSampleKnowledgePackAuthorization.TAXONOMY_VERSION,
                            searchIndexVersion = "attacker-search-index-v1",
                            builtAtEpochMillis = fixture.manifest.builtAtEpochMillis,
                        ),
                )

            val failure =
                runCatching {
                    HighSchoolKnowledgePackProvisioner.provisionApkBundledReviewedPack(
                        context = context,
                        pack = wrongSearchIndexPack,
                    )
                }.exceptionOrNull()

            assertTrue(failure is SecurityException)
            assertTrue(failure?.message.orEmpty().contains("search-index", ignoreCase = true))
            assertTrue(!context.getDatabasePath(HIGH_SCHOOL_KNOWLEDGE_DATABASE_NAME).exists())
            assertTrue(
                !context.getDatabasePath(HighSchoolKnowledgePackBuilder.NEXT_DATABASE_NAME).exists(),
            )
        } finally {
            clearProductionPackForTest(context)
        }
    }

    @Test
    fun openBuilderBlocksActivationAndDiscardAndCannotWriteAfterClose() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        clearProductionPackForTest(context)
        try {
            val bundle = fixtureBundle()
            val builder = HighSchoolKnowledgePackBuilder.openNext(context)
            try {
                builder.replacePack(bundle)
                assertNotNull(
                    "Activation must reject an open writable builder",
                    runCatching {
                        KnowledgePackActivationManager.activateNext(
                            context,
                            activationAuthorization(bundle, generation = 1L),
                        )
                    }.exceptionOrNull(),
                )
                assertNotNull(
                    "Discard must reject an open writable builder",
                    runCatching {
                        HighSchoolKnowledgePackBuilder.discardNext(context)
                    }.exceptionOrNull(),
                )
            } finally {
                builder.close()
            }

            HighSchoolKnowledgePackBuilder.discardNext(context)
            val laterBuilder = HighSchoolKnowledgePackBuilder.openNext(context)
            try {
                assertNotNull(
                    "A closed build handle must never write into a later lifecycle",
                    runCatching {
                        builder.replacePack(bundle)
                    }.exceptionOrNull(),
                )
            } finally {
                laterBuilder.close()
            }
            HighSchoolKnowledgePackBuilder.discardNext(context)
        } finally {
            clearProductionPackForTest(context)
        }
    }

    @Test
    fun openRuntimeSnapshotBlocksActivationUntilCatalogCloses() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        clearProductionPackForTest(context)
        try {
            val first = fixtureBundle()
            HighSchoolKnowledgePackBuilder.openNext(context).use { builder ->
                builder.replacePack(first)
            }
            KnowledgePackActivationManager.activateNext(
                context,
                activationAuthorization(first, generation = 1L),
            )

            val catalog = HighSchoolKnowledgeCatalogFactory.open(context)
            val second = fixtureBundle(knowledgePackVersion = "test-content-v2")
            try {
                assertEquals(first.manifest.toCatalogManifest(), catalog.readManifest())
                HighSchoolKnowledgePackBuilder.openNext(context).use { builder ->
                    builder.replacePack(second)
                }
                assertNotNull(
                    "Activation must not rename beneath an open runtime snapshot",
                    runCatching {
                        KnowledgePackActivationManager.activateNext(
                            context,
                            activationAuthorization(second, generation = 2L),
                        )
                    }.exceptionOrNull(),
                )
            } finally {
                catalog.close()
            }

            val receipt =
                KnowledgePackActivationManager.activateNext(
                    context,
                    activationAuthorization(second, generation = 2L),
                )
            assertEquals(2L, receipt.generation)
        } finally {
            clearProductionPackForTest(context)
        }
    }

    @Test
    fun failedRuntimeSnapshotOpenReleasesLeaseForLaterActivation() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        clearProductionPackForTest(context)
        try {
            val first = fixtureBundle()
            HighSchoolKnowledgePackBuilder.openNext(context).use { builder ->
                builder.replacePack(first)
            }
            KnowledgePackActivationManager.activateNext(
                context,
                activationAuthorization(first, generation = 1L),
            )

            assertNotNull(
                "An injected runtime-open failure must be observable",
                runCatching {
                    KnowledgePackActivationManager.openRuntimeSnapshot(context) { _, _ ->
                        error("injected runtime-open failure")
                    }
                }.exceptionOrNull(),
            )

            val second = fixtureBundle(knowledgePackVersion = "test-content-v2")
            HighSchoolKnowledgePackBuilder.openNext(context).use { builder ->
                builder.replacePack(second)
            }
            val receipt =
                KnowledgePackActivationManager.activateNext(
                    context,
                    activationAuthorization(second, generation = 2L),
                )

            assertEquals(2L, receipt.generation)
        } finally {
            clearProductionPackForTest(context)
        }
    }

    @Test
    fun activationRejectsMissingOrChangedCanonicalIndexes() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        clearProductionPackForTest(context)
        val mutations =
            listOf<(SQLiteDatabase) -> Unit>(
                { database ->
                    database.execSQL("DROP INDEX index_knowledge_node_subject_canonical_name")
                },
                { database ->
                    database.execSQL("DROP INDEX index_knowledge_node_subject_canonical_name")
                    database.execSQL(
                        "CREATE INDEX index_knowledge_node_subject_canonical_name " +
                            "ON knowledge_node(display_name)",
                    )
                },
            )
        try {
            mutations.forEachIndexed { index, mutate ->
                val bundle = fixtureBundle(knowledgePackVersion = "index-test-v$index")
                HighSchoolKnowledgePackBuilder.openNext(context).use { builder ->
                    builder.replacePack(bundle)
                }
                val candidateFile =
                    context.getDatabasePath(HighSchoolKnowledgePackBuilder.NEXT_DATABASE_NAME)
                SQLiteDatabase.openDatabase(
                    candidateFile.absolutePath,
                    null,
                    SQLiteDatabase.OPEN_READWRITE,
                ).use(mutate)

                assertNotNull(
                    "Activation must reject missing or changed canonical indexes",
                    runCatching {
                        KnowledgePackActivationManager.activateNext(
                            context,
                            activationAuthorization(bundle, generation = 1L),
                        )
                    }.exceptionOrNull(),
                )
                HighSchoolKnowledgePackBuilder.discardNext(context)
            }
        } finally {
            clearProductionPackForTest(context)
        }
    }

    @Test
    fun activationRejectsSchemaObjectsWhoseNamesMimicSqlitePrefix() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        clearProductionPackForTest(context)
        val mutations =
            listOf<(SQLiteDatabase) -> Unit>(
                { database ->
                    database.execSQL("CREATE TABLE sqliteXhidden(value TEXT NOT NULL)")
                },
                { database ->
                    database.execSQL(
                        "CREATE VIEW sqliteXhidden_view AS SELECT knowledge_node_id FROM knowledge_node",
                    )
                },
                { database ->
                    database.execSQL(
                        """
                        CREATE TRIGGER sqliteXhidden_trigger
                        AFTER UPDATE ON knowledge_node
                        BEGIN
                            SELECT 1;
                        END
                        """.trimIndent(),
                    )
                },
            )
        try {
            mutations.forEachIndexed { index, mutate ->
                val bundle = fixtureBundle(knowledgePackVersion = "schema-prefix-v$index")
                HighSchoolKnowledgePackBuilder.openNext(context).use { builder ->
                    builder.replacePack(bundle)
                }
                val candidateFile =
                    context.getDatabasePath(HighSchoolKnowledgePackBuilder.NEXT_DATABASE_NAME)
                SQLiteDatabase.openDatabase(
                    candidateFile.absolutePath,
                    null,
                    SQLiteDatabase.OPEN_READWRITE,
                ).use(mutate)

                assertNotNull(
                    "Activation must reject non-internal objects that only mimic sqlite_",
                    runCatching {
                        KnowledgePackActivationManager.activateNext(
                            context,
                            activationAuthorization(bundle, generation = 1L),
                        )
                    }.exceptionOrNull(),
                )
                assertTrue(!context.getDatabasePath(HIGH_SCHOOL_KNOWLEDGE_DATABASE_NAME).exists())
                HighSchoolKnowledgePackBuilder.discardNext(context)
            }
        } finally {
            clearProductionPackForTest(context)
        }
    }

    @Test
    fun activationRejectsTamperedContentAndExtraRowsThenReleasesCandidateResources() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        clearProductionPackForTest(context)
        val mutations =
            listOf<(SQLiteDatabase) -> Unit>(
                { database ->
                    database.execSQL(
                        "UPDATE knowledge_node SET display_name = ? WHERE knowledge_node_id = ?",
                        arrayOf("被篡改的名称", QUADRATIC_NODE_ID),
                    )
                },
                { database ->
                    database.execSQL(
                        """
                        INSERT INTO knowledge_node_source_binding(
                            knowledge_node_id,
                            source_id,
                            source_locator,
                            derivation_note,
                            reviewed_at_epoch_millis
                        ) VALUES (?, ?, ?, ?, ?)
                        """.trimIndent(),
                        arrayOf<Any>(
                            QUADRATIC_NODE_ID,
                            SOURCE_ID,
                            "课程标准/未授权额外定位",
                            "未授权额外行",
                            REVIEWED_AT,
                        ),
                    )
                },
            )
        try {
            mutations.forEachIndexed { index, mutate ->
                val bundle = fixtureBundle(knowledgePackVersion = "content-tamper-v$index")
                HighSchoolKnowledgePackBuilder.openNext(context).use { builder ->
                    builder.replacePack(bundle)
                }
                val candidateFile =
                    context.getDatabasePath(HighSchoolKnowledgePackBuilder.NEXT_DATABASE_NAME)
                SQLiteDatabase.openDatabase(
                    candidateFile.absolutePath,
                    null,
                    SQLiteDatabase.OPEN_READWRITE,
                ).use(mutate)

                val failure =
                    runCatching {
                        KnowledgePackActivationManager.activateNext(
                            context,
                            activationAuthorization(bundle, generation = 1L),
                        )
                    }.exceptionOrNull()

                assertNotNull(
                    "Activation must hash every persisted canonical row before cutover",
                    failure,
                )
                assertTrue(failure?.message.orEmpty().contains("fingerprint", ignoreCase = true))
                assertTrue(!context.getDatabasePath(HIGH_SCHOOL_KNOWLEDGE_DATABASE_NAME).exists())
                HighSchoolKnowledgePackBuilder.discardNext(context)
            }

            val validAfterFailures = fixtureBundle(knowledgePackVersion = "after-tamper-v1")
            HighSchoolKnowledgePackBuilder.openNext(context).use { builder ->
                builder.replacePack(validAfterFailures)
            }
            val receipt =
                KnowledgePackActivationManager.activateNext(
                    context,
                    activationAuthorization(validAfterFailures, generation = 1L),
                )
            assertEquals(validAfterFailures.manifest.contentFingerprint, receipt.manifestFingerprint)
        } finally {
            clearProductionPackForTest(context)
        }
    }

    @Test
    fun activationFingerprintUsesTheSameUnicodeOrderAsPersistedSQLiteText() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        clearProductionPackForTest(context)
        try {
            val base = fixtureBundle(knowledgePackVersion = "unicode-order-v1")
            val unicodeBindings =
                base.nodeSourceBindings +
                    listOf(
                        KnowledgeNodeSourceBindingEntity(
                            knowledgeNodeId = QUADRATIC_NODE_ID,
                            sourceId = SOURCE_ID,
                            sourceLocator = "\uE000-locator",
                            derivationNote = "Unicode canonical-order boundary.",
                            reviewedAtEpochMillis = REVIEWED_AT,
                        ),
                        KnowledgeNodeSourceBindingEntity(
                            knowledgeNodeId = QUADRATIC_NODE_ID,
                            sourceId = SOURCE_ID,
                            sourceLocator = "\uD83D\uDE00-locator",
                            derivationNote = "Unicode canonical-order boundary.",
                            reviewedAtEpochMillis = REVIEWED_AT,
                        ),
                    )
            val bundle =
                KnowledgePackInstallBundle(
                    manifest = base.manifest.copy(contentFingerprint = "0".repeat(64)),
                    nodes = base.nodes,
                    sources = base.sources,
                    nodeSourceBindings = unicodeBindings,
                    relations = base.relations,
                    searchFeatures = base.searchFeatures,
                    materials = base.materials,
                    materialBindings = base.materialBindings,
                ).withRecomputedContentFingerprint()
            HighSchoolKnowledgePackBuilder.openNext(context).use { builder ->
                builder.replacePack(bundle)
            }

            val receipt =
                KnowledgePackActivationManager.activateNext(
                    context,
                    activationAuthorization(bundle, generation = 1L),
                )

            assertEquals(bundle.manifest.contentFingerprint, receipt.manifestFingerprint)
        } finally {
            clearProductionPackForTest(context)
        }
    }

    @Test
    fun activationNeverPromotesAValidationSnapshotWhoseInodeWasSwapped() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        clearProductionPackForTest(context)
        val databaseDirectory =
            requireNotNull(context.getDatabasePath(HIGH_SCHOOL_KNOWLEDGE_DATABASE_NAME).parentFile)
        val displacedSnapshot = java.io.File(databaseDirectory, "displaced-validation-snapshot.db")
        try {
            val bundle = fixtureBundle(knowledgePackVersion = "snapshot-inode-swap-v1")
            HighSchoolKnowledgePackBuilder.openNext(context).use { builder ->
                builder.replacePack(bundle)
            }

            val failure =
                runCatching {
                    KnowledgePackActivationManager.activateNextForTest(
                        context = context,
                        authorization = activationAuthorization(bundle, generation = 1L),
                    ) { validationSnapshot ->
                        Os.rename(
                            validationSnapshot.absolutePath,
                            displacedSnapshot.absolutePath,
                        )
                        copyFileWithSyncForTest(displacedSnapshot, validationSnapshot)
                    }
                }.exceptionOrNull()

            assertNotNull("A different inode must never inherit validation", failure)
            assertTrue(!context.getDatabasePath(HIGH_SCHOOL_KNOWLEDGE_DATABASE_NAME).exists())
            assertTrue(
                !java.io.File(
                    databaseDirectory,
                    "$HIGH_SCHOOL_KNOWLEDGE_DATABASE_NAME.activation.pending",
                ).exists(),
            )
            assertTrue(
                !java.io.File(
                    databaseDirectory,
                    "$HIGH_SCHOOL_KNOWLEDGE_DATABASE_NAME.activation.validation",
                ).exists(),
            )
        } finally {
            if (displacedSnapshot.exists()) assertTrue(displacedSnapshot.delete())
            clearProductionPackForTest(context)
        }
    }

    @Test
    fun recoveryRejectsLegacyPendingReceiptEvenWhenDatabaseContentMatches() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        clearProductionPackForTest(context)
        try {
            val bundle = fixtureBundle(knowledgePackVersion = "post-rename-recovery-v1")
            HighSchoolKnowledgePackBuilder.openNext(context).use { builder ->
                builder.replacePack(bundle)
            }
            val candidateFile =
                context.getDatabasePath(HighSchoolKnowledgePackBuilder.NEXT_DATABASE_NAME)
            val activeFile = context.getDatabasePath(HIGH_SCHOOL_KNOWLEDGE_DATABASE_NAME)
            val databaseDirectory = requireNotNull(activeFile.parentFile)
            val pendingFile =
                java.io.File(
                    databaseDirectory,
                    "$HIGH_SCHOOL_KNOWLEDGE_DATABASE_NAME.activation.pending",
                )
            writeActivationReceiptForTest(
                file = pendingFile,
                bundle = bundle,
                generation = 1L,
                manifestFingerprint = bundle.manifest.contentFingerprint,
            )
            listOf("-wal", "-shm", "-journal").forEach { suffix ->
                val sidecar = java.io.File(candidateFile.path + suffix)
                if (sidecar.exists()) assertTrue(sidecar.delete())
            }
            Os.rename(candidateFile.absolutePath, activeFile.absolutePath)

            val recovered = KnowledgePackActivationManager.currentActivationReceipt(context)

            assertEquals(null, recovered)
            assertTrue(!activeFile.exists())
            assertTrue(!pendingFile.exists())
            assertTrue(
                !java.io.File(
                    databaseDirectory,
                    "$HIGH_SCHOOL_KNOWLEDGE_DATABASE_NAME.activation",
                ).exists(),
            )
        } finally {
            clearProductionPackForTest(context)
        }
    }

    @Test
    fun firstInstallRecoveryDeletesAnInodeSwappedActiveDatabaseAndPendingReceipt() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        clearProductionPackForTest(context)
        val activeFile = context.getDatabasePath(HIGH_SCHOOL_KNOWLEDGE_DATABASE_NAME)
        val databaseDirectory = requireNotNull(activeFile.parentFile)
        val displacedActive = java.io.File(databaseDirectory, "displaced-active-pack.db")
        val pendingFile =
            java.io.File(
                databaseDirectory,
                "$HIGH_SCHOOL_KNOWLEDGE_DATABASE_NAME.activation.pending",
            )
        try {
            val bundle = fixtureBundle(knowledgePackVersion = "recovery-inode-swap-v1")
            HighSchoolKnowledgePackBuilder.openNext(context).use { builder ->
                builder.replacePack(bundle)
            }
            val candidateFile =
                context.getDatabasePath(HighSchoolKnowledgePackBuilder.NEXT_DATABASE_NAME)
            writeActivationReceiptForTest(
                file = pendingFile,
                bundle = bundle,
                generation = 1L,
                manifestFingerprint = bundle.manifest.contentFingerprint,
            )
            Os.rename(candidateFile.absolutePath, activeFile.absolutePath)
            Os.rename(activeFile.absolutePath, displacedActive.absolutePath)
            copyFileWithSyncForTest(displacedActive, activeFile)
            SQLiteDatabase.openDatabase(
                activeFile.absolutePath,
                null,
                SQLiteDatabase.OPEN_READWRITE,
            ).use { database ->
                database.execSQL(
                    "UPDATE knowledge_node SET display_name = ? WHERE knowledge_node_id = ?",
                    arrayOf("恢复阶段篡改", QUADRATIC_NODE_ID),
                )
            }
            listOf("-wal", "-shm", "-journal").forEach { suffix ->
                val sidecar = java.io.File(activeFile.path + suffix)
                if (sidecar.exists()) assertTrue(sidecar.delete())
            }

            assertEquals(null, KnowledgePackActivationManager.currentActivationReceipt(context))
            assertTrue(!activeFile.exists())
            assertTrue(!pendingFile.exists())
        } finally {
            if (displacedActive.exists()) assertTrue(displacedActive.delete())
            clearProductionPackForTest(context)
        }
    }

    @Test
    fun activationRejectsCandidateWalSidecarBeforeSnapshotting() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        clearProductionPackForTest(context)
        try {
            val bundle = fixtureBundle(knowledgePackVersion = "wal-race-v1")
            HighSchoolKnowledgePackBuilder.openNext(context).use { builder ->
                builder.replacePack(bundle)
            }
            val candidateFile =
                context.getDatabasePath(HighSchoolKnowledgePackBuilder.NEXT_DATABASE_NAME)
            val walFile = java.io.File(candidateFile.path + "-wal")
            java.io.FileOutputStream(walFile).use { output ->
                output.write(byteArrayOf(0x37, 0x7f, 0x06, 0x82.toByte()))
                output.fd.sync()
            }
            assertTrue(walFile.isFile && walFile.length() > 0L)

            assertNotNull(
                "A WAL sidecar must prevent candidate snapshotting",
                runCatching {
                    KnowledgePackActivationManager.activateNext(
                        context,
                        activationAuthorization(bundle, generation = 1L),
                    )
                }.exceptionOrNull(),
            )
            assertTrue(!context.getDatabasePath(HIGH_SCHOOL_KNOWLEDGE_DATABASE_NAME).exists())
        } finally {
            clearProductionPackForTest(context)
        }
    }

    @Test
    fun activationRejectsARealUncheckpointedWalWhileReaderAndWriterRemainOpen() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        clearProductionPackForTest(context)
        val bundle = fixtureBundle(knowledgePackVersion = "live-wal-v1")
        HighSchoolKnowledgePackBuilder.openNext(context).use { builder ->
            builder.replacePack(bundle)
        }
        val candidateFile =
            context.getDatabasePath(HighSchoolKnowledgePackBuilder.NEXT_DATABASE_NAME)
        val writer =
            SQLiteDatabase.openDatabase(
                candidateFile.absolutePath,
                null,
                SQLiteDatabase.OPEN_READWRITE,
            )
        var reader: SQLiteDatabase? = null
        try {
            assertTrue(writer.enableWriteAheadLogging())
            writer.rawQuery("PRAGMA journal_mode", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("wal", cursor.getString(0).lowercase())
            }
            writer.rawQuery("PRAGMA wal_autocheckpoint = 0", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }

            val readOnlySnapshot =
                SQLiteDatabase.openDatabase(
                    candidateFile.absolutePath,
                    null,
                    SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
                )
            reader = readOnlySnapshot
            readOnlySnapshot.rawQuery("PRAGMA journal_mode", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("wal", cursor.getString(0).lowercase())
            }
            readOnlySnapshot.execSQL("BEGIN DEFERRED TRANSACTION")
            val originalDisplayName =
                DatabaseUtils.stringForQuery(
                    readOnlySnapshot,
                    "SELECT display_name FROM knowledge_node WHERE knowledge_node_id = ?",
                    arrayOf(QUADRATIC_NODE_ID),
                )
            writer.beginTransactionNonExclusive()
            try {
                writer.execSQL(
                    "UPDATE knowledge_node SET display_name = ? WHERE knowledge_node_id = ?",
                    arrayOf("并发写入未检查点", QUADRATIC_NODE_ID),
                )
                writer.setTransactionSuccessful()
            } finally {
                writer.endTransaction()
            }
            assertEquals(
                originalDisplayName,
                DatabaseUtils.stringForQuery(
                    readOnlySnapshot,
                    "SELECT display_name FROM knowledge_node WHERE knowledge_node_id = ?",
                    arrayOf(QUADRATIC_NODE_ID),
                ),
            )
            assertEquals(
                "并发写入未检查点",
                DatabaseUtils.stringForQuery(
                    writer,
                    "SELECT display_name FROM knowledge_node WHERE knowledge_node_id = ?",
                    arrayOf(QUADRATIC_NODE_ID),
                ),
            )
            val walFile = java.io.File(candidateFile.path + "-wal")
            assertTrue(walFile.isFile && walFile.length() > 0L)

            assertNotNull(
                "A live committed WAL must prevent candidate snapshotting",
                runCatching {
                    KnowledgePackActivationManager.activateNext(
                        context,
                        activationAuthorization(bundle, generation = 1L),
                    )
                }.exceptionOrNull(),
            )
            assertTrue(!context.getDatabasePath(HIGH_SCHOOL_KNOWLEDGE_DATABASE_NAME).exists())
        } finally {
            reader?.let { readOnlySnapshot ->
                runCatching { readOnlySnapshot.execSQL("ROLLBACK") }
                readOnlySnapshot.close()
            }
            writer.close()
            clearProductionPackForTest(context)
        }
    }

    @Test
    fun forgedCurrentFormatPendingReceiptCannotAuthorizeCrashRecovery() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        clearProductionPackForTest(context)
        val activeFile = context.getDatabasePath(HIGH_SCHOOL_KNOWLEDGE_DATABASE_NAME)
        val pendingFile =
            java.io.File(
                requireNotNull(activeFile.parentFile),
                "$HIGH_SCHOOL_KNOWLEDGE_DATABASE_NAME.activation.pending",
            )
        try {
            val bundle = fixtureBundle(knowledgePackVersion = "forged-recovery-v1")
            HighSchoolKnowledgePackBuilder.openNext(context).use { builder ->
                builder.replacePack(bundle)
            }
            val candidateFile =
                context.getDatabasePath(HighSchoolKnowledgePackBuilder.NEXT_DATABASE_NAME)
            writeCurrentActivationReceiptForTest(
                file = pendingFile,
                bundle = bundle,
                generation = 77L,
            )
            Os.rename(candidateFile.absolutePath, activeFile.absolutePath)

            assertEquals(null, KnowledgePackActivationManager.currentActivationReceipt(context))
            assertTrue(!activeFile.exists())
            assertTrue(!pendingFile.exists())
        } finally {
            clearProductionPackForTest(context)
        }
    }

    @Test
    fun sameSubjectHandlesRemainValidAcrossDisplayPagesAndCatalogReopen() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        clearProductionPackForTest(context)
        try {
            val bundle = fixtureBundle(knowledgePackVersion = "stable-subject-snapshot-v1")
            HighSchoolKnowledgePackBuilder.openNext(context).use { builder ->
                builder.replacePack(bundle)
            }
            KnowledgePackActivationManager.activateNext(
                context,
                activationAuthorization(bundle, generation = 1L),
            )

            var handles = emptyList<VerifiedKnowledgeNodeHandle>()
            HighSchoolKnowledgeCatalogFactory.open(context).use { catalog ->
                val firstPage =
                    catalog.readDisplayOrderPage(
                        subject = SubjectKind.MATH,
                        afterOrderToken = null,
                        limit = 1,
                    )
                val secondPage =
                    catalog.readDisplayOrderPage(
                        subject = SubjectKind.MATH,
                        afterOrderToken = requireNotNull(firstPage.nextAfterOrderToken),
                        limit = 1,
                    )
                val nodeIds =
                    (firstPage.entries + secondPage.entries)
                        .map { entry -> entry.ref.knowledgeNodeId }
                assertEquals(2, nodeIds.distinct().size)
                handles = catalog.resolveNodes(SubjectKind.MATH, nodeIds)
                assertEquals(nodeIds, handles.map { handle -> handle.ref.knowledgeNodeId })
            }

            HighSchoolKnowledgeCatalogFactory.open(context).use { reopened ->
                val materials = reopened.readTeachingMaterials(handles)
                assertTrue(materials.isNotEmpty())
                assertTrue(materials.all { material -> material.subject == SubjectKind.MATH })
            }
        } finally {
            clearProductionPackForTest(context)
        }
    }

    @Test
    fun trustedRoomLockReplacesOnlyASymlinkAndPreservesItsTarget() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "room-lock-symlink.knowledge-test.db"
        val databaseFile = context.getDatabasePath(databaseName)
        val lockFile = roomLockCompanionFile(databaseFile)
        val target = java.io.File(requireNotNull(databaseFile.parentFile), "room-lock-target.txt")
        requireNotNull(databaseFile.parentFile).mkdirs()
        target.writeText("do-not-touch", Charsets.UTF_8)
        Os.symlink(target.absolutePath, lockFile.absolutePath)
        try {
            HighSchoolKnowledgePackBuilder.openForTest(context, databaseName).use { builder ->
                builder.replacePack(fixtureBundle())
            }
            assertEquals("do-not-touch", target.readText(Charsets.UTF_8))
            assertTrue(OsConstants.S_ISREG(Os.lstat(lockFile.absolutePath).st_mode))
        } finally {
            deleteTrustedRoomLockCompanionIfPresent(databaseFile)
            context.deleteDatabase(databaseName)
            if (target.exists()) assertTrue(target.delete())
        }
    }

    @Test
    fun trustedRoomLockRejectsDirectories() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "room-lock-directory.knowledge-test.db"
        val databaseFile = context.getDatabasePath(databaseName)
        val lockFile = roomLockCompanionFile(databaseFile)
        requireNotNull(databaseFile.parentFile).mkdirs()
        assertTrue(lockFile.mkdir())
        try {
            assertNotNull(
                "A directory lock companion must be rejected",
                runCatching {
                    HighSchoolKnowledgePackBuilder.openForTest(context, databaseName).close()
                }.exceptionOrNull(),
            )
        } finally {
            assertTrue(lockFile.delete())
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun trustedRoomLockRejectsHardLinksWhenFilesystemSupportsThem() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "room-lock-hardlink.knowledge-test.db"
        val databaseFile = context.getDatabasePath(databaseName)
        val lockFile = roomLockCompanionFile(databaseFile)
        val target = java.io.File(requireNotNull(databaseFile.parentFile), "$databaseName.target")
        requireNotNull(databaseFile.parentFile).mkdirs()
        target.writeText("owned-target", Charsets.UTF_8)
        val linkFailure = runCatching {
            Os.link(target.absolutePath, lockFile.absolutePath)
        }.exceptionOrNull()
        if (linkFailure != null) {
            assertTrue(
                linkFailure is ErrnoException &&
                    linkFailure.errno in
                    setOf(OsConstants.EACCES, OsConstants.EPERM, OsConstants.EOPNOTSUPP),
            )
            target.delete()
            assumeNoException(
                "Device filesystem does not permit creating the hard-link test fixture",
                linkFailure,
            )
        }
        try {
            assertNotNull(
                "A hard-link lock companion must be rejected",
                runCatching {
                    HighSchoolKnowledgePackBuilder.openForTest(context, databaseName).close()
                }.exceptionOrNull(),
            )
            assertEquals("owned-target", target.readText(Charsets.UTF_8))
        } finally {
            if (lockFile.exists()) Os.remove(lockFile.absolutePath)
            if (target.exists()) assertTrue(target.delete())
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun staleTrustedRoomLockIsReusedAcrossConcurrentRuntimeCatalogs() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "room-lock-reuse.knowledge-test.db"
        val databaseFile = context.getDatabasePath(databaseName)
        val lockFile = roomLockCompanionFile(databaseFile)
        requireNotNull(databaseFile.parentFile).mkdirs()
        lockFile.writeBytes(byteArrayOf())
        val staleIdentity = Os.lstat(lockFile.absolutePath).st_ino
        try {
            HighSchoolKnowledgePackBuilder.openForTest(context, databaseName).use { builder ->
                builder.replacePack(fixtureBundle())
            }
            val first = HighSchoolKnowledgeCatalogFactory.openExistingForTest(context, databaseName)
            val second = HighSchoolKnowledgeCatalogFactory.openExistingForTest(context, databaseName)
            try {
                assertNotNull(first.readManifest())
                assertNotNull(second.readManifest())
                assertEquals(staleIdentity, Os.lstat(lockFile.absolutePath).st_ino)
                assertNotNull(
                    "A live catalog must pin the shared lock path",
                    runCatching {
                        deleteTrustedRoomLockCompanionIfPresent(databaseFile)
                    }.exceptionOrNull(),
                )
                first.close()
                assertNotNull(second.readManifest())
                assertEquals(staleIdentity, Os.lstat(lockFile.absolutePath).st_ino)
            } finally {
                runCatching { first.close() }
                second.close()
            }
            deleteTrustedRoomLockCompanionIfPresent(databaseFile)
            assertTrue(!lockFile.exists())
        } finally {
            if (lockFile.exists()) deleteTrustedRoomLockCompanionIfPresent(databaseFile)
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun activationRejectsNoCasePrimaryKeyEvenWhenRoomIdentityHashIsUnchanged() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        clearProductionPackForTest(context)
        try {
            val bundle = fixtureBundle(knowledgePackVersion = "nocase-pk-v1")
            HighSchoolKnowledgePackBuilder.openNext(context).use { builder ->
                builder.replacePack(bundle)
            }
            val candidateFile =
                context.getDatabasePath(HighSchoolKnowledgePackBuilder.NEXT_DATABASE_NAME)
            SQLiteDatabase.openDatabase(
                candidateFile.absolutePath,
                null,
                SQLiteDatabase.OPEN_READWRITE,
            ).use { database ->
                database.execSQL(
                    "ALTER TABLE knowledge_pack_manifest RENAME TO old_knowledge_pack_manifest",
                )
                database.execSQL(
                    """
                    CREATE TABLE knowledge_pack_manifest (
                        manifest_key TEXT NOT NULL COLLATE NOCASE PRIMARY KEY,
                        pack_id TEXT NOT NULL,
                        schema_version INTEGER NOT NULL,
                        knowledge_pack_version TEXT NOT NULL,
                        taxonomy_version TEXT NOT NULL,
                        search_index_version TEXT NOT NULL,
                        content_fingerprint TEXT NOT NULL,
                        built_at_epoch_millis INTEGER NOT NULL,
                        node_count INTEGER NOT NULL,
                        source_count INTEGER NOT NULL,
                        relation_count INTEGER NOT NULL,
                        material_count INTEGER NOT NULL,
                        search_feature_count INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                database.execSQL(
                    "INSERT INTO knowledge_pack_manifest SELECT * FROM old_knowledge_pack_manifest",
                )
                database.execSQL("DROP TABLE old_knowledge_pack_manifest")
            }

            val failure =
                runCatching {
                    KnowledgePackActivationManager.activateNext(
                        context,
                        activationAuthorization(bundle, generation = 1L),
                    )
                }.exceptionOrNull()

            assertNotNull("Primary-key collation is part of the trusted schema", failure)
            assertTrue(!context.getDatabasePath(HIGH_SCHOOL_KNOWLEDGE_DATABASE_NAME).exists())
        } finally {
            clearProductionPackForTest(context)
        }
    }

    private fun nodeRef(knowledgeNodeId: String): KnowledgeNodeRef =
        KnowledgeNodeRef(
            subject = SubjectKind.MATH,
            knowledgeNodeId = knowledgeNodeId,
            taxonomyVersion = TAXONOMY_VERSION,
            knowledgePackVersion = KNOWLEDGE_PACK_VERSION,
        )

    private fun fixtureBundle(
        knowledgePackVersion: String = KNOWLEDGE_PACK_VERSION,
    ): KnowledgePackInstallBundle {
        val mathNodes =
            listOf(
                KnowledgeNodeEntity(
                    knowledgeNodeId = FUNCTION_NODE_ID,
                    stableCode = "math.function",
                    subject = SubjectKind.MATH.name,
                    displayName = "函数",
                    canonicalName = "函数",
                    nodeKind = KnowledgeNodeKind.TOPIC.name,
                    granularity = KnowledgeNodeGranularity.TOPIC.name,
                    aliasesText = encodeAliases(listOf("函数概念")),
                    boundaryMarkdown = "高中函数的定义、表示与基本性质。",
                    verificationStatus = KnowledgeNodeVerificationStatus.CURATED.name,
                    parentKnowledgeNodeId = null,
                    taxonomyVersion = TAXONOMY_VERSION,
                    reviewedAtEpochMillis = REVIEWED_AT,
                ),
                KnowledgeNodeEntity(
                    knowledgeNodeId = QUADRATIC_NODE_ID,
                    stableCode = "math.function.quadratic",
                    subject = SubjectKind.MATH.name,
                    displayName = "二次函数",
                    canonicalName = "二次函数",
                    nodeKind = KnowledgeNodeKind.CONCEPT.name,
                    granularity = KnowledgeNodeGranularity.ATOMIC.name,
                    aliasesText = encodeAliases(listOf("抛物线", "二次多项式函数")),
                    boundaryMarkdown = "研究二次函数的图像、开口、对称轴与性质。",
                    verificationStatus = KnowledgeNodeVerificationStatus.SOURCE_GROUNDED.name,
                    parentKnowledgeNodeId = FUNCTION_NODE_ID,
                    taxonomyVersion = TAXONOMY_VERSION,
                    reviewedAtEpochMillis = REVIEWED_AT,
                ),
            )
        val otherSubjectNodes =
            highSchoolSubjects()
                .filter { it != SubjectKind.MATH }
                .map { subject ->
                    KnowledgeNodeEntity(
                        knowledgeNodeId = "node.${subject.name.lowercase()}.overview",
                        stableCode = "${subject.name.lowercase()}.overview",
                        subject = subject.name,
                        displayName = "${subject.name}基础",
                        canonicalName = "${subject.name}基础",
                        nodeKind = KnowledgeNodeKind.TOPIC.name,
                        granularity = KnowledgeNodeGranularity.TOPIC.name,
                        aliasesText = encodeAliases(emptyList()),
                        boundaryMarkdown = "该学科高中阶段的测试边界。",
                        verificationStatus = KnowledgeNodeVerificationStatus.CURATED.name,
                        parentKnowledgeNodeId = null,
                        taxonomyVersion = TAXONOMY_VERSION,
                        reviewedAtEpochMillis = REVIEWED_AT,
                    )
                }
        val nodes = mathNodes + otherSubjectNodes
        val sources =
            highSchoolSubjects().map { subject ->
                KnowledgeSourceEntity(
                    sourceId = sourceId(subject),
                    subject = subject.name,
                    sourceType = KnowledgeSourceType.OFFICIAL_CURRICULUM_STANDARD.name,
                    title = "${subject.name}高中课程标准",
                    publisher = "测试出版社",
                    edition = "测试版",
                    sourceUri = null,
                    licenseStatus = KnowledgeSourceLicenseStatus.PUBLIC_OFFICIAL.name,
                    contentUsePolicy =
                        KnowledgeSourceContentUsePolicy.REVIEWED_SYNTHESIS_ONLY.name,
                    contentFingerprint = sourceFingerprint(subject),
                    licenseExpression = null,
                    licenseUri = null,
                    attributionText = "测试来源",
                    reviewedAtEpochMillis = REVIEWED_AT,
                )
            }
        val relation =
            KnowledgeNodeRelationEntity(
                relationId = "relation.quadratic.part-of.function",
                subject = SubjectKind.MATH.name,
                fromKnowledgeNodeId = QUADRATIC_NODE_ID,
                toKnowledgeNodeId = FUNCTION_NODE_ID,
                relationType = "PART_OF",
                taxonomyVersion = TAXONOMY_VERSION,
                sourceId = SOURCE_ID,
                sourceLocator = "课程标准/函数",
                reviewedAtEpochMillis = REVIEWED_AT,
            )
        val material =
            KnowledgeTeachingMaterialEntity(
                materialId = MATERIAL_ID,
                stableCode = "material.math.quadratic.concept",
                subject = SubjectKind.MATH.name,
                materialType = KnowledgeTeachingMaterialType.CONCEPT_EXPLANATION.name,
                title = "二次函数概念说明",
                summaryMarkdown = "二次函数的简要说明。",
                applicabilityMarkdown = "适用于二次函数概念复习。",
                contentMarkdown = "二次函数可写成 \$y=ax^2+bx+c\$。",
                boundaryMarkdown = "不包含大学阶段的二次型。",
                derivationKind = KnowledgeMaterialDerivationKind.REVIEWED_SYNTHESIS.name,
                sourceId = SOURCE_ID,
                sourceLocator = "课程标准/函数",
                contentFingerprint = "c".repeat(64),
                reviewedAtEpochMillis = REVIEWED_AT,
            )
        val searchFeatures = KnowledgeSearchIndexBuilder.build(nodes)

        return KnowledgePackInstallBundle(
            manifest =
                KnowledgePackManifestEntity(
                    manifestKey = ACTIVE_MANIFEST_KEY,
                    packId = "high-school-test-pack",
                    schemaVersion = HIGH_SCHOOL_KNOWLEDGE_DATABASE_VERSION,
                    knowledgePackVersion = knowledgePackVersion,
                    taxonomyVersion = TAXONOMY_VERSION,
                    searchIndexVersion = "test-search-v1",
                    contentFingerprint = "0".repeat(64),
                    builtAtEpochMillis = REVIEWED_AT,
                    nodeCount = nodes.size,
                    sourceCount = sources.size,
                    relationCount = 1,
                    materialCount = 1,
                    searchFeatureCount = searchFeatures.size,
                ),
            nodes = nodes,
            sources = sources,
            nodeSourceBindings =
                nodes.map { node ->
                    KnowledgeNodeSourceBindingEntity(
                        knowledgeNodeId = node.knowledgeNodeId,
                        sourceId = sourceId(SubjectKind.valueOf(node.subject)),
                        sourceLocator = "课程标准/${node.stableCode}",
                        derivationNote = "依据课程标准人工复核。",
                        reviewedAtEpochMillis = REVIEWED_AT,
                    )
                },
            relations = listOf(relation),
            searchFeatures = searchFeatures,
            materials = listOf(material),
            materialBindings =
                listOf(
                    KnowledgeTeachingMaterialNodeBindingEntity(
                        materialId = MATERIAL_ID,
                        knowledgeNodeId = QUADRATIC_NODE_ID,
                        role = KnowledgeMaterialNodeRole.PRIMARY.name,
                    ),
                ),
        ).withRecomputedContentFingerprint()
    }

    private fun highSchoolSubjects(): List<SubjectKind> =
        enumValues<SubjectKind>().filter { it != SubjectKind.GENERAL }

    private fun sourceId(subject: SubjectKind): String =
        if (subject == SubjectKind.MATH) {
            SOURCE_ID
        } else {
            "source.${subject.name.lowercase()}.curriculum"
        }

    private fun sourceFingerprint(subject: SubjectKind): String =
        (subject.ordinal + 1).toString(16).padStart(64, '0')

    private fun activationAuthorization(
        bundle: KnowledgePackInstallBundle,
        generation: Long,
    ): KnowledgePackActivationAuthorization =
        KnowledgePackActivationAuthorization(
            packId = bundle.manifest.packId,
            knowledgePackVersion = bundle.manifest.knowledgePackVersion,
            taxonomyVersion = bundle.manifest.taxonomyVersion,
            generation = generation,
            expectedContentFingerprint = bundle.manifest.contentFingerprint,
        )

    private fun writeActivationReceiptForTest(
        file: java.io.File,
        bundle: KnowledgePackInstallBundle,
        generation: Long,
        manifestFingerprint: String,
    ) {
        file.writeText(
            listOf(
                "high-school-knowledge-activation-v2",
                generation.toString(),
                REVIEWED_AT.toString(),
                bundle.manifest.packId,
                bundle.manifest.knowledgePackVersion,
                bundle.manifest.taxonomyVersion,
                manifestFingerprint,
            ).joinToString(separator = "\n", postfix = "\n"),
            Charsets.UTF_8,
        )
    }

    private fun writeCurrentActivationReceiptForTest(
        file: java.io.File,
        bundle: KnowledgePackInstallBundle,
        generation: Long,
    ) {
        val manifest = bundle.manifest
        val authorizationIdentity =
            trustedKnowledgePackAuthorizationIdentity(
                packId = manifest.packId,
                knowledgePackVersion = manifest.knowledgePackVersion,
                taxonomyVersion = manifest.taxonomyVersion,
                searchIndexVersion = manifest.searchIndexVersion,
                generation = generation,
                contentFingerprint = manifest.contentFingerprint,
            )
        file.writeText(
            listOf(
                "high-school-knowledge-activation-v3",
                generation.toString(),
                REVIEWED_AT.toString(),
                manifest.packId,
                manifest.knowledgePackVersion,
                manifest.taxonomyVersion,
                manifest.searchIndexVersion,
                manifest.contentFingerprint,
                authorizationIdentity,
            ).joinToString(separator = "\n", postfix = "\n"),
            Charsets.UTF_8,
        )
    }

    private fun List<String>.countKnowledgeAuthoritySelects(): Int =
        count { sql ->
            sql.trimStart().startsWith("SELECT", ignoreCase = true) &&
                (
                    sql.contains("knowledge_pack_manifest", ignoreCase = true) ||
                        sql.contains("knowledge_node", ignoreCase = true)
                )
        }

    private fun copyFileWithSyncForTest(
        source: java.io.File,
        destination: java.io.File,
    ) {
        source.inputStream().use { input ->
            java.io.FileOutputStream(destination, false).use { output ->
                input.copyTo(output)
                output.fd.sync()
            }
        }
    }

    private fun KnowledgePackInstallBundle.toReviewedPack(
        metadata: ReviewedKnowledgePackMetadata? = null,
    ): ReviewedKnowledgePack =
        ReviewedKnowledgePack(
            metadata =
                metadata ?: ReviewedKnowledgePackMetadata(
                    packId = manifest.packId,
                    knowledgePackVersion = manifest.knowledgePackVersion,
                    taxonomyVersion = manifest.taxonomyVersion,
                    searchIndexVersion = manifest.searchIndexVersion,
                    builtAtEpochMillis = manifest.builtAtEpochMillis,
                ),
            nodes =
                nodes.map { node ->
                    ReviewedKnowledgeNode(
                        knowledgeNodeId = node.knowledgeNodeId,
                        stableCode = node.stableCode,
                        subject = SubjectKind.valueOf(node.subject),
                        displayName = node.displayName,
                        canonicalName = node.canonicalName,
                        kind = KnowledgeNodeKind.valueOf(node.nodeKind),
                        granularity = KnowledgeNodeGranularity.valueOf(node.granularity),
                        aliases = decodeAliases(node.aliasesText),
                        boundaryMarkdown = node.boundaryMarkdown,
                        verificationStatus =
                            KnowledgeNodeVerificationStatus.valueOf(node.verificationStatus),
                        parentKnowledgeNodeId = node.parentKnowledgeNodeId,
                        reviewedAtEpochMillis = node.reviewedAtEpochMillis,
                    )
                },
            sources =
                sources.map { source ->
                    ReviewedKnowledgeSource(
                        sourceId = source.sourceId,
                        subject = SubjectKind.valueOf(source.subject),
                        sourceType = KnowledgeSourceType.valueOf(source.sourceType),
                        title = source.title,
                        publisher = source.publisher,
                        edition = source.edition,
                        sourceUri = source.sourceUri,
                        licenseStatus =
                            KnowledgeSourceLicenseStatus.valueOf(source.licenseStatus),
                        contentUsePolicy =
                            KnowledgeSourceContentUsePolicy.valueOf(source.contentUsePolicy),
                        contentFingerprint = source.contentFingerprint,
                        licenseExpression = source.licenseExpression,
                        licenseUri = source.licenseUri,
                        attributionText = source.attributionText,
                        reviewedAtEpochMillis = source.reviewedAtEpochMillis,
                    )
                },
            nodeSourceBindings =
                nodeSourceBindings.map { binding ->
                    ReviewedKnowledgeNodeSourceBinding(
                        knowledgeNodeId = binding.knowledgeNodeId,
                        sourceId = binding.sourceId,
                        sourceLocator = binding.sourceLocator,
                        derivationNote = binding.derivationNote,
                        reviewedAtEpochMillis = binding.reviewedAtEpochMillis,
                    )
                },
            relations =
                relations.map { relation ->
                    ReviewedKnowledgeRelation(
                        relationId = relation.relationId,
                        subject = SubjectKind.valueOf(relation.subject),
                        fromKnowledgeNodeId = relation.fromKnowledgeNodeId,
                        toKnowledgeNodeId = relation.toKnowledgeNodeId,
                        relationType = relation.relationType,
                        sourceId = relation.sourceId,
                        sourceLocator = relation.sourceLocator,
                        reviewedAtEpochMillis = relation.reviewedAtEpochMillis,
                    )
                },
            teachingMaterials =
                materials.map { material ->
                    ReviewedKnowledgeTeachingMaterial(
                        materialId = material.materialId,
                        stableCode = material.stableCode,
                        subject = SubjectKind.valueOf(material.subject),
                        materialType =
                            KnowledgeTeachingMaterialType.valueOf(material.materialType),
                        title = material.title,
                        summaryMarkdown = material.summaryMarkdown,
                        applicabilityMarkdown = material.applicabilityMarkdown,
                        contentMarkdown = material.contentMarkdown,
                        boundaryMarkdown = material.boundaryMarkdown,
                        derivationKind =
                            KnowledgeMaterialDerivationKind.valueOf(material.derivationKind),
                        sourceId = material.sourceId,
                        sourceLocator = material.sourceLocator,
                        contentFingerprint = material.contentFingerprint,
                        reviewedAtEpochMillis = material.reviewedAtEpochMillis,
                    )
                },
            teachingMaterialBindings =
                materialBindings.map { binding ->
                    ReviewedKnowledgeTeachingMaterialBinding(
                        materialId = binding.materialId,
                        knowledgeNodeId = binding.knowledgeNodeId,
                        role = KnowledgeMaterialNodeRole.valueOf(binding.role),
                    )
                },
        )

    private fun clearProductionPackForTest(context: android.content.Context) {
        HighSchoolKnowledgePackBuilder.discardNext(context)
        deleteTrustedRoomLockCompanionIfPresent(
            context.getDatabasePath(HIGH_SCHOOL_KNOWLEDGE_DATABASE_NAME),
        )
        context.deleteDatabase(HIGH_SCHOOL_KNOWLEDGE_DATABASE_NAME)
        val databaseDirectory =
            requireNotNull(context.getDatabasePath(HIGH_SCHOOL_KNOWLEDGE_DATABASE_NAME).parentFile)
        listOf(
            "$HIGH_SCHOOL_KNOWLEDGE_DATABASE_NAME.activation",
            "$HIGH_SCHOOL_KNOWLEDGE_DATABASE_NAME.activation.pending",
            "$HIGH_SCHOOL_KNOWLEDGE_DATABASE_NAME.activation.rollback",
            "$HIGH_SCHOOL_KNOWLEDGE_DATABASE_NAME.activation.validation",
        ).forEach { name ->
            val file = java.io.File(databaseDirectory, name)
            if (file.exists()) assertTrue(file.delete())
        }
    }

    private companion object {
        const val TAXONOMY_VERSION = "high-school-test-taxonomy-v1"
        const val KNOWLEDGE_PACK_VERSION = "test-content-v1"
        const val FUNCTION_NODE_ID = "node.math.function"
        const val QUADRATIC_NODE_ID = "node.math.function.quadratic"
        const val SOURCE_ID = "source.math.curriculum"
        const val MATERIAL_ID = "material.math.quadratic"
        const val TEST_DATABASE_NAME = "high-school-catalog-slice.knowledge-test.db"
        const val REVIEWED_AT = 1_700_000_000_000L
    }
}
