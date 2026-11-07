package com.shuangdu.reader.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 皮肤：一套管住整个 app 的配色 */
enum class Skin(val label: String, val isDark: Boolean) {
    PAPER("纸感", false),
    INK_GREEN("墨绿", true),
    DUSK_PINK("暗粉", true),
    INK_BLUE("墨蓝", true),
    BLACK("纯黑", true)
}

enum class PageAnim(val label: String) {
    SLIDE("平移"), COVER("覆盖"), SCROLL("上下滑动"), NONE("无动画")
}

enum class ReadFont(val label: String, val family: String) {
    SYSTEM("默认", "sans-serif"),
    SERIF("宋体", "serif"),
    LIGHT("细体", "sans-serif-light")
}

data class ReaderSettings(
    val fontSize: Float = 19f,
    val lineSpacing: Float = 1.6f,
    val skin: Skin = Skin.INK_GREEN,
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

    val skin: Skin get() = _state.value.skin

    private fun load(): ReaderSettings {
        val d = ReaderSettings()
        return ReaderSettings(
            fontSize = sp.getFloat("fontSize", d.fontSize),
            lineSpacing = sp.getFloat("lineSpacing", d.lineSpacing),
            skin = loadSkin(),
            pageAnim = runCatching { PageAnim.valueOf(sp.getString("pageAnim", d.pageAnim.name)!!) }
                .getOrDefault(d.pageAnim),
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

    /** 兼容老版本的 theme 键 */
    private fun loadSkin(): Skin {
        sp.getString("skin", null)?.let { v ->
            runCatching { return Skin.valueOf(v) }
        }
        return when (sp.getString("theme", null)) {
            "WHITE", "PAPER" -> Skin.PAPER
            "GREEN" -> Skin.INK_GREEN
            "DARK" -> Skin.BLACK
            else -> Skin.INK_GREEN
        }
    }

    fun update(block: (ReaderSettings) -> ReaderSettings) {
        val next = block(_state.value)
        _state.value = next
        sp.edit()
            .putFloat("fontSize", next.fontSize)
            .putFloat("lineSpacing", next.lineSpacing)
            .putString("skin", next.skin.name)
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

    fun setSkin(skin: Skin) = update { it.copy(skin = skin) }
}
