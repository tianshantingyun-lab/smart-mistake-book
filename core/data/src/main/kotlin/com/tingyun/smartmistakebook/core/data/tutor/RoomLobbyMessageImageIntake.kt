package com.tingyun.smartmistakebook.core.data.tutor

import android.content.Context
import android.net.Uri
import com.tingyun.smartmistakebook.core.data.capture.AndroidBatchImportSourceStaging
import com.tingyun.smartmistakebook.core.data.capture.AndroidCanonicalAssetVault
import com.tingyun.smartmistakebook.core.data.capture.batchImportProviderAuthority
import com.tingyun.smartmistakebook.core.database.StudyDatabasePort
import com.tingyun.smartmistakebook.core.database.StudyDbValue
import com.tingyun.smartmistakebook.core.domain.LobbyMessageImage
import com.tingyun.smartmistakebook.core.domain.LobbyMessageImageIntake
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 消息附图摄取：相机拍照与相册选择都统一落到规范资产库。
 *
 * - 相机路径给出的是本应用 FileProvider 的 URI，vault 可直接导入；
 * - 相册/系统选择器给出的是外部 content URI，先经 batch staging 有界拷贝到
 *   应用私有目录并换发 FileProvider URI，再进 vault（字节与图片链同源校验）。
 */
internal class RoomLobbyMessageImageIntake(
    context: Context,
    private val database: StudyDatabasePort,
) : LobbyMessageImageIntake {
    private val appContext = context.applicationContext
    private val vault = AndroidCanonicalAssetVault(appContext)
    private val staging = AndroidBatchImportSourceStaging(appContext)

    override suspend fun registerImage(
        localUri: String,
        occurredAtEpochMillis: Long,
    ): LobbyMessageImage = withContext(Dispatchers.IO) {
        val importableUri = if (isVaultImportable(localUri)) {
            localUri
        } else {
            stagedUri(localUri)
        }
        val record = vault.import(
            localUri = importableUri,
            sourceType = StudyDbValue.SourceAssetType.PHOTO_PICKER,
            createdAtEpochMillis = occurredAtEpochMillis,
        )
        database.registerCanonicalSourceAsset(record)
        LobbyMessageImage(
            assetId = record.sourceAssetId,
            sha256 = record.contentSha256,
            byteSize = record.byteSize,
            width = record.width,
            height = record.height,
        )
    }

    override suspend fun resolveImageUri(assetId: String): String? = withContext(Dispatchers.IO) {
        val record = database.readCanonicalSourceAsset(assetId) ?: return@withContext null
        val file = runCatching { vault.resolve(record) }.getOrNull() ?: return@withContext null
        if (file.isFile) "file://${file.absolutePath}" else null
    }

    override suspend fun describeImage(assetId: String): LobbyMessageImage? =
        withContext(Dispatchers.IO) {
            val record = database.readCanonicalSourceAsset(assetId) ?: return@withContext null
            LobbyMessageImage(
                assetId = record.sourceAssetId,
                sha256 = record.contentSha256,
                byteSize = record.byteSize,
                width = record.width,
                height = record.height,
            )
        }

    private fun isVaultImportable(localUri: String): Boolean {
        val uri = Uri.parse(localUri)
        return uri.scheme == "content" &&
            (
                uri.authority == "${appContext.packageName}.capture.fileprovider" ||
                    uri.authority == batchImportProviderAuthority(appContext)
                )
    }

    private fun stagedUri(localUri: String): String {
        val staged = staging.stage(listOf(localUri))
        return staged.sourceUris.singleOrNull()
            ?: throw IllegalArgumentException("Lobby image could not be staged")
    }
}

object LobbyMessageImageIntakeFactory {
    fun create(context: Context, database: StudyDatabasePort): LobbyMessageImageIntake =
        RoomLobbyMessageImageIntake(context, database)
}
