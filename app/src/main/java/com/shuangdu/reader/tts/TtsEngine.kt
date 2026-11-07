package com.shuangdu.reader.tts

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 听书能力预留接口。第一版只保留结构与调用点，具体实现（系统 TTS / 在线语音）后续接入。
 * 约定：所有长度单位都是「字符 offset」，与阅读页的 charOffset 同一坐标系（章节内偏移）。
 */
interface TtsEngine {

    data class State(
        val playing: Boolean = false,
        val chapterIndex: Int = 0,
        val charOffset: Int = 0,
        val speed: Float = 1.0f
    )

    val state: StateFlow<State>

    /** 传入整章文本与起始位置，从该位置开始朗读 */
    fun speak(chapterText: String, chapterIndex: Int, startOffset: Int)

    fun pause()

    fun resume()

    fun stop()

    fun setSpeed(speed: Float)

    /** 朗读进度回调，阅读页据此自动翻页 */
    fun progress(): Flow<Int>

    fun release()
}
