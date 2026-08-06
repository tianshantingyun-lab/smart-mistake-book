package com.tingyun.smartmistakebook.core.student.mistake.database

import android.content.Context
import androidx.room3.Room

/**
 * Instrumentation-only database opener.
 *
 * Production code cannot select a database name or obtain the Room database. Tests keep that
 * capability in the androidTest artifact and require a visibly test-only suffix.
 */
internal object StudentMistakeStoreFactory {
    const val TEST_DATABASE_SUFFIX = ".student-mistake-test.db"

    fun openForTest(
        context: Context,
        databaseName: String,
    ): RoomStudentMistakeStore =
        RoomStudentMistakeStore(openDatabaseForTest(context, databaseName))

    fun openDatabaseForTest(
        context: Context,
        databaseName: String,
    ): StudentMistakeRoomDatabase {
        requireTestDatabaseName(databaseName)
        return StudentMistakeDatabaseConfiguration.configure(
            Room.databaseBuilder(
                context.applicationContext,
                StudentMistakeRoomDatabase::class.java,
                databaseName,
            ),
        ).build()
    }

    private fun requireTestDatabaseName(databaseName: String) {
        require(databaseName.endsWith(TEST_DATABASE_SUFFIX)) {
            "Test student-mistake database must use the '$TEST_DATABASE_SUFFIX' suffix"
        }
    }
}

internal fun StudentMistakeMigrationPortFactory.openForTest(
    context: Context,
    databaseName: String,
): StudentMistakeMigrationPort =
    RoomStudentMistakeMigrationPort(
        StudentMistakeStoreFactory.openDatabaseForTest(context, databaseName),
    )

internal fun StudentMistakeCutoverControlPortFactory.openForTest(
    context: Context,
    databaseName: String,
): StudentMistakeCutoverControlPort =
    RoomStudentMistakeCutoverControlPort(
        StudentMistakeStoreFactory.openDatabaseForTest(context, databaseName),
    )

internal fun StudentCutoverDestinationAttestationPortFactory.openForTest(
    context: Context,
    databaseName: String,
): StudentCutoverDestinationAttestationPorts =
    StudentCutoverDestinationAttestationEngine(
        source =
            RoomStudentCutoverDestinationReadSource(
                StudentMistakeStoreFactory.openDatabaseForTest(context, databaseName),
            ),
        nowEpochMillis =
            java.util.function.LongSupplier { System.currentTimeMillis() },
        ownerKey = StudentMistakeOwnerKey.INSTANCE,
    )

internal fun StudentMistakeModelReadPortFactory.openForTest(
    context: Context,
    databaseName: String,
    learnerId: String,
): StudentMistakeModelReadPort =
    StoreBackedStudentMistakeModelReadPort(
        learnerId = learnerId,
        store = StudentMistakeStoreFactory.openForTest(context, databaseName),
    )
