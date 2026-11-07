package com.shuangdu.reader.format

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * TXT 编码识别。中文小说最常见的是 GBK/GB18030，其次是 UTF-8（可能带 BOM）。
 * 策略：BOM > UTF-16 无 BOM 猜测 > UTF-8 严格试解码 > GB18030 兜底。
 */
object TextCodec {

    private val GB18030: Charset = Charset.forName("GB18030")
    private val GBK: Charset = Charset.forName("GBK")

    fun decode(bytes: ByteArray): String {
        val charset = detect(bytes)
        val text = try {
            String(bytes, charset)
        } catch (e: Exception) {
            String(bytes, Charsets.UTF_8)
        }
        return normalize(text)
    }

    fun detect(bytes: ByteArray): Charset {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return Charsets.UTF_8
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return Charsets.UTF_16LE
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return Charsets.UTF_16BE
        }
        if (looksLikeUtf16Le(bytes)) return Charsets.UTF_16LE

        // 先看前 256KB 是否合法 UTF-8，避免大文件整本解码两次
        val probe = if (bytes.size > 256 * 1024) bytes.copyOfRange(0, 256 * 1024) else bytes
        val probeTrimmed = trimBrokenUtf8Tail(probe)
        if (isStrictUtf8(probeTrimmed)) {
            // 抽样通过后再整体校验一次
            if (bytes.size <= 1024 * 1024 || isStrictUtf8(trimBrokenUtf8Tail(bytes.copyOfRange(0, 1024 * 1024)))) {
                return Charsets.UTF_8
            }
        }
        return if (canDecode(bytes, GB18030)) GB18030 else GBK
    }

    private fun looksLikeUtf16Le(bytes: ByteArray): Boolean {
        val n = minOf(bytes.size, 2048)
        if (n < 8) return false
        var zeroOdd = 0
        var zeroEven = 0
        var i = 0
        while (i + 1 < n) {
            if (bytes[i] == 0.toByte()) zeroEven++
            if (bytes[i + 1] == 0.toByte()) zeroOdd++
            i += 2
        }
        val pairs = n / 2
        return zeroOdd > pairs * 0.4 && zeroEven < pairs * 0.1
    }

    private fun isStrictUtf8(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return true
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(ByteBuffer.wrap(bytes))
            true
        } catch (e: CharacterCodingException) {
            false
        }
    }

    /** 截断到最后一个完整的 UTF-8 字符边界，避免抽样时切坏多字节字符 */
    private fun trimBrokenUtf8Tail(bytes: ByteArray): ByteArray {
        var end = bytes.size
        var back = 0
        while (end > 0 && back < 4) {
            val b = bytes[end - 1].toInt() and 0xFF
            if (b and 0xC0 == 0x80) { // 连续字节
                end--; back++
            } else if (b and 0x80 != 0) { // 起始字节
                end--; back++
                break
            } else {
                break
            }
        }
        return if (end == bytes.size) bytes else bytes.copyOfRange(0, end)
    }

    private fun canDecode(bytes: ByteArray, charset: Charset): Boolean {
        val decoder = charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(ByteBuffer.wrap(bytes))
            true
        } catch (e: Exception) {
            false
        }
    }

    /** 统一换行、去掉零宽字符与多余空行 */
    fun normalize(raw: String): String {
        var s = raw
        if (s.startsWith("\uFEFF")) s = s.substring(1)
        s = s.replace("\r\n", "\n").replace('\r', '\n')
        s = s.replace("\u00A0", " ")
        s = s.replace("\u200B", "").replace("\uFEFF", "")
        val sb = StringBuilder(s.length)
        var blank = 0
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\n') {
                blank++
                if (blank <= 2) sb.append('\n')
            } else {
                if (c != ' ' && c != '\t' && c != '\u3000') blank = 0
                sb.append(c)
            }
            i++
        }
        // 去掉行尾空格
        return sb.toString().lines().joinToString("\n") { it.trimEnd() }.trim()
    }
}
