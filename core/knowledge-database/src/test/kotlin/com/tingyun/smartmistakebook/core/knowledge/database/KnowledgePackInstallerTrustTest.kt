package com.tingyun.smartmistakebook.core.knowledge.database

import com.tingyun.smartmistakebook.core.model.KnowledgeNodeGranularity
import com.tingyun.smartmistakebook.core.model.KnowledgeNodeKind
import com.tingyun.smartmistakebook.core.model.KnowledgeNodeVerificationStatus
import com.tingyun.smartmistakebook.core.model.KnowledgeSourceContentUsePolicy
import com.tingyun.smartmistakebook.core.model.KnowledgeSourceLicenseStatus
import com.tingyun.smartmistakebook.core.model.KnowledgeSourceType
import com.tingyun.smartmistakebook.core.model.SubjectKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class KnowledgePackInstallerTrustTest {
    @Test
    fun completeFingerprintIsIndependentOfInputIterationOrder() {
        val first = trustedBundle()
        val second =
            rebuild(
                first,
                nodes = first.nodes.reversed(),
                sources = first.sources.reversed(),
                nodeSourceBindings = first.nodeSourceBindings.reversed(),
            )

        assertEquals(first.manifest.contentFingerprint, second.manifest.contentFingerprint)
        assertEquals(
            first.validatedSnapshot().searchFeatures,
            second.validatedSnapshot().searchFeatures,
        )
    }

    @Test
    fun bundleSnapshotsMutableInputsBeforeValidation() {
        val trusted = trustedBundle()
        val mutableNodes = trusted.nodes.toMutableList()
        val snapshot =
            KnowledgePackInstallBundle(
                manifest = trusted.manifest,
                nodes = mutableNodes,
                sources = trusted.sources,
                nodeSourceBindings = trusted.nodeSourceBindings,
                relations = trusted.relations,
                searchFeatures = trusted.searchFeatures,
                materials = trusted.materials,
                materialBindings = trusted.materialBindings,
            )

        mutableNodes.clear()

        assertEquals(trusted.nodes.size, snapshot.validatedSnapshot().nodes.size)
    }

    @Test
    fun oversizedCollectionsAreRejectedBeforeTheyAreCopied() {
        val trusted = trustedBundle()
        val oversizedNodes =
            object : AbstractList<KnowledgeNodeEntity>() {
                override val size: Int = KnowledgePackBudgets.MAX_NODES + 1

                override fun get(index: Int): KnowledgeNodeEntity =
                    error("An over-budget collection must not be iterated")
            }

        assertThrows(IllegalArgumentException::class.java) {
            KnowledgePackInstallBundle(
                manifest = trusted.manifest,
                nodes = oversizedNodes,
                sources = trusted.sources,
                nodeSourceBindings = trusted.nodeSourceBindings,
                relations = trusted.relations,
                searchFeatures = trusted.searchFeatures,
                materials = trusted.materials,
                materialBindings = trusted.materialBindings,
            )
        }
    }

    @Test
    fun oversizedNodeBoundaryAndAliasesAreRejectedBeforeDeepValidation() {
        val trusted = trustedBundle()

        fun bundleWith(firstNode: KnowledgeNodeEntity): KnowledgePackInstallBundle =
            KnowledgePackInstallBundle(
                manifest = trusted.manifest,
                nodes = listOf(firstNode) + trusted.nodes.drop(1),
                sources = trusted.sources,
                nodeSourceBindings = trusted.nodeSourceBindings,
                relations = trusted.relations,
                searchFeatures = trusted.searchFeatures,
                materials = trusted.materials,
                materialBindings = trusted.materialBindings,
            )

        val oversizedBoundary =
            trusted.nodes.first().copy(
                boundaryMarkdown =
                    "b".repeat(KnowledgePackBudgets.MAX_NODE_BOUNDARY_CHARS + 1),
            )
        assertTrue(
            assertThrows(IllegalArgumentException::class.java) {
                bundleWith(oversizedBoundary).validatedSnapshot()
            }.message.orEmpty().contains("boundary"),
        )

        val tooManyAliases =
            trusted.nodes.first().copy(
                aliasesText =
                    List(KnowledgePackBudgets.MAX_NODE_ALIASES + 1) { index -> "alias-$index" }
                        .joinToString(ALIAS_SEPARATOR),
            )
        assertTrue(
            assertThrows(IllegalArgumentException::class.java) {
                bundleWith(tooManyAliases).validatedSnapshot()
            }.message.orEmpty().contains("count budget"),
        )

        val oversizedAlias =
            trusted.nodes.first().copy(
                aliasesText = "a".repeat(KnowledgePackBudgets.MAX_NODE_ALIAS_CHARS + 1),
            )
        assertTrue(
            assertThrows(IllegalArgumentException::class.java) {
                bundleWith(oversizedAlias).validatedSnapshot()
            }.message.orEmpty().contains("character budget"),
        )
    }

    @Test
    fun installerRejectsFingerprintOrLocallyRebuiltSearchMismatch() {
        val trusted = trustedBundle()
        val fingerprintMismatch =
            rawBundle(
                trusted,
                manifest = trusted.manifest.copy(contentFingerprint = "f".repeat(64)),
            )
        assertThrows(IllegalArgumentException::class.java) {
            fingerprintMismatch.validatedSnapshot()
        }

        val firstFeature = trusted.searchFeatures.first()
        val tamperedFeatures =
            listOf(firstFeature.copy(rankWeight = firstFeature.rankWeight - 1)) +
                trusted.searchFeatures.drop(1)
        val searchMismatch =
            rebuild(
                trusted,
                searchFeatures = tamperedFeatures,
            )
        assertThrows(IllegalArgumentException::class.java) {
            searchMismatch.validatedSnapshot()
        }
    }

    @Test
    fun formalPackRejectsModelCandidatesMissingSourcesAndPartialSubjectCoverage() {
        val trusted = trustedBundle()
        val candidateNode =
            trusted.nodes.first().copy(
                verificationStatus = KnowledgeNodeVerificationStatus.MODEL_CANDIDATE.name,
            )
        val modelCandidate =
            rebuild(
                trusted,
                nodes = listOf(candidateNode) + trusted.nodes.drop(1),
            )
        assertThrows(IllegalArgumentException::class.java) {
            modelCandidate.validatedSnapshot()
        }

        val missingSource =
            rebuild(
                trusted,
                nodeSourceBindings = trusted.nodeSourceBindings.drop(1),
            )
        assertThrows(IllegalArgumentException::class.java) {
            missingSource.validatedSnapshot()
        }

        val omittedSubject = SubjectKind.GEOGRAPHY.name
        val partial =
            rebuild(
                trusted,
                nodes = trusted.nodes.filterNot { it.subject == omittedSubject },
                sources = trusted.sources.filterNot { it.subject == omittedSubject },
                nodeSourceBindings =
                    trusted.nodeSourceBindings.filterNot {
                        it.knowledgeNodeId.endsWith(".geography")
                    },
            )
        assertThrows(IllegalArgumentException::class.java) {
            partial.validatedSnapshot()
        }
    }

    @Test
    fun prerequisiteRelationsMustRemainAcyclic() {
        val trusted = trustedBundle()
        val mathNode = trusted.nodes.first { it.subject == SubjectKind.MATH.name }
        val physicsNode = trusted.nodes.first { it.subject == SubjectKind.PHYSICS.name }
        val secondMathNode =
            physicsNode.copy(
                knowledgeNodeId = "node.test.math.second",
                stableCode = "test.math.second",
                subject = SubjectKind.MATH.name,
            )
        val nodes = trusted.nodes + secondMathNode
        val bindings =
            trusted.nodeSourceBindings +
                KnowledgeNodeSourceBindingEntity(
                    knowledgeNodeId = secondMathNode.knowledgeNodeId,
                    sourceId = sourceId(SubjectKind.MATH),
                    sourceLocator = "curriculum/test.math.second",
                    derivationNote = "Reviewed test binding.",
                    reviewedAtEpochMillis = REVIEWED_AT,
                )
        val relations =
            listOf(
                prerequisite(
                    id = "relation.test.forward",
                    from = mathNode.knowledgeNodeId,
                    to = secondMathNode.knowledgeNodeId,
                ),
                prerequisite(
                    id = "relation.test.reverse",
                    from = secondMathNode.knowledgeNodeId,
                    to = mathNode.knowledgeNodeId,
                ),
            )
        val cycle =
            rebuild(
                trusted,
                nodes = nodes,
                nodeSourceBindings = bindings,
                relations = relations,
            )

        val failure =
            assertThrows(IllegalArgumentException::class.java) {
                cycle.validatedSnapshot()
            }
        assertTrue(failure.message.orEmpty().contains("cycle"))
    }

    private fun trustedBundle(): KnowledgePackInstallBundle {
        val subjects = enumValues<SubjectKind>().filter { it != SubjectKind.GENERAL }
        val nodes =
            subjects.map { subject ->
                KnowledgeNodeEntity(
                    knowledgeNodeId = "node.test.${subject.name.lowercase()}",
                    stableCode = "test.${subject.name.lowercase()}",
                    subject = subject.name,
                    displayName = "${subject.name} test node",
                    canonicalName = "${subject.name} test node",
                    nodeKind = KnowledgeNodeKind.TOPIC.name,
                    granularity = KnowledgeNodeGranularity.TOPIC.name,
                    aliasesText = encodeAliases(emptyList()),
                    boundaryMarkdown = "Reviewed high-school test boundary.",
                    verificationStatus = KnowledgeNodeVerificationStatus.CURATED.name,
                    parentKnowledgeNodeId = null,
                    taxonomyVersion = TAXONOMY_VERSION,
                    reviewedAtEpochMillis = REVIEWED_AT,
                )
            }
        val sources =
            subjects.map { subject ->
                KnowledgeSourceEntity(
                    sourceId = sourceId(subject),
                    subject = subject.name,
                    sourceType = KnowledgeSourceType.OFFICIAL_CURRICULUM_STANDARD.name,
                    title = "${subject.name} curriculum",
                    publisher = "Test publisher",
                    edition = "Test edition",
                    sourceUri = null,
                    licenseStatus = KnowledgeSourceLicenseStatus.PUBLIC_OFFICIAL.name,
                    contentUsePolicy =
                        KnowledgeSourceContentUsePolicy.REVIEWED_SYNTHESIS_ONLY.name,
                    contentFingerprint =
                        (subject.ordinal + 1).toString(16).padStart(64, '0'),
                    licenseExpression = null,
                    licenseUri = null,
                    attributionText = "Reviewed test source",
                    reviewedAtEpochMillis = REVIEWED_AT,
                )
            }
        val bindings =
            nodes.map { node ->
                KnowledgeNodeSourceBindingEntity(
                    knowledgeNodeId = node.knowledgeNodeId,
                    sourceId = sourceId(SubjectKind.valueOf(node.subject)),
                    sourceLocator = "curriculum/${node.stableCode}",
                    derivationNote = "Reviewed test binding.",
                    reviewedAtEpochMillis = REVIEWED_AT,
                )
            }
        val searchFeatures = KnowledgeSearchIndexBuilder.build(nodes)
        return KnowledgePackInstallBundle(
            manifest =
                KnowledgePackManifestEntity(
                    manifestKey = ACTIVE_MANIFEST_KEY,
                    packId = PACK_ID,
                    schemaVersion = HIGH_SCHOOL_KNOWLEDGE_DATABASE_VERSION,
                    knowledgePackVersion = PACK_VERSION,
                    taxonomyVersion = TAXONOMY_VERSION,
                    searchIndexVersion = SEARCH_VERSION,
                    contentFingerprint = "0".repeat(64),
                    builtAtEpochMillis = REVIEWED_AT,
                    nodeCount = nodes.size,
                    sourceCount = sources.size,
                    relationCount = 0,
                    materialCount = 0,
                    searchFeatureCount = searchFeatures.size,
                ),
            nodes = nodes,
            sources = sources,
            nodeSourceBindings = bindings,
            relations = emptyList(),
            searchFeatures = searchFeatures,
            materials = emptyList(),
            materialBindings = emptyList(),
        ).withRecomputedContentFingerprint()
    }

    private fun rebuild(
        original: KnowledgePackInstallBundle,
        nodes: List<KnowledgeNodeEntity> = original.nodes,
        sources: List<KnowledgeSourceEntity> = original.sources,
        nodeSourceBindings: List<KnowledgeNodeSourceBindingEntity> =
            original.nodeSourceBindings,
        relations: List<KnowledgeNodeRelationEntity> = original.relations,
        searchFeatures: List<KnowledgeSearchFeatureEntity> =
            KnowledgeSearchIndexBuilder.build(nodes),
    ): KnowledgePackInstallBundle {
        val manifest =
            original.manifest.copy(
                contentFingerprint = "0".repeat(64),
                nodeCount = nodes.size,
                sourceCount = sources.size,
                relationCount = relations.size,
                searchFeatureCount = searchFeatures.size,
            )
        return KnowledgePackInstallBundle(
            manifest = manifest,
            nodes = nodes,
            sources = sources,
            nodeSourceBindings = nodeSourceBindings,
            relations = relations,
            searchFeatures = searchFeatures,
            materials = original.materials,
            materialBindings = original.materialBindings,
        ).withRecomputedContentFingerprint()
    }

    private fun rawBundle(
        original: KnowledgePackInstallBundle,
        manifest: KnowledgePackManifestEntity,
    ): KnowledgePackInstallBundle =
        KnowledgePackInstallBundle(
            manifest = manifest,
            nodes = original.nodes,
            sources = original.sources,
            nodeSourceBindings = original.nodeSourceBindings,
            relations = original.relations,
            searchFeatures = original.searchFeatures,
            materials = original.materials,
            materialBindings = original.materialBindings,
        )

    private fun prerequisite(
        id: String,
        from: String,
        to: String,
    ): KnowledgeNodeRelationEntity =
        KnowledgeNodeRelationEntity(
            relationId = id,
            subject = SubjectKind.MATH.name,
            fromKnowledgeNodeId = from,
            toKnowledgeNodeId = to,
            relationType = "PREREQUISITE_OF",
            taxonomyVersion = TAXONOMY_VERSION,
            sourceId = sourceId(SubjectKind.MATH),
            sourceLocator = "curriculum/prerequisite",
            reviewedAtEpochMillis = REVIEWED_AT,
        )

    private fun sourceId(subject: SubjectKind): String =
        "source.test.${subject.name.lowercase()}"

    private companion object {
        const val PACK_ID = "test-pack"
        const val PACK_VERSION = "test-pack-v1"
        const val TAXONOMY_VERSION = "test-taxonomy-v1"
        const val SEARCH_VERSION = "test-search-v1"
        const val REVIEWED_AT = 1_700_000_000_000L
    }
}
