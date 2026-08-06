package com.tingyun.smartmistakebook.core.knowledge.database

import android.content.Context
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
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Collections

/**
 * Module-private authorization generated alongside one reviewed APK bundle.
 *
 * Callers cannot construct or supply this type. The fixed registry below is the only source of
 * authorizations, and the digest covers the complete canonical pack, including its manifest and
 * locally rebuilt search features.
 */
private class TrustedKnowledgePackAuthorization(
    val packId: String,
    val knowledgePackVersion: String,
    val taxonomyVersion: String,
    val searchIndexVersion: String,
    val generation: Long,
    val expectedContentFingerprint: String,
    val productionCutoverEligible: Boolean,
) {
    val recoveryAuthorizationIdentity: String =
        trustedKnowledgePackAuthorizationIdentity(
            packId = packId,
            knowledgePackVersion = knowledgePackVersion,
            taxonomyVersion = taxonomyVersion,
            searchIndexVersion = searchIndexVersion,
            generation = generation,
            contentFingerprint = expectedContentFingerprint,
        )

    init {
        packId.requireProvisionId("Authorized pack id")
        knowledgePackVersion.requireProvisionId("Authorized pack version")
        taxonomyVersion.requireProvisionId("Authorized taxonomy version")
        searchIndexVersion.requireProvisionId("Authorized search-index version")
        require(generation > 0L) { "Authorized knowledge-pack generation must be positive" }
        require(PROVISION_SHA_256.matches(expectedContentFingerprint)) {
            "Authorized knowledge-pack fingerprint must be lowercase SHA-256"
        }
    }
}

data class ReviewedKnowledgePackMetadata(
    val packId: String,
    val knowledgePackVersion: String,
    val taxonomyVersion: String,
    val searchIndexVersion: String,
    val builtAtEpochMillis: Long,
) {
    init {
        packId.requireProvisionId("Reviewed pack id")
        knowledgePackVersion.requireProvisionId("Reviewed pack version")
        taxonomyVersion.requireProvisionId("Reviewed taxonomy version")
        searchIndexVersion.requireProvisionId("Reviewed search-index version")
        require(builtAtEpochMillis >= 0L) {
            "Reviewed knowledge-pack build time must not be negative"
        }
    }
}

data class ReviewedKnowledgeNode(
    val knowledgeNodeId: String,
    val stableCode: String,
    val subject: SubjectKind,
    val displayName: String,
    val canonicalName: String,
    val kind: KnowledgeNodeKind,
    val granularity: KnowledgeNodeGranularity,
    val aliases: List<String> = emptyList(),
    val boundaryMarkdown: String?,
    val verificationStatus: KnowledgeNodeVerificationStatus,
    val parentKnowledgeNodeId: String?,
    val reviewedAtEpochMillis: Long,
) {
    init {
        knowledgeNodeId.requireProvisionId("Reviewed knowledge-node id")
        stableCode.requireProvisionId("Reviewed knowledge-node stable code")
        subject.requireProvisionSubject("Reviewed knowledge node")
        displayName.requireProvisionText(
            "Reviewed knowledge-node display name",
            PROVISION_MAX_DISPLAY_CHARS,
        )
        canonicalName.requireProvisionText(
            "Reviewed knowledge-node canonical name",
            PROVISION_MAX_DISPLAY_CHARS,
        )
        require((kind == KnowledgeNodeKind.TOPIC) == (granularity == KnowledgeNodeGranularity.TOPIC)) {
            "Only topic nodes may use topic granularity"
        }
        require(aliases.size <= PROVISION_MAX_ALIASES && aliases.distinct().size == aliases.size) {
            "Reviewed knowledge-node aliases are invalid"
        }
        aliases.forEach { alias ->
            alias.requireProvisionText(
                label = "Reviewed knowledge-node alias",
                maximumLength = PROVISION_MAX_ALIAS_CHARS,
            )
        }
        require(canonicalName !in aliases) {
            "Reviewed knowledge-node aliases must not repeat the canonical name"
        }
        boundaryMarkdown?.requireProvisionText("Reviewed knowledge-node boundary")
        require(verificationStatus != KnowledgeNodeVerificationStatus.MODEL_CANDIDATE) {
            "Model-candidate nodes cannot enter a reviewed APK bundle"
        }
        parentKnowledgeNodeId?.requireProvisionId("Reviewed knowledge-node parent id")
        require(parentKnowledgeNodeId != knowledgeNodeId) {
            "A reviewed knowledge node cannot be its own parent"
        }
        require(reviewedAtEpochMillis >= 0L) {
            "Reviewed knowledge-node review time must not be negative"
        }
    }
}

data class ReviewedKnowledgeSource(
    val sourceId: String,
    val subject: SubjectKind,
    val sourceType: KnowledgeSourceType,
    val title: String,
    val publisher: String?,
    val edition: String?,
    val sourceUri: String?,
    val licenseStatus: KnowledgeSourceLicenseStatus,
    val contentUsePolicy: KnowledgeSourceContentUsePolicy,
    val contentFingerprint: String,
    val licenseExpression: String?,
    val licenseUri: String?,
    val attributionText: String?,
    val reviewedAtEpochMillis: Long,
) {
    init {
        sourceId.requireProvisionId("Reviewed knowledge-source id")
        subject.requireProvisionSubject("Reviewed knowledge source")
        title.requireProvisionText("Reviewed knowledge-source title")
        publisher?.requireProvisionText("Reviewed knowledge-source publisher")
        edition?.requireProvisionText("Reviewed knowledge-source edition")
        sourceUri?.requireProvisionText("Reviewed knowledge-source URI")
        require(PROVISION_SHA_256.matches(contentFingerprint)) {
            "Reviewed knowledge-source fingerprint must be lowercase SHA-256"
        }
        licenseExpression?.requireProvisionText("Reviewed knowledge-source license expression")
        licenseUri?.requireProvisionText("Reviewed knowledge-source license URI")
        attributionText?.requireProvisionText("Reviewed knowledge-source attribution")
        require(
            licenseStatus != KnowledgeSourceLicenseStatus.REFERENCE_ONLY ||
                contentUsePolicy == KnowledgeSourceContentUsePolicy.REVIEWED_SYNTHESIS_ONLY,
        ) {
            "Reference-only sources may only support reviewed synthesis"
        }
        if (
            licenseStatus == KnowledgeSourceLicenseStatus.LICENSED &&
            contentUsePolicy != KnowledgeSourceContentUsePolicy.REVIEWED_SYNTHESIS_ONLY
        ) {
            require(!licenseExpression.isNullOrBlank() && !attributionText.isNullOrBlank()) {
                "Licensed excerpts and adaptations require license metadata and attribution"
            }
        }
        require(reviewedAtEpochMillis >= 0L) {
            "Reviewed knowledge-source review time must not be negative"
        }
    }
}

data class ReviewedKnowledgeNodeSourceBinding(
    val knowledgeNodeId: String,
    val sourceId: String,
    val sourceLocator: String,
    val derivationNote: String,
    val reviewedAtEpochMillis: Long,
) {
    init {
        knowledgeNodeId.requireProvisionId("Reviewed node-source knowledge-node id")
        sourceId.requireProvisionId("Reviewed node-source source id")
        sourceLocator.requireProvisionText("Reviewed node-source locator")
        derivationNote.requireProvisionText("Reviewed node-source derivation note")
        require(reviewedAtEpochMillis >= 0L) {
            "Reviewed node-source binding time must not be negative"
        }
    }
}

data class ReviewedKnowledgeRelation(
    val relationId: String,
    val subject: SubjectKind,
    val fromKnowledgeNodeId: String,
    val toKnowledgeNodeId: String,
    val relationType: String,
    val sourceId: String,
    val sourceLocator: String,
    val reviewedAtEpochMillis: Long,
) {
    init {
        relationId.requireProvisionId("Reviewed knowledge-relation id")
        subject.requireProvisionSubject("Reviewed knowledge relation")
        fromKnowledgeNodeId.requireProvisionId("Reviewed relation source-node id")
        toKnowledgeNodeId.requireProvisionId("Reviewed relation target-node id")
        require(fromKnowledgeNodeId != toKnowledgeNodeId) {
            "A reviewed knowledge relation cannot point to itself"
        }
        relationType.requireProvisionId("Reviewed knowledge-relation type")
        sourceId.requireProvisionId("Reviewed knowledge-relation source id")
        sourceLocator.requireProvisionText(
            "Reviewed knowledge-relation source locator",
            PROVISION_MAX_DISPLAY_CHARS,
        )
        require(reviewedAtEpochMillis >= 0L) {
            "Reviewed knowledge-relation review time must not be negative"
        }
    }
}

/**
 * Reviewed explanatory content. It supports knowledge retrieval and tutoring only; it has no
 * question identity, learner answer, mistake status, review schedule, or mastery fields.
 */
data class ReviewedKnowledgeTeachingMaterial(
    val materialId: String,
    val stableCode: String,
    val subject: SubjectKind,
    val materialType: KnowledgeTeachingMaterialType,
    val title: String,
    val summaryMarkdown: String,
    val applicabilityMarkdown: String,
    val contentMarkdown: String,
    val boundaryMarkdown: String,
    val derivationKind: KnowledgeMaterialDerivationKind,
    val sourceId: String,
    val sourceLocator: String,
    val contentFingerprint: String,
    val reviewedAtEpochMillis: Long,
) {
    init {
        materialId.requireProvisionId("Reviewed teaching-material id")
        stableCode.requireProvisionId("Reviewed teaching-material stable code")
        subject.requireProvisionSubject("Reviewed teaching material")
        title.requireProvisionText("Reviewed teaching-material title")
        summaryMarkdown.requireProvisionText("Reviewed teaching-material summary")
        applicabilityMarkdown.requireProvisionText("Reviewed teaching-material applicability")
        contentMarkdown.requireProvisionText("Reviewed teaching-material content")
        boundaryMarkdown.requireProvisionText("Reviewed teaching-material boundary")
        require(
            summaryMarkdown.length +
                applicabilityMarkdown.length +
                contentMarkdown.length +
                boundaryMarkdown.length <=
                HighSchoolKnowledgeCatalog.MAX_SINGLE_TEACHING_MATERIAL_MARKDOWN_CHARS,
        ) {
            "Reviewed teaching material exceeds the per-material Markdown budget"
        }
        sourceId.requireProvisionId("Reviewed teaching-material source id")
        sourceLocator.requireProvisionText("Reviewed teaching-material source locator")
        require(PROVISION_SHA_256.matches(contentFingerprint)) {
            "Reviewed teaching-material fingerprint must be lowercase SHA-256"
        }
        require(reviewedAtEpochMillis >= 0L) {
            "Reviewed teaching-material review time must not be negative"
        }
    }
}

data class ReviewedKnowledgeTeachingMaterialBinding(
    val materialId: String,
    val knowledgeNodeId: String,
    val role: KnowledgeMaterialNodeRole,
) {
    init {
        materialId.requireProvisionId("Reviewed teaching-material binding material id")
        knowledgeNodeId.requireProvisionId("Reviewed teaching-material binding node id")
    }
}

/**
 * Fully structured input for a reviewed knowledge pack.
 *
 * There is intentionally no JSON, byte stream, URL, Room, DAO, SQL, or file-path entry point.
 * Search rows are omitted because the module deterministically rebuilds them from reviewed nodes.
 */
class ReviewedKnowledgePack(
    val metadata: ReviewedKnowledgePackMetadata,
    nodes: List<ReviewedKnowledgeNode>,
    sources: List<ReviewedKnowledgeSource>,
    nodeSourceBindings: List<ReviewedKnowledgeNodeSourceBinding>,
    relations: List<ReviewedKnowledgeRelation>,
    teachingMaterials: List<ReviewedKnowledgeTeachingMaterial>,
    teachingMaterialBindings: List<ReviewedKnowledgeTeachingMaterialBinding>,
) {
    val nodes: List<ReviewedKnowledgeNode> =
        nodes.snapshotProvisionList(KnowledgePackBudgets.MAX_NODES, "node") { node ->
            node.copy(
                aliases =
                    Collections.unmodifiableList(
                        ArrayList(node.aliases),
                    ),
            )
        }
    val sources: List<ReviewedKnowledgeSource> =
        sources.snapshotProvisionList(KnowledgePackBudgets.MAX_SOURCES, "source")
    val nodeSourceBindings: List<ReviewedKnowledgeNodeSourceBinding> =
        nodeSourceBindings.snapshotProvisionList(
            KnowledgePackBudgets.MAX_NODE_SOURCE_BINDINGS,
            "node-source binding",
        )
    val relations: List<ReviewedKnowledgeRelation> =
        relations.snapshotProvisionList(KnowledgePackBudgets.MAX_RELATIONS, "relation")
    val teachingMaterials: List<ReviewedKnowledgeTeachingMaterial> =
        teachingMaterials.snapshotProvisionList(
            KnowledgePackBudgets.MAX_MATERIALS,
            "teaching material",
        )
    val teachingMaterialBindings: List<ReviewedKnowledgeTeachingMaterialBinding> =
        teachingMaterialBindings.snapshotProvisionList(
            KnowledgePackBudgets.MAX_MATERIAL_BINDINGS,
            "teaching-material binding",
        )
}

enum class KnowledgePackProvisionOutcome {
    ACTIVATED,
    ALREADY_ACTIVE,
}

/**
 * Result of a trusted local provision attempt. The activation proof cannot be constructed or
 * altered by ordinary callers.
 */
class KnowledgePackProvisionReceipt private constructor(
    val outcome: KnowledgePackProvisionOutcome,
    val activation: KnowledgeCatalogActivationReceipt,
    val productionCutoverEligible: Boolean,
) {
    internal companion object {
        /**
         * The only receipt issuer. Even JVM callers that can see Kotlin-internal bytecode must
         * pass through the fixed registry and complete pack validation; no generic factory accepts
         * an activation receipt or a caller-selected production flag.
         */
        suspend fun provisionRegisteredPack(
            context: Context,
            pack: ReviewedKnowledgePack,
        ): KnowledgePackProvisionReceipt {
            val registered = TrustedKnowledgePackRegistry.requireRegistered(pack)
            val authorization = registered.authorization
            val bundle = registered.bundle
            val current =
                KnowledgePackActivationManager.currentActivationReceipt(
                    context.applicationContext,
                )
            if (current != null && current.hasSameContentIdentity(authorization)) {
                require(current.generation == authorization.generation) {
                    "An active knowledge pack must retain its original activation generation"
                }
                return KnowledgePackProvisionReceipt(
                    outcome = KnowledgePackProvisionOutcome.ALREADY_ACTIVE,
                    activation = current,
                    productionCutoverEligible = authorization.productionCutoverEligible,
                )
            }
            if (current != null) {
                require(current.packId == authorization.packId) {
                    "Knowledge-pack provisioning cannot switch package lineage"
                }
                require(current.knowledgePackVersion != authorization.knowledgePackVersion) {
                    "An installed knowledge-pack version cannot be replaced"
                }
                require(authorization.generation > current.generation) {
                    "Knowledge-pack generation must advance monotonically"
                }
            }

            var ownsCandidate = false
            try {
                val builder = HighSchoolKnowledgePackBuilder.openNext(context.applicationContext)
                ownsCandidate = true
                builder.use { handle ->
                    handle.replacePack(bundle)
                }
                val activation =
                    KnowledgePackActivationManager.activateNext(
                        context = context.applicationContext,
                        authorization = authorization.toActivationAuthorization(),
                    )
                ownsCandidate = false
                return KnowledgePackProvisionReceipt(
                    outcome = KnowledgePackProvisionOutcome.ACTIVATED,
                    activation = activation,
                    productionCutoverEligible = authorization.productionCutoverEligible,
                )
            } catch (failure: Throwable) {
                if (ownsCandidate) {
                    runCatching {
                        HighSchoolKnowledgePackBuilder.discardNext(context.applicationContext)
                    }.exceptionOrNull()?.let(failure::addSuppressed)
                }
                throw failure
            }
        }
    }
}

object HighSchoolKnowledgePackProvisioner {
    /**
     * Installs one reviewed APK-bundled pack through `.next`, complete validation, and atomic
     * activation. Repeating the exact registered pack is idempotent. Trust-registry and validation
     * failures never replace the active catalog.
     */
    suspend fun provisionApkBundledReviewedPack(
        context: Context,
        pack: ReviewedKnowledgePack,
    ): KnowledgePackProvisionReceipt =
        KnowledgePackProvisionReceipt.provisionRegisteredPack(
            context = context,
            pack = pack,
        )
}

/**
 * Fixed identity constants for the historical sample used only to prove the three-store migration
 * path. This is not the complete production corpus and its cutover eligibility is always false.
 *
 * The fingerprint is generated and checked by a knowledge-module consistency test from the
 * reviewed APK resources. Runtime code can ask the private registry to compare this exact pack; it
 * cannot mint an authorization from model, network, or user data.
 */
object HistoricalSampleKnowledgePackAuthorization {
    const val PACK_ID = "moe-2020-foundation-v1"
    const val KNOWLEDGE_PACK_VERSION =
        "moe-2020-foundation-v1-sample-migration-v1"
    const val TAXONOMY_VERSION = "moe-2020-foundation-v1"
    const val SEARCH_INDEX_VERSION = "local-search-index-v1"
    const val GENERATION = 1L
    const val PRODUCTION_CUTOVER_ELIGIBLE = false

    // Updated only together with the reviewed APK resources and the consistency test.
    const val EXPECTED_CONTENT_FINGERPRINT =
        "c52f87aa1300136f10923b81fb7fc5f35f4682c5aad7893f97de0da18574e267"

    /**
     * Recomputes the complete persisted representation with the knowledge module's canonical
     * implementation. It never returns a new authorization and therefore cannot self-authorize
     * arbitrary runtime input.
     */
    fun requireMatchingReviewedPack(pack: ReviewedKnowledgePack) {
        TrustedKnowledgePackRegistry.requireHistoricalSample(pack)
    }
}

private class RegisteredKnowledgePack(
    val authorization: TrustedKnowledgePackAuthorization,
    val bundle: KnowledgePackInstallBundle,
)

/**
 * The sole production trust root for knowledge-pack mutation.
 *
 * Adding a production-eligible pack requires a reviewed source change in this module. Runtime
 * callers can submit structured content for comparison, but cannot select an authorization,
 * provide a digest, or declare production eligibility.
 */
private object TrustedKnowledgePackRegistry {
    private val historicalSample =
        TrustedKnowledgePackAuthorization(
            packId = HistoricalSampleKnowledgePackAuthorization.PACK_ID,
            knowledgePackVersion =
                HistoricalSampleKnowledgePackAuthorization.KNOWLEDGE_PACK_VERSION,
            taxonomyVersion = HistoricalSampleKnowledgePackAuthorization.TAXONOMY_VERSION,
            searchIndexVersion = HistoricalSampleKnowledgePackAuthorization.SEARCH_INDEX_VERSION,
            generation = HistoricalSampleKnowledgePackAuthorization.GENERATION,
            expectedContentFingerprint =
                HistoricalSampleKnowledgePackAuthorization.EXPECTED_CONTENT_FINGERPRINT,
            productionCutoverEligible =
                HistoricalSampleKnowledgePackAuthorization.PRODUCTION_CUTOVER_ELIGIBLE,
        )

    private val buildVariantEntries =
        BuildVariantTrustedKnowledgePackRegistry.entries.map { definition ->
            val metadata = definition.pack.metadata
            val provisional =
                TrustedKnowledgePackAuthorization(
                    packId = metadata.packId,
                    knowledgePackVersion = metadata.knowledgePackVersion,
                    taxonomyVersion = metadata.taxonomyVersion,
                    searchIndexVersion = metadata.searchIndexVersion,
                    generation = definition.generation,
                    expectedContentFingerprint = "0".repeat(64),
                    productionCutoverEligible = definition.productionCutoverEligible,
                )
            val expectedContentFingerprint =
                KnowledgePackContentFingerprint.compute(
                    definition.pack.toInstallBundle(provisional),
                )
            definition.requireGovernance(
                expectedContentFingerprint = expectedContentFingerprint,
                trustedSigningKeys = BuildVariantTrustedKnowledgePackRegistry.formalSigningKeys,
            )
            TrustedKnowledgePackAuthorization(
                packId = metadata.packId,
                knowledgePackVersion = metadata.knowledgePackVersion,
                taxonomyVersion = metadata.taxonomyVersion,
                searchIndexVersion = metadata.searchIndexVersion,
                generation = definition.generation,
                expectedContentFingerprint = expectedContentFingerprint,
                productionCutoverEligible = definition.productionCutoverEligible,
            )
        }

    private val entries = listOf(historicalSample) + buildVariantEntries

    init {
        check(!historicalSample.productionCutoverEligible) {
            "The migration-only historical sample can never qualify for production cutover"
        }
        check(entries.distinctBy { it.packId to it.knowledgePackVersion }.size == entries.size) {
            "Fixed knowledge-pack registry contains a duplicate content identity"
        }
        check(entries.distinctBy(TrustedKnowledgePackAuthorization::generation).size == entries.size) {
            "Fixed knowledge-pack registry contains a duplicate activation generation"
        }
    }

    fun requireRegistered(pack: ReviewedKnowledgePack): RegisteredKnowledgePack {
        val authorization =
            entries.singleOrNull { candidate ->
                candidate.packId == pack.metadata.packId &&
                    candidate.knowledgePackVersion == pack.metadata.knowledgePackVersion
            } ?: throw SecurityException(
                "Knowledge pack is not present in the fixed trust registry",
            )

        pack.requireAuthorizedMetadata(authorization)
        val bundle = pack.toInstallBundle(authorization)
        authorization.requireMatchingManifest(bundle)
        bundle.validatedSnapshot()
        return RegisteredKnowledgePack(
            authorization = authorization,
            bundle = bundle,
        )
    }

    fun requireHistoricalSample(pack: ReviewedKnowledgePack) {
        val registered = requireRegistered(pack)
        check(registered.authorization === historicalSample) {
            "Only the fixed historical sample may use this consistency check"
        }
    }

    fun isProductionCutoverActivation(
        packId: String,
        knowledgePackVersion: String,
        taxonomyVersion: String,
        searchIndexVersion: String,
        generation: Long,
        contentFingerprint: String,
    ): Boolean =
        entries.any { authorization ->
            authorization.productionCutoverEligible &&
                authorization.packId == packId &&
                authorization.knowledgePackVersion == knowledgePackVersion &&
                authorization.taxonomyVersion == taxonomyVersion &&
                authorization.searchIndexVersion == searchIndexVersion &&
                authorization.generation == generation &&
                authorization.expectedContentFingerprint == contentFingerprint
        }

    fun requireRecoveryActivation(
        packId: String,
        knowledgePackVersion: String,
        taxonomyVersion: String,
        searchIndexVersion: String,
        generation: Long,
        contentFingerprint: String,
        authorizationIdentity: String,
    ) {
        val registered =
            entries.singleOrNull { authorization ->
                authorization.packId == packId &&
                    authorization.knowledgePackVersion == knowledgePackVersion &&
                    authorization.taxonomyVersion == taxonomyVersion &&
                    authorization.searchIndexVersion == searchIndexVersion &&
                    authorization.generation == generation &&
                    authorization.expectedContentFingerprint == contentFingerprint &&
                    authorization.recoveryAuthorizationIdentity == authorizationIdentity
            }
        if (registered == null) {
            throw SecurityException(
                "Pending knowledge-pack activation is not present in the fixed trust registry",
            )
        }
    }
}

internal fun isRegisteredProductionCutoverActivation(
    packId: String,
    knowledgePackVersion: String,
    taxonomyVersion: String,
    searchIndexVersion: String,
    generation: Long,
    contentFingerprint: String,
): Boolean =
    TrustedKnowledgePackRegistry.isProductionCutoverActivation(
        packId = packId,
        knowledgePackVersion = knowledgePackVersion,
        taxonomyVersion = taxonomyVersion,
        searchIndexVersion = searchIndexVersion,
        generation = generation,
        contentFingerprint = contentFingerprint,
    )

internal fun requireRegisteredKnowledgePackRecoveryActivation(
    packId: String,
    knowledgePackVersion: String,
    taxonomyVersion: String,
    searchIndexVersion: String,
    generation: Long,
    contentFingerprint: String,
    authorizationIdentity: String,
) {
    TrustedKnowledgePackRegistry.requireRecoveryActivation(
        packId = packId,
        knowledgePackVersion = knowledgePackVersion,
        taxonomyVersion = taxonomyVersion,
        searchIndexVersion = searchIndexVersion,
        generation = generation,
        contentFingerprint = contentFingerprint,
        authorizationIdentity = authorizationIdentity,
    )
}

internal fun trustedKnowledgePackAuthorizationIdentity(
    packId: String,
    knowledgePackVersion: String,
    taxonomyVersion: String,
    searchIndexVersion: String,
    generation: Long,
    contentFingerprint: String,
): String {
    val canonical =
        listOf(
            "smart-mistake-book/trusted-knowledge-pack-activation/v1",
            packId,
            knowledgePackVersion,
            taxonomyVersion,
            searchIndexVersion,
            generation.toString(),
            contentFingerprint,
        ).joinToString(separator = "\u0000")
    return MessageDigest
        .getInstance("SHA-256")
        .digest(canonical.toByteArray(StandardCharsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
}

private fun ReviewedKnowledgePack.toInstallBundle(
    authorization: TrustedKnowledgePackAuthorization,
): KnowledgePackInstallBundle {
    requireAuthorizedMetadata(authorization)
    val nodeEntities =
        nodes.map { node ->
            KnowledgeNodeEntity(
                knowledgeNodeId = node.knowledgeNodeId,
                stableCode = node.stableCode,
                subject = node.subject.name,
                displayName = node.displayName,
                canonicalName = node.canonicalName,
                nodeKind = node.kind.name,
                granularity = node.granularity.name,
                aliasesText = encodeAliases(node.aliases),
                boundaryMarkdown = node.boundaryMarkdown,
                verificationStatus = node.verificationStatus.name,
                parentKnowledgeNodeId = node.parentKnowledgeNodeId,
                taxonomyVersion = metadata.taxonomyVersion,
                reviewedAtEpochMillis = node.reviewedAtEpochMillis,
            )
        }
    val sourceEntities =
        sources.map { source ->
            KnowledgeSourceEntity(
                sourceId = source.sourceId,
                subject = source.subject.name,
                sourceType = source.sourceType.name,
                title = source.title,
                publisher = source.publisher,
                edition = source.edition,
                sourceUri = source.sourceUri,
                licenseStatus = source.licenseStatus.name,
                contentUsePolicy = source.contentUsePolicy.name,
                contentFingerprint = source.contentFingerprint,
                licenseExpression = source.licenseExpression,
                licenseUri = source.licenseUri,
                attributionText = source.attributionText,
                reviewedAtEpochMillis = source.reviewedAtEpochMillis,
            )
        }
    val searchFeatures = KnowledgeSearchIndexBuilder.build(nodeEntities)
    return KnowledgePackInstallBundle(
        manifest =
            KnowledgePackManifestEntity(
                manifestKey = ACTIVE_MANIFEST_KEY,
                packId = metadata.packId,
                schemaVersion = HIGH_SCHOOL_KNOWLEDGE_DATABASE_VERSION,
                knowledgePackVersion = metadata.knowledgePackVersion,
                taxonomyVersion = metadata.taxonomyVersion,
                searchIndexVersion = metadata.searchIndexVersion,
                contentFingerprint = authorization.expectedContentFingerprint,
                builtAtEpochMillis = metadata.builtAtEpochMillis,
                nodeCount = nodeEntities.size,
                sourceCount = sourceEntities.size,
                relationCount = relations.size,
                materialCount = teachingMaterials.size,
                searchFeatureCount = searchFeatures.size,
            ),
        nodes = nodeEntities,
        sources = sourceEntities,
        nodeSourceBindings =
            nodeSourceBindings.map { binding ->
                KnowledgeNodeSourceBindingEntity(
                    knowledgeNodeId = binding.knowledgeNodeId,
                    sourceId = binding.sourceId,
                    sourceLocator = binding.sourceLocator,
                    derivationNote = binding.derivationNote,
                    reviewedAtEpochMillis = binding.reviewedAtEpochMillis,
                )
            },
        relations =
            relations.map { relation ->
                KnowledgeNodeRelationEntity(
                    relationId = relation.relationId,
                    subject = relation.subject.name,
                    fromKnowledgeNodeId = relation.fromKnowledgeNodeId,
                    toKnowledgeNodeId = relation.toKnowledgeNodeId,
                    relationType = relation.relationType,
                    taxonomyVersion = metadata.taxonomyVersion,
                    sourceId = relation.sourceId,
                    sourceLocator = relation.sourceLocator,
                    reviewedAtEpochMillis = relation.reviewedAtEpochMillis,
                )
            },
        searchFeatures = searchFeatures,
        materials =
            teachingMaterials.map { material ->
                KnowledgeTeachingMaterialEntity(
                    materialId = material.materialId,
                    stableCode = material.stableCode,
                    subject = material.subject.name,
                    materialType = material.materialType.name,
                    title = material.title,
                    summaryMarkdown = material.summaryMarkdown,
                    applicabilityMarkdown = material.applicabilityMarkdown,
                    contentMarkdown = material.contentMarkdown,
                    boundaryMarkdown = material.boundaryMarkdown,
                    derivationKind = material.derivationKind.name,
                    sourceId = material.sourceId,
                    sourceLocator = material.sourceLocator,
                    contentFingerprint = material.contentFingerprint,
                    reviewedAtEpochMillis = material.reviewedAtEpochMillis,
                )
            },
        materialBindings =
            teachingMaterialBindings.map { binding ->
                KnowledgeTeachingMaterialNodeBindingEntity(
                    materialId = binding.materialId,
                    knowledgeNodeId = binding.knowledgeNodeId,
                    role = binding.role.name,
                )
            },
    )
}

private fun ReviewedKnowledgePack.requireAuthorizedMetadata(
    authorization: TrustedKnowledgePackAuthorization,
) {
    if (metadata.packId != authorization.packId) {
        throw SecurityException("Reviewed knowledge-pack id is not authorized")
    }
    if (metadata.knowledgePackVersion != authorization.knowledgePackVersion) {
        throw SecurityException("Reviewed knowledge-pack version is not authorized")
    }
    if (metadata.taxonomyVersion != authorization.taxonomyVersion) {
        throw SecurityException("Reviewed knowledge-pack taxonomy is not authorized")
    }
    if (metadata.searchIndexVersion != authorization.searchIndexVersion) {
        throw SecurityException("Reviewed knowledge-pack search-index version is not authorized")
    }
    metadata.packId.requireProvisionId("Reviewed pack id")
    metadata.knowledgePackVersion.requireProvisionId("Reviewed pack version")
    metadata.taxonomyVersion.requireProvisionId("Reviewed taxonomy version")
    metadata.searchIndexVersion.requireProvisionId("Reviewed search-index version")
    require(metadata.builtAtEpochMillis >= 0L) {
        "Reviewed knowledge-pack build time must not be negative"
    }
}

private fun TrustedKnowledgePackAuthorization.requireMatchingManifest(
    bundle: KnowledgePackInstallBundle,
) {
    val manifest = bundle.manifest
    val fixedFieldsMatch =
        manifest.manifestKey == ACTIVE_MANIFEST_KEY &&
            manifest.schemaVersion == HIGH_SCHOOL_KNOWLEDGE_DATABASE_VERSION &&
            manifest.packId == packId &&
            manifest.knowledgePackVersion == knowledgePackVersion &&
            manifest.taxonomyVersion == taxonomyVersion &&
            manifest.searchIndexVersion == searchIndexVersion &&
            manifest.contentFingerprint == expectedContentFingerprint
    if (!fixedFieldsMatch) {
        throw SecurityException("Knowledge-pack manifest is not authorized")
    }

    // The canonical digest also commits builtAt and every declared count in the manifest.
    val actualFingerprint = KnowledgePackContentFingerprint.compute(bundle)
    if (actualFingerprint != expectedContentFingerprint) {
        throw SecurityException("Knowledge-pack manifest fingerprint is not authorized")
    }
}

private fun TrustedKnowledgePackAuthorization.toActivationAuthorization():
    KnowledgePackActivationAuthorization =
    KnowledgePackActivationAuthorization(
        packId = packId,
        knowledgePackVersion = knowledgePackVersion,
        taxonomyVersion = taxonomyVersion,
        generation = generation,
        expectedContentFingerprint = expectedContentFingerprint,
    )

private fun KnowledgeCatalogActivationReceipt.hasSameContentIdentity(
    authorization: TrustedKnowledgePackAuthorization,
): Boolean =
    packId == authorization.packId &&
        knowledgePackVersion == authorization.knowledgePackVersion &&
        taxonomyVersion == authorization.taxonomyVersion &&
        manifestFingerprint == authorization.expectedContentFingerprint

private fun <T> List<T>.snapshotProvisionList(
    maximumSize: Int,
    label: String,
    transform: (T) -> T = { it },
): List<T> {
    require(size <= maximumSize) { "Reviewed knowledge pack exceeds the $label budget" }
    val snapshot = ArrayList<T>(size)
    forEach { item -> snapshot += transform(item) }
    return Collections.unmodifiableList(snapshot)
}

private fun String.requireProvisionId(label: String) {
    require(isNotBlank() && length <= 160 && none { character -> character.isISOControl() }) {
        "$label is invalid"
    }
}

private fun String.requireProvisionText(
    label: String,
    maximumLength: Int = PROVISION_MAX_TEXT_CHARS,
) {
    require(
        isNotBlank() &&
            length <= maximumLength &&
            none { character ->
                character.isISOControl() && character != '\n' && character != '\t'
            },
    ) {
        "$label is invalid"
    }
}

private fun SubjectKind.requireProvisionSubject(label: String) {
    require(this != SubjectKind.GENERAL) { "$label requires a specific subject" }
}

private val PROVISION_SHA_256 = Regex("[0-9a-f]{64}")
private const val PROVISION_MAX_ALIASES = 12
private const val PROVISION_MAX_ALIAS_CHARS = 4_096
private const val PROVISION_MAX_DISPLAY_CHARS = 4_096
private const val PROVISION_MAX_TEXT_CHARS = 16_384
