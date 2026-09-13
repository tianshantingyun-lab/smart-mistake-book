package com.tingyun.smartmistakebook.core.data.capture

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.core.content.FileProvider
import com.tingyun.smartmistakebook.core.database.StudyDatabaseFactory
import com.tingyun.smartmistakebook.core.database.StudyDatabasePort
import com.tingyun.smartmistakebook.core.database.StudyDbValue
import com.tingyun.smartmistakebook.core.data.model.ModelTaskRepositoryFactory
import com.tingyun.smartmistakebook.core.data.splitimport.SplitImportRepositoryFactory
import com.tingyun.smartmistakebook.core.domain.BatchImportJob
import com.tingyun.smartmistakebook.core.domain.BatchImportPageStatus
import com.tingyun.smartmistakebook.core.domain.BatchImportStatus
import com.tingyun.smartmistakebook.core.domain.CaptureDraftImportRequest
import com.tingyun.smartmistakebook.core.domain.CaptureEntryOrigin
import com.tingyun.smartmistakebook.core.domain.CaptureInputSource
import com.tingyun.smartmistakebook.core.domain.CreateBatchImportRequest
import com.tingyun.smartmistakebook.core.domain.CreatePdfImportRequest
import com.tingyun.smartmistakebook.core.domain.ModelGateway
import com.tingyun.smartmistakebook.core.model.CaptureAssessment
import com.tingyun.smartmistakebook.core.model.CaptureAssessmentDecision
import com.tingyun.smartmistakebook.core.model.CaptureAssessmentInput
import com.tingyun.smartmistakebook.core.model.CaptureAssessmentOutput
import com.tingyun.smartmistakebook.core.model.CapturePageRelation
import com.tingyun.smartmistakebook.core.model.ModelExecutionLocation
import com.tingyun.smartmistakebook.core.model.ModelGatewayEvent
import com.tingyun.smartmistakebook.core.model.ModelGatewayExecution
import com.tingyun.smartmistakebook.core.model.ModelTaskKind
import com.tingyun.smartmistakebook.core.model.ProviderCapabilitySnapshot
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.system.measureTimeMillis
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BatchImportRepositoryInstrumentedTest {
    private lateinit var context: Context
    private lateinit var database: StudyDatabasePort
    private lateinit var databaseName: String
    private val externalUris = mutableListOf<Uri>()
    private val privateSourceFiles = mutableListOf<File>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        databaseName = "batch-import-repository-${System.nanoTime()}.db"
        context.deleteDatabase(databaseName)
        clearOwnedDirectory(File(context.filesDir, BATCH_IMPORT_STAGING_DIRECTORY))
        clearOwnedDirectory(File(context.filesDir, "source-assets"))
        database = StudyDatabaseFactory.open(context, databaseName)
    }

    @After
    fun tearDown() {
        runCatching { database.close() }
        context.deleteDatabase(databaseName)
        externalUris.forEach { uri -> runCatching { context.contentResolver.delete(uri, null, null) } }
        privateSourceFiles.forEach { file -> runCatching { file.delete() } }
        clearOwnedDirectory(File(context.filesDir, BATCH_IMPORT_STAGING_DIRECTORY))
        clearOwnedDirectory(File(context.filesDir, "source-assets"))
    }

    @Test
    fun externalContentUrisAreStagedBeforeTheDurableJobAndSurviveRebuild() = runBlocking {
        val selected = listOf(insertImage(), insertImage())
        val directFailure = runCatching {
            captureRepository(database).importDraft(
                CaptureDraftImportRequest(
                    requestId = "untrusted-direct-media-store",
                    localUri = selected.first().toString(),
                    source = CaptureInputSource.PHOTO_PICKER,
                    origin = CaptureEntryOrigin.LIBRARY,
                    occurredAtEpochMillis = 900,
                ),
            )
        }.exceptionOrNull()
        assertTrue(directFailure is IllegalArgumentException)
        val stoppedScope = CoroutineScope(SupervisorJob().also { it.cancel() } + Dispatchers.Default)
        val initialRepository = BatchImportRepositoryFactory.create(
            context = context,
            database = database,
            capture = captureRepository(database),
            processingScope = stoppedScope,
        )
        val created = initialRepository.createBatchImport(
            CreateBatchImportRequest(
                requestId = "external-rebuild",
                localUris = selected.map(Uri::toString),
                occurredAtEpochMillis = 1_000,
            ),
        )
        val persisted = checkNotNull(database.readBatchImportJob(created.jobId))
        assertTrue(
            persisted.pages.all { page ->
                Uri.parse(page.sourceUri).authority == batchImportProviderAuthority(context)
            },
        )
        assertTrue(
            context.contentResolver.persistedUriPermissions.none { permission ->
                permission.uri in selected
            },
        )

        database.close()
        selected.forEach { uri -> assertEquals(1, context.contentResolver.delete(uri, null, null)) }
        externalUris.removeAll(selected.toSet())
        database = StudyDatabaseFactory.open(context, databaseName)
        val processingScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val rebuiltRepository = BatchImportRepositoryFactory.create(
                context = context,
                database = database,
                capture = captureRepository(database),
                processingScope = processingScope,
            )
            val completed = withTimeout(15_000) {
                rebuiltRepository.observeBatchImports().first { jobs ->
                    jobs.firstOrNull()?.status == BatchImportStatus.COMPLETED
                }.first()
            }

            assertEquals(2, completed.readyCount)
            assertTrue(stagedFiles().isEmpty())
        } finally {
            processingScope.cancel()
        }
    }

    @Test
    fun failedSourceIsRetainedUntilThePageIsSkipped() = runBlocking {
        val selected = listOf(insertImage(), insertInvalidImage())
        val processingScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val repository = BatchImportRepositoryFactory.create(
                context = context,
                database = database,
                capture = captureRepository(database),
                processingScope = processingScope,
            )
            val created = repository.createBatchImport(
                CreateBatchImportRequest(
                    requestId = "failed-retention",
                    localUris = selected.map(Uri::toString),
                    occurredAtEpochMillis = 2_000,
                ),
            )
            val failed = withTimeout(15_000) {
                repository.observeBatchImports().first { jobs ->
                    jobs.firstOrNull()?.let { job ->
                        job.status == BatchImportStatus.COMPLETED && job.failedCount == 1
                    } == true
                }.first()
            }

            assertEquals(1, stagedFiles().size)
            val failedPage = failed.pages.single { it.status == BatchImportPageStatus.FAILED }
            repository.skipBatchImportPage(created.jobId, failedPage.pageIndex)
            assertTrue(stagedFiles().isEmpty())
        } finally {
            processingScope.cancel()
        }
    }

    @Test
    fun concurrentRequestReplayKeepsOnlyTheCommittedStagingSession() = runBlocking {
        val selected = listOf(insertImage(), insertImage())
        val stoppedScope = CoroutineScope(SupervisorJob().also { it.cancel() } + Dispatchers.Default)
        val repository = BatchImportRepositoryFactory.create(
            context = context,
            database = database,
            capture = captureRepository(database),
            processingScope = stoppedScope,
        )
        val request = CreateBatchImportRequest(
            requestId = "concurrent-replay",
            localUris = selected.map(Uri::toString),
            occurredAtEpochMillis = 3_000,
        )

        val jobs = listOf(
            async(Dispatchers.IO) { repository.createBatchImport(request) },
            async(Dispatchers.IO) { repository.createBatchImport(request) },
        ).awaitAll()

        assertEquals(1, jobs.map { it.jobId }.distinct().size)
        val root = File(context.filesDir, BATCH_IMPORT_STAGING_DIRECTORY).canonicalFile
        val persisted = checkNotNull(database.readBatchImportJob(jobs.first().jobId))
        val staged = stagedFiles()
        val persistedFiles = persisted.pages.map { page ->
            val uri = Uri.parse(page.sourceUri)
            assertEquals(batchImportProviderAuthority(context), uri.authority)
            root.resolve(uri.pathSegments[1]).resolve(uri.pathSegments[2]).canonicalFile
        }

        assertEquals(2, persisted.pages.size)
        assertEquals(2, staged.size)
        assertEquals(1, root.listFiles().orEmpty().count(File::isDirectory))
        assertTrue(staged.none { file -> file.name.endsWith(".partial") })
        assertTrue(persistedFiles.all(File::isFile))
        assertEquals(
            staged.map(File::getCanonicalPath).sorted(),
            persistedFiles.map(File::getCanonicalPath).sorted(),
        )
    }

    @Test
    fun stagingRejectsContentAboveTheCanonicalVaultInputLimit() {
        val oversized = insertBinaryContent(MAX_CANONICAL_SOURCE_INPUT_BYTES + 1)
        val staging = AndroidBatchImportSourceStaging(context)

        val failure = runCatching {
            staging.stage(listOf(oversized.toString()))
        }.exceptionOrNull()

        assertTrue(failure is IOException)
        assertTrue(stagedFiles().isEmpty())
    }

    @Test
    fun startupRemovesOldOrphansWithoutDeletingAFreshUncommittedSession() = runBlocking {
        val selected = List(4) { insertImage() }
        val staging = AndroidBatchImportSourceStaging(context)
        val oldOrphan = staging.stage(selected.take(2).map(Uri::toString))
        val freshSession = staging.stage(selected.drop(2).map(Uri::toString))
        assertTrue(sessionDirectory(oldOrphan).setLastModified(1))
        val processingScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            BatchImportRepositoryFactory.create(
                context = context,
                database = database,
                capture = captureRepository(database),
                processingScope = processingScope,
            )
            withTimeout(5_000) {
                while (stagedFiles(oldOrphan).any(File::exists)) delay(20)
            }

            assertTrue(stagedFiles(oldOrphan).none(File::exists))
            assertTrue(stagedFiles(freshSession).all(File::isFile))
        } finally {
            staging.delete(freshSession)
            processingScope.cancel()
        }
    }

    @Test
    fun pdfIsPrivatelyRenderedByPageAndUsesTheExistingRecoverablePipeline() = runBlocking {
        val source = createPdf(pageCount = 3)
        val processingScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val repository = BatchImportRepositoryFactory.create(
                context = context,
                database = database,
                capture = captureRepository(database),
                processingScope = processingScope,
            )

            var completed: BatchImportJob? = null
            val elapsedMillis = measureTimeMillis {
                repository.createPdfImport(
                    CreatePdfImportRequest(
                        requestId = "pdf-import",
                        localUri = source.toString(),
                        occurredAtEpochMillis = 4_000,
                    ),
                )
                completed = withTimeout(20_000) {
                    repository.observeBatchImports().first { jobs ->
                        jobs.firstOrNull()?.status == BatchImportStatus.COMPLETED
                    }.first()
                }
            }
            val completedJob = checkNotNull(completed)

            assertEquals(3, completedJob.readyCount)
            assertTrue(stagedFiles().isEmpty())
            val assets = File(context.filesDir, "source-assets").walkTopDown()
                .filter(File::isFile)
                .toList()
            assertEquals(3, assets.size)
            assets.forEach { asset ->
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(asset.path, bounds)
                assertTrue(bounds.outWidth > 0 && bounds.outHeight > 0)
                assertEquals(2_200, maxOf(bounds.outWidth, bounds.outHeight))
            }
            println("pdf-import benchmark: 3 pages = ${elapsedMillis}ms")
        } finally {
            processingScope.cancel()
        }
    }

    @Test
    fun pdfAboveThePageBudgetIsRejectedWithoutLeavingPrivateStaging() {
        val source = createPdf(pageCount = 31)

        val failure = runCatching {
            AndroidBatchImportSourceStaging(context).stagePdf(source.toString())
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertTrue(stagedFiles().isEmpty())
    }

    @Test
    fun explicitOrganizationMergesOnlyAModelConfirmedContinuation() = runBlocking {
        val selected = listOf(
            insertImage(Color.WHITE),
            insertImage(Color.LTGRAY),
            insertImage(Color.YELLOW),
            insertImage(Color.CYAN),
        )
        val processingScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val gateway = PageRelationGateway(CapturePageRelation.SAME_QUESTION)
            val modelTasks = ModelTaskRepositoryFactory.create(
                database = database,
                gateway = gateway,
            )
            val repository = BatchImportRepositoryFactory.create(
                context = context,
                database = database,
                capture = captureRepository(database),
                processingScope = processingScope,
                modelTasks = modelTasks,
                modelEgressAllowed = { true },
            )
            val created = repository.createBatchImport(
                CreateBatchImportRequest(
                    requestId = "confirmed-continuation",
                    localUris = selected.map(Uri::toString),
                    occurredAtEpochMillis = 5_000,
                ),
            )
            val completed = withTimeout(15_000) {
                repository.observeBatchImports().first { jobs ->
                    jobs.firstOrNull()?.status == BatchImportStatus.COMPLETED
                }.first()
            }
            val originalDraftIds = completed.pages.map { checkNotNull(it.draftId) }

            val organizationMillis = measureTimeMillis {
                repository.organizeBatch(created.jobId)
            }

            val organized = withTimeout(5_000) {
                repository.observeBatchImports().first { jobs ->
                    jobs.firstOrNull()?.remainingBoundaryCount == 0
                }.first()
            }
            assertEquals(1, organized.questionCount)
            assertEquals(1, organized.pages.mapNotNull { it.draftId }.distinct().size)
            val merged = checkNotNull(database.readProblemDraft(organized.pages.first().draftId!!))
            assertEquals(4, merged.sourceAssets.size)
            originalDraftIds.drop(1).forEach { draftId ->
                assertEquals(
                    StudyDbValue.ProblemDraftStatus.ABANDONED,
                    checkNotNull(database.readProblemDraft(draftId)).status,
                )
            }
            assertNull(database.readPendingCaptureDraft(merged.draftId)?.latestAssessmentTask)
            assertEquals(1, gateway.executionCount)
            assertTrue("Four-page organization took ${organizationMillis}ms", organizationMillis < 2_000)
            println(
                "batch-page-organization benchmark: 4 pages, 1 model request = " +
                    "${organizationMillis}ms",
            )
        } finally {
            processingScope.cancel()
        }
    }

    @Test
    fun uncertainPageRelationKeepsBothOriginalDrafts() = runBlocking {
        val selected = listOf(insertImage(Color.YELLOW), insertImage(Color.CYAN))
        val processingScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val modelTasks = ModelTaskRepositoryFactory.create(
                database = database,
                gateway = PageRelationGateway(CapturePageRelation.UNSURE),
            )
            val repository = BatchImportRepositoryFactory.create(
                context = context,
                database = database,
                capture = captureRepository(database),
                processingScope = processingScope,
                modelTasks = modelTasks,
                modelEgressAllowed = { true },
            )
            val created = repository.createBatchImport(
                CreateBatchImportRequest(
                    requestId = "uncertain-continuation",
                    localUris = selected.map(Uri::toString),
                    occurredAtEpochMillis = 6_000,
                ),
            )
            val completed = withTimeout(15_000) {
                repository.observeBatchImports().first { jobs ->
                    jobs.firstOrNull()?.status == BatchImportStatus.COMPLETED
                }.first()
            }
            repository.organizeBatch(created.jobId)

            val organized = checkNotNull(database.readBatchImportJob(created.jobId))
            assertEquals(2, organized.pages.mapNotNull { it.resultDraftId }.distinct().size)
            completed.pages.mapNotNull { it.draftId }.forEach { draftId ->
                assertEquals(
                    StudyDbValue.ProblemDraftStatus.EDITING,
                    checkNotNull(database.readProblemDraft(draftId)).status,
                )
            }
        } finally {
            processingScope.cancel()
        }
    }

    private fun captureRepository(store: StudyDatabasePort) = RoomCaptureWorkflowRepository(
        store,
        AndroidCanonicalAssetVault(context),
        LocalQuestionTextRecognizer { _, _, _ ->
            LocalTextRecognition(emptyList(), "fixture-no-text-v1")
        },
    )

    private fun insertImage(color: Int = Color.WHITE): Uri = insertMediaStoreImage { stream ->
        val bitmap = Bitmap.createBitmap(72, 96, Bitmap.Config.ARGB_8888).apply {
            eraseColor(color)
        }
        try {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
        } finally {
            bitmap.recycle()
        }
    }

    private class PageRelationGateway(
        private val relation: CapturePageRelation,
    ) : ModelGateway {
        var executionCount: Int = 0
            private set

        override suspend fun capabilities(): ProviderCapabilitySnapshot = CAPABILITIES

        override fun execute(execution: ModelGatewayExecution) = flow {
            executionCount += 1
            emit(ModelGatewayEvent.Started(CAPABILITIES))
            emit(
                ModelGatewayEvent.Completed(
                    CaptureAssessmentOutput(
                        CaptureAssessment(
                            decision = CaptureAssessmentDecision.PASS,
                            issues = emptyList(),
                            suggestedActions = emptyList(),
                            modelVersion = "fixture/page-relation-v1",
                            followingPageRelations =
                                List(
                                    (execution.request.input as CaptureAssessmentInput)
                                        .followingSourceAssets.size,
                                ) { relation },
                        ),
                    ),
                ),
            )
        }

        private companion object {
            val CAPABILITIES = ProviderCapabilitySnapshot(
                providerId = "fixture-page-relation",
                providerDisplayName = "测试模型",
                modelId = "fixture-v1",
                supportedTasks = setOf(ModelTaskKind.CAPTURE_ASSESS),
                supportsImageInput = true,
                supportsStructuredOutput = true,
                supportsStreaming = false,
                executionLocation = ModelExecutionLocation.EXTERNAL_PROVIDER,
                providerConfigurationVersion = "fixture-config-v1",
            )
        }
    }

    private fun createPdf(pageCount: Int): Uri {
        val directory = File(context.cacheDir, "captured_images").apply {
            check(isDirectory || mkdirs() || isDirectory)
        }
        val file = File(directory, "batch-${System.nanoTime()}.pdf").canonicalFile
        check(file.parentFile == directory.canonicalFile)
        privateSourceFiles += file
        val document = PdfDocument()
        try {
            repeat(pageCount) { pageIndex ->
                val page = document.startPage(
                    PdfDocument.PageInfo.Builder(595, 842, pageIndex + 1).create(),
                )
                page.canvas.drawColor(Color.WHITE)
                page.canvas.drawText(
                    "Question page ${pageIndex + 1}",
                    48f,
                    96f,
                    Paint().apply {
                        color = Color.BLACK
                        textSize = 28f
                    },
                )
                document.finishPage(page)
            }
            FileOutputStream(file).use(document::writeTo)
        } finally {
            document.close()
        }
        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.capture.fileprovider",
            file,
        )
    }

    private fun insertInvalidImage(): Uri = insertMediaStoreImage { stream ->
        stream.write("not-an-image".toByteArray())
    }

    private fun insertBinaryContent(byteCount: Long): Uri = insertMediaStoreImage { stream ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var remaining = byteCount
        while (remaining > 0) {
            val count = minOf(buffer.size.toLong(), remaining).toInt()
            stream.write(buffer, 0, count)
            remaining -= count
        }
    }

    private fun insertMediaStoreImage(write: (java.io.OutputStream) -> Unit): Uri {
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "batch-${System.nanoTime()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/SmartMistakeBookTest/")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val uri = checkNotNull(context.contentResolver.insert(collection, values))
        externalUris += uri
        checkNotNull(context.contentResolver.openOutputStream(uri)).use(write)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.contentResolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                null,
                null,
            )
        }
        return uri
    }

    private fun stagedFiles(): List<File> {
        val root = File(context.filesDir, BATCH_IMPORT_STAGING_DIRECTORY)
        return root.walkTopDown().filter(File::isFile).toList()
    }

    private fun stagedFiles(sources: StagedBatchImportSources): List<File> =
        sources.sourceUris.map { sourceUri ->
            val uri = Uri.parse(sourceUri)
            sessionDirectory(sources).resolve(uri.pathSegments[2]).canonicalFile
        }

    private fun sessionDirectory(sources: StagedBatchImportSources): File {
        val uri = Uri.parse(sources.sourceUris.first())
        assertEquals(batchImportProviderAuthority(context), uri.authority)
        val root = File(context.filesDir, BATCH_IMPORT_STAGING_DIRECTORY).canonicalFile
        return root.resolve(uri.pathSegments[1]).canonicalFile.also { session ->
            check(session.parentFile == root)
        }
    }

    /**
     * Regression for the P0 where the production wiring (which always supplies a
     * split recognizer) made every batch page throw before import: with the
     * default unavailable model provider, split recognition must degrade and
     * every page must still land in the library (spec batch-intake I1).
     */
    @Test
    fun splitRecognitionDegradesWhenTheModelIsUnavailableAndEveryPageStillImports() = runBlocking {
        val selected = listOf(insertImage(), insertImage())
        val processingScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val repository = BatchImportRepositoryFactory.create(
                context = context,
                database = database,
                capture = captureRepository(database),
                processingScope = processingScope,
                splitImports = SplitImportRepositoryFactory.createConcrete(database),
            )
            val created = repository.createBatchImport(
                CreateBatchImportRequest(
                    requestId = "split-degrades",
                    localUris = selected.map(Uri::toString),
                    occurredAtEpochMillis = 1_000,
                ),
            )
            val settled = withTimeout(20_000) {
                repository.observeBatchImports().first { jobs ->
                    jobs.singleOrNull { it.jobId == created.jobId }?.let { job ->
                        job.status == BatchImportStatus.COMPLETED ||
                            job.pages.all { page ->
                                page.status == BatchImportPageStatus.READY ||
                                    page.status == BatchImportPageStatus.FAILED
                            }
                    } == true
                }.single { it.jobId == created.jobId }
            }
            assertEquals(
                "Split recognition must never fail a page: " + settled.pages.map { it.status },
                2,
                settled.pages.count { it.status == BatchImportPageStatus.READY },
            )
        } finally {
            processingScope.cancel()
        }
    }

    /**
     * A configured model is the single gate for agent rounds: with none configured, an
     * external split round is never attempted, so the page import stays clean and
     * no guaranteed-failure model task is written per page.
     */
    @Test
    fun splitRecognitionSkipsExternalEgressWithoutAConfiguredModelAndImportsEveryPage() = runBlocking {
        val selected = listOf(insertImage(), insertImage())
        val processingScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val gateway = PageRelationGateway(CapturePageRelation.SAME_QUESTION)
            val repository = BatchImportRepositoryFactory.create(
                context = context,
                database = database,
                capture = captureRepository(database),
                processingScope = processingScope,
                modelTasks = ModelTaskRepositoryFactory.create(
                    database = database,
                    gateway = gateway,
                ),
                splitImports = SplitImportRepositoryFactory.createConcrete(database),
                modelEgressAllowed = { false },
            )
            val created = repository.createBatchImport(
                CreateBatchImportRequest(
                    requestId = "split-no-model",
                    localUris = selected.map(Uri::toString),
                    occurredAtEpochMillis = 9_000,
                ),
            )
            val settled = withTimeout(20_000) {
                repository.observeBatchImports().first { jobs ->
                    jobs.singleOrNull { it.jobId == created.jobId }?.let { job ->
                        job.status == BatchImportStatus.COMPLETED ||
                            job.pages.all { page ->
                                page.status == BatchImportPageStatus.READY ||
                                    page.status == BatchImportPageStatus.FAILED
                            }
                    } == true
                }.single { it.jobId == created.jobId }
            }
            assertEquals(
                "Every page must still import without consent: " + settled.pages.map { it.status },
                2,
                settled.pages.count { it.status == BatchImportPageStatus.READY },
            )
            assertEquals(0, gateway.executionCount)
            assertNull(
                "An unconfigured-model external split must not persist a model task",
                database.readModelTask("batch-split:${created.jobId}:0"),
            )
        } finally {
            processingScope.cancel()
        }
    }

    private fun clearOwnedDirectory(directory: File) {
        val filesRoot = context.filesDir.canonicalFile
        val owned = directory.canonicalFile
        check(owned.parentFile == filesRoot)
        if (!owned.exists()) return
        owned.walkBottomUp().forEach { child ->
            check(child.canonicalPath.startsWith(owned.canonicalPath))
            check(child.delete())
        }
    }
}
