package com.shuangdu.reader.ui.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shuangdu.reader.data.PageAnim
import com.shuangdu.reader.data.ReadFont
import com.shuangdu.reader.data.ReaderSettings
import com.shuangdu.reader.data.Skin
import com.shuangdu.reader.ui.theme.paletteOf

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderSettingsSheet(
    settings: ReaderSettings,
    onChange: ((ReaderSettings) -> ReaderSettings) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val sliderColors = androidx.compose.material3.SliderDefaults.colors(
        thumbColor = MaterialTheme.colorScheme.primary,
        activeTrackColor = MaterialTheme.colorScheme.primary,
        inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
    )
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp)
                .padding(bottom = 64.dp)
        ) {
            Text("字体大小", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("A", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Slider(
                    value = settings.fontSize,
                    onValueChange = { v -> onChange { it.copy(fontSize = v) } },
                    valueRange = 14f..30f,
                    steps = 15,
                    colors = sliderColors,
                    modifier = Modifier.weight(1f).padding(horizontal = 10.dp)
                )
                Text("A", fontSize = 22.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Text("行间距", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Slider(
                value = settings.lineSpacing,
                onValueChange = { v -> onChange { it.copy(lineSpacing = v) } },
                valueRange = 1.2f..2.2f,
                steps = 9,
                colors = sliderColors,
                modifier = Modifier.padding(vertical = 2.dp)
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "段间距",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(52.dp)
                )
                Slider(
                    value = settings.paragraphGap,
                    onValueChange = { v -> onChange { it.copy(paragraphGap = v) } },
                    valueRange = 0f..1f,
                    steps = 9,
                    colors = sliderColors,
                    modifier = Modifier.weight(1f).padding(horizontal = 6.dp)
                )
                Text(
                    if (settings.paragraphGap <= 0.06f) "无" else "${(settings.paragraphGap * 100).toInt()}%",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(38.dp)
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "亮度",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(52.dp)
                )
                Slider(
                    value = settings.brightness,
                    onValueChange = { v -> onChange { it.copy(brightness = v) } },
                    valueRange = 0f..1f,
                    steps = 19,
                    colors = sliderColors,
                    modifier = Modifier.weight(1f).padding(horizontal = 6.dp)
                )
                Text(
                    if (settings.brightness <= 0.02f) "跟随" else "${(settings.brightness * 100).toInt()}%",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(38.dp)
                )
            }

            Text("左右边距", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Slider(
                value = settings.marginH,
                onValueChange = { v -> onChange { it.copy(marginH = v) } },
                valueRange = 10f..40f,
                steps = 14,
                colors = sliderColors,
                modifier = Modifier.padding(vertical = 2.dp)
            )

            Spacer(Modifier.height(12.dp))
            Text("背景皮肤", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Skin.entries.forEach { s ->
                    SkinOption(s, selected = settings.skin == s) {
                        onChange { it.copy(skin = s) }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Text("翻页方式", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                PageAnim.entries.forEach { a ->
                    Chip(text = a.label, selected = settings.pageAnim == a) {
                        onChange { it.copy(pageAnim = a) }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Text("字体", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                ReadFont.entries.forEach { f ->
                    Chip(text = f.label, selected = settings.font == f) {
                        onChange { it.copy(font = f) }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            SettingSwitch("首行缩进", settings.indent) { v -> onChange { it.copy(indent = v) } }
            SettingSwitch("显示顶部章节名", settings.showChapterTitle) { v -> onChange { it.copy(showChapterTitle = v) } }
            SettingSwitch("显示时间电量", settings.showStatus) { v -> onChange { it.copy(showStatus = v) } }
            SettingSwitch("阅读时常亮", settings.keepScreenOn) { v -> onChange { it.copy(keepScreenOn = v) } }
        }
    }
}

@Composable
fun SkinOption(skin: Skin, selected: Boolean, onClick: () -> Unit) {
    val p = paletteOf(skin)
    Column(
        Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 2.dp, vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(p.readBg),
            contentAlignment = Alignment.Center
        ) {
            Box(
                Modifier
                    .size(15.dp)
                    .clip(CircleShape)
                    .background(p.readText)
            )
            if (selected) {
                Box(
                    Modifier
                        .matchParentSize()
                        .clip(RoundedCornerShape(13.dp))
                        .background(p.primary.copy(alpha = 0.20f))
                )
            }
        }
        Spacer(Modifier.height(5.dp))
        Text(
            skin.label,
            fontSize = 10.5.sp,
            color = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal
        )
    }
}

@Composable
private fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(CircleShape)
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 15.dp, vertical = 8.dp)
    ) {
        Text(
            text,
            fontSize = 13.sp,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal
        )
    }
}

@Composable
private fun SettingSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun ChapterDrawer(
    visible: Boolean,
    bookTitle: String,
    titles: List<String>,
    current: Int,
    audioChapters: Set<Int> = emptySet(),
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val listState = rememberLazyListState()
    LaunchedEffect(visible, current) {
        if (visible && current in titles.indices) {
            listState.scrollToItem((current - 4).coerceAtLeast(0))
        }
    }

    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(170)),
            exit = fadeOut(tween(150))
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.48f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { onDismiss() }
            )
        }

        AnimatedVisibility(
            visible = visible,
            enter = slideInHorizontally(tween(240, easing = FastOutSlowInEasing)) { -it },
            exit = slideOutHorizontally(tween(200, easing = FastOutSlowInEasing)) { -it }
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(0.85f)
            ) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .systemBarsPadding()
                ) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 20.dp, end = 8.dp, top = 16.dp, bottom = 12.dp)
                    ) {
                        Row(verticalAlignment = Alignment.Top) {
                            Text(
                                bookTitle,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                                Icon(
                                    Icons.Rounded.Close,
                                    "关闭",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "共 ${titles.size} 章 · 当前第 ${current + 1} 章",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 24.dp)
                    ) {
                        itemsIndexed(titles) { index, title ->
                            val selected = index == current
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .background(
                                        if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                                        else Color.Transparent
                                    )
                                    .clickable { onSelect(index) }
                                    .padding(start = 20.dp, end = 16.dp, top = 13.dp, bottom = 13.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    title.ifBlank { "第${index + 1}章" },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (selected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "${index + 1}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (index in audioChapters) {
                                    Spacer(Modifier.width(4.dp))
                                    Icon(
                                        Icons.Rounded.Headphones,
                                        "已有音频",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(13.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
