package com.tingyun.smartmistakebook.core.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LegacySessionDatabaseOwnerInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val databaseName = StudyDatabaseFactory.DEFAULT_DATABASE_NAME

    @Test
    fun duplicateOpenReturnsIndependentLeasesAndLastCloseCreatesANewResource() {
        context.deleteDatabase(databaseName)
        val first = LegacySessionDatabaseOwnerFactory.open(context)
        val second = LegacySessionDatabaseOwnerFactory.open(context)

        try {
            assertNotSame(first, second)
            val firstCapability = first.tutorSessions()
            val secondCapability = second.tutorSessions()
            assertSame(firstCapability, secondCapability)

            first.close()
            assertSame(secondCapability, second.tutorSessions())

            second.close()
            val reopened = LegacySessionDatabaseOwnerFactory.open(context)
            try {
                assertNotSame(secondCapability, reopened.tutorSessions())
            } finally {
                reopened.close()
            }
        } finally {
            first.close()
            second.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun forgedOnOpenTriggerFailsBeforeOwnerReturnAndDoesNotPoisonRegistry() {
        context.deleteDatabase(databaseName)
        LegacyStudyDatabaseTestFactory.open(context, databaseName).close()
        forgeOneBarrierTrigger()

        var ownerReturned = false
        val failure =
            runCatching {
                LegacySessionDatabaseOwnerFactory.open(context).use {
                    ownerReturned = true
                }
            }.exceptionOrNull()

        try {
            assertFalse("Owner returned before Room onOpen validation", ownerReturned)
            assertNotNull(failure)

            context.deleteDatabase(databaseName)
            LegacySessionDatabaseOwnerFactory.open(context).close()
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    private fun forgeOneBarrierTrigger() {
        val definition =
            LegacyBusinessWriteBarrierSchema.triggerDefinitions().values.first()
        SQLiteDatabase.openDatabase(
            context.getDatabasePath(databaseName).path,
            null,
            SQLiteDatabase.OPEN_READWRITE,
        ).use { sqlite ->
            sqlite.execSQL("DROP TRIGGER `${definition.name}`")
            sqlite.execSQL(
                definition.sql.replace(
                    LEGACY_BUSINESS_WRITE_REJECTED_MESSAGE,
                    "forged legacy barrier message",
                ),
            )
        }
    }
}
