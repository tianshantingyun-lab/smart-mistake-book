package com.tingyun.smartmistakebook.core.data.authority

import com.tingyun.smartmistakebook.core.database.LegacyAuthorityCutoverJournalPort
import com.tingyun.smartmistakebook.core.database.LegacyBusinessWriteBarrierOwnerPort
import com.tingyun.smartmistakebook.core.database.StudyDatabasePort
import java.io.File
import java.lang.reflect.Modifier
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductionTerminalMigrationWriterSessionContractTest {
    @Test
    fun terminalJournalLeaseIsPackagePrivateAndDoesNotExposeDatabasePorts() {
        val lease =
            Class.forName(
                "com.tingyun.smartmistakebook.core.database." +
                    "LegacyTerminalCutoverJournalLease",
                false,
                javaClass.classLoader,
            )

        assertFalse(Modifier.isPublic(lease.modifiers))
        val externallyVisibleMethods =
            lease.declaredMethods.filter { method ->
                Modifier.isPublic(method.modifiers) ||
                    Modifier.isProtected(method.modifiers)
            }
        assertTrue(
            externallyVisibleMethods.none { method ->
                FORBIDDEN_TYPES.any { forbidden ->
                    forbidden.isAssignableFrom(method.returnType)
                }
            },
        )
        assertFalse(
            lease.declaredMethods.single { it.name == "journal" }.let { method ->
                Modifier.isPublic(method.modifiers) ||
                    Modifier.isProtected(method.modifiers)
            },
        )
    }

    @Test
    fun productionSessionOwnsTheHiddenLeaseAndCannotFinalizeTheBarrier() {
        val source =
            File(
                projectRoot(),
                "core/data/src/main/kotlin/com/tingyun/smartmistakebook/core/database/" +
                    "ProductionTerminalMigrationWriterSession.kt",
            ).readText()
        val leaseSource =
            File(
                projectRoot(),
                "core/data/src/main/java/com/tingyun/smartmistakebook/core/database/" +
                    "LegacyTerminalCutoverJournalLease.java",
            ).readText()

        assertTrue(source.contains("LegacyTerminalCutoverJournalLease.open(context)"))
        assertTrue(source.contains("writer.migrateAndVerifyTerminalJournal()"))
        assertTrue(source.contains("lease.close()"))
        assertFalse(source.contains("activateTerminalCutover"))
        assertFalse(source.contains("finalizeAfterVerifiedFence"))
        assertFalse(leaseSource.contains("writeBarrier"))
        assertFalse(leaseSource.contains("LegacyBusinessWriteBarrierOwnerPort"))
        assertFalse(leaseSource.contains("StudyDatabase"))
        assertFalse(leaseSource.contains("Room"))
        assertFalse(leaseSource.contains("Dao"))
    }

    private fun projectRoot(): File =
        generateSequence(
            File(requireNotNull(System.getProperty("user.dir"))).canonicalFile,
        ) { directory ->
            directory.parentFile
        }.firstOrNull { directory ->
            File(directory, "settings.gradle.kts").isFile
        } ?: error("Could not locate the project root")

    private companion object {
        val FORBIDDEN_TYPES =
            listOf(
                LegacyAuthorityCutoverJournalPort::class.java,
                LegacyBusinessWriteBarrierOwnerPort::class.java,
                StudyDatabasePort::class.java,
            )
    }
}
