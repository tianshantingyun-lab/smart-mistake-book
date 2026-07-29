package com.tingyun.smartmistakebook.core.database.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import com.tingyun.smartmistakebook.core.database.DatabaseContractViolationException
import com.tingyun.smartmistakebook.core.database.ImmutablePayloadConflictException
import com.tingyun.smartmistakebook.core.database.ResolveKnowledgeGroundingCommand
import com.tingyun.smartmistakebook.core.database.ValidatedKnowledgeGroundingResolution
import com.tingyun.smartmistakebook.core.database.entity.KnowledgeNodeEntity
import com.tingyun.smartmistakebook.core.database.entity.KnowledgeNodeRelationEntity
import com.tingyun.smartmistakebook.core.database.entity.KnowledgeNodeSourceBindingEntity
import com.tingyun.smartmistakebook.core.database.entity.KnowledgeGroundingRequestEntity
import com.tingyun.smartmistakebook.core.database.entity.KnowledgeGroundingResolutionEntity
import com.tingyun.smartmistakebook.core.database.entity.KnowledgeSourceEntity
import com.tingyun.smartmistakebook.core.database.entity.KnowledgeSearchFeatureEntity
import com.tingyun.smartmistakebook.core.database.entity.PracticeUnitKnowledgeBindingEntity
import com.tingyun.smartmistakebook.core.model.BindingAcceptanceSource
import com.tingyun.smartmistakebook.core.model.KnowledgeGroundingFingerprint
import com.tingyun.smartmistakebook.core.model.KnowledgeNodeGranularity
import com.tingyun.smartmistakebook.core.model.KnowledgeNodeVerificationStatus
import kotlinx.coroutines.flow.Flow

internal data class KnowledgeGroundingSummaryRow(
    val groundingKey: String,
    val subject: String,
    val expectedParentKnowledgeDisplayName: String,
    val query: String,
    val relatedQuestionCount: Int,
    val firstObservedAtEpochMillis: Long,
    val lastObservedAtEpochMillis: Long,
)

internal data class KnowledgeGroundingResolutionTargetRow(
    val knowledgeNodeId: String,
    val subject: String,
    val taxonomyVersion: String,
    val granularity: String,
    val verificationStatus: String,
    val reviewedSourceCount: Int,
    val earliestGroundedAtEpochMillis: Long,
    val createdAtEpochMillis: Long,
)

@Dao
internal interface KnowledgeGroundingDao {
    @Query(
        """
        SELECT * FROM knowledge_grounding_request
        WHERE status = 'PENDING'
        ORDER BY created_at_epoch_millis ASC, grounding_request_id ASC
        LIMIT :limit
        """,
    )
    fun observePending(limit: Int): Flow<List<KnowledgeGroundingRequestEntity>>

    @Query(
        """
        SELECT
            grounding_key AS groundingKey,
            subject,
            expected_parent_knowledge_display_name AS expectedParentKnowledgeDisplayName,
            query,
            COUNT(*) AS relatedQuestionCount,
            MIN(created_at_epoch_millis) AS firstObservedAtEpochMillis,
            MAX(created_at_epoch_millis) AS lastObservedAtEpochMillis
        FROM knowledge_grounding_request
        WHERE status = 'PENDING'
        GROUP BY grounding_key, subject, expected_parent_knowledge_display_name, query
        ORDER BY lastObservedAtEpochMillis DESC, groundingKey ASC
        LIMIT :limit
        """,
    )
    fun observePendingSummaries(limit: Int): Flow<List<KnowledgeGroundingSummaryRow>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(requests: List<KnowledgeGroundingRequestEntity>): List<Long>

    @Query(
        "SELECT * FROM knowledge_grounding_request WHERE grounding_request_id IN (:ids)",
    )
    suspend fun readByIds(ids: Set<String>): List<KnowledgeGroundingRequestEntity>

    @Query(
        """
        SELECT * FROM knowledge_grounding_request
        WHERE grounding_key = :groundingKey
        ORDER BY created_at_epoch_millis ASC, grounding_request_id ASC
        """,
    )
    suspend fun readByGroundingKey(groundingKey: String): List<KnowledgeGroundingRequestEntity>

    @Query(
        "SELECT * FROM knowledge_grounding_resolution WHERE grounding_key = :groundingKey",
    )
    suspend fun readResolution(groundingKey: String): KnowledgeGroundingResolutionEntity?

    @Query(
        "SELECT * FROM knowledge_grounding_resolution WHERE grounding_key IN (:groundingKeys)",
    )
    suspend fun readResolutions(
        groundingKeys: Set<String>,
    ): List<KnowledgeGroundingResolutionEntity>

    @Query(
        """
        SELECT
            node.knowledge_node_id AS knowledgeNodeId,
            node.subject AS subject,
            node.taxonomy_version AS taxonomyVersion,
            node.granularity AS granularity,
            node.verification_status AS verificationStatus,
            COUNT(DISTINCT provenance.source_id) AS reviewedSourceCount,
            MIN(MAX(
                provenance.reviewed_at_epoch_millis,
                source.imported_at_epoch_millis
            )) AS earliestGroundedAtEpochMillis,
            node.created_at_epoch_millis AS createdAtEpochMillis
        FROM knowledge_node AS node
        INNER JOIN knowledge_node_source_binding AS provenance
          ON provenance.knowledge_node_id = node.knowledge_node_id
         AND provenance.reviewed_at_epoch_millis IS NOT NULL
        INNER JOIN knowledge_source AS source
          ON source.source_id = provenance.source_id
        WHERE node.knowledge_node_id = :knowledgeNodeId
        GROUP BY
            node.knowledge_node_id,
            node.subject,
            node.taxonomy_version,
            node.granularity,
            node.verification_status,
            node.created_at_epoch_millis
        """,
    )
    suspend fun readResolutionTarget(
        knowledgeNodeId: String,
    ): KnowledgeGroundingResolutionTargetRow?

    @Query(
        """
        SELECT * FROM practice_unit_knowledge_binding
        WHERE practice_unit_id = :practiceUnitId
          AND knowledge_node_id = :knowledgeNodeId
          AND basis_revision_id = :basisRevisionId
          AND taxonomy_version = :taxonomyVersion
        LIMIT 1
        """,
    )
    suspend fun readKnowledgeBinding(
        practiceUnitId: String,
        knowledgeNodeId: String,
        basisRevisionId: String,
        taxonomyVersion: String,
    ): PracticeUnitKnowledgeBindingEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertKnowledgeBindings(bindings: List<PracticeUnitKnowledgeBindingEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertResolution(resolution: KnowledgeGroundingResolutionEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertReviewedSources(sources: List<KnowledgeSourceEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertReviewedNodes(nodes: List<KnowledgeNodeEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertReviewedSourceBindings(bindings: List<KnowledgeNodeSourceBindingEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertReviewedRelations(relations: List<KnowledgeNodeRelationEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertKnowledgeSearchFeatures(features: List<KnowledgeSearchFeatureEntity>)

    @Query("SELECT * FROM knowledge_source WHERE source_id IN (:ids)")
    suspend fun readReviewedSources(ids: Set<String>): List<KnowledgeSourceEntity>

    @Query("SELECT * FROM knowledge_node WHERE knowledge_node_id IN (:ids)")
    suspend fun readReviewedNodes(ids: Set<String>): List<KnowledgeNodeEntity>

    @Query(
        "SELECT * FROM knowledge_node_source_binding WHERE knowledge_node_id IN (:nodeIds)",
    )
    suspend fun readReviewedSourceBindings(
        nodeIds: Set<String>,
    ): List<KnowledgeNodeSourceBindingEntity>

    @Query("SELECT * FROM knowledge_node_relation WHERE relation_id IN (:ids)")
    suspend fun readReviewedRelations(ids: Set<String>): List<KnowledgeNodeRelationEntity>

    @Query(
        """
        UPDATE knowledge_grounding_request
        SET status = 'RESOLVED', updated_at_epoch_millis = :resolvedAtEpochMillis
        WHERE grounding_key = :groundingKey AND status = 'PENDING'
        """,
    )
    suspend fun markPendingResolved(
        groundingKey: String,
        resolvedAtEpochMillis: Long,
    ): Int

    @Transaction
    suspend fun recordAll(requests: List<KnowledgeGroundingRequestEntity>) {
        recordAllInCurrentTransaction(requests)
    }

    suspend fun recordAllInCurrentTransaction(
        requests: List<KnowledgeGroundingRequestEntity>,
    ) {
        if (requests.isEmpty()) return
        val results = insertAll(requests)
        val replayedIds = requests.indices
            .filterTo(mutableSetOf()) { results[it] == -1L }
            .mapTo(mutableSetOf()) { requests[it].groundingRequestId }
        if (replayedIds.isEmpty()) return
        val existingById = readByIds(replayedIds).associateBy { it.groundingRequestId }
        requests.filter { it.groundingRequestId in replayedIds }.forEach { request ->
            if (!existingById[request.groundingRequestId].sameOccurrenceAs(request)) {
                throw ImmutablePayloadConflictException(
                    entityType = "knowledgeGroundingRequest",
                    entityId = request.groundingRequestId,
                )
            }
        }
    }

    @Transaction
    suspend fun applyReviewedPack(
        sources: List<KnowledgeSourceEntity>,
        nodes: List<KnowledgeNodeEntity>,
        sourceBindings: List<KnowledgeNodeSourceBindingEntity>,
        relations: List<KnowledgeNodeRelationEntity>,
        searchFeatures: List<KnowledgeSearchFeatureEntity>,
        resolutions: List<ValidatedKnowledgeGroundingResolution>,
    ): List<KnowledgeGroundingResolutionEntity> {
        insertReviewedSources(sources)
        requireExactReplay(
            entityType = "knowledgeSource",
            expected = sources,
            actual = readReviewedSources(sources.mapTo(mutableSetOf()) { it.sourceId }),
            keyOf = KnowledgeSourceEntity::sourceId,
            entityIdOf = KnowledgeSourceEntity::sourceId,
        )

        val parentFirstNodes = nodes.parentFirst()
        insertReviewedNodes(parentFirstNodes)
        insertKnowledgeSearchFeatures(searchFeatures)
        requireExactReplay(
            entityType = "knowledgeNode",
            expected = nodes,
            actual = readReviewedNodes(nodes.mapTo(mutableSetOf()) { it.knowledgeNodeId }),
            keyOf = KnowledgeNodeEntity::knowledgeNodeId,
            entityIdOf = KnowledgeNodeEntity::knowledgeNodeId,
        )

        insertReviewedSourceBindings(sourceBindings)
        requireExactReplay(
            entityType = "knowledgeNodeSourceBinding",
            expected = sourceBindings,
            actual = readReviewedSourceBindings(
                sourceBindings.mapTo(mutableSetOf()) { it.knowledgeNodeId },
            ),
            keyOf = KnowledgeNodeSourceBindingEntity::identity,
            entityIdOf = { binding -> "${binding.knowledgeNodeId}|${binding.sourceId}" },
        )

        insertReviewedRelations(relations)
        requireExactReplay(
            entityType = "knowledgeNodeRelation",
            expected = relations,
            actual = readReviewedRelations(relations.mapTo(mutableSetOf()) { it.relationId }),
            keyOf = KnowledgeNodeRelationEntity::relationId,
            entityIdOf = KnowledgeNodeRelationEntity::relationId,
        )

        return resolutions.map { resolution ->
            resolve(resolution.command, resolution.resolutionId)
        }
    }

    @Transaction
    suspend fun resolve(
        command: ResolveKnowledgeGroundingCommand,
        resolutionId: String,
    ): KnowledgeGroundingResolutionEntity {
        readResolution(command.groundingKey)?.let { existing ->
            if (!existing.matches(command, resolutionId)) {
                throw ImmutablePayloadConflictException(
                    entityType = "knowledgeGroundingResolution",
                    entityId = command.groundingKey,
                )
            }
            return existing
        }

        val occurrences = readByGroundingKey(command.groundingKey)
        if (occurrences.isEmpty()) {
            throw DatabaseContractViolationException(
                "Knowledge-grounding resolution has no queued occurrences",
            )
        }
        if (occurrences.any { occurrence -> occurrence.status != "PENDING" }) {
            throw DatabaseContractViolationException(
                "Knowledge-grounding resolution requires an entirely pending occurrence set",
            )
        }
        if (occurrences.any { occurrence -> occurrence.subject != command.subject }) {
            throw DatabaseContractViolationException(
                "Knowledge-grounding resolution cannot cross subjects",
            )
        }
        if (occurrences.any { occurrence -> occurrence.createdAtEpochMillis > command.resolvedAtEpochMillis }) {
            throw DatabaseContractViolationException(
                "Knowledge-grounding resolution cannot predate an occurrence",
            )
        }

        val target = readResolutionTarget(command.knowledgeNodeId)
            ?: throw DatabaseContractViolationException(
                "Knowledge-grounding resolution target is missing reviewed source evidence",
            )
        if (
            target.subject != command.subject ||
            target.granularity != KnowledgeNodeGranularity.ATOMIC.name ||
            target.verificationStatus != KnowledgeNodeVerificationStatus.SOURCE_GROUNDED.name ||
            target.reviewedSourceCount <= 0 ||
            target.earliestGroundedAtEpochMillis > command.resolvedAtEpochMillis ||
            target.createdAtEpochMillis > command.resolvedAtEpochMillis
        ) {
            throw DatabaseContractViolationException(
                "Knowledge-grounding resolution target is not a reviewed same-subject atomic node",
            )
        }

        val bindings = occurrences
            .map { occurrence ->
                PracticeUnitKnowledgeBindingEntity(
                    bindingId = KnowledgeGroundingFingerprint.resolvedBindingId(
                        practiceUnitId = occurrence.practiceUnitId,
                        knowledgeNodeId = target.knowledgeNodeId,
                        problemRevisionId = occurrence.problemRevisionId,
                        taxonomyVersion = target.taxonomyVersion,
                    ),
                    practiceUnitId = occurrence.practiceUnitId,
                    knowledgeNodeId = target.knowledgeNodeId,
                    basisRevisionId = occurrence.problemRevisionId,
                    strength = 1.0,
                    sourceType = BindingAcceptanceSource.CURATED_REFERENCE.name,
                    taxonomyVersion = target.taxonomyVersion,
                    acceptedAtEpochMillis = command.resolvedAtEpochMillis,
                )
            }
            .distinctBy(PracticeUnitKnowledgeBindingEntity::bindingId)
        val missingBindings = bindings.filter { binding ->
            readKnowledgeBinding(
                practiceUnitId = binding.practiceUnitId,
                knowledgeNodeId = binding.knowledgeNodeId,
                basisRevisionId = binding.basisRevisionId,
                taxonomyVersion = binding.taxonomyVersion,
            ) == null
        }
        if (missingBindings.isNotEmpty()) insertKnowledgeBindings(missingBindings)

        val resolution = KnowledgeGroundingResolutionEntity(
            groundingKey = command.groundingKey,
            resolutionId = resolutionId,
            subject = command.subject,
            knowledgeNodeId = target.knowledgeNodeId,
            taxonomyVersion = target.taxonomyVersion,
            resolvedOccurrenceCount = occurrences.size,
            linkedPracticeUnitCount = bindings.size,
            resolvedAtEpochMillis = command.resolvedAtEpochMillis,
        )
        insertResolution(resolution)
        val updated = markPendingResolved(
            groundingKey = command.groundingKey,
            resolvedAtEpochMillis = command.resolvedAtEpochMillis,
        )
        if (updated != occurrences.size) {
            throw DatabaseContractViolationException(
                "Knowledge-grounding resolution did not close its exact occurrence set",
            )
        }
        return resolution
    }
}

private fun List<KnowledgeNodeEntity>.parentFirst(): List<KnowledgeNodeEntity> {
    val remaining = associateBy(KnowledgeNodeEntity::knowledgeNodeId).toMutableMap()
    val ordered = ArrayList<KnowledgeNodeEntity>(size)
    while (remaining.isNotEmpty()) {
        val ready = remaining.values
            .filter { node -> node.parentKnowledgeNodeId !in remaining }
            .sortedBy(KnowledgeNodeEntity::knowledgeNodeId)
        if (ready.isEmpty()) {
            throw DatabaseContractViolationException("Knowledge nodes cannot contain a parent cycle")
        }
        ordered += ready
        ready.forEach { node -> remaining.remove(node.knowledgeNodeId) }
    }
    return ordered
}

private fun KnowledgeNodeSourceBindingEntity.identity(): String =
    "$knowledgeNodeId\u001F$sourceId\u001F$sourceLocator"

private inline fun <T> requireExactReplay(
    entityType: String,
    expected: List<T>,
    actual: List<T>,
    keyOf: (T) -> String,
    entityIdOf: (T) -> String,
) {
    val actualByKey = actual.associateBy(keyOf)
    expected.forEach { record ->
        val key = keyOf(record)
        if (actualByKey[key] != record) {
            throw ImmutablePayloadConflictException(
                entityType = entityType,
                entityId = entityIdOf(record),
            )
        }
    }
}

private fun KnowledgeGroundingRequestEntity?.sameOccurrenceAs(
    other: KnowledgeGroundingRequestEntity,
): Boolean = this != null && copy(
    status = other.status,
    updatedAtEpochMillis = other.updatedAtEpochMillis,
) == other

private fun KnowledgeGroundingResolutionEntity.matches(
    command: ResolveKnowledgeGroundingCommand,
    expectedResolutionId: String,
): Boolean = resolutionId == expectedResolutionId &&
    groundingKey == command.groundingKey &&
    subject == command.subject &&
    knowledgeNodeId == command.knowledgeNodeId &&
    resolvedAtEpochMillis == command.resolvedAtEpochMillis
