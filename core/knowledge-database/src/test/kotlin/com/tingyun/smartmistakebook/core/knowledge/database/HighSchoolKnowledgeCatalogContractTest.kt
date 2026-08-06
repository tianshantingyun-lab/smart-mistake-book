package com.tingyun.smartmistakebook.core.knowledge.database

import com.tingyun.smartmistakebook.core.model.KnowledgeMaterialDerivationKind
import com.tingyun.smartmistakebook.core.model.KnowledgeMaterialNodeRole
import com.tingyun.smartmistakebook.core.model.KnowledgeNodeGranularity
import com.tingyun.smartmistakebook.core.model.KnowledgeNodeKind
import com.tingyun.smartmistakebook.core.model.KnowledgeNodeVerificationStatus
import com.tingyun.smartmistakebook.core.model.KnowledgeTeachingMaterialType
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.storage.KnowledgeNodeRef
import com.tingyun.smartmistakebook.core.model.storage.VerifiedKnowledgeReferenceProof
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HighSchoolKnowledgeCatalogContractTest {
    @Test
    fun roomLockMetadataRejectsHardLinksWithoutFilesystemSupport() {
        assertTrue(
            isOwnedSingleLinkRegularRoomLockMetadata(
                isRegularFile = true,
                ownerUid = 10_000,
                expectedOwnerUid = 10_000,
                linkCount = 1L,
            ),
        )
        assertTrue(
            !isOwnedSingleLinkRegularRoomLockMetadata(
                isRegularFile = true,
                ownerUid = 10_000,
                expectedOwnerUid = 10_000,
                linkCount = 2L,
            ),
        )
    }

    @Test
    fun productionDatabaseNameIsFixed() {
        assertEquals("high-school-knowledge.db", HIGH_SCHOOL_KNOWLEDGE_DATABASE_NAME)
    }

    @Test
    fun runtimeCatalogDeclaresOnlyReadOperations() {
        assertEquals(
            setOf(
                "expandRelations",
                "findNode",
                "findNodes",
                "observeSnapshotRevision",
                "readDisplayOrderPage",
                "readManifest",
                "readNeighborhood",
                "readTeachingMaterials",
                "recall",
                "resolveNode",
                "resolveNodes",
                "verifyReference",
                "verifyReferences",
            ),
            HighSchoolKnowledgeCatalog::class.java.declaredMethods
                .filterNot { it.isSynthetic || '$' in it.name }
                .mapTo(sortedSetOf(), java.lang.reflect.Method::getName),
        )
        val forbiddenFragments =
            listOf("insert", "update", "delete", "write", "install", "raw", "database", "attach")
        HighSchoolKnowledgeCatalog::class.java.methods.forEach { method ->
            forbiddenFragments.forEach { fragment ->
                assertTrue(
                    "Runtime catalog unexpectedly exposes ${method.name}",
                    !method.name.contains(fragment, ignoreCase = true),
                )
            }
        }
        assertEquals(
            KnowledgeNodeRef::class.java,
            HighSchoolKnowledgeCatalog::class.java
                .getDeclaredMethod("findNode", KnowledgeNodeRef::class.java, kotlin.coroutines.Continuation::class.java)
                .parameterTypes
                .first(),
        )
        assertEquals(
            List::class.java,
            HighSchoolKnowledgeCatalog::class.java
                .getDeclaredMethod(
                    "findNodes",
                    List::class.java,
                    kotlin.coroutines.Continuation::class.java,
                ).parameterTypes
                .first(),
        )
        assertEquals(
            StateFlow::class.java,
            HighSchoolKnowledgeCatalog::class.java
                .getDeclaredMethod("observeSnapshotRevision")
                .returnType,
        )
        assertEquals(
            List::class.java,
            HighSchoolKnowledgeCatalog::class.java
                .getDeclaredMethod(
                    "verifyReferences",
                    List::class.java,
                    kotlin.coroutines.Continuation::class.java,
                ).parameterTypes
                .first(),
        )
    }

    @Test
    fun batchDisplayLookupSurfaceIsBoundedAndContainsNoLearnerAuthorityTypes() {
        assertEquals(64, HighSchoolKnowledgeCatalog.MAX_BATCH_NODE_REQUESTS)
        assertEquals(64, HighSchoolKnowledgeCatalog.MAX_BATCH_NODE_ROWS)
        val exposedClasses =
            listOf(
                KnowledgeCatalogSnapshotMetadata::class.java,
                KnowledgeCatalogNodeDisplayMetadata::class.java,
                KnowledgeCatalogNodeDisplayLookup::class.java,
                KnowledgeCatalogNodeDisplayBatch::class.java,
            )
        val forbiddenFragments =
            listOf("Learner", "Student", "Problem", "Conversation", "Authority", "Mastery")
        exposedClasses.forEach { exposedClass ->
            val exposedTypes =
                exposedClass.declaredFields.map { field -> field.type.name } +
                    exposedClass.declaredMethods.flatMap { method ->
                        method.parameterTypes.map(Class<*>::getName) + method.returnType.name
                    }
            forbiddenFragments.forEach { fragment ->
                assertTrue(
                    "$exposedClass unexpectedly exposes $fragment state",
                    exposedTypes.none { typeName -> fragment in typeName },
                )
            }
        }
    }

    @Test
    fun catalogWithoutBatchedNeighborhoodOverrideFailsBeforePerNodeApis() {
        val catalog = CatalogWithoutNeighborhoodOverride()

        val failure =
            runImmediateSuspend {
                catalog.readNeighborhood(
                    subject = SubjectKind.MATH,
                    query = "bounded lookup",
                )
            }.exceptionOrNull()

        assertTrue(failure is UnsupportedOperationException)
        assertEquals(0, catalog.perNodeApiCallCount)
    }

    @Test
    fun verifiedNodeCapabilityCannotBeConstructedOrCopiedByCallers() {
        assertTrue(
            VerifiedKnowledgeNodeHandle::class.java.constructors.none { constructor ->
                !constructor.isSynthetic
            },
        )
        assertTrue(
            VerifiedKnowledgeNodeHandle::class.java.declaredMethods.none {
                it.name == "copy"
            },
        )
        assertTrue(
            VerifiedKnowledgeReferenceProof::class.java.constructors.none { constructor ->
                !constructor.isSynthetic
            },
        )
        assertTrue(
            VerifiedKnowledgeReferenceProof::class.java.declaredMethods.none {
                it.name == "copy"
            },
        )
        assertEquals(
            setOf("getActivationGeneration", "getManifestFingerprint", "getRef"),
            VerifiedKnowledgeReferenceProof::class.java.declaredMethods
                .filter { java.lang.reflect.Modifier.isPublic(it.modifiers) }
                .filterNot { it.isSynthetic }
                .mapTo(sortedSetOf(), java.lang.reflect.Method::getName),
        )
        assertTrue(
            VerifiedKnowledgeReferenceProof::class.java.declaredMethods.none {
                it.returnType == KnowledgeCatalogNode::class.java ||
                    it.name.contains("node", ignoreCase = true) ||
                    it.name.contains("alias", ignoreCase = true) ||
                    it.name.contains("boundary", ignoreCase = true)
            },
        )
        assertTrue(
            KnowledgeCatalogActivationReceipt::class.java.constructors.none { constructor ->
                !constructor.isSynthetic
            },
        )
        assertTrue(
            KnowledgeCatalogActivationReceipt::class.java.declaredMethods.none {
                it.name == "copy"
            },
        )
        assertTrue(
            KnowledgeCatalogActivationReceipt::class.java.declaredMethods.any {
                it.name == "getManifestFingerprint"
            },
        )
        assertTrue(
            KnowledgeCatalogActivationReceipt::class.java.declaredMethods.none {
                it.name == "getContentFingerprint"
            },
        )
        assertTrue(
            KnowledgePackProvisionReceipt::class.java.constructors.none { constructor ->
                !constructor.isSynthetic
            },
        )
        assertTrue(
            KnowledgePackProvisionReceipt::class.java.declaredMethods.none {
                it.name == "copy"
            },
        )
        assertTrue(
            KnowledgePackProvisionReceipt::class.java.declaredMethods.any {
                it.name == "getProductionCutoverEligible"
            },
        )
        val receiptCompanion =
            KnowledgePackProvisionReceipt::class.java.declaredClasses.single { nested ->
                nested.simpleName == "Companion"
            }
        val publicReceiptIssuers =
            receiptCompanion.declaredMethods.filter { method ->
                java.lang.reflect.Modifier.isPublic(method.modifiers) && !method.isSynthetic
            }
        assertEquals(1, publicReceiptIssuers.size)
        val safeIssuer = publicReceiptIssuers.single()
        assertTrue(safeIssuer.name.startsWith("provisionRegisteredPack"))
        assertEquals(
            listOf(
                android.content.Context::class.java,
                ReviewedKnowledgePack::class.java,
                kotlin.coroutines.Continuation::class.java,
            ),
            safeIssuer.parameterTypes.toList(),
        )
        val forbiddenIssuerParameters =
            setOf(
                java.lang.Boolean.TYPE,
                KnowledgeCatalogActivationReceipt::class.java,
            )
        assertTrue(
            (KnowledgePackProvisionReceipt::class.java.declaredMethods +
                receiptCompanion.declaredMethods).none { method ->
                java.lang.reflect.Modifier.isPublic(method.modifiers) &&
                    method.parameterTypes.any(forbiddenIssuerParameters::contains)
            },
        )
    }

    @Test
    fun trustedProvisioningDoesNotExposeMintableAuthorizationOrRawInputs() {
        val provision =
            HighSchoolKnowledgePackProvisioner::class.java.declaredMethods.single { method ->
                method.name == "provisionApkBundledReviewedPack"
            }
        assertEquals(
            listOf(
                android.content.Context::class.java,
                ReviewedKnowledgePack::class.java,
                kotlin.coroutines.Continuation::class.java,
            ),
            provision.parameterTypes.toList(),
        )
        val forbiddenTypes =
            listOf(
                java.io.File::class.java,
                java.io.InputStream::class.java,
                java.net.URL::class.java,
                android.database.sqlite.SQLiteDatabase::class.java,
            )
        assertTrue(provision.parameterTypes.none(forbiddenTypes::contains))
        assertTrue(
            provision.parameterTypes.none { parameter ->
                parameter.simpleName.contains("Authorization")
            },
        )
        assertTrue(
            HistoricalSampleKnowledgePackAuthorization::class.java.declaredMethods.none { method ->
                method.name == "authorization"
            },
        )
        assertTrue(
            HistoricalSampleKnowledgePackAuthorization::class.java.constructors.none { constructor ->
                !constructor.isSynthetic
            },
        )
        assertThrows(ClassNotFoundException::class.java) {
            Class.forName(
                "com.tingyun.smartmistakebook.core.knowledge.database." +
                    "ReviewedKnowledgePackAuthorization",
            )
        }
        val privateAuthorization =
            Class.forName(
                "com.tingyun.smartmistakebook.core.knowledge.database." +
                    "TrustedKnowledgePackAuthorization",
            )
        assertTrue(!java.lang.reflect.Modifier.isPublic(privateAuthorization.modifiers))
    }

    @Test
    fun teachingMaterialDtoEnforcesPerItemMarkdownBudget() {
        assertThrows(IllegalArgumentException::class.java) {
            KnowledgeCatalogTeachingMaterial(
                materialId = "material.math.oversized",
                stableCode = "material.math.oversized",
                knowledgeNodeRef =
                    KnowledgeNodeRef(
                        subject = SubjectKind.MATH,
                        knowledgeNodeId = "node.math.oversized",
                        taxonomyVersion = "test-taxonomy-v1",
                        knowledgePackVersion = "test-content-v1",
                    ),
                subject = SubjectKind.MATH,
                materialType = KnowledgeTeachingMaterialType.CONCEPT_EXPLANATION,
                nodeRole = KnowledgeMaterialNodeRole.PRIMARY,
                title = "Oversized material",
                summaryMarkdown = "s".repeat(10_000),
                applicabilityMarkdown = "a",
                contentMarkdown =
                    "x".repeat(
                        HighSchoolKnowledgeCatalog
                            .MAX_SINGLE_TEACHING_MATERIAL_MARKDOWN_CHARS / 2,
                    ),
                boundaryMarkdown = "b",
                derivationKind = KnowledgeMaterialDerivationKind.REVIEWED_SYNTHESIS,
                sourceId = "source.math",
                sourceLocator = "reviewed source",
                contentFingerprint = "a".repeat(64),
                reviewedAtEpochMillis = 1L,
            )
        }
    }

    @Test
    fun modelContextFacadeExposesOnlyBoundedReviewedTypes() {
        assertEquals(
            setOf("read"),
            ReviewedKnowledgeContextReader::class.java.declaredMethods
                .filterNot { method -> method.isSynthetic }
                .mapTo(sortedSetOf(), java.lang.reflect.Method::getName),
        )
        assertEquals(
            setOf("open"),
            ReviewedKnowledgeContextReaderFactory::class.java.declaredMethods
                .filterNot { method -> method.isSynthetic }
                .mapTo(sortedSetOf(), java.lang.reflect.Method::getName),
        )
        val read =
            ReviewedKnowledgeContextReader::class.java.getDeclaredMethod(
                "read",
                ReviewedKnowledgeContextRequest::class.java,
                kotlin.coroutines.Continuation::class.java,
            )
        assertEquals(ReviewedKnowledgeContextRequest::class.java, read.parameterTypes.first())
        val forbiddenTypeFragments =
            listOf(
                "HighSchoolKnowledgeCatalog",
                "KnowledgeNodeRef",
                "KnowledgeCatalogDao",
                "RoomDatabase",
                "SQLiteDatabase",
            )
        val exposedTypes =
            ReviewedKnowledgeContextReader::class.java.methods.flatMap { method ->
                method.parameterTypes.toList() + method.returnType
            }
        forbiddenTypeFragments.forEach { fragment ->
            assertTrue(exposedTypes.none { type -> fragment in type.name })
        }
        assertEquals(6, ReviewedKnowledgeContextReader.MAX_DIRECT_NODES)
        assertEquals(12, ReviewedKnowledgeContextReader.MAX_RELATED_NODES)
        assertEquals(4, ReviewedKnowledgeContextReader.MAX_TEACHING_MATERIALS)
        assertEquals(20_000, ReviewedKnowledgeContextReader.MAX_TEACHING_MATERIAL_MARKDOWN_CHARS)
        assertEquals(64_000, ReviewedKnowledgeContextReader.MAX_TOTAL_MODEL_CONTEXT_CHARS)
        val modelFacingFields =
            listOf(
                ReviewedKnowledgeContextNode::class.java,
                ReviewedKnowledgeContextRelation::class.java,
                ReviewedKnowledgeContextMaterial::class.java,
                ReviewedKnowledgeContext::class.java,
            ).flatMap { type ->
                type.declaredFields.map { field -> field.name.lowercase() }
            }
        listOf(
            "taxonomy",
            "verificationstatus",
            "sourceid",
            "sourcelocator",
            "contentfingerprint",
            "knowledgenodeid",
            "relationid",
            "materialid",
            "rank",
            "origin",
            "granularity",
            "nodekind",
            "materialtype",
            "noderole",
            "derivationkind",
        ).forEach { forbidden ->
            assertTrue(
                "Model-facing context unexpectedly exposes $forbidden",
                modelFacingFields.none { field -> forbidden in field },
            )
        }
    }

    @Test
    fun modelContextRequestRequiresOneSubjectBeforeSearch() {
        assertThrows(IllegalArgumentException::class.java) {
            ReviewedKnowledgeContextRequest(
                subject = SubjectKind.GENERAL,
                query = "generic synthetic query",
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReviewedKnowledgeContextRequest(
                subject = SubjectKind.MATH,
                currentQuestion =
                    "x".repeat(
                        HighSchoolKnowledgeCatalog.MAX_STRUCTURED_QUERY_FIELD_CHARS + 1,
                    ),
            )
        }
    }

    @Test
    fun searchNormalizationIsLocaleIndependentAndDeterministic() {
        val first = KnowledgeSearchNormalizer.queryFeatures("  二次函数：图像  ")
        val second = KnowledgeSearchNormalizer.queryFeatures("二次函数：图像")

        assertEquals(first, second)
        assertTrue("二次函数" in first)
        assertTrue("二次函数图像" in first)
        assertTrue("图像" in first)
        assertEquals(
            KnowledgeSearchNormalizer.queryFeatures("Ｈｅｌｌｏ"),
            KnowledgeSearchNormalizer.queryFeatures("hello"),
        )
    }

    @Test
    fun searchNormalizationPreservesScientificMeaning() {
        val neutralSodium = KnowledgeSearchNormalizer.queryFeatures("Na")
        val sodiumIon = KnowledgeSearchNormalizer.queryFeatures("Na+")
        val chloride = KnowledgeSearchNormalizer.queryFeatures("Cl")
        val chlorideIon = KnowledgeSearchNormalizer.queryFeatures("Cl−")

        assertTrue("Na" in neutralSodium)
        assertTrue("Na+" in sodiumIon)
        assertTrue("Na+" !in neutralSodium)
        assertTrue("Cl" in chloride)
        assertTrue("Cl-" in chlorideIon)
        assertTrue("Cl-" !in chloride)
        assertEquals(
            KnowledgeSearchNormalizer.queryFeatures("Na⁺Cl⁻"),
            KnowledgeSearchNormalizer.queryFeatures("Na + Cl -"),
        )
        assertTrue(
            KnowledgeSearchNormalizer.queryFeatures("V") !=
                KnowledgeSearchNormalizer.queryFeatures("v"),
        )
        assertTrue(
            KnowledgeSearchNormalizer.queryFeatures("Ω") !=
                KnowledgeSearchNormalizer.queryFeatures("ω"),
        )
        assertTrue("+" in KnowledgeSearchNormalizer.queryFeatures("V+v"))
        assertTrue("Na+" !in KnowledgeSearchNormalizer.queryFeatures("V+v"))
        assertTrue("=" in KnowledgeSearchNormalizer.queryFeatures("f′(x)=0"))
        assertTrue("≠" in KnowledgeSearchNormalizer.queryFeatures("f′(x)≠0"))
        assertTrue("⇌" in KnowledgeSearchNormalizer.queryFeatures("N₂+3H₂⇌2NH₃"))
        assertTrue("→" in KnowledgeSearchNormalizer.queryFeatures("a⃗→b⃗"))
        assertTrue(
            KnowledgeSearchNormalizer.queryFeatures("f′(x)=0") !=
                KnowledgeSearchNormalizer.queryFeatures("f′(x)≠0"),
        )
    }

    @Test
    fun longProblemFeaturesCoverHeadMiddleTailAndSubquestions() {
        val longProblem =
            buildString {
                append("驻点 ")
                append("甲 ".repeat(2_400))
                append(" Na+ ")
                append("乙 ".repeat(1_200))
                append("（2）Cl- 与反应平衡 ⇌ ")
                append("丙 ".repeat(1_200))
                append("导数为零")
            }

        val first = KnowledgeSearchNormalizer.queryFeatures(longProblem)
        val second = KnowledgeSearchNormalizer.queryFeatures(longProblem)

        assertEquals(first, second)
        assertTrue("驻点" in first)
        assertTrue("Na+" in first)
        assertTrue("Cl-" in first)
        assertTrue("⇌" in first)
        assertTrue("导数为零" in first)
    }

    @Test
    fun structuredQueryPrioritizesCurrentSubquestionAndStratifiesAllQuestionMarkers() {
        val wholeQuestion =
            buildString {
                repeat(18) { index ->
                    append("（${index + 1}）")
                    append(
                        when (index) {
                            0 -> "题首条件 "
                            8 -> "题中条件 "
                            17 -> "题尾条件 "
                            else -> "干扰前缀$index "
                        },
                    )
                    append("背景 ".repeat(120))
                }
            }
        val features =
            KnowledgeSearchNormalizer.queryFeatures(
                KnowledgeRecallQuery(
                    currentQuestion = wholeQuestion,
                    currentSubquestion = "当前只求配位数",
                    surroundingContext = "本页还讨论晶胞",
                ),
            )

        assertTrue("当前只求配位数" in features)
        assertTrue("题首条件" in features)
        assertTrue("题中条件" in features)
        assertTrue("题尾条件" in features)
    }

    @Test
    fun exactAliasWeightOutranksBoundaryNgrams() {
        val exact =
            node(
                id = "node.exact",
                stableCode = "math.exact",
                aliases = listOf("驻点"),
            )
        val boundaryOnly =
            node(
                id = "node.boundary",
                stableCode = "math.boundary",
                aliases = emptyList(),
            ).copy(
                canonicalName = "另一个知识",
                displayName = "另一个知识",
                boundaryMarkdown = "讨论驻点附近的多种边界情况",
            )
        val indexed = KnowledgeSearchIndexBuilder.build(listOf(boundaryOnly, exact))
        val exactWeight =
            indexed.single {
                it.knowledgeNodeId == exact.knowledgeNodeId && it.searchFeature == "驻点"
            }.rankWeight
        val boundaryWeight =
            indexed.single {
                it.knowledgeNodeId == boundaryOnly.knowledgeNodeId &&
                    it.searchFeature == "驻点"
            }.rankWeight

        assertTrue(exactWeight > boundaryWeight)
    }

    @Test
    fun searchIndexDoesNotDependOnNodeOrAliasIterationOrder() {
        val first =
            listOf(
                node(
                    id = "node.quadratic",
                    stableCode = "math.quadratic",
                    aliases = listOf("抛物线", "二次多项式"),
                ),
                node(
                    id = "node.function",
                    stableCode = "math.function",
                    aliases = listOf("映射"),
                ),
            )
        val second =
            listOf(
                node(
                    id = "node.function",
                    stableCode = "math.function",
                    aliases = listOf("映射"),
                ),
                node(
                    id = "node.quadratic",
                    stableCode = "math.quadratic",
                    aliases = listOf("二次多项式", "抛物线"),
                ),
            )

        assertEquals(
            KnowledgeSearchIndexBuilder.build(first),
            KnowledgeSearchIndexBuilder.build(second),
        )
    }

    @Test
    fun smallRelationBudgetsAreDistributedByDirectHitOrdinal() {
        val directIds = (0 until 6).map { index -> "direct-$index" }
        val candidates = fairRelationCandidates(directIds)
        val expectedOriginsByLimit =
            mapOf(
                1 to directIds.take(1),
                3 to directIds.take(3),
                6 to directIds,
                7 to directIds + directIds.first(),
            )

        expectedOriginsByLimit.forEach { (limit, expectedOrigins) ->
            val selected =
                selectFairRelationRows(
                    directKnowledgeNodeIds = directIds,
                    candidates = candidates,
                    relationLimit = limit,
                )

            assertEquals(
                "relation budget $limit",
                expectedOrigins,
                selected.map { relation -> relation.fromKnowledgeNodeId },
            )
        }
    }

    @Test
    fun relationFairnessFollowsRecallOrderWhenDirectHitsAreReordered() {
        val originalDirectIds = (0 until 6).map { index -> "direct-$index" }
        val reorderedDirectIds = originalDirectIds.reversed()
        val candidates = fairRelationCandidates(originalDirectIds)

        val selected =
            selectFairRelationRows(
                directKnowledgeNodeIds = reorderedDirectIds,
                candidates = candidates,
                relationLimit = 7,
            )

        assertEquals(
            reorderedDirectIds + reorderedDirectIds.first(),
            selected.map { relation -> relation.fromKnowledgeNodeId },
        )
    }

    @Test
    fun installerRejectsParentCyclesBeforeWriting() {
        val nodes =
            listOf(
                node(
                    id = "node.a",
                    stableCode = "math.a",
                    aliases = emptyList(),
                    parentId = "node.b",
                ),
                node(
                    id = "node.b",
                    stableCode = "math.b",
                    aliases = emptyList(),
                    parentId = "node.a",
                ),
            )
        val features = KnowledgeSearchIndexBuilder.build(nodes)
        val bundle =
            KnowledgePackInstallBundle(
                manifest = manifest(nodes.size, features.size),
                nodes = nodes,
                sources = emptyList(),
                nodeSourceBindings = emptyList(),
                relations = emptyList(),
                searchFeatures = features,
                materials = emptyList(),
                materialBindings = emptyList(),
            )

        assertThrows(IllegalArgumentException::class.java) {
            bundle.validateAndOrderNodes()
        }
    }

    private fun node(
        id: String,
        stableCode: String,
        aliases: List<String>,
        parentId: String? = null,
    ): KnowledgeNodeEntity =
        KnowledgeNodeEntity(
            knowledgeNodeId = id,
            stableCode = stableCode,
            subject = SubjectKind.MATH.name,
            displayName = stableCode,
            canonicalName = stableCode,
            nodeKind = KnowledgeNodeKind.CONCEPT.name,
            granularity = KnowledgeNodeGranularity.ATOMIC.name,
            aliasesText = encodeAliases(aliases),
            boundaryMarkdown = "High-school mathematics boundary.",
            verificationStatus = KnowledgeNodeVerificationStatus.CURATED.name,
            parentKnowledgeNodeId = parentId,
            taxonomyVersion = TAXONOMY_VERSION,
            reviewedAtEpochMillis = 1L,
        )

    private fun fairRelationCandidates(
        directKnowledgeNodeIds: List<String>,
    ): List<KnowledgeRelationForOriginRow> =
        directKnowledgeNodeIds.flatMap { originId ->
            (1..4).map { rank ->
                KnowledgeRelationForOriginRow(
                    relation =
                        KnowledgeNodeRelationEntity(
                            relationId = "relation:$originId:$rank",
                            subject = SubjectKind.MATH.name,
                            fromKnowledgeNodeId = originId,
                            toKnowledgeNodeId = "related:$originId:$rank",
                            relationType = "RELATED_TO",
                            taxonomyVersion = TAXONOMY_VERSION,
                            sourceId = "source:fairness",
                            sourceLocator = "contract fixture",
                            reviewedAtEpochMillis = 1L,
                        ),
                    originKnowledgeNodeId = originId,
                    originRank = rank,
                )
            }
        }

    private fun manifest(
        nodeCount: Int,
        featureCount: Int,
    ): KnowledgePackManifestEntity =
        KnowledgePackManifestEntity(
            manifestKey = ACTIVE_MANIFEST_KEY,
            packId = "test-pack",
            schemaVersion = HIGH_SCHOOL_KNOWLEDGE_DATABASE_VERSION,
            knowledgePackVersion = "test-content-v1",
            taxonomyVersion = TAXONOMY_VERSION,
            searchIndexVersion = "test-search-v1",
            contentFingerprint = "a".repeat(64),
            builtAtEpochMillis = 1L,
            nodeCount = nodeCount,
            sourceCount = 0,
            relationCount = 0,
            materialCount = 0,
            searchFeatureCount = featureCount,
        )

    private companion object {
        const val TAXONOMY_VERSION = "test-taxonomy-v1"
    }
}

private class CatalogWithoutNeighborhoodOverride : HighSchoolKnowledgeCatalog {
    var perNodeApiCallCount: Int = 0
        private set

    override suspend fun readManifest(): KnowledgePackManifest = unexpectedPerNodeCall()

    override suspend fun findNode(ref: KnowledgeNodeRef): KnowledgeCatalogNode? =
        unexpectedPerNodeCall()

    override suspend fun findNodes(
        refs: List<KnowledgeNodeRef>,
    ): KnowledgeCatalogNodeDisplayBatch = unexpectedPerNodeCall()

    override suspend fun verifyReference(
        ref: KnowledgeNodeRef,
    ): VerifiedKnowledgeReferenceProof? = unexpectedPerNodeCall()

    override suspend fun resolveNode(ref: KnowledgeNodeRef): VerifiedKnowledgeNodeHandle? =
        unexpectedPerNodeCall()

    override suspend fun recall(
        subject: SubjectKind,
        query: String,
        limit: Int,
    ): List<KnowledgeCatalogSearchHit> = unexpectedPerNodeCall()

    override suspend fun expandRelations(
        origin: KnowledgeNodeRef,
        direction: KnowledgeRelationDirection,
        relationTypes: Set<String>,
        limit: Int,
    ): List<KnowledgeCatalogRelation> = unexpectedPerNodeCall()

    override suspend fun readTeachingMaterials(
        node: VerifiedKnowledgeNodeHandle,
        limit: Int,
    ): List<KnowledgeCatalogTeachingMaterial> = unexpectedPerNodeCall()

    override fun close() = Unit

    private fun <T> unexpectedPerNodeCall(): T {
        perNodeApiCallCount += 1
        error("The fail-closed neighborhood default called a catalog API")
    }
}

private fun <T> runImmediateSuspend(block: suspend () -> T): Result<T> {
    var outcome: Result<T>? = null
    block.startCoroutine(
        object : Continuation<T> {
            override val context = EmptyCoroutineContext

            override fun resumeWith(result: Result<T>) {
                outcome = result
            }
        },
    )
    return checkNotNull(outcome) { "Contract coroutine unexpectedly suspended" }
}
