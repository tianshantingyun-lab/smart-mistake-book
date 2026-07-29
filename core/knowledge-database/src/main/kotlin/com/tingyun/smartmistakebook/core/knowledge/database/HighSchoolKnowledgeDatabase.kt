package com.tingyun.smartmistakebook.core.knowledge.database

import android.content.Context
import androidx.room3.Database
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import com.tingyun.smartmistakebook.core.model.KnowledgeNodeVerificationStatus
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.storage.KnowledgeNodeRef

internal const val HIGH_SCHOOL_KNOWLEDGE_DATABASE_VERSION = 1

@Database(
    entities = [
        KnowledgePackManifestEntity::class,
        KnowledgeNodeEntity::class,
        KnowledgeSourceEntity::class,
        KnowledgeNodeSourceBindingEntity::class,
        KnowledgeNodeRelationEntity::class,
        KnowledgeSearchFeatureEntity::class,
        KnowledgeTeachingMaterialEntity::class,
        KnowledgeTeachingMaterialNodeBindingEntity::class,
    ],
    version = HIGH_SCHOOL_KNOWLEDGE_DATABASE_VERSION,
    exportSchema = true,
)
internal abstract class HighSchoolKnowledgeRoomDatabase : RoomDatabase() {
    abstract fun catalogDao(): KnowledgeCatalogDao

    abstract fun installDao(): KnowledgeCatalogInstallDao
}

object HighSchoolKnowledgeCatalogFactory {
    /**
     * Opens the catalog from the one app-private production filename. The caller receives only the
     * read-only catalog surface; Room and pack installation remain inaccessible.
     */
    fun open(context: Context): HighSchoolKnowledgeCatalog {
        return RoomHighSchoolKnowledgeCatalog(
            openReadOnlyExistingDatabase(context, HIGH_SCHOOL_KNOWLEDGE_DATABASE_NAME),
        )
    }

    internal fun openExistingForTest(
        context: Context,
        databaseName: String,
    ): HighSchoolKnowledgeCatalog {
        require(databaseName.endsWith(TEST_DATABASE_SUFFIX)) {
            "Test knowledge database must use the '$TEST_DATABASE_SUFFIX' suffix"
        }
        return RoomHighSchoolKnowledgeCatalog(
            openReadOnlyExistingDatabase(context, databaseName),
        )
    }

    /**
     * Test-only physical boundary probe. It deliberately opens a separate connection and never
     * shares a database handle with a runtime catalog.
     */
    internal suspend fun attemptInstallThroughReadOnlyConnectionForTest(
        context: Context,
        databaseName: String,
        bundle: KnowledgePackInstallBundle,
    ) {
        require(databaseName.endsWith(TEST_DATABASE_SUFFIX)) {
            "Test knowledge database must use the '$TEST_DATABASE_SUFFIX' suffix"
        }
        val database = openReadOnlyExistingDatabase(context, databaseName)
        try {
            KnowledgePackInstaller(database.installDao()).replacePack(bundle)
        } finally {
            database.close()
        }
    }

    private fun openReadOnlyExistingDatabase(
        context: Context,
        databaseName: String,
    ): HighSchoolKnowledgeRoomDatabase {
        val applicationContext = context.applicationContext
        val databaseFile = applicationContext.getDatabasePath(databaseName)
        check(databaseFile.isFile && databaseFile.length() > 0L) {
            "High-school knowledge database '$databaseName' is not installed"
        }
        return Room.databaseBuilder(
            applicationContext,
            HighSchoolKnowledgeRoomDatabase::class.java,
            databaseName,
        ).addCallback(QUERY_ONLY_CALLBACK)
            .setDriver(AndroidSQLiteDriver())
            .build()
    }

    internal const val TEST_DATABASE_SUFFIX = ".knowledge-test.db"
}

internal object HighSchoolKnowledgePackBuilder {
    const val NEXT_DATABASE_NAME = "$HIGH_SCHOOL_KNOWLEDGE_DATABASE_NAME.next"

    fun openNext(context: Context): KnowledgePackBuildHandle =
        openWritable(context, NEXT_DATABASE_NAME)

    fun openForTest(
        context: Context,
        databaseName: String,
    ): KnowledgePackBuildHandle {
        require(databaseName.endsWith(HighSchoolKnowledgeCatalogFactory.TEST_DATABASE_SUFFIX)) {
            "Test knowledge database must use the " +
                "'${HighSchoolKnowledgeCatalogFactory.TEST_DATABASE_SUFFIX}' suffix"
        }
        return openWritable(context, databaseName)
    }

    private fun openWritable(
        context: Context,
        databaseName: String,
    ): KnowledgePackBuildHandle {
        check(databaseName != HIGH_SCHOOL_KNOWLEDGE_DATABASE_NAME) {
            "Pack building must never target the runtime knowledge database"
        }
        val database =
            Room.databaseBuilder(
                context.applicationContext,
                HighSchoolKnowledgeRoomDatabase::class.java,
                databaseName,
            ).setDriver(AndroidSQLiteDriver())
                .build()
        return KnowledgePackBuildHandle(database)
    }
}

internal class KnowledgePackBuildHandle(
    private val database: HighSchoolKnowledgeRoomDatabase,
) : AutoCloseable {
    private val installer = KnowledgePackInstaller(database.installDao())

    suspend fun replacePack(bundle: KnowledgePackInstallBundle) {
        installer.replacePack(bundle)
    }

    override fun close() {
        database.close()
    }
}

private class RoomHighSchoolKnowledgeCatalog(
    private val database: HighSchoolKnowledgeRoomDatabase,
) : HighSchoolKnowledgeCatalog {
    private val catalogDao = database.catalogDao()

    override suspend fun readManifest(): KnowledgePackManifest =
        readActiveManifest().toCatalogManifest()

    override suspend fun findNode(ref: KnowledgeNodeRef): KnowledgeCatalogNode? {
        val manifest = readActiveManifest()
        if (!ref.matches(manifest)) return null
        return catalogDao.findNode(
            subject = ref.subject.name,
            knowledgeNodeId = ref.knowledgeNodeId,
            taxonomyVersion = ref.taxonomyVersion,
        )?.toCatalogNode(manifest)
    }

    override suspend fun recall(
        subject: SubjectKind,
        query: String,
        limit: Int,
    ): List<KnowledgeCatalogSearchHit> {
        require(subject != SubjectKind.GENERAL) {
            "High-school knowledge recall requires a specific subject"
        }
        limit.requireCatalogLimit()
        val manifest = readActiveManifest()
        val features = KnowledgeSearchNormalizer.queryFeatures(query)
        return catalogDao.recall(subject.name, features, limit).map { row ->
            KnowledgeCatalogSearchHit(
                node = row.node.toCatalogNode(manifest),
                matchedFeatureCount = row.matchedFeatureCount.toInt(),
                bestRankWeight = row.bestRankWeight,
            )
        }
    }

    override suspend fun expandRelations(
        origin: KnowledgeNodeRef,
        direction: KnowledgeRelationDirection,
        relationTypes: Set<String>,
        limit: Int,
    ): List<KnowledgeCatalogRelation> {
        limit.requireCatalogLimit()
        require(relationTypes.size <= MAX_RELATION_TYPE_FILTERS) {
            "Too many relation types were requested"
        }
        relationTypes.forEach { relationType ->
            require(
                relationType.isNotBlank() &&
                    relationType.length <= 160 &&
                    relationType.none { it.isISOControl() },
            ) {
                "Knowledge-relation type is invalid"
            }
        }
        val manifest = readActiveManifest()
        if (!origin.matches(manifest)) return emptyList()

        val outgoing =
            if (direction != KnowledgeRelationDirection.INCOMING) {
                if (relationTypes.isEmpty()) {
                    catalogDao.readOutgoingRelations(
                        subject = origin.subject.name,
                        knowledgeNodeId = origin.knowledgeNodeId,
                        taxonomyVersion = origin.taxonomyVersion,
                        limit = limit,
                    )
                } else {
                    catalogDao.readOutgoingRelationsOfTypes(
                        subject = origin.subject.name,
                        knowledgeNodeId = origin.knowledgeNodeId,
                        taxonomyVersion = origin.taxonomyVersion,
                        relationTypes = relationTypes,
                        limit = limit,
                    )
                }
            } else {
                emptyList()
            }
        val incoming =
            if (direction != KnowledgeRelationDirection.OUTGOING) {
                if (relationTypes.isEmpty()) {
                    catalogDao.readIncomingRelations(
                        subject = origin.subject.name,
                        knowledgeNodeId = origin.knowledgeNodeId,
                        taxonomyVersion = origin.taxonomyVersion,
                        limit = limit,
                    )
                } else {
                    catalogDao.readIncomingRelationsOfTypes(
                        subject = origin.subject.name,
                        knowledgeNodeId = origin.knowledgeNodeId,
                        taxonomyVersion = origin.taxonomyVersion,
                        relationTypes = relationTypes,
                        limit = limit,
                    )
                }
            } else {
                emptyList()
            }

        return (outgoing + incoming)
            .distinctBy(KnowledgeNodeRelationEntity::relationId)
            .sortedWith(
                compareBy(
                    KnowledgeNodeRelationEntity::relationType,
                    KnowledgeNodeRelationEntity::fromKnowledgeNodeId,
                    KnowledgeNodeRelationEntity::toKnowledgeNodeId,
                    KnowledgeNodeRelationEntity::relationId,
                ),
            )
            .take(limit)
            .map { relation -> relation.toCatalogRelation(manifest) }
    }

    override fun close() {
        database.close()
    }

    private suspend fun readActiveManifest(): KnowledgePackManifestEntity =
        checkNotNull(catalogDao.readManifest()) {
            "The high-school knowledge catalog has no installed manifest"
        }

    private companion object {
        const val MAX_RELATION_TYPE_FILTERS = 16
    }
}

internal fun KnowledgePackManifestEntity.toCatalogManifest(): KnowledgePackManifest =
    KnowledgePackManifest(
        packId = packId,
        schemaVersion = schemaVersion,
        knowledgePackVersion = knowledgePackVersion,
        taxonomyVersion = taxonomyVersion,
        searchIndexVersion = searchIndexVersion,
        contentFingerprint = contentFingerprint,
        builtAtEpochMillis = builtAtEpochMillis,
        nodeCount = nodeCount,
        sourceCount = sourceCount,
        relationCount = relationCount,
        materialCount = materialCount,
        searchFeatureCount = searchFeatureCount,
    )

internal fun KnowledgeNodeEntity.toCatalogNode(
    manifest: KnowledgePackManifestEntity,
): KnowledgeCatalogNode {
    check(taxonomyVersion == manifest.taxonomyVersion) {
        "Corrupt high-school knowledge catalog: node taxonomy does not match manifest"
    }
    val nodeSubject = enumValueOrCorrupt<SubjectKind>(subject, "node subject")
    val ref =
        KnowledgeNodeRef(
            subject = nodeSubject,
            knowledgeNodeId = knowledgeNodeId,
            taxonomyVersion = taxonomyVersion,
            knowledgePackVersion = manifest.knowledgePackVersion,
        )
    return KnowledgeCatalogNode(
        ref = ref,
        stableCode = stableCode,
        subject = nodeSubject,
        displayName = displayName,
        canonicalName = canonicalName,
        kind = enumValueOrCorrupt(nodeKind, "node kind"),
        granularity = enumValueOrCorrupt(granularity, "node granularity"),
        aliases = decodeAliases(aliasesText),
        boundaryMarkdown = boundaryMarkdown,
        verificationStatus =
            enumValueOrCorrupt<KnowledgeNodeVerificationStatus>(
                verificationStatus,
                "node verification status",
            ),
        parentRef =
            parentKnowledgeNodeId?.let { parentId ->
                KnowledgeNodeRef(
                    subject = nodeSubject,
                    knowledgeNodeId = parentId,
                    taxonomyVersion = taxonomyVersion,
                    knowledgePackVersion = manifest.knowledgePackVersion,
                )
            },
    )
}

private fun KnowledgeNodeRelationEntity.toCatalogRelation(
    manifest: KnowledgePackManifestEntity,
): KnowledgeCatalogRelation {
    check(taxonomyVersion == manifest.taxonomyVersion) {
        "Corrupt high-school knowledge catalog: relation taxonomy does not match manifest"
    }
    val relationSubject = enumValueOrCorrupt<SubjectKind>(subject, "relation subject")
    return KnowledgeCatalogRelation(
        relationId = relationId,
        subject = relationSubject,
        from =
            KnowledgeNodeRef(
                subject = relationSubject,
                knowledgeNodeId = fromKnowledgeNodeId,
                taxonomyVersion = taxonomyVersion,
                knowledgePackVersion = manifest.knowledgePackVersion,
            ),
        to =
            KnowledgeNodeRef(
                subject = relationSubject,
                knowledgeNodeId = toKnowledgeNodeId,
                taxonomyVersion = taxonomyVersion,
                knowledgePackVersion = manifest.knowledgePackVersion,
            ),
        relationType = relationType,
        sourceId = sourceId,
        sourceLocator = sourceLocator,
        reviewedAtEpochMillis = reviewedAtEpochMillis,
    )
}

private fun KnowledgeNodeRef.matches(manifest: KnowledgePackManifestEntity): Boolean =
    taxonomyVersion == manifest.taxonomyVersion &&
        knowledgePackVersion == manifest.knowledgePackVersion

private fun Int.requireCatalogLimit() {
    require(this in 1..HighSchoolKnowledgeCatalog.MAX_RESULT_LIMIT) {
        "Knowledge catalog result limit must be between 1 and " +
            HighSchoolKnowledgeCatalog.MAX_RESULT_LIMIT
    }
}

private inline fun <reified T : Enum<T>> enumValueOrCorrupt(
    value: String,
    label: String,
): T =
    enumValues<T>().firstOrNull { it.name == value }
        ?: error("Corrupt high-school knowledge catalog: unknown $label '$value'")

private val QUERY_ONLY_CALLBACK =
    object : RoomDatabase.Callback() {
        override suspend fun onOpen(connection: SQLiteConnection) {
            connection.execSQL("PRAGMA query_only = ON")
        }
    }
