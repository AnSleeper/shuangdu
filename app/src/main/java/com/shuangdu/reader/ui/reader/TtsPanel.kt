package com.shuangdu.reader.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shuangdu.reader.AppContainer
import com.shuangdu.reader.data.Book
import com.shuangdu.reader.tts.TtsSegment
import com.shuangdu.reader.ui.tts.VoicePickerSheet
import kotlinx.coroutines.delay
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TtsPanel(
    container: AppContainer,
    book: Book,
    chapterTitles: List<String>,
    currentChapter: Int,
    startSegment: Int,
    onDismiss: () -> Unit,
    onJumpToChapter: (Int) -> Unit
) {
    val tts by container.ttsSettings.state.collectAsStateWithLifecycle()
    val progMap by container.ttsConverter.state.collectAsStateWithLifecycle()
    val playerState by container.ttsPlayer.state.collectAsStateWithLifecycle()
    val prog = progMap[book.id]

    val index = remember(book.id) { container.ttsConverter.indexOf(book) }
    val dir = remember(book.id) { File(container.repository.bookDir(book.id), "tts") }

    var showVoicePicker by remember { mutableStateOf(false) }
    var timerMinutes by remember { mutableIntStateOf(0) }
    var playingChapter by remember { mutableIntStateOf(currentChapter) }

    val isThisBook = playerState.bookId == book.id
    val isPlaying = isThisBook && playerState.playing

    fun playChapter(ch: Int, from: Int) {
        val segs = index.segmentsOf(ch)
        if (segs.isEmpty()) {
            container.ttsConverter.convert(book, listOf(ch))
            return
        }
        if (segs.none { it.done }) {
            container.ttsConverter.convert(book, listOf(ch))
            return
        }
        playingChapter = ch
        container.ttsPlayer.play(book.id, ch, segs, from, dir)
        container.ttsPlayer.setSpeed(tts.speed)
    }

    // 自动接下一章
    DisposableEffect(book.id) {
        val player = container.ttsPlayer
        player.onQueueEnd = {
            val next = playingChapter + 1
            val segs = index.segmentsOf(next)
            if (next < chapterTitles.size && segs.isNotEmpty() && segs.all { it.done }) {
                playingChapter = next
                player.play(book.id, next, segs, 0, dir)
            }
        }
        onDispose {
            player.onQueueEnd = null
        }
    }

    // 定时停止
    LaunchedEffect(timerMinutes, isPlaying) {
        if (timerMinutes > 0 && isPlaying) {
            delay(timerMinutes * 60_000L)
            container.ttsPlayer.pause()
            timerMinutes = 0
        }
    }

    val chaptersWithAudio = index.completedChapters()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(Modifier.padding(horizontal = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.Headphones,
                    null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text("听书", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.weight(1f))
                Text(
                    "已有音频 ${chaptersWithAudio.size} 章",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(12.dp))

            // 音色
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        index.voiceName.ifBlank { tts.globalVoiceName.ifBlank { "未选择音色" } },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        if (index.voiceId.isBlank()) "跟随全局音色" else "本书专用音色",
                        fontSize = 10.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = { showVoicePicker = true }) { Text("更换") }
            }

            Spacer(Modifier.height(14.dp))

            // 播放控制
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                        .clickable {
                            when {
                                isPlaying -> container.ttsPlayer.pause()
                                isThisBook && playerState.chapter == playingChapter -> container.ttsPlayer.resume()
                                else -> playChapter(playingChapter, 0)
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        if (isPlaying) "暂停" else "播放",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (isPlaying) "正在播放" else "准备播放",
                        fontSize = 10.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        chapterTitles.getOrNull(playingChapter)?.ifBlank { "第${playingChapter + 1}章" }
                            ?: "第${playingChapter + 1}章",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val segs = index.segmentsOf(playingChapter)
                    val doneCount = segs.count { it.done }
                    Text(
                        if (segs.isEmpty()) "这一章还没转音频" else "已转 $doneCount / ${segs.size} 段",
                        fontSize = 10.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = { playChapter(currentChapter, startSegment) }) { Text("从本页听") }
            }

            // 转换进度
            prog?.let { p ->
                if (p.running || p.message != null) {
                    Spacer(Modifier.height(10.dp))
                    if (p.total > 0) {
                        LinearProgressIndicator(
                            progress = { if (p.total == 0) 0f else (p.done + p.failed).toFloat() / p.total },
                            modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape)
                        )
                        Spacer(Modifier.height(6.dp))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            p.message ?: "正在转换 ${p.done + p.failed}/${p.total}（第 ${p.chapter + 1} 章）",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        if (p.running) {
                            TextButton(onClick = { container.ttsConverter.pause(book.id) }) { Text("暂停") }
                        } else {
                            TextButton(onClick = {
                                container.ttsConverter.convert(book, null)
                            }) { Text("继续转换") }
                        }
                    }
                }
            }

            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionChip("转本章") {
                    container.ttsConverter.convert(book, listOf(currentChapter))
                }
                ActionChip("转整本") {
                    container.ttsConverter.convert(book, null)
                }
                ActionChip("定时 ${if (timerMinutes == 0) "关" else "${timerMinutes}分"}") {
                    timerMinutes = when (timerMinutes) {
                        0 -> 15
                        15 -> 30
                        30 -> 60
                        else -> 0
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            Text(
                "章节音频",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))
            LazyColumn(
                Modifier.fillMaxWidth().heightIn(max = 300.dp),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                // 用进度当 key 的一部分，转换过程中列表状态能实时刷新
                val refresh = (prog?.done ?: 0) * 1000 + (prog?.failed ?: 0) + (prog?.total ?: 0)
                items(chapterTitles.indices.toList(), key = { "$it-$refresh" }) { ch ->
                    val (done, total) = index.chapterProgress(ch)
                    val complete = total > 0 && done == total
                    val current = ch == currentChapter
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onJumpToChapter(ch) }
                            .padding(horizontal = 6.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Rounded.Headphones,
                            null,
                            tint = when {
                                complete -> MaterialTheme.colorScheme.primary
                                done > 0 -> MaterialTheme.colorScheme.onSurfaceVariant
                                else -> MaterialTheme.colorScheme.outline
                            },
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            chapterTitles[ch].ifBlank { "第${ch + 1}章" },
                            fontSize = 13.sp,
                            color = if (current) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            when {
                                complete -> "已转好"
                                total == 0 -> "未转"
                                else -> "$done/$total"
                            },
                            fontSize = 10.5.sp,
                            color = if (complete) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (total > 0 && !complete) {
                            TextButton(onClick = {
                                container.ttsConverter.convert(book, listOf(ch))
                            }) { Text("转", fontSize = 11.sp) }
                        }
                    }
                }
            }
        }
    }

    if (showVoicePicker) {
        VoicePickerSheet(
            container = container,
            currentId = index.voiceId.ifBlank { tts.globalVoiceId },
            onDismiss = { showVoicePicker = false },
            onPick = { v ->
                index.voiceId = v.id
                index.voiceName = v.title
                index.save()
                showVoicePicker = false
            }
        )
    }
}

@Composable
private fun ActionChip(text: String, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(text, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Suppress("unused")
private fun unusedSegment(s: TtsSegment) = s
