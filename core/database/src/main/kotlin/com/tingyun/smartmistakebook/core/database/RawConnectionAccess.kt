package com.tingyun.smartmistakebook.core.database

import android.annotation.SuppressLint
import androidx.room3.RoomDatabase
import androidx.room3.Transactor

/**
 * The one place this app is allowed to reach Room's raw connection.
 *
 * `RoomDatabase.useConnection` is restricted to Room's own library group: it is
 * deliberately outside the public surface, so a Room upgrade may change or
 * remove it without notice. The app needs it anyway — `PRAGMA
 * wal_checkpoint(TRUNCATE)`, `PRAGMA foreign_keys`, `VACUUM INTO` and the
 * integrity pragmas have no DAO/query equivalent, and the backup and delete
 * paths cannot be built without them.
 *
 * Why a wrapper instead of an annotation per call site: the restriction is a
 * forward-compatibility risk that deserves exactly one reviewed decision. A
 * suppression at each of the call sites would multiply the unreviewed spots,
 * and — worse — a file- or class-level suppression would also silently legalize
 * a *different* restricted API that someone adds to those files later. Routing
 * every call through here keeps the exposure to this function so the compiler's
 * "restricted" signal keeps working everywhere else.
 *
 * If Room exposes a supported raw-connection API, this function is the single
 * place that changes.
 */
@SuppressLint("RestrictedApi")
internal suspend fun <R> RoomDatabase.withRawConnection(
    isReadOnly: Boolean,
    block: suspend (Transactor) -> R,
): R = useConnection(isReadOnly = isReadOnly, block = block)
