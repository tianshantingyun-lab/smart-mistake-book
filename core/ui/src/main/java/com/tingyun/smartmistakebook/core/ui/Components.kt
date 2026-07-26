package com.tingyun.smartmistakebook.core.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Biotech
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.Functions
import androidx.compose.material.icons.outlined.HistoryEdu
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tingyun.smartmistakebook.core.model.SubjectKind

@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 40.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(5.dp)
                .height(24.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(JadeActive),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            color = Ink,
            style = MaterialTheme.typography.titleLarge,
        )
        if (action != null) {
            Spacer(Modifier.width(8.dp))
            action()
        }
    }
}

@Composable
fun LocalModeLine(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Outlined.PhoneAndroid,
    contentDescription: String = "本地数据状态",
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 32.dp)
            .semantics(mergeDescendants = true) {
                this.contentDescription = "$contentDescription：$text"
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = JadeActive,
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = text,
            color = JadeActive,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
fun PrimaryActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    contentDescription: String = text,
) {
    Button(
        onClick = onClick,
        modifier = modifier
            .defaultMinSize(minHeight = SmartDimens.PrimaryControlHeight)
            .semantics {
                this.contentDescription = contentDescription
                role = Role.Button
            },
        enabled = enabled,
        shape = RoundedCornerShape(SmartDimens.CallToActionRadius),
        colors = ButtonDefaults.buttonColors(
            containerColor = Jade,
            contentColor = OnJade,
            disabledContainerColor = JadeSoft,
            disabledContentColor = InkMuted,
        ),
        elevation = ButtonDefaults.buttonElevation(
            defaultElevation = 0.dp,
            pressedElevation = 0.dp,
            focusedElevation = 0.dp,
            hoveredElevation = 0.dp,
            disabledElevation = 0.dp,
        ),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(SmartDimens.IconSize),
            )
            Spacer(Modifier.width(10.dp))
        }
        Text(text = text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun OutlineActionChip(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    selected: Boolean = false,
    enabled: Boolean = true,
    contentDescription: String = text,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier
            .defaultMinSize(minHeight = SmartDimens.MinimumTouchTarget)
            .semantics {
                this.contentDescription = contentDescription
                role = Role.Button
            },
        enabled = enabled,
        shape = RoundedCornerShape(SmartDimens.ChipRadius),
        border = ButtonDefaults.outlinedButtonBorder(enabled).copy(
            width = 1.dp,
            brush = androidx.compose.ui.graphics.SolidColor(if (selected) JadeActive else Outline),
        ),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (selected) JadeSoft else Color.Transparent,
            contentColor = if (selected) JadeActive else Jade,
            disabledContentColor = InkMuted,
        ),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(SmartDimens.SmallIconSize),
            )
            Spacer(Modifier.width(7.dp))
        }
        Text(text = text, style = MaterialTheme.typography.labelMedium)
    }
}

private fun SubjectKind.icon(): ImageVector = when (this) {
    SubjectKind.CHINESE -> Icons.Outlined.Translate
    SubjectKind.MATH -> Icons.Outlined.Functions
    SubjectKind.ENGLISH -> Icons.Outlined.Language
    SubjectKind.PHYSICS -> Icons.Outlined.Bolt
    SubjectKind.CHEMISTRY -> Icons.Outlined.Science
    SubjectKind.BIOLOGY -> Icons.Outlined.Biotech
    SubjectKind.POLITICS -> Icons.Outlined.AccountBalance
    SubjectKind.HISTORY -> Icons.Outlined.HistoryEdu
    SubjectKind.GEOGRAPHY -> Icons.Outlined.Public
    SubjectKind.GENERAL -> Icons.AutoMirrored.Outlined.MenuBook
}

@Composable
fun SubjectIcon(
    subject: SubjectKind,
    modifier: Modifier = Modifier,
    contentDescription: String = "${subject.studentLabel()}学科",
) {
    SubjectIcon(
        icon = subject.icon(),
        contentDescription = contentDescription,
        modifier = modifier,
    )
}

@Composable
fun SubjectIcon(
    icon: ImageVector,
    contentDescription: String,
    modifier: Modifier = Modifier,
    containerColor: Color = JadeSoft,
    tint: Color = JadeActive,
) {
    Box(
        modifier = modifier
            .size(SmartDimens.PrimaryControlHeight)
            .clip(CircleShape)
            .background(containerColor)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(SmartDimens.IconSize),
            tint = tint,
        )
    }
}

@Composable
fun PaperDivider(
    modifier: Modifier = Modifier,
    color: Color = Divider,
) {
    HorizontalDivider(
        modifier = modifier,
        thickness = 1.dp,
        color = color,
    )
}

@Composable
fun RootPageColumn(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues? = null,
    scrollState: ScrollState = rememberScrollState(),
    content: @Composable ColumnScope.() -> Unit,
) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(Paper),
        contentAlignment = Alignment.TopCenter,
    ) {
        val resolvedContentPadding = contentPadding ?: PaddingValues(
            start = SmartDimens.contentHorizontalPadding(maxWidth),
            top = SmartDimens.Space8,
            end = SmartDimens.contentHorizontalPadding(maxWidth),
            bottom = SmartDimens.Space12,
        )
        Column(
            modifier = Modifier
                .widthIn(max = SmartDimens.MaximumContentWidth)
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(resolvedContentPadding),
            verticalArrangement = Arrangement.Top,
            content = content,
        )
    }
}

@Composable
fun RootPageLazyColumn(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues? = null,
    listState: LazyListState = rememberLazyListState(),
    content: LazyListScope.() -> Unit,
) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(Paper),
        contentAlignment = Alignment.TopCenter,
    ) {
        val resolvedContentPadding = contentPadding ?: PaddingValues(
            start = SmartDimens.contentHorizontalPadding(maxWidth),
            top = SmartDimens.Space8,
            end = SmartDimens.contentHorizontalPadding(maxWidth),
            bottom = SmartDimens.Space12,
        )
        LazyColumn(
            modifier = Modifier
                .widthIn(max = SmartDimens.MaximumContentWidth)
                .fillMaxSize(),
            state = listState,
            contentPadding = resolvedContentPadding,
            verticalArrangement = Arrangement.Top,
            content = content,
        )
    }
}
