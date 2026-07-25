package com.tingyun.smartmistakebook.feature.library

import android.content.ContentValues
import android.graphics.Bitmap
import android.provider.MediaStore
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onRoot
import androidx.test.platform.app.InstrumentationRegistry

internal fun ComposeContentTestRule.captureLibraryQaScreenshot(displayName: String) {
    require(displayName.matches(Regex("[a-z0-9-]+\\.png"))) {
        "QA screenshot names must be lowercase PNG filenames"
    }
    val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
    val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    resolver.delete(
        collection,
        "${MediaStore.Images.Media.DISPLAY_NAME} = ? AND " +
            "${MediaStore.Images.Media.RELATIVE_PATH} = ?",
        arrayOf(displayName, QA_SCREENSHOT_PATH),
    )
    val uri = checkNotNull(
        resolver.insert(
            collection,
            ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, QA_SCREENSHOT_PATH)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            },
        ),
    )
    val screenshot = onRoot().captureToImage().asAndroidBitmap()
    try {
        val saved = checkNotNull(resolver.openOutputStream(uri)).use { stream ->
            screenshot.compress(Bitmap.CompressFormat.PNG, 100, stream)
        }
        check(saved) { "Expected $displayName to be saved" }
        check(
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                null,
                null,
            ) == 1,
        ) { "Expected $displayName to be published" }
    } catch (failure: Throwable) {
        resolver.delete(uri, null, null)
        throw failure
    } finally {
        screenshot.recycle()
    }
}

private const val QA_SCREENSHOT_PATH = "Pictures/SmartMistakeBookQA/"
