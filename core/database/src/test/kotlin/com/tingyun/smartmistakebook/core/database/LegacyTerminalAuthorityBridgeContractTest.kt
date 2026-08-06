package com.tingyun.smartmistakebook.core.database

import java.io.File
import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyTerminalAuthorityBridgeContractTest {
    @Test
    fun bridgeIsPackagePrivateAndExposesOnlyTheTwoTerminalPortsAndClose() {
        val bridge =
            Class.forName(
                "com.tingyun.smartmistakebook.core.database.LegacyTerminalAuthorityBridge",
                false,
                javaClass.classLoader,
            )

        assertFalse(Modifier.isPublic(bridge.modifiers))
        assertTrue(AutoCloseable::class.java.isAssignableFrom(bridge))

        val publicMethods =
            bridge.declaredMethods
                .filter { method -> Modifier.isPublic(method.modifiers) && !method.isSynthetic }
                .associate { method -> method.name to method.returnType }
        assertEquals(
            mapOf(
                "cutoverJournal" to LegacyAuthorityCutoverJournalPort::class.java,
                "writeBarrier" to LegacyBusinessWriteBarrierOwnerPort::class.java,
                "close" to Void.TYPE,
            ),
            publicMethods,
        )
        assertTrue(
            publicMethods.values.none { returnType ->
                FORBIDDEN_TYPES.any { forbidden -> forbidden.isAssignableFrom(returnType) }
            },
        )

        val ownerMethods =
            LegacySessionDatabaseOwner::class.java.methods.mapTo(linkedSetOf()) { it.name }
        assertFalse("cutoverJournal" in ownerMethods)
        assertFalse("writeBarrier" in ownerMethods)
        assertFalse("appendStageReceipt" in ownerMethods)
        assertFalse("activateTerminalCutover" in ownerMethods)
    }

    @Test
    fun regularAndTerminalLeasesShareOneCanonicalResource() {
        val opens = AtomicInteger()
        val closes = AtomicInteger()
        val resourceRegistry =
            ReferenceCountedLegacySessionOwnerRegistry<String, Any> {
                closes.incrementAndGet()
            }
        val writerRegistry = ExclusiveLegacyTerminalWriterRegistry<String>()
        val regular =
            resourceRegistry.acquire(CANONICAL_KEY) {
                opens.incrementAndGet()
                Any()
            }
        val regularResource = regular.useResource { it }
        val terminal =
            writerRegistry.acquire(CANONICAL_KEY) {
                resourceRegistry.acquire(CANONICAL_KEY) {
                    opens.incrementAndGet()
                    Any()
                }
            }

        val terminalResource = terminal.resourceLease().useResource { it }
        assertSame(regularResource, terminalResource)
        assertEquals(1, opens.get())

        terminal.close()
        assertEquals(0, closes.get())
        assertSame(regularResource, regular.useResource { it })

        regular.close()
        assertEquals(1, closes.get())
    }

    @Test
    fun terminalWriterIsExclusiveAndFailureCannotStrandItsClaim() {
        val writerRegistry = ExclusiveLegacyTerminalWriterRegistry<String>()
        val first = writerRegistry.acquire(CANONICAL_KEY) { TrackingLease() }

        assertThrows(IllegalStateException::class.java) {
            writerRegistry.acquire(CANONICAL_KEY) { TrackingLease() }
        }

        first.close()
        val afterClose = writerRegistry.acquire(CANONICAL_KEY) { TrackingLease() }
        afterClose.close()

        assertThrows(IllegalArgumentException::class.java) {
            writerRegistry.acquire<TrackingLease>(CANONICAL_KEY) {
                throw IllegalArgumentException("resource open failed")
            }
        }
        writerRegistry.acquire(CANONICAL_KEY) { TrackingLease() }.close()
    }

    @Test
    fun resourceCloseFailureStillReleasesTheExclusiveWriter() {
        val writerRegistry = ExclusiveLegacyTerminalWriterRegistry<String>()
        val failing =
            writerRegistry.acquire(CANONICAL_KEY) {
                TrackingLease(closeFailure = IllegalStateException("close failed"))
            }

        assertThrows(IllegalStateException::class.java) {
            failing.close()
        }

        writerRegistry.acquire(CANONICAL_KEY) { TrackingLease() }.close()
    }

    @Test
    fun sourceUsesOneFixedCanonicalRegistryAndHasNoPublicBridgeFactory() {
        val source =
            File(
                projectRoot(),
                "core/database/src/main/kotlin/com/tingyun/smartmistakebook/core/database/" +
                    "LegacySessionDatabasePort.kt",
            ).readText()

        assertTrue(source.contains("private val productionLegacySessionOwners"))
        assertTrue(source.contains("private fun canonicalLegacyDatabaseTarget(context: Context)"))
        assertTrue(
            source.contains(
                ".getDatabasePath(StudyDatabaseFactory.DEFAULT_DATABASE_NAME)",
            ),
        )
        assertTrue(source.contains("private fun acquireProductionLegacySessionResource("))
        assertEquals(
            3,
            source.windowed("acquireProductionLegacySessionResource(".length)
                .count { it == "acquireProductionLegacySessionResource(" },
        )
        assertTrue(source.contains("private fun openLegacyTerminalAuthorityBridge("))
        assertFalse(source.contains("fun openLegacyTerminalAuthorityBridge(\n    context: Context,\n    database"))
        assertFalse(source.contains("LegacyTerminalAuthorityBridge(\n    database:"))
        assertFalse(source.contains("LegacyTerminalAuthorityBridge(\n    port: StudyDatabasePort"))

        val topLevelClass =
            Class.forName(
                "com.tingyun.smartmistakebook.core.database.LegacySessionDatabasePortKt",
                false,
                javaClass.classLoader,
            )
        val topLevelMethods =
            topLevelClass.methods.mapTo(linkedSetOf()) { method -> method.name }
        assertFalse("openLegacyTerminalAuthorityBridge" in topLevelMethods)
        val hiddenFactory =
            topLevelClass.declaredMethods.single { method ->
                method.name == "openLegacyTerminalAuthorityBridge"
            }
        val bridge =
            Class.forName(
                "com.tingyun.smartmistakebook.core.database.LegacyTerminalAuthorityBridge",
                false,
                javaClass.classLoader,
            )
        assertTrue(Modifier.isPrivate(hiddenFactory.modifiers))
        assertEquals(listOf("android.content.Context"), hiddenFactory.parameterTypes.map { it.name })
        assertSame(bridge, hiddenFactory.returnType)
    }

    private fun projectRoot(): File =
        generateSequence(
            File(requireNotNull(System.getProperty("user.dir"))).canonicalFile,
        ) { directory ->
            directory.parentFile
        }.firstOrNull { directory ->
            File(directory, "settings.gradle.kts").isFile
        } ?: error("Could not locate the project root")

    private class TrackingLease(
        private val closeFailure: RuntimeException? = null,
    ) : AutoCloseable {
        override fun close() {
            closeFailure?.let { throw it }
        }
    }

    private companion object {
        const val CANONICAL_KEY = "app/databases/smart-mistake-book.db"
        val FORBIDDEN_TYPES =
            listOf(
                StudyDatabasePort::class.java,
                StudyDatabase::class.java,
                RoomStudyDatabase::class.java,
            )
    }
}
