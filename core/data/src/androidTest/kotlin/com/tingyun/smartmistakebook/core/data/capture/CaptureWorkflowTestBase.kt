package com.tingyun.smartmistakebook.core.data.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tingyun.smartmistakebook.core.data.model.FakeModelGateway
import com.tingyun.smartmistakebook.core.data.model.RoomModelTaskRepository
import com.tingyun.smartmistakebook.core.database.StudyDatabaseFactory
import com.tingyun.smartmistakebook.core.database.StudyDatabasePort
import com.tingyun.smartmistakebook.core.domain.CaptureTranscriptionReview
import com.tingyun.smartmistakebook.core.domain.CaptureWritingLayer
import com.tingyun.smartmistakebook.core.domain.ConfirmCapturedProblemRequest
import com.tingyun.smartmistakebook.core.domain.SaveCaptureDraftWorkspaceRequest
import com.tingyun.smartmistakebook.core.model.CaptureDraftEditedField
import com.tingyun.smartmistakebook.core.model.CaptureDraftEditorMode
import com.tingyun.smartmistakebook.core.model.CaptureDraftWorkspace
import com.tingyun.smartmistakebook.core.model.CaptureFinalConfirmationRequestIdentity
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocument
import com.tingyun.smartmistakebook.core.model.ContentBlock
import com.tingyun.smartmistakebook.core.model.ImagePipelineProblemKind
import com.tingyun.smartmistakebook.core.model.NormalizedSourceRegion
import com.tingyun.smartmistakebook.core.model.QuestionBlockEvidence
import com.tingyun.smartmistakebook.core.model.QuestionBlockProvenance
import com.tingyun.smartmistakebook.core.model.QuestionBlockReviewStatus
import com.tingyun.smartmistakebook.core.model.QuestionDocument
import com.tingyun.smartmistakebook.core.model.WritingLayer
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
abstract class CaptureWorkflowTestBase {
    protected lateinit var context: Context
    protected lateinit var database: StudyDatabasePort
    protected lateinit var repository: RoomCaptureWorkflowRepository
    protected lateinit var databaseName: String

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        databaseName = "capture-workflow-${System.nanoTime()}.db"
        context.deleteDatabase(databaseName)
        clearOwnedFlatDirectory(context.cacheDir, "captured_images")
        clearOwnedFlatDirectory(context.filesDir, "source-assets")
        clearOwnedDirectoryTree(context.filesDir, BATCH_IMPORT_STAGING_DIRECTORY)
        database = StudyDatabaseFactory.open(context, databaseName)
        repository = RoomCaptureWorkflowRepository(
            database,
            AndroidCanonicalAssetVault(context),
            noTextRecognizer(),
            splitImports = com.tingyun.smartmistakebook.core.data.splitimport.RoomSplitImportRepository(
                database,
            ),
        )
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(databaseName)
        clearOwnedFlatDirectory(context.cacheDir, "captured_images")
        clearOwnedFlatDirectory(context.filesDir, "source-assets")
        clearOwnedDirectoryTree(context.filesDir, BATCH_IMPORT_STAGING_DIRECTORY)
    }

    protected fun createJpeg(
        width: Int,
        height: Int,
        orientation: Int = ExifInterface.ORIENTATION_NORMAL,
    ): File {
        val directory = File(context.cacheDir, "captured_images").also { it.mkdirs() }
        val file = File.createTempFile("question_", ".jpg", directory)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            FileOutputStream(file).use { stream ->
                assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, stream))
            }
        } finally {
            bitmap.recycle()
        }
        if (orientation != ExifInterface.ORIENTATION_NORMAL) {
            ExifInterface(file).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
                saveAttributes()
            }
        }
        return file
    }

    @Suppress("FunctionName")
    protected suspend fun ConfirmCapturedProblemRequest(
        requestId: String,
        draftId: String,
        expectedRevisionNumber: Int,
        subject: String,
        title: String,
        transcription: String,
        writingLayer: CaptureWritingLayer,
        transcriptionReview: CaptureTranscriptionReview,
        occurredAtEpochMillis: Long,
        confirmedDocument: CapturedQuestionDocument? = null,
    ): ConfirmCapturedProblemRequest {
        val pending = checkNotNull(repository.readPendingCapture(draftId))
        require(pending.currentRevisionNumber == expectedRevisionNumber)
        require(
            confirmedDocument == null ||
                transcriptionReview == CaptureTranscriptionReview.MODEL_DOCUMENT_EXPLICITLY_CONFIRMED,
        )
        val modelWritingLayer = when (writingLayer) {
            CaptureWritingLayer.PRINTED -> WritingLayer.PRINTED
            CaptureWritingLayer.HANDWRITTEN -> WritingLayer.HANDWRITTEN
            CaptureWritingLayer.MIXED -> WritingLayer.MIXED
            CaptureWritingLayer.UNKNOWN -> WritingLayer.UNKNOWN
        }
        val document = confirmedDocument?.copy(
            document = confirmedDocument.document.copy(title = title.trim()),
            blockEvidence = confirmedDocument.blockEvidence.map { evidence ->
                evidence.copy(
                    writingLayer = evidence.writingLayer.takeUnless { it == WritingLayer.UNKNOWN }
                        ?: modelWritingLayer,
                    reviewStatus = QuestionBlockReviewStatus.USER_CONFIRMED,
                )
            },
        ) ?: CapturedQuestionDocument(
            document = QuestionDocument(
                id = "document-$draftId",
                title = title.trim(),
                blocks = listOf(ContentBlock.Paragraph("stem", transcription.trim())),
            ),
            blockEvidence = listOf(
                QuestionBlockEvidence(
                    blockId = "stem",
                    sourceAssetId = pending.sourceAssetId,
                    sourceRegion = NormalizedSourceRegion(0.0, 0.0, 1.0, 1.0),
                    writingLayer = modelWritingLayer,
                    provenance = QuestionBlockProvenance.USER_CORRECTION,
                    reviewStatus = QuestionBlockReviewStatus.USER_CONFIRMED,
                ),
            ),
        )
        val previous = pending.workspace
        val saved = repository.saveDraftWorkspace(
            SaveCaptureDraftWorkspaceRequest(
                draftId = draftId,
                basisRevisionNumber = expectedRevisionNumber,
                expectedWorkspaceVersion = previous?.identity?.workspaceVersion ?: 0,
                expectedWorkspaceFingerprint = previous?.identity?.workspaceFingerprint,
                workspace = CaptureDraftWorkspace(
                    subject = subject,
                    workingDocument = document,
                    editorMode = CaptureDraftEditorMode.STRUCTURED_DOCUMENT,
                    userEditedFields = setOf(
                        CaptureDraftEditedField.SUBJECT,
                        CaptureDraftEditedField.TITLE,
                        CaptureDraftEditedField.TRANSCRIPTION,
                    ),
                    userEditedBlockIds = document.document.blocks.map(ContentBlock::id).toSet(),
                    baseCandidateFingerprint = pending.currentRevisionDocumentFingerprint,
                    finalConfirmationRequest = CaptureFinalConfirmationRequestIdentity(
                        requestId = requestId,
                        occurredAtEpochMillis = occurredAtEpochMillis,
                    ),
                ),
                occurredAtEpochMillis = occurredAtEpochMillis,
            ),
        )
        return ConfirmCapturedProblemRequest(
            draftId = draftId,
            workspaceIdentity = saved.identity,
        )
    }

    protected fun createPng(width: Int, height: Int): File {
        val directory = File(context.cacheDir, "captured_images").also { it.mkdirs() }
        val file = File.createTempFile("question_", ".img", directory)
        writePng(file, width, height)
        return file
    }

    protected fun createInvalidImage(): File {
        val directory = File(context.cacheDir, "captured_images").also { it.mkdirs() }
        val file = File.createTempFile("question_", ".img", directory)
        FileOutputStream(file).use { stream -> stream.write("not-an-image".toByteArray()) }
        return file
    }

    protected fun stagedSourceFile(sourceUri: String): File {
        val uri = Uri.parse(sourceUri)
        assertEquals(batchImportProviderAuthority(context), uri.authority)
        assertEquals("batch_import_staging", uri.pathSegments.first())
        val session = File(context.filesDir, BATCH_IMPORT_STAGING_DIRECTORY)
            .resolve(uri.pathSegments[1])
            .canonicalFile
        val root = File(context.filesDir, BATCH_IMPORT_STAGING_DIRECTORY).canonicalFile
        check(session.parentFile == root)
        return session.resolve(uri.pathSegments[2]).canonicalFile.also { source ->
            check(source.parentFile == session)
        }
    }

    protected fun writePng(file: File, width: Int, height: Int) {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
        }
        try {
            FileOutputStream(file).use { stream ->
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
            }
        } finally {
            bitmap.recycle()
        }
    }

    protected fun privateUri(file: File) = FileProvider.getUriForFile(
        context,
        "${context.packageName}.capture.fileprovider",
        file,
    )

    internal fun noTextRecognizer() = LocalQuestionTextRecognizer { _, _, _ ->
        LocalTextRecognition(emptyList(), "fixture-no-text-v1")
    }

    /** A model-task repo whose classify round reports WITH_FIGURE (drives redraw). */
    protected fun withFigureClassifyModelTasks(): RoomModelTaskRepository = RoomModelTaskRepository(
        database = database,
        gateway = FakeModelGateway(
            stepDelayMillis = 0,
            classifyProblemKind = ImagePipelineProblemKind.WITH_FIGURE,
        ),
    )

    protected fun clearOwnedFlatDirectory(
        parent: File,
        childName: String,
    ) {
        val canonicalParent = parent.canonicalFile
        val directory = File(canonicalParent, childName).canonicalFile
        check(directory.parentFile == canonicalParent)
        if (!directory.exists()) return
        check(directory.isDirectory)
        directory.listFiles().orEmpty().forEach { child ->
            val canonicalChild = child.canonicalFile
            check(canonicalChild.parentFile == directory)
            check(canonicalChild.isFile)
            check(canonicalChild.delete())
        }
        check(directory.delete())
    }

    protected fun clearOwnedDirectoryTree(parent: File, childName: String) {
        val canonicalParent = parent.canonicalFile
        val directory = File(canonicalParent, childName).canonicalFile
        check(directory.parentFile == canonicalParent)
        if (!directory.exists()) return
        directory.walkBottomUp().forEach { child ->
            check(child.canonicalPath.startsWith(directory.canonicalPath))
            check(child.delete())
        }
    }
}
