package com.tingyun.smartmistakebook.core.knowledge.database;

import android.database.sqlite.SQLiteDatabase;
import androidx.sqlite.SQLiteConnection;
import androidx.sqlite.SQLiteStatement;
import androidx.sqlite.db.framework.FrameworkSQLiteDatabase;
import androidx.sqlite.driver.SupportSQLiteConnection;
import java.util.Locale;

/**
 * Version-locked bridge from a platform OPEN_READONLY handle to AndroidX's public connection API.
 *
 * <p>The framework database wrapper is public JVM bytecode but Kotlin-internal, so this tiny Java
 * bridge keeps that implementation detail out of the Kotlin catalog surface.
 */
final class ReadOnlyFrameworkConnectionFactory {
    private ReadOnlyFrameworkConnectionFactory() {}

    static SQLiteConnection create(SQLiteDatabase database) {
        return new ReadOnlyRoomConnection(
                new SupportSQLiteConnection(new FrameworkSQLiteDatabase(database)));
    }

    /**
     * Room configures a journal mode whenever it opens a connection. SQLite implements
     * {@code PRAGMA journal_mode = ...} as a file mutation, so a genuine OPEN_READONLY handle
     * correctly rejects it. For this one framework-generated statement, execute the read-only
     * query form instead. All application statements still reach the physical read-only handle.
     */
    private static final class ReadOnlyRoomConnection implements SQLiteConnection {
        private final SQLiteConnection delegate;

        ReadOnlyRoomConnection(SQLiteConnection delegate) {
            this.delegate = delegate;
        }

        @Override
        public SQLiteStatement prepare(String sql) {
            String normalized = sql.trim().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
            if (normalized.startsWith("PRAGMA JOURNAL_MODE") && normalized.contains("=")) {
                return delegate.prepare("PRAGMA journal_mode");
            }
            return delegate.prepare(sql);
        }

        @Override
        public boolean inTransaction() {
            return delegate.inTransaction();
        }

        @Override
        public void close() {
            delegate.close();
        }
    }
}
