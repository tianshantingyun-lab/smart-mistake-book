package com.tingyun.smartmistakebook.core.database

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import kotlinx.coroutines.runBlocking

/**
 * Debug/test-only bridge for compatibility and migration tests.
 *
 * Custom database names and broad handles do not exist in the release artifact.
 */
object LegacyStudyDatabaseTestFactory {
    fun open(
        context: Context,
        databaseName: String = StudyDatabaseFactory.DEFAULT_DATABASE_NAME,
    ): StudyDatabasePort =
        openDebugPersistentDatabase(
            context = context,
            databaseName = databaseName,
            autoActivateFreshEmpty = true,
            clock = System::currentTimeMillis,
        )

    fun openPreCutoverForTest(
        context: Context,
        databaseName: String,
    ): StudyDatabasePort =
        openDebugPersistentDatabase(
            context = context,
            databaseName = databaseName,
            autoActivateFreshEmpty = false,
            clock = System::currentTimeMillis,
        )

    fun openInMemory(
        context: Context,
        clock: () -> Long = System::currentTimeMillis,
    ): StudyDatabasePort =
        openDebugInMemoryDatabase(context, clock)
}

/**
 * Same-package compatibility extensions keep database-module tests concise while ensuring these
 * broad entry points are absent from release bytecode.
 */
internal fun StudyDatabaseFactory.open(
    context: Context,
    databaseName: String = StudyDatabaseFactory.DEFAULT_DATABASE_NAME,
): StudyDatabasePort =
    LegacyStudyDatabaseTestFactory.open(context, databaseName)

internal fun StudyDatabaseFactory.openPreCutoverForTest(
    context: Context,
    databaseName: String,
    freeResponseOutboxCipher: TutorFreeResponseOutboxCipher =
        AndroidKeystoreTutorFreeResponseOutboxCipher(),
    clock: () -> Long = System::currentTimeMillis,
): RoomStudyDatabase =
    openDebugPersistentDatabase(
        context = context,
        databaseName = databaseName,
        autoActivateFreshEmpty = false,
        clock = clock,
        freeResponseOutboxCipher = freeResponseOutboxCipher,
    )

internal fun StudyDatabaseFactory.openPreCutoverForTest(
    context: Context,
    databaseName: String,
    clock: () -> Long,
): RoomStudyDatabase =
    openPreCutoverForTest(
        context = context,
        databaseName = databaseName,
        freeResponseOutboxCipher = AndroidKeystoreTutorFreeResponseOutboxCipher(),
        clock = clock,
    )

internal fun StudyDatabaseFactory.openInMemory(
    context: Context,
    freeResponseOutboxCipher: TutorFreeResponseOutboxCipher =
        AndroidKeystoreTutorFreeResponseOutboxCipher(),
    clock: () -> Long = System::currentTimeMillis,
    freeResponseOutboxExpirySchedulerFactory: TutorFreeResponseOutboxExpirySchedulerFactory =
        CoroutineTutorFreeResponseOutboxExpirySchedulerFactory,
): RoomStudyDatabase =
    openDebugInMemoryDatabase(
        context,
        clock,
        freeResponseOutboxCipher,
        freeResponseOutboxExpirySchedulerFactory,
    )

internal fun StudyDatabaseFactory.openInMemory(
    context: Context,
    clock: () -> Long,
): RoomStudyDatabase =
    openInMemory(
        context = context,
        freeResponseOutboxCipher = AndroidKeystoreTutorFreeResponseOutboxCipher(),
        clock = clock,
    )

internal fun StudyDatabaseFactory.authorizeTutorSession(
    database: StudyDatabasePort,
): TrustedTutorSessionDatabaseCapability {
    require(database is RoomStudyDatabase) {
        "Tutor session coordination requires a debug database opened by the test factory"
    }
    return RoomTrustedTutorSessionDatabaseCapability(database)
}

internal fun StudyDatabaseFactory.authorizeLegacyBusinessWriteBarrier(
    database: StudyDatabasePort,
): LegacyBusinessWriteBarrierCapability {
    require(database is RoomStudyDatabase) {
        "Legacy business write barrier requires a debug database opened by the test factory"
    }
    return RoomLegacyBusinessWriteBarrierCapability(database)
}

private fun openDebugPersistentDatabase(
    context: Context,
    databaseName: String,
    autoActivateFreshEmpty: Boolean,
    clock: () -> Long,
    freeResponseOutboxCipher: TutorFreeResponseOutboxCipher =
        AndroidKeystoreTutorFreeResponseOutboxCipher(),
): RoomStudyDatabase {
    requireSimpleDebugDatabaseName(databaseName)
    val applicationContext = context.applicationContext ?: context
    var preOpen = inspectStudyDatabaseBeforeRoomOpen(applicationContext, databaseName)
    if (preOpen.hygienePending) {
        runTutorFreeResponseMigrationHygieneExclusive(applicationContext, databaseName)
        preOpen = inspectStudyDatabaseBeforeRoomOpen(applicationContext, databaseName)
    }
    val requiresPostMigrationHygiene =
        preOpen.version in 1 until STUDY_DATABASE_VERSION

    fun buildDatabase() =
        Room.databaseBuilder(
            applicationContext,
            StudyDatabase::class.java,
            databaseName,
        ).addMigrations(*legacyStudyDatabaseMigrations().toTypedArray())
            .addCallback(
                LegacyBusinessWriteBarrierCreateCallback(
                    autoActivateFreshEmpty = autoActivateFreshEmpty,
                    clock = clock,
                ),
            )
            .setDriver(AndroidSQLiteDriver())
            .build()
    var database = buildDatabase()
    try {
        runBlocking { database.useConnection(isReadOnly = false) { Unit } }
        if (requiresPostMigrationHygiene) {
            database.close()
            runTutorFreeResponseMigrationHygieneExclusive(applicationContext, databaseName)
            database = buildDatabase()
        }
        return eagerlyVerifyDebugDatabase(database, clock, freeResponseOutboxCipher)
    } catch (failure: Throwable) {
        try {
            database.close()
        } catch (closeFailure: Throwable) {
            failure.addSuppressed(closeFailure)
        }
        throw failure
    }
}

private fun openDebugInMemoryDatabase(
    context: Context,
    clock: () -> Long,
    freeResponseOutboxCipher: TutorFreeResponseOutboxCipher =
        AndroidKeystoreTutorFreeResponseOutboxCipher(),
    freeResponseOutboxExpirySchedulerFactory: TutorFreeResponseOutboxExpirySchedulerFactory =
        CoroutineTutorFreeResponseOutboxExpirySchedulerFactory,
): RoomStudyDatabase {
    val applicationContext = context.applicationContext ?: context
    val database =
        Room.inMemoryDatabaseBuilder(
            applicationContext,
            StudyDatabase::class.java,
        ).addCallback(
            LegacyBusinessWriteBarrierCreateCallback(
                autoActivateFreshEmpty = false,
                clock = clock,
            ),
        )
            .setDriver(AndroidSQLiteDriver())
            .build()
    return eagerlyVerifyDebugDatabase(
        database,
        clock,
        freeResponseOutboxCipher,
        freeResponseOutboxExpirySchedulerFactory,
    )
}

private fun eagerlyVerifyDebugDatabase(
    database: StudyDatabase,
    clock: () -> Long,
    freeResponseOutboxCipher: TutorFreeResponseOutboxCipher,
    freeResponseOutboxExpirySchedulerFactory: TutorFreeResponseOutboxExpirySchedulerFactory =
        CoroutineTutorFreeResponseOutboxExpirySchedulerFactory,
): RoomStudyDatabase {
    try {
        runBlocking {
            database.useConnection(isReadOnly = false) { Unit }
        }
        return RoomStudyDatabase(
            database = database,
            clock = clock,
            freeResponseOutboxCipher = freeResponseOutboxCipher,
            freeResponseOutboxExpirySchedulerFactory =
                freeResponseOutboxExpirySchedulerFactory,
        )
    } catch (failure: Throwable) {
        try {
            database.close()
        } catch (closeFailure: Throwable) {
            failure.addSuppressed(closeFailure)
        }
        throw failure
    }
}

private fun requireSimpleDebugDatabaseName(databaseName: String) {
    require(
        databaseName.isNotBlank() &&
            databaseName == databaseName.trim() &&
            '/' !in databaseName &&
            '\\' !in databaseName &&
            databaseName != "." &&
            databaseName != "..",
    ) { "Debug database name must be a simple app-private file name" }
}
