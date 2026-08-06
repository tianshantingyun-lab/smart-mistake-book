package com.tingyun.smartmistakebook.core.database

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProblemOrganizationKnowledgeAuthorityBoundaryTest {
    @Test
    fun organizationPersistenceCannotReachTheEmbeddedLegacyCatalog() {
        val root = projectRoot()
        val organizationStore =
            root.source(
                "core/database/src/main/kotlin/com/tingyun/smartmistakebook/" +
                    "core/database/RoomProblemOrganizationStore.kt",
            )
        val organizationDao =
            root.source(
                "core/database/src/main/kotlin/com/tingyun/smartmistakebook/" +
                    "core/database/dao/ProblemOrganizationDao.kt",
            )
        val combined = organizationStore + organizationDao

        assertFalse(
            "Organization persistence regained a legacy knowledge DAO capability",
            "legacyKnowledgeCatalogDao" in combined,
        )
        assertFalse(
            "Organization persistence regained a legacy knowledge entity capability",
            "KnowledgeNodeEntity" in combined,
        )
        assertFalse(
            "Organization persistence can seed legacy knowledge nodes",
            "insertKnowledgeNodes" in combined,
        )
        assertFalse(
            "Organization persistence can read legacy knowledge nodes",
            Regex("""\breadKnowledgeNode\s*\(""").containsMatchIn(combined),
        )
        assertFalse(
            "Organization persistence queries the embedded legacy knowledge_node table",
            LEGACY_KNOWLEDGE_TABLE_SQL.containsMatchIn(combined),
        )
    }

    @Test
    fun organizationCompositionUsesOnlyTypedKnowledgeCatalogContracts() {
        val root = projectRoot()
        val organizationRepository =
            root.source(
                "core/data/src/main/kotlin/com/tingyun/smartmistakebook/" +
                    "core/data/mistake/RoomMistakeOrganizationRepository.kt",
            )
        val reviewedContext =
            root.source(
                "core/data/src/main/kotlin/com/tingyun/smartmistakebook/" +
                    "core/data/knowledge/ReviewedProblemKnowledgeContextRepository.kt",
            )

        assertFalse(
            "Organization confirmation must not construct legacy knowledge rows",
            "KnowledgeNodeSeedRecord" in organizationRepository,
        )
        assertFalse(
            "Organization composition must not call legacy knowledge recall",
            LEGACY_KNOWLEDGE_READS.any(organizationRepository::contains),
        )
        assertTrue(
            "Confirmation must request proof through the typed reviewed-context contract",
            "verifyConfirmationReferences" in organizationRepository,
        )
        assertTrue(
            "The reviewed-context adapter must depend on the independent catalog contract",
            "HighSchoolKnowledgeCatalog" in reviewedContext,
        )
        assertFalse(
            "The reviewed-context adapter must not reach a Room DAO or database implementation",
            RAW_KNOWLEDGE_DATABASE_CAPABILITIES.any(reviewedContext::contains),
        )
    }

    private fun projectRoot(): File =
        generateSequence(
            File(requireNotNull(System.getProperty("user.dir"))).canonicalFile,
        ) { directory ->
            directory.parentFile
        }.firstOrNull { directory ->
            File(directory, "settings.gradle.kts").isFile
        } ?: error("Could not locate the project root")

    private fun File.source(relativePath: String): String =
        File(this, relativePath).also { source ->
            check(source.isFile) { "Missing boundary source: $relativePath" }
        }.readText()

    private companion object {
        val LEGACY_KNOWLEDGE_TABLE_SQL =
            Regex("""(?i)\b(?:FROM|JOIN|INTO|UPDATE)\s+`?knowledge_node`?\b""")
        val LEGACY_KNOWLEDGE_READS =
            setOf(
                "readSubjectKnowledgeNodes",
                "readSubjectKnowledgeRecallCandidates",
                "readKnowledgeNodesByIds",
                "readKnowledgeNodeRelationsForDependents",
            )
        val RAW_KNOWLEDGE_DATABASE_CAPABILITIES =
            setOf(
                "HighSchoolKnowledgeDatabase",
                "KnowledgeCatalogDao",
                "SQLiteConnection",
                "RoomDatabase",
                "ATTACH DATABASE",
            )
    }
}
