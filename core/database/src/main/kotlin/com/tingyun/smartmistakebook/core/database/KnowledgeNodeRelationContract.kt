package com.tingyun.smartmistakebook.core.database

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

object KnowledgeNodeRelationContract {
    fun validate(
        incoming: List<KnowledgeNodeRelationRecord>,
        nodes: List<KnowledgeNodeSeedRecord>,
        sources: List<KnowledgeSourceSeedRecord>,
        existing: List<KnowledgeNodeRelationRecord>,
    ) {
        if (incoming.isEmpty()) return
        requireValid(incoming.map(KnowledgeNodeRelationRecord::relationId).distinct().size == incoming.size) {
            "Knowledge-node relation ids must be unique"
        }
        val nodesById = nodes.associateBy(KnowledgeNodeSeedRecord::knowledgeNodeId)
        val sourcesById = sources.associateBy(KnowledgeSourceSeedRecord::sourceId)
        incoming.forEach { relation ->
            val prerequisite = nodesById[relation.prerequisiteKnowledgeNodeId]
                ?: invalid("Knowledge-node relation prerequisite must exist")
            val dependent = nodesById[relation.dependentKnowledgeNodeId]
                ?: invalid("Knowledge-node relation dependent must exist")
            val source = sourcesById[relation.sourceId]
                ?: invalid("Knowledge-node relation source must exist")
            requireValid(relation.relationId == expectedId(relation)) {
                "Knowledge-node relation id does not match its immutable payload"
            }
            requireValid(relation.relationType == StudyDbValue.KnowledgeRelationType.PREREQUISITE_OF) {
                "Unsupported knowledge-node relation type"
            }
            requireValid(prerequisite.knowledgeNodeId != dependent.knowledgeNodeId) {
                "A knowledge point cannot be its own prerequisite"
            }
            requireValid(
                prerequisite.subject == relation.subject &&
                    dependent.subject == relation.subject &&
                    source.subject == relation.subject,
            ) { "Knowledge-node relations cannot cross subjects" }
            requireValid(
                prerequisite.granularity == "ATOMIC" && dependent.granularity == "ATOMIC" &&
                    prerequisite.verificationStatus == "SOURCE_GROUNDED" &&
                    dependent.verificationStatus == "SOURCE_GROUNDED",
            ) { "Knowledge-node relations require reviewed fine-grained knowledge points" }
            requireValid(relation.reviewedAtEpochMillis >= source.importedAtEpochMillis) {
                "Knowledge-node relation review cannot predate its source import"
            }
            requireValid(
                relation.reviewedAtEpochMillis >= prerequisite.createdAtEpochMillis &&
                    relation.reviewedAtEpochMillis >= dependent.createdAtEpochMillis,
            ) { "Knowledge-node relation review cannot predate its knowledge points" }
            requireValid(relation.sourceLocator.isNotBlank() && relation.sourceLocator.length <= 512) {
                "Knowledge-node relation source locator is invalid"
            }
        }
        val all = (existing + incoming).distinctBy(KnowledgeNodeRelationRecord::relationId)
        requireValid(all.distinctBy { Triple(it.prerequisiteKnowledgeNodeId, it.dependentKnowledgeNodeId, it.relationType) }.size == all.size) {
            "Knowledge-node relation endpoints must be unique"
        }
        requireValid(!all.hasCycle()) { "Knowledge-node prerequisites cannot contain a cycle" }
    }

    fun expectedId(relation: KnowledgeNodeRelationRecord): String {
        val payload = listOf(
            relation.subject,
            relation.prerequisiteKnowledgeNodeId,
            relation.dependentKnowledgeNodeId,
            relation.relationType,
            relation.sourceId,
            relation.sourceLocator,
        ).joinToString("\u001F")
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(payload.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        return "knowledge-relation:${digest.take(40)}"
    }

    private fun List<KnowledgeNodeRelationRecord>.hasCycle(): Boolean {
        val nodeIds = flatMapTo(linkedSetOf()) {
            listOf(it.prerequisiteKnowledgeNodeId, it.dependentKnowledgeNodeId)
        }
        val outgoing = groupBy(KnowledgeNodeRelationRecord::prerequisiteKnowledgeNodeId)
        val indegree = nodeIds.associateWithTo(hashMapOf()) { 0 }
        forEach { relation ->
            indegree[relation.dependentKnowledgeNodeId] =
                indegree.getValue(relation.dependentKnowledgeNodeId) + 1
        }
        val ready = ArrayDeque(indegree.filterValues { it == 0 }.keys)
        var visited = 0
        while (ready.isNotEmpty()) {
            val id = ready.removeFirst()
            visited += 1
            outgoing[id].orEmpty().forEach { relation ->
                val next = indegree.getValue(relation.dependentKnowledgeNodeId) - 1
                indegree[relation.dependentKnowledgeNodeId] = next
                if (next == 0) ready.addLast(relation.dependentKnowledgeNodeId)
            }
        }
        return visited != nodeIds.size
    }

    private inline fun requireValid(condition: Boolean, message: () -> String) {
        if (!condition) throw DatabaseContractViolationException(message())
    }

    private fun invalid(message: String): Nothing = throw DatabaseContractViolationException(message)
}
