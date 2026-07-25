package com.tingyun.smartmistakebook.core.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class LocalImageLoadState {
    LOADING,
    AVAILABLE,
    UNAVAILABLE,
}

/** Decodes only a sampled local content/file URI; it never performs a network request. */
@Composable
fun BoundedLocalImage(
    imageUri: String,
    contentDescription: String,
    expanded: Boolean,
    modifier: Modifier = Modifier,
    collapsedMaxHeight: Dp = 240.dp,
    expandedMaxHeight: Dp = 520.dp,
    onLoadStateChange: (LocalImageLoadState) -> Unit = {},
) {
    val context = LocalContext.current
    val preview by produceState<LocalImagePreview>(LocalImagePreview.Loading, imageUri) {
        val bitmap = withContext(Dispatchers.IO) {
            decodeBoundedBitmap(context, Uri.parse(imageUri))
        }
        value = bitmap?.let(LocalImagePreview::Available) ?: LocalImagePreview.Unavailable
    }
    val loadState = when (preview) {
        LocalImagePreview.Loading -> LocalImageLoadState.LOADING
        is LocalImagePreview.Available -> LocalImageLoadState.AVAILABLE
        LocalImagePreview.Unavailable -> LocalImageLoadState.UNAVAILABLE
    }
    LaunchedEffect(imageUri, loadState) {
        onLoadStateChange(loadState)
    }
    (preview as? LocalImagePreview.Available)?.bitmap?.let { bitmap ->
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            modifier = modifier.heightIn(
                max = if (expanded) expandedMaxHeight else collapsedMaxHeight,
            ),
        )
    }
}

private fun decodeBoundedBitmap(context: Context, uri: Uri): Bitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    uri.decode(context, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
    var sampleSize = 1
    while (
        bounds.outWidth / sampleSize > MAX_PREVIEW_EDGE ||
        bounds.outHeight / sampleSize > MAX_PREVIEW_EDGE
    ) {
        sampleSize *= 2
    }
    val options = BitmapFactory.Options().apply {
        inSampleSize = sampleSize
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    uri.decode(context, options)
}.getOrNull()

private fun Uri.decode(context: Context, options: BitmapFactory.Options): Bitmap? = when (scheme) {
    "file" -> path?.let { path -> BitmapFactory.decodeFile(path, options) }
    "content" -> context.contentResolver.openInputStream(this)?.use { stream ->
        BitmapFactory.decodeStream(stream, null, options)
    }
    else -> null
}

private sealed interface LocalImagePreview {
    data object Loading : LocalImagePreview
    data class Available(val bitmap: Bitmap) : LocalImagePreview
    data object Unavailable : LocalImagePreview
}

private const val MAX_PREVIEW_EDGE = 1_600
