package com.tingyun.smartmistakebook.core.mastery.database

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import com.tingyun.smartmistakebook.core.model.LOCAL_LEARNER_ID
import java.util.function.LongSupplier

internal const val LEARNER_MASTERY_TEST_DATABASE_SUFFIX = ".mastery-test.db"

internal object LearnerMasteryStoreFactory {
    fun openForTest(
        context: Context,
        databaseName: String,
        nowEpochMillis: () -> Long,
    ): LearnerMasteryStore =
        RoomLearnerMasteryStore(
            database = LearnerMasteryTestDatabaseFactory.openDatabase(context, databaseName),
            nowEpochMillis = nowEpochMillis,
            reopenDatabase = {
                LearnerMasteryTestDatabaseFactory.openDatabase(context, databaseName)
            },
        )

    fun openDatabaseForTest(
        context: Context,
        databaseName: String,
    ): LearnerMasteryRoomDatabase =
        LearnerMasteryTestDatabaseFactory.openDatabase(context, databaseName)
}

internal fun LearnerMasteryLegacyMigrationPortFactory.openForTest(
    context: Context,
    databaseName: String,
    learnerId: String,
): LearnerMasteryLegacyMigrationPort {
    require(learnerId == LOCAL_LEARNER_ID) {
        "Legacy mastery migration is defined only for the fixed local learner"
    }
    return RoomLearnerMasteryLegacyMigrationPort(
        database = LearnerMasteryTestDatabaseFactory.openDatabase(context, databaseName),
        learnerId = learnerId,
    )
}

internal fun LearnerMasteryCutoverControlPortFactory.openForTest(
    context: Context,
    databaseName: String,
    learnerId: String,
): LearnerMasteryCutoverControlPort {
    require(learnerId == LOCAL_LEARNER_ID) {
        "Learner-mastery cutover is defined only for the device's fixed local learner"
    }
    return RoomLearnerMasteryCutoverControlPort(
        database = LearnerMasteryTestDatabaseFactory.openDatabase(context, databaseName),
        learnerId = learnerId,
    )
}

internal fun LearnerMasteryCutoverDestinationAttestationPortFactory.openForTest(
    context: Context,
    databaseName: String,
    nowEpochMillis: LongSupplier = LongSupplier { System.currentTimeMillis() },
): LearnerMasteryCutoverDestinationAttestationPorts =
    LearnerMasteryCutoverDestinationAttestationEngine(
        source =
            RoomLearnerMasteryCutoverDestinationReadSource(
                LearnerMasteryTestDatabaseFactory.openDatabase(context, databaseName),
            ),
        nowEpochMillis = nowEpochMillis,
        ownerKey = LearnerMasteryOwnerKey.INSTANCE,
    )

private object LearnerMasteryTestDatabaseFactory {
    fun openDatabase(
        context: Context,
        databaseName: String,
    ): LearnerMasteryRoomDatabase {
        requireTestDatabaseName(databaseName)
        return Room.databaseBuilder(
            context.applicationContext,
            LearnerMasteryRoomDatabase::class.java,
            databaseName,
        ).addMigrations(*LEARNER_MASTERY_MIGRATIONS.toTypedArray())
            .addCallback(LEARNER_MASTERY_DATABASE_GUARD_CALLBACK)
            .setDriver(AndroidSQLiteDriver())
            .build()
    }

    private fun requireTestDatabaseName(databaseName: String) {
        require(databaseName.endsWith(LEARNER_MASTERY_TEST_DATABASE_SUFFIX)) {
            "Test mastery database must use the '$LEARNER_MASTERY_TEST_DATABASE_SUFFIX' suffix"
        }
        require(
            databaseName.length <= 160 &&
                ".." !in databaseName &&
                Regex("[A-Za-z0-9][A-Za-z0-9._-]*").matches(databaseName),
        ) {
            "Test mastery database must be a simple file name"
        }
    }
}
