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

    /**
     * 读回**已登记**资产的元数据（不复读字节、不新建资产）。
     *
     * 消灭的失败："重新发送"与"追问带上文图片"都需要为一张**已经属于历史消息**的图片
     * 重建 egress 授权范围，而授权要的是 sha256/字节数/尺寸 —— 此前只有"从本地 URI 注册
     * 新图"一条路，于是重发只能丢图（学生看到的就是失败后重发没有图），或者干脆重打一遍。
     * 资产缺失或被改动时返回 null：调用方按"这张图不再出网"处理，而不是让整条消息发不出去。
     */
    suspend fun describeImage(assetId: String): LobbyMessageImage?
}
