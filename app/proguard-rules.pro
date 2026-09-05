# Phase 0 has no reflection-based network or serialization stack, but
# androidx.work instantiates its Room-generated WorkDatabase_Impl
# reflectively during androidx.startup — under R8 full mode the unused
# default constructor is stripped and startup crashes with
# NoSuchMethodException before any UI is shown (observed on emulator,
# 2026-09-06). Room ships consumer rules for app-owned databases; WorkManager's
# bundled database needs this explicit keep.
-keepclassmembers class * extends androidx.room.RoomDatabase {
    <init>();
}
