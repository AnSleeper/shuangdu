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
    val speed: Float = 1.0f,
    val showPill: Boolean = true,
    val pillOpacity: Float = 0.85f,
    val pillOnLeft: Boolean = false,
    val pillYFraction: Float = 0.45f
)

class TtsSettingsStore(context: Context) {

    /** 收藏的音色 */
    data class FavVoice(val id: String, val title: String)

    private val sp = context.getSharedPreferences("tts_settings", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(load())
    val state: StateFlow<TtsSettings> = _state.asStateFlow()
    val current: TtsSettings get() = _state.value

    private val _favorites = MutableStateFlow(loadFavorites())
    val favorites: StateFlow<List<FavVoice>> = _favorites.asStateFlow()

    fun isFavorite(id: String): Boolean = _favorites.value.any { it.id == id }

    fun toggleFavorite(id: String, title: String) {
        val list = _favorites.value.toMutableList()
        val existing = list.firstOrNull { it.id == id }
        if (existing != null) list.remove(existing) else list.add(0, FavVoice(id, title))
        _favorites.value = list
        sp.edit().putString("favorites", toJson(list)).apply()
    }

    private fun loadFavorites(): List<FavVoice> = runCatching {
        val raw = sp.getString("favorites", null) ?: return@runCatching emptyList()
        val arr = org.json.JSONArray(raw)
        (0 until arr.length()).mapNotNull { o ->
            arr.optJSONObject(o)?.let { FavVoice(it.optString("id"), it.optString("title")) }
        }
    }.getOrDefault(emptyList())

    private fun toJson(list: List<FavVoice>): String {
        val arr = org.json.JSONArray()
        list.forEach { arr.put(org.json.JSONObject().put("id", it.id).put("title", it.title)) }
        return arr.toString()
    }

    private fun load(): TtsSettings {
        val d = TtsSettings()
        return TtsSettings(
            modelId = sp.getString("modelId", d.modelId) ?: d.modelId,
            globalVoiceId = sp.getString("globalVoiceId", d.globalVoiceId).orEmpty(),
            globalVoiceName = sp.getString("globalVoiceName", d.globalVoiceName).orEmpty(),
            concurrency = sp.getInt("concurrency", d.concurrency).coerceIn(1, 7),
            speed = sp.getFloat("speed", d.speed).coerceIn(0.6f, 1.6f),
            showPill = sp.getBoolean("showPill", d.showPill),
            pillOpacity = sp.getFloat("pillOpacity", d.pillOpacity).coerceIn(0.3f, 1f),
            pillOnLeft = sp.getBoolean("pillOnLeft", d.pillOnLeft),
            pillYFraction = sp.getFloat("pillYFraction", d.pillYFraction).coerceIn(0f, 1f)
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
            .putBoolean("showPill", next.showPill)
            .putFloat("pillOpacity", next.pillOpacity)
            .putBoolean("pillOnLeft", next.pillOnLeft)
            .putFloat("pillYFraction", next.pillYFraction)
            .apply()
    }
}
