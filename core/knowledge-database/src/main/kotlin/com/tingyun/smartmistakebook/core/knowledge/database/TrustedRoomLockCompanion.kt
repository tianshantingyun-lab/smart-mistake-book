package com.tingyun.smartmistakebook.core.knowledge.database

import android.os.Process
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import java.io.Closeable
import java.io.File
import java.io.FileDescriptor

/**
 * Pins Room's sibling `.lck` file so Room cannot silently reuse an unsafe path entry.
 *
 * Room owns the advisory byte-range lock; this object owns only the companion file's identity.
 * Keeping one descriptor per open Room database lets concurrent databases reuse the same regular
 * inode without either database unlinking the other's lock path.
 */
internal class TrustedRoomLockCompanion private constructor(
    private val lockFile: File,
    private val descriptor: FileDescriptor,
    private val identity: RoomLockIdentity,
) : Closeable {
    private var closed = false

    fun requireAtPath() {
        synchronized(ROOM_LOCK_MONITOR) {
            check(!closed) { "Room lock companion is closed" }
            val pathStat = inspectPath(lockFile)
                ?: error("Room lock companion '${lockFile.name}' disappeared")
            val descriptorStat = Os.fstat(descriptor)
            requireTrustedRoomLockStat(pathStat, lockFile)
            requireTrustedRoomLockStat(descriptorStat, lockFile)
            check(RoomLockIdentity.from(pathStat) == identity) {
                "Room lock companion '${lockFile.name}' changed at its path"
            }
            check(RoomLockIdentity.from(descriptorStat) == identity) {
                "Room lock companion '${lockFile.name}' changed after it was opened"
            }
        }
    }

    override fun close() {
        synchronized(ROOM_LOCK_MONITOR) {
            if (closed) return
            closed = true
            Os.close(descriptor)
            val registration = ACTIVE_ROOM_LOCKS[lockFile.absolutePath]
                ?: error("Room lock companion registration was lost")
            check(registration.identity == identity && registration.openCount > 0) {
                "Room lock companion registration changed"
            }
            if (registration.openCount == 1) {
                ACTIVE_ROOM_LOCKS.remove(lockFile.absolutePath)
            } else {
                registration.openCount -= 1
            }
        }
    }

    companion object {
        fun open(databaseFile: File): TrustedRoomLockCompanion =
            synchronized(ROOM_LOCK_MONITOR) {
                val parent = requireNotNull(databaseFile.absoluteFile.parentFile) {
                    "Room database has no parent directory"
                }
                ensureTrustedDatabaseDirectory(parent)
                val lockFile = roomLockCompanionFile(databaseFile)
                removeSymlinkOnlyIfPresent(lockFile, parent)
                val descriptor = openOrCreateTrustedLock(lockFile, parent)
                try {
                    val descriptorStat = Os.fstat(descriptor)
                    val pathStat = inspectPath(lockFile)
                        ?: error("Room lock companion '${lockFile.name}' disappeared")
                    requireTrustedRoomLockStat(descriptorStat, lockFile)
                    requireTrustedRoomLockStat(pathStat, lockFile)
                    val identity = RoomLockIdentity.from(descriptorStat)
                    check(RoomLockIdentity.from(pathStat) == identity) {
                        "Room lock companion '${lockFile.name}' changed while opening"
                    }
                    val registration = ACTIVE_ROOM_LOCKS[lockFile.absolutePath]
                    if (registration == null) {
                        ACTIVE_ROOM_LOCKS[lockFile.absolutePath] =
                            ActiveRoomLock(identity = identity, openCount = 1)
                    } else {
                        check(registration.identity == identity) {
                            "A live Room database pins a different lock companion"
                        }
                        registration.openCount += 1
                    }
                    TrustedRoomLockCompanion(lockFile, descriptor, identity)
                } catch (failure: Throwable) {
                    Os.close(descriptor)
                    throw failure
                }
            }
    }
}

internal fun deleteTrustedRoomLockCompanionIfPresent(databaseFile: File) {
    synchronized(ROOM_LOCK_MONITOR) {
        val lockFile = roomLockCompanionFile(databaseFile)
        check(ACTIVE_ROOM_LOCKS[lockFile.absolutePath] == null) {
            "Cannot remove a Room lock companion while its database is open"
        }
        val stat = inspectPath(lockFile) ?: return
        when {
            OsConstants.S_ISLNK(stat.st_mode) -> Os.remove(lockFile.absolutePath)
            OsConstants.S_ISREG(stat.st_mode) -> {
                requireOwnedSingleLinkRegularRoomLock(stat, lockFile)
                Os.remove(lockFile.absolutePath)
            }
            else -> error("Room lock companion '${lockFile.name}' is not a removable file")
        }
        check(inspectPath(lockFile) == null) {
            "Unable to remove Room lock companion '${lockFile.name}'"
        }
        syncRoomLockDirectory(requireNotNull(lockFile.parentFile))
    }
}

internal fun roomLockCompanionFile(databaseFile: File): File = File(databaseFile.path + ".lck")

private fun openOrCreateTrustedLock(lockFile: File, parent: File): FileDescriptor {
    val existing = inspectPath(lockFile)
    if (existing != null) {
        check(OsConstants.S_ISREG(existing.st_mode)) {
            "Room lock companion '${lockFile.name}' must be a regular file"
        }
        check(existing.st_uid == Process.myUid() && existing.st_nlink == 1L) {
            "Room lock companion '${lockFile.name}' is not exclusively app-owned"
        }
        val descriptor =
            Os.open(
                lockFile.absolutePath,
                OsConstants.O_RDWR or OsConstants.O_CLOEXEC or OsConstants.O_NOFOLLOW,
                0,
            )
        try {
            val descriptorStat = Os.fstat(descriptor)
            requireOwnedSingleLinkRegularRoomLock(descriptorStat, lockFile)
            check(RoomLockIdentity.from(descriptorStat) == RoomLockIdentity.from(existing)) {
                "Room lock companion '${lockFile.name}' changed while opening"
            }
            Os.fchmod(descriptor, TRUSTED_ROOM_LOCK_MODE)
            requireTrustedRoomLockStat(Os.fstat(descriptor), lockFile)
            Os.fsync(descriptor)
            return descriptor
        } catch (failure: Throwable) {
            Os.close(descriptor)
            throw failure
        }
    }

    val descriptor =
        try {
            Os.open(
                lockFile.absolutePath,
                OsConstants.O_RDWR or
                    OsConstants.O_CREAT or
                    OsConstants.O_EXCL or
                    OsConstants.O_CLOEXEC or
                    OsConstants.O_NOFOLLOW,
                TRUSTED_ROOM_LOCK_MODE,
            )
        } catch (failure: ErrnoException) {
            if (failure.errno == OsConstants.EEXIST) {
                return openOrCreateTrustedLock(lockFile, parent)
            }
            throw failure
        }
    try {
        Os.fchmod(descriptor, TRUSTED_ROOM_LOCK_MODE)
        requireTrustedRoomLockStat(Os.fstat(descriptor), lockFile)
        Os.fsync(descriptor)
        syncRoomLockDirectory(parent)
        return descriptor
    } catch (failure: Throwable) {
        Os.close(descriptor)
        throw failure
    }
}

private fun removeSymlinkOnlyIfPresent(lockFile: File, parent: File) {
    val stat = inspectPath(lockFile) ?: return
    if (!OsConstants.S_ISLNK(stat.st_mode)) return
    Os.remove(lockFile.absolutePath)
    check(inspectPath(lockFile) == null) {
        "Unable to remove unsafe Room lock symlink '${lockFile.name}'"
    }
    syncRoomLockDirectory(parent)
}

private fun ensureTrustedDatabaseDirectory(directory: File) {
    if (inspectPath(directory) == null) {
        check(directory.mkdirs()) { "Unable to create Room database directory" }
    }
    val stat = Os.lstat(directory.absolutePath)
    check(OsConstants.S_ISDIR(stat.st_mode) && stat.st_uid == Process.myUid()) {
        "Room database directory must be app-owned"
    }
}

private fun requireTrustedRoomLockStat(stat: StructStat, lockFile: File) {
    requireOwnedSingleLinkRegularRoomLock(stat, lockFile)
    check(stat.st_mode and UNIX_PERMISSION_BITS == TRUSTED_ROOM_LOCK_MODE) {
        "Room lock companion '${lockFile.name}' must use owner-only read/write permissions"
    }
}

private fun requireOwnedSingleLinkRegularRoomLock(stat: StructStat, lockFile: File) {
    check(
        isOwnedSingleLinkRegularRoomLockMetadata(
            isRegularFile = OsConstants.S_ISREG(stat.st_mode),
            ownerUid = stat.st_uid,
            expectedOwnerUid = Process.myUid(),
            linkCount = stat.st_nlink,
        ),
    ) {
        "Room lock companion '${lockFile.name}' must be an app-owned single-link regular file"
    }
}

internal fun isOwnedSingleLinkRegularRoomLockMetadata(
    isRegularFile: Boolean,
    ownerUid: Int,
    expectedOwnerUid: Int,
    linkCount: Long,
): Boolean =
    isRegularFile && ownerUid == expectedOwnerUid && linkCount == 1L

private fun inspectPath(file: File): StructStat? =
    try {
        Os.lstat(file.absolutePath)
    } catch (failure: ErrnoException) {
        if (failure.errno == OsConstants.ENOENT) null else throw failure
    }

private fun syncRoomLockDirectory(directory: File) {
    val descriptor = Os.open(directory.absolutePath, OsConstants.O_RDONLY or OsConstants.O_CLOEXEC, 0)
    try {
        Os.fsync(descriptor)
    } finally {
        Os.close(descriptor)
    }
}

private data class RoomLockIdentity(
    val device: Long,
    val inode: Long,
) {
    companion object {
        fun from(stat: StructStat): RoomLockIdentity = RoomLockIdentity(stat.st_dev, stat.st_ino)
    }
}

private data class ActiveRoomLock(
    val identity: RoomLockIdentity,
    var openCount: Int,
)

private val ROOM_LOCK_MONITOR = Any()
private val ACTIVE_ROOM_LOCKS = mutableMapOf<String, ActiveRoomLock>()
private val TRUSTED_ROOM_LOCK_MODE = OsConstants.S_IRUSR or OsConstants.S_IWUSR
private const val UNIX_PERMISSION_BITS = 0x1ff
