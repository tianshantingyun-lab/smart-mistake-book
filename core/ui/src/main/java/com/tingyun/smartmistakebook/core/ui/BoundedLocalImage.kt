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
import com.tingyun.smartmistakebook.core.model.NormalizedSourceRegion
import kotlin.math.ceil
import kotlin.math.floor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class LocalImageLoadState {
    LOADING,
    AVAILABLE,
    UNAVAILABLE,
}

/**
 * Decodes only a sampled local content/file URI; it never performs a network request.
 *
 * [sourceRegion] optionally selects a normalized (0..1) sub-rectangle of the image, so a
 * caller can show a single cropped area (e.g. one split-out question) without materializing
 * a new file; the crop is taken from the same sampled bitmap the whole-image preview uses.
 */
@Composable
fun BoundedLocalImage(
    imageUri: String,
    contentDescription: String,
    expanded: Boolean,
    modifier: Modifier = Modifier,
    collapsedMaxHeight: Dp = 240.dp,
    expandedMaxHeight: Dp = 520.dp,
    sourceRegion: NormalizedSourceRegion? = null,
    onLoadStateChange: (LocalImageLoadState) -> Unit = {},
) {
    val context = LocalContext.current
    val preview by produceState<LocalImagePreview>(
        LocalImagePreview.Loading,
        imageUri,
        sourceRegion,
    ) {
        val bitmap = withContext(Dispatchers.IO) {
            decodeBoundedBitmap(context, Uri.parse(imageUri), sourceRegion)
        }
        value = bitmap?.let(LocalImagePreview::Available) ?: LocalImagePreview.Unavailable
    }
    val loadState = when (preview) {
        LocalImagePreview.Loading -> LocalImageLoadState.LOADING
        is LocalImagePreview.Available -> LocalImageLoadState.AVAILABLE
        LocalImagePreview.Unavailable -> LocalImageLoadState.UNAVAILABLE
    }
    LaunchedEffect(imageUri, sourceRegion, loadState) {
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

private fun decodeBoundedBitmap(
    context: Context,
    uri: Uri,
    region: NormalizedSourceRegion?,
): Bitmap? = runCatching {
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
    val decoded = uri.decode(context, options) ?: return@runCatching null
    val crop = region?.let { normalizedRegionToPixelCrop(it, decoded.width, decoded.height) }
        ?: return@runCatching decoded
    runCatching {
        Bitmap.createBitmap(decoded, crop.left, crop.top, crop.width, crop.height)
    }.getOrDefault(decoded)
}.getOrNull()

/** A pixel-space crop rectangle derived from a normalized region. */
internal data class PixelCrop(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
)

/**
 * Maps a normalized (0..1) region onto pixel bounds of a [width] x [height] image.
 * Fractions are clamped to the image, the result keeps at least one pixel per side, and
 * a region that cannot describe an area (non-finite, inverted, empty) returns null so the
 * caller falls back to the whole image.
 */
internal fun normalizedRegionToPixelCrop(
    region: NormalizedSourceRegion,
    width: Int,
    height: Int,
): PixelCrop? {
    if (width <= 0 || height <= 0) return null
    if (
        !region.left.isFinite() || !region.top.isFinite() ||
        !region.right.isFinite() || !region.bottom.isFinite()
    ) {
        return null
    }
    val leftFraction = region.left.coerceIn(0.0, 1.0)
    val topFraction = region.top.coerceIn(0.0, 1.0)
    val rightFraction = region.right.coerceIn(0.0, 1.0)
    val bottomFraction = region.bottom.coerceIn(0.0, 1.0)
    if (rightFraction <= leftFraction || bottomFraction <= topFraction) return null
    val left = floor(leftFraction * width).toInt().coerceIn(0, width - 1)
    val top = floor(topFraction * height).toInt().coerceIn(0, height - 1)
    val right = ceil(rightFraction * width).toInt().coerceIn(left + 1, width)
    val bottom = ceil(bottomFraction * height).toInt().coerceIn(top + 1, height)
    return PixelCrop(left = left, top = top, width = right - left, height = bottom - top)
}

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
