package com.tingyun.smartmistakebook.core.knowledge.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room3.Database
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import com.tingyun.smartmistakebook.core.model.KnowledgeMaterialDerivationKind
import com.tingyun.smartmistakebook.core.model.KnowledgeMaterialNodeRole
import com.tingyun.smartmistakebook.core.model.KnowledgeNodeVerificationStatus
import com.tingyun.smartmistakebook.core.model.KnowledgeTeachingMaterialType
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.storage.KnowledgeReferenceProofAuthority
import com.tingyun.smartmistakebook.core.model.storage.KnowledgeReferenceProofIssuer
import com.tingyun.smartmistakebook.core.model.storage.KnowledgeNodeRef
import com.tingyun.smartmistakebook.core.model.storage.VerifiedKnowledgeReferenceProof
import kotlin.jvm.JvmSynthetic
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal const val HIGH_SCHOOL_KNOWLEDGE_DATABASE_VERSION = 1

/**
 * Leading high-confidence recall slice for two-stage knowledge recall.
 * `queryFeatures` orders features EXACT/TOKEN first and N-gram last, so the
 * first slice is the most specific subset; scanning it first avoids touching
 * the full N-gram feature set on every query.
 */
internal const val PRIMARY_RECALL_FEATURE_SLICE = 12

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
    private var trustedRoomLockCompanion: TrustedRoomLockCompanion? = null

    abstract fun catalogDao(): KnowledgeCatalogDao

    abstract fun installDao(): KnowledgeCatalogInstallDao

    abstract fun verificationDao(): KnowledgeCatalogVerificationDao

    internal fun attachTrustedRoomLockCompanion(companion: TrustedRoomLockCompanion) {
        synchronized(this) {
            check(trustedRoomLockCompanion == null) {
                "Room lock companion is already attached"
            }
            trustedRoomLockCompanion = companion
        }
    }

    override fun close() {
        val companion = synchronized(this) {
            trustedRoomLockCompanion.also { trustedRoomLockCompanion = null }
        }
        var failure: Throwable? = null
        fun capture(block: () -> Unit) {
            try {
                block()
            } catch (caught: Throwable) {
                val existing = failure
                if (existing == null) failure = caught else existing.addSuppressed(caught)
            }
        }
        companion?.let { capture(it::requireAtPath) }
        capture { super.close() }
        companion?.let { trusted ->
            capture(trusted::requireAtPath)
            capture(trusted::close)
        }
        failure?.let { throw it }
    }
}

object HighSchoolKnowledgeCatalogFactory {
    /**
     * Opens the catalog from the one app-private production filename. The caller receives only the
     * read-only catalog surface; Room and pack installation remain inaccessible.
     */
    fun open(context: Context): HighSchoolKnowledgeCatalog {
        val isolatedAuthority = KnowledgeReferenceProofAuthority.create()
        return open(context, isolatedAuthority.issuer)
    }

    /**
     * Opens a catalog bound to the process-local issuer retained by the authority runtime.
     *
     * The issuer never becomes part of [HighSchoolKnowledgeCatalog]'s public read surface.
     */
    @JvmSynthetic
    fun open(
        context: Context,
        proofIssuer: KnowledgeReferenceProofIssuer,
    ): HighSchoolKnowledgeCatalog = openActivated(context, proofIssuer, queryObserver = null)

    @JvmSynthetic
    internal fun openActivatedForTest(
        context: Context,
        proofIssuer: KnowledgeReferenceProofIssuer,
        queryObserver: (String) -> Unit,
    ): HighSchoolKnowledgeCatalog = openActivated(context, proofIssuer, queryObserver)

    private fun openActivated(
        context: Context,
        proofIssuer: KnowledgeReferenceProofIssuer,
        queryObserver: ((String) -> Unit)?,
    ): HighSchoolKnowledgeCatalog =
        KnowledgePackActivationManager.openRuntimeSnapshot(
            context.applicationContext,
        ) { runtimeTrust, runtimeLease ->
            val database =
                openReadOnlyExistingDatabase(
                    context,
                    HIGH_SCHOOL_KNOWLEDGE_DATABASE_NAME,
                    queryObserver,
                )
            try {
                RoomHighSchoolKnowledgeCatalog(
                    database = database,
                    runtimeTrust = runtimeTrust,
                    runtimeLease = runtimeLease,
                    proofIssuer = proofIssuer,
                )
            } catch (failure: Throwable) {
                try {
                    database.close()
                    KnowledgePackActivationManager.abortRuntimeSnapshotOpen(runtimeLease)
                } catch (closeFailure: Throwable) {
                    failure.addSuppressed(closeFailure)
                }
                throw failure
            }
        }

    internal fun openExistingForTest(
        context: Context,
        databaseName: String,
        proofIssuer: KnowledgeReferenceProofIssuer =
            KnowledgeReferenceProofAuthority.create().issuer,
        queryObserver: ((String) -> Unit)? = null,
    ): HighSchoolKnowledgeCatalog {
        require(databaseName.endsWith(TEST_DATABASE_SUFFIX)) {
            "Test knowledge database must use the '$TEST_DATABASE_SUFFIX' suffix"
        }
        return RoomHighSchoolKnowledgeCatalog(
            openReadOnlyExistingDatabase(context, databaseName, queryObserver),
            runtimeTrust = null,
            runtimeLease = null,
            proofIssuer = proofIssuer,
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

    internal fun openReadOnlyExistingDatabase(
        context: Context,
        databaseName: String,
        queryObserver: ((String) -> Unit)? = null,
    ): HighSchoolKnowledgeRoomDatabase {
        val applicationContext = context.applicationContext
        val databaseFile = applicationContext.getDatabasePath(databaseName)
        check(databaseFile.isFile && databaseFile.length() > 0L) {
            "High-school knowledge database '$databaseName' is not installed"
        }
        val lockCompanion = TrustedRoomLockCompanion.open(databaseFile)
        return try {
            Room.databaseBuilder(
                applicationContext,
                HighSchoolKnowledgeRoomDatabase::class.java,
                databaseName,
            ).addCallback(trustedRoomOpenCallback(lockCompanion, queryOnly = true))
                .setDriver(ReadOnlyAndroidSQLiteDriver(queryObserver))
                .build()
                .also { it.attachTrustedRoomLockCompanion(lockCompanion) }
        } catch (failure: Throwable) {
            lockCompanion.close()
            throw failure
        }
    }

    internal const val TEST_DATABASE_SUFFIX = ".knowledge-test.db"
}

internal object HighSchoolKnowledgePackBuilder {
    const val NEXT_DATABASE_NAME = "$HIGH_SCHOOL_KNOWLEDGE_DATABASE_NAME.next"

    fun openNext(context: Context): KnowledgePackBuildHandle {
        val applicationContext = context.applicationContext
        val lease = KnowledgePackActivationManager.beginNextBuild(applicationContext)
        return try {
            openWritable(applicationContext, NEXT_DATABASE_NAME, lease)
        } catch (failure: Throwable) {
            KnowledgePackActivationManager.abortNextBuildOpen(lease)
            throw failure
        }
    }

    fun discardNext(context: Context) {
        KnowledgePackActivationManager.discardNext(context.applicationContext)
    }

    fun openForTest(
        context: Context,
        databaseName: String,
    ): KnowledgePackBuildHandle {
        require(databaseName.endsWith(HighSchoolKnowledgeCatalogFactory.TEST_DATABASE_SUFFIX)) {
            "Test knowledge database must use the " +
                "'${HighSchoolKnowledgeCatalogFactory.TEST_DATABASE_SUFFIX}' suffix"
        }
        return openWritable(context, databaseName, productionLease = null)
    }

    private fun openWritable(
        context: Context,
        databaseName: String,
        productionLease: KnowledgePackActivationManager.BuilderLease?,
    ): KnowledgePackBuildHandle {
        check(databaseName != HIGH_SCHOOL_KNOWLEDGE_DATABASE_NAME) {
            "Pack building must never target the runtime knowledge database"
        }
        val applicationContext = context.applicationContext
        val lockCompanion =
            TrustedRoomLockCompanion.open(applicationContext.getDatabasePath(databaseName))
        val database =
            try {
                Room.databaseBuilder(
                    applicationContext,
                    HighSchoolKnowledgeRoomDatabase::class.java,
                    databaseName,
                ).addCallback(trustedRoomOpenCallback(lockCompanion, queryOnly = false))
                    .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
                    .setDriver(AndroidSQLiteDriver())
                    .build()
                    .also { it.attachTrustedRoomLockCompanion(lockCompanion) }
            } catch (failure: Throwable) {
                lockCompanion.close()
                throw failure
            }
        return KnowledgePackBuildHandle(database, productionLease)
    }
}

internal class KnowledgePackBuildHandle(
    private val database: HighSchoolKnowledgeRoomDatabase,
    private val productionLease: KnowledgePackActivationManager.BuilderLease?,
) : AutoCloseable {
    private val installer = KnowledgePackInstaller(database.installDao())
    private var testHandleClosed = false

    suspend fun replacePack(bundle: KnowledgePackInstallBundle) {
        val lease = productionLease
        if (lease == null) {
            check(!testHandleClosed) { "Knowledge-pack build handle is closed" }
            installer.replacePack(bundle)
            return
        }
        KnowledgePackActivationManager.beginBuilderWrite(lease)
        try {
            installer.replacePack(bundle)
        } finally {
            KnowledgePackActivationManager.endBuilderWrite(lease)
        }
    }

    override fun close() {
        val lease = productionLease
        if (lease == null) {
            if (testHandleClosed) return
            testHandleClosed = true
            database.close()
            return
        }
        KnowledgePackActivationManager.closeBuilder(lease) {
            database.close()
        }
    }
}

private class RoomHighSchoolKnowledgeCatalog(
    private val database: HighSchoolKnowledgeRoomDatabase,
    private val runtimeTrust: KnowledgeCatalogRuntimeTrust?,
    private val runtimeLease: KnowledgePackActivationManager.RuntimeLease?,
    private val proofIssuer: KnowledgeReferenceProofIssuer,
) : HighSchoolKnowledgeCatalog {
    private val catalogDao = database.catalogDao()
    private val snapshotRevision: StateFlow<KnowledgeCatalogSnapshotMetadata>? =
        runtimeTrust?.let { trust ->
            MutableStateFlow(
                KnowledgeCatalogSnapshotMetadata(
                    knowledgePackVersion = trust.knowledgePackVersion,
                    taxonomyVersion = trust.taxonomyVersion,
                    manifestFingerprint = trust.manifestFingerprint,
                    activationGeneration = trust.generation,
                ),
            ).asStateFlow()
        }

    override suspend fun readManifest(): KnowledgePackManifest =
        readActiveManifest().toCatalogManifest()

    override fun observeSnapshotRevision(): StateFlow<KnowledgeCatalogSnapshotMetadata> =
        checkNotNull(snapshotRevision) {
            "Activated knowledge revision requires a verified production catalog"
        }

    override suspend fun findNode(ref: KnowledgeNodeRef): KnowledgeCatalogNode? {
        val manifest = readActiveManifest()
        if (!ref.matches(manifest)) return null
        return findNode(ref, manifest)
    }

    override suspend fun findNodes(
        refs: List<KnowledgeNodeRef>,
    ): KnowledgeCatalogNodeDisplayBatch {
        require(refs.size <= HighSchoolKnowledgeCatalog.MAX_BATCH_NODE_REQUESTS) {
            "Knowledge-node batch request must contain at most " +
                HighSchoolKnowledgeCatalog.MAX_BATCH_NODE_REQUESTS +
                " references"
        }
        val trust =
            requireNotNull(runtimeTrust) {
                "Knowledge-node batch lookup requires a verified production catalog"
            }
        val manifest = readActiveManifest()
        check(trust.matches(manifest)) {
            "Active knowledge catalog changed after its activation receipt was verified"
        }
        require(refs.all { ref -> ref.matches(manifest) }) {
            "Knowledge-node batch contains a stale catalog reference"
        }

        val rows =
            if (refs.isEmpty()) {
                emptyList()
            } else {
                val knowledgeNodeIds =
                    refs
                        .map(KnowledgeNodeRef::knowledgeNodeId)
                        .distinct()
                catalogDao.findNodes(
                    taxonomyVersion = manifest.taxonomyVersion,
                    knowledgeNodeIds = knowledgeNodeIds,
                    rowLimit =
                        minOf(
                            knowledgeNodeIds.size,
                            HighSchoolKnowledgeCatalog.MAX_BATCH_NODE_ROWS,
                        ),
                )
            }
        check(rows.size <= HighSchoolKnowledgeCatalog.MAX_BATCH_NODE_ROWS) {
            "Knowledge-node batch query exceeded its row budget"
        }
        val metadataByRef =
            rows.associate { row ->
                val metadata = row.toCatalogNodeDisplayMetadata(manifest)
                metadata.ref to metadata
            }
        check(metadataByRef.size == rows.size) {
            "Corrupt high-school knowledge catalog: duplicate batch lookup rows"
        }

        val manifestAfterRead = readActiveManifest()
        check(manifestAfterRead == manifest && trust.matches(manifestAfterRead)) {
            "Active knowledge catalog changed during a batch lookup"
        }
        return KnowledgeCatalogNodeDisplayBatch(
            snapshot =
                KnowledgeCatalogSnapshotMetadata(
                    knowledgePackVersion = manifest.knowledgePackVersion,
                    taxonomyVersion = manifest.taxonomyVersion,
                    manifestFingerprint = trust.manifestFingerprint,
                    activationGeneration = trust.generation,
                ),
            lookups =
                refs.map { ref ->
                    KnowledgeCatalogNodeDisplayLookup(
                        requestedRef = ref,
                        metadata = metadataByRef[ref],
                    )
                },
        )
    }

    override suspend fun readDisplayOrderPage(
        subject: SubjectKind,
        afterOrderToken: String?,
        limit: Int,
    ): KnowledgeCatalogDisplayOrderPage {
        require(subject != SubjectKind.GENERAL) {
            "Knowledge display ordering requires a high-school subject"
        }
        require(limit in 1..HighSchoolKnowledgeCatalog.MAX_BATCH_NODE_REQUESTS) {
            "Knowledge display-order limit is outside the supported range"
        }
        afterOrderToken?.let { token ->
            require(token.isCatalogDisplayOrderToken()) {
                "Knowledge display-order cursor is invalid"
            }
        }
        val trust =
            requireNotNull(runtimeTrust) {
                "Knowledge display ordering requires a verified production catalog"
            }
        val manifest = readActiveManifest()
        check(trust.matches(manifest)) {
            "Active knowledge catalog changed after its activation receipt was verified"
        }
        val rows =
            catalogDao.readDisplayOrderPage(
                subject = subject.name,
                taxonomyVersion = manifest.taxonomyVersion,
                afterOrderToken = afterOrderToken,
                rowLimit = limit + 1,
            )
        check(rows.size <= limit + 1) {
            "Knowledge display-order query exceeded its row budget"
        }
        rows.forEach { row ->
            check(
                row.subject == subject.name &&
                    row.taxonomyVersion == manifest.taxonomyVersion &&
                    row.displayOrderToken.isCatalogDisplayOrderToken(),
            ) {
                "Corrupt high-school knowledge catalog display order"
            }
        }
        check(
            rows.zipWithNext().all { (left, right) ->
                left.displayOrderToken < right.displayOrderToken
            },
        ) {
            "Knowledge display-order query is not strictly ordered"
        }
        val hasNext = rows.size > limit
        val pageRows = rows.take(limit)
        val manifestAfterRead = readActiveManifest()
        check(manifestAfterRead == manifest && trust.matches(manifestAfterRead)) {
            "Active knowledge catalog changed during a display-order lookup"
        }
        return KnowledgeCatalogDisplayOrderPage(
            snapshot =
                KnowledgeCatalogSnapshotMetadata(
                    knowledgePackVersion = manifest.knowledgePackVersion,
                    taxonomyVersion = manifest.taxonomyVersion,
                    manifestFingerprint = trust.manifestFingerprint,
                    activationGeneration = trust.generation,
                ),
            orderingPolicyVersion =
                HighSchoolKnowledgeCatalog.DISPLAY_ORDERING_POLICY_VERSION,
            entries =
                pageRows.map { row ->
                    KnowledgeCatalogDisplayOrderEntry(
                        ref =
                            KnowledgeNodeRef(
                                subject = subject,
                                knowledgeNodeId = row.knowledgeNodeId,
                                taxonomyVersion = row.taxonomyVersion,
                                knowledgePackVersion = manifest.knowledgePackVersion,
                            ),
                        orderToken = row.displayOrderToken,
                    )
                },
            nextAfterOrderToken =
                pageRows.lastOrNull()?.displayOrderToken?.takeIf { hasNext },
        )
    }

    override suspend fun verifyReference(
        ref: KnowledgeNodeRef,
    ): VerifiedKnowledgeReferenceProof? {
        val trust = runtimeTrust ?: return null
        val manifest = readActiveManifest()
        check(trust.matches(manifest)) {
            "Active knowledge catalog changed after its activation receipt was verified"
        }
        if (!ref.matches(manifest)) return null
        val exists =
            catalogDao.containsNodeReference(
                subject = ref.subject.name,
                knowledgeNodeId = ref.knowledgeNodeId,
                taxonomyVersion = ref.taxonomyVersion,
            )
        if (!exists) return null
        val manifestAfterRead = readActiveManifest()
        check(manifestAfterRead == manifest && trust.matches(manifestAfterRead)) {
            "Active knowledge catalog changed while verifying a knowledge reference"
        }
        return proofIssuer.issue(
            ref,
            trust.manifestFingerprint,
            trust.generation,
        )
    }

    override suspend fun verifyReferences(
        refs: List<KnowledgeNodeRef>,
    ): List<VerifiedKnowledgeReferenceProof> {
        require(refs.size <= HighSchoolKnowledgeCatalog.MAX_BATCH_NODE_REQUESTS) {
            "Knowledge-reference verification batch exceeds its request budget"
        }
        if (refs.isEmpty()) return emptyList()
        require(refs.distinct().size == refs.size) {
            "Knowledge-reference verification batch must be unique"
        }
        val subject = refs.first().subject
        require(
            subject != SubjectKind.GENERAL && refs.all { ref -> ref.subject == subject },
        ) {
            "Knowledge-reference verification batch must stay inside one subject"
        }

        val trust = runtimeTrust ?: return emptyList()
        val manifest = readActiveManifest()
        check(trust.matches(manifest)) {
            "Active knowledge catalog changed after its activation receipt was verified"
        }
        if (refs.any { ref -> !ref.matches(manifest) }) return emptyList()
        val existingNodeIds =
            catalogDao.findCatalogNodes(
                subject = subject.name,
                taxonomyVersion = manifest.taxonomyVersion,
                knowledgeNodeIds = refs.map(KnowledgeNodeRef::knowledgeNodeId),
                rowLimit = refs.size,
            ).mapTo(hashSetOf(), KnowledgeNodeEntity::knowledgeNodeId)
        val manifestAfterRead = readActiveManifest()
        check(manifestAfterRead == manifest && trust.matches(manifestAfterRead)) {
            "Active knowledge catalog changed while verifying knowledge references"
        }
        return refs.mapNotNull { ref ->
            if (ref.knowledgeNodeId !in existingNodeIds) {
                null
            } else {
                proofIssuer.issue(
                    ref,
                    trust.manifestFingerprint,
                    trust.generation,
                )
            }
        }
    }

    override suspend fun resolveNode(ref: KnowledgeNodeRef): VerifiedKnowledgeNodeHandle? {
        val trust = runtimeTrust ?: return null
        val manifest = readActiveManifest()
        check(trust.matches(manifest)) {
            "Active knowledge catalog changed after its activation receipt was verified"
        }
        if (!ref.matches(manifest)) return null
        val node = findNode(ref, manifest) ?: return null
        return VerifiedKnowledgeNodeHandle.create(
            node = node,
            manifestFingerprint = trust.manifestFingerprint,
            activationGeneration = trust.generation,
        )
    }

    override suspend fun resolveNodes(
        subject: SubjectKind,
        knowledgeNodeIds: List<String>,
    ): List<VerifiedKnowledgeNodeHandle> {
        require(subject != SubjectKind.GENERAL) {
            "Knowledge handle resolution requires a specific subject"
        }
        require(knowledgeNodeIds.size in 1..HighSchoolKnowledgeCatalog.MAX_BATCH_NODE_REQUESTS) {
            "Knowledge handle batch exceeds its request budget"
        }
        require(knowledgeNodeIds.all(String::isNotBlank)) {
            "Knowledge handle ids must not be blank"
        }
        require(knowledgeNodeIds.distinct().size == knowledgeNodeIds.size) {
            "Knowledge handle ids must be unique"
        }
        val trust = runtimeTrust ?: return emptyList()
        val manifest = readActiveManifest()
        check(trust.matches(manifest)) {
            "Active knowledge catalog changed after its activation receipt was verified"
        }
        val nodesById =
            catalogDao.findCatalogNodes(
                subject = subject.name,
                taxonomyVersion = manifest.taxonomyVersion,
                knowledgeNodeIds = knowledgeNodeIds,
                rowLimit = knowledgeNodeIds.size,
            ).associateBy(KnowledgeNodeEntity::knowledgeNodeId)
        return knowledgeNodeIds.mapNotNull { nodeId ->
            nodesById[nodeId]?.toCatalogNode(manifest)?.let { node ->
                VerifiedKnowledgeNodeHandle.create(
                    node = node,
                    manifestFingerprint = trust.manifestFingerprint,
                    activationGeneration = trust.generation,
                )
            }
        }
    }

    override suspend fun recall(
        subject: SubjectKind,
        query: String,
        limit: Int,
    ): List<KnowledgeCatalogSearchHit> =
        recallByFeatures(
            subject = subject,
            features = KnowledgeSearchNormalizer.queryFeatures(query),
            limit = limit,
        )

    override suspend fun recall(
        subject: SubjectKind,
        query: KnowledgeRecallQuery,
        limit: Int,
    ): List<KnowledgeCatalogSearchHit> =
        recallByFeatures(
            subject = subject,
            features = KnowledgeSearchNormalizer.queryFeatures(query),
            limit = limit,
        )

    private suspend fun recallByFeatures(
        subject: SubjectKind,
        features: List<String>,
        limit: Int,
    ): List<KnowledgeCatalogSearchHit> {
        require(subject != SubjectKind.GENERAL) {
            "High-school knowledge recall requires a specific subject"
        }
        limit.requireCatalogLimit()
        val manifest = readActiveManifest()
        // Two-stage recall: queryFeatures orders features from high confidence
        // (EXACT/TOKEN) to low confidence (NGRAM), so the leading slice is the
        // most specific match. If it already fills the limit we avoid scanning
        // the full N-gram feature set.
        val leadingSlice = features.take(PRIMARY_RECALL_FEATURE_SLICE)
        val primary = recallRows(
            subject = subject,
            manifest = manifest,
            features = leadingSlice,
            limit = limit,
        )
        if (primary.size >= limit || leadingSlice.size == features.size) {
            return primary
        }
        return recallRows(
            subject = subject,
            manifest = manifest,
            features = features,
            limit = limit,
        )
    }

    private suspend fun recallRows(
        subject: SubjectKind,
        manifest: KnowledgePackManifestEntity,
        features: List<String>,
        limit: Int,
    ): List<KnowledgeCatalogSearchHit> =
        catalogDao.recall(
            subject = subject.name,
            taxonomyVersion = manifest.taxonomyVersion,
            features = features,
            limit = limit,
        ).map { row ->
            KnowledgeCatalogSearchHit(
                node = row.node.toCatalogNode(manifest),
                matchedFeatureCount = row.matchedFeatureCount.toInt(),
                bestRankWeight = row.bestRankWeight,
            )
        }

    override suspend fun readNeighborhood(
        subject: SubjectKind,
        query: String,
        directLimit: Int,
        relatedLimit: Int,
        relationLimit: Int,
    ): KnowledgeCatalogNeighborhood =
        readNeighborhoodByFeatures(
            subject = subject,
            features = KnowledgeSearchNormalizer.queryFeatures(query),
            directLimit = directLimit,
            relatedLimit = relatedLimit,
            relationLimit = relationLimit,
        )

    override suspend fun readNeighborhood(
        subject: SubjectKind,
        query: KnowledgeRecallQuery,
        directLimit: Int,
        relatedLimit: Int,
        relationLimit: Int,
    ): KnowledgeCatalogNeighborhood =
        readNeighborhoodByFeatures(
            subject = subject,
            features = KnowledgeSearchNormalizer.queryFeatures(query),
            directLimit = directLimit,
            relatedLimit = relatedLimit,
            relationLimit = relationLimit,
        )

    private suspend fun readNeighborhoodByFeatures(
        subject: SubjectKind,
        features: List<String>,
        directLimit: Int,
        relatedLimit: Int,
        relationLimit: Int,
    ): KnowledgeCatalogNeighborhood {
        require(subject != SubjectKind.GENERAL) {
            "Knowledge neighborhood requires a specific subject"
        }
        require(directLimit in 1..HighSchoolKnowledgeCatalog.MAX_NEIGHBORHOOD_DIRECT_NODES)
        require(relatedLimit in 1..HighSchoolKnowledgeCatalog.MAX_NEIGHBORHOOD_RELATED_NODES)
        require(relationLimit in 1..HighSchoolKnowledgeCatalog.MAX_NEIGHBORHOOD_RELATIONS)

        val manifest = readActiveManifest()
        val directHits =
            catalogDao.recall(
                subject = subject.name,
                taxonomyVersion = manifest.taxonomyVersion,
                features = features,
                limit = directLimit,
            ).map { row ->
                KnowledgeCatalogSearchHit(
                    node = row.node.toCatalogNode(manifest),
                    matchedFeatureCount = row.matchedFeatureCount.toInt(),
                    bestRankWeight = row.bestRankWeight,
                )
            }
        if (directHits.isEmpty()) {
            return KnowledgeCatalogNeighborhood(
                manifest = manifest.toCatalogManifest(),
                directHits = emptyList(),
                relations = emptyList(),
                relatedNodes = emptyList(),
                parentNodes = emptyList(),
            )
        }

        val directIds =
            directHits.mapTo(linkedSetOf()) { hit -> hit.node.ref.knowledgeNodeId }
        val candidateRelationRows =
            catalogDao.readRelationsForNodes(
                subject = subject.name,
                taxonomyVersion = manifest.taxonomyVersion,
                knowledgeNodeIds = directIds.toList(),
                perNodeLimit =
                    HighSchoolKnowledgeCatalog.MAX_NEIGHBORHOOD_RELATIONS_PER_DIRECT_NODE,
                rowLimit =
                    directIds.size *
                        HighSchoolKnowledgeCatalog.MAX_NEIGHBORHOOD_RELATIONS_PER_DIRECT_NODE,
            )
        val relationRows =
            selectFairRelationRows(
                directKnowledgeNodeIds = directIds.toList(),
                candidates = candidateRelationRows,
                relationLimit = relationLimit,
            )
        val relations = relationRows.map { relation -> relation.toCatalogRelation(manifest) }
        val relatedIds =
            relationRows
                .asSequence()
                .flatMap { relation ->
                    sequenceOf(
                        relation.fromKnowledgeNodeId,
                        relation.toKnowledgeNodeId,
                    )
                }
                .filterNot(directIds::contains)
                .distinct()
                .take(relatedLimit)
                .toList()
        val relatedNodes =
            if (relatedIds.isEmpty()) {
                emptyList()
            } else {
                catalogDao.findCatalogNodes(
                    subject = subject.name,
                    taxonomyVersion = manifest.taxonomyVersion,
                    knowledgeNodeIds = relatedIds,
                    rowLimit = relatedLimit,
                ).map { node -> node.toCatalogNode(manifest) }
            }
        val parentIds =
            (directHits.asSequence().map { hit -> hit.node } + relatedNodes.asSequence())
                .mapNotNull { node -> node.parentRef?.knowledgeNodeId }
                .distinct()
                .take(HighSchoolKnowledgeCatalog.MAX_NEIGHBORHOOD_PARENT_NODES)
                .toList()
        val parentNodes =
            if (parentIds.isEmpty()) {
                emptyList()
            } else {
                catalogDao.findCatalogNodes(
                    subject = subject.name,
                    taxonomyVersion = manifest.taxonomyVersion,
                    knowledgeNodeIds = parentIds,
                    rowLimit = HighSchoolKnowledgeCatalog.MAX_NEIGHBORHOOD_PARENT_NODES,
                ).map { node -> node.toCatalogNode(manifest) }
            }
        check(readActiveManifest() == manifest) {
            "Active knowledge catalog changed during a neighborhood lookup"
        }
        return KnowledgeCatalogNeighborhood(
            manifest = manifest.toCatalogManifest(),
            directHits = directHits,
            relations = relations,
            relatedNodes = relatedNodes,
            parentNodes = parentNodes,
        )
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

    override suspend fun readTeachingMaterials(
        node: VerifiedKnowledgeNodeHandle,
        limit: Int,
    ): List<KnowledgeCatalogTeachingMaterial> =
        readTeachingMaterials(nodes = listOf(node), limit = limit)

    override suspend fun readTeachingMaterials(
        nodes: List<VerifiedKnowledgeNodeHandle>,
        limit: Int,
    ): List<KnowledgeCatalogTeachingMaterial> {
        limit.requireTeachingMaterialLimit()
        require(nodes.isNotEmpty()) { "Batch teaching lookup requires at least one node" }
        require(nodes.size <= HighSchoolKnowledgeCatalog.MAX_BATCH_NODE_REQUESTS) {
            "Batch teaching lookup exceeds its node budget"
        }
        val trust =
            requireNotNull(runtimeTrust) {
                "Teaching materials require a verified production catalog"
            }
        val manifest = readActiveManifest()
        check(trust.matches(manifest)) {
            "Active knowledge catalog changed after its activation receipt was verified"
        }
        val subject = nodes.first().ref.subject
        require(
            subject != SubjectKind.GENERAL &&
                nodes.all { node ->
                    node.ref.subject == subject &&
                        node.manifestFingerprint == trust.manifestFingerprint &&
                        node.activationGeneration == trust.generation &&
                        node.ref.matches(manifest)
                },
        ) {
            "Verified knowledge handles do not share the active subject snapshot"
        }
        val handlesById = nodes.associateBy { node -> node.ref.knowledgeNodeId }
        require(handlesById.size == nodes.size) {
            "Batch teaching lookup contains duplicate knowledge handles"
        }
        val activeNodes =
            catalogDao.findCatalogNodes(
                subject = subject.name,
                taxonomyVersion = manifest.taxonomyVersion,
                knowledgeNodeIds = handlesById.keys.toList(),
                rowLimit = handlesById.size,
            ).associateBy(KnowledgeNodeEntity::knowledgeNodeId)
        require(
            activeNodes.size == handlesById.size &&
                handlesById.all { (nodeId, handle) ->
                    activeNodes[nodeId]?.toCatalogNode(manifest) == handle.node
                },
        ) {
            "Verified knowledge handles do not match the active catalog nodes"
        }

        val summaryRows =
            catalogDao.readTeachingMaterialSummaries(
                subject = subject.name,
                taxonomyVersion = manifest.taxonomyVersion,
                knowledgeNodeIds = handlesById.keys.toList(),
                rowLimit =
                    minOf(
                        handlesById.size * HighSchoolKnowledgeCatalog.MAX_TEACHING_MATERIAL_LIMIT,
                        MAX_BATCH_TEACHING_SUMMARY_ROWS,
                    ),
            )
        val selectedMaterialIds = LinkedHashSet<String>()
        var selectedMarkdownChars = 0
        for (row in summaryRows) {
            if (row.materialId in selectedMaterialIds) continue
            val materialMarkdownChars =
                row.summaryMarkdown.length +
                    row.applicabilityMarkdown.length +
                    row.boundaryMarkdown.length +
                    row.contentMarkdownLength
            if (
                selectedMarkdownChars + materialMarkdownChars >
                    HighSchoolKnowledgeCatalog.MAX_RETURNED_TEACHING_MATERIAL_MARKDOWN_CHARS
            ) {
                continue
            }
            selectedMaterialIds += row.materialId
            selectedMarkdownChars += materialMarkdownChars
            if (selectedMaterialIds.size == limit) break
        }
        if (selectedMaterialIds.isEmpty()) return emptyList()

        val bodies =
            catalogDao.readTeachingMaterialBodies(
                materialIds = selectedMaterialIds.toList(),
                rowLimit = selectedMaterialIds.size,
            ).associateBy(KnowledgeTeachingMaterialBodyRow::materialId)
        val acceptedMaterialIds = LinkedHashSet<String>()
        var markdownChars = 0
        val result = ArrayList<KnowledgeCatalogTeachingMaterial>()
        for (row in summaryRows) {
            if (row.materialId !in selectedMaterialIds) continue
            val body = bodies[row.materialId]?.contentMarkdown ?: continue
            if (row.materialId !in acceptedMaterialIds) {
                val materialChars =
                    row.summaryMarkdown.length +
                        row.applicabilityMarkdown.length +
                        body.length +
                        row.boundaryMarkdown.length
                if (
                    markdownChars + materialChars >
                        HighSchoolKnowledgeCatalog.MAX_RETURNED_TEACHING_MATERIAL_MARKDOWN_CHARS
                ) {
                    continue
                }
                acceptedMaterialIds += row.materialId
                markdownChars += materialChars
            }
            if (row.materialId !in acceptedMaterialIds) continue
            val ref = handlesById[row.knowledgeNodeId]?.ref ?: continue
            result += row.toCatalogTeachingMaterial(ref, body)
        }
        check(readActiveManifest() == manifest && trust.matches(manifest)) {
            "Active knowledge catalog changed during a batch teaching lookup"
        }
        return result
    }

    override fun close() {
        val lease = runtimeLease
        if (lease == null) {
            database.close()
            return
        }
        KnowledgePackActivationManager.closeRuntimeSnapshot(lease) {
            database.close()
        }
    }

    private suspend fun readActiveManifest(): KnowledgePackManifestEntity =
        checkNotNull(catalogDao.readManifest()) {
            "The high-school knowledge catalog has no installed manifest"
        }

    private suspend fun findNode(
        ref: KnowledgeNodeRef,
        manifest: KnowledgePackManifestEntity,
    ): KnowledgeCatalogNode? =
        catalogDao.findNode(
            subject = ref.subject.name,
            knowledgeNodeId = ref.knowledgeNodeId,
            taxonomyVersion = ref.taxonomyVersion,
        )?.toCatalogNode(manifest)

    private companion object {
        const val MAX_RELATION_TYPE_FILTERS = 16
        const val MAX_BATCH_TEACHING_SUMMARY_ROWS = 512
    }
}

/**
 * Selects at most one unique relation per direct hit in each round.
 *
 * Recall order remains the deterministic tie-breaker when the total budget cannot give every
 * direct hit another relation. A relation joining two direct hits is consumed only once and does
 * not prevent the second origin from advancing to its next candidate in the same round.
 */
internal fun selectFairRelationRows(
    directKnowledgeNodeIds: List<String>,
    candidates: List<KnowledgeRelationForOriginRow>,
    relationLimit: Int,
): List<KnowledgeNodeRelationEntity> {
    require(directKnowledgeNodeIds.isNotEmpty())
    require(directKnowledgeNodeIds.distinct().size == directKnowledgeNodeIds.size)
    require(relationLimit > 0)
    val directIds = directKnowledgeNodeIds.toHashSet()
    val candidatesByOrigin =
        candidates
            .asSequence()
            .filter { candidate -> candidate.originKnowledgeNodeId in directIds }
            .groupBy(KnowledgeRelationForOriginRow::originKnowledgeNodeId)
            .mapValues { (_, rows) ->
                rows.sortedWith(
                    compareBy(
                        KnowledgeRelationForOriginRow::originRank,
                        { row -> row.relation.relationType },
                        { row -> row.relation.fromKnowledgeNodeId },
                        { row -> row.relation.toKnowledgeNodeId },
                        { row -> row.relation.relationId },
                    ),
                )
            }
    val cursorByOrigin = directKnowledgeNodeIds.associateWithTo(HashMap()) { 0 }
    val selectedIds = HashSet<String>(relationLimit)
    val selected = ArrayList<KnowledgeNodeRelationEntity>(relationLimit)
    while (selected.size < relationLimit) {
        var consumedCandidate = false
        for (originId in directKnowledgeNodeIds) {
            val originCandidates = candidatesByOrigin[originId].orEmpty()
            var cursor = cursorByOrigin.getValue(originId)
            while (cursor < originCandidates.size) {
                val relation = originCandidates[cursor].relation
                cursor += 1
                cursorByOrigin[originId] = cursor
                consumedCandidate = true
                if (!selectedIds.add(relation.relationId)) continue
                selected += relation
                break
            }
            if (selected.size == relationLimit) return selected
        }
        if (!consumedCandidate) break
    }
    return selected
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

private fun KnowledgeNodeDisplayRow.toCatalogNodeDisplayMetadata(
    manifest: KnowledgePackManifestEntity,
): KnowledgeCatalogNodeDisplayMetadata {
    check(taxonomyVersion == manifest.taxonomyVersion) {
        "Corrupt high-school knowledge catalog: node taxonomy does not match manifest"
    }
    val nodeSubject = enumValueOrCorrupt<SubjectKind>(subject, "node subject")
    val nodeRef =
        KnowledgeNodeRef(
            subject = nodeSubject,
            knowledgeNodeId = knowledgeNodeId,
            taxonomyVersion = taxonomyVersion,
            knowledgePackVersion = manifest.knowledgePackVersion,
        )
    return KnowledgeCatalogNodeDisplayMetadata(
        ref = nodeRef,
        displayName = displayName,
        kind = enumValueOrCorrupt(nodeKind, "node kind"),
        granularity = enumValueOrCorrupt(granularity, "node granularity"),
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

private fun KnowledgeTeachingMaterialRow.toCatalogTeachingMaterial(
    knowledgeNodeRef: KnowledgeNodeRef,
): KnowledgeCatalogTeachingMaterial {
    val materialSubject =
        enumValueOrCorrupt<SubjectKind>(material.subject, "teaching-material subject")
    return KnowledgeCatalogTeachingMaterial(
        materialId = material.materialId,
        stableCode = material.stableCode,
        knowledgeNodeRef = knowledgeNodeRef,
        subject = materialSubject,
        materialType =
            enumValueOrCorrupt<KnowledgeTeachingMaterialType>(
                material.materialType,
                "teaching-material type",
            ),
        nodeRole =
            enumValueOrCorrupt<KnowledgeMaterialNodeRole>(
                role,
                "teaching-material node role",
            ),
        title = material.title,
        summaryMarkdown = material.summaryMarkdown,
        applicabilityMarkdown = material.applicabilityMarkdown,
        contentMarkdown = material.contentMarkdown,
        boundaryMarkdown = material.boundaryMarkdown,
        derivationKind =
            enumValueOrCorrupt<KnowledgeMaterialDerivationKind>(
                material.derivationKind,
                "teaching-material derivation kind",
            ),
        sourceId = material.sourceId,
        sourceLocator = material.sourceLocator,
        contentFingerprint = material.contentFingerprint,
        reviewedAtEpochMillis = material.reviewedAtEpochMillis,
    )
}

private fun KnowledgeTeachingMaterialSummaryRow.toCatalogTeachingMaterial(
    knowledgeNodeRef: KnowledgeNodeRef,
    contentMarkdown: String,
): KnowledgeCatalogTeachingMaterial {
    val materialSubject =
        enumValueOrCorrupt<SubjectKind>(subject, "teaching-material subject")
    return KnowledgeCatalogTeachingMaterial(
        materialId = materialId,
        stableCode = stableCode,
        knowledgeNodeRef = knowledgeNodeRef,
        subject = materialSubject,
        materialType =
            enumValueOrCorrupt<KnowledgeTeachingMaterialType>(
                materialType,
                "teaching-material type",
            ),
        nodeRole =
            enumValueOrCorrupt<KnowledgeMaterialNodeRole>(
                role,
                "teaching-material node role",
            ),
        title = title,
        summaryMarkdown = summaryMarkdown,
        applicabilityMarkdown = applicabilityMarkdown,
        contentMarkdown = contentMarkdown,
        boundaryMarkdown = boundaryMarkdown,
        derivationKind =
            enumValueOrCorrupt<KnowledgeMaterialDerivationKind>(
                derivationKind,
                "teaching-material derivation kind",
            ),
        sourceId = sourceId,
        sourceLocator = sourceLocator,
        contentFingerprint = contentFingerprint,
        reviewedAtEpochMillis = reviewedAtEpochMillis,
    )
}

private fun KnowledgeNodeRef.matches(manifest: KnowledgePackManifestEntity): Boolean =
    taxonomyVersion == manifest.taxonomyVersion &&
        knowledgePackVersion == manifest.knowledgePackVersion

private fun KnowledgeCatalogRuntimeTrust.matches(
    manifest: KnowledgePackManifestEntity,
): Boolean =
    packId == manifest.packId &&
        knowledgePackVersion == manifest.knowledgePackVersion &&
        taxonomyVersion == manifest.taxonomyVersion &&
        manifestFingerprint == manifest.contentFingerprint

private fun Int.requireCatalogLimit() {
    require(this in 1..HighSchoolKnowledgeCatalog.MAX_RESULT_LIMIT) {
        "Knowledge catalog result limit must be between 1 and " +
            HighSchoolKnowledgeCatalog.MAX_RESULT_LIMIT
    }
}

private fun Int.requireTeachingMaterialLimit() {
    require(this in 1..HighSchoolKnowledgeCatalog.MAX_TEACHING_MATERIAL_LIMIT) {
        "Teaching-material limit must be between 1 and " +
            HighSchoolKnowledgeCatalog.MAX_TEACHING_MATERIAL_LIMIT
    }
}

private inline fun <reified T : Enum<T>> enumValueOrCorrupt(
    value: String,
    label: String,
): T =
    enumValues<T>().firstOrNull { it.name == value }
        ?: error("Corrupt high-school knowledge catalog: unknown $label '$value'")

private fun trustedRoomOpenCallback(
    lockCompanion: TrustedRoomLockCompanion,
    queryOnly: Boolean,
): RoomDatabase.Callback =
    object : RoomDatabase.Callback() {
        override suspend fun onOpen(connection: SQLiteConnection) {
            lockCompanion.requireAtPath()
            if (queryOnly) connection.execSQL("PRAGMA query_only = ON")
            lockCompanion.requireAtPath()
        }
    }

/**
 * AndroidSQLiteDriver uses openOrCreateDatabase. Runtime catalog access instead uses the platform
 * OPEN_READONLY flag so neither a missing file nor a schema mismatch can create or migrate data.
 */
internal class ReadOnlyAndroidSQLiteDriver(
    private val queryObserver: ((String) -> Unit)? = null,
) : SQLiteDriver {
    override fun open(fileName: String): SQLiteConnection {
        val database =
            SQLiteDatabase.openDatabase(
                fileName,
                null,
                SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
            )
        check(database.isReadOnly) {
            database.close()
            "High-school knowledge catalog did not open as a physical read-only database"
        }
        val connection = ReadOnlyFrameworkConnectionFactory.create(database)
        val observer = queryObserver ?: return connection
        return object : SQLiteConnection {
            override fun prepare(sql: String) =
                connection.prepare(sql).also { observer(sql) }

            override fun inTransaction(): Boolean = connection.inTransaction()

            override fun close() = connection.close()
        }
    }
}
