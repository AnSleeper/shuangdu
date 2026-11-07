package com.shuangdu.reader.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.shuangdu.reader.data.ReadTheme
import com.shuangdu.reader.data.ReaderSettings
import com.shuangdu.reader.ui.theme.paletteOf
import androidx.compose.foundation.clickable

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
        Column(Modifier.padding(horizontal = 22.dp).padding(bottom = 28.dp)) {
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

            Text("左右边距", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Slider(
                value = settings.marginH,
                onValueChange = { v -> onChange { it.copy(marginH = v) } },
                valueRange = 10f..40f,
                steps = 14,
                colors = sliderColors,
                modifier = Modifier.padding(vertical = 2.dp)
            )

            Spacer(Modifier.height(10.dp))
            Text("背景", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ReadTheme.entries.forEach { t ->
                    val p = paletteOf(t)
                    Box(
                        Modifier
                            .size(46.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(p.bg)
                            .clickable { onChange { it.copy(theme = t) } },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            t.label,
                            fontSize = 11.sp,
                            color = p.text,
                            fontWeight = if (settings.theme == t) FontWeight.Bold else FontWeight.Normal
                        )
                        if (settings.theme == t) {
                            Box(
                                Modifier
                                    .matchParentSize()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f))
                            )
                        }
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
private fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(CircleShape)
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp)
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChapterListSheet(
    titles: List<String>,
    current: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val listState = rememberLazyListState()
    LaunchedEffect(current) {
        if (current in titles.indices) listState.scrollToItem((current - 3).coerceAtLeast(0))
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().padding(start = 22.dp, end = 12.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "目录",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "${current + 1} / ${titles.size}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.heightIn(max = 520.dp),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                itemsIndexed(titles) { index, title ->
                    val selected = index == current
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(index) }
                            .padding(horizontal = 22.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            title.ifBlank { "第${index + 1}章" },
                            style = MaterialTheme.typography.bodyLarge,
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
                    }
                }
            }
        }
    }
}
