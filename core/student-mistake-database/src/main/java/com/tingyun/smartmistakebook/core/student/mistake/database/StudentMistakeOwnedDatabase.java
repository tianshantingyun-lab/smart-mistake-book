package com.tingyun.smartmistakebook.core.student.mistake.database;

import android.content.Context;
import androidx.room3.Room;
import androidx.room3.RoomDatabase;
import androidx.room3.migration.Migration;
import androidx.sqlite.SQLiteConnection;
import androidx.sqlite.driver.AndroidSQLiteDriver;
import com.tingyun.smartmistakebook.core.model.storage.KnowledgeReferenceProofAuthority;
import kotlin.Unit;
import kotlin.coroutines.Continuation;

/** The sole production opener for {@code student-mistakes.db}. */
final class StudentMistakeOwnedDatabase {
    private StudentMistakeOwnedDatabase() {}

    static RoomStudentMistakeStore openStore(
            Context context,
            KnowledgeReferenceProofAuthority.Verifier knowledgeReferenceVerifier,
            StudentMistakeOwnerKey ownerKey) {
        return new RoomStudentMistakeStore(
                openDatabase(context, ownerKey),
                knowledgeReferenceVerifier);
    }

    static StudentMistakeRoomDatabase openDatabase(
            Context context,
            StudentMistakeOwnerKey ownerKey) {
        requireOwner(ownerKey);
        return StudentMistakeDatabaseConfiguration.configure(
                        Room.databaseBuilder(
                                context.getApplicationContext(),
                                StudentMistakeRoomDatabase.class,
                                StudentMistakeStoreKt.STUDENT_MISTAKE_DATABASE_NAME))
                .build();
    }

    private static void requireOwner(StudentMistakeOwnerKey ownerKey) {
        if (ownerKey != StudentMistakeOwnerKey.INSTANCE) {
            throw new IllegalStateException(
                    "Student-mistake database requires the core:data owner key");
        }
    }
}

/**
 * Shared schema configuration. It accepts an already scoped builder but never chooses a path,
 * opens a database, returns a DAO, or holds an owner key.
 */
final class StudentMistakeDatabaseConfiguration {
    private StudentMistakeDatabaseConfiguration() {}

    static RoomDatabase.Builder<StudentMistakeRoomDatabase> configure(
            RoomDatabase.Builder<StudentMistakeRoomDatabase> builder) {
        return builder
                .addMigrations(migrations())
                .addCallback(
                        StudentOutboxAuthenticityKeyStateKt
                                .getSTUDENT_OUTBOX_AUTHENTICITY_DATABASE_CALLBACK())
                .addCallback(REVIEW_RECEIPT_IMMUTABILITY)
                .addCallback(CUTOVER_IMMUTABILITY)
                .addCallback(PROBLEM_ORGANIZATION_IMMUTABILITY)
                .addCallback(PROBLEM_IDENTITY_RECEIPT_IMMUTABILITY)
                .setDriver(new AndroidSQLiteDriver());
    }

    private static Migration[] migrations() {
        return new Migration[] {
            StudentMistakeMigration1To2Kt.getSTUDENT_MISTAKE_MIGRATION_1_2(),
            StudentMistakeMigration2To3Kt.getSTUDENT_MISTAKE_MIGRATION_2_3(),
            StudentMistakeMigration3To4Kt.getSTUDENT_MISTAKE_MIGRATION_3_4(),
            StudentMistakeMigration4To5Kt.getSTUDENT_MISTAKE_MIGRATION_4_5(),
            StudentMistakeMigration5To6Kt.getSTUDENT_MISTAKE_MIGRATION_5_6(),
            StudentMistakeMigration6To7Kt.getSTUDENT_MISTAKE_MIGRATION_6_7(),
            StudentMistakeMigration7To8Kt.getSTUDENT_MISTAKE_MIGRATION_7_8(),
            StudentMistakeMigration8To9Kt.getSTUDENT_MISTAKE_MIGRATION_8_9(),
            StudentMistakeMigration9To10Kt.getSTUDENT_MISTAKE_MIGRATION_9_10(),
            StudentMistakeMigration10To11Kt.getSTUDENT_MISTAKE_MIGRATION_10_11(),
            StudentMistakeMigration11To12Kt.getSTUDENT_MISTAKE_MIGRATION_11_12(),
            StudentMistakeMigration12To13Kt.getSTUDENT_MISTAKE_MIGRATION_12_13(),
            StudentMistakeMigration13To14Kt.getSTUDENT_MISTAKE_MIGRATION_13_14(),
            StudentMistakeMigration14To15Kt.getSTUDENT_MISTAKE_MIGRATION_14_15(),
            StudentMistakeMigration15To16Kt.getSTUDENT_MISTAKE_MIGRATION_15_16(),
            StudentMistakeMigration16To17Kt.getSTUDENT_MISTAKE_MIGRATION_16_17(),
            StudentMistakeMigration17To18Kt.getSTUDENT_MISTAKE_MIGRATION_17_18(),
            StudentMistakeMigration18To19Kt.getSTUDENT_MISTAKE_MIGRATION_18_19(),
            StudentMistakeMigration19To20Kt.getSTUDENT_MISTAKE_MIGRATION_19_20()
        };
    }

    private static final RoomDatabase.Callback REVIEW_RECEIPT_IMMUTABILITY =
            new RoomDatabase.Callback() {
                @Override
                public Object onCreate(
                        SQLiteConnection connection,
                        Continuation<? super Unit> continuation) {
                    StudentMistakeRoomDatabaseKt
                            .createStudentReviewReceiptImmutabilityTriggers(connection);
                    return Unit.INSTANCE;
                }

                @Override
                public Object onOpen(
                        SQLiteConnection connection,
                        Continuation<? super Unit> continuation) {
                    StudentMistakeRoomDatabaseKt
                            .createStudentReviewReceiptImmutabilityTriggers(connection);
                    StudentMistakeRoomDatabaseKt
                            .verifyStudentReviewReceiptImmutabilityTriggers(connection);
                    return Unit.INSTANCE;
                }
            };

    private static final RoomDatabase.Callback CUTOVER_IMMUTABILITY =
            new RoomDatabase.Callback() {
                @Override
                public Object onCreate(
                        SQLiteConnection connection,
                        Continuation<? super Unit> continuation) {
                    StudentMistakeMigration7To8Kt
                            .createStudentCutoverAndMigrationLedgerImmutabilityTriggers(connection);
                    StudentMistakeMigration8To9Kt
                            .createStudentImportSnapshotImmutabilityTriggers(connection);
                    StudentMistakeMigration15To16Kt
                            .createStudentDestinationReattestationImmutabilityTriggers(connection);
                    return Unit.INSTANCE;
                }

                @Override
                public Object onOpen(
                        SQLiteConnection connection,
                        Continuation<? super Unit> continuation) {
                    StudentMistakeRoomDatabaseKt
                            .verifyStudentCutoverAndMigrationLedgerImmutabilityTriggers(connection);
                    return Unit.INSTANCE;
                }
            };

    private static final RoomDatabase.Callback PROBLEM_ORGANIZATION_IMMUTABILITY =
            new RoomDatabase.Callback() {
                @Override
                public Object onCreate(
                        SQLiteConnection connection,
                        Continuation<? super Unit> continuation) {
                    StudentMistakeMigration9To10Kt
                            .createStudentProblemOrganizationImmutabilityTriggers(connection);
                    return Unit.INSTANCE;
                }

                @Override
                public Object onOpen(
                        SQLiteConnection connection,
                        Continuation<? super Unit> continuation) {
                    StudentMistakeRoomDatabaseKt
                            .verifyStudentProblemOrganizationImmutabilityTriggers(connection);
                    return Unit.INSTANCE;
                }
            };

    private static final RoomDatabase.Callback PROBLEM_IDENTITY_RECEIPT_IMMUTABILITY =
            new RoomDatabase.Callback() {
                @Override
                public Object onCreate(
                        SQLiteConnection connection,
                        Continuation<? super Unit> continuation) {
                    StudentMistakeMigration10To11Kt
                            .createStudentProblemIdentityReceiptImmutabilityTriggers(connection);
                    return Unit.INSTANCE;
                }

                @Override
                public Object onOpen(
                        SQLiteConnection connection,
                        Continuation<? super Unit> continuation) {
                    StudentMistakeRoomDatabaseKt
                            .verifyStudentProblemIdentityReceiptImmutabilityTriggers(connection);
                    return Unit.INSTANCE;
                }
            };
}
