package com.tingyun.smartmistakebook.core.data.authority

import android.content.Context
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KnowledgeDatabaseActivationWitnessReaderContractTest {
    @Test
    fun adapterHasOnlyTheNarrowReadCapability() {
        assertTrue(
            KnowledgeActivationWitnessReader::class.java.isAssignableFrom(
                KnowledgeDatabaseActivationWitnessReader::class.java,
            ),
        )
        assertEquals(
            listOf(Context::class.java),
            KnowledgeDatabaseActivationWitnessReader::class.java.declaredConstructors
                .single()
                .parameterTypes
                .toList(),
        )
        val exposedTypes =
            KnowledgeDatabaseActivationWitnessReader::class.java.declaredMethods.flatMap { method ->
                method.parameterTypes.toList() + method.returnType
            } +
                KnowledgeDatabaseActivationWitnessReader::class.java.declaredFields.map { field ->
                    field.type
                }
        assertTrue(
            exposedTypes.none { type ->
                type.name.contains("room", ignoreCase = true) ||
                    type.name.contains("sqlite", ignoreCase = true) ||
                    type.simpleName.contains("Dao")
            },
        )
        assertTrue(
            KnowledgeDatabaseActivationWitnessReader::class.java.declaredMethods.none { method ->
                method.name.contains("write", ignoreCase = true) ||
                    method.name.contains("install", ignoreCase = true) ||
                    method.name.contains("open", ignoreCase = true)
            },
        )
    }

    @Test
    fun adapterAlwaysUsesTheCanonicalCutoverWitnessFactory() {
        val source =
            File(
                projectRoot(),
                "core/data/src/main/kotlin/com/tingyun/smartmistakebook/core/data/authority/" +
                    "KnowledgeDatabaseActivationWitnessReader.kt",
            ).readText()

        assertTrue(".readFreshProductionActivation()" in source)
        assertTrue("KnowledgeActivationWitness.create(" in source)
        assertTrue("KnowledgeCatalogActivationReceipt" !in source)
        assertTrue("SQLiteDatabase" !in source)
        assertTrue("RoomDatabase" !in source)
    }

    private fun projectRoot(): File {
        var current = File(System.getProperty("user.dir")).canonicalFile
        repeat(8) {
            if (File(current, "settings.gradle.kts").isFile) return current
            current = current.parentFile ?: return@repeat
        }
        error("Project root was not found")
    }
}
