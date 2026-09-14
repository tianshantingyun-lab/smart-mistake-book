package com.tingyun.smartmistakebook.core.domain

/**
 * A student-selected lobby message image after it has been registered as a
 * canonical asset: the ids/hashes the egress manifest needs to prove scope.
 */
data class LobbyMessageImage(
    val assetId: String,
    val sha256: String,
    val byteSize: Long,
    val width: Int,
    val height: Int,
) {
    init {
        require(assetId.isNotBlank()) { "Lobby message image id must not be blank" }
        require(sha256.length == 64 && sha256.all { it in "0123456789abcdef" }) {
            "Lobby message image hash must be a lowercase SHA-256 value"
        }
        require(byteSize > 0) { "Lobby message image byte size must be positive" }
        require(width > 0 && height > 0) { "Lobby message image dimensions must be positive" }
    }
}

/**
 * Registers a photo the student picked (camera capture or gallery) as a
 * canonical source asset so it can be attached to a lobby message: the bytes
 * land in the private vault, the ledger row is registered, and the returned
 * reference carries the exact sha256/dimensions the manifest must match.
 */
interface LobbyMessageImageIntake {
    suspend fun registerImage(localUri: String, occurredAtEpochMillis: Long): LobbyMessageImage

    /** 把已登记的资产解析为可渲染的本地 file URI；资产缺失或被改动时返回 null。 */
    suspend fun resolveImageUri(assetId: String): String?
}
