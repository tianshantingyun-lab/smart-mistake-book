package com.tingyun.smartmistakebook.core.knowledge.database

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
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
import com.tingyun.smartmistakebook.core.model.storage.KnowledgeReferenceProofAuthority
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in release gate at the real catalog budget. From PowerShell at the repository root, run
 * `./gradlew.bat :core:knowledge-database:connectedKnowledgeScaleBenchmark` with the
 * `-PrunKnowledgeScaleBenchmark=true` property.
 *
 * The small synthetic catalog in [ReviewedKnowledgeContextReaderInstrumentedTest] is the fast
 * contract. Ordinary connected tests skip this fixture; the explicit task above fails before
 * instrumentation unless its opt-in property is present and runs only this benchmark class.
 */
@LargeTest
@RunWith(AndroidJUnit4::class)
class KnowledgeRetrievalScaleInstrumentedTest {
    @Test
    fun fiftyThousandNodeCatalogKeepsPublicRetrievalSurfacesBounded() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(
            "Full knowledge scale benchmark is opt-in",
            InstrumentationRegistry.getArguments()
                .getString("runKnowledgeScaleBenchmark") == "true",
        )
        val context = instrumentation.targetContext
        clearProductionPackForTest(context)
        try {
            val bundle = fullScaleBundle()
            HighSchoolKnowledgePackBuilder.openNext(context).use { builder ->
                builder.replacePack(bundle)
            }
            KnowledgePackActivationManager.activateNext(
                context,
                KnowledgePackActivationAuthorization(
                    packId = bundle.manifest.packId,
                    knowledgePackVersion = bundle.manifest.knowledgePackVersion,
                    taxonomyVersion = bundle.manifest.taxonomyVersion,
                    generation = 1L,
                    expectedContentFingerprint = bundle.manifest.contentFingerprint,
                ),
            )
            val observedSql = mutableListOf<String>()
            val proofAuthority = KnowledgeReferenceProofAuthority.create()
            val catalog =
                HighSchoolKnowledgeCatalogFactory.openActivatedForTest(
                    context = context,
                    proofIssuer = proofAuthority.issuer,
                    queryObserver = observedSql::add,
                )
            try {
                val subjects = highSchoolSubjects()
                repeat(WARMUP_READS) { iteration ->
                    catalog.recall(
                        subject = subjects[iteration % subjects.size],
                        query = ZIPF_HEAD_FEATURE,
                        limit = 20,
                    )
                }
                val recallDurationsMillis =
                    (0 until MEASURED_READS).map { iteration ->
                        val subject = subjects[iteration % subjects.size]
                        val started = SystemClock.elapsedRealtimeNanos()
                        val hits =
                            catalog.recall(
                                subject = subject,
                                query = ZIPF_HEAD_FEATURE,
                                limit = 20,
                            )
                        val duration =
                            (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0
                        assertTrue(hits.isNotEmpty())
                        assertTrue(hits.all { hit -> hit.node.subject == subject })
                        duration
                    }.sorted()
                val recallP95 = recallDurationsMillis.p95()
                assertTrue(
                    "Benchmark did not execute the production Room recall query: $observedSql",
                    observedSql.any { sql ->
                        "FROM knowledge_search_feature AS feature" in sql &&
                            "INNER JOIN knowledge_node AS node" in sql &&
                            "node.taxonomy_version" in sql
                    },
                )
                assertTrue(
                    "Scale public recall P95 was ${recallP95}ms",
                    recallP95 < MAX_RECALL_P95_MILLIS,
                )

                repeat(WARMUP_NEIGHBORHOOD_READS) { iteration ->
                    catalog.readNeighborhood(
                        subject = subjects[iteration % subjects.size],
                        query = ZIPF_HEAD_FEATURE,
                    )
                }
                val neighborhoodDurationsMillis =
                    (0 until MEASURED_NEIGHBORHOOD_READS).map { iteration ->
                        val subject = subjects[iteration % subjects.size]
                        val started = SystemClock.elapsedRealtimeNanos()
                        val neighborhood =
                            catalog.readNeighborhood(
                                subject = subject,
                                query = ZIPF_HEAD_FEATURE,
                            )
                        val duration =
                            (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0
                        assertTrue(neighborhood.directHits.isNotEmpty())
                        assertTrue(neighborhood.directHits.all { it.node.subject == subject })
                        assertTrue(neighborhood.relatedNodes.all { it.subject == subject })
                        assertTrue(neighborhood.relations.all { it.subject == subject })
                        duration
                    }.sorted()
                val neighborhoodP95 = neighborhoodDurationsMillis.p95()
                assertTrue(
                    "Scale public neighborhood P95 was ${neighborhoodP95}ms",
                    neighborhoodP95 < MAX_NEIGHBORHOOD_P95_MILLIS,
                )

                val materialRequests =
                    subjects
                        .take(HighSchoolKnowledgeCatalog.MAX_TEACHING_MATERIAL_LIMIT)
                        .map { subject ->
                            val nodeIndex = subjects.indexOf(subject)
                            catalog.resolveNodes(subject, listOf(nodeId(nodeIndex)))
                        }
                repeat(WARMUP_MATERIAL_READS) {
                    materialRequests.forEach { handles ->
                        assertTrue(catalog.readTeachingMaterials(handles).isNotEmpty())
                    }
                }
                val materialDurationsMillis =
                    (0 until MEASURED_MATERIAL_READS).flatMap {
                        materialRequests.map { handles ->
                            val started = SystemClock.elapsedRealtimeNanos()
                            val materials = catalog.readTeachingMaterials(handles)
                            val duration =
                                (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0
                            assertTrue(materials.size == handles.size)
                            duration
                        }
                    }.sorted()
                val materialP95 = materialDurationsMillis.p95()
                assertTrue(
                    "Scale public teaching-material P95 was ${materialP95}ms",
                    materialP95 < MAX_MATERIAL_P95_MILLIS,
                )

                val displayStarted = SystemClock.elapsedRealtimeNanos()
                val displayDurationsMillis = mutableListOf<Double>()
                var displayedNodeCount = 0
                subjects.forEach { subject ->
                    var afterOrderToken: String? = null
                    var subjectNodeCount = 0
                    var pageCount = 0
                    do {
                        val pageStarted = SystemClock.elapsedRealtimeNanos()
                        val page =
                            catalog.readDisplayOrderPage(
                                subject = subject,
                                afterOrderToken = afterOrderToken,
                                limit = HighSchoolKnowledgeCatalog.MAX_BATCH_NODE_REQUESTS,
                            )
                        displayDurationsMillis +=
                            (SystemClock.elapsedRealtimeNanos() - pageStarted) / 1_000_000.0
                        assertTrue(page.entries.all { it.ref.subject == subject })
                        assertTrue(page.entries.all { it.orderToken > (afterOrderToken ?: "") })
                        subjectNodeCount += page.entries.size
                        pageCount += 1
                        assertTrue(pageCount <= MAX_DISPLAY_PAGES_PER_SUBJECT)
                        val next = page.nextAfterOrderToken
                        assertTrue(next == null || next != afterOrderToken)
                        afterOrderToken = next
                    } while (afterOrderToken != null)
                    assertTrue(subjectNodeCount == nodesPerSubject(subject))
                    displayedNodeCount += subjectNodeCount
                }
                val displayTotalMillis =
                    (SystemClock.elapsedRealtimeNanos() - displayStarted) / 1_000_000.0
                assertTrue(displayedNodeCount == NODE_COUNT)
                val displayP95 = displayDurationsMillis.sorted().p95()
                assertTrue(
                    "Scale display-order page P95 was ${displayP95}ms",
                    displayP95 < MAX_DISPLAY_PAGE_P95_MILLIS,
                )
                assertTrue(
                    "Scale full display-order scan was ${displayTotalMillis}ms",
                    displayTotalMillis < MAX_DISPLAY_FULL_SCAN_MILLIS,
                )
            } finally {
                catalog.close()
            }
        } finally {
            clearProductionPackForTest(context)
        }
    }

    private fun fullScaleBundle(): KnowledgePackInstallBundle {
        val subjects = highSchoolSubjects()
        val nodes =
            (0 until NODE_COUNT).map { index ->
                val subject = subjects[index % subjects.size]
                KnowledgeNodeEntity(
                    knowledgeNodeId = nodeId(index),
                    stableCode = uniqueEightCharacterCode(index),
                    subject = subject.name,
                    displayName = ZIPF_HEAD_FEATURE,
                    canonicalName = ZIPF_HEAD_FEATURE,
                    nodeKind = KnowledgeNodeKind.TOPIC.name,
                    granularity = KnowledgeNodeGranularity.TOPIC.name,
                    aliasesText = encodeAliases(emptyList()),
                    boundaryMarkdown = null,
                    verificationStatus = KnowledgeNodeVerificationStatus.CURATED.name,
                    parentKnowledgeNodeId = null,
                    taxonomyVersion = TAXONOMY_VERSION,
                    reviewedAtEpochMillis = REVIEWED_AT,
                )
            }
        val nodeIndicesBySubject =
            nodes.indices.groupBy { index -> nodes[index].subject }
        val relations =
            buildList(RELATION_COUNT) {
                nodeIndicesBySubject.forEach { (subject, indices) ->
                    indices.forEachIndexed { position, fromIndex ->
                        repeat(RELATIONS_PER_NODE) { offset ->
                            val toIndex = indices[(position + offset + 1) % indices.size]
                            add(
                                KnowledgeNodeRelationEntity(
                                    relationId = "scale.relation.$fromIndex.$offset",
                                    subject = subject,
                                    fromKnowledgeNodeId = nodeId(fromIndex),
                                    toKnowledgeNodeId = nodeId(toIndex),
                                    relationType = "RELATED_TO",
                                    taxonomyVersion = TAXONOMY_VERSION,
                                    sourceId = sourceId(SubjectKind.valueOf(subject)),
                                    sourceLocator = "scale/relation/$fromIndex/$offset",
                                    reviewedAtEpochMillis = REVIEWED_AT,
                                ),
                            )
                        }
                    }
                }
            }
        val features = KnowledgeSearchIndexBuilder.build(nodes)
        check(features.size == SEARCH_FEATURE_COUNT) {
            "Production index builder emitted ${features.size}, expected $SEARCH_FEATURE_COUNT"
        }
        check(
            features.count { feature -> feature.searchFeature == ZIPF_HEAD_FEATURE } ==
                NODE_COUNT,
        ) {
            "Full-scale index is missing its deterministic Zipf head"
        }
        val sources =
            subjects.map { subject ->
                KnowledgeSourceEntity(
                    sourceId = sourceId(subject),
                    subject = subject.name,
                    sourceType = KnowledgeSourceType.OFFICIAL_CURRICULUM_STANDARD.name,
                    title = "Scale source ${subject.name}",
                    publisher = "Synthetic benchmark",
                    edition = null,
                    sourceUri = null,
                    licenseStatus = KnowledgeSourceLicenseStatus.PUBLIC_OFFICIAL.name,
                    contentUsePolicy = KnowledgeSourceContentUsePolicy.REVIEWED_SYNTHESIS_ONLY.name,
                    contentFingerprint =
                        (subject.ordinal + 1).toString(16).padStart(64, '0'),
                    licenseExpression = null,
                    licenseUri = null,
                    attributionText = "Synthetic full-scale benchmark",
                    reviewedAtEpochMillis = REVIEWED_AT,
                )
            }
        val materials =
            subjects.map { subject ->
                KnowledgeTeachingMaterialEntity(
                    materialId = materialId(subject),
                    stableCode = "scale.material.${subject.name.lowercase()}",
                    subject = subject.name,
                    materialType = KnowledgeTeachingMaterialType.CONCEPT_EXPLANATION.name,
                    title = "Scale material ${subject.name}",
                    summaryMarkdown = "Reviewed scale summary for ${subject.name}.",
                    applicabilityMarkdown = "Applies to the linked scale knowledge point.",
                    contentMarkdown = "Reviewed scale method content for ${subject.name}.",
                    boundaryMarkdown = "Synthetic benchmark content only.",
                    derivationKind = KnowledgeMaterialDerivationKind.REVIEWED_SYNTHESIS.name,
                    sourceId = sourceId(subject),
                    sourceLocator = "scale/material/${subject.name.lowercase()}",
                    contentFingerprint =
                        (subject.ordinal + 32).toString(16).padStart(64, '0'),
                    reviewedAtEpochMillis = REVIEWED_AT,
                )
            }
        return KnowledgePackInstallBundle(
            manifest =
                KnowledgePackManifestEntity(
                    manifestKey = ACTIVE_MANIFEST_KEY,
                    packId = "synthetic-scale-pack",
                    schemaVersion = HIGH_SCHOOL_KNOWLEDGE_DATABASE_VERSION,
                    knowledgePackVersion = PACK_VERSION,
                    taxonomyVersion = TAXONOMY_VERSION,
                    searchIndexVersion = "synthetic-scale-search-v1",
                    contentFingerprint = "0".repeat(64),
                    builtAtEpochMillis = REVIEWED_AT,
                    nodeCount = nodes.size,
                    sourceCount = sources.size,
                    relationCount = relations.size,
                    materialCount = materials.size,
                    searchFeatureCount = features.size,
                ),
            nodes = nodes,
            sources = sources,
            nodeSourceBindings =
                nodes.map { node ->
                    KnowledgeNodeSourceBindingEntity(
                        knowledgeNodeId = node.knowledgeNodeId,
                        sourceId = sourceId(SubjectKind.valueOf(node.subject)),
                        sourceLocator = "scale/node/${node.knowledgeNodeId}",
                        derivationNote = "Synthetic scale fixture",
                        reviewedAtEpochMillis = REVIEWED_AT,
                    )
                },
            relations = relations,
            searchFeatures = features,
            materials = materials,
            materialBindings =
                subjects.mapIndexed { index, subject ->
                    KnowledgeTeachingMaterialNodeBindingEntity(
                        materialId = materialId(subject),
                        knowledgeNodeId = nodeId(index),
                        role = KnowledgeMaterialNodeRole.PRIMARY.name,
                    )
                },
        ).withRecomputedContentFingerprint()
    }

    private fun highSchoolSubjects(): List<SubjectKind> =
        enumValues<SubjectKind>().filter { subject -> subject != SubjectKind.GENERAL }

    private fun nodeId(index: Int): String = "scale.node.$index"

    private fun uniqueEightCharacterCode(index: Int): String {
        var remainder = index.toLong()
        val available = SCALE_CODE_ALPHABET.toMutableList()
        return buildString(8) {
            repeat(8) {
                val selected = (remainder % available.size).toInt()
                append(available.removeAt(selected))
                remainder /= available.size
            }
        }
    }

    private fun sourceId(subject: SubjectKind): String =
        "scale.source.${subject.name.lowercase()}"

    private fun materialId(subject: SubjectKind): String =
        "scale.material.${subject.name.lowercase()}"

    private fun nodesPerSubject(subject: SubjectKind): Int {
        val subjectIndex = highSchoolSubjects().indexOf(subject)
        require(subjectIndex >= 0)
        return if (subjectIndex < NODE_COUNT % highSchoolSubjects().size) {
            NODE_COUNT / highSchoolSubjects().size + 1
        } else {
            NODE_COUNT / highSchoolSubjects().size
        }
    }

    private fun List<Double>.p95(): Double =
        get((size * 95 / 100).coerceAtMost(lastIndex))

    private fun clearProductionPackForTest(context: android.content.Context) {
        HighSchoolKnowledgePackBuilder.discardNext(context)
        context.deleteDatabase(HIGH_SCHOOL_KNOWLEDGE_DATABASE_NAME)
        val databaseDirectory =
            requireNotNull(context.getDatabasePath(HIGH_SCHOOL_KNOWLEDGE_DATABASE_NAME).parentFile)
        listOf(
            "$HIGH_SCHOOL_KNOWLEDGE_DATABASE_NAME.activation",
            "$HIGH_SCHOOL_KNOWLEDGE_DATABASE_NAME.activation.pending",
            "$HIGH_SCHOOL_KNOWLEDGE_DATABASE_NAME.activation.rollback",
        ).forEach { name -> File(databaseDirectory, name).delete() }
    }

    private companion object {
        const val NODE_COUNT = 50_000
        const val SEARCH_FEATURE_COUNT = 1_000_000
        const val RELATIONS_PER_NODE = 5
        const val RELATION_COUNT = 250_000
        const val MEASURED_READS = 100
        const val WARMUP_READS = 10
        const val WARMUP_NEIGHBORHOOD_READS = 10
        const val MEASURED_NEIGHBORHOOD_READS = 40
        const val MEASURED_MATERIAL_READS = 25
        const val WARMUP_MATERIAL_READS = 3
        const val MAX_RECALL_P95_MILLIS = 50.0
        const val MAX_NEIGHBORHOOD_P95_MILLIS = 150.0
        const val MAX_MATERIAL_P95_MILLIS = 100.0
        const val MAX_DISPLAY_PAGE_P95_MILLIS = 500.0
        const val MAX_DISPLAY_FULL_SCAN_MILLIS = 30_000.0
        const val MAX_DISPLAY_PAGES_PER_SUBJECT = 128
        const val TAXONOMY_VERSION = "synthetic-scale-taxonomy-v1"
        const val PACK_VERSION = "synthetic-scale-content-v1"
        const val REVIEWED_AT = 1_700_000_000_000L
        const val ZIPF_HEAD_FEATURE = "x"
        const val SCALE_CODE_ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyz"
    }
}
