package com.shuangdu.reader.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ReadTheme(val label: String) {
    WHITE("白"), PAPER("米黄"), GREEN("护眼"), DARK("夜间")
}

enum class PageAnim(val label: String) {
    SLIDE("平移"), COVER("覆盖"), NONE("无动画")
}

enum class ReadFont(val label: String, val family: String) {
    SYSTEM("默认", "sans-serif"),
    SERIF("宋体", "serif"),
    LIGHT("细体", "sans-serif-light")
}

data class ReaderSettings(
    val fontSize: Float = 19f,
    val lineSpacing: Float = 1.6f,
    val theme: ReadTheme = ReadTheme.PAPER,
    val pageAnim: PageAnim = PageAnim.SLIDE,
    val marginH: Float = 22f,
    val paddingV: Float = 14f,
    val font: ReadFont = ReadFont.SYSTEM,
    val indent: Boolean = true,
    val showStatus: Boolean = true,
    val keepScreenOn: Boolean = false,
    val showChapterTitle: Boolean = true,
    val autoCheckUpdate: Boolean = true
)

class SettingsStore(context: Context) {

    private val sp = context.getSharedPreferences("reader_settings", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(load())
    val state: StateFlow<ReaderSettings> = _state.asStateFlow()

    val current: ReaderSettings get() = _state.value

    private fun load(): ReaderSettings {
        val d = ReaderSettings()
        return ReaderSettings(
            fontSize = sp.getFloat("fontSize", d.fontSize),
            lineSpacing = sp.getFloat("lineSpacing", d.lineSpacing),
            theme = runCatching { ReadTheme.valueOf(sp.getString("theme", d.theme.name)!!) }.getOrDefault(d.theme),
            pageAnim = runCatching { PageAnim.valueOf(sp.getString("pageAnim", d.pageAnim.name)!!) }.getOrDefault(d.pageAnim),
            marginH = sp.getFloat("marginH", d.marginH),
            paddingV = sp.getFloat("paddingV", d.paddingV),
            font = runCatching { ReadFont.valueOf(sp.getString("font", d.font.name)!!) }.getOrDefault(d.font),
            indent = sp.getBoolean("indent", d.indent),
            showStatus = sp.getBoolean("showStatus", d.showStatus),
            keepScreenOn = sp.getBoolean("keepScreenOn", d.keepScreenOn),
            showChapterTitle = sp.getBoolean("showChapterTitle", d.showChapterTitle),
            autoCheckUpdate = sp.getBoolean("autoCheckUpdate", d.autoCheckUpdate)
        )
    }

    fun update(block: (ReaderSettings) -> ReaderSettings) {
        val next = block(_state.value)
        _state.value = next
        sp.edit()
            .putFloat("fontSize", next.fontSize)
            .putFloat("lineSpacing", next.lineSpacing)
            .putString("theme", next.theme.name)
            .putString("pageAnim", next.pageAnim.name)
            .putFloat("marginH", next.marginH)
            .putFloat("paddingV", next.paddingV)
            .putString("font", next.font.name)
            .putBoolean("indent", next.indent)
            .putBoolean("showStatus", next.showStatus)
            .putBoolean("keepScreenOn", next.keepScreenOn)
            .putBoolean("showChapterTitle", next.showChapterTitle)
            .putBoolean("autoCheckUpdate", next.autoCheckUpdate)
            .apply()
    }
}
