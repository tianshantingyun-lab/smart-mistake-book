package com.tingyun.smartmistakebook.core.database.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index

@Entity(
    tableName = "knowledge_search_feature",
    primaryKeys = ["subject", "search_feature", "knowledge_node_id"],
    foreignKeys = [
        ForeignKey(
            entity = KnowledgeNodeEntity::class,
            parentColumns = ["knowledge_node_id"],
            childColumns = ["knowledge_node_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["knowledge_node_id"]),
        Index(value = ["subject", "knowledge_node_id"]),
    ],
)
internal data class KnowledgeSearchFeatureEntity(
    val subject: String,
    @ColumnInfo(name = "search_feature")
    val searchFeature: String,
    @ColumnInfo(name = "knowledge_node_id")
    val knowledgeNodeId: String,
)
