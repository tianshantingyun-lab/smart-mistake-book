package com.tingyun.smartmistakebook.core.data.model

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tingyun.smartmistakebook.core.data.capture.AndroidCanonicalAssetVault
import com.tingyun.smartmistakebook.core.data.capture.LocalQuestionTextRecognizer
import com.tingyun.smartmistakebook.core.data.capture.LocalTextRecognition
import com.tingyun.smartmistakebook.core.data.capture.RoomCaptureWorkflowRepository
import com.tingyun.smartmistakebook.core.database.StudyDatabaseFactory
import com.tingyun.smartmistakebook.core.database.StudyDatabasePort
import com.tingyun.smartmistakebook.core.domain.CaptureDraftImportRequest
import com.tingyun.smartmistakebook.core.domain.CaptureEntryOrigin
import com.tingyun.smartmistakebook.core.domain.CaptureInputSource
import com.tingyun.smartmistakebook.core.model.CaptureAssessmentInput
import com.tingyun.smartmistakebook.core.model.CaptureAssessmentOrigin
import com.tingyun.smartmistakebook.core.model.ModelEgressAssetGrant
import com.tingyun.smartmistakebook.core.model.ModelEgressDataClass
import com.tingyun.smartmistakebook.core.model.ModelEgressManifest
import com.tingyun.smartmistakebook.core.model.ModelEgressPolicy
import com.tingyun.smartmistakebook.core.model.ModelEgressPurpose
import com.tingyun.smartmistakebook.core.model.ModelExecutionLocation
import com.tingyun.smartmistakebook.core.model.ModelPromptPolicyVersions
import com.tingyun.smartmistakebook.core.model.ModelTaskKind
import com.tingyun.smartmistakebook.core.model.ModelTaskRequest
import com.tingyun.smartmistakebook.core.model.ProviderCapabilitySnapshot
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RestrictedModelAssetSourceInstrumentedTest {
    private lateinit var context: Context
    private lateinit var database: StudyDatabasePort
    private lateinit var databaseName: String
    private var createdInput: File? = null
    private var createdCanonical: File? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        databaseName = "restricted-model-asset-${System.nanoTime()}.db"
        database = StudyDatabaseFactory.open(context, databaseName)
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(databaseName)
        createdInput?.delete()
        createdCanonical?.delete()
    }

    @Test
    fun approvedCanonicalAssetCanBeReadOnlyThroughTheExactExternalPermit() = runBlocking {
        val vault = AndroidCanonicalAssetVault(context)
        val repository = RoomCaptureWorkflowRepository(
            database = database,
            assetVault = vault,
            localTextRecognizer = LocalQuestionTextRecognizer { _, _, _ ->
                LocalTextRecognition(emptyList(), "no-local-text-v1")
            },
        )
        val input = createPng().also { createdInput = it }
        val draft = repository.importDraft(
            CaptureDraftImportRequest(
                requestId = "import-1",
                localUri = privateUri(input).toString(),
                source = CaptureInputSource.PHOTO_PICKER,
                origin = CaptureEntryOrigin.LIBRARY,
                occurredAtEpochMillis = 100,
            ),
        )
        val record = checkNotNull(database.readCanonicalSourceAsset(draft.sourceAssetId))
        createdCanonical = vault.resolve(record)
        val request = ModelTaskRequest(
            requestId = "capture-assess:import-1",
            input = CaptureAssessmentInput(
                draftId = draft.draftId,
                sourceAssetId = draft.sourceAssetId,
                origin = CaptureAssessmentOrigin.LIBRARY,
                imageWidth = draft.width,
                imageHeight = draft.height,
            ),
            occurredAtEpochMillis = 100,
            egressManifest = manifest(draft, record.byteSize),
        )
        val execution = ModelEgressPolicy.authorize(request, provider(), 101)

        val opened = AndroidRestrictedModelAssetSource(context, database)
            .open(execution, draft.sourceAssetId)
        val bytes = opened.use { it.stream.readBytes() }
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

        assertEquals(record.byteSize, bytes.size.toLong())
        assertEquals(record.contentSha256, hash)
        assertEquals(record.mimeType, opened.mimeType)
        assertTrue(bytes.isNotEmpty())
    }

    private fun manifest(
        draft: com.tingyun.smartmistakebook.core.domain.CaptureDraftSummary,
        byteSize: Long,
    ) = ModelEgressManifest(
        authorizationId = "approval-1",
        subjectId = draft.draftId,
        purpose = ModelEgressPurpose.CAPTURE_TO_DOCUMENT,
        authorizedTaskKinds = setOf(
            ModelTaskKind.CAPTURE_ASSESS,
            ModelTaskKind.CAPTURE_PARSE,
        ),
        providerId = "provider-1",
        modelId = "vision-model-1",
        providerConfigurationVersion = "provider-config-v1",
        promptPolicyVersion = ModelPromptPolicyVersions.CAPTURE_DOCUMENT,
        approvedAtEpochMillis = 101,
        assets = listOf(
            ModelEgressAssetGrant(
                assetId = draft.sourceAssetId,
                sha256 = draft.sourceAssetSha256,
                byteSize = byteSize,
                width = draft.width,
                height = draft.height,
            ),
        ),
        disclosedData = setOf(
            ModelEgressDataClass.SANITIZED_IMAGE_BYTES,
            ModelEgressDataClass.IMAGE_DIMENSIONS,
        ),
        prohibitedData = ModelEgressManifest.CAPTURE_PROHIBITED_DATA,
    )

    private fun provider() = ProviderCapabilitySnapshot(
        providerId = "provider-1",
        providerDisplayName = "测试视觉模型",
        modelId = "vision-model-1",
        supportedTasks = setOf(ModelTaskKind.CAPTURE_ASSESS, ModelTaskKind.CAPTURE_PARSE),
        supportsImageInput = true,
        supportsStructuredOutput = true,
        supportsStreaming = true,
        executionLocation = ModelExecutionLocation.EXTERNAL_PROVIDER,
        providerConfigurationVersion = "provider-config-v1",
    )

    private fun createPng(): File {
        val directory = File(context.cacheDir, "captured_images").also { it.mkdirs() }
        val file = File.createTempFile("egress_", ".png", directory)
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
        }
        try {
            FileOutputStream(file).use { stream ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
            }
        } finally {
            bitmap.recycle()
        }
        return file
    }

    private fun privateUri(file: File) = FileProvider.getUriForFile(
        context,
        "${context.packageName}.capture.fileprovider",
        file,
    )
}
