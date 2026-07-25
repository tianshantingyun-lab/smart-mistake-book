package com.tingyun.smartmistakebook.core.data.capture

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID

internal const val BATCH_IMPORT_STAGING_DIRECTORY = "batch_import_staging"
private const val BATCH_IMPORT_FILE_PROVIDER_ROOT = "batch_import_staging"
private const val ORPHAN_GRACE_MILLIS = 60L * 60L * 1_000L
private const val MAX_ORPHAN_SESSIONS_PER_RECONCILIATION = 128
private val SESSION_ID = Regex("[a-f0-9-]{36}")
private val PAGE_FILE_NAME = Regex("page-[0-9]{2}\\.source")

internal fun batchImportProviderAuthority(context: Context): String =
    "${context.packageName}.batch-import.fileprovider"

internal data class StagedBatchImportSources(
    val sourceUris: List<String>,
)

internal interface BatchImportSourceStaging {
    fun stage(sourceUris: List<String>): StagedBatchImportSources

    fun stagePdf(sourceUri: String): StagedBatchImportSources

    fun delete(sourceUri: String): Boolean

    fun reconcileOrphans(referencedSourceUris: Set<String>, nowEpochMillis: Long)

    fun delete(sources: StagedBatchImportSources) {
        sources.sourceUris.forEach(::delete)
    }
}

internal class AndroidBatchImportSourceStaging(
    context: Context,
    private val pdfRenderer: PdfBatchImportPageRenderer = AndroidPdfBatchImportPageRenderer(
        context.applicationContext.contentResolver,
    ),
) : BatchImportSourceStaging {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver
    private val authority = batchImportProviderAuthority(appContext)
    private val stagingRoot = File(appContext.filesDir, BATCH_IMPORT_STAGING_DIRECTORY)
        .canonicalFile

    init {
        check(stagingRoot.parentFile == appContext.filesDir.canonicalFile)
    }

    override fun stage(sourceUris: List<String>): StagedBatchImportSources {
        require(sourceUris.isNotEmpty())
        require(sourceUris.all(String::isNotBlank))
        ensureDirectory(stagingRoot)
        val session = File(stagingRoot, UUID.randomUUID().toString()).canonicalFile
        check(session.parentFile == stagingRoot)
        ensureDirectory(session)
        val stagedUris = ArrayList<String>(sourceUris.size)
        var batchBytes = 0L
        try {
            sourceUris.forEachIndexed { index, sourceUri ->
                val source = Uri.parse(sourceUri)
                require(source.scheme == ContentResolver.SCHEME_CONTENT) {
                    "Batch import only accepts local content URIs"
                }
                val destination = File(session, "page-${index.toString().padStart(2, '0')}.source")
                    .canonicalFile
                check(destination.parentFile == session)
                val temporary = File.createTempFile("copy-", ".partial", session).canonicalFile
                check(temporary.parentFile == session)
                val copied = try {
                    val input = resolver.openInputStream(source)
                        ?: throw IOException("Batch import source could not be opened")
                    input.use { sourceStream ->
                        FileOutputStream(temporary).use { destinationStream ->
                            copyBoundedSource(
                                input = sourceStream,
                                output = destinationStream,
                                sourceLimit = MAX_CANONICAL_SOURCE_INPUT_BYTES,
                                batchLimit = MAX_BATCH_IMPORT_STAGED_BYTES - batchBytes,
                            )
                        }
                    }
                } catch (failure: Exception) {
                    temporary.delete()
                    throw failure
                }
                if (copied == 0L) {
                    temporary.delete()
                    throw IOException("Batch import source was empty")
                }
                check(temporary.renameTo(destination)) {
                    "Batch import source could not be committed to private staging"
                }
                batchBytes += copied
                stagedUris += FileProvider.getUriForFile(
                    appContext,
                    authority,
                    destination,
                ).toString()
            }
        } catch (failure: Exception) {
            deleteSession(session)
            throw failure
        }
        return StagedBatchImportSources(stagedUris)
    }

    override fun stagePdf(sourceUri: String): StagedBatchImportSources {
        require(sourceUri.isNotBlank())
        val source = Uri.parse(sourceUri)
        require(source.scheme == ContentResolver.SCHEME_CONTENT) {
            "PDF import only accepts a local content URI"
        }
        ensureDirectory(stagingRoot)
        val session = File(stagingRoot, UUID.randomUUID().toString()).canonicalFile
        check(session.parentFile == stagingRoot)
        ensureDirectory(session)
        try {
            val stagedUris = pdfRenderer.render(source, session).map { renderedPage ->
                val ownedPage = renderedPage.canonicalFile
                check(
                    ownedPage.parentFile == session &&
                        ownedPage.isFile &&
                        PAGE_FILE_NAME.matches(ownedPage.name),
                ) { "PDF renderer returned an unsafe page" }
                FileProvider.getUriForFile(appContext, authority, ownedPage).toString()
            }
            return StagedBatchImportSources(stagedUris)
        } catch (failure: Exception) {
            deleteSession(session)
            throw failure
        }
    }

    override fun delete(sourceUri: String): Boolean {
        val uri = runCatching { Uri.parse(sourceUri) }.getOrNull() ?: return false
        if (uri.scheme != ContentResolver.SCHEME_CONTENT || uri.authority != authority) return false
        val segments = uri.pathSegments
        if (
            segments.size != 3 ||
            segments[0] != BATCH_IMPORT_FILE_PROVIDER_ROOT ||
            !SESSION_ID.matches(segments[1]) ||
            !PAGE_FILE_NAME.matches(segments[2])
        ) {
            return false
        }
        val session = File(stagingRoot, segments[1]).canonicalFile
        if (session.parentFile != stagingRoot) return false
        val source = File(session, segments[2]).canonicalFile
        if (source.parentFile != session) return false
        val deleted = !source.exists() || source.isFile && source.delete()
        if (deleted && session.isDirectory && session.listFiles().orEmpty().isEmpty()) {
            session.delete()
        }
        return deleted
    }

    override fun reconcileOrphans(
        referencedSourceUris: Set<String>,
        nowEpochMillis: Long,
    ) {
        require(nowEpochMillis >= 0)
        if (!stagingRoot.isDirectory) return
        val referencedSessions = referencedSourceUris.mapNotNullTo(mutableSetOf()) { sourceUri ->
            ownedSessionId(sourceUri)
        }
        val cutoff = if (nowEpochMillis > ORPHAN_GRACE_MILLIS) {
            nowEpochMillis - ORPHAN_GRACE_MILLIS
        } else {
            0L
        }
        stagingRoot.listFiles().orEmpty().asSequence()
            .filter { session ->
                session.isDirectory &&
                    SESSION_ID.matches(session.name) &&
                    session.name !in referencedSessions
            }
            .sortedBy(File::lastModified)
            .take(MAX_ORPHAN_SESSIONS_PER_RECONCILIATION)
            .filter { session -> session.lastModified() in 1..cutoff }
            .forEach(::deleteSession)
    }

    private fun ensureDirectory(directory: File) {
        check(directory.isDirectory || directory.mkdirs() || directory.isDirectory) {
            "Batch import staging directory could not be created"
        }
    }

    private fun ownedSessionId(sourceUri: String): String? {
        val uri = runCatching { Uri.parse(sourceUri) }.getOrNull() ?: return null
        val segments = uri.pathSegments
        return segments.getOrNull(1)?.takeIf { sessionId ->
            uri.scheme == ContentResolver.SCHEME_CONTENT &&
                uri.authority == authority &&
                segments.size == 3 &&
                segments[0] == BATCH_IMPORT_FILE_PROVIDER_ROOT &&
                SESSION_ID.matches(sessionId) &&
                PAGE_FILE_NAME.matches(segments[2])
        }
    }

    private fun deleteSession(session: File) {
        if (session.parentFile != stagingRoot || !session.isDirectory) return
        session.listFiles().orEmpty().forEach { child ->
            val owned = child.canonicalFile
            if (owned.parentFile == session && owned.isFile) owned.delete()
        }
        if (session.listFiles().orEmpty().isEmpty()) session.delete()
    }
}
