package com.tingyun.smartmistakebook.core.visual.ui

import android.content.Context
import com.google.android.filament.filamat.MaterialBuilder
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal object TutorVisualMaterialRepository {
    private val mutex = Mutex()
    private val filamatInitialized = AtomicBoolean(false)

    suspend fun loadOrCompile(context: Context): ByteBuffer = mutex.withLock {
        val cacheFile = cacheFile(context)
        readCache(cacheFile)?.let { return@withLock it }
        val bytes = withContext(Dispatchers.Default) {
            compileMaterial()
        }
        withContext(Dispatchers.IO) {
            cacheFile.parentFile?.mkdirs()
            val staging = File(cacheFile.parentFile, "${cacheFile.name}.staging")
            staging.outputStream().use { it.write(bytes) }
            runCatching {
                java.nio.file.Files.move(
                    staging.toPath(),
                    cacheFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            }.getOrElse {
                java.nio.file.Files.move(
                    staging.toPath(),
                    cacheFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
        }
        ByteBuffer.wrap(bytes)
    }

    private suspend fun readCache(file: File): ByteBuffer? = withContext(Dispatchers.IO) {
        if (!file.isFile || file.length() !in MIN_PACKAGE_BYTES..MAX_PACKAGE_BYTES) {
            return@withContext null
        }
        ByteBuffer.wrap(file.readBytes())
    }

    private fun compileMaterial(): ByteArray {
        if (filamatInitialized.compareAndSet(false, true)) {
            MaterialBuilder.init()
        }
        val materialPackage = MaterialBuilder()
            .name("SmartMistakeBookVisualUnlit")
            .shading(MaterialBuilder.Shading.UNLIT)
            .uniformParameter(MaterialBuilder.UniformType.FLOAT4, "baseColor")
            .material(
                """
                void material(inout MaterialInputs material) {
                    prepareMaterial(material);
                    material.baseColor = materialParams.baseColor;
                }
                """.trimIndent(),
            )
            .blending(MaterialBuilder.BlendingMode.OPAQUE)
            .culling(MaterialBuilder.CullingMode.NONE)
            .depthWrite(true)
            .depthCulling(true)
            .platform(MaterialBuilder.Platform.MOBILE)
            .targetApi(MaterialBuilder.TargetApi.ALL)
            .optimization(MaterialBuilder.Optimization.SIZE)
            .build()
        require(materialPackage.isValid) { "Filament rejected the local visual material" }
        val buffer = materialPackage.buffer.duplicate()
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        require(bytes.size.toLong() in MIN_PACKAGE_BYTES..MAX_PACKAGE_BYTES)
        return bytes
    }

    private fun cacheFile(context: Context): File =
        File(
            context.noBackupFilesDir,
            "visual-v2/filament-$FILAMENT_VERSION/visual_unlit.filamat",
        )

    private const val FILAMENT_VERSION = "1.71.5"
    private const val MIN_PACKAGE_BYTES = 256L
    private const val MAX_PACKAGE_BYTES = 8L * 1024L * 1024L
}
