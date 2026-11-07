package com.shuangdu.reader.tts

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 听书相关设置（不含密钥，密钥单独加密存） */
data class TtsSettings(
    val modelId: String = TtsModels.S2_1_PRO_FREE,
    val globalVoiceId: String = "",
    val globalVoiceName: String = "",
    val concurrency: Int = 5,
    val speed: Float = 1.0f
)

class TtsSettingsStore(context: Context) {

    private val sp = context.getSharedPreferences("tts_settings", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(load())
    val state: StateFlow<TtsSettings> = _state.asStateFlow()
    val current: TtsSettings get() = _state.value

    private fun load(): TtsSettings {
        val d = TtsSettings()
        return TtsSettings(
            modelId = sp.getString("modelId", d.modelId) ?: d.modelId,
            globalVoiceId = sp.getString("globalVoiceId", d.globalVoiceId).orEmpty(),
            globalVoiceName = sp.getString("globalVoiceName", d.globalVoiceName).orEmpty(),
            concurrency = sp.getInt("concurrency", d.concurrency).coerceIn(1, 7),
            speed = sp.getFloat("speed", d.speed).coerceIn(0.6f, 1.6f)
        )
    }

    fun update(block: (TtsSettings) -> TtsSettings) {
        val next = block(_state.value)
        _state.value = next
        sp.edit()
            .putString("modelId", next.modelId)
            .putString("globalVoiceId", next.globalVoiceId)
            .putString("globalVoiceName", next.globalVoiceName)
            .putInt("concurrency", next.concurrency.coerceIn(1, 7))
            .putFloat("speed", next.speed)
            .apply()
    }
}
