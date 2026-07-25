package com.tingyun.smartmistakebook.feature.capture

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.tingyun.smartmistakebook.core.ui.BoundedLocalImage
import com.tingyun.smartmistakebook.core.ui.Ink
import com.tingyun.smartmistakebook.core.ui.InkSecondary
import com.tingyun.smartmistakebook.core.ui.JadeSoft
import com.tingyun.smartmistakebook.core.ui.LocalImageLoadState
import com.tingyun.smartmistakebook.core.ui.Outline

@Composable
internal fun CaptureSourceImagePreview(
    imageUri: String,
    onStateChange: (LocalImageLoadState) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable(imageUri) { mutableStateOf(false) }
    var previewState by rememberSaveable(imageUri) {
        mutableStateOf(LocalImageLoadState.LOADING)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, Outline, RoundedCornerShape(8.dp))
            .background(JadeSoft.copy(alpha = 0.2f), RoundedCornerShape(8.dp))
            .clickable(enabled = previewState == LocalImageLoadState.AVAILABLE) {
                expanded = !expanded
            }
            .padding(12.dp)
            .testTag("capture_source_preview"),
    ) {
        Text(
            text = "原图",
            color = Ink,
            style = MaterialTheme.typography.titleSmall,
        )
        BoundedLocalImage(
            imageUri = imageUri,
            contentDescription = "题目原图",
            expanded = expanded,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
            onLoadStateChange = { state ->
                previewState = state
                onStateChange(state)
            },
        )
        when (previewState) {
            LocalImageLoadState.LOADING -> Text(
                text = "正在打开原图…",
                modifier = Modifier.padding(top = 8.dp),
                color = InkSecondary,
                style = MaterialTheme.typography.bodySmall,
            )
            LocalImageLoadState.AVAILABLE -> Text(
                text = if (expanded) "点击图片区域收起" else "点击图片区域放大查看",
                modifier = Modifier.padding(top = 8.dp),
                color = InkSecondary,
                style = MaterialTheme.typography.bodySmall,
            )
            LocalImageLoadState.UNAVAILABLE -> Text(
                text = "原图预览暂时不可用，请重新选择或拍摄。",
                modifier = Modifier.padding(top = 8.dp),
                color = InkSecondary,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
